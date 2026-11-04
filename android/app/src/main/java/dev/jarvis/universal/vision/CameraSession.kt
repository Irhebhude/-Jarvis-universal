package dev.jarvis.universal.vision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.jarvis.universal.util.Logx
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Owns the CameraX binding for one lens at a time.
 *
 * Everything is released on [close]: the provider is unbound, the executor shut down, and the
 * lifecycle moved to DESTROYED. That is what keeps a crashed or interrupted session from leaving
 * the camera on.
 */
class CameraSession(
    private val context: Context,
    private val onError: (String) -> Unit
) : LifecycleOwner {

    private val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-vision").apply { isDaemon = true }
    }

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var capture: ImageCapture? = null
    private var analyzer: VisionAnalyzer? = null

    @Volatile
    var lens: Lens? = null
        private set

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun open(
        target: Lens,
        cadence: VisionCadence,
        onSnapshot: (SceneSnapshot) -> Unit
    ): Boolean {
        if (!hasPermission()) {
            onError("camera_permission_required")
            return false
        }
        return try {
            val cameraProvider = awaitProvider()
            val selector = CameraSelector.Builder()
                .requireLensFacing(
                    if (target == Lens.FRONT) CameraSelector.LENS_FACING_FRONT
                    else CameraSelector.LENS_FACING_BACK
                )
                .build()
            if (!cameraProvider.hasCamera(selector)) {
                onError("camera_missing_${target.wire}")
                return false
            }

            releaseBinding()

            val analysisUseCase = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val visionAnalyzer = VisionAnalyzer(target, cadence) { snapshot ->
                VisionRepository.onSnapshot(snapshot)
                onSnapshot(snapshot)
            }
            analysisUseCase.setAnalyzer(executor, visionAnalyzer)

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, selector, analysisUseCase, imageCapture)

            analysis = analysisUseCase
            capture = imageCapture
            analyzer = visionAnalyzer
            provider = cameraProvider
            lens = target
            if (registry.currentState == Lifecycle.State.INITIALIZED) {
                registry.currentState = Lifecycle.State.RESUMED
            } else {
                registry.currentState = Lifecycle.State.RESUMED
            }
            VisionRepository.onSessionStarted(target)
            true
        } catch (t: Throwable) {
            Logx.e("CameraSession", "failed to open ${target.wire} camera", t)
            onError("camera_open_failed:${t.javaClass.simpleName}")
            releaseBinding()
            false
        }
    }

    /**
     * Grabs one frame and re-encodes it as JPEG for a single visual-question request.
     * The frame is never written to disk.
     */
    suspend fun captureJpeg(maxWidth: Int = 1024, quality: Int = 72): ByteArray? {
        val useCase = capture ?: return null
        val proxy = suspendCancellableCoroutine<ImageProxy?> { continuation ->
            useCase.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    if (continuation.isActive) continuation.resume(image) else image.close()
                }

                override fun onError(exception: ImageCaptureException) {
                    Logx.w("CameraSession", "still capture failed: ${exception.imageCaptureError}")
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        } ?: return null

        return try {
            proxy.toJpeg(maxWidth, quality)
        } catch (t: Throwable) {
            Logx.w("CameraSession", "jpeg encode failed: ${t.javaClass.simpleName}")
            null
        } finally {
            proxy.close()
        }
    }

    fun close() {
        releaseBinding()
        registry.currentState = Lifecycle.State.DESTROYED
        VisionRepository.onSessionStopped()
    }

    private fun releaseBinding() {
        analyzer?.let { runCatching { it.close() } }
        analyzer = null
        analysis?.clearAnalyzer()
        analysis = null
        capture = null
        runCatching { provider?.unbindAll() }
        lens = null
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (t: Throwable) {
                        continuation.cancel(t)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }
}

/** YUV_420_888 → rotated, downscaled JPEG. */
internal fun ImageProxy.toJpeg(maxWidth: Int, quality: Int): ByteArray? {
    val image = image ?: return null
    val width = width
    val height = height
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val nv21 = ByteArray(width * height * 3 / 2)
    var position = 0

    val yBuffer = yPlane.buffer
    yBuffer.rewind()
    for (row in 0 until height) {
        var index = row * yPlane.rowStride
        for (col in 0 until width) {
            nv21[position++] = yBuffer.get(index)
            index += yPlane.pixelStride
        }
    }
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    uBuffer.rewind()
    vBuffer.rewind()
    for (row in 0 until height / 2) {
        var index = row * uPlane.rowStride
        for (col in 0 until width / 2) {
            nv21[position++] = vBuffer.get(index)
            nv21[position++] = uBuffer.get(index)
            index += uPlane.pixelStride
        }
    }

    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val raw = ByteArrayOutputStream()
    if (!yuv.compressToJpeg(Rect(0, 0, width, height), quality, raw)) return null

    val decoded = BitmapFactory.decodeByteArray(raw.toByteArray(), 0, raw.size()) ?: return null
    val rotation = imageInfo.rotationDegrees
    val scale = if (decoded.width > maxWidth) maxWidth.toFloat() / decoded.width else 1f
    val matrix = Matrix().apply {
        postRotate(rotation.toFloat())
        if (scale < 1f) postScale(scale, scale)
    }
    val output = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    val jpeg = ByteArrayOutputStream()
    output.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)
    if (output !== decoded) output.recycle()
    decoded.recycle()
    return jpeg.toByteArray()
}
