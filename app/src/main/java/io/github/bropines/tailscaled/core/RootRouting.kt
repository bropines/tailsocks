package io.github.bropines.tailscaled.core


/**
 * The kernel-side names of the Root Mode ruleset, in one place: the policy
 * routing tables (53 ours, 52 the daemon's, 1099 the one used up to 4.0), the rule
 * priorities (150 LAN return, 190 per-app exclusions, 200 catch-all; the
 * tailnet rules at 100 are written into the scripts), the fwmark bits, the
 * iptables chains and the markers the apply script prints for the caller to
 * read back. Every script builder ([RootRoutingScript], [RootUtils.applyTailscale0Routing],
 * the cleanup) and the boot script must agree on these; `RootRoutingConstantsTest`
 * pins the values so an edit cannot change one silently.
 */
internal object RootRouting {
    /**
     * Policy routing table reserved for the tailscale0 interface.
     *
     * 53, and no longer the 1099 every build up to 4.0 used. netd names its
     * per-network tables after the interface index plus 1000 and keeps 97-99
     * for its own local and legacy tables, so table 1099 is simply the table of
     * the network whose interface index reached 99 — indexes are monotonic
     * within a boot, and a phone cycling tun/clat/rmnet/ipsec interfaces gets
     * there in ordinary use. Cleanup flushed that table unconditionally, which
     * on such a device deletes a live network's routes. 53 sits beside the
     * daemon's own 52, is recognisably ours, and is outside every range netd
     * hands out.
     */
    internal const val ROUTE_TABLE = "53"

    /**
     * The table used up to 4.0. Nothing is written here any more, but every
     * teardown path still removes what an earlier version left in it — by
     * content, one route and one rule at a time, never with a flush, because
     * the table may belong to a live network by now. Kept for at least one
     * release, so an upgrade cannot strand a ruleset nothing will collect.
     */
    internal const val LEGACY_ROUTE_TABLE = "1099"

    /**
     * Firewall mark used to steer tailnet traffic into [ROUTE_TABLE].
     *
     * Android packs its own routing decision into fwmark: the low 16 bits are the
     * netId, above them sit the explicit/protect/permission flags. Writing a bare
     * `--set-mark 1099` overwrites all of it, which tells netd the packet belongs
     * to network 1099 and breaks routing whenever another VPN owns the default
     * network. A single high bit is set through a mask instead, leaving netd's
     * bookkeeping intact.
     */
    internal const val MARK_BIT = "0x1000000"
    internal const val MARK_MASK = "0x1000000"

    /** Pre-3.6 mark, still removed on cleanup so upgrades do not leave it behind. */
    internal const val LEGACY_MARK = "1099"

    /**
     * Policy routing table the daemon writes itself (patch 13, Android router):
     * peer /32s, accepted subnet routes, 100.100.100.100/32, `default dev
     * tailscale0` while an exit node is selected and `throw` routes for the LAN
     * prefixes when LAN access is allowed. The app never adds to, flushes or
     * verify-fails on this table — it only points a rule at it.
     */
    internal const val DAEMON_TABLE = "52"

    /**
     * SO_MARK the root daemon sets on every socket it opens itself (patch 16,
     * net/netns/netns_android.go TailsocksBypassMark — the two values must stay
     * equal). Bit 25 sits outside Android's Fwmark layout (bits 0-20) and apart
     * from [MARK_BIT] (bit 24); the low 16 bits stay zero, so netd's
     * `fwmark 0x0/0xffff lookup <default network>` still matches marked packets.
     */
    internal const val BYPASS_MARK = "0x2000000"

    /**
     * Mask of the pref-200 catch-all: [BYPASS_MARK] plus Android's
     * protectedFromVpn bit (0x20000). A packet with either bit set skips the
     * daemon's table: the daemon's own WireGuard/DERP/control traffic (which
     * would otherwise loop the moment table 52 holds a default route) and the
     * sockets Android deliberately keeps off VPNs (network validation probes,
     * MMS/IMS, other VPN apps' protected sockets) — the same `fwmark 0x0/0x20000`
     * idiom netd uses for its own VPN rules.
     */
    internal const val CATCH_ALL_MASK = "0x2020000"

    internal const val CATCH_ALL_PRIO = "200"

    /**
     * Priority of the per-app exclusion rules, above the catch-all so an
     * excluded uid never consults the daemon's table at all.
     *
     * A mark set in mangle OUTPUT is too late for TCP: the route and with it
     * the source address are chosen at connect(), so a marked packet left with
     * the tailnet address on the physical interface and no reply ever came
     * back. A uid rule is evaluated at connect() and fixes both — measured on
     * the APatch phone: `ip route get 1.1.1.1 uid <excluded>` went from
     * "dev tailscale0 table 52 src 100.92.68.28" to "via 192.168.1.254 dev
     * wlan0 src 192.168.1.94" while other uids kept using the tunnel.
     */
    internal const val EXCLUDE_PRIO = "190"

    /**
     * Priority of the rules that hand a local prefix back to the platform.
     * Above [EXCLUDE_PRIO] and [CATCH_ALL_PRIO], below the tailnet rules at 100.
     * The daemon installs no rules on Android and netd's start at
     * [NETD_RULE_MIN], so this priority belongs to one purpose only.
     */
    internal const val LAN_RETURN_PRIO = "150"

    /** Android's protectedFromVpn bit; a socket carrying it is routed by the physical network. */
    internal const val PROTECT_MARK = "0x20000"

    /**
     * The band netd keeps its own rules in: per-network rules, the VPN
     * membership rules (measured at 13000 and 16000-17000 on the APatch phone)
     * and the fallthrough rules above them. Nothing of ours lives here.
     *
     * Two things read this band. The exclusion rules jump to the lowest
     * priority found inside it rather than to a hardcoded 16000 — the number
     * differs between Android versions, and `goto` to a priority that holds no
     * rule is accepted by the kernel and then silently skipped. And the
     * coexistence probe looks here for another tunnel's per-uid rules, which is
     * the only trace a foreign VPN client leaves that a root shell can read.
     */
    internal const val NETD_RULE_MIN = "10000"
    internal const val NETD_RULE_MAX = "32000"

    /** Pre-fix rp_filter value of `all`, kept so cleanup can restore it; shared, like the ruleset. */
    internal const val RP_FILTER_ORIG = RootPaths.RP_FILTER_ORIG

    /** Markers the apply script prints when a catch-all did not land; distinct so v4 → WARN, v6 → INFO. */
    internal const val V4_CATCH_ALL_MISSING = "verify: v4 exit-node catch-all NOT installed (ip dropped the fwmark mask)"
    internal const val V6_CATCH_ALL_MISSING = "verify: v6 exit-node catch-all not installed"

    /**
     * Printed by the apply script when an `-m owner` rule was refused — the
     * match is a kernel module (`xt_owner`) and some ROM kernels ship without
     * it. Only the DNS half of the per-app exclusions is lost: the uid RETURN
     * rules at the top of [CHAIN_DNS] never land, so an excluded app's port 53
     * is redirected into MagicDNS like everyone else's. Its routing exclusion
     * is unaffected — that is a policy rule, not an iptables match — so the app
     * ends up outside the tunnel with its DNS inside it. Every other rule is
     * unaffected, so the script carries on and the caller turns this into a WARN.
     */
    internal const val OWNER_MATCH_MISSING = "verify: -m owner unavailable, per-app DNS exclusions not installed"

    /**
     * Printed by the apply script when an exclusion rule was accepted with a
     * `goto` target the kernel could not resolve. `ip rule add ... goto <pref>`
     * never fails for a priority that holds no rule: the rule is installed,
     * printed with `[detached]` or `unresolved`, and skipped at lookup time —
     * so the excluded uid silently falls back into the catch-all. The script
     * deletes such a rule, installs the physical-table form instead and prints
     * this for the caller to surface.
     */
    internal const val GOTO_UNRESOLVED = "verify: exclusion goto target unresolved, physical table used instead"

    /**
     * Printed by the apply script when an excluded uid ended up with no rule of
     * its own at all: neither the `goto` form nor the physical-table fallback
     * was accepted, which on a device whose `ip` was built without uid-range
     * support is what always happens. The exclusion then does not exist, while
     * the log used to announce it as installed — the caller turns this into a
     * warning that says so. The v6 marker is only ever printed when IPv6 is up,
     * so a device with IPv6 disabled cannot raise a false alarm.
     */
    internal const val UIDRANGE_MISSING_V4 = "verify: ip refused every uidrange rule, per-app exclusions not installed"
    internal const val UIDRANGE_MISSING_V6 = "verify: ip -6 refused every uidrange rule, per-app exclusions not installed (v6)"

    /*
     * Every iptables/ip6tables call here (and in the boot script) passes `-w`:
     * wait for the xtables lock instead of failing at once with "Another app
     * is currently holding the xtables lock". The apply is triggered by
     * network changes, exactly when netd is rewriting its own chains, and an
     * immediate failure there dropped a rule silently. `-w` without a timeout
     * exists in every iptables Android has shipped since 5.0; a lock that is
     * never released is bounded by the root shell's own timeout.
     */

    /** Dedicated iptables chains. Owning named chains makes every rule we install
     *  idempotent, inspectable and removable in one shot — unlike appending to
     *  the shared OUTPUT/FORWARD chains, which accumulates duplicates. */
    internal const val CHAIN_MARK = "TAILSOCKS_MARK"
    internal const val CHAIN_DNS = "TAILSOCKS_DNS"

    /**
     * Legacy per-app exclusion chain, no longer installed — kept only so that
     * upgrades remove it.
     *
     * It gave every excluded uid [BYPASS_MARK] in mangle OUTPUT, hooked from
     * OUTPUT with no match of its own: the one rule we ever installed that
     * inspected every locally generated packet on the device. It was also
     * redundant — a mark set in mangle OUTPUT comes too late for TCP (the route
     * and the source address are chosen at connect()), which is why the pref-
     * [EXCLUDE_PRIO] uid rules were added in the first place, and those do the
     * whole job on their own.
     */
    internal const val CHAIN_BYPASS = "TAILSOCKS_BYPASS"

    /** Printed by [setDnsRedirectHooked] when asked to arm a redirect whose chain is not there. */
    /**
     * Printed by the apply script when device-wide DNS goes in on a kernel with
     * no IPv6 `nat` table — 4.19 on the Redmi is one. There is nowhere to write
     * the v6 rules, so the redirect covers IPv4 only and a query that leaves
     * over IPv6 passes MagicDNS by. A firmware limit rather than a gap of ours,
     * but it used to be silent, which is worse: the summary announced
     * device-wide DNS without saying half of it was missing. Only ever printed
     * when IPv6 is up, so a device with it off cannot raise a false alarm.
     */
    internal const val DNS_V6_NO_NAT = "verify: no IPv6 nat table, DNS redirect covers IPv4 only"

    internal const val DNS_CHAIN_ABSENT = "verify: $CHAIN_DNS does not exist, nothing to hook"

    /** Printed by [setDnsRedirectHooked] when the jump it installed is not on the device afterwards. */
    internal const val DNS_HOOK_FAILED = "verify: nat OUTPUT jump into $CHAIN_DNS not installed"

    internal const val CGNAT_V4 = "100.64.0.0/10"
    internal const val TAILNET_V6 = "fd7a:115c:a1e0::/48"
    internal const val MAGIC_DNS = "100.100.100.100"
}
