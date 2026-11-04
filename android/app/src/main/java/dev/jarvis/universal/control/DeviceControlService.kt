package dev.jarvis.universal.control

import android.app.Application
import dev.jarvis.universal.control.actions.ActionDeps
import dev.jarvis.universal.control.actions.AppActions
import dev.jarvis.universal.control.actions.CameraActions
import dev.jarvis.universal.control.actions.CommsActions
import dev.jarvis.universal.control.actions.DeviceActions
import dev.jarvis.universal.control.actions.FileActions
import dev.jarvis.universal.control.actions.NotificationActions
import dev.jarvis.universal.control.actions.SystemActions
import dev.jarvis.universal.net.CoreClient
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The only path from an AI plan to an Android operation.
 *
 * Pipeline for every request:
 *   registered? -> arguments valid? -> permissions granted? -> owner confirmed (when required)?
 *   -> execute -> structured result -> audit event.
 *
 * Anything outside that path is rejected. There is no shell, no reflection on arbitrary
 * components, and no way for the model to name a permission or an action that is not in the
 * registry above.
 */
class DeviceControlService(
    private val app: Application,
    private val prefs: Prefs,
    private val core: CoreClient
) {

    val registry = ActionRegistry()
    val permissionState = AndroidPermissionState(app)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deps = ActionDeps(app, prefs, permissionState, core)

    init {
        AppActions.register(registry, deps)
        DeviceActions.register(registry, deps)
        CommsActions.register(registry, deps)
        NotificationActions.register(registry, deps)
        FileActions.register(registry, deps)
        SystemActions.register(registry, deps)
        CameraActions.register(registry, deps)
        Logx.d(TAG, "registered ${registry.size()} actions: ${registry.ids().joinToString(",")}")
    }

    fun catalog(): JSONArray = registry.catalog()

    fun permissionReport(): Map<String, Boolean> = permissionState.report()

    /** Executes one registered action. [confirmed] may only be true after an explicit owner yes. */
    suspend fun execute(
        actionId: String,
        args: Args,
        confirmed: Boolean,
        origin: String
    ): ActionResult {
        val spec = registry.get(actionId)
        if (spec == null) {
            audit(actionId, args, origin, ok = false, error = "unregistered_action")
            return ActionResult.fail(
                "unregistered_action",
                "I do not have an approved operation called $actionId."
            )
        }

        val problems = ArgValidator.validate(spec.args, args)
        if (problems.isNotEmpty()) {
            audit(actionId, args, origin, ok = false, error = "invalid_arguments:${problems.joinToString(";")}")
            return ActionResult.fail(
                "invalid_arguments",
                "That request did not match the rules for ${spec.description.lowercase()}."
            ).copy(data = mapOf("problems" to problems))
        }

        val missing = spec.permissions.filterNot { permissionState.requirementSatisfied(it) }
        if (missing.isNotEmpty()) {
            val labels = missing.joinToString(", ") { it.label }
            audit(actionId, args, origin, ok = false, error = "permission_required:$labels")
            return ActionResult(
                ok = false,
                spoken = "I need $labels permission before I can do that. Open setup to grant it.",
                data = mapOf(
                    "missing_permissions" to missing.map { it.permission },
                    "labels" to missing.map { it.label }
                ),
                error = "permission_required"
            )
        }

        if (spec.requiresConfirmation && !confirmed) {
            audit(actionId, args, origin, ok = false, error = "confirmation_required")
            return ActionResult(
                ok = false,
                spoken = confirmationPrompt(spec, args),
                data = mapOf(
                    "confirmation_prompt" to confirmationPrompt(spec, args),
                    "action" to spec.id,
                    "danger" to spec.danger.name.lowercase()
                ),
                error = "confirmation_required",
                needsConfirmation = true
            )
        }

        val result = try {
            spec.handler(args)
        } catch (t: Throwable) {
            Logx.e(TAG, "action $actionId threw", t)
            ActionResult.fail(
                "execution_failed",
                "That operation failed on the device."
            )
        }

        audit(
            actionId = actionId,
            args = args,
            origin = origin,
            ok = result.ok,
            error = result.error,
            detail = result.spoken
        )
        return result
    }

    private fun confirmationPrompt(spec: ActionSpec, args: Args): String {
        val specifics = buildString {
            args.string("contact")?.let { append(" to $it") }
            args.string("number")?.let { append(" at $it") }
            args.string("name")?.let { append(" $it") }
            args.string("body")?.let { append(": \u201c${it.take(120)}\u201d") }
            args.int("percent")?.let { append(" to $it percent") }
        }
        return "Do you want me to ${spec.description.lowercase().trimEnd('.')}$specifics? Say yes to confirm."
    }

    private fun audit(
        actionId: String,
        args: Args,
        origin: String,
        ok: Boolean,
        error: String?,
        detail: String? = null
    ) {
        scope.launch {
            try {
                val payload = JSONObject()
                    .put("action_id", actionId)
                    .put("origin", origin)
                    .put("ok", ok)
                    .put("args_digest", describeArgs(args))
                error?.let { payload.put("error", it.take(200)) }
                detail?.let { payload.put("spoken", it.take(200)) }
                core.postEvent("action_result", payload)
            } catch (t: Throwable) {
                Logx.w(TAG, "audit delivery failed: ${t.javaClass.simpleName}")
            }
        }
    }

    /**
     * Audit records keep the shape of what was requested but not sensitive payloads: phone
     * numbers, message bodies and URLs are reduced to a length marker.
     */
    private fun describeArgs(args: Args): String {
        val map = args.asMap()
        if (map.isEmpty()) return "{}"
        return map.entries.joinToString(", ", "{", "}") { (key, value) ->
            if (key in SENSITIVE_ARG_KEYS) {
                "\"$key\":\"<redacted:${value?.toString()?.length ?: 0}>\""
            } else {
                "\"$key\":\"${value?.toString()?.take(60)?.replace("\"", "'")}\""
            }
        }
    }

    companion object {
        private const val TAG = "DeviceControl"
        private val SENSITIVE_ARG_KEYS = setOf("number", "body", "url", "uri", "packages", "query")
    }
}
