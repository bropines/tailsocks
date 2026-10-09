package io.github.bropines.tailscaled.admin.api

import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What /acl/validate said about a candidate policy. The endpoint answers 200 either way: an
 * empty body means it passed, `{message, data}` lists what failed — test failures per user,
 * or the parse error with its position in the message. A 400 carries the same in `message`.
 */
data class PolicyValidation(val ok: Boolean, val message: String? = null, val details: List<String> = emptyList()) {
    companion object {
        val OK = PolicyValidation(true)

        fun parse(body: String): PolicyValidation {
            if (body.isBlank()) return OK
            val root = runCatching { AppJson.parseToJsonElement(body) }.getOrNull() as? JsonObject
                ?: return PolicyValidation(false, body.trim().take(500))
            val message = (root["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val details = (root["data"] as? JsonArray)?.flatMap { describe(it) }.orEmpty()
            return if (message == null && details.isEmpty()) OK else PolicyValidation(false, message, details)
        }

        /** One `data` item as lines: "user: error" for each error it lists. */
        private fun describe(el: JsonElement): List<String> {
            val o = el as? JsonObject ?: return listOf(el.toString().take(300))
            val who = listOf("user", "src", "target").firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } }
            val errors = listOf("errors", "warnings").flatMap { k ->
                (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: (o[k] as? JsonPrimitive)?.content?.let { listOf(it) }.orEmpty()
            }
            if (errors.isEmpty()) return listOf(o.toString().take(300))
            return errors.map { if (who != null) "$who: $it" else it }
        }
    }
}

/** Which way /acl/preview looks: at what a user may reach, or at who may reach an ip:port. */
enum class PolicyPreviewType(val wire: String) { USER("user"), IP_PORT("ipport") }

/** One rule that matched a preview: its sources ([users]), its destinations ([ports]), where it is. */
@Serializable
data class PolicyRuleMatch(
    val users: List<String> = emptyList(),
    val ports: List<String> = emptyList(),
    val lineNumber: Int? = null,
)

@Serializable
data class PolicyPreview(
    val matches: List<PolicyRuleMatch> = emptyList(),
    val type: String? = null,
    val previewFor: String? = null,
)
