package io.github.bropines.tailscaled.admin
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminApiNoTailnetScreen(
    onBack: () -> Unit,
    onSaveTailnet: (String) -> Unit
) {
    val context = LocalContext.current
    var enteredTailnet by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.admin_cd_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(80.dp)
            )

            Text(
                text = stringResource(R.string.admin_setup_no_tailnet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(R.string.admin_setup_no_tailnet_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = enteredTailnet,
                onValueChange = { enteredTailnet = it },
                label = { Text(stringResource(R.string.admin_setup_tailnet_label)) },
                placeholder = { Text(stringResource(R.string.admin_setup_tailnet_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    if (enteredTailnet.isBlank()) {
                        Toast.makeText(context, context.getString(R.string.admin_setup_tailnet_required), Toast.LENGTH_SHORT).show()
                    } else {
                        onSaveTailnet(enteredTailnet.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.admin_setup_set_tailnet_name))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminApiSetupScreen(
    tailnet: String,
    initialAuthType: String,
    initialToken: String,
    initialClientId: String,
    initialClientSecret: String,
    initialProxyMode: String,
    initialProxyHost: String,
    initialProxyPort: Int,
    initialProxyUser: String,
    initialProxyPass: String,
    onBack: () -> Unit,
    onSave: (String, String, String, String, String, String, Int, String, String) -> Unit,
    onResetTailnet: () -> Unit
) {
    val context = LocalContext.current
    var authType by remember { mutableStateOf(initialAuthType) }
    var enteredToken by remember { mutableStateOf(initialToken) }
    var enteredClientId by remember { mutableStateOf(initialClientId) }
    var enteredClientSecret by remember { mutableStateOf(initialClientSecret) }

    // Proxy State
    var proxyMode by remember { mutableStateOf(initialProxyMode) }
    var proxyHost by remember { mutableStateOf(initialProxyHost) }
    var proxyPort by remember { mutableStateOf(if (initialProxyPort > 0) initialProxyPort.toString() else "") }
    var proxyUser by remember { mutableStateOf(initialProxyUser) }
    var proxyPass by remember { mutableStateOf(initialProxyPass) }
    var isProxyExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.admin_cd_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AdminPanelSettings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )

            Text(
                text = stringResource(R.string.admin_setup_integration_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.admin_setup_active_tailnet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Text(tailnet, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    }
                    TextButton(onClick = onResetTailnet) {
                        Text(stringResource(R.string.action_edit))
                    }
                }
            }

            // Auth Type TabRow
            TabRow(
                selectedTabIndex = if (authType == "TOKEN") 0 else 1,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            ) {
                Tab(
                    selected = authType == "TOKEN",
                    onClick = { authType = "TOKEN" },
                    text = { Text(stringResource(R.string.admin_setup_tab_token), fontSize = 13.sp) }
                )
                Tab(
                    selected = authType == "OAUTH",
                    onClick = { authType = "OAUTH" },
                    text = { Text(stringResource(R.string.admin_setup_tab_oauth), fontSize = 13.sp) }
                )
            }

            if (authType == "TOKEN") {
                OutlinedTextField(
                    value = enteredToken,
                    onValueChange = { enteredToken = it },
                    label = { Text(stringResource(R.string.admin_setup_token_label)) },
                    placeholder = { Text(stringResource(R.string.admin_setup_token_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            } else {
                OutlinedTextField(
                    value = enteredClientId,
                    onValueChange = { enteredClientId = it },
                    label = { Text(stringResource(R.string.admin_setup_client_id_label)) },
                    placeholder = { Text(stringResource(R.string.admin_setup_client_id_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = enteredClientSecret,
                    onValueChange = { enteredClientSecret = it },
                    label = { Text(stringResource(R.string.admin_setup_client_secret_label)) },
                    placeholder = { Text(stringResource(R.string.admin_setup_client_secret_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Advanced Proxy Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { isProxyExpanded = !isProxyExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.admin_proxy_config_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(
                            imageVector = if (isProxyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }

                    if (isProxyExpanded) {
                        Spacer(Modifier.height(12.dp))

                        // Proxy Mode selector
                        Text(stringResource(R.string.admin_proxy_mode_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val proxyOptions = listOf(
                                "CONTROL_PLANE" to stringResource(R.string.admin_proxy_control_plane),
                                "DIRECT" to stringResource(R.string.admin_proxy_direct),
                                "LOCAL_SOCKS5" to stringResource(R.string.admin_proxy_local_socks5),
                                "CUSTOM_SOCKS5" to stringResource(R.string.admin_proxy_custom_socks5)
                            )
                            proxyOptions.forEach { (modeVal, labelText) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { proxyMode = modeVal },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = proxyMode == modeVal, onClick = { proxyMode = modeVal })
                                    Text(labelText, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        if (proxyMode == "CONTROL_PLANE") {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.admin_proxy_control_plane_desc),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (proxyMode == "CUSTOM_SOCKS5") {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyHost,
                                onValueChange = { proxyHost = it },
                                label = { Text(stringResource(R.string.admin_proxy_socks5_host)) },
                                placeholder = { Text(stringResource(R.string.admin_proxy_socks5_host_placeholder)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyPort,
                                onValueChange = { proxyPort = it },
                                label = { Text(stringResource(R.string.admin_proxy_socks5_port)) },
                                placeholder = { Text(stringResource(R.string.admin_proxy_socks5_port_placeholder)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyUser,
                                onValueChange = { proxyUser = it },
                                label = { Text(stringResource(R.string.admin_proxy_username_optional)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyPass,
                                onValueChange = { proxyPass = it },
                                label = { Text(stringResource(R.string.admin_proxy_password_optional)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                        } else if (proxyMode == "LOCAL_SOCKS5") {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.admin_proxy_local_desc_setup),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    val portVal = proxyPort.toIntOrNull() ?: 0
                    if (authType == "TOKEN" && enteredToken.isBlank()) {
                        Toast.makeText(context, context.getString(R.string.admin_setup_token_required), Toast.LENGTH_SHORT).show()
                    } else if (authType == "OAUTH" && (enteredClientId.isBlank() || enteredClientSecret.isBlank())) {
                        Toast.makeText(context, context.getString(R.string.admin_setup_oauth_required), Toast.LENGTH_SHORT).show()
                    } else if (proxyMode == "CUSTOM_SOCKS5" && (proxyHost.isBlank() || portVal <= 0)) {
                        Toast.makeText(context, context.getString(R.string.admin_proxy_socks5_required), Toast.LENGTH_SHORT).show()
                    } else {
                        onSave(
                            authType,
                            enteredToken.trim(),
                            enteredClientId.trim(),
                            enteredClientSecret.trim(),
                            proxyMode,
                            proxyHost.trim(),
                            portVal,
                            proxyUser.trim(),
                            proxyPass.trim()
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Save, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.admin_setup_save_connect))
            }
        }
    }
}
