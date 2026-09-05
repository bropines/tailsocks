package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import appctr.Appctr

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlProxyDialog(onDismiss: () -> Unit, onApply: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(GlobalSettings.isCPProxyEnabled(context)) }
    var type by remember { mutableStateOf(GlobalSettings.getCPField(context, "type", "SOCKS5")) }
    var host by remember { mutableStateOf(GlobalSettings.getCPField(context, "host")) }
    var port by remember { mutableStateOf(GlobalSettings.getCPField(context, "port")) }
    var user by remember { mutableStateOf(GlobalSettings.getCPField(context, "user")) }
    var pass by remember { mutableStateOf(GlobalSettings.getCPField(context, "pass")) }

    var importUri by remember { mutableStateOf("") }
    var presets by remember { mutableStateOf(GlobalSettings.getCPPresets(context)) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_control_proxy_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_control_proxy_enable), Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Spacer(Modifier.height(16.dp))

                if (enabled) {
                    // Import Section
                    OutlinedTextField(
                        value = importUri,
                        onValueChange = { importUri = it },
                        label = { Text(stringResource(R.string.settings_proxy_import)) },
                        placeholder = { Text("socks5://user:pass@host:port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(10.dp),
                        trailingIcon = {
                            IconButton(onClick = {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipData = clipboardManager.primaryClip
                                val clipboardText = if (clipData != null && clipData.itemCount > 0) {
                                    clipData.getItemAt(0).text?.toString()?.trim()
                                } else null

                                val linkToParse = if (!clipboardText.isNullOrEmpty()) {
                                    importUri = clipboardText
                                    clipboardText
                                } else {
                                    importUri.trim()
                                }

                                if (linkToParse.isNotEmpty()) {
                                    val parsed = GlobalSettings.parseProxyUri(linkToParse)
                                    if (parsed != null) {
                                        type = parsed["type"] ?: "SOCKS5"
                                        host = parsed["host"] ?: ""
                                        port = parsed["port"] ?: ""
                                        user = parsed["user"] ?: ""
                                        pass = parsed["pass"] ?: ""
                                        importUri = ""
                                        Toast.makeText(context, context.getString(R.string.settings_proxy_parsed_success), Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.settings_proxy_import_error), Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, context.getString(R.string.settings_proxy_clipboard_empty), Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.settings_proxy_cd_paste_parse))
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InputChip(
                            selected = false,
                            onClick = {
                                if (host.isNotEmpty() && port.isNotEmpty()) {
                                    presetName = "$host:$port"
                                    showSavePresetDialog = true
                                } else {
                                    Toast.makeText(context, context.getString(R.string.settings_proxy_preset_fill_fields_error), Toast.LENGTH_SHORT).show()
                                }
                            },
                            label = { Text(stringResource(R.string.settings_proxy_preset_add_chip)) },
                            leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) }
                        )

                        presets.forEach { preset ->
                            InputChip(
                                selected = false,
                                onClick = {
                                    type = preset.type
                                    host = preset.host
                                    port = preset.port
                                    user = preset.user
                                    pass = preset.pass
                                },
                                label = { Text(preset.name) },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            val updated = presets.filter { it != preset }
                                            presets = updated
                                            GlobalSettings.saveCPPresets(context, updated)
                                        },
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp))
                                    }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_control_proxy_type), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        val proxyTypes = listOf("SOCKS5", "HTTP", "HTTPS")
                        val selectedProxyTypeIdx = proxyTypes.indexOf(type).coerceAtLeast(0)
                        SlidingSegmentedChips(
                            options = proxyTypes,
                            selectedIndex = selectedProxyTypeIdx,
                            onOptionSelected = { idx -> type = proxyTypes[idx] },
                            modifier = Modifier.fillMaxWidth(),
                            height = 36.dp
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.settings_control_proxy_host)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, maxLines = 1, shape = RoundedCornerShape(10.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { newValue ->
                            val digits = newValue.filter { it.isDigit() }
                            if (digits.length <= 5) {
                                val num = digits.toIntOrNull()
                                if (num == null || num <= 65535) {
                                    port = digits
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.settings_control_proxy_port)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(10.dp),
                        placeholder = { Text(if (type == "SOCKS5") "1080" else "8080") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text(stringResource(R.string.settings_control_proxy_username)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, maxLines = 1, shape = RoundedCornerShape(10.dp))
                    OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text(stringResource(R.string.settings_control_proxy_password)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, maxLines = 1, shape = RoundedCornerShape(10.dp))

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            val uri = GlobalSettings.buildProxyUri(type, host, port, user, pass)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Proxy URI", uri))
                            Toast.makeText(context, context.getString(R.string.settings_proxy_copied), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Share, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_proxy_copy_btn))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                GlobalSettings.setCPProxyEnabled(context, enabled)
                GlobalSettings.setCPField(context, "type", type)
                GlobalSettings.setCPField(context, "host", host)
                GlobalSettings.setCPField(context, "port", port)
                GlobalSettings.setCPField(context, "user", user)
                GlobalSettings.setCPField(context, "pass", pass)
                onApply()
                onDismiss()
            }) { Text(stringResource(R.string.settings_proxy_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )

    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text(stringResource(R.string.settings_proxy_preset_save_title)) },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.settings_proxy_preset_name_label)) },
                    singleLine = true,
                    maxLines = 1,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nameToSave = presetName.trim().ifEmpty { "$host:$port" }
                        val newPreset = GlobalSettings.ProxyPreset(
                            name = nameToSave,
                            type = type,
                            host = host,
                            port = port,
                            user = user,
                            pass = pass
                        )
                        val updated = presets + newPreset
                        presets = updated
                        GlobalSettings.saveCPPresets(context, updated)
                        showSavePresetDialog = false
                    }
                ) {
                    Text(stringResource(R.string.settings_proxy_preset_save_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun SettingsClickableItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.3f else 0.1f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline) },
            supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline) },
            leadingContent = { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { if (enabled) onCheckedChange(!checked) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.3f else 0.1f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline) },
            supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline) },
            leadingContent = { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
            trailingContent = { Switch(checked = checked, onCheckedChange = if (enabled) onCheckedChange else null, enabled = enabled) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
}

@Composable
fun SettingsEditItem(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    placeholder: String = "",
    description: String = "",
    enabled: Boolean = true,
    suggestions: List<String> = emptyList(),
    onAction: (() -> String)? = null,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onSave: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(showDialog) { if (showDialog) text = value }
    val notSet = stringResource(R.string.settings_not_set)
    Surface(
        onClick = { if (enabled) showDialog = true },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.3f else 0.15f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
            supportingContent = {
                Text(
                    text = if (!enabled && description.isNotEmpty()) description else if (value.isEmpty()) {
                        if (description.isNotEmpty()) description else (placeholder.ifEmpty { notSet })
                    } else value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            },
            leadingContent = { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) },
            trailingContent = if (!enabled) { { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(18.dp)) } } else null,
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(10.dp),
                        label = { if (placeholder.isNotEmpty()) Text(stringResource(R.string.settings_field_example, placeholder)) },
                        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
                        trailingIcon = if (onAction != null && actionIcon != null) {
                            { IconButton(onClick = { text = onAction() }) { Icon(actionIcon, null) } }
                        } else null
                    )
                    if (suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.settings_suggested), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            suggestions.forEach { tag ->
                                val cleanTag = if (tag.startsWith("tag:")) tag else "tag:$tag"
                                val isSelected = text.split(",").map { it.trim() }.contains(cleanTag)

                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val currentTags = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                                        if (currentTags.contains(cleanTag)) {
                                            currentTags.remove(cleanTag)
                                        } else {
                                            currentTags.add(cleanTag)
                                        }
                                        text = currentTags.joinToString(", ")
                                    },
                                    label = { Text(tag) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { onSave(text); showDialog = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsExitNodeItem(
    title: String,
    currentId: String,
    currentIp: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onSave: (String, String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var exitNodes by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun applyExitNode(id: String, ip: String) {
        onSave(id, ip)
        scope.launch(Dispatchers.IO) {
            // The choice is saved regardless; only push it live when the daemon
            // is up. Calling setPrefs while stopped surfaced a LocalAPI error for
            // a change that was in fact stored fine.
            if (Appctr.isRunning()) {
                val prefsJson = "{\"ExitNodeID\": \"$id\", \"ExitNodeIDSet\": true}"
                val res = Appctr.setPrefs(prefsJson)
                if (res != "OK") {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, context.getString(R.string.settings_local_api_error_format, res), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } else if (ProxyState.isActualRunning(context)) {
                TailscaledService.requestApplySettings(context)
            }
            updateAllWidgets(context)
            if (GlobalSettings.isTunModeEnabled(context)) {
                context.startService(Intent(context, TunVpnService::class.java).apply {
                    action = TunVpnService.ACTION_START
                })
            }
        }
    }

    Surface(
        onClick = {
            showDialog = true
            isLoading = true
            scope.launch(Dispatchers.IO) {
                try {
                    val pJson = Appctr.getStatusFromAPI()
                    if (!pJson.startsWith("Error") && pJson.isNotBlank()) {
                        val status = AppJson.decodeFromString<StatusResponse>(pJson)
                        val nodes = status.peers?.values?.filter { it.exitNodeOption == true }?.toList() ?: emptyList()
                        withContext(Dispatchers.Main) { exitNodes = nodes }
                    }
                } catch (e: Exception) {}
                withContext(Dispatchers.Main) { isLoading = false }
            }
        },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(if (currentIp.isEmpty()) "None" else currentIp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }

    if (showDialog) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val maxHeight = (configuration.screenHeightDp * 0.85f).dp

        ModalBottomSheet(onDismissRequest = { showDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .navigationBarsPadding()
            ) {
                Text(
                    stringResource(R.string.settings_exit_node_select),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (isLoading) {
                    Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { CircularProgressIndicator() }
                } else if (exitNodes.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) {
                        Text(stringResource(R.string.settings_exit_node_empty), color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                item {
                                    val isSelected = currentIp.isEmpty()
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                            .clickable { applyExitNode("", ""); showDialog = false },
                                        shape = RoundedCornerShape(14.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                        ),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Clear,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(Modifier.width(16.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    stringResource(R.string.settings_none),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    stringResource(R.string.main_route_traffic_directly),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                        }
                                    }
                                }

                                items(exitNodes.size) { i ->
                                    val node = exitNodes[i]
                                    val isSelected = node.id == currentId || node.getPrimaryIp() == currentIp
                                    val (osIcon, osColor) = getOsVisuals(node.os).let { (icon, color) ->
                                        if (icon == Icons.Default.Devices) Icons.Default.VpnKey to MaterialTheme.colorScheme.primary
                                        else icon to color
                                    }
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                            .clickable { applyExitNode(node.id ?: "", node.getPrimaryIp()); showDialog = false },
                                        shape = RoundedCornerShape(14.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                        ),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                        else osColor.copy(alpha = 0.12f)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    osIcon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else osColor
                                                )
                                            }
                                            Spacer(Modifier.width(16.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    node.getDisplayName(),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    node.getPrimaryIp(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CopyablePathItem(
    label: String,
    path: String,
    context: Context
) {
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setPrimaryClip(ClipData.newPlainText(label, path))
                Toast.makeText(context, context.getString(R.string.copied_to_clipboard, label), Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.settings_copy_cd_format, label),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
        }
        Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
