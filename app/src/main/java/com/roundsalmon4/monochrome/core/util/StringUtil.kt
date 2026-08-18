package com.roundsalmon4.monochrome.core.util

object StringUtil {

    private val PARENTHETICAL_RE = Regex("[\\(\\[][^)\\]]*[)\\]]")
    private val FEATURED_ARTIST_RE = Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*")
    private val WHITESPACE_RE = Regex("\\s+")

    fun normalizeTitle(raw: String): String {
        var s = raw
        s = PARENTHETICAL_RE.replace(s, "")
        s = FEATURED_ARTIST_RE.replace(s, "")
        s = s.lowercase().trim()
        s = WHITESPACE_RE.replace(s, " ")
        return s
    }

    fun titlesMatch(requested: String, result: String, threshold: Double = 0.6): Boolean {
        val a = normalizeTitle(requested)
        val b = normalizeTitle(result)
        if (a.isEmpty() || b.isEmpty()) return true
        if (a == b) return true
        if (a.contains(b) || b.contains(a)) return true
        return similarity(a, b) >= threshold
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = maxOf(a.length, b.length)
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val temp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev
                else minOf(prev, dp[j], dp[j - 1]) + 1
                prev = temp
            }
        }
        return dp[n]
    }
}
