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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuroagent.NeuroAgentApp
import com.neuroagent.tools.ShellExecutorTool
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Converts a string with ANSI SGR colour codes (ESC[...m) into coloured spans. Other escapes are stripped. */
fun ansiToAnnotated(raw: String): AnnotatedString {
    val esc = Regex("\u001B\\[([0-9;]*)m|\u001B\\[[0-9;?]*[A-Za-z]")
    return buildAnnotatedString {
        var last = 0
        var fg: Color? = null
        var bold = false
        fun flush(end: Int) {
            if (end <= last) return
            val text = raw.substring(last, end)
            if (fg == null && !bold) append(text)
            else withStyle(SpanStyle(color = fg ?: Color.Unspecified, fontWeight = if (bold) FontWeight.Bold else null)) { append(text) }
        }
        for (m in esc.findAll(raw)) {
            flush(m.range.first)
            last = m.range.last + 1
            val codes = m.groupValues[1].split(';').mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) }
            for (c in codes) when (c) {
                0 -> { fg = null; bold = false }
                1 -> bold = true
                in 30..37 -> fg = ansiPalette[c - 30]
                in 90..97 -> fg = ansiPalette[c - 90 + 8]
                39 -> fg = null
            }
        }
        flush(raw.length)
    }
}

private val ansiPalette = listOf(
    Color(0xFF000000), Color(0xFFCD3131), Color(0xFF0DBC79), Color(0xFFE5E510),
    Color(0xFF2472C8), Color(0xFFBC3FBC), Color(0xFF11A8CD), Color(0xFFE5E5E5),
    Color(0xFF666666), Color(0xFFF14C4C), Color(0xFF23D18B), Color(0xFFF5F543),
    Color(0xFF3B8EEA), Color(0xFFD670D6), Color(0xFF29B8DB), Color(0xFFFFFFFF),
)

private class TermLine(val prompt: String, val text: AnnotatedString)

@Composable
fun TerminalScreen() {
    val app = LocalContext.current.applicationContext as NeuroAgentApp
    val tool = remember { ShellExecutorTool(app.settings) }
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<TermLine>() }
    val history = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

    fun run(cmd: String) {
        if (cmd.isBlank() || !app.settings.allowShell) return
        history.add(cmd)
        historyIndex = -1
        lines.add(TermLine("$ ", AnnotatedString(cmd)))
        scope.launch {
            val r = tool.execute(buildJsonObject { put("command", cmd) }.toString())
            lines.add(TermLine("", ansiToAnnotated(r.content)))
        }
        input = ""
    }

    Column(Modifier.fillMaxSize().padding(8.dp).background(Color(0xFF111111))) {
        if (!app.settings.allowShell) {
            Text(
                "Выполнение команд отключено. Включите «Shell-команды» в Настройках → Разрешения.",
                color = Color(0xFFF5F543), modifier = Modifier.padding(8.dp), fontSize = 13.sp,
            )
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(lines) { line ->
                Text(
                    buildAnnotatedString {
                        if (line.prompt.isNotEmpty()) withStyle(SpanStyle(color = Color(0xFF23D18B))) { append(line.prompt) }
                        append(line.text)
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color(0xFFE5E5E5),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { run(input) }),
            )
            Button(onClick = { run(input) }, enabled = app.settings.allowShell && input.isNotBlank()) { Text("▶") }
            Button(onClick = {
                if (history.isNotEmpty()) {
                    historyIndex = if (historyIndex < 0) history.lastIndex else (historyIndex - 1).coerceAtLeast(0)
                    input = history[historyIndex]
                }
            }) { Text("↑") }
        }
    }
}
