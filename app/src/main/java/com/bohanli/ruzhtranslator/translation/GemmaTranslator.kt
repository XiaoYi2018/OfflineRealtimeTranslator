package com.bohanli.ruzhtranslator.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kotlin wrapper around the llama.cpp JNI bridge for Gemma 3 4B-IT.
 * Translates Russian text to Simplified Chinese using prompt-based generation.
 */
class GemmaTranslator {

    companion object {
        private const val TAG = "GemmaTranslator"

        init {
            try {
                System.loadLibrary("translator")
                Log.i(TAG, "Native translator library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native translator library: ${e.message}")
            }
        }
    }

    @Volatile private var nativeHandle: Long = 0L
    @Volatile var onStreamToken: ((String) -> Unit)? = null

    private external fun nativeCreate(modelPath: String): Long
    private external fun nativeTranslate(handle: Long, text: String): String
    private external fun nativeIsAvailable(handle: Long): Boolean
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLastPromptMs(handle: Long): Long
    private external fun nativeLastGenMs(handle: Long): Long
    private external fun nativeLastPromptTokens(handle: Long): Int
    private external fun nativeLastGenTokens(handle: Long): Int

    /** Last-call timing snapshot returned by [translateWithMetrics]. */
    data class TranslationResult(
        val text: String,
        val promptMs: Long,
        val genMs: Long,
        val promptTokens: Int,
        val genTokens: Int,
    ) {
        val totalMs: Long get() = promptMs + genMs
    }

    /** Called from JNI during generation — forwards token to listener. */
    @Suppress("unused")
    @androidx.annotation.Keep
    fun onStreamToken(token: String) {
        onStreamToken?.invoke(token)
    }

    /** Initialize on an IO thread. Looks for *.gguf file in modelDir. */
    suspend fun initialize(modelDir: File) = withContext(Dispatchers.IO) {
        try {
            val ggufFile = modelDir.listFiles()?.firstOrNull { it.name.endsWith(".gguf") }
            if (ggufFile == null || !ggufFile.exists()) {
                Log.w(TAG, "No .gguf file found in ${modelDir.absolutePath}")
                return@withContext
            }
            Log.i(TAG, "Loading model: ${ggufFile.name} (${ggufFile.length() / 1024 / 1024}MB)")
            nativeHandle = nativeCreate(ggufFile.absolutePath)
            if (nativeHandle != 0L) {
                Log.i(TAG, "Gemma translator initialized")
            } else {
                Log.w(TAG, "Gemma init returned null handle")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translator init failed: ${e.message}")
        }
    }

    /** Translate [text] synchronously on the calling thread (must be off main thread). */
    fun translate(text: String): String {
        if (nativeHandle == 0L) {
            return "[翻译引擎未加载]"
        }
        return try {
            nativeTranslate(nativeHandle, text)
        } catch (e: Exception) {
            Log.e(TAG, "Translation exception: ${e.message}")
            "[翻译异常: ${e.message}]"
        }
    }

    /** Translate and return timing metrics alongside the result string. */
    fun translateWithMetrics(text: String): TranslationResult {
        if (nativeHandle == 0L) {
            return TranslationResult("[翻译引擎未加载]", 0L, 0L, 0, 0)
        }
        return try {
            val out = nativeTranslate(nativeHandle, text)
            TranslationResult(
                text = out,
                promptMs = nativeLastPromptMs(nativeHandle),
                genMs = nativeLastGenMs(nativeHandle),
                promptTokens = nativeLastPromptTokens(nativeHandle),
                genTokens = nativeLastGenTokens(nativeHandle),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Translation exception: ${e.message}")
            TranslationResult("[翻译异常: ${e.message}]", 0L, 0L, 0, 0)
        }
    }

    fun isAvailable(): Boolean =
        nativeHandle != 0L && try { nativeIsAvailable(nativeHandle) } catch (_: Exception) { false }

    fun destroy() {
        if (nativeHandle != 0L) {
            try { nativeDestroy(nativeHandle) } catch (_: Exception) {}
            nativeHandle = 0L
        }
    }
}
