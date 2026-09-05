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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxySettingsDialog(
    initialProxyMode: String,
    initialProxyHost: String,
    initialProxyPort: Int,
    initialProxyUser: String,
    initialProxyPass: String,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, String, String) -> Unit
) {
    val context = LocalContext.current
    var proxyMode by remember { mutableStateOf(initialProxyMode) }
    var proxyHost by remember { mutableStateOf(initialProxyHost) }
    var proxyPort by remember { mutableStateOf(if (initialProxyPort > 0) initialProxyPort.toString() else "") }
    var proxyUser by remember { mutableStateOf(initialProxyUser) }
    var proxyPass by remember { mutableStateOf(initialProxyPass) }

    // Strings resolved in the parent composition — see wrapContextWithLocale().
    val strAdminProxySettingsTitle = stringResource(R.string.admin_proxy_settings_title)
    val strAdminProxyControlPlane = stringResource(R.string.admin_proxy_control_plane)
    val strAdminProxyDirect = stringResource(R.string.admin_proxy_direct)
    val strAdminProxyLocalSocks5 = stringResource(R.string.admin_proxy_local_socks5)
    val strAdminProxyCustomSocks5 = stringResource(R.string.admin_proxy_custom_socks5)
    val strAdminProxyControlPlaneDesc = stringResource(R.string.admin_proxy_control_plane_desc)
    val strAdminProxySocks5Host = stringResource(R.string.admin_proxy_socks5_host)
    val strAdminProxySocks5HostPlaceholder = stringResource(R.string.admin_proxy_socks5_host_placeholder)
    val strAdminProxySocks5Port = stringResource(R.string.admin_proxy_socks5_port)
    val strAdminProxySocks5PortPlaceholder = stringResource(R.string.admin_proxy_socks5_port_placeholder)
    val strAdminProxyUsername = stringResource(R.string.admin_proxy_username)
    val strAdminProxyPassword = stringResource(R.string.admin_proxy_password)
    val strAdminProxyLocalDesc = stringResource(R.string.admin_proxy_local_desc)
    val strActionSave = stringResource(R.string.action_save)
    val strActionCancel = stringResource(R.string.action_cancel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strAdminProxySettingsTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val proxyOptions = listOf(
                    "CONTROL_PLANE" to strAdminProxyControlPlane,
                    "DIRECT" to strAdminProxyDirect,
                    "LOCAL_SOCKS5" to strAdminProxyLocalSocks5,
                    "CUSTOM_SOCKS5" to strAdminProxyCustomSocks5
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

                if (proxyMode == "CONTROL_PLANE") {
                    Text(
                        strAdminProxyControlPlaneDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else if (proxyMode == "CUSTOM_SOCKS5") {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it },
                        label = { Text(strAdminProxySocks5Host) },
                        placeholder = { Text(strAdminProxySocks5HostPlaceholder) },
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = { newValue ->
                            val digits = newValue.filter { it.isDigit() }
                            if (digits.length <= 5) {
                                val num = digits.toIntOrNull()
                                if (num == null || num <= 65535) {
                                    proxyPort = digits
                                }
                            }
                        },
                        label = { Text(strAdminProxySocks5Port) },
                        placeholder = { Text(strAdminProxySocks5PortPlaceholder) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proxyUser,
                        onValueChange = { proxyUser = it },
                        label = { Text(strAdminProxyUsername) },
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = proxyPass,
                        onValueChange = { proxyPass = it },
                        label = { Text(strAdminProxyPassword) },
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (proxyMode == "LOCAL_SOCKS5") {
                    Text(
                        strAdminProxyLocalDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portVal = proxyPort.toIntOrNull() ?: 0
                    if (proxyMode == "CUSTOM_SOCKS5" && (proxyHost.isBlank() || portVal <= 0)) {
                        Toast.makeText(context, context.getString(R.string.admin_proxy_socks5_required), Toast.LENGTH_SHORT).show()
                    } else {
                        onSave(proxyMode, proxyHost.trim(), portVal, proxyUser.trim(), proxyPass.trim())
                    }
                }
            ) {
                Text(strActionSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strActionCancel) }
        }
    )
}

fun isTimeExpired(isoTime: String): Boolean {
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val date = format.parse(isoTime)
        date != null && date.before(Date())
    } catch (e: Exception) {
        false
    }
}

fun formatExpires(isoTime: String?): String {
    if (isoTime.isNullOrEmpty() || isoTime.startsWith("0001-01-01")) return "\u221e"
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val date = format.parse(isoTime) ?: return isoTime
        
        val displayFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        displayFormat.format(date)
    } catch (e: Exception) {
        isoTime
    }
}

@Composable
fun CopyableDetailBlock(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(2.dp))
            SelectionContainer {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
