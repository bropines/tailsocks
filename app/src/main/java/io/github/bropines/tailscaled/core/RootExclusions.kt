package io.github.bropines.tailscaled.core

import android.content.Context
import io.github.bropines.tailscaled.core.RootShell.rootLog

/**
 * What Root Mode keeps out of the tunnel, gathered from the device: the
 * user's manual CIDR exclusions and link-local ([manualExclusions],
 * [filterCidrs]), the LAN prefixes this device sits on ([ownLanPrefixes],
 * [localLanSignature]), the excluded apps' uids ([excludedUids]) and the
 * interfaces that use CGNAT space beside the tailnet ([cgnatInterfacePrefixes],
 * [cgnatGuardSignature]). The rules built from them are in [RootRoutingScript].
 */
internal object RootExclusions {
    /**
     * Destinations kept out of the exit node, as `throw` routes in the daemon's
     * table: the lookup falls through to Android's own rules, which send them
     * out of the physical interface.
     *
     * Without this, selecting an exit node in Root Mode takes the local network
     * with it — the router, a NAS, a printer, adb over Wi-Fi — because the
     * pref-200 catch-all matches every unmarked local packet, LAN included.
     * Measured on the APatch phone: `ip route get 192.168.1.100` answered
     * `dev tailscale0 table 52` until these were added. The list is the user's
     * own TUN exclusions, so both tunnel modes exclude the same things, plus
     * link-local, which nothing routes anyway.
     */
    private const val LINK_LOCAL_V4 = "169.254.0.0/16"
    private const val LINK_LOCAL_V6 = "fe80::/10"

    /** Interface name prefixes that are tunnels or bridges, never the LAN this device sits on. */
    private val NON_LAN_INTERFACES = listOf("tailscale", "tun", "ppp", "wg", "ipsec", "docker", "veth", "clat", "dummy")

    /** Accepts only a bare CIDR — the list comes from a user-editable setting. */
    private val CIDR_V4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}$")
    private val CIDR_V6 = Regex("^[0-9a-fA-F:]{2,45}/\\d{1,3}$")

    /**
     * Installs the catch-all `fwmark 0x0/0x2020000 iif lo lookup 52 priority 200`:
     * every locally generated packet without the daemon's bypass bit or Android's
     * protectedFromVpn bit consults the daemon's table first (peer /32s, subnet
     * routes, exit-node default, LAN throws) and falls through to netd when
     * nothing matches. `iif lo` = output lookups only, so forwarded and tethered
     * packets stay on netd's rules. The old rule is removed by content first
     * (any pref-200 rule pointing at table 52, so an earlier mask variant goes
     * too), and the result is read back: a rule that prints without the mask
     * (bare `fwmark 0x0` / `fwmark 0`) has kernel mask 0 and matches EVERY
     * packet, daemon included — that is a routing loop, so it is deleted again
     * and [missingMarker] is printed for the caller to surface. iproute2 4.x
     * prints the zero mark as `0x0`, 5.x and later (`%#llx`) as a bare `0`;
     * the pattern accepts both.
     */
    /**
     * The user's own exclusions plus link-local, filtered to plain CIDRs of the
     * right family. These are written as `throw` routes in the daemon's table,
     * where longest-prefix match lets a more specific accepted subnet route win
     * over a broader exclusion.
     */
    internal fun manualExclusions(context: Context, v6: Boolean): List<String> {
        val (valid, dropped) = filterCidrs(GlobalSettings.getTunExcludedCIDRs(context), v6)
        if (dropped.isNotEmpty()) {
            rootLog("WARN", "exit-node exclusions ignored, not plain CIDRs: ${dropped.joinToString(", ")}")
        }
        return valid
    }

    /**
     * The setting's text split into CIDRs of one family, link-local added:
     * the ones kept (in order, once each) and the ones dropped for not being
     * plain CIDRs.
     */
    internal fun filterCidrs(fromSettings: String, v6: Boolean): Pair<List<String>, List<String>> {
        val all = (fromSettings.split(',', ';', ' ', '\n') + listOf(LINK_LOCAL_V4, LINK_LOCAL_V6))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val wanted = all.filter { if (v6) it.contains(':') else !it.contains(':') }
        val valid = wanted.filter { if (v6) CIDR_V6.matches(it) else CIDR_V4.matches(it) }
        val dropped = wanted - valid.toSet()
        return valid.distinct() to dropped
    }

    /**
     * Prefixes this device actually sits on, when the avoidance setting is on.
     * These are written as pref-[LAN_RETURN_PRIO] rules instead of throws: the
     * conflict they exist for is an accepted route with the *same* prefix, which
     * the daemon's RouteReplace would overwrite in table 52.
     */
    internal fun autoLanExclusions(context: Context, v6: Boolean): List<String> =
        if (GlobalSettings.isRootYieldLanEnabled(context)) ownLanPrefixes(v6) else emptyList()

    /**
     * Prefixes this device holds on a real interface: the LAN the platform
     * routes on its own. CGNAT and link-local are dropped (the first belongs to
     * [cgnatInterfacePrefixes], the second to nothing), and so is every
     * interface that is a tunnel rather than a link.
     */
    private fun ownLanPrefixes(v6: Boolean): List<String> {
        val found = mutableListOf<String>()
        val interfaces =
            try {
                java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            } catch (e: Exception) {
                return emptyList()
            }
        for (nif in interfaces) {
            val name = nif.name ?: continue
            if (runCatching { nif.isLoopback }.getOrDefault(true)) continue
            if (!runCatching { nif.isUp }.getOrDefault(false)) continue
            if (NON_LAN_INTERFACES.any { name.startsWith(it) }) continue
            for (ia in nif.interfaceAddresses.orEmpty()) {
                val addr = ia.address ?: continue
                val len = ia.networkPrefixLength.toInt()
                if (!v6 && addr is java.net.Inet4Address && len in 1..32) {
                    val value = ipv4Value(addr.address)
                    if (value in 0x64400000L..0x647fffffL) continue
                    if (value in 0xa9fe0000L..0xa9feffffL) continue
                    if (value in 0x7f000000L..0x7fffffffL) continue
                    found.add(ipv4Network(addr.address, len))
                } else if (v6 && addr is java.net.Inet6Address && len in 1..128) {
                    if (addr.isLinkLocalAddress || addr.isLoopbackAddress) continue
                    val bytes = addr.address
                    val network = ByteArray(bytes.size) { i ->
                        val keep = (len - i * 8).coerceIn(0, 8)
                        if (keep == 0) 0 else (bytes[i].toInt() and (0xff shl (8 - keep))).toByte()
                    }
                    val masked = java.net.InetAddress.getByAddress(network) as java.net.Inet6Address
                    found.add("${masked.hostAddress}/$len")
                }
            }
        }
        return found.distinct()
    }

    /** An IPv4 address's four bytes as an unsigned number. */
    internal fun ipv4Value(bytes: ByteArray): Long = bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }

    /** `a.b.c.d/len` for the network an IPv4 address of prefix length [len] (1..32) sits in. */
    internal fun ipv4Network(bytes: ByteArray, len: Int): String {
        val mask = (0xffffffffL shl (32 - len)) and 0xffffffffL
        val network = ipv4Value(bytes) and mask
        val dotted = (24 downTo 0 step 8).joinToString(".") { ((network shr it) and 0xff).toString() }
        return "$dotted/$len"
    }

    /** Signature for the routing-apply gate: a changed LAN prefix must re-apply the rules. */
    fun localLanSignature(): String = (ownLanPrefixes(false) + ownLanPrefixes(v6 = true)).sorted().joinToString(",")

    /**
     * The user's excluded apps as uids, resolved at apply time.
     *
     * The same list the TUN mode hands to `addDisallowedApplication`, so one
     * setting governs both tunnel modes. A package that is no longer installed
     * is skipped and named in the log rather than failing the apply.
     */
    internal fun excludedUids(context: Context): List<Int> {
        val pkgs = GlobalSettings.getTunExcludedApps(context).map { it.trim() }.filter { it.isNotEmpty() }
        if (pkgs.isEmpty()) return emptyList()
        val pm = context.packageManager
        val uids = mutableListOf<Int>()
        val skipped = mutableListOf<String>()
        for (pkg in pkgs.sorted()) {
            try {
                uids.add(pm.getApplicationInfo(pkg, 0).uid)
            } catch (e: Exception) {
                skipped.add(pkg)
            }
        }
        if (skipped.isNotEmpty()) {
            rootLog("INFO", "excluded apps not installed, skipped: ${skipped.joinToString(", ")}")
        }
        return uids.distinct().sorted()
    }

    /**
     * Prefixes inside 100.64.0.0/10 that belong to an interface which is not
     * ours, paired with the interface that holds them.
     *
     * `to 100.64.0.0/10 iif lo lookup <ours> priority 100` is unconditional and
     * sits above every per-app exclusion, so nothing on the device can opt out
     * of it. That is right while the CGNAT range means "the tailnet" and wrong
     * the moment it does not: a carrier handing out a 100.64.0.0/10 WAN address,
     * or another VPN client whose relay or tun sits there, and we capture
     * traffic that has nothing to do with Tailscale — with no way for the user
     * to get it back. Upstream guards the same case in ipn/ipnlocal/local.go
     * through netmon's HasCGNATInterface(), which walks the interfaces for a
     * prefix overlapping the range and skips Tailscale's own; this is that walk,
     * from Java, and every prefix it returns becomes a `throw` route in our
     * table. Longest-prefix match inside the table picks the throw over our /10
     * and the lookup falls through to the rules below ours: the pref-
     * [CATCH_ALL_PRIO] catch-all where it is installed, so a tailnet peer that
     * happens to sit inside the colliding prefix is still reached through its
     * own /32 in the daemon's table, and netd otherwise, which routes it exactly
     * as it did before Root Mode started.
     *
     * A prefix shorter than /11 would cover more than the CGNAT range itself and
     * a /10 would replace our own route outright, so those are refused and named
     * rather than installed.
     */
    internal fun cgnatInterfacePrefixes(): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        val interfaces = try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            rootLog("WARN", "CGNAT guard skipped: this device's interface list could not be read (${e.message})")
            return emptyList()
        }
        for (nif in interfaces) {
            val name = nif.name ?: continue
            // Ours by name, the way upstream's isTailscaleInterface does it: the
            // whole point is to find someone else's CGNAT address.
            if (name.startsWith("tailscale")) continue
            if (!(runCatching { nif.isUp }.getOrDefault(false))) continue
            for (ia in nif.interfaceAddresses.orEmpty()) {
                val addr = ia.address as? java.net.Inet4Address ?: continue
                val value = ipv4Value(addr.address)
                if (value !in 0x64400000L..0x647fffffL) continue
                val len = ia.networkPrefixLength.toInt()
                if (len !in 11..32) {
                    rootLog(
                        "WARN",
                        "$name holds ${addr.hostAddress}/$len inside 100.64.0.0/10, but a /$len throw route would " +
                            "cover the tailnet itself; leaving the tailnet rule alone and that interface captured"
                    )
                    continue
                }
                found.add(name to ipv4Network(addr.address, len))
            }
        }
        return found.distinctBy { it.second }
    }

    /**
     * The CGNAT guard as a routing-signature term: every colliding prefix, in a
     * stable order.
     *
     * The guard is the one input of an apply that is read from the device rather
     * than from a setting, and an apply is skipped while its inputs are
     * unchanged. Without this term the walk below is repeated only when
     * something else happens to change: a carrier that hands out a
     * 100.64.0.0/10 address after the last apply is captured for the rest of the
     * session, and a throw for an interface that has gone stays in our table.
     * It belongs in that list rather than in a callback because it costs what
     * every other member costs — no root shell, no network — and because the
     * question it answers is "would the ruleset differ?", which is the only
     * question the signature asks.
     */
    fun cgnatGuardSignature(): String =
        cgnatInterfacePrefixes().map { it.second }.sorted().joinToString(",")
}
