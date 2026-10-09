package io.github.bropines.tailscaled.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.console.ProfileDraft
import io.github.bropines.tailscaled.admin.headscale.HeadscaleEditor
import io.github.bropines.tailscaled.admin.headscale.HeadscaleEditorFields
import io.github.bropines.tailscaled.admin.profile.AuthType
import io.github.bropines.tailscaled.admin.secure.AdminWriteGate
import io.github.bropines.tailscaled.admin.secure.SecretField
import io.github.bropines.tailscaled.admin.secure.SecureWindow
import io.github.bropines.tailscaled.admin.secure.UnlockFailure
import io.github.bropines.tailscaled.admin.secure.UnlockResult
import io.github.bropines.tailscaled.admin.secure.findFragmentActivity
import io.github.bropines.tailscaled.core.SlidingSegmentedChips
import io.github.bropines.tailscaled.ui.AppTopBar
import io.github.bropines.tailscaled.ui.HelpText

/**
 * One admin profile: a name, a credential and how to reach the server. The window is kept out
 * of screenshots while it is open; secret fields are masked; a stored secret is never shown —
 * the field stays empty and keeps it. A new credential is checked against the server before
 * it is stored.
 */
@Composable
fun AdminProfileEditorScreen(
    draft: ProfileDraft,
    firstProfile: Boolean,
    onChange: ((ProfileDraft) -> ProfileDraft) -> Unit,
    onSave: (unchecked: Boolean) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onUnlockForWrites: (UnlockResult) -> Unit,
) {
    val ctx = LocalContext.current
    SecureWindow()
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showProxy by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val showLabel = ctx.getString(R.string.admin2_secret_show)
    val hideLabel = ctx.getString(R.string.admin2_secret_hide)
    val keepPlaceholder = if (draft.hasStoredSecret) ctx.getString(R.string.admin2_secret_stored) else null

    // Switching read-only off is unlocked like any other change; the editor waits for it.
    val inPreview = LocalInspectionMode.current
    val currentOnUnlock by rememberUpdatedState(onUnlockForWrites)
    LaunchedEffect(draft.awaitingUnlock) {
        if (!draft.awaitingUnlock || inPreview) return@LaunchedEffect
        val activity = ctx.findFragmentActivity()
        currentOnUnlock(
            if (activity == null) UnlockResult.Failed(UnlockFailure.PROMPT_UNAVAILABLE)
            else AdminWriteGate.unlock(activity, ctx.getString(R.string.admin2_write_unlock_title), ctx.getString(R.string.admin2_unlock_to_allow_writes, draft.name.ifBlank { "…" }))
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = ctx.getString(if (draft.id == null) R.string.admin2_profile_new_title else R.string.admin2_profile_edit_title),
                onBack = onCancel,
            )
        }
    ) { padding ->
        // A form: on a tablet or in landscape it keeps a readable width instead of spanning the screen.
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (firstProfile) {
                    Icon(Icons.Default.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally))
                    Text(
                        ctx.getString(R.string.admin_setup_integration_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HelpText(ctx.getString(R.string.admin2_profile_intro))

                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { v -> onChange { it.copy(name = v) } },
                    label = { Text(ctx.getString(R.string.admin2_profile_name)) },
                    placeholder = { Text(ctx.getString(R.string.admin2_profile_name_placeholder)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Tailscale or Headscale; which Headscale API generation is found on saving.
                val headscale = HeadscaleEditor.isHeadscale(draft)
                SlidingSegmentedChips(
                    options = listOf(ctx.getString(R.string.admin_hs_editor_tailscale), ctx.getString(R.string.admin_hs_editor_headscale)),
                    selectedIndex = if (headscale) 1 else 0,
                    onOptionSelected = { i -> onChange { HeadscaleEditor.switchBackend(it, toHeadscale = i == 1) } },
                    modifier = Modifier.fillMaxWidth(),
                    height = 38.dp,
                )

                if (headscale) {
                    HeadscaleEditorFields(draft, onChange, keepPlaceholder, showLabel, hideLabel)
                } else {
                    SlidingSegmentedChips(
                        options = listOf(ctx.getString(R.string.admin_setup_tab_token), ctx.getString(R.string.admin_setup_tab_oauth)),
                        selectedIndex = if (draft.authType == AuthType.OAUTH_CLIENT) 1 else 0,
                        onOptionSelected = { i -> onChange { it.copy(authType = if (i == 1) AuthType.OAUTH_CLIENT else AuthType.API_TOKEN, secret = "") } },
                        modifier = Modifier.fillMaxWidth(),
                        height = 38.dp,
                    )
                    HelpText(ctx.getString(if (draft.authType == AuthType.OAUTH_CLIENT) R.string.admin2_auth_oauth_help else R.string.admin2_auth_token_help))

                    if (draft.authType == AuthType.OAUTH_CLIENT) {
                        OutlinedTextField(
                            value = draft.oauthClientId,
                            onValueChange = { v -> onChange { it.copy(oauthClientId = v.trim()) } },
                            label = { Text(ctx.getString(R.string.admin_setup_client_id_label)) },
                            placeholder = { Text(ctx.getString(R.string.admin_setup_client_id_placeholder)) },
                            supportingText = { Text(ctx.getString(R.string.admin_oauth_client_id_optional)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SecretField(
                            value = draft.secret,
                            onValueChange = { v -> onChange { it.copy(secret = v.trim()) } },
                            label = ctx.getString(R.string.admin_setup_client_secret_label),
                            placeholder = keepPlaceholder ?: ctx.getString(R.string.admin_setup_client_secret_placeholder),
                            showLabel = showLabel, hideLabel = hideLabel,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        SecretField(
                            value = draft.secret,
                            onValueChange = { v -> onChange { it.copy(secret = v.trim()) } },
                            label = ctx.getString(R.string.admin_setup_token_label),
                            placeholder = keepPlaceholder ?: ctx.getString(R.string.admin_setup_token_placeholder),
                            showLabel = showLabel, hideLabel = hideLabel,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .toggleable(value = draft.readOnly, role = Role.Switch) { v -> onChange { it.copy(readOnly = v) } }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Visibility, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ctx.getString(R.string.admin2_profile_read_only), style = MaterialTheme.typography.bodyLarge)
                        HelpText(ctx.getString(R.string.admin2_profile_read_only_desc), inClickableRow = true)
                    }
                    Switch(checked = draft.readOnly, onCheckedChange = null)
                }

                if (!headscale) ExpandableCard(
                    title = ctx.getString(R.string.admin2_profile_advanced),
                    expanded = showAdvanced,
                    onToggle = { showAdvanced = !showAdvanced },
                ) {
                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { v -> onChange { it.copy(baseUrl = v.trim()) } },
                        label = { Text(ctx.getString(R.string.admin2_profile_base_url)) },
                        supportingText = { Text(ctx.getString(R.string.admin2_profile_base_url_help)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.tailnet,
                        onValueChange = { v -> onChange { it.copy(tailnet = v.trim()) } },
                        label = { Text(ctx.getString(R.string.admin2_profile_tailnet)) },
                        supportingText = { Text(ctx.getString(R.string.admin2_profile_tailnet_help)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ExpandableCard(
                    title = ctx.getString(R.string.admin_proxy_config_title),
                    expanded = showProxy,
                    onToggle = { showProxy = !showProxy },
                ) {
                    ProxySettingsFields(
                        proxy = draft.proxy,
                        password = draft.proxyPassword,
                        hasStoredPassword = draft.hasStoredProxyPassword,
                        onChange = { p -> onChange { it.copy(proxy = p) } },
                        onPasswordChange = { v -> onChange { it.copy(proxyPassword = v) } },
                    )
                }

                draft.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (draft.checking) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(ctx.getString(R.string.admin2_profile_checking), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                Button(
                    onClick = { onSave(false) },
                    enabled = !draft.checking && !draft.awaitingUnlock,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Default.Save, null)
                    Spacer(Modifier.width(8.dp))
                    Text(ctx.getString(R.string.admin2_profile_save))
                }
                if (draft.offerUnchecked) {
                    OutlinedButton(onClick = { onSave(true) }, enabled = !draft.checking, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                        Text(ctx.getString(R.string.admin2_profile_save_anyway))
                    }
                }
                if (draft.id != null) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text(ctx.getString(R.string.admin2_profile_delete))
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(ctx.getString(R.string.admin2_profile_delete_title)) },
            text = { Text(ctx.getString(R.string.admin2_profile_delete_text, draft.name.ifBlank { draft.baseUrl })) },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(ctx.getString(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(ctx.getString(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ExpandableCard(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onToggle).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            }
        }
    }
}
