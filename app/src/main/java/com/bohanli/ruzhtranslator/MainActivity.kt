package com.bohanli.ruzhtranslator

import android.Manifest
import android.content.Intent
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
import com.bohanli.ruzhtranslator.history.AppDatabase
import com.bohanli.ruzhtranslator.history.HistoryActivity
import com.bohanli.ruzhtranslator.history.TranslationRecord
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
        private const val MODEL_ASR        = "vosk-model-small-ru-0.22"
        private const val MODEL_RECASEPUNC = "vosk-recasepunc-ru-0.22"
        private const val MODEL_GEMMA      = "gemma-3-4b-it-Q4_K_M"

        // 16 bright rainbow colors for dark backgrounds, no white
        private val SEGMENT_COLORS = intArrayOf(
            Color.parseColor("#FFFF6666"), // red
            Color.parseColor("#FFFF8855"), // red-orange
            Color.parseColor("#FFFF9944"), // orange
            Color.parseColor("#FFFFBB44"), // amber
            Color.parseColor("#FFFFDD55"), // yellow
            Color.parseColor("#FFCCEE55"), // yellow-green
            Color.parseColor("#FF80FF80"), // green
            Color.parseColor("#FF44FFBB"), // emerald
            Color.parseColor("#FF44FFDD"), // teal
            Color.parseColor("#FF44DDFF"), // cyan
            Color.parseColor("#FF66BBFF"), // sky blue
            Color.parseColor("#FF8899FF"), // blue
            Color.parseColor("#FFAA88FF"), // indigo
            Color.parseColor("#FFCC88FF"), // purple
            Color.parseColor("#FFFF80CC"), // magenta
            Color.parseColor("#FFFF80A0"), // pink
        )
    }

    private lateinit var binding: ActivityMainBinding

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var voskAsr: VoskAsrManager? = null
    private var recasepunc: RecasepuncProcessor? = null
    private var gemmaTranslator: GemmaTranslator? = null
    private var translationQueue: TranslationQueue? = null

    @Volatile private var isListening = false
    private var lastPartial = ""

    // Segment lists for colored display
    private val russianSegments = mutableListOf<String>()
    private val chineseSegments = mutableListOf<String>()

    // Smart auto-scroll: track whether user has manually scrolled up
    private var autoScrollRussian = true
    private var autoScrollChinese = true

    // Database
    private val db by lazy { AppDatabase.getInstance(this) }

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
        // Save synchronously before canceling scope
        saveSessionToHistorySync()
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
        // Start/Stop circular button
        binding.btnStartStop.isEnabled = false
        binding.btnStartStop.alpha = 0.4f
        binding.btnStartStop.setOnClickListener {
            if (isListening) stopListening() else requestMic()
        }

        // History button → open history tab
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java)
                .putExtra("tab", 0))
        }

        // Favorites button → open favorites tab
        binding.btnFavorites.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java)
                .putExtra("tab", 1))
        }

        // Scroll-to-bottom buttons
        binding.btnScrollBottomRu.setOnClickListener {
            binding.scrollViewRussian.fullScroll(View.FOCUS_DOWN)
            autoScrollRussian = true
            binding.btnScrollBottomRu.visibility = View.GONE
        }
        binding.btnScrollBottomZh.setOnClickListener {
            binding.scrollViewChinese.fullScroll(View.FOCUS_DOWN)
            autoScrollChinese = true
            binding.btnScrollBottomZh.visibility = View.GONE
        }

        // Smart scroll: detect when user scrolls away from bottom
        setupSmartScroll(binding.scrollViewRussian, { autoScrollRussian },
            { autoScrollRussian = it }, binding.btnScrollBottomRu)
        setupSmartScroll(binding.scrollViewChinese, { autoScrollChinese },
            { autoScrollChinese = it }, binding.btnScrollBottomZh)
    }

    private fun setupSmartScroll(
        scrollView: android.widget.ScrollView,
        getAutoScroll: () -> Boolean,
        setAutoScroll: (Boolean) -> Unit,
        scrollBtn: View
    ) {
        scrollView.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            val sv = v as android.widget.ScrollView
            val child = sv.getChildAt(0) ?: return@setOnScrollChangeListener
            val atBottom = scrollY + sv.height >= child.height - 50
            if (atBottom) {
                setAutoScroll(true)
                scrollBtn.visibility = View.GONE
            } else if (getAutoScroll()) {
                // User scrolled up manually
                setAutoScroll(false)
                scrollBtn.visibility = View.VISIBLE
            }
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
                asr.initialize()
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
                    onResult      = { result -> finalizeChinese(result) },
                    onStreamToken = { token -> appendStreamToken(token) },
                    onStreamStart = { startChineseStream() },
                    onBusyChanged = { busy ->
                        if (busy) updateStatus(AppStatus.Translating)
                        else if (isListening) updateStatus(AppStatus.Listening)
                    }
                )

                // Enable button and auto-start listening
                binding.btnStartStop.isEnabled = true
                binding.btnStartStop.alpha = 0.75f
                requestMic()

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
        updateRussianDisplay(partial)
    }

    private fun onVoskFinal(rawText: String) {
        lastPartial = ""
        if (rawText.isBlank()) {
            updateRussianDisplay()
            return
        }

        val rcp = recasepunc
        val queue = translationQueue

        mainScope.launch {
            val processed = withContext(Dispatchers.IO) {
                val result = rcp?.takeIf { it.isAvailable() }?.process(rawText) ?: rawText
                Log.d(TAG, "Recasepunc: in=[$rawText] out=[$result] available=${rcp?.isAvailable()}")
                result
            }
            if (processed.isNotBlank()) {
                appendRussianSegment(processed)
                queue?.submit(processed)
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
        // Save previous session before starting a new one
        saveSessionToHistory()
        isListening = true
        asr.startListening()
        updateStatus(AppStatus.Listening)
        // Switch to red stop icon
        binding.btnStartStop.setImageResource(R.drawable.ic_stop_square)
        binding.btnStartStop.setBackgroundResource(R.drawable.bg_circle_button_stop)
    }

    private fun stopListening() {
        isListening = false
        voskAsr?.stopListening()

        if (lastPartial.isNotBlank()) {
            val text = lastPartial
            lastPartial = ""
            appendRussianSegment(text)
            translationQueue?.submit(text)
        }
        updateRussianDisplay()

        updateStatus(AppStatus.Ready)
        // Switch to green play icon
        binding.btnStartStop.setImageResource(R.drawable.ic_play_arrow)
        binding.btnStartStop.setBackgroundResource(R.drawable.bg_circle_button_start)
    }

    // -------------------------------------------------------------------------
    // History persistence
    // -------------------------------------------------------------------------

    private fun saveSessionToHistory() {
        if (russianSegments.isEmpty() && chineseSegments.isEmpty()) return
        // Discard pending/in-flight translations from the old session
        translationQueue?.clear()
        streamingText.clear()
        if (russianSegments.isNotEmpty() && chineseSegments.isNotEmpty()) {
            val ruText = russianSegments.joinToString("\n")
            val zhText = chineseSegments.joinToString("\n")
            mainScope.launch {
                withContext(Dispatchers.IO) {
                    db.translationDao().insert(
                        TranslationRecord(ruText = ruText, zhText = zhText)
                    )
                }
                Log.d(TAG, "Session saved to history: ${russianSegments.size} ru, ${chineseSegments.size} zh")
            }
        }
        russianSegments.clear()
        chineseSegments.clear()
        binding.tvRussianHistory.text = ""
        binding.tvChineseHistory.text = ""
    }

    /** Synchronous version for onDestroy — runs on calling thread. */
    private fun saveSessionToHistorySync() {
        if (russianSegments.isEmpty() || chineseSegments.isEmpty()) return
        val ruText = russianSegments.joinToString("\n")
        val zhText = chineseSegments.joinToString("\n")
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                db.translationDao().insert(
                    TranslationRecord(ruText = ruText, zhText = zhText)
                )
            }
            Log.d(TAG, "Session saved to history (sync): ${russianSegments.size} segments")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session on destroy", e)
        }
        russianSegments.clear()
        chineseSegments.clear()
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

        for ((i, seg) in russianSegments.withIndex()) {
            if (ssb.isNotEmpty()) ssb.append("\n")
            val start = ssb.length
            ssb.append(seg)
            val color = SEGMENT_COLORS[i % SEGMENT_COLORS.size]
            ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (partial.isNotEmpty()) {
            if (ssb.isNotEmpty()) ssb.append("\n")
            val start = ssb.length
            ssb.append(partial)
            ssb.setSpan(ForegroundColorSpan(Color.parseColor("#FF888888")), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        binding.tvRussianHistory.text = ssb
        if (autoScrollRussian) {
            binding.scrollViewRussian.post {
                binding.scrollViewRussian.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    // Streaming state
    private var streamingText = StringBuilder()

    private fun startChineseStream() {
        streamingText.clear()
    }

    private fun appendStreamToken(token: String) {
        streamingText.append(token)
        updateChineseDisplay(streamingText.toString())
    }

    private fun finalizeChinese(text: String) {
        streamingText.clear()
        chineseSegments.add(text)
        updateChineseDisplay(null)
    }

    private fun updateChineseDisplay(streaming: String?) {
        val ssb = SpannableStringBuilder()

        for ((i, seg) in chineseSegments.withIndex()) {
            if (ssb.isNotEmpty()) ssb.append("\n")
            val start = ssb.length
            ssb.append(seg)
            val color = SEGMENT_COLORS[i % SEGMENT_COLORS.size]
            ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (streaming != null && streaming.isNotEmpty()) {
            if (ssb.isNotEmpty()) ssb.append("\n")
            val start = ssb.length
            ssb.append(streaming)
            val color = SEGMENT_COLORS[chineseSegments.size % SEGMENT_COLORS.size]
            ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        binding.tvChineseHistory.text = ssb
        if (autoScrollChinese) {
            binding.scrollViewChinese.post {
                binding.scrollViewChinese.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
