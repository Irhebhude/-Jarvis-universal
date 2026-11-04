package dev.jarvis.universal.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.jarvis.universal.JarvisApp
import dev.jarvis.universal.util.Logx

/**
 * Receives the notification actions and voice-triggered control intents (mute, unmute, stop,
 * start). All of them target the already-running foreground service, so no background-start
 * exemption is abused.
 */
class ServiceControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = (context.applicationContext as? JarvisApp)?.prefs
        Logx.d("ServiceControl", "received $action")
        when (action) {
            ACTION_START -> {
                prefs?.wakeServiceEnabled = true
                if (!WakeWordService.start(context)) {
                    JarvisNotifications.alert(
                        context,
                        "JARVIS could not start listening",
                        "Open the app and start listening from the interface so Android allows the microphone service."
                    )
                }
            }

            ACTION_STOP -> {
                prefs?.wakeServiceEnabled = false
                prefs?.micMuted = false
                WakeWordService.sendAction(context, WakeWordService.ACTION_STOP)
            }

            ACTION_MUTE -> {
                prefs?.micMuted = true
                WakeWordService.sendAction(context, WakeWordService.ACTION_MUTE)
            }

            ACTION_UNMUTE -> {
                prefs?.micMuted = false
                WakeWordService.sendAction(context, WakeWordService.ACTION_UNMUTE)
            }
        }
    }

    companion object {
        const val ACTION_START = "dev.jarvis.universal.action.START_LISTENING"
        const val ACTION_STOP = "dev.jarvis.universal.action.STOP"
        const val ACTION_MUTE = "dev.jarvis.universal.action.MUTE"
        const val ACTION_UNMUTE = "dev.jarvis.universal.action.UNMUTE"
    }
}
