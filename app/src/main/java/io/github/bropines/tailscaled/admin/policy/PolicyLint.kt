package io.github.bropines.tailscaled.admin.policy

/** What a rule can open up; each has its own words on screen. */
enum class RiskKind {
    /** `*` as a source: every device and user of the tailnet. */
    WILDCARD_SOURCE,
    /** `*` as a destination: every device. */
    WILDCARD_DESTINATION,
    /** autogroup:danger-all: everyone, shared-in users and devices included. */
    DANGER_ALL,
    /** An SSH rule with `*` in its sources, destinations or users. */
    SSH_WILDCARD,
    /** SSH as root without a re-check (`accept`, not `check`). */
    SSH_ROOT,
    /** Routes or exit nodes approved automatically for anyone, or for every tagged device. */
    AUTOAPPROVER_BROAD,
    /** A route of /8 or wider (IPv6 /32), including the whole internet, approved automatically. */
    AUTOAPPROVER_WIDE_ROUTE,
    /** Funnel — the public internet — allowed for everyone. */
    FUNNEL_BROAD,
    /** Fewer tests than before: what used to guard the policy no longer does. */
    TESTS_REMOVED,
}

/**
 * One risky thing in a policy. [detail] says which rule, in the policy's own words (machine
 * text: identities, ports, CIDRs); [line] is where it starts in the file that has it.
 */
data class RiskFinding(val kind: RiskKind, val section: String, val detail: String, val line: Int?) {
    /** What identifies the finding across two versions of the file: a rule widened is a new one. */
    val key: String get() = "$kind|$section|$detail"
}

/**
 * The risk lint. Reports what a candidate policy opens that the current one did not: a
 * finding that was already there is not news (the default policy itself allows `*` to `*`),
 * a new one or a widened one — the same rule reaching further — is.
 */
object PolicyLint {

    private val BROAD = setOf("*", "autogroup:member", "autogroup:danger-all", "autogroup:tagged")

    /** Every finding in [root], in file order. */
    fun findings(root: HuObject): List<RiskFinding> = buildList {
        acls(root, this)
        grants(root, this)
        ssh(root, this)
        autoApprovers(root, this)
        nodeAttrs(root, this)
    }.sortedBy { it.line ?: Int.MAX_VALUE }

    /** What [after] has that [before] did not, plus removed tests. [before] null: the current file did not parse. */
    fun introduced(before: HuObject?, after: HuObject): List<RiskFinding> {
        val old = before?.let { b -> findings(b).map { it.key }.toSet() }.orEmpty()
        val out = findings(after).filter { it.key !in old }.toMutableList()
        if (before != null) {
            for (section in listOf("tests", "sshTests")) {
                val was = (before[section] as? HuArray)?.items?.size ?: 0
                val now = (after[section] as? HuArray)?.items?.size ?: 0
                if (now < was) out += RiskFinding(RiskKind.TESTS_REMOVED, section, "$was → $now", after[section]?.line)
            }
        }
        return out
    }

    private fun rules(root: HuObject, section: String): List<HuObject> =
        (root[section] as? HuArray)?.items?.filterIsInstance<HuObject>().orEmpty()

    private fun list(items: List<String>) = items.sorted().joinToString(", ")

    /** The host part of an ACL destination, "tag:web:443" → "tag:web", "*:*" → "*". */
    private fun aclHost(dst: String): String {
        val cut = dst.lastIndexOf(':')
        if (cut <= 0) return dst
        // IPv6 destinations are bracketed or plain CIDRs; only a trailing port list is cut.
        val ports = dst.substring(cut + 1)
        return if (ports == "*" || ports.all { it.isDigit() || it == ',' || it == '-' }) dst.substring(0, cut) else dst
    }

    private fun acls(root: HuObject, out: MutableList<RiskFinding>) {
        for (rule in rules(root, "acls")) {
            if ((rule["action"] as? HuString)?.value?.lowercase() == "deny") continue
            val src = rule["src"].strings() + rule["users"].strings()
            val dst = rule["dst"].strings() + rule["ports"].strings()
            val flow = "${list(src)} → ${list(dst)}"
            if ("*" in src) out += RiskFinding(RiskKind.WILDCARD_SOURCE, "acls", flow, rule.line)
            if (dst.any { aclHost(it) == "*" }) out += RiskFinding(RiskKind.WILDCARD_DESTINATION, "acls", flow, rule.line)
            if ("autogroup:danger-all" in src || dst.any { aclHost(it) == "autogroup:danger-all" }) {
                out += RiskFinding(RiskKind.DANGER_ALL, "acls", flow, rule.line)
            }
        }
    }

    private fun grants(root: HuObject, out: MutableList<RiskFinding>) {
        for (rule in rules(root, "grants")) {
            val src = rule["src"].strings()
            val dst = rule["dst"].strings()
            val ip = rule["ip"].strings()
            val flow = "${list(src)} → ${list(dst)}" + if (ip.isNotEmpty()) " (${list(ip)})" else ""
            if ("*" in src) out += RiskFinding(RiskKind.WILDCARD_SOURCE, "grants", flow, rule.line)
            if ("*" in dst) out += RiskFinding(RiskKind.WILDCARD_DESTINATION, "grants", flow, rule.line)
            if ("autogroup:danger-all" in src || "autogroup:danger-all" in dst) out += RiskFinding(RiskKind.DANGER_ALL, "grants", flow, rule.line)
        }
    }

    private fun ssh(root: HuObject, out: MutableList<RiskFinding>) {
        for (rule in rules(root, "ssh")) {
            val src = rule["src"].strings()
            val dst = rule["dst"].strings()
            val users = rule["users"].strings()
            val action = (rule["action"] as? HuString)?.value?.lowercase()
            val flow = "${list(src)} → ${list(dst)} (${list(users)})"
            if ("*" in src || "*" in dst || "*" in users) out += RiskFinding(RiskKind.SSH_WILDCARD, "ssh", flow, rule.line)
            if ("autogroup:danger-all" in src) out += RiskFinding(RiskKind.DANGER_ALL, "ssh", flow, rule.line)
            if ("root" in users && action == "accept") out += RiskFinding(RiskKind.SSH_ROOT, "ssh", flow, rule.line)
        }
    }

    private fun autoApprovers(root: HuObject, out: MutableList<RiskFinding>) {
        val aa = root["autoApprovers"] as? HuObject ?: return
        (aa["routes"] as? HuObject)?.fields?.forEach { f ->
            val approvers = f.value.strings()
            val detail = "${f.name}: ${list(approvers)}"
            if (approvers.any { it in BROAD }) out += RiskFinding(RiskKind.AUTOAPPROVER_BROAD, "autoApprovers", detail, f.line)
            if (isWideRoute(f.name)) out += RiskFinding(RiskKind.AUTOAPPROVER_WIDE_ROUTE, "autoApprovers", detail, f.line)
        }
        aa["exitNode"]?.let { node ->
            val approvers = node.strings()
            if (approvers.any { it in BROAD }) out += RiskFinding(RiskKind.AUTOAPPROVER_BROAD, "autoApprovers", "exitNode: ${list(approvers)}", node.line)
        }
    }

    private fun nodeAttrs(root: HuObject, out: MutableList<RiskFinding>) {
        for (rule in rules(root, "nodeAttrs")) {
            val target = rule["target"].strings()
            val attrs = rule["attr"].strings()
            if (attrs.any { it == "funnel" } && target.any { it in BROAD }) {
                out += RiskFinding(RiskKind.FUNNEL_BROAD, "nodeAttrs", "${list(target)}: funnel", rule.line)
            }
        }
    }

    /** IPv4 /8 or wider, IPv6 /32 or wider; a route that is not a CIDR is not judged. */
    fun isWideRoute(cidr: String): Boolean {
        val slash = cidr.indexOf('/')
        if (slash < 0) return false
        val bits = cidr.substring(slash + 1).toIntOrNull() ?: return false
        return if (':' in cidr.substring(0, slash)) bits <= 32 else bits <= 8
    }
}
