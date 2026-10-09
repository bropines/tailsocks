package io.github.bropines.tailscaled.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiService
import io.github.bropines.tailscaled.admin.api.ApiServiceHost
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.ui.EmptyState
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.rememberFullSheetState

@Composable
fun ServicesTabContent(
    state: Loadable<List<ApiService>>,
    onRetry: () -> Unit,
    onServiceClick: (ApiService) -> Unit,
) {
    val ctx = LocalContext.current
    val services = state.value.orEmpty()
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            HelpText(ctx.getString(R.string.admin_services_info), modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        LoadProblems(state, onRetry, Modifier.padding(horizontal = 16.dp))
        when {
            services.isEmpty() && state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
            services.isEmpty() -> EmptyState(Icons.Default.CloudQueue, ctx.getString(R.string.admin_services_no_services), Modifier.weight(1f).fillMaxWidth())
            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(services, key = { it.name }) { service -> ServiceRow(service = service, onClick = { onServiceClick(service) }) }
            }
        }
    }
}

@Composable
fun ServiceRow(service: ApiService, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onClick() },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.CloudQueue, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(service.displayName?.takeIf { it.isNotBlank() } ?: service.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(service.addrs.firstOrNull() ?: ctx.getString(R.string.admin_services_no_ip), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (service.ports.isNotEmpty()) {
                    Text(service.ports.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** One service and the devices that host it; approving a host is a MEDIUM change. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceDetailBottomSheet(
    service: ApiService,
    hosts: Loadable<List<ApiServiceHost>>?,
    allDevices: List<ApiDevice>,
    canWrite: Boolean,
    onLoadHosts: () -> Unit,
    onSetHost: (deviceId: String, deviceName: String, approved: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberFullSheetState()
    // Resolved through the parent's context; the sheet's own ignores the app locale.
    val ctx = LocalContext.current
    LaunchedEffect(service.name) { if (hosts == null) onLoadHosts() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.CloudQueue, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            Text(service.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow(ctx.getString(R.string.admin_services_ip_addresses), service.addrs.joinToString("\n").ifBlank { "—" })
                    DetailRow(ctx.getString(R.string.admin_services_exposed_ports), service.ports.joinToString(", ").ifBlank { "—" })
                    DetailRow(ctx.getString(R.string.admin_services_comment), service.comment?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.admin_services_no_comment))
                    if (service.tags.isNotEmpty()) DetailRow(ctx.getString(R.string.admin_services_tags), service.tags.joinToString(", "))
                }
            }

            Text(ctx.getString(R.string.admin_services_hosting_devices), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))

            val list = hosts?.value
            when {
                hosts != null && list == null && hosts.error != null -> LoadProblems(hosts, onLoadHosts)
                list == null -> Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
                list.isEmpty() -> Text(ctx.getString(R.string.admin_services_no_hosts), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                else -> Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { host ->
                            val device = allDevices.find { it.nodeId == host.stableNodeID || it.id == host.stableNodeID }
                            val name = device?.shortName ?: host.stableNodeID
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .toggleable(value = host.isApproved, enabled = canWrite, role = Role.Switch) { onSetHost(host.stableNodeID, name, it) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(
                                        ctx.getString(R.string.admin_services_host_level, host.approvalLevel ?: "—", host.configured ?: "—"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Switch(checked = host.isApproved, onCheckedChange = null, enabled = canWrite)
                            }
                        }
                    }
                }
            }
        }
    }
}
