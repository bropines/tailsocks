package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.PolicyText
import io.github.bropines.tailscaled.admin.policy.RiskFinding

/**
 * From a place in the text — a line a server message names, a lint finding, a selector the
 * server complains about — to the element the visual editor shows: a rule, a group, a section.
 * That is how a refusal from /acl/validate lands on the card that caused it.
 */
object PolicyLocator {

    /** The path of the deepest member whose source covers [offset]; the root when none does. */
    fun pathAt(t: SourceTree, offset: Int): PolicyPath {
        var path = PolicyPath.ROOT
        var node: SrcValue = t.root
        while (node is SrcContainer) {
            val i = node.members.indexOfFirst { offset >= it.start && offset < it.end }
            if (i < 0) break
            val m = node.members[i]
            path = if (node is SrcObject) path + m.key!! else path + i
            node = m.value
        }
        return path
    }

    /**
     * The visual element [path] belongs to: a rule (`acls[3]`), a definition (`groups.group:a`,
     * `autoApprovers.routes.10.0.0.0/8`), or the section itself for anything else.
     */
    fun element(path: PolicyPath): PolicyPath {
        val s = path.steps
        val section = (s.firstOrNull() as? PathStep.Key)?.name ?: return PolicyPath.ROOT
        val depth = when (Section.of(section)) {
            Section.ACLS, Section.GRANTS, Section.SSH, Section.NODE_ATTRS, Section.TESTS, Section.SSH_TESTS,
            Section.GROUPS, Section.TAG_OWNERS, Section.HOSTS, Section.IPSETS, Section.POSTURES -> 2
            Section.AUTO_APPROVERS -> if ((s.getOrNull(1) as? PathStep.Key)?.name == "exitNode") 2 else 3
            else -> 1
        }
        return PolicyPath(s.take(depth))
    }

    /** The element written on 1-based [line]: the first member that starts on it or spans it. */
    fun elementAtLine(t: SourceTree, line: Int): PolicyPath {
        val lines = HuJson.Lines(t.text)
        val start = lines.startOf(line)
        val end = if (line < lines.count) lines.startOf(line + 1) else t.text.length
        // The first significant character on the line, else the line's start.
        var at = start
        while (at < end && t.text[at].isWhitespace()) at++
        return element(pathAt(t, if (at < end) at else start))
    }

    /** Where a lint finding points, as an element. */
    fun element(t: SourceTree, finding: RiskFinding): PolicyPath? = finding.line?.let { elementAtLine(t, it) }

    private val NAMED = Regex("""(?:autogroup|group|tag|ipset|posture|svc):[A-Za-z0-9._@*-]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]*""")

    /**
     * The elements a server message is about, most likely first: those on the lines it names
     * ("line 12, column 3"), then those that use a group, tag, user or other name it mentions.
     * Empty when the message gives nothing to go on.
     */
    fun locate(t: SourceTree, message: String?): List<PolicyPath> {
        if (message.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<PolicyPath>()
        PolicyText.lineHints(message).forEach { out += elementAtLine(t, it) }
        val words = NAMED.findAll(message).map { it.value.trimEnd('.', ',', ':') }.toSet()
        for (w in words) {
            PolicyEdits.uses(t, w).forEach { out += element(it) }
            // The definition itself (a tag owner that names a missing group, say).
            for (section in listOf("groups", "tagOwners", "hosts", "ipsets", "postures")) {
                if ((t.root[section] as? SrcObject)?.field(w) != null) out += PolicyPath.of(section, w)
            }
        }
        out.remove(PolicyPath.ROOT)
        return out.toList()
    }
}
