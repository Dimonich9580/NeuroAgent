/*
 * NeuroAgent - Autonomous AI Agent for Android
 * Copyright (C) 2026 NeuroAgent contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.neuroagent.tools

import com.neuroagent.agent.Tool
import com.neuroagent.agent.ToolResult
import com.neuroagent.ai.ToolSpec
import com.neuroagent.settings.SettingsStore
import com.neuroagent.system.NeuroAccessibilityService
import com.neuroagent.system.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

private val argJson = Json { ignoreUnknownKeys = true }

/** Parses the model's arguments object; returns null-safe accessor helpers. */
internal fun parseArgs(json: String): JsonObject =
    runCatching { argJson.parseToJsonElement(json) as JsonObject }.getOrElse { JsonObject(emptyMap()) }

internal fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
internal fun JsonObject.dbl(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

/** ТЗ 3.2.1 — reads the UI tree through AccessibilityService. */
class ScreenReaderTool : Tool {
    override val spec = ToolSpec(
        name = "screen_reader",
        description = "Возвращает дерево элементов текущего экрана (текст, описания, id, кликабельность, координаты). Вызывай перед действиями.",
        parametersSchemaJson = """{"type":"object","properties":{"max_nodes":{"type":"integer","description":"Максимум узлов, по умолчанию 300"}},"additionalProperties":false}""",
    )

    override fun isEnabled() = NeuroAccessibilityService.instance != null

    override suspend fun execute(argumentsJson: String): ToolResult {
        val service = NeuroAccessibilityService.instance
            ?: return ToolResult(false, "AccessibilityService не включён. Включите NeuroAgent в настройках специальных возможностей.")
        val max = parseArgs(argumentsJson).int("max_nodes")?.coerceIn(10, 1000) ?: 300
        return ToolResult(true, service.dumpTree(max))
    }
}

/** ТЗ 3.2.2 — клики, свайпы, ввод текста, глобальные действия. */
class ActionExecutorTool(private val settings: SettingsStore) : Tool {
    override val spec = ToolSpec(
        name = "action_executor",
        description = "Действия на экране: click_text (по тексту), click_id (по resource-id), tap (x,y), swipe (x1,y1,x2,y2), input_text (в активное поле), global (back, home, recents, notifications, quick_settings).",
        parametersSchemaJson = """{"type":"object","required":["action"],"properties":{
            "action":{"type":"string","enum":["click_text","click_id","tap","swipe","input_text","global"]},
            "text":{"type":"string"},
            "id":{"type":"string"},
            "x":{"type":"number"},"y":{"type":"number"},"x2":{"type":"number"},"y2":{"type":"number"},
            "global_action":{"type":"string","enum":["back","home","recents","notifications","quick_settings"]}
        },"additionalProperties":false}""",
    )

    override fun isEnabled() = settings.allowAccessibilityActions && NeuroAccessibilityService.instance != null

    override suspend fun execute(argumentsJson: String): ToolResult {
        val service = NeuroAccessibilityService.instance
            ?: return ToolResult(false, "AccessibilityService не подключён.")
        val a = parseArgs(argumentsJson)
        return when (a.str("action")) {
            "click_text" -> {
                val label = a.str("text") ?: return ToolResult(false, "Нужен параметр text")
                val ok = service.clickByText(label)
                ToolResult(ok, if (ok) "Нажат элемент «$label»" else "Элемент «$label» не найден или не кликабелен")
            }
            "click_id" -> {
                val id = a.str("id") ?: a.str("text") ?: return ToolResult(false, "Нужен параметр id")
                val ok = service.clickByResourceId(id)
                ToolResult(ok, if (ok) "Нажат элемент id=$id" else "Элемент id=$id не найден или не кликабелен")
            }
            "tap" -> {
                val x = a.dbl("x"); val y = a.dbl("y")
                if (x == null || y == null) return ToolResult(false, "Нужны x и y")
                val ok = service.tap(x.toFloat(), y.toFloat())
                ToolResult(ok, if (ok) "Тап ($x, $y)" else "Жест отклонён системой")
            }
            "swipe" -> {
                val x1 = a.dbl("x"); val y1 = a.dbl("y"); val x2 = a.dbl("x2"); val y2 = a.dbl("y2")
                if (listOf(x1, y1, x2, y2).any { it == null }) return ToolResult(false, "Нужны x, y, x2, y2")
                val ok = service.swipe(x1!!.toFloat(), y1!!.toFloat(), x2!!.toFloat(), y2!!.toFloat())
                ToolResult(ok, if (ok) "Свайп выполнен" else "Жест отклонён системой")
            }
            "input_text" -> {
                val text = a.str("text") ?: return ToolResult(false, "Нужен параметр text")
                val ok = service.inputText(text)
                ToolResult(ok, if (ok) "Текст введён" else "Нет активного поля ввода")
            }
            "global" -> {
                val code = when (a.str("global_action")) {
                    "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                    "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                    "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    "quick_settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    else -> return ToolResult(false, "Неизвестное global_action")
                }
                val ok = service.performGlobalAction(code)
                ToolResult(ok, if (ok) "Глобальное действие выполнено" else "Действие не выполнено")
            }
            else -> ToolResult(false, "Неизвестное action")
        }
    }
}

/** ТЗ 3.2.3 — bash-команды на устройстве (stdout/stderr). Требует разрешения в Настройках. */
class ShellExecutorTool(private val settings: SettingsStore) : Tool {
    override val spec = ToolSpec(
        name = "shell_executor",
        description = "Выполняет shell-команду (sh -c) в песочнице приложения. Возвращает exit code, stdout и stderr. Таймаут 30 с.",
        parametersSchemaJson = """{"type":"object","required":["command"],"properties":{"command":{"type":"string"}},"additionalProperties":false}""",
    )

    override fun isEnabled() = settings.allowShell

    override suspend fun execute(argumentsJson: String): ToolResult {
        val cmd = parseArgs(argumentsJson).str("command")?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "Нужен параметр command")
        return withContext(Dispatchers.IO) {
            val pb = ProcessBuilder("sh", "-c", cmd).directory(File("/data/local/tmp").takeIf { it.isDirectory } ?: File("/"))
            val process = pb.start()
            try {
                val out = StringBuilder()
                val err = StringBuilder()
                val tOut = Thread { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { appendCapped(out, it) } } }
                val tErr = Thread { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { appendCapped(err, it) } } }
                tOut.start(); tErr.start()
                val finished = process.waitFor(30, TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                tOut.join(1000); tErr.join(1000)
                val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
                val body = buildString {
                    append("exit_code=").append(code).append('\n')
                    if (out.isNotEmpty()) append("stdout:\n").append(out).append('\n')
                    if (err.isNotEmpty()) append("stderr:\n").append(err).append('\n')
                    if (!finished) append("(таймаут 30 с)\n")
                }
                settings.appendAuditLog("shell", cmd, code)
                ToolResult(finished && code == 0, body)
            } finally {
                if (process.isAlive) runCatching { process.destroyForcibly() }
            }
        }
    }

    private fun appendCapped(sb: StringBuilder, line: String) {
        if (sb.length < MAX_OUTPUT) sb.append(line).append('\n')
    }

    companion object { const val MAX_OUTPUT = 16_000 }
}

/** ТЗ 3.2.4 — привилегированные ADB-команды через Shizuku. */
class ShizukuControllerTool(private val settings: SettingsStore) : Tool {
    override val spec = ToolSpec(
        name = "shizuku_controller",
        description = "Выполняет команду от имени shell (ADB-уровень) через Shizuku. Примеры: 'svc wifi enable', 'svc wifi disable', 'settings put system screen_brightness 120', 'cmd bluetooth_manager enable'.",
        parametersSchemaJson = """{"type":"object","required":["command"],"properties":{"command":{"type":"string"}},"additionalProperties":false}""",
    )

    override fun isEnabled() = settings.allowShizuku && ShizukuManager.isGranted

    override suspend fun execute(argumentsJson: String): ToolResult {
        val cmd = parseArgs(argumentsJson).str("command")?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "Нужен параметр command")
        return try {
            val r = ShizukuManager.exec(cmd)
            settings.appendAuditLog("shizuku", cmd, r.exitCode)
            ToolResult(
                r.exitCode == 0,
                "exit_code=${r.exitCode}\n" +
                    (if (r.stdout.isNotBlank()) "stdout:\n${r.stdout.take(MAX)}\n" else "") +
                    (if (r.stderr.isNotBlank()) "stderr:\n${r.stderr.take(MAX)}\n" else ""),
            )
        } catch (e: IllegalStateException) {
            ToolResult(false, e.message ?: "Shizuku недоступен")
        }
    }

    companion object { private const val MAX = 16_000 }
}
