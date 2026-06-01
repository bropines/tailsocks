package io.github.bropines.tailscaled

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TailnetSettingsTabContent(
    settings: TailnetSettings?,
    onApplySettings: (TailnetSettings) -> Unit
) {
    if (settings == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var devicesApproval by remember(settings) { mutableStateOf(settings.devicesApprovalOn == true) }
    var usersApproval by remember(settings) { mutableStateOf(settings.usersApprovalOn == true) }
    var autoUpdates by remember(settings) { mutableStateOf(settings.devicesAutoUpdatesOn == true) }
    var keyDurationDays by remember(settings) { mutableIntStateOf(settings.devicesKeyDurationDays ?: 180) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Default Key Expiry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Set the default expiration time for device keys in this tailnet. Value must be between 1 and 180 days.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Duration (Days)", fontWeight = FontWeight.Medium)

                    var expandedDropdown by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expandedDropdown = true }) {
                            Text("$keyDurationDays Days")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(
                            expanded = expandedDropdown,
                            onDismissRequest = { expandedDropdown = false }
                        ) {
                            listOf(1, 7, 30, 90, 180).forEach { days ->
                                DropdownMenuItem(
                                    text = { Text("$days Days") },
                                    onClick = {
                                        keyDurationDays = days
                                        expandedDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Access & Approval Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { devicesApproval = !devicesApproval },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Device Approval Required", fontWeight = FontWeight.Medium)
                        Text("New devices must be approved by an administrator before they can join.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = devicesApproval, onCheckedChange = { devicesApproval = it })
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { usersApproval = !usersApproval },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("User Approval Required", fontWeight = FontWeight.Medium)
                        Text("New members require manual approval from owners or admins to join.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = usersApproval, onCheckedChange = { usersApproval = it })
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Device Software Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { autoUpdates = !autoUpdates },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic Updates", fontWeight = FontWeight.Medium)
                        Text("Enable Tailscale to auto-update on devices belonging to this tailnet.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = autoUpdates, onCheckedChange = { autoUpdates = it })
                }
            }
        }

        val hasChanges = devicesApproval != (settings.devicesApprovalOn == true) ||
                usersApproval != (settings.usersApprovalOn == true) ||
                autoUpdates != (settings.devicesAutoUpdatesOn == true) ||
                keyDurationDays != (settings.devicesKeyDurationDays ?: 180)

        Button(
            onClick = {
                val updated = TailnetSettings(
                    aclsExternallyManagedOn = settings.aclsExternallyManagedOn,
                    aclsExternalLink = settings.aclsExternalLink,
                    devicesApprovalOn = devicesApproval,
                    devicesAutoUpdatesOn = autoUpdates,
                    devicesKeyDurationDays = keyDurationDays,
                    usersApprovalOn = usersApproval,
                    usersRoleAllowedToJoinExternalTailnets = settings.usersRoleAllowedToJoinExternalTailnets,
                    networkFlowLoggingOn = settings.networkFlowLoggingOn,
                    regionalRoutingOn = settings.regionalRoutingOn
                )
                onApplySettings(updated)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = hasChanges,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Done, null)
            Spacer(Modifier.width(8.dp))
            Text("Apply Settings")
        }
    }
}
