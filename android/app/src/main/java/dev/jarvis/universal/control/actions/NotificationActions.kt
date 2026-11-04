package dev.jarvis.universal.control.actions

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
import dev.jarvis.universal.notify.NotificationBuffer

/**
 * Notification digest. Content is only available after the owner explicitly grants notification
 * access, excluded packages are dropped before any text is read, and nothing is persisted.
 */
object NotificationActions {

    fun register(registry: ActionRegistry, deps: ActionDeps) {

        registry.register(
            ActionSpec(
                id = "notifications_summary",
                description = "Summarise recent notifications that the owner has allowed JARVIS to see.",
                args = listOf(
                    ArgSpec("limit", ArgType.INTEGER, required = false, min = 1.0, max = 20.0)
                ),
                permissions = listOf(
                    PermissionRequirement(
                        AndroidPermissionState.SPECIAL_NOTIFICATION_LISTENER,
                        "Notification access",
                        PermissionKind.SPECIAL
                    )
                ),
                danger = Danger.SENSITIVE
            ) { args -> summary(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "notifications_set_exclusions",
                description = "Set which applications JARVIS must never read notifications from. Pass an empty string to clear.",
                args = listOf(
                    ArgSpec("packages", ArgType.STRING, required = true, maxLength = 2000)
                ),
                danger = Danger.SENSITIVE
            ) { args -> setExclusions(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "notifications_status",
                description = "Report notification access state, buffered count and excluded applications."
            ) { status(deps) }
        )
    }

    private fun summary(deps: ActionDeps, args: Args): ActionResult {
        val excluded = deps.prefs.notificationExclusions
        val limit = args.int("limit") ?: 6
        val entries = NotificationBuffer.recent(excluded, limit)
        if (entries.isEmpty()) {
            return ActionResult.ok(
                "There are no recent notifications I am allowed to see.",
                mapOf("count" to 0, "excluded" to excluded.toList())
            )
        }
        val lines = entries.mapNotNull { entry ->
            val body = entry.text.ifBlank { entry.title }
            if (body.isBlank()) null else "${entry.appLabel}: ${entry.title.ifBlank { body }}"
        }
        val spoken = "You have ${entries.size} recent notification${if (entries.size == 1) "" else "s"}. " +
            lines.take(4).joinToString(". ") + "."
        return ActionResult.ok(
            spoken = spoken,
            data = mapOf(
                "count" to entries.size,
                "excluded" to excluded.toList(),
                "items" to entries.map {
                    mapOf(
                        "app" to it.appLabel,
                        "package" to it.packageName,
                        "title" to it.title,
                        "text" to it.text,
                        "ongoing" to it.isOngoing
                    )
                }
            )
        )
    }

    private fun setExclusions(deps: ActionDeps, args: Args): ActionResult {
        val raw = args.string("packages").orEmpty()
        val packages = raw.split(',', ' ', '\n')
            .map { it.trim() }
            .filter { it.matches(Regex("[A-Za-z0-9_.]+")) }
            .toSet()
        deps.prefs.notificationExclusions = packages
        // Drop anything already buffered so exclusions take effect immediately.
        NotificationBuffer.purge(packages)
        return ActionResult.ok(
            if (packages.isEmpty()) "Notification exclusions cleared."
            else "I will ignore notifications from ${packages.size} application${if (packages.size == 1) "" else "s"}.",
            mapOf("excluded" to packages.toList())
        )
    }

    private fun status(deps: ActionDeps): ActionResult {
        val allowed = deps.permissions.notificationAccessEnabled()
        val excluded = deps.prefs.notificationExclusions
        val spoken = if (allowed) {
            "Notification access is granted. I am holding ${NotificationBuffer.size()} notification summaries in memory, " +
                "and ignoring ${excluded.size} excluded applications."
        } else {
            "Notification access is not granted, so I cannot read any notifications."
        }
        return ActionResult.ok(
            spoken,
            mapOf(
                "access_granted" to allowed,
                "buffered" to NotificationBuffer.size(),
                "excluded" to excluded.toList()
            )
        )
    }
}
