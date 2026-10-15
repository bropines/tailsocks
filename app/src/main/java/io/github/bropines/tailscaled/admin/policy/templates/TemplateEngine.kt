package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.policy.HuArray
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.PortSet
import io.github.bropines.tailscaled.admin.policy.TailnetView
import io.github.bropines.tailscaled.admin.policy.templates.TemplateRules.isSelector
import io.github.bropines.tailscaled.admin.policy.visual.Definitions
import io.github.bropines.tailscaled.admin.policy.visual.Fields
import io.github.bropines.tailscaled.admin.policy.visual.GrantRule
import io.github.bropines.tailscaled.admin.policy.visual.Origin
import io.github.bropines.tailscaled.admin.policy.visual.PeerRelays
import io.github.bropines.tailscaled.admin.policy.visual.PolicyEditException
import io.github.bropines.tailscaled.admin.policy.visual.PolicyEdits
import io.github.bropines.tailscaled.admin.policy.visual.PolicyModel
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.PolicyValue
import io.github.bropines.tailscaled.admin.policy.visual.PortWords
import io.github.bropines.tailscaled.admin.policy.visual.RelayDeviceNote
import io.github.bropines.tailscaled.admin.policy.visual.Renderer
import io.github.bropines.tailscaled.admin.policy.visual.RuleForms
import io.github.bropines.tailscaled.admin.policy.visual.Section
import io.github.bropines.tailscaled.admin.policy.visual.SelectorRules
import io.github.bropines.tailscaled.admin.policy.visual.SourceEdits
import io.github.bropines.tailscaled.admin.policy.visual.SourceStyle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * A template and the form's values turned into edits of one policy: the fragments expanded,
 * their conditions and the checks decided against the file as it is, access rules written in
 * the syntax the file already uses, items the file has already left out, named lists and
 * approvers merged rather than replaced. The edits are PolicyEdits' own, so what a template
 * adds is laid out like everything else the visual editor writes, and the server's check on
 * save stays the gate.
 */

/** The policy a template is planned against, and what the console knows besides. */
class TemplateContext(
    val text: String,
    val headscale: Boolean,
    val view: TailnetView? = null,
    val language: String = "en",
) {
    val model: PolicyModel? by lazy { PolicyModel.read(text) }
    val policy: HuObject? by lazy { HuJson.parseOrNull(text) }
}

/** Why Apply is not offered for these values. */
sealed class TemplateIssue {
    /** A required parameter has no value. */
    data class Missing(val param: TemplateParam) : TemplateIssue()
    /** A value the parameter's kind does not take: not a port, not an address. */
    data class BadValue(val param: TemplateParam, val value: String) : TemplateIssue()
    /** Tags among the sources of a rule to "their own devices": a tag owns none, and the server refuses it. */
    data class SelfWithoutOwner(val sources: List<String>) : TemplateIssue()
    /** The template says Headscale does not take what it writes. */
    data object NotOnHeadscale : TemplateIssue()
    /** A host alias the policy has already, pointing elsewhere. */
    data class Conflict(val key: String, val existing: String, val wanted: String) : TemplateIssue()
    /** The engine could not write it into this text. */
    data class Unwritable(val message: String) : TemplateIssue()
    /** The policy does not parse. */
    data object Unreadable : TemplateIssue()
    /** Everything the template would write is in the policy already. */
    data object NothingToAdd : TemplateIssue()
}

/** A check decided: whether it holds, the elements it found, and what the device list says (relays). */
data class CheckOutcome(
    val check: TemplateCheck,
    val holds: Boolean,
    val paths: List<PolicyPath> = emptyList(),
    val relayNotes: List<RelayDeviceNote> = emptyList(),
    val broad: List<String> = emptyList(),
)

/** One edit a plan makes, against whatever text it is given. */
sealed class TemplateOp {
    abstract fun apply(text: String): String

    /** An item at the end of an array section, the section written first when the file has none; [note] above it. */
    data class Add(val section: Section, val fields: Fields, val note: String? = null) : TemplateOp() {
        override fun apply(text: String): String = PolicyEdits.addRule(text, section, fields, note = note)
    }

    /** A named list (group, tag owners, IP set, posture) given [values] it lacks; created when the file has none of that name. */
    data class Extend(val section: Section, val name: String, val values: List<String>) : TemplateOp() {
        override fun apply(text: String): String {
            val m = PolicyModel.read(text) ?: throw PolicyEditException("the policy does not parse")
            val posture = if (section == Section.POSTURES) m.postures.firstOrNull { it.name == name } else null
            val current = posture?.assertions ?: TemplateEngine.named(m, section, name).orEmpty()
            val merged = current + values.filter { it !in current }
            if (merged == current && (posture != null || TemplateEngine.named(m, section, name) != null)) return text
            return if (posture != null) Definitions.setAssertions(text, posture, merged) else PolicyEdits.putNamed(text, section, name, merged)
        }
    }

    /** A host alias. */
    data class Host(val name: String, val address: String) : TemplateOp() {
        override fun apply(text: String): String = PolicyEdits.putHost(text, name, address)
    }

    /** Approvers added to a route ([map] `routes`, [key] the prefix), a service (`services`) or the exit nodes (`exitNode`). */
    data class Approve(val map: String, val key: String?, val values: List<String>) : TemplateOp() {
        override fun apply(text: String): String {
            val a = PolicyModel.read(text)?.autoApprovers
            val current = when (map) {
                "routes" -> a?.routes?.firstOrNull { it.name == key }?.values
                "services" -> a?.services?.firstOrNull { it.name == key }?.values
                else -> a?.exitNode
            }.orEmpty()
            val merged = current + values.filter { it !in current }
            if (merged == current) return text
            return when (map) {
                "routes" -> PolicyEdits.setRouteApprovers(text, key!!, merged)
                "services" -> PolicyEdits.setServiceApprovers(text, key!!, merged)
                else -> PolicyEdits.setExitNodeApprovers(text, merged)
            }
        }
    }
}

/**
 * What a template would do to one policy with these values: the edits, in order; why it cannot
 * yet ([issues], empty when Apply is offered); the checks as decided; how many items the policy
 * has already and were left out ([kept]).
 */
data class TemplatePlan(
    val template: PolicyTemplate,
    val accessSection: Section,
    val ops: List<TemplateOp>,
    val issues: List<TemplateIssue>,
    val checks: List<CheckOutcome>,
    val kept: Int = 0,
) {
    val ready: Boolean get() = issues.isEmpty()

    /** [text] with every edit made; [PolicyEditException] when one cannot be. */
    fun apply(text: String): String = ops.fold(text) { t, op -> op.apply(t) }

    /** The same, keeping a file's `\r\n` line endings: what a preview shows. */
    fun preview(text: String): String = SourceEdits.preservingLineEndings(text, ::apply)

    /**
     * Where the editor goes after [text] has had the plan applied: the first rule it adds —
     * a test only when it adds nothing else. Null when it only merges into definitions.
     */
    fun landing(text: String): PolicyPath? {
        val m = PolicyModel.read(text) ?: return null
        val adds = ops.filterIsInstance<TemplateOp.Add>()
        val first = adds.firstOrNull { it.section != Section.TESTS && it.section != Section.SSH_TESTS } ?: adds.firstOrNull() ?: return null
        val before = adds.takeWhile { it !== first }.count { it.section == first.section }
        return PolicyPath.of(first.section.key, TemplateEngine.sizeOf(m, first.section) + before)
    }
}

object TemplateEngine {

    /** The form's starting values: each parameter's default, nothing where it has none. */
    fun defaults(t: PolicyTemplate): Map<String, ParamValue> = t.params.associate { p ->
        val d = p.default
        p.id to when {
            p.kind == ParamKind.BOOL -> ParamValue.Flag((d as? JsonPrimitive)?.booleanOrNull ?: false)
            d is JsonArray -> ParamValue.Items(d.mapNotNull { (it as? JsonPrimitive)?.content })
            d is JsonPrimitive && d.isString -> ParamValue.Items(listOf(d.content))
            else -> ParamValue.Items(emptyList())
        }
    }

    fun plan(t: PolicyTemplate, values: Map<String, ParamValue>, ctx: TemplateContext): TemplatePlan {
        val m = ctx.model ?: return TemplatePlan(t, Section.GRANTS, emptyList(), listOf(TemplateIssue.Unreadable), emptyList())
        val b = Bindings(t.params.associateBy { it.id }, values)
        val issues = mutableListOf<TemplateIssue>()
        if (ctx.headscale && t.headscale.supported == HeadscaleLevel.NONE) issues += TemplateIssue.NotOnHeadscale
        t.params.forEach { p -> issues += valueIssues(p, b.value(p.id)) }
        val accessSection = RuleForms.newAccessSection(m, ctx.headscale)
        val checks = t.checks.map { check(it, b, m, ctx) }
        val fact: (String) -> Boolean = { name -> fact(name, b, checks, ctx.headscale, accessSection) }
        fun holds(condition: String?): Boolean = condition == null || Conditions.parse(condition).eval(fact)

        val ops = mutableListOf<TemplateOp>()
        var kept = 0
        val existing = HashMap<String, MutableSet<String>>()
        fun present(section: String): MutableSet<String> = existing.getOrPut(section) {
            ((ctx.policy?.get(section) as? HuArray)?.items?.map(HuJson::canonical) ?: emptyList()).toMutableSet()
        }
        fun add(section: Section, fields: Fields, note: String?) {
            // An item the file holds already, or that this plan writes twice, is written once.
            if (!present(section.key).add(canonical(fields))) kept++ else ops += TemplateOp.Add(section, fields, note)
        }

        try {
            for (block in blocks(t.fragments)) {
                if (!holds((block[TemplateRules.WHEN] as? JsonPrimitive)?.content)) continue
                for ((key, value) in block) {
                    when (key) {
                        TemplateRules.WHEN -> Unit
                        in TemplateRules.ARRAY_SECTIONS -> for (item in value as JsonArray) {
                            val o = item as JsonObject
                            if (!holds((o[TemplateRules.WHEN] as? JsonPrimitive)?.content)) continue
                            val note = (o[TemplateRules.COMMENT] as? JsonObject)?.let { comment(it, b, ctx.language) }
                            when {
                                key == "access" -> access(o, b, m, accessSection, issues)?.let { (s, f) -> add(s, f, note) }
                                o[TemplateRules.GENERATE] != null -> pairTest(o, b, m, ctx)?.let { add(Section.TESTS, it, note) }
                                else -> {
                                    val body = JsonObject(o.filterKeys { it != TemplateRules.WHEN && it != TemplateRules.COMMENT })
                                    val expanded = Placeholders.expand(body, b) as JsonObject
                                    add(Section.of(key)!!, expanded.map { (k, v) -> k to policyValue(v) }, note)
                                }
                            }
                        }
                        in TemplateRules.OBJECT_SECTIONS -> kept += merge(key, value as JsonObject, b, m, ops, issues)
                    }
                }
            }
        } catch (e: ExpandException) {
            issues += TemplateIssue.Unwritable(e.message.orEmpty())
        } catch (e: ConditionException) {
            issues += TemplateIssue.Unwritable(e.message.orEmpty())
        }
        if (ops.isEmpty() && issues.isEmpty()) issues += TemplateIssue.NothingToAdd
        return TemplatePlan(t, accessSection, ops, issues.distinct(), checks, kept)
    }

    /**
     * Whether [t] stands as a card on [page] in [slot] for this policy: it asks to, this backend
     * takes it, and the suggestion's condition holds with the form's defaults.
     */
    fun suggested(t: PolicyTemplate, page: String, slot: String, ctx: TemplateContext): Boolean {
        if (ctx.headscale && t.headscale.supported == HeadscaleLevel.NONE) return false
        val here = t.suggest.filter { it.page == page && it.slot == slot }
        if (here.isEmpty()) return false
        val m = ctx.model ?: return false
        val b = Bindings(t.params.associateBy { it.id }, defaults(t))
        val checks by lazy { t.checks.map { check(it, b, m, ctx) } }
        val access by lazy { RuleForms.newAccessSection(m, ctx.headscale) }
        return here.any { s ->
            val c = s.condition ?: return@any true
            try {
                Conditions.parse(c).eval { fact(it, b, checks, ctx.headscale, access) }
            } catch (e: ConditionException) {
                false
            }
        }
    }

    private fun blocks(fragments: JsonElement): List<JsonObject> = when (fragments) {
        is JsonObject -> listOf(fragments)
        is JsonArray -> fragments.filterIsInstance<JsonObject>()
        else -> emptyList()
    }

    private fun comment(c: JsonObject, b: Bindings, language: String): String? {
        val texts = c.mapValues { (_, v) -> (v as? JsonPrimitive)?.content?.let { Placeholders.expandKey(it, b) }.orEmpty() }
        return texts.pick(language).takeIf { it.isNotBlank() }
    }

    // ---- values ----

    private val NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")
    private val TAG_NAME = Regex("""[A-Za-z][A-Za-z0-9-]*""")
    private val LOGIN = Regex("""[^\s@]+@[^\s@]*""")
    private val DURATION = Regex("""(\d+h)?(\d+m)?(\d+s)?""")
    private const val MAX_VALUE = 200

    /** What is wrong with [v] for [p]: nothing where it is required, a value its kind does not take. */
    fun valueIssues(p: TemplateParam, v: ParamValue): List<TemplateIssue> {
        if (p.required && !v.isSet && p.kind != ParamKind.BOOL) return listOf(TemplateIssue.Missing(p))
        val items = (v as? ParamValue.Items)?.items.orEmpty()
        return items.filter { !valueOk(p, it) }.map { TemplateIssue.BadValue(p, it) }
    }

    /** Whether [s] is a value [p] takes. Selectors are judged by their field, as the editor's own pickers judge them. */
    fun valueOk(p: TemplateParam, s: String): Boolean {
        if (s.isBlank() || s.length > MAX_VALUE || s.any { it.isISOControl() }) return false
        if (p.kind.isSelector) return true
        return when (p.kind) {
            ParamKind.PORT -> SelectorRules.checkPorts(s) == null
            ParamKind.HOST -> '/' !in s && Definitions.checkAddress(s) == null
            ParamKind.CIDR -> Definitions.checkRoute(s) == null
            ParamKind.CHOICE -> p.options.any { it.value == s }
            ParamKind.TEXT -> when (p.format) {
                "name" -> NAME.matches(s)
                "tag-name" -> TAG_NAME.matches(s)
                "login" -> LOGIN.matches(s)
                "duration" -> DURATION.matches(s) && s.isNotEmpty()
                else -> true
            }
            else -> true
        }
    }

    // ---- facts and checks ----

    private fun fact(name: String, b: Bindings, checks: List<CheckOutcome>, headscale: Boolean, access: Section): Boolean = when {
        name == "headscale" -> headscale
        name == "tailscale" -> !headscale
        name == "style:acls" -> access == Section.ACLS
        name == "style:grants" -> access == Section.GRANTS
        name.startsWith("param:") -> {
            val rest = name.removePrefix("param:")
            val id = rest.substringBefore('=')
            if ('=' in rest) rest.substringAfter('=') in b.value(id).words else b.value(id).isSet
        }
        name.startsWith("check:") -> checks.firstOrNull { it.check.id == name.removePrefix("check:") }?.holds == true
        else -> throw ConditionException("unknown name \"$name\"")
    }

    private fun check(c: TemplateCheck, b: Bindings, m: PolicyModel, ctx: TemplateContext): CheckOutcome {
        fun list(l: List<String>) = try {
            Placeholders.strings(l, b)
        } catch (e: ExpandException) {
            emptyList()
        }
        return when (c.kind) {
            CheckKind.ACCESS_OPEN -> {
                val ports = list(c.ports).flatMap(PortWords::parts).map { PortSet.parse(it) ?: return CheckOutcome(c, false) }
                    .fold(PortSet.NONE) { acc, p -> acc + p }
                val proto = c.proto?.let { list(listOf(it)).firstOrNull() }
                val paths = AccessCoverage.openedBy(m, list(c.src), list(c.dst), ports, proto)
                CheckOutcome(c, paths != null, paths.orEmpty())
            }
            CheckKind.GRANT_APP -> {
                val cap = c.cap?.let { list(listOf(it)).firstOrNull() }
                val paths = m.grants.filter { g -> g.app.any { it.name == cap } }.map { it.origin.path }
                CheckOutcome(c, paths.isNotEmpty(), paths)
            }
            CheckKind.NODE_ATTR -> {
                val attrs = list(c.attrs)
                val rules = m.nodeAttrs.filter { r -> r.attr.any { it in attrs } }
                CheckOutcome(c, attrs.isNotEmpty() && attrs.all { a -> rules.any { a in it.attr } }, rules.map { it.origin.path })
            }
            CheckKind.RELAY_DEVICES -> {
                val clients = list(c.clients)
                val relays = list(c.relays)
                val view = ctx.view?.takeIf { it.devices.isNotEmpty() }
                val notes = if (view == null || clients.isEmpty() || relays.isEmpty()) emptyList() else {
                    val r = PeerRelays.check(GrantRule(Origin(PolicyPath.ROOT, 0), clients, relays, emptyList(), emptyList(), emptyList(), emptyList()), ctx.policy, view)
                    r.relayNotes + r.clientNotes
                }
                val broad = PeerRelays.broad(clients)
                CheckOutcome(c, notes.isNotEmpty() || broad.isNotEmpty(), relayNotes = notes, broad = broad)
            }
        }
    }

    // ---- items ----

    /**
     * An `access` item as the fields of an ACL or a grant: [section] (the file's syntax), or a
     * grant whatever the file uses when it goes `via` tags. Null when a side came out empty.
     */
    private fun access(o: JsonObject, b: Bindings, m: PolicyModel, section: Section, issues: MutableList<TemplateIssue>): Pair<Section, Fields>? {
        fun list(key: String) = (o[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.let { Placeholders.strings(it, b) }.orEmpty()
        val src = list("src")
        val dst = list("dst")
        if (src.isEmpty() || dst.isEmpty()) return null
        val parts = list("ports").flatMap(PortWords::parts).ifEmpty { listOf("*") }
        val ports = if ("*" in parts) listOf("*") else parts.distinct()
        val proto = (o["proto"] as? JsonPrimitive)?.content?.let { Placeholders.expandKey(it, b) }?.takeIf { it.isNotBlank() }
        val via = list("via")
        val posture = list("srcPosture")
        AccessCoverage.selfWithoutOwner(src, dst).takeIf { it.isNotEmpty() }?.let { issues += TemplateIssue.SelfWithoutOwner(it) }
        val target = if (via.isNotEmpty()) Section.GRANTS else section
        return target to if (target == Section.GRANTS) {
            PolicyEdits.grantFields(src, dst, ports.map { p -> proto?.let { "$it:$p" } ?: p }, via, posture)
        } else {
            PolicyEdits.aclFields(src, dst.map { "$it:${ports.joinToString(",")}" }, proto, posture)
        }
    }

    /** The `pair-test` generator: the test that pins the path, or none ([AccessCoverage.pairTest]). */
    private fun pairTest(o: JsonObject, b: Bindings, m: PolicyModel, ctx: TemplateContext): Fields? {
        fun list(key: String) = (o[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.let { Placeholders.strings(it, b) }.orEmpty()
        val port = (o["port"] as? JsonPrimitive)?.content?.let { Placeholders.expandKey(it, b) } ?: return null
        if (port.toIntOrNull()?.takeIf { it in 0..65535 } == null) return null
        val proto = (o["proto"] as? JsonPrimitive)?.content?.let { Placeholders.expandKey(it, b) }?.takeIf { it.isNotBlank() }
        return AccessCoverage.pairTest(m, list("src"), list("dst"), port, proto, ctx.view, ctx.policy)
    }

    /** An object section merged key by key; returns how many entries the policy had already. */
    private fun merge(section: String, o: JsonObject, b: Bindings, m: PolicyModel, ops: MutableList<TemplateOp>, issues: MutableList<TemplateIssue>): Int {
        var kept = 0
        fun key(k: String): String? = if (Placeholders.find(k).any { b.value(it.id).words.isEmpty() }) null else Placeholders.expandKey(k, b)
        fun strings(v: JsonElement) = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.let { Placeholders.strings(it, b) }.orEmpty()
        for ((rawKey, value) in o) {
            val name = key(rawKey) ?: continue
            when (section) {
                "hosts" -> {
                    val address = Placeholders.expandValue((value as JsonPrimitive).content, b)?.let { (it as? JsonPrimitive)?.content } ?: continue
                    val have = m.hosts.firstOrNull { it.name == name }
                    when {
                        have == null -> ops += TemplateOp.Host(name, address)
                        have.address == address -> kept++
                        else -> issues += TemplateIssue.Conflict("hosts.$name", have.address, address)
                    }
                }
                "autoApprovers" -> when (name) {
                    "exitNode" -> {
                        val want = strings(value)
                        if (want.isEmpty()) continue
                        if (want.all { it in m.autoApprovers?.exitNode.orEmpty() }) kept++ else ops += TemplateOp.Approve("exitNode", null, want)
                    }
                    else -> for ((rk, rv) in value as JsonObject) {
                        val k = key(rk) ?: continue
                        val want = strings(rv)
                        if (want.isEmpty()) continue
                        val have = (if (name == "routes") m.autoApprovers?.routes else m.autoApprovers?.services)?.firstOrNull { it.name == k }?.values.orEmpty()
                        if (want.all { it in have }) kept++ else ops += TemplateOp.Approve(name, k, want)
                    }
                }
                else -> {
                    val s = Section.of(section)!!
                    val want = strings(value)
                    val have = if (s == Section.POSTURES) m.postures.firstOrNull { it.name == name }?.assertions else named(m, s, name)
                    if (have != null && want.all { it in have }) kept++ else ops += TemplateOp.Extend(s, name, want)
                }
            }
        }
        return kept
    }

    // ---- helpers ----

    /** The values of the named list [name] in [section], or null when the file has none. */
    internal fun named(m: PolicyModel, section: Section, name: String): List<String>? = when (section) {
        Section.GROUPS -> m.groups
        Section.TAG_OWNERS -> m.tagOwners
        Section.IPSETS -> m.ipsets
        else -> emptyList()
    }.firstOrNull { it.name == name }?.values

    internal fun sizeOf(m: PolicyModel, s: Section): Int = when (s) {
        Section.ACLS -> m.acls.size
        Section.GRANTS -> m.grants.size
        Section.SSH -> m.ssh.size
        Section.NODE_ATTRS -> m.nodeAttrs.size
        Section.TESTS -> m.tests.size
        Section.SSH_TESTS -> m.sshTests.size
        else -> 0
    }

    /** A fragment's JSON as the editor writes values. */
    fun policyValue(e: JsonElement): PolicyValue = when (e) {
        is JsonObject -> PolicyValue.Obj(e.map { (k, v) -> k to policyValue(v) })
        is JsonArray -> PolicyValue.Arr(e.map(::policyValue))
        JsonNull -> PolicyValue.Null
        is JsonPrimitive -> when {
            e.isString -> PolicyValue.Str(e.content)
            e.booleanOrNull != null -> PolicyValue.Bool(e.booleanOrNull!!)
            else -> PolicyValue.Num(e.content)
        }
    }

    /** What [fields] say, as HuJson's canonical form: how an item is told from those the file has. */
    private fun canonical(fields: Fields): String {
        val v = PolicyValue.Obj(fields.mapNotNull { (k, value) -> value?.let { k to it } })
        val text = Renderer(SourceStyle()).render(v, "", 0)
        return HuJson.canonical(HuJson.parse(text))
    }
}
