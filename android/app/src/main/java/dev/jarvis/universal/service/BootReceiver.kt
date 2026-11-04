package dev.jarvis.universal.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.jarvis.universal.JarvisApp
import dev.jarvis.universal.util.Logx

/**
 * Recovery after reboot.
 *
 * Android 14+ forbids starting a microphone foreground service from BOOT_COMPLETED, and asking
 * for the microphone without a visible user action would be poor privacy practice anyway. So the
 * receiver restores the *intent* to listen and posts a tap-to-resume notification. The wake
 * service therefore never claims to be listening after a reboot until the owner confirms.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = (context.applicationContext as? JarvisApp)?.prefs ?: return
        Logx.d(TAG, "boot/replace event: $action, wake enabled=${prefs.wakeServiceEnabled}")

        // A service that was already running before the package update may still be alive.
        if (WakeWordService.running) return
        if (prefs.micMuted) return
        if (!prefs.wakeServiceEnabled) return

        JarvisNotifications.ensureChannels(context)
        JarvisNotifications.resumePrompt(context)
        HealthWorker.schedule(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
