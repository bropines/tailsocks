package io.github.bropines.tailscaled.admin.policy.visual

/** Fields of a rule to write, in order; a null value removes that field. */
typealias Fields = List<Pair<String, PolicyValue?>>

/**
 * The visual editor's operations in the policy's own terms — rules, groups, tag owners, hosts,
 * approvers, tests — built on [SourceEdits], so each is as small a text change as the file
 * allows. Every function takes the whole text and returns the whole new text.
 */
object PolicyEdits {

    private fun list(items: List<String>): PolicyValue? = items.takeIf { it.isNotEmpty() }?.pv()

    fun aclFields(src: List<String>, dst: List<String>, proto: String? = null, srcPosture: List<String> = emptyList()): Fields = listOf(
        "action" to "accept".pv(),
        "src" to src.pv(),
        "dst" to dst.pv(),
        "proto" to proto?.pv(),
        "srcPosture" to list(srcPosture),
    )

    fun grantFields(src: List<String>, dst: List<String>, ip: List<String>, via: List<String> = emptyList(), srcPosture: List<String> = emptyList()): Fields = listOf(
        "src" to src.pv(),
        "dst" to dst.pv(),
        "ip" to list(ip),
        "via" to list(via),
        "srcPosture" to list(srcPosture),
    )

    fun sshFields(action: String, src: List<String>, dst: List<String>, users: List<String>, checkPeriod: String? = null, acceptEnv: List<String> = emptyList()): Fields = listOf(
        "action" to action.pv(),
        "src" to src.pv(),
        "dst" to dst.pv(),
        "users" to users.pv(),
        // A check period means nothing on an accept rule; Headscale rejects it there.
        "checkPeriod" to checkPeriod?.takeIf { action == "check" }?.pv(),
        "acceptEnv" to list(acceptEnv),
    )

    fun nodeAttrFields(target: List<String>, attr: List<String>): Fields = listOf("target" to target.pv(), "attr" to attr.pv())

    fun testFields(src: String, accept: List<String>, deny: List<String>, proto: String? = null): Fields = listOf(
        "src" to src.pv(),
        "proto" to proto?.pv(),
        "accept" to list(accept),
        "deny" to list(deny),
    )

    fun sshTestFields(src: String, dst: List<String>, accept: List<String>, check: List<String>, deny: List<String>): Fields = listOf(
        "src" to src.pv(),
        "dst" to dst.pv(),
        "accept" to list(accept),
        "check" to list(check),
        "deny" to list(deny),
    )

    private fun obj(fields: Fields) = PolicyValue.Obj(fields.mapNotNull { (k, v) -> v?.let { k to it } })

    // ---- rules ----

    /**
     * Add a rule to [section] (an array section: acls, grants, ssh, nodeAttrs, tests, sshTests),
     * creating the section when the file has none. [index] null appends.
     */
    fun addRule(text: String, section: Section, fields: Fields, index: Int? = null, anchor: Anchor = Anchor.AFTER_PREVIOUS, note: String? = null): String {
        val withSection = SourceEdits.ensureSection(text, section.key, PolicyValue.Arr(emptyList()))
        val path = PolicyPath.of(section.key)
        val size = (SourceEdits.tree(withSection).at(path) as? SrcArray)?.members?.size
            ?: throw PolicyEditException("${section.key} is not a list")
        return SourceEdits.insert(withSection, path, index ?: size, obj(fields), anchor, note)
    }

    /**
     * Change the fields of the rule at [path]: lists are edited item by item (their comments
     * stay), single values replaced, null fields removed. Fields not named are not touched.
     */
    fun updateRule(text: String, path: PolicyPath, fields: Fields): String {
        var out = text
        for ((key, value) in fields) {
            val t = SourceEdits.tree(out)
            val o = t.at(path) as? SrcObject ?: throw PolicyEditException("no rule at $path")
            val existing = o[key]
            out = when {
                value == null -> if (existing != null) SourceEdits.remove(out, path + key) else out
                value is PolicyValue.Arr && value.items.all { it is PolicyValue.Str } && (existing == null || existing is SrcArray) ->
                    SourceEdits.setStrings(out, path + key, value.items.map { (it as PolicyValue.Str).value })
                else -> SourceEdits.put(out, path, key, value)
            }
        }
        return out
    }

    fun removeRule(text: String, path: PolicyPath): String = SourceEdits.remove(text, path, withNote = true)

    fun moveRule(text: String, section: Section, from: Int, to: Int, anchor: Anchor = Anchor.AFTER_PREVIOUS): String =
        SourceEdits.move(text, PolicyPath.of(section.key), from, to, anchor)

    // ---- named definitions: groups, tag owners, hosts, IP sets, postures ----

    /** Set the list [name] in an object section (groups, tagOwners, ipsets, postures), adding it last when new. */
    fun putNamed(text: String, section: Section, name: String, values: List<String>): String {
        val withSection = SourceEdits.ensureSection(text, section.key, PolicyValue.Obj(emptyList()))
        val path = PolicyPath.of(section.key)
        val t = SourceEdits.tree(withSection)
        val o = t.at(path) as? SrcObject ?: throw PolicyEditException("${section.key} is not an object")
        return if (o[name] is SrcArray) SourceEdits.setStrings(withSection, path + name, values, emptyList())
        else SourceEdits.put(withSection, path, name, values.pv(), emptyList())
    }

    fun putHost(text: String, name: String, address: String): String {
        val withSection = SourceEdits.ensureSection(text, Section.HOSTS.key, PolicyValue.Obj(emptyList()))
        return SourceEdits.put(withSection, PolicyPath.of(Section.HOSTS.key), name, address.pv(), emptyList())
    }

    fun removeNamed(text: String, section: Section, name: String): String = SourceEdits.remove(text, PolicyPath.of(section.key, name))

    /**
     * Rename a group, tag, host, IP set or posture: its definition's key and every reference to it
     * in a place that takes a selector (rule sources and destinations, owners, approvers, targets,
     * tests). SSH login names, attributes, ports and comments are not references and stay.
     */
    fun rename(text: String, old: String, new: String): String {
        if (old == new) return text
        val t = SourceEdits.tree(text)
        if (refs(t).any { it.isKey && it.value == new }) throw PolicyEditException("\"$new\" already exists")
        val r = Renderer(SourceStyle())
        val edits = refs(t).mapNotNull { ref ->
            val rewritten = rewrite(ref.value, old, new, ref.kind) ?: return@mapNotNull null
            if (ref.isKey) SourceEdits.renameEdits(t, ref.member!!, rewritten) else listOf(SourceEdits.Edit(ref.start, ref.end, r.quote(rewritten)))
        }.flatten()
        return if (edits.isEmpty()) text else SourceEdits.done(text, edits)
    }

    /** Where [name] is used, its own definition excluded: what deleting it would leave dangling. */
    fun uses(t: SourceTree, name: String): List<PolicyPath> =
        refs(t).filter { !it.isKey && rewrite(it.value, name, name, it.kind) != null }.map { it.path }

    private enum class RefKind { PLAIN, HOST_PORTS, IPSET_OP }

    private class Ref(val path: PolicyPath, val value: String, val start: Int, val end: Int, val kind: RefKind, val isKey: Boolean, val member: SrcMember? = null)

    private fun rewrite(value: String, old: String, new: String, kind: RefKind): String? = when (kind) {
        RefKind.PLAIN -> if (value == old) new else null
        RefKind.HOST_PORTS -> when {
            value == old -> new
            value.startsWith("$old:") && Selectors.isPortSpec(value.substring(old.length + 1)) -> new + value.substring(old.length)
            else -> null
        }
        RefKind.IPSET_OP -> {
            // "add ipset:x", "remove host:nas", or a bare target.
            val op = value.substringBefore(' ', "").takeIf { it == "add" || it == "remove" }
            val target = if (op != null) value.substringAfter(' ').trim() else value
            val renamed = when {
                target == old -> new
                target == "host:$old" -> "host:$new"
                else -> null
            }
            renamed?.let { if (op != null) "$op $it" else it }
        }
    }

    /** Every string in a selector position, and every key that defines a name. */
    private fun refs(t: SourceTree): List<Ref> {
        val out = mutableListOf<Ref>()
        val root = t.root
        fun strings(v: SrcValue?, path: PolicyPath, kind: RefKind = RefKind.PLAIN) {
            when (v) {
                is SrcString -> out += Ref(path, v.value, v.start, v.end, kind, false)
                is SrcArray -> v.members.forEachIndexed { i, m -> (m.value as? SrcString)?.let { out += Ref(path + i, it.value, it.start, it.end, kind, false) } }
                else -> Unit
            }
        }
        fun keys(o: SrcObject?, path: PolicyPath, values: RefKind?) {
            o?.members?.forEach { m ->
                out += Ref(path + m.key!!, m.key, m.keyStart, m.keyEnd, RefKind.PLAIN, true, m)
                if (values != null) strings(m.value, path + m.key, values)
            }
        }
        fun rules(key: String, fields: Map<String, RefKind>) {
            (root[key] as? SrcArray)?.members?.forEachIndexed { i, m ->
                val o = m.value as? SrcObject ?: return@forEachIndexed
                for ((f, kind) in fields) strings(o[f], PolicyPath.of(key, i, f), kind)
            }
        }
        keys(root["groups"] as? SrcObject, PolicyPath.of("groups"), RefKind.PLAIN)
        keys(root["tagOwners"] as? SrcObject, PolicyPath.of("tagOwners"), RefKind.PLAIN)
        keys(root["hosts"] as? SrcObject, PolicyPath.of("hosts"), null)
        keys(root["ipsets"] as? SrcObject, PolicyPath.of("ipsets"), RefKind.IPSET_OP)
        keys(root["postures"] as? SrcObject, PolicyPath.of("postures"), null)
        strings(root["defaultSrcPosture"], PolicyPath.of("defaultSrcPosture"))
        (root["autoApprovers"] as? SrcObject)?.let { a ->
            val base = PolicyPath.of("autoApprovers")
            (a["routes"] as? SrcObject)?.members?.forEach { m -> strings(m.value, base + "routes" + m.key!!) }
            strings(a["exitNode"], base + "exitNode")
            keys(a["services"] as? SrcObject, base + "services", RefKind.PLAIN)
        }
        val plain = RefKind.PLAIN
        rules("acls", mapOf("src" to plain, "dst" to RefKind.HOST_PORTS, "srcPosture" to plain))
        rules("grants", mapOf("src" to plain, "dst" to plain, "via" to plain, "srcPosture" to plain))
        rules("ssh", mapOf("src" to plain, "dst" to plain, "srcPosture" to plain))
        rules("nodeAttrs", mapOf("target" to plain))
        rules("tests", mapOf("src" to plain, "accept" to RefKind.HOST_PORTS, "deny" to RefKind.HOST_PORTS))
        rules("sshTests", mapOf("src" to plain, "dst" to plain))
        return out
    }

    // ---- auto approvers and options ----

    /** Who may advertise [cidr] without an admin's approval; an empty list removes the route. */
    fun setRouteApprovers(text: String, cidr: String, approvers: List<String>): String {
        var out = SourceEdits.ensureSection(text, Section.AUTO_APPROVERS.key, PolicyValue.Obj(emptyList()))
        val base = PolicyPath.of(Section.AUTO_APPROVERS.key)
        if (approvers.isEmpty()) {
            return if (SourceEdits.tree(out).at(base + "routes" + cidr) != null) SourceEdits.remove(out, base + "routes" + cidr) else out
        }
        if (SourceEdits.tree(out).at(base + "routes") == null) out = SourceEdits.put(out, base, "routes", PolicyValue.Obj(emptyList()), APPROVER_ORDER)
        val routes = base + "routes"
        return if (SourceEdits.tree(out).at(routes + cidr) is SrcArray) SourceEdits.setStrings(out, routes + cidr, approvers, emptyList())
        else SourceEdits.put(out, routes, cidr, approvers.pv(), emptyList())
    }

    /** Who may offer an exit node without an admin's approval; empty removes the field. */
    fun setExitNodeApprovers(text: String, approvers: List<String>): String {
        val base = PolicyPath.of(Section.AUTO_APPROVERS.key)
        if (approvers.isEmpty()) {
            return if (SourceEdits.tree(text).at(base + "exitNode") != null) SourceEdits.remove(text, base + "exitNode") else text
        }
        val out = SourceEdits.ensureSection(text, Section.AUTO_APPROVERS.key, PolicyValue.Obj(emptyList()))
        return if (SourceEdits.tree(out).at(base + "exitNode") is SrcArray) SourceEdits.setStrings(out, base + "exitNode", approvers, APPROVER_ORDER)
        else SourceEdits.put(out, base, "exitNode", approvers.pv(), APPROVER_ORDER)
    }

    private val APPROVER_ORDER = listOf("routes", "exitNode", "services")

    /** Set a top-level option (randomizeClientPort, disableIPv4, OneCGNATRoute); null removes it. */
    fun setOption(text: String, section: Section, value: PolicyValue?): String {
        val exists = SourceEdits.tree(text).root.field(section.key) != null
        return when {
            value == null -> if (exists) SourceEdits.remove(text, PolicyPath.of(section.key)) else text
            else -> SourceEdits.put(text, PolicyPath.ROOT, section.key, value, SourceEdits.SECTION_ORDER)
        }
    }

    // ---- derived rules ----

    /**
     * A test that pins what [rule] allows (or, with [accept] false, what it used to allow before
     * it was deleted): from its first source that a test accepts, to each destination with one
     * concrete port. Null when no source or destination can be written as a test.
     */
    fun testFrom(rule: AclRule, accept: Boolean = true): Fields? {
        val src = rule.src.firstOrNull { testableSource(it) } ?: return null
        val targets = rule.destinations.mapNotNull { hp ->
            val port = hp.ports?.let(::firstPort) ?: return@mapNotNull null
            if (!testableDestination(hp.host)) null else "${hp.host}:$port"
        }.distinct()
        if (targets.isEmpty()) return null
        return if (accept) testFields(src, targets, emptyList(), rule.proto) else testFields(src, emptyList(), targets, rule.proto)
    }

    /** The same for a grant's network part ([GrantRule.ip]); app-only grants give nothing to test. */
    fun testFrom(rule: GrantRule, accept: Boolean = true): Fields? {
        val src = rule.src.firstOrNull { testableSource(it) } ?: return null
        val spec = rule.ipSpecs.firstOrNull { it.proto == null || it.proto == "tcp" || it.proto == "udp" } ?: return null
        val port = firstPort(spec.ports) ?: return null
        val targets = rule.dst.filter(::testableDestination).map { "$it:$port" }
        if (targets.isEmpty()) return null
        return if (accept) testFields(src, targets, emptyList(), spec.proto) else testFields(src, emptyList(), targets, spec.proto)
    }

    /** Tests take one user, group, tag, host or address as their source — no wildcards, no autogroups. */
    private fun testableSource(s: String): Boolean = when (Selectors.parse(s).kind) {
        SelectorKind.USER, SelectorKind.GROUP, SelectorKind.TAG, SelectorKind.IP, SelectorKind.UNKNOWN, SelectorKind.HOST -> true
        else -> false
    }

    private fun testableDestination(host: String): Boolean = when (Selectors.parse(host).kind) {
        SelectorKind.ANY, SelectorKind.CIDR, SelectorKind.AUTOGROUP, SelectorKind.IPSET, SelectorKind.IP_RANGE -> false
        else -> true
    }

    /** One port from a spec: the first listed, `*` read as 22 — the port a lock-out would hurt first. */
    private fun firstPort(spec: String): Int? {
        if (spec == "*") return 22
        return spec.split(',').firstOrNull()?.trim()?.substringBefore('-')?.toIntOrNull()
    }

    /**
     * [rule] as grants, the way the Tailscale console converts them: one grant per distinct port
     * spec, its destinations without ports, each port (range) a separate `ip` entry, with the
     * protocol as their prefix.
     */
    fun grantsFrom(rule: AclRule): List<Fields> {
        val byPorts = LinkedHashMap<String, MutableList<String>>()
        for (hp in rule.destinations) byPorts.getOrPut(hp.ports ?: "*") { mutableListOf() } += hp.host
        return byPorts.map { (ports, hosts) ->
            val ip = ports.split(',').map { it.trim() }.map { p -> if (rule.proto != null) "${rule.proto}:$p" else p }
            grantFields(rule.src, hosts.distinct(), ip, srcPosture = rule.srcPosture)
        }
    }
}
