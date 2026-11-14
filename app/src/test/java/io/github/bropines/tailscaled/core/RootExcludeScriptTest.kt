package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The per-app exclusion fragment of the routing apply, run for real in a POSIX
 * shell against a stand-in `ip` that keeps its rule list in a file. What it
 * checks is what docs/ROOT.md measured on devices: every excluded uid gets a
 * pref-190 `goto` into netd's band, a detached `goto` is replaced by the
 * physical-table form and reported, and an `ip` without uid-range support is
 * reported instead of announced as working — now with a fixed number of rule
 * reads per family, whatever the number of uids.
 */
class RootExcludeScriptTest {

    private val dir: File = File.createTempFile("excl", "").apply { delete(); mkdirs() }

    /**
     * The stand-in. `rule show` prints the family's file; `rule add` appends a
     * rule (marked `[detached]` when GOTO_BAD is set and it is a goto; refused
     * when NO_UIDRANGE is set); `rule del uidrange X priority P` removes one
     * matching rule or fails; `route get … mark` names the physical table.
     * Every `rule show` is counted in shows.log.
     */
    private fun fakeIp(): String = """
        D='${dir.absolutePath}'
        printf '0:\tfrom all lookup local\n10000:\tfrom all fwmark 0xc0000/0xd0000 lookup legacy_system\n32000:\tfrom all unreachable\n' > "${'$'}D/rules4"
        cp "${'$'}D/rules4" "${'$'}D/rules6"
        : > "${'$'}D/shows.log"
        ip() {
            fam=4
            if [ "${'$'}1" = -6 ]; then fam=6; shift; fi
            f="${'$'}D/rules${'$'}fam"
            case "${'$'}1 ${'$'}2" in
                "route get") echo "1.1.1.1 via 192.168.1.1 dev wlan0 table wlan0 src 192.168.1.5 uid 0"; return 0;;
                "rule show") echo "${'$'}fam" >> "${'$'}D/shows.log"; cat "${'$'}f"; return 0;;
                "rule add")
                    [ -n "${'$'}NO_UIDRANGE" ] && return 2
                    shift 2
                    # uidrange U goto G priority P | uidrange U iif lo lookup T priority P
                    u="${'$'}2"
                    if [ "${'$'}3" = goto ]; then
                        line="${'$'}6:	from all uidrange ${'$'}u goto ${'$'}4"
                        [ -n "${'$'}GOTO_BAD" ] && line="${'$'}line [detached]"
                    else
                        line="${'$'}8:	from all iif lo uidrange ${'$'}u lookup ${'$'}6"
                    fi
                    echo "${'$'}line" >> "${'$'}f"; return 0;;
                "rule del")
                    shift 2
                    u="${'$'}2"; p="${'$'}4"
                    n=${'$'}(grep -n "^${'$'}p:.*uidrange ${'$'}u " "${'$'}f" | head -n 1 | cut -d: -f1)
                    [ -n "${'$'}n" ] || return 2
                    sed "${'$'}{n}d" "${'$'}f" > "${'$'}f.tmp" && mv "${'$'}f.tmp" "${'$'}f"; return 0;;
            esac
            return 1
        }
    """.trimIndent() + "\n"

    private class Run(val output: String, val rules4: List<String>, val rules6: List<String>, val shows4: Int)

    private fun run(uids: List<Int>, env: Map<String, String> = emptyMap()): Run {
        // A Korn shell first, the family of Android's mksh; then dash. Not bash 3.2,
        // the /bin/sh of macOS, which cannot parse `case` inside `$( )`.
        val sh = listOf("/system/bin/sh", "/bin/mksh", "/usr/bin/mksh", "/bin/ksh", "/usr/bin/ksh", "/bin/dash", "/usr/bin/dash")
            .map { File(it) }.firstOrNull { it.canExecute() }
        assumeTrue("needs mksh, ksh or dash", sh != null)
        sh!!
        val script = fakeIp() + RootUtils.excludeRulesInstall(uids)
        val pb = ProcessBuilder(sh.path, "-c", script).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        fun rules(fam: Int) = File(dir, "rules$fam").readLines().filter { it.startsWith("190:") }
        val shows = File(dir, "shows.log").readLines().count { it == "4" }
        return Run(out, rules(4), rules(6), shows)
    }

    @Test
    fun `no uids, no fragment`() {
        assertEquals("", RootUtils.excludeRulesInstall(emptyList()))
    }

    @Test
    fun `every uid jumps to the lowest rule in netd's band, in both families`() {
        val r = run(listOf(10123, 10456))
        assertEquals(listOf("190:\tfrom all uidrange 10123-10123 goto 10000", "190:\tfrom all uidrange 10456-10456 goto 10000"), r.rules4)
        assertEquals(r.rules4, r.rules6)
        assertFalse(r.output, r.output.contains("verify:"))
    }

    @Test
    fun `a detached goto is replaced by the physical table and reported`() {
        val r = run(listOf(10123, 10456), mapOf("GOTO_BAD" to "1"))
        assertEquals(
            listOf("190:\tfrom all iif lo uidrange 10123-10123 lookup wlan0", "190:\tfrom all iif lo uidrange 10456-10456 lookup wlan0"),
            r.rules4
        )
        assertTrue(r.output, r.output.contains("verify: exclusion goto target unresolved, physical table used instead"))
        assertFalse(r.output, r.output.contains("refused every uidrange rule"))
    }

    @Test
    fun `an ip without uidrange support is reported, not announced`() {
        val r = run(listOf(10123), mapOf("NO_UIDRANGE" to "1"))
        assertTrue(r.rules4.isEmpty())
        assertTrue(r.output, r.output.contains("verify: ip refused every uidrange rule, per-app exclusions not installed"))
    }

    @Test
    fun `the rule list is read a fixed number of times whatever the number of uids`() {
        val one = run(listOf(10123)).shows4
        val many = run((10100..10109).toList()).shows4
        // Band discovery, the snapshot after the adds, the check after the fallbacks.
        assertEquals(3, one)
        assertEquals(one, many)
    }
}
