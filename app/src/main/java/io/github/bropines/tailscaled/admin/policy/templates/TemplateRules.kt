package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.policy.visual.SelectorKind
import io.github.bropines.tailscaled.admin.policy.visual.SelectorSlot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI

/*
 * What a template file must be before the app takes it: the names it may use, the shapes of
 * its sections, every placeholder and condition resolvable, the limits that keep a file from
 * outgrowing a form. A file that breaks one is skipped whole, with the reason in the log;
 * nothing of it reaches a screen.
 */
object TemplateRules {

    /** The newest file format this app reads. */
    const val SCHEMA = 1

    private val ID = Regex("""[a-z0-9]+(-[a-z0-9]+)*""")
    private val PARAM_ID = Regex("""[a-z][a-z0-9_-]*""")
    const val MAX_ID = 64
    const val MAX_PARAMS = 16
    const val MAX_CHECKS = 16
    const val MAX_ITEMS = 50
    const val MAX_TEXT = 4000

    /** A template's id, a category: kebab-case, at most [MAX_ID] characters. */
    fun isId(s: String): Boolean = ID.matches(s) && s.length <= MAX_ID

    /** The sections whose fragments are lists of items: appended. */
    val ARRAY_SECTIONS = listOf("access", "acls", "grants", "ssh", "nodeAttrs", "tests", "sshTests")

    /** The sections whose fragments are objects: merged key by key. */
    val OBJECT_SECTIONS = listOf("groups", "tagOwners", "hosts", "ipsets", "postures", "autoApprovers")

    /** Keys of an item the engine reads and never writes into the policy. */
    const val WHEN = "when"
    const val COMMENT = "comment"
    const val GENERATE = "generate"

    /** What a `generate` item of `tests` may ask for. */
    const val PAIR_TEST = "pair-test"

    /** The keys an `access` item may have. */
    val ACCESS_KEYS = setOf("src", "dst", "ports", "proto", "via", "srcPosture", WHEN, COMMENT)

    /** The keys of autoApprovers a fragment may merge into. */
    val APPROVER_KEYS = setOf("routes", "exitNode", "services")

    /** The editor's pages a template may be suggested on, and where on them. */
    val PAGES = setOf("access", "attributes", "peer-relays")
    val SLOTS = setOf("empty", "before", "after")

    /** Formats a text parameter may be checked against. */
    val FORMATS = setOf("any", "name", "tag-name", "login", "duration")

    /** Hosts a template's documentation links may open. */
    val DOC_HOSTS = setOf("tailscale.com", "headscale.net", "github.com", "bropines.github.io")

    /** A selector slot by its file name (`acl-src`, `node-target`). */
    fun slot(name: String): SelectorSlot? = SelectorSlot.entries.firstOrNull { it.fileName == name }

    val SelectorSlot.fileName: String get() = name.lowercase().replace('_', '-')

    /** A selector kind by its file name (`group`, `user-domain`). */
    fun kind(name: String): SelectorKind? = SelectorKind.entries.firstOrNull { it != SelectorKind.UNKNOWN && it.name.lowercase().replace('_', '-') == name }

    /** The slot a parameter's picker follows: its own, or for user/group/tag an access source's. */
    fun slotOf(p: TemplateParam): SelectorSlot? = p.slot?.let(::slot) ?: when (p.kind) {
        ParamKind.USER, ParamKind.GROUP, ParamKind.TAG -> SelectorSlot.ACL_SRC
        else -> null
    }

    /** The kinds a parameter's picker offers; empty: whatever its slot takes. */
    fun allowedKinds(p: TemplateParam): Set<SelectorKind> = when (p.kind) {
        ParamKind.USER -> setOf(SelectorKind.USER)
        ParamKind.GROUP -> setOf(SelectorKind.GROUP)
        ParamKind.TAG -> setOf(SelectorKind.TAG)
        else -> p.allow.mapNotNull(::kind).toSet()
    }

    val ParamKind.isSelector: Boolean get() = this == ParamKind.SELECTOR || this == ParamKind.USER || this == ParamKind.GROUP || this == ParamKind.TAG

    /** Why [t] cannot be used; empty when it can. */
    fun problems(t: PolicyTemplate): List<String> {
        val out = mutableListOf<String>()
        fun bad(s: String) { out += s }
        if (t.schema !in 1..SCHEMA) bad("schema ${t.schema} is newer than $SCHEMA")
        if (!isId(t.id)) bad("id \"${t.id}\" is not kebab-case")
        if (t.version < 1) bad("version must be 1 or more")
        if (!isId(t.category)) bad("category \"${t.category}\" is not kebab-case")
        text(t.title, "title", ::bad)
        text(t.description, "description", ::bad)
        t.headscale.note?.let { text(it, "headscale.note", ::bad) }
        t.docs.forEach { d ->
            text(d.title, "docs.title", ::bad)
            if (!docUrl(d.url)) bad("docs url ${d.url} is not https on ${DOC_HOSTS.joinToString()}")
        }

        if (t.params.size > MAX_PARAMS) bad("more than $MAX_PARAMS parameters")
        val ids = mutableSetOf<String>()
        t.params.forEach { p ->
            if (!PARAM_ID.matches(p.id)) bad("parameter id \"${p.id}\"")
            if (!ids.add(p.id)) bad("parameter \"${p.id}\" twice")
            param(p, ::bad)
        }

        if (t.checks.size > MAX_CHECKS) bad("more than $MAX_CHECKS checks")
        val checkIds = mutableSetOf<String>()
        t.checks.forEach { c ->
            if (!PARAM_ID.matches(c.id)) bad("check id \"${c.id}\"")
            if (!checkIds.add(c.id)) bad("check \"${c.id}\" twice")
            c.note?.let { text(it, "check note", ::bad) }
            val lists = c.src + c.dst + c.ports + c.attrs + c.relays + c.clients + listOfNotNull(c.cap, c.proto)
            lists.forEach { placeholders(it, t, inArray = true, ::bad) }
            when (c.kind) {
                CheckKind.ACCESS_OPEN -> if (c.src.isEmpty() || c.dst.isEmpty() || c.ports.isEmpty()) bad("check ${c.id}: src, dst and ports are needed")
                CheckKind.GRANT_APP -> if (c.cap.isNullOrBlank()) bad("check ${c.id}: cap is needed")
                CheckKind.NODE_ATTR -> if (c.attrs.isEmpty()) bad("check ${c.id}: attrs are needed")
                CheckKind.RELAY_DEVICES -> if (c.relays.isEmpty() || c.clients.isEmpty()) bad("check ${c.id}: relays and clients are needed")
            }
        }

        val names = { c: String -> condition(c, t, ::bad) }
        t.suggest.forEach { s ->
            if (s.page !in PAGES) bad("suggest page \"${s.page}\"")
            if (s.slot !in SLOTS) bad("suggest slot \"${s.slot}\"")
            s.condition?.let(names)
        }

        val blocks = when (val f = t.fragments) {
            is JsonObject -> listOf(f)
            is JsonArray -> f.map { it as? JsonObject ?: run { bad("a fragments block is not an object"); JsonObject(emptyMap()) } }
            else -> { bad("fragments is neither an object nor a list"); emptyList() }
        }
        var items = 0
        blocks.forEach { block ->
            block.forEach { (key, value) ->
                when (key) {
                    WHEN -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let { names(it.content) } ?: bad("a block's when is not a string")
                    in ARRAY_SECTIONS -> {
                        val a = value as? JsonArray ?: return@forEach bad("$key is not a list")
                        items += a.size
                        a.forEach { item -> item(key, item, t, ::bad, names) }
                    }
                    in OBJECT_SECTIONS -> {
                        val o = value as? JsonObject ?: return@forEach bad("$key is not an object")
                        items += o.size
                        objectSection(key, o, t, ::bad)
                    }
                    else -> bad("unknown section \"$key\"")
                }
            }
        }
        if (items == 0) bad("fragments write nothing")
        if (items > MAX_ITEMS) bad("more than $MAX_ITEMS items")
        return out
    }

    private fun text(l: LocalText, what: String, bad: (String) -> Unit) {
        if (l["en"].isNullOrBlank()) bad("$what has no English text")
        if (l.values.any { it.length > MAX_TEXT }) bad("$what is longer than $MAX_TEXT characters")
    }

    /** An https link to a host [DOC_HOSTS] names (or one of its subdomains). */
    fun docUrl(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        return u.scheme == "https" && u.userInfo == null && u.port == -1 && DOC_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun param(p: TemplateParam, bad: (String) -> Unit) {
        text(p.label, "label of ${p.id}", bad)
        p.help?.let { text(it, "help of ${p.id}", bad) }
        if (p.kind == ParamKind.SELECTOR && p.slot == null) bad("${p.id}: a selector needs a slot")
        if (p.slot != null && (!p.kind.isSelector || slot(p.slot) == null)) bad("${p.id}: slot \"${p.slot}\"")
        if (p.allow.isNotEmpty() && (p.kind != ParamKind.SELECTOR || p.allow.any { kind(it) == null })) bad("${p.id}: allow ${p.allow}")
        if (p.kind == ParamKind.CHOICE) {
            if (p.options.isEmpty()) bad("${p.id}: a choice needs options")
            if (p.options.map { it.value }.distinct().size != p.options.size) bad("${p.id}: an option twice")
            p.options.forEach { text(it.label, "option of ${p.id}", bad) }
        } else if (p.options.isNotEmpty()) bad("${p.id}: options belong to a choice")
        if (p.kind == ParamKind.BOOL && p.multi) bad("${p.id}: a switch is not a list")
        if (p.format != null && (p.kind != ParamKind.TEXT || p.format !in FORMATS)) bad("${p.id}: format \"${p.format}\"")
        p.default?.let { d ->
            val ok = when {
                p.kind == ParamKind.BOOL -> (d as? JsonPrimitive)?.booleanOrNull != null
                d is JsonPrimitive -> d.isString
                d is JsonArray -> p.multi && d.all { it is JsonPrimitive && it.isString }
                else -> false
            }
            if (!ok) bad("${p.id}: default does not fit a ${p.kind}")
            if (ok && p.kind == ParamKind.CHOICE) {
                val values = p.options.map { it.value }
                val given = if (d is JsonArray) d.map { (it as JsonPrimitive).content } else listOf((d as JsonPrimitive).content)
                if (given.any { it !in values }) bad("${p.id}: default is not an option")
            }
        }
    }

    private fun condition(c: String, t: PolicyTemplate, bad: (String) -> Unit) {
        val parsed = try {
            Conditions.parse(c)
        } catch (e: ConditionException) {
            return bad("condition \"$c\": ${e.message}")
        }
        parsed.names.forEach { n ->
            val ok = when {
                n == "headscale" || n == "tailscale" || n == "style:acls" || n == "style:grants" -> true
                n.startsWith("param:") -> t.param(n.removePrefix("param:").substringBefore('=')) != null
                n.startsWith("check:") -> t.checks.any { it.id == n.removePrefix("check:") }
                else -> false
            }
            if (!ok) bad("condition \"$c\" names \"$n\", which this template does not have")
        }
    }

    /** One item of an array section: its reserved keys, its shape, its placeholders. */
    private fun item(section: String, item: JsonElement, t: PolicyTemplate, bad: (String) -> Unit, names: (String) -> Unit) {
        val o = item as? JsonObject ?: return bad("an item of $section is not an object")
        (o[WHEN])?.let { w -> (w as? JsonPrimitive)?.takeIf { it.isString }?.let { names(it.content) } ?: bad("$section: when is not a string") }
        (o[COMMENT])?.let { c ->
            val ok = c is JsonObject && c.values.all { it is JsonPrimitive && it.isString } && c["en"] != null
            if (!ok) bad("$section: comment is not text in languages") else c.jsonValues().forEach { placeholders(it, t, inArray = false, bad) }
        }
        val generate = (o[GENERATE] as? JsonPrimitive)?.content
        when {
            o[GENERATE] != null && (section != "tests" || generate != PAIR_TEST) -> bad("$section: generate \"$generate\"")
            generate == PAIR_TEST -> {
                for (k in listOf("src", "dst")) if (o[k] !is JsonArray) bad("pair-test: $k is not a list")
                if ((o["port"] as? JsonPrimitive)?.isString != true) bad("pair-test: port is not a string")
            }
            section == "access" -> {
                (o.keys - ACCESS_KEYS).forEach { bad("access: unknown field \"$it\"") }
                for (k in listOf("src", "dst")) if (o[k] !is JsonArray) bad("access: $k is not a list")
                for (k in listOf("ports", "via", "srcPosture")) if (o[k] != null && o[k] !is JsonArray) bad("access: $k is not a list")
                if (o["proto"] != null && (o["proto"] as? JsonPrimitive)?.isString != true) bad("access: proto is not a string")
            }
        }
        o.filterKeys { it != WHEN && it != COMMENT }.values.forEach { walk(it, t, inArray = false, bad) }
    }

    private fun objectSection(section: String, o: JsonObject, t: PolicyTemplate, bad: (String) -> Unit) {
        o.forEach { (key, value) ->
            placeholders(key, t, inArray = false, bad, key = true)
            when (section) {
                "hosts" -> if ((value as? JsonPrimitive)?.isString != true) bad("hosts.$key is not a string")
                "autoApprovers" -> when (key) {
                    "exitNode" -> if (value !is JsonArray) bad("autoApprovers.exitNode is not a list")
                    "routes", "services" -> (value as? JsonObject)?.forEach { (k, v) ->
                        placeholders(k, t, inArray = false, bad, key = true)
                        if (v !is JsonArray) bad("autoApprovers.$key.$k is not a list")
                    } ?: bad("autoApprovers.$key is not an object")
                    else -> bad("autoApprovers.$key is not one of $APPROVER_KEYS")
                }
                else -> if (value !is JsonArray) bad("$section.$key is not a list")
            }
            walk(value, t, inArray = false, bad)
        }
    }

    private fun walk(e: JsonElement, t: PolicyTemplate, inArray: Boolean, bad: (String) -> Unit) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> placeholders(k, t, inArray = false, bad, key = true); walk(v, t, inArray = false, bad) }
            is JsonArray -> e.forEach { walk(it, t, inArray = true, bad) }
            is JsonPrimitive -> if (e.isString) placeholders(e.content, t, inArray, bad)
        }
    }

    /** Every placeholder of [s] names a parameter, and a list stands only where a list may. */
    private fun placeholders(s: String, t: PolicyTemplate, inArray: Boolean, bad: (String) -> Unit, key: Boolean = false) {
        if (Placeholders.hasStray(s)) bad("\"$s\": a {{ or }} that is not a placeholder")
        val found = Placeholders.find(s)
        val whole = Placeholders.whole(s)
        found.forEach { p ->
            val param = t.param(p.id) ?: return@forEach bad("\"$s\" names {{${p.id}}}, which is not a parameter")
            val list = param.multi && param.kind != ParamKind.BOOL
            // A list may stand alone as an item or a value, or per element inside an item; elsewhere only joined.
            val listOk = p.join || inArray || (!key && whole != null)
            if (list && !listOk) bad("\"$s\": {{${p.id}}} is a list; write {{${p.id}|join}}")
        }
    }

    private fun JsonObject.jsonValues(): List<String> = values.mapNotNull { (it as? JsonPrimitive)?.content }
}
