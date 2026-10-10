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
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalToolCallParserTest {
    @Test
    fun streamsTextAndExtractsToolCallSplitAcrossTokens() {
        val events = mutableListOf<StreamEvent>()
        val parser = LocalToolCallParser { events += it }
        listOf("Hi <tool_", "call>{\"name\":\"echo\",", "\"arguments\":{\"x\":1}}</tool_", "call> done").forEach(parser::feed)
        parser.finish()

        val text = events.filterIsInstance<StreamEvent.ContentDelta>().joinToString("") { it.text }
        assertEquals("Hi  done", text)
        val call = events.filterIsInstance<StreamEvent.ToolCallDelta>().single()
        assertEquals("echo", call.name)
        assertEquals("{\"x\":1}", call.argumentsFragment)
        assertEquals(StreamEvent.Finished("tool_calls"), events.last())
    }

    @Test
    fun invalidToolBlockIsShownAsText() {
        val events = mutableListOf<StreamEvent>()
        val parser = LocalToolCallParser { events += it }
        parser.feed("<tool_call>not json</tool_call>")
        parser.finish()
        assertEquals(0, events.count { it is StreamEvent.ToolCallDelta })
        val text = events.filterIsInstance<StreamEvent.ContentDelta>().joinToString("") { it.text }
        assertEquals("<tool_call>not json</tool_call>", text)
    }
}
