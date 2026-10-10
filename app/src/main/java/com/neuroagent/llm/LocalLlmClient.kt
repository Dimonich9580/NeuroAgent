/*
 * NeuroAgent - Autonomous AI Agent for Android
 * Copyright (C) 2026 NeuroAgent contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.neuroagent.llm

import com.neuroagent.ai.ChatMessage
import com.neuroagent.ai.LlmClient
import com.neuroagent.ai.Role
import com.neuroagent.ai.StreamEvent
import com.neuroagent.ai.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

/** Native binding to llama.cpp (see src/main/cpp/neuro_llama_jni.cpp). */
object NativeLlama {
    init { System.loadLibrary("neuro_llama") }

    /** Returns an opaque handle (0 on failure). */
    external fun load(modelPath: String, nCtx: Int, nThreads: Int): Long
    external fun free(handle: Long)
    /** Generates text for [prompt], calling [onToken] for each piece. Returns false if cancelled. */
    external fun generate(handle: Long, prompt: String, maxTokens: Int, onToken: (String) -> Boolean): Boolean
}

/**
 * On-device model via llama.cpp. Loads the GGUF file once and keeps it in memory.
 * [modelPath] must be a local file path (the UI copies the SAF URI into app storage first).
 */
class LocalLlmClient(
    val modelPath: String,
    private val nThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
) : LlmClient {

    @Volatile private var handle: Long = 0L

    @Synchronized
    private fun ensureLoaded(): Long {
        if (handle == 0L) {
            handle = NativeLlama.load(modelPath, nCtx = 4096, nThreads = nThreads)
            check(handle != 0L) { "Не удалось загрузить модель: $modelPath" }
        }
        return handle
    }

    override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<StreamEvent> =
        channelFlow {
            val h = ensureLoaded()
            val prompt = ChatTemplate.chatML(messages, tools)
            val parser = LocalToolCallParser { trySend(it) } // UNLIMITED buffer: never drops
            // Native generation is blocking; tokens arrive synchronously via the callback.
            NativeLlama.generate(h, prompt, maxTokens = 1024) { piece ->
                parser.feed(piece)
                !isClosedForSend // returning false stops generation when the collector is gone
            }
            parser.finish()
        }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    @Synchronized
    fun release() {
        if (handle != 0L) {
            NativeLlama.free(handle)
            handle = 0L
        }
    }
}

/** ChatML prompt (Qwen / many GGUF instruct models). Tools are described in the system prompt. */
internal object ChatTemplate {
    fun chatML(messages: List<ChatMessage>, tools: List<ToolSpec>): String {
        val sb = StringBuilder()
        val system = StringBuilder()
        messages.firstOrNull { it.role == Role.SYSTEM }?.content?.let { system.append(it) }
        if (tools.isNotEmpty()) {
            system.append("\n\n# Tools\nYou may call one or more functions. Call a function with a JSON object inside <tool_call></tool_call>:\n")
            system.append("<tool_call>{\"name\": <function-name>, \"arguments\": <args-json-object>}</tool_call>\n")
            system.append("Available functions (JSON Schema):\n")
            tools.forEach { system.append("{\"name\":\"${it.name}\",\"description\":\"${it.description.replace("\"", "'")}\",\"parameters\":${it.parametersSchemaJson}}\n") }
        }
        sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        for (m in messages) {
            when (m.role) {
                Role.SYSTEM -> Unit
                Role.USER -> sb.append("<|im_start|>user\n").append(m.content.orEmpty()).append("<|im_end|>\n")
                Role.ASSISTANT -> {
                    sb.append("<|im_start|>assistant\n").append(m.content.orEmpty())
                    m.toolCalls.forEach { c ->
                        sb.append("\n<tool_call>{\"name\":\"${c.name}\",\"arguments\":${c.argumentsJson}}</tool_call>")
                    }
                    sb.append("<|im_end|>\n")
                }
                Role.TOOL -> sb.append("<|im_start|>user\n<tool_response>\n").append(m.content.orEmpty()).append("\n</tool_response><|im_end|>\n")
            }
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}
