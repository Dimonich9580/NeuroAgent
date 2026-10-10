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
 * Re-assembles tool calls from streamed deltas. OpenAI sends the first fragment with
 * id and name, then argument fragments keyed by `index`.
 */
class ToolCallAccumulator {
    private class Partial(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private val partials = sortedMapOf<Int, Partial>()

    fun add(delta: StreamEvent.ToolCallDelta) {
        val p = partials.getOrPut(delta.index) { Partial() }
        delta.id?.takeIf { it.isNotEmpty() }?.let { p.id = it }
        delta.name?.takeIf { it.isNotEmpty() }?.let { p.name = it }
        delta.argumentsFragment?.let { p.args.append(it) }
    }

    fun build(): List<ToolCall> = partials.values
        .filter { it.name.isNotEmpty() }
        .mapIndexed { i, p ->
            ToolCall(
                id = p.id.ifEmpty { "call_$i" },
                name = p.name,
                argumentsJson = p.args.toString().ifBlank { "{}" },
            )
        }

    fun isEmpty(): Boolean = partials.isEmpty()
}
