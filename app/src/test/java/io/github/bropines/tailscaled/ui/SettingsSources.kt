package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/*
 * The app's own files, as the tests of the settings catalogue read them: the strings of a
 * language straight from its XML, and the Kotlin sources of the screens. A unit test has no
 * Android resources; it has the checkout.
 */

/** The module's directory, whether the tests run from it or from the repository's root. */
private val module: File = listOf(File("."), File("app")).first { File(it, "src/main/res/values/strings.xml").isFile }

/** The strings of one `values` directory by name, as `getString` would give them. */
internal fun stringsOf(directory: String): Map<String, String> {
    val out = HashMap<String, String>()
    val files = File(module, "src/main/res/$directory").listFiles { file -> file.name.endsWith(".xml") } ?: emptyArray()
    for (file in files) {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            out[node.attributes.getNamedItem("name").nodeValue] = unescape(node.textContent)
        }
    }
    return out
}

private fun unescape(text: String): String = text
    .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\u0020", " ").replace("\\@", "@").replace("\\?", "?")

/** The name of a string resource: what the catalogue holds is its number. */
internal val stringNames: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

internal fun stringName(resource: Int): String = stringNames[resource] ?: error("no string resource $resource")

internal val englishStrings: Map<String, String> by lazy { stringsOf("values") }
internal val russianStrings: Map<String, String> by lazy { stringsOf("values-ru") }

/** A language as the app resolves it: its own string, or the English one where it has none. */
internal fun language(own: Map<String, String>): Strings = { resource ->
    val name = stringName(resource)
    own[name] ?: englishStrings[name] ?: error("string $name is in no values directory")
}

/** Every Kotlin source of the app, by its path under the package directory. */
internal val appSources: Map<String, String> by lazy {
    val root = File(module, "src/main/java/io/github/bropines/tailscaled")
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.relativeTo(root).path.replace(File.separatorChar, '/') to it.readText() }
}

/** A call of a composable found in a source: its arguments as written, split at the top level. */
internal class SourceCall(val file: String, val line: Int, val name: String, val arguments: List<String>) {
    /** The argument called [name], or the one at [position] when the call names none so. */
    fun argument(name: String, position: Int): String? {
        val named = Regex("^\\s*$name\\s*=(?!=)\\s*")
        arguments.firstOrNull { named.containsMatchIn(it) }?.let { return it.replace(named, "").trim() }
        return arguments.filter { !Regex("^\\s*\\w+\\s*=(?!=)").containsMatchIn(it) }.getOrNull(position)?.trim()
    }
}

/**
 * The calls of [names] in [source] — not their declarations. The arguments are cut where a
 * comma stands outside every bracket and string: enough of Kotlin for a composable's call.
 */
internal fun callsOf(file: String, source: String, names: List<String>): List<SourceCall> {
    val calls = ArrayList<SourceCall>()
    val pattern = Regex("\\b(${names.joinToString("|")})\\(")
    for (match in pattern.findAll(source)) {
        if (source.substring(maxOf(0, match.range.first - 4), match.range.first) == "fun ") continue
        val arguments = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var i = match.range.last + 1
        var closed = false
        while (i < source.length && !closed) {
            val c = source[i]
            when {
                c == '"' -> {
                    // A string as a whole, its escapes included.
                    val end = generateSequence(i + 1) { it + 1 }.first { source[it] == '"' && source[it - 1] != '\\' }
                    current.append(source, i, end + 1)
                    i = end
                }
                c == '(' || c == '[' || c == '{' -> { depth++; current.append(c) }
                c == ')' || c == ']' || c == '}' -> {
                    if (depth == 0) closed = true else { depth--; current.append(c) }
                }
                c == ',' && depth == 0 -> { arguments.add(current.toString()); current.setLength(0) }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) arguments.add(current.toString())
        // A lambda after the brackets is the call's last argument, and never its title.
        calls.add(SourceCall(file, source.take(match.range.first).count { it == '\n' } + 1, match.groupValues[1], arguments))
    }
    return calls
}
