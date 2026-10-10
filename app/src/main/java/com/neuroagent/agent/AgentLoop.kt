/*
 * NeuroAgent - Autonomous AI Agent for Android
 * Copyright (C) 2026 NeuroAgent contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.neuroagent.agent

import com.neuroagent.ai.ChatMessage
import com.neuroagent.ai.LlmClient
import com.neuroagent.ai.Role
import com.neuroagent.ai.StreamEvent
import com.neuroagent.ai.ToolCall
import com.neuroagent.ai.ToolCallAccumulator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface AgentEvent {
    data class Token(val text: String) : AgentEvent
    data class Reasoning(val text: String) : AgentEvent
    data class ToolStarted(val call: ToolCall) : AgentEvent
    data class ToolFinished(val call: ToolCall, val result: ToolResult) : AgentEvent
    data class Done(val finalText: String) : AgentEvent
    data class Error(val message: String) : AgentEvent
}

/**
 * Tool-calling cycle:
 * request -> stream -> (tool_calls -> execute -> role=tool messages -> request again)* -> final answer.
 */
class AgentLoop(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val maxSteps: Int = 5,
) {
    /**
     * @param history conversation so far, including the new user message and system prompt.
     * The loop does not mutate the caller's list.
     */
    fun run(history: List<ChatMessage>): Flow<AgentEvent> = flow {
        val messages = history.toMutableList()
        repeat(maxSteps) {
            val text = StringBuilder()
            val acc = ToolCallAccumulator()

            llm.stream(messages, tools.enabledSpecs()).collect { ev ->
                when (ev) {
                    is StreamEvent.ContentDelta -> { text.append(ev.text); emit(AgentEvent.Token(ev.text)) }
                    is StreamEvent.ReasoningDelta -> emit(AgentEvent.Reasoning(ev.text))
                    is StreamEvent.ToolCallDelta -> acc.add(ev)
                    is StreamEvent.Finished -> Unit
                }
            }

            val calls = acc.build()
            if (calls.isEmpty()) {
                messages += ChatMessage(Role.ASSISTANT, text.toString())
                emit(AgentEvent.Done(text.toString()))
                return@flow
            }

            messages += ChatMessage(Role.ASSISTANT, text.toString().ifEmpty { null }, toolCalls = calls)
            for (call in calls) {
                emit(AgentEvent.ToolStarted(call))
                val tool = tools.find(call.name)
                val result = if (tool == null) {
                    ToolResult(false, "Unknown or disabled tool: ${call.name}")
                } else {
                    try {
                        tool.execute(call.argumentsJson)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ToolResult(false, "Tool crashed: ${e.message ?: e::class.simpleName}")
                    }
                }
                messages += ChatMessage(Role.TOOL, result.content, toolCallId = call.id)
                emit(AgentEvent.ToolFinished(call, result))
            }
        }
        emit(AgentEvent.Error("Достигнут лимит шагов агента ($maxSteps)"))
    }
}
