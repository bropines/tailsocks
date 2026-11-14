package io.github.bropines.tailscaled.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the boot script and RootUtils both write, written twice in two
 * languages: the script runs at late_start with no app process, so it cannot
 * call the Kotlin. These tests keep the copies from drifting apart.
 */
class RootBootScriptDriftTest {

    private val script: String by lazy {
        val module = listOf(File("."), File("app")).first { File(it, "src/main/assets/scripts/tailscaled.sh").isFile }
        File(module, "src/main/assets/scripts/tailscaled.sh").readText()
    }
    private val scriptLines: Set<String> by lazy { script.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }

    private fun assertAllInScript(what: String, fragment: String) {
        val missing = fragment.lines().map { it.trim() }.filter { it.isNotEmpty() && it !in scriptLines }
        assertTrue("$what: lines the boot script does not have:\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    @Test
    fun `the boot script's tailnet tier is the app's, line for line`() {
        // docs/ROOT.md §4, T1: the boot script installs this tier and nothing else.
        assertAllInScript("T1 IPv4 route", RootUtils.tailnetRouteV4())
        assertAllInScript("T1 IPv4 rules", RootUtils.tailnetRulesV4())
        assertAllInScript("T1 IPv6", RootUtils.tailnetTierV6())
        assertAllInScript("pre-3.6 rule cleanup", RootUtils.legacyRuleCleanup())
    }

    @Test
    fun `the boot script takes the launch lock the way the app does`() {
        // Both launchers must agree on the lock, or the boot script and the app
        // could each start a daemon on the same socket.
        val guard = RootUtils.launchGuardScript("/x", "/y").lines()
        val fn = guard.subList(guard.indexOf("ts_launch_lock() {"), guard.indexOf("}") + 1).joinToString("\n")
        assertAllInScript("launch lock", fn)
        assertTrue(script.contains("TS_LAUNCH_LOCK=\"\$DATA_DIR/files/root-daemon-launch.lock\""))
        assertTrue(RootUtils.launchLockDir("/data/data/p").endsWith("/files/root-daemon-launch.lock"))
        assertTrue(scriptLines.contains("if pgrep -f -- \"--socket=\$SOCKET_PATH\" >/dev/null 2>&1; then"))
    }

    @Test
    fun `the boot script installs no device-wide tier`() {
        assertTrue(scriptLines.none { it.contains("lookup 52") || it.contains("priority 200") })
        assertTrue(scriptLines.none { !it.startsWith("#") && it.contains("TAILSOCKS_DNS") })
        assertTrue(scriptLines.none { !it.startsWith("#") && it.contains("priority 190") })
    }

    @Test
    fun `the app's log trim is the boot script's`() {
        // The script names the file "$LOG_FILE"; the app quotes the real path.
        val kotlin = RootUtils.logTrimScript("@LOG@")
            .replace("'@LOG@.tmp'", "\"\$LOG_FILE.tmp\"")
            .replace("'@LOG@'", "\"\$LOG_FILE\"")
        assertAllInScript("log trim", kotlin)
    }
}
