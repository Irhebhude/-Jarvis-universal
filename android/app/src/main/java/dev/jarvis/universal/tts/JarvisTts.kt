package dev.jarvis.universal.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.jarvis.universal.JarvisState
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.util.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.math.sin

/**
 * Female voice output.
 *
 * Voice choice, in order of preference:
 *  1. the voice the owner explicitly selected in Setup,
 *  2. an installed voice whose name/locale marks it as female,
 *  3. the default voice with a raised pitch as an honest fallback (reported as such).
 *
 * Speech is interruptible, queued, and reports completion so JARVIS never claims a task finished
 * while it is still speaking. The mouth animation is driven by word-range callbacks plus a
 * synthesized envelope — it is a visualisation, not an audio analysis, and is labelled as such.
 */
class JarvisTts(context: Context, private val prefs: Prefs) {

    data class VoiceInfo(val name: String, val locale: String, val femaleGuess: Boolean)

    private val appContext = context.applicationContext
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)
    private var tts: TextToSpeech? = null
    private var ready = false
    private var envelopeJob: Job? = null

    @Volatile
    var voiceLabel: String = "uninitialised"
        private set

    @Volatile
    var usingFallbackPitch: Boolean = false
        private set

    private val initSignal = CompletableDeferred<Boolean>()

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configure()
                ready = true
                if (!initSignal.isCompleted) initSignal.complete(true)
            } else {
                voiceLabel = "TTS engine unavailable (status $status)"
                Logx.w("Tts", voiceLabel)
                if (!initSignal.isCompleted) initSignal.complete(false)
            }
        }
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                JarvisState.setPhase(JarvisState.Phase.SPEAKING)
                startEnvelope()
            }

            override fun onDone(utteranceId: String?) {
                stopEnvelope()
                pending.remove(utteranceId)?.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                stopEnvelope()
                pending.remove(utteranceId)?.complete(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Logx.w("Tts", "utterance error $errorCode")
                stopEnvelope()
                pending.remove(utteranceId)?.complete(false)
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                // Pulse the avatar mouth when a word starts.
                JarvisState.setAmplitude(0.55f + ((end - start).coerceAtMost(12) / 24f))
            }
        })
    }

    suspend fun awaitReady(timeoutMs: Long = 4000): Boolean =
        withTimeoutOrNull(timeoutMs) { initSignal.await() } ?: false

    private fun configure() {
        val engine = tts ?: return
        val localeResult = engine.setLanguage(Locale.US)
        if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
        val voices = try {
            engine.voices?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        val chosen = prefs.ttsVoiceName?.let { name -> voices.firstOrNull { it.name == name } }
            ?: pickFemaleVoice(voices)

        if (chosen != null) {
            val ok = try {
                engine.voice = chosen
                true
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                engine.setPitch(1.0f)
                engine.setSpeechRate(1.0f)
                usingFallbackPitch = false
                voiceLabel = "${chosen.name} (${chosen.locale.toLanguageTag()})"
                Logx.d("Tts", "voice=$voiceLabel femaleGuess=${isFemaleVoice(chosen)}")
                return
            }
        }
        // No explicit female voice installed: raise pitch so the default voice reads feminine,
        // and report the fallback rather than claiming a female voice was selected.
        engine.setPitch(1.14f)
        engine.setSpeechRate(1.0f)
        usingFallbackPitch = true
        voiceLabel = "default voice, pitched up (no female voice installed)"
        Logx.w("Tts", voiceLabel)
    }

    fun availableVoices(): List<VoiceInfo> {
        val engine = tts ?: return emptyList()
        return try {
            engine.voices.orEmpty()
                .filter { it.locale.language == "en" }
                .map { VoiceInfo(it.name, it.locale.toLanguageTag(), isFemaleVoice(it)) }
                .sortedByDescending { it.femaleGuess }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun selectVoice(name: String?): Boolean {
        val engine = tts ?: return false
        if (name == null) {
            prefs.ttsVoiceName = null
            configure()
            return true
        }
        val voice = try {
            engine.voices?.firstOrNull { it.name == name }
        } catch (_: Throwable) {
            null
        } ?: return false
        return try {
            engine.voice = voice
            engine.setPitch(1.0f)
            usingFallbackPitch = false
            prefs.ttsVoiceName = name
            voiceLabel = "${voice.name} (${voice.locale.toLanguageTag()})"
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isFemaleVoice(voice: Voice): Boolean {
        val n = voice.name.lowercase(Locale.US)
        val tag = voice.locale.toLanguageTag().lowercase(Locale.US)
        return FEMALE_MARKERS.any { n.contains(it) || tag.contains(it) }
    }

    private fun pickFemaleVoice(voices: List<Voice>): Voice? {
        if (voices.isEmpty()) return null
        val english = voices.filter { it.locale.language == "en" }
        val pool = if (english.isNotEmpty()) english else voices
        // Prefer higher quality voices when the network is not required.
        return pool
            .filter { isFemaleVoice(it) && !it.isNetworkConnectionRequired }
            .maxByOrNull { it.quality }
            ?: pool.filter { isFemaleVoice(it) }.maxByOrNull { it.quality }
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Speaks and suspends until the utterance finishes (or the timeout elapses). */
    suspend fun speakAndWait(text: String, flush: Boolean = true, timeoutMs: Long = 30000): Boolean {
        if (text.isBlank()) return true
        if (!ready) init()
        val engine = tts ?: return false
        if (!awaitReady()) {
            Logx.w("Tts", "speak skipped: engine not ready")
            return false
        }
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = engine.speak(text, mode, Bundle(), id)
        if (result != TextToSpeech.SUCCESS) {
            pending.remove(id)
            Logx.w("Tts", "speak() returned $result")
            JarvisState.setPhase(JarvisState.Phase.IDLE)
            return false
        }
        val ok = withTimeoutOrNull(timeoutMs) { done.await() } ?: run {
            Logx.w("Tts", "utterance timed out")
            engine.stop()
            false
        }
        pending.remove(id)
        JarvisState.setPhase(JarvisState.Phase.IDLE)
        JarvisState.setAmplitude(0f)
        return ok
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
        stopEnvelope()
        JarvisState.setPhase(JarvisState.Phase.IDLE)
    }

    fun shutdown() {
        stopEnvelope()
        scopeJob.cancel()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        ready = false
    }

    private fun startEnvelope() {
        stopEnvelope()
        envelopeJob = scope.launch {
            var phase = 0.0
            while (true) {
                val base = JarvisState.amplitude.value
                val wobble = (sin(phase) * 0.12 + sin(phase * 2.7) * 0.06).toFloat()
                JarvisState.setAmplitude((base * 0.86f + wobble).coerceIn(0.05f, 1f))
                phase += 0.55
                delay(90)
            }
        }
    }

    private fun stopEnvelope() {
        envelopeJob?.cancel()
        envelopeJob = null
        JarvisState.setAmplitude(0f)
    }

    companion object {
        private val FEMALE_MARKERS = listOf(
            "female", "samantha", "victoria", "karen", "moira", "tessa", "fiona", "serena",
            "ava", "allison", "susan", "zoe", "kate", "amy", "emma", "joanna", "salli",
            "nicole", "olivia", "-f-", "sfg", "gbg", "iob", "en-us-x-iom"
        )
    }
}
