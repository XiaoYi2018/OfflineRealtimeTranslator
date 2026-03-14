/**
 * llama_jni.cpp
 *
 * JNI bridge between GemmaTranslator.kt and llama.cpp.
 * Uses Gemma 3 1B-IT (GGUF) for Russian → Chinese translation.
 *
 * JNI name mangling for package com.bohanli.ruzhtranslator:
 *   underscores in the package name → _1
 *   so  ruzhtranslator  →  ruzhtranslator
 */

#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "TranslatorJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlamaCtx {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * sampler = nullptr;
    int n_threads = 6;
};

static std::string build_prompt(const std::string& text) {
    return "<start_of_turn>user\n"
           "Translate the following Russian text to Simplified Chinese. "
           "Output only the translation, nothing else.\n\n"
           "Russian: " + text + "\n"
           "Chinese:<end_of_turn>\n"
           "<start_of_turn>model\n";
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_bohanli_ruzhtranslator_translation_GemmaTranslator_nativeCreate(
        JNIEnv* env, jobject, jstring jModelPath) {

    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    LOGI("Loading Gemma model from %s", modelPath);

    auto* lctx = new LlamaCtx();

    try {
        // Initialize llama backend
        llama_backend_init();

        // Load model
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0; // CPU only for now
        lctx->model = llama_model_load_from_file(modelPath, model_params);
        if (!lctx->model) {
            LOGE("Failed to load model from %s", modelPath);
            throw std::runtime_error("Model load failed");
        }

        // Create context
        auto ctx_params = llama_context_default_params();
        ctx_params.n_ctx = 512;
        ctx_params.n_batch = 512;
        ctx_params.n_threads = lctx->n_threads;
        ctx_params.n_threads_batch = lctx->n_threads;
        ctx_params.no_perf = true;
        lctx->ctx = llama_init_from_model(lctx->model, ctx_params);
        if (!lctx->ctx) {
            LOGE("Failed to create context");
            throw std::runtime_error("Context creation failed");
        }

        // Create sampler (greedy for deterministic translation)
        auto sparams = llama_sampler_chain_default_params();
        lctx->sampler = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(lctx->sampler, llama_sampler_init_greedy());

        LOGI("Gemma model loaded successfully");

    } catch (const std::exception& e) {
        LOGE("Init error: %s", e.what());
        if (lctx->sampler) llama_sampler_free(lctx->sampler);
        if (lctx->ctx) llama_free(lctx->ctx);
        if (lctx->model) llama_model_free(lctx->model);
        delete lctx;
        lctx = nullptr;
    }

    env->ReleaseStringUTFChars(jModelPath, modelPath);
    return reinterpret_cast<jlong>(lctx);
}

JNIEXPORT jstring JNICALL
Java_com_bohanli_ruzhtranslator_translation_GemmaTranslator_nativeTranslate(
        JNIEnv* env, jobject, jlong jHandle, jstring jText) {

    auto* lctx = reinterpret_cast<LlamaCtx*>(jHandle);
    if (!lctx || !lctx->model || !lctx->ctx) {
        return env->NewStringUTF("[翻译引擎句柄无效]");
    }

    const char* text = env->GetStringUTFChars(jText, nullptr);
    std::string prompt = build_prompt(text);
    env->ReleaseStringUTFChars(jText, text);

    LOGI("Prompt length: %zu chars", prompt.size());

    std::string result;
    try {
        const llama_vocab * vocab = llama_model_get_vocab(lctx->model);

        // Tokenize prompt
        int n_prompt_max = prompt.size() + 256;
        std::vector<llama_token> tokens(n_prompt_max);
        int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                                       tokens.data(), n_prompt_max, true, true);
        if (n_tokens < 0) {
            LOGE("Tokenization failed");
            return env->NewStringUTF("[分词失败]");
        }
        tokens.resize(n_tokens);
        LOGI("Prompt tokens: %d", n_tokens);

        // Clear KV cache
        llama_memory_clear(llama_get_memory(lctx->ctx), true);

        // Decode prompt
        auto t0 = std::chrono::steady_clock::now();
        llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
        if (llama_decode(lctx->ctx, batch) != 0) {
            LOGE("Prompt decode failed");
            return env->NewStringUTF("[解码失败]");
        }
        auto t1 = std::chrono::steady_clock::now();

        // Generate (short translations: 256 tokens max)
        const int max_gen = 256;
        std::string output;
        char piece_buf[128];

        for (int i = 0; i < max_gen; i++) {
            llama_token token = llama_sampler_sample(lctx->sampler, lctx->ctx, -1);

            // Check EOS
            if (llama_vocab_is_eog(vocab, token)) {
                LOGI("EOS at step %d", i);
                break;
            }

            // Decode token to text
            int n = llama_token_to_piece(vocab, token, piece_buf, sizeof(piece_buf), 0, true);
            if (n > 0) {
                output.append(piece_buf, n);
                // Stop if we hit end_of_turn marker (check AFTER append)
                auto eot = output.find("<end_of_turn>");
                if (eot != std::string::npos) {
                    output = output.substr(0, eot);
                    LOGI("end_of_turn at step %d", i);
                    break;
                }
            }

            // Prepare next token
            llama_batch next = llama_batch_get_one(&token, 1);
            if (llama_decode(lctx->ctx, next) != 0) {
                LOGE("Generation decode failed at step %d", i);
                break;
            }
        }
        auto t2 = std::chrono::steady_clock::now();

        auto promptMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
        auto genMs = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();
        LOGI("Timing: prompt=%lldms generation=%lldms", (long long)promptMs, (long long)genMs);

        // Clean up output: trim whitespace
        auto start = output.find_first_not_of(" \t\n\r");
        auto end = output.find_last_not_of(" \t\n\r");
        if (start != std::string::npos && end != std::string::npos) {
            result = output.substr(start, end - start + 1);
        } else {
            result = output;
        }

        // Remove any trailing <end_of_turn> that might have partially accumulated
        auto eot_pos = result.find("<end_of_turn>");
        if (eot_pos != std::string::npos) {
            result = result.substr(0, eot_pos);
        }

        LOGI("Generated %zu chars: %s", result.size(), result.c_str());

    } catch (const std::exception& e) {
        LOGE("Translation error: %s", e.what());
        result = std::string("[翻译错误: ") + e.what() + "]";
    }

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_bohanli_ruzhtranslator_translation_GemmaTranslator_nativeIsAvailable(
        JNIEnv* env, jobject, jlong jHandle) {
    auto* lctx = reinterpret_cast<LlamaCtx*>(jHandle);
    return (lctx && lctx->model && lctx->ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_bohanli_ruzhtranslator_translation_GemmaTranslator_nativeDestroy(
        JNIEnv* env, jobject, jlong jHandle) {
    auto* lctx = reinterpret_cast<LlamaCtx*>(jHandle);
    if (lctx) {
        if (lctx->sampler) llama_sampler_free(lctx->sampler);
        if (lctx->ctx) llama_free(lctx->ctx);
        if (lctx->model) llama_model_free(lctx->model);
        delete lctx;
    }
    llama_backend_free();
}

} // extern "C"
