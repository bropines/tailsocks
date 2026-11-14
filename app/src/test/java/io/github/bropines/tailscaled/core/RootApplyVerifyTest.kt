package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The end of the routing apply ([RootUtils.applyVerifyScript]), run in a POSIX
 * shell against stand-in `ip` and `iptables` that print what a device would.
 * A partial ruleset must fail the script (and so go through the apply's
 * retry); a tailscale0 the daemon left unconfigured must be named and fail
 * nothing.
 */
class RootApplyVerifyTest {

    private val dir: File = File.createTempFile("verify", "").apply { delete(); mkdirs() }

    private val fullRules = "0:\tfrom all lookup local\n" +
        "100:\tfrom all fwmark 0x1000000/0x1000000 lookup 53\n" +
        "100:\tfrom all to 100.64.0.0/10 iif lo lookup 53\n" +
        "200:\tfrom all fwmark 0x0/0x2020000 iif lo lookup 52\n" +
        "32000:\tfrom all unreachable\n"
    private val fullDns = "-N TAILSOCKS_DNS\n" +
        "-A TAILSOCKS_DNS -d 100.64.0.0/10 -j RETURN\n" +
        "-A TAILSOCKS_DNS -p udp -m udp --dport 53 -j DNAT --to-destination 100.100.100.100:53\n" +
        "-A TAILSOCKS_DNS -p tcp -m tcp --dport 53 -j DNAT --to-destination 100.100.100.100:53\n"

    private class Device(
        var addr: String = "5: tailscale0: <POINTOPOINT,UP>\n    inet 100.92.68.28/32 scope global tailscale0\n",
        var table52: String = "default dev tailscale0\n100.100.100.100 dev tailscale0\n",
        var table53: String = "100.64.0.0/10 dev tailscale0 metric 1\n",
        var rules: String = "",
        var markHook: Boolean = true,
        var dns: String? = null,
    )

    private fun fakes(d: Device): String {
        File(dir, "addr").writeText(d.addr)
        File(dir, "t52").writeText(d.table52)
        File(dir, "t53").writeText(d.table53)
        File(dir, "rules").writeText(d.rules)
        File(dir, "markhook").let { if (d.markHook) it.writeText("") else it.delete() }
        File(dir, "dns").let { f -> d.dns?.let { f.writeText(it) } ?: f.delete() }
        return """
            D='${dir.absolutePath}'
            ip() {
                case "${'$'}*" in
                    "addr show dev tailscale0") cat "${'$'}D/addr";;
                    "route show table 52") cat "${'$'}D/t52";;
                    "route show table 53") cat "${'$'}D/t53";;
                    "rule show") cat "${'$'}D/rules";;
                    *) return 1;;
                esac
            }
            iptables() {
                case "${'$'}*" in
                    "-w -t mangle -C OUTPUT -d 100.64.0.0/10 -j TAILSOCKS_MARK") [ -e "${'$'}D/markhook" ];;
                    "-w -t nat -S TAILSOCKS_DNS") [ -e "${'$'}D/dns" ] && cat "${'$'}D/dns";;
                    *) return 1;;
                esac
            }
        """.trimIndent() + "\n"
    }

    private class Run(val code: Int, val out: String)

    private fun run(d: Device, installDns: Boolean = true, checkExitRoute: Boolean = true): Run {
        val sh = listOf("/system/bin/sh", "/bin/mksh", "/usr/bin/mksh", "/bin/ksh", "/usr/bin/ksh", "/bin/dash", "/usr/bin/dash")
            .map { File(it) }.firstOrNull { it.canExecute() }
        assumeTrue("needs mksh, ksh or dash", sh != null)
        val script = fakes(d) + RootUtils.applyVerifyScript(installDns, checkExitRoute)
        val p = ProcessBuilder(sh!!.path, "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        return Run(p.exitValue(), out)
    }

    private fun healthy() = Device(rules = fullRules, dns = fullDns)

    @Test
    fun `a complete ruleset verifies and names no daemon problem`() {
        val r = run(healthy())
        assertEquals(r.out, 0, r.code)
        assertTrue(r.out, r.out.contains("verify: ok"))
        assertFalse(r.out, r.out.contains("daemon:"))
    }

    @Test
    fun `a missing priority-100 rule fails the apply`() {
        val noMark = run(healthy().apply { rules = fullRules.lines().filterNot { it.contains("fwmark 0x1000000") }.joinToString("\n") })
        assertEquals(1, noMark.code)
        assertTrue(noMark.out, noMark.out.contains("verify: priority-100 fwmark rule"))
        val noDest = run(healthy().apply { rules = fullRules.lines().filterNot { it.contains("to 100.64") }.joinToString("\n") })
        assertEquals(1, noDest.code)
        assertTrue(noDest.out, noDest.out.contains("verify: priority-100 tailnet destination rule"))
    }

    @Test
    fun `a missing mangle hook fails the apply`() {
        val r = run(healthy().apply { markHook = false })
        assertEquals(1, r.code)
        assertTrue(r.out, r.out.contains("verify: mangle OUTPUT jump into TAILSOCKS_MARK missing"))
    }

    @Test
    fun `a DNS chain without its DNAT lines fails the apply, but only when DNS was installed`() {
        val onlyReturns = "-N TAILSOCKS_DNS\n-A TAILSOCKS_DNS -d 100.64.0.0/10 -j RETURN\n"
        val r = run(healthy().apply { dns = onlyReturns })
        assertEquals(1, r.code)
        assertTrue(r.out, r.out.contains("verify: TAILSOCKS_DNS lacks its DNAT lines"))
        val oneDnat = run(healthy().apply { dns = fullDns.lines().filterNot { it.contains("tcp") }.joinToString("\n") })
        assertEquals(1, oneDnat.code)
        val missing = run(healthy().apply { dns = null })
        assertEquals(1, missing.code)
        assertTrue(missing.out, missing.out.contains("verify: TAILSOCKS_DNS chain missing"))
        assertEquals(0, run(healthy().apply { dns = null }, installDns = false).code)
    }

    @Test
    fun `an unconfigured tailscale0 is named and fails nothing`() {
        // What a second daemon's start-up cleanup left: no address, nothing but our throws in 52.
        val r = run(healthy().apply { addr = "5: tailscale0: <POINTOPOINT,UP>\n"; table52 = "throw 192.168.0.0/16\n" })
        assertEquals(r.out, 0, r.code)
        assertTrue(r.out, r.out.contains(RootUtils.DAEMON_NO_ADDRESS))
        assertTrue(r.out, r.out.contains(RootUtils.DAEMON_NO_EXIT_ROUTE))
        assertTrue(r.out, r.out.contains("verify: ok"))
    }

    @Test
    fun `no exit node, no default route expected`() {
        val r = run(healthy().apply { table52 = "100.100.100.100 dev tailscale0\n" }, checkExitRoute = false)
        assertEquals(0, r.code)
        assertFalse(r.out, r.out.contains("daemon:"))
    }

    @Test
    fun `an IPv6-only tailnet address counts`() {
        val r = run(healthy().apply { addr = "    inet6 fd7a:115c:a1e0::1234/128 scope global\n" })
        assertFalse(r.out, r.out.contains(RootUtils.DAEMON_NO_ADDRESS))
    }

    @Test
    fun `the daemon checks run even when the verify fails`() {
        val r = run(healthy().apply { addr = ""; table53 = "" })
        assertEquals(1, r.code)
        assertTrue(r.out, r.out.contains(RootUtils.DAEMON_NO_ADDRESS))
        assertTrue(r.out, r.out.contains("verify: table 53 is empty"))
    }
}
