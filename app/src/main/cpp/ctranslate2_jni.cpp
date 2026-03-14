/**
 * ctranslate2_jni.cpp
 *
 * JNI bridge between NllbTranslator.kt and CTranslate2.
 *
 * Compiled in two modes:
 *   CTRANSLATE2_AVAILABLE=1  — links against prebuilt libctranslate2.so +
 *                               libsentencepiece.so for real NLLB translation.
 *   CTRANSLATE2_AVAILABLE=0  — stub mode, all functions return an error string.
 *
 * JNI name mangling for package com.bohanli.ruzhtranslator:
 *   underscores in the package name → _1
 *   so  ruzhtranslator  →  ruzhtranslator
 */

#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "TranslatorJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
#if CTRANSLATE2_AVAILABLE
// ---------------------------------------------------------------------------

#include <ctranslate2/translator.h>
#include <sentencepiece_processor.h>
#include <vector>
#include <stdexcept>

struct TranslatorCtx {
    ctranslate2::Translator* translator = nullptr;
    sentencepiece::SentencePieceProcessor* sp = nullptr;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_bohanli_ruzhtranslator_translation_NllbTranslator_nativeCreate(
        JNIEnv* env, jobject, jstring jModelPath, jstring jSpModelPath) {

    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    const char* spPath    = env->GetStringUTFChars(jSpModelPath, nullptr);

    auto* ctx = new TranslatorCtx();

    try {
        // Load SentencePiece tokeniser
        ctx->sp = new sentencepiece::SentencePieceProcessor();
        auto spStatus = ctx->sp->Load(spPath);
        if (!spStatus.ok()) {
            LOGE("SentencePiece load failed: %s", spStatus.ToString().c_str());
            throw std::runtime_error("SentencePiece load failed");
        }

        // Load CTranslate2 model (CPU, int8 supported natively)
        ctranslate2::ReplicaPoolConfig poolCfg;
        poolCfg.num_threads_per_replica = 4;
        ctx->translator = new ctranslate2::Translator(
            modelPath, ctranslate2::Device::CPU, ctranslate2::ComputeType::INT8,
            {0}, false, poolCfg);

        LOGI("CTranslate2 + SentencePiece loaded from %s", modelPath);
    } catch (const std::exception& e) {
        LOGE("Translator init error: %s", e.what());
        delete ctx->sp;
        delete ctx->translator;
        delete ctx;
        ctx = nullptr;
    }

    env->ReleaseStringUTFChars(jModelPath, modelPath);
    env->ReleaseStringUTFChars(jSpModelPath, spPath);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_bohanli_ruzhtranslator_translation_NllbTranslator_nativeTranslate(
        JNIEnv* env, jobject, jlong jHandle, jstring jText, jstring jSrcLang, jstring jTgtLang) {

    auto* ctx = reinterpret_cast<TranslatorCtx*>(jHandle);
    if (!ctx || !ctx->translator || !ctx->sp) {
        return env->NewStringUTF("[翻译引擎句柄无效]");
    }

    const char* text    = env->GetStringUTFChars(jText, nullptr);
    const char* srcLang = env->GetStringUTFChars(jSrcLang, nullptr);
    const char* tgtLang = env->GetStringUTFChars(jTgtLang, nullptr);

    std::string result;
    try {
        // SentencePiece encode
        std::vector<std::string> pieces;
        ctx->sp->Encode(text, &pieces);

        // NLLB source language token prepended, EOS appended
        pieces.insert(pieces.begin(), std::string(srcLang));
        pieces.push_back("</s>");

        // NLLB forced BOS = target language token
        std::vector<std::string> forcedBos = {std::string(tgtLang)};

        // Log source tokens for debugging
        std::string debugSrc;
        for (const auto& p : pieces) { debugSrc += p + " "; }
        LOGI("Source tokens (%zu): %s", pieces.size(), debugSrc.c_str());

        ctranslate2::TranslationOptions transOpts;
        transOpts.max_decoding_length = 300;
        transOpts.beam_size = 2;
        transOpts.no_repeat_ngram_size = 4;

        auto results = ctx->translator->translate_batch(
            {pieces},
            {forcedBos},
            transOpts
        );

        if (!results.empty() && !results[0].hypotheses.empty()) {
            const auto& hyp = results[0].hypotheses[0];
            // Log raw output tokens
            std::string debugOut;
            for (const auto& t : hyp) { debugOut += t + " "; }
            LOGI("Output tokens (%zu): %s", hyp.size(), debugOut.c_str());

            // Detokenise; strip language tokens and EOS
            std::vector<std::string> stripped;
            for (const auto& t : hyp) {
                if (t == std::string(srcLang) || t == std::string(tgtLang)) continue;
                if (t == "</s>") continue;
                stripped.push_back(t);
            }
            ctx->sp->Decode(stripped, &result);
            LOGI("Decoded result: %s", result.c_str());
        }
    } catch (const std::exception& e) {
        LOGE("Translation error: %s", e.what());
        result = std::string("[翻译错误: ") + e.what() + "]";
    }

    env->ReleaseStringUTFChars(jText, text);
    env->ReleaseStringUTFChars(jSrcLang, srcLang);
    env->ReleaseStringUTFChars(jTgtLang, tgtLang);

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_bohanli_ruzhtranslator_translation_NllbTranslator_nativeIsAvailable(
        JNIEnv* env, jobject, jlong jHandle) {
    auto* ctx = reinterpret_cast<TranslatorCtx*>(jHandle);
    return (ctx && ctx->translator && ctx->sp) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_bohanli_ruzhtranslator_translation_NllbTranslator_nativeDestroy(
        JNIEnv* env, jobject, jlong jHandle) {
    auto* ctx = reinterpret_cast<TranslatorCtx*>(jHandle);
    if (ctx) {
        delete ctx->translator;
        delete ctx->sp;
        delete ctx;
    }
}

} // extern "C"

// ---------------------------------------------------------------------------
#else  // CTRANSLATE2_AVAILABLE == 0  →  stub mode
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_bohanli_ruzhtranslator_translation_NllbTranslator_nativeCreate(
        JNIEnv* env, jobject, jstring jModelPath, jstring jSpModelPath) {
    LOGW("CTranslate2 not available (stub). See SETUP.md for build instructions.");
    (void)jModelPath; (void)jSpModelPath;
    return 0L;
}

JNIEXPORT jstring JNICALL
Java_com_bohanli_ruzhtranslator_translation_NllbTranslator_nativeTranslate(
        JNIEnv* env, jobject, jlong jHandle, jstring jText, jstring jSrcLang, jstring jTgtLang) {
    (void)jHandle; (void)jText; (void)jSrcLang; (void)jTgtLang;
    return env->NewStringUTF(
        "[翻译不可用: CTranslate2 库未编译入 APK。\n"
        "请按照 SETUP.md 编译 libctranslate2.so 并放入 jniLibs/arm64-v8a/]"
    );
}

JNIEXPORT jboolean JNICALL
Java_com_bohanli_ruzhtranslator_translation_NllbTranslator_nativeIsAvailable(
        JNIEnv* env, jobject, jlong jHandle) {
    (void)env; (void)jHandle;
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_bohanli_ruzhtranslator_translation_NllbTranslator_nativeDestroy(
        JNIEnv* env, jobject, jlong jHandle) {
    (void)env; (void)jHandle;
}

} // extern "C"

#endif  // CTRANSLATE2_AVAILABLE
