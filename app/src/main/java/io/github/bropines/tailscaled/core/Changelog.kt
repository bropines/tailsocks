package io.github.bropines.tailscaled.core

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Reader for the bundled `assets/CHANGELOG.md` (Keep a Changelog format).
 *
 * The file is copied from the repository root at build time (see
 * `copyChangelogAsset` in app/build.gradle.kts), so the app always ships the
 * changelog of the commit it was built from. Only the structure the app needs
 * is parsed: `## [version] - date` sections, `### Group` sub-headings and
 * `- bullet` items (nested bullets are indented by two spaces per level).
 */
object Changelog {
    const val ASSET_NAME = "CHANGELOG.md"
    const val FULL_CHANGELOG_URL = "https://github.com/bropines/tailsocks/blob/main/CHANGELOG.md"

    data class Item(val text: String, val level: Int)
    data class Group(val title: String, val items: List<Item>)
    data class Section(val version: String, val date: String, val groups: List<Group>)

    private val sectionHeading = Regex("""^##\s+\[([^\]]+)\](?:\s*-\s*(.*))?\s*$""")
    private val groupHeading = Regex("""^###\s+(.+?)\s*$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")

    @Volatile
    private var cached: List<Section>? = null

    /** All sections, newest first (file order). Never throws; missing asset yields an empty list. */
    fun load(context: Context): List<Section> {
        cached?.let { return it }
        val parsed = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { parse(it.readText()) }
        } catch (e: Exception) {
            android.util.Log.w("Changelog", "Cannot read $ASSET_NAME asset: ${e.message}")
            emptyList()
        }
        cached = parsed
        return parsed
    }

    /** The newest released section (an `[Unreleased]` block, if present, is skipped). */
    fun latest(context: Context): Section? =
        load(context).firstOrNull { !it.version.equals("unreleased", ignoreCase = true) }

    /**
     * The running build's base version, e.g. `3.6.0` for `v3.6.0-abc123.release`.
     * Used as the "already seen" key: the git hash and build-type suffix change
     * on every commit and would otherwise re-show the dialog after each rebuild.
     */
    fun currentVersion(): String =
        io.github.bropines.tailscaled.BuildConfig.VERSION_NAME.removePrefix("v").substringBefore('-')

    fun parse(markdown: String): List<Section> {
        val sections = mutableListOf<Section>()

        var version: String? = null
        var date = ""
        var groups = mutableListOf<MutableGroup>()
        var group: MutableGroup? = null

        fun flushSection() {
            val v = version ?: return
            sections += Section(v, date, groups.map { Group(it.title, it.items.toList()) })
        }

        for (raw in markdown.lineSequence()) {
            val line = raw.trimEnd()

            val section = sectionHeading.matchEntire(line)
            if (section != null) {
                flushSection()
                version = section.groupValues[1].trim()
                date = section.groupValues[2].trim()
                groups = mutableListOf()
                group = null
                continue
            }

            if (version == null) continue // preamble before the first section

            val heading = groupHeading.matchEntire(line)
            if (heading != null) {
                val title = heading.groupValues[1]
                // Keep a Changelog allows a group to appear only once, but a
                // hand-edited file can repeat one (3.6.0 has two "Fixed"); merge them.
                group = groups.firstOrNull { it.title.equals(title, ignoreCase = true) }
                    ?: MutableGroup(title).also { groups += it }
                continue
            }

            if (line.isBlank()) continue
            if (line.startsWith("#")) continue // any other heading level: ignore

            val g = group ?: MutableGroup("").also { groups += it; group = it }
            val item = bullet.matchEntire(line)
            if (item != null) {
                val indent = item.groupValues[1].replace("\t", "  ").length
                g.items += Item(item.groupValues[2].trim(), indent / 2)
                continue
            }

            // Continuation of the previous bullet (wrapped line).
            val last = g.items.lastOrNull()
            if (last != null) {
                g.items[g.items.lastIndex] = last.copy(text = last.text + " " + line.trim())
            } else {
                g.items += Item(line.trim(), 0)
            }
        }
        flushSection()
        return sections
    }

    private class MutableGroup(val title: String) {
        val items = mutableListOf<Item>()
    }

    /**
     * Renders the inline markdown subset used in the changelog: `**bold**` and
     * `` `code` ``. Anything else is passed through verbatim.
     */
    fun inlineMarkdown(
        text: String,
        codeBackground: Color = Color.Unspecified,
        codeColor: Color = Color.Unspecified
    ): AnnotatedString = buildAnnotatedString {
        var i = 0
        var bold = false
        var code = false
        val plain = StringBuilder()

        fun flush() {
            if (plain.isEmpty()) return
            val s = plain.toString()
            plain.setLength(0)
            when {
                code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, color = codeColor)) { append(s) }
                bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s) }
                else -> append(s)
            }
        }

        while (i < text.length) {
            val c = text[i]
            when {
                // Inside code spans markdown is literal, only the closing backtick matters.
                code && c == '`' -> { flush(); code = false; i++ }
                code -> { plain.append(c); i++ }
                c == '`' -> { flush(); code = true; i++ }
                c == '*' && i + 1 < text.length && text[i + 1] == '*' -> { flush(); bold = !bold; i += 2 }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
    }
}
