/*
 * NeuroAgent - Autonomous AI Agent for Android
 * Copyright (C) 2026 NeuroAgent contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
// JNI bridge between com.neuroagent.llm.NativeLlama and llama.cpp (pinned tag b11514).
// Only the public C API from llama.h is used, so the bridge stays small and auditable.
#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

#define LOG_TAG "NeuroLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaHandle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::mutex mutex; // one generation at a time per handle
};

bool g_backend_ready = false;

// Length of the longest prefix that ends on a complete UTF-8 sequence.
// Tokens can split multi-byte characters; those tail bytes are kept for the next piece.
size_t utf8_complete_prefix(const std::string &s) {
    size_t n = s.size();
    // look back at most 3 bytes for a lead byte
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue; // continuation byte
        size_t need = (c >= 0xF0) ? 4 : (c >= 0xE0) ? 3 : (c >= 0xC0) ? 2 : 1;
        return (back >= need) ? n : n - back;
    }
    return n;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_neuroagent_llm_NativeLlama_load(JNIEnv *env, jobject, jstring jpath, jint nCtx, jint nThreads) {
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }
    const char *path = env->GetStringUTFChars(jpath, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only: reproducible and no vendor GPU blobs
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) {
        LOGE("failed to load model");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(nCtx);
    cparams.n_batch = 512;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *h = new LlamaHandle();
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_neuroagent_llm_NativeLlama_free(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<LlamaHandle *>(handle);
    if (!h) return;
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        llama_free(h->ctx);
        llama_model_free(h->model);
    }
    delete h;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neuroagent_llm_NativeLlama_generate(JNIEnv *env, jobject, jlong handle, jstring jprompt,
                                             jint maxTokens, jobject onToken) {
    auto *h = reinterpret_cast<LlamaHandle *>(handle);
    if (!h) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(h->mutex);

    // Callback: kotlin.jvm.functions.Function1<String, Boolean>
    jclass fnClass = env->GetObjectClass(onToken);
    jmethodID invoke = env->GetMethodID(fnClass, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    jclass boolClass = env->FindClass("java/lang/Boolean");
    jmethodID booleanValue = env->GetMethodID(boolClass, "booleanValue", "()Z");

    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string text(prompt);
    env->ReleaseStringUTFChars(jprompt, prompt);

    // Fresh context for every request (stateless API; the app sends full history each time).
    llama_memory_clear(llama_get_memory(h->ctx), true);

    // Tokenize: first call with a null buffer returns the negative required size.
    int32_t n = -llama_tokenize(h->vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    if (n <= 0) return JNI_FALSE;
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(h->vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), n, true, true) < 0) {
        LOGE("tokenize failed");
        return JNI_FALSE;
    }
    if (static_cast<uint32_t>(n) + static_cast<uint32_t>(maxTokens) > llama_n_ctx(h->ctx)) {
        LOGE("prompt too long for context: %d tokens", n);
        return JNI_FALSE;
    }

    if (llama_decode(h->ctx, llama_batch_get_one(tokens.data(), n)) != 0) {
        LOGE("prompt decode failed");
        return JNI_FALSE;
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string pending; // bytes not yet forming a complete UTF-8 character
    bool completed = true;
    for (int i = 0; i < maxTokens; ++i) {
        llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, id)) break;

        char buf[256];
        int len = llama_token_to_piece(h->vocab, id, buf, sizeof(buf), 0, true);
        if (len < 0) break;
        pending.append(buf, static_cast<size_t>(len));

        size_t cut = utf8_complete_prefix(pending);
        if (cut > 0) {
            jstring piece = env->NewStringUTF(pending.substr(0, cut).c_str());
            pending.erase(0, cut);
            jobject ret = env->CallObjectMethod(onToken, invoke, piece);
            env->DeleteLocalRef(piece);
            if (env->ExceptionCheck() || !ret || !env->CallBooleanMethod(ret, booleanValue)) {
                completed = false; // collector cancelled or Kotlin threw
                break;
            }
            env->DeleteLocalRef(ret);
        }

        if (llama_decode(h->ctx, llama_batch_get_one(&id, 1)) != 0) {
            LOGE("decode failed at step %d", i);
            break;
        }
    }
    llama_sampler_free(smpl);
    return completed ? JNI_TRUE : JNI_FALSE;
}
