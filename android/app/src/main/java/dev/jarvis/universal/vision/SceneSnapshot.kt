package dev.jarvis.universal.vision

/**
 * Scene model produced by on-device ML Kit detectors.
 *
 * Everything here is deliberately plain Kotlin — no Android or ML Kit types — so the interpretation
 * logic (presence, head orientation, position, scene hints, change detection) is unit testable
 * without a camera or a device.
 */

enum class Lens(val wire: String) {
    FRONT("front"),
    REAR("rear");

    val spoken: String get() = if (this == FRONT) "front" else "rear"

    companion object {
        fun parse(value: String?): Lens? = when (value?.trim()?.lowercase()) {
            "front", "selfie", "user", "face" -> FRONT
            "rear", "back", "environment", "world", "main" -> REAR
            else -> null
        }

        fun other(lens: Lens): Lens = if (lens == FRONT) REAR else FRONT
    }
}

data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val centreX: Float get() = (left + right) / 2f
    val centreY: Float get() = (top + bottom) / 2f
}

data class FaceObservation(
    val trackingId: Int?,
    val box: Box,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val smilingProbability: Float?,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?
) {
    /** Coarse horizontal gaze bucket, useful for "where is the owner looking". */
    val yawBucket: String
        get() = when {
            yaw <= -SceneAnalysis.FACING_YAW_DEGREES -> "left"
            yaw >= SceneAnalysis.FACING_YAW_DEGREES -> "right"
            else -> "centre"
        }
}

data class ObjectObservation(
    val label: String,
    val confidence: Float,
    val box: Box,
    val trackingId: Int?
)

data class CodeObservation(val value: String, val format: String)

data class SceneSnapshot(
    val lens: Lens,
    val faces: List<FaceObservation>,
    val objects: List<ObjectObservation>,
    val text: String,
    val codes: List<CodeObservation>,
    val frameWidth: Int,
    val frameHeight: Int,
    val capturedAtMs: Long,
    val analyzedFrames: Long,
    val droppedFrames: Long
) {
    fun ageMs(nowMs: Long): Long = (nowMs - capturedAtMs).coerceAtLeast(0L)
}

/** Frame pacing: keeps analysis off the battery-draining path. Pure logic, no timers. */
class VisionCadence(targetFps: Int = 2, textEvery: Int = 3) {

    var targetFps: Int = targetFps.coerceIn(1, 15)
        private set
    var textEvery: Int = textEvery.coerceAtLeast(1)
        private set

    private var lastAdmittedMs: Long = Long.MIN_VALUE

    var seen: Long = 0L
        private set
    var analyzed: Long = 0L
        private set
    var dropped: Long = 0L
        private set

    val intervalMs: Long get() = (1000L / targetFps)

    fun admit(nowMs: Long): Boolean {
        seen += 1
        val due = lastAdmittedMs == Long.MIN_VALUE || nowMs - lastAdmittedMs >= intervalMs
        if (!due) {
            dropped += 1
            return false
        }
        lastAdmittedMs = nowMs
        analyzed += 1
        return true
    }

    /** Text recognition is the most expensive detector, so it runs on a slower cadence. */
    fun runText(): Boolean = textEvery <= 1 || analyzed % textEvery == 0L
}

object SceneAnalysis {

    const val FACING_YAW_DEGREES = 30f
    const val FACING_PITCH_DEGREES = 24f

    private val WORKSPACE = setOf("laptop", "keyboard", "mouse", "monitor", "book", "tv")
    private val FOOD = setOf("cup", "bottle", "bowl", "wine glass", "dining table", "fork", "knife", "spoon")
    private val OUTDOORS = setOf("car", "bus", "truck", "motorcycle", "bicycle", "traffic light", "stop sign", "bench")
    private val ELECTRONICS = setOf("cell phone", "remote", "tv", "laptop", "keyboard")

    fun dominantFace(snapshot: SceneSnapshot?): FaceObservation? =
        snapshot?.faces?.maxByOrNull { it.box.area }

    fun presence(snapshot: SceneSnapshot?): String = when {
        snapshot == null -> "unknown"
        snapshot.faces.isNotEmpty() -> "present"
        else -> "absent"
    }

    fun isFacingCamera(
        face: FaceObservation,
        maxYaw: Float = FACING_YAW_DEGREES,
        maxPitch: Float = FACING_PITCH_DEGREES
    ): Boolean = kotlin.math.abs(face.yaw) <= maxYaw && kotlin.math.abs(face.pitch) <= maxPitch

    /** "yes" | "no" | "unknown" — never a guess when no face is present. */
    fun facing(snapshot: SceneSnapshot?): String {
        val face = dominantFace(snapshot) ?: return "unknown"
        return if (isFacingCamera(face)) "yes" else "no"
    }

    /** Nine-region position of a face inside the frame. */
    fun position(face: FaceObservation, frameWidth: Int, frameHeight: Int): String {
        if (frameWidth <= 0 || frameHeight <= 0) return "unknown"
        val horizontal = when {
            face.box.centreX < frameWidth / 3f -> "left"
            face.box.centreX > frameWidth * 2f / 3f -> "right"
            else -> "centre"
        }
        val vertical = when {
            face.box.centreY < frameHeight / 3f -> "upper"
            face.box.centreY > frameHeight * 2f / 3f -> "lower"
            else -> "middle"
        }
        return if (horizontal == "centre" && vertical == "middle") "centre of the frame" else "$vertical $horizontal"
    }

    /** Coarse, honest scene hint derived from detected labels — not a scene classifier. */
    fun sceneHint(objects: List<ObjectObservation>): String? {
        if (objects.isEmpty()) return null
        val labels = objects.map { it.label.lowercase() }.toSet()
        return when {
            labels.any { it in OUTDOORS } -> "somewhere with a vehicle or street in view"
            labels.any { it in WORKSPACE } -> "a desk or workspace"
            labels.any { it in FOOD } -> "a table with food or drink nearby"
            labels.any { it in ELECTRONICS } -> "somewhere with electronics around"
            else -> {
                val top = objects.sortedByDescending { it.confidence }.take(2).joinToString(" and ") { it.label }
                "mostly $top"
            }
        }
    }

    /** 0 = identical, 1 = completely different. Used for visual change detection. */
    fun changeScore(previous: SceneSnapshot?, next: SceneSnapshot): Float {
        if (previous == null) return 1f
        val before = previous.objects.map { it.label.lowercase() }.toSet()
        val after = next.objects.map { it.label.lowercase() }.toSet()
        val union = (before + after).size
        val jaccard = if (union == 0) 1f else (before intersect after).size.toFloat() / union
        val textChanged = if (previous.text.trim() != next.text.trim()) 0.35f else 0f
        return (1f - jaccard + textChanged).coerceIn(0f, 1f)
    }

    fun trimText(text: String, maxChars: Int = 280): String {
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        if (collapsed.length <= maxChars) return collapsed
        return collapsed.take(maxChars - 3).trimEnd() + "..."
    }

    fun spokeObjects(snapshot: SceneSnapshot, limit: Int = 3): String =
        snapshot.objects
            .sortedByDescending { it.confidence }
            .map { it.label }
            .distinct()
            .take(limit)
            .joinToString(", ")

    /** Payload for the core's /vision/analyze endpoint. No image, only measurements. */
    fun evidence(snapshot: SceneSnapshot, nowMs: Long): Map<String, Any?> = mapOf(
        "camera" to snapshot.lens.wire,
        "faces" to snapshot.faces.size,
        "user_present" to snapshot.faces.isNotEmpty(),
        "facing_camera" to facing(snapshot),
        "head_yaw" to dominantFace(snapshot)?.let { it.yaw.toDouble() },
        "objects" to snapshot.objects
            .sortedByDescending { it.confidence }
            .take(12)
            .map { mapOf("label" to it.label, "confidence" to it.confidence.toDouble()) },
        "text" to trimText(snapshot.text, 300),
        "codes" to snapshot.codes.take(4).map { it.value },
        "scene" to sceneHint(snapshot.objects),
        "width" to snapshot.frameWidth,
        "height" to snapshot.frameHeight,
        "frame_age_ms" to snapshot.ageMs(nowMs)
    )

    /** Spoken status for "camera status" and for the startup confirmation. */
    fun spokenStatus(snapshot: SceneSnapshot?, lens: Lens, nowMs: Long): String {
        if (snapshot == null) return "The ${lens.spoken} camera is on but I have not analysed a frame yet."
        val stale = snapshot.ageMs(nowMs) > 10_000
        val parts = mutableListOf<String>()
        parts += "The ${snapshot.lens.spoken} camera is on."
        if (stale) parts += "The last frame I analysed is more than ten seconds old."
        val face = dominantFace(snapshot)
        if (face != null) {
            val where = position(face, snapshot.frameWidth, snapshot.frameHeight)
            parts += if (isFacingCamera(face)) {
                "I can see one person in the $where, facing the camera."
            } else {
                "I can see someone in the $where, looking away."
            }
        } else if (snapshot.lens == Lens.FRONT) {
            parts += "I do not see anyone in front of the camera."
        }
        val objects = spokeObjects(snapshot)
        if (objects.isNotEmpty()) parts += "I can make out $objects."
        if (snapshot.text.isNotBlank()) parts += "There is readable text in view."
        return parts.joinToString(" ")
    }
}
