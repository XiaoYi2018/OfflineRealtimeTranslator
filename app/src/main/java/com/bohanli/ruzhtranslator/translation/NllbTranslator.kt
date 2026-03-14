package com.bohanli.ruzhtranslator.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kotlin wrapper around the CTranslate2 JNI bridge.
 *
 * The native library "translator" is built from ctranslate2_jni.cpp.
 * When CTRANSLATE2_AVAILABLE=0 (no prebuilt .so), every translation call
 * returns a human-readable stub message instructing the user to see SETUP.md.
 *
 * Source language:  rus_Cyrl  (Russian Cyrillic)
 * Target language:  zho_Hans  (Simplified Chinese)
 */
class NllbTranslator {

    companion object {
        private const val TAG = "NllbTranslator"
        const val SRC_LANG = "rus_Cyrl"
        const val TGT_LANG = "zho_Hans"

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

    private external fun nativeCreate(modelPath: String, spModelPath: String): Long
    private external fun nativeTranslate(handle: Long, text: String, srcLang: String, tgtLang: String): String
    private external fun nativeIsAvailable(handle: Long): Boolean
    private external fun nativeDestroy(handle: Long)

    /** Initialize on an IO thread. Safe to call even if modelDir is null. */
    suspend fun initialize(modelDir: File) = withContext(Dispatchers.IO) {
        try {
            val spModel = File(modelDir, "sentencepiece.bpe.model")
            if (!spModel.exists()) {
                Log.w(TAG, "sentencepiece.bpe.model not found in ${modelDir.absolutePath}")
            }
            nativeHandle = nativeCreate(modelDir.absolutePath, spModel.absolutePath)
            if (nativeHandle != 0L) {
                Log.i(TAG, "CTranslate2 translator initialized")
            } else {
                Log.w(TAG, "CTranslate2 init returned null handle (stub mode or missing libs)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translator init failed: ${e.message}")
        }
    }

    /** Translate [text] synchronously on the calling thread (must be off main thread). */
    fun translate(text: String): String {
        if (nativeHandle == 0L) {
            return "[翻译引擎未加载。请参阅 SETUP.md 配置 CTranslate2 库。]"
        }
        return try {
            nativeTranslate(nativeHandle, text, SRC_LANG, TGT_LANG)
        } catch (e: Exception) {
            Log.e(TAG, "Translation exception: ${e.message}")
            "[翻译异常: ${e.message}]"
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
