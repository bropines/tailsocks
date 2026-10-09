package io.github.bropines.tailscaled.admin.keys

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.api.OAuthClientRequest
import io.github.bropines.tailscaled.core.SlidingSegmentedChips
import io.github.bropines.tailscaled.ui.HelpText

/*
 * The forms for a new auth key and a new OAuth client. Each only gathers and checks; the
 * request goes to the safety gates from the Keys tab.
 */

private const val SEP = "\n"

private fun String.items(): List<String> = split(SEP).filter { it.isNotBlank() }

/** The chosen preset's lifetime in words ("7 days"); [customDays] for CUSTOM. */
internal fun expiryLabel(ctx: Context, preset: ExpiryPreset, customDays: String): String {
    val seconds = KeyRules.expirySeconds(preset, customDays) ?: return ctx.getString(R.string.admin_k_expiry_custom)
    return spanText(ctx, seconds * 1000)
}

private fun descriptionError(ctx: Context, problem: DescriptionProblem?): String? = when (problem) {
    DescriptionProblem.TOO_LONG -> ctx.getString(R.string.admin_k_desc_too_long, KeyRules.DESCRIPTION_MAX)
    DescriptionProblem.BAD_CHARACTERS -> ctx.getString(R.string.admin_k_desc_bad_chars)
    DescriptionProblem.MISSING -> ctx.getString(R.string.admin_k_desc_missing)
    null -> null
}

@Composable
private fun DescriptionField(value: String, required: Boolean, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    // Only a missing one is not an error while nothing is typed.
    val problem = KeyRules.descriptionProblem(value, required).takeUnless { it == DescriptionProblem.MISSING && value.isEmpty() }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(ctx.getString(if (required) R.string.admin_k_desc_required else R.string.admin_keys_desc_label)) },
        placeholder = { Text(ctx.getString(R.string.admin_k_desc_placeholder)) },
        supportingText = { Text(descriptionError(ctx, problem) ?: "${value.length}/${KeyRules.DESCRIPTION_MAX}") },
        isError = problem != null,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Tags as chips from the policy (what [tags] says this credential may use), plus a field for
 * the rest; [picked] and [typed] are the caller's state.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPicker(
    tags: List<String>,
    loading: Boolean,
    problem: String?,
    picked: List<String>,
    onPicked: (List<String>) -> Unit,
    typed: String,
    onTyped: (String) -> Unit,
    help: String?,
    required: Boolean,
) {
    val ctx = LocalContext.current
    val (_, bad) = KeyRules.typedTags(typed)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(ctx.getString(if (required) R.string.admin_k_tags_required else R.string.admin_k_tags), style = MaterialTheme.typography.titleSmall)
        help?.let { HelpText(it) }
        when {
            loading && tags.isEmpty() -> Text(ctx.getString(R.string.admin_k_tags_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            problem != null -> HelpText(ctx.getString(R.string.admin_k_tags_unreadable, problem), color = MaterialTheme.colorScheme.error)
            tags.isEmpty() -> HelpText(ctx.getString(R.string.admin_k_tags_none))
        }
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (tags + picked.filter { it !in tags }).forEach { tag ->
                    val on = tag in picked
                    FilterChip(
                        selected = on,
                        onClick = { onPicked(if (on) picked - tag else picked + tag) },
                        label = { Text(tag.removePrefix("tag:")) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = typed,
            onValueChange = onTyped,
            label = { Text(ctx.getString(R.string.admin_k_tags_other)) },
            placeholder = { Text("tag:server, tag:ci", fontFamily = FontFamily.Monospace) },
            supportingText = bad.takeIf { it.isNotEmpty() }?.let { { Text(ctx.getString(R.string.admin_k_tags_bad, bad.joinToString(", "))) } },
            isError = bad.isNotEmpty(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * What the new-auth-key form holds, hoisted so the dialog's button can read it and a preview
 * can fill it; saved across rotation by [Saver].
 */
@Stable
class AuthKeyForm(
    desc: String = "",
    reusable: Boolean = false,
    ephemeral: Boolean = false,
    preauth: Boolean = false,
    preset: ExpiryPreset = ExpiryPreset.QUARTER,
    customDays: String = "",
    picked: List<String> = emptyList(),
    typed: String = "",
) {
    var desc by mutableStateOf(desc)
    var reusable by mutableStateOf(reusable)
    var ephemeral by mutableStateOf(ephemeral)
    var preauth by mutableStateOf(preauth)
    var preset by mutableStateOf(preset)
    var customDays by mutableStateOf(customDays)
    var picked by mutableStateOf(picked)
    var typed by mutableStateOf(typed)

    val seconds: Long? get() = KeyRules.expirySeconds(preset, customDays)
    val tags: List<String> get() = (picked + KeyRules.typedTags(typed).first).distinct()

    /** [scoped]: an OAuth client's keys must carry a tag. */
    fun request(scoped: Boolean): AuthKeyRequest? {
        val s = seconds ?: return null
        if (KeyRules.descriptionProblem(desc) != null) return null
        if (KeyRules.typedTags(typed).second.isNotEmpty() || (scoped && tags.isEmpty())) return null
        return AuthKeyRequest(desc.trim(), s, reusable, ephemeral, preauth, tags)
    }

    companion object {
        val Saver: Saver<AuthKeyForm, Any> = listSaver(
            save = { listOf(it.desc, it.reusable, it.ephemeral, it.preauth, it.preset.name, it.customDays, it.picked.joinToString(SEP), it.typed) },
            restore = { v ->
                AuthKeyForm(
                    v[0] as String, v[1] as Boolean, v[2] as Boolean, v[3] as Boolean,
                    ExpiryPreset.valueOf(v[4] as String), v[5] as String, (v[6] as String).items(), v[7] as String,
                )
            },
        )
    }
}

/**
 * A new auth key: reusable, ephemeral and pre-approved are three switches of their own; tags
 * from the policy's tagOwners as chips (typed by hand when the policy cannot be read); the
 * expiry from presets or a number of days; the description checked against the API's rules
 * before anything is sent. With an OAuth client as the console's credential the key must
 * carry a tag the client may hand out, and the client's own tags start picked.
 */
@Composable
fun CreateAuthKeyDialog(
    tags: List<String>,
    tagsLoading: Boolean,
    tagsProblem: String?,
    credentialTags: List<String>,
    scoped: Boolean,
    onDismiss: () -> Unit,
    onCreate: (AuthKeyRequest, expiry: String) -> Unit,
) {
    val ctx = LocalContext.current
    val form = rememberSaveable(saver = AuthKeyForm.Saver) { AuthKeyForm(picked = credentialTags) }
    val request = form.request(scoped)
    LocaleDialog(
        onDismiss = onDismiss,
        title = ctx.getString(R.string.admin_k_create_auth_title),
        icon = Icons.Default.VpnKey,
        confirmButton = {
            Button(enabled = request != null, onClick = { request?.let { onCreate(it, expiryLabel(ctx, form.preset, form.customDays)) } }) {
                Text(ctx.getString(R.string.admin_k_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    ) {
        AuthKeyFields(form, tags, tagsLoading, tagsProblem, credentialTags, scoped)
    }
}

/** The new-auth-key form's fields, apart so a preview can draw them without a dialog window. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuthKeyFields(form: AuthKeyForm, tags: List<String>, tagsLoading: Boolean, tagsProblem: String?, credentialTags: List<String>, scoped: Boolean) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DescriptionField(form.desc, required = false) { form.desc = it }
        Column {
            SwitchRow(ctx.getString(R.string.admin2_key_reusable), ctx.getString(R.string.admin2_key_reusable_desc), form.reusable) { form.reusable = it }
            SwitchRow(ctx.getString(R.string.admin_k_ephemeral), ctx.getString(R.string.admin2_key_ephemeral_desc), form.ephemeral) { form.ephemeral = it }
            SwitchRow(ctx.getString(R.string.admin_k_preauthorized), ctx.getString(R.string.admin2_key_preauth_desc), form.preauth) { form.preauth = it }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ctx.getString(R.string.admin_k_expiry), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExpiryPreset.entries.forEach { p ->
                    FilterChip(
                        selected = form.preset == p,
                        onClick = { form.preset = p },
                        label = { Text(p.seconds?.let { spanText(ctx, it * 1000) } ?: ctx.getString(R.string.admin_k_expiry_custom)) },
                    )
                }
            }
            if (form.preset == ExpiryPreset.CUSTOM) {
                OutlinedTextField(
                    value = form.customDays,
                    onValueChange = { v -> form.customDays = v.filter { it.isDigit() }.take(3) },
                    label = { Text(ctx.getString(R.string.admin_k_expiry_days)) },
                    supportingText = { Text(ctx.getString(R.string.admin_k_expiry_days_range, KeyRules.MAX_DAYS)) },
                    isError = form.customDays.isNotEmpty() && form.seconds == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        TagPicker(
            tags = tags,
            loading = tagsLoading,
            problem = tagsProblem,
            picked = form.picked,
            onPicked = { form.picked = it },
            typed = form.typed,
            onTyped = { form.typed = it },
            help = if (scoped) ctx.getString(R.string.admin_k_tags_scoped_help, credentialTags.joinToString(", ").ifEmpty { "\u2014" })
            else ctx.getString(R.string.admin_k_tags_help),
            required = scoped,
        )
    }
}

private fun encodeLevels(levels: Map<String, ScopeLevel>): String =
    levels.filterValues { it != ScopeLevel.NONE }.entries.joinToString(SEP) { "${it.key}=${it.value.name}" }

private fun decodeLevels(text: String): Map<String, ScopeLevel> =
    text.items().mapNotNull { line ->
        val (k, v) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        runCatching { k to ScopeLevel.valueOf(v) }.getOrNull()
    }.toMap()

/** Words for the scopes this app knows; one it does not is shown by its raw name. */
private val SCOPE_LABELS: Map<AdminArea, Int> = mapOf(
    AdminArea.DEVICES to R.string.admin_k_scope_devices,
    AdminArea.ROUTES to R.string.admin_k_scope_routes,
    AdminArea.POSTURE to R.string.admin_k_scope_posture,
    AdminArea.DEVICE_INVITES to R.string.admin_k_scope_device_invites,
    AdminArea.AUTH_KEYS to R.string.admin_k_scope_auth_keys,
    AdminArea.OAUTH_KEYS to R.string.admin_k_scope_oauth_keys,
    AdminArea.API_TOKENS to R.string.admin_k_scope_api_tokens,
    AdminArea.FEDERATED_KEYS to R.string.admin_k_scope_federated,
    AdminArea.DNS to R.string.admin_k_scope_dns,
    AdminArea.POLICY to R.string.admin_k_scope_policy,
    AdminArea.USERS to R.string.admin_k_scope_users,
    AdminArea.WEBHOOKS to R.string.admin_k_scope_webhooks,
    AdminArea.AUDIT_LOGS to R.string.admin_k_scope_audit_logs,
    AdminArea.NETWORK_LOGS to R.string.admin_k_scope_network_logs,
    AdminArea.LOG_STREAMING to R.string.admin_k_scope_log_streaming,
    AdminArea.SETTINGS to R.string.admin_k_scope_feature_settings,
    AdminArea.NETWORKING_SETTINGS to R.string.admin_k_scope_networking_settings,
    AdminArea.ACCOUNT_SETTINGS to R.string.admin_k_scope_account_settings,
    AdminArea.SERVICES to R.string.admin_k_scope_services,
)

internal fun scopeLabel(ctx: Context, option: ScopeOption): String = when (val area = option.area) {
    null -> ctx.getString(R.string.admin_k_scope_all)
    else -> SCOPE_LABELS[area]?.let(ctx::getString) ?: option.scope
}

/** What the new-OAuth-client form holds; see [AuthKeyForm]. */
@Stable
class OAuthClientForm(desc: String = "", levels: Map<String, ScopeLevel> = emptyMap(), picked: List<String> = emptyList(), typed: String = "") {
    var desc by mutableStateOf(desc)
    var levels by mutableStateOf(levels)
    var picked by mutableStateOf(picked)
    var typed by mutableStateOf(typed)

    val scopes: List<String> get() = OAuthScopes.toScopes(levels)
    val tags: List<String> get() = (picked + KeyRules.typedTags(typed).first).distinct()
    val needsTags: Boolean get() = OAuthScopes.needsTags(scopes)

    fun request(): OAuthClientRequest? {
        if (KeyRules.descriptionProblem(desc, required = true) != null || scopes.isEmpty()) return null
        if (KeyRules.typedTags(typed).second.isNotEmpty() || (needsTags && tags.isEmpty())) return null
        return OAuthClientRequest(desc.trim(), scopes, tags)
    }

    companion object {
        val Saver: Saver<OAuthClientForm, Any> = listSaver(
            save = { listOf(it.desc, encodeLevels(it.levels), it.picked.joinToString(SEP), it.typed) },
            restore = { v -> OAuthClientForm(v[0] as String, decodeLevels(v[1] as String), (v[2] as String).items(), v[3] as String) },
        )
    }
}

/**
 * A new OAuth client (trust credential): a description, a level per scope — off, read or
 * write — and tags, which become mandatory once it may write devices or auth keys. It never
 * expires, so the form says so before the gates do.
 */
@Composable
fun CreateOAuthClientDialog(
    tags: List<String>,
    tagsProblem: String?,
    onDismiss: () -> Unit,
    onCreate: (OAuthClientRequest) -> Unit,
) {
    val ctx = LocalContext.current
    val form = rememberSaveable(saver = OAuthClientForm.Saver) { OAuthClientForm() }
    val request = form.request()
    LocaleDialog(
        onDismiss = onDismiss,
        title = ctx.getString(R.string.admin_k_create_client_title),
        icon = Icons.Default.SmartToy,
        confirmButton = {
            Button(enabled = request != null, onClick = { request?.let(onCreate) }) { Text(ctx.getString(R.string.admin_k_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    ) {
        OAuthClientFields(form, tags, tagsProblem)
    }
}

/** The new-OAuth-client form's fields, apart so a preview can draw them without a dialog window. */
@Composable
fun OAuthClientFields(form: OAuthClientForm, tags: List<String>, tagsProblem: String?) {
    val ctx = LocalContext.current
    val scopes = form.scopes
    val offReadWrite = listOf(ctx.getString(R.string.admin_k_scope_off), ctx.getString(R.string.admin_k_scope_read), ctx.getString(R.string.admin_k_scope_write))
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HelpText(ctx.getString(R.string.admin_k_create_client_help))
        DescriptionField(form.desc, required = true) { form.desc = it }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ctx.getString(R.string.admin_k_scopes), style = MaterialTheme.typography.titleSmall)
            OAuthScopes.options.forEach { option ->
                val level = form.levels[option.scope] ?: ScopeLevel.NONE
                val choices = if (option.readOnly) offReadWrite.take(2) else offReadWrite
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(scopeLabel(ctx, option), style = MaterialTheme.typography.bodyMedium)
                        Text(option.scope, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.width(8.dp))
                    SlidingSegmentedChips(
                        options = choices,
                        selectedIndex = level.ordinal.coerceAtMost(choices.lastIndex),
                        onOptionSelected = { i -> form.levels = form.levels + (option.scope to ScopeLevel.entries[i]) },
                        modifier = Modifier.width(if (option.readOnly) 112.dp else 168.dp),
                        height = 32.dp,
                    )
                }
            }
            if (scopes.isNotEmpty()) {
                Text(scopes.joinToString(" "), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TagPicker(
            tags = tags,
            loading = false,
            problem = tagsProblem,
            picked = form.picked,
            onPicked = { form.picked = it },
            typed = form.typed,
            onTyped = { form.typed = it },
            help = ctx.getString(R.string.admin_k_client_tags_help),
            required = form.needsTags,
        )
    }
}
