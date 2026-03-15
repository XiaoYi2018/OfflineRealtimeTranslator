package com.bohanli.ruzhtranslator.translation

import android.util.Log
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

                val result = withContext(Dispatchers.IO) {
                    translator.translate(text)
                }

                translator.onStreamToken = null

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
