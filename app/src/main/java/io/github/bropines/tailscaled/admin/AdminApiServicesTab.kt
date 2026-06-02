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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ServicesTabContent(
    services: List<VIPServiceInfo>,
    onServiceClick: (VIPServiceInfo) -> Unit
) {
    if (services.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No virtual services found", color = MaterialTheme.colorScheme.outline)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(services) { service ->
                ServiceRow(service = service, onClick = { onServiceClick(service) })
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
                    service.addrs?.firstOrNull() ?: "No IP",
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
                    DetailRow("IP Addresses", service.addrs?.joinToString("\n") ?: "N/A")
                    DetailRow("Exposed Ports", service.ports?.joinToString(", ") ?: "N/A")
                    DetailRow("Comment", service.comment ?: "No comment")
                    if (!service.tags.isNullOrEmpty()) {
                        DetailRow("Service Tags", service.tags.joinToString(", "))
                    }
                }
            }

            Text(
                "Hosting Devices & Approvals",
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
                    "No devices are configured or requesting to host this service.",
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
                                        "Level: ${host.approvalLevel ?: "unknown"} • Config: ${host.configured ?: "unknown"}",
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
