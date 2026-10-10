/*
 * NeuroAgent - Autonomous AI Agent for Android
 * Copyright (C) 2026 NeuroAgent contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.neuroagent.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** One cloud model slot: OpenAI-compatible endpoint, key, model name. */
data class CloudSlot(
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val isConfigured: Boolean get() = endpoint.isNotBlank() && model.isNotBlank()
}

/** Which backend the agent uses for the next request. */
sealed interface ProviderChoice {
    data class Cloud(val slot: Int) : ProviderChoice // 0..2
    data object Local : ProviderChoice
}

/**
 * Stores settings. API keys live in EncryptedSharedPreferences (AES256-GCM, Android Keystore).
 * Non-secret settings use the same encrypted file for simplicity.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "neuroagent_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun cloudSlot(index: Int): CloudSlot {
        require(index in 0..2) { "Cloud slot must be 0..2" }
        return CloudSlot(
            endpoint = prefs.getString("slot$index.endpoint", "") ?: "",
            apiKey = prefs.getString("slot$index.key", "") ?: "",
            model = prefs.getString("slot$index.model", "") ?: "",
        )
    }

    fun saveCloudSlot(index: Int, slot: CloudSlot) {
        require(index in 0..2)
        prefs.edit()
            .putString("slot$index.endpoint", slot.endpoint.trim())
            .putString("slot$index.key", slot.apiKey.trim())
            .putString("slot$index.model", slot.model.trim())
            .apply()
    }

    var providerChoice: ProviderChoice
        get() = when (val v = prefs.getString("provider", "cloud0")) {
            "local" -> ProviderChoice.Local
            else -> ProviderChoice.Cloud((v ?: "cloud0").removePrefix("cloud").toIntOrNull() ?: 0)
        }
        set(value) {
            val s = when (value) {
                is ProviderChoice.Local -> "local"
                is ProviderChoice.Cloud -> "cloud${value.slot}"
            }
            prefs.edit().putString("provider", s).apply()
        }

    var localModelUri: String
        get() = prefs.getString("local.uri", "") ?: ""
        set(value) { prefs.edit().putString("local.uri", value).apply() }

    var systemPrompt: String
        get() = prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) { prefs.edit().putString("system_prompt", value).apply() }

    /** Permission gates (ТЗ 3.3 «управление разрешениями»). All default to OFF. */
    var allowShell: Boolean
        get() = prefs.getBoolean("perm.shell", false)
        set(value) { prefs.edit().putBoolean("perm.shell", value).apply() }

    var allowShizuku: Boolean
        get() = prefs.getBoolean("perm.shizuku", false)
        set(value) { prefs.edit().putBoolean("perm.shizuku", value).apply() }

    var allowAccessibilityActions: Boolean
        get() = prefs.getBoolean("perm.a11y_actions", false)
        set(value) { prefs.edit().putBoolean("perm.a11y_actions", value).apply() }

    fun appendAuditLog(source: String, command: String, exitCode: Int) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val line = "$stamp [$source] exit=$exitCode ${command.replace('\n', ' ').take(200)}"
        val prev = prefs.getString("audit.log", "") ?: ""
        val next = (prev.lineSequence().filter { it.isNotBlank() } + line).toList().takeLast(40).joinToString("\n")
        prefs.edit().putString("audit.log", next).apply()
    }

    fun auditLog(): String = prefs.getString("audit.log", "") ?: ""

    fun clearAuditLog() {
        prefs.edit().remove("audit.log").apply()
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Ты — NeuroAgent, автономный агент на Android. Для действий на устройстве используй инструменты. " +
                "Сначала проверь состояние экрана, затем действуй, и после каждого действия проверяй результат. " +
                "Отвечай кратко, на языке пользователя."
    }
}
