package com.bohanli.ruzhtranslator.translation

/**
 * Output-language verification for RU -> ZH translation.
 *
 * Two failure modes are detected:
 *   1. Low CJK-Han ratio  -> likely drift to Russian / English.
 *   2. Presence of Japanese kana (hiragana / katakana) -> drift to Japanese.
 *      Needed because Japanese kanji shares the U+4E00..U+9FFF block with
 *      Chinese Han, so a pure CJK-ratio check cannot distinguish them.
 */
object DriftDetector {
    /** Fraction of non-whitespace characters that fall in CJK Unified Ideographs (U+4E00..U+9FFF). */
    fun cjkRatio(s: String): Double {
        if (s.isEmpty()) return 0.0
        var cjk = 0
        var total = 0
        for (ch in s) {
            if (ch.isWhitespace()) continue
            total++
            val code = ch.code
            if (code in 0x4E00..0x9FFF) cjk++
        }
        return if (total == 0) 0.0 else cjk.toDouble() / total
    }

    /** True if the string contains any hiragana or katakana character. */
    fun hasJapaneseKana(s: String): Boolean {
        for (ch in s) {
            val code = ch.code
            if (code in 0x3040..0x30FF) return true   // hiragana + katakana
            if (code in 0x31F0..0x31FF) return true   // katakana phonetic extensions
        }
        return false
    }

    /** True when the string is a non-empty translation whose output language is not Chinese. */
    fun isLikelyDrift(s: String, minRatio: Double = 0.4): Boolean {
        if (s.isBlank()) return false
        // Skip GemmaTranslator error markers: "[翻译引擎未加载]", "[翻译异常: ...]"
        if (s.startsWith("[")) return false
        if (hasJapaneseKana(s)) return true
        return cjkRatio(s) < minRatio
    }
}
