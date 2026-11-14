package io.github.bropines.tailscaled.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The colours of a log line on the Logs screen ([highlightLogMessage]). The function runs per
 * row while a fling scrolls the log, so its regexes and keyword sets live outside it; these
 * cases pin its output — every span's text, colour and weight — so that moving things around
 * cannot change what a line looks like. Light and dark pages each get their own palette.
 */
class LogHighlightTest {

    /** Each styled run as `text|colour|weight`, in order. */
    private fun dump(s: AnnotatedString): String =
        s.spanStyles.joinToString("\n") { r ->
            val c = r.item.color
            val hex = if (c == Color.Unspecified) "-" else "%08X".format(
                ((c.alpha * 255).toInt() shl 24) or ((c.red * 255).toInt() shl 16) or
                    ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
            )
            "${s.text.substring(r.start, r.end)}|$hex|${r.item.fontWeight?.weight ?: "-"}"
        }

    private val dark = Color(0xFFE0E0E0)
    private val light = Color(0xFF1C1B1F)

    private val lines = listOf(
        Triple("12:00:01", "CORE", "magicsock: derp-1 connected to 100.64.0.1:41641 ok"),
        Triple("12:00:02", "ERROR", "dial failed: unreachable fd7a:115c:a1e0:ab12:4843:cd96:6258:b240 after 3s"),
        Triple("12:00:03", "TAILSCALE", "health state=Running online=true latency=12.5ms err=Failed"),
        Triple("12:00:04", "ROOT", "iptables -w: rule 1.2.3.4 key=value=x warning"),
        Triple("12:00:05", "WEIRD", ""),
        Triple("12:00:06", "LOGCAT", "a=1 b=2 10.0.0.1:80=x"),
    )

    private fun render(): String = buildString {
        for ((ts, cat, msg) in lines) {
            append("=== $cat dark\n")
            append(dump(highlightLogMessage(ts, cat, msg, dark))).append('\n')
            append("=== $cat light\n")
            append(dump(highlightLogMessage(ts, cat, msg, light))).append('\n')
        }
    }

    /**
     * src/test/resources/ui/log_highlight.golden was recorded from the highlighter as it
     * was before its regexes moved out of the function: overlapping matches (an address inside
     * a key=value, `key=value=x`), IPv4 with a port, a full IPv6 address, keyword values, a
     * number-like value, an empty message and an unknown category.
     */
    @Test
    fun spansMatchTheRecordedOutput() {
        val golden = requireNotNull(javaClass.getResource("/ui/log_highlight.golden")) { "no golden file" }.readText()
        assertEquals(golden, render())
    }

    /** The same line twice gives the same spans: nothing is carried from one call to the next. */
    @Test
    fun repeatedCallsAgree() {
        val (ts, cat, msg) = lines[2]
        assertEquals(
            dump(highlightLogMessage(ts, cat, msg, dark)),
            dump(highlightLogMessage(ts, cat, msg, dark))
        )
        // A light page after a dark one still gets the light colours.
        assertEquals(
            dump(highlightLogMessage(ts, cat, msg, light)),
            dump(highlightLogMessage(ts, cat, msg, dark).let { highlightLogMessage(ts, cat, msg, light) })
        )
    }
}
