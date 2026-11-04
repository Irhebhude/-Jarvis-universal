package dev.jarvis.universal.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import dev.jarvis.universal.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Short, quiet acknowledgement tone played the instant a wake phrase is detected. */
class AckTone {
    private val handler = Handler(Looper.getMainLooper())

    fun play() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 40)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 110)
            handler.postDelayed({ tone.release() }, 350)
        } catch (t: Throwable) {
            Logx.w("AckTone", "ack tone unavailable: ${t.javaClass.simpleName}")
        }
    }
}

/**
 * Captures one spoken command with energy-based end-of-speech detection.
 *
 * The microphone is opened only for the duration of a command; it is released before speech
 * output starts so JARVIS never records its own voice and never holds two mic sessions.
 */
class CommandCapture {

    data class Capture(val pcm: ByteArray, val durationMs: Long, val voiced: Boolean) {
        val isEmpty: Boolean get() = pcm.isEmpty() || !voiced
    }

    @SuppressLint("MissingPermission") // caller verifies RECORD_AUDIO and handles SecurityException
    suspend fun capture(
        maxMs: Int,
        silenceMs: Int = 900,
        onLevel: (Float) -> Unit = {}
    ): Capture = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return@withContext Capture(ByteArray(0), 0, false)

        val frameSamples = 640 // 40 ms
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                maxOf(minBuffer * 3, frameSamples * 8)
            )
        } catch (t: Throwable) {
            Logx.e("Capture", "AudioRecord creation failed", t)
            return@withContext Capture(ByteArray(0), 0, false)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Logx.w("Capture", "AudioRecord not initialized")
            return@withContext Capture(ByteArray(0), 0, false)
        }

        val out = java.io.ByteArrayOutputStream()
        val shortBuf = ShortArray(frameSamples)
        val startedAt = System.currentTimeMillis()
        var voiced = false
        var silenceRun = 0L
        var frames = 0

        try {
            recorder.startRecording()
            while (true) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= maxMs) break
                val read = recorder.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) continue
                frames++

                var sum = 0.0
                for (i in 0 until read) {
                    val s = shortBuf[i].toInt()
                    sum += s.toDouble() * s.toDouble()
                    out.write(s and 0xFF)
                    out.write((s shr 8) and 0xFF)
                }
                val rms = sqrt(sum / read) / Short.MAX_VALUE
                onLevel(rms.toFloat())

                if (rms > VOICE_THRESHOLD) {
                    voiced = true
                    silenceRun = 0
                } else if (voiced) {
                    silenceRun += (read * 1000L) / SAMPLE_RATE
                }

                // Stop as soon as the speaker has clearly finished.
                if (voiced && silenceRun >= silenceMs) break
                // Hard stop for a speaker who never pauses.
                if (elapsed > 1000 && !voiced && frames > 40) {
                    // ~1.6 s of silence without any speech: treat as no command.
                    break
                }
            }
        } catch (t: Throwable) {
            Logx.e("Capture", "capture failed", t)
        } finally {
            try {
                recorder.stop()
            } catch (_: Throwable) {
            }
            recorder.release()
        }

        val duration = System.currentTimeMillis() - startedAt
        Logx.d("Capture", "captured ${out.size()} bytes in ${duration}ms voiced=$voiced")
        Capture(out.toByteArray(), duration, voiced)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val VOICE_THRESHOLD = 0.012
    }
}
