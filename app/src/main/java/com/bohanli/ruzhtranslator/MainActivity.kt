package com.bohanli.ruzhtranslator

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bohanli.ruzhtranslator.asr.RecasepuncProcessor
import com.bohanli.ruzhtranslator.asr.VoskAsrManager
import com.bohanli.ruzhtranslator.core.AppStatus
import com.bohanli.ruzhtranslator.core.ModelManager
import com.bohanli.ruzhtranslator.databinding.ActivityMainBinding
import com.bohanli.ruzhtranslator.segmentation.SentenceSegmenter
import com.bohanli.ruzhtranslator.translation.GemmaTranslator
import com.bohanli.ruzhtranslator.translation.TranslationQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MODEL_ASR        = "vosk-model-ru-0.42"
        private const val MODEL_RECASEPUNC = "vosk-recasepunc-ru-0.22"
        private const val MODEL_GEMMA      = "gemma-3-4b-it-Q4_K_M"

        // Bright colors visible on dark backgrounds, cycling per segment
        private val SEGMENT_COLORS = intArrayOf(
            Color.parseColor("#FF80FF80"), // bright green
            Color.parseColor("#FFFF80C0"), // bright pink
            Color.parseColor("#FFFFFFFF"), // white
            Color.parseColor("#FFFF6666"), // bright red
            Color.parseColor("#FF66CCFF"), // bright sky blue
            Color.parseColor("#FFFFDD55"), // bright yellow
            Color.parseColor("#FFCC88FF"), // bright purple
            Color.parseColor("#FFFF9944"), // bright orange
            Color.parseColor("#FF44FFDD"), // bright cyan/teal
            Color.parseColor("#FFDDAAFF"), // bright lavender
        )

        // Partial stability: confirm words that have been unchanged across N consecutive partials
        private const val STABLE_HITS = 8       // how many callbacks a word must survive unchanged
        private const val STABLE_MIN_WORDS = 8  // don't confirm until partial has at least this many unconsumed words
    }

    private lateinit var binding: ActivityMainBinding

    // All coroutines anchored to Main scope; withContext(IO) for heavy work
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Core components – initialised asynchronously in loadModels()
    private var voskAsr: VoskAsrManager? = null
    private var recasepunc: RecasepuncProcessor? = null
    private var gemmaTranslator: GemmaTranslator? = null
    private var translationQueue: TranslationQueue? = null
    private val segmenter = SentenceSegmenter()

    @Volatile private var isListening = false
    private var lastPartial = ""

    // Partial stability tracking
    private var stablePrefix = ""          // longest prefix that matched last partial
    private var stableHitCount = 0         // how many consecutive partials kept that prefix
    private var confirmedWordCount = 0     // how many words from current partial already confirmed

    // Segment lists for colored display
    private val russianSegments = mutableListOf<String>()
    private val chineseSegments = mutableListOf<String>()
    private var segmentColorIndex = 0

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else updateStatus(AppStatus.Error(getString(R.string.permission_denied)))
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUi()
        loadModels()
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        voskAsr?.release()
        translationQueue?.shutdown()
        gemmaTranslator?.destroy()
        recasepunc?.close()
        mainScope.cancel()
    }

    // -------------------------------------------------------------------------
    // UI setup
    // -------------------------------------------------------------------------

    private fun setupUi() {
        binding.btnStartStop.isEnabled = false
        binding.btnStartStop.setOnClickListener {
            if (isListening) stopListening() else requestMic()
        }
    }

    // -------------------------------------------------------------------------
    // Model loading
    // -------------------------------------------------------------------------

    private fun loadModels() {
        mainScope.launch {
            try {
                // ---- Vosk ASR (required) ----
                updateStatus(AppStatus.Loading("加载语音识别模型 ($MODEL_ASR)..."))
                val asrDir = withContext(Dispatchers.IO) {
                    ModelManager.getModelDir(this@MainActivity, MODEL_ASR)
                } ?: throw IllegalStateException(
                    "找不到 Vosk ASR 模型。\n" +
                    "请将 $MODEL_ASR/ 放至:\n" +
                    ModelManager.getExternalModelDir(this@MainActivity)
                )

                val asr = VoskAsrManager(
                    modelPath     = asrDir.absolutePath,
                    onPartial     = { text -> onVoskPartial(text) },
                    onFinalResult = { text -> onVoskFinal(text) },
                    onError       = { msg  -> updateStatus(AppStatus.Error(msg)) }
                )
                updateStatus(AppStatus.Loading("初始化语音识别引擎..."))
                asr.initialize()   // suspend, runs on IO
                voskAsr = asr

                // ---- Recasepunc (optional) ----
                updateStatus(AppStatus.Loading("加载标点恢复模型..."))
                val recasepuncDir = withContext(Dispatchers.IO) {
                    ModelManager.getModelDir(this@MainActivity, MODEL_RECASEPUNC)
                }
                if (recasepuncDir == null) Log.w(TAG, "Recasepunc model not found – punctuation skipped")
                recasepunc = withContext(Dispatchers.IO) {
                    RecasepuncProcessor(recasepuncDir)
                }

                // ---- Gemma translator ----
                updateStatus(AppStatus.Loading("加载翻译引擎 (Gemma)..."))
                val gemmaDir = withContext(Dispatchers.IO) {
                    ModelManager.getModelDir(this@MainActivity, MODEL_GEMMA)
                }
                if (gemmaDir == null) Log.w(TAG, "Gemma model not found")

                val translator = GemmaTranslator()
                if (gemmaDir != null) translator.initialize(gemmaDir)
                gemmaTranslator = translator

                // ---- Translation queue ----
                translationQueue = TranslationQueue(
                    translator    = translator,
                    onResult      = { result -> appendChinese(result) },
                    onBusyChanged = { busy ->
                        if (busy) updateStatus(AppStatus.Translating)
                        else if (isListening) updateStatus(AppStatus.Listening)
                    }
                )

                updateStatus(AppStatus.Ready)
                binding.btnStartStop.isEnabled = true

            } catch (e: Exception) {
                Log.e(TAG, "Model loading failed", e)
                updateStatus(AppStatus.Error(e.message ?: "模型加载失败"))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Vosk callbacks  (already on Main thread via VoskAsrManager)
    // -------------------------------------------------------------------------

    private fun onVoskPartial(partial: String) {
        lastPartial = partial

        val words = partial.split(" ").filter { it.isNotBlank() }
        val unconsumed = words.size - confirmedWordCount

        if (unconsumed >= STABLE_MIN_WORDS) {
            // Check how many unconsumed words match the previous partial's prefix
            val unconsumedText = words.drop(confirmedWordCount).joinToString(" ")
            if (unconsumedText.startsWith(stablePrefix) && stablePrefix.isNotEmpty()) {
                stableHitCount++
            } else {
                // Prefix changed — reset stability counter to the new longest common prefix
                stablePrefix = unconsumedText
                stableHitCount = 1
            }

            if (stableHitCount >= STABLE_HITS) {
                // The front ~60% of unconsumed words are stable — confirm them
                val confirmCount = (unconsumed * 0.6).toInt()
                if (confirmCount > 0) {
                    val newWords = words.subList(confirmedWordCount, confirmedWordCount + confirmCount)
                    val newText = newWords.joinToString(" ")
                    confirmedWordCount += confirmCount
                    stablePrefix = ""
                    stableHitCount = 0

                    Log.d(TAG, "Stable confirm: [$newText] ($confirmedWordCount/${words.size} words)")

                    val rcp = recasepunc
                    val queue = translationQueue
                    mainScope.launch {
                        val processed = withContext(Dispatchers.IO) {
                            rcp?.takeIf { it.isAvailable() }?.process(newText) ?: newText
                        }
                        var first = true
                        while (true) {
                            val segment = segmenter.process(
                                if (first) processed else "",
                                isPause = false
                            ) ?: break
                            first = false
                            if (segment.isNotBlank()) {
                                appendRussianSegment(segment)
                                queue?.submit(segment)
                            }
                        }
                        updateRussianDisplay(partial)
                    }
                    return
                }
            }
        } else {
            stablePrefix = ""
            stableHitCount = 0
        }

        updateRussianDisplay(partial)
    }

    private fun onVoskFinal(rawText: String) {
        lastPartial = ""

        // Only process the tail not already confirmed from partials
        val allWords = rawText.split(" ").filter { it.isNotBlank() }
        val remaining = if (confirmedWordCount > 0 && confirmedWordCount <= allWords.size) {
            allWords.drop(confirmedWordCount).joinToString(" ")
        } else if (confirmedWordCount > 0) {
            ""
        } else {
            rawText
        }

        // Reset stability tracking for next utterance
        confirmedWordCount = 0
        stablePrefix = ""
        stableHitCount = 0

        if (remaining.isBlank()) {
            updateRussianDisplay()
            return
        }

        val rcp = recasepunc
        val queue = translationQueue

        mainScope.launch {
            val processed = withContext(Dispatchers.IO) {
                val result = rcp?.takeIf { it.isAvailable() }?.process(remaining) ?: remaining
                Log.d(TAG, "Recasepunc: in=[$remaining] out=[$result] available=${rcp?.isAvailable()}")
                result
            }

            var first = true
            while (true) {
                val segment = segmenter.process(
                    if (first) processed else "",
                    isPause = true
                ) ?: break
                first = false
                if (segment.isNotBlank()) {
                    appendRussianSegment(segment)
                    queue?.submit(segment)
                }
            }
            updateRussianDisplay()
        }
    }

    // -------------------------------------------------------------------------
    // Start / Stop
    // -------------------------------------------------------------------------

    private fun requestMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        val asr = voskAsr ?: run {
            updateStatus(AppStatus.Error("语音识别引擎未就绪")); return
        }
        segmenter.reset()
        confirmedWordCount = 0
        stablePrefix = ""
        stableHitCount = 0
        isListening = true
        asr.startListening()
        updateStatus(AppStatus.Listening)
        binding.btnStartStop.text = getString(R.string.btn_stop)
    }

    private fun stopListening() {
        isListening = false
        voskAsr?.stopListening()

        // Feed last partial into segmenter so it's not lost
        if (lastPartial.isNotBlank()) {
            segmenter.process(lastPartial, isPause = false)
            lastPartial = ""
        }

        // Flush any remaining uncommitted buffer to translation
        segmenter.flush()?.let { remaining ->
            if (remaining.isNotBlank()) {
                appendRussianSegment(remaining)
                translationQueue?.submit(remaining)
            }
        }
        updateRussianDisplay()

        updateStatus(AppStatus.Ready)
        binding.btnStartStop.text = getString(R.string.btn_start)
    }

    // -------------------------------------------------------------------------
    // UI helpers  (all called on Main)
    // -------------------------------------------------------------------------

    private fun updateStatus(status: AppStatus) {
        binding.tvStatus.text = status.toDisplayString()
    }

    private fun appendRussianSegment(text: String) {
        russianSegments.add(text)
    }

    private fun updateRussianDisplay(partial: String = "") {
        val ssb = SpannableStringBuilder()

        // Committed segments with colors
        for ((i, seg) in russianSegments.withIndex()) {
            if (ssb.isNotEmpty()) ssb.append("\n")
            val start = ssb.length
            ssb.append(seg)
            val color = SEGMENT_COLORS[i % SEGMENT_COLORS.size]
            ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Uncommitted buffer (gray, not yet a segment)
        val buf = segmenter.getCurrentBuffer()
        if (buf.isNotEmpty()) {
            if (ssb.isNotEmpty()) ssb.append("\n")
            val start = ssb.length
            ssb.append(buf)
            ssb.setSpan(ForegroundColorSpan(Color.parseColor("#FF888888")), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Current partial (dimmer, in-progress) — only show unconfirmed tail
        if (partial.isNotEmpty()) {
            val partialWords = partial.split(" ").filter { it.isNotBlank() }
            val displayText = if (confirmedWordCount > 0 && confirmedWordCount < partialWords.size) {
                partialWords.drop(confirmedWordCount).joinToString(" ")
            } else if (confirmedWordCount >= partialWords.size) {
                ""
            } else {
                partial
            }
            if (displayText.isNotEmpty()) {
                if (ssb.isNotEmpty()) ssb.append("\n")
                val start = ssb.length
                ssb.append(displayText)
                ssb.setSpan(ForegroundColorSpan(Color.parseColor("#FF666666")), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        binding.tvRussianHistory.text = ssb
        binding.scrollViewRussian.post {
            binding.scrollViewRussian.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun appendChinese(text: String) {
        chineseSegments.add(text)

        val ssb = SpannableStringBuilder()
        for ((i, seg) in chineseSegments.withIndex()) {
            if (ssb.isNotEmpty()) ssb.append("\n")
            val start = ssb.length
            ssb.append(seg)
            // Same color index as the corresponding Russian segment
            val color = SEGMENT_COLORS[i % SEGMENT_COLORS.size]
            ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        binding.tvChineseHistory.text = ssb
        binding.scrollViewChinese.post {
            binding.scrollViewChinese.fullScroll(View.FOCUS_DOWN)
        }
    }
}
