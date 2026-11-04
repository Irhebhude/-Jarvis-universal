package dev.jarvis.universal.control

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Resolves real permission state on this device. Runtime permissions are checked directly;
 * "special" capabilities (settings writes, DND policy, notification access, accessibility) each
 * have their own system gate and are checked the way that gate requires.
 */
class AndroidPermissionState(private val context: Context) {

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isSpecialGranted(id: String): Boolean = when (id) {
        SPECIAL_WRITE_SETTINGS -> Settings.System.canWrite(context)
        SPECIAL_NOTIFICATION_POLICY -> canChangeDnd()
        SPECIAL_NOTIFICATION_LISTENER -> notificationAccessEnabled()
        SPECIAL_ACCESSIBILITY -> accessibilityEnabled()
        SPECIAL_EXACT_ALARM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManagerCanScheduleExactAlarms()
        } else true
        SPECIAL_BATTERY_UNRESTRICTED -> batteryUnrestricted()
        else -> false
    }

    fun requirementSatisfied(requirement: PermissionRequirement): Boolean = when (requirement.kind) {
        PermissionKind.RUNTIME -> isGranted(requirement.permission)
        PermissionKind.SPECIAL -> isSpecialGranted(requirement.permission)
    }

    fun canChangeDnd(): Boolean = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        nm.isNotificationPolicyAccessGranted
    } catch (_: Throwable) {
        false
    }

    fun notificationAccessEnabled(): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    } catch (_: Throwable) {
        false
    }

    fun accessibilityEnabled(): Boolean = try {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val expected = "${context.packageName}/${JarvisAccessibilityService::class.java.name}"
        enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    } catch (_: Throwable) {
        false
    }

    private fun alarmManagerCanScheduleExactAlarms(): Boolean = try {
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        am.canScheduleExactAlarms()
    } catch (_: Throwable) {
        false
    }

    fun batteryUnrestricted(): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Throwable) {
        false
    }

    /** Snapshot for the setup screen and the diagnostics panel. */
    fun report(): Map<String, Boolean> = linkedMapOf(
        "microphone" to isGranted(Manifest.permission.RECORD_AUDIO),
        "notifications" to if (Build.VERSION.SDK_INT >= 33) {
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else true,
        "contacts" to isGranted(Manifest.permission.READ_CONTACTS),
        "phone" to isGranted(Manifest.permission.CALL_PHONE),
        "sms" to isGranted(Manifest.permission.SEND_SMS),
        "location" to (
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
            ),
        "camera" to isGranted(Manifest.permission.CAMERA),
        "activity_recognition" to if (Build.VERSION.SDK_INT >= 29) {
            isGranted(Manifest.permission.ACTIVITY_RECOGNITION)
        } else true,
        "bluetooth" to if (Build.VERSION.SDK_INT >= 31) {
            isGranted(Manifest.permission.BLUETOOTH_CONNECT)
        } else true,
        "write_settings" to isSpecialGranted(SPECIAL_WRITE_SETTINGS),
        "dnd_policy" to isSpecialGranted(SPECIAL_NOTIFICATION_POLICY),
        "notification_access" to isSpecialGranted(SPECIAL_NOTIFICATION_LISTENER),
        "accessibility" to isSpecialGranted(SPECIAL_ACCESSIBILITY),
        "battery_unrestricted" to isSpecialGranted(SPECIAL_BATTERY_UNRESTRICTED)
    )

    fun audioManager(): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        const val SPECIAL_WRITE_SETTINGS = "write_settings"
        const val SPECIAL_NOTIFICATION_POLICY = "notification_policy"
        const val SPECIAL_NOTIFICATION_LISTENER = "notification_listener"
        const val SPECIAL_ACCESSIBILITY = "accessibility"
        const val SPECIAL_EXACT_ALARM = "exact_alarm"
        const val SPECIAL_BATTERY_UNRESTRICTED = "battery_unrestricted"
    }
}
