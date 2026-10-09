package io.github.bropines.tailscaled.admin

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.profile.AdminProxySettings
import io.github.bropines.tailscaled.admin.secure.SecretField
import io.github.bropines.tailscaled.ui.HelpText
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
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

/**
 * What went wrong with the last load of a tab, in words, with a retry — instead of a toast
 * that vanished with the raw body in it — and how many items could not be read, so a list
 * that lost entries does not pass for the whole tailnet.
 */
@Composable
fun <T> LoadProblems(state: Loadable<T>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val error = state.error
    if (error == null && state.issues.isEmpty()) return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
        ),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (error != null) Icons.Default.ErrorOutline else Icons.Default.Warning, null,
                tint = if (error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (error != null) {
                    HelpText(ConsoleText.error(ctx, error), color = MaterialTheme.colorScheme.onErrorContainer)
                }
                if (state.issues.isNotEmpty()) {
                    Text(
                        ctx.resources.getQuantityString(R.plurals.admin2_unreadable_items, state.issues.size, state.issues.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            if (error != null) TextButton(onClick = onRetry) { Text(ctx.getString(R.string.admin2_retry)) }
        }
    }
}

fun isTimeExpired(isoTime: String): Boolean = parseIso(isoTime)?.before(Date()) == true

/** RFC 3339 with or without fractional seconds; null for the zero time and for nonsense. */
fun parseIso(isoTime: String?): Date? {
    if (isoTime.isNullOrBlank() || isoTime.startsWith("0001-01-01")) return null
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        format.parse(isoTime.substringBefore('.').removeSuffix("Z"))
    } catch (e: Exception) {
        null
    }
}

/** A date from the API in the app's language — not the system's, which the app may override. */
fun formatExpires(ctx: Context, isoTime: String?): String {
    if (isoTime.isNullOrEmpty() || isoTime.startsWith("0001-01-01")) return "∞"
    val date = parseIso(isoTime) ?: return isoTime
    return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, ctx.resources.configuration.locales[0]).format(date)
}

@Composable
fun CopyableDetailBlock(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
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

/**
 * How the console reaches the API: the daemon's control proxy, direct, our own SOCKS5
 * listener, or a SOCKS5 proxy of its own whose password is a secret field.
 */
@Composable
fun ProxySettingsFields(
    proxy: AdminProxySettings,
    password: String,
    hasStoredPassword: Boolean,
    onChange: (AdminProxySettings) -> Unit,
    onPasswordChange: (String) -> Unit,
) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            AdminProxySettings.MODE_CONTROL_PLANE to R.string.admin_proxy_control_plane,
            AdminProxySettings.MODE_DIRECT to R.string.admin_proxy_direct,
            AdminProxySettings.MODE_LOCAL_SOCKS5 to R.string.admin_proxy_local_socks5,
            AdminProxySettings.MODE_CUSTOM_SOCKS5 to R.string.admin_proxy_custom_socks5,
        ).forEach { (mode, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = proxy.mode == mode, role = Role.RadioButton) { onChange(proxy.copy(mode = mode)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = proxy.mode == mode, onClick = null, modifier = Modifier.padding(12.dp))
                Text(ctx.getString(label), style = MaterialTheme.typography.bodyMedium)
            }
        }
        when (proxy.mode) {
            AdminProxySettings.MODE_CONTROL_PLANE -> HelpText(ctx.getString(R.string.admin_proxy_control_plane_desc))
            AdminProxySettings.MODE_LOCAL_SOCKS5 -> HelpText(ctx.getString(R.string.admin_proxy_local_desc_setup))
            AdminProxySettings.MODE_CUSTOM_SOCKS5 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = proxy.host,
                    onValueChange = { onChange(proxy.copy(host = it)) },
                    label = { Text(ctx.getString(R.string.admin_proxy_socks5_host)) },
                    placeholder = { Text(ctx.getString(R.string.admin_proxy_socks5_host_placeholder)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = if (proxy.port > 0) proxy.port.toString() else "",
                    onValueChange = { v ->
                        val digits = v.filter { it.isDigit() }.take(5)
                        val n = digits.toIntOrNull() ?: 0
                        if (n <= 65535) onChange(proxy.copy(port = n))
                    },
                    label = { Text(ctx.getString(R.string.admin_proxy_socks5_port)) },
                    placeholder = { Text(ctx.getString(R.string.admin_proxy_socks5_port_placeholder)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = proxy.user,
                    onValueChange = { onChange(proxy.copy(user = it)) },
                    label = { Text(ctx.getString(R.string.admin_proxy_username_optional)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = ctx.getString(R.string.admin_proxy_password_optional),
                    placeholder = if (hasStoredPassword) ctx.getString(R.string.admin2_secret_stored) else null,
                    showLabel = ctx.getString(R.string.admin2_secret_show),
                    hideLabel = ctx.getString(R.string.admin2_secret_hide),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> Unit
        }
        Spacer(Modifier.size(4.dp))
    }
}
