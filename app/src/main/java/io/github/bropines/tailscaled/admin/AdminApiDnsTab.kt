package io.github.bropines.tailscaled.admin

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.DnsConfiguration
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.ui.HelpText

/**
 * MagicDNS, the global nameservers, split DNS and search domains, read in one call from
 * /dns/configuration. Every change goes to the safety pipeline; what is shown here stays the
 * server's until the change is verified and the tab reloads.
 */
@Composable
fun DnsTabContent(
    state: Loadable<DnsConfiguration>,
    canWrite: Boolean,
    onRetry: () -> Unit,
    onMagicDnsChanged: (Boolean) -> Unit,
    onApplyNameservers: (List<String>) -> Unit,
    onUpdateSplitDns: (String, List<String>?) -> Unit,
    onApplySearchPaths: (List<String>) -> Unit,
) {
    val ctx = LocalContext.current
    val cfg = state.value
    if (cfg == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            LoadProblems(state, onRetry)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (state.loading) LoadingIndicatorCompat() }
        }
        return
    }
    val nameservers = cfg.nameserverAddresses
    val searchPaths = cfg.searchPaths
    val nsList = remember(nameservers) { mutableStateListOf(*nameservers.toTypedArray()) }
    val pathList = remember(searchPaths) { mutableStateListOf(*searchPaths.toTypedArray()) }
    var newNs by remember { mutableStateOf("") }
    var newPath by remember { mutableStateOf("") }
    var splitDomain by remember { mutableStateOf("") }
    var splitServers by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoadProblems(state, onRetry)

        Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = cfg.magicDns, enabled = canWrite, role = Role.Switch) { onMagicDnsChanged(it) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ctx.getString(R.string.admin_dns_magic_dns_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    HelpText(ctx.getString(R.string.admin_dns_magic_dns_desc), inClickableRow = true)
                }
                Switch(checked = cfg.magicDns, onCheckedChange = null, enabled = canWrite)
            }
        }

        EditableListCard(
            title = ctx.getString(R.string.admin_dns_global_ns_title),
            emptyText = ctx.getString(R.string.admin_dns_no_custom_ns),
            items = nsList,
            newValue = newNs,
            onNewValue = { newNs = it },
            placeholder = ctx.getString(R.string.admin_dns_ns_placeholder),
            applyLabel = ctx.getString(R.string.admin_dns_apply_ns),
            changed = nsList.toList() != nameservers,
            canWrite = canWrite,
            monospace = true,
            onApply = { onApplyNameservers(nsList.toList()) },
        )

        Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ctx.getString(R.string.admin_dns_split_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val split = cfg.splitDnsAddresses
                if (split.isEmpty()) {
                    Text(ctx.getString(R.string.admin_dns_no_split), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    split.forEach { (domain, servers) ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(domain, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(servers.joinToString(", "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (canWrite) IconButton(onClick = { onUpdateSplitDns(domain, null) }) {
                                Icon(Icons.Default.Delete, ctx.getString(R.string.action_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                if (canWrite) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OutlinedTextField(
                        value = splitDomain,
                        onValueChange = { splitDomain = it.trim() },
                        label = { Text(ctx.getString(R.string.admin_dns_domain_label)) },
                        placeholder = { Text(ctx.getString(R.string.admin_dns_domain_placeholder)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = splitServers,
                        onValueChange = { splitServers = it },
                        label = { Text(ctx.getString(R.string.admin_dns_ns_list_label)) },
                        placeholder = { Text(ctx.getString(R.string.admin_dns_ns_list_placeholder)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val servers = splitServers.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            if (splitDomain.isBlank() || servers.isEmpty()) {
                                Toast.makeText(ctx, ctx.getString(R.string.admin_dns_domain_and_ns_required), Toast.LENGTH_SHORT).show()
                            } else {
                                onUpdateSplitDns(splitDomain.trim().trimEnd('.'), servers)
                                splitDomain = ""
                                splitServers = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(ctx.getString(R.string.admin_dns_add_split_route))
                    }
                }
            }
        }

        EditableListCard(
            title = ctx.getString(R.string.admin_dns_search_domains_title),
            emptyText = ctx.getString(R.string.admin_dns_no_search_domains),
            items = pathList,
            newValue = newPath,
            onNewValue = { newPath = it },
            placeholder = ctx.getString(R.string.admin_dns_search_domain_placeholder),
            applyLabel = ctx.getString(R.string.admin_dns_apply_search),
            changed = pathList.toList() != searchPaths,
            canWrite = canWrite,
            monospace = false,
            onApply = { onApplySearchPaths(pathList.toList()) },
        )

        val uriHandler = LocalUriHandler.current
        Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ctx.getString(R.string.admin_dns_tailnet_name_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                HelpText(ctx.getString(R.string.admin_dns_tailnet_name_desc))
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        runCatching { uriHandler.openUri("https://login.tailscale.com/admin/settings/general") }
                            .onFailure { Toast.makeText(ctx, ctx.getString(R.string.cannot_open_browser), Toast.LENGTH_SHORT).show() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Default.OpenInBrowser, null)
                    Spacer(Modifier.width(8.dp))
                    Text(ctx.getString(R.string.admin_dns_rename_web))
                }
            }
        }
    }
}

/** A list edited locally and applied as one change: nameservers, search domains. */
@Composable
private fun EditableListCard(
    title: String,
    emptyText: String,
    items: MutableList<String>,
    newValue: String,
    onNewValue: (String) -> Unit,
    placeholder: String,
    applyLabel: String,
    changed: Boolean,
    canWrite: Boolean,
    monospace: Boolean,
    onApply: () -> Unit,
) {
    val ctx = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (items.isEmpty()) {
                Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items.toList().forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item,
                                fontFamily = if (monospace) FontFamily.Monospace else null,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                            )
                            if (canWrite) IconButton(onClick = { items.remove(item) }) {
                                Icon(Icons.Default.Close, ctx.getString(R.string.action_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            if (canWrite) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { onNewValue(it.trim()) },
                        placeholder = { Text(placeholder) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            if (newValue.isNotBlank() && newValue !in items) items.add(newValue)
                            onNewValue("")
                        },
                        shape = MaterialTheme.shapes.medium,
                    ) { Text(ctx.getString(R.string.action_add)) }
                }
                Button(onClick = onApply, enabled = changed, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Done, null)
                    Spacer(Modifier.width(8.dp))
                    Text(applyLabel)
                }
            }
        }
    }
}
