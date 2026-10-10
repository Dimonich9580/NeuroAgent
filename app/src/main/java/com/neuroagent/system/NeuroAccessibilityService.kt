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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Backs the `screen_reader` and `action_executor` tools (ТЗ 3.2.1, 3.2.2).
 * Only the static [instance] is shared; all work happens on the main thread callbacks.
 */
class NeuroAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /** Human-readable UI tree, one node per line, limited to [maxNodes]. */
    fun dumpTree(maxNodes: Int = 300): String {
        val root = rootInActiveWindow ?: return "Нет активного окна (или нет доступа к содержимому)."
        val out = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes) return
            count++
            val r = Rect().also { node.getBoundsInScreen(it) }
            val text = node.text?.toString()?.take(200).orEmpty()
            val desc = node.contentDescription?.toString()?.take(200).orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            val flags = buildList {
                if (node.isClickable) add("clickable")
                if (node.isEditable) add("editable")
                if (node.isScrollable) add("scrollable")
                if (node.isFocused) add("focused")
            }
            if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty() || flags.isNotEmpty()) {
                out.append("  ".repeat(depth)).append(node.className?.toString()?.substringAfterLast('.'))
                if (id.isNotEmpty()) out.append(" id=").append(id)
                if (text.isNotEmpty()) out.append(" text=\"").append(text).append('"')
                if (desc.isNotEmpty()) out.append(" desc=\"").append(desc).append('"')
                if (flags.isNotEmpty()) out.append(" [").append(flags.joinToString(",")).append(']')
                out.append(" bounds=").append(r.flattenToString()).append('\n')
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    walk(child, depth + 1)
                    recycleSafely(child)
                }
            }
        }
        try {
            walk(root, 0)
        } finally {
            recycleSafely(root)
        }
        if (count >= maxNodes) out.append("… (обрезано до $maxNodes узлов)\n")
        return out.toString().ifBlank { "Экран пуст." }
    }

    /** Clicks the first clickable node whose text, description or view id contains [label]. */
    fun clickByText(label: String): Boolean {
        val needle = label.trim()
        if (needle.isEmpty()) return false
        return withRoot { root ->
            clickMatching(root) { node ->
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val id = node.viewIdResourceName.orEmpty()
                text.contains(needle, ignoreCase = true) ||
                    desc.contains(needle, ignoreCase = true) ||
                    id.contains(needle, ignoreCase = true)
            }
        } ?: false
    }

    /** Clicks the first node whose view id equals or ends with [viewId]. */
    fun clickByResourceId(viewId: String): Boolean {
        val needle = viewId.trim()
        if (needle.isEmpty()) return false
        return withRoot { root ->
            val exact = runCatching { root.findAccessibilityNodeInfosByViewId(needle) }.getOrNull().orEmpty()
            for (node in exact) {
                val clicked = clickAncestors(node)
                recycleSafely(node)
                if (clicked) return@withRoot true
            }
            clickMatching(root) { node ->
                val id = node.viewIdResourceName.orEmpty()
                id == needle || id.endsWith("/$needle") || id.endsWith(needle)
            }
        } ?: false
    }

    /** Sets text on the focused editable field (appends to existing text). */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return try {
            if (focus == null) return false
            val current = focus.text?.toString().orEmpty()
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text)
            }
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            recycleSafely(focus)
            recycleSafely(root)
        }
    }

    suspend fun tap(x: Float, y: Float, durationMs: Long = 50): Boolean =
        dispatch(Path().apply { moveTo(x, y); lineTo(x + 1, y) }, durationMs)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean =
        dispatch(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs)

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }

    private inline fun <T> withRoot(block: (AccessibilityNodeInfo) -> T): T? {
        val root = rootInActiveWindow ?: return null
        return try {
            block(root)
        } finally {
            recycleSafely(root)
        }
    }

    private fun clickMatching(node: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (pred(node) && clickAncestors(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = clickMatching(child, pred)
            recycleSafely(child)
            if (found) return true
        }
        return false
    }

    private fun clickAncestors(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var extra: AccessibilityNodeInfo? = null
        while (current != null) {
            if (current.isClickable) {
                val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                recycleSafely(extra)
                return ok
            }
            val parent = current.parent
            recycleSafely(extra)
            extra = parent
            current = parent
        }
        recycleSafely(extra)
        return false
    }

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            node.recycle()
        } catch (_: Exception) {
            // Recycle is a no-op / throws on API 33+ pooled nodes.
        }
    }

    companion object {
        @Volatile var instance: NeuroAccessibilityService? = null
            private set
    }
}
