package io.github.bropines.tailscaled.admin.policy.visual

/*
 * The policy file as the visual editor shows it: sections, and in them rules and definitions
 * with typed fields — read from the SourceTree, so every element knows its path (for edits), its
 * line (for "show in JSON" and server errors) and its comments (shown on its card).
 *
 * The model is read-only. Edits go through SourceEdits on the text and the model is read again:
 * nothing the model does not understand can be lost, because the model never writes the file.
 */

/** Top-level sections the editor knows. */
enum class Section(val key: String) {
    GROUPS("groups"),
    TAG_OWNERS("tagOwners"),
    HOSTS("hosts"),
    IPSETS("ipsets"),
    POSTURES("postures"),
    DEFAULT_SRC_POSTURE("defaultSrcPosture"),
    AUTO_APPROVERS("autoApprovers"),
    ACLS("acls"),
    GRANTS("grants"),
    SSH("ssh"),
    NODE_ATTRS("nodeAttrs"),
    TESTS("tests"),
    SSH_TESTS("sshTests"),
    DERP_MAP("derpMap"),
    RANDOMIZE_CLIENT_PORT("randomizeClientPort"),
    DISABLE_IPV4("disableIPv4"),
    ONE_CGNAT_ROUTE("OneCGNATRoute"),
    EXTERNAL_TAILNETS("externalTailnets"),
    ATTR_CONFIG("attrConfig"),
    ;

    companion object {
        /** Go's decoder matches keys case-insensitively; the documented spelling is what the editor writes. */
        fun of(key: String): Section? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** Why an element is shown read-only: the visual editor would have to guess what it means. */
enum class ShapeIssue {
    /** A field the editor shows as a list holds something else. */
    NOT_A_LIST,
    /** A list holds something other than strings. */
    NOT_STRINGS,
    /** A field that should be a single string is not one. */
    NOT_A_STRING,
    NOT_AN_OBJECT,
    /** A key written twice: only the last counts, and editing either would mislead. */
    DUPLICATE_KEY,
    /** A key in another spelling than documented (`Action`, `regions`): the server reads it, the editor leaves it alone. */
    ODD_CASE,
}

/**
 * Where an element is and what is written around it. [line] is 1-based; [note], [header] and
 * [trailing] are its comments ([MemberComments]). [extra] lists fields the editor does not
 * show; they stay in the file untouched and the card says "more in JSON".
 */
data class Origin(
    val path: PolicyPath,
    val line: Int,
    val note: String? = null,
    val header: String? = null,
    val trailing: String? = null,
    val extra: List<String> = emptyList(),
    val issues: Set<ShapeIssue> = emptySet(),
) {
    /** Whether the visual editor may change it; otherwise it is shown with "edit in JSON". */
    val editable: Boolean get() = issues.isEmpty()
}

/** A named list: a group's members, a tag's owners, an IP set's operations, a route's approvers. */
data class NamedList(val origin: Origin, val name: String, val values: List<String>)

data class HostAlias(val origin: Origin, val name: String, val address: String)

/** A device posture: its assertions (both the array form and `{assertions, onFailure}`). */
data class Posture(val origin: Origin, val name: String, val assertions: List<String>, val objectForm: Boolean)

data class AclRule(
    val origin: Origin,
    val action: String,
    val src: List<String>,
    val dst: List<String>,
    val proto: String?,
    val srcPosture: List<String>,
) {
    val destinations: List<HostPorts> get() = dst.map(Selectors::hostPorts)
}

/** One application capability of a grant or node attribute: its name and its parameters as written. */
data class AppCapability(val name: String, val source: String, val line: Int)

data class GrantRule(
    val origin: Origin,
    val src: List<String>,
    val dst: List<String>,
    val ip: List<String>,
    val app: List<AppCapability>,
    val via: List<String>,
    val srcPosture: List<String>,
) {
    val ipSpecs: List<IpSpec> get() = ip.map(Selectors::ipSpec)
}

data class SshRule(
    val origin: Origin,
    val action: String,
    val src: List<String>,
    val dst: List<String>,
    val users: List<String>,
    val checkPeriod: String?,
    val acceptEnv: List<String>,
    val srcPosture: List<String>,
)

data class NodeAttr(
    val origin: Origin,
    val target: List<String>,
    val attr: List<String>,
    val app: List<AppCapability>,
    val ipPool: List<String>,
)

data class AutoApprovers(
    val origin: Origin,
    val routes: List<NamedList>,
    val exitNode: List<String>,
    val services: List<NamedList>,
)

data class AclTest(
    val origin: Origin,
    val src: String,
    val proto: String?,
    val accept: List<String>,
    val deny: List<String>,
    /** `srcPostureAttrs` as written: shown, edited in JSON. */
    val srcPostureAttrs: String?,
)

data class SshTest(
    val origin: Origin,
    val src: String,
    val dst: List<String>,
    val accept: List<String>,
    val check: List<String>,
    val deny: List<String>,
)

data class DerpRegion(val id: String, val code: String?, val name: String?, val nodes: Int, val disabled: Boolean)

/** derpMap, summarised: custom regions are written in JSON, the visual editor only shows them. */
data class DerpSummary(val origin: Origin, val omitDefaultRegions: Boolean, val regions: List<DerpRegion>)

/** A top-level key and what it is; [section] null for one the editor does not know (kept, shown read-only). */
data class SectionEntry(val key: String, val section: Section?, val origin: Origin)

data class PolicyModel(
    val sections: List<SectionEntry>,
    val groups: List<NamedList>,
    val tagOwners: List<NamedList>,
    val hosts: List<HostAlias>,
    val ipsets: List<NamedList>,
    val postures: List<Posture>,
    val defaultSrcPosture: List<String>,
    val autoApprovers: AutoApprovers?,
    val acls: List<AclRule>,
    val grants: List<GrantRule>,
    val ssh: List<SshRule>,
    val nodeAttrs: List<NodeAttr>,
    val tests: List<AclTest>,
    val sshTests: List<SshTest>,
    val derpMap: DerpSummary?,
    val randomizeClientPort: Boolean?,
    val disableIPv4: Boolean?,
    val oneCGNATRoute: String?,
    /** Top-level keys written more than once: the visual editor refuses to edit the file until they are fixed. */
    val duplicateSections: Set<String>,
) {
    /** Every name defined in the file, by kind: what the pickers offer next to users and devices. */
    val definedNames: List<String>
        get() = groups.map { it.name } + tagOwners.map { it.name } + hosts.map { it.name } + ipsets.map { it.name } + postures.map { it.name }

    /** Top-level keys the editor does not know: kept as written, shown as "edit in JSON". */
    val unknownSections: List<SectionEntry> get() = sections.filter { it.section == null }

    companion object {
        fun read(text: String): PolicyModel? = SourceTree.parseOrNull(text)?.let(::read)

        fun read(t: SourceTree): PolicyModel = Reader(t).model()
    }
}

/** Builds a [PolicyModel] from a [SourceTree]. */
private class Reader(private val t: SourceTree) {
    private val lines = io.github.bropines.tailscaled.admin.policy.HuJson.Lines(t.text)
    private val root = t.root

    private fun line(v: SrcValue) = lines.lineOf(v.start)

    private fun origin(path: PolicyPath, c: SrcContainer, index: Int, known: Set<String>? = null, issues: Set<ShapeIssue> = emptySet()): Origin {
        val m = c.members[index]
        val cm = Trivia.comments(t, c, index)
        val obj = m.value as? SrcObject
        val extra = if (known != null && obj != null) obj.members.mapNotNull { it.key }.filter { it !in known }.distinct() else emptyList()
        val all = issues.toMutableSet()
        if (obj != null) {
            if (obj.duplicateKeys.isNotEmpty()) all += ShapeIssue.DUPLICATE_KEY
            if (known != null && extra.any { e -> known.any { it.equals(e, ignoreCase = true) } }) all += ShapeIssue.ODD_CASE
        }
        return Origin(path, lines.lineOf(m.start), cm.note, cm.header, cm.trailing, extra, all)
    }

    /** A list of strings, or a single string read as a list of one (both are accepted by the server). */
    private fun strings(v: SrcValue?, issues: MutableSet<ShapeIssue>): List<String> = when (v) {
        null -> emptyList()
        is SrcString -> listOf(v.value)
        is SrcArray -> v.members.mapNotNull { m -> (m.value as? SrcString)?.value ?: run { issues += ShapeIssue.NOT_STRINGS; null } }
        is SrcLiteral -> if (v.raw == "null") emptyList() else { issues += ShapeIssue.NOT_A_LIST; emptyList() }
        else -> { issues += ShapeIssue.NOT_A_LIST; emptyList() }
    }

    private fun string(v: SrcValue?, issues: MutableSet<ShapeIssue>): String? = when (v) {
        null -> null
        is SrcString -> v.value
        else -> { issues += ShapeIssue.NOT_A_STRING; null }
    }

    private fun bool(v: SrcValue?): Boolean? = (v as? SrcLiteral)?.raw?.toBooleanStrictOrNull()

    private fun section(key: String): SrcValue? = root.members.lastOrNull { it.key == key }?.value

    /** The rules of an array section, each object read by [read]; anything else in the array is skipped. */
    private fun <T> rules(key: String, known: Set<String>, read: (SrcObject, Origin, MutableSet<ShapeIssue>) -> T): List<T> {
        val a = section(key) as? SrcArray ?: return emptyList()
        val base = PolicyPath.of(key)
        return a.members.indices.mapNotNull { i ->
            val o = a.members[i].value as? SrcObject ?: return@mapNotNull null
            val issues = mutableSetOf<ShapeIssue>()
            val probe = origin(base + i, a, i, known)
            val value = read(o, probe, issues)
            if (issues.isEmpty()) value else read(o, probe.copy(issues = probe.issues + issues), mutableSetOf())
        }
    }

    /** The fields of an object section as named lists (groups, tagOwners, ipsets, routes). */
    private fun namedLists(o: SrcObject?, base: PolicyPath): List<NamedList> {
        o ?: return emptyList()
        return o.members.indices.map { i ->
            val m = o.members[i]
            val issues = mutableSetOf<ShapeIssue>()
            val values = strings(m.value, issues)
            if (m.value is SrcString) issues += ShapeIssue.NOT_A_LIST
            if (o.members.count { it.key == m.key } > 1) issues += ShapeIssue.DUPLICATE_KEY
            NamedList(origin(base + m.key!!, o, i, issues = issues), m.key, values)
        }
    }

    private fun apps(v: SrcValue?): List<AppCapability> = (v as? SrcObject)?.members?.map { m ->
        AppCapability(m.key!!, t.slice(m.value), line(m.value))
    }.orEmpty()

    fun model(): PolicyModel {
        val sections = root.members.indices.map { i ->
            val m = root.members[i]
            SectionEntry(m.key!!, Section.of(m.key).takeIf { it?.key == m.key }, origin(PolicyPath.of(m.key), root, i))
        }
        val hostsObj = section("hosts") as? SrcObject
        return PolicyModel(
            sections = sections,
            groups = namedLists(section("groups") as? SrcObject, PolicyPath.of("groups")),
            tagOwners = namedLists(section("tagOwners") as? SrcObject, PolicyPath.of("tagOwners")),
            hosts = hostsObj?.members?.indices?.map { i ->
                val m = hostsObj.members[i]
                val issues = mutableSetOf<ShapeIssue>()
                val addr = string(m.value, issues).orEmpty()
                HostAlias(origin(PolicyPath.of("hosts", m.key!!), hostsObj, i, issues = issues), m.key, addr)
            }.orEmpty(),
            ipsets = namedLists(section("ipsets") as? SrcObject, PolicyPath.of("ipsets")),
            postures = postures(),
            defaultSrcPosture = strings(section("defaultSrcPosture"), mutableSetOf()),
            autoApprovers = autoApprovers(),
            acls = rules("acls", ACL_KEYS) { o, origin, issues ->
                AclRule(origin, string(o["action"], issues) ?: "accept", strings(o["src"], issues), strings(o["dst"], issues),
                    string(o["proto"], issues), strings(o["srcPosture"], issues))
            },
            grants = rules("grants", GRANT_KEYS) { o, origin, issues ->
                if (o["app"] != null && o["app"] !is SrcObject) issues += ShapeIssue.NOT_AN_OBJECT
                GrantRule(origin, strings(o["src"], issues), strings(o["dst"], issues), strings(o["ip"], issues), apps(o["app"]),
                    strings(o["via"], issues), strings(o["srcPosture"], issues))
            },
            ssh = rules("ssh", SSH_KEYS) { o, origin, issues ->
                SshRule(origin, string(o["action"], issues) ?: "", strings(o["src"], issues), strings(o["dst"], issues),
                    strings(o["users"], issues), string(o["checkPeriod"], issues), strings(o["acceptEnv"], issues),
                    strings(o["srcPosture"], issues))
            },
            nodeAttrs = rules("nodeAttrs", NODE_ATTR_KEYS) { o, origin, issues ->
                NodeAttr(origin, strings(o["target"], issues), strings(o["attr"], issues), apps(o["app"]), strings(o["ipPool"], issues))
            },
            tests = rules("tests", TEST_KEYS) { o, origin, issues ->
                AclTest(origin, string(o["src"], issues).orEmpty(), string(o["proto"], issues), strings(o["accept"], issues),
                    strings(o["deny"], issues), o["srcPostureAttrs"]?.let { t.slice(it) })
            },
            sshTests = rules("sshTests", SSH_TEST_KEYS) { o, origin, issues ->
                SshTest(origin, string(o["src"], issues).orEmpty(), strings(o["dst"], issues), strings(o["accept"], issues),
                    strings(o["check"], issues), strings(o["deny"], issues))
            },
            derpMap = derp(),
            randomizeClientPort = bool(section("randomizeClientPort")),
            disableIPv4 = bool(section("disableIPv4")),
            oneCGNATRoute = (section("OneCGNATRoute") as? SrcString)?.value,
            duplicateSections = root.duplicateKeys,
        )
    }

    private fun postures(): List<Posture> {
        val o = section("postures") as? SrcObject ?: return emptyList()
        return o.members.indices.map { i ->
            val m = o.members[i]
            val issues = mutableSetOf<ShapeIssue>()
            val path = PolicyPath.of("postures", m.key!!)
            when (val v = m.value) {
                is SrcObject -> Posture(origin(path, o, i, setOf("assertions", "onFailure"), issues), m.key, strings(v["assertions"], issues), true)
                else -> Posture(origin(path, o, i, issues = issues), m.key, strings(v, issues), false)
            }.let { p -> if (issues.isEmpty()) p else p.copy(origin = p.origin.copy(issues = p.origin.issues + issues)) }
        }
    }

    private fun autoApprovers(): AutoApprovers? {
        val i = root.indexOf("autoApprovers")
        if (i < 0) return null
        val o = root.members[i].value as? SrcObject
        val base = PolicyPath.of("autoApprovers")
        val issues = mutableSetOf<ShapeIssue>()
        if (o == null) issues += ShapeIssue.NOT_AN_OBJECT
        val exit = strings(o?.get("exitNode"), issues)
        return AutoApprovers(
            origin(base, root, i, setOf("routes", "exitNode", "services"), issues),
            namedLists(o?.get("routes") as? SrcObject, base + "routes"),
            exit,
            namedLists(o?.get("services") as? SrcObject, base + "services"),
        )
    }

    private fun derp(): DerpSummary? {
        val i = root.indexOf("derpMap")
        if (i < 0) return null
        val o = root.members[i].value as? SrcObject
        val regions = (o?.members?.lastOrNull { it.key.equals("Regions", ignoreCase = true) }?.value as? SrcObject)?.members?.map { m ->
            val r = m.value as? SrcObject
            fun s(name: String) = (r?.members?.lastOrNull { it.key.equals(name, ignoreCase = true) }?.value as? SrcString)?.value
            val nodes = (r?.members?.lastOrNull { it.key.equals("Nodes", ignoreCase = true) }?.value as? SrcArray)?.members?.size ?: 0
            DerpRegion(m.key!!, s("RegionCode"), s("RegionName"), nodes, disabled = m.value is SrcLiteral)
        }.orEmpty()
        val omit = o?.members?.lastOrNull { it.key.equals("OmitDefaultRegions", ignoreCase = true) }?.value?.let { bool(it) } ?: false
        return DerpSummary(origin(PolicyPath.of("derpMap"), root, i), omit, regions)
    }

    companion object {
        val ACL_KEYS = setOf("action", "src", "dst", "proto", "srcPosture")
        val GRANT_KEYS = setOf("src", "dst", "ip", "app", "via", "srcPosture")
        val SSH_KEYS = setOf("action", "src", "dst", "users", "checkPeriod", "acceptEnv", "srcPosture")
        val NODE_ATTR_KEYS = setOf("target", "attr", "app", "ipPool")
        val TEST_KEYS = setOf("src", "proto", "accept", "deny", "srcPostureAttrs")
        val SSH_TEST_KEYS = setOf("src", "dst", "accept", "check", "deny")
    }
}
