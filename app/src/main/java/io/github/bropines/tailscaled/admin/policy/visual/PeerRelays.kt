package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.PolicyLint
import io.github.bropines.tailscaled.admin.policy.TailnetView

/*
 * Peer relays in the policy, without Compose. A peer relay is a device of the tailnet that
 * relays traffic for others when they cannot connect directly; which devices may use which
 * relays is a grant carrying the `tailscale.com/cap/relay` capability: `src` the devices that
 * use them, `dst` the relays, the capability's value an empty list. Tailscale and Headscale
 * both take it. Whether a device actually relays is its own setting (`tailscale set
 * --relay-server-port`), which neither the policy nor the console's device list carries.
 */

/** Why a device named in a peer relay rule will not take part, as the console's device list tells it. */
enum class RelayDeviceProblem {
    /** Android, iOS and tvOS clients do not relay for others. */
    CANNOT_RELAY,
    /** Older than Tailscale 1.86, which neither relays nor uses relays. */
    TOO_OLD,
    /** Shared into this tailnet from another: relays serve only their own tailnet. */
    SHARED,
}

/** One device a rule names and why it will not take part. */
data class RelayDeviceNote(val device: ApiDevice, val problem: RelayDeviceProblem)

/**
 * What the phone can tell about one peer relay rule: the devices that relay and those that may
 * use them, the ones of each that will not take part, and the sources broad enough that every
 * device of a kind would try the relays.
 */
data class RelayCheck(
    val relays: Coverage,
    val clients: Coverage,
    val relayNotes: List<RelayDeviceNote>,
    val clientNotes: List<RelayDeviceNote>,
    val broad: List<String>,
)

object PeerRelays {

    const val CAP = "tailscale.com/cap/relay"

    /** The first Tailscale release that relays and uses relays. */
    private const val MIN_MINOR = 86

    /** As the console's device list writes them, lowercased: the systems a relay cannot run on. */
    private val CANNOT_RELAY_OS = setOf("android", "ios", "tvos")

    /** Whether [g] lets its sources use its destinations as peer relays. */
    fun isRelay(g: GrantRule): Boolean = g.app.any { it.name == CAP }

    /**
     * A grant that says nothing but "these may use those as relays": no ports, no other
     * capability, no via or posture. The Peer relays page edits it whole; any other grant
     * carrying the capability is an access rule first, and edited on the Access page.
     */
    fun relayOnly(g: GrantRule): Boolean = isRelay(g) && g.app.size == 1 && g.ip.isEmpty() && g.via.isEmpty() && g.srcPosture.isEmpty()

    /** The grants of [m] that carry the relay capability, in file order. */
    fun rules(m: PolicyModel): List<GrantRule> = m.grants.filter(::isRelay)

    /** A peer relay rule's fields: [clients] may use [relays]. */
    fun fields(clients: List<String>, relays: List<String>): Fields = listOf(
        "src" to clients.pv(),
        "dst" to relays.pv(),
        "app" to PolicyValue.Obj(listOf(CAP to PolicyValue.Arr(emptyList()))),
    )

    /** A new rule at the end of `grants`, the section written first when the file has none. */
    fun add(text: String, clients: List<String>, relays: List<String>): String =
        PolicyEdits.addRule(text, Section.GRANTS, fields(clients, relays))

    /** The devices that may use the relays of the rule at [path]. */
    fun setClients(text: String, path: PolicyPath, clients: List<String>): String =
        PolicyEdits.updateRule(text, path, listOf("src" to clients.pv()))

    /** The relays of the rule at [path]. */
    fun setRelays(text: String, path: PolicyPath, relays: List<String>): String =
        PolicyEdits.updateRule(text, path, listOf("dst" to relays.pv()))

    /**
     * The rule at [path] no longer grants relays. A rule that grants nothing else — no ports,
     * no other capability — goes whole, its note with it (a grant with neither `ip` nor `app`
     * is refused); otherwise only the capability goes, and `app` with it when it held nothing
     * else. The rest of the rule is not touched.
     */
    fun remove(text: String, path: PolicyPath): String {
        val rule = SourceEdits.tree(text).at(path) as? SrcObject ?: throw PolicyEditException("no rule at $path")
        val app = rule["app"] as? SrcObject ?: throw PolicyEditException("$path grants no capability")
        if (app.members.count { it.key == CAP } != 1) throw PolicyEditException("$path does not grant $CAP once")
        val othersInApp = app.members.size > 1
        val ip = rule["ip"]
        val opensPorts = ip != null && !(ip is SrcArray && ip.members.isEmpty())
        return when {
            othersInApp -> SourceEdits.remove(text, path + "app" + CAP)
            opensPorts -> SourceEdits.remove(text, path + "app")
            else -> PolicyEdits.removeRule(text, path)
        }
    }

    /**
     * Whether the rule's relay part can stand as a rule of its own: it has something besides —
     * ports or another capability — that stays when the capability leaves it.
     */
    fun splittable(g: GrantRule): Boolean = isRelay(g) && (g.ip.isNotEmpty() || g.app.size > 1)

    /**
     * The relay part of the rule at [path] as a rule of its own, right after it: the capability
     * leaves the rule ([remove]), and a rule of the same sides and nothing else follows it. The
     * relay page then edits that one whole, and the access rule keeps what it opened.
     */
    fun split(text: String, path: PolicyPath): String {
        val index = (path.last as? PathStep.Index)?.index ?: throw PolicyEditException("$path is not in a list")
        val rule = PolicyModel.read(text)?.grants?.firstOrNull { it.origin.path == path } ?: throw PolicyEditException("no grant at $path")
        if (!splittable(rule)) throw PolicyEditException("$path has nothing to keep without the relay capability")
        val out = remove(text, path)
        return PolicyEdits.addRule(out, Section.GRANTS, fields(rule.src, rule.dst), index = index + 1)
    }

    // ---- what the device list says ----

    /** Sources that make every device of a kind try the relays: what Tailscale advises against. */
    fun broad(clients: List<String>): List<String> = clients.filter { it in PolicyLint.BROAD }

    /** Why [d] cannot relay for others, or null when nothing the console knows stands in the way. */
    fun relayProblem(d: ApiDevice): RelayDeviceProblem? = when {
        d.isShared -> RelayDeviceProblem.SHARED
        d.os?.lowercase() in CANNOT_RELAY_OS -> RelayDeviceProblem.CANNOT_RELAY
        tooOld(d.clientVersion) -> RelayDeviceProblem.TOO_OLD
        else -> null
    }

    /** Why [d] cannot use a relay, or null. */
    fun clientProblem(d: ApiDevice): RelayDeviceProblem? = when {
        d.isShared -> RelayDeviceProblem.SHARED
        tooOld(d.clientVersion) -> RelayDeviceProblem.TOO_OLD
        else -> null
    }

    /** Whether a client version ("1.84.3-t…", "1.90.0") is older than 1.86; unknown is not judged. */
    fun tooOld(version: String?): Boolean {
        val parts = version?.substringBefore('-')?.split('.') ?: return false
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return major < 1 || (major == 1 && minor < MIN_MINOR)
    }

    /** What the phone can tell about [rule] from the device and user lists in [view]. */
    fun check(rule: GrantRule, policy: HuObject?, view: TailnetView): RelayCheck {
        val relays = RuleCoverage.of(rule.dst, policy, view)
        val clients = RuleCoverage.of(rule.src, policy, view)
        return RelayCheck(
            relays = relays,
            clients = clients,
            relayNotes = relays.devices.mapNotNull { d -> relayProblem(d)?.let { RelayDeviceNote(d, it) } },
            // A device that relays for itself is judged once, as a relay.
            clientNotes = clients.devices.filter { it !in relays.devices || relayProblem(it) == null }
                .mapNotNull { d -> clientProblem(d)?.let { RelayDeviceNote(d, it) } },
            broad = broad(rule.src),
        )
    }
}

/**
 * A peer relay rule not yet in the file: who may use the relays and which devices relay.
 * Nothing is written until both sides have a value ([complete]).
 */
data class RelayForm(val clients: List<String> = emptyList(), val relays: List<String> = emptyList()) {
    val complete: Boolean get() = clients.isNotEmpty() && relays.isNotEmpty()

    /** The rule's fields. */
    val src: List<String> get() = clients
    val dst: List<String> get() = relays
}
