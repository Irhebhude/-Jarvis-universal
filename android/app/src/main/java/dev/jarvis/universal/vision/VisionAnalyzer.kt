package dev.jarvis.universal.vision

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.jarvis.universal.util.Logx
import java.util.concurrent.atomic.AtomicInteger

/**
 * One analysis pass per admitted frame, entirely on-device.
 *
 * Frames are never queued or stored: the ImageProxy is closed as soon as every detector for that
 * frame has completed. Face and object detection run on each admitted frame; OCR and barcode
 * scanning run on a slower cadence because they are the expensive detectors.
 */
class VisionAnalyzer(
    private val lens: Lens,
    private val cadence: VisionCadence,
    private val onSnapshot: (SceneSnapshot) -> Unit
) : ImageAnalysis.Analyzer {

    private val faceDetector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.1f)
            .build()
    )

    private val objectDetector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
    )

    private val textRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient()

    @Volatile
    private var cachedText: String = ""

    @Volatile
    private var cachedCodes: List<CodeObservation> = emptyList()

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (!cadence.admit(now)) {
            image.close()
            return
        }

        val media = image.image
        if (media == null) {
            image.close()
            return
        }

        val rotation = image.imageInfo.rotationDegrees
        val input = try {
            InputImage.fromMediaImage(media, rotation)
        } catch (t: Throwable) {
            Logx.w(TAG, "frame could not be wrapped: ${t.javaClass.simpleName}")
            image.close()
            return
        }

        val runText = cadence.runText()
        val remaining = AtomicInteger(if (runText) 4 else 2)
        var faces: List<FaceObservation> = emptyList()
        var objects: List<ObjectObservation> = emptyList()
        var textResult: String? = null
        var codeResult: List<CodeObservation>? = null

        fun finishIfReady() {
            if (remaining.decrementAndGet() != 0) return

            textResult?.let { cachedText = it }
            codeResult?.let { cachedCodes = it }

            val snapshot = SceneSnapshot(
                lens = lens,
                faces = faces,
                objects = objects,
                text = cachedText,
                codes = cachedCodes,
                frameWidth = image.width,
                frameHeight = image.height,
                capturedAtMs = System.currentTimeMillis(),
                analyzedFrames = cadence.analyzed,
                droppedFrames = cadence.dropped
            )
            image.close()
            onSnapshot(snapshot)
        }

        faceDetector.process(input)
            .addOnSuccessListener { detected -> faces = detected.map(::toFaceObservation) }
            .addOnFailureListener { e -> Logx.w(TAG, "face detection failed: ${e.javaClass.simpleName}") }
            .addOnCompleteListener { finishIfReady() }

        objectDetector.process(input)
            .addOnSuccessListener { detected -> objects = detected.map(::toObjectObservation) }
            .addOnFailureListener { e -> Logx.w(TAG, "object detection failed: ${e.javaClass.simpleName}") }
            .addOnCompleteListener { finishIfReady() }

        if (!runText) return

        textRecognizer.process(input)
            .addOnSuccessListener { result -> textResult = extractText(result) }
            .addOnFailureListener { e -> Logx.w(TAG, "text recognition failed: ${e.javaClass.simpleName}") }
            .addOnCompleteListener { finishIfReady() }

        barcodeScanner.process(input)
            .addOnSuccessListener { codes ->
                codeResult = codes.mapNotNull { code ->
                    val value = code.rawValue ?: return@mapNotNull null
                    CodeObservation(value = value, format = formatName(code.format))
                }
            }
            .addOnFailureListener { e -> Logx.w(TAG, "barcode scan failed: ${e.javaClass.simpleName}") }
            .addOnCompleteListener { finishIfReady() }
    }

    fun close() {
        runCatching { faceDetector.close() }
        runCatching { objectDetector.close() }
        runCatching { textRecognizer.close() }
        runCatching { barcodeScanner.close() }
    }

    private fun toFaceObservation(face: Face): FaceObservation {
        val box = face.boundingBox
        return FaceObservation(
            trackingId = face.trackingId,
            box = Box(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()),
            yaw = face.headEulerAngleY,
            pitch = face.headEulerAngleX,
            roll = face.headEulerAngleZ,
            smilingProbability = face.smilingProbability,
            leftEyeOpenProbability = face.leftEyeOpenProbability,
            rightEyeOpenProbability = face.rightEyeOpenProbability
        )
    }

    private fun toObjectObservation(detected: DetectedObject): ObjectObservation {
        val best = detected.labels.maxByOrNull { it.confidence }
        val box = detected.boundingBox
        return ObjectObservation(
            label = best?.text ?: "object",
            confidence = best?.confidence ?: 0f,
            box = Box(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()),
            trackingId = detected.trackingId
        )
    }

    private fun extractText(result: Text): String =
        SceneAnalysis.trimText(result.text, 600)

    private fun formatName(format: Int): String = when (format) {
        1 -> "code-128"
        2 -> "code-39"
        3 -> "code-93"
        4 -> "codabar"
        5 -> "data-matrix"
        6 -> "ean-13"
        7 -> "ean-8"
        8 -> "itf"
        9 -> "qr"
        10 -> "upc-a"
        11 -> "upc-e"
        12 -> "pdf417"
        13 -> "aztec"
        else -> "barcode"
    }

    companion object {
        private const val TAG = "VisionAnalyzer"
    }
}
