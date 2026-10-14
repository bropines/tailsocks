package io.github.bropines.tailscaled.core

import java.security.MessageDigest

/**
 * The version line of the boot script in `/data/adb/service.d`, and what Settings
 * makes of it.
 *
 * The script is a copy: written once, then run as root on every boot whatever
 * the app has become since. BootReceiver refreshes it after an update, but only
 * while Root Mode is on and only if `su` answers at that moment, so a copy can
 * outlive the build that wrote it — and until now nothing could tell. The line
 * names the version of the script and the build that wrote it:
 *
 *     # tailsocks-boot-script: 3f9a2c1b7d0e 5.0.0
 *
 * The version is a digest of the script as this build would write it, the
 * template with its placeholders filled in (the package, the daemon path, and
 * the package's own env file — [RootPaths]), so it cannot be forgotten when
 * the template changes, and it also moves when the daemon path baked into the
 * script does (Android renames the install directory on every update; a stale
 * path costs the boot start a wait for the package manager). The build name is
 * for people: it is shown, never compared. A script without the line was
 * written before it existed and counts as outdated. The boot script ignores the
 * line — it is a comment.
 */
object RootBootScript {
    const val HEADER_TAG = "# tailsocks-boot-script:"

    /** Lines read from the top of the installed script to find the header. */
    const val HEADER_SEARCH_LINES = 5

    private val versionToken = Regex("^[0-9a-f]{12}$")
    private val unsafeBuildName = Regex("[^A-Za-z0-9._+-]")

    /** What the installed copy is, as far as Settings is concerned. */
    enum class Status { ABSENT, CURRENT, OUTDATED }

    /** [writtenBy] is the build named in the header, null when there is none. */
    data class State(val status: Status, val writtenBy: String? = null) {
        val installed: Boolean get() = status != Status.ABSENT
    }

    /** The script this build installs: the template filled in, the header after the shebang. */
    fun render(template: String, packageName: String, daemonBin: String, buildName: String): String {
        val body = fill(template, packageName, daemonBin)
        val header = "$HEADER_TAG ${versionOf(body)} ${buildName.replace(unsafeBuildName, "")}".trimEnd() + "\n"
        val firstLineEnd = body.indexOf('\n')
        return if (body.startsWith("#!") && firstLineEnd >= 0) {
            body.substring(0, firstLineEnd + 1) + header + body.substring(firstLineEnd + 1)
        } else {
            header + body
        }
    }

    /** The first line of the root env file: the package and the build that wrote it. */
    fun envFileHeader(packageName: String, buildName: String): String =
        "# tailsocks-root-env: ${packageName.replace(unsafeBuildName, "")} ${buildName.replace(unsafeBuildName, "")}".trimEnd() + "\n"

    /** The version [render] writes for a script body. */
    fun versionOf(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    /** The version this build would write: [render]'s header, read back. */
    fun expectedVersion(template: String, packageName: String, daemonBin: String): String =
        versionOf(fill(template, packageName, daemonBin))

    /** The template with this package's values in: its name, its daemon, its env file. */
    private fun fill(template: String, packageName: String, daemonBin: String): String = template
        .replace("%PKG_NAME%", packageName)
        .replace("%DAEMON_BIN%", daemonBin)
        .replace("%ENV_FILE%", RootPaths.forPackage(packageName).envFile)

    /** The header of an installed script: its version and the build that wrote it. */
    data class Header(val version: String, val writtenBy: String?)

    /** Reads the header from the first lines of a script; null when there is none. */
    fun parseHeader(head: String): Header? = head.lineSequence()
        .take(HEADER_SEARCH_LINES)
        .map { it.trim() }
        .firstOrNull { it.startsWith(HEADER_TAG) }
        ?.removePrefix(HEADER_TAG)
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.let { fields ->
            val version = fields.getOrNull(0)?.takeIf { versionToken.matches(it) } ?: return null
            Header(version, fields.getOrNull(1)?.takeIf { it.isNotEmpty() })
        }

    /**
     * The state of the installed copy. [head] is the start of the installed file,
     * null when there is no file; [expected] is [expectedVersion] for this build.
     */
    fun state(head: String?, expected: String): State {
        if (head == null) return State(Status.ABSENT)
        val header = parseHeader(head) ?: return State(Status.OUTDATED)
        return State(
            if (header.version == expected) Status.CURRENT else Status.OUTDATED,
            header.writtenBy
        )
    }
}
