package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bropines.tailscaled.core.ManagedAccess
import io.github.bropines.tailscaled.core.ManagedApp
import io.github.bropines.tailscaled.core.ManagedCaps
import io.github.bropines.tailscaled.core.ManagedChanged
import io.github.bropines.tailscaled.core.ManagedExitNode
import io.github.bropines.tailscaled.core.ManagedFetch
import io.github.bropines.tailscaled.core.ManagedGuard
import io.github.bropines.tailscaled.core.ManagedKeys
import io.github.bropines.tailscaled.core.ManagedLock
import io.github.bropines.tailscaled.core.ManagedOp
import io.github.bropines.tailscaled.core.ManagedPermission
import io.github.bropines.tailscaled.core.ManagedRefusal
import io.github.bropines.tailscaled.core.ManagedResult
import io.github.bropines.tailscaled.core.ManagedSetting
import io.github.bropines.tailscaled.core.ManagedShown
import io.github.bropines.tailscaled.core.ManagedStatus
import io.github.bropines.tailscaled.core.RemoteClient
import io.github.bropines.tailscaled.core.RemoteFile
import io.github.bropines.tailscaled.core.RemoteJson
import io.github.bropines.tailscaled.core.RemotePairFailure
import io.github.bropines.tailscaled.core.cidrList
import io.github.bropines.tailscaled.core.clearOnly
import io.github.bropines.tailscaled.core.failure
import io.github.bropines.tailscaled.core.isShareName
import io.github.bropines.tailscaled.core.lock
import io.github.bropines.tailscaled.core.setting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * What the managing page (ui/RemoteManagePage.kt) shows and everything that changes it. No
 * Android in here: the device is a RemoteClient, time and waiting are handed in, so the whole
 * of it runs in a plain test against the contract's fake device.
 *
 * Three rules the page keeps, and this file is where they are kept:
 *   - a device is offered only what it announces (ManagedAccess), and is never sent a field
 *     it did not announce;
 *   - nothing is claimed that the device did not say. After an answer that leaves the outcome
 *     open the device is read again, and the page shows what it says now;
 *   - a change that could cut the channel is confirmed without asking the person anything
 *     (GuardKeeper), and when it cannot be, the page says what the device will do by itself.
 */

// ─── What the page draws ─────────────────────────────────────────────────────────────────────

/** The one thing offered next to a problem. */
enum class ManageProblemAction { RETRY, PAIR_AGAIN, DISMISS, CLOSE }

/** What went wrong while managing a device: one sentence and one action each. */
enum class ManageProblem {
    NOT_CONNECTED,
    NO_PEER,
    OFFLINE,
    /** In the tailnet and not answering on the port: management is off there, or the mode keeps peers out. */
    NOT_ACCEPTING,
    REVOKED,
    EXPIRED,
    OWNER_ONLY,
    /** Full control was needed and is off there. */
    NEEDS_FULL,
    /** What was asked about is no longer there: the lists were read again. */
    GONE,
    /** The device's app said no, in its own words. */
    REFUSED,
    /** Another change still waits to be confirmed there. */
    GUARD_PENDING,
    SLOW_DOWN,
    /** The app there was not asked: nothing was changed. */
    BUSY,
    /** The app was asked and did not say: the page shows what the device says now. */
    UNCLEAR,
    FAILED;

    /** The pairing is gone or the device is: nothing on the page can be used any more. */
    val final: Boolean get() = this == REVOKED || this == EXPIRED || this == NO_PEER

    /** The device cannot be asked: what the page shows of it is what it said before. */
    val stale: Boolean get() = this == NOT_CONNECTED || this == OFFLINE || this == NOT_ACCEPTING

    /** [loaded]: the page has something of the device to show, so a passing problem only needs to be read. */
    fun action(loaded: Boolean): ManageProblemAction = when {
        this == NO_PEER -> ManageProblemAction.CLOSE
        this == REVOKED || this == EXPIRED -> ManageProblemAction.PAIR_AGAIN
        stale || !loaded -> ManageProblemAction.RETRY
        else -> ManageProblemAction.DISMISS
    }

    companion object {
        /**
         * [peerOnline] is what this device's own network map says of the peer. No answer from
         * one that is online means the port is closed to us — nobody is merely away.
         */
        fun of(refusal: ManagedRefusal, peerOnline: Boolean): ManageProblem = when (refusal) {
            ManagedRefusal.NOT_CONNECTED -> NOT_CONNECTED
            ManagedRefusal.NO_PEER -> NO_PEER
            ManagedRefusal.UNREACHABLE -> if (peerOnline) NOT_ACCEPTING else OFFLINE
            ManagedRefusal.UNAUTHORIZED -> REVOKED
            ManagedRefusal.EXPIRED -> EXPIRED
            ManagedRefusal.FORBIDDEN -> OWNER_ONLY
            ManagedRefusal.NEEDS_FULL -> NEEDS_FULL
            ManagedRefusal.NOT_FOUND -> GONE
            ManagedRefusal.REFUSED -> REFUSED
            ManagedRefusal.GUARD_PENDING -> GUARD_PENDING
            ManagedRefusal.SLOW_DOWN -> SLOW_DOWN
            ManagedRefusal.UNAVAILABLE -> BUSY
            ManagedRefusal.PENDING, ManagedRefusal.GARBLED -> UNCLEAR
            ManagedRefusal.TOO_EARLY, ManagedRefusal.FAILED -> FAILED
        }
    }
}

/** Where a provisional change stands. */
enum class GuardPhase {
    /** Sent and applied there for now; being confirmed over the channel it may have cut. */
    CHECKING,
    /** The device does not answer. It will put the old value back by itself; this keeps trying. */
    SILENT,
    /** The time ran out with no answer: the old value is back there by now. Waiting to hear it. */
    REVERTED_AWAY,
    /** Confirmed: kept. */
    APPLIED,
    /** The device put the old value back, and says so. */
    REVERTED,
    /** The device was asked to keep it and did not say whether it has. */
    UNCLEAR;

    /** Still being worked on: nothing else is changed meanwhile. */
    val active: Boolean get() = this == CHECKING || this == SILENT || this == REVERTED_AWAY
}

/** A provisional change. [revertsAt] is this device's clock, milliseconds, for when the other puts the old value back. */
@Immutable
data class GuardState(val id: String, val key: String, val phase: GuardPhase, val revertsAt: Long)

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

/** A file on its way from this device to the managed one, or how the last send ended. */
@Immutable
data class RemoteSendState(
    /** The file being sent now, "2/5" over its name when there are several; null when none is. */
    val progress: String? = null,
    val sent: List<String> = emptyList(),
    /** The files that did not arrive, each with the reason. */
    val failed: List<Pair<String, String>> = emptyList(),
)

/** The device's log or its report, as asked for. */
@Immutable
data class RemoteDiagState(
    val log: Boolean,
    val loading: Boolean = true,
    val text: String = "",
    /** How many secrets the device replaced in the log before sending it. */
    val scrubbed: Int = 0,
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
        /** From the stored pairing until the device answers, then what it says. */
        val owner: Boolean = false,
        /** Seconds the pairing has left; -1 for until revoked. */
        val left: Long = -1,
        val status: ManagedStatus? = null,
        /** The app's last report: kept while a later status comes without one. */
        val app: ManagedApp? = null,
        val settings: List<ManagedSetting> = emptyList(),
        val files: List<RemoteFile>? = null,
        val loading: Boolean = false,
        /** A change is in flight. */
        val busy: Boolean = false,
        val problem: ManageProblem? = null,
        /** The managed device's own words for a refusal. */
        val problemDetail: String = "",
        val reconnecting: Boolean = false,
        val guard: GuardState? = null,
        /** The exit-node list is on show; [exitNodes] is null until it arrives. */
        val picking: Boolean = false,
        val exitNodes: List<ManagedExitNode>? = null,
        val identifying: Boolean = false,
        /** How the last "Identify" ended. */
        val identified: ManagedShown? = null,
        /** How the last request for each permission ended, until it is granted. */
        val asked: Map<String, ManagedShown> = emptyMap(),
        /** The status is read more often until this moment of this device's clock. */
        val watchUntil: Long = 0,
        /** Why this device's own daemon will not send the peer a file; null when it will. */
        val sendBlocked: String? = null,
        val send: RemoteSendState? = null,
        val diag: RemoteDiagState? = null,
        /** This device's clock, milliseconds: what "expires in" is measured against. */
        val now: Long = 0,
        /** This device's own app version, to notice an older one there. */
        val appVersion: String = "",
    ) : RemoteManageUi {
        val access: ManagedAccess get() = status?.let(::ManagedAccess) ?: ManagedAccess.NONE

        /** The controls answer: the device can be asked, and nothing is in flight. */
        val usable: Boolean
            get() = status != null && !busy && !loading && !reconnecting &&
                problem?.stale != true && problem?.final != true && guard?.phase?.active != true

        /** Something is expected to change there soon: the status is read every few seconds. */
        fun watching(now: Long): Boolean =
            app?.asked != null || now < watchUntil || status?.fetch?.state == ManagedFetch.SENDING || guard?.phase == GuardPhase.REVERTED_AWAY

        /** The exit node as the device has it now. */
        val exitNode: ManagedExitNode? get() = status?.exitNode

        /** This device's last "send me this file": under way, or ended within the ten minutes the device keeps it. */
        val fetch: ManagedFetch? get() = status?.fetch?.takeIf { access.has(ManagedCaps.STORAGE) && it.id.isNotEmpty() }
    }

    /** Forgotten on this device while the other could not be told. */
    @Immutable
    data class Forgotten(override val name: String, override val tv: Boolean = false) : RemoteManageUi
}

/** What the page can ask for. */
interface RemoteManageActions {
    fun setCode(text: String)
    fun pair()
    /** The one action offered next to a problem. */
    fun resolve(action: ManageProblemAction)
    fun refresh()
    fun dismissGuard()

    fun openExitNodes()
    fun closeExitNodes()
    fun pickExitNode(id: String)
    fun setSwitch(key: String, on: Boolean)
    /** The subnets the device offers, as typed; false when they are not a list of prefixes. */
    fun setRoutes(text: String): Boolean
    fun reconnect()
    /** An action that needs nothing read back but the status: `reconnect_relays`, `save_received`, `receive_folder_default`. */
    fun act(action: String)

    fun identify()
    fun askPermission(id: String)
    fun deleteFile(name: String)
    /** "Send me this file": the device sends it with its own Taildrop. */
    fun fetch(path: String)
    fun addShare(name: String, path: String)
    fun removeShare(name: String)
    fun openDiag(log: Boolean)
    fun closeDiag()

    fun forget()
    fun close()

    object None : RemoteManageActions {
        override fun setCode(text: String) {}
        override fun pair() {}
        override fun resolve(action: ManageProblemAction) {}
        override fun refresh() {}
        override fun dismissGuard() {}
        override fun openExitNodes() {}
        override fun closeExitNodes() {}
        override fun pickExitNode(id: String) {}
        override fun setSwitch(key: String, on: Boolean) {}
        override fun setRoutes(text: String) = true
        override fun reconnect() {}
        override fun act(action: String) {}
        override fun identify() {}
        override fun askPermission(id: String) {}
        override fun deleteFile(name: String) {}
        override fun fetch(path: String) {}
        override fun addShare(name: String, path: String) {}
        override fun removeShare(name: String) {}
        override fun openDiag(log: Boolean) {}
        override fun closeDiag() {}
        override fun forget() {}
        override fun close() {}
    }
}

// ─── Guarded changes ─────────────────────────────────────────────────────────────────────────

/** Between two tries of a confirmation that brought nothing back. */
private const val GUARD_RETRY_MS = 3_000L
/** How many tries in a row may go unanswered before the page says the device is silent: a restart takes a moment. */
private const val SILENT_AFTER = 2
/** After the deadline: how many times the device is asked before the page's own slower reading takes over. */
private const val AWAY_TRIES = 10

/** What a page is told about its device's provisional change. */
internal interface GuardListener {
    fun onGuard(state: GuardState?)
    /** The change was kept, or put back, or its pairing ended: time to read the device again. */
    suspend fun onSettled()
}

/**
 * Confirms provisional changes (contract §7). A change that can cut the channel it was made
 * over is applied on the device for a minute at most; unless the device that made it confirms
 * it over that channel, the old value comes back by itself. So the confirmation is the proof
 * that the device can still be reached — and it must not depend on the page staying open:
 * a person who picks an exit node and leaves would otherwise find it undone. The keeper
 * outlives its pages; one process-wide instance serves them all ([RemoteGuards]).
 *
 * It waits the time the device asks for, confirms, waits again when told it is too early,
 * and keeps trying while nothing comes back. When the time runs out unconfirmed it says the
 * device has put the old value back, and waits to hear from it. Touched from one thread only.
 */
internal class GuardKeeper(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val states = HashMap<String, GuardState>()
    private val listeners = HashMap<String, GuardListener>()

    fun state(peerId: String): GuardState? = states[peerId]

    /** A page for [peerId] is open: it hears of the change from now on, and is told where it stands. */
    fun attach(peerId: String, listener: GuardListener) {
        listeners[peerId] = listener
        listener.onGuard(states[peerId])
    }

    fun detach(peerId: String, listener: GuardListener) {
        if (listeners[peerId] === listener) listeners.remove(peerId)
    }

    /** Forgets a change that is over, once the page has said how it ended. */
    fun dismiss(peerId: String) {
        if (states[peerId]?.phase?.active == false) publish(peerId, null)
    }

    /**
     * A change of [key] went through with no guard while one of that key was being confirmed:
     * the person put the value back by hand, the device has ended the wait, and so does this.
     */
    fun release(peerId: String, key: String) {
        val state = states[peerId] ?: return
        if (state.key == key && state.phase.active) publish(peerId, null)
    }

    /** The device answered again after the deadline had passed in silence. */
    fun heardAgain(peerId: String) {
        val state = states[peerId] ?: return
        if (state.phase == GuardPhase.REVERTED_AWAY) publish(peerId, state.copy(phase = GuardPhase.REVERTED))
    }

    /**
     * Takes over a change the device answered with [guard]. A change it already watches is
     * left alone: a page reopened in the middle reads the same id from the status.
     */
    fun watch(client: RemoteClient, guard: ManagedGuard) {
        val peer = client.peerId
        if (guard.id.isEmpty()) return
        states[peer]?.let { if (it.id == guard.id && it.phase.active) return }
        val deadline = now() + guard.seconds.coerceAtLeast(0) * 1000
        publish(peer, GuardState(guard.id, guard.key, GuardPhase.CHECKING, deadline))
        scope.launch { confirm(client, guard, deadline) }
    }

    private suspend fun confirm(client: RemoteClient, guard: ManagedGuard, deadline: Long) {
        val peer = client.peerId
        // A later change of the same key replaces this one, with an id and a deadline of its own.
        fun mine() = states[peer]?.id == guard.id
        fun left() = deadline - now()
        fun phase(phase: GuardPhase) {
            val state = states[peer] ?: return
            if (state.phase != phase) publish(peer, state.copy(phase = phase))
        }

        pause((guard.after.coerceAtLeast(0) * 1000).coerceAtMost(left().coerceAtLeast(0)))
        var unanswered = 0
        while (mine() && left() > 0) {
            val answer = withContext(io) { client.confirm(guard.id) }
            if (!mine()) return
            val refusal = answer.failed?.refusal ?: return settle(peer, GuardPhase.APPLIED)
            if (refusal.away) {
                if (++unanswered >= SILENT_AFTER) phase(GuardPhase.SILENT)
            } else {
                unanswered = 0
                phase(GuardPhase.CHECKING)
            }
            when {
                // No such pending change: the time ran out there, and the old value is back.
                refusal == ManagedRefusal.NOT_FOUND -> return settle(peer, GuardPhase.REVERTED)
                // The pairing ended meanwhile: the page's next reading says so.
                refusal.final -> return settle(peer, null)
                refusal == ManagedRefusal.TOO_EARLY -> pause((answer.failed!!.after * 1000).coerceIn(500, left().coerceAtLeast(500)))
                refusal.unclear -> {
                    // Asked to keep it, and no word back. If it still waits there, ask again.
                    val status = withContext(io) { client.status() }
                    if (!mine()) return
                    if (status is ManagedResult.Ok && status.value.guard?.id != guard.id) return settle(peer, GuardPhase.UNCLEAR)
                    pause(GUARD_RETRY_MS)
                }
                else -> pause(GUARD_RETRY_MS)
            }
        }
        if (!mine()) return
        // Unconfirmed in time: the device has put the old value back by now, whether or not it can say so.
        phase(GuardPhase.REVERTED_AWAY)
        repeat(AWAY_TRIES) {
            val status = withContext(io) { client.status() }
            if (!mine()) return
            if (status is ManagedResult.Ok) return settle(peer, GuardPhase.REVERTED)
            if (status.failed?.refusal?.final == true) return settle(peer, null)
            pause(GUARD_RETRY_MS)
        }
    }

    private suspend fun settle(peer: String, phase: GuardPhase?) {
        publish(peer, phase?.let { states[peer]?.copy(phase = it) })
        listeners[peer]?.onSettled()
    }

    private fun publish(peer: String, state: GuardState?) {
        if (state == null) states.remove(peer) else states[peer] = state
        listeners[peer]?.onGuard(state)
    }
}

/** The keeper of the app: its changes are confirmed on the main thread's time, whatever page is open. */
internal object RemoteGuards {
    val keeper: GuardKeeper by lazy { GuardKeeper(CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)) }
}

// ─── The controller ──────────────────────────────────────────────────────────────────────────

/** After a reconnect: how long the device is left alone, then how often and how many times it is asked. */
private const val RECONNECT_WAIT_MS = 5_000L
private const val RECONNECT_RETRY_MS = 4_000L
private const val RECONNECT_TRIES = 4
/** Between a change of the exit node and reading the status back. */
private const val SETTLE_MS = 800L
/** After the relays were re-dialled and the answer was lost with them. */
private const val RELAYS_WAIT_MS = 4_000L
/** How long the status is read often after a page was put in front of someone there, and after a file was sent. */
internal const val WATCH_SHOWN_MS = 120_000L
internal const val WATCH_SENT_MS = 20_000L

/**
 * The page's state and everything that changes it. Requests run on [scope], off the main
 * thread ([io]); what they bring back is one new [ui]. [pause] and [now] are the waiting and
 * the clock, so a test can run a minute of a guarded change in no time.
 */
@Stable
internal class RemoteManageController(
    private val client: RemoteClient,
    private val scope: CoroutineScope,
    private val guards: GuardKeeper,
    private val peerOnline: () -> Boolean,
    private val onClose: () -> Unit,
    private val name: String,
    private val tv: Boolean = false,
    private val codeOnShow: Boolean = true,
    code: String = "",
    private val appVersion: String = "",
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) : RemoteManageActions, GuardListener {

    var ui by mutableStateOf<RemoteManageUi>(RemoteManageUi.Managing(name, tv, loading = true, now = now(), appVersion = appVersion))
        private set

    private val scanned = code.filter { it in '0'..'9' }.take(RemoteJson.CODE_LENGTH)

    private val managing: RemoteManageUi.Managing? get() = ui as? RemoteManageUi.Managing
    private fun pairing(code: String = "") = RemoteManageUi.Pairing(name, tv, code = code, codeOnShow = codeOnShow)

    private suspend fun <T> io(block: () -> T): T = withContext(io) { block() }

    private inline fun update(change: (RemoteManageUi.Managing) -> RemoteManageUi.Managing) {
        managing?.let { ui = change(it) }
    }

    /** Paired already, or the digits are asked for. */
    suspend fun start() {
        val record = io { client.pairing() }
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
            now = now(),
            appVersion = appVersion,
        )
        guards.attach(client.peerId, this)
        if (load()) resumeGuard()
    }

    /** The page is gone. A change still being confirmed stays with the keeper. */
    fun stop() = guards.detach(client.peerId, this)

    /**
     * A change that was still waiting when the page — or the app — was closed: its id is in
     * the status, and it can still be kept.
     */
    private fun resumeGuard() {
        val pending = managing?.status?.guard ?: return
        if (!managing!!.access.has(ManagedCaps.GUARD)) return
        guards.watch(client, ManagedGuard(pending.id, pending.key, seconds = pending.left, after = pending.after))
    }

    override fun onGuard(state: GuardState?) = update { it.copy(guard = state) }

    override suspend fun onSettled() {
        if (managing == null) return
        read()
    }

    /** The periodic look at the status; quiet unless the device went away or came back. */
    suspend fun tick() {
        val m = managing ?: return
        if (m.busy || m.loading || m.reconnecting || m.problem?.final == true) return
        if (m.status == null) {
            // Never reached yet, and away: asked again at the page's own pace until it is there.
            if (m.problem?.stale == true && read()) update { it.copy(problem = null, problemDetail = "") }
            return
        }
        // While a change is being confirmed the keeper does the talking.
        if (m.guard?.phase == GuardPhase.CHECKING || m.guard?.phase == GuardPhase.SILENT) return
        val away = m.problem?.stale == true
        when (val status = io { client.status() }) {
            is ManagedResult.Ok -> {
                update { it.withStatus(status.value) }
                guards.heardAgain(client.peerId)
                // Back after being away: everything it showed is old.
                if (away || m.guard?.phase == GuardPhase.REVERTED_AWAY) load()
                else if (now() < m.watchUntil) readFiles()
            }
            // A poll nobody asked for raises no banner over a passing refusal.
            is ManagedResult.Failed -> if (status.refusal.away || status.refusal.final) fail(status)
        }
    }

    private fun RemoteManageUi.Managing.withStatus(status: ManagedStatus): RemoteManageUi.Managing {
        val report = status.app ?: app
        val granted = report?.permissions.orEmpty().filter { it.state == ManagedPermission.GRANTED }.map { it.id }.toSet()
        return copy(
            status = status,
            app = report,
            name = name.ifEmpty { status.name },
            tv = tv || status.tv,
            owner = status.you.owner,
            left = status.you.left,
            asked = asked - granted,
            now = now(),
        )
    }

    /** Reads everything the page shows. Stops at the first thing that fails, and says it. */
    private suspend fun load(): Boolean {
        update { it.copy(loading = true, problem = null, problemDetail = "") }
        return read()
    }

    /** [load] without touching the banner: for reading back after something happened. */
    private suspend fun read(): Boolean {
        val status = io { client.status() }
        if (status is ManagedResult.Failed) return fail(status)
        update { it.withStatus((status as ManagedResult.Ok).value) }
        val settings = io { client.settings() }
        if (settings is ManagedResult.Failed) return fail(settings)
        val files = io { client.files() }
        if (files is ManagedResult.Failed) return fail(files)
        update {
            it.copy(
                settings = (settings as ManagedResult.Ok).value.settings,
                files = (files as ManagedResult.Ok).value.files,
                loading = false,
            )
        }
        return true
    }

    private suspend fun readStatus() {
        when (val status = io { client.status() }) {
            is ManagedResult.Ok -> update { it.withStatus(status.value) }
            is ManagedResult.Failed -> fail(status)
        }
    }

    private suspend fun readSettings() {
        when (val settings = io { client.settings() }) {
            is ManagedResult.Ok -> update { it.copy(settings = settings.value.settings) }
            is ManagedResult.Failed -> fail(settings)
        }
    }

    private suspend fun readFiles() {
        when (val files = io { client.files() }) {
            is ManagedResult.Ok -> update { it.copy(files = files.value.files) }
            is ManagedResult.Failed -> fail(files)
        }
    }

    private fun fail(failed: ManagedResult.Failed): Boolean {
        update {
            it.copy(
                problem = ManageProblem.of(failed.refusal, peerOnline()),
                problemDetail = failed.message,
                loading = false,
                busy = false,
                picking = false,
                reconnecting = false,
                identifying = false,
                diag = null,
            )
        }
        return false
    }

    /**
     * A request that did not do what was asked. Where the device may have done it anyway, or
     * where its lists have moved on, it is read again first: the page then shows what is
     * true now, and the sentence only says that it had to look.
     */
    private suspend fun failedChange(failed: ManagedResult.Failed) {
        val refusal = failed.refusal
        val reread = refusal.unclear || refusal == ManagedRefusal.NOT_FOUND ||
            // The level or the class is not what the page thought: the status says what it is.
            refusal == ManagedRefusal.NEEDS_FULL || refusal == ManagedRefusal.FORBIDDEN
        if (reread && !read()) return
        fail(failed)
    }

    /** One change: [request] is sent while nothing else is, and what it changed is read back by [then]. */
    private fun <T> change(request: () -> ManagedResult<T>, then: suspend (T) -> Unit) {
        val m = managing ?: return
        if (!m.usable) return
        // A passing problem was read: the next press takes it away.
        ui = m.copy(busy = true, problem = null, problemDetail = "")
        scope.launch {
            when (val result = io(request)) {
                is ManagedResult.Ok -> then(result.value)
                is ManagedResult.Failed -> failedChange(result)
            }
            update { it.copy(busy = false) }
        }
    }

    /**
     * A change of [key] that may come back guarded: the keeper confirms it, the page reads the
     * new value. Without a guard in the answer nothing is provisional — and nothing of [key]
     * that was, is any more: put back by hand, the wait is over (contract §7).
     */
    private fun guarded(key: String, request: () -> ManagedResult<ManagedChanged>, then: suspend () -> Unit) {
        val m = managing ?: return
        if (!m.usable) return
        ui = m.copy(busy = true, problem = null, problemDetail = "")
        scope.launch {
            when (val result = io(request)) {
                is ManagedResult.Ok -> {
                    val guard = result.value.guard
                    if (guard != null) guards.watch(client, guard) else guards.release(client.peerId, key)
                    then()
                }
                is ManagedResult.Failed -> {
                    // Asked, and no word back — but the change is under guard already: if the app
                    // applies it, it is put back unless confirmed. Confirmed as any other.
                    val guard = result.guard
                    if (guard != null) {
                        guards.watch(client, guard)
                        read()
                    } else failedChange(result)
                }
            }
            update { it.copy(busy = false) }
        }
    }

    /** A failure that happened in a window of the page's — the storage browser — and is the page's to say. */
    suspend fun failedOutside(failed: ManagedResult.Failed) = failedChange(failed)

    override fun setCode(text: String) {
        val p = ui as? RemoteManageUi.Pairing ?: return
        ui = p.copy(code = text.filter { it in '0'..'9' }.take(RemoteJson.CODE_LENGTH), error = null)
    }

    override fun pair() {
        val p = ui as? RemoteManageUi.Pairing ?: return
        if (p.busy || !RemoteJson.isCode(p.code)) return
        ui = p.copy(busy = true, error = null)
        scope.launch {
            val answer = io { client.pair(p.code, tv) }
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
            val qr = io { client.parseQr(text) }
            when {
                qr == null -> ui = p.copy(error = RemotePairError(RemotePairFailure.NOT_A_CODE))
                qr.nodeId != client.peerId -> ui = p.copy(error = RemotePairError(RemotePairFailure.OTHER_DEVICE, other = qr.name))
                else -> {
                    ui = p.copy(code = qr.code, error = null)
                    pair()
                }
            }
        }
    }

    override fun resolve(action: ManageProblemAction) {
        when (action) {
            ManageProblemAction.RETRY -> scope.launch { load() }
            ManageProblemAction.PAIR_AGAIN -> ui = pairing()
            ManageProblemAction.DISMISS -> update { it.copy(problem = null, problemDetail = "") }
            ManageProblemAction.CLOSE -> onClose()
        }
    }

    override fun refresh() {
        val m = managing ?: return
        if (m.busy || m.loading || m.reconnecting || m.problem?.final == true || m.guard?.phase?.active == true) return
        scope.launch { load() }
    }

    override fun dismissGuard() = guards.dismiss(client.peerId)

    override fun openExitNodes() {
        val m = managing ?: return
        if (!m.usable) return
        ui = m.copy(picking = true, exitNodes = null, problem = null, problemDetail = "")
        scope.launch {
            when (val nodes = io { client.exitNodes() }) {
                is ManagedResult.Ok -> update { if (it.picking) it.copy(exitNodes = nodes.value.nodes) else it }
                is ManagedResult.Failed -> fail(nodes)
            }
        }
    }

    override fun closeExitNodes() = update { it.copy(picking = false) }

    override fun pickExitNode(id: String) {
        val m = managing ?: return
        val guard = m.access.guardsExitNode
        guarded(ManagedKeys.EXIT_NODE, { client.setExitNode(id, guard) }) {
            update { it.copy(picking = false) }
            // The daemon there takes a moment to route through the new node.
            pause(SETTLE_MS)
            readStatus()
        }
    }

    override fun setSwitch(key: String, on: Boolean) {
        val m = managing ?: return
        val row = m.settings.setting(key, ManagedSetting.BOOL) ?: return
        // What the device would refuse is not sent: the row says why it does not move.
        if (row.lock(m.owner) != ManagedLock.NONE || row.on == on) return
        val guard = m.access.asksGuard(row)
        guarded(key, { client.setSetting(key, on.toString(), guard) }) {
            // Full control switched off takes what it opened with it: the status says what is left.
            if (key == ManagedKeys.FULL_CONTROL || key == ManagedKeys.TUN_MODE || key == ManagedKeys.ADVERTISE_EXIT_NODE) readStatus()
            readSettings()
        }
    }

    override fun setRoutes(text: String): Boolean {
        val m = managing ?: return false
        val list = cidrList(text) ?: return false
        val row = m.settings.setting(ManagedKeys.ADVERTISE_ROUTES, ManagedSetting.CIDRS) ?: return false
        if (!m.access.has(ManagedCaps.ADVERTISE) || !row.writable) return false
        // At the basic level the list can be withdrawn, not written: the device would say needs_full.
        if (row.clearOnly && list.isNotEmpty()) return false
        guarded(ManagedKeys.ADVERTISE_ROUTES, { client.setSetting(ManagedKeys.ADVERTISE_ROUTES, list, false) }) {
            readStatus()
            readSettings()
        }
        return true
    }

    /**
     * The device restarts its connection, and with it the endpoint: it is left alone for a
     * few seconds and then asked until it answers, before silence counts as a problem.
     */
    override fun reconnect() = change({ client.action(ManagedKeys.ACTION_RECONNECT) }) {
        update { it.copy(reconnecting = true, busy = false) }
        pause(RECONNECT_WAIT_MS)
        var last: ManagedResult<ManagedStatus> = io { client.status() }
        var tries = 1
        fun away() = last.failed?.refusal.let { it == ManagedRefusal.UNREACHABLE || it == ManagedRefusal.UNAVAILABLE }
        while (away() && tries < RECONNECT_TRIES && managing?.reconnecting == true) {
            pause(RECONNECT_RETRY_MS)
            last = io { client.status() }
            tries++
        }
        update { it.copy(reconnecting = false) }
        when (val result = last) {
            is ManagedResult.Ok -> update { it.withStatus(result.value) }
            is ManagedResult.Failed -> fail(result)
        }
    }

    override fun act(action: String) {
        val m = managing ?: return
        val known = when (action) {
            ManagedKeys.ACTION_RECEIVE_DEFAULT -> m.access.has(ManagedCaps.REPORT)
            ManagedKeys.ACTION_RELAYS -> m.access.has(ManagedCaps.RELAYS)
            ManagedKeys.ACTION_SAVE_RECEIVED -> m.access.has(ManagedCaps.SAVE)
            else -> false
        }
        if (!known || !m.access.gate(ManagedOp.ACTION).open || !m.usable) return
        ui = m.copy(busy = true, problem = null, problemDetail = "")
        scope.launch {
            val result = io { client.action(action) }
            when {
                result is ManagedResult.Ok -> {
                    readStatus()
                    if (action == ManagedKeys.ACTION_SAVE_RECEIVED) readFiles()
                }
                // Dropping the relay connections may take the answer to this very request with
                // them: the device is asked again in a moment before silence means anything.
                action == ManagedKeys.ACTION_RELAYS && result.failed?.refusal?.away == true -> {
                    pause(RELAYS_WAIT_MS)
                    readStatus()
                }
                else -> failedChange(result as ManagedResult.Failed)
            }
            update { it.copy(busy = false) }
        }
    }

    override fun identify() {
        val m = managing ?: return
        if (!m.usable || m.identifying || !m.access.gate(ManagedOp.IDENTIFY).open) return
        ui = m.copy(identifying = true, identified = null, problem = null, problemDetail = "")
        scope.launch {
            when (val answer = io { client.identify() }) {
                is ManagedResult.Ok -> update { it.copy(identifying = false, identified = ManagedShown.of(answer.value.result) ?: ManagedShown.WAITING) }
                is ManagedResult.Failed -> failedChange(answer)
            }
        }
    }

    override fun askPermission(id: String) {
        val m = managing ?: return
        if (!m.access.gate(ManagedOp.PERMISSION_REQUEST).open) return
        // The button is drawn from the device's own flag, and so is the request.
        if (m.app?.permissions?.firstOrNull { it.id == id }?.ask != true) return
        change({ client.askPermission(id) }) { answer ->
            val shown = ManagedShown.of(answer.result) ?: ManagedShown.WAITING
            // It grants nothing, and no answer means "granted": the status is watched for that.
            update { it.copy(asked = it.asked + (id to shown), watchUntil = maxOf(it.watchUntil, now() + WATCH_SHOWN_MS)) }
            readStatus()
        }
    }

    override fun deleteFile(name: String) {
        if (managing?.access?.gate(ManagedOp.FILES_DELETE)?.open != true) return
        change({ client.deleteFile(name) }) { readFiles() }
    }

    override fun fetch(path: String) {
        if (managing?.access?.gate(ManagedOp.STORAGE_SEND)?.open != true) return
        change({ client.storageSend(path) }) { readStatus() }
    }

    override fun addShare(name: String, path: String) {
        if (managing?.access?.gate(ManagedOp.SHARES_ADD)?.open != true || !isShareName(name)) return
        change({ client.addShare(name, path) }) { readStatus() }
    }

    override fun removeShare(name: String) {
        if (managing?.access?.gate(ManagedOp.SHARES_REMOVE)?.open != true) return
        change({ client.removeShare(name) }) { readStatus() }
    }

    override fun openDiag(log: Boolean) {
        val m = managing ?: return
        if (!m.usable || !m.access.gate(if (log) ManagedOp.DIAG_LOG else ManagedOp.DIAG_REPORT).open) return
        ui = m.copy(diag = RemoteDiagState(log = log), problem = null, problemDetail = "")
        scope.launch {
            val read: ManagedResult<RemoteDiagState> = io {
                if (log) when (val answer = client.log()) {
                    is ManagedResult.Ok -> ManagedResult.Ok(RemoteDiagState(true, false, answer.value.lines.joinToString("\n"), answer.value.scrubbed))
                    is ManagedResult.Failed -> answer
                } else when (val answer = client.report()) {
                    is ManagedResult.Ok -> ManagedResult.Ok(RemoteDiagState(false, false, answer.value.text.trimEnd()))
                    is ManagedResult.Failed -> answer
                }
            }
            when (read) {
                // Closed while it was on its way: nothing to show it in.
                is ManagedResult.Ok -> update { if (it.diag?.log == log) it.copy(diag = read.value) else it }
                is ManagedResult.Failed -> failedChange(read)
            }
        }
    }

    override fun closeDiag() = update { it.copy(diag = null) }

    /** Why this device's own daemon will not send the peer a file; null when it will. */
    fun sendVerdict(blocked: String?) = update { it.copy(sendBlocked = blocked) }

    /** A file is on its way from this device, or the send is over. */
    fun sending(state: RemoteSendState?) = update { it.copy(send = state) }

    /** The send is over: the file shows up in one of the device's two lists within a few seconds. */
    fun sent(state: RemoteSendState) {
        update { it.copy(send = state, watchUntil = maxOf(it.watchUntil, now() + WATCH_SENT_MS)) }
        if (state.sent.isNotEmpty()) scope.launch { tick() }
    }

    override fun forget() {
        val m = managing ?: return
        ui = m.copy(busy = true)
        scope.launch {
            when (io { client.unpair() }) {
                is ManagedResult.Ok -> onClose()
                // The token is gone from here either way; the other side was not told.
                is ManagedResult.Failed -> ui = RemoteManageUi.Forgotten(m.name, m.tv)
            }
        }
    }

    override fun close() = onClose()
}
