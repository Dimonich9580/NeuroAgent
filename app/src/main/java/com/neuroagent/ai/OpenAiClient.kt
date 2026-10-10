/*
 * NeuroAgent - Autonomous AI Agent for Android
 * Copyright (C) 2026 NeuroAgent contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.neuroagent.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class LlmHttpException(val code: Int, val body: String) :
    RuntimeException("HTTP $code: ${body.take(500)}")

/**
 * OpenAI Chat Completions compatible client (streaming, tools).
 * Works with OpenAI, OpenRouter, Ollama (/v1), LM Studio, vLLM, etc.
 */
class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient,
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<StreamEvent> = callbackFlow {
        val body = buildRequestBody(messages, tools).toString()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) close() else close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    try {
                        if (!resp.isSuccessful) {
                            close(LlmHttpException(resp.code, resp.body?.string().orEmpty()))
                            return
                        }
                        val source = resp.body?.source()
                        if (source == null) {
                            close()
                            return
                        }
                        val lines = generateSequence {
                            try {
                                source.readUtf8Line()
                            } catch (_: IOException) {
                                null
                            }
                        }
                        for (payload in SseParser.dataEvents(lines)) {
                            for (event in parseChunk(payload)) {
                                // Blocking send: SSE must not drop tokens if the collector is busy.
                                if (!trySendBlocking(event).isSuccess) return
                            }
                        }
                        close()
                    } catch (e: Exception) {
                        if (call.isCanceled()) close() else close(e)
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    internal fun buildRequestBody(messages: List<ChatMessage>, tools: List<ToolSpec>): JsonObject =
        buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { m -> add(encodeMessage(m)) }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", json.parseToJsonElement(t.parametersSchemaJson))
                            }
                        })
                    }
                }
                put("tool_choice", "auto")
            }
        }

    private fun encodeMessage(m: ChatMessage): JsonObject = buildJsonObject {
        put("role", m.role.name.lowercase())
        if (m.content != null) put("content", m.content) else put("content", JsonPrimitive(null as String?))
        if (m.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                m.toolCalls.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", c.name)
                            put("arguments", c.argumentsJson)
                        }
                    })
                }
            }
        }
        m.toolCallId?.let { put("tool_call_id", it) }
    }

    /** Converts one SSE chunk into zero or more [StreamEvent]s. */
    internal fun parseChunk(payload: String): List<StreamEvent> {
        val root = json.parseToJsonElement(payload).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        val out = mutableListOf<StreamEvent>()
        val delta = choice["delta"]?.jsonObject
        delta?.get("content")?.stringOrNull()?.takeIf { it.isNotEmpty() }?.let {
            out += StreamEvent.ContentDelta(it)
        }
        delta?.get("reasoning_content")?.stringOrNull()?.takeIf { it.isNotEmpty() }?.let {
            out += StreamEvent.ReasoningDelta(it)
        }
        (delta?.get("tool_calls") as? JsonArray)?.forEach { el ->
            val tc = el.jsonObject
            val fn = tc["function"]?.jsonObject
            out += StreamEvent.ToolCallDelta(
                index = parseToolCallIndex(tc["index"]),
                id = tc["id"]?.stringOrNull(),
                name = fn?.get("name")?.stringOrNull(),
                argumentsFragment = fn?.get("arguments")?.stringOrNull(),
            )
        }
        choice["finish_reason"]?.stringOrNull()?.let { out += StreamEvent.Finished(it) }
        return out
    }

    /**
     * OpenAI sends `index` as a JSON number. Some proxies stringify it.
     * `contentOrNull` only works for JSON strings, so a numeric index would
     * collapse every parallel tool call onto slot 0.
     */
    internal fun parseToolCallIndex(element: JsonElement?): Int {
        val primitive = element as? JsonPrimitive ?: return 0
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull() ?: 0
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
