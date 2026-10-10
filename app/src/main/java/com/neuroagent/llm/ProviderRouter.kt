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
import com.neuroagent.ai.LlmClient
import com.neuroagent.ai.OpenAiClient
import com.neuroagent.ai.StreamEvent
import com.neuroagent.ai.ToolSpec
import com.neuroagent.settings.ProviderChoice
import com.neuroagent.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient

/**
 * AI router (ТЗ 3.1): 3 cloud slots (OpenAI-compatible) + 1 local GGUF slot.
 * The active provider is read from settings on every request, so changes apply immediately.
 */
class ProviderRouter(
    private val settings: SettingsStore,
    private val http: OkHttpClient,
) : LlmClient {

    @Volatile private var local: LocalLlmClient? = null

    override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<StreamEvent> = flow {
        emitAll(resolve().stream(messages, tools))
    }

    /** Returns the client for the currently selected provider. Throws with a user-readable message. */
    @Synchronized
    fun resolve(): LlmClient = when (val choice = settings.providerChoice) {
        is ProviderChoice.Cloud -> {
            val slot = settings.cloudSlot(choice.slot)
            check(slot.isConfigured) { "Облачный слот ${choice.slot + 1} не настроен (endpoint и model обязательны)" }
            OpenAiClient(slot.endpoint, slot.apiKey, slot.model, http)
        }
        is ProviderChoice.Local -> {
            val path = settings.localModelUri
            check(path.isNotBlank()) { "Локальная модель не выбрана в Настройках" }
            val current = local
            if (current != null && current.modelPath == path) current
            else LocalLlmClient(path).also { local?.release(); local = it }
        }
    }

    /** Unloads the GGUF model so native memory can be reclaimed. */
    @Synchronized
    fun release() {
        local?.release()
        local = null
    }
}
