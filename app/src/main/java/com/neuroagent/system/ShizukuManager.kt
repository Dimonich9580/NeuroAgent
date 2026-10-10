/*
 * NeuroAgent - Autonomous AI Agent for Android
 * Copyright (C) 2026 NeuroAgent contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.neuroagent.system

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * Shizuku bridge (ТЗ 3.2.4). Commands run as the shell user (uid 2000) with ADB-level rights.
 * Shizuku.newProcess is not public API in 13.x, so it is reached via reflection.
 */
object ShizukuManager {
    private const val REQUEST_CODE = 4217

    val isAvailable: Boolean get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    val isGranted: Boolean
        get() = isAvailable && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun requestPermission() {
        if (isAvailable) Shizuku.requestPermission(REQUEST_CODE)
    }

    data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

    suspend fun exec(command: String, timeoutSec: Long = 30): ExecResult = withContext(Dispatchers.IO) {
        check(isGranted) { "Shizuku недоступен или разрешение не выдано" }
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        try {
            coroutineScope {
                // Drain stderr concurrently so a full pipe cannot block the child process.
                val stderrJob = async { process.errorStream.bufferedReader().use { it.readText() } }
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    stderrJob.cancel()
                    ExecResult(-1, stdout, "timeout after ${timeoutSec}s")
                } else {
                    ExecResult(process.exitValue(), stdout, stderrJob.await())
                }
            }
        } finally {
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
    }
}
