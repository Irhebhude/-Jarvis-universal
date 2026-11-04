package dev.jarvis.universal.net

import android.util.Base64
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.util.Prefs
import dev.jarvis.universal.util.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Raised when the intelligence core cannot be reached at all. */
class CoreUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Raised when the core rejects the pairing token (401/403). */
class CoreUnauthorized(message: String) : IOException(message)

data class HealthSnapshot(
    val reachable: Boolean,
    val status: String,
    val version: String,
    val detail: String
)

data class PlannedAction(
    val id: String,
    val args: JSONObject,
    val needsConfirmation: Boolean,
    val description: String
)

data class CommandPlan(
    val planId: String,
    val speech: String,
    val actions: List<PlannedAction>,
    val contextUsed: List<String>
)

/**
 * Authenticated localhost bridge to the Termux intelligence core.
 *
 * Security properties:
 *  - the base URL is 127.0.0.1 by default (never a LAN address unless the owner changes it),
 *  - the bearer token lives in [SecretStore] (Keystore-encrypted) and is sent as a header, never
 *    in a query string, so it does not appear in URLs or access logs,
 *  - tokens are never logged; [dev.jarvis.universal.util.Logx.redact] is used if a label is needed.
 */
class CoreClient(private val prefs: Prefs, private val secrets: SecretStore) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val wsHttp: OkHttpClient = http.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var eventJob: Job? = null

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    var lastEventAt: Long = 0L
        private set

    val baseUrl: String
        get() = "http://${prefs.coreHost}:${prefs.corePort}"

    private val wsUrl: String
        get() = "ws://${prefs.coreHost}:${prefs.corePort}/ws/device"

    fun hasPairedToken(): Boolean = !token().isNullOrBlank()

    fun token(): String? = secrets.get(TOKEN_KEY)

    fun storeToken(token: String) = secrets.put(TOKEN_KEY, token)

    fun clearToken() = secrets.remove(TOKEN_KEY)

    private fun authorized(builder: Request.Builder, auth: Boolean = true): Request.Builder {
        if (auth) {
            token()?.let { builder.header("Authorization", "Bearer $it") }
        }
        return builder.header("Accept", "application/json")
    }

    suspend fun health(): HealthSnapshot = withContext(Dispatchers.IO) {
        try {
            val request = authorized(Request.Builder().url("$baseUrl/health"), auth = false).get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext HealthSnapshot(
                        false, "http_${response.code}", "unknown", body.take(160)
                    )
                }
                val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                HealthSnapshot(
                    reachable = json.optString("status") == "ok",
                    status = json.optString("status", "unknown"),
                    version = json.optString("version", "unknown"),
                    detail = json.optString("detail", "")
                )
            }
        } catch (io: IOException) {
            HealthSnapshot(false, "unreachable", "unknown", io.message ?: "connection failed")
        }
    }

    suspend fun status(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(executeJson("GET", "/status", null))
    }

    suspend fun pair(port: Int, code: String, deviceName: String): JSONObject = withContext(Dispatchers.IO) {
        prefs.corePort = port
        val payload = JSONObject()
            .put("code", code.trim())
            .put("device_name", deviceName)
            .put("platform", "android")
        val response = executeJson("POST", "/pair", payload, auth = false)
        val json = JSONObject(response)
        val token = json.optString("device_token")
        if (token.isBlank()) throw CoreUnavailable(json.optString("error", "Pairing rejected"))
        storeToken(token)
        Logx.d(TAG, "paired with core (token ${Logx.redact(token)})")
        json
    }

    suspend fun command(text: String, sessionId: String, deviceContext: JSONObject): CommandPlan =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("text", text)
                .put("session_id", sessionId)
                .put("device", deviceContext)
            val json = JSONObject(executeJson("POST", "/assistant/command", payload))
            val actions = mutableListOf<PlannedAction>()
            val arr = json.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                actions.add(
                    PlannedAction(
                        id = id,
                        args = item.optJSONObject("args") ?: JSONObject(),
                        needsConfirmation = item.optBoolean("needs_confirmation", false),
                        description = item.optString("description", id)
                    )
                )
            }
            CommandPlan(
                planId = json.optString("plan_id", ""),
                speech = json.optString("speech", ""),
                actions = actions,
                contextUsed = json.optJSONArray("context")?.let { ctx ->
                    (0 until ctx.length()).map { ctx.optString(it) }
                } ?: emptyList()
            )
        }

    suspend fun execute(planId: String, results: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("plan_id", planId).put("results", results)
        JSONObject(executeJson("POST", "/assistant/execute", payload))
    }

    /** Returns null when the core has no local transcription engine (HTTP 501). */
    suspend fun transcribe(pcm: ByteArray, sampleRate: Int = 16000): String? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("format", "pcm16")
                .put("sample_rate", sampleRate)
                .put("audio_b64", Base64.encodeToString(pcm, Base64.NO_WRAP))
            val result = executeJsonOrNull("POST", "/voice/transcript", payload) ?: return@withContext null
            val json = runCatching { JSONObject(result.body) }.getOrElse { return@withContext null }
            if (result.code == 501) null else json.optString("text").ifBlank { null }
        }

    suspend fun postEvent(type: String, payload: JSONObject): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("type", type).put("payload", payload).put("ts", System.currentTimeMillis())
            executeJson("POST", "/device/event", body)
            true
        } catch (e: Exception) {
            Logx.w(TAG, "event $type not delivered: ${e.message}")
            false
        }
    }

    suspend fun memory(path: String, method: String = "GET", payload: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) { JSONObject(executeJson(method, path, payload)) }

    private data class BodyResult(val code: Int, val body: String)

    private fun executeJsonOrNull(method: String, path: String, payload: JSONObject?): BodyResult? {
        val request = buildRequest(method, path, payload) ?: return null
        return try {
            http.newCall(request).execute().use { response ->
                BodyResult(response.code, response.body?.string().orEmpty())
            }
        } catch (io: IOException) {
            throw CoreUnavailable(io.message ?: "Core unreachable", io)
        }
    }

    private fun executeJson(
        method: String,
        path: String,
        payload: JSONObject?,
        auth: Boolean = true
    ): String {
        val request = buildRequest(method, path, payload, auth)
            ?: throw CoreUnavailable("Invalid core URL")
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> return body
                    response.code == 401 || response.code == 403 ->
                        throw CoreUnauthorized("Core rejected the pairing token (${response.code})")
                    response.code == 429 ->
                        throw CoreUnavailable("Core is rate limiting requests; try again shortly.")
                    else -> throw CoreUnavailable(
                        "Core returned HTTP ${response.code}: ${body.take(180)}"
                    )
                }
            }
        } catch (io: IOException) {
            if (io is CoreUnavailable || io is CoreUnauthorized) throw io
            throw CoreUnavailable(io.message ?: "Core unreachable", io)
        }
    }

    private fun buildRequest(
        method: String,
        path: String,
        payload: JSONObject?,
        auth: Boolean = true
    ): Request? {
        val url = "$baseUrl$path".toHttpUrlOrNull() ?: return null
        val builder = authorized(Request.Builder().url(url), auth)
        return when (method.uppercase()) {
            "GET" -> builder.get().build()
            "DELETE" -> builder.delete().build()
            else -> {
                val body = (payload ?: JSONObject()).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                builder.post(body).build()
            }
        }
    }

    /**
     * Opens the continuous device event channel and keeps it open with bounded exponential
     * backoff. The core pushes status changes here; the device pushes audit events.
     */
    fun startEventChannel(
        scope: CoroutineScope,
        onEvent: (JSONObject) -> Unit,
        onConnection: (Boolean, String) -> Unit
    ) {
        if (eventJob?.isActive == true) return
        eventJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                val tokenValue = token()
                if (tokenValue.isNullOrBlank()) {
                    onConnection(false, "not paired")
                    delay(15_000)
                    continue
                }
                val request = Request.Builder()
                    .url(wsUrl)
                    .header("Authorization", "Bearer $tokenValue")
                    .build()

                val connected = suspendCancellableCoroutine<Boolean> { cont ->
                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            attempt = 0
                            socket = webSocket
                            onConnection(true, "connected")
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            lastEventAt = System.currentTimeMillis()
                            runCatching { JSONObject(text) }.onSuccess(onEvent)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            socket = null
                            onConnection(false, t.message ?: "socket failure")
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            socket = null
                            onConnection(false, "closed $code")
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    try {
                        wsHttp.newWebSocket(request, listener)
                    } catch (t: Throwable) {
                        onConnection(false, t.message ?: "socket error")
                        if (cont.isActive) cont.resume(false)
                    }
                }
                if (!connected) {
                    attempt++
                    val backoff = minOf(30_000L, 1_000L * (1L shl minOf(attempt, 5)))
                    delay(backoff)
                }
            }
        }
    }

    fun stopEventChannel() {
        eventJob?.cancel()
        eventJob = null
        socket?.close(1000, "service stopping")
        socket = null
    }

    companion object {
        private const val TAG = "CoreClient"
        private const val TOKEN_KEY = "bridge_device_token"
    }
}
