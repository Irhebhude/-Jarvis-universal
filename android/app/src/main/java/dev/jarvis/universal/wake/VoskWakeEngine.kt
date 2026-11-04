package dev.jarvis.universal.wake

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import dev.jarvis.universal.util.Logx
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Offline wake-word detection using Vosk with a grammar constrained to the two wake phrases.
 *
 * Why Vosk: Apache-2.0, no account, no network, runs on mid-range ARM phones, and the grammar
 * restriction keeps CPU and RAM low while making both requested phrases first-class.
 *
 * Cost control: the acoustic model and recognizer are only alive while the engine runs. The
 * service stops the engine during command capture, speech output and idle timeout, so the
 * expensive recognition stack is not resident continuously.
 */
class VoskWakeEngine(
    private val modelDir: File,
    initialConfig: WakeConfig,
    private val callbacks: WakeEngine.Callbacks
) : WakeEngine {

    override val id: String = WakeEngineFactory.ID_VOSK

    private val running = AtomicBoolean(false)
    private val guard = Any()

    @Volatile
    private var config: WakeConfig = initialConfig

    private var worker: Thread? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var record: AudioRecord? = null

    @Volatile
    private var lastWakeAt = 0L

    override val isRunning: Boolean
        get() = running.get()

    override fun updateConfig(config: WakeConfig) {
        this.config = config
    }

    override fun start() {
        synchronized(guard) {
            if (running.get()) {
                Logx.d(TAG, "start() ignored: engine already running")
                return
            }
            val modelPresent = File(modelDir, "conf").isDirectory ||
                File(modelDir, "am").isDirectory || File(modelDir, "graph").exists()
            if (!modelPresent) {
                callbacks.onError("model_missing", "Wake model is not installed.")
                return
            }
            running.set(true)
            worker = Thread({ processLoop() }, "jarvis-wake").also {
                it.priority = Thread.NORM_PRIORITY + 1
                it.start()
            }
        }
    }

    override fun stop() {
        val thread: Thread?
        synchronized(guard) {
            if (!running.getAndSet(false)) return
            thread = worker
            worker = null
        }
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        try {
            thread?.join(700)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        releaseRecognizer()
        Logx.d(TAG, "engine stopped")
    }

    @SuppressLint("MissingPermission") // service verifies RECORD_AUDIO before starting the engine
    private fun processLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val bufferBytes = 1024 * 2 // 32 ms of 16 kHz mono PCM16
        val shortBuffer = ShortArray(bufferBytes / 2)
        val byteBuffer = ByteArray(bufferBytes)

        try {
            if (!loadRecognizer()) return

            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuffer <= 0) {
                callbacks.onError("mic_unsupported", "This device cannot open a 16 kHz mono capture stream.")
                return
            }
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                maxOf(minBuffer * 3, bufferBytes * 8)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                callbacks.onError("mic_unavailable", "Microphone is busy or unavailable (another app may be recording).")
                return
            }
            record = recorder
            recorder.startRecording()
            Logx.d(TAG, "listening (mode=${config.mode}, sensitivity=${config.sensitivity})")

            var frames = 0
            var quietFrames = 0
            while (running.get()) {
                val read = recorder.read(shortBuffer, 0, shortBuffer.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        callbacks.onError("mic_read_failed", "Microphone read failed (code $read).")
                        break
                    }
                    continue
                }
                frames++

                var sum = 0.0
                for (i in 0 until read) {
                    val s = shortBuffer[i].toInt()
                    sum += (s.toDouble() * s.toDouble())
                    byteBuffer[i * 2] = (s and 0xFF).toByte()
                    byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                val rms = sqrt(sum / read) / Short.MAX_VALUE
                callbacks.onLevel(rms.toFloat())
                if (rms < 0.006) quietFrames++ else quietFrames = 0

                val rec = recognizer ?: break
                val completed = rec.acceptWaveForm(byteBuffer, read * 2)
                if (completed) {
                    maybeWake(extractText(rec.result))
                } else if (frames % 4 == 0) {
                    // Partials give sub-second latency on "hey jarvis" without widening the
                    // bare-"jarvis" path, which stays result-only.
                    inspectPartial(extractText(rec.partialResult))
                }
            }
            Logx.d(TAG, "loop exited (frames=$frames, quietFrames=$quietFrames)")
        } catch (se: SecurityException) {
            callbacks.onError("mic_permission", "Microphone permission was revoked while listening.", se)
        } catch (ioe: java.io.IOException) {
            callbacks.onError("model_io", "Wake model could not be loaded from storage.", ioe)
        } catch (t: Throwable) {
            if (running.get()) {
                callbacks.onError("engine_failure", "Wake engine stopped unexpectedly.", t)
            }
        } finally {
            running.set(false)
            releaseRecognizer()
            try {
                record?.release()
            } catch (_: Throwable) {
            }
            record = null
        }
    }

    private fun loadRecognizer(): Boolean {
        return try {
            val dir = modelDir
            val loaded = Model(dir.absolutePath)
            val rec = Recognizer(loaded, SAMPLE_RATE.toFloat())
            rec.setGrammar(grammarJson(config.mode))
            model = loaded
            recognizer = rec
            Logx.d(TAG, "model loaded from ${dir.name}")
            true
        } catch (t: Throwable) {
            callbacks.onError(
                "model_load_failed",
                "Could not load the wake model. Reinstall it from Setup step 4.",
                t
            )
            false
        }
    }

    private fun releaseRecognizer() {
        try {
            recognizer?.close()
        } catch (_: Throwable) {
        }
        recognizer = null
        try {
            model?.close()
        } catch (_: Throwable) {
        }
        model = null
    }

    private fun grammarJson(mode: WakeMode): String {
        val phrases = JSONArray()
        phrases.put(WakePhraseMatcher.HEY_JARVIS)
        if (mode == WakeMode.DUAL) phrases.put(WakePhraseMatcher.JARVIS)
        phrases.put("[unk]")
        return phrases.toString()
    }

    private fun extractText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString("text", "")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun maybeWake(text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < config.cooldownMs) return
        val hit = WakePhraseMatcher.match(text, config.mode, config.sensitivity) ?: return
        lastWakeAt = now
        Logx.d(TAG, "wake hit phrase=${hit.phrase} conf=${"%.2f".format(hit.confidence)}")
        callbacks.onWake(hit)
    }

    private fun inspectPartial(text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < config.cooldownMs) return
        val hit = WakePhraseMatcher.match(text, WakeMode.HEY_ONLY, config.sensitivity) ?: return
        if (hit.phrase == WakePhraseMatcher.HEY_JARVIS && hit.confidence >= 0.90) {
            lastWakeAt = now
            Logx.d(TAG, "partial wake hit conf=${"%.2f".format(hit.confidence)}")
            callbacks.onWake(hit)
        }
    }

    companion object {
        private const val TAG = "WakeVosk"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val LONG_SILENCE_FRAMES = 1500 // ~48 s of near-silence at 32 ms frames
    }
}
