package dev.jarvis.universal.control.actions

import android.Manifest
import dev.jarvis.universal.control.ActionRegistry
import dev.jarvis.universal.control.ActionResult
import dev.jarvis.universal.control.ActionSpec
import dev.jarvis.universal.control.ArgSpec
import dev.jarvis.universal.control.ArgType
import dev.jarvis.universal.control.Args
import dev.jarvis.universal.control.Danger
import dev.jarvis.universal.control.PermissionRequirement
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.vision.Lens
import dev.jarvis.universal.vision.SceneAnalysis
import dev.jarvis.universal.vision.SceneSnapshot
import dev.jarvis.universal.vision.VisionRepository
import dev.jarvis.universal.vision.VisionService
import org.json.JSONObject

/**
 * Camera actions. These are the only way a plan can reach the camera.
 *
 * A frame is never an argument: the model can ask JARVIS to look, and JARVIS decides what evidence
 * to gather. Answers always come from a real frame plus on-device detectors; when the configured
 * model cannot take images, the spoken answer is built from those detectors alone.
 */
object CameraActions {

    private const val TAG = "CameraActions"

    fun register(registry: ActionRegistry, deps: ActionDeps) {
        val cameraPermission = listOf(PermissionRequirement(Manifest.permission.CAMERA, "camera"))

        registry.register(
            ActionSpec(
                id = "camera_start",
                description = "Turn a camera on and start looking.",
                args = listOf(
                    ArgSpec(
                        "mode", ArgType.ENUM, required = true,
                        description = "Which camera to use.",
                        enumValues = listOf("front", "rear", "switch")
                    )
                ),
                permissions = cameraPermission,
                danger = Danger.SENSITIVE
            ) { args ->
                val mode = args.string("mode") ?: "rear"
                val lens = when (mode.lowercase()) {
                    "front" -> Lens.FRONT
                    "switch" -> VisionRepository.activeLens?.let(Lens::other) ?: Lens.REAR
                    else -> Lens.REAR
                }
                startCamera(deps, lens)
            }
        )

        registry.register(
            ActionSpec(
                id = "camera_stop",
                description = "Turn the camera off immediately.",
                danger = Danger.SAFE
            ) {
                VisionService.stop(deps.app)
                ActionResult.ok(
                    "The camera is off.",
                    mapOf("camera" to null, "active" to false)
                )
            }
        )

        registry.register(
            ActionSpec(
                id = "camera_status",
                description = "Report which camera is active and whether the owner is visible.",
                danger = Danger.SAFE
            ) {
                val lens = VisionRepository.activeLens
                val snapshot = VisionRepository.snapshot
                val now = System.currentTimeMillis()
                val spoken = if (lens == null) {
                    "The camera is off. Say activate the rear camera when you want me to look."
                } else {
                    SceneAnalysis.spokenStatus(snapshot, lens, now)
                }
                ActionResult.ok(
                    spoken,
                    mapOf(
                        "active" to VisionRepository.isActive,
                        "camera" to lens?.wire,
                        "faces" to (snapshot?.faces?.size ?: 0),
                        "objects" to (snapshot?.objects?.size ?: 0),
                        "presence" to SceneAnalysis.presence(snapshot),
                        "facing" to SceneAnalysis.facing(snapshot),
                        "error" to VisionRepository.lastError
                    )
                )
            }
        )

        registry.register(
            ActionSpec(
                id = "camera_describe",
                description = "Look through the camera and describe what is actually visible.",
                args = listOf(
                    ArgSpec("question", ArgType.STRING, description = "What the owner asked.", maxLength = 300),
                    ArgSpec(
                        "camera", ArgType.ENUM,
                        description = "Which camera to look through.",
                        enumValues = listOf("front", "rear", "current")
                    )
                ),
                permissions = cameraPermission,
                danger = Danger.SENSITIVE
            ) { args ->
                describe(deps, args, defaultQuestion = "What am I looking at?")
            }
        )

        registry.register(
            ActionSpec(
                id = "camera_read_text",
                description = "Read text, documents or a screen through the camera.",
                args = listOf(
                    ArgSpec(
                        "camera", ArgType.ENUM,
                        description = "Which camera to use.",
                        enumValues = listOf("front", "rear", "current")
                    )
                ),
                permissions = cameraPermission,
                danger = Danger.SENSITIVE
            ) { args ->
                readText(deps, args)
            }
        )

        registry.register(
            ActionSpec(
                id = "camera_identify",
                description = "Identify the object the camera is pointed at.",
                args = listOf(
                    ArgSpec("question", ArgType.STRING, description = "What the owner asked.", maxLength = 300),
                    ArgSpec(
                        "camera", ArgType.ENUM,
                        description = "Which camera to use.",
                        enumValues = listOf("front", "rear", "current")
                    )
                ),
                permissions = cameraPermission,
                danger = Danger.SENSITIVE
            ) { args ->
                describe(deps, args, defaultQuestion = "Identify this object.")
            }
        )
    }

    // ------------------------------------------------------------------ actions

    private suspend fun startCamera(deps: ActionDeps, lens: Lens): ActionResult {
        if (!deps.permissions.isGranted(Manifest.permission.CAMERA)) {
            return ActionResult.fail(
                "camera_permission_required",
                "I need camera permission before I can look. Open JARVIS and grant camera access."
            )
        }

        val refusal = VisionService.start(deps.app, lens)
        if (refusal != null) {
            Logx.w(TAG, "camera start refused: $refusal")
            val spoken = when (refusal) {
                "camera_background_blocked" ->
                    "Android only lets me start the camera while JARVIS is on screen. Open the app " +
                        "and ask me again."
                else -> "The camera did not start. $refusal"
            }
            return ActionResult.fail(refusal, spoken)
        }

        val snapshot = VisionRepository.awaitSnapshot(lens, maxAgeMs = 1_500, timeoutMs = 3_000)
        val now = System.currentTimeMillis()
        val spoken = if (snapshot != null) {
            SceneAnalysis.spokenStatus(snapshot, lens, now)
        } else {
            val error = VisionRepository.lastError
            if (error != null) {
                return ActionResult.fail(
                    error,
                    "The ${lens.spoken} camera could not start. $error"
                )
            }
            "The ${lens.spoken} camera is on and I am analysing what I can see."
        }
        return ActionResult.ok(
            spoken,
            mapOf(
                "camera" to lens.wire,
                "active" to true,
                "faces" to (snapshot?.faces?.size ?: 0),
                "objects" to (snapshot?.objects?.size ?: 0)
            )
        )
    }

    private suspend fun describe(
        deps: ActionDeps,
        args: Args,
        defaultQuestion: String
    ): ActionResult {
        val lens = resolveLens(args.string("camera"))
        val question = args.string("question")?.trim().orEmpty().ifBlank { defaultQuestion }

        if (!VisionRepository.isActive || VisionRepository.activeLens != lens) {
            val started = startCamera(deps, lens)
            if (!started.ok) return started
        }

        // If the camera was just opened, give the detectors a moment to report something real.
        var snapshot = VisionRepository.awaitSnapshot(lens, maxAgeMs = 3_000, timeoutMs = 4_000)
        if (snapshot == null) {
            val error = VisionRepository.lastError
            return ActionResult.fail(
                error ?: "camera_no_frames",
                "The ${lens.spoken} camera is on but I have not received an analysable frame yet."
            )
        }

        val now = System.currentTimeMillis()
        val facts = SceneAnalysis.evidence(snapshot, now)
        var answer: String? = null
        var source = "local-facts"
        var remoteDetail: String? = null

        if (deps.prefs.cameraRemoteVision) {
            val frame = VisionRepository.captureFrame()
            if (frame == null) {
                remoteDetail = "no_frame_captured"
            } else {
                val response = deps.core.analyzeImage(frame, question, lens.wire, JSONObject(facts))
                if (response != null) {
                    answer = response.optString("answer").ifBlank { null }
                    source = response.optString("source", "local-facts")
                    if (source == "local-facts") {
                        remoteDetail = response.optString("detail").ifBlank { null }
                    }
                } else {
                    remoteDetail = "core_unreachable"
                }
            }
        } else {
            remoteDetail = "remote_vision_disabled"
        }

        val spoken = answer ?: localAnswer(snapshot, question)
        return ActionResult.ok(
            spoken,
            mapOf(
                "camera" to lens.wire,
                "source" to source,
                "remote_detail" to remoteDetail,
                "faces" to snapshot.faces.size,
                "objects" to snapshot.objects.take(5).map { it.label },
                "text" to SceneAnalysis.trimText(snapshot.text, 200),
                "frame_age_ms" to snapshot.ageMs(now)
            )
        )
    }

    private suspend fun readText(deps: ActionDeps, args: Args): ActionResult {
        val lens = resolveLens(args.string("camera"))

        if (!VisionRepository.isActive || VisionRepository.activeLens != lens) {
            val started = startCamera(deps, lens)
            if (!started.ok) return started
        }

        // OCR runs every few frames, so wait for a pass that actually produced text.
        var snapshot = VisionRepository.awaitSnapshot(lens, maxAgeMs = 4_000, timeoutMs = 4_000)
        var attempts = 0
        while ((snapshot == null || snapshot.text.isBlank()) && attempts < 3) {
            attempts += 1
            kotlinx.coroutines.delay(700)
            snapshot = VisionRepository.snapshot
        }

        if (snapshot == null) {
            return ActionResult.fail(
                "camera_no_frames",
                "I could not get a usable frame from the ${lens.spoken} camera."
            )
        }

        if (snapshot.text.isBlank()) {
            return ActionResult.ok(
                "I do not see readable text. Hold the phone steady and point the ${lens.spoken} " +
                    "camera at the text.",
                mapOf("camera" to lens.wire, "text" to "", "found" to false)
            )
        }

        val text = SceneAnalysis.trimText(snapshot.text, 400)
        return ActionResult.ok(
            "It reads: $text",
            mapOf(
                "camera" to lens.wire,
                "text" to text,
                "found" to true,
                "codes" to snapshot.codes.map { it.value }.take(3)
            )
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun resolveLens(requested: String?): Lens {
        return when (requested?.lowercase()) {
            "front" -> Lens.FRONT
            "rear" -> Lens.REAR
            else -> VisionRepository.activeLens ?: Lens.REAR
        }
    }

    /** Spoken answer grounded in measurements only. */
    private fun localAnswer(snapshot: SceneSnapshot, question: String): String {
        val parts = mutableListOf<String>()
        val face = SceneAnalysis.dominantFace(snapshot)
        if (face != null) {
            val where = SceneAnalysis.position(face, snapshot.frameWidth, snapshot.frameHeight)
            parts += if (SceneAnalysis.isFacingCamera(face)) {
                "I can see someone in the $where, facing the camera."
            } else {
                "I can see someone in the $where, looking away."
            }
        } else if (snapshot.lens == Lens.FRONT) {
            parts += "I do not see anyone in front of the camera."
        }

        val objects = snapshot.objects.sortedByDescending { it.confidence }.map { it.label }.distinct()
        if (objects.isNotEmpty()) {
            parts += "I can make out " + objects.take(4).joinToString(", ") + "."
        }
        if (snapshot.text.isNotBlank()) {
            parts += "There is readable text in view."
        }
        snapshot.codes.firstOrNull()?.let { parts += "There is a code containing ${it.value.take(40)}." }

        if (parts.isEmpty()) {
            return "I cannot make anything out clearly. Point the ${snapshot.lens.spoken} camera at " +
                "what you want me to look at."
        }
        if (question.contains("who", ignoreCase = true) && face != null) {
            parts += "I can tell someone is there, but I do not identify people from the camera."
        }
        return parts.joinToString(" ")
    }
}
