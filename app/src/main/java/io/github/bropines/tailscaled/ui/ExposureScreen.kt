package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.Exposure
import io.github.bropines.tailscaled.core.ExposureFacts
import io.github.bropines.tailscaled.core.ExposureGroup
import io.github.bropines.tailscaled.core.ExposureItem
import io.github.bropines.tailscaled.core.ExposureNet
import io.github.bropines.tailscaled.core.ExposureNote
import io.github.bropines.tailscaled.core.ExposureReach
import io.github.bropines.tailscaled.core.ExposureRead
import io.github.bropines.tailscaled.core.ExposureRow
import io.github.bropines.tailscaled.core.ExposureSummary
import io.github.bropines.tailscaled.core.ExposureSwitches
import io.github.bropines.tailscaled.core.NetAddr
import io.github.bropines.tailscaled.core.tryStartActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Settings → Advanced → What this device exposes: one list of everything that listens or can be
 * reached on this device, who reaches it, and the switch or the setting that turns it off
 * (core/Exposure.kt decides, core/ExposureRead.kt reads). Grouped by who reaches it, the public
 * internet first; what is off folds away at the bottom.
 *
 * A row turns itself off right here only where its own page has the same switch, and turns it
 * off the way that page does (ExposureSwitches); every other row opens the setting it is about,
 * on its row. Nothing here turns anything on.
 *
 * A page of Settings, drawn over it while open, like remote management's (SettingsActivity).
 */

/** How often the list is read again while it is on screen: a switch thrown elsewhere shows up within this. */
private const val REFRESH_MS = 4_000L

/** The page, reading the device while it is on screen. */
@Composable
fun ExposureScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var facts by remember { mutableStateOf<ExposureFacts?>(null) }
    // Rows being turned off: their switches show off at once, the list follows when it is read.
    var pending by remember { mutableStateOf(emptySet<String>()) }
    // Cuts the wait before the next reading short: a switch was just thrown.
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }

    if (!LocalInspectionMode.current) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) {
            // Root is asked once a visit: a root manager may announce every shell it grants.
            var root: Pair<Boolean?, Boolean?>? = null
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val read = withContext(Dispatchers.IO) {
                        val f = ExposureRead.facts(context, probeRoot = root == null)
                        if (root == null && f.rootMode) root = f.bootScript to f.cli
                        root?.let { (boot, cli) -> f.copy(bootScript = boot, cli = cli) } ?: f
                    }
                    facts = read
                    pending = emptySet()
                    withTimeoutOrNull(REFRESH_MS) { wake.receive() }
                }
            }
        }
    }

    BackHandler(onBack = onBack)
    ExposureContent(
        facts = facts,
        pending = pending,
        onBack = onBack,
        onTurnOff = { row ->
            pending = pending + row.key
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { ExposureSwitches.turnOff(context, row) } }
                wake.trySend(Unit)
            }
        },
        onOpen = { item -> openSettingOf(context, item) },
    )
}

/** The page for [facts]: null while the first reading is on its way. Previews draw it with made-up facts. */
@Composable
internal fun ExposureContent(
    facts: ExposureFacts?,
    onBack: () -> Unit,
    pending: Set<String> = emptySet(),
    offOpenAtFirst: Boolean = false,
    onTurnOff: (ExposureRow) -> Unit = {},
    onOpen: (ExposureItem) -> Unit = {},
) {
    val layout = rememberWindowLayout()
    var offOpen by rememberSaveable { mutableStateOf(offOpenAtFirst) }
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.exposure_title), onBack = onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ReadableWidth {
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .fillMaxSize()
                        .tvScrollPastStops(scroll)
                        .verticalScroll(scroll)
                        .padding(horizontal = layout.margin, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (facts == null) {
                        Text(stringResource(R.string.exposure_reading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        return@Column
                    }
                    val rows = remember(facts) { Exposure.rows(facts) }
                    SummaryCard(Exposure.summary(rows), facts)
                    val groups = rows.groupBy { it.group }
                    // The remote starts on the first row the page has.
                    var first = true
                    for (group in ExposureGroup.entries) {
                        val inGroup = groups[group].orEmpty()
                        if (inGroup.isEmpty()) continue
                        val focusFirst = first
                        first = false
                        if (group == ExposureGroup.OFF) {
                            OffCard(inGroup, facts, offOpen, focusFirst, onToggle = { offOpen = !offOpen }, onOpen = onOpen)
                        } else {
                            GroupCard(group, inGroup.size) {
                                inGroup.forEachIndexed { index, row ->
                                    key(row.key) {
                                        ExposureRowItem(
                                            row, facts,
                                            switchable = ExposureSwitches.canTurnOff(row, facts),
                                            pending = row.key in pending,
                                            modifier = if (focusFirst && index == 0) Modifier.tvInitialFocus() else Modifier,
                                            onTurnOff = { onTurnOff(row) },
                                            onOpen = { onOpen(row.item) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }
}

/** The line at the top: how many things each kind of outsider reaches, and what stands in the way. */
@Composable
private fun SummaryCard(summary: ExposureSummary, facts: ExposureFacts) {
    val open = summary.internet > 0
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (open) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (summary.quiet) Icons.Default.GppGood else if (open) Icons.Default.Public else Icons.Default.GppMaybe,
                contentDescription = null,
                tint = if (open) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    summaryText(summary),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (open) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                )
                if (!facts.running) Text(stringResource(R.string.exposure_stopped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (facts.shieldsUp) Text(stringResource(R.string.exposure_shields), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "2 things reachable from the tailnet, nothing from the internet": the widest reach first. */
@Composable
private fun summaryText(s: ExposureSummary): String {
    if (s.quiet) return stringResource(R.string.exposure_sum_quiet)
    val parts = ArrayList<String>()
    if (s.internet > 0) parts += pluralStringResource(R.plurals.exposure_sum_internet, s.internet, s.internet)
    if (s.tailnet > 0) parts += pluralStringResource(R.plurals.exposure_sum_tailnet, s.tailnet, s.tailnet)
    if (s.lan > 0) parts += pluralStringResource(R.plurals.exposure_sum_lan, s.lan, s.lan)
    if (s.internet == 0) parts += stringResource(R.string.exposure_sum_no_internet)
    return parts.joinToString(", ").replaceFirstChar { it.titlecase() }
}

/** A group of rows on a card of its own; the public internet's is marked in the error colours. */
@Composable
private fun GroupCard(group: ExposureGroup, count: Int, rows: @Composable () -> Unit) {
    val internet = group == ExposureGroup.INTERNET
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (internet) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = if (internet) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            GroupHeader(group, count)
            if (internet) {
                Text(
                    stringResource(R.string.exposure_group_internet_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }
            rows()
        }
    }
}

@Composable
private fun GroupHeader(group: ExposureGroup, count: Int, modifier: Modifier = Modifier) {
    val color = if (group == ExposureGroup.INTERNET) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(groupIcon(group), null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(groupTitle(group)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What is off, folded under one row that the remote can reach as well as a finger. */
@Composable
private fun OffCard(rows: List<ExposureRow>, facts: ExposureFacts, open: Boolean, focusFirst: Boolean, onToggle: () -> Unit, onOpen: (ExposureItem) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val help = remember { mutableStateOf(false) }
            ExplainedRow(
                onClick = onToggle,
                help = help,
                onClickLabel = stringResource(if (open) R.string.help_collapse else R.string.help_expand),
                color = Color.Transparent,
                modifier = if (focusFirst) Modifier.tvInitialFocus() else Modifier,
            ) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GroupHeader(ExposureGroup.OFF, rows.size, Modifier.weight(1f).padding(top = 6.dp))
                    Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                }
            }
            if (open) {
                for (row in rows) {
                    key(row.key) {
                        ExposureRowItem(row, facts, switchable = false, pending = false, onTurnOff = {}, onOpen = { onOpen(row.item) })
                    }
                }
            }
        }
    }
}

/**
 * One row: what it is, its state in a line, the explanation folded under it, and on its end the
 * switch that turns it off — the whole row is that switch — or the way to its setting.
 */
@Composable
private fun ExposureRowItem(
    row: ExposureRow,
    facts: ExposureFacts,
    switchable: Boolean,
    pending: Boolean,
    modifier: Modifier = Modifier,
    onTurnOff: () -> Unit,
    onOpen: () -> Unit,
) {
    val help = remember(row.key) { mutableStateOf(false) }
    // Open to others with nothing asked of them: the line says so in the error colour.
    val unguarded = row.on && row.effective.isNotEmpty() && row.guarded == false &&
        row.item in setOf(ExposureItem.SOCKS5, ExposureItem.HTTP_PROXY, ExposureItem.TAILDRIVE_PROXY, ExposureItem.TAILCAT_SERVER)
    val internet = row.group == ExposureGroup.INTERNET
    ExplainedRow(
        onClick = { if (!switchable) onOpen() else if (!pending) onTurnOff() },
        help = help,
        onClickLabel = stringResource(if (switchable) R.string.exposure_turn_off else R.string.exposure_open_setting),
        modifier = modifier.padding(vertical = 2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (row.on) 0.3f else 0.15f),
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    itemIcon(row.item), null,
                    tint = when {
                        internet -> MaterialTheme.colorScheme.error
                        row.on -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            },
            supportingContent = {
                Column {
                    Text(
                        stateLine(row, facts),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (unguarded || internet) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Who is paired, by name: the row is about them.
                    if (row.item == ExposureItem.REMOTE && row.on) {
                        for (p in facts.pairings) {
                            Text(
                                "${p.name} · " + stringResource(if (p.owner) R.string.exposure_owner else R.string.exposure_guest) +
                                    (if (p.elevatedUntil > 0) " · " + stringResource(R.string.exposure_until, clock(p.elevatedUntil)) else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HelpText(explanation(row, facts), expanded = help, inClickableRow = true)
                }
            },
            trailingContent = {
                if (switchable) {
                    // The row is the switch: for a remote the switch is no stop of its own.
                    Switch(checked = !pending, onCheckedChange = { if (!it) onTurnOff() }, enabled = !pending, modifier = Modifier.tvNoFocus())
                } else {
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        ) { Text(stringResource(itemTitle(row.item))) }
    }
}

/** "On · 127.0.0.1:48115 · this device only". */
@Composable
private fun stateLine(row: ExposureRow, facts: ExposureFacts): String {
    val parts = ArrayList<String>()
    parts += when (row.note) {
        ExposureNote.NOT_KNOWN -> stringResource(R.string.exposure_note_not_known)
        ExposureNote.NO_TOKEN -> stringResource(R.string.exposure_note_no_token)
        ExposureNote.POLICY_DENIED -> stringResource(R.string.exposure_note_policy_denied)
        ExposureNote.NO_STORAGE -> stringResource(R.string.exposure_note_no_storage)
        ExposureNote.NOT_CHECKED -> stringResource(R.string.exposure_note_not_checked)
        else -> stringResource(if (row.on) R.string.exposure_on else R.string.exposure_off)
    }
    if (!row.on) return parts.joinToString(" · ")

    // Where it listens, or what it is.
    when (row.item) {
        ExposureItem.BYEDPI -> parts += row.address.ifEmpty { stringResource(R.string.exposure_random_loopback) }
        ExposureItem.PEER_RELAY -> parts += row.address.removePrefix(":").ifEmpty { stringResource(R.string.exposure_port_any) }.let { if (it.first().isDigit()) "UDP $it" else it }
        ExposureItem.DEVICE_PORTS -> parts += stringResource(if (facts.net == ExposureNet.NETSTACK) R.string.exposure_ports_netstack else R.string.exposure_ports_interface)
        ExposureItem.ROOT_CLI -> parts += row.address
        ExposureItem.REMOTE -> parts += "TCP ${row.address.removePrefix(":")}"
        else -> if (row.address.isNotEmpty() && row.item != ExposureItem.TAILCAT_FORWARDS) parts += row.address
    }
    // Who reaches it.
    parts += when {
        row.note == ExposureNote.BLOCKED -> stringResource(R.string.exposure_note_blocked)
        ExposureReach.INTERNET in row.effective -> stringResource(R.string.exposure_reach_internet)
        ExposureReach.LAN in row.effective && ExposureReach.TAILNET in row.effective -> stringResource(R.string.exposure_reach_lan_tailnet)
        ExposureReach.LAN in row.effective -> stringResource(R.string.exposure_reach_lan)
        ExposureReach.TAILNET in row.effective -> stringResource(R.string.exposure_reach_tailnet)
        else -> stringResource(R.string.exposure_reach_device)
    }
    // What else there is to say in a word.
    when (row.item) {
        ExposureItem.SOCKS5, ExposureItem.HTTP_PROXY -> parts += stringResource(if (row.guarded == true) R.string.exposure_password else R.string.exposure_no_password)
        ExposureItem.TAILDRIVE_PROXY -> parts += stringResource(if (row.guarded == true) R.string.exposure_login else R.string.exposure_no_login)
        ExposureItem.TAILCAT_SERVER -> parts += if (row.guarded == true) pluralStringResource(R.plurals.exposure_keys, row.count, row.count) else stringResource(R.string.exposure_anyone)
        ExposureItem.REMOTE -> parts += if (row.count == 0) stringResource(R.string.exposure_none_paired) else pluralStringResource(R.plurals.exposure_paired, row.count, row.count)
        ExposureItem.REMOTE_FULL -> if (row.count > 0) parts += pluralStringResource(R.plurals.exposure_owners, row.count, row.count)
        ExposureItem.REMOTE_ELEVATED -> {
            parts += row.names.joinToString()
            parts += stringResource(R.string.exposure_until, clock(row.until))
        }
        ExposureItem.TAILDRIVE_SHARES -> {
            parts += pluralStringResource(R.plurals.exposure_folders, row.count, row.count)
            if (row.guarded == true) parts += stringResource(R.string.exposure_whole_storage)
            if (row.note == ExposureNote.POLICY_UNKNOWN) parts += stringResource(R.string.exposure_note_policy_unknown)
        }
        ExposureItem.TAILDROP -> parts += row.names.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.exposure_to_folder, it) } ?: stringResource(R.string.exposure_inbox)
        ExposureItem.SUBNET_ROUTES -> parts += pluralStringResource(R.plurals.exposure_routes, row.count, row.count)
        ExposureItem.FUNNEL, ExposureItem.SERVE -> parts += row.names.joinToString("; ")
        ExposureItem.TAILCAT_FORWARDS -> parts += stringResource(R.string.exposure_ports, row.names.joinToString(", "))
        ExposureItem.APP_FUNCTIONS -> parts += stringResource(if (row.guarded == true) R.string.exposure_changes_allowed else R.string.exposure_reading_only)
        else -> Unit
    }
    return parts.filter { it.isNotEmpty() }.joinToString(" · ")
}

/** The row's explanation and, for a listener, why it is reached from where it is. */
@Composable
private fun explanation(row: ExposureRow, facts: ExposureFacts): String {
    val own = when (row.item) {
        ExposureItem.DEVICE_PORTS -> stringResource(if (facts.net == ExposureNet.NETSTACK) R.string.exposure_device_ports_help_netstack else R.string.exposure_device_ports_help_interface)
        ExposureItem.ROOT_CLI -> stringResource(R.string.exposure_root_cli_help, facts.cliName)
        else -> stringResource(itemHelp(row.item))
    }
    val listener = row.on && row.address.isNotEmpty() && row.item.local && row.item != ExposureItem.ROOT_CLI
    if (!listener) return own
    val host = NetAddr.host(row.address)
    val why = when {
        ExposureReach.LAN in row.reach && ExposureReach.TAILNET in row.reach -> R.string.exposure_why_wildcard
        ExposureReach.LAN in row.reach -> R.string.exposure_why_lan
        ExposureReach.TAILNET in row.reach && (host.startsWith("127.") || host.equals("localhost", ignoreCase = true)) -> R.string.exposure_why_netstack
        ExposureReach.TAILNET in row.reach -> R.string.exposure_why_tailnet
        else -> R.string.exposure_why_device
    }
    return own + " " + stringResource(why)
}

/** A time of day as the remote management page writes it. */
private fun clock(unixSeconds: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(unixSeconds * 1000))

@StringRes
private fun groupTitle(group: ExposureGroup): Int = when (group) {
    ExposureGroup.INTERNET -> R.string.exposure_group_internet
    ExposureGroup.TAILNET -> R.string.exposure_group_tailnet
    ExposureGroup.LAN -> R.string.exposure_group_lan
    ExposureGroup.DEVICE -> R.string.exposure_group_device
    ExposureGroup.OFF -> R.string.exposure_group_off
}

private fun groupIcon(group: ExposureGroup): ImageVector = when (group) {
    ExposureGroup.INTERNET -> Icons.Default.Public
    ExposureGroup.TAILNET -> Icons.Default.Hub
    ExposureGroup.LAN -> Icons.Default.Router
    ExposureGroup.DEVICE -> Icons.Default.PhoneAndroid
    ExposureGroup.OFF -> Icons.Default.ToggleOff
}

@StringRes
private fun itemTitle(item: ExposureItem): Int = when (item) {
    ExposureItem.FUNNEL -> R.string.exposure_funnel
    ExposureItem.TAILCAT_SERVER -> R.string.exposure_tailcat_server
    ExposureItem.REMOTE -> R.string.exposure_remote
    ExposureItem.REMOTE_FULL -> R.string.exposure_remote_full
    ExposureItem.REMOTE_FILES -> R.string.exposure_remote_files
    ExposureItem.REMOTE_ELEVATED -> R.string.exposure_remote_elevated
    ExposureItem.TAILDRIVE_SHARES -> R.string.exposure_taildrive_shares
    ExposureItem.TAILDROP -> R.string.exposure_taildrop
    ExposureItem.SERVE -> R.string.exposure_serve
    ExposureItem.EXIT_NODE -> R.string.exposure_exit_node
    ExposureItem.SUBNET_ROUTES -> R.string.exposure_routes_title
    ExposureItem.APP_CONNECTOR -> R.string.exposure_app_connector
    ExposureItem.PEER_RELAY -> R.string.exposure_relay
    ExposureItem.LAN_ACCESS -> R.string.exposure_lan_access
    ExposureItem.SOCKS5 -> R.string.exposure_socks5
    ExposureItem.HTTP_PROXY -> R.string.exposure_http
    ExposureItem.DNS_PROXY -> R.string.exposure_dns
    ExposureItem.WEB_UI -> R.string.exposure_web_ui
    ExposureItem.TAILDRIVE_PROXY -> R.string.exposure_drive_proxy
    ExposureItem.TAILCAT_FORWARDS -> R.string.exposure_tailcat_forwards
    ExposureItem.BYEDPI -> R.string.exposure_byedpi
    ExposureItem.DEVICE_PORTS -> R.string.exposure_device_ports
    ExposureItem.ROOT_KERNEL_TUN -> R.string.exposure_root_tun
    ExposureItem.ROOT_BOOT_SCRIPT -> R.string.exposure_root_boot
    ExposureItem.ROOT_CLI -> R.string.exposure_root_cli
    ExposureItem.AUTOMATION -> R.string.exposure_automation
    ExposureItem.APP_FUNCTIONS -> R.string.exposure_app_functions
}

@StringRes
private fun itemHelp(item: ExposureItem): Int = when (item) {
    ExposureItem.FUNNEL -> R.string.exposure_funnel_help
    ExposureItem.TAILCAT_SERVER -> R.string.exposure_tailcat_server_help
    ExposureItem.REMOTE -> R.string.exposure_remote_help
    ExposureItem.REMOTE_FULL -> R.string.exposure_remote_full_help
    ExposureItem.REMOTE_FILES -> R.string.exposure_remote_files_help
    ExposureItem.REMOTE_ELEVATED -> R.string.exposure_remote_elevated_help
    ExposureItem.TAILDRIVE_SHARES -> R.string.exposure_taildrive_shares_help
    ExposureItem.TAILDROP -> R.string.exposure_taildrop_help
    ExposureItem.SERVE -> R.string.exposure_serve_help
    ExposureItem.EXIT_NODE -> R.string.exposure_exit_node_help
    ExposureItem.SUBNET_ROUTES -> R.string.exposure_routes_help
    ExposureItem.APP_CONNECTOR -> R.string.exposure_app_connector_help
    ExposureItem.PEER_RELAY -> R.string.exposure_relay_help
    ExposureItem.LAN_ACCESS -> R.string.exposure_lan_access_help
    ExposureItem.SOCKS5 -> R.string.exposure_socks5_help
    ExposureItem.HTTP_PROXY -> R.string.exposure_http_help
    ExposureItem.DNS_PROXY -> R.string.exposure_dns_help
    ExposureItem.WEB_UI -> R.string.exposure_web_ui_help
    ExposureItem.TAILDRIVE_PROXY -> R.string.exposure_drive_proxy_help
    ExposureItem.TAILCAT_FORWARDS -> R.string.exposure_tailcat_forwards_help
    ExposureItem.BYEDPI -> R.string.exposure_byedpi_help
    ExposureItem.DEVICE_PORTS -> R.string.exposure_device_ports_help_netstack
    ExposureItem.ROOT_KERNEL_TUN -> R.string.exposure_root_tun_help
    ExposureItem.ROOT_BOOT_SCRIPT -> R.string.exposure_root_boot_help
    ExposureItem.ROOT_CLI -> R.string.exposure_root_cli_help
    ExposureItem.AUTOMATION -> R.string.exposure_automation_help
    ExposureItem.APP_FUNCTIONS -> R.string.exposure_app_functions_help
}

private fun itemIcon(item: ExposureItem): ImageVector = when (item) {
    ExposureItem.FUNNEL -> Icons.Default.Public
    ExposureItem.TAILCAT_SERVER -> Icons.Default.Pets
    ExposureItem.REMOTE -> Icons.Default.SettingsRemote
    ExposureItem.REMOTE_FULL -> Icons.Default.AdminPanelSettings
    ExposureItem.REMOTE_FILES -> Icons.Default.FolderOpen
    ExposureItem.REMOTE_ELEVATED -> Icons.Default.LockClock
    ExposureItem.TAILDRIVE_SHARES -> Icons.Default.FolderShared
    ExposureItem.TAILDROP -> Icons.Default.Inbox
    ExposureItem.SERVE -> Icons.Default.Web
    ExposureItem.EXIT_NODE -> Icons.AutoMirrored.Filled.ExitToApp
    ExposureItem.SUBNET_ROUTES -> Icons.AutoMirrored.Filled.AltRoute
    ExposureItem.APP_CONNECTOR -> Icons.Default.Apps
    ExposureItem.PEER_RELAY -> Icons.Default.DeviceHub
    ExposureItem.LAN_ACCESS -> Icons.Default.Lan
    ExposureItem.SOCKS5 -> Icons.Default.Language
    ExposureItem.HTTP_PROXY -> Icons.Default.Http
    ExposureItem.DNS_PROXY -> Icons.Default.Dns
    ExposureItem.WEB_UI -> Icons.Default.Web
    ExposureItem.TAILDRIVE_PROXY -> Icons.Default.CloudSync
    ExposureItem.TAILCAT_FORWARDS -> Icons.Default.Pets
    ExposureItem.BYEDPI -> Icons.Default.Shield
    ExposureItem.DEVICE_PORTS -> Icons.Default.SettingsEthernet
    ExposureItem.ROOT_KERNEL_TUN -> Icons.Default.Memory
    ExposureItem.ROOT_BOOT_SCRIPT -> Icons.Default.PowerSettingsNew
    ExposureItem.ROOT_CLI -> Icons.Default.Terminal
    ExposureItem.AUTOMATION -> Icons.Default.SmartButton
    ExposureItem.APP_FUNCTIONS -> Icons.Default.AutoAwesome
}

/**
 * Where a row's setting is: a row of Settings (an entry of ui/SettingsCatalog.kt by its id, which
 * Settings opens on its page, wherever this device keeps it), or a screen of its own by its
 * `tailsocks://` host.
 */
internal fun settingOf(item: ExposureItem): Pair<String?, String?> = when (item) {
    ExposureItem.FUNNEL, ExposureItem.SERVE -> null to "serve"
    ExposureItem.TAILCAT_SERVER, ExposureItem.TAILCAT_FORWARDS -> null to "tailcat"
    ExposureItem.TAILDRIVE_SHARES, ExposureItem.TAILDRIVE_PROXY -> null to "taildrive"
    ExposureItem.REMOTE, ExposureItem.REMOTE_FULL, ExposureItem.REMOTE_FILES, ExposureItem.REMOTE_ELEVATED -> "remote_management" to null
    ExposureItem.TAILDROP -> "taildrop_folder" to null
    ExposureItem.EXIT_NODE -> "advertise_exit_node" to null
    ExposureItem.SUBNET_ROUTES -> "advertise_routes" to null
    ExposureItem.APP_CONNECTOR -> "app_connector" to null
    ExposureItem.PEER_RELAY -> "relay" to null
    ExposureItem.LAN_ACCESS -> "lan_access" to null
    ExposureItem.SOCKS5 -> "socks5_address" to null
    ExposureItem.HTTP_PROXY -> "http_enable" to null
    ExposureItem.DNS_PROXY -> "dns_proxy_address" to null
    ExposureItem.WEB_UI -> "web_ui" to null
    ExposureItem.BYEDPI -> "byedpi" to null
    // What closes every port at once.
    ExposureItem.DEVICE_PORTS -> "shields_up" to null
    ExposureItem.ROOT_KERNEL_TUN -> "root_tun" to null
    ExposureItem.ROOT_BOOT_SCRIPT -> "root_service" to null
    ExposureItem.ROOT_CLI -> "root_cli" to null
    ExposureItem.AUTOMATION, ExposureItem.APP_FUNCTIONS -> "automation_enable" to null
}

/** Opens [item]'s setting on top of this page, so Back comes here again; nothing where this device has no such screen. */
private fun openSettingOf(context: Context, item: ExposureItem) {
    val (row, screen) = settingOf(item)
    val intent = when {
        row != null -> Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN_ROW, row)
        screen != null -> DeepLinks.intentFor(context, Uri.parse("${DeepLinks.SCHEME}://$screen"))
        else -> null
    } ?: return
    context.tryStartActivity(intent)
}
