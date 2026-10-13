package io.github.bropines.tailscaled.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.FileSource
import io.github.bropines.tailscaled.core.ManagedCaps
import io.github.bropines.tailscaled.core.ManagedOp
import io.github.bropines.tailscaled.core.ManagedShown
import io.github.bropines.tailscaled.core.ManagedStatus
import io.github.bropines.tailscaled.core.RemoteJson
import io.github.bropines.tailscaled.core.RemotePairFailure
import io.github.bropines.tailscaled.core.RemoteProbe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Remote management, the managing device's page: another device's TailSocks, run from this
 * one. What it shows is RemoteManageUi (ui/RemoteManageController.kt); what it is built of is
 * in RemoteManageParts.kt and RemoteManageGroups.kt; the windows it opens — the exit nodes, the
 * device's storage, its log — are in RemoteManageWindows.kt.
 *
 * One page for every device class. A phone reads it as one column: the device, then its
 * groups, the less used ones folded to a line each. A tablet and a television stand the same
 * cards in two columns. Nothing on it needs more than the D-pad, OK and Back, and nothing
 * acts on a long press.
 *
 * What is wrong and what is being confirmed stay pinned under the top bar, above the part
 * that scrolls: a switch far down the page is answered where the eye can find it.
 */

/** What the page needs of the device in hand; all of it is absent in a preview. */
@Stable
class RemoteManageHooks(
    /** Opens the scanner for a pairing code; null where there is no camera to ask. */
    val scan: (() -> Unit)? = null,
    /** Opens this device's picker for files to send to the managed one. */
    val sendFile: (() -> Unit)? = null,
    /** The managed device's storage, for the browser. */
    val storage: FileSource? = null,
    /** Hands a text to another app; null where there is none to hand it to. */
    val shareText: ((title: String, text: String) -> Unit)? = null,
    /** Puts a text on the clipboard; null where nothing could paste it. */
    val copyText: ((String) -> Unit)? = null,
    /** Opens this device's own received files. */
    val openInbox: (() -> Unit)? = null,
)

/**
 * The groups that start open: what the page is most often opened for. A television, whose
 * screen is half a tablet's in height, opens the first alone and keeps the rest in sight.
 */
private val OPEN_AT_FIRST = setOf(ManageGroup.CONNECTION, ManageGroup.FILES)
private val OPEN_AT_FIRST_TV = setOf(ManageGroup.CONNECTION)

/**
 * The page, without the window it lives in: a preview draws this. [opened] fixes which groups
 * are open, for a preview; the page itself remembers what the person folded.
 */
@Composable
internal fun RemoteManageContent(
    ui: RemoteManageUi,
    actions: RemoteManageActions,
    hooks: RemoteManageHooks = RemoteManageHooks(),
    opened: Set<ManageGroup>? = null,
) {
    val layout = rememberWindowLayout()
    Scaffold(
        topBar = {
            AppTopBar(
                title = ui.name,
                subtitle = stringResource(R.string.rmanage_subtitle),
                onBack = actions::close,
                actions = {
                    if (ui is RemoteManageUi.Managing && ui.status != null && ui.problem?.final != true) {
                        IconButton(onClick = actions::refresh, enabled = !ui.busy && !ui.loading && ui.guard?.phase?.active != true) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (ui) {
                is RemoteManageUi.Pairing -> Narrow(layout) { PairingBody(ui, actions, hooks.scan) }
                is RemoteManageUi.Forgotten -> Narrow(layout) {
                    Text(stringResource(R.string.remote_forget_local, ui.name), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = actions::close, shape = MaterialTheme.shapes.medium, modifier = Modifier.tvInitialFocus()) {
                        Text(stringResource(R.string.action_close))
                    }
                }
                is RemoteManageUi.Managing -> Managing(ui, actions, hooks, layout, opened)
            }
        }
    }
}

/** A short form in the middle of the page: the pairing code, the last word after forgetting. */
@Composable
private fun Narrow(layout: WindowLayout, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = layout.margin, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

// ─── Managing ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Managing(
    ui: RemoteManageUi.Managing,
    actions: RemoteManageActions,
    hooks: RemoteManageHooks,
    layout: WindowLayout,
    opened: Set<ManageGroup>?,
) {
    var remembered by rememberSaveable(
        stateSaver = listSaver<Set<ManageGroup>, String>(save = { set -> set.map { it.name } }, restore = { names -> names.map(ManageGroup::valueOf).toSet() })
    ) { mutableStateOf(if (layout.tv) OPEN_AT_FIRST_TV else OPEN_AT_FIRST) }
    val open = opened ?: remembered
    var asked by remember { mutableStateOf<ManageAsk?>(null) }

    val status = ui.status
    val groups = if (status == null || ui.problem?.final == true) emptyList() else groupsOf(ui)
    val card: @Composable (ManageGroup, Modifier) -> Unit = { group, place ->
        val summary = summaryOf(group, ui)
        ManageCard(
            icon = group.icon(),
            title = group.title(),
            summary = summary.text,
            attention = summary.attention,
            open = group in open,
            onToggle = { remembered = if (group in remembered) remembered - group else remembered + group },
            headerModifier = place,
        ) { GroupBody(group, ui, actions, hooks) { asked = it } }
    }
    // A television must have something in focus: the first group's header, unless a banner's button took it.
    val first = groups.firstOrNull()
    val focusOf: @Composable (ManageGroup) -> Modifier = { if (it == first) Modifier.tvInitialFocus() else Modifier }

    Column(Modifier.fillMaxSize()) {
        Banners(ui, actions, layout)
        when {
            status == null -> Narrow(layout) {
                if (ui.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.remote_loading, ui.name), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                // Never reached: all that is left is to forget it.
                if (ui.problem != null && !ui.problem.final) {
                    OutlinedButton(onClick = { asked = ManageAsk.Forget }, enabled = !ui.busy, shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(R.string.remote_forget))
                    }
                }
            }
            groups.isEmpty() -> Unit
            // What the device last said stays readable while it cannot be asked, dimmed: it is not news.
            layout.listDetail -> Row(
                Modifier.fillMaxSize().padding(horizontal = layout.margin).alpha(if (ui.problem?.stale == true) 0.5f else 1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // The device and how it is connected on one side, everything it holds on the other.
                val (left, right) = groups.partition { it == ManageGroup.CONNECTION }
                PageColumn(Modifier.weight(1f)) {
                    DeviceHeader(ui, status, actions)
                    left.forEach { card(it, focusOf(it)) }
                }
                PageColumn(Modifier.weight(1f)) { right.forEach { card(it, focusOf(it)) } }
            }
            else -> ReadableWidth {
                PageColumn(Modifier.fillMaxSize().padding(horizontal = layout.margin).alpha(if (ui.problem?.stale == true) 0.5f else 1f)) {
                    DeviceHeader(ui, status, actions)
                    groups.forEach { card(it, focusOf(it)) }
                }
            }
        }
    }

    ManageWindows(ui, actions, hooks, asked, onAsked = { asked = it })
}

/** A column of cards that scrolls, and that a remote can read to its end past rows it cannot stop on. */
@Composable
private fun PageColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier.tvScrollPastStops(scroll).verticalScroll(scroll).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

// ─── What is wrong, and what is being confirmed ──────────────────────────────────────────────

@Composable
private fun Banners(ui: RemoteManageUi.Managing, actions: RemoteManageActions, layout: WindowLayout) {
    val problem = ui.problem
    val guard = ui.guard
    if (problem == null && guard == null) return
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .then(if (layout.listDetail) Modifier else Modifier.widthIn(max = ReadableContentWidth))
                .fillMaxWidth()
                .padding(horizontal = layout.margin)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (problem != null) {
                val action = problem.action(loaded = ui.status != null)
                Banner(
                    text = problemText(problem, ui.name, ui.problemDetail),
                    warm = true,
                    icon = Icons.Default.Warning,
                    action = problemActionText(action),
                    onAction = { actions.resolve(action) },
                )
            }
            if (guard != null) GuardBanner(ui, guard, actions)
        }
    }
}

/** One sentence, and at most one thing to do about it. [warm] for what needs a person, calm for what is under way. */
@Composable
private fun Banner(
    text: String,
    warm: Boolean,
    icon: ImageVector? = null,
    working: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (warm) scheme.tertiaryContainer else scheme.secondaryContainer,
        contentColor = if (warm) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            else if (icon != null) Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = onAction, shape = MaterialTheme.shapes.medium, modifier = Modifier.tvInitialFocus()) { Text(action) }
            }
        }
    }
}

private fun clockTime(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

/** Where a provisional change stands, in the page's own words: nothing here asks the person to decide. */
@Composable
private fun GuardBanner(ui: RemoteManageUi.Managing, guard: GuardState, actions: RemoteManageActions) {
    val (what, value) = guardedValue(guard.key, ui)
    val now = stringResource(R.string.rmanage_value_now, what, value)
    val ok = stringResource(R.string.remote_action_ok)
    when (guard.phase) {
        GuardPhase.CHECKING -> Banner(stringResource(R.string.rmanage_guard_checking, ui.name), warm = false, working = true)
        GuardPhase.APPLIED -> Banner(stringResource(R.string.rmanage_guard_applied, ui.name), warm = false, icon = Icons.Default.CheckCircle)
        GuardPhase.SILENT -> Banner(
            stringResource(R.string.rmanage_guard_silent, ui.name, what, remember(guard.revertsAt) { clockTime(guard.revertsAt) }),
            warm = true, working = true,
        )
        GuardPhase.REVERTED_AWAY -> Banner(stringResource(R.string.rmanage_guard_away, ui.name, what), warm = true, working = true)
        GuardPhase.REVERTED -> Banner(
            stringResource(R.string.rmanage_guard_reverted, ui.name, now),
            warm = true, icon = Icons.Default.Warning, action = ok, onAction = actions::dismissGuard,
        )
        GuardPhase.UNCLEAR -> Banner(
            stringResource(R.string.rmanage_guard_unclear, ui.name, now),
            warm = true, icon = Icons.Default.Warning, action = ok, onAction = actions::dismissGuard,
        )
    }
}

@Composable
internal fun problemText(problem: ManageProblem, name: String, detail: String): String = when (problem) {
    ManageProblem.NOT_CONNECTED -> stringResource(R.string.remote_problem_not_connected)
    ManageProblem.NO_PEER -> stringResource(R.string.remote_problem_no_peer, name)
    ManageProblem.OFFLINE -> stringResource(R.string.remote_problem_offline, name)
    ManageProblem.NOT_ACCEPTING -> stringResource(R.string.remote_problem_not_accepting, name)
    ManageProblem.REVOKED -> stringResource(R.string.remote_problem_revoked, name)
    ManageProblem.EXPIRED -> stringResource(R.string.remote_problem_expired, name)
    ManageProblem.OWNER_ONLY -> stringResource(R.string.remote_problem_owner_only)
    ManageProblem.NEEDS_FULL -> stringResource(R.string.rmanage_deny_full, name)
    ManageProblem.GONE -> stringResource(R.string.remote_problem_gone)
    ManageProblem.REFUSED ->
        if (detail.isEmpty()) stringResource(R.string.remote_problem_refused, name)
        else stringResource(R.string.remote_problem_refused_why, name, detail.trimEnd('.'))
    ManageProblem.GUARD_PENDING -> stringResource(R.string.rmanage_problem_guard_pending, name)
    ManageProblem.SLOW_DOWN -> stringResource(R.string.remote_problem_slow)
    ManageProblem.BUSY -> stringResource(R.string.remote_problem_busy, name)
    ManageProblem.UNCLEAR -> stringResource(R.string.rmanage_problem_unclear, name)
    ManageProblem.FAILED -> stringResource(R.string.remote_problem_failed, name)
}

@Composable
private fun problemActionText(action: ManageProblemAction): String = stringResource(
    when (action) {
        ManageProblemAction.RETRY -> R.string.action_retry
        ManageProblemAction.PAIR_AGAIN -> R.string.remote_action_pair_again
        ManageProblemAction.DISMISS -> R.string.remote_action_ok
        ManageProblemAction.CLOSE -> R.string.action_close
    }
)

// ─── The device at a glance ──────────────────────────────────────────────────────────────────

internal fun deviceGlyph(deviceClass: String, tv: Boolean): ImageVector = when {
    tv || deviceClass == RemoteProbe.DEVICE_TV -> Icons.Default.Tv
    deviceClass == "tablet" -> Icons.Default.TabletAndroid
    else -> Icons.Default.PhoneAndroid
}

/**
 * The device as the main screen draws this one's own status: the same colours for the same
 * states, so "connected" looks connected before a word is read. Then what it is, what this
 * device is to it, and the one thing a person standing between two televisions wants.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceHeader(ui: RemoteManageUi.Managing, status: ManagedStatus, actions: RemoteManageActions) {
    val scheme = MaterialTheme.colorScheme
    val warnings = status.health.size
    val troubled = !status.running && status.backendState in setOf("NeedsLogin", "NeedsMachineAuth") || status.running && warnings > 0
    val starting = !status.running && status.backendState == "Starting"
    val ground by animateColorAsState(
        when {
            troubled -> scheme.tertiaryContainer
            status.running -> scheme.primaryContainer
            starting -> scheme.secondaryContainer
            else -> scheme.surfaceContainerHigh
        },
        label = "remote_header_bg"
    )
    val ink by animateColorAsState(
        when {
            troubled -> scheme.onTertiaryContainer
            status.running -> scheme.onPrimaryContainer
            starting -> scheme.onSecondaryContainer
            else -> scheme.onSurfaceVariant
        },
        label = "remote_header_fg"
    )
    val access = ui.access
    val device = status.device?.takeIf { access.has(ManagedCaps.DEVICE) }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = ground, contentColor = ink, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                    Surface(color = ink.copy(alpha = 0.12f), modifier = Modifier.matchParentSize()) {}
                    Icon(deviceGlyph(status.deviceClass, ui.tv), null, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (status.running) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (status.running) stringResource(R.string.remote_status_running) else backendStateText(status.backendState),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val facts = listOfNotNull(
                        device?.model?.takeIf { it.isNotEmpty() },
                        device?.android?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.rmanage_android, it) },
                        if (device == null) status.dnsName.trimEnd('.').takeIf { it.isNotEmpty() } else null,
                        if (warnings > 0) pluralStringResource(R.plurals.rmanage_warnings, warnings, warnings) else null,
                    )
                    if (facts.isNotEmpty()) {
                        Text(
                            facts.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ink.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // On the header's own colour: a tag in a container colour of its own would fight it.
                val tag: @Composable (String) -> Unit = { text ->
                    Surface(shape = MaterialTheme.shapes.small, color = ink.copy(alpha = 0.14f), contentColor = ink) {
                        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                tag(
                    if (ui.owner) stringResource(R.string.remote_role_owner)
                    else if (ui.left < 0) stringResource(R.string.remote_role_guest)
                    else stringResource(R.string.remote_guest_left, remoteDurationText(ui.left))
                )
                if (access.levelKnown) tag(levelText(access.level))
            }

            val identify = access.gate(ManagedOp.IDENTIFY)
            if (identify.offered) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ManageButton(
                        stringResource(R.string.rmanage_identify),
                        onClick = actions::identify,
                        icon = Icons.Default.Visibility,
                        enabled = ui.usable && identify.open && !ui.identifying,
                        working = ui.identifying,
                    )
                    Spacer(Modifier.width(12.dp))
                    val told = when (ui.identified) {
                        ManagedShown.SHOWN -> stringResource(R.string.rmanage_identify_shown, ui.name)
                        ManagedShown.NOTIFIED -> stringResource(R.string.rmanage_identify_notified, ui.name)
                        ManagedShown.WAITING -> stringResource(R.string.rmanage_identify_unseen, ui.name)
                        null -> null
                    }
                    if (told != null) Text(told, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    else HelpText(stringResource(R.string.rmanage_identify_help), Modifier.weight(1f), color = ink.copy(alpha = 0.8f))
                }
            }
        }
    }
}

// ─── Pairing ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PairingBody(ui: RemoteManageUi.Pairing, actions: RemoteManageActions, onScan: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GroupIcon(deviceGlyph("", ui.tv))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.remote_pair_enter, ui.name), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
    OutlinedTextField(
        value = ui.code,
        onValueChange = actions::setCode,
        label = { Text(stringResource(R.string.remote_pair_code_label)) },
        singleLine = true,
        enabled = !ui.busy,
        isError = ui.error != null,
        supportingText = ui.error?.let { error -> { Text(pairErrorText(error, ui.name)) } },
        textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { actions.pair() }),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().tvInitialFocus()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = actions::pair,
            enabled = !ui.busy && RemoteJson.isCode(ui.code),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.weight(1f).heightIn(min = 46.dp)
        ) {
            if (ui.busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.remote_pair_action))
        }
        if (onScan != null) {
            OutlinedButton(
                onClick = onScan,
                enabled = !ui.busy,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f).heightIn(min = 46.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.remote_pair_scan), textAlign = TextAlign.Center)
            }
        }
    }
    if (!ui.codeOnShow) {
        Text(
            stringResource(R.string.remote_pair_where),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun pairErrorText(error: RemotePairError, name: String): String = when (error.failure) {
    RemotePairFailure.WRONG -> error.attemptsLeft?.let { pluralStringResource(R.plurals.remote_pair_err_wrong, it, it) }
        ?: stringResource(R.string.remote_pair_err_wrong_plain)
    RemotePairFailure.LOCKED -> stringResource(R.string.remote_pair_err_locked)
    RemotePairFailure.CLOSED -> stringResource(R.string.remote_pair_err_closed)
    RemotePairFailure.USED -> stringResource(R.string.remote_pair_err_used)
    RemotePairFailure.NOT_ALLOWED -> stringResource(R.string.remote_pair_err_not_allowed)
    RemotePairFailure.FULL -> stringResource(R.string.remote_pair_err_full)
    RemotePairFailure.SLOW_DOWN -> stringResource(R.string.remote_pair_err_slow)
    RemotePairFailure.NOT_CONNECTED -> stringResource(R.string.remote_problem_not_connected)
    RemotePairFailure.NO_PEER -> stringResource(R.string.remote_problem_no_peer, name)
    RemotePairFailure.UNREACHABLE ->
        stringResource(if (error.online) R.string.remote_problem_not_accepting else R.string.remote_problem_offline, name)
    RemotePairFailure.NOT_STORED -> stringResource(R.string.remote_pair_err_not_stored)
    RemotePairFailure.OTHER_DEVICE -> stringResource(R.string.remote_pair_err_other_device, error.other)
    RemotePairFailure.NOT_A_CODE -> stringResource(R.string.remote_pair_err_not_code)
    RemotePairFailure.FAILED -> stringResource(R.string.remote_pair_err_other)
}

/** The parent's language for a window opened from the page: it would otherwise answer in the system's. */
@Composable
internal fun InPageLanguage(locals: io.github.bropines.tailscaled.admin.settings.ParentLocals, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalContext provides locals.context,
        androidx.compose.ui.platform.LocalConfiguration provides locals.configuration,
        androidx.compose.ui.platform.LocalResources provides locals.resources,
        content = content
    )
}
