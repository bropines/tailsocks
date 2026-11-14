package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R

/*
 * The addresses of the local listeners while LAN access is on, as the Proxies page shows
 * them under its switch: a block per network with the host and a chip per port to copy.
 */

/**
 * The listeners' addresses while LAN access is on: one block per network
 * (Wi-Fi/LAN, tailnet) with the host on its own line and a chip per port.
 * Tapping the host copies it, tapping a chip copies host:port. Without
 * [copies] — a device with no clipboard to speak of — the same presses hand
 * the address to [onCopy] to be shown, and the copy icons are not drawn.
 */
@Composable
internal fun LanEndpoints(
    networks: List<Pair<String, String?>>,
    ports: List<Pair<String, Int>>,
    copies: Boolean = true,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((label, host) in networks) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (host == null) {
                        Text(
                            stringResource(R.string.settings_lan_endpoint_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onCopy(host) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                host,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(vertical = 2.dp)
                            )
                            if (copies) Icon(
                                Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy),
                                modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for ((name, port) in ports) {
                                AssistChip(
                                    onClick = { onCopy("$host:$port") },
                                    label = { Text("$name  :$port", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium) },
                                    trailingIcon = if (copies) { { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp)) } } else null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
