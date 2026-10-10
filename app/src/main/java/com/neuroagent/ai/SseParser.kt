/*
 * NeuroAgent - Autonomous AI Agent for Android
 * Copyright (C) 2026 NeuroAgent contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.neuroagent.ai

/**
 * Parses a Server-Sent Events stream (text/event-stream) and yields the `data:` payloads.
 * Multi-line data fields are joined with '\n'. The stream ends at `[DONE]` (OpenAI convention).
 */
object SseParser {

    fun dataEvents(lines: Sequence<String>): Sequence<String> = sequence {
        val buffer = StringBuilder()
        var hasData = false
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            when {
                line.isEmpty() -> {
                    if (hasData) {
                        val payload = buffer.toString()
                        buffer.clear()
                        hasData = false
                        if (payload == "[DONE]") return@sequence
                        yield(payload)
                    }
                }
                line.startsWith(":") -> Unit // comment / keep-alive
                line.startsWith("data:") -> {
                    if (hasData) buffer.append('\n')
                    buffer.append(line.removePrefix("data:").removePrefix(" "))
                    hasData = true
                }
                else -> Unit // event:, id:, retry: are not used by OpenAI-compatible APIs
            }
        }
        if (hasData) {
            val payload = buffer.toString()
            if (payload != "[DONE]") yield(payload)
        }
    }
}
