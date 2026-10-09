package io.github.bropines.tailscaled.admin.policy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

/**
 * The policy's colours: the console's JSON scheme (keys primary, strings tertiary, numbers
 * and literals secondary, punctuation dimmed) plus comments, dimmed and italic, and what the
 * tokenizer could not read, underlined in the error colour.
 */
class PolicyColors(
    val key: Color, val string: Color, val number: Color, val punct: Color,
    val comment: Color, val error: Color, val match: Color, val currentMatch: Color,
)

@Composable
fun rememberPolicyColors(): PolicyColors {
    val s = MaterialTheme.colorScheme
    return remember(s) {
        PolicyColors(
            key = s.primary, string = s.tertiary, number = s.secondary, punct = s.outline,
            comment = s.outline, error = s.error, match = s.tertiaryContainer, currentMatch = s.primaryContainer,
        )
    }
}

/** A text larger than this is edited plain: tens of thousands of spans per keystroke cost more than they show. */
const val MAX_HIGHLIGHTED = 200 * 1024

private fun styleOf(type: HuTokenType, c: PolicyColors): SpanStyle? = when (type) {
    HuTokenType.KEY -> SpanStyle(color = c.key)
    HuTokenType.STRING -> SpanStyle(color = c.string)
    HuTokenType.NUMBER -> SpanStyle(color = c.number)
    HuTokenType.LITERAL -> SpanStyle(color = c.number, fontWeight = FontWeight.Bold)
    HuTokenType.PUNCT -> SpanStyle(color = c.punct)
    HuTokenType.COMMENT -> SpanStyle(color = c.comment, fontStyle = FontStyle.Italic)
    HuTokenType.ERROR -> SpanStyle(color = c.error, textDecoration = TextDecoration.Underline)
    HuTokenType.SPACE -> null
}

/** [text] with its tokens coloured; the text itself unchanged, so offsets map one to one. */
fun highlightHuJson(text: String, c: PolicyColors): AnnotatedString {
    if (text.length > MAX_HIGHLIGHTED) return AnnotatedString(text)
    val b = AnnotatedString.Builder(text)
    for (t in HuJson.tokenize(text)) styleOf(t.type, c)?.let { b.addStyle(it, t.start, t.end) }
    return b.toAnnotatedString()
}

/**
 * [text] cut into lines, each coloured: one pass over the tokens, a block comment split where
 * its lines break. What the viewer's lazy list draws a line at a time.
 */
fun highlightLines(text: String, c: PolicyColors): List<AnnotatedString> {
    if (text.length > MAX_HIGHLIGHTED) return text.split('\n').map { AnnotatedString(it) }
    val out = ArrayList<AnnotatedString>()
    var line = AnnotatedString.Builder()
    for (t in HuJson.tokenize(text)) {
        val style = styleOf(t.type, c)
        var start = t.start
        while (start <= t.end) {
            var nl = -1
            for (k in start until t.end) if (text[k] == '\n') { nl = k; break }
            val end = if (nl < 0) t.end else nl
            if (end > start) {
                if (style == null) line.append(text, start, end)
                else { val at = line.length; line.append(text, start, end); line.addStyle(style, at, line.length) }
            }
            if (nl < 0) break
            out += line.toAnnotatedString()
            line = AnnotatedString.Builder()
            start = nl + 1
        }
    }
    out += line.toAnnotatedString()
    return out
}

/** The editor's colouring; the last result is kept, so a recomposition without a keystroke costs nothing. */
class HuJsonTransformation(private val colors: PolicyColors) : VisualTransformation {
    private var lastText: String? = null
    private var last: AnnotatedString = AnnotatedString("")

    override fun filter(text: AnnotatedString): TransformedText {
        if (text.text != lastText) {
            lastText = text.text
            last = highlightHuJson(text.text, colors)
        }
        return TransformedText(last, OffsetMapping.Identity)
    }
}

/** Where [query] occurs in [line], ignoring case; empty for a blank query. */
fun matchesIn(line: String, query: String): List<IntRange> {
    if (query.isBlank()) return emptyList()
    val out = mutableListOf<IntRange>()
    var from = 0
    while (true) {
        val i = line.indexOf(query, from, ignoreCase = true)
        if (i < 0) break
        out += i until i + query.length
        from = i + maxOf(1, query.length)
    }
    return out
}
