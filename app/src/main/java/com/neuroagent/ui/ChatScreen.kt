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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.items.size, state.items.lastOrNull()) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    fun submit() {
        if (state.busy || input.isBlank()) return
        vm.send(input)
        input = ""
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(state.items) { _, item -> ChatItem(item) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Например: включи Wi-Fi") },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            if (state.busy) {
                TextButton(onClick = vm::stop) { Text("Стоп") }
            } else {
                Button(
                    onClick = { submit() },
                    enabled = input.isNotBlank(),
                ) { Text("Отправить") }
            }
        }
    }
}

@Composable
private fun ChatItem(item: UiItem) {
    when (item) {
        is UiItem.User -> Bubble(alignEnd = true) { Text(item.text) }
        is UiItem.Assistant -> Bubble(alignEnd = false) {
            if (item.reasoning.isNotEmpty()) ChainOfThought(item.reasoning)
            if (item.text.isEmpty() && item.reasoning.isEmpty()) Text("…")
            else MarkdownText(item.text)
        }
        is UiItem.ToolCall -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text("🔧 ${item.name}", style = MaterialTheme.typography.labelLarge)
                Text(item.args, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                item.result?.let { r ->
                    Text(
                        (if (item.ok == true) "✓ " else "✗ ") + r.take(1500),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        is UiItem.Error -> Text("⚠ ${item.text}", color = MaterialTheme.colorScheme.error)
    }
}

/** Collapsible Chain of Thought (ТЗ 3.3 «отображение Chain of Thought»). */
@Composable
private fun ChainOfThought(text: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = !open }) { Text(if (open) "▾ Ход рассуждений" else "▸ Ход рассуждений") }
        if (open) Text(text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Bubble(alignEnd: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (alignEnd) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) { Column(Modifier.padding(10.dp)) { content() } }
    }
}
