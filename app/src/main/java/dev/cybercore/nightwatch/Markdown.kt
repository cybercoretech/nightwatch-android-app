package dev.cybercore.nightwatch

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * Just enough Markdown for Cyberdeck's reports: headings, bold, inline code,
 * fenced code blocks, bullets and rules. Tables and everything else show as
 * the plain text they already are in a monospace font.
 */
fun renderMarkdown(md: String, accent: Color = Grid.cyan, code: Color = Grid.green, muted: Color = Grid.muted): AnnotatedString =
    buildAnnotatedString {
        var fenced = false
        md.lines().forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            val line = raw.trimEnd()
            when {
                line.trimStart().startsWith("```") -> {
                    fenced = !fenced
                }
                fenced -> withStyle(SpanStyle(color = code)) { append(line) }
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    val size = when (level) { 1 -> 19.sp; 2 -> 16.sp; else -> 14.sp }
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold, fontSize = size)) {
                        inline(line.drop(level).trim(), accent, code)
                    }
                }
                line.matches(Regex("^\\s*([-*_])\\s*\\1\\s*\\1[\\s\\-*_]*$")) ->
                    withStyle(SpanStyle(color = muted)) { append("─".repeat(24)) }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val indent = line.length - line.trimStart().length
                    append(" ".repeat(indent))
                    withStyle(SpanStyle(color = accent)) { append("• ") }
                    inline(line.trimStart().drop(2), accent, code)
                }
                else -> inline(line, accent, code)
            }
        }
    }

/** `**bold**` and `` `code` `` inside one line. */
private fun AnnotatedString.Builder.inline(text: String, accent: Color, code: Color) {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end < 0) { append(text.substring(i)); return }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { append(text.substring(i + 2, end)) }
                i = end + 2
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end < 0) { append(text.substring(i)); return }
                withStyle(SpanStyle(color = code)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }
            else -> {
                val next = listOf(text.indexOf("**", i), text.indexOf('`', i)).filter { it >= 0 }.minOrNull() ?: text.length
                append(text.substring(i, next))
                i = next
            }
        }
    }
}
