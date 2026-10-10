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

import com.neuroagent.ai.StreamEvent

/**
 * Local GGUF models have no native tool-calling API, so they emit calls as text:
 *   <tool_call>{"name": "...", "arguments": {...}}</tool_call>
 * This filter streams normal text through and turns tagged blocks into ToolCallDelta events.
 */
class LocalToolCallParser(private val emit: (StreamEvent) -> Unit) {
    private val open = "<tool_call>"
    private val close = "</tool_call>"
    private var pending = StringBuilder()
    private var inTool = false
    private var toolIndex = 0

    fun feed(token: String) {
        pending.append(token)
        while (true) {
            if (inTool) {
                val end = pending.indexOf(close)
                if (end < 0) return
                emitToolCall(pending.substring(0, end))
                pending = StringBuilder(pending.substring(end + close.length))
                inTool = false
            } else {
                val start = pending.indexOf(open)
                if (start >= 0) {
                    if (start > 0) emit(StreamEvent.ContentDelta(pending.substring(0, start)))
                    pending = StringBuilder(pending.substring(start + open.length))
                    inTool = true
                } else {
                    // keep a tail that might be the beginning of the open tag
                    val safe = pending.length - (open.length - 1)
                    if (safe > 0) {
                        emit(StreamEvent.ContentDelta(pending.substring(0, safe)))
                        pending = StringBuilder(pending.substring(safe))
                    }
                    return
                }
            }
        }
    }

    /** Call when generation has finished. Flushes buffered text. */
    fun finish() {
        if (!inTool && pending.isNotEmpty()) emit(StreamEvent.ContentDelta(pending.toString()))
        pending = StringBuilder()
        emit(StreamEvent.Finished(if (toolIndex > 0) "tool_calls" else "stop"))
    }

    private fun emitToolCall(body: String) {
        val parsed = ToolCallJson.parse(body.trim()) ?: run {
            emit(StreamEvent.ContentDelta(open + body + close)) // not a valid call: show as text
            return
        }
        val idx = toolIndex++
        emit(StreamEvent.ToolCallDelta(idx, "local_$idx", parsed.first, parsed.second))
    }
}

/** Minimal extraction of name + arguments JSON without depending on a full schema. */
internal object ToolCallJson {
    fun parse(body: String): Pair<String, String>? {
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(body).let { it as? kotlinx.serialization.json.JsonObject }
                ?: return null
            val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            val args = obj["arguments"]?.toString() ?: "{}"
            name to args
        } catch (_: Exception) {
            null
        }
    }
}
