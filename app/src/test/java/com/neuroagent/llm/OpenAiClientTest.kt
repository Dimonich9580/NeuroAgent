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
import com.neuroagent.ai.LlmHttpException
import com.neuroagent.ai.OpenAiClient
import com.neuroagent.ai.Role
import com.neuroagent.ai.StreamEvent
import com.neuroagent.ai.ToolCallAccumulator
import com.neuroagent.ai.ToolSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun streamsContentAndToolCallsAndSendsTools() = runBlocking {
        val sse = """
            data: {"choices":[{"delta":{"reasoning_content":"думаю"}}]}

            data: {"choices":[{"delta":{"content":"Прив"}}]}

            data: {"choices":[{"delta":{"content":"ет"}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9","function":{"name":"screen_reader","arguments":"{}"}}]}}]}

            data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

            data: [DONE]

        """.trimIndent() + "\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))

        val client = OpenAiClient(server.url("/v1").toString(), "sk-test", "gpt-test", OkHttpClient())
        val tools = listOf(ToolSpec("screen_reader", "read screen", """{"type":"object","properties":{}}"""))
        val events = client.stream(listOf(ChatMessage(Role.USER, "hi")), tools).toList()

        assertTrue(events.contains(StreamEvent.ReasoningDelta("думаю")))
        assertEquals("Привет", events.filterIsInstance<StreamEvent.ContentDelta>().joinToString("") { it.text })
        val tc = events.filterIsInstance<StreamEvent.ToolCallDelta>().single()
        assertEquals("call_9", tc.id)
        assertEquals("screen_reader", tc.name)
        assertEquals(0, tc.index)
        assertEquals(StreamEvent.Finished("tool_calls"), events.last())

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"name\":\"screen_reader\""))
        assertTrue(body.contains("\"tool_choice\":\"auto\""))
    }

    @Test
    fun parsesNumericIndexesForParallelToolCalls() {
        val client = OpenAiClient("http://localhost/v1", "x", "m", OkHttpClient())
        val first = client.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c0","function":{"name":"screen_reader","arguments":"{"}}]}}]}""",
        ).filterIsInstance<StreamEvent.ToolCallDelta>().single()
        val second = client.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"c1","function":{"name":"shell_executor","arguments":"{"}}]}}]}""",
        ).filterIsInstance<StreamEvent.ToolCallDelta>().single()
        val stringIndex = client.parseChunk(
            """{"choices":[{"delta":{"tool_calls":[{"index":"2","id":"c2","function":{"name":"echo","arguments":"{}"}}]}}]}""",
        ).filterIsInstance<StreamEvent.ToolCallDelta>().single()

        assertEquals(0, first.index)
        assertEquals("c0", first.id)
        assertEquals(1, second.index)
        assertEquals("c1", second.id)
        assertEquals(2, stringIndex.index)

        val acc = ToolCallAccumulator()
        acc.add(first)
        acc.add(StreamEvent.ToolCallDelta(0, null, null, "}"))
        acc.add(second)
        acc.add(StreamEvent.ToolCallDelta(1, null, null, "}"))
        val calls = acc.build()
        assertEquals(2, calls.size)
        assertEquals("screen_reader", calls[0].name)
        assertEquals("shell_executor", calls[1].name)
        assertEquals("{}", calls[0].argumentsJson)
        assertEquals("{}", calls[1].argumentsJson)
    }

    @Test
    fun httpErrorIsReportedWithStatusAndBody() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        val client = OpenAiClient(server.url("/v1").toString(), "x", "m", OkHttpClient())
        val ex = runCatching { runBlocking { client.stream(listOf(ChatMessage(Role.USER, "hi")), emptyList()).toList() } }
            .exceptionOrNull() as? LlmHttpException
        assertEquals(401, ex?.code)
        assertEquals("bad key", ex?.body)
    }
}
