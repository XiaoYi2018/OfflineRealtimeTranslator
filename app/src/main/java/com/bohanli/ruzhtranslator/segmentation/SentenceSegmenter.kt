package com.bohanli.ruzhtranslator.segmentation

/**
 * Decides when to submit a text segment for translation.
 *
 * Rules (in priority order):
 *  1. Sentence-ending punctuation (.  。  ?  ？  !  ！) → submit up to and including the punct
 *  2. COMMA_THRESHOLD or more commas → submit everything before the last comma
 *  3. Pause fallback → only if buffer meets PAUSE_MIN_WORDS + PAUSE_MIN_CHARS
 *
 * All thresholds are constants here for easy tuning.
 */
class SentenceSegmenter {

    companion object {
        // Rule 2: number of commas that triggers a forced split
        const val COMMA_THRESHOLD = 2

        // Rule 3: minimum word count for pause-based submission
        const val PAUSE_MIN_WORDS = 5

        // Rule 3: minimum character count for pause-based submission
        const val PAUSE_MIN_CHARS = 15

        private val END_PUNCT_CHARS = charArrayOf('.', '。', '?', '？', '!', '！')
        private val COMMA_CHARS = charArrayOf(',', '，')
    }

    private val buffer = StringBuilder()

    /**
     * Appends [newText] to the buffer, then applies segmentation rules.
     *
     * @param newText Newly recognised (and recasepunc-processed) text to append.
     * @param isPause Whether Vosk just emitted a final result (speech pause detected).
     * @return A segment to submit for translation, or null if no rule triggered yet.
     *         The buffer is updated to hold only the remainder after the segment.
     */
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

        // Rule 3: pause fallback with minimum length guard
        if (isPause) {
            val wordCount = current.trim().split("\\s+".toRegex()).count { it.isNotEmpty() }
            if (wordCount >= PAUSE_MIN_WORDS && current.length >= PAUSE_MIN_CHARS) {
                buffer.clear()
                return current.trim()
            }
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
