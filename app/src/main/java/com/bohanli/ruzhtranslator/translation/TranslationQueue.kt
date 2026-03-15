package com.bohanli.ruzhtranslator.translation

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sequential translation queue.
 *
 * Segments are processed one at a time in submission order, so the Chinese
 * history is always appended in the correct sequence even if translation takes
 * variable time.
 *
 * [onResult] is called on the MAIN thread with each completed translation.
 * [onBusyChanged] is called on MAIN thread: true when a translation starts, false when it finishes.
 */
class TranslationQueue(
    private val translator: GemmaTranslator,
    private val onResult: (String) -> Unit,
    private val onStreamToken: (String) -> Unit = {},
    private val onStreamStart: () -> Unit = {},
    private val onBusyChanged: (busy: Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "TranslationQueue"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // UNLIMITED capacity so submitters never block
    private val channel = Channel<String>(Channel.UNLIMITED)

    init {
        // Single consumer coroutine guarantees ordering
        scope.launch {
            for (text in channel) {
                withContext(Dispatchers.Main) {
                    onBusyChanged(true)
                    onStreamStart()
                }
                Log.i(TAG, "Translating: ${text.take(60)}...")

                // Set up streaming callback before translation
                translator.onStreamToken = { token ->
                    scope.launch(Dispatchers.Main) { onStreamToken(token) }
                }

                val result = withContext(Dispatchers.IO) {
                    translator.translate(text)
                }

                translator.onStreamToken = null

                Log.i(TAG, "Result: ${result.take(60)}")
                withContext(Dispatchers.Main) {
                    onBusyChanged(false)
                    onResult(result)
                }
            }
        }
    }

    /** Submit a Russian segment for translation. Non-blocking. */
    fun submit(text: String) {
        if (text.isBlank()) return
        val offered = channel.trySend(text)
        if (offered.isFailure) {
            Log.e(TAG, "Channel send failed (should not happen with UNLIMITED capacity)")
        }
    }

    fun shutdown() {
        channel.close()
        scope.cancel()
    }
}
