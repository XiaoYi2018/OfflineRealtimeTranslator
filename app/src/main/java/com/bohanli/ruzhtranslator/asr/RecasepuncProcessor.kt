package com.bohanli.ruzhtranslator.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.LongBuffer

/**
 * Punctuation and capitalisation restoration using the vosk-recasepunc-ru-0.22 ONNX model.
 *
 * Expected model directory contents:
 *   model.onnx   – BERT-based sequence tagger (case + punct per token)
 *   vocab.txt    – WordPiece vocabulary, one token per line (standard BERT format)
 *
 * Model output assumption (benob/recasepunc format):
 *   Two output tensors, each [1, seq_len, n_labels] float32
 *     output 0 → case  labels: LOWER=0, CAPITALIZE=1, UPPER=2, OTHER=3
 *     output 1 → punct labels: O=0, COMMA=1, PERIOD=2, QUESTION=3, EXCLAMATION=4, COLON=5
 *
 *   Fallback: if single output, first n_case=4 classes = case, next n_punct=6 = punct.
 *
 * Silently returns the original text on any error, so ASR keeps working without this model.
 */
class RecasepuncProcessor(modelDir: File?) : AutoCloseable {

    companion object {
        private const val TAG = "Recasepunc"
        private val PUNCT_CHARS = arrayOf("", ",", ".", "?", "!")
        private const val N_CASE_LABELS = 4
        private const val N_PUNCT_LABELS = 5   // must match PUNCT_CHARS.size

        // Standard multilingual-BERT special token IDs
        private const val CLS_ID = 101L
        private const val SEP_ID = 102L
        private const val UNK_ID = 100L
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private val vocab = mutableMapOf<String, Long>()   // token → id

    init {
        if (modelDir != null && modelDir.isDirectory) {
            try {
                loadVocab(File(modelDir, "vocab.txt"))
                loadModel(File(modelDir, "model.onnx"))
            } catch (e: Exception) {
                Log.w(TAG, "Init failed (recasepunc will be skipped): ${e.message}")
            }
        }
    }

    private fun loadVocab(file: File) {
        if (!file.exists()) { Log.w(TAG, "vocab.txt not found at ${file.absolutePath}"); return }
        file.readLines().forEachIndexed { idx, token -> vocab[token] = idx.toLong() }
        Log.i(TAG, "Loaded vocab: ${vocab.size} entries")
    }

    private fun loadModel(file: File) {
        if (!file.exists()) { Log.w(TAG, "model.onnx not found at ${file.absolutePath}"); return }
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        session = env.createSession(file.absolutePath, opts)
        Log.i(TAG, "Recasepunc session loaded. Inputs=${session!!.inputNames} Outputs=${session!!.outputNames}")
    }

    fun isAvailable() = session != null && vocab.isNotEmpty()

    /** Returns the punctuated/capitalised text, or [text] unchanged on any error. */
    fun process(text: String): String {
        if (!isAvailable() || text.isBlank()) return text
        return try {
            runInference(text)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            text
        }
    }

    private fun runInference(text: String): String {
        val sess = session ?: return text
        val words = text.lowercase().trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.isEmpty()) return text

        // Build token ID sequence with CLS/SEP framing
        val tokenIds = mutableListOf(CLS_ID)
        val wordFirstTok = IntArray(words.size)   // index of each word's first subword token

        for ((i, word) in words.withIndex()) {
            wordFirstTok[i] = tokenIds.size
            tokenIds.addAll(wordpieceTokenize(word))
        }
        tokenIds.add(SEP_ID)

        val seqLen = tokenIds.size.toLong()
        val idArr = tokenIds.toLongArray()
        val maskArr = LongArray(tokenIds.size) { 1L }

        val idTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(idArr), longArrayOf(1, seqLen))
        val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(maskArr), longArrayOf(1, seqLen))

        // Build input map using output names heuristic
        val inputNames = sess.inputNames.toList()
        val inputs = mutableMapOf<String, OnnxTensor>()
        for ((i, name) in inputNames.withIndex()) {
            when {
                "input_id" in name.lowercase() -> inputs[name] = idTensor
                "attention" in name.lowercase() || "mask" in name.lowercase() -> inputs[name] = maskTensor
                i == 0 && inputs.size == 0 -> inputs[name] = idTensor
                i == 1 && inputs.size == 1 -> inputs[name] = maskTensor
            }
        }

        if (inputs.isEmpty()) {
            idTensor.close(); maskTensor.close()
            return text
        }

        val result = sess.run(inputs)
        val sb = StringBuilder()

        try {
            val outputNames = sess.outputNames.toList()
            val numOutputs = outputNames.size

            if (numOutputs >= 2) {
                // Two separate output tensors: case and punct
                val caseTensor = result.get(outputNames[0]).orElse(null) as? OnnxTensor
                val punctTensor = result.get(outputNames[1]).orElse(null) as? OnnxTensor

                // Debug: log tensor shapes and first word's logits
                if (caseTensor != null) Log.d(TAG, "case_logits shape: ${caseTensor.info.shape.toList()}")
                if (punctTensor != null) {
                    val pShape = punctTensor.info.shape
                    Log.d(TAG, "punc_logits shape: ${pShape.toList()}")
                    val nLabels = pShape[2].toInt()
                    val buf = punctTensor.floatBuffer
                    // Log first 3 words' punc logits
                    for (di in 0 until minOf(3, words.size)) {
                        val tok = wordFirstTok[di]
                        val base = tok * nLabels
                        val vals = (0 until nLabels).map { buf.get(base + it) }
                        Log.d(TAG, "punc word[$di]='${words[di]}' tok=$tok logits=$vals argmax=${vals.indices.maxByOrNull { vals[it] }}")
                    }
                }

                for ((i, word) in words.withIndex()) {
                    val tok = wordFirstTok[i]
                    val cl = if (caseTensor != null) argmax(caseTensor, tok, 0, N_CASE_LABELS) else 0
                    val pl = if (punctTensor != null) argmax(punctTensor, tok, 0, punctTensor.info.shape[2].toInt()) else 0
                    if (i > 0) sb.append(' ')
                    sb.append(applyCase(word, cl))
                    if (pl in 1 until PUNCT_CHARS.size) sb.append(PUNCT_CHARS[pl])
                }
            } else if (numOutputs == 1) {
                // Single combined tensor: first N_CASE classes = case, next N_PUNCT = punct
                val combined = result.get(outputNames[0]).orElse(null) as? OnnxTensor
                for ((i, word) in words.withIndex()) {
                    val tok = wordFirstTok[i]
                    val cl = if (combined != null) argmax(combined, tok, 0, N_CASE_LABELS) else 0
                    val pl = if (combined != null) argmax(combined, tok, N_CASE_LABELS, N_CASE_LABELS + N_PUNCT_LABELS) else 0
                    if (i > 0) sb.append(' ')
                    sb.append(applyCase(word, cl))
                    if (pl in 1 until PUNCT_CHARS.size) sb.append(PUNCT_CHARS[pl])
                }
            } else {
                return text
            }
        } finally {
            result.close()
            idTensor.close()
            maskTensor.close()
        }

        return sb.toString().ifBlank { text }
    }

    /**
     * Argmax over logits[0, tokenIdx, from .. until) in a float32 [1, seq, labels] tensor.
     * Returns the winning index within the slice (0-based relative to [from]).
     */
    private fun argmax(tensor: OnnxTensor, tokenIdx: Int, from: Int, until: Int): Int {
        val shape = tensor.info.shape          // [1, seq_len, num_labels]
        val numLabels = shape[2].toInt()
        val buf = tensor.floatBuffer           // row-major flat buffer

        val clampedUntil = minOf(until, numLabels)
        if (from >= clampedUntil) return 0

        val base = tokenIdx * numLabels        // offset for row tokenIdx (batch=0)
        var best = from
        var bestVal = buf.get(base + from)
        for (j in (from + 1) until clampedUntil) {
            val v = buf.get(base + j)
            if (v > bestVal) { bestVal = v; best = j }
        }
        return best - from
    }

    private fun wordpieceTokenize(word: String): List<Long> {
        if (vocab.isEmpty()) return listOf(UNK_ID)
        vocab[word]?.let { return listOf(it) }

        val tokens = mutableListOf<Long>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = false
            while (start < end) {
                val sub = if (start == 0) word.substring(start, end)
                          else "##${word.substring(start, end)}"
                val id = vocab[sub]
                if (id != null) { tokens.add(id); found = true; start = end; break }
                end--
            }
            if (!found) { tokens.add(UNK_ID); start++ }
        }
        return if (tokens.isEmpty()) listOf(UNK_ID) else tokens
    }

    private fun applyCase(word: String, caseLabel: Int): String = when (caseLabel) {
        1 -> word.replaceFirstChar { it.uppercase() }   // CAPITALIZE
        2 -> word.uppercase()                            // UPPER
        else -> word                                     // LOWER / OTHER
    }

    override fun close() {
        try { session?.close() } catch (_: Exception) {}
    }
}
