package dev.jarvis.universal.control

import org.json.JSONArray
import org.json.JSONObject

/** Argument types accepted by registered actions. No free-form shell, no expressions. */
enum class ArgType { STRING, INTEGER, NUMBER, BOOLEAN, ENUM }

data class ArgSpec(
    val name: String,
    val type: ArgType,
    val required: Boolean = false,
    val description: String = "",
    val enumValues: List<String> = emptyList(),
    val maxLength: Int = 0,
    val min: Double = Double.NEGATIVE_INFINITY,
    val max: Double = Double.POSITIVE_INFINITY
)

/** Typed view over the arguments supplied by the intelligence core. */
class Args(private val values: Map<String, Any?>) {

    fun keys(): Set<String> = values.keys

    fun string(name: String): String? = when (val v = values[name]) {
        null -> null
        is String -> v
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> null
    }

    fun int(name: String): Int? = when (val v = values[name]) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toInt() else null
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    fun double(name: String): Double? = when (val v = values[name]) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun bool(name: String): Boolean? = when (val v = values[name]) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "true", "yes", "1", "on" -> true
            "false", "no", "0", "off" -> false
            else -> null
        }
        else -> null
    }

    fun asMap(): Map<String, Any?> = values

    companion object {
        fun of(vararg pairs: Pair<String, Any?>): Args = Args(pairs.toMap().filterValues { it != null })

        /** Android-only helper; validation itself stays free of org.json so it is unit testable. */
        fun fromJson(json: JSONObject?): Args {
            if (json == null) return Args(emptyMap())
            val map = mutableMapOf<String, Any?>()
            val it = json.keys()
            while (it.hasNext()) {
                val key = it.next()
                map[key] = json.opt(key)
            }
            return Args(map)
        }
    }
}

/**
 * Validates arguments against the registered schema. Unknown arguments are rejected rather than
 * ignored: that stops a misbehaving model from smuggling options into a tool it does not own.
 */
object ArgValidator {

    fun validate(specs: List<ArgSpec>, args: Args): List<String> {
        val problems = mutableListOf<String>()
        val declared = specs.map { it.name }.toSet()

        for (key in args.keys()) {
            if (key !in declared) problems.add("unexpected argument '$key'")
        }

        for (spec in specs) {
            val present = args.asMap().containsKey(spec.name) && args.asMap()[spec.name] != null
            if (!present) {
                if (spec.required) problems.add("missing required argument '${spec.name}'")
                continue
            }
            when (spec.type) {
                ArgType.STRING, ArgType.ENUM -> {
                    val value = args.string(spec.name)
                    if (value == null) {
                        problems.add("'${spec.name}' must be a string")
                        continue
                    }
                    if (spec.maxLength > 0 && value.length > spec.maxLength) {
                        problems.add("'${spec.name}' is longer than ${spec.maxLength} characters")
                    }
                    if (spec.type == ArgType.ENUM && spec.enumValues.isNotEmpty() &&
                        value.lowercase() !in spec.enumValues.map { it.lowercase() }
                    ) {
                        problems.add("'${spec.name}' must be one of ${spec.enumValues.joinToString(", ")}")
                    }
                }

                ArgType.INTEGER -> {
                    val value = args.int(spec.name)
                    if (value == null) {
                        problems.add("'${spec.name}' must be a whole number")
                    } else if (value.toDouble() < spec.min || value.toDouble() > spec.max) {
                        problems.add("'${spec.name}' must be between ${spec.min.toInt()} and ${spec.max.toInt()}")
                    }
                }

                ArgType.NUMBER -> {
                    val value = args.double(spec.name)
                    if (value == null) {
                        problems.add("'${spec.name}' must be a number")
                    } else if (value < spec.min || value > spec.max) {
                        problems.add("'${spec.name}' must be between ${spec.min} and ${spec.max}")
                    }
                }

                ArgType.BOOLEAN -> {
                    if (args.bool(spec.name) == null) problems.add("'${spec.name}' must be true or false")
                }
            }
        }
        return problems
    }
}

enum class PermissionKind { RUNTIME, SPECIAL }

data class PermissionRequirement(
    val permission: String,
    val label: String,
    val kind: PermissionKind = PermissionKind.RUNTIME
)

enum class Danger { SAFE, SENSITIVE, DESTRUCTIVE }

data class ActionResult(
    val ok: Boolean,
    val spoken: String,
    val data: Map<String, Any?> = emptyMap(),
    val error: String? = null,
    val needsConfirmation: Boolean = false
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("ok", ok)
        json.put("spoken", spoken)
        json.put("needs_confirmation", needsConfirmation)
        if (error != null) json.put("error", error)
        json.put("data", toJsonValue(data))
        return json
    }

    companion object {
        fun ok(spoken: String, data: Map<String, Any?> = emptyMap()) = ActionResult(true, spoken, data)
        fun fail(error: String, spoken: String) = ActionResult(false, spoken, error = error)

        fun toJsonValue(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val o = JSONObject()
                value.forEach { (k, v) -> if (k != null) o.put(k.toString(), toJsonValue(v)) }
                o
            }
            is List<*> -> {
                val a = JSONArray()
                value.forEach { a.put(toJsonValue(it)) }
                a
            }
            is Boolean, is Number, is String -> value
            else -> value.toString()
        }
    }
}

class ActionSpec(
    val id: String,
    val description: String,
    val args: List<ArgSpec> = emptyList(),
    val permissions: List<PermissionRequirement> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val danger: Danger = Danger.SAFE,
    val handler: suspend (Args) -> ActionResult
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("description", description)
        json.put("requires_confirmation", requiresConfirmation)
        json.put("danger", danger.name.lowercase())
        val argArray = JSONArray()
        args.forEach { arg ->
            argArray.put(
                JSONObject()
                    .put("name", arg.name)
                    .put("type", arg.type.name.lowercase())
                    .put("required", arg.required)
                    .put("description", arg.description)
                    .apply { if (arg.enumValues.isNotEmpty()) put("enum", JSONArray(arg.enumValues)) }
                    .apply { if (arg.maxLength > 0) put("max_length", arg.maxLength) }
            )
        }
        json.put("args", argArray)
        val permArray = JSONArray()
        permissions.forEach {
            permArray.put(JSONObject().put("id", it.permission).put("kind", it.kind.name.lowercase()))
        }
        json.put("permissions", permArray)
        return json
    }
}

/** Ordered, immutable registry. Lookup by exact id only — no wildcards, no prefix matching. */
class ActionRegistry {

    private val specs = LinkedHashMap<String, ActionSpec>()

    fun register(spec: ActionSpec): ActionRegistry {
        require(spec.id.matches(Regex("[a-z][a-z0-9_]{2,40}"))) { "Invalid action id: ${spec.id}" }
        require(!specs.containsKey(spec.id)) { "Duplicate action id: ${spec.id}" }
        specs[spec.id] = spec
        return this
    }

    fun get(id: String): ActionSpec? = specs[id]

    fun all(): List<ActionSpec> = specs.values.toList()

    fun ids(): List<String> = specs.keys.toList()

    fun catalog(): JSONArray {
        val array = JSONArray()
        specs.values.forEach { array.put(it.toJson()) }
        return array
    }

    fun size(): Int = specs.size
}
