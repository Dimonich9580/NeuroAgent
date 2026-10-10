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
import com.neuroagent.ai.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Returns scripted turns one after another and records what the loop sent. */
private class ScriptedLlm(private val turns: List<List<StreamEvent>>) : LlmClient {
    val seen = mutableListOf<List<ChatMessage>>()
    private var i = 0
    override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<StreamEvent> {
        seen += messages.toList()
        return flowOf(*turns[i++].toTypedArray())
    }
}

private class EchoTool(override val spec: ToolSpec = ToolSpec("echo", "echo", "{}")) : Tool {
    override suspend fun execute(argumentsJson: String) = ToolResult(true, "echoed $argumentsJson")
}

class AgentLoopTest {
    @Test
    fun executesToolThenAnswers() = runBlocking {
        val llm = ScriptedLlm(
            listOf(
                listOf(
                    StreamEvent.ToolCallDelta(0, "c1", "echo", "{\"x\":1}"),
                    StreamEvent.Finished("tool_calls"),
                ),
                listOf(StreamEvent.ContentDelta("Готово"), StreamEvent.Finished("stop")),
            ),
        )
        val loop = AgentLoop(llm, ToolRegistry(listOf(EchoTool())))
        val events = loop.run(listOf(ChatMessage(Role.USER, "hi"))).toList()

        assertTrue(events.any { it is AgentEvent.ToolFinished && it.result.ok })
        assertEquals(AgentEvent.Done("Готово"), events.last())
        val toolMsg = llm.seen[1].last()
        assertEquals(Role.TOOL, toolMsg.role)
        assertEquals("c1", toolMsg.toolCallId)
        assertEquals("echoed {\"x\":1}", toolMsg.content)
    }

    @Test
    fun unknownToolIsReportedToModelNotThrown() = runBlocking {
        val llm = ScriptedLlm(
            listOf(
                listOf(StreamEvent.ToolCallDelta(0, "c1", "nope", "{}")),
                listOf(StreamEvent.ContentDelta("ok")),
            ),
        )
        val events = AgentLoop(llm, ToolRegistry(emptyList())).run(listOf(ChatMessage(Role.USER, "x"))).toList()
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals(false, finished.result.ok)
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun stopsAtStepLimit() = runBlocking {
        val callTurn = listOf(StreamEvent.ToolCallDelta(0, "c", "echo", "{}"))
        val llm = ScriptedLlm(List(3) { callTurn })
        val events = AgentLoop(llm, ToolRegistry(listOf(EchoTool())), maxSteps = 3).run(listOf(ChatMessage(Role.USER, "x"))).toList()
        assertTrue(events.last() is AgentEvent.Error)
    }
}
