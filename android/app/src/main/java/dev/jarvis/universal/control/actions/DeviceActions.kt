package dev.jarvis.universal.control.actions

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import dev.jarvis.universal.control.ActionRegistry
import dev.jarvis.universal.control.ActionResult
import dev.jarvis.universal.control.ActionSpec
import dev.jarvis.universal.control.AndroidPermissionState
import dev.jarvis.universal.control.ArgSpec
import dev.jarvis.universal.control.ArgType
import dev.jarvis.universal.control.Args
import dev.jarvis.universal.control.Danger
import dev.jarvis.universal.control.PermissionKind
import dev.jarvis.universal.control.PermissionRequirement
import dev.jarvis.universal.util.Logx
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Device awareness and system controls. Every reading comes from a real platform API; nothing is
 * invented when hardware or permission is missing.
 */
object DeviceActions {

    private const val TAG = "DeviceActions"

    fun register(registry: ActionRegistry, deps: ActionDeps) {

        registry.register(
            ActionSpec(
                id = "battery_status",
                description = "Read the real battery level, charging state and temperature."
            ) { battery(deps) }
        )

        registry.register(
            ActionSpec(
                id = "network_status",
                description = "Report whether the device is online, on Wi-Fi or mobile data, and if the connection is metered."
            ) { network(deps) }
        )

        registry.register(
            ActionSpec(
                id = "device_info",
                description = "Report device model, Android version, memory and free storage."
            ) { deviceInfo(deps) }
        )

        registry.register(
            ActionSpec(
                id = "volume_get",
                description = "Read the current volume of an audio stream.",
                args = listOf(streamArg(required = false))
            ) { args -> volumeGet(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "volume_set",
                description = "Set the volume of an audio stream.",
                args = listOf(
                    streamArg(required = true),
                    ArgSpec("percent", ArgType.INTEGER, required = true, min = 0.0, max = 100.0)
                ),
                danger = Danger.SENSITIVE
            ) { args -> volumeSet(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "dnd_status",
                description = "Report the Do Not Disturb state."
            ) { dndStatus(deps) }
        )

        registry.register(
            ActionSpec(
                id = "dnd_set",
                description = "Change Do Not Disturb. Requires the Do Not Disturb access permission.",
                args = listOf(
                    ArgSpec(
                        "mode",
                        ArgType.ENUM,
                        required = true,
                        enumValues = listOf("off", "priority", "alarms", "silence")
                    )
                ),
                permissions = listOf(
                    PermissionRequirement(
                        AndroidPermissionState.SPECIAL_NOTIFICATION_POLICY,
                        "Do Not Disturb access",
                        PermissionKind.SPECIAL
                    )
                ),
                requiresConfirmation = true,
                danger = Danger.SENSITIVE
            ) { args -> dndSet(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "brightness_get",
                description = "Read the screen brightness level."
            ) { brightnessGet(deps) }
        )

        registry.register(
            ActionSpec(
                id = "brightness_set",
                description = "Set screen brightness. Requires the modify system settings permission.",
                args = listOf(
                    ArgSpec("percent", ArgType.INTEGER, required = true, min = 5.0, max = 100.0)
                ),
                permissions = listOf(
                    PermissionRequirement(
                        AndroidPermissionState.SPECIAL_WRITE_SETTINGS,
                        "Modify system settings",
                        PermissionKind.SPECIAL
                    )
                ),
                danger = Danger.SENSITIVE
            ) { args -> brightnessSet(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "bluetooth_status",
                description = "Report whether Bluetooth is available and enabled."
            ) { bluetooth(deps) }
        )

        registry.register(
            ActionSpec(
                id = "sensor_snapshot",
                description = "Read real values from an available hardware sensor.",
                args = listOf(
                    ArgSpec(
                        "sensor",
                        ArgType.ENUM,
                        required = true,
                        enumValues = listOf(
                            "light", "proximity", "accelerometer", "gyroscope",
                            "magnetometer", "pressure", "step_counter", "humidity"
                        )
                    )
                ),
            ) { args -> sensorSnapshot(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "location_last_known",
                description = "Report the last known location fix. May be stale; never claims a live fix.",
                permissions = listOf(
                    PermissionRequirement(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        "Location",
                        PermissionKind.RUNTIME
                    )
                )
            ) { location(deps) }
        )
    }

    private fun streamArg(required: Boolean) = ArgSpec(
        "stream",
        ArgType.ENUM,
        required = required,
        enumValues = listOf("music", "ring", "alarm", "call", "notification"),
        description = "Audio stream to read or change"
    )

    // ---------------------------------------------------------------- handlers

    private fun battery(deps: ActionDeps): ActionResult {
        val intent: Intent? = try {
            deps.app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (t: Throwable) {
            Logx.w(TAG, "battery broadcast unavailable: ${t.javaClass.simpleName}")
            null
        }
        if (intent == null) {
            val bm = deps.app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct < 0) return ActionResult.fail("battery_unavailable", "I cannot read the battery on this device.")
            return ActionResult.ok("Battery is at $pct percent.", mapOf("percent" to pct))
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val tempC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10.0 else null

        val source = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "mains power"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless charger"
            else -> null
        }
        val spoken = buildString {
            append("Battery is at $percent percent")
            if (charging) append(", charging") else append(", not charging")
            if (source != null) append(" on $source")
            if (tempC != null) append(". Temperature ${"%.1f".format(tempC)} degrees")
            append(".")
        }
        return ActionResult.ok(
            spoken = spoken,
            data = mapOf(
                "percent" to percent,
                "charging" to charging,
                "plugged" to plugged,
                "temperature_c" to tempC,
                "source" to (source ?: "battery")
            )
        )
    }

    private fun network(deps: ActionDeps): ActionResult {
        val cm = deps.app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ActionResult.fail("network_unavailable", "Network information is unavailable.")
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val metered = cm.isActiveNetworkMetered
        val transport = when {
            vpn -> "VPN"
            wifi -> "Wi-Fi"
            cellular -> "mobile data"
            else -> "no active transport"
        }
        val spoken = when {
            active == null -> "You are offline."
            !online -> "There is no internet connection right now."
            wifi && !validated -> "You are on Wi-Fi, but it has not validated an internet connection."
            else -> "You are online over $transport" + if (metered) ", and it is metered." else "."
        }
        return ActionResult.ok(
            spoken,
            mapOf(
                "online" to online,
                "validated" to validated,
                "transport" to transport,
                "metered" to metered
            )
        )
    }

    private fun deviceInfo(deps: ActionDeps): ActionResult {
        val am = deps.app.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mem)
        val stat = try {
            StatFs(deps.app.filesDir.absolutePath)
        } catch (_: Throwable) {
            null
        }
        val freeMb = stat?.let { (it.availableBytes / (1024 * 1024)) } ?: -1
        val data = mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "abis" to Build.SUPPORTED_ABIS.toList(),
            "ram_available_mb" to (mem.availMem / (1024 * 1024)),
            "ram_total_mb" to (mem.totalMem / (1024 * 1024)),
            "free_storage_mb" to freeMb
        )
        val spoken = "This is a ${Build.MANUFACTURER} ${Build.MODEL} running Android ${Build.VERSION.RELEASE}, " +
            "with ${(mem.availMem / (1024 * 1024))} megabytes of memory free."
        return ActionResult.ok(spoken, data)
    }

    private fun streamOf(name: String?): Int = when (name?.lowercase()) {
        "ring" -> android.media.AudioManager.STREAM_RING
        "alarm" -> android.media.AudioManager.STREAM_ALARM
        "call" -> android.media.AudioManager.STREAM_VOICE_CALL
        "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
        else -> android.media.AudioManager.STREAM_MUSIC
    }

    private fun volumeGet(deps: ActionDeps, args: Args): ActionResult {
        val am = deps.permissions.audioManager()
        val stream = streamOf(args.string("stream"))
        val current = am.getStreamVolume(stream)
        val max = am.getStreamMaxVolume(stream)
        val percent = if (max > 0) current * 100 / max else 0
        return ActionResult.ok(
            "Volume is at $percent percent of maximum.",
            mapOf("percent" to percent, "index" to current, "max" to max)
        )
    }

    private fun volumeSet(deps: ActionDeps, args: Args): ActionResult {
        val am = deps.permissions.audioManager()
        val stream = streamOf(args.string("stream"))
        val percent = args.int("percent") ?: return ActionResult.fail("missing_argument", "Tell me the volume level.")
        val max = am.getStreamMaxVolume(stream)
        val index = (percent.coerceIn(0, 100) * max) / 100
        return try {
            am.setStreamVolume(stream, index, 0)
            val now = am.getStreamVolume(stream)
            ActionResult.ok(
                "Volume set to ${if (max > 0) now * 100 / max else 0} percent.",
                mapOf("index" to now, "max" to max)
            )
        } catch (t: Throwable) {
            Logx.w(TAG, "volume set failed: ${t.javaClass.simpleName}")
            ActionResult.fail("volume_failed", "Android refused to change the volume.")
        }
    }

    private fun dndStatus(deps: ActionDeps): ActionResult {
        val nm = deps.app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ActionResult.fail("dnd_unavailable", "Do Not Disturb state is unavailable.")
        val label = when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> "total silence"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority only"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms only"
            NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
            else -> "unknown"
        }
        return ActionResult.ok(
            "Do Not Disturb is $label.",
            mapOf("filter" to label, "can_change" to deps.permissions.canChangeDnd())
        )
    }

    private fun dndSet(deps: ActionDeps, args: Args): ActionResult {
        val nm = deps.app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ActionResult.fail("dnd_unavailable", "Do Not Disturb state is unavailable.")
        val filter = when (args.string("mode")?.lowercase()) {
            "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "silence" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> return ActionResult.fail("invalid_mode", "That Do Not Disturb mode is not recognised.")
        }
        return try {
            nm.setInterruptionFilter(filter)
            ActionResult.ok("Do Not Disturb updated.", mapOf("filter" to nm.currentInterruptionFilter))
        } catch (se: SecurityException) {
            ActionResult.fail(
                "dnd_permission",
                "I need Do Not Disturb access. Open setup to grant it."
            )
        }
    }

    private fun brightnessGet(deps: ActionDeps): ActionResult {
        val value = try {
            Settings.System.getInt(deps.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (t: Throwable) {
            -1
        }
        if (value < 0) return ActionResult.fail("brightness_unavailable", "I cannot read the brightness.")
        val percent = value * 100 / 255
        val auto = try {
            Settings.System.getInt(deps.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (_: Throwable) {
            false
        }
        return ActionResult.ok(
            "Brightness is at $percent percent" + if (auto) ", set automatically by Android." else ".",
            mapOf("percent" to percent, "automatic" to auto)
        )
    }

    private fun brightnessSet(deps: ActionDeps, args: Args): ActionResult {
        if (!Settings.System.canWrite(deps.app)) {
            return ActionResult.fail(
                "write_settings_permission",
                "I need permission to modify system settings. Open setup to grant it."
            )
        }
        val percent = args.int("percent") ?: return ActionResult.fail("missing_argument", "Tell me the brightness.")
        val value = (percent.coerceIn(5, 100) * 255) / 100
        return try {
            Settings.System.putInt(deps.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            val verified = Settings.System.getInt(deps.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            ActionResult.ok(
                "Brightness set to ${verified * 100 / 255} percent.",
                mapOf("percent" to verified * 100 / 255)
            )
        } catch (se: SecurityException) {
            ActionResult.fail("write_settings_permission", "Android refused the brightness change.")
        }
    }

    private fun bluetooth(deps: ActionDeps): ActionResult {
        val manager = deps.app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
            ?: return ActionResult.fail("bluetooth_unavailable", "This device has no Bluetooth adapter.")
        return try {
            val enabled = adapter.isEnabled
            ActionResult.ok(
                if (enabled) "Bluetooth is on." else "Bluetooth is off.",
                mapOf("supported" to true, "enabled" to enabled)
            )
        } catch (se: SecurityException) {
            ActionResult.fail(
                "bluetooth_permission",
                "I need Bluetooth permission to read that. Open setup to grant it."
            )
        }
    }

    private suspend fun sensorSnapshot(deps: ActionDeps, args: Args): ActionResult {
        val sm = deps.app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return ActionResult.fail("sensors_unavailable", "This device exposes no sensors.")
        val type = when (args.string("sensor")?.lowercase()) {
            "light" -> Sensor.TYPE_LIGHT
            "proximity" -> Sensor.TYPE_PROXIMITY
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "magnetometer" -> Sensor.TYPE_MAGNETIC_FIELD
            "pressure" -> Sensor.TYPE_PRESSURE
            "step_counter" -> Sensor.TYPE_STEP_COUNTER
            "humidity" -> Sensor.TYPE_RELATIVE_HUMIDITY
            else -> return ActionResult.fail("unknown_sensor", "I do not know that sensor.")
        }
        val sensor = sm.getDefaultSensor(type)
            ?: return ActionResult.fail(
                "sensor_missing",
                "This device does not have that sensor."
            )
        if (type == Sensor.TYPE_STEP_COUNTER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !deps.permissions.isGranted(Manifest.permission.ACTIVITY_RECOGNITION)
        ) {
            return ActionResult.fail(
                "activity_recognition_permission",
                "The step counter needs the physical activity permission. Open setup to grant it."
            )
        }
        val reading = readOnce(sm, sensor)
            ?: return ActionResult.fail(
                "sensor_timeout",
                "The ${sensor.name} sensor did not report a value."
            )
        val unit = when (type) {
            Sensor.TYPE_LIGHT -> "lux"
            Sensor.TYPE_PROXIMITY -> "centimetres"
            Sensor.TYPE_PRESSURE -> "hectopascals"
            Sensor.TYPE_STEP_COUNTER -> "steps since boot"
            Sensor.TYPE_RELATIVE_HUMIDITY -> "percent relative humidity"
            else -> "units"
        }
        val spoken = when (type) {
            Sensor.TYPE_STEP_COUNTER -> "The step counter reads ${reading.first().toInt()} steps since boot."
            Sensor.TYPE_LIGHT -> "Ambient light is about ${reading.first().toInt()} lux."
            Sensor.TYPE_PROXIMITY -> if (reading.first() < 1f) "Something is close to the screen." else "Nothing is covering the sensor."
            else -> "${sensor.name} reads ${reading.joinToString(", ") { "%.2f".format(it) }}."
        }
        return ActionResult.ok(
            spoken,
            mapOf(
                "sensor" to sensor.name,
                "values" to reading.toList(),
                "unit" to unit,
                "vendor" to sensor.vendor
            )
        )
    }

    private suspend fun readOnce(
        sm: SensorManager,
        sensor: Sensor,
        timeoutMs: Long = 2500
    ): FloatArray? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (cont.isActive) {
                        sm.unregisterListener(this)
                        cont.resume(event.values.copyOf())
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            cont.invokeOnCancellation { sm.unregisterListener(listener) }
        }
    }

    private fun location(deps: ActionDeps): ActionResult {
        if (!deps.permissions.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !deps.permissions.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            return ActionResult.fail("location_permission", "I need location permission for that.")
        }
        val lm = deps.app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ActionResult.fail("location_unavailable", "Location services are unavailable.")
        val best = try {
            lm.getProviders(true)
                .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (se: SecurityException) {
            null
        } ?: return ActionResult.ok(
            "I have no recent location fix. Location may be switched off.",
            mapOf("fix" to false)
        )
        val ageMinutes = (System.currentTimeMillis() - best.time) / 60000
        return ActionResult.ok(
            "My last known location is ${"%.4f".format(best.latitude)}, ${"%.4f".format(best.longitude)}, " +
                "accurate to about ${best.accuracy.toInt()} metres, from $ageMinutes minutes ago.",
            mapOf(
                "latitude" to best.latitude,
                "longitude" to best.longitude,
                "accuracy_m" to best.accuracy,
                "age_minutes" to ageMinutes,
                "provider" to best.provider,
                "live" to false
            )
        )
    }
}
