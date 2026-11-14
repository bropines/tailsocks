package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.bropines.tailscaled.core.RootRouting.DAEMON_TABLE
import io.github.bropines.tailscaled.core.RootRouting.LEGACY_ROUTE_TABLE
import io.github.bropines.tailscaled.core.RootRouting.NETD_RULE_MAX
import io.github.bropines.tailscaled.core.RootRouting.NETD_RULE_MIN
import io.github.bropines.tailscaled.core.RootRouting.PROTECT_MARK
import io.github.bropines.tailscaled.core.RootRouting.ROUTE_TABLE
import io.github.bropines.tailscaled.core.RootShell.runSu
import io.github.bropines.tailscaled.core.RootUtils.ForeignVpnVerdict

/**
 * The coexistence probe: whether another VPN client holds this device, read
 * from the root side ([detectForeignVpn], [parseForeignVpnProbe] — netd's
 * per-uid rules are the only trace a foreign tunnel leaves that a root shell
 * can see), and the uid arithmetic that scopes the catch-all to the ranges
 * that tunnel bypasses ([splitAround], [foreignBypassGaps], [carryableGaps]).
 * The verdict type, [RootUtils.ForeignVpnVerdict], stays on the façade.
 */
internal object RootForeignVpn {
    /** Highest uid netd writes rules for; the ranges are dense below it. */
    private const val MAX_ANDROID_UID = 99_999L

    /**
     * The uids the other tunnel does *not* claim.
     *
     * netd publishes VPN membership as uid ranges; everything between them is an
     * app its owner put in the bypass list, which Android then routes over the
     * physical network. Those are the apps we can still carry without touching
     * anything that belongs to the other tunnel — which is what makes coexisting
     * something better than "we switch off".
     *
     * Our own uid is dropped: the other tunnel excludes us so we always show up
     * in a gap, and routing the app's own traffic into our table buys nothing.
     */
    /**
     * uids of apps that provide a `VpnService` of their own.
     *
     * A VPN client is always outside its own tunnel — it would loop its relay
     * traffic through itself otherwise — so its uid is always a gap, every time,
     * whether or not its owner bypassed anything. Carrying it would route the
     * other client's relay out through our exit node, which nobody asked for,
     * and would keep [foreignBypassGaps] from ever returning empty, so the full
     * yield could never be reached. Android knows who these apps are; we ask it.
     */
    private fun vpnClientUids(vpnServices: List<android.content.pm.ResolveInfo>?): Set<Long> =
        vpnServices?.mapNotNull { it.serviceInfo?.applicationInfo?.uid?.toLong() }?.toSet() ?: emptySet()

    /** Every app's `VpnService`, asked once per apply for both uses below; null when the query failed. */
    private fun vpnServices(context: Context): List<android.content.pm.ResolveInfo>? = runCatching {
        context.packageManager.queryIntentServices(Intent("android.net.VpnService"), PackageManager.MATCH_ALL)
    }.getOrNull()

    /**
     * uids we are willing to carry: installed applications that are not VPN
     * clients and not us.
     *
     * Built as a positive set on purpose. Subtracting the bad uids instead meant
     * trusting what the framework reports for an arbitrary uid, and a gap can
     * contain uids no application owns at all — Android reserves an SDK sandbox
     * uid beside every app (appId + 10000), so netd's membership list has a hole
     * there too. Measured on the device: uid 20354 sat in a gap with no package
     * behind it, and carrying it wrote a rule that could never match anything.
     */
    private fun carryableUids(context: Context, vpnServices: List<android.content.pm.ResolveInfo>?): Set<Long>? {
        val pm = context.packageManager
        vpnServices ?: return null
        return runCatching {
            val vpnPackages = vpnServices
                .mapNotNull { it.serviceInfo?.packageName }
                .toSet()
            val selfUid = context.applicationInfo?.uid?.toLong()
            pm.getInstalledApplications(0)
                .filter { it.packageName !in vpnPackages }
                .map { it.uid.toLong() }
                .filterNot { it == selfUid }
                .toSet()
        }.getOrNull()
    }

    /** Splits [range] around [holes], dropping the holes themselves. */
    internal fun splitAround(range: LongRange, holes: Collection<Long>): List<LongRange> {
        val sorted = holes.filter { it in range }.sorted()
        if (sorted.isEmpty()) return listOf(range)
        val out = mutableListOf<LongRange>()
        var start = range.first
        for (h in sorted) {
            if (start <= h - 1) out.add(start..(h - 1))
            start = h + 1
        }
        if (start <= range.last) out.add(start..range.last)
        return out
    }

    /** Beyond this many uids a gap is kept whole rather than resolved app by app. */
    private const val GAP_RESOLVE_LIMIT = 64

    internal fun foreignBypassGaps(ranges: List<LongRange>): List<LongRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val gaps = mutableListOf<LongRange>()
        var next = 0L
        for (r in sorted) {
            if (r.first > next) gaps.add(next..(r.first - 1))
            if (r.last + 1 > next) next = r.last + 1
        }
        if (next <= MAX_ANDROID_UID) gaps.add(next..MAX_ANDROID_UID)
        return gaps
    }

    /**
     * The gaps we may actually carry: everything the other tunnel bypassed, minus
     * ourselves and minus every uid that is a VPN client or nothing at all.
     *
     * A small gap is resolved uid by uid, which is the common case — a handful of
     * apps the user picked. A large one is kept whole and only known VPN uids are
     * cut out of it, so a client running in "tunnel only these apps" mode cannot
     * make us walk thousands of uids or install a rule per app.
     */
    internal fun carryableGaps(ranges: List<LongRange>, selfUid: Long?, context: Context): List<LongRange> {
        val raw = foreignBypassGaps(ranges)
        if (raw.isEmpty()) return raw
        val services = vpnServices(context)
        val vpnUids = vpnClientUids(services) + listOfNotNull(selfUid)
        val carryable = carryableUids(context, services)
        return raw.flatMap { g ->
            val span = g.last - g.first + 1
            when {
                // The ordinary case: a handful of apps the user picked. Name them
                // one by one, so nothing is captured that no app owns.
                carryable != null && span <= GAP_RESOLVE_LIMIT ->
                    (g.first..g.last).filter { it in carryable }.map { it..it }
                // A client tunnelling only a few apps leaves most of the device in
                // the gaps. Keep those whole rather than writing a rule per app;
                // the uids that own nothing cost a rule that never matches, which
                // is cheaper than hundreds of rules that do.
                else -> splitAround(g, vpnUids)
            }
        }
    }

    /**
     * Looks for another VPN client that owns this device, from a root shell and
     * without a Context.
     *
     * ConnectivityManager can only report a VPN transport the calling app is a
     * member of, so a tunnel that excludes TailSocks is invisible to it — and
     * that is exactly the tunnel we would break. Two independent traces are
     * cross-checked instead, and both must be there:
     *  - a tunnel interface that is not ours. Only `tun`/`ppp`/`wg`/`ipsec`
     *    names count, so our own `tailscale0` can never match; a name is never
     *    proof on its own either (a stopped client leaves its interface behind,
     *    and a kernel WireGuard tunnel may be routed by hand).
     *  - a per-uid rule inside netd's own band pointing at a table that is
     *    neither ours nor a physical one. That is how netd expresses "these apps
     *    are members of a VPN", and nothing else on the device writes it.
     *
     * The physical tables are read back through Android's protectedFromVpn mark,
     * the same way [excludeRulesInstall] finds them, so the Wi-Fi and mobile
     * tables are never mistaken for a tunnel's.
     */
    fun detectForeignVpn(): ForeignVpnVerdict {
        val script = buildString {
            append("PHYS=$(ip route get 1.1.1.1 mark $PROTECT_MARK 2>/dev/null | sed -n 's/.*table \\([A-Za-z0-9_]*\\).*/\\1/p' | head -n1)\n")
            append("PHYS6=$(ip -6 route get 2001:4860:4860::8888 mark $PROTECT_MARK 2>/dev/null | sed -n 's/.*table \\([A-Za-z0-9_]*\\).*/\\1/p' | head -n1)\n")
            append("ip -o link show 2>/dev/null | grep -Eo '(tun[0-9]+|ppp[0-9]+|wg[0-9]+|ipsec[0-9]+)' | sort -u | while read -r n; do\n")
            append("    echo \"FOREIGN_IF \$n\"\n")
            append("done\n")
            // `ip rule show` prints one rule per line, priority first; the table
            // is the word after `lookup`. Parsed with shell builtins only —
            // awk is not on every Android image.
            for (ip in listOf("ip", "ip -6")) {
                append("$ip rule show 2>/dev/null | while read -r p rest; do\n")
                append("    p=\${p%:}\n")
                append("    case \"\$p\" in ''|*[!0-9]*) continue;; esac\n")
                append("    [ \"\$p\" -ge $NETD_RULE_MIN ] || continue\n")
                append("    [ \"\$p\" -le $NETD_RULE_MAX ] || continue\n")
                append("    case \"\$rest\" in *uidrange*) ;; *) continue;; esac\n")
                append("    case \"\$rest\" in *'lookup '*) ;; *) continue;; esac\n")
                append("    t=\${rest##*lookup }\n")
                append("    t=\${t%% *}\n")
                append("    case \"\$t\" in $ROUTE_TABLE|$LEGACY_ROUTE_TABLE|$DAEMON_TABLE|main|local|default|tailscale0|'') continue;; esac\n")
                append("    [ \"\$t\" = \"\$PHYS\" ] && continue\n")
                append("    [ \"\$t\" = \"\$PHYS6\" ] && continue\n")
                append("    u=\"\"; case \"\$rest\" in *'uidrange '*) u=\${rest#*uidrange }; u=\${u%% *};; esac\n")
                append("    m=\"\"; case \"\$rest\" in *'fwmark '*) m=\${rest#*fwmark }; m=\${m%% *};; esac\n")
                append("    [ -n \"\$u\" ] && echo \"FOREIGN_UIDS \$t \$u\"\n")
                append("    [ -n \"\$m\" ] && echo \"FOREIGN_MARK \$t \$m\"\n")
                append("    echo \"FOREIGN_RULE \$p \$t\"\n")
                append("done\n")
            }
        }
        val res = runSu("coexist-probe", script, timeoutMs = 10_000L)
        if (!res.ok) {
            // A probe we could not run is not evidence of a free device, but
            // refusing to install anything on it would be worse: the same root
            // shell failure would already have broken the apply itself.
            return ForeignVpnVerdict(false, "the coexistence probe could not be run")
        }
        return parseForeignVpnProbe(res.output)
    }

    /** The coexistence probe's `FOREIGN_*` lines, read into a verdict. */
    internal fun parseForeignVpnProbe(output: String): ForeignVpnVerdict {
        fun values(tag: String) = output.lineSequence()
            .filter { it.startsWith("$tag ") }
            .map { it.removePrefix("$tag ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        val ifaces = values("FOREIGN_IF")
        // Membership counts only when it points at the tunnel itself. netd names
        // a network's table after its interface, so "lookup tun0" is the other
        // client's and "lookup wlan0_local" is not — and that distinction is
        // load-bearing: the physical *local* table carries a uidrange spanning
        // every uid on the device, and folding it in leaves no gaps at all,
        // which silently turns the partial yield back into a full one.
        fun forTunnel(tag: String) = values(tag).mapNotNull { line ->
            val table = line.substringBefore(' ').trim()
            val rest = line.substringAfter(' ', "").trim()
            if (table.isEmpty() || rest.isEmpty() || table !in ifaces) null else rest
        }
        // "10355-10374" as netd printed it. A malformed line is dropped rather
        // than guessed at: a wrong range would hand the other tunnel's apps to us.
        val memberRanges = forTunnel("FOREIGN_UIDS").mapNotNull { spec ->
            val parts = spec.split("-")
            if (parts.size != 2) return@mapNotNull null
            val lo = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
            val hi = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
            if (lo > hi) null else lo..hi
        }.distinct()
        // "0x10084/0x1ffff" -> netId 0x84. The low 16 bits are netd's network id;
        // the bits above it (explicitly-selected, permission) vary per rule.
        val netIds = forTunnel("FOREIGN_MARK").mapNotNull { spec ->
            val value = spec.substringBefore('/').trim().removePrefix("0x").removePrefix("0X")
            value.toIntOrNull(16)?.and(0xffff)
        }.filter { it != 0 }.distinct()
        // One rule per uid range, so a tunnel with many member apps prints many
        // identical pref/table pairs; the log only needs to name the tunnel.
        val rules = values("FOREIGN_RULE").take(4)
        return when {
            ifaces.isNotEmpty() && rules.isNotEmpty() -> ForeignVpnVerdict(
                true,
                "${ifaces.joinToString(", ")} is up and this device's routing sends apps to it (${rules.joinToString("; ")})",
                memberRanges,
                netIds
            )
            ifaces.isNotEmpty() -> ForeignVpnVerdict(
                false,
                "${ifaces.joinToString(", ")} exists but no app is routed into it"
            )
            rules.isNotEmpty() -> ForeignVpnVerdict(
                false,
                "apps are routed to ${rules.joinToString("; ")} but no tunnel interface is up"
            )
            else -> ForeignVpnVerdict(false, "no other tunnel interface and no foreign per-app routing rules")
        }
    }
}
