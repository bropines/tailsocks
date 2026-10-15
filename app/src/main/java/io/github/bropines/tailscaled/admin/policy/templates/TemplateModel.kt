package io.github.bropines.tailscaled.admin.policy.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * A policy template as its file says it (policy-templates/README.md is the format, for people):
 * what it is, the parameters its form asks for, what it would merge into the policy and what
 * tells it that the policy has some of it already. Data only — the engine (TemplateEngine)
 * gives it meaning, and nothing in a template is ever run or fetched.
 */

/** Text in several languages: `{"en": "...", "ru": "..."}`; English is the one every template must have. */
typealias LocalText = Map<String, String>

/** [this] in [language], else in English, else in whatever it has. */
fun LocalText.pick(language: String): String = this[language] ?: this["en"] ?: values.firstOrNull().orEmpty()

/** How far Headscale takes what a template writes. */
@Serializable(with = HeadscaleLevelSerializer::class)
enum class HeadscaleLevel { FULL, PARTIAL, NONE }

/** `true`, `false` or `"partial"` — as a template writes it; `"full"` and `"none"` are read too. */
object HeadscaleLevelSerializer : KSerializer<HeadscaleLevel> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("HeadscaleLevel", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): HeadscaleLevel {
        val p = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive ?: throw SerializationException("headscale.supported: not a value")
        p.booleanOrNull?.let { return if (it) HeadscaleLevel.FULL else HeadscaleLevel.NONE }
        return when (p.content) {
            "partial" -> HeadscaleLevel.PARTIAL
            "full", "yes" -> HeadscaleLevel.FULL
            "none", "no" -> HeadscaleLevel.NONE
            else -> throw SerializationException("headscale.supported: ${p.content}")
        }
    }

    override fun serialize(encoder: Encoder, value: HeadscaleLevel) {
        when (value) {
            HeadscaleLevel.FULL -> encoder.encodeBoolean(true)
            HeadscaleLevel.NONE -> encoder.encodeBoolean(false)
            HeadscaleLevel.PARTIAL -> encoder.encodeString("partial")
        }
    }
}

@Serializable
data class HeadscaleSupport(val supported: HeadscaleLevel = HeadscaleLevel.FULL, val note: LocalText? = null)

/** A page of documentation the template's page links to; only hosts in [TemplateRules.DOC_HOSTS]. */
@Serializable
data class DocLink(val title: LocalText, val url: String)

/**
 * Where the visual editor offers the template as a card of its own, besides the catalog:
 * [page] (`access`, `attributes`, `peer-relays`) and [slot] — `empty` among the templates of a
 * page with no rules, `before` over the page's list, `after` under it — while [condition] holds.
 */
@Serializable
data class Suggestion(val page: String, val slot: String, @SerialName("when") val condition: String? = null)

/** What a parameter is: what its field looks like and which values it takes. */
@Serializable
enum class ParamKind {
    /** Users, groups, tags, autogroups, hosts — the editor's own picker for [TemplateParam.slot], narrowed by [TemplateParam.allow]. */
    @SerialName("selector") SELECTOR,
    @SerialName("user") USER,
    @SerialName("group") GROUP,
    @SerialName("tag") TAG,
    /** `22`, `8000-8100`, `*`: one port spec per value. */
    @SerialName("port") PORT,
    /** An IP address. */
    @SerialName("host") HOST,
    /** An address with its prefix length: a route. */
    @SerialName("cidr") CIDR,
    @SerialName("text") TEXT,
    @SerialName("bool") BOOL,
    /** One (or with multi, several) of [TemplateParam.options]. */
    @SerialName("choice") CHOICE,
}

@Serializable
data class ParamOption(val value: String, val label: LocalText)

/**
 * One field of the template's form. [default] is a string, a list of strings or, for a bool, a
 * boolean. [slot] names the editor's selector slot (`acl-src`, `grant-dst`, `node-target`, …)
 * whose rules a selector follows; [allow] narrows the kinds it offers. [format] checks a text.
 */
@Serializable
data class TemplateParam(
    val id: String,
    val kind: ParamKind,
    val label: LocalText,
    val help: LocalText? = null,
    val required: Boolean = false,
    val multi: Boolean = false,
    val default: JsonElement? = null,
    val slot: String? = null,
    val allow: List<String> = emptyList(),
    val options: List<ParamOption> = emptyList(),
    val format: String? = null,
)

/** What a check looks for in the policy as it is. */
@Serializable
enum class CheckKind {
    /** Rules of the file already let every source reach every destination on these ports (the remote-management duplicate detection). */
    @SerialName("access-open") ACCESS_OPEN,
    /** Some grant carries the application capability [TemplateCheck.cap]. */
    @SerialName("grant-app") GRANT_APP,
    /** Each of [TemplateCheck.attrs] is turned on by some device attribute rule. */
    @SerialName("node-attr") NODE_ATTR,
    /** What the device list says about relays and their clients; holds when something will not take part. */
    @SerialName("relay-devices") RELAY_DEVICES,
}

/**
 * A declarative look at the policy before anything is written: [id] is what a condition names
 * (`check:open`), [note] what the page says while it holds. Lists take placeholders.
 */
@Serializable
data class TemplateCheck(
    val id: String,
    val kind: CheckKind,
    val src: List<String> = emptyList(),
    val dst: List<String> = emptyList(),
    val ports: List<String> = emptyList(),
    val proto: String? = null,
    val cap: String? = null,
    val attrs: List<String> = emptyList(),
    val relays: List<String> = emptyList(),
    val clients: List<String> = emptyList(),
    val note: LocalText? = null,
)

/**
 * A template file. [fragments] is one block — sections to merge, by name — or a list of blocks,
 * each with its own `when`; it stays JSON until the engine expands it for one form.
 */
@Serializable
data class PolicyTemplate(
    val schema: Int = 1,
    val id: String,
    val version: Int,
    /** The oldest app that may use it: a version name (`"5.1.0"`) or a version code (`5010000`). */
    val minApp: JsonPrimitive? = null,
    val category: String,
    val title: LocalText,
    val description: LocalText,
    val headscale: HeadscaleSupport = HeadscaleSupport(),
    val docs: List<DocLink> = emptyList(),
    val suggest: List<Suggestion> = emptyList(),
    val params: List<TemplateParam> = emptyList(),
    val checks: List<TemplateCheck> = emptyList(),
    val fragments: JsonElement,
) {
    fun param(id: String): TemplateParam? = params.firstOrNull { it.id == id }
}

/** One template an index lists: where its file is, and what it must be. */
@Serializable
data class IndexEntry(
    val id: String,
    val file: String,
    val version: Int,
    val sha256: String,
    val minApp: JsonPrimitive? = null,
)

/** `index.json`: the templates of a set, in the order the catalog shows them. */
@Serializable
data class TemplateIndex(val schema: Int = 1, val templates: List<IndexEntry>)

/** The codec for template files: strict JSON, newer optional fields ignored, nothing coerced. */
val TemplateJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
    coerceInputValues = false
}
