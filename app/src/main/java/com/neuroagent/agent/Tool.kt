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

import com.neuroagent.ai.ToolSpec

data class ToolResult(val ok: Boolean, val content: String)

/** A capability the agent can call. Implementations must never throw for expected failures. */
interface Tool {
    val spec: ToolSpec

    /** Whether this tool is currently allowed (e.g. permission granted in Settings). */
    fun isEnabled(): Boolean = true

    suspend fun execute(argumentsJson: String): ToolResult
}

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.spec.name }

    /** Specs of enabled tools only: disabled tools are hidden from the model. */
    fun enabledSpecs(): List<ToolSpec> = byName.values.filter { it.isEnabled() }.map { it.spec }

    fun find(name: String): Tool? = byName[name]?.takeIf { it.isEnabled() }
}
