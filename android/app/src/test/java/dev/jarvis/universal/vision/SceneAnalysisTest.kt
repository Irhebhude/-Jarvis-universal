package dev.jarvis.universal.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneAnalysisTest {

    private fun face(
        yaw: Float = 0f,
        pitch: Float = 0f,
        left: Float = 280f,
        top: Float = 160f,
        size: Float = 120f
    ) = FaceObservation(
        trackingId = 1,
        box = Box(left, top, left + size, top + size),
        yaw = yaw,
        pitch = pitch,
        roll = 0f,
        smilingProbability = null,
        leftEyeOpenProbability = null,
        rightEyeOpenProbability = null
    )

    private fun snapshot(
        lens: Lens = Lens.REAR,
        faces: List<FaceObservation> = emptyList(),
        objects: List<ObjectObservation> = emptyList(),
        text: String = "",
        codes: List<CodeObservation> = emptyList(),
        capturedAtMs: Long = System.currentTimeMillis()
    ) = SceneSnapshot(
        lens = lens,
        faces = faces,
        objects = objects,
        text = text,
        codes = codes,
        frameWidth = 640,
        frameHeight = 480,
        capturedAtMs = capturedAtMs,
        analyzedFrames = 10,
        droppedFrames = 3
    )

    private fun obj(label: String, confidence: Float = 0.8f) =
        ObjectObservation(label, confidence, Box(0f, 0f, 100f, 100f), null)

    // ------------------------------------------------------------- presence

    @Test
    fun presence_is_unknown_without_a_snapshot() {
        assertEquals("unknown", SceneAnalysis.presence(null))
    }

    @Test
    fun presence_is_absent_when_no_faces_are_detected() {
        assertEquals("absent", SceneAnalysis.presence(snapshot()))
    }

    @Test
    fun presence_is_present_with_a_detected_face() {
        assertEquals("present", SceneAnalysis.presence(snapshot(faces = listOf(face()))))
    }

    // ------------------------------------------------------------- orientation

    @Test
    fun facing_camera_is_yes_within_the_yaw_threshold() {
        val s = snapshot(faces = listOf(face(yaw = 8f, pitch = 4f)))
        assertEquals("yes", SceneAnalysis.facing(s))
    }

    @Test
    fun facing_camera_is_no_when_the_head_is_turned_away() {
        val s = snapshot(faces = listOf(face(yaw = 55f)))
        assertEquals("no", SceneAnalysis.facing(s))
    }

    @Test
    fun facing_camera_is_no_when_the_head_is_tilted_far_down() {
        val s = snapshot(faces = listOf(face(pitch = 40f)))
        assertEquals("no", SceneAnalysis.facing(s))
    }

    @Test
    fun facing_camera_is_unknown_without_a_face() {
        assertEquals("unknown", SceneAnalysis.facing(snapshot()))
    }

    @Test
    fun largest_face_wins_when_several_people_are_visible() {
        val small = face(size = 40f)
        val large = face(size = 180f)
        val dominant = SceneAnalysis.dominantFace(snapshot(faces = listOf(small, large)))
        assertEquals(180f, dominant!!.box.width, 0.01f)
    }

    // ------------------------------------------------------------- position

    @Test
    fun face_position_is_reported_in_nine_regions() {
        assertEquals("centre of the frame", SceneAnalysis.position(face(left = 280f, top = 200f), 640, 480))
        assertTrue(SceneAnalysis.position(face(left = 20f, top = 20f), 640, 480).contains("upper left"))
        assertTrue(SceneAnalysis.position(face(left = 500f, top = 360f), 640, 480).contains("lower right"))
    }

    @Test
    fun position_falls_back_to_unknown_for_a_zero_sized_frame() {
        assertEquals("unknown", SceneAnalysis.position(face(), 0, 0))
    }

    @Test
    fun yaw_bucket_describes_gaze_direction() {
        assertEquals("left", face(yaw = -70f).yawBucket)
        assertEquals("right", face(yaw = 70f).yawBucket)
        assertEquals("centre", face(yaw = 2f).yawBucket)
    }

    // ------------------------------------------------------------- scene

    @Test
    fun scene_hint_uses_detected_labels_not_a_classifier() {
        assertEquals("a desk or workspace", SceneAnalysis.sceneHint(listOf(obj("laptop"))))
        assertEquals(
            "somewhere with a vehicle or street in view",
            SceneAnalysis.sceneHint(listOf(obj("car")))
        )
        assertNull(SceneAnalysis.sceneHint(emptyList()))
    }

    @Test
    fun change_score_rises_when_the_scene_changes() {
        val first = snapshot(objects = listOf(obj("laptop")))
        val same = snapshot(objects = listOf(obj("laptop")))
        val different = snapshot(objects = listOf(obj("bicycle")))
        assertTrue("identical scenes should score low", SceneAnalysis.changeScore(first, same) < 0.2f)
        assertTrue("different scenes should score high", SceneAnalysis.changeScore(first, different) > 0.7f)
        assertEquals(1f, SceneAnalysis.changeScore(null, first), 0.001f)
    }

    // ------------------------------------------------------------- evidence

    @Test
    fun evidence_payload_matches_the_core_contract() {
        val now = System.currentTimeMillis()
        val s = snapshot(
            lens = Lens.FRONT,
            faces = listOf(face(yaw = 5f)),
            objects = listOf(obj("cup", 0.6f), obj("laptop", 0.9f)),
            text = "Invoice 4471",
            codes = listOf(CodeObservation("https://example.com", "qr")),
            capturedAtMs = now - 500
        )
        val facts = SceneAnalysis.evidence(s, now)
        assertEquals("front", facts["camera"])
        assertEquals(1, facts["faces"])
        assertEquals(true, facts["user_present"])
        assertEquals("yes", facts["facing_camera"])
        assertEquals("Invoice 4471", facts["text"])
        assertEquals(listOf("https://example.com"), facts["codes"])
        @Suppress("UNCHECKED_CAST")
        val objects = facts["objects"] as List<Map<String, Any?>>
        assertEquals("laptop", objects.first()["label"])
        assertTrue((facts["frame_age_ms"] as Long) >= 500L)
    }

    @Test
    fun evidence_never_contains_image_bytes_or_identity() {
        val facts = SceneAnalysis.evidence(snapshot(faces = listOf(face())), System.currentTimeMillis())
        assertFalse(facts.containsKey("image"))
        assertFalse(facts.containsKey("identity"))
        assertFalse(facts.containsKey("name"))
    }

    // ------------------------------------------------------------- speech

    @Test
    fun spoken_status_describes_the_front_camera_honestly() {
        val spoken = SceneAnalysis.spokenStatus(snapshot(lens = Lens.FRONT), Lens.FRONT, System.currentTimeMillis())
        assertTrue(spoken.contains("front camera is on"))
        assertTrue(spoken.contains("do not see anyone"))
    }

    @Test
    fun spoken_status_warns_about_a_stale_frame() {
        val old = snapshot(capturedAtMs = System.currentTimeMillis() - 30_000)
        val spoken = SceneAnalysis.spokenStatus(old, Lens.REAR, System.currentTimeMillis())
        assertTrue(spoken.contains("ten seconds old"))
    }

    @Test
    fun text_is_trimmed_for_display() {
        val long = "word ".repeat(200)
        val trimmed = SceneAnalysis.trimText(long, 40)
        assertTrue(trimmed.length <= 40)
        assertTrue(trimmed.endsWith("..."))
        assertEquals("a b", SceneAnalysis.trimText("  a \n b  "))
    }

    // ------------------------------------------------------------- cadence

    @Test
    fun cadence_admits_at_the_target_rate_and_counts_drops() {
        val cadence = VisionCadence(targetFps = 2, textEvery = 3)
        assertEquals(500L, cadence.intervalMs)
        assertTrue(cadence.admit(1_000L))
        assertFalse(cadence.admit(1_100L))
        assertFalse(cadence.admit(1_300L))
        assertTrue(cadence.admit(1_600L))
        assertEquals(2L, cadence.analyzed)
        assertEquals(2L, cadence.dropped)
        assertEquals(4L, cadence.seen)
    }

    @Test
    fun text_recognition_runs_on_a_slower_cadence() {
        val cadence = VisionCadence(targetFps = 2, textEvery = 3)
        var ticks: Long = 0
        val textRuns = mutableListOf<Boolean>()
        for (i in 0 until 6) {
            cadence.admit(ticks)
            ticks += 600
            textRuns += cadence.runText()
        }
        assertEquals(2, textRuns.count { it })
    }

    @Test
    fun cadence_clamps_out_of_range_settings() {
        val cadence = VisionCadence(targetFps = 0, textEvery = 0)
        assertEquals(1, cadence.targetFps)
        assertEquals(1, cadence.textEvery)
    }

    @Test
    fun lens_parsing_accepts_speech_friendly_aliases() {
        assertEquals(Lens.FRONT, Lens.parse("front"))
        assertEquals(Lens.FRONT, Lens.parse("Selfie"))
        assertEquals(Lens.REAR, Lens.parse("back"))
        assertEquals(Lens.REAR, Lens.parse("environment"))
        assertNull(Lens.parse("upside-down"))
        assertEquals(Lens.REAR, Lens.other(Lens.FRONT))
    }
}
