/*
 * NeuroAgent - Autonomous AI Agent for Android
 * Copyright (C) 2026 NeuroAgent contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.neuroagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neuroagent.NeuroAgentApp
import com.neuroagent.agent.AgentEvent
import com.neuroagent.ai.ChatMessage
import com.neuroagent.ai.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One item shown in the chat list. */
sealed interface UiItem {
    data class User(val text: String) : UiItem
    data class Assistant(val text: String, val reasoning: String = "") : UiItem
    data class ToolCall(val name: String, val args: String, val result: String? = null, val ok: Boolean? = null) : UiItem
    data class Error(val text: String) : UiItem
}

data class ChatUiState(val items: List<UiItem> = emptyList(), val busy: Boolean = false)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as NeuroAgentApp
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Conversation sent to the model (system prompt is prepended on each request). */
    private val history = mutableListOf<ChatMessage>()
    private var job: Job? = null

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        history += ChatMessage(Role.USER, trimmed)
        _state.update { it.copy(items = it.items + UiItem.User(trimmed), busy = true) }
        // Start a fresh assistant bubble; the stream appends to it.
        _state.update { it.copy(items = it.items + UiItem.Assistant("")) }

        job = viewModelScope.launch {
            val request = listOf(ChatMessage(Role.SYSTEM, container.settings.systemPrompt)) + history
            try {
                container.newAgent().run(request).collect { ev -> handle(ev) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(UiItem.Error(e.message ?: e::class.java.simpleName))
            } finally {
                dropEmptyTrailingAssistant()
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        dropEmptyTrailingAssistant()
        _state.update { it.copy(busy = false) }
    }

    private fun handle(ev: AgentEvent) {
        when (ev) {
            is AgentEvent.Token -> updateLastAssistant { it.copy(text = it.text + ev.text) }
            is AgentEvent.Reasoning -> updateLastAssistant { it.copy(reasoning = it.reasoning + ev.text) }
            is AgentEvent.ToolStarted -> {
                // Close the current bubble, then show the tool call and a new bubble for the next step.
                append(UiItem.ToolCall(ev.call.name, ev.call.argumentsJson))
            }
            is AgentEvent.ToolFinished -> _state.update { st ->
                val items = st.items.toMutableList()
                val idx = items.indexOfLast { it is UiItem.ToolCall && it.result == null }
                if (idx >= 0) {
                    val tc = items[idx] as UiItem.ToolCall
                    items[idx] = tc.copy(result = ev.result.content, ok = ev.result.ok)
                }
                items += UiItem.Assistant("")
                st.copy(items = items)
            }
            is AgentEvent.Done -> {
                history += ChatMessage(Role.ASSISTANT, ev.finalText)
                dropEmptyTrailingAssistant()
            }
            is AgentEvent.Error -> {
                dropEmptyTrailingAssistant()
                append(UiItem.Error(ev.message))
            }
        }
    }

    private fun append(item: UiItem) = _state.update { it.copy(items = it.items + item) }

    private fun updateLastAssistant(f: (UiItem.Assistant) -> UiItem.Assistant) = _state.update { st ->
        val items = st.items.toMutableList()
        val idx = items.indexOfLast { it is UiItem.Assistant }
        if (idx >= 0) items[idx] = f(items[idx] as UiItem.Assistant) else items += f(UiItem.Assistant(""))
        st.copy(items = items)
    }

    private fun dropEmptyTrailingAssistant() = _state.update { st ->
        val last = st.items.lastOrNull()
        if (last is UiItem.Assistant && last.text.isEmpty() && last.reasoning.isEmpty()) st.copy(items = st.items.dropLast(1)) else st
    }
}
