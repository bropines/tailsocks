package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.TailcatService
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TailcatActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TailSocksTheme { TailcatScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailcatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by TailcatService.status.collectAsState()
    val log by TailcatService.log.collectAsState()
    val running = status.state == TailcatService.State.RUNNING

    var address by remember { mutableStateOf(GlobalSettings.getString(context, TailcatService.PREF_ADDRESS, "")) }
    var ports by remember { mutableStateOf(GlobalSettings.getString(context, TailcatService.PREF_PORTS, "")) }
    var clientKey by remember { mutableStateOf(GlobalSettings.getString(context, TailcatService.PREF_CLIENT_KEY, "")) }
    // The address is what lets a client in: hidden unless asked for.
    var showAddress by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }

    val portsValid = TailcatService.parsePorts(ports) != null
    val addressValid = address.isNotBlank() && address.trim().none { it.isWhitespace() }

    fun copy(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, R.string.tailcat_copied, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tailcat_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HelpText(stringResource(R.string.tailcat_intro), lines = 3)

            ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(stringResource(R.string.tailcat_address)) },
                        placeholder = { Text("tc…") },
                        singleLine = true,
                        enabled = !running,
                        isError = address.isNotEmpty() && !addressValid,
                        visualTransformation = if (showAddress) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showAddress = !showAddress }) {
                                Icon(if (showAddress) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ports,
                        onValueChange = { ports = it },
                        label = { Text(stringResource(R.string.tailcat_ports)) },
                        placeholder = { Text("8080") },
                        supportingText = { Text(stringResource(R.string.tailcat_ports_hint)) },
                        singleLine = true,
                        enabled = !running,
                        isError = ports.isNotEmpty() && !portsValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )

                    val statusText = when {
                        running -> stringResource(R.string.tailcat_status_running, status.detail)
                        status.detail.isNotEmpty() -> status.detail
                        else -> stringResource(R.string.tailcat_status_stopped)
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (running) MaterialTheme.colorScheme.primary
                                else if (status.detail.isNotEmpty()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            if (running) {
                                TailcatService.stop(context)
                            } else {
                                GlobalSettings.setString(context, TailcatService.PREF_ADDRESS, address.trim())
                                GlobalSettings.setString(context, TailcatService.PREF_PORTS, ports.trim())
                                TailcatService.start(context)
                            }
                        },
                        enabled = running || (addressValid && portsValid),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(if (running) R.string.tailcat_stop else R.string.tailcat_start))
                    }
                }
            }

            ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.tailcat_client_key), style = MaterialTheme.typography.titleSmall)
                    HelpText(stringResource(R.string.tailcat_client_key_desc))
                    if (clientKey.isNotEmpty()) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                clientKey,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { copy("tailcat client key", clientKey) }) {
                                Icon(Icons.Default.ContentCopy, stringResource(R.string.tailcat_copy))
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                generating = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { TailcatService.generateClientKey(context) }
                                    generating = false
                                    result.onSuccess { clientKey = it }
                                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                }
                            },
                            enabled = !generating,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.tailcat_client_key_create))
                        }
                    }
                }
            }

            if (log.isNotEmpty()) {
                ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.tailcat_output), style = MaterialTheme.typography.titleSmall)
                        Text(
                            log.takeLast(40).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
