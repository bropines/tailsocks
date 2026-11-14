package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every pure shell-script builder of Root Mode, for fixed inputs, against a
 * recorded copy: the fragments [RootUtils.applyTailscale0Routing] concatenates
 * into one apply (tailnet tier, catch-all, exclusions, LAN throws and return
 * rules, CGNAT guard, DNS hooks, rp_filter, teardowns, verify tail), the whole
 * cleanup script, and the launch, stop and hosts scripts.
 *
 * The apply script itself is assembled inside [RootUtils.applyTailscale0Routing]
 * from device reads (settings, the package manager, the daemon log), so it
 * cannot be built on the JVM; what can drift is pinned here instead, piece by
 * piece. src/test/resources/core/root_scripts.golden was recorded from the code
 * before RootUtils.kt was split into RootRouting/RootRoutingScript/RootDaemon/…
 * — a pure move must leave every byte of it unchanged. A deliberate change to a
 * script updates the golden in the same commit, so the diff shows the new
 * script.
 */
class RootScriptSnapshotTest {

    private fun render(): String {
        val s = RootRoutingScript
        val cases = listOf<Pair<String, () -> String>>(
            "tailnetRouteV4" to { RootUtils.tailnetRouteV4() },
            "tailnetRulesV4" to { RootUtils.tailnetRulesV4() },
            "tailnetTierV6" to { RootUtils.tailnetTierV6() },
            "legacyRuleCleanup" to { RootUtils.legacyRuleCleanup() },
            "staleDaemonRulePurge ip" to { s.staleDaemonRulePurge("ip") },
            "staleDaemonRulePurge ip -6" to { s.staleDaemonRulePurge("ip -6") },
            "excludeRulesInstall 3" to { RootUtils.excludeRulesInstall(listOf(10123, 10124, 10200)) },
            "excludeRulesInstall 0" to { RootUtils.excludeRulesInstall(emptyList()) },
            "excludeRulesTeardown" to { s.excludeRulesTeardown() },
            "bypassChainTeardown v4" to { s.bypassChainTeardown("iptables -w") },
            "bypassChainTeardown v6" to { s.bypassChainTeardown("ip6tables -w") },
            "localBypassRoutes v4" to { s.localBypassRoutes("ip", listOf("192.168.1.0/24", "10.0.0.0/8")) },
            "localBypassRoutes v6" to { s.localBypassRoutes("ip -6", listOf("fd00::/8", "fe80::/10")) },
            "localBypassRoutesRemove v4" to { s.localBypassRoutesRemove("ip", listOf("192.168.1.0/24", "10.0.0.0/8")) },
            "localBypassRoutesRemove v6" to { s.localBypassRoutesRemove("ip -6", listOf("fd00::/8")) },
            "localReturnRulesInstall v4" to { s.localReturnRulesInstall("ip", listOf("192.168.1.0/24")) },
            "localReturnRulesInstall v6" to { s.localReturnRulesInstall("ip -6", listOf("2001:db8:1::/64")) },
            "localReturnRulesRemove v4" to { s.localReturnRulesRemove("ip") },
            "localReturnRulesRemove v6" to { s.localReturnRulesRemove("ip -6") },
            "cgnatGuardRoutes" to { s.cgnatGuardRoutes(listOf("100.64.5.0/24", "100.100.0.0/16")) },
            "cgnatGuardRoutes empty" to { s.cgnatGuardRoutes(emptyList()) },
            "routeTableTeardown 53" to { s.routeTableTeardown("53", true) },
            "routeTableTeardown 1099" to { s.routeTableTeardown("1099", false) },
            "catchAllTeardown v4" to { s.catchAllTeardown("ip") },
            "catchAllTeardown v6" to { s.catchAllTeardown("ip -6") },
            "catchAllInstall v4" to { s.catchAllInstall("ip", "MISSING4", false, emptyList()) },
            "catchAllInstall v6" to { s.catchAllInstall("ip -6", "MISSING6", true, emptyList()) },
            "catchAllInstall scoped" to { s.catchAllInstall("ip", "MISSING4", false, listOf(0L..9999L, 10201L..99999L)) },
            "rpFilterLoosen" to { s.rpFilterLoosen() },
            "rpFilterRestore" to { s.rpFilterRestore() },
            "dnsChainTeardown" to { s.dnsChainTeardown() },
            "dnsHookInstall" to { s.dnsHookInstall() },
            "dnsHookRemove" to { s.dnsHookRemove() },
            "applyVerifyScript tt" to { RootUtils.applyVerifyScript(true, true) },
            "applyVerifyScript tf" to { RootUtils.applyVerifyScript(true, false) },
            "applyVerifyScript ft" to { RootUtils.applyVerifyScript(false, true) },
            "applyVerifyScript ff" to { RootUtils.applyVerifyScript(false, false) },
            // The only package-specific part of any script: the hosts copy's path (debug and release differ).
            "routingCleanupScript" to {
                val hosts = RootPaths.current.hostsFile
                RootUtils.routingCleanupScript().replace(hosts, "<HOSTS>").replace(hosts.removePrefix("/data"), "<HOSTS-REL>")
            },
            "launchLockDir" to { RootUtils.launchLockDir("/data/user/0/app") },
            "launchGuardScript" to {
                RootUtils.launchGuardScript("/data/user/0/app/files/root-daemon-launch.lock", "/data/user/0/app/files/tailscaled.sock")
            },
            "logTrimScript" to { RootUtils.logTrimScript("/data/user/0/app/logs/tailscaled.log") },
            "hostsMarker" to { RootUtils.hostsMarker("/data/adb/tailsocks/hosts") },
            "hostsUnbindScript" to { RootUtils.hostsUnbindScript("/data/adb/tailsocks/hosts", "# marker it's") },
            "daemonKillScript sock" to { RootUtils.daemonKillScript("/data/user/0/app/files/tailscaled.sock") },
            "daemonKillScript empty" to { RootUtils.daemonKillScript("") },
            "stateHandBackScript" to {
                RootUtils.stateHandBackScript(10234, "/data/user/0/app", "/storage/emulated/0/Download/Taildrop")
            },
        )
        return buildString { for ((k, f) in cases) append("=== ").append(k).append(" ===\n").append(f()).append("\n") }
    }

    @Test
    fun `every generated root script is byte-identical to the recorded one`() {
        val golden = requireNotNull(javaClass.getResource("/core/root_scripts.golden")) { "no golden file" }.readText()
        assertEquals(golden, render())
    }
}
