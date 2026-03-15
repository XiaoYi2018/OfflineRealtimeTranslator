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

    // KV cache prefix reuse: save state after decoding the fixed prompt prefix
    std::vector<uint8_t> prefix_state;
    int prefix_n_tokens = 0;  // number of tokens in the prefix (= starting pos for suffix)
};

// Fixed prefix — everything before the variable Russian text
static const char* PROMPT_PREFIX =
    "<start_of_turn>user\n"
    "Translate the following Russian text to Simplified Chinese. "
    "Output only the translation, nothing else.\n\n"
    "Russian: ";

// Build the suffix: Russian text + closing tags
static std::string build_suffix(const std::string& text) {
    return text + "\n"
           "Chinese:<end_of_turn>\n"
           "<start_of_turn>model\n";
}

// Full prompt (used only for logging)
static std::string build_prompt(const std::string& text) {
    return std::string(PROMPT_PREFIX) + build_suffix(text);
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
        model_params.n_gpu_layers = 0; // CPU only — Adreno Vulkan compute triggers ErrorDeviceLost
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

        // --- Pre-compute the fixed prompt prefix into KV cache ---
        const llama_vocab * vocab = llama_model_get_vocab(lctx->model);
        std::string prefix(PROMPT_PREFIX);
        int n_prefix_max = prefix.size() + 64;
        std::vector<llama_token> prefix_tokens(n_prefix_max);
        int n_prefix = llama_tokenize(vocab, prefix.c_str(), prefix.size(),
                                       prefix_tokens.data(), n_prefix_max, true, true);
        if (n_prefix > 0) {
            prefix_tokens.resize(n_prefix);
            llama_batch batch = llama_batch_get_one(prefix_tokens.data(), n_prefix);
            if (llama_decode(lctx->ctx, batch) == 0) {
                // Save the KV cache state for sequence 0
                size_t state_size = llama_state_seq_get_size(lctx->ctx, 0);
                lctx->prefix_state.resize(state_size);
                size_t written = llama_state_seq_get_data(lctx->ctx, lctx->prefix_state.data(),
                                                          lctx->prefix_state.size(), 0);
                lctx->prefix_state.resize(written);
                lctx->prefix_n_tokens = n_prefix;
                LOGI("Prefix KV cache saved: %d tokens, %zu bytes", n_prefix, written);
            } else {
                LOGW("Prefix decode failed, will fall back to full decode each call");
            }
        } else {
            LOGW("Prefix tokenization failed (%d), will fall back to full decode each call", n_prefix);
        }

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
    std::string suffix = build_suffix(text);
    env->ReleaseStringUTFChars(jText, text);

    std::string result;
    try {
        const llama_vocab * vocab = llama_model_get_vocab(lctx->model);

        bool using_prefix_cache = !lctx->prefix_state.empty();

        // Clear KV cache
        llama_memory_clear(llama_get_memory(lctx->ctx), true);

        auto t0 = std::chrono::steady_clock::now();

        if (using_prefix_cache) {
            // Restore the pre-computed prefix KV cache state
            size_t read = llama_state_seq_set_data(lctx->ctx, lctx->prefix_state.data(),
                                                    lctx->prefix_state.size(), 0);
            if (read == 0) {
                LOGW("Prefix state restore failed, falling back to full decode");
                using_prefix_cache = false;
            }
        }

        if (using_prefix_cache) {
            // Only tokenize and decode the suffix (variable part)
            int n_suffix_max = suffix.size() + 64;
            std::vector<llama_token> suffix_tokens(n_suffix_max);
            int n_suffix = llama_tokenize(vocab, suffix.c_str(), suffix.size(),
                                           suffix_tokens.data(), n_suffix_max, false, true);
            if (n_suffix < 0) {
                LOGE("Suffix tokenization failed");
                return env->NewStringUTF("[分词失败]");
            }
            suffix_tokens.resize(n_suffix);

            LOGI("Prefix reuse: %d cached tokens + %d suffix tokens", lctx->prefix_n_tokens, n_suffix);

            // Decode only the suffix, starting at the position after the prefix
            llama_batch batch = llama_batch_get_one(suffix_tokens.data(), n_suffix);
            if (llama_decode(lctx->ctx, batch) != 0) {
                LOGE("Suffix decode failed");
                return env->NewStringUTF("[解码失败]");
            }
        } else {
            // Fallback: full prompt decode (no prefix cache available)
            std::string prompt = std::string(PROMPT_PREFIX) + suffix;
            int n_prompt_max = prompt.size() + 256;
            std::vector<llama_token> tokens(n_prompt_max);
            int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                                           tokens.data(), n_prompt_max, true, true);
            if (n_tokens < 0) {
                LOGE("Tokenization failed");
                return env->NewStringUTF("[分词失败]");
            }
            tokens.resize(n_tokens);
            LOGI("Full prompt tokens: %d (no prefix cache)", n_tokens);

            llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
            if (llama_decode(lctx->ctx, batch) != 0) {
                LOGE("Prompt decode failed");
                return env->NewStringUTF("[解码失败]");
            }
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
