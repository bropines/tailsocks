package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The version line of the installed boot script, and what Settings makes of an installed copy. */
class RootBootScriptTest {

    private val template = "#!/system/bin/sh\n# comment\nPKG=\"%PKG_NAME%\"\nBIN=\"%DAEMON_BIN%\"\n"
    private val pkg = "io.github.bropines.tailscaled"
    private val bin = "/data/app/~~a/io.github.bropines.tailscaled-b/lib/arm64/libtailscale.so"

    private fun render(t: String = template, b: String = bin, build: String = "5.0.0") =
        RootBootScript.render(t, pkg, b, build)

    @Test
    fun `the header goes right after the shebang and the rest is the filled-in template`() {
        val script = render()
        val lines = script.lines()
        assertEquals("#!/system/bin/sh", lines[0])
        assertTrue(lines[1].startsWith(RootBootScript.HEADER_TAG))
        assertEquals("# comment\nPKG=\"$pkg\"\nBIN=\"$bin\"\n", lines.drop(2).joinToString("\n"))
    }

    @Test
    fun `the header names the version this build expects and the build that wrote it`() {
        val header = RootBootScript.parseHeader(render())!!
        assertEquals(RootBootScript.expectedVersion(template, pkg, bin), header.version)
        assertEquals("5.0.0", header.writtenBy)
    }

    @Test
    fun `a copy this build wrote is current`() {
        val state = RootBootScript.state(render(), RootBootScript.expectedVersion(template, pkg, bin))
        assertEquals(RootBootScript.Status.CURRENT, state.status)
        assertEquals("5.0.0", state.writtenBy)
        assertTrue(state.installed)
    }

    @Test
    fun `no file is absent, not outdated`() {
        val state = RootBootScript.state(null, "0123456789ab")
        assertEquals(RootBootScript.Status.ABSENT, state.status)
        assertFalse(state.installed)
    }

    @Test
    fun `a script without the line counts as outdated`() {
        val state = RootBootScript.state(template, RootBootScript.expectedVersion(template, pkg, bin))
        assertEquals(RootBootScript.Status.OUTDATED, state.status)
        assertNull(state.writtenBy)
    }

    @Test
    fun `a changed template is another version`() {
        val older = render(t = template.replace("# comment", "# older comment"), build = "4.10.2")
        val state = RootBootScript.state(older, RootBootScript.expectedVersion(template, pkg, bin))
        assertEquals(RootBootScript.Status.OUTDATED, state.status)
        assertEquals("4.10.2", state.writtenBy)
    }

    @Test
    fun `a daemon path that moved with an update is another version`() {
        val moved = bin.replace("~~a", "~~z")
        assertNotEquals(
            RootBootScript.expectedVersion(template, pkg, bin),
            RootBootScript.expectedVersion(template, pkg, moved)
        )
        val state = RootBootScript.state(render(), RootBootScript.expectedVersion(template, pkg, moved))
        assertEquals(RootBootScript.Status.OUTDATED, state.status)
    }

    @Test
    fun `the build name is only shown, a different one does not make a copy outdated`() {
        val state = RootBootScript.state(render(build = "4.99.0-dev"), RootBootScript.expectedVersion(template, pkg, bin))
        assertEquals(RootBootScript.Status.CURRENT, state.status)
        assertEquals("4.99.0-dev", state.writtenBy)
    }

    @Test
    fun `nothing but a version and a build name gets into the header line`() {
        val script = render(build = "5.0.0\nrm -rf /data \$(id)")
        val header = script.lines()[1]
        assertEquals("${RootBootScript.HEADER_TAG} ${RootBootScript.expectedVersion(template, pkg, bin)} 5.0.0rm-rfdataid", header)
        assertEquals("#!/system/bin/sh", script.lines()[0])
    }

    @Test
    fun `a malformed or missing version is no header`() {
        assertNull(RootBootScript.parseHeader("#!/system/bin/sh\n${RootBootScript.HEADER_TAG} not-a-version 5.0.0\n"))
        assertNull(RootBootScript.parseHeader("#!/system/bin/sh\n${RootBootScript.HEADER_TAG}\n"))
        assertNull(RootBootScript.parseHeader("#!/system/bin/sh\n# TailSocks Root Autostart Service Script\n"))
    }

    @Test
    fun `a header without a build name still has a version`() {
        val header = RootBootScript.parseHeader("#!/system/bin/sh\n${RootBootScript.HEADER_TAG} 0123456789ab\n")!!
        assertEquals("0123456789ab", header.version)
        assertNull(header.writtenBy)
    }

    @Test
    fun `the header is only looked for at the top of the file`() {
        val buried = (1..RootBootScript.HEADER_SEARCH_LINES).joinToString("\n") { "# line $it" } +
            "\n${RootBootScript.HEADER_TAG} 0123456789ab 5.0.0\n"
        assertNull(RootBootScript.parseHeader(buried))
    }

    @Test
    fun `the env file header is one comment line the boot script skips`() {
        val header = RootBootScript.envFileHeader("io.github.bropines.tailscaled.dev", "5.0.0-dev\nexport X='1'")
        assertEquals("# tailsocks-root-env: io.github.bropines.tailscaled.dev 5.0.0-devexportX1\n", header)
        // The script takes only `export NAME='value'` lines (assets/scripts/tailscaled.sh).
        assertFalse(Regex("^export [A-Z_]*='.*'$").matches(header.trimEnd()))
    }

    @Test
    fun `the real boot script gets its header on the second line and keeps its shebang`() {
        val module = listOf(File("."), File("app")).first { File(it, "src/main/assets/scripts/tailscaled.sh").isFile }
        val asset = File(module, "src/main/assets/scripts/tailscaled.sh").readText()
        val script = RootBootScript.render(asset, pkg, bin, "5.0.0")
        assertTrue(script.startsWith("#!/system/bin/sh\n${RootBootScript.HEADER_TAG} "))
        assertFalse(script.contains("%PKG_NAME%"))
        assertFalse(script.contains("%DAEMON_BIN%"))
        // What Settings reads back is what `head` of the installed file returns.
        val head = script.lines().take(RootBootScript.HEADER_SEARCH_LINES).joinToString("\n")
        assertEquals(
            RootBootScript.Status.CURRENT,
            RootBootScript.state(head, RootBootScript.expectedVersion(asset, pkg, bin)).status
        )
    }
}
