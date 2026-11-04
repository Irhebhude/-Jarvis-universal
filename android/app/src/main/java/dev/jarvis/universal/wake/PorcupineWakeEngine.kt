package dev.jarvis.universal.wake

import android.content.Context
import dev.jarvis.universal.util.Logx
import java.lang.reflect.Proxy
import java.io.File

/**
 * Optional Picovoice Porcupine wake engine.
 *
 * Porcupine is loaded reflectively so the project builds and runs without the optional SDK
 * artifact. Consequence: Porcupine only works if you add
 * `implementation("ai.picovoice:porcupine-android:3.0.2")` to app/build.gradle.kts and supply a
 * Picovoice access key (a free-tier account is required).
 *
 * Keyword coverage:
 *  - "jarvis" is a Porcupine built-in keyword and works without any custom file.
 *  - "Hey JARVIS" is NOT built-in. It requires a custom `.ppn` keyword file trained in the
 *    Picovoice Console. Without that file the engine refuses to start in HEY_ONLY mode instead
 *    of silently pretending to listen for it.
 *
 * This is why Vosk is the default engine: it needs no account and covers both phrases.
 */
class PorcupineWakeEngine(
    private val context: Context,
    private val accessKey: String,
    initialConfig: WakeConfig,
    private val callbacks: WakeEngine.Callbacks,
    /** Absolute paths to custom .ppn files, index 0 = "hey jarvis", index 1 = "jarvis". */
    private val customKeywordPaths: List<String> = emptyList()
) : WakeEngine {

    override val id: String = WakeEngineFactory.ID_PORCUPINE

    @Volatile
    private var config: WakeConfig = initialConfig

    @Volatile
    private var manager: Any? = null

    @Volatile
    private var activePhrases: List<String> = emptyList()

    override val isRunning: Boolean
        get() = manager != null

    override fun updateConfig(config: WakeConfig) {
        this.config = config
        // Porcupine sensitivities are fixed at build time; apply on next start.
    }

    override fun start() {
        if (manager != null) return
        val hasHeyKeyword = customKeywordPaths.isNotEmpty() && File(customKeywordPaths.first()).isFile
        if (config.mode == WakeMode.HEY_ONLY && !hasHeyKeyword) {
            callbacks.onError(
                "porcupine_keyword_missing",
                "Porcupine has no built-in \u201cHey JARVIS\u201d keyword. Add a custom .ppn or switch the wake mode to include \u201cJARVIS\u201d."
            )
            return
        }
        try {
            val phrases = mutableListOf<String>()
            val keywords = mutableListOf<String>()
            val paths = mutableListOf<String>()
            val sensitivities = mutableListOf<Float>()

            if (hasHeyKeyword && customKeywordPaths.size >= 2 && File(customKeywordPaths[1]).isFile) {
                paths.add(customKeywordPaths[0])
                phrases.add(WakePhraseMatcher.HEY_JARVIS)
                sensitivities.add(config.sensitivity / 100f)
                paths.add(customKeywordPaths[1])
                phrases.add(WakePhraseMatcher.JARVIS)
                sensitivities.add(config.sensitivity / 100f)
            } else {
                keywords.add("jarvis")
                phrases.add(WakePhraseMatcher.JARVIS)
                sensitivities.add(config.sensitivity / 100f)
            }

            val builderClass = Class.forName("ai.picovoice.porcupine.PorcupineManager\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()

            builderClass.getMethod("setAccessKey", String::class.java).invoke(builder, accessKey)
            if (paths.isEmpty()) {
                builderClass.getMethod("setKeywords", Array<String>::class.java)
                    .invoke(builder, keywords.toTypedArray())
            } else {
                builderClass.getMethod("setKeywordPaths", Array<String>::class.java)
                    .invoke(builder, paths.toTypedArray())
            }
            builderClass.getMethod("setSensitivities", FloatArray::class.java)
                .invoke(builder, sensitivities.toFloatArray())

            val callbackClass = Class.forName("ai.picovoice.porcupine.PorcupineManagerCallback")
            val idxPhrases = phrases.toList()
            val proxy = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                if (method.name == "invoke" && args != null && args.isNotEmpty()) {
                    val index = (args[0] as? Int) ?: 0
                    val phrase = idxPhrases.getOrNull(index) ?: WakePhraseMatcher.JARVIS
                    Logx.d(TAG, "porcupine keyword $index -> $phrase")
                    callbacks.onWake(WakeHit(phrase, phrase, 0.98))
                }
                null
            }

            builderClass
                .getMethod("setKeywordDetectionCallback", callbackClass)
                .invoke(builder, proxy)

            val built = builderClass.getMethod("build", Context::class.java).invoke(builder, context)
            built.javaClass.getMethod("start").invoke(built)
            manager = built
            activePhrases = phrases
            Logx.d(TAG, "porcupine started for $phrases")
        } catch (cnf: ClassNotFoundException) {
            callbacks.onError(
                "porcupine_missing",
                "Porcupine SDK is not bundled. Add the optional dependency and rebuild.",
                cnf
            )
        } catch (t: Throwable) {
            callbacks.onError(
                "porcupine_start_failed",
                "Porcupine could not start: ${t.javaClass.simpleName}",
                t
            )
            releaseQuietly()
        }
    }

    override fun stop() {
        releaseQuietly()
        Logx.d(TAG, "porcupine stopped")
    }

    private fun releaseQuietly() {
        val current = manager
        manager = null
        activePhrases = emptyList()
        if (current == null) return
        try {
            current.javaClass.getMethod("stop").invoke(current)
        } catch (_: Throwable) {
        }
        try {
            current.javaClass.getMethod("delete").invoke(current)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "WakePorcupine"

        fun availability(accessKey: String?): EngineAvailability {
            val present = try {
                Class.forName("ai.picovoice.porcupine.PorcupineManager")
                true
            } catch (_: Throwable) {
                false
            }
            return when {
                !present -> EngineAvailability(
                    WakeEngineFactory.ID_PORCUPINE,
                    false,
                    "Porcupine SDK not bundled in this build. Vosk is the working offline engine."
                )
                accessKey.isNullOrBlank() -> EngineAvailability(
                    WakeEngineFactory.ID_PORCUPINE,
                    false,
                    "Add a Picovoice access key in Setup. \u201cHey JARVIS\u201d also needs a custom keyword file."
                )
                else -> EngineAvailability(
                    WakeEngineFactory.ID_PORCUPINE,
                    true,
                    "Access key present. \u201cJARVIS\u201d is built in; \u201cHey JARVIS\u201d needs a custom .ppn keyword."
                )
            }
        }
    }
}
