package dev.jarvis.universal.control

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.jarvis.universal.util.Logx

/**
 * Optional accessibility service used ONLY for global navigation actions
 * (back / home / recents / notification shade).
 *
 * Privacy boundary, by design:
 *  - the service config forbids retrieving window content,
 *  - [onAccessibilityEvent] deliberately discards every event; no screen text is read, stored,
 *    or transmitted,
 *  - there is no screenshot capability and no gesture injection.
 *
 * Enabling it is never required for the core voice features.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logx.d(TAG, "accessibility connected (navigation only, no content access)")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Intentionally empty: JARVIS must not observe what is on screen. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Performs one of the four whitelisted navigation actions. */
    fun performNavigation(action: String): Boolean {
        val globalAction = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return try {
            performGlobalAction(globalAction)
        } catch (t: Throwable) {
            Logx.w(TAG, "global action failed: ${t.javaClass.simpleName}")
            false
        }
    }

    companion object {
        private const val TAG = "Accessibility"

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set
    }
}
