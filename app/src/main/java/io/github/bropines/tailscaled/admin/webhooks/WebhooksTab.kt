package io.github.bropines.tailscaled.admin.webhooks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.DetailRow
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiWebhook
import io.github.bropines.tailscaled.admin.api.WebhookEvents
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.RevealedSecret
import io.github.bropines.tailscaled.admin.formatExpires
import io.github.bropines.tailscaled.admin.settings.ConfigLoading
import io.github.bropines.tailscaled.admin.settings.ConfigNote
import io.github.bropines.tailscaled.admin.settings.NoteTone
import io.github.bropines.tailscaled.admin.settings.Provide
import io.github.bropines.tailscaled.admin.settings.rememberParentLocals
import io.github.bropines.tailscaled.ui.EmptyState
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.rememberFullSheetState

/**
 * The tailnet's webhooks: a card each (endpoint, format, how many events, when made), a sheet
 * per webhook to change its events, send a test, rotate the secret or delete it, and the
 * form for a new one. A secret the server hands out — on creation, after a rotation — is
 * shown once, in the console's reveal dialog.
 */
@Composable
fun WebhooksTab(state: ConsoleState, vm: AdminConsoleViewModel?) {
    val ctx = LocalContext.current
    val webhooks = state.webhooks.value.orEmpty()
    val canWrite = state.canWrite(AdminArea.WEBHOOKS)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ctx.getString(R.string.admin_webhooks_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                HelpText(ctx.getString(R.string.admin_cfg_wh_help))
            }
            if (canWrite) {
                Spacer(Modifier.width(12.dp))
                Button(onClick = { creating = true }, shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text(ctx.getString(R.string.admin_webhooks_add), maxLines = 1)
                }
            }
        }
        LoadProblems(state.webhooks, { vm?.refresh(ConsoleTab.WEBHOOKS, force = true) }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (!canWrite && state.writeBlock == null && state.webhooks.value != null) {
            ConfigNote(ctx.getString(R.string.admin_cfg_scope_missing, AdminArea.WEBHOOKS.scope), NoteTone.LOCKED, Modifier.padding(horizontal = 16.dp))
        }
        when {
            webhooks.isEmpty() && state.webhooks.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { ConfigLoading() }
            webhooks.isEmpty() -> EmptyState(Icons.Default.Webhook, ctx.getString(R.string.admin_webhooks_no_webhooks), Modifier.weight(1f).fillMaxWidth())
            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(webhooks, key = { it.endpointId }) { w -> WebhookCard(w) { selected = w.endpointId } }
            }
        }
    }

    selected?.let { id ->
        val w = webhooks.firstOrNull { it.endpointId == id }
        // Gone after a refresh (deleted): the sheet goes with it.
        LaunchedEffect(w == null) { if (w == null) selected = null }
        if (w != null) WebhookSheet(w, canWrite, vm, onDismiss = { selected = null })
    }
    if (creating) {
        CreateWebhookDialog(onDismiss = { creating = false }) { url, provider, events ->
            creating = false
            vm?.let { createWebhook(it, url, provider, events) }
        }
    }
}

private fun reveal(vm: AdminConsoleViewModel, w: ApiWebhook) {
    val t = vm.text
    w.secret?.takeIf { it.isNotBlank() }?.let { secret ->
        vm.update { it.copy(revealed = RevealedSecret(t.getString(R.string.admin2_webhook_secret_title), t.getString(R.string.admin2_webhook_secret_text), secret)) }
    }
}

private fun createWebhook(vm: AdminConsoleViewModel, url: String, provider: String, events: List<String>) {
    var created: ApiWebhook? = null
    vm.propose(WebhookChanges.create(vm.text, url, provider, events, vm.tailnetLabel) { created = it }) { created?.let { reveal(vm, it) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebhookCard(w: ApiWebhook, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(scheme.secondaryContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Webhook, null, tint = scheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(WebhookInput.host(w.endpointUrl), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(w.endpointUrl, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        ConsoleText.webhookProvider(ctx, w.providerType),
                        ctx.resources.getQuantityString(R.plurals.admin2_webhook_events, w.subscriptions.size, w.subscriptions.size),
                        w.created?.let { ctx.getString(R.string.admin_cfg_wh_created, formatExpires(ctx, it)) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One webhook: its details, its events to change, and its actions — no menu, each a button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebhookSheet(w: ApiWebhook, canWrite: Boolean, vm: AdminConsoleViewModel?, onDismiss: () -> Unit) {
    val parent = rememberParentLocals()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        parent.Provide { WebhookSheetContent(w, canWrite, vm) }
    }
}

/** The sheet's body, apart so a preview can draw it without the sheet's window. */
@Composable
fun WebhookSheetContent(w: ApiWebhook, canWrite: Boolean, vm: AdminConsoleViewModel?) {
    val ctx = LocalContext.current
    val events = remember(w.subscriptions) { mutableStateListOf(*w.subscriptions.toTypedArray()) }
    val changed = events.toSet() != w.subscriptions.toSet()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(WebhookInput.host(w.endpointUrl), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(w.endpointUrl, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DetailRow(ctx.getString(R.string.admin2_webhook_provider), ConsoleText.webhookProvider(ctx, w.providerType))
            w.creatorLoginName?.takeIf { it.isNotBlank() }?.let { DetailRow(ctx.getString(R.string.admin_cfg_wh_creator), it) }
            w.created?.let { DetailRow(ctx.getString(R.string.admin_cfg_wh_created_label), formatExpires(ctx, it)) }
            w.lastModified?.let { DetailRow(ctx.getString(R.string.admin_cfg_wh_modified_label), formatExpires(ctx, it)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(ctx.getString(R.string.admin_cfg_wh_events), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        EventPicker(events, enabled = canWrite)
        if (canWrite) {
            if (events.isEmpty()) ConfigNote(ctx.getString(R.string.admin_cfg_wh_events_none), NoteTone.WARNING)
            Button(
                onClick = { vm?.let { it.propose(WebhookChanges.updateSubscriptions(it.text, w, events.toList())) } },
                enabled = changed && events.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) { Text(ctx.getString(R.string.admin_cfg_wh_save_events)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedButton(
                onClick = {
                    vm?.let { v ->
                        v.propose(WebhookChanges.test(v.text, w)) { v.say(v.text.getString(R.string.admin_cfg_wh_test_sent, WebhookInput.host(w.endpointUrl))) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin_cfg_wh_test))
            }
            HelpText(ctx.getString(R.string.admin_cfg_wh_test_help))
            OutlinedButton(
                onClick = {
                    vm?.let { v ->
                        var rotated: ApiWebhook? = null
                        v.propose(WebhookChanges.rotate(v.text, w) { rotated = it }) { rotated?.let { reveal(v, it) } }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Default.Key, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin_cfg_wh_rotate))
            }
            HelpText(ctx.getString(R.string.admin_cfg_wh_rotate_help))
            Button(
                onClick = { vm?.let { it.propose(WebhookChanges.delete(it.text, w)) } },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin_cfg_wh_delete))
            }
        }
    }
}

/** The eighteen events in their groups; a group's box sets or clears all of it. */
@Composable
fun EventPicker(selected: SnapshotStateList<String>, enabled: Boolean) {
    val ctx = LocalContext.current
    Column {
        WebhookGroups.all.forEach { group ->
            val on = group.events.count { it in selected }
            val tri = when (on) {
                0 -> ToggleableState.Off
                group.events.size -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            }
            Row(
                Modifier.fillMaxWidth().triStateToggleable(state = tri, enabled = enabled, role = Role.Checkbox) {
                    if (tri == ToggleableState.On) selected.removeAll(group.events) else group.events.forEach { e -> if (e !in selected) selected += e }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TriStateCheckbox(state = tri, onClick = null, enabled = enabled, modifier = Modifier.padding(8.dp))
                Text(ctx.getString(group.titleRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            group.events.forEach { e ->
                val checked = e in selected
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp).toggleable(value = checked, enabled = enabled, role = Role.Checkbox) {
                        if (it) selected += e else selected.remove(e)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(8.dp))
                    Text(WebhookText.event(ctx, e), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * A new webhook: an https endpoint on 443 or 80, its format, and at least one event. Its
 * words come from the parent's context: the dialog is a window of its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateWebhookDialog(onDismiss: () -> Unit, onSave: (url: String, provider: String, events: List<String>) -> Unit) {
    val parent = rememberParentLocals()
    val ctx = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val events = remember { mutableStateListOf(*WebhookGroups.defaults.toTypedArray()) }
    val urlError = WebhookInput.urlError(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_webhooks_add_title)) },
        text = { parent.Provide { CreateWebhookContent(url, { url = it.trim() }, provider, { provider = it }, events, if (showErrors) urlError else null) } },
        confirmButton = {
            Button(onClick = {
                showErrors = true
                if (urlError == null && events.isNotEmpty()) onSave(url, provider, WebhookEvents.all.filter { it in events })
            }, enabled = events.isNotEmpty()) { Text(ctx.getString(R.string.admin_cfg_wh_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

/** The form's body, apart so a preview can draw it without the dialog's window. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateWebhookContent(
    url: String,
    onUrl: (String) -> Unit,
    provider: String,
    onProvider: (String) -> Unit,
    events: SnapshotStateList<String>,
    urlError: WebhookUrlError?,
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrl,
            label = { Text(ctx.getString(R.string.admin_webhooks_url_label)) },
            placeholder = { Text(ctx.getString(R.string.admin_webhooks_url_placeholder)) },
            isError = urlError != null,
            supportingText = { Text(urlError?.let { WebhookText.urlError(ctx, it) } ?: ctx.getString(R.string.admin_cfg_wh_url_help)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(ctx.getString(R.string.admin2_webhook_provider), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WebhookEvents.providerTypes.forEach { p ->
                FilterChip(selected = provider == p, onClick = { onProvider(p) }, label = { Text(ConsoleText.webhookProvider(ctx, p)) })
            }
        }
        Text(ctx.getString(R.string.admin_cfg_wh_events), style = MaterialTheme.typography.labelLarge)
        if (events.isEmpty()) ConfigNote(ctx.getString(R.string.admin_cfg_wh_events_none), NoteTone.WARNING)
        EventPicker(events, enabled = true)
    }
}
