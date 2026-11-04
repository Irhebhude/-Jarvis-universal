package dev.jarvis.universal.model

import android.content.Context
import android.net.Uri
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.wake.WakeEngineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Installs the offline Vosk wake model into app-private storage.
 *
 * Two paths, both owner-initiated:
 *  - download the official small English model from alphacephei.com (~40 MB),
 *  - import a .zip the owner already downloaded (no network use).
 *
 * The installer refuses zip entries that escape the target directory (zip-slip) and only reports
 * success after the model files are actually present.
 */
class ModelInstaller(private val context: Context) {

    data class Progress(val bytesRead: Long, val totalBytes: Long, val phase: String) {
        val percent: Int
            get() = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    val targetDir: File get() = WakeEngineFactory.voskModelDir(context.filesDir)

    fun isInstalled(): Boolean = WakeEngineFactory.voskModelInstalled(context.filesDir)

    suspend fun downloadAndInstall(
        url: String = DEFAULT_MODEL_URL,
        onProgress: (Progress) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onProgress(Progress(0, 0, "connecting"))
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Model download failed with HTTP ${response.code}")
                    )
                }
                val body = response.body
                    ?: return@withContext Result.failure(IllegalStateException("Empty download"))
                val total = body.contentLength()
                val tempZip = File(context.cacheDir, "vosk-model.zip")
                body.byteStream().use { input ->
                    FileOutputStream(tempZip).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var written = 0L
                        while (input.read(buffer).also { read = it } > 0) {
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(Progress(written, total, "downloading"))
                        }
                    }
                }
                val unzip = unzipInto(tempZip, onProgress)
                tempZip.delete()
                if (unzip.isFailure) return@withContext unzip
                if (!isInstalled()) {
                    return@withContext Result.failure(
                        IllegalStateException("Archive did not contain a Vosk model layout")
                    )
                }
                Logx.d(TAG, "wake model installed at ${targetDir.absolutePath}")
                Result.success(targetDir.absolutePath)
            }
        } catch (t: Throwable) {
            Logx.e(TAG, "model install failed", t)
            Result.failure(t)
        }
    }

    suspend fun importFromUri(uri: Uri, onProgress: (Progress) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val tempZip = File(context.cacheDir, "vosk-model-import.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempZip).use { output -> input.copyTo(output) }
                } ?: return@withContext Result.failure(IllegalStateException("Cannot read the selected file"))
                val unzip = unzipInto(tempZip, onProgress)
                tempZip.delete()
                if (unzip.isFailure) return@withContext unzip
                if (!isInstalled()) {
                    return@withContext Result.failure(
                        IllegalStateException("Archive did not contain a Vosk model layout")
                    )
                }
                Result.success(targetDir.absolutePath)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    private fun unzipInto(zip: File, onProgress: (Progress) -> Unit): Result<String> {
        val staging = File(context.filesDir, "vosk-model-staging")
        staging.deleteRecursively()
        if (!staging.mkdirs()) return Result.failure(IllegalStateException("Cannot create staging directory"))
        val canonicalRoot = staging.canonicalPath
        val total = zip.length()

        try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                var extracted = 0L
                while (entry != null) {
                    val name = entry.name
                    val destination = File(staging, name)
                    if (!destination.canonicalPath.startsWith(canonicalRoot)) {
                        throw SecurityException("Archive entry escapes target directory: $name")
                    }
                    if (entry.isDirectory) {
                        destination.mkdirs()
                    } else {
                        destination.parentFile?.mkdirs()
                        FileOutputStream(destination).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (zis.read(buffer).also { read = it } > 0) {
                                out.write(buffer, 0, read)
                                extracted += read
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                    onProgress(Progress(extracted.coerceAtMost(total), total, "extracting"))
                }
            }
        } catch (t: Throwable) {
            staging.deleteRecursively()
            return Result.failure(t)
        }

        val modelRoot = findModelRoot(staging)
            ?: run {
                staging.deleteRecursively()
                return Result.failure(IllegalStateException("No Vosk model folder found inside the archive"))
            }

        // Replace any previous model only after a complete, valid extraction.
        targetDir.deleteRecursively()
        targetDir.parentFile?.mkdirs()
        if (!modelRoot.renameTo(targetDir)) {
            modelRoot.copyRecursively(targetDir, overwrite = true)
        }
        staging.deleteRecursively()
        onProgress(Progress(total, total, "installed"))
        return Result.success(targetDir.absolutePath)
    }

    private fun findModelRoot(root: File): File? {
        if (File(root, "conf").isDirectory) return root
        File(root, "am").takeIf { it.isDirectory }?.let { return root }
        root.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                if (File(child, "conf").isDirectory || File(child, "am").isDirectory) return child
            }
        }
        return null
    }

    fun uninstall(): Boolean {
        val removed = targetDir.deleteRecursively()
        Logx.d(TAG, "wake model removed=$removed")
        return removed
    }

    /** Streams the model size for diagnostics without loading it. */
    fun installedSizeMb(): Long {
        if (!targetDir.exists()) return 0
        return targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
    }

    companion object {
        private const val TAG = "ModelInstaller"

        /** Official Apache-2.0 licensed small English model (about 40 MB). */
        const val DEFAULT_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }
}
