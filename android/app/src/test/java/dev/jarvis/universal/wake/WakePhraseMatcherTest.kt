package dev.jarvis.universal.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseMatcherTest {

    @Test
    fun normalizesCaseAndPunctuation() {
        assertEquals("hey jarvis", WakePhraseMatcher.normalize("  Hey,  JARVIS!  "))
        assertEquals("jarvis whats my battery", WakePhraseMatcher.normalize("JARVIS - what's my battery?"))
        assertEquals("", WakePhraseMatcher.normalize("   "))
    }

    @Test
    fun levenshteinIsCorrect() {
        assertEquals(0, WakePhraseMatcher.levenshtein("jarvis", "jarvis"))
        assertEquals(1, WakePhraseMatcher.levenshtein("jarvis", "jervis"))
        assertEquals(3, WakePhraseMatcher.levenshtein("kitten", "sitting"))
    }

    @Test
    fun exactHeyJarvisMatchesInBothModes() {
        for (mode in WakeMode.entries) {
            val hit = WakePhraseMatcher.match("Hey JARVIS", mode, sensitivity = 60)
            assertNotNull("expected a hit in $mode", hit)
            assertEquals(WakePhraseMatcher.HEY_JARVIS, hit!!.phrase)
            assertTrue(hit.confidence > 0.9)
        }
    }

    @Test
    fun wakePhraseInsideLongerUtteranceStillMatches() {
        val hit = WakePhraseMatcher.match("hey jarvis what is my battery level", WakeMode.HEY_ONLY, 60)
        assertNotNull(hit)
        assertEquals(WakePhraseMatcher.HEY_JARVIS, hit!!.phrase)
    }

    @Test
    fun bareJarvisRejectedInHeyOnlyMode() {
        assertNull(WakePhraseMatcher.match("jarvis", WakeMode.HEY_ONLY, sensitivity = 100))
    }

    @Test
    fun bareJarvisMatchesInDualMode() {
        val hit = WakePhraseMatcher.match("Jarvis", WakeMode.DUAL, sensitivity = 60)
        assertNotNull(hit)
        assertEquals(WakePhraseMatcher.JARVIS, hit!!.phrase)
    }

    @Test
    fun nearMissWakePhrasesAreTolerated() {
        // Common speech-to-text renderings of the wake phrase.
        listOf("hay jarvis", "hey jervis", "hey jarvas").forEach { phrase ->
            val hit = WakePhraseMatcher.match(phrase, WakeMode.DUAL, sensitivity = 70)
            assertNotNull("expected '$phrase' to match", hit)
        }
    }

    @Test
    fun unrelatedSpeechIsRejected() {
        listOf(
            "harvest the field",
            "javascript is hard",
            "how are you today",
            "turn on the lights",
            "jarvis please help me with this long sentence that goes on"
        ).forEach { phrase ->
            assertNull("'$phrase' must not wake JARVIS", WakePhraseMatcher.match(phrase, WakeMode.DUAL, 60))
        }
    }

    @Test
    fun sensitivityWidensThresholdsMonotonically() {
        val strict = WakePhraseMatcher.thresholdsForSensitivity(0)
        val eager = WakePhraseMatcher.thresholdsForSensitivity(100)
        assertTrue(strict.hey > eager.hey)
        assertTrue(strict.bare > eager.bare)
        assertTrue(eager.bare >= eager.hey)
        assertTrue(strict.bare >= strict.hey)
    }

    @Test
    fun emptyInputNeverMatches() {
        assertNull(WakePhraseMatcher.match("", WakeMode.DUAL, 100))
        assertNull(WakePhraseMatcher.match("   ", WakeMode.DUAL, 100))
    }
}
