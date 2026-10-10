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
import org.junit.Test

class SseParserTest {
    @Test
    fun yieldsDataPayloadsAndStopsAtDone() {
        val lines = sequenceOf(
            "data: {\"a\":1}", "",
            ": keep-alive", "",
            "event: message", "data: line1", "data: line2", "",
            "data: [DONE]", "",
            "data: ignored-after-done", "",
        )
        assertEquals(listOf("{\"a\":1}", "line1\nline2"), SseParser.dataEvents(lines).toList())
    }

    @Test
    fun handlesCrlfAndMissingTrailingBlankLine() {
        val lines = sequenceOf("data: x\r", "\r", "data: y")
        assertEquals(listOf("x", "y"), SseParser.dataEvents(lines).toList())
    }
}
