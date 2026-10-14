package io.github.bropines.tailscaled.core

import java.net.URLDecoder
import java.util.Locale

/*
 * A place in Taildrive typed into the share browser's path field: `device/share/folder/…`, the
 * names under the tailnet, as the field shows the place it is on. Pure, so the reading of what
 * people type and paste has tests; the page lists the levels and moves.
 */

/** Where a typed path leads, once it has been looked for level by level. */
sealed interface PathFound {
    /** The whole path is there and is a folder (a device and a share count as folders). */
    data class Folder(val location: DriveLocation) : PathFound

    /** The whole path is there and its last name is a file of [folder]. */
    data class File(val folder: DriveLocation, val name: String) : PathFound

    /** [missing] is not in [deepest], the last place of the path that is there. */
    data class Missing(val deepest: DriveLocation, val missing: String) : PathFound

    /** [at] could not be listed, so it is not known whether the rest is there. */
    data class Failed(val at: DriveLocation, val error: DriveError) : PathFound
}

object DrivePathInput {
    /** The place as the field shows it: the names under the tailnet, slash-joined; empty at the tailnet itself. */
    fun format(location: DriveLocation): String = location.segments.drop(1).joinToString("/")

    /**
     * The names [typed] stands for, under the tailnet called [tailnet]. Written by hand or pasted:
     *  - an address loses its scheme and host — Taildrive's own `http://100.100.100.100:8080/…`, the
     *    local proxy's `http://127.0.0.1:33445/…` — and its escapes (`%20`);
     *  - backslashes are slashes, and Windows' `DavWWWRoot` is no name: a path copied from Explorer;
     *  - doubled, leading and trailing slashes count for nothing, nor do spaces around a name;
     *  - a leading tailnet name, in any case, is dropped: the field is under the tailnet already.
     */
    fun parse(typed: String, tailnet: String?): List<String> {
        var text = typed.trim()
        val scheme = text.indexOf("://")
        if (scheme in 1..10 && text.substring(0, scheme).all { it.isLetter() }) {
            // Past the host, and the escapes undone: a "+" in a path is a plus, not a space.
            text = text.substring(scheme + 3).let { rest -> rest.indexOf('/').let { if (it < 0) "" else rest.substring(it) } }
            text = runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)
        }
        var names = text.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }
        // A bare Taildrive address in front, as written without a scheme or from Explorer.
        if (names.firstOrNull()?.let(::isTaildriveHost) == true) names = names.drop(1)
        if (names.firstOrNull().equals("DavWWWRoot", ignoreCase = true)) names = names.drop(1)
        if (tailnet != null && names.firstOrNull().equals(tailnet, ignoreCase = true)) names = names.drop(1)
        return names
    }

    private fun isTaildriveHost(name: String): Boolean = name == "100.100.100.100" || name.startsWith("100.100.100.100:")

    /** [name] among [names]: itself, or else the one name that is it without regard to case; null when there is none, or several. */
    fun match(name: String, names: Collection<String>): String? {
        if (name in names) return name
        return names.filter { it.lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT) }.singleOrNull()
    }

    /**
     * Looks for [names] under [base], one level at a time, through [list] — the browser's listing.
     * A device typed in another case is the device; so is a share or a folder, where only one name
     * fits. Stops at the first name that is not there, and says what is.
     */
    fun descend(base: DriveLocation, names: List<String>, list: (DriveLocation) -> DriveResult<List<DriveEntry>>): PathFound {
        var here = base
        names.forEachIndexed { i, name ->
            val entries = when (val listed = list(here)) {
                is DriveResult.Ok -> listed.value
                is DriveResult.Err -> return PathFound.Failed(here, listed.error)
            }
            val found = match(name, entries.map { it.name })?.let { real -> entries.first { it.name == real } }
                ?: return PathFound.Missing(here, name)
            if (!found.isFolder) {
                return if (i == names.lastIndex) PathFound.File(here, found.name) else PathFound.Missing(here, names[i + 1])
            }
            here = here.child(found.name)
        }
        return PathFound.Folder(here)
    }
}
