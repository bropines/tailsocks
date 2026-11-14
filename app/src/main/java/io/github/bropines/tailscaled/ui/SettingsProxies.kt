package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import appctr.Appctr
import java.net.URLEncoder

/**
 * The Local proxies page's stored values, read once when Settings opens. [socks5] is read by
 * the Tunnel page as well (the VPN rows say where the proxy listens), so it lives here, with
 * the page that changes it, and the Tunnel page is handed it.
 */
@Stable
internal class SettingsProxiesState(context: Context, private val accountName: String) {
    private val appContext = context
    var socks5 by mutableStateOf(GlobalSettings.getString(context, "socks5", "127.0.0.1:48115"))
    var socks5User by mutableStateOf(GlobalSettings.getString(context, "socks5_user", ""))
    var socks5Pass by mutableStateOf(GlobalSettings.getString(context, "socks5_pass", ""))
    var httpProxy by mutableStateOf(GlobalSettings.getString(context, "httpproxy", ""))
    var lanAccessEnabled by mutableStateOf(GlobalSettings.isLanAccessEnabled(context))
    // The local proxies opened to the tailnet (Local proxies → Reachable from the tailnet).
    var tailnetOpen by mutableStateOf(TailnetReach.opened(context))

    /**
     * host: the address to publish in the link. Null means this device — the
     * stored bind host, which with LAN access on has been widened to the
     * wildcard and is published as the loopback alias the user typed. A link is
     * meant to be used from somewhere else, so the caller picks the Wi-Fi or
     * tailnet address when the listeners are open to them.
     */
    fun copySagerNetLink(host: String? = null) {
        val context = appContext
        try {
            val encodedUser = URLEncoder.encode(socks5User, "UTF-8").replace("+", "%20")
            val encodedPass = URLEncoder.encode(socks5Pass, "UTF-8").replace("+", "%20")
            val label = URLEncoder.encode("TAILSCALE (${accountName})", "UTF-8")
            val stored = GlobalSettings.getString(context, "socks5", GlobalSettings.DEFAULT_SOCKS5)
            val port = NetAddr.port(stored) ?: ""
            val localHost = NetAddr.host(stored).ifEmpty { NetAddr.LOOPBACK_V4 }.let { if (NetAddr.isWildcard(stored)) NetAddr.LOOPBACK_V4 else it }
            val endpoint = "${host ?: localHost}:$port"
            val link = if (encodedUser.isNotEmpty()) {
                "socks5://$encodedUser:$encodedPass@$endpoint#$label"
            } else {
                "socks5://$endpoint#$label"
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SagerNet SOCKS5", link))
            Toast.makeText(context, context.getString(R.string.settings_sagernet_copied), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.settings_error_format, e.message), Toast.LENGTH_SHORT).show()
        }
    }
}

// C. Local proxies
@Composable
internal fun SettingsProxiesPage(env: SettingsPageEnv, state: SettingsProxiesState) {
    with(env) {
    with(state) {
        // Where the listeners can be reached from outside this device: the Wi-Fi
        // address (none on mobile data) and this node's tailnet address — in
        // userspace mode the daemon's netstack hands connections to that address to
        // local listeners (measured 2026-09-08 from another tailnet node). Read when
        // this page opens, off the main thread, not on every open of Settings: the
        // interfaces are enumerated, and the tailnet address comes from the bridge's
        // bus snapshot rather than a full status with every peer decoded.
        val lanIp by produceState<String?>(null) { value = withContext(Dispatchers.IO) { NetAddr.lanIpv4() } }
        val tailnetIp by produceState<String?>(null) {
            value = withContext(Dispatchers.IO) {
                runCatching { Appctr.getSelfIPs() }.getOrDefault("")
                    .split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() && ':' !in it }
            }
        }
        SettingsCard(title = stringResource(R.string.settings_sect_socks5)) {
            SettingsEditItem(stringResource(R.string.settings_socks5_address_title), socks5, Icons.Default.Language, onAction = { generateRandomLoopbackAddress() }, actionIcon = Icons.Default.Casino) { socks5 = it; saveGlobalPref("socks5", it) }
            SettingsEditItem(stringResource(R.string.settings_socks5_username_title), socks5User, Icons.Default.Person, onAction = { generateRandomString(8) }, actionIcon = Icons.Default.Casino) { socks5User = it; saveGlobalPref("socks5_user", it) }
            SettingsEditItem(stringResource(R.string.settings_socks5_password_title), socks5Pass, Icons.Default.Password, onAction = { generateRandomString(12) }, actionIcon = Icons.Default.Casino) { socks5Pass = it; saveGlobalPref("socks5_pass", it) }
            // Authentication takes both fields or neither, in every part of the
            // app; one alone is ignored, and saying so here beats a proxy that
            // quietly accepts anyone while the user believes it is locked.
            if (socks5User.isBlank() != socks5Pass.isBlank()) {
                Text(
                    stringResource(R.string.settings_socks5_auth_needs_both),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }
            // The link is made to be pasted elsewhere: offered where there is a clipboard to speak of.
            val copies = place(Feature.CLIPBOARD_ACTIONS) == Availability.OFFERED
            // The tailnet reaches the proxy where it is opened to it, or where Root Mode's interface
            // carries the tailnet and it listens on every address; never under the native engine,
            // whose VPN the app is outside of (core/Exposure.kt, ExposureNet).
            val socksForTailnet = when (TailnetReach.net(context)) {
                ExposureNet.NETSTACK -> TailnetListener.SOCKS5 in tailnetOpen
                ExposureNet.VPN -> false
                ExposureNet.INTERFACE -> lanAccessEnabled
            }
            if (copies) Spacer(Modifier.height(12.dp))
            if (!copies) {
                // Nothing to copy with.
            } else if (!lanAccessEnabled && !socksForTailnet) {
                OutlinedButton(onClick = { copySagerNetLink() }, modifier = Modifier.fillMaxWidth().settingAnchor(stringResource(R.string.settings_sagernet_copy)), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_sagernet_copy))
                }
            } else {
                // Open to the network: the link is for another device, so it
                // carries the address that device can reach. Unknown ones are dim.
                Text(
                    stringResource(R.string.settings_sagernet_copy_for),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                Row(Modifier.fillMaxWidth().settingAnchor(stringResource(R.string.settings_sagernet_copy)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copySagerNetLink() }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
                        Text(stringResource(R.string.settings_sagernet_copy_local), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(onClick = { lanIp?.let { copySagerNetLink(it) } }, enabled = lanAccessEnabled && lanIp != null, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
                        Text(stringResource(R.string.settings_lan_endpoint_lan), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(onClick = { tailnetIp?.let { copySagerNetLink(it) } }, enabled = socksForTailnet && tailnetIp != null, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
                        Text(stringResource(R.string.settings_lan_endpoint_tailnet), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        SettingsCardGap()

        SettingsCard(title = stringResource(R.string.settings_sect_http)) {
            val isHttpEnabled = httpProxy.isNotEmpty()
            SettingsSwitchItem(
                title = stringResource(R.string.settings_http_enable_title),
                subtitle = stringResource(R.string.settings_http_enable_desc),
                icon = Icons.Default.Http,
                checked = isHttpEnabled
            ) { enabled ->
                if (enabled) {
                    val defaultAddr = "127.0.0.1:8080"
                    httpProxy = defaultAddr
                    saveGlobalPref("httpproxy", defaultAddr)
                } else {
                    httpProxy = ""
                    saveGlobalPref("httpproxy", "")
                }
            }

            if (isHttpEnabled) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                SettingsEditItem(
                    title = stringResource(R.string.settings_http_address_title),
                    value = httpProxy,
                    icon = Icons.Default.Http,
                    placeholder = "127.0.0.1:8080",
                    onAction = { generateRandomLoopbackAddress() },
                    actionIcon = Icons.Default.Casino
                ) { 
                    httpProxy = it
                    saveGlobalPref("httpproxy", it) 
                }
                HelpText(
                    stringResource(R.string.settings_http_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                )
            }
        }

        SettingsCardGap()

        // Last in the section on purpose, with the tailnet's card after it: the
        // switches here that expose the listeners above to other devices.
        SettingsCard(title = stringResource(R.string.settings_sect_lan)) {
            val socksHasAuth = socks5User.isNotEmpty() || socks5Pass.isNotEmpty()

            SettingsSwitchItem(
                title = stringResource(R.string.settings_lan_access_title),
                subtitle = if (lanAccessEnabled) stringResource(R.string.settings_lan_access_listening)
                           else stringResource(R.string.settings_lan_access_desc),
                icon = Icons.Default.Lan,
                checked = lanAccessEnabled
            ) { enabled ->
                lanAccessEnabled = enabled
                GlobalSettings.setLanAccessEnabled(context, enabled)
                if (ProxyState.isUserLetRunning(context)) {
                    val intent = Intent(context, TailscaledService::class.java).apply {
                        action = "RESTART_ACTION"
                    }
                    context.startService(intent)
                }
            }

            if (lanAccessEnabled) {
                // What is actually bound: the stored fields keep the user's own
                // value, the wildcard is applied on top of it. One block per
                // network, every host and host:port a tap away.
                val ports = listOfNotNull(
                    NetAddr.port(GlobalSettings.getSocks5BindAddr(context))?.let { "SOCKS5" to it },
                    GlobalSettings.getHttpProxyBindAddr(context)
                        .takeIf { it.isNotEmpty() }?.let { a -> NetAddr.port(a)?.let { "HTTP" to it } },
                    NetAddr.port(GlobalSettings.getDnsProxyBindAddr(context))?.let { "DNS" to it }
                )
                val copiedFmt = stringResource(R.string.settings_lan_copied)
                // Where there is no clipboard to speak of — a television — an address is read off
                // the screen and typed on another device: OK shows it large instead of copying it.
                val copies = place(Feature.CLIPBOARD_ACTIONS) == Availability.OFFERED
                var shownEndpoint by remember { mutableStateOf<String?>(null) }
                LanEndpoints(
                    // The tailnet reaches these addresses only where Root Mode's interface carries
                    // it; in userspace networking the card below opens them to it one by one.
                    networks = listOfNotNull(
                        stringResource(R.string.settings_lan_endpoint_lan) to lanIp,
                        (stringResource(R.string.settings_lan_endpoint_tailnet) to tailnetIp)
                            .takeIf { TailnetReach.net(context) == ExposureNet.INTERFACE }
                    ),
                    ports = ports,
                    copies = copies,
                    onCopy = { text ->
                        if (copies) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TailSocks", text))
                            Toast.makeText(context, copiedFmt.format(text), Toast.LENGTH_SHORT).show()
                        } else {
                            shownEndpoint = text
                        }
                    }
                )
                shownEndpoint?.let { text ->
                    ValueDialog(
                        title = stringResource(R.string.settings_lan_access_title),
                        values = listOf(ShownValue(text, text, qr = false)),
                        strings = rememberShownValueStrings(),
                        onDismiss = { shownEndpoint = null }
                    )
                }
            }

            if (lanAccessEnabled && !socksHasAuth) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_lan_access_no_auth_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        SettingsCardGap()

        // Which of them the tailnet may use (core/TailnetReach.kt); the password is the SOCKS5 card's.
        TailnetReachCard(
            open = tailnetOpen,
            onOpenChange = { tailnetOpen = it },
            tailnetIp = tailnetIp,
            socks5Login = socks5User.isNotBlank() && socks5Pass.isNotBlank(),
        ) {
            if (socks5User.isBlank()) {
                socks5User = generateRandomString(8)
                saveGlobalPref("socks5_user", socks5User)
            }
            socks5Pass = generateRandomString(12)
            saveGlobalPref("socks5_pass", socks5Pass)
        }
    }
    }
}
