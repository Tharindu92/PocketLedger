package com.billbook.app.ocr

/**
 * Recognises a business the user has saved before in what the scanner read, even when OCR
 * misread a word or only returned part of the name. The user's own spelling is returned.
 */
object KnownNames {

    private const val FULL_MATCH = 0.8       // whole strings at least 80 % alike
    private const val PIECE_COVERAGE = 0.5   // a piece must cover at least half of the saved name…
    private const val PIECE_ERRORS = 0.15    // …with at most 15 % of its letters wrong
    private const val MIN_PIECE_LENGTH = 8

    /**
     * @param candidates business names read from the bill, best first
     * @param known names already saved in the app
     * @return the saved name that matches a candidate best, or null when none is close enough
     */
    fun bestMatch(candidates: List<String>, known: List<String>): String? {
        var best: String? = null
        var bestScore = 0.0
        val savedNames = known.filter { it.isNotBlank() }.distinctBy { normalize(it) }
        for (candidate in candidates) {
            val c = normalize(candidate)
            if (c.length < 4) continue
            for (name in savedNames) {
                val k = normalize(name)
                if (k.isEmpty()) continue
                val score = similarity(c, k)
                if (score > bestScore) {
                    bestScore = score
                    best = name
                }
            }
        }
        return best
    }

    /** 0 when unrelated; otherwise how well [read] matches [saved] (1 = identical). */
    internal fun similarity(read: String, saved: String): Double {
        val full = 1.0 - levenshtein(read, saved).toDouble() / maxOf(read.length, saved.length)
        if (full >= FULL_MATCH) return full
        // Part of the name, e.g. "registration of persons" inside the saved full name.
        if (read.length >= MIN_PIECE_LENGTH && read.length < saved.length) {
            val coverage = read.length.toDouble() / saved.length
            val errors = substringDistance(read, saved).toDouble() / read.length
            if (coverage >= PIECE_COVERAGE && errors <= PIECE_ERRORS) return coverage * (1 - errors) * FULL_MATCH
        }
        return 0.0
    }

    internal fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Fewest edits to turn [needle] into some part of [haystack] (free start and end). */
    private fun substringDistance(needle: String, haystack: String): Int {
        var prev = IntArray(haystack.length + 1)   // row 0 is all zeros: the match may start anywhere
        for (i in 1..needle.length) {
            val cur = IntArray(haystack.length + 1)
            cur[0] = i
            for (j in 1..haystack.length) {
                val cost = if (needle[i - 1] == haystack[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev.min()                           // …and end anywhere
    }
}
