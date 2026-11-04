package dev.jarvis.universal.wake

/**
 * Which phrases may activate JARVIS.
 */
enum class WakeMode(val label: String, val description: String) {
    HEY_ONLY(
        "\u201cHey JARVIS\u201d only",
        "Fewest false activations. Recommended while you tune sensitivity."
    ),
    DUAL(
        "\u201cHey JARVIS\u201d + \u201cJARVIS\u201d",
        "Both phrases work. \u201cJARVIS\u201d alone is matched more strictly and can be disabled at any time."
    );

    companion object {
        fun parse(raw: String?): WakeMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: HEY_ONLY
    }
}

/** A detected wake phrase. */
data class WakeHit(
    /** Canonical phrase that matched, e.g. "hey jarvis". */
    val phrase: String,
    /** What the recognizer actually heard (normalized), useful for false-activation review. */
    val heard: String,
    val confidence: Double
)

/**
 * Pure-Kotlin wake phrase matching, deliberately free of Android APIs so it can be unit tested
 * on the JVM. The speech engine produces a short transcript (Vosk is constrained to a tiny
 * grammar) and this object decides whether it is a wake phrase.
 *
 * Tuning notes:
 *  - "hey jarvis" is matched on word windows and on edit distance, so "hay jarvis" or a slurred
 *    "hey jervis" still trigger.
 *  - bare "jarvis" is matched on exactly one token with a stricter threshold, because a single
 *    common-ish word is the main source of false activations.
 */
object WakePhraseMatcher {

    const val HEY_JARVIS = "hey jarvis"
    const val JARVIS = "jarvis"

    data class Thresholds(val hey: Double, val bare: Double)

    /** Words people say before a wake phrase; stripped so they do not inflate the edit distance. */
    private val LEADING_FILLERS = setOf("ok", "okay", "um", "uh", "yo", "hey", "hay", "oi")

    fun normalize(raw: String): String =
        raw.lowercase()
            // Apostrophes are dropped rather than spaced so "what's" becomes "whats" instead of
            // two tokens; speech engines render contractions both ways.
            .filter { it != '\'' && it != '\u2019' }
            .map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val max = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / max.toDouble()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /**
     * Maps the owner-facing sensitivity slider (0 = strict, 100 = eager) onto match thresholds.
     * Bare "jarvis" stays stricter than "hey jarvis" at every sensitivity so the two-phrase mode
     * never becomes leakier than the single-phrase mode.
     */
    fun thresholdsForSensitivity(sensitivity: Int): Thresholds {
        val s = sensitivity.coerceIn(0, 100) / 100.0
        val hey = 0.92 - (0.24 * s)
        val bare = 0.99 - (0.17 * s)
        return Thresholds(hey = hey, bare = maxOf(bare, hey))
    }

    fun match(raw: String, mode: WakeMode, sensitivity: Int): WakeHit? =
        match(raw, mode, thresholdsForSensitivity(sensitivity))

    fun match(raw: String, mode: WakeMode, thresholds: Thresholds): WakeHit? {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return null

        val tokens = normalized.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        // 1) Exact two-word window anywhere in the utterance: highest confidence.
        //    This runs before the length guard because "hey jarvis, what's my battery" is a
        //    single breath the recognizer may return as one result.
        for (i in 0..tokens.size - 2) {
            if (tokens[i] == "hey" && tokens[i + 1] == JARVIS) {
                return WakeHit(HEY_JARVIS, normalized, 0.99)
            }
        }

        // Fuzzy matching is limited to short utterances; long dictation is never treated as a
        // wake phrase even if it is loosely similar.
        if (tokens.size > 6) return null

        // 2) Whole utterance against "hey jarvis", with fillers stripped.
        val strippedHey = tokens.dropWhile { it in LEADING_FILLERS && tokens.size > 2 }
        val heyCandidate = strippedHey.joinToString(" ")
        if (heyCandidate.isNotEmpty()) {
            val sim = similarity(heyCandidate, HEY_JARVIS)
            if (sim >= thresholds.hey) {
                // Confidence scaled so the threshold itself maps to ~0.6 and an exact hit to 1.0.
                val confidence = 0.6 + 0.4 * ((sim - thresholds.hey) / maxOf(0.0001, 1.0 - thresholds.hey))
                return WakeHit(HEY_JARVIS, normalized, confidence.coerceIn(0.0, 1.0))
            }
        }

        // 3) Bare "jarvis" is only allowed in DUAL mode and only as a standalone token.
        if (mode == WakeMode.DUAL) {
            val last = tokens.last()
            if (tokens.size <= 2) {
                val sim = similarity(last, JARVIS)
                if (sim >= thresholds.bare) {
                    val confidence = 0.55 + 0.35 * ((sim - thresholds.bare) / maxOf(0.0001, 1.0 - thresholds.bare))
                    return WakeHit(JARVIS, normalized, confidence.coerceIn(0.0, 1.0))
                }
            }
        }

        return null
    }
}
