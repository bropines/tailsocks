package io.github.bropines.tailscaled.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/*
 * The log line highlighter: its light and dark palettes, the regexes and keyword sets it
 * colours by, and highlightLogMessage, which LogsActivity calls per row.
 */

/**
 * The colours of a highlighted log line. They were pastels picked for a dark page, and on a light
 * one a value such as `false` or `Running` was near-white on white: each has a darker twin that
 * keeps its hue and reads on a light background.
 */
private class LogPalette(light: Boolean, val text: Color) {
    val timestamp = if (light) Color(0xFF616161) else Color(0xFF757575)
    val error = if (light) Color(0xFFC62828) else Color(0xFFEF5350)
    val core = if (light) Color(0xFF1565C0) else Color(0xFF42A5F5)
    val tailscale = if (light) Color(0xFF2E7D32) else Color(0xFF66BB6A)
    val root = if (light) Color(0xFF7B1FA2) else Color(0xFFBA68C8)
    val logcat = if (light) Color(0xFF00695C) else Color(0xFF26A69A)
    val other = if (light) Color(0xFFB35300) else Color(0xFFFFA726)
    val address = if (light) Color(0xFF00695C) else Color(0xFF80DEEA)
    val key = if (light) Color(0xFF9A4F00) else Color(0xFFFFCC80)
    val valueGood = if (light) Color(0xFF2E7D32) else Color(0xFFA5D6A7)
    val valueBad = if (light) Color(0xFFC62828) else Color(0xFFEF9A9A)
    val valueNumber = if (light) Color(0xFF5E35B1) else Color(0xFFB39DDB)
    val value = if (light) text else Color(0xFFEEEEEE)
    val wordGood = if (light) Color(0xFF2E7D32) else Color(0xFF81C784)
    val wordBad = if (light) Color(0xFFC62828) else Color(0xFFE57373)
}

/*
 * What the highlighter looks for. It runs once per log row entering the composition — all
 * through a fling, on the main thread — so the patterns are compiled and the sets built once
 * here rather than per line (Regex is stateless and safe to share). LogHighlightTest pins the
 * output against a recording taken before they moved.
 */
private val LOG_IPV4 = """\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?\b""".toRegex()
private val LOG_IPV6 = """\b([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b""".toRegex()
private val LOG_KEY_VALUE = """\b([a-zA-Z0-9_\-]+)=([^\s]+)\b""".toRegex()
private val LOG_WORD = """\b[a-zA-Z_]+\b""".toRegex()
private val LOG_SUCCESS_WORDS = setOf("success", "successful", "ok", "online", "active", "connected", "working", "available", "healthy")
private val LOG_ERROR_WORDS = setOf("error", "failed", "fail", "blocked", "offline", "unavailable", "unreachable", "exception", "panic", "warning", "warn")

/**
 * The palette of the last page colour asked for. A page has one text colour (two, counting the
 * light and dark themes), so this is almost always a hit and a line costs no palette of its own.
 * Read and written from the main thread; a race would only build one palette too many.
 */
@Volatile private var lastLogPalette: LogPalette? = null

private fun logPaletteFor(text: Color): LogPalette =
    lastLogPalette?.takeIf { it.text == text }
        ?: LogPalette(light = text.luminance() < 0.5f, text = text).also { lastLogPalette = it }

/** A log line with its parts coloured; [defaultColor] is the page's text colour, and tells a light page from a dark one. */
fun highlightLogMessage(
    timestamp: String,
    category: String,
    message: String,
    defaultColor: Color
): AnnotatedString {
    val palette = logPaletteFor(defaultColor)
    return buildAnnotatedString {
        withStyle(style = SpanStyle(color = palette.timestamp)) {
            append(timestamp)
            append(" ")
        }

        val catColor = when (category) {
            "ERROR" -> palette.error
            "CORE" -> palette.core
            "TAILSCALE" -> palette.tailscale
            "ROOT" -> palette.root
            "LOGCAT" -> palette.logcat
            else -> palette.other
        }
        withStyle(style = SpanStyle(color = catColor, fontWeight = FontWeight.Bold)) {
            append("[")
            append(category)
            append("] ")
        }

        val ipRegex = LOG_IPV4
        val ipv6Regex = LOG_IPV6
        val keyValueRegex = LOG_KEY_VALUE
        val successKeywords = LOG_SUCCESS_WORDS
        val errorKeywords = LOG_ERROR_WORDS

        val text = message
        var lastIdx = 0
        
        val matches = (ipRegex.findAll(text) + ipv6Regex.findAll(text) + keyValueRegex.findAll(text))
            .sortedBy { it.range.first }
            .toList()

        val nonOverlappingMatches = mutableListOf<MatchResult>()
        for (match in matches) {
            if (nonOverlappingMatches.isEmpty() || match.range.first >= nonOverlappingMatches.last().range.last + 1) {
                nonOverlappingMatches.add(match)
            }
        }

        for (match in nonOverlappingMatches) {
            val start = match.range.first
            val end = match.range.last + 1
            
            appendWithKeywords(text.substring(lastIdx, start), palette, successKeywords, errorKeywords)
            
            val matchText = match.value
            if (ipRegex.matches(matchText) || ipv6Regex.matches(matchText)) {
                withStyle(style = SpanStyle(color = palette.address, fontWeight = FontWeight.Medium)) {
                    append(matchText)
                }
            } else {
                val parts = matchText.split("=", limit = 2)
                if (parts.size == 2) {
                    withStyle(style = SpanStyle(color = palette.key)) {
                        append(parts[0])
                        append("=")
                    }
                    val valText = parts[1]
                    val valColor = when {
                        valText.lowercase() in successKeywords -> palette.valueGood
                        valText.lowercase() in errorKeywords -> palette.valueBad
                        valText.all { it.isDigit() || it == '.' || it == ':' || it == 'm' || it == 's' } -> palette.valueNumber
                        else -> palette.value
                    }
                    withStyle(style = SpanStyle(color = valColor)) {
                        append(valText)
                    }
                } else {
                    append(matchText)
                }
            }
            lastIdx = end
        }
        
        if (lastIdx < text.length) {
            appendWithKeywords(text.substring(lastIdx), palette, successKeywords, errorKeywords)
        }
    }
}

private fun AnnotatedString.Builder.appendWithKeywords(
    text: String,
    palette: LogPalette,
    successKeywords: Set<String>,
    errorKeywords: Set<String>
) {
    val defaultColor = palette.text
    var lastIdx = 0
    val matches = LOG_WORD.findAll(text).toList()
    
    for (match in matches) {
        val start = match.range.first
        val end = match.range.last + 1
        
        if (start > lastIdx) {
            withStyle(style = SpanStyle(color = defaultColor)) {
                append(text.substring(lastIdx, start))
            }
        }
        
        val word = match.value
        val lowerWord = word.lowercase()
        val wordColor = when {
            lowerWord in successKeywords -> palette.wordGood
            lowerWord in errorKeywords -> palette.wordBad
            else -> defaultColor
        }
        withStyle(style = SpanStyle(color = wordColor, fontWeight = if (wordColor != defaultColor) FontWeight.Bold else FontWeight.Normal)) {
            append(word)
        }
        lastIdx = end
    }
    
    if (lastIdx < text.length) {
        withStyle(style = SpanStyle(color = defaultColor)) {
            append(text.substring(lastIdx))
        }
    }
}
