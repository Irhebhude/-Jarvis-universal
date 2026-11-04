package dev.jarvis.universal.vision

import kotlinx.coroutines.delay

/**
 * The single in-memory place where camera perception lives.
 *
 * Privacy properties:
 *  - at most one decoded frame is ever retained, and only while the owner is asking JARVIS to look;
 *  - nothing is written to disk, to logs or to the database;
 *  - [clear] runs on every session stop, camera error and app shutdown.
 */
object VisionRepository {

    @Volatile
    var snapshot: SceneSnapshot? = null
        private set

    @Volatile
    var activeLens: Lens? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var activeSinceMs: Long = 0L
        private set

    /** One frame, kept in memory only, used for a single visual-question request. */
    @Volatile
    private var pendingFrame: ByteArray? = null

    val isActive: Boolean get() = activeLens != null

    fun onSessionStarted(lens: Lens) {
        activeLens = lens
        snapshot = null
        lastError = null
        activeSinceMs = System.currentTimeMillis()
        pendingFrame = null
    }

    fun onSnapshot(next: SceneSnapshot) {
        snapshot = next
        activeLens = next.lens
    }

    fun rememberFrame(jpeg: ByteArray?) {
        pendingFrame = jpeg
    }

    fun takeFrame(): ByteArray? = pendingFrame

    /**
     * Set by the live camera session. Actions ask for one frame only when the owner has enabled
     * remote reasoning; otherwise this is never called and no frame is ever encoded.
     */
    @Volatile
    var frameCapture: (suspend () -> ByteArray?)? = null

    suspend fun captureFrame(): ByteArray? {
        val provider = frameCapture ?: return null
        val jpeg = runCatching { provider() }.getOrNull()
        pendingFrame = jpeg
        return jpeg
    }

    fun onSessionStopped() {
        activeLens = null
        snapshot = null
        pendingFrame = null
        frameCapture = null
    }

    fun onError(message: String) {
        lastError = message
        activeLens = null
        snapshot = null
        pendingFrame = null
        frameCapture = null
    }

    /** Waits briefly for a usable snapshot, so a spoken request does not answer from nothing. */
    suspend fun awaitSnapshot(
        lens: Lens? = null,
        maxAgeMs: Long = 2_500,
        timeoutMs: Long = 3_500
    ): SceneSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = snapshot
            val now = System.currentTimeMillis()
            if (current != null &&
                (lens == null || current.lens == lens) &&
                current.ageMs(now) <= maxAgeMs
            ) {
                return current
            }
            if (lastError != null) return null
            delay(80)
        }
        val current = snapshot
        return if (current != null && (lens == null || current.lens == lens)) current else null
    }

    fun statusMap(nowMs: Long = System.currentTimeMillis()): Map<String, Any?> = mapOf(
        "active" to isActive,
        "lens" to activeLens?.wire,
        "error" to lastError,
        "active_since_ms" to if (isActive) System.currentTimeMillis() - activeSinceMs else 0L,
        "snapshot" to snapshot?.let {
            mapOf(
                "age_ms" to it.ageMs(nowMs),
                "faces" to it.faces.size,
                "objects" to it.objects.size,
                "text_chars" to it.text.length,
                "presence" to SceneAnalysis.presence(it),
                "facing" to SceneAnalysis.facing(it)
            )
        }
    )
}
