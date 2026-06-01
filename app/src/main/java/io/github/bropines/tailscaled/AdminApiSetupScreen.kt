package io.github.bropines.tailscaled

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
                title = { Text("Admin API Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                text = "Tailnet Not Detected",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "TailSocks must be connected to the VPN at least once to automatically detect your Tailnet domain.\n\nAlternatively, you can specify your Tailnet domain manually below:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = enteredTailnet,
                onValueChange = { enteredTailnet = it },
                label = { Text("Tailnet Domain Name") },
                placeholder = { Text("e.g. taila1b2.ts.net") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    if (enteredTailnet.isBlank()) {
                        Toast.makeText(context, "Tailnet domain is required", Toast.LENGTH_SHORT).show()
                    } else {
                        onSaveTailnet(enteredTailnet.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("Set Tailnet Name")
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
                title = { Text("Admin API Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                text = "Tailscale API Integration",
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
                        Text("Active Tailnet Domain", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Text(tailnet, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    }
                    TextButton(onClick = onResetTailnet) {
                        Text("Edit")
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
                    text = { Text("Personal Token", fontSize = 13.sp) }
                )
                Tab(
                    selected = authType == "OAUTH",
                    onClick = { authType = "OAUTH" },
                    text = { Text("OAuth Client", fontSize = 13.sp) }
                )
            }

            if (authType == "TOKEN") {
                OutlinedTextField(
                    value = enteredToken,
                    onValueChange = { enteredToken = it },
                    label = { Text("API Access Token (tskey-api-...)") },
                    placeholder = { Text("tskey-api-XXXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            } else {
                OutlinedTextField(
                    value = enteredClientId,
                    onValueChange = { enteredClientId = it },
                    label = { Text("OAuth Client ID") },
                    placeholder = { Text("e.g. cKXXXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = enteredClientSecret,
                    onValueChange = { enteredClientSecret = it },
                    label = { Text("OAuth Client Secret (tskey-client-...)") },
                    placeholder = { Text("tskey-client-XXXXX") },
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
                            Text("API Proxy Configuration", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(
                            imageVector = if (isProxyExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }

                    if (isProxyExpanded) {
                        Spacer(Modifier.height(12.dp))

                        // Proxy Mode selector
                        Text("Proxy Mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("DIRECT" to "Direct (No Proxy)", "LOCAL_SOCKS5" to "Tailsocks SOCKS5 Proxy", "CUSTOM_SOCKS5" to "Custom SOCKS5 Proxy").forEach { (modeVal, labelText) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { proxyMode = modeVal },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = proxyMode == modeVal, onClick = { proxyMode = modeVal })
                                    Text(labelText, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        if (proxyMode == "CUSTOM_SOCKS5") {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyHost,
                                onValueChange = { proxyHost = it },
                                label = { Text("SOCKS5 Host") },
                                placeholder = { Text("e.g. 192.168.1.100") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyPort,
                                onValueChange = { proxyPort = it },
                                label = { Text("SOCKS5 Port") },
                                placeholder = { Text("e.g. 1080") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyUser,
                                onValueChange = { proxyUser = it },
                                label = { Text("Proxy Username (Optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyPass,
                                onValueChange = { proxyPass = it },
                                label = { Text("Proxy Password (Optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                        } else if (proxyMode == "LOCAL_SOCKS5") {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Routes API calls through the active Socks5 server. Useful if you want to route API calls through your Exit Node.",
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
                        Toast.makeText(context, "API Access Token is required", Toast.LENGTH_SHORT).show()
                    } else if (authType == "OAUTH" && (enteredClientId.isBlank() || enteredClientSecret.isBlank())) {
                        Toast.makeText(context, "Client ID and Client Secret are required", Toast.LENGTH_SHORT).show()
                    } else if (proxyMode == "CUSTOM_SOCKS5" && (proxyHost.isBlank() || portVal <= 0)) {
                        Toast.makeText(context, "Valid SOCKS5 host and port are required", Toast.LENGTH_SHORT).show()
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
                Text("Save & Connect")
            }
        }
    }
}
