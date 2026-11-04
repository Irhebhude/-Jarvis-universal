package dev.jarvis.universal.control.actions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.jarvis.universal.JarvisState
import dev.jarvis.universal.control.ActionRegistry
import dev.jarvis.universal.control.ActionResult
import dev.jarvis.universal.control.ActionSpec
import dev.jarvis.universal.control.AndroidPermissionState
import dev.jarvis.universal.control.ArgSpec
import dev.jarvis.universal.control.ArgType
import dev.jarvis.universal.control.Args
import dev.jarvis.universal.control.Danger
import dev.jarvis.universal.control.JarvisAccessibilityService
import dev.jarvis.universal.control.PermissionKind
import dev.jarvis.universal.control.PermissionRequirement
import dev.jarvis.universal.service.ServiceControlReceiver
import dev.jarvis.universal.ui.MainActivity
import dev.jarvis.universal.util.Logx

/**
 * Interface control, settings shortcuts and the optional accessibility navigation bridge.
 *
 * Background activity starts are restricted by Android, so actions that open a screen check the
 * display-over-other-apps grant first and fail honestly when it is missing.
 */
object SystemActions {

    private const val TAG = "SystemActions"

    fun register(registry: ActionRegistry, deps: ActionDeps) {

        registry.register(
            ActionSpec(
                id = "open_settings_screen",
                description = "Open a specific Android settings screen for the owner.",
                args = listOf(
                    ArgSpec(
                        "screen",
                        ArgType.ENUM,
                        required = true,
                        enumValues = listOf(
                            "wifi", "bluetooth", "battery", "notifications", "dnd",
                            "write_settings", "accessibility", "overlay", "app_info",
                            "battery_optimization", "location", "sound"
                        )
                    )
                )
            ) { args -> openSettings(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "accessibility_nav",
                description = "Perform a global navigation action (back, home, recents) using the optional accessibility service.",
                args = listOf(
                    ArgSpec(
                        "action",
                        ArgType.ENUM,
                        required = true,
                        enumValues = listOf("back", "home", "recents", "notifications", "quick_settings")
                    )
                ),
                permissions = listOf(
                    PermissionRequirement(
                        AndroidPermissionState.SPECIAL_ACCESSIBILITY,
                        "Accessibility navigation",
                        PermissionKind.SPECIAL
                    )
                ),
                danger = Danger.SENSITIVE
            ) { args -> navigate(args) }
        )

        registry.register(
            ActionSpec(
                id = "open_jarvis_ui",
                description = "Bring the holographic JARVIS interface to the foreground."
            ) { openUi(deps) }
        )

        registry.register(
            ActionSpec(
                id = "jarvis_status",
                description = "Report what JARVIS is doing, whether the core is reachable and whether the microphone is muted."
            ) { status() }
        )

        registry.register(
            ActionSpec(
                id = "set_microphone_muted",
                description = "Pause or resume microphone listening immediately.",
                args = listOf(ArgSpec("muted", ArgType.BOOLEAN, required = true))
            ) { args -> setMuted(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "set_wake_word_enabled",
                description = "Enable or disable the always-on wake-word service.",
                args = listOf(ArgSpec("enabled", ArgType.BOOLEAN, required = true))
            ) { args -> setWakeEnabled(deps, args) }
        )
    }

    // ---------------------------------------------------------------- handlers

    private fun openSettings(deps: ActionDeps, args: Args): ActionResult {
        if (!AppActions.canStartActivities(deps)) return AppActions.blockedByBackgroundRestriction()
        val screen = args.string("screen")?.lowercase().orEmpty()
        val intent = when (screen) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "write_settings" -> Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${deps.app.packageName}")
            )
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "overlay" -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${deps.app.packageName}")
            )
            "app_info" -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${deps.app.packageName}")
            )
            "battery_optimization" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            else -> return ActionResult.fail("unknown_screen", "I do not know that settings screen.")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            deps.app.startActivity(intent)
            ActionResult.ok("Opening the $screen settings screen.", mapOf("screen" to screen))
        } catch (t: Throwable) {
            Logx.w(TAG, "settings screen $screen unavailable")
            ActionResult.fail("settings_unavailable", "This device does not expose that settings screen.")
        }
    }

    private fun navigate(args: Args): ActionResult {
        val service = JarvisAccessibilityService.instance
            ?: return ActionResult.fail(
                "accessibility_disabled",
                "Accessibility navigation is off. Enable JARVIS navigation in Android accessibility settings."
            )
        val action = args.string("action")?.lowercase().orEmpty()
        return if (service.performNavigation(action)) {
            ActionResult.ok("Done.", mapOf("navigation" to action))
        } else {
            ActionResult.fail("navigation_failed", "Android refused the $action action.")
        }
    }

    private fun openUi(deps: ActionDeps): ActionResult {
        if (!AppActions.canStartActivities(deps)) return AppActions.blockedByBackgroundRestriction()
        val intent = Intent(deps.app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return try {
            deps.app.startActivity(intent)
            ActionResult.ok("Opening my interface.", mapOf("opened" to true))
        } catch (t: Throwable) {
            ActionResult.fail("ui_unavailable", "I could not open my interface.")
        }
    }

    private fun status(): ActionResult {
        val phase = JarvisState.phase.value.name.lowercase()
        val muted = JarvisState.micMuted.value
        val wake = JarvisState.wakeEnabled.value
        val core = when (JarvisState.coreOnline.value) {
            true -> "reachable"
            false -> "unreachable"
            null -> "not checked yet"
        }
        val spoken = "I am ${if (muted) "muted" else phase}. The wake word is " +
            (if (wake) "enabled" else "disabled") + ", and my intelligence core is $core."
        return ActionResult.ok(
            spoken,
            mapOf(
                "phase" to phase,
                "muted" to muted,
                "wake_enabled" to wake,
                "core" to core,
                "task" to JarvisState.task.value
            )
        )
    }

    private fun setMuted(deps: ActionDeps, args: Args): ActionResult {
        val muted = args.bool("muted") ?: return ActionResult.fail("missing_argument", "Mute or unmute?")
        deps.prefs.micMuted = muted
        val action = if (muted) ServiceControlReceiver.ACTION_MUTE else ServiceControlReceiver.ACTION_UNMUTE
        deps.app.sendBroadcast(Intent(action).setPackage(deps.app.packageName))
        return ActionResult.ok(
            if (muted) "Microphone muted. I will not listen until you unmute me."
            else "Microphone resumed.",
            mapOf("muted" to muted)
        )
    }

    private fun setWakeEnabled(deps: ActionDeps, args: Args): ActionResult {
        val enabled = args.bool("enabled") ?: return ActionResult.fail("missing_argument", "Enable or disable?")
        deps.prefs.wakeServiceEnabled = enabled
        val action = if (enabled) ServiceControlReceiver.ACTION_START else ServiceControlReceiver.ACTION_STOP
        deps.app.sendBroadcast(Intent(action).setPackage(deps.app.packageName))
        return ActionResult.ok(
            if (enabled) "Wake word service enabled." else "Wake word service disabled.",
            mapOf("enabled" to enabled)
        )
    }
}
