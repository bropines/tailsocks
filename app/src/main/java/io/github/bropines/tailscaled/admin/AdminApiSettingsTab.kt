package io.github.bropines.tailscaled.admin
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@Composable
fun TailnetSettingsTabContent(
    settings: TailnetSettings?,
    onApplySettings: (TailnetSettings) -> Unit,
    onManageKeysClick: () -> Unit,
    onBillingClick: () -> Unit
) {
    if (settings == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // A menu popup opens its own window whose LocalContext ignores the app
    // locale, so its strings are resolved through this parent context instead —
    // see wrapContextWithLocale().
    val ctx = LocalContext.current

    var devicesApproval by remember(settings) { mutableStateOf(settings.devicesApprovalOn == true) }
    var usersApproval by remember(settings) { mutableStateOf(settings.usersApprovalOn == true) }
    var autoUpdates by remember(settings) { mutableStateOf(settings.devicesAutoUpdatesOn == true) }
    var keyDurationDays by remember(settings) { mutableIntStateOf(settings.devicesKeyDurationDays ?: 180) }

    var networkFlowLogging by remember(settings) { mutableStateOf(settings.networkFlowLoggingOn == true) }
    var regionalRouting by remember(settings) { mutableStateOf(settings.regionalRoutingOn == true) }
    var postureIdentityCollection by remember(settings) { mutableStateOf(settings.postureIdentityCollectionOn == true) }
    var allowedExternalJoinRole by remember(settings) { mutableStateOf(settings.usersRoleAllowedToJoinExternalTailnets ?: "admin") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onManageKeysClick() }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.admin_settings_auth_keys_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.admin_settings_auth_keys_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }

        // Billing removed and moved to Web Links tab
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.admin_settings_key_expiry_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.admin_settings_key_expiry_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // The label yields to the button, which cannot shrink.
                    Text(
                        stringResource(R.string.admin_settings_duration_days),
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))

                    var expandedDropdown by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expandedDropdown = true }) {
                            Text(
                                stringResource(R.string.admin_settings_days_option, keyDurationDays),
                                maxLines = 1,
                                softWrap = false
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(
                            expanded = expandedDropdown,
                            onDismissRequest = { expandedDropdown = false }
                        ) {
                            listOf(1, 7, 30, 90, 180).forEach { days ->
                                DropdownMenuItem(
                                    text = { Text(ctx.getString(R.string.admin_settings_days_option, days)) },
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
                Text(stringResource(R.string.admin_settings_access_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { devicesApproval = !devicesApproval },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_settings_device_approval_title), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.admin_settings_device_approval_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
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
                        Text(stringResource(R.string.admin_settings_user_approval_title), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.admin_settings_user_approval_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = usersApproval, onCheckedChange = { usersApproval = it })
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.admin_settings_software_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { autoUpdates = !autoUpdates },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_settings_auto_updates_title), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.admin_settings_auto_updates_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = autoUpdates, onCheckedChange = { autoUpdates = it })
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.admin_settings_network_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { networkFlowLogging = !networkFlowLogging },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_settings_flow_logging_title), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.admin_settings_flow_logging_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = networkFlowLogging, onCheckedChange = { networkFlowLogging = it })
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { regionalRouting = !regionalRouting },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_settings_regional_routing_title), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.admin_settings_regional_routing_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = regionalRouting, onCheckedChange = { regionalRouting = it })
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { postureIdentityCollection = !postureIdentityCollection },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_settings_posture_title), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.admin_settings_posture_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = postureIdentityCollection, onCheckedChange = { postureIdentityCollection = it })
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.admin_settings_external_tailnets_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.admin_settings_external_tailnets_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // The label yields to the button, which cannot shrink.
                    Text(
                        stringResource(R.string.admin_settings_allowed_role),
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))

                    var expandedRoleDropdown by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expandedRoleDropdown = true }) {
                            Text(
                                allowedExternalJoinRole.uppercase(),
                                maxLines = 1,
                                softWrap = false
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(
                            expanded = expandedRoleDropdown,
                            onDismissRequest = { expandedRoleDropdown = false }
                        ) {
                            listOf("none", "admin", "member").forEach { role ->
                                DropdownMenuItem(
                                    text = { Text(role.uppercase()) },
                                    onClick = {
                                        allowedExternalJoinRole = role
                                        expandedRoleDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        val hasChanges = devicesApproval != (settings.devicesApprovalOn == true) ||
                usersApproval != (settings.usersApprovalOn == true) ||
                autoUpdates != (settings.devicesAutoUpdatesOn == true) ||
                keyDurationDays != (settings.devicesKeyDurationDays ?: 180) ||
                networkFlowLogging != (settings.networkFlowLoggingOn == true) ||
                regionalRouting != (settings.regionalRoutingOn == true) ||
                postureIdentityCollection != (settings.postureIdentityCollectionOn == true) ||
                allowedExternalJoinRole != (settings.usersRoleAllowedToJoinExternalTailnets ?: "admin")

        Button(
            onClick = {
                val updated = TailnetSettings(
                    aclsExternallyManagedOn = settings.aclsExternallyManagedOn,
                    aclsExternalLink = settings.aclsExternalLink,
                    devicesApprovalOn = devicesApproval,
                    devicesAutoUpdatesOn = autoUpdates,
                    devicesKeyDurationDays = keyDurationDays,
                    usersApprovalOn = usersApproval,
                    usersRoleAllowedToJoinExternalTailnets = allowedExternalJoinRole,
                    networkFlowLoggingOn = networkFlowLogging,
                    regionalRoutingOn = regionalRouting,
                    postureIdentityCollectionOn = postureIdentityCollection
                )
                onApplySettings(updated)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = hasChanges,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Done, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.admin_settings_apply))
        }
    }
}
