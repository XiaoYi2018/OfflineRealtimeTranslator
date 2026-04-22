package com.bohanli.ruzhtranslator.translation

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide reference to the active GemmaTranslator instance.
 *
 * MainActivity populates this after model loading so other components can
 * reuse the already-loaded translator instead of loading a second copy of
 * the 2.37 GB Q4 model.
 */
object TranslatorHolder {
    private val ref = AtomicReference<GemmaTranslator?>()
    fun set(translator: GemmaTranslator?) { ref.set(translator) }
    fun get(): GemmaTranslator? = ref.get()
}
