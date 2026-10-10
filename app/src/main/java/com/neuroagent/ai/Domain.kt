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

/** Transport-independent chat types. JSON mapping lives in the OpenAI client. */
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ToolCall(
    val id: String,
    val name: String,
    /** Raw JSON object string produced by the model. */
    val argumentsJson: String,
)

data class ChatMessage(
    val role: Role,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/** JSON-Schema based tool description sent to the model. */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema of the parameters object, as a string. */
    val parametersSchemaJson: String,
)

/** Events produced by a streaming completion. */
sealed interface StreamEvent {
    data class ContentDelta(val text: String) : StreamEvent
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsFragment: String?,
    ) : StreamEvent
    data class Finished(val reason: String?) : StreamEvent
}

/** Anything that can run a streaming chat completion (cloud or local). */
interface LlmClient {
    /** Streams events for one model turn. Implementations must be cancellation-aware. */
    fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): kotlinx.coroutines.flow.Flow<StreamEvent>
}
