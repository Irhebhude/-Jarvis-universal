package dev.jarvis.universal.control.actions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.jarvis.universal.control.ActionRegistry
import dev.jarvis.universal.control.ActionResult
import dev.jarvis.universal.control.ActionSpec
import dev.jarvis.universal.control.ArgSpec
import dev.jarvis.universal.control.ArgType
import dev.jarvis.universal.control.Args
import dev.jarvis.universal.control.Danger
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.wake.WakePhraseMatcher

/**
 * Application launching and resolution.
 *
 * Reality check baked into the results: Android 10+ blocks background activity starts. JARVIS
 * can only launch another app while its own interface is closed if the owner grants
 * "Display over other apps" (SYSTEM_ALERT_WINDOW), which is a legitimate, user-visible
 * exemption. Without it the action fails with a clear explanation instead of pretending the app
 * opened.
 */
object AppActions {

    private const val TAG = "AppActions"

    data class AppEntry(val label: String, val packageName: String)

    fun register(registry: ActionRegistry, deps: ActionDeps) {

        registry.register(
            ActionSpec(
                id = "list_apps",
                description = "List launchable applications installed on this device.",
                args = listOf(
                    ArgSpec("query", ArgType.STRING, required = false, maxLength = 60),
                    ArgSpec("limit", ArgType.INTEGER, required = false, min = 1.0, max = 60.0)
                )
            ) { args -> listApps(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "launch_app",
                description = "Open an installed application by name or package identifier.",
                args = listOf(
                    ArgSpec("name", ArgType.STRING, required = false, maxLength = 80, description = "App name or package"),
                    ArgSpec("package", ArgType.STRING, required = false, maxLength = 120, description = "Exact package id")
                )
            ) { args -> launchApp(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "open_web_link",
                description = "Open an https link in the device browser.",
                args = listOf(ArgSpec("url", ArgType.STRING, required = true, maxLength = 800)),
                requiresConfirmation = true,
                danger = Danger.SENSITIVE
            ) { args -> openLink(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "open_display_over_other_apps",
                description = "Open the system screen that grants display-over-other-apps, needed for background app launching."
            ) { openOverlaySettings(deps) }
        )
    }

    // ---------------------------------------------------------------- handlers

    private fun listApps(deps: ActionDeps, args: Args): ActionResult {
        val all = launcherApps(deps)
        val query = args.string("query")?.trim()?.lowercase()
        val filtered = if (query.isNullOrBlank()) all else all.filter {
            it.label.lowercase().contains(query) || it.packageName.contains(query)
        }
        val limit = args.int("limit") ?: 25
        val slice = filtered.take(limit)
        val spoken = if (filtered.isEmpty()) {
            "I could not find a matching application."
        } else {
            "I can see ${filtered.size} application${if (filtered.size == 1) "" else "s"}" +
                (if (!query.isNullOrBlank()) " matching $query." else ".")
        }
        return ActionResult.ok(
            spoken = spoken,
            data = mapOf(
                "total" to filtered.size,
                "apps" to slice.map { mapOf("label" to it.label, "package" to it.packageName) }
            )
        )
    }

    private fun launchApp(deps: ActionDeps, args: Args): ActionResult {
        val apps = launcherApps(deps)
        val query = args.string("package")?.takeIf { it.isNotBlank() } ?: args.string("name")
        if (query.isNullOrBlank()) {
            return ActionResult.fail("missing_argument", "Tell me which application to open.")
        }
        return when (val resolution = resolve(apps, query)) {
            is Resolution.NotFound -> ActionResult.fail(
                "app_not_found",
                "I could not find an application called $query on this device."
            )

            is Resolution.Ambiguous -> ActionResult(
                ok = false,
                spoken = "There are several matches: ${resolution.candidates.joinToString(", ") { it.label }}. Which one?",
                data = mapOf("candidates" to resolution.candidates.map { it.label }),
                error = "ambiguous_app"
            )

            is Resolution.Found -> launch(deps, resolution.entry)
        }
    }

    private fun openLink(deps: ActionDeps, args: Args): ActionResult {
        val raw = args.string("url")?.trim().orEmpty()
        if (!raw.startsWith("https://", ignoreCase = true)) {
            return ActionResult.fail("invalid_url", "I only open https links.")
        }
        if (!canStartActivities(deps)) return blockedByBackgroundRestriction()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(raw)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            deps.app.startActivity(intent)
            ActionResult.ok("Opening the link in your browser.", mapOf("url" to raw))
        } catch (t: Throwable) {
            Logx.e(TAG, "open link failed", t)
            ActionResult.fail("launch_failed", "Android refused to open that link.")
        }
    }

    private fun openOverlaySettings(deps: ActionDeps): ActionResult {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${deps.app.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            deps.app.startActivity(intent)
            ActionResult.ok(
                "Grant display over other apps, then ask me to open an app again.",
                mapOf("screen" to "overlay")
            )
        } catch (t: Throwable) {
            Logx.w(TAG, "overlay settings unavailable: ${t.javaClass.simpleName}")
            ActionResult.fail("settings_unavailable", "I could not open that settings screen.")
        }
    }

    // ---------------------------------------------------------------- helpers

    sealed class Resolution {
        data class Found(val entry: AppEntry) : Resolution()
        data class Ambiguous(val candidates: List<AppEntry>) : Resolution()
        data object NotFound : Resolution()
    }

    fun launcherApps(deps: ActionDeps): List<AppEntry> {
        val pm = deps.app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (t: Throwable) {
            Logx.w(TAG, "queryIntentActivities failed: ${t.javaClass.simpleName}")
            emptyList()
        }
        return resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = try {
                    info.loadLabel(pm).toString()
                } catch (_: Throwable) {
                    pkg
                }
                AppEntry(label, pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun resolve(apps: List<AppEntry>, query: String): Resolution {
        val q = query.trim()
        if (q.isEmpty()) return Resolution.NotFound
        apps.firstOrNull { it.packageName.equals(q, ignoreCase = true) }?.let {
            return Resolution.Found(it)
        }
        apps.firstOrNull { it.label.equals(q, ignoreCase = true) }?.let {
            return Resolution.Found(it)
        }
        val contains = apps.filter { it.label.contains(q, ignoreCase = true) }
        if (contains.size == 1) return Resolution.Found(contains.first())
        if (contains.size > 1) {
            val exactish = contains.sortedBy { it.label.length }
            if (exactish.first().label.length <= 3 + q.length) return Resolution.Found(exactish.first())
            return Resolution.Ambiguous(exactish.take(4))
        }
        // Fuzzy pass, for speech-to-text misspellings ("whatsap", "you tube").
        val scored = apps
            .map { it to WakePhraseMatcher.similarity(it.label.lowercase(), q.lowercase()) }
            .filter { it.second >= 0.82 }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return Resolution.NotFound
        if (scored.size > 1 && scored[0].second - scored[1].second < 0.05) {
            return Resolution.Ambiguous(scored.take(4).map { it.first })
        }
        return Resolution.Found(scored.first().first)
    }

    fun canStartActivities(deps: ActionDeps): Boolean = try {
        Settings.canDrawOverlays(deps.app)
    } catch (_: Throwable) {
        false
    }

    fun blockedByBackgroundRestriction(): ActionResult = ActionResult(
        ok = false,
        spoken = "I can only open apps in the background if you allow me to display over other apps. " +
            "Open setup to grant it.",
        data = mapOf("needs" to "display_over_other_apps", "screen" to "overlay"),
        error = "background_activity_blocked"
    )

    private fun launch(deps: ActionDeps, entry: AppEntry): ActionResult {
        if (!canStartActivities(deps)) return blockedByBackgroundRestriction()
        val pm = deps.app.packageManager
        val intent = pm.getLaunchIntentForPackage(entry.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        if (intent == null) {
            return ActionResult.fail(
                "no_launch_intent",
                "${entry.label} does not expose a launcher activity."
            )
        }
        return try {
            deps.app.startActivity(intent)
            // Android does not tell the launching app whether the new task reached the
            // foreground, so this reports exactly what was verified: the intent was accepted.
            ActionResult.ok(
                spoken = "Opening ${entry.label}.",
                data = mapOf(
                    "package" to entry.packageName,
                    "verified" to "launch_intent_accepted",
                    "note" to "Android does not expose foreground confirmation to the launching app."
                )
            )
        } catch (t: Throwable) {
            Logx.e(TAG, "launch failed for ${entry.packageName}", t)
            ActionResult.fail("launch_failed", "Android refused to start ${entry.label}.")
        }
    }
}
