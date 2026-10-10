package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.ReachResolver
import io.github.bropines.tailscaled.admin.policy.TailnetView

/**
 * Which devices a list of selectors covers, worked out on the phone from the device and user
 * lists the console already holds — instant, for the "3 devices" under each side of a rule.
 * [unresolved] keeps what cannot be told here (the internet, autogroup:self without a subject,
 * a CIDR outside the tailnet): the card says "and more", and /acl/preview stays the authority.
 */
data class Coverage(val devices: Set<ApiDevice>, val unresolved: List<String>) {
    val complete: Boolean get() = unresolved.isEmpty()
}

object RuleCoverage {

    fun of(selectors: List<String>, policy: HuObject?, view: TailnetView, subject: String? = null): Coverage {
        val devices = LinkedHashSet<ApiDevice>()
        val unresolved = mutableListOf<String>()
        for (s in selectors) {
            val found = ReachResolver.devicesFor(s, policy, view, subject)
            if (found == null) unresolved += s else devices += found
        }
        return Coverage(devices, unresolved)
    }

    /** Destinations of an ACL rule, ports dropped: the devices it opens something on. */
    fun aclDestinations(rule: AclRule, policy: HuObject?, view: TailnetView): Coverage =
        of(rule.destinations.map { it.host }, policy, view)

    /** [of] with the policy read from [text], for callers that hold only the draft. */
    fun of(selectors: List<String>, text: String, view: TailnetView): Coverage = of(selectors, HuJson.parseOrNull(text), view)
}
