package io.github.bropines.tailscaled.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.bropines.tailscaled.R

@Composable
fun ServicesTabContent(
    services: List<VIPServiceInfo>,
    onServiceClick: (VIPServiceInfo) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.secondary)
                Text(
                    text = stringResource(R.string.admin_services_info),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp
                )
            }
        }

        if (services.isEmpty()) {
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.admin_services_no_services), color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(services) { service ->
                    ServiceRow(service = service, onClick = { onServiceClick(service) })
                }
            }
        }
    }
}

@Composable
fun ServiceRow(service: VIPServiceInfo, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CloudQueue, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    service.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    service.addrs?.firstOrNull() ?: stringResource(R.string.admin_services_no_ip),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!service.ports.isNullOrEmpty()) {
                    Text(
                        service.ports.joinToString(", "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceDetailBottomSheet(
    service: VIPServiceInfo,
    client: TailscaleApiClient,
    allDevices: List<ApiDevice>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var hosts by remember { mutableStateOf<List<ServiceHostInfo>>(emptyList()) }
    var isLoadingHosts by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(service.name) {
        scope.launch(Dispatchers.IO) {
            try {
                val list = client.listServiceHosts(service.name)
                withContext(Dispatchers.Main) {
                    hosts = list
                    isLoadingHosts = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingHosts = false
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CloudQueue, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }

            Text(
                service.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow(stringResource(R.string.admin_services_ip_addresses), service.addrs?.joinToString("\n") ?: "N/A")
                    DetailRow(stringResource(R.string.admin_services_exposed_ports), service.ports?.joinToString(", ") ?: "N/A")
                    DetailRow(stringResource(R.string.admin_services_comment), service.comment ?: stringResource(R.string.admin_services_no_comment))
                    if (!service.tags.isNullOrEmpty()) {
                        DetailRow(stringResource(R.string.admin_services_tags), service.tags.joinToString(", "))
                    }
                }
            }

            Text(
                stringResource(R.string.admin_services_hosting_devices),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )

            if (isLoadingHosts) {
                Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (hosts.isEmpty()) {
                Text(
                    stringResource(R.string.admin_services_no_hosts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        hosts.forEach { host ->
                            val matchedDevice = allDevices.find { it.id == host.stableNodeID || it.hostname == host.stableNodeID }
                            val deviceName = matchedDevice?.getDisplayName() ?: host.stableNodeID

                            val isApproved = host.approvalLevel?.startsWith("approved") == true

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(deviceName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(
                                        stringResource(R.string.admin_services_host_level, host.approvalLevel ?: "unknown", host.configured ?: "unknown"),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }

                                Switch(
                                    checked = isApproved,
                                    onCheckedChange = { approved ->
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                client.setServiceDeviceApproved(service.name, host.stableNodeID, approved)
                                                val updatedList = client.listServiceHosts(service.name)
                                                withContext(Dispatchers.Main) {
                                                    hosts = updatedList
                                                }
                                            } catch (e: Exception) {}
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
