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

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuroagent.NeuroAgentApp
import com.neuroagent.settings.CloudSlot
import com.neuroagent.settings.ProviderChoice
import com.neuroagent.system.NeuroAccessibilityService
import com.neuroagent.system.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as NeuroAgentApp
    val s = app.settings
    val scope = rememberCoroutineScope()

    var choice by remember { mutableStateOf(s.providerChoice) }
    var localPath by remember { mutableStateOf(s.localModelUri) }
    var status by remember { mutableStateOf("") }
    var audit by remember { mutableStateOf(s.auditLog()) }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            status = "Копирование модели…"
            // Copy into app-private storage: llama.cpp needs a real file path, not a content:// URI.
            val target = withContext(Dispatchers.IO) {
                val dir = File(context.filesDir, "models").apply { mkdirs() }
                val name = uri.lastPathSegment?.substringAfterLast('/')?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "model.gguf"
                File(dir, name).also { f ->
                    context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                }
            }
            localPath = target.absolutePath
            s.localModelUri = localPath
            status = "Модель сохранена: ${target.name}"
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Провайдер ИИ", style = MaterialTheme.typography.titleMedium)
        for (i in 0..2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = choice == ProviderChoice.Cloud(i), onClick = {
                    choice = ProviderChoice.Cloud(i); s.providerChoice = choice
                })
                Text("Облако, слот ${i + 1}")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = choice == ProviderChoice.Local, onClick = {
                choice = ProviderChoice.Local; s.providerChoice = choice
            })
            Text("Локально (GGUF)")
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        for (i in 0..2) CloudSlotEditor(i, s.cloudSlot(i)) { slot -> s.saveCloudSlot(i, slot); status = "Слот ${i + 1} сохранён" }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Локальная модель", style = MaterialTheme.typography.titleMedium)
        Text(if (localPath.isEmpty()) "Модель не выбрана" else localPath, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { pickModel.launch(arrayOf("*/*")) }, modifier = Modifier.padding(vertical = 4.dp)) { Text("Выбрать GGUF-файл") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Разрешения", style = MaterialTheme.typography.titleMedium)
        PermissionSwitch("Shell-команды (терминал и агент)", s.allowShell) { s.allowShell = it }
        PermissionSwitch("Привилегированные команды (Shizuku)", s.allowShizuku) { s.allowShizuku = it }
        PermissionSwitch("Действия на экране (клики, ввод)", s.allowAccessibilityActions) { s.allowAccessibilityActions = it }
        Text(
            "Accessibility: " + if (NeuroAccessibilityService.instance != null) "включён" else "выключен",
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Спец. возможности") }
            Button(onClick = { ShizukuManager.requestPermission() }) {
                Text(if (ShizukuManager.isGranted) "Shizuku: разрешено" else "Запросить Shizuku")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Системный промпт", style = MaterialTheme.typography.titleMedium)
        var prompt by remember { mutableStateOf(s.systemPrompt) }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it; s.systemPrompt = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Журнал команд", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { s.clearAuditLog(); audit = "" }) { Text("Очистить") }
        }
        Text(
            "Последние shell- и Shizuku-команды агента и терминала. Ключи API сюда не пишутся.",
            style = MaterialTheme.typography.bodySmall,
        )
        SelectionContainer {
            Text(
                audit.ifBlank { "Пока пусто" },
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        if (status.isNotEmpty()) Text(status, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CloudSlotEditor(index: Int, initial: CloudSlot, onSave: (CloudSlot) -> Unit) {
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("Слот ${index + 1} (OpenAI-совместимый)", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Endpoint, напр. https://api.openai.com/v1") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(
            key,
            { key = it },
            label = { Text("API key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(model, { model = it }, label = { Text("Model name, напр. gpt-4o-mini") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { onSave(CloudSlot(endpoint, key, model)) }) { Text("Сохранить слот ${index + 1}") }
    }
}

@Composable
private fun PermissionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var value by remember { mutableStateOf(checked) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = { value = it; onChange(it) })
    }
}
