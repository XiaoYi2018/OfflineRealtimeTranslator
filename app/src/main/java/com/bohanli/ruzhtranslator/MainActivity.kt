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
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
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
import com.bohanli.ruzhtranslator.settings.SettingsActivity
import com.bohanli.ruzhtranslator.translation.GemmaTranslator
import com.bohanli.ruzhtranslator.translation.TranslationQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MODEL_ASR_SMALL   = "vosk-model-small-ru-0.22"
        private const val MODEL_ASR_LARGE   = "vosk-model-ru-0.42"
        private const val MODEL_RECASEPUNC  = "vosk-recasepunc-ru-0.22"
        private const val MODEL_GEMMA       = "gemma-3-4b-it-Q4_K_M"
        private const val PREF_ASR_MODEL    = "asr_model"

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
    @Volatile private var isPaused = false
    private var lastPartial = ""

    // Current ASR model name
    private var currentAsrModel = MODEL_ASR_SMALL

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

        // Load ASR model preference
        currentAsrModel = getPreferences(MODE_PRIVATE)
            .getString(PREF_ASR_MODEL, MODEL_ASR_SMALL) ?: MODEL_ASR_SMALL

        setupUi()
        loadModels()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Save synchronously before canceling scope
        saveSessionToHistorySync()
        isListening = false
        isPaused = false
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
            if (isPaused) {
                // If paused, stop fully
                resumeFromPause()
                stopListening()
            } else if (isListening) {
                stopListening()
            } else {
                requestMic()
            }
        }

        // Pause/Resume button
        binding.btnPauseResume.setOnClickListener {
            if (isPaused) resumeFromPause() else pauseListening()
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

        // Settings button → popup menu
        binding.btnSettings.setOnClickListener { showSettingsPopup(it) }

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
    // Settings popup
    // -------------------------------------------------------------------------

    private fun showSettingsPopup(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.TOP)
        popup.menu.add(0, 1, 0, getString(R.string.settings_title))

        val isLargeModel = currentAsrModel == MODEL_ASR_LARGE
        val switchLabel = if (isLargeModel) getString(R.string.switch_small_model)
            else getString(R.string.switch_large_model)
        popup.menu.add(0, 2, 1, switchLabel)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                2 -> {
                    switchAsrModel()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun switchAsrModel() {
        val newModel = if (currentAsrModel == MODEL_ASR_SMALL) MODEL_ASR_LARGE else MODEL_ASR_SMALL

        // Stop listening if active
        if (isListening || isPaused) {
            if (isPaused) resumeFromPause()
            stopListening()
        }

        // Disable button during reload
        binding.btnStartStop.isEnabled = false
        binding.btnStartStop.alpha = 0.4f

        mainScope.launch {
            try {
                updateStatus(AppStatus.Loading("切换模型: $newModel..."))

                // Release old Vosk
                voskAsr?.release()
                voskAsr = null

                // Load new model
                val asrDir = withContext(Dispatchers.IO) {
                    ModelManager.getModelDir(this@MainActivity, newModel)
                }
                if (asrDir == null) {
                    updateStatus(AppStatus.Error("找不到模型: $newModel"))
                    // Revert to old model
                    reloadVoskAsr(currentAsrModel)
                    return@launch
                }

                val asr = VoskAsrManager(
                    modelPath     = asrDir.absolutePath,
                    onPartial     = { text -> onVoskPartial(text) },
                    onFinalResult = { text -> onVoskFinal(text) },
                    onError       = { msg  -> updateStatus(AppStatus.Error(msg)) }
                )
                asr.initialize()
                voskAsr = asr

                currentAsrModel = newModel
                getPreferences(MODE_PRIVATE).edit()
                    .putString(PREF_ASR_MODEL, newModel)
                    .apply()

                binding.btnStartStop.isEnabled = true
                binding.btnStartStop.alpha = 0.75f
                updateStatus(AppStatus.Ready)
                Toast.makeText(this@MainActivity,
                    getString(R.string.model_switched, newModel), Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "Model switch failed", e)
                updateStatus(AppStatus.Error("模型切换失败: ${e.message}"))
                // Try to reload old model
                reloadVoskAsr(currentAsrModel)
            }
        }
    }

    private suspend fun reloadVoskAsr(model: String) {
        try {
            val dir = withContext(Dispatchers.IO) {
                ModelManager.getModelDir(this@MainActivity, model)
            } ?: return
            val asr = VoskAsrManager(
                modelPath     = dir.absolutePath,
                onPartial     = { text -> onVoskPartial(text) },
                onFinalResult = { text -> onVoskFinal(text) },
                onError       = { msg  -> updateStatus(AppStatus.Error(msg)) }
            )
            asr.initialize()
            voskAsr = asr
            binding.btnStartStop.isEnabled = true
            binding.btnStartStop.alpha = 0.75f
            updateStatus(AppStatus.Ready)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reload ASR", e)
        }
    }

    // -------------------------------------------------------------------------
    // Model loading (parallel)
    // -------------------------------------------------------------------------

    private fun loadModels() {
        mainScope.launch {
            try {
                updateStatus(AppStatus.Loading("加载模型..."))
                val loadedCount = AtomicInteger(0)

                coroutineScope {
                    // Vosk ASR
                    val asrDeferred = async(Dispatchers.IO) {
                        val dir = ModelManager.getModelDir(this@MainActivity, currentAsrModel)
                            ?: throw IllegalStateException(
                                "找不到 Vosk ASR 模型。\n请将 $currentAsrModel/ 放至:\n" +
                                ModelManager.getExternalModelDir(this@MainActivity)
                            )
                        val asr = VoskAsrManager(
                            modelPath     = dir.absolutePath,
                            onPartial     = { text -> onVoskPartial(text) },
                            onFinalResult = { text -> onVoskFinal(text) },
                            onError       = { msg  -> updateStatus(AppStatus.Error(msg)) }
                        )
                        asr.initialize()
                        val n = loadedCount.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            updateStatus(AppStatus.Loading("加载模型 ($n/3)..."))
                        }
                        asr
                    }

                    // Recasepunc
                    val recasepuncDeferred = async(Dispatchers.IO) {
                        val dir = ModelManager.getModelDir(this@MainActivity, MODEL_RECASEPUNC)
                        if (dir == null) Log.w(TAG, "Recasepunc model not found – punctuation skipped")
                        val rcp = RecasepuncProcessor(dir)
                        val n = loadedCount.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            updateStatus(AppStatus.Loading("加载模型 ($n/3)..."))
                        }
                        rcp
                    }

                    // Gemma translator
                    val gemmaDeferred = async(Dispatchers.IO) {
                        val dir = ModelManager.getModelDir(this@MainActivity, MODEL_GEMMA)
                        if (dir == null) Log.w(TAG, "Gemma model not found")
                        val translator = GemmaTranslator()
                        if (dir != null) translator.initialize(dir)
                        val n = loadedCount.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            updateStatus(AppStatus.Loading("加载模型 ($n/3)..."))
                        }
                        translator
                    }

                    voskAsr = asrDeferred.await()
                    recasepunc = recasepuncDeferred.await()
                    gemmaTranslator = gemmaDeferred.await()
                }

                // Translation queue (needs gemmaTranslator ready)
                translationQueue = TranslationQueue(
                    translator    = gemmaTranslator!!,
                    onResult      = { result -> finalizeChinese(result) },
                    onStreamToken = { token -> appendStreamToken(token) },
                    onStreamStart = { startChineseStream() },
                    onBusyChanged = { busy ->
                        if (busy) updateStatus(AppStatus.Translating)
                        else if (isListening) updateStatus(AppStatus.Listening)
                        else if (isPaused) updateStatus(AppStatus.Paused)
                    }
                )

                // Enable button and auto-start
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
    // Start / Stop / Pause / Resume
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
        isPaused = false
        asr.startListening()
        updateStatus(AppStatus.Listening)
        // Switch to red stop icon
        binding.btnStartStop.setImageResource(R.drawable.ic_stop_square)
        binding.btnStartStop.setBackgroundResource(R.drawable.bg_circle_button_stop)
        // Show pause button
        binding.btnPauseResume.visibility = View.VISIBLE
        binding.btnPauseResume.setImageResource(R.drawable.ic_pause)
        binding.btnPauseResume.setBackgroundResource(R.drawable.bg_circle_button_pause)
    }

    private fun stopListening() {
        isListening = false
        isPaused = false
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
        // Hide pause button
        binding.btnPauseResume.visibility = View.GONE
    }

    private fun pauseListening() {
        isPaused = true
        voskAsr?.stopListening()

        // Flush any pending partial to queue
        if (lastPartial.isNotBlank()) {
            val text = lastPartial
            lastPartial = ""
            appendRussianSegment(text)
            translationQueue?.submit(text)
            updateRussianDisplay()
        }

        // Pause queue (finishes current translation, then waits)
        translationQueue?.pause()

        updateStatus(AppStatus.Paused)
        // Change pause button to resume (play arrow with amber bg)
        binding.btnPauseResume.setImageResource(R.drawable.ic_play_arrow)
        binding.btnPauseResume.setBackgroundResource(R.drawable.bg_circle_button_start)
    }

    private fun resumeFromPause() {
        isPaused = false
        translationQueue?.resume()
        voskAsr?.startListening()
        isListening = true

        updateStatus(AppStatus.Listening)
        // Change back to pause icon
        binding.btnPauseResume.setImageResource(R.drawable.ic_pause)
        binding.btnPauseResume.setBackgroundResource(R.drawable.bg_circle_button_pause)
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
