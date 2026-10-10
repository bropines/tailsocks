package io.github.bropines.tailscaled.admin.policy.visual

/** What a selector in `src`, `dst`, `target`, an owner list or a test names. */
enum class SelectorKind {
    /** `*`: every device and user of the tailnet. */
    ANY,
    AUTOGROUP,
    GROUP,
    TAG,
    /** A login: `alice@example.com`, `alice@github`, Headscale's `alice@`. */
    USER,
    /** `user:*@example.com`: every member whose login is in that domain. */
    USER_DOMAIN,
    /** An alias from `hosts`. */
    HOST,
    IP,
    CIDR,
    /** `10.0.0.1-10.0.0.9`, valid only inside `ipsets`. */
    IP_RANGE,
    IPSET,
    /** `svc:name`, a Tailscale Service. */
    SERVICE,
    POSTURE,
    /** `localpart:*@example.com` in SSH `users`. */
    LOCALPART,
    /** Group or tag of another tailnet (`group://alias/name`). */
    EXTERNAL,
    UNKNOWN,
}

data class Selector(val raw: String, val kind: SelectorKind) {
    /** The name without its prefix: `tag:web` → `web`, `autogroup:self` → `self`. */
    val name: String
        get() = when (kind) {
            SelectorKind.AUTOGROUP, SelectorKind.GROUP, SelectorKind.TAG, SelectorKind.IPSET,
            SelectorKind.SERVICE, SelectorKind.POSTURE -> raw.substringAfter(':')
            else -> raw
        }
}

/** An ACL destination `host:ports` or a test target `host:port`, split. [ports] is null when there is no port part. */
data class HostPorts(val host: String, val ports: String?) {
    val text: String get() = if (ports == null) host else "$host:$ports"
}

/** A grant's `ip` entry: `*`, `443`, `tcp:443`, `icmp:*`. [proto] null means TCP, UDP and ICMP. */
data class IpSpec(val proto: String?, val ports: String) {
    val text: String get() = if (proto == null) ports else "$proto:$ports"
}

/** Where an autogroup may be used; the console's pickers offer each only where the server accepts it. */
enum class AutogroupUse { SRC, DST, SSH_SRC, SSH_DST, SSH_USERS, TARGET, OWNER }

/** One autogroup the editor knows, and whether Headscale accepts it too. */
data class Autogroup(val raw: String, val uses: Set<AutogroupUse>, val headscale: Boolean)

/** Common port sets offered as chips; anything else is typed. */
enum class PortPreset(val spec: String) {
    ALL("*"), SSH("22"), HTTPS("443"), WEB("80,443"), HTTP("80"), DNS("53"), RDP("3389"),
}

object Selectors {

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val IPV6 = Regex("""[0-9a-fA-F:]*:[0-9a-fA-F:.]*""")
    private val PORT = Regex("""\d{1,5}(-\d{1,5})?""")

    fun parse(s: String, hosts: Set<String> = emptySet()): Selector = Selector(s, kind(s, hosts))

    private fun kind(s: String, hosts: Set<String>): SelectorKind = when {
        s == "*" -> SelectorKind.ANY
        s.startsWith("autogroup:") -> SelectorKind.AUTOGROUP
        s.startsWith("group://") || s.startsWith("tag://") -> SelectorKind.EXTERNAL
        s.startsWith("group:") -> SelectorKind.GROUP
        s.startsWith("tag:") -> SelectorKind.TAG
        s.startsWith("ipset:") -> SelectorKind.IPSET
        s.startsWith("svc:") -> SelectorKind.SERVICE
        s.startsWith("posture:") -> SelectorKind.POSTURE
        s.startsWith("user:") && s.contains("@") -> SelectorKind.USER_DOMAIN
        s.startsWith("localpart:") -> SelectorKind.LOCALPART
        s in hosts -> SelectorKind.HOST
        isAddress(s.substringBefore('/')) && s.contains('/') -> SelectorKind.CIDR
        isAddress(s) -> SelectorKind.IP
        s.contains('-') && s.split('-').let { p -> p.size == 2 && p.all { isAddress(it) } } -> SelectorKind.IP_RANGE
        s.contains('@') -> SelectorKind.USER
        else -> SelectorKind.UNKNOWN
    }

    private fun isAddress(s: String): Boolean = IPV4.matches(s) || (s.count { it == ':' } >= 2 && IPV6.matches(s))

    /** `*`, `22`, `80,443`, `1000-2000` and lists of those. */
    fun isPortSpec(s: String): Boolean = s == "*" || (s.isNotEmpty() && s.split(',').all { PORT.matches(it.trim()) })

    /**
     * An ACL destination split at its last colon when what follows is a port spec:
     * `tag:web:80,443` → (`tag:web`, `80,443`), `*:*` → (`*`, `*`), `[fd7a::1]:22` → (`[fd7a::1]`, `22`).
     */
    fun hostPorts(dst: String): HostPorts {
        val cut = dst.lastIndexOf(':')
        if (cut <= 0) return HostPorts(dst, null)
        val ports = dst.substring(cut + 1)
        val host = dst.substring(0, cut)
        // A bare IPv6 address ends in hex groups, not ports: only split it when bracketed.
        if (!isPortSpec(ports) || (host.contains(':') && isAddress(dst) && !host.startsWith("["))) return HostPorts(dst, null)
        return HostPorts(host, ports)
    }

    /** A grant `ip` entry: an optional protocol before the first colon, then ports. */
    fun ipSpec(s: String): IpSpec {
        val cut = s.indexOf(':')
        return if (cut > 0 && !isPortSpec(s)) IpSpec(s.substring(0, cut), s.substring(cut + 1)) else IpSpec(null, s)
    }

    /** The preset [ports] is exactly, if any. */
    fun preset(ports: String): PortPreset? = PortPreset.entries.firstOrNull { it.spec == ports.replace(" ", "") }

    private val ACCESS = setOf(AutogroupUse.SRC, AutogroupUse.DST, AutogroupUse.TARGET, AutogroupUse.OWNER)

    /** The autogroups, as Tailscale documents them (targets-and-selectors) and Headscale's policy v2 parses them. */
    val AUTOGROUPS: List<Autogroup> = listOf(
        Autogroup("autogroup:member", ACCESS + AutogroupUse.SSH_SRC + AutogroupUse.SSH_DST, headscale = true),
        Autogroup("autogroup:tagged", ACCESS + AutogroupUse.SSH_SRC + AutogroupUse.SSH_DST, headscale = true),
        Autogroup("autogroup:self", setOf(AutogroupUse.DST, AutogroupUse.SSH_DST), headscale = true),
        Autogroup("autogroup:internet", setOf(AutogroupUse.DST), headscale = true),
        Autogroup("autogroup:nonroot", setOf(AutogroupUse.SSH_USERS), headscale = true),
        Autogroup("autogroup:shared", setOf(AutogroupUse.SRC), headscale = false),
        Autogroup("autogroup:admin", ACCESS, headscale = false),
        Autogroup("autogroup:owner", ACCESS, headscale = false),
        Autogroup("autogroup:it-admin", ACCESS, headscale = false),
        Autogroup("autogroup:network-admin", ACCESS, headscale = false),
        Autogroup("autogroup:billing-admin", ACCESS, headscale = false),
        Autogroup("autogroup:auditor", ACCESS, headscale = false),
        Autogroup("autogroup:danger-all", setOf(AutogroupUse.SRC), headscale = true),
    )

    /** Protocols an ACL's `proto` or a grant's `ip` prefix names (IANA numbers 1–255 work too). */
    val PROTOCOLS = listOf("tcp", "udp", "icmp", "sctp", "gre", "esp", "ah", "igmp", "ipv4", "ip-in-ip", "egp", "igp")
}
