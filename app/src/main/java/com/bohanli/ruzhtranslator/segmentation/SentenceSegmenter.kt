package com.bohanli.ruzhtranslator.segmentation

/**
 * Decides when to submit a text segment for translation.
 *
 * Rules (in priority order):
 *  1. Sentence-ending punctuation (.  。  ?  ？  !  ！) → submit up to and including the punct
 *  2. COMMA_THRESHOLD or more commas → submit everything before the last comma
 *  3. Russian conjunction split → if buffer >= CONJ_MIN_WORDS, split before last conjunction
 *  4. Pause fallback → only if buffer meets PAUSE_MIN_WORDS + PAUSE_MIN_CHARS
 *  5. Force split at MAX_WORDS
 */
class SentenceSegmenter {

    companion object {
        const val COMMA_THRESHOLD = 2
        const val PAUSE_MIN_WORDS = 3
        const val PAUSE_MIN_CHARS = 10
        const val MAX_WORDS = 10

        // Rule 3: minimum words before we allow conjunction-based split
        const val CONJ_MIN_WORDS = 4

        private val END_PUNCT_CHARS = charArrayOf('.', '。', '?', '？', '!', '！')
        private val COMMA_CHARS = charArrayOf(',', '，')

        // Common Russian conjunctions / connectors (lowercase)
        // Split BEFORE these words when buffer is long enough
        private val RU_CONJUNCTIONS = setOf(
            "и", "а", "но", "или", "что", "потому", "когда",
            "если", "чтобы", "также", "потом", "затем", "поэтому",
            "который", "которая", "которое", "которые",
            "где", "как", "так", "тоже", "ведь", "хотя",
            "однако", "либо", "причём", "притом", "зато",
            "то", "ещё", "уже", "тогда", "после"
        )
    }

    private val buffer = StringBuilder()

    fun process(newText: String, isPause: Boolean): String? {
        if (newText.isNotBlank()) {
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(newText.trim())
        }

        val current = buffer.toString()

        // Rule 1: sentence-ending punctuation
        val endIdx = current.lastIndexOfAny(END_PUNCT_CHARS)
        if (endIdx >= 0) {
            val segment = current.substring(0, endIdx + 1).trim()
            val remainder = current.substring(endIdx + 1).trim()
            buffer.clear()
            if (remainder.isNotEmpty()) buffer.append(remainder)
            if (segment.isNotBlank()) return segment
        }

        // Rule 2: multiple commas → split before last comma
        val commaCount = current.count { it in COMMA_CHARS }
        if (commaCount >= COMMA_THRESHOLD) {
            val lastCommaIdx = current.lastIndexOfAny(COMMA_CHARS)
            if (lastCommaIdx > 0) {
                val segment = current.substring(0, lastCommaIdx).trim()
                val remainder = current.substring(lastCommaIdx + 1).trim()
                buffer.clear()
                if (remainder.isNotEmpty()) buffer.append(remainder)
                if (segment.isNotBlank()) return segment
            }
        }

        // Rule 3: Russian conjunction split (when buffer has enough words)
        val words = current.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.size >= CONJ_MIN_WORDS) {
            // Find the LAST conjunction at position >= CONJ_MIN_WORDS-1
            // so we produce a segment of at least CONJ_MIN_WORDS words
            var splitIdx = -1
            for (i in (CONJ_MIN_WORDS - 1) until words.size) {
                if (words[i].lowercase() in RU_CONJUNCTIONS) {
                    splitIdx = i
                }
            }
            if (splitIdx > 0) {
                val segment = words.take(splitIdx).joinToString(" ")
                val remainder = words.drop(splitIdx).joinToString(" ")
                buffer.clear()
                if (remainder.isNotEmpty()) buffer.append(remainder)
                if (segment.isNotBlank()) return segment
            }
        }

        // Rule 4: pause fallback with minimum length guard
        if (isPause) {
            val wordCount = words.size
            if (wordCount >= PAUSE_MIN_WORDS && current.length >= PAUSE_MIN_CHARS) {
                buffer.clear()
                return current.trim()
            }
        }

        // Rule 5: force split if buffer is too long
        if (words.size >= MAX_WORDS) {
            val segment = words.take(MAX_WORDS).joinToString(" ")
            val remainder = words.drop(MAX_WORDS).joinToString(" ")
            buffer.clear()
            if (remainder.isNotEmpty()) buffer.append(remainder)
            return segment
        }

        return null
    }

    /** Force-flush everything remaining in the buffer (e.g. on stop). */
    fun flush(): String? {
        val text = buffer.toString().trim()
        buffer.clear()
        return if (text.isNotBlank()) text else null
    }

    /** Current uncommitted buffer content, for display in the Russian text area. */
    fun getCurrentBuffer(): String = buffer.toString()

    /** Clear the buffer without returning anything. */
    fun reset() = buffer.clear()
}
