package io.github.bropines.tailscaled.core

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The daemon-log witness of the SO_MARK gate (docs/ROOT.md §4, T2): current
 * run only, an explicit negative stands, silence is a "no" the caller may
 * overrule — and after one full scan only what was appended is read.
 */
class SoMarkLogScanTest {

    private val file: File = File.createTempFile("tailscaled", ".log")

    @After
    fun cleanUp() {
        file.delete()
    }

    private val marker = "TailSocks: daemon start"
    private val engine = "2026/11/14 10:00:00 wgengine.NewUserspaceEngine(tun \"tailscale0\") ..."
    private val ok = "2026/11/14 10:00:01 magicsock: netns: SO_MARK 0x2000000 set on tailscaled sockets"
    private val bad = "2026/11/14 10:00:01 magicsock: netns: SO_MARK unavailable (operation not permitted)"
    private fun noise(i: Int) = "2026/11/14 10:00:02 magicsock: endpoint update $i, nothing to see here"

    private fun write(vararg lines: String) = file.writeText(lines.joinToString("\n", postfix = "\n"))
    private fun append(vararg lines: String) = file.appendText(lines.joinToString("\n", postfix = "\n"))

    // Small chunks so the fixtures cross chunk boundaries the way a real log crosses 1 MiB ones.
    private fun scan(chunk: Int = 64, limit: Long = 1L shl 30) = SoMarkLogScan(chunk, limit)

    @Test
    fun `the probe line after the run start opens the gate`() {
        write(marker, engine, ok, noise(1), noise(2))
        val v = scan().verdict(file, -1)
        assertTrue(v.reason, v.marks)
    }

    @Test
    fun `an explicit negative from the daemon stands and says so`() {
        write(marker, engine, bad, noise(1))
        val v = scan().verdict(file, -1)
        assertFalse(v.marks)
        assertTrue(v.reason, v.reason.startsWith("daemon logged"))
    }

    @Test
    fun `an earlier run's probe line cannot vouch for this run`() {
        write(marker, engine, ok, noise(1), marker, engine, noise(2))
        val v = scan().verdict(file, -1)
        assertFalse(v.marks)
        // Silence, not a negative: the caller may ask the process instead.
        assertFalse(v.reason, v.reason.startsWith("daemon logged"))
    }

    @Test
    fun `the later of two runs decides`() {
        write(marker, engine, bad, noise(1), marker, engine, ok)
        assertTrue(scan().verdict(file, -1).marks)
    }

    @Test
    fun `a probe line with no run start below it is silence`() {
        write(noise(1), ok, noise(2))
        val v = scan().verdict(file, -1)
        assertFalse(v.marks)
        assertTrue(v.reason, v.reason.contains("no run start"))
    }

    @Test
    fun `lines the app wrote are never evidence`() {
        write(marker, engine, "TailSocks: $ok", noise(1))
        assertFalse(scan().verdict(file, -1).marks)
    }

    @Test
    fun `the recorded launch offset bounds the run`() {
        write(noise(1), ok, noise(2))
        val launch = file.length()
        append(noise(3))
        // The earlier probe line lies below the launch offset: not this run's.
        val v = scan().verdict(file, launch)
        assertFalse(v.marks)
        assertTrue(v.reason, v.reason.contains("run started at byte $launch"))
        append(ok)
        assertTrue(scan().verdict(file, launch).marks)
    }

    @Test
    fun `a run longer than the limit cannot be bounded`() {
        write(marker, engine, ok, *Array(200) { noise(it) })
        val v = scan(chunk = 64, limit = 1024).verdict(file, -1)
        assertFalse(v.marks)
        assertTrue(v.reason, v.reason.contains("run unverified"))
    }

    @Test
    fun `a long run with the probe near its start is found and then not read again`() {
        write(marker, engine, ok, *Array(2000) { noise(it) })
        val s = scan(chunk = 4096)
        assertTrue(s.verdict(file, -1).marks)
        val full = s.lastBytesRead
        assertTrue(full >= file.length())
        append(noise(5000), noise(5001))
        assertTrue(s.verdict(file, -1).marks)
        assertTrue("read ${s.lastBytesRead} of ${file.length()}", s.lastBytesRead < 2048)
        // Unchanged file: the remembered answer, after checking its tail.
        assertTrue(s.verdict(file, -1).marks)
        assertTrue(s.lastBytesRead < 2048)
    }

    @Test
    fun `a new run appended to a remembered one is seen`() {
        write(marker, engine, ok, *Array(100) { noise(it) })
        val s = scan()
        assertTrue(s.verdict(file, -1).marks)
        append(marker, engine, noise(1))
        assertFalse(s.verdict(file, -1).marks)
        append(bad)
        val v = s.verdict(file, -1)
        assertFalse(v.marks)
        assertTrue(v.reason, v.reason.startsWith("daemon logged"))
    }

    @Test
    fun `a probe line that arrives after the first look opens the gate`() {
        write(marker, engine, noise(1))
        val s = scan()
        assertFalse(s.verdict(file, -1).marks)
        append(noise(2), ok)
        assertTrue(s.verdict(file, -1).marks)
    }

    @Test
    fun `a half-written last line is judged again once complete`() {
        write(marker, engine)
        file.appendText("2026/11/14 10:00:01 magicsock: netns: SO_MARK 0x2000")
        val s = scan()
        // Half a line reads as a probe line that is not the success text.
        assertFalse(s.verdict(file, -1).marks)
        file.appendText("000 set on tailscaled sockets\n")
        assertTrue(s.verdict(file, -1).marks)
    }

    @Test
    fun `an unbounded log stays unbounded until a run start appears`() {
        write(noise(1), noise(2))
        val s = scan()
        assertFalse(s.verdict(file, -1).marks)
        append(ok)
        val v = s.verdict(file, -1)
        assertFalse(v.marks)
        assertTrue(v.reason, v.reason.contains("no run start"))
        append(marker, engine, ok)
        assertTrue(s.verdict(file, -1).marks)
    }

    @Test
    fun `a truncated or rewritten log is scanned in full again`() {
        write(marker, engine, ok, *Array(50) { noise(it) })
        val s = scan()
        assertTrue(s.verdict(file, -1).marks)
        // Trimmed the way the start scripts do it, to a tail without the run start.
        write(*Array(10) { noise(it) })
        assertFalse(s.verdict(file, -1).marks)
        // Rewritten to the same length or longer: the remembered tail no longer matches.
        write(marker, engine, bad, *Array(60) { "2026/11/15 08:00:00 another run's line $it" })
        val v = s.verdict(file, -1)
        assertFalse(v.marks)
        assertTrue(v.reason, v.reason.startsWith("daemon logged"))
    }

    @Test
    fun `reset forgets the remembered answer`() {
        write(marker, engine, ok, *Array(50) { noise(it) })
        val s = scan()
        assertTrue(s.verdict(file, -1).marks)
        s.reset()
        assertTrue(s.verdict(file, -1).marks)
        // Read in full again, not answered from the tail.
        assertTrue(s.lastBytesRead >= file.length())
    }

    @Test
    fun `a missing file is a no`() {
        file.delete()
        val v = scan().verdict(file, -1)
        assertFalse(v.marks)
        assertTrue(v.reason, v.reason.startsWith("cannot read"))
    }
}
