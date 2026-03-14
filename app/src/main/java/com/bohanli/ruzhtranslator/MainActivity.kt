package com.bohanli.ruzhtranslator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.bohanli.ruzhtranslator.translation.NllbTranslator
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
        private const val MODEL_NLLB       = "nllb-200-distilled-1.3B-ct2-int8"
    }

    private lateinit var binding: ActivityMainBinding

    // All coroutines anchored to Main scope; withContext(IO) for heavy work
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Core components – initialised asynchronously in loadModels()
    private var voskAsr: VoskAsrManager? = null
    private var recasepunc: RecasepuncProcessor? = null
    private var nllbTranslator: NllbTranslator? = null
    private var translationQueue: TranslationQueue? = null
    private val segmenter = SentenceSegmenter()

    @Volatile private var isListening = false
    private val russianHistory = StringBuilder()

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
        nllbTranslator?.destroy()
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

                // ---- NLLB translator (stub-safe) ----
                updateStatus(AppStatus.Loading("加载翻译引擎..."))
                val nllbDir = withContext(Dispatchers.IO) {
                    ModelManager.getModelDir(this@MainActivity, MODEL_NLLB)
                }
                if (nllbDir == null) Log.w(TAG, "NLLB model not found – stub mode active")

                val translator = NllbTranslator()
                if (nllbDir != null) translator.initialize(nllbDir)
                nllbTranslator = translator

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

    /**
     * Partial result from Vosk – update the Russian display with
     * committed buffer + current in-progress hypothesis.
     */
    private fun onVoskPartial(partial: String) {
        updateRussianDisplay(partial)
    }

    /**
     * Final result from Vosk (speech pause detected).
     * Runs recasepunc on IO, then applies segmentation on Main.
     */
    private fun onVoskFinal(rawText: String) {
        // Capture references on Main before any context switch
        val rcp = recasepunc
        val queue = translationQueue

        mainScope.launch {
            val processed = withContext(Dispatchers.IO) {
                rcp?.takeIf { it.isAvailable() }?.process(rawText) ?: rawText
            }

            // Segmentation and buffer management always on Main (no mutex needed)
            val segment = segmenter.process(processed, isPause = true)
            if (segment != null && segment.isNotBlank()) {
                appendRussianSegment(segment)
                queue?.submit(segment)
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
        isListening = true
        asr.startListening()
        updateStatus(AppStatus.Listening)
        binding.btnStartStop.text = getString(R.string.btn_stop)
    }

    private fun stopListening() {
        isListening = false
        voskAsr?.stopListening()

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
        if (russianHistory.isNotEmpty()) russianHistory.append("\n")
        russianHistory.append(text)
    }

    private fun updateRussianDisplay(partial: String = "") {
        val parts = buildList {
            val h = russianHistory.toString()
            if (h.isNotEmpty()) add(h)
            val buf = segmenter.getCurrentBuffer()
            if (buf.isNotEmpty()) add(buf)
            if (partial.isNotEmpty()) add(partial)
        }
        binding.tvRussianHistory.text = parts.joinToString("\n")
        binding.scrollViewRussian.post {
            binding.scrollViewRussian.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun appendChinese(text: String) {
        val current = binding.tvChineseHistory.text
        binding.tvChineseHistory.text = if (current.isEmpty()) text else "$current\n$text"
        // Auto-scroll to bottom after layout pass
        binding.scrollViewChinese.post {
            binding.scrollViewChinese.fullScroll(View.FOCUS_DOWN)
        }
    }
}
