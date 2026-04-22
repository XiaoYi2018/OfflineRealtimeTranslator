package com.bohanli.ruzhtranslator.translation

import android.util.Log
import com.bohanli.ruzhtranslator.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private var channel = Channel<Pair<Int, String>>(Channel.UNLIMITED)

    // Generation counter: incremented on clear(), results from old generations are discarded
    private val generation = AtomicInteger(0)

    // Pause support: consumer waits on resumeSignal when paused
    private val paused = AtomicBoolean(false)
    private var resumeSignal = Channel<Unit>(1)

    init {
        startConsumer()
    }

    private fun startConsumer() {
        scope.launch {
            for ((gen, text) in channel) {
                // Skip items from old generations (queue was cleared)
                if (gen != generation.get()) continue

                // Wait if paused
                if (paused.get()) {
                    Log.i(TAG, "Consumer paused, waiting for resume...")
                    resumeSignal.receive()
                    Log.i(TAG, "Consumer resumed")
                }

                // Re-check generation after potential pause
                if (gen != generation.get()) continue

                val currentGen = gen
                withContext(Dispatchers.Main) {
                    onBusyChanged(true)
                    onStreamStart()
                }
                Log.i(TAG, "Translating: ${text.take(60)}...")

                translator.onStreamToken = { token ->
                    if (generation.get() == currentGen) {
                        scope.launch(Dispatchers.Main) { onStreamToken(token) }
                    }
                }

                var result = withContext(Dispatchers.IO) {
                    translator.translate(text)
                }

                translator.onStreamToken = null

                // Output-language verification + one-shot retry.
                // Skip if queue has moved on, or feature disabled, or result is an
                // error marker. Retry does NOT stream to UI (onStreamToken is null).
                if (AppSettings.driftRetryEnabled &&
                    generation.get() == currentGen &&
                    DriftDetector.isLikelyDrift(result)
                ) {
                    val firstRatio = DriftDetector.cjkRatio(result)
                    Log.w(TAG, "Drift detected (cjk=${"%.2f".format(firstRatio)}), retrying once")
                    try {
                        val retry = withContext(Dispatchers.IO) {
                            translator.translate(text)
                        }
                        val retryRatio = DriftDetector.cjkRatio(retry)
                        if (retryRatio > firstRatio && !retry.startsWith("[")) {
                            Log.i(TAG, "Retry improved cjk ${"%.2f".format(firstRatio)} → ${"%.2f".format(retryRatio)}")
                            result = retry
                        } else {
                            Log.i(TAG, "Retry no improvement (${"%.2f".format(retryRatio)}), keeping original")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Retry failed: ${e.message}, keeping first result")
                    }
                }

                // Only deliver result if generation hasn't changed
                if (generation.get() == currentGen) {
                    Log.i(TAG, "Result: ${result.take(60)}")
                    withContext(Dispatchers.Main) {
                        onBusyChanged(false)
                        onResult(result)
                    }
                } else {
                    Log.i(TAG, "Discarding stale result (gen $currentGen, now ${generation.get()})")
                    withContext(Dispatchers.Main) { onBusyChanged(false) }
                }
            }
        }
    }

    /** Submit a Russian segment for translation. Non-blocking. */
    fun submit(text: String) {
        if (text.isBlank()) return
        val offered = channel.trySend(generation.get() to text)
        if (offered.isFailure) {
            Log.e(TAG, "Channel send failed (should not happen with UNLIMITED capacity)")
        }
    }

    /** Clear pending translations. In-flight translation result will be discarded. */
    fun clear() {
        generation.incrementAndGet()
        // Drain the channel
        while (channel.tryReceive().isSuccess) { /* discard */ }
        Log.i(TAG, "Queue cleared, generation=${generation.get()}")
    }

    /** Pause the consumer after the current translation finishes. */
    fun pause() {
        paused.set(true)
        Log.i(TAG, "Queue paused")
    }

    /** Resume the consumer. */
    fun resume() {
        if (paused.compareAndSet(true, false)) {
            resumeSignal.trySend(Unit)
            Log.i(TAG, "Queue resumed")
        }
    }

    fun shutdown() {
        // Resume consumer if paused so it can exit cleanly
        paused.set(false)
        resumeSignal.trySend(Unit)
        channel.close()
        scope.cancel()
    }
}
