package dev.jarvis.universal.util

import android.content.Context
import android.content.SharedPreferences
import dev.jarvis.universal.BuildConfig
import dev.jarvis.universal.wake.WakeMode

/**
 * All user-tunable JARVIS settings. Deliberately tiny and synchronous: the wake-word service
 * reads these on every cycle, so they must be cheap.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    var wakeServiceEnabled: Boolean
        get() = sp.getBoolean(KEY_WAKE_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_WAKE_ENABLED, value).apply()

    var micMuted: Boolean
        get() = sp.getBoolean(KEY_MUTED, false)
        set(value) = sp.edit().putBoolean(KEY_MUTED, value).apply()

    var wakeMode: WakeMode
        get() = WakeMode.parse(sp.getString(KEY_WAKE_MODE, WakeMode.HEY_ONLY.name))
        set(value) = sp.edit().putString(KEY_WAKE_MODE, value.name).apply()

    /** 0 = strictest (fewest false activations), 100 = most sensitive. */
    var sensitivity: Int
        get() = sp.getInt(KEY_SENSITIVITY, 60).coerceIn(0, 100)
        set(value) = sp.edit().putInt(KEY_SENSITIVITY, value.coerceIn(0, 100)).apply()

    var engineId: String
        get() = sp.getString(KEY_ENGINE, ENGINE_VOSK) ?: ENGINE_VOSK
        set(value) = sp.edit().putString(KEY_ENGINE, value).apply()

    var transcriptionMode: String
        get() = sp.getString(KEY_TRANSCRIPTION, TRANSCRIPTION_CORE) ?: TRANSCRIPTION_CORE
        set(value) = sp.edit().putString(KEY_TRANSCRIPTION, value).apply()

    var corePort: Int
        get() = sp.getInt(KEY_CORE_PORT, BuildConfig.DEFAULT_CORE_PORT)
        set(value) = sp.edit().putInt(KEY_CORE_PORT, value).apply()

    var coreHost: String
        get() = sp.getString(KEY_CORE_HOST, "127.0.0.1") ?: "127.0.0.1"
        set(value) = sp.edit().putString(KEY_CORE_HOST, value).apply()

    var ttsVoiceName: String?
        get() = sp.getString(KEY_TTS_VOICE, null)
        set(value) = sp.edit().putString(KEY_TTS_VOICE, value).apply()

    var listeningTimeoutMs: Int
        get() = sp.getInt(KEY_LISTEN_TIMEOUT, 8000)
        set(value) = sp.edit().putInt(KEY_LISTEN_TIMEOUT, value.coerceIn(3000, 30000)).apply()

    var commandTimeoutMs: Int
        get() = sp.getInt(KEY_COMMAND_TIMEOUT, 25000)
        set(value) = sp.edit().putInt(KEY_COMMAND_TIMEOUT, value.coerceIn(5000, 120000)).apply()

    var batterySaver: Boolean
        get() = sp.getBoolean(KEY_BATTERY_SAVER, true)
        set(value) = sp.edit().putBoolean(KEY_BATTERY_SAVER, value).apply()

    var announceNotifications: Boolean
        get() = sp.getBoolean(KEY_ANNOUNCE_NOTIF, false)
        set(value) = sp.edit().putBoolean(KEY_ANNOUNCE_NOTIF, value).apply()

    var notificationExclusions: Set<String>
        get() = sp.getStringSet(KEY_NOTIF_EXCLUDED, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_NOTIF_EXCLUDED, value).apply()

    var safTreeUri: String?
        get() = sp.getString(KEY_SAF_TREE, null)
        set(value) = sp.edit().putString(KEY_SAF_TREE, value).apply()

    var setupComplete: Boolean
        get() = sp.getBoolean(KEY_SETUP_DONE, false)
        set(value) = sp.edit().putBoolean(KEY_SETUP_DONE, value).apply()

    var wakeTestPending: Boolean
        get() = sp.getBoolean(KEY_WAKE_TEST_PENDING, false)
        set(value) = sp.edit().putBoolean(KEY_WAKE_TEST_PENDING, value).apply()

    /** Non-sensitive transcript of the last exchange, shown in the holographic interface. */
    var lastTask: String
        get() = sp.getString(KEY_LAST_TASK, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_TASK, value.take(280)).apply()

    companion object {
        const val ENGINE_VOSK = "vosk"
        const val ENGINE_PORCUPINE = "porcupine"
        const val TRANSCRIPTION_CORE = "core"
        const val TRANSCRIPTION_ANDROID = "android"

        private const val KEY_WAKE_ENABLED = "wake_enabled"
        private const val KEY_MUTED = "mic_muted"
        private const val KEY_WAKE_MODE = "wake_mode"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_ENGINE = "engine"
        private const val KEY_TRANSCRIPTION = "transcription_mode"
        private const val KEY_CORE_PORT = "core_port"
        private const val KEY_CORE_HOST = "core_host"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_LISTEN_TIMEOUT = "listen_timeout"
        private const val KEY_COMMAND_TIMEOUT = "command_timeout"
        private const val KEY_BATTERY_SAVER = "battery_saver"
        private const val KEY_ANNOUNCE_NOTIF = "announce_notifications"
        private const val KEY_NOTIF_EXCLUDED = "notification_exclusions"
        private const val KEY_SAF_TREE = "saf_tree_uri"
        private const val KEY_SETUP_DONE = "setup_complete"
        private const val KEY_WAKE_TEST_PENDING = "wake_test_pending"
        private const val KEY_LAST_TASK = "last_task"
    }
}
