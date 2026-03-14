package com.bohanli.ruzhtranslator.asr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Wraps Vosk speech recognition for Russian audio input.
 * All callbacks are delivered on the MAIN thread.
 */
class VoskAsrManager(
    private val modelPath: String,
    private val onPartial: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "VoskAsrManager"
        private const val SAMPLE_RATE = 16000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    @Volatile private var running = false

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Loading Vosk model: $modelPath")
            model = Model(modelPath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            Log.i(TAG, "Vosk model loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Vosk load failed: ${e.message}")
            withContext(Dispatchers.Main) { onError("Vosk 模型加载失败: ${e.message}") }
        }
    }

    fun startListening() {
        if (recognizer == null) {
            scope.launch(Dispatchers.Main) { onError("Vosk 识别器未初始化") }
            return
        }
        running = true
        recordingJob = scope.launch { audioLoop() }
    }

    fun stopListening() {
        running = false
        // Stopping the recorder causes read() to return an error code,
        // which breaks the audio loop naturally.
        try { audioRecord?.stop() } catch (e: Exception) { Log.w(TAG, "stop: ${e.message}") }
        recordingJob?.cancel()
    }

    fun release() {
        stopListening()
        try { recognizer?.close() } catch (e: Exception) { Log.w(TAG, e.message ?: "") }
        try { model?.close() } catch (e: Exception) { Log.w(TAG, e.message ?: "") }
        recognizer = null
        model = null
    }

    private suspend fun audioLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 5 * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize * 4
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            withContext(Dispatchers.Main) { onError("AudioRecord 初始化失败") }
            return
        }

        audioRecord = recorder
        recorder.startRecording()
        Log.i(TAG, "Recording started, bufSize=$bufSize")

        val buf = ShortArray(bufSize / 2)
        val rec = recognizer ?: return

        try {
            // Loop while running; read() returns negative when recorder is stopped externally
            while (running) {
                val read = recorder.read(buf, 0, buf.size)
                if (read < 0) break   // recorder was stopped via stopListening()
                if (read == 0) continue

                if (rec.acceptWaveForm(buf, read)) {
                    val text = extractField(rec.result, "text")
                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main) { onFinalResult(text) }
                    }
                } else {
                    val partial = extractField(rec.partialResult, "partial")
                    withContext(Dispatchers.Main) { onPartial(partial) }
                }
            }

            // Drain final result after stopping
            val finalText = extractField(rec.finalResult, "text")
            if (finalText.isNotBlank()) {
                withContext(Dispatchers.Main) { onFinalResult(finalText) }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Audio loop error: ${e.message}")
            if (running) {
                withContext(Dispatchers.Main) { onError("录音错误: ${e.message}") }
            }
        } finally {
            try { recorder.stop() } catch (e: Exception) { Log.w(TAG, e.message ?: "") }
            recorder.release()
            audioRecord = null
            Log.i(TAG, "Recording stopped")
        }
    }

    /**
     * Extracts a string field from Vosk's compact JSON output.
     * Handles both {"text" : "value"} and {"text":"value"} formats.
     */
    private fun extractField(json: String, field: String): String {
        for (sep in listOf("\"$field\" : \"", "\"$field\":\"")) {
            val start = json.indexOf(sep)
            if (start >= 0) {
                val valueStart = start + sep.length
                val valueEnd = json.indexOf('"', valueStart)
                if (valueEnd > valueStart) {
                    return json.substring(valueStart, valueEnd).trim()
                }
            }
        }
        return ""
    }
}
