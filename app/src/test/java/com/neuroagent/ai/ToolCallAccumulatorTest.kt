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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallAccumulatorTest {
    @Test
    fun reassemblesArgumentsSplitAcrossDeltas() {
        val acc = ToolCallAccumulator()
        acc.add(StreamEvent.ToolCallDelta(0, "call_1", "shell_executor", ""))
        acc.add(StreamEvent.ToolCallDelta(0, null, null, "{\"command\":\"ec"))
        acc.add(StreamEvent.ToolCallDelta(0, null, null, "ho hi\"}"))
        acc.add(StreamEvent.ToolCallDelta(1, "call_2", "screen_reader", null))
        val calls = acc.build()
        assertEquals(2, calls.size)
        assertEquals("shell_executor", calls[0].name)
        assertEquals("{\"command\":\"echo hi\"}", calls[0].argumentsJson)
        assertEquals("{}", calls[1].argumentsJson)
    }

    @Test
    fun emptyWhenNoDeltas() {
        assertTrue(ToolCallAccumulator().build().isEmpty())
    }
}
