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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A block of a Markdown document. Exposed for unit tests. */
sealed interface MdBlock {
    data class Text(val lines: List<String>) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
}

/** Splits Markdown into prose and fenced code blocks (``` ... ```). Unclosed fences are treated as code (streaming-safe). */
fun parseMarkdownBlocks(src: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val prose = mutableListOf<String>()
    val code = StringBuilder()
    var lang = ""
    var inCode = false
    for (line in src.lines()) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (inCode) {
                blocks += MdBlock.Code(lang, code.toString().trimEnd('\n'))
                code.clear(); inCode = false
            } else {
                if (prose.isNotEmpty()) { blocks += MdBlock.Text(prose.toList()); prose.clear() }
                lang = trimmed.removePrefix("```").trim(); inCode = true
            }
        } else if (inCode) {
            code.append(line).append('\n')
        } else {
            prose += line
        }
    }
    if (inCode) blocks += MdBlock.Code(lang, code.toString().trimEnd('\n'))
    else if (prose.isNotEmpty()) blocks += MdBlock.Text(prose.toList())
    return blocks
}

/** Inline formatting: **bold**, *italic*, `code`. */
fun inlineMarkdown(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> {
                val end = s.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                i = end + 2
            }
            s[i] == '`' && s.indexOf('`', i + 1) > 0 -> {
                val end = s.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22888888))) {
                    append(s.substring(i + 1, end))
                }
                i = end + 1
            }
            s[i] == '*' && s.indexOf('*', i + 1) > 0 && s.getOrNull(i + 1) != ' ' -> {
                val end = s.indexOf('*', i + 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            else -> { append(s[i]); i++ }
        }
    }
}

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "if", "else", "for", "while", "return", "when",
    "import", "package", "suspend", "override", "private", "public", "null", "true", "false", "def", "let",
    "const", "function", "echo", "export", "in", "is", "as", "try", "catch", "throw", "new", "static",
)

private val NUMBERED = Regex("^(\\d+)\\.\\s+(.*)$")

/** Very small tokenizer for keywords, strings, numbers and comments. */
fun highlightCode(code: String): AnnotatedString = buildAnnotatedString {
    val kw = SpanStyle(color = Color(0xFF7C4DFF), fontWeight = FontWeight.Bold)
    val str = SpanStyle(color = Color(0xFF2E7D32))
    val num = SpanStyle(color = Color(0xFFEF6C00))
    val com = SpanStyle(color = Color(0xFF808080), fontStyle = FontStyle.Italic)
    var i = 0
    while (i < code.length) {
        val c = code[i]
        when {
            code.startsWith("//", i) || code.startsWith("#", i) -> {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                withStyle(com) { append(code.substring(i, end)) }; i = end
            }
            c == '"' || c == '\'' -> {
                var j = i + 1
                while (j < code.length && code[j] != c && code[j] != '\n') { if (code[j] == '\\') j++; j++ }
                val end = minOf(j + 1, code.length)
                withStyle(str) { append(code.substring(i, end)) }; i = end
            }
            c.isDigit() -> {
                var j = i
                while (j < code.length && (code[j].isDigit() || code[j] == '.')) j++
                withStyle(num) { append(code.substring(i, j)) }; i = j
            }
            c.isLetter() || c == '_' -> {
                var j = i
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                if (word in KEYWORDS) withStyle(kw) { append(word) } else append(word)
                i = j
            }
            else -> { append(c); i++ }
        }
    }
}

@Composable
fun MarkdownText(src: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        for (block in parseMarkdownBlocks(src)) {
            when (block) {
                is MdBlock.Text -> block.lines.forEach { line ->
                    val t = line.trimStart()
                    val numbered = NUMBERED.matchEntire(t)
                    val text = when {
                        t.startsWith("### ") -> buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp)) { append(t.removePrefix("### ")) } }
                        t.startsWith("## ") -> buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) { append(t.removePrefix("## ")) } }
                        t.startsWith("# ") -> buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 19.sp)) { append(t.removePrefix("# ")) } }
                        t.startsWith("- ") || t.startsWith("* ") -> buildAnnotatedString { append("• "); append(inlineMarkdown(t.drop(2))) }
                        numbered != null -> buildAnnotatedString {
                            append(numbered.groupValues[1])
                            append(". ")
                            append(inlineMarkdown(numbered.groupValues[2]))
                        }
                        else -> inlineMarkdown(line)
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Code -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color(0x14808080)).padding(8.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    if (block.lang.isNotEmpty()) Text(block.lang, style = MaterialTheme.typography.labelSmall)
                    Text(highlightCode(block.code), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
    }
}
