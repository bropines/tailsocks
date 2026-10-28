package io.github.bropines.tailscaled.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import appctr.Appctr
import io.github.bropines.tailscaled.BuildConfig
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.settings.rememberParentLocals
import io.github.bropines.tailscaled.core.AccountManager
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.BridgeRemotePeerApi
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.RemoteClient
import io.github.bropines.tailscaled.core.RemoteElevate
import io.github.bropines.tailscaled.core.RemoteJson
import io.github.bropines.tailscaled.core.RemoteManage
import io.github.bropines.tailscaled.core.RemoteQr
import io.github.bropines.tailscaled.core.TailscaledService
import io.github.bropines.tailscaled.core.sendTaildropFiles
import io.github.bropines.tailscaled.core.tryStartActivity
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import io.github.bropines.tailscaled.models.TaildropSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Remote management, the managing device's side: the way in from a peer's details, and the
 * page — a window over whatever screen opened it — in which another device's TailSocks is run
 * from this one. The page's state is ui/RemoteManageController.kt, what it draws
 * ui/RemoteManagePage.kt; here is what ties them to this device: the bridge, the tokens, the
 * file picker, the scanner, and how often the device is asked.
 *
 * Three ways in, each for a screen someone else owns:
 *   - rememberRemoteManage(peer) + RemoteManageButton(entry): the peer's details. The first
 *     asks the peer, once, whether it offers management, and belongs in the screen's own
 *     composition; the button draws nothing until the answer is yes, and may sit inside a
 *     sheet. RemoteManageEntry(peer) is both at once, for a place that is not in a sheet.
 *   - remotePairingQr(text) + RemoteManagePage(…, code = …): a pairing code read by the app's
 *     scanner. The page opens with the code filled in; pairing is the person's press.
 *   - RemoteDevices (ui/RemoteManageDevices.kt): the devices this one is paired with.
 */

// ─── The entry in a peer's details ───────────────────────────────────────────────────────────

/** What the peer's details know about managing that peer. */
@Stable
class RemoteManageEntryState internal constructor(internal val label: String, internal val pairedLabel: String = "") {
    /** The peer answered as a TailSocks that offers management, or this device is paired with it. */
    var offered by mutableStateOf(false)
        internal set
    /** This device holds a pairing for the peer. */
    var paired by mutableStateOf(false)
        internal set
    internal var tv by mutableStateOf(false)
    internal var open by mutableStateOf(false)
}

/** Before a peer is asked: turning the pages of the details past it costs it nothing. */
private const val PROBE_AFTER_MS = 350L

/**
 * Whether [peer]'s details look into remote management at all: never this device's own, and
 * never a peer without an id the bridge would take. Then only what this device keeps is read.
 */
internal fun remoteManageConcerns(peer: PeerData?, isSelf: Boolean): Boolean =
    !isSelf && RemoteJson.isNodeId(peer?.id.orEmpty())

/**
 * Whether [peer] is asked over the network: only one that is online — an offline peer would
 * cost the whole wait and answer nothing — and says it runs TailSocks ([PeerData.isTailSocks]).
 * Until 5.3 every Android and Linux peer was asked, the official clients and servers among
 * them; a TailSocks of 5.3 or older in honest mode says nothing and is not asked until it is
 * updated, though one this device is paired with is still offered.
 */
internal fun remoteManageAsks(peer: PeerData?, isSelf: Boolean): Boolean =
    remoteManageConcerns(peer, isSelf) && peer?.online == true && peer.isTailSocks

/**
 * Asks [peer] whether it offers remote management — one small request when the details open,
 * only of a peer that is online and runs TailSocks — and holds the page the button
 * opens. A device this one is already paired with is offered whatever it answers, so that it
 * can be forgotten while it is away. Call it in the screen's own composition, outside any
 * sheet: its strings and its page take the language from there (see wrapContextWithLocale).
 */
@Composable
fun rememberRemoteManage(peer: PeerData?, isSelf: Boolean = false): RemoteManageEntryState {
    val context = LocalContext.current.applicationContext
    val live = !LocalInspectionMode.current && LocalDemo.current == null
    val id = peer?.id.orEmpty()
    val online = peer?.online == true
    val label = stringResource(R.string.remote_manage_button)
    val pairedLabel = stringResource(R.string.rmanage_paired)
    val entry = remember(id, label, pairedLabel) { RemoteManageEntryState(label, pairedLabel) }
    var codeOnShow by remember(id) { mutableStateOf(true) }

    if (live && remoteManageConcerns(peer, isSelf)) {
        val ask = remoteManageAsks(peer, isSelf)
        // Read again when the page closes: pairing and forgetting both happen in it.
        LaunchedEffect(id, ask, entry.open) {
            if (entry.open) return@LaunchedEffect
            delay(PROBE_AFTER_MS)
            val (known, probe) = withContext(Dispatchers.IO) {
                val record = runCatching {
                    RemoteManage.tokens(context).get(AccountManager.getActiveAccount(context).id, id)
                }.getOrNull()
                val probe = if (ask && Appctr.isRunning()) runCatching { BridgeRemotePeerApi.probe(id) }.getOrNull() else null
                record to probe
            }
            entry.paired = known != null
            entry.offered = known != null || probe?.ok == true
            entry.tv = probe?.tv ?: (known?.tv == true)
            codeOnShow = probe?.pairing ?: true
        }
    }
    if (entry.open && peer != null) {
        RemoteManagePage(
            peerId = id,
            name = peer.getDisplayName(),
            online = online,
            tv = entry.tv,
            codeOnShow = codeOnShow,
            onClose = { entry.open = false }
        )
    }
    return entry
}

/**
 * "Manage this device", with a television's glyph for one, and a mark on a device this one is
 * already paired with. Nothing unless the peer offers it.
 */
@Composable
fun RemoteManageButton(entry: RemoteManageEntryState, modifier: Modifier = Modifier) {
    if (!entry.offered) return
    FilledTonalButton(
        onClick = { entry.open = true },
        modifier = modifier.heightIn(min = 46.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Icon(remoteGlyph(entry.tv), null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(entry.label, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (entry.paired && entry.pairedLabel.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            ManageTag(entry.pairedLabel)
        }
    }
}

/** [rememberRemoteManage] and [RemoteManageButton] together, for a place that is not inside a sheet. */
@Composable
fun RemoteManageEntry(peer: PeerData, modifier: Modifier = Modifier, isSelf: Boolean = false) {
    RemoteManageButton(rememberRemoteManage(peer, isSelf), modifier)
}

/** [text] as a pairing code read from a managed device's screen, or null: for the app's scanner. */
fun remotePairingQr(text: String): RemoteQr? = runCatching { BridgeRemotePeerApi.parseQr(text) }.getOrNull()

internal fun remoteGlyph(tv: Boolean): ImageVector = if (tv) Icons.Default.Tv else Icons.Default.SettingsRemote

// ─── The page and what drives it ─────────────────────────────────────────────────────────────

/** How often the status is read while the page is open: seldom, and often while something is expected to change. */
private const val STATUS_EVERY_MS = 10_000L
private const val WATCH_EVERY_MS = 3_000L
/** How long "Applied" stays before it goes away by itself. */
private const val APPLIED_SHOWN_MS = 4_000L

/**
 * What outlives a page: a file half sent is not dropped, and a change is still confirmed,
 * because the page was left.
 */
private val background = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * The peer as this device's own daemon has it now, with nobody asked over the network: what
 * the page shows of it before the device answers ([ManagePeer]), and the peer itself for a
 * file to be sent to — null when the daemon cannot say or does not hold it.
 *
 * Only a daemon that is Running has a network map to read: one that is starting or waits for
 * a sign-in lists no peers, and a peer missing from that would be taken for one that left.
 */
private fun localView(peerId: String): Pair<ManagePeer, PeerData?> = runCatching {
    if (!Appctr.isRunning()) return ManagePeer(stopped = true) to null
    if (Appctr.getBackendState() != "Running") return ManagePeer() to null
    val json = Appctr.getStatusFromAPI()
    if (json.isBlank() || json.startsWith("Error")) return ManagePeer() to null
    val status = AppJson.decodeFromString<StatusResponse>(json)
    val peers = status.peers ?: return ManagePeer() to null
    val peer = peers.values.firstOrNull { it.id == peerId } ?: return ManagePeer(RemotePresence.GONE) to null
    ManagePeer(
        presence = if (peer.online == true) RemotePresence.ONLINE else RemotePresence.OFFLINE,
        lastSeen = parseRfc3339Millis(peer.lastSeen) ?: 0,
        address = peer.tailscaleIPs?.firstOrNull().orEmpty(),
    ) to peer.withTaildropOwnership(status.self?.userID)
}.getOrDefault(ManagePeer() to null)

/** Starts this device's own TailSocks the way the main screen's switch does; false when it could not be asked to. */
private fun startOwnTailSocks(context: Context): Boolean {
    // The same refusal the main screen makes: the service would start with nowhere to listen.
    if (GlobalSettings.getString(context, "socks5", "127.0.0.1:48115").isBlank()) {
        Toast.makeText(context, context.getString(R.string.main_error_socks5_empty), Toast.LENGTH_LONG).show()
        return false
    }
    return runCatching {
        ContextCompat.startForegroundService(context, Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" })
    }.isSuccess
}

/**
 * The page that pairs with a device and manages it, in a window of its own over the screen
 * that opened it. [peerId] is the peer's StableNodeID. [online]: the caller takes the device
 * for there — its own list said so, or a code was just scanned off its screen. The page reads
 * this device's network map itself the moment it opens; what [online] decides is only that a
 * device taken for there is asked before the page calls it offline on the map's word. [code]
 * fills the pairing code in — a scanned one — and pairing stays the person's press. Back
 * closes it. Call it in the screen's own composition, not inside a sheet.
 */
@Composable
fun RemoteManagePage(
    peerId: String,
    name: String,
    online: Boolean,
    onClose: () -> Unit,
    tv: Boolean = false,
    codeOnShow: Boolean = true,
    code: String = "",
) {
    val locals = rememberParentLocals()
    PageWindow(locals, onClose) { RemoteManageHost(peerId, name, online, onClose, tv, codeOnShow, code) }
}

@Composable
private fun RemoteManageHost(
    peerId: String,
    name: String,
    online: Boolean,
    onClose: () -> Unit,
    tv: Boolean,
    codeOnShow: Boolean,
    code: String,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)
    // The peer as this device's own daemon has it: for a file that is sent to it from here.
    var peer by remember(peerId) { mutableStateOf<PeerData?>(null) }

    val client = remember(peerId) {
        RemoteClient(BridgeRemotePeerApi, RemoteManage.tokens(app), AccountManager.getActiveAccount(app).id, peerId)
    }
    val controller = remember(client) {
        RemoteManageController(
            client = client,
            scope = scope,
            guards = RemoteGuards.keeper,
            local = { localView(peerId).first },
            onClose = { close() },
            name = name,
            tv = tv,
            codeOnShow = codeOnShow,
            code = code,
            appVersion = BuildConfig.VERSION_NAME,
            present = online,
            memory = RemoteMemory.memory,
            startOwn = { startOwnTailSocks(app) },
        )
    }
    DisposableEffect(controller) { onDispose { controller.stop() } }
    // The main screen names the device managed last first (ui/ManagedOnMain.kt).
    LaunchedEffect(peerId) { withContext(Dispatchers.IO) { ManagedLast.note(app, AccountManager.getActiveAccount(app).id, peerId) } }

    val reasons = remember(context) { TaildropReasonStrings.from(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller) {
        controller.start()
        // The device is asked only while the page is on screen.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // The start has just looked, and so has the round before a pause: the first round only reads the peer.
            var looked = true
            while (true) {
                val (seen, data) = withContext(Dispatchers.IO) { localView(peerId) }
                if (data != null) peer = data
                controller.sendVerdict((peer?.let { taildropStatusOf(it, reasons) } as? TaildropStatus.Blocked)?.reason)
                if (!looked) controller.tick(seen)
                looked = false
                val managing = controller.ui as? RemoteManageUi.Managing
                delay(if (managing?.watching(System.currentTimeMillis()) == true) WATCH_EVERY_MS else STATUS_EVERY_MS)
            }
        }
    }
    // "Applied" is said for a moment and goes away by itself: nothing in it needs an answer.
    val guard = (controller.ui as? RemoteManageUi.Managing)?.guard
    LaunchedEffect(guard) {
        if (guard?.phase == GuardPhase.APPLIED) {
            delay(APPLIED_SHOWN_MS)
            controller.dismissGuard()
        }
    }

    // Plain Taildrop from this device to that peer: the app's own picker and its own send.
    val picker = rememberSendFilePicker(multiple = true) { uris ->
        val to = peer
        if (uris.isEmpty() || to == null) return@rememberSendFilePicker
        controller.sending(RemoteSendState(progress = ""))
        background.launch {
            val outcomes = withContext(Dispatchers.IO) {
                sendTaildropFiles(context, uris, to, TaildropSource.PEERS) { line ->
                    background.launch { controller.sending(RemoteSendState(progress = line)) }
                }
            }
            controller.sent(
                RemoteSendState(
                    sent = outcomes.filter { it.error == null }.map { it.name },
                    failed = outcomes.mapNotNull { outcome -> outcome.error?.let { outcome.name to it } },
                )
            )
        }
    }
    // This device's own lock in front of the other one's files: asked over the screen, held for
    // a few minutes by the page and gone with it (ui/RemoteFilesLock.kt).
    val unlockTitle = stringResource(R.string.rmanage_files_unlock_title, name)
    val unlockText = stringResource(R.string.rmanage_files_unlock_subtitle)
    val answer = remember(peerId) { arrayOfNulls<(RemoteUnlockOutcome) -> Unit>(1) }
    val unlock = rememberLauncherForActivityResult(RemoteUnlock) { outcome ->
        answer[0]?.invoke(outcome)
        answer[0] = null
    }
    val filesLock = remember(peerId) {
        RemoteFilesLock(
            secure = { RemoteElevate.deviceSecure(app) },
            ask = { onResult ->
                answer[0] = onResult
                if (runCatching { unlock.launch(RemoteUnlock.Ask(unlockTitle, unlockText)) }.isFailure) {
                    answer[0] = null
                    onResult(RemoteUnlockOutcome.FAILED)
                }
            },
        )
    }
    // The scanner is asked for here, not in a sheet: it launches from the screen's own window.
    val scanner = rememberQrScanner { text -> controller.scanned(text) }
    val canScan = Feature.QR_SCANNER.offered()
    val canCopy = Feature.CLIPBOARD_ACTIONS.offered()
    val television = isTv
    val hooks = remember(controller, canScan, canCopy, television, name) {
        RemoteManageHooks(
            scan = scanner.takeIf { canScan },
            sendFile = { picker.pick(to = name) },
            storage = RemoteFileSource(client::storageList) { failed -> scope.launch { controller.failedOutside(failed) } },
            // A television has nothing to hand a text to.
            shareText = if (television) null else { title, text ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
                context.tryStartActivity(Intent.createChooser(send, title))
            },
            copyText = if (!canCopy) null else { text ->
                runCatching {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(name, text))
                }
            },
            openInbox = { context.tryStartActivity(Intent(context, FilesActivity::class.java)) },
            files = filesLock,
        )
    }
    RemoteManageContent(controller.ui, controller, hooks)
}
