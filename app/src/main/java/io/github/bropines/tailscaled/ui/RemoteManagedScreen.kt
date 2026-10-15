package io.github.bropines.tailscaled.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LockClock
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
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
import io.github.bropines.tailscaled.core.AppPermission
import io.github.bropines.tailscaled.core.REMOTE_CLASS_OWNER
import io.github.bropines.tailscaled.core.RemoteAccess
import io.github.bropines.tailscaled.core.RemoteAccessClass
import io.github.bropines.tailscaled.core.RemoteAccessSetting
import io.github.bropines.tailscaled.core.RemoteCode
import io.github.bropines.tailscaled.core.RemoteDurationUnit
import io.github.bropines.tailscaled.core.RemoteElevate
import io.github.bropines.tailscaled.core.RemoteElevateText
import io.github.bropines.tailscaled.core.RemoteElevationNotice
import io.github.bropines.tailscaled.core.RemoteEvent
import io.github.bropines.tailscaled.core.RemoteJson
import io.github.bropines.tailscaled.core.RemoteLevel
import io.github.bropines.tailscaled.core.RemoteManage
import io.github.bropines.tailscaled.core.RemoteOutcome
import io.github.bropines.tailscaled.core.RemotePairedDevice
import io.github.bropines.tailscaled.core.RemotePromptRules
import io.github.bropines.tailscaled.core.RemoteReach
import io.github.bropines.tailscaled.core.RemoteRevoked
import io.github.bropines.tailscaled.core.RemoteState
import io.github.bropines.tailscaled.core.remoteCodeGroups
import io.github.bropines.tailscaled.core.remoteCountdown
import io.github.bropines.tailscaled.core.remoteDuration
import io.github.bropines.tailscaled.core.tryStartActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/*
 * Remote management, the managed device's own screen (core/RemoteManage.kt, core/RemoteLevel.kt;
 * the bridge's appctr/remote.go is the model): the switch and the three that go with it — full
 * control, guests let in or not, and a guest given full rights for a while or not
 * (core/RemoteElevate.kt) — a pairing code to show, who is paired, what a paired device may do
 * and what they changed. A page, not an Activity — whoever owns the settings shows it.
 *
 * Made for a television first: every control is one stop for the remote and OK does its one
 * thing; nothing waits for a long press. An explanation that would be tapped open on a phone
 * unfolds while the remote is on it. On a phone it is one column, the same content; from a
 * medium window up, what one does is on the left and what one reads on the right.
 *
 * What it never hides: with the native TUN engine or Root Mode's own interface nobody can
 * connect, and then it says so and offers no code; pairing always takes a code from here. And
 * what it never writes itself: the list of what a paired device may do is put together from
 * the lists the endpoint enforces (RemoteAccessJSON, remoteAccessGroups below), so the page
 * cannot promise less than is done from afar.
 */

/** A line about the last thing done on this screen that did not go as asked. */
enum class RemoteNotice {
    /** A pairing was removed and the removal is not on disk yet. */
    NOT_SAVED,
    /** No code could be made. */
    NO_CODE,
    /** The switch could not be stored. */
    NO_SWITCH,
    /** Nor could the one for full control. */
    NO_FULL,
    /** Nor the one for guests. */
    NO_GUESTS,
    /** Nor the one for a guest's full rights for a while. */
    NO_ELEVATE,
    /** Nor the one for network access to files. */
    NO_FILES,
    /** This device's lock could not be asked, so the files stayed closed. */
    FILES_LOCK,
    /** Too many tries at this device's lock: the files stayed closed. */
    FILES_LOCKOUT,
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
    /** Full control, as stored on this device. Never on without [enabled]. */
    val fullControl: Boolean = false,
    /** Whether other people's devices may pair as guests, as stored on this device. */
    val guests: Boolean = true,
    /** Whether a guest may be given full rights for a while here, as stored on this device (core/RemoteElevate.kt). */
    val elevate: Boolean = true,
    /** Network access to this device's files, as stored on this device. Never on without [enabled]. */
    val files: Boolean = false,
    /** This device has a screen lock to confirm opening the files with; without one, the question alone does. */
    val locked: Boolean = true,
    /** What a paired device may do right now, as the bridge enforces it: it follows the two switches a moment later. */
    val access: RemoteAccess = RemoteAccess(),
    val accessOpen: Boolean = false,
)

/** What the screen can ask for. */
interface RemoteManagedActions {
    fun setEnabled(on: Boolean)
    fun setFullControl(on: Boolean) {}
    fun setGuests(allowed: Boolean) {}
    fun setElevate(allowed: Boolean) {}
    /** Closes the files at once; opens them — after the question was answered — with this device's lock, where it has one. */
    fun setFiles(open: Boolean) {}
    /** Opens the question that gives [device] full rights for a while (RemoteElevateActivity). */
    fun elevate(device: RemotePairedDevice) {}
    fun takeBack(nodeId: String) {}
    /** A fresh code, replacing the one on show. */
    fun showCode()
    fun hideCode()
    fun revoke(nodeId: String)
    fun revokeAll()
    fun toggleLog()
    fun toggleAccess() {}

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
    // Cuts the wait between two readings short: something was just asked for on this screen.
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }

    suspend fun refresh(lists: Boolean) {
        val before = ui
        val at = changes.get()
        val read = withContext(Dispatchers.IO) { readManaged(context, before, lists) }
        if (at != changes.get()) return
        // The bridge's word replaces everything but what belongs to the screen itself.
        ui = read.copy(logOpen = ui.logOpen, accessOpen = ui.accessOpen, notice = ui.notice)
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
                    // The service applies a switch within a moment; if it does not, stop hurrying.
                    val behind = ui.reach == RemoteReach.APPLYING || ui.reach == RemoteReach.CLOSING || ui.levelBehind
                    applying = if (behind) applying + 1 else 0
                    val wait = when {
                        applying in 1..APPLYING_POLLS -> POLL_APPLYING_MS
                        ui.state.pairing != null -> POLL_WITH_CODE_MS
                        else -> POLL_IDLE_MS
                    }
                    withTimeoutOrNull(wait) { wake.receive() }
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

    // Opening the files to the network takes this device's own lock, after the question: asked
    // over the screen (RemoteUnlockActivity), and only then is the switch written.
    val unlockTitle = stringResource(R.string.remote_netfiles_prompt_title)
    val unlockText = stringResource(R.string.remote_netfiles_title)
    val unlocked = remember { arrayOfNulls<(RemoteUnlockOutcome) -> Unit>(1) }
    val unlock = rememberLauncherForActivityResult(RemoteUnlock) { outcome ->
        unlocked[0]?.invoke(outcome)
        unlocked[0] = null
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
                    // The service applies it a moment later: look again now, not when the idle wait is over.
                    wake.trySend(Unit)
                }
            }

            override fun setEnabled(on: Boolean) {
                // The switch answers at once; the endpoint follows when the service has applied it.
                val reach = if (on) RemoteReach.APPLYING else RemoteReach.of(false, ui.state)
                ui = ui.copy(enabled = on, reach = reach, fullControl = false, files = false)
                // Full control and the files go off with the switch, whichever way it is turned.
                change { if (RemoteManage.setManaged(context, on)) null else RemoteNotice.NO_SWITCH }
            }

            override fun setFiles(open: Boolean) {
                if (!open) {
                    // Closed at once, and told to the bridge at once: nothing is asked for that.
                    ui = ui.copy(files = false)
                    change { if (RemoteLevel.setFilesOpen(context, false)) null else RemoteNotice.NO_FILES }
                    return
                }
                val write = {
                    ui = ui.copy(files = true)
                    change { if (RemoteLevel.setFilesOpen(context, true)) null else RemoteNotice.NO_FILES }
                }
                // Without a lock the question that was just answered is the confirmation.
                if (!RemoteElevate.deviceSecure(context)) return write()
                unlocked[0] = { outcome ->
                    when (outcome) {
                        // Gone since the question was asked: the answer stands, as without one.
                        RemoteUnlockOutcome.UNLOCKED, RemoteUnlockOutcome.NO_LOCK -> write()
                        RemoteUnlockOutcome.CANCELLED -> Unit
                        RemoteUnlockOutcome.LOCKOUT -> ui = ui.copy(notice = RemoteNotice.FILES_LOCKOUT)
                        RemoteUnlockOutcome.FAILED -> ui = ui.copy(notice = RemoteNotice.FILES_LOCK)
                    }
                }
                if (runCatching { unlock.launch(RemoteUnlock.Ask(unlockTitle, unlockText)) }.isFailure) {
                    unlocked[0] = null
                    ui = ui.copy(notice = RemoteNotice.FILES_LOCK)
                }
            }

            override fun setFullControl(on: Boolean) {
                ui = ui.copy(fullControl = on)
                change { if (RemoteLevel.setFullControl(context, on)) null else RemoteNotice.NO_FULL }
            }

            override fun setGuests(allowed: Boolean) {
                ui = ui.copy(guests = allowed)
                change { if (RemoteLevel.setGuestsAllowed(context, allowed)) null else RemoteNotice.NO_GUESTS }
            }

            override fun setElevate(allowed: Boolean) {
                ui = ui.copy(elevate = allowed)
                change { if (RemoteElevate.setAllowed(context, allowed)) null else RemoteNotice.NO_ELEVATE }
            }

            // The question, the lock and the grant are the activity's: the same as for a guest's request.
            override fun elevate(device: RemotePairedDevice) {
                context.tryStartActivity(RemoteElevateActivity.giveIntent(context, device.nodeId))
            }

            // Taken back at once; a write that has to be tried again is the state's "unsaved" line.
            override fun takeBack(nodeId: String) = change { RemoteElevate.takeBack(context, nodeId); null }

            override fun showCode() = change {
                if (RemoteJson.code(Appctr.remoteStartPairing()).ok) null else RemoteNotice.NO_CODE
            }

            override fun hideCode() = change { Appctr.remoteCancelPairing(); null }

            override fun revoke(nodeId: String) = change { noticeOf(RemoteJson.revoked(Appctr.remoteRevoke(nodeId))) }

            override fun revokeAll() = change { noticeOf(RemoteJson.revoked(Appctr.remoteRevokeAll())) }

            override fun toggleLog() {
                ui = ui.copy(logOpen = !ui.logOpen)
            }

            override fun toggleAccess() {
                ui = ui.copy(accessOpen = !ui.accessOpen)
            }
        }
    }
    // A television's top bar has no arrow: the remote's Back key is the way out.
    BackHandler(onBack = onBack)
    RemoteManagedContent(ui, actions, onBack)
}

private fun noticeOf(answer: RemoteRevoked): RemoteNotice? =
    if (answer.error == RemoteRevoked.NOT_SAVED) RemoteNotice.NOT_SAVED else null

/**
 * One reading of the switches and the bridge. Blocking; [lists] reads the paired devices, the
 * log and what a paired device may do whatever the revision.
 */
private fun readManaged(context: android.content.Context, before: RemoteManagedUi, lists: Boolean): RemoteManagedUi {
    val enabled = RemoteManage.isEnabled(context)
    val state = RemoteJson.state(Appctr.remoteStateJSON())
    val changed = lists || state.rev != before.state.rev
    val devices = if (changed) RemoteJson.paired(Appctr.remotePairedJSON()) else before.devices
    // The notification of full rights in force follows what the page reads: it is never behind the page.
    if (changed) RemoteElevationNotice.show(context, RemoteElevate.elevations(devices))
    return RemoteManagedUi(
        enabled = enabled,
        state = state,
        reach = RemoteReach.of(enabled, state),
        tagged = if (changed) selfTagged() else before.tagged,
        devices = devices,
        log = if (changed) RemoteJson.actions(Appctr.remoteActionsJSON()) else before.log,
        now = System.currentTimeMillis(),
        // The switches are files, read every time: a paired owner may have given full control up.
        fullControl = RemoteLevel.isFullControl(context),
        guests = RemoteLevel.guestsAllowed(context),
        elevate = RemoteElevate.isAllowed(context),
        files = RemoteLevel.isFilesOpen(context),
        locked = RemoteElevate.deviceSecure(context),
        // The document is there with the service stopped as well, at the basic level.
        access = if (changed) RemoteAccess.of(Appctr.remoteAccessJSON()) else before.access,
    )
}

/** The bridge's document is not what this page's switches say: it takes them from the running service. */
private val RemoteManagedUi.switchesBehind: Boolean
    get() = (access.level == RemoteAccess.LEVEL_FULL) != fullControl || access.guests != guests || access.files != files

/**
 * The bridge is about to catch up: the service applies a switch a moment after it is turned.
 * Never while the endpoint is off — then nothing is applied, and nobody can connect either.
 */
private val RemoteManagedUi.levelBehind: Boolean get() = state.enabled && switchesBehind

/**
 * And it will not catch up until TailSocks is started: the list of what a paired device may do
 * is then the one from before the switches were turned, and says so.
 */
private val RemoteManagedUi.accessNotApplied: Boolean get() = reach == RemoteReach.NOT_RUNNING && switchesBehind

/** Whether this node runs under ACL tags, from the daemon's status; false when it does not say. */
private fun selfTagged(): Boolean = runCatching {
    if (!Appctr.isRunning()) return false
    val self = AppJson.parseToJsonElement(Appctr.getStatusJSON(false)).jsonObject["Self"] as? JsonObject
    (self?.get("Tags") as? JsonArray)?.isNotEmpty() == true
}.getOrDefault(false)

@Composable
internal fun RemoteManagedContent(ui: RemoteManagedUi, actions: RemoteManagedActions, onBack: () -> Unit) {
    val layout = rememberWindowLayout()
    // Resolved here: a confirmation is a window of its own (see wrapContextWithLocale).
    val confirmTitle = stringResource(R.string.remote_revoke_all_title)
    val confirmText = stringResource(R.string.remote_revoke_all_text)
    val confirmYes = stringResource(R.string.remote_revoke_all)
    val confirmNo = stringResource(R.string.action_cancel)
    val fullTitle = stringResource(R.string.remote_full_confirm_title)
    val fullText = stringResource(R.string.remote_full_confirm_text)
    val fullYes = stringResource(R.string.remote_full_confirm_yes)
    val filesTitle = stringResource(R.string.remote_netfiles_confirm_title)
    val filesText = stringResource(R.string.remote_netfiles_confirm_text)
    val filesLock = stringResource(if (ui.locked) R.string.remote_netfiles_confirm_lock else R.string.remote_netfiles_confirm_no_lock)
    val filesYes = stringResource(R.string.remote_netfiles_confirm_yes)
    var confirmAll by remember { mutableStateOf(false) }
    var confirmFull by remember { mutableStateOf(false) }
    var confirmFiles by remember { mutableStateOf(false) }
    val access = remember(ui.access) { remoteAccessGroups(ui.access) }
    // Where the remote goes from the list of paired devices once its last row is revoked: to
    // the pairing code's button, and where that is not there or cannot be pressed, to the switch.
    val switchStop = remember { FocusRequester() }
    val pairStop = remember { FocusRequester() }
    val afterRows = if (ui.enabled && (ui.state.pairing != null || ui.reach.canPair)) pairStop else switchStop

    // What one does: the switches and the code.
    val first: @Composable ColumnScope.() -> Unit = {
        val failed = stringResource(R.string.remote_switch_failed)
        SwitchCard(
            Icons.Default.SettingsRemote,
            stringResource(R.string.remote_switch_title),
            stringResource(R.string.remote_managed_switch_help),
            ui.enabled,
            actions::setEnabled,
            Modifier.tvInitialFocus().focusRequester(switchStop)
        )
        if (ui.notice == RemoteNotice.NO_SWITCH) NoticeLine(failed)
        // Off is not claimed before the endpoint is closed.
        if (ui.reach == RemoteReach.CLOSING) {
            Text(
                stringResource(R.string.remote_reach_closing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ReachCard(ui.reach, ui.state.port)
        if (ui.enabled) {
            // The code first: it is what one comes here for. How much a pairing gives, and to whom, is set once.
            PairCard(ui, actions, wide = !layout.isPhone, first = pairStop)
            SwitchCard(
                Icons.Default.AdminPanelSettings,
                stringResource(R.string.remote_full_title),
                stringResource(R.string.remote_full_help),
                ui.fullControl,
                // On is asked about once; off is never in anyone's way.
                { on -> if (on) confirmFull = true else actions.setFullControl(false) }
            )
            if (ui.notice == RemoteNotice.NO_FULL) NoticeLine(failed)
            // A ceiling over full control and over a guest's rights for a while: off unless
            // opened here, after a question and this device's lock.
            SwitchCard(
                Icons.Default.FolderOpen,
                stringResource(R.string.remote_netfiles_title),
                stringResource(R.string.remote_netfiles_help),
                ui.files,
                { open -> if (open) confirmFiles = true else actions.setFiles(false) }
            )
            when (ui.notice) {
                RemoteNotice.NO_FILES -> NoticeLine(failed)
                RemoteNotice.FILES_LOCK -> NoticeLine(stringResource(R.string.remote_netfiles_lock_failed))
                RemoteNotice.FILES_LOCKOUT -> NoticeLine(stringResource(R.string.remote_netfiles_lockout))
                else -> Unit
            }
            SwitchCard(Icons.Default.Group, stringResource(R.string.remote_guests_title), guestsHelp(ui), ui.guests, actions::setGuests)
            if (ui.notice == RemoteNotice.NO_GUESTS) NoticeLine(failed)
            // Full rights for a while are a guest's: without guests there is nobody to give them to.
            if (ui.guests) {
                SwitchCard(
                    Icons.Default.LockClock,
                    stringResource(R.string.remote_elevate_switch_title),
                    stringResource(R.string.remote_elevate_switch_help),
                    ui.elevate,
                    actions::setElevate
                )
                if (ui.notice == RemoteNotice.NO_ELEVATE) NoticeLine(failed)
            }
        }
    }
    // What one reads. Pairings outlive the switch: they are listed, and can be revoked, while it
    // is off too; and what a paired device may do can be read before the switch is ever turned.
    val listed = ui.enabled || ui.devices.isNotEmpty()
    val hasSecond = listed || access.isNotEmpty()
    val second: @Composable ColumnScope.() -> Unit = {
        if (listed) {
            DevicesCard(
                ui, onRevoke = actions::revoke, onRevokeAll = { confirmAll = true }, afterRows = afterRows,
                onElevate = actions::elevate, onTakeBack = actions::takeBack,
            )
        }
        if (access.isNotEmpty()) AccessCard(access, ui.accessOpen, ui.accessNotApplied, actions::toggleAccess)
        if (listed) LogCard(ui.log, ui.logOpen, ui.now, actions::toggleLog)
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
    if (confirmFull) {
        AppAlertDialog(
            onDismissRequest = { confirmFull = false },
            title = { Text(fullTitle) },
            text = { Text(fullText) },
            confirmButton = { TextButton(onClick = { confirmFull = false; actions.setFullControl(true) }) { Text(fullYes) } },
            // So does one that hands more of the device out.
            dismissButton = { TextButton(onClick = { confirmFull = false }, modifier = Modifier.tvInitialFocus()) { Text(confirmNo) } }
        )
    }
    if (confirmFiles) {
        AppAlertDialog(
            onDismissRequest = { confirmFiles = false },
            title = { Text(filesTitle) },
            text = { Text("$filesText\n\n$filesLock") },
            confirmButton = { TextButton(onClick = { confirmFiles = false; actions.setFiles(true) }) { Text(filesYes) } },
            dismissButton = { TextButton(onClick = { confirmFiles = false }, modifier = Modifier.tvInitialFocus()) { Text(confirmNo) } }
        )
    }
}

/** The question before the files are opened, drawn without its window for a preview: the renderer has none. */
@Composable
internal fun RemoteFilesQuestion(locked: Boolean, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp, modifier = modifier) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.remote_netfiles_confirm_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.remote_netfiles_confirm_text) + "\n\n" +
                    stringResource(if (locked) R.string.remote_netfiles_confirm_lock else R.string.remote_netfiles_confirm_no_lock),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {}) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = {}) { Text(stringResource(R.string.remote_netfiles_confirm_yes)) }
            }
        }
    }
}

/** What the guests switch means, with how long a guest stays and, on a tagged node, what off then costs. */
@Composable
private fun guestsHelp(ui: RemoteManagedUi): String {
    val hours = (ui.state.policy.guestSeconds / 3600).toInt()
    val guest = if (hours > 0) {
        stringResource(R.string.remote_guests_help_lasts, pluralStringResource(R.plurals.remote_hours, hours, hours))
    } else stringResource(R.string.remote_guests_help_plain)
    // A tagged node has no owner: without guests nobody is left who could pair.
    val off = stringResource(if (ui.tagged) R.string.remote_guests_help_off_tagged else R.string.remote_guests_help_off)
    return "$guest\n$off"
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

/**
 * How long the remote rests on a switch before its explanation unfolds. Three of them stand one
 * under another, and a remote on its way down must not make each swell and shrink as it passes
 * (ExplainedRow.kt keeps the same rule for the rows of Settings).
 */
private const val HELP_REVEAL_MS = 700L

/** A switch: the whole card turns it, and its explanation unfolds under the remote. */
@Composable
private fun SwitchCard(
    icon: ImageVector,
    title: String,
    help: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val open = remember { mutableStateOf(false) }
    val tv = isTv
    val scope = rememberCoroutineScope()
    val inView = remember { BringIntoViewRequester() }
    if (tv) {
        val focused by source.collectIsFocusedAsState()
        LaunchedEffect(focused) {
            if (focused) delay(HELP_REVEAL_MS)
            open.value = focused
        }
    }
    val shape = MaterialTheme.shapes.large
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = modifier
            .fillMaxWidth()
            // An explanation unfolds downwards: under the last card in view that is below the edge.
            .bringIntoViewRequester(inView)
            .onSizeChanged { if (tv && open.value) scope.launch { inView.bringIntoView() } }
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
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                HelpText(help, modifier = Modifier.tvNoFocus(), expanded = open, inClickableRow = true)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = null)
        }
    }
}

/**
 * For a block whose buttons come and go with [state]. The button the remote was on is taken
 * away with the state it belonged to, and the focus with it: the next key would start from
 * the top of the page. When that happens this hands the focus to whatever carries [heir] in
 * the new state. Goes on the block.
 */
@Composable
private fun Modifier.keepsFocus(state: Any?, heir: FocusRequester): Modifier {
    val inside = remember { booleanArrayOf(false) }
    // Read while the new state is composed: the old buttons, and the focus on one, are still there.
    val follow = remember(state) { inside[0] }
    LaunchedEffect(state) {
        if (!follow) return@LaunchedEffect
        // The heir is in the tree once this frame is laid out.
        withFrameNanos { }
        runCatching { heir.requestFocus() }
    }
    return onFocusChanged { inside[0] = it.hasFocus }
}

/** Why nobody can connect right now, and what to switch. Nothing while they can. */
@Composable
private fun ReachCard(reach: RemoteReach, port: Int) {
    val text = when (reach) {
        RemoteReach.OFF, RemoteReach.CLOSING, RemoteReach.APPLYING, RemoteReach.READY -> return
        RemoteReach.NOT_RUNNING -> stringResource(R.string.remote_reach_not_running)
        RemoteReach.NOT_FORWARDED -> stringResource(R.string.remote_reach_not_forwarded)
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
private fun PairCard(ui: RemoteManagedUi, actions: RemoteManagedActions, wide: Boolean, first: FocusRequester) {
    val code = ui.state.pairing
    // "Show a code" gives way to "New code" and "Hide", and back when the code is hidden, used or
    // out of time: the remote stays in the card through each of these. [first] is the card's
    // first button in either state — also where the remote lands from an emptied list of devices.
    RemoteCard(stringResource(R.string.remote_pair_title), Modifier.keepsFocus(code != null, first)) {
        if (code == null) {
            Button(
                onClick = actions::showCode,
                enabled = ui.reach.canPair,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.focusRequester(first)
            ) { Text(stringResource(R.string.remote_pair_show)) }
            // How the last code ended, while that holds: not "paired" of a device that has been forgotten since.
            outcomeText(ui.state.lastPairing?.takeIf { it.stillStands(ui.devices) })?.let {
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
                OutlinedButton(onClick = actions::showCode, shape = MaterialTheme.shapes.medium, modifier = Modifier.focusRequester(first)) {
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

/**
 * Who may manage this device, each with its Revoke button in plain sight — and a guest with
 * its full rights for a while: how long they last and "Take the rights back", or the way to
 * give them.
 */
@Composable
private fun DevicesCard(
    ui: RemoteManagedUi,
    onRevoke: (String) -> Unit,
    onRevokeAll: () -> Unit,
    afterRows: FocusRequester,
    onElevate: (RemotePairedDevice) -> Unit = {},
    onTakeBack: (String) -> Unit = {},
) {
    // A revoked device's row goes, and the button the remote was on with it: the next key would
    // start from the top of the page. The remote is handed to the row that took its place, and
    // from an emptied list to [afterRows].
    val ids = ui.devices.map { it.nodeId }
    val stops = remember { mutableMapOf<String, FocusRequester>() }
    val held = remember { arrayOfNulls<String>(1) }
    val shown = remember { arrayOf(ids) }
    // Read while the new list is composed: the old rows, and the focus on one, are still there.
    val heir = remember(ids) { remoteRowHeir(shown[0], ids, held[0]).also { shown[0] = ids } }
    LaunchedEffect(ids) {
        stops.keys.retainAll(ids.toSet())
        val to = when (heir) {
            RemoteRowHeir.Stay -> return@LaunchedEffect
            is RemoteRowHeir.Row -> stops[heir.id] ?: return@LaunchedEffect
            RemoteRowHeir.Out -> afterRows
        }
        // The heir is in the tree once this frame is laid out.
        withFrameNanos { }
        runCatching { to.requestFocus() }
    }
    fun Modifier.stop(id: String): Modifier = onFocusChanged {
        if (it.hasFocus) held[0] = id else if (held[0] == id) held[0] = null
    }
    RemoteCard(
        stringResource(R.string.remote_paired_title),
        action = if (ui.devices.isEmpty()) null else {
            {
                TextButton(
                    onClick = onRevokeAll,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.stop(REMOTE_ROWS_ALL)
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
            key(device.nodeId) {
                val stop = stops.getOrPut(device.nodeId) { FocusRequester() }
                // A guest paired before guests were shut out keeps its place and is refused meanwhile.
                val refused = !device.owner && !ui.guests
                DeviceRow(
                    device, ui.now, refused = refused, onRevoke = { onRevoke(device.nodeId) },
                    revoke = Modifier.focusRequester(stop),
                    // Full rights are a guest's, and given while the switch lets them be.
                    elevate = !device.owner && !refused && ui.elevate,
                    onElevate = { onElevate(device) },
                    onTakeBack = { onTakeBack(device.nodeId) },
                    // The row as one: the remote moving between its two buttons has not left it.
                    modifier = Modifier.stop(device.nodeId),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        if (ui.notice == RemoteNotice.NOT_SAVED) NoticeLine(stringResource(R.string.remote_revoke_not_saved))
        else if (ui.state.unsaved) NoticeLine(stringResource(R.string.remote_unsaved))
        Spacer(Modifier.height(4.dp))
        // Who can pair. A tagged node has no owner; with guests shut out as well, that is nobody.
        val fact = when {
            ui.tagged && !ui.guests -> R.string.remote_paired_nobody
            ui.tagged -> R.string.remote_paired_tagged
            else -> null
        }
        if (fact != null) {
            Text(stringResource(fact), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
        val hours = (ui.state.policy.guestSeconds / 3600).toInt()
        when {
            !ui.guests -> if (!ui.tagged) FoldedHelp(stringResource(R.string.remote_paired_help_owners))
            hours > 0 -> FoldedHelp(stringResource(R.string.remote_paired_help, pluralStringResource(R.plurals.remote_hours, hours, hours)))
        }
    }
}

/**
 * Where the remote goes when the stop it was on is taken from a list of rows. [before] and
 * [after] are the rows' ids as they were and as they are, [focused] the row the remote was on,
 * [REMOTE_ROWS_ALL] for the stop above the rows that is there while there are any, null for
 * anywhere else.
 */
internal sealed interface RemoteRowHeir {
    /** The remote was on nothing that went away. */
    data object Stay : RemoteRowHeir

    /** The row that took the place of the one that went: the next one still there, else the one before it. */
    data class Row(val id: String) : RemoteRowHeir

    /** No row is left: out of the list. */
    data object Out : RemoteRowHeir
}

/** "Revoke all", as a stop of the list: no node is called this. */
internal const val REMOTE_ROWS_ALL = "\u0000all"

internal fun remoteRowHeir(before: List<String>, after: List<String>, focused: String?): RemoteRowHeir {
    if (focused == null) return RemoteRowHeir.Stay
    if (focused == REMOTE_ROWS_ALL) return if (before.isNotEmpty() && after.isEmpty()) RemoteRowHeir.Out else RemoteRowHeir.Stay
    val at = before.indexOf(focused)
    if (at < 0 || focused in after) return RemoteRowHeir.Stay
    val next = before.drop(at + 1).firstOrNull { it in after }
        ?: before.take(at).lastOrNull { it in after }
        ?: after.firstOrNull()
    return if (next == null) RemoteRowHeir.Out else RemoteRowHeir.Row(next)
}

/**
 * One paired device; [revoke] goes on its Revoke button. A guest's full rights for a while are a
 * line under its name with "Take the rights back", and where [elevate] lets them be given and
 * there are none, "Give full rights for a while…": a second stop of the row.
 */
@Composable
private fun DeviceRow(
    device: RemotePairedDevice,
    now: Long,
    refused: Boolean,
    onRevoke: () -> Unit,
    revoke: Modifier = Modifier,
    elevate: Boolean = false,
    onElevate: () -> Unit = {},
    onTakeBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val term = if (device.left < 0) stringResource(R.string.remote_paired_until_revoked)
    else stringResource(R.string.remote_paired_expires_in, remoteDurationText(device.left))
    val used = when {
        refused -> stringResource(R.string.remote_paired_guest_refused)
        device.lastUsed <= 0 -> stringResource(R.string.remote_paired_never_used)
        else -> stringResource(R.string.remote_paired_last_used, agoText(context, device.lastUsed * 1000, now))
    }
    // Since when: a pairing nobody remembers making is the one to revoke. A store from before the
    // time was kept says nothing.
    val since = if (device.created > 0) stringResource(R.string.remote_paired_since, agoText(context, device.created * 1000, now)) else null
    val revokeLabel = stringResource(R.string.remote_revoke_named, device.name)
    // Full rights in force, by this device's clock: "Full rights · until 18:30".
    val rights = device.elevated?.takeIf { !device.owner }?.let { e ->
        val until = remember(e.until) { RemoteElevateText.clock(context, e.until) }
        stringResource(if (e.full) R.string.remote_elevate_row_full else R.string.remote_elevate_row, until)
    }
    // "Give" and "Take back" stand in one place: the remote on one is on the other once it is pressed.
    val rightsStop = remember { FocusRequester() }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).keepsFocus(rights != null, rightsStop)) {
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
                    listOfNotNull(since, term, used).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (rights != null) {
                    Text(rights, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    val label = stringResource(R.string.remote_elevate_take_back_named, device.name)
                    TextButton(
                        onClick = onTakeBack,
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.focusRequester(rightsStop).semantics { contentDescription = label }
                    ) { Text(stringResource(R.string.remote_elevate_take_back)) }
                } else if (elevate) {
                    val label = stringResource(R.string.remote_elevate_give_named, device.name)
                    TextButton(
                        onClick = onElevate,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.focusRequester(rightsStop).semantics { contentDescription = label }
                    ) { Text(stringResource(R.string.remote_elevate_give)) }
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onRevoke,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = revoke.semantics { contentDescription = revokeLabel }
            ) { Text(stringResource(R.string.remote_revoke)) }
        }
    }
}

/**
 * A card folded until asked for: its title is the button. A television cannot drag a page, so
 * there what unfolds scrolls in a pane of its own, [pane] high at most, which the remote stops
 * on and moves with Up and Down.
 */
@Composable
private fun FoldCard(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    pane: Dp,
    spacing: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.large
    // Unfolded at the foot of the page, the card would open below the edge with only its arrow to
    // show for it: it is brought into view when it is opened. Not when the page is drawn with it open.
    val inView = remember { BringIntoViewRequester() }
    val wasOpen = remember { booleanArrayOf(open) }
    LaunchedEffect(open) {
        val opened = open && !wasOpen[0]
        wasOpen[0] = open
        if (!opened) return@LaunchedEffect
        withFrameNanos { }
        inView.bringIntoView()
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(inView)
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
                    title,
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
                val lines = if (isTv) {
                    val scroll = rememberScrollState()
                    Modifier.heightIn(max = pane).tvScrollKeys(scroll).verticalScroll(scroll)
                } else Modifier
                Column(
                    lines.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    content = content
                )
            }
        }
    }
}

// ─── What a paired device may do ────────────────────────────────────────────────────────────

/** A name from one of the bridge's lists, and this page's words for it; 0 where it has none, and then the name is shown as it came. */
data class RemoteAccessName(val name: String, @param:StringRes val text: Int = 0)

/**
 * One line of the card: the sentence [text] with [names] in it as a list — the last joined
 * with "and" where [and] — or, with no sentence (0), the names alone; or a sentence alone.
 */
data class RemoteAccessRow(@param:StringRes val text: Int, val names: List<RemoteAccessName> = emptyList(), val and: Boolean = false)

/** The lines under one heading: a class of paired device, what full control adds, what nobody may. */
data class RemoteAccessGroup(@param:StringRes val title: Int, val rows: List<RemoteAccessRow>)

/**
 * This page's words for every name the bridge's lists are made of (appctr/remote_policy.go,
 * remote_level.go). RemoteManagedAccessTest holds them against the bridge's own list, so a name
 * added there fails the build here until it has words.
 */
internal object RemoteAccessWords {
    const val OP_SET = "settings.set"
    const val OP_ACTION = "action"
    const val OP_ASK = "permission.request"
    /** A guest's request for full rights for a while: said by a line of its own, and never an owner's. */
    const val OP_ELEVATE = "elevate.request"
    /** What a guarded change of the exit node is called in the log (the bridge's RemoteKeyExitNode). */
    const val KEY_EXIT_NODE = "exit_node"

    /** Operations that show something: together they are one sentence, "See …". */
    val seen: Map<String, Int> = mapOf(
        "status" to R.string.remote_can_status,
        "exit_nodes" to R.string.remote_can_exit_nodes,
        "settings" to R.string.remote_can_settings,
        "files" to R.string.remote_can_files,
        "folders" to R.string.remote_can_folders,
        "storage.list" to R.string.remote_can_storage_list,
        "diag.report" to R.string.remote_can_diag_report,
        "diag.log" to R.string.remote_can_diag_log,
    )

    /**
     * Operations that do something. Three of them stand for a list of their own — the settings,
     * the actions, the permissions — and are said in these words only where that list is empty.
     */
    val done: Map<String, Int> = mapOf(
        "exit_node.set" to R.string.remote_can_exit_node_set,
        OP_SET to R.string.remote_can_settings_set,
        OP_ACTION to R.string.remote_can_action,
        "files.delete" to R.string.remote_can_files_delete,
        "unpair" to R.string.remote_can_unpair,
        OP_ASK to R.string.remote_can_permission_request,
        "confirm" to R.string.remote_can_confirm,
        "identify" to R.string.remote_can_identify,
        "shares.remove" to R.string.remote_can_shares_remove,
        "shares.add" to R.string.remote_can_shares_add,
        "storage.send" to R.string.remote_can_storage_send,
        OP_ELEVATE to R.string.remote_can_elevate_request,
    )

    val settings: Map<String, Int> = mapOf(
        "accept_dns" to R.string.remote_can_set_accept_dns,
        "accept_routes" to R.string.remote_can_set_accept_routes,
        "exit_node_lan_access" to R.string.remote_can_set_exit_node_lan_access,
        "tun_mode" to R.string.remote_can_set_tun_mode,
        "auto_reconnect" to R.string.remote_can_set_auto_reconnect,
        "auto_start" to R.string.remote_can_set_auto_start,
        "keep_awake" to R.string.remote_can_set_keep_awake,
        "taildrive_enabled" to R.string.remote_can_set_taildrive_enabled,
        "taildrive_proxy_enabled" to R.string.remote_can_set_taildrive_proxy_enabled,
        "advertise_exit_node" to R.string.remote_can_set_advertise_exit_node,
        "advertise_routes" to R.string.remote_can_set_advertise_routes,
        "full_control" to R.string.remote_can_set_full_control,
    )

    val actions: Map<String, Int> = mapOf(
        "reconnect" to R.string.remote_can_act_reconnect,
        "reconnect_relays" to R.string.remote_can_act_reconnect_relays,
        "receive_folder_default" to R.string.remote_can_act_receive_folder_default,
        "save_received" to R.string.remote_can_act_save_received,
    )

    /**
     * A permission is called here what the Permissions screen, a request's card and its
     * notification call it ([RemotePromptRules.title]): one name on this device for one thing —
     * access to files being "Access to all files" from Android 11 and "Storage" before it.
     * The VPN consent is never asked for from afar and has only this page's word.
     */
    val permissions: Map<String, Int> =
        AppPermission.entries.associate { it.id to (RemotePromptRules.title(it) ?: R.string.remote_perm_vpn) }

    val never: Map<String, Int> = mapOf(
        "stop" to R.string.remote_never_stop,
        "sign_out" to R.string.remote_never_sign_out,
        "account" to R.string.remote_never_account,
        "credentials" to R.string.remote_never_credentials,
        "control_proxy" to R.string.remote_never_control_proxy,
        "root_mode" to R.string.remote_never_root_mode,
        "grant_permission" to R.string.remote_never_grant_permission,
        "full_control_on" to R.string.remote_never_full_control_on,
        "files_on" to R.string.remote_never_files_on,
    )

    /** What the app did about a request to show something, as the log quotes it. */
    val results: Map<String, Int> = mapOf(
        "shown" to R.string.remote_log_result_shown,
        "notified" to R.string.remote_log_result_notified,
        "waiting" to R.string.remote_log_result_waiting,
        "unseen" to R.string.remote_log_result_unseen,
    )

    /** What the bridge adds to a line when the app did not simply do as asked (remote_server.go, changeTold; remote_guard.go). */
    val remarks: Map<String, Int> = mapOf(
        "(no answer from the app yet)" to R.string.remote_log_remark_pending,
        "(applied late)" to R.string.remote_log_remark_late,
        "(failed late)" to R.string.remote_log_remark_failed_late,
        "(not asked of the app)" to R.string.remote_log_remark_not_asked,
        "(the app's answer was not understood)" to R.string.remote_log_remark_bad_answer,
        "(the app refused to put it back)" to R.string.remote_log_remark_not_put_back,
    )

    /** What a line of the log can say was changed: a setting, or the exit node. */
    val changed: Map<String, Int> = settings + (KEY_EXIT_NODE to R.string.remote_can_set_exit_node)

    /** Why a guest's full rights ended, as the bridge logs it (remote_elevate.go, remoteElevateEnds). */
    val elevationEnds: Map<String, Int> = mapOf(
        "time" to R.string.remote_log_elevation_time,
        "taken_back" to R.string.remote_log_elevation_taken_back,
        "switch_off" to R.string.remote_log_elevation_switch_off,
        "guests_off" to R.string.remote_log_elevation_guests_off,
    )

    fun name(name: String, words: Map<String, Int>) = RemoteAccessName(name, words[name] ?: 0)
}

/**
 * The bridge's document as the lines the card draws, and nothing that is not in the document:
 * a guest's operations, an owner's with which way each setting goes, what full control would
 * add — no such group while it is on, those lines are the owner's then — what network access
 * to files keeps closed while it is off, and what nobody may.
 * Merged so that it reads as a dozen lines: what is seen is one sentence, what is done another,
 * the settings one line for each way they go, and an owner who may all a guest may is said so
 * instead of said twice.
 */
internal fun remoteAccessGroups(access: RemoteAccess): List<RemoteAccessGroup> {
    val words = RemoteAccessWords
    // Asking for full rights is a guest's line of its own: an owner has nothing to ask for.
    val elevate = words.OP_ELEVATE in access.guest.ops
    val guestOps = access.guest.ops - words.OP_ELEVATE
    val ownerOps = access.owner.ops - words.OP_ELEVATE
    // With guests shut out a guest is nobody, whatever the list says a guest could do.
    val guest = if (access.guests) {
        classRows(access.guest, guestOps, ::settingRows) + (if (elevate) listOf(RemoteAccessRow(R.string.remote_access_guest_elevate)) else emptyList())
    } else listOf(RemoteAccessRow(R.string.remote_access_guests_off))
    val alike = access.guests && guestOps.isNotEmpty() && ownerOps.containsAll(guestOps)
    val owner = (if (alike) listOf(RemoteAccessRow(R.string.remote_access_like_guest)) else emptyList()) +
        classRows(access.owner, if (alike) ownerOps - guestOps.toSet() else ownerOps, ::settingRows)
    // What full control opens of a setting is turning it on: off was the owner's already.
    val full = classRows(access.full, access.full.ops) { settings ->
        listOf(RemoteAccessRow(R.string.remote_access_set_with_full, settings.map { words.name(it.key, words.settings) }))
    }
    // What the files' switch closes, it closes both ways: a setting there is changed neither way.
    val withFiles = classRows(access.withFiles, access.withFiles.ops) { settings ->
        listOf(RemoteAccessRow(R.string.remote_access_set, settings.map { words.name(it.key, words.settings) }))
    }
    val never = if (access.never.isEmpty()) emptyList()
    else listOf(RemoteAccessRow(0, access.never.map { words.name(it, words.never) }))
    return listOf(
        RemoteAccessGroup(R.string.remote_access_guest, guest),
        RemoteAccessGroup(
            if (access.level == RemoteAccess.LEVEL_FULL) R.string.remote_access_owner_full else R.string.remote_access_owner,
            owner
        ),
        RemoteAccessGroup(R.string.remote_access_full, full),
        RemoteAccessGroup(R.string.remote_access_with_files, withFiles),
        RemoteAccessGroup(R.string.remote_access_never, never),
    ).filter { it.rows.isNotEmpty() }
}

/** One class's lines: what it sees, the settings, what it does — its actions among that — and the permissions it may ask for. */
private fun classRows(
    of: RemoteAccessClass,
    ops: List<String>,
    settingsAs: (List<RemoteAccessSetting>) -> List<RemoteAccessRow>,
): List<RemoteAccessRow> {
    val words = RemoteAccessWords
    val rows = mutableListOf<RemoteAccessRow>()
    val seen = ops.filter { it in words.seen }
    if (seen.isNotEmpty()) rows += RemoteAccessRow(R.string.remote_access_see, seen.map { words.name(it, words.seen) }, and = true)
    if (of.settings.isNotEmpty()) rows += settingsAs(of.settings)
    val done = ops.flatMap { op ->
        when {
            op in words.seen -> emptyList()
            // Said by its own list, below or above; by its own words only where there is no list.
            op == words.OP_SET && of.settings.isNotEmpty() -> emptyList()
            op == words.OP_ASK && of.permissions.isNotEmpty() -> emptyList()
            op == words.OP_ACTION && of.actions.isNotEmpty() -> of.actions.map { words.name(it, words.actions) }
            // An operation this page has never heard of lands here, under the name it came with.
            else -> listOf(words.name(op, words.done))
        }
    }
    if (done.isNotEmpty()) rows += RemoteAccessRow(0, done)
    if (of.permissions.isNotEmpty()) {
        rows += RemoteAccessRow(R.string.remote_access_permissions, of.permissions.map { words.name(it, words.permissions) })
    }
    return rows
}

/** The settings, one line for each way they go from afar, in the order the bridge lists them. */
private fun settingRows(settings: List<RemoteAccessSetting>): List<RemoteAccessRow> =
    settings.groupBy(::settingWay).map { (way, group) ->
        RemoteAccessRow(way, group.map { RemoteAccessWords.name(it.key, RemoteAccessWords.settings) })
    }

@StringRes
private fun settingWay(setting: RemoteAccessSetting): Int = when {
    // Off, or cleared, at any level; the other way takes full control.
    setting.needs.isNotEmpty() -> R.string.remote_access_set_needs_full
    setting.only == "true" -> R.string.remote_access_set_on_only
    setting.only == "false" -> R.string.remote_access_set_off_only
    // Either way, and the device puts it back unless the change is confirmed.
    setting.guard.isNotEmpty() -> R.string.remote_access_set_guarded
    else -> R.string.remote_access_set
}

/** A line in words: its names joined the way this language joins a list, inside the sentence the line is. */
@Composable
private fun accessRowText(row: RemoteAccessRow): String {
    if (row.names.isEmpty()) return stringResource(row.text)
    var list = ""
    row.names.forEachIndexed { i, name ->
        val word = if (name.text != 0) stringResource(name.text) else name.name
        list = when {
            i == 0 -> word
            row.and && i == row.names.lastIndex -> stringResource(R.string.remote_access_list_last, list, word)
            else -> stringResource(R.string.remote_access_list_more, list, word)
        }
    }
    if (row.text != 0) return stringResource(row.text, list)
    // Names alone begin like a sentence — unless the first is a name left as the bridge sent it.
    val locale = LocalConfiguration.current.locales[0]
    return if (row.names.first().text != 0) list.replaceFirstChar { it.titlecase(locale) } else list
}

/** How tall the pane of each folded card may get on a television. */
private val ACCESS_PANE = 260.dp
private val LOG_PANE = 200.dp

/**
 * What a paired device may do, class by class, folded until asked for: [remoteAccessGroups] in
 * words. [notApplied] when the switches were turned with TailSocks stopped and the list is still
 * the one from before.
 */
@Composable
private fun AccessCard(groups: List<RemoteAccessGroup>, open: Boolean, notApplied: Boolean, onToggle: () -> Unit) {
    FoldCard(stringResource(R.string.remote_access_title), open, onToggle, ACCESS_PANE, spacing = 12.dp) {
        if (notApplied) {
            Text(
                stringResource(R.string.remote_access_not_applied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        groups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(group.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                group.rows.forEach { row ->
                    Row {
                        Text(
                            "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(14.dp)
                        )
                        Text(
                            accessRowText(row),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ─── What they changed ──────────────────────────────────────────────────────────────────────

/** How much of the log is drawn: the bridge keeps a hundred lines, a screen needs the recent ones. */
private const val LOG_LINES = 30

/** What paired devices changed, folded until asked for. */
@Composable
private fun LogCard(log: List<RemoteEvent>, open: Boolean, now: Long, onToggle: () -> Unit) {
    val context = LocalContext.current
    FoldCard(stringResource(R.string.remote_log_title), open, onToggle, LOG_PANE, spacing = 8.dp) {
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

/**
 * A log line's detail taken apart the way the bridge puts it together: a [name] — a setting's
 * key, a permission's id, an action — then "=" or ": " and a [value], null where there is
 * neither, then the bridge's own remark in brackets ("(no answer from the app yet)"), kept
 * with its brackets. Not for a detail that is a file's or a node's name: those are never taken
 * apart.
 */
internal data class RemoteLogDetail(val name: String, val value: String? = null, val note: String = "")

internal fun remoteLogDetail(detail: String): RemoteLogDetail {
    val text = detail.trim()
    val noteAt = if (text.startsWith("(")) 0 else text.indexOf(" (")
    val head = if (noteAt < 0) text else text.substring(0, noteAt)
    val note = if (noteAt < 0) "" else text.substring(noteAt).trim()
    val cut = head.indexOfFirst { it == '=' || it == ':' }
    return if (cut < 0) RemoteLogDetail(head, null, note)
    else RemoteLogDetail(head.substring(0, cut).trim(), head.substring(cut + 1).trim(), note)
}

/** One line of the log in words (RemoteLogLine.kt), in the language of this screen. */
@Composable
private fun eventText(event: RemoteEvent): String {
    // Read as stringResource reads it: a change of language draws the line again.
    LocalConfiguration.current
    val resources = LocalContext.current.resources
    return remoteEventLine(event.op, event.detail, event.ok) { resources.getString(it) }
}
