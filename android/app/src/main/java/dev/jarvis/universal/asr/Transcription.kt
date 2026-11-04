package dev.jarvis.universal.asr

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.jarvis.universal.net.CoreClient
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Wraps Android's SpeechRecognizer. The platform may route audio to a remote service unless the
 * on-device model is installed, so this path is only used when the owner chooses it or when the
 * local core has no transcription model.
 */
class AndroidSpeechTranscriber(private val context: Context) {

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun listen(timeoutMs: Long = 12_000, preferOffline: Boolean = true): String? =
        withContext(Dispatchers.Main) {
            if (!isAvailable()) {
                Logx.w("Asr", "SpeechRecognizer not available on this device")
                return@withContext null
            }
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val finished = AtomicBoolean(false)
                var recognizer: SpeechRecognizer? = null

                fun finish(text: String?) {
                    if (!finished.compareAndSet(false, true)) return
                    handler.removeCallbacksAndMessages(null)
                    runCatching { recognizer?.destroy() }
                    recognizer = null
                    if (cont.isActive) cont.resume(text)
                }

                try {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
                        sr.setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {}
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(rmsdB: Float) {}
                            override fun onBufferReceived(buffer: ByteArray?) {}
                            override fun onEndOfSpeech() {}
                            override fun onPartialResults(partialResults: Bundle?) {}
                            override fun onEvent(eventType: Int, params: Bundle?) {}

                            override fun onError(error: Int) {
                                Logx.w("Asr", "recognition error code=$error")
                                finish(null)
                            }

                            override fun onResults(results: Bundle?) {
                                val text = results
                                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                                finish(text?.trim()?.ifBlank { null })
                            }
                        })
                    }
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                        }
                    }
                    recognizer?.startListening(intent)
                    handler.postDelayed({
                        Logx.w("Asr", "recognition timed out after ${timeoutMs}ms")
                        finish(null)
                    }, timeoutMs)
                    cont.invokeOnCancellation { finish(null) }
                } catch (t: Throwable) {
                    Logx.e("Asr", "recognizer failed to start", t)
                    finish(null)
                }
            }
        }
}

/** Where a transcript came from, so the UI can report it honestly. */
sealed class Transcript {
    data class Ok(val text: String, val source: String) : Transcript()
    data class Failed(val reason: String) : Transcript()
}

/**
 * Chooses how command audio becomes text:
 *  - CORE: audio already captured by the app is sent to the local core (fully offline);
 *  - ANDROID: the platform recognizer listens directly.
 * In CORE mode a 501 from the core (no local model) transparently falls back to the platform
 * recognizer, and the fallback is reported rather than hidden.
 */
class TranscriptionRouter(
    private val context: Context,
    private val core: CoreClient,
    private val prefs: Prefs
) {
    private val android = AndroidSpeechTranscriber(context)

    suspend fun fromCapturedAudio(pcm: ByteArray): Transcript {
        if (pcm.isEmpty()) return Transcript.Failed("no audio captured")
        val local = try {
            core.transcribe(pcm)
        } catch (t: Throwable) {
            Logx.w("Asr", "core transcription failed: ${t.message}")
            null
        }
        if (!local.isNullOrBlank()) return Transcript.Ok(local, "core-local")
        // Fall back to the platform recognizer; it needs its own microphone session.
        val platform = android.listen()
        return if (platform.isNullOrBlank()) {
            Transcript.Failed("both local and platform transcription failed")
        } else {
            Transcript.Ok(platform, "android-speech")
        }
    }

    suspend fun fromPlatform(): Transcript {
        val platform = android.listen()
        return if (platform.isNullOrBlank()) Transcript.Failed("platform recognizer returned nothing")
        else Transcript.Ok(platform, "android-speech")
    }

    fun platformAvailable(): Boolean = android.isAvailable()

    fun mode(): String = prefs.transcriptionMode
}
