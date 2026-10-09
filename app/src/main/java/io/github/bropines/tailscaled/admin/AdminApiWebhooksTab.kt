package io.github.bropines.tailscaled.admin

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiWebhook
import io.github.bropines.tailscaled.admin.api.WebhookEvents
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.ui.EmptyState

@Composable
fun WebhooksTabContent(
    state: Loadable<List<ApiWebhook>>,
    canWrite: Boolean,
    onRetry: () -> Unit,
    onCreateClick: () -> Unit,
    onTestClick: (ApiWebhook) -> Unit,
    onDeleteClick: (ApiWebhook) -> Unit,
) {
    val ctx = LocalContext.current
    val webhooks = state.value.orEmpty()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The heading takes what is left after the button; Russian strings collided otherwise.
            Text(
                ctx.getString(R.string.admin_webhooks_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (canWrite) {
                Spacer(Modifier.width(12.dp))
                Button(onClick = onCreateClick, shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(ctx.getString(R.string.admin_webhooks_add), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                }
            }
        }
        LoadProblems(state, onRetry, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        when {
            webhooks.isEmpty() && state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
            webhooks.isEmpty() -> EmptyState(Icons.Default.Webhook, ctx.getString(R.string.admin_webhooks_no_webhooks), Modifier.weight(1f).fillMaxWidth())
            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(webhooks, key = { it.endpointId }) { webhook ->
                    WebhookRow(webhook, canWrite, onTest = { onTestClick(webhook) }, onDelete = { onDeleteClick(webhook) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebhookRow(webhook: ApiWebhook, canWrite: Boolean, onTest: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = scheme.surfaceContainer) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(MaterialTheme.shapes.small).background(scheme.secondary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Webhook, null, tint = scheme.secondary)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(webhook.endpointUrl, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${ConsoleText.webhookProvider(ctx, webhook.providerType)} · " +
                            ctx.resources.getQuantityString(R.plurals.admin2_webhook_events, webhook.subscriptions.size, webhook.subscriptions.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (webhook.subscriptions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    webhook.subscriptions.forEach { StatusTag(it, scheme.surfaceContainerHighest, scheme.onSurface) }
                }
            }
            if (canWrite) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(ctx.getString(R.string.admin_webhooks_test_ping), style = MaterialTheme.typography.labelLarge)
                    }
                    Button(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(ctx.getString(R.string.action_delete), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * A new webhook: an https endpoint, its format, and the events it receives — the schema's
 * `subscriptions`, all eighteen of them on offer (the old dialog sent `subscribedEvents`, which
 * the API does not know, and the webhook came out with none).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateWebhookDialog(onDismiss: () -> Unit, onSave: (url: String, provider: String, events: List<String>) -> Unit) {
    val ctx = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("") }
    val events = remember {
        mutableStateListOf("nodeCreated", "nodeNeedsApproval", "nodeKeyExpiringInOneDay", "nodeKeyExpired", "userNeedsApproval")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_webhooks_add_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text(ctx.getString(R.string.admin_webhooks_url_label)) },
                    placeholder = { Text(ctx.getString(R.string.admin_webhooks_url_placeholder)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(ctx.getString(R.string.admin2_webhook_provider), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WebhookEvents.providerTypes.forEach { p ->
                        FilterChip(selected = provider == p, onClick = { provider = p }, label = { Text(ConsoleText.webhookProvider(ctx, p)) })
                    }
                }
                Text(ctx.getString(R.string.admin_webhooks_subscribe_label), style = MaterialTheme.typography.labelLarge)
                WebhookEvents.all.forEach { event ->
                    val on = event in events
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .toggleable(value = on, role = Role.Checkbox) { if (it) events.add(event) else events.remove(event) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        Text(event, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    !url.startsWith("https://") || !android.util.Patterns.WEB_URL.matcher(url).matches() ->
                        Toast.makeText(ctx, ctx.getString(R.string.admin_webhooks_invalid_url), Toast.LENGTH_SHORT).show()
                    events.isEmpty() -> Toast.makeText(ctx, ctx.getString(R.string.admin_webhooks_no_events_selected), Toast.LENGTH_SHORT).show()
                    else -> onSave(url, provider, WebhookEvents.all.filter { it in events })
                }
            }) { Text(ctx.getString(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}
