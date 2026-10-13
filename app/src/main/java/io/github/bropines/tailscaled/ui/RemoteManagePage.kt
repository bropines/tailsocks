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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
                        ManageIconButton(
                            Icons.Default.Refresh,
                            stringResource(R.string.action_refresh),
                            onClick = actions::refresh,
                            enabled = !ui.busy && !ui.loading && ui.guard?.phase?.active != true,
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (ui) {
                is RemoteManageUi.Opening -> Narrow(layout) { AskingNote(ui.name) }
                is RemoteManageUi.Pairing -> Pairing(ui, actions, hooks.scan, layout)
                is RemoteManageUi.Forgotten -> Narrow(layout) {
                    Text(stringResource(R.string.remote_forget_local, ui.name), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = actions::close, shape = MaterialTheme.shapes.medium, modifier = Modifier.tvInitialFocus()) {
                        Text(stringResource(R.string.action_close))
                    }
                }
                is RemoteManageUi.Managing -> Managing(ui, actions, hooks, layout, opened)
            }
            if (ui.asking) AskingLine(ui.name, Modifier.align(Alignment.TopCenter))
        }
    }
}

/**
 * The device is being asked: a thin line under the bar, over the page and not in its place.
 * What the page already has to show stays where it is, and nothing moves when the line goes.
 */
@Composable
private fun AskingLine(name: String, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.remote_loading, name)
    LinearProgressIndicator(
        modifier = modifier.fillMaxWidth().height(3.dp).semantics { contentDescription = label },
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        trackColor = Color.Transparent,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
    )
}

/** A short form in the middle of the page: the pairing code, the last word after forgetting. */
@Composable
private fun Narrow(layout: WindowLayout, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                // The page's window draws under the keyboard: the form stays reachable above it.
                .imePadding()
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
    // A television must have something in focus: the page's first action — Identify, where the
    // device offers it, else the first group's header. A banner that comes with the page has a
    // button that takes the focus instead: two that ask at once would leave it to chance.
    val bannerButton = ui.problem != null || ui.guard?.phase == GuardPhase.REVERTED || ui.guard?.phase == GuardPhase.UNCLEAR
    val startHere = remember(status != null) { !bannerButton }
    val identify = startHere && status != null && ui.access.gate(ManagedOp.IDENTIFY).offered
    val first = groups.firstOrNull().takeIf { startHere && !identify }
    val focusOf: @Composable (ManageGroup) -> Modifier = { if (it == first) Modifier.tvInitialFocus() else Modifier }
    // The pairing is gone there, and here with it: nothing of the device is this device's to show.
    val unpaired = ui.problem == ManageProblem.REVOKED || ui.problem == ManageProblem.EXPIRED

    Column(Modifier.fillMaxSize()) {
        Banners(ui, actions, layout, onForget = { asked = ManageAsk.Forget })
        when {
            unpaired -> Unit
            // Nothing of the device to show — it has not answered yet, or never will: what this
            // device knows by itself, and the one thing that needs no answer.
            status == null || groups.isEmpty() -> Narrow(layout) {
                PeerHeader(ui.name, ui.tv, ui.peer, tags = pairingTags(ui))
                if (ui.loading && ui.problem == null) AskingNote(ui.name)
                // Forgetting needs no answer, so it is there from the start. For a device that left
                // the tailnet it is all there is, and the banner offers it: here is only why.
                if (ui.problem == ManageProblem.NO_PEER) HelpText(stringResource(R.string.rmanage_one_tailnet))
                else ForgetButton(ui) { asked = ManageAsk.Forget }
            }
            // What the device last said stays readable while it cannot be asked, dimmed: it is not news.
            layout.listDetail -> Row(
                Modifier.fillMaxSize().padding(horizontal = layout.margin).alpha(if (ui.old) 0.5f else 1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // The device and how it is connected on one side, everything it holds on the other.
                val (left, right) = groups.partition { it == ManageGroup.CONNECTION }
                PageColumn(Modifier.weight(1f)) {
                    DeviceHeader(ui, status, actions, start = identify)
                    left.forEach { card(it, focusOf(it)) }
                }
                PageColumn(Modifier.weight(1f)) { right.forEach { card(it, focusOf(it)) } }
            }
            else -> ReadableWidth {
                PageColumn(Modifier.fillMaxSize().padding(horizontal = layout.margin).alpha(if (ui.old) 0.5f else 1f)) {
                    DeviceHeader(ui, status, actions, start = identify)
                    groups.forEach { card(it, focusOf(it)) }
                }
            }
        }
    }

    ManageWindows(ui, actions, hooks, asked, onAsked = { asked = it })
}

@Composable
private fun ForgetButton(ui: RemoteManageUi.Managing, onClick: () -> Unit) {
    ManageButton(stringResource(R.string.remote_forget), onClick = onClick, enabled = !ui.busy, outlined = true)
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
private fun Banners(ui: RemoteManageUi.Managing, actions: RemoteManageActions, layout: WindowLayout, onForget: () -> Unit) {
    val problem = ui.problem
    val guard = ui.guard
    if (problem == null && guard == null) return
    BannerColumn(layout) {
        // While a change's own banner says the device is silent, "offline" beside it says the same twice.
        val guardSpeaks = guard?.phase == GuardPhase.SILENT || guard?.phase == GuardPhase.REVERTED_AWAY
        val action = ui.problemAction
        if (problem != null && action != null && !(guardSpeaks && problem.stale)) {
            ProblemBanner(
                text = problemText(problem, ui.name, ui.problemDetail, ui.peer, ui.now, starting = ui.starting),
                action = action,
                // Asked again, or started: the button waits with the page, and keeps the remote.
                waiting = ui.loading || ui.starting || ui.busy,
                layout = layout,
                // Forgetting is asked about first, like anywhere else on the page.
                onAction = { if (action == ManageProblemAction.FORGET) onForget() else actions.resolve(action) },
            )
        }
        if (guard != null) GuardBanner(ui, guard, actions)
    }
}

/** Where the banners stand: pinned under the bar, as wide as the page's own column. */
@Composable
private fun BannerColumn(layout: WindowLayout, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .then(if (layout.listDetail) Modifier else Modifier.widthIn(max = ReadableContentWidth))
                .fillMaxWidth()
                .padding(horizontal = layout.margin)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/** What is in the way, and the one thing to do about it. [waiting]: that thing is being done. */
@Composable
private fun ProblemBanner(text: String, action: ManageProblemAction, waiting: Boolean, layout: WindowLayout, onAction: () -> Unit) {
    Banner(
        text = text,
        warm = true,
        icon = Icons.Default.Warning,
        action = problemActionText(action),
        actionWaiting = waiting,
        wide = layout.listDetail,
        onAction = onAction,
    )
}

/**
 * One sentence, and at most one thing to do about it. [warm] for what needs a person, calm for
 * what is under way. [actionWaiting]: the thing is being done — the button stands still, and
 * on a television stays a stop for the remote, which would otherwise be dropped with it.
 * [wide]: the banner spans a tablet or a television, where a long button has room beside it.
 */
@Composable
private fun Banner(
    text: String,
    warm: Boolean,
    icon: ImageVector? = null,
    working: Boolean = false,
    action: String? = null,
    actionWaiting: Boolean = false,
    wide: Boolean = false,
    onAction: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (warm) scheme.tertiaryContainer else scheme.secondaryContainer,
        contentColor = if (warm) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        // A short sentence has its button beside it; a long one would be squeezed into a
        // column of five lines by it, so there the button stands under the text. On a phone a
        // button of three words squeezes even a short sentence, and stands under it too.
        val beside = action != null && text.length <= BANNER_BESIDE_MAX && (wide || action.length <= BANNER_ACTION_BESIDE_MAX)
        // ManageButton's manners: a button that waits looks disabled and does not answer, and on a
        // television it is still where the remote stands.
        val deaf = isTv && actionWaiting
        val colors = ButtonDefaults.buttonColors().let { if (deaf) it.copy(containerColor = it.disabledContainerColor, contentColor = it.disabledContentColor) else it }
        val press = { if (!actionWaiting) onAction() }
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                else if (icon != null) Icon(icon, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (action != null && beside) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = press, enabled = !actionWaiting || deaf, colors = colors, shape = MaterialTheme.shapes.medium, modifier = Modifier.tvInitialFocus()) { Text(action) }
                }
            }
            if (action != null && !beside) {
                Button(
                    onClick = press,
                    enabled = !actionWaiting || deaf,
                    colors = colors,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.End).padding(top = 8.dp).tvInitialFocus()
                ) { Text(action) }
            }
        }
    }
}

/** The longest sentence, in characters, that keeps its button beside it. */
private const val BANNER_BESIDE_MAX = 90
/** The longest button, in characters, that stands beside a sentence on a phone: "Pair again" does, "Start TailSocks" does not. */
private const val BANNER_ACTION_BESIDE_MAX = 14

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

/**
 * A problem's one sentence. [peer] and [now] add since when a device has been offline;
 * [paired] tells a device that left the tailnet from one that was never seen in it.
 */
@Composable
internal fun problemText(
    problem: ManageProblem,
    name: String,
    detail: String,
    peer: ManagePeer? = null,
    now: Long = 0,
    paired: Boolean = true,
    starting: Boolean = false,
): String = when (problem) {
    ManageProblem.NOT_CONNECTED ->
        stringResource(if (starting) R.string.rmanage_starting_own else R.string.remote_problem_not_connected)
    ManageProblem.NO_PEER -> stringResource(if (paired) R.string.rmanage_problem_left else R.string.remote_problem_no_peer, name)
    ManageProblem.OFFLINE -> {
        val seen = peer?.lastSeen ?: 0
        if (seen > 0 && now > 0) stringResource(R.string.rmanage_problem_offline_since, name, agoText(LocalContext.current, seen, now))
        else stringResource(R.string.remote_problem_offline, name)
    }
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
        ManageProblemAction.START -> R.string.rmanage_start_own
        ManageProblemAction.FORGET -> R.string.remote_forget
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
private fun DeviceHeader(ui: RemoteManageUi.Managing, status: ManagedStatus, actions: RemoteManageActions, start: Boolean = false) {
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
                    // Under what the device says of itself, what the tailnet says of it now.
                    peerLine(ui.peer)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HeaderTag(roleTag(ui), ink)
                if (access.levelKnown) HeaderTag(levelText(access.level), ink)
                // Why the page is dimmed: it shows what the device said then.
                if (ui.old && ui.heardAt > 0) HeaderTag(stringResource(R.string.rmanage_heard, agoText(LocalContext.current, ui.heardAt, ui.now)), ink)
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
                        // Where the remote starts on the page.
                        modifier = if (start) Modifier.tvInitialFocus() else Modifier,
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

/** A tag on a header's own colour: one in a container colour of its own would fight it. */
@Composable
private fun HeaderTag(text: String, ink: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = ink.copy(alpha = 0.14f), contentColor = ink) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** What this device is to the managed one: an owner, or a guest and for how long. */
@Composable
private fun roleTag(ui: RemoteManageUi.Managing): String =
    if (ui.owner) stringResource(R.string.remote_role_owner)
    else if (ui.left < 0) stringResource(R.string.remote_role_guest)
    else stringResource(R.string.remote_guest_left, remoteDurationText(ui.left))

/** A day in the app's own language, with its year when that is not this one. */
@Composable
internal fun dayText(millis: Long, now: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(millis, now, locale) {
        val sameYear = SimpleDateFormat("yyyy", locale).let { it.format(Date(millis)) == it.format(Date(now)) }
        SimpleDateFormat(if (sameYear) "d MMM" else "d MMM yyyy", locale).format(Date(millis))
    }
}

/** Who this device is to the managed one by the pairing it keeps: the class, and since when. */
@Composable
private fun pairingTags(ui: RemoteManageUi.Managing): List<String> = listOfNotNull(
    roleTag(ui),
    if (ui.pairedAt > 0) stringResource(R.string.rmanage_paired_since, dayText(ui.pairedAt, ui.now)) else null,
)

@Composable
private fun presenceText(presence: RemotePresence): String? = when (presence) {
    RemotePresence.ONLINE -> stringResource(R.string.rmanage_device_online)
    RemotePresence.OFFLINE -> stringResource(R.string.rmanage_device_offline)
    RemotePresence.GONE -> stringResource(R.string.rmanage_device_gone)
    RemotePresence.UNKNOWN -> null
}

/**
 * What this device's own network map says of the peer, in one short line under what the device
 * says of itself: whether it is there, and its address. Since when it is not is the banner's to
 * say. Null when the map was not read or cannot say.
 */
@Composable
private fun peerLine(peer: ManagePeer?): String? {
    val presence = presenceText(peer?.presence ?: return null) ?: return null
    return listOfNotNull(presence, peer.address.takeIf { it.isNotEmpty() }).joinToString(" · ")
}

/**
 * The device before it has said anything to this page, or when it never will: what this
 * device knows by itself. Whether the tailnet holds it and at what address — and in [tags] who
 * this device is to it by the stored pairing. Nobody was asked for any of it. Since when a
 * device has been offline is the banner's to say.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeerHeader(name: String, tv: Boolean, peer: ManagePeer?, tags: List<String> = emptyList()) {
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onSurfaceVariant
    val presence = peer?.presence ?: RemotePresence.UNKNOWN
    Surface(shape = MaterialTheme.shapes.extraLarge, color = scheme.surfaceContainerHigh, contentColor = ink, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                    Surface(color = ink.copy(alpha = 0.12f), modifier = Modifier.matchParentSize()) {}
                    Icon(deviceGlyph("", tv), null, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val mark = when (presence) {
                            RemotePresence.ONLINE -> Icons.Default.Wifi
                            RemotePresence.OFFLINE -> Icons.Default.WifiOff
                            RemotePresence.GONE -> Icons.Default.LinkOff
                            RemotePresence.UNKNOWN -> null
                        }
                        if (mark != null) {
                            Icon(mark, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        // Where the map cannot say, the device is at least named.
                        Text(
                            presenceText(presence) ?: name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val address = peer?.address.orEmpty()
                    if (address.isNotEmpty()) {
                        Text(
                            address,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ink.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { HeaderTag(it, ink) }
                }
            }
        }
    }
}

/** Said under the header while the first answer is on its way; the line under the bar is what moves. */
@Composable
private fun AskingNote(name: String) {
    Text(stringResource(R.string.remote_loading, name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ─── Pairing ─────────────────────────────────────────────────────────────────────────────────

/**
 * Not paired. The code's field is there only for a device that has just said it takes a
 * pairing. Until it has, and when it cannot, the page shows what this device knows of it by
 * itself, and a banner says what is in the way: an error under the field is about the code.
 */
@Composable
private fun Pairing(ui: RemoteManageUi.Pairing, actions: RemoteManageActions, onScan: (() -> Unit)?, layout: WindowLayout) {
    val problem = ui.problem
    val action = ui.problemAction
    Column(Modifier.fillMaxSize()) {
        if (problem != null && action != null) {
            BannerColumn(layout) {
                ProblemBanner(
                    text = problemText(problem, ui.name, "", ui.peer, ui.now, paired = false, starting = ui.starting),
                    action = action,
                    waiting = ui.checking || ui.starting,
                    layout = layout,
                    onAction = { actions.resolve(action) },
                )
            }
        }
        Narrow(layout) {
            if (ui.form) {
                PairingBody(ui, actions, onScan)
            } else {
                // Where the tailnet has the device. Of one it does not hold, and when this device
                // cannot say, there is nothing to add to the banner.
                val held = ui.peer?.presence == RemotePresence.ONLINE || ui.peer?.presence == RemotePresence.OFFLINE
                if (held) PeerHeader(ui.name, ui.tv, ui.peer)
                if (problem == null) AskingNote(ui.name)
                if (problem == ManageProblem.NO_PEER) HelpText(stringResource(R.string.rmanage_one_tailnet))
            }
        }
    }
}

@Composable
private fun PairingBody(ui: RemoteManageUi.Pairing, actions: RemoteManageActions, onScan: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GroupIcon(deviceGlyph("", ui.tv))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.remote_pair_enter, ui.name), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done)
    if (isTv) {
        // A field in a page takes the arrows the moment the remote lands on it: there it is a
        // row that OK opens as a dialog around the field (ui/PageTextField.kt).
        TvFieldRow(
            value = ui.code,
            onValueChange = actions::setCode,
            label = stringResource(R.string.remote_pair_code_label),
            modifier = Modifier.fillMaxWidth().tvInitialFocus(),
            supportingText = ui.error?.let { pairErrorText(it) },
            isError = ui.error != null,
            keyboardOptions = keyboard,
        )
    } else {
        OutlinedTextField(
            value = ui.code,
            onValueChange = actions::setCode,
            label = { Text(stringResource(R.string.remote_pair_code_label)) },
            singleLine = true,
            enabled = !ui.busy,
            isError = ui.error != null,
            supportingText = ui.error?.let { error -> { Text(pairErrorText(error)) } },
            textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
            keyboardOptions = keyboard,
            keyboardActions = KeyboardActions(onDone = { actions.pair() }),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // The button keeps the remote while the device is asked (ManageButton): the page that
        // follows takes the focus from it, not from nowhere.
        ManageButton(
            stringResource(R.string.remote_pair_action),
            onClick = actions::pair,
            enabled = !ui.busy && RemoteJson.isCode(ui.code),
            primary = true,
            working = ui.busy,
            modifier = Modifier.weight(1f),
        )
        if (onScan != null) {
            ManageButton(
                stringResource(R.string.remote_pair_scan),
                onClick = onScan,
                enabled = !ui.busy,
                outlined = true,
                icon = Icons.Default.QrCodeScanner,
                modifier = Modifier.weight(1f),
            )
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

/** Why the code did not pair. A device that cannot be reached is not said here: it takes the form away. */
@Composable
private fun pairErrorText(error: RemotePairError): String = when (error.failure) {
    RemotePairFailure.WRONG -> error.attemptsLeft?.let { pluralStringResource(R.plurals.remote_pair_err_wrong, it, it) }
        ?: stringResource(R.string.remote_pair_err_wrong_plain)
    RemotePairFailure.LOCKED -> stringResource(R.string.remote_pair_err_locked)
    RemotePairFailure.CLOSED -> stringResource(R.string.remote_pair_err_closed)
    RemotePairFailure.USED -> stringResource(R.string.remote_pair_err_used)
    RemotePairFailure.NOT_ALLOWED -> stringResource(R.string.remote_pair_err_not_allowed)
    RemotePairFailure.FULL -> stringResource(R.string.remote_pair_err_full)
    RemotePairFailure.SLOW_DOWN -> stringResource(R.string.remote_pair_err_slow)
    RemotePairFailure.NOT_STORED -> stringResource(R.string.remote_pair_err_not_stored)
    RemotePairFailure.OTHER_DEVICE -> stringResource(R.string.remote_pair_err_other_device, error.other)
    RemotePairFailure.NOT_A_CODE -> stringResource(R.string.remote_pair_err_not_code)
    RemotePairFailure.NOT_CONNECTED, RemotePairFailure.NO_PEER, RemotePairFailure.UNREACHABLE, RemotePairFailure.FAILED ->
        stringResource(R.string.remote_pair_err_other)
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
