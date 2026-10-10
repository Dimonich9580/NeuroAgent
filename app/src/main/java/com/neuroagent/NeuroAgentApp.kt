/*
 * NeuroAgent - Autonomous AI Agent for Android
 * Copyright (C) 2026 NeuroAgent contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.neuroagent

import android.app.Application
import android.content.ComponentCallbacks2
import com.neuroagent.agent.AgentLoop
import com.neuroagent.agent.ToolRegistry
import com.neuroagent.llm.ProviderRouter
import com.neuroagent.settings.SettingsStore
import com.neuroagent.tools.ActionExecutorTool
import com.neuroagent.tools.ScreenReaderTool
import com.neuroagent.tools.ShellExecutorTool
import com.neuroagent.tools.ShizukuControllerTool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container: small app, no DI framework (fewer deps for F-Droid audit). */
class NeuroAgentApp : Application() {

    val settings: SettingsStore by lazy { SettingsStore(this) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // long gaps are normal while a model thinks
            .retryOnConnectionFailure(true)
            .build()
    }

    val router: ProviderRouter by lazy { ProviderRouter(settings, http) }

    val registry: ToolRegistry by lazy {
        ToolRegistry(
            listOf(
                ScreenReaderTool(),
                ActionExecutorTool(settings),
                ShellExecutorTool(settings),
                ShizukuControllerTool(settings),
            ),
        )
    }

    fun newAgent(): AgentLoop = AgentLoop(router, registry)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            router.release()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        router.release()
    }
}
