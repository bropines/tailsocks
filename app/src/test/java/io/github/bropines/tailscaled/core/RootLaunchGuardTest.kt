package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The head of the root daemon's launch script ([RootUtils.launchGuardScript]),
 * run for real in a POSIX shell against a stand-in `pgrep` that reads a process
 * list from a file. The launch itself is a stand-in too: it adds the daemon to
 * that list, counts itself, and takes half a second, as su and the daemon do.
 */
class RootLaunchGuardTest {

    private val dir: File = File.createTempFile("launch", "").apply { delete(); mkdirs() }
    private val socket = "/data/user/0/io.github.bropines.tailscaled/files/tailscaled.sock"
    private val lock = File(dir, "files/root-daemon-launch.lock")

    private val sh: File? = listOf("/system/bin/sh", "/bin/mksh", "/usr/bin/mksh", "/bin/ksh", "/usr/bin/ksh", "/bin/dash", "/usr/bin/dash")
        .map { File(it) }.firstOrNull { it.canExecute() }

    private fun script(extra: String = ""): String {
        val d = dir.absolutePath
        return """
            D='$d'
            mkdir -p "${'$'}D/files"
            pgrep() { [ "${'$'}1" = -f ] && [ "${'$'}2" = -- ] || return 2; grep -qF -- "${'$'}3" "${'$'}D/procs" 2>/dev/null; }
            $extra
        """.trimIndent() + "\n" +
            RootUtils.launchGuardScript(lock.absolutePath, socket) +
            "echo \"libtailscale.so --socket=$socket\" >> \"\$D/procs\"\n" +
            "echo launched >> \"\$D/launches\"\n" +
            "sleep 0.5\n"
    }

    private fun start(script: String): Process {
        assumeTrue("needs mksh, ksh or dash", sh != null)
        return ProcessBuilder(sh!!.path, "-c", script).redirectErrorStream(true).start()
    }

    private fun finish(p: Process): String {
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        return out
    }

    private fun launches(): Int = File(dir, "launches").takeIf { it.exists() }?.readLines()?.size ?: 0

    @Test
    fun `two concurrent launches start one daemon`() = concurrentLaunches()

    /**
     * uutils coreutils' mkdir (Ubuntu 26.04, 0.10) reports success to both of two concurrent calls
     * for one directory, and this test launched two daemons on CI. The stand-in does the same on
     * every call — success whether the directory was there or not — so the lock must hold by its
     * pid file alone, wherever the test runs.
     */
    @Test
    fun `two concurrent launches start one daemon where mkdir does not refuse an existing directory`() =
        concurrentLaunches("mkdir() { command mkdir -p \"${'$'}@\"; }")

    private fun concurrentLaunches(extra: String = "") {
        repeat(10) { round ->
            File(dir, "procs").delete(); File(dir, "launches").delete(); File(dir, "go").delete()
            // Both shells spin until the go file appears, so they reach the guard together. One
            // may still come late, after the other has launched and let the lock go: it then finds
            // the daemon. Either way one launches and the other says why it did not.
            val barrier = "$extra\nwhile [ ! -e \"\$D/go\" ]; do :; done"
            val a = start(script(barrier))
            val b = start(script(barrier))
            Thread.sleep(150)
            File(dir, "go").writeText("")
            val outs = listOf(finish(a), finish(b))
            assertEquals("round $round: $outs", 1, launches())
            assertTrue(
                "round $round: $outs",
                outs.count { it.contains(RootUtils.LAUNCH_SKIPPED_LOCKED) || it.contains(RootUtils.LAUNCH_SKIPPED_RUNNING) } == 1
            )
            assertFalse("the lock goes with the shell that held it", lock.exists())
        }
    }

    @Test
    fun `a daemon that is already running is not launched again`() {
        File(dir, "procs").writeText("libtailscale.so --statedir=x --socket=$socket\n")
        val out = finish(start(script()))
        assertTrue(out, out.contains(RootUtils.LAUNCH_SKIPPED_RUNNING))
        assertEquals(0, launches())
        assertFalse(lock.exists())
    }

    @Test
    fun `a daemon on another socket does not count`() {
        File(dir, "procs").writeText("libtailscale.so --socket=/data/user/0/other/files/tailscaled.sock\n")
        val out = finish(start(script()))
        assertFalse(out, out.contains("skipped"))
        assertEquals(1, launches())
    }

    @Test
    fun `a lock held by a live launch is left alone`() {
        // Process.pid() is not in Android's java.lang.Process; the shell says its own.
        val holder = ProcessBuilder("/bin/sh", "-c", "echo \$\$; exec sleep 20").start()
        try {
            val pid = holder.inputStream.bufferedReader().readLine()
            lock.mkdirs()
            File(lock, "pid").writeText("$pid\n")
            val out = finish(start(script()))
            assertTrue(out, out.contains(RootUtils.LAUNCH_SKIPPED_LOCKED))
            assertEquals(0, launches())
            assertTrue("someone else's lock is not removed", lock.exists())
        } finally {
            holder.destroyForcibly()
        }
    }

    @Test
    fun `a lock whose owner is gone is taken over`() {
        val gone = ProcessBuilder("/bin/sh", "-c", "echo \$\$").start().let { p ->
            p.inputStream.bufferedReader().readLine().also { p.waitFor() }
        }
        lock.mkdirs()
        File(lock, "pid").writeText("$gone\n")
        val out = finish(start(script()))
        assertFalse(out, out.contains("skipped"))
        assertEquals(1, launches())
        assertFalse(lock.exists())
    }

    @Test
    fun `a lock with no owner yet is a launch in progress, unless it is old`() {
        lock.mkdirs()
        val fresh = finish(start(script()))
        assertTrue(fresh, fresh.contains(RootUtils.LAUNCH_SKIPPED_LOCKED))
        assertEquals(0, launches())
        // An mtime of the epoch: far older than any launch takes.
        val old = finish(start(script("stat() { echo 0; }")))
        assertFalse(old, old.contains("skipped"))
        assertEquals(1, launches())
    }

    @Test
    fun `the socket path is quoted into the pattern`() {
        val s = RootUtils.launchGuardScript("/d/files/root-daemon-launch.lock", "/x/it's.sock")
        assertTrue(s, s.contains("TS_LAUNCH_PAT='--socket=/x/it'\\''s.sock'"))
        assertTrue(s, s.contains("TS_LAUNCH_LOCK='/d/files/root-daemon-launch.lock'"))
    }
}
