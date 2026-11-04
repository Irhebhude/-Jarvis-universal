package dev.jarvis.universal.wake

import java.io.File

/** Tunables for a wake engine. Read fresh from [dev.jarvis.universal.util.Prefs] on each start. */
data class WakeConfig(
    val mode: WakeMode = WakeMode.HEY_ONLY,
    val sensitivity: Int = 60,
    /** Minimum gap between two activations, protects against double-trigger from one utterance. */
    val cooldownMs: Long = 2500L
)

/**
 * A local wake-word engine. Implementations must:
 *  - process audio entirely on-device,
 *  - never hold two microphone sessions at once,
 *  - report failures instead of silently dying.
 */
interface WakeEngine {
    val id: String
    val isRunning: Boolean

    fun start()
    fun stop()
    fun updateConfig(config: WakeConfig)

    interface Callbacks {
        fun onWake(hit: WakeHit)
        fun onError(code: String, message: String, cause: Throwable? = null)
        fun onLevel(level: Float) {}
    }
}

/** Availability probe used by setup UI and the factory. */
data class EngineAvailability(
    val engineId: String,
    val available: Boolean,
    val detail: String
)

object WakeEngineFactory {

    const val ID_VOSK = "vosk"
    const val ID_PORCUPINE = "porcupine"

    fun voskModelDir(filesDir: File) = File(filesDir, "vosk-model")

    fun voskModelInstalled(filesDir: File): Boolean {
        val dir = voskModelDir(filesDir)
        return File(dir, "conf").isDirectory || File(dir, "am").isDirectory || File(dir, "graph").exists()
    }

    fun describeVosk(filesDir: File): EngineAvailability =
        if (voskModelInstalled(filesDir)) {
            EngineAvailability(ID_VOSK, true, "Offline model installed \u2014 detects both wake phrases locally.")
        } else {
            EngineAvailability(ID_VOSK, false, "Offline model not installed. Install it from Setup step 4.")
        }

    fun describePorcupine(accessKey: String?): EngineAvailability =
        PorcupineWakeEngine.availability(accessKey)
}
