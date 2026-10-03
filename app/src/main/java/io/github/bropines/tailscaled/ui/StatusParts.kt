package io.github.bropines.tailscaled.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SignalWifiConnectedNoInternet4
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.models.HealthWarning
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse

/**
 * What a daemon health warning is about, which decides its title, its icon and
 * the one thing the user can do about it.
 */
enum class HealthKind { Relay, Coordination, Network, Login, Other }

fun healthKindOf(code: String): HealthKind = when (code) {
    "no-derp-connection", "no-derp-home", "derp-timed-out", "derp-region-error" -> HealthKind.Relay
    "not-in-map-poll", "mapresponse-timeout", "tls-connection-failed" -> HealthKind.Coordination
    "network-status", "no-udp4-bind", "magicsock-receive-func-error" -> HealthKind.Network
    "login-state" -> HealthKind.Login
    else -> HealthKind.Other
}

/**
 * Codes that are never worth a banner: start-up noise, update nags, things the
 * app already shows in its own way, and the upstream test warnable.
 */
private val quietCodes = setOf(
    "warming-up", "update-available", "security-update-available", "is-using-unstable-version",
    "test-warnable", "apply-disk-config", "local-log-config-error", "wantrunning-false",
    "ip-forwarding-off", "tls-cert-pending", "android",
)

/**
 * Warnings about the daemon's own DNS resolver. A SOCKS5 or HTTP client's names
 * never go through it (they resolve on the way out, through the exit node's DoH
 * or the ordinary resolver), and what does — the app's DNS proxy — answers from
 * the fallback servers when it fails. Only where the system's DNS goes to the
 * daemon directly is it a connection problem; see GlobalSettings.dnsHasFallback.
 */
private val resolverCodes = setOf("dns-forward-failing")

/**
 * A warning must have held for this long before it is shown. The daemon raises
 * and clears several of them within a second of any network change; a banner
 * that blinks in and out is worse than none.
 */
private const val SETTLE_MS = 5_000L

/**
 * The warnings to show, most serious first: the ones that are about the
 * connection — by kind or by the daemon's own verdict — and have settled.
 */
fun visibleWarnings(all: List<HealthWarning>, nowMs: Long): List<HealthWarning> =
    all.asSequence()
        .filter { it.code !in quietCodes }
        .filter { healthKindOf(it.code) != HealthKind.Other || it.impactsConnectivity || it.severity == "high" }
        .filter { it.brokenSinceMs == 0L || nowMs - it.brokenSinceMs >= SETTLE_MS }
        .sortedWith(compareBy({ severityRank(it.severity) }, { if (it.impactsConnectivity) 0 else 1 }))
        .toList()

private fun severityRank(s: String) = when (s) { "high" -> 0; "medium" -> 1; else -> 2 }

/**
 * Whether these warnings mean traffic is actually affected — the card then says
 * so. [dnsHasFallback]: a failing resolver is only a notice where the app's DNS
 * proxy stands in for it.
 */
fun warningsDegradeConnection(visible: List<HealthWarning>, dnsHasFallback: Boolean): Boolean =
    visible.any {
        if (dnsHasFallback && it.code in resolverCodes) false
        else it.impactsConnectivity || healthKindOf(it.code) in setOf(HealthKind.Relay, HealthKind.Coordination, HealthKind.Network)
    }

@Composable
fun healthTitle(w: HealthWarning): String = when (w.code) {
    "no-derp-connection", "no-derp-home" -> stringResource(R.string.health_relay_unreachable)
    "derp-timed-out" -> stringResource(R.string.health_relay_timeout)
    "derp-region-error" -> stringResource(R.string.health_relay_region)
    "not-in-map-poll" -> stringResource(R.string.health_coord_unreachable)
    "mapresponse-timeout" -> stringResource(R.string.health_coord_timeout)
    "tls-connection-failed" -> stringResource(R.string.health_tls_failed)
    "network-status" -> stringResource(R.string.health_network_down)
    "no-udp4-bind" -> stringResource(R.string.health_udp_unavailable)
    "magicsock-receive-func-error" -> stringResource(R.string.health_network_error)
    "login-state" -> stringResource(R.string.health_login)
    "dns-forward-failing" -> stringResource(R.string.health_dns_unavailable)
    else -> w.title.ifBlank { w.code }
}

/** The folded line under a title: the daemon's own text, or ours where we know better. */
@Composable
fun healthText(w: HealthWarning, dnsHasFallback: Boolean): String = when (w.code) {
    "dns-forward-failing" -> stringResource(if (dnsHasFallback) R.string.health_dns_fallback_text else R.string.health_dns_down_text)
    else -> w.text
}

private fun iconOf(kind: HealthKind): ImageVector = when (kind) {
    HealthKind.Relay -> Icons.Default.Hub
    HealthKind.Coordination -> Icons.Default.CloudOff
    HealthKind.Network -> Icons.Default.SignalWifiConnectedNoInternet4
    HealthKind.Login -> Icons.AutoMirrored.Filled.Login
    HealthKind.Other -> Icons.Default.WarningAmber
}

/**
 * What is wrong with the connection right now, under the status card: at most
 * two problems, each with its one action, and how many more there are. The
 * daemon's own explanation stays folded (HelpText) — the title says enough to
 * act on.
 */
@Composable
fun HealthBanner(
    warnings: List<HealthWarning>,
    reconnecting: Boolean,
    dnsHasFallback: Boolean,
    onReconnectRelays: () -> Unit,
    onOpenBypass: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (warnings.isEmpty()) return
    val high = warnings.any { it.severity == "high" }
    // A neutral surface with a coloured accent: sitting under a card that is
    // already tinted for the same problem, a second tinted block would read as
    // one heavy slab rather than "here is what is wrong, and what to do".
    val accent = if (high) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().animateContentSize()
    ) {
        val onColor = MaterialTheme.colorScheme.onSurface
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            warnings.take(2).forEach { w ->
                val kind = healthKindOf(w.code)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Icon(iconOf(kind), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            healthTitle(w),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = onColor
                        )
                        val text = healthText(w, dnsHasFallback)
                        if (text.isNotBlank() && text != w.title) {
                            HelpText(text, lines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    when (kind) {
                        HealthKind.Relay -> TextButton(onClick = onReconnectRelays, enabled = !reconnecting) {
                            Text(
                                stringResource(if (reconnecting) R.string.health_reconnecting else R.string.health_action_reconnect),
                                color = accent
                            )
                        }
                        HealthKind.Coordination -> TextButton(onClick = onOpenBypass) {
                            Text(stringResource(R.string.health_action_bypass), color = accent)
                        }
                        else -> {}
                    }
                }
            }
            if (warnings.size > 2) {
                Text(
                    stringResource(R.string.health_more, warnings.size - 2),
                    style = MaterialTheme.typography.labelSmall,
                    color = onColor.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 32.dp, top = 2.dp)
                )
            }
        }
    }
}

/** What a connected tailnet looks like at a glance, read from the status document. */
data class ConnectionSummary(
    val deviceName: String?,
    val deviceIp: String?,
    val online: Int,
    val total: Int,
    val exitNodeName: String?,
    val homeRelay: String?,
)

/**
 * Builds the summary from /localapi/v0/status. The exit node is looked up by
 * the address the app keeps, so the card can name it instead of printing an
 * IP; the home relay is Self.Relay — the region this node meets the others in.
 */
fun summarize(status: StatusResponse, exitNodeIp: String): ConnectionSummary {
    val peers: List<PeerData> = listablePeers(status)
    val exit = if (exitNodeIp.isBlank()) null
        else status.peers?.values?.firstOrNull { p -> p.tailscaleIPs?.any { it == exitNodeIp } == true }
    return ConnectionSummary(
        deviceName = status.self?.hostName?.takeIf { it.isNotBlank() },
        deviceIp = status.self?.tailscaleIPs?.firstOrNull { !it.contains(':') },
        online = peers.count { it.online == true },
        total = peers.size,
        exitNodeName = exit?.hostName?.takeIf { it.isNotBlank() },
        homeRelay = status.self?.relay?.takeIf { it.isNotBlank() },
    )
}

/**
 * The summary under a connected card: this device and its address, how much of
 * the tailnet is reachable, the exit node by name, the home relay. Pills, not
 * buttons — every one of them is also one tap away elsewhere.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionSummaryRow(summary: ConnectionSummary, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (summary.deviceIp != null) {
            SummaryPill(Icons.Default.PhoneAndroid, listOfNotNull(summary.deviceName, summary.deviceIp).joinToString(" · "))
        }
        if (summary.total > 0) {
            SummaryPill(Icons.Default.Devices, stringResource(R.string.summary_online, summary.online, summary.total))
        }
        if (summary.exitNodeName != null) {
            SummaryPill(Icons.Default.Public, summary.exitNodeName)
        }
        if (summary.homeRelay != null) {
            SummaryPill(Icons.Default.Router, stringResource(R.string.summary_relay, summary.homeRelay))
        }
    }
}

@Composable
private fun SummaryPill(icon: ImageVector, text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
