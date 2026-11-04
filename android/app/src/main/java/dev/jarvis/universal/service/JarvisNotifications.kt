package dev.jarvis.universal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.jarvis.universal.R
import dev.jarvis.universal.ui.MainActivity

/**
 * All JARVIS notifications in one place.
 *
 * The listening notification is mandatory for a microphone foreground service: Android shows it
 * whenever the microphone is in use. It is deliberately quiet, always visible, and carries the
 * mute and stop controls.
 */
object JarvisNotifications {

    const val CHANNEL_LISTENING = "jarvis_listening"
    const val CHANNEL_ALERTS = "jarvis_alerts"
    const val LISTENING_NOTIFICATION_ID = 4101
    const val ALERT_NOTIFICATION_ID = 4102
    const val RESUME_NOTIFICATION_ID = 4103

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val listening = NotificationChannel(
            CHANNEL_LISTENING,
            context.getString(R.string.channel_listening_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_listening_desc)
            setShowBadge(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_alerts_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(listening)
        manager.createNotificationChannel(alerts)
    }

    fun listening(
        context: Context,
        muted: Boolean,
        wakeEnabled: Boolean,
        coreOnline: Boolean?,
        detail: String?
    ): Notification {
        val status = when {
            muted -> context.getString(R.string.notif_listening_muted)
            !wakeEnabled -> "Wake word is off"
            detail != null -> detail
            else -> context.getString(R.string.notif_listening_active)
        }
        val coreText = when (coreOnline) {
            true -> "core connected"
            false -> "core unreachable"
            null -> "core not checked"
        }

        val openIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteAction = if (muted) ServiceControlReceiver.ACTION_UNMUTE else ServiceControlReceiver.ACTION_MUTE
        val muteIntent = PendingIntent.getBroadcast(
            context,
            11,
            Intent(context, ServiceControlReceiver::class.java).setAction(muteAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            context,
            12,
            Intent(context, ServiceControlReceiver::class.java)
                .setAction(ServiceControlReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS")
            .setContentText("$status \u00b7 $coreText")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                0,
                context.getString(if (muted) R.string.notif_action_unmute else R.string.notif_action_mute),
                muteIntent
            )
            .addAction(0, context.getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    fun alert(context: Context, title: String, text: String, id: Int = ALERT_NOTIFICATION_ID) {
        val openIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(id, n)
    }

    /**
     * Android 14+ forbids starting a microphone foreground service from BOOT_COMPLETED, so
     * recovery after reboot is a tap-to-resume prompt. That keeps recovery honest and inside
     * platform rules.
     */
    fun resumePrompt(context: Context) {
        val resumeIntent = PendingIntent.getForegroundService(
            context,
            20,
            Intent(context, WakeWordService::class.java)
                .setAction(WakeWordService.ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS is ready")
            .setContentText("Tap to resume listening for your wake phrase after the restart.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Tap to resume listening. Android does not allow a microphone service to start " +
                    "automatically at boot, so this confirmation is required."
            ))
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(resumeIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(RESUME_NOTIFICATION_ID, n)
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }
}
