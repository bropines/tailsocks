package io.github.bropines.tailscaled.ui

import android.text.format.Formatter
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AccountManager
import io.github.bropines.tailscaled.core.BridgeRemotePeerApi
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.RemoteExitNode
import io.github.bropines.tailscaled.core.RemoteFailure
import io.github.bropines.tailscaled.core.RemoteFile
import io.github.bropines.tailscaled.core.RemoteJson
import io.github.bropines.tailscaled.core.RemoteKeys
import io.github.bropines.tailscaled.core.RemoteManage
import io.github.bropines.tailscaled.core.RemotePairFailure
import io.github.bropines.tailscaled.core.RemotePeerApi
import io.github.bropines.tailscaled.core.RemoteProbe
import io.github.bropines.tailscaled.core.RemoteProblem
import io.github.bropines.tailscaled.core.RemoteProblemAction
import io.github.bropines.tailscaled.core.RemoteQr
import io.github.bropines.tailscaled.core.RemoteResult
import io.github.bropines.tailscaled.core.RemoteSession
import io.github.bropines.tailscaled.core.RemoteSetting
import io.github.bropines.tailscaled.core.RemoteStatus
import io.github.bropines.tailscaled.core.failure
import io.github.bropines.tailscaled.models.PeerData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Remote management, the managing device's side (core/RemoteManage.kt): the entry in a peer's
 * details, pairing with the six digits from the other device's screen, and the sheet that
 * manages it — status, exit node, three switches, a reconnect, received files, forgetting it.
 *
 * Two ways in, both for a screen someone else owns:
 *   - rememberRemoteManage(peer) + RemoteManageButton(entry): the peer's details. The first
 *     asks the peer, once, whether it offers management, and belongs in the screen's own
 *     composition; the button draws nothing until the answer is yes, and may sit inside a
 *     sheet. RemoteManageEntry(peer) is both at once, for a place that is not in a sheet.
 *   - remotePairingQr(text) + RemoteManageSheet(…, code = …): a pairing code read by the
 *     app's scanner. The sheet opens with the code filled in; pairing is the person's press.
 *
 * Every request can fail, and each failure is said as one sentence with one thing to do
 * (RemoteProblem). Nothing here retries on its own, beyond reading the status again while the
 * sheet is open and waiting out a reconnect it asked for itself.
 */

// ─── The entry in a peer's details ───────────────────────────────────────────────────────────

/** What the peer's details know about managing that peer. */
@Stable
class RemoteManageEntryState internal constructor(internal val label: String) {
    /** The peer answered as a TailSocks that offers management, or this device is paired with it. */
    var offered by mutableStateOf(false)
        internal set
    internal var tv by mutableStateOf(false)
    internal var open by mutableStateOf(false)
}

/** Before a peer is asked: turning the pages of the details past it costs it nothing. */
private const val PROBE_AFTER_MS = 350L

/**
 * Whether a peer could be a TailSocks at all, by the OS it reports: Android when it says what
 * it is, Linux by default (the hostinfo masquerade). The rest are never asked.
 */
private fun PeerData.mayBeTailSocks(): Boolean = os.isNullOrEmpty() || os.lowercase() in setOf("android", "linux")

/**
 * Asks [peer] whether it offers remote management — one small request when the details open,
 * only of a peer that is online and could be a TailSocks — and holds the sheet the button
 * opens. A device this one is already paired with is offered whatever it answers, so that it
 * can be forgotten while it is away. Call it in the screen's own composition, outside any
 * sheet: its strings and its sheet take the language from there (see wrapContextWithLocale).
 */
@Composable
fun rememberRemoteManage(peer: PeerData?, isSelf: Boolean = false): RemoteManageEntryState {
    val context = LocalContext.current.applicationContext
    val live = !LocalInspectionMode.current && LocalDemo.current == null
    val id = peer?.id.orEmpty()
    val online = peer?.online == true
    val label = stringResource(R.string.remote_manage_button)
    val entry = remember(id, label) { RemoteManageEntryState(label) }
    var codeOnShow by remember(id) { mutableStateOf(true) }

    if (live && !isSelf && RemoteJson.isNodeId(id)) {
        // An offline peer would cost the whole wait and answer nothing.
        val ask = online && peer?.mayBeTailSocks() == true
        LaunchedEffect(id, ask) {
            delay(PROBE_AFTER_MS)
            val (known, probe) = withContext(Dispatchers.IO) {
                val record = runCatching {
                    RemoteManage.tokens(context).get(AccountManager.getActiveAccount(context).id, id)
                }.getOrNull()
                val probe = if (ask && Appctr.isRunning()) runCatching { BridgeRemotePeerApi.probe(id) }.getOrNull() else null
                record to probe
            }
            entry.offered = known != null || probe?.ok == true
            entry.tv = probe?.tv ?: (known?.tv == true)
            codeOnShow = probe?.pairing ?: true
        }
    }
    if (entry.open && peer != null) {
        RemoteManageSheet(
            peerId = id,
            name = peer.getDisplayName(),
            online = online,
            tv = entry.tv,
            codeOnShow = codeOnShow,
            onDismiss = { entry.open = false }
        )
    }
    return entry
}

/** "Manage this device", with a television's glyph for one. Nothing unless the peer offers it. */
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
    }
}

/** [rememberRemoteManage] and [RemoteManageButton] together, for a place that is not inside a sheet. */
@Composable
fun RemoteManageEntry(peer: PeerData, modifier: Modifier = Modifier, isSelf: Boolean = false) {
    RemoteManageButton(rememberRemoteManage(peer, isSelf), modifier)
}

/** [text] as a pairing code read from a managed device's screen, or null: for the app's scanner. */
fun remotePairingQr(text: String): RemoteQr? = runCatching { BridgeRemotePeerApi.parseQr(text) }.getOrNull()

private fun remoteGlyph(tv: Boolean): ImageVector = if (tv) Icons.Default.Tv else Icons.Default.SettingsRemote

// ─── What the sheet draws ────────────────────────────────────────────────────────────────────

/** Why pairing did not happen, with what the sentence needs. */
@Immutable
data class RemotePairError(
    val failure: RemotePairFailure,
    val attemptsLeft: Int? = null,
    /** The name in a code that belongs to another device. */
    val other: String = "",
    /** Whether the device is in this device's network map as online, for "no answer". */
    val online: Boolean = false,
)

sealed interface RemoteManageUi {
    val name: String
    val tv: Boolean

    /** Not paired: the six digits are asked for. */
    @Immutable
    data class Pairing(
        override val name: String,
        override val tv: Boolean = false,
        val code: String = "",
        val busy: Boolean = false,
        /** The device said a code is on show; false when it said none is. */
        val codeOnShow: Boolean = true,
        val error: RemotePairError? = null,
    ) : RemoteManageUi

    /** Paired: the device as it last answered. */
    @Immutable
    data class Managing(
        override val name: String,
        override val tv: Boolean = false,
        val owner: Boolean = false,
        /** Seconds the pairing has left; -1 for until revoked. */
        val left: Long = -1,
        val status: RemoteStatus? = null,
        val settings: List<RemoteSetting> = emptyList(),
        val files: List<RemoteFile>? = null,
        /** The exit-node list is on show; [exitNodes] is null until it arrives. */
        val picking: Boolean = false,
        val exitNodes: List<RemoteExitNode>? = null,
        val loading: Boolean = false,
        val busy: Boolean = false,
        val problem: RemoteProblem? = null,
        /** The managed device's own words for a refusal. */
        val problemDetail: String = "",
        val reconnecting: Boolean = false,
        val confirmDelete: String? = null,
        val confirmForget: Boolean = false,
    ) : RemoteManageUi {
        /** The controls answer: nothing is wrong, nothing is in flight, and the device is not restarting. */
        val usable: Boolean get() = problem == null && !busy && !loading && !reconnecting
    }

    /** Forgotten on this device while the other could not be told. */
    @Immutable
    data class Forgotten(override val name: String, override val tv: Boolean = false) : RemoteManageUi
}

interface RemoteManageActions {
    fun setCode(text: String)
    fun pair()
    /** The one action offered next to a problem. */
    fun resolve(action: RemoteProblemAction)
    fun openExitNodes()
    fun closeExitNodes()
    fun pickExitNode(id: String)
    fun setSwitch(key: String, on: Boolean)
    fun reconnect()
    /** Asks before deleting [name]; null withdraws the question. */
    fun askDelete(name: String?)
    fun deleteFile(name: String)
    fun askForget(ask: Boolean)
    fun forget()
    fun close()

    object None : RemoteManageActions {
        override fun setCode(text: String) {}
        override fun pair() {}
        override fun resolve(action: RemoteProblemAction) {}
        override fun openExitNodes() {}
        override fun closeExitNodes() {}
        override fun pickExitNode(id: String) {}
        override fun setSwitch(key: String, on: Boolean) {}
        override fun reconnect() {}
        override fun askDelete(name: String?) {}
        override fun deleteFile(name: String) {}
        override fun askForget(ask: Boolean) {}
        override fun forget() {}
        override fun close() {}
    }
}

// ─── The sheet and what drives it ────────────────────────────────────────────────────────────

/** How often the status is read again while the sheet is open and nothing is wrong. */
private const val STATUS_EVERY_MS = 10_000L
/** After a reconnect: how long the device is left alone, then how often and how many times it is asked. */
private const val RECONNECT_WAIT_MS = 5_000L
private const val RECONNECT_RETRY_MS = 4_000L
private const val RECONNECT_TRIES = 4
/** Between a change of the exit node and reading the status back. */
private const val SETTLE_MS = 800L

/**
 * The sheet that pairs with a device and manages it. [peerId] is the peer's StableNodeID;
 * [online] what this device's network map says of it. [code] fills the pairing code in — a
 * scanned one — and pairing stays the person's press. Call it in the screen's own
 * composition, not inside another sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteManageSheet(
    peerId: String,
    name: String,
    online: Boolean,
    onDismiss: () -> Unit,
    tv: Boolean = false,
    codeOnShow: Boolean = true,
    code: String = "",
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberFullSheetState()
    val isOnline by rememberUpdatedState(online)
    val hide: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    val close by rememberUpdatedState(hide)

    val controller = remember(peerId) {
        val app = context.applicationContext
        RemoteManageController(
            session = RemoteSession(BridgeRemotePeerApi, RemoteManage.tokens(app), AccountManager.getActiveAccount(app).id, peerId),
            api = BridgeRemotePeerApi,
            scope = scope,
            peerOnline = { isOnline },
            onClose = { close() },
            name = name,
            tv = tv,
            codeOnShow = codeOnShow,
            code = code,
        )
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller) {
        controller.start()
        // The status is read again only while the sheet is on screen.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(STATUS_EVERY_MS)
                controller.tick()
            }
        }
    }
    // The scanner is asked for here, not in the sheet: it launches from the screen's own window.
    val scanner = rememberQrScanner { text -> controller.scanned(text) }
    val canScan = Feature.QR_SCANNER.offered()
    val scan = scanner.takeIf { canScan }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // The sheet's window answers in the system language; the screen's own does not.
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalResources provides resources
        ) {
            RemoteManageContent(controller.ui, controller, onScan = scan)
        }
    }
}

/**
 * The sheet's state and everything that changes it. Requests run on [scope], off the main
 * thread; what they bring back is one new [ui].
 */
@Stable
internal class RemoteManageController(
    private val session: RemoteSession,
    private val api: RemotePeerApi,
    private val scope: CoroutineScope,
    private val peerOnline: () -> Boolean,
    private val onClose: () -> Unit,
    private val name: String,
    private val tv: Boolean,
    private val codeOnShow: Boolean,
    code: String = "",
) : RemoteManageActions {

    var ui by mutableStateOf<RemoteManageUi>(RemoteManageUi.Managing(name, tv, loading = true))
        private set

    private val scanned = code.filter { it in '0'..'9' }.take(RemoteJson.CODE_LENGTH)

    private val managing: RemoteManageUi.Managing? get() = ui as? RemoteManageUi.Managing
    private fun pairing(code: String = "") = RemoteManageUi.Pairing(name, tv, code = code, codeOnShow = codeOnShow)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Paired already, or the digits are asked for. */
    suspend fun start() {
        val record = io { session.pairing() }
        if (record == null) {
            ui = pairing(scanned)
            return
        }
        // The name stays the one this device knows the peer by: it is what was pressed.
        ui = RemoteManageUi.Managing(
            name = name.ifEmpty { record.name },
            tv = tv || record.tv,
            owner = record.owner,
            loading = true,
        )
        load()
    }

    /** The periodic look at the status; quiet unless something changed. */
    suspend fun tick() {
        val m = managing ?: return
        if (!m.usable || m.picking || m.reconnecting) return
        when (val status = io { session.status() }) {
            is RemoteResult.Ok -> managing?.let { ui = it.withStatus(status.value) }
            is RemoteResult.Failed -> fail(status)
        }
    }

    private fun RemoteManageUi.Managing.withStatus(status: RemoteStatus) = copy(
        status = status,
        name = name.ifEmpty { status.name },
        tv = tv || status.deviceClass == RemoteProbe.DEVICE_TV,
        owner = status.you.owner,
        left = status.you.left,
    )

    /** No answer, or an app there that is not ready yet: what a device does while it restarts. */
    private fun RemoteResult<*>.away(): Boolean =
        this is RemoteResult.Failed && (failure == RemoteFailure.UNREACHABLE || failure == RemoteFailure.UNAVAILABLE)

    /** Reads everything the sheet shows. Stops at the first thing that fails, and says it. */
    private suspend fun load(): Boolean {
        managing?.let { ui = it.copy(loading = true, problem = null, problemDetail = "") }
        val status = io { session.status() }
        if (status is RemoteResult.Failed) return fail(status)
        managing?.let { ui = it.withStatus((status as RemoteResult.Ok).value) }
        val settings = io { session.settings() }
        if (settings is RemoteResult.Failed) return fail(settings)
        val files = io { session.files() }
        if (files is RemoteResult.Failed) return fail(files)
        managing?.let {
            ui = it.copy(
                settings = (settings as RemoteResult.Ok).value.settings,
                files = (files as RemoteResult.Ok).value.files,
                loading = false,
                busy = false,
            )
        }
        return true
    }

    private fun fail(failed: RemoteResult.Failed): Boolean {
        managing?.let {
            ui = it.copy(
                problem = RemoteProblem.of(failed.failure, peerOnline()),
                problemDetail = failed.message,
                loading = false,
                busy = false,
                picking = false,
                reconnecting = false,
                confirmDelete = null,
                confirmForget = false,
            )
        }
        return false
    }

    /** One change: [request] is sent, and what it changed is read back by [then]. */
    private fun change(request: () -> RemoteResult<Unit>, then: suspend () -> Unit) {
        val m = managing ?: return
        if (!m.usable) return
        ui = m.copy(busy = true)
        scope.launch {
            when (val result = io(request)) {
                is RemoteResult.Ok -> then()
                is RemoteResult.Failed -> fail(result)
            }
            managing?.let { ui = it.copy(busy = false) }
        }
    }

    override fun setCode(text: String) {
        val p = ui as? RemoteManageUi.Pairing ?: return
        ui = p.copy(code = text.filter { it in '0'..'9' }.take(RemoteJson.CODE_LENGTH), error = null)
    }

    override fun pair() {
        val p = ui as? RemoteManageUi.Pairing ?: return
        if (p.busy || !RemoteJson.isCode(p.code)) return
        ui = p.copy(busy = true, error = null)
        scope.launch {
            val answer = io { session.pair(p.code, tv) }
            if (answer.ok) {
                start()
            } else {
                ui = p.copy(busy = false, error = RemotePairError(answer.failure(), answer.attemptsLeft, online = peerOnline()))
            }
        }
    }

    /** A code read by the scanner: this device's own is typed in and sent, anything else is said. */
    fun scanned(text: String) {
        val p = ui as? RemoteManageUi.Pairing ?: return
        scope.launch {
            val qr = io { runCatching { api.parseQr(text) }.getOrNull() }
            when {
                qr == null -> ui = p.copy(error = RemotePairError(RemotePairFailure.NOT_A_CODE))
                qr.nodeId != session.peerId -> ui = p.copy(error = RemotePairError(RemotePairFailure.OTHER_DEVICE, other = qr.name))
                else -> {
                    ui = p.copy(code = qr.code, error = null)
                    pair()
                }
            }
        }
    }

    override fun resolve(action: RemoteProblemAction) {
        when (action) {
            RemoteProblemAction.RETRY, RemoteProblemAction.REFRESH -> scope.launch { load() }
            RemoteProblemAction.PAIR_AGAIN -> ui = pairing()
            RemoteProblemAction.DISMISS -> managing?.let { ui = it.copy(problem = null, problemDetail = "") }
            RemoteProblemAction.CLOSE -> onClose()
        }
    }

    override fun openExitNodes() {
        val m = managing ?: return
        if (!m.usable) return
        ui = m.copy(picking = true, exitNodes = null)
        scope.launch {
            when (val nodes = io { session.exitNodes() }) {
                is RemoteResult.Ok -> managing?.let { if (it.picking) ui = it.copy(exitNodes = nodes.value.nodes) }
                is RemoteResult.Failed -> fail(nodes)
            }
        }
    }

    override fun closeExitNodes() {
        managing?.let { ui = it.copy(picking = false) }
    }

    override fun pickExitNode(id: String) = change({ session.setExitNode(id) }) {
        managing?.let { ui = it.copy(picking = false) }
        // The daemon there takes a moment to route through the new node.
        delay(SETTLE_MS)
        when (val status = io { session.status() }) {
            is RemoteResult.Ok -> managing?.let { ui = it.withStatus(status.value) }
            is RemoteResult.Failed -> fail(status)
        }
    }

    override fun setSwitch(key: String, on: Boolean) = change({ session.setSwitch(key, on) }) {
        when (val settings = io { session.settings() }) {
            is RemoteResult.Ok -> managing?.let { ui = it.copy(settings = settings.value.settings) }
            is RemoteResult.Failed -> fail(settings)
        }
    }

    /**
     * The device restarts its connection, and with it the endpoint: it is left alone for a
     * few seconds and then asked until it answers, before silence counts as a problem.
     */
    override fun reconnect() = change({ session.reconnect() }) {
        managing?.let { ui = it.copy(reconnecting = true, busy = false) }
        delay(RECONNECT_WAIT_MS)
        var last: RemoteResult<RemoteStatus> = io { session.status() }
        var tries = 1
        while (last.away() && tries < RECONNECT_TRIES && managing?.reconnecting == true) {
            delay(RECONNECT_RETRY_MS)
            last = io { session.status() }
            tries++
        }
        managing?.let { ui = it.copy(reconnecting = false) }
        when (val result = last) {
            is RemoteResult.Ok -> managing?.let { ui = it.withStatus(result.value) }
            is RemoteResult.Failed -> fail(result)
        }
    }

    override fun askDelete(name: String?) {
        managing?.let { ui = it.copy(confirmDelete = name) }
    }

    override fun deleteFile(name: String) {
        managing?.let { ui = it.copy(confirmDelete = null) }
        change({ session.deleteFile(name) }) {
            when (val files = io { session.files() }) {
                is RemoteResult.Ok -> managing?.let { ui = it.copy(files = files.value.files) }
                is RemoteResult.Failed -> fail(files)
            }
        }
    }

    override fun askForget(ask: Boolean) {
        managing?.let { ui = it.copy(confirmForget = ask) }
    }

    override fun forget() {
        val m = managing ?: return
        ui = m.copy(busy = true, confirmForget = false)
        scope.launch {
            when (io { session.unpair() }) {
                is RemoteResult.Ok -> onClose()
                // The token is gone from here either way; the other side was not told.
                is RemoteResult.Failed -> ui = RemoteManageUi.Forgotten(m.name, m.tv)
            }
        }
    }

    override fun close() = onClose()
}

// ─── Drawing ─────────────────────────────────────────────────────────────────────────────────

/** What the sheet holds, without the sheet: a preview draws this. */
@Composable
internal fun RemoteManageContent(ui: RemoteManageUi, actions: RemoteManageActions, onScan: (() -> Unit)?) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val picking = (ui as? RemoteManageUi.Managing)?.picking == true
        if (!picking) Header(ui)
        when (ui) {
            is RemoteManageUi.Pairing -> PairingBody(ui, actions, onScan)
            is RemoteManageUi.Managing -> if (picking) ExitNodeList(ui, actions) else ManagingBody(ui, actions)
            is RemoteManageUi.Forgotten -> {
                Text(stringResource(R.string.remote_forget_local, ui.name), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = actions::close, shape = MaterialTheme.shapes.medium, modifier = Modifier.tvInitialFocus()) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

/** The device, that this is experimental, and what this device is to it. */
@Composable
private fun Header(ui: RemoteManageUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(remoteGlyph(ui.tv), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.remote_manage_title, ui.name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.remote_experimental),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (ui is RemoteManageUi.Managing && ui.status != null) {
            Spacer(Modifier.width(8.dp))
            RemoteRoleTag(
                ui.owner,
                text = if (ui.owner || ui.left < 0) null else stringResource(R.string.remote_guest_left, remoteDurationText(ui.left))
            )
        }
    }
}

@Composable
private fun PairingBody(ui: RemoteManageUi.Pairing, actions: RemoteManageActions, onScan: (() -> Unit)?) {
    Text(stringResource(R.string.remote_pair_enter, ui.name), style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun problemText(problem: RemoteProblem, name: String, detail: String): String = when (problem) {
    RemoteProblem.NOT_CONNECTED -> stringResource(R.string.remote_problem_not_connected)
    RemoteProblem.NO_PEER -> stringResource(R.string.remote_problem_no_peer, name)
    RemoteProblem.OFFLINE -> stringResource(R.string.remote_problem_offline, name)
    RemoteProblem.NOT_ACCEPTING -> stringResource(R.string.remote_problem_not_accepting, name)
    RemoteProblem.REVOKED -> stringResource(R.string.remote_problem_revoked, name)
    RemoteProblem.EXPIRED -> stringResource(R.string.remote_problem_expired, name)
    RemoteProblem.OWNER_ONLY -> stringResource(R.string.remote_problem_owner_only)
    RemoteProblem.GONE -> stringResource(R.string.remote_problem_gone)
    RemoteProblem.REFUSED ->
        if (detail.isEmpty()) stringResource(R.string.remote_problem_refused, name)
        else stringResource(R.string.remote_problem_refused_why, name, detail.trimEnd('.'))
    RemoteProblem.SLOW_DOWN -> stringResource(R.string.remote_problem_slow)
    RemoteProblem.BUSY -> stringResource(R.string.remote_problem_busy, name)
    RemoteProblem.PENDING -> stringResource(R.string.remote_problem_pending, name)
    RemoteProblem.FAILED -> stringResource(R.string.remote_problem_failed, name)
}

@Composable
private fun problemActionText(action: RemoteProblemAction): String = stringResource(
    when (action) {
        RemoteProblemAction.RETRY -> R.string.action_retry
        RemoteProblemAction.PAIR_AGAIN -> R.string.remote_action_pair_again
        RemoteProblemAction.REFRESH -> R.string.action_refresh
        RemoteProblemAction.DISMISS -> R.string.remote_action_ok
        RemoteProblemAction.CLOSE -> R.string.action_close
    }
)

/** One sentence and one thing to do about it. */
@Composable
private fun ProblemCard(problem: RemoteProblem, name: String, detail: String, onAction: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Warning, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(problemText(problem, name, detail), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onAction,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.End).tvInitialFocus()
            ) { Text(problemActionText(problem.action)) }
        }
    }
}

/** A block of the sheet: rows on one shared ground. */
@Composable
private fun Block(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
}

@Composable
private fun ManagingBody(ui: RemoteManageUi.Managing, actions: RemoteManageActions) {
    ui.problem?.let { problem ->
        ProblemCard(problem, ui.name, ui.problemDetail) { actions.resolve(problem.action) }
    }
    val status = ui.status
    if (status == null) {
        if (ui.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.remote_loading, ui.name), style = MaterialTheme.typography.bodyMedium)
            }
        }
        // Not managed any more, or never reached: all that is left is to forget it.
        if (ui.problem?.final != true) ForgetRow(ui, actions)
        return
    }
    if (ui.problem?.final == true) return

    // What the device last said stays readable while it cannot be asked, dimmed: it is not news.
    Column(
        Modifier.alpha(if (ui.problem?.stale == true) 0.5f else 1f),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) { ManagedState(ui, status, actions) }
    ForgetRow(ui, actions)
}

/** The device as it last answered: status, exit node and switches, Reconnect, received files. */
@Composable
private fun ManagedState(ui: RemoteManageUi.Managing, status: RemoteStatus, actions: RemoteManageActions) {
    StatusBlock(status)

    Block {
        val node = status.exitNode
        val value = when {
            node == null -> stringResource(R.string.remote_exit_none)
            node.online -> node.name.ifEmpty { node.id }
            else -> stringResource(R.string.remote_exit_offline, node.name.ifEmpty { node.id })
        }
        ValueRow(stringResource(R.string.remote_exit_node), value, enabled = ui.usable, onClick = actions::openExitNodes)
        ui.settings.filter { it.kind == "bool" }.forEach { setting ->
            SwitchRow(
                title = settingTitle(setting.key),
                note = if (setting.error.isNotEmpty()) stringResource(R.string.remote_setting_unknown) else null,
                checked = setting.on,
                enabled = ui.usable && setting.writable && setting.error.isEmpty(),
                onChange = { actions.setSwitch(setting.key, it) }
            )
        }
    }
    if (!ui.owner) {
        // Said once, under what it is about: why the switches and the button below do not answer.
        Text(
            stringResource(R.string.remote_guest_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    FilledTonalButton(
        onClick = actions::reconnect,
        enabled = ui.usable && ui.owner && !ui.reconnecting,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)
    ) {
        if (ui.reconnecting) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(R.string.remote_reconnect))
    }
    if (ui.reconnecting) {
        Text(
            stringResource(R.string.remote_reconnecting, ui.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    ui.files?.let { FilesBlock(ui, it, actions) }
}

@Composable
private fun settingTitle(key: String): String = when (key) {
    RemoteKeys.ACCEPT_DNS -> stringResource(R.string.remote_setting_accept_dns)
    RemoteKeys.ACCEPT_ROUTES -> stringResource(R.string.remote_setting_accept_routes)
    RemoteKeys.EXIT_NODE_LAN -> stringResource(R.string.remote_setting_lan)
    else -> key
}

@Composable
private fun backendStateText(state: String): String = when (state) {
    "NeedsLogin" -> stringResource(R.string.remote_state_needs_login)
    "NeedsMachineAuth" -> stringResource(R.string.remote_state_needs_approval)
    "Starting" -> stringResource(R.string.remote_state_starting)
    "Stopped" -> stringResource(R.string.remote_state_stopped)
    else -> state.ifEmpty { stringResource(R.string.remote_status_not_running) }
}

/** Running or not and in what state, the versions, and what the daemon complains about. */
@Composable
private fun StatusBlock(status: RemoteStatus) {
    Block {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (status.running) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    null,
                    tint = if (status.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (status.running) stringResource(R.string.remote_status_running) else backendStateText(status.backendState),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (status.version.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    if (status.appVersion.isEmpty()) stringResource(R.string.remote_status_version, status.version)
                    else stringResource(R.string.remote_status_version_app, status.version, status.appVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            if (status.health.isEmpty()) {
                Text(
                    stringResource(R.string.remote_status_health_ok),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            status.health.forEach { warning ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(warning, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** A row that opens something: a title, its present value, an arrow. */
@Composable
private fun ValueRow(title: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .tvFocusRing(source, shape)
            .clip(shape)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, note: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .tvFocusRing(source, shape)
            .clip(shape)
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Received Taildrop files: names, sizes and dates. An owner deletes, after being asked once. */
@Composable
private fun FilesBlock(ui: RemoteManageUi.Managing, files: List<RemoteFile>, actions: RemoteManageActions) {
    val context = LocalContext.current
    Text(
        stringResource(R.string.remote_files_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    if (files.isEmpty()) {
        Text(
            stringResource(R.string.remote_files_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Block {
        files.take(FILES_SHOWN).forEach { file ->
            if (ui.confirmDelete == file.name) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.remote_file_confirm, file.name), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { actions.askDelete(null) }, modifier = Modifier.tvInitialFocus()) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        TextButton(
                            onClick = { actions.deleteFile(file.name) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text(stringResource(R.string.action_delete)) }
                    }
                }
            } else {
                // A row nobody may delete from has nothing for a remote to stop on, and a list
                // of them could not be scrolled: on a television such a row is a stop itself.
                val source = remember { MutableInteractionSource() }
                val stop = if (isTv && !ui.owner) {
                    Modifier.tvFocusRing(source, MaterialTheme.shapes.medium).focusable(interactionSource = source)
                } else Modifier
                Row(stop.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(
                            Formatter.formatShortFileSize(context, file.size) + " · " + remember(file.modTime) { fileDate(file.modTime) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (ui.owner) {
                        IconButton(onClick = { actions.askDelete(file.name) }, enabled = ui.usable) {
                            Icon(Icons.Default.Delete, stringResource(R.string.remote_file_delete, file.name))
                        }
                    }
                }
            }
        }
    }
    val more = files.size - FILES_SHOWN
    if (more > 0) {
        Text(
            pluralStringResource(R.plurals.remote_files_more, more, more),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** How many received files the sheet lists; the rest are counted. */
private const val FILES_SHOWN = 50

private fun fileDate(unixSeconds: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(unixSeconds * 1000))

/** "Forget this device", asked once before it is done: the way back is a new code from that device's screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForgetRow(ui: RemoteManageUi.Managing, actions: RemoteManageActions) {
    if (!ui.confirmForget) {
        TextButton(
            onClick = { actions.askForget(true) },
            enabled = !ui.busy,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text(stringResource(R.string.remote_forget)) }
        return
    }
    Text(stringResource(R.string.remote_forget_confirm, ui.name), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.askForget(false) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.tvInitialFocus()) {
            Text(stringResource(R.string.action_cancel))
        }
        Button(
            onClick = actions::forget,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) { Text(stringResource(R.string.remote_forget_action)) }
    }
}

/** The managed device's own exit nodes, with "None" first. One that is offline cannot be chosen. */
@Composable
private fun ExitNodeList(ui: RemoteManageUi.Managing, actions: RemoteManageActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions::closeExitNodes) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.remote_exit_pick_title, ui.name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    val nodes = ui.exitNodes
    if (nodes == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.remote_loading, ui.name), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val selected = nodes.firstOrNull { it.selected }?.id ?: ui.status?.exitNode?.id.orEmpty()
    Block {
        ExitNodeRow(stringResource(R.string.remote_exit_none), null, selected.isEmpty(), enabled = !ui.busy) { actions.pickExitNode("") }
        nodes.forEach { node ->
            val place = listOf(node.city, node.country).filter { it.isNotEmpty() }.joinToString(", ")
            val note = listOfNotNull(
                place.ifEmpty { null },
                if (node.online) null else stringResource(R.string.remote_exit_offline_tag)
            ).joinToString(" · ").ifEmpty { null }
            ExitNodeRow(node.name.ifEmpty { node.id }, note, node.id == selected, enabled = !ui.busy && (node.online || node.id == selected)) {
                actions.pickExitNode(node.id)
            }
        }
    }
    if (nodes.isEmpty()) {
        Text(
            stringResource(R.string.remote_exit_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExitNodeRow(title: String, note: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .tvFocusRing(source, shape)
            .clip(shape)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
