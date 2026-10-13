package io.github.bropines.tailscaled.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.REMOTE_CLASS_OWNER
import io.github.bropines.tailscaled.core.RemoteBlocker
import io.github.bropines.tailscaled.core.RemoteCode
import io.github.bropines.tailscaled.core.RemoteDurationUnit
import io.github.bropines.tailscaled.core.RemoteEvent
import io.github.bropines.tailscaled.core.RemoteJson
import io.github.bropines.tailscaled.core.RemoteManage
import io.github.bropines.tailscaled.core.RemoteOutcome
import io.github.bropines.tailscaled.core.RemotePairedDevice
import io.github.bropines.tailscaled.core.RemoteReach
import io.github.bropines.tailscaled.core.RemoteRevoked
import io.github.bropines.tailscaled.core.RemoteState
import io.github.bropines.tailscaled.core.remoteCodeGroups
import io.github.bropines.tailscaled.core.remoteCountdown
import io.github.bropines.tailscaled.core.remoteDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/*
 * Remote management, the managed device's own screen (core/RemoteManage.kt; the bridge's
 * appctr/remote.go is the model): the switch, a pairing code to show, who is paired and what
 * they changed. A page, not an Activity — whoever owns the settings shows it.
 *
 * Made for a television first: every control is one stop for the remote and OK does its one
 * thing; nothing waits for a long press. An explanation that would be tapped open on a phone
 * unfolds while the remote is on it. On a phone it is one column, the same content.
 *
 * What it never hides: with the native TUN engine or Root Mode's own interface nobody can
 * connect, and then it says so and offers no code; pairing always takes a code from here.
 */

/** A line about the last thing done on this screen that did not go as asked. */
enum class RemoteNotice {
    /** A pairing was removed and the removal is not on disk yet. */
    NOT_SAVED,
    /** No code could be made. */
    NO_CODE,
    /** The switch could not be stored. */
    NO_SWITCH,
}

/** Everything the screen draws. */
@Immutable
data class RemoteManagedUi(
    /** The switch as stored on this device. */
    val enabled: Boolean = false,
    val state: RemoteState = RemoteState(),
    val reach: RemoteReach = RemoteReach.OFF,
    /** This node runs under tags: nobody is its owner. */
    val tagged: Boolean = false,
    val devices: List<RemotePairedDevice> = emptyList(),
    val log: List<RemoteEvent> = emptyList(),
    val logOpen: Boolean = false,
    val notice: RemoteNotice? = null,
    /** What "used 2 h ago" is measured against, milliseconds. */
    val now: Long = 0,
)

/** What the screen can ask for. */
interface RemoteManagedActions {
    fun setEnabled(on: Boolean)
    /** A fresh code, replacing the one on show. */
    fun showCode()
    fun hideCode()
    fun revoke(nodeId: String)
    fun revokeAll()
    fun toggleLog()

    object None : RemoteManagedActions {
        override fun setEnabled(on: Boolean) {}
        override fun showCode() {}
        override fun hideCode() {}
        override fun revoke(nodeId: String) {}
        override fun revokeAll() {}
        override fun toggleLog() {}
    }
}

/**
 * How often the bridge is asked: every second while a code counts down, sooner while the
 * service is still applying the switch, otherwise seldom.
 */
private const val POLL_WITH_CODE_MS = 1_000L
private const val POLL_APPLYING_MS = 500L
private const val APPLYING_POLLS = 20
private const val POLL_IDLE_MS = 3_000L
/** The lists follow the bridge's revision; this is how often they are read without one, for "left". */
private const val LIST_EVERY_POLLS = 20

/**
 * The page. Reads the bridge while it is on screen and withdraws a pairing code when it is
 * left or the app goes to the background: a code is for someone looking at this screen.
 */
@Composable
fun RemoteManagedScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf(RemoteManagedUi(now = System.currentTimeMillis())) }
    // The preview renderer has no bridge: it draws RemoteManagedContent with a made-up state.
    val live = !LocalInspectionMode.current && LocalDemo.current == null

    // Counts what was done on this screen: a reading that started before the last of it is old news.
    val changes = remember { AtomicInteger() }

    suspend fun refresh(lists: Boolean) {
        val before = ui
        val at = changes.get()
        val read = withContext(Dispatchers.IO) { readManaged(context, before, lists) }
        if (at != changes.get()) return
        // The bridge's word replaces everything but what belongs to the screen itself.
        ui = read.copy(logOpen = ui.logOpen, notice = ui.notice)
    }

    if (live) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        // Only while the page is on screen: nothing is asked of the bridge from the background.
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var polls = 0
                var applying = 0
                while (true) {
                    refresh(lists = polls % LIST_EVERY_POLLS == 0)
                    polls++
                    // The service applies the switch within a moment; if it does not, stop hurrying.
                    applying = if (ui.reach == RemoteReach.APPLYING || ui.reach == RemoteReach.CLOSING) applying + 1 else 0
                    delay(
                        when {
                            applying in 1..APPLYING_POLLS -> POLL_APPLYING_MS
                            ui.state.pairing != null -> POLL_WITH_CODE_MS
                            else -> POLL_IDLE_MS
                        }
                    )
                }
            }
        }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) Appctr.remoteCancelPairing()
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                Appctr.remoteCancelPairing()
            }
        }
    }

    val actions = remember(context, scope) {
        object : RemoteManagedActions {
            fun change(work: () -> RemoteNotice?) {
                changes.incrementAndGet()
                scope.launch {
                    val notice = withContext(Dispatchers.IO) { work() }
                    changes.incrementAndGet()
                    ui = ui.copy(notice = notice)
                    refresh(lists = true)
                }
            }

            override fun setEnabled(on: Boolean) {
                // The switch answers at once; the endpoint follows when the service has applied it.
                val reach = if (on) RemoteReach.APPLYING else RemoteReach.of(false, ui.state) { RemoteBlocker.OTHER }
                ui = ui.copy(enabled = on, reach = reach)
                change { if (RemoteManage.setEnabled(context, on)) null else RemoteNotice.NO_SWITCH }
            }

            override fun showCode() = change {
                if (RemoteJson.code(Appctr.remoteStartPairing()).ok) null else RemoteNotice.NO_CODE
            }

            override fun hideCode() = change { Appctr.remoteCancelPairing(); null }

            override fun revoke(nodeId: String) = change { noticeOf(RemoteJson.revoked(Appctr.remoteRevoke(nodeId))) }

            override fun revokeAll() = change { noticeOf(RemoteJson.revoked(Appctr.remoteRevokeAll())) }

            override fun toggleLog() {
                ui = ui.copy(logOpen = !ui.logOpen)
            }
        }
    }
    // A television's top bar has no arrow: the remote's Back key is the way out.
    BackHandler(onBack = onBack)
    RemoteManagedContent(ui, actions, onBack)
}

private fun noticeOf(answer: RemoteRevoked): RemoteNotice? =
    if (answer.error == RemoteRevoked.NOT_SAVED) RemoteNotice.NOT_SAVED else null

/** One reading of the bridge. Blocking; [lists] reads the paired devices and the log whatever the revision. */
private fun readManaged(context: android.content.Context, before: RemoteManagedUi, lists: Boolean): RemoteManagedUi {
    val enabled = RemoteManage.isEnabled(context)
    val state = RemoteJson.state(Appctr.remoteStateJSON())
    val changed = lists || state.rev != before.state.rev
    return RemoteManagedUi(
        enabled = enabled,
        state = state,
        reach = RemoteReach.of(enabled, state) { RemoteManage.blockedBy(context) },
        tagged = if (changed) selfTagged() else before.tagged,
        devices = if (changed) RemoteJson.paired(Appctr.remotePairedJSON()) else before.devices,
        log = if (changed) RemoteJson.actions(Appctr.remoteActionsJSON()) else before.log,
        now = System.currentTimeMillis(),
    )
}

/** Whether this node runs under ACL tags, from the daemon's status; false when it does not say. */
private fun selfTagged(): Boolean = runCatching {
    if (!Appctr.isRunning()) return false
    val self = AppJson.parseToJsonElement(Appctr.getStatusJSON(false)).jsonObject["Self"] as? JsonObject
    (self?.get("Tags") as? JsonArray)?.isNotEmpty() == true
}.getOrDefault(false)

@Composable
internal fun RemoteManagedContent(ui: RemoteManagedUi, actions: RemoteManagedActions, onBack: () -> Unit) {
    val layout = rememberWindowLayout()
    // Resolved here: the confirmation is a window of its own (see wrapContextWithLocale).
    val confirmTitle = stringResource(R.string.remote_revoke_all_title)
    val confirmText = stringResource(R.string.remote_revoke_all_text)
    val confirmYes = stringResource(R.string.remote_revoke_all)
    val confirmNo = stringResource(R.string.action_cancel)
    var confirmAll by remember { mutableStateOf(false) }

    val first: @Composable ColumnScope.() -> Unit = {
        SwitchCard(ui.enabled, actions::setEnabled)
        if (ui.notice == RemoteNotice.NO_SWITCH) NoticeLine(stringResource(R.string.remote_switch_failed))
        // Off is not claimed before the endpoint is closed.
        if (ui.reach == RemoteReach.CLOSING) {
            Text(
                stringResource(R.string.remote_reach_closing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ReachCard(ui.reach, ui.state.port)
        if (ui.enabled) PairCard(ui, actions, wide = !layout.isPhone)
    }
    // Pairings outlive the switch: they are listed, and can be revoked, while it is off too.
    val hasSecond = ui.enabled || ui.devices.isNotEmpty()
    val second: @Composable ColumnScope.() -> Unit = {
        DevicesCard(ui, onRevoke = actions::revoke, onRevokeAll = { confirmAll = true })
        LogCard(ui.log, ui.logOpen, ui.now, actions::toggleLog)
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.remote_title),
                subtitle = stringResource(R.string.remote_experimental),
                onBack = onBack
            )
        }
    ) { padding ->
        val gap = Arrangement.spacedBy(12.dp)
        if (layout.listDetail && hasSecond) {
            Row(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = layout.margin),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp).tvFocusGroup(),
                    verticalArrangement = gap,
                    content = first
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp).tvFocusGroup(),
                    verticalArrangement = gap,
                    content = second
                )
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) {
                ReadableWidth {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = layout.margin, vertical = 8.dp),
                        verticalArrangement = gap
                    ) {
                        first()
                        if (hasSecond) second()
                    }
                }
            }
        }
    }

    if (confirmAll) {
        AppAlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(confirmTitle) },
            text = { Text(confirmText) },
            confirmButton = {
                TextButton(
                    onClick = { confirmAll = false; actions.revokeAll() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(confirmYes) }
            },
            // A destructive question opens on the answer that changes nothing.
            dismissButton = { TextButton(onClick = { confirmAll = false }, modifier = Modifier.tvInitialFocus()) { Text(confirmNo) } }
        )
    }
}

/** The container every block of this screen stands in: a card on the screen's background. */
@Composable
private fun RemoteCard(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                action?.invoke()
            }
            Spacer(Modifier.height(if (action == null) 12.dp else 4.dp))
            content()
        }
    }
}

/**
 * A folded explanation. On a phone it is [HelpText] as everywhere. A television has nothing
 * to tap it open with, so there it is a stop for the remote and reads in full while the
 * remote is on it.
 */
@Composable
private fun FoldedHelp(text: String, modifier: Modifier = Modifier) {
    val open = remember(text) { mutableStateOf(false) }
    if (!isTv) {
        HelpText(text, modifier, expanded = open)
        return
    }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { open.value = focused }
    Box(
        modifier
            .tvFocusRing(source, MaterialTheme.shapes.small)
            .focusable(interactionSource = source)
            .padding(6.dp)
            // The text's own tap target is not a second stop.
            .tvNoFocus()
    ) { HelpText(text, expanded = open) }
}

/** The switch: the whole card turns it, and its explanation unfolds under the remote. */
@Composable
private fun SwitchCard(on: Boolean, onChange: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val help = remember { mutableStateOf(false) }
    if (isTv) {
        val focused by source.collectIsFocusedAsState()
        LaunchedEffect(focused) { help.value = focused }
    }
    val shape = MaterialTheme.shapes.large
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .tvInitialFocus()
            .tvFocusRing(source, shape)
            .clip(shape)
            .toggleable(
                value = on,
                interactionSource = source,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = onChange
            )
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SettingsRemote, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.remote_switch_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                HelpText(
                    stringResource(R.string.remote_switch_help),
                    modifier = Modifier.tvNoFocus(),
                    expanded = help,
                    inClickableRow = true
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = null)
        }
    }
}

/** Why nobody can connect right now, and what to switch. Nothing while they can. */
@Composable
private fun ReachCard(reach: RemoteReach, port: Int) {
    val text = when (reach) {
        RemoteReach.OFF, RemoteReach.CLOSING, RemoteReach.APPLYING, RemoteReach.READY -> return
        RemoteReach.NOT_RUNNING -> stringResource(R.string.remote_reach_not_running)
        RemoteReach.NATIVE_TUN -> stringResource(R.string.remote_reach_native_tun)
        RemoteReach.ROOT_TUN -> stringResource(R.string.remote_reach_root_tun)
        RemoteReach.OTHER_MODE -> stringResource(R.string.remote_reach_other)
        RemoteReach.PORT_BUSY -> stringResource(R.string.remote_reach_port, port)
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.remote_reach_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun NoticeLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** "Pair a device": the button, or the code with its QR, what is left of it and who guessed wrong. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PairCard(ui: RemoteManagedUi, actions: RemoteManagedActions, wide: Boolean) {
    val code = ui.state.pairing
    RemoteCard(stringResource(R.string.remote_pair_title)) {
        if (code == null) {
            Button(
                onClick = actions::showCode,
                enabled = ui.reach.canPair,
                shape = MaterialTheme.shapes.medium
            ) { Text(stringResource(R.string.remote_pair_show)) }
            outcomeText(ui.state.lastPairing)?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ui.notice == RemoteNotice.NO_CODE) {
                Spacer(Modifier.height(8.dp))
                NoticeLine(stringResource(R.string.remote_pair_failed))
            }
        } else {
            val qr: @Composable (Dp) -> Unit = { side ->
                QrCodeImage(code.qr, Modifier.size(side), contentDescription = stringResource(R.string.remote_pair_qr))
            }
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { CodeFacts(code, TextAlign.Start) }
                    Spacer(Modifier.width(16.dp))
                    qr(168.dp)
                }
            } else {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CodeFacts(code, TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    qr(208.dp)
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions::showCode, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.remote_pair_new))
                }
                TextButton(onClick = actions::hideCode, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.remote_pair_hide))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FoldedHelp(stringResource(R.string.remote_pair_hint))
    }
}

/** The six digits, large, and under them the time and the guesses the code has left. */
@Composable
private fun CodeFacts(code: RemoteCode, align: TextAlign) {
    val spoken = code.code.toCharArray().joinToString(" ")
    Text(
        remoteCodeGroups(code.code),
        fontSize = 44.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        softWrap = false,
        textAlign = align,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken }
    )
    // Two facts, a line each: side by side they wrap mid-sentence in the narrow column beside the QR.
    for (fact in listOf(
        stringResource(R.string.remote_pair_left, remoteCountdown(code.left)),
        pluralStringResource(R.plurals.remote_pair_attempts, code.attemptsLeft, code.attemptsLeft),
    )) {
        Text(
            fact,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            modifier = Modifier.fillMaxWidth()
        )
    }
    code.lastWrong?.let { wrong ->
        val at = remember(wrong.time) { clockTime(wrong.time) }
        Spacer(Modifier.height(6.dp))
        // Said calmly: a wrong digit is usually a slip, and the code has guesses to spare.
        Text(
            if (wrong.login.isEmpty()) stringResource(R.string.remote_pair_wrong, wrong.name, at)
            else stringResource(R.string.remote_pair_wrong_login, wrong.name, wrong.login, at),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun clockTime(unixSeconds: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(unixSeconds * 1000))

/** How the last code ended, in a sentence; null when there is nothing worth saying. */
@Composable
private fun outcomeText(outcome: RemoteOutcome?): String? = when (outcome?.result) {
    RemoteOutcome.PAIRED -> stringResource(
        if (outcome.role == REMOTE_CLASS_OWNER) R.string.remote_outcome_owner else R.string.remote_outcome_guest,
        outcome.name
    )
    RemoteOutcome.EXPIRED -> stringResource(R.string.remote_outcome_expired)
    RemoteOutcome.LOCKED -> stringResource(R.string.remote_outcome_locked, outcome.name)
    else -> null
}

/** [seconds] as "21 h" or "35 min". */
@Composable
internal fun remoteDurationText(seconds: Long): String {
    val (count, unit) = remoteDuration(seconds)
    return stringResource(
        when (unit) {
            RemoteDurationUnit.DAYS -> R.string.remote_dur_days
            RemoteDurationUnit.HOURS -> R.string.remote_dur_hours
            RemoteDurationUnit.MINUTES -> R.string.remote_dur_minutes
        },
        count.toInt()
    )
}

/** Owner or guest, as a small tag. */
@Composable
internal fun RemoteRoleTag(owner: Boolean, modifier: Modifier = Modifier, text: String? = null) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (owner) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (owner) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
    ) {
        Text(
            text ?: stringResource(if (owner) R.string.remote_role_owner else R.string.remote_role_guest),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** Who may manage this device, each with its Revoke button in plain sight. */
@Composable
private fun DevicesCard(ui: RemoteManagedUi, onRevoke: (String) -> Unit, onRevokeAll: () -> Unit) {
    RemoteCard(
        stringResource(R.string.remote_paired_title),
        action = if (ui.devices.isEmpty()) null else {
            {
                TextButton(
                    onClick = onRevokeAll,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.remote_revoke_all)) }
            }
        }
    ) {
        if (ui.devices.isEmpty()) {
            Text(
                stringResource(R.string.remote_paired_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ui.devices.forEach { device ->
            DeviceRow(device, ui.now, onRevoke = { onRevoke(device.nodeId) })
            Spacer(Modifier.height(8.dp))
        }
        if (ui.notice == RemoteNotice.NOT_SAVED) NoticeLine(stringResource(R.string.remote_revoke_not_saved))
        else if (ui.state.unsaved) NoticeLine(stringResource(R.string.remote_unsaved))
        Spacer(Modifier.height(4.dp))
        if (ui.tagged) {
            Text(
                stringResource(R.string.remote_paired_tagged),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
        }
        val hours = (ui.state.policy.guestSeconds / 3600).toInt()
        if (hours > 0) {
            FoldedHelp(stringResource(R.string.remote_paired_help, pluralStringResource(R.plurals.remote_hours, hours, hours)))
        }
    }
}

@Composable
private fun DeviceRow(device: RemotePairedDevice, now: Long, onRevoke: () -> Unit) {
    val context = LocalContext.current
    val term = if (device.left < 0) stringResource(R.string.remote_paired_until_revoked)
    else stringResource(R.string.remote_paired_expires_in, remoteDurationText(device.left))
    val used = if (device.lastUsed <= 0) stringResource(R.string.remote_paired_never_used)
    else stringResource(R.string.remote_paired_last_used, agoText(context, device.lastUsed * 1000, now))
    val revokeLabel = stringResource(R.string.remote_revoke_named, device.name)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    RemoteRoleTag(device.owner)
                }
                if (device.login.isNotEmpty()) {
                    Text(
                        device.login,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "$term · $used",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onRevoke,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.semantics { contentDescription = revokeLabel }
            ) { Text(stringResource(R.string.remote_revoke)) }
        }
    }
}

/** How much of the log is drawn: the bridge keeps a hundred lines, a screen needs the recent ones. */
private const val LOG_LINES = 30

/** What paired devices changed, folded until asked for. */
@Composable
private fun LogCard(log: List<RemoteEvent>, open: Boolean, now: Long, onToggle: () -> Unit) {
    val context = LocalContext.current
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.large
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .tvFocusRing(source, shape)
                    .clip(shape)
                    .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.remote_log_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (open) {
                // A television cannot drag a page: there the lines scroll in a pane of their own,
                // which the remote stops on and moves with Up and Down.
                val lines = if (isTv) {
                    val scroll = rememberScrollState()
                    Modifier.heightIn(max = 200.dp).tvScrollKeys(scroll).verticalScroll(scroll)
                } else Modifier
                Column(
                    lines.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (log.isEmpty()) {
                        Text(
                            stringResource(R.string.remote_log_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    log.take(LOG_LINES).forEach { event ->
                        Column {
                            Text(eventText(event), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOf(event.name, agoText(context, event.time * 1000, now)).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One line of the log: what happened, the bridge's own detail after it, and whether it was done. */
@Composable
private fun eventText(event: RemoteEvent): String {
    val what = when (event.op) {
        "paired" -> stringResource(R.string.remote_op_paired)
        "pair_failed" -> stringResource(R.string.remote_op_pair_failed)
        "pair_locked" -> stringResource(R.string.remote_op_pair_locked)
        "unpaired" -> stringResource(R.string.remote_op_unpaired)
        "revoked" -> stringResource(R.string.remote_op_revoked)
        "revoked_all" -> stringResource(R.string.remote_op_revoked_all)
        "expired" -> stringResource(R.string.remote_op_expired)
        "reset" -> stringResource(R.string.remote_op_reset)
        "exit_node" -> stringResource(R.string.remote_op_exit_node)
        "setting" -> stringResource(R.string.remote_op_setting)
        "action" -> stringResource(R.string.remote_op_action)
        "file_deleted" -> stringResource(R.string.remote_op_file_deleted)
        "denied" -> stringResource(R.string.remote_op_denied)
        else -> event.op
    }
    // The detail is the bridge's: a setting's key and value, a node's or a file's name.
    val detailed = event.op in CHANGES || event.op == "denied"
    val line = if (detailed && event.detail.isNotEmpty()) "$what: ${event.detail}" else what
    return if (event.op in CHANGES && !event.ok) "$line — ${stringResource(R.string.remote_log_failed)}" else line
}

/** The log's lines that are changes asked of this device, which can fail. */
private val CHANGES = setOf("exit_node", "setting", "action", "file_deleted")
