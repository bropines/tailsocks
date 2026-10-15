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
import io.github.bropines.tailscaled.core.ManagedElevated
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
import io.github.bropines.tailscaled.core.ManagedSettings
import io.github.bropines.tailscaled.core.ManagedShown
import io.github.bropines.tailscaled.core.ManagedStatus
import io.github.bropines.tailscaled.core.RemoteClient
import io.github.bropines.tailscaled.core.RemoteElevateLengths
import io.github.bropines.tailscaled.core.RemoteFile
import io.github.bropines.tailscaled.core.RemoteFiles
import io.github.bropines.tailscaled.core.RemoteJson
import io.github.bropines.tailscaled.core.RemotePairFailure
import io.github.bropines.tailscaled.core.RemotePairingRecord
import io.github.bropines.tailscaled.core.RemoteProbe
import io.github.bropines.tailscaled.core.cidrList
import io.github.bropines.tailscaled.core.clearOnly
import io.github.bropines.tailscaled.core.failure
import io.github.bropines.tailscaled.core.isShareName
import io.github.bropines.tailscaled.core.lock
import io.github.bropines.tailscaled.core.setting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/*
 * What the managing page (ui/RemoteManagePage.kt) shows and everything that changes it. No
 * Android in here: the device is a RemoteClient, time and waiting are handed in, so the whole
 * of it runs in a plain test against the contract's fake device.
 *
 * Four rules the page keeps, and this file is where they are kept:
 *   - a device is offered only what it announces (ManagedAccess), and is never sent a field
 *     it did not announce;
 *   - nothing is claimed that the device did not say. After an answer that leaves the outcome
 *     open the device is read again, and the page shows what it says now;
 *   - a change that could cut the channel is confirmed without asking the person anything
 *     (GuardKeeper), and when it cannot be, the page says what the device will do by itself;
 *   - the page is there before the device answers. What this device knows by itself — its own
 *     network map (ManagePeer), the stored pairing, what the device said last (ManageMemory) —
 *     is shown at once and decides what is asked at all: a pairing code is asked for only of
 *     a device that has just said it takes one.
 */

// ─── What the page draws ─────────────────────────────────────────────────────────────────────

/** The one thing offered next to a problem. */
enum class ManageProblemAction {
    RETRY,
    PAIR_AGAIN,
    DISMISS,
    CLOSE,
    /** Starts this device's own TailSocks: nothing can be asked without it. */
    START,
    /** Forgets the pairing: all that is left to do with a device that left the tailnet. */
    FORGET,
}

/**
 * What this device's own daemon says of the peer: its network map, with nobody asked over the
 * network. It is there the moment the page opens, and it comes before any request — a device
 * the map does not hold, or holds as offline, is not asked whether it is there.
 */
@Immutable
data class ManagePeer(
    /** [RemotePresence.UNKNOWN]: this device's own TailSocks is not connected, so it cannot say. */
    val presence: RemotePresence = RemotePresence.UNKNOWN,
    /** This device's clock, milliseconds, of when the tailnet last saw the peer; 0 when it does not say. */
    val lastSeen: Long = 0,
    /** The peer's tailnet address. */
    val address: String = "",
    /** Not connected because this device's TailSocks is stopped — it can be started from the page. */
    val stopped: Boolean = false,
)

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
    /** The device's files were needed and are closed there. */
    FILES_CLOSED,
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
         * What is in the way before anything is sent, by this device's own network map; null
         * when the device may be asked. One the map holds as offline is not asked either —
         * unless a person [asked] for it: the map can be a moment behind.
         */
        fun local(peer: ManagePeer, asked: Boolean = false): ManageProblem? = when (peer.presence) {
            RemotePresence.UNKNOWN -> NOT_CONNECTED
            RemotePresence.GONE -> NO_PEER
            RemotePresence.OFFLINE -> if (asked) null else OFFLINE
            RemotePresence.ONLINE -> null
        }

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
            ManagedRefusal.FILES_CLOSED -> FILES_CLOSED
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

/**
 * Why the code did not pair, with what the sentence needs. It stands under the code's field
 * and is only ever about the code and the pairing; a device that cannot be reached is a
 * [ManageProblem], which takes the form away.
 */
@Immutable
data class RemotePairError(
    val failure: RemotePairFailure,
    val attemptsLeft: Int? = null,
    /** The name in a code that belongs to another device. */
    val other: String = "",
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

/**
 * What a device said when it was last heard, kept so that its page has something to show
 * before it answers again — and while it does not.
 */
@Immutable
internal data class ManageSnapshot(
    val status: ManagedStatus,
    val app: ManagedApp?,
    val settings: List<ManagedSetting>,
    val files: List<RemoteFile>?,
    /** This device's clock, milliseconds, of when the status was heard. */
    val at: Long,
)

/**
 * The last thing each managed device said, by [RemoteClient.key]. In memory and nowhere else:
 * it lives as long as the app's process, and a device that is forgotten, or that refuses the
 * pairing, is dropped from it at once.
 */
internal class ManageMemory {
    private val last = HashMap<String, ManageSnapshot>()

    @Synchronized operator fun get(key: String): ManageSnapshot? = last[key]
    @Synchronized fun keep(key: String, snapshot: ManageSnapshot) { last[key] = snapshot }
    @Synchronized fun forget(key: String) { last.remove(key) }
}

/** The memory of the app: whatever page opens next finds what the last one heard. */
internal object RemoteMemory {
    val memory = ManageMemory()
}

sealed interface RemoteManageUi {
    val name: String
    val tv: Boolean

    /** What this device's own network map says of the peer; null until it was looked at. */
    val peer: ManagePeer? get() = null

    /** The device is being asked for what the page shows: a calm line under the bar, and the page stays the page. */
    val asking: Boolean get() = false

    /** This device's own TailSocks is being started from the page. */
    val starting: Boolean get() = false

    /**
     * Just opened: whether this device holds a pairing for the peer is being read off its own
     * store. A moment, and nothing is claimed in it — neither a pairing nor the lack of one.
     */
    @Immutable
    data class Opening(override val name: String, override val tv: Boolean = false) : RemoteManageUi {
        override val asking: Boolean get() = true
    }

    /**
     * Not paired. The six digits are asked for only of a device that has just said it takes
     * a pairing: while it is being asked ([checking]) there is no form, and when it cannot
     * be reached ([problem]) the page says why instead.
     */
    @Immutable
    data class Pairing(
        override val name: String,
        override val tv: Boolean = false,
        val code: String = "",
        val busy: Boolean = false,
        /** The device said a code is on show; false when it said none is. */
        val codeOnShow: Boolean = true,
        val error: RemotePairError? = null,
        override val peer: ManagePeer? = null,
        /** The device is being asked whether it takes a pairing at all. */
        val checking: Boolean = false,
        /** Why no code is asked for: this device is not connected, or that one cannot be reached. */
        val problem: ManageProblem? = null,
        override val starting: Boolean = false,
        /** This device's clock, milliseconds: what "last seen" is measured against. */
        val now: Long = 0,
    ) : RemoteManageUi {
        override val asking: Boolean get() = checking

        /** The code's field is offered. */
        val form: Boolean get() = !checking && problem == null

        /** Nothing here to forget: a device that is not in the tailnet leaves only the way out. */
        val problemAction: ManageProblemAction?
            get() = when (problem) {
                null -> null
                ManageProblem.NO_PEER -> ManageProblemAction.CLOSE
                ManageProblem.NOT_CONNECTED -> if (peer?.stopped == true) ManageProblemAction.START else ManageProblemAction.RETRY
                else -> ManageProblemAction.RETRY
            }
    }

    /** Paired: the device as it last answered. */
    @Immutable
    data class Managing(
        override val name: String,
        override val tv: Boolean = false,
        /** From the stored pairing until the device answers, then what it says. */
        val owner: Boolean = false,
        /** Seconds the pairing has left; -1 for until revoked. */
        val left: Long = -1,
        /** What the device said last: now, or to an earlier page ([remembered]). */
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
        /** How the last request for full rights ended; they come, if they come, in the status. */
        val rightsAsked: ManagedShown? = null,
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
        override val peer: ManagePeer? = null,
        /** This device's clock, milliseconds, of when [status] was heard; 0 when that is not known. */
        val heardAt: Long = 0,
        /** [status] and the rest are what the device said to an earlier page: shown until it answers this one. */
        val remembered: Boolean = false,
        /** This device's clock, milliseconds, of when it was paired; 0 when the pairing does not say. */
        val pairedAt: Long = 0,
        override val starting: Boolean = false,
    ) : RemoteManageUi {
        override val asking: Boolean get() = loading

        val access: ManagedAccess get() = status?.let(::ManagedAccess) ?: ManagedAccess.NONE

        /** The controls answer: the device can be asked, it is its own word the page shows, and nothing is in flight. */
        val usable: Boolean
            get() = status != null && !remembered && !busy && !loading && !reconnecting &&
                problem?.stale != true && problem?.final != true && guard?.phase?.active != true

        /** What the page shows of the device is not news: it said so a while ago, and cannot be asked or has not answered yet. */
        val old: Boolean get() = status != null && (remembered || problem?.stale == true)

        /**
         * The one thing to do about [problem]. A device that left the tailnet can only be
         * forgotten; this device's own TailSocks, when it is stopped, can be started from here.
         */
        val problemAction: ManageProblemAction?
            get() = when (problem) {
                null -> null
                ManageProblem.NO_PEER -> ManageProblemAction.FORGET
                ManageProblem.NOT_CONNECTED -> if (peer?.stopped == true) ManageProblemAction.START else ManageProblemAction.RETRY
                // What an earlier page heard is not this one's reading: its first problem is retried, not waved away.
                else -> problem.action(loaded = status != null && !remembered)
            }

        /** Something is expected to change there soon: the status is read every few seconds. */
        fun watching(now: Long): Boolean =
            app?.asked != null || now < watchUntil || status?.fetch?.state == ManagedFetch.SENDING || guard?.phase == GuardPhase.REVERTED_AWAY

        /** The exit node as the device has it now. */
        val exitNode: ManagedExitNode? get() = status?.exitNode

        /** This device's full rights for a while, while the device says they last. */
        val elevated: ManagedElevated? get() = status?.you?.elevated?.takeIf { access.has(ManagedCaps.ELEVATE) }

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
    /** A guest asks the person at the device for full rights for [seconds] (0: until the pairing ends). */
    fun askFullRights(seconds: Long) {}
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
/** How long "applied" is still worth telling a page that opens. */
private const val APPLIED_NEWS_MS = 10_000L

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
    private val settledAt = HashMap<String, Long>()
    private val listeners = HashMap<String, GuardListener>()

    fun state(peerId: String): GuardState? = states[peerId]

    /**
     * A page for [peerId] is open: it hears of the change from now on, and is told where it
     * stands. "Applied" is news for a moment only: a page opened later is not told it again.
     * A change that was put back is told whenever the page is next opened — nobody saw it.
     */
    fun attach(peerId: String, listener: GuardListener) {
        listeners[peerId] = listener
        val state = states[peerId]
        if (state?.phase == GuardPhase.APPLIED && now() - (settledAt[peerId] ?: 0L) > APPLIED_NEWS_MS) states.remove(peerId)
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
        settledAt[peer] = now()
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
/** After asking for full rights: how long the status is read more often for a person there to give them. */
internal const val WATCH_RIGHTS_MS = 120_000L
/** After this device's own TailSocks was started from the page: how often it is looked at, and how many times. */
private const val START_EVERY_MS = 1_000L
private const val START_TRIES = 45

/**
 * The page's state and everything that changes it. Requests run on [scope], off the main
 * thread ([io]); what they bring back is one new [ui]. [pause] and [now] are the waiting and
 * the clock, so a test can run a minute of a guarded change in no time.
 *
 * [local] is this device's own daemon on the peer — blocking, and nothing leaves the device
 * for it. [present]: whoever opened the page has reason to take the device for there — its own
 * list said so, or a code was just scanned off its screen — so the page does not call it
 * offline on the network map's word before it has asked. [memory] keeps what each device said
 * last. [startOwn] starts this device's own TailSocks and says whether it could be asked to;
 * null where the page cannot.
 */
@Stable
internal class RemoteManageController(
    private val client: RemoteClient,
    private val scope: CoroutineScope,
    private val guards: GuardKeeper,
    private val local: () -> ManagePeer,
    private val onClose: () -> Unit,
    private val name: String,
    private val tv: Boolean = false,
    private val codeOnShow: Boolean = true,
    code: String = "",
    private val appVersion: String = "",
    private val present: Boolean = false,
    private val memory: ManageMemory = ManageMemory(),
    private val startOwn: (() -> Boolean)? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) : RemoteManageActions, GuardListener {

    var ui by mutableStateOf<RemoteManageUi>(RemoteManageUi.Opening(name, tv))
        private set

    private val scanned = code.filter { it in '0'..'9' }.take(RemoteJson.CODE_LENGTH)

    /** Counts the readings of the device: an answer that arrives for an earlier one is not this one's to show. */
    private var reading = 0

    /** Everything the page shows was read from the device by this page: only then is it worth keeping. */
    private var whole = false

    private val managing: RemoteManageUi.Managing? get() = ui as? RemoteManageUi.Managing
    private val pairing: RemoteManageUi.Pairing? get() = ui as? RemoteManageUi.Pairing

    /** The page of a device this one holds no pairing for: nothing is offered until the device was asked. */
    private fun unpaired(code: String = "") =
        RemoteManageUi.Pairing(name, tv, code = code, codeOnShow = codeOnShow, peer = ui.peer, checking = true)

    private suspend fun <T> io(block: () -> T): T = withContext(io) { block() }

    private inline fun update(change: (RemoteManageUi.Managing) -> RemoteManageUi.Managing) {
        managing?.let { ui = change(it) }
    }

    private inline fun updatePairing(change: (RemoteManageUi.Pairing) -> RemoteManageUi.Pairing) {
        pairing?.let { ui = change(it) }
    }

    /** What this device's own daemon says of the peer now — [seen], when the page's owner has just looked. */
    private suspend fun look(seen: ManagePeer? = null): ManagePeer {
        val peer = seen ?: io(local)
        update { it.copy(peer = peer, now = now()) }
        updatePairing { it.copy(peer = peer, now = now()) }
        return peer
    }

    private val online: Boolean get() = ui.peer?.presence == RemotePresence.ONLINE

    /**
     * Paired already, or the device is asked whether it can be paired with.
     *
     * What this device knows by itself — the stored pairing, its own network map — is read
     * side by side, before the page shows anything of the device. The first questions then
     * leave in the same breath as the page appears: drawing a whole remembered device takes a
     * slow television long enough to notice, and must not be what the device waits for.
     */
    suspend fun start() {
        val (record, peer) = coroutineScope {
            val record = async { io { client.pairing() } }
            val peer = async { io(local) }
            record.await() to peer.await()
        }
        whole = false
        if (record == null) {
            ui = unpaired(scanned).copy(peer = peer, now = now())
            check(seen = peer, asked = present)
            return
        }
        ui = paired(record).copy(peer = peer)
        guards.attach(client.peerId, this)
        if (open(seen = peer, asked = present)) resumeGuard()
    }

    /**
     * The page of a device this one is paired with, before the device is asked anything: who
     * this device is to it by the stored pairing, and what it said last, if this app heard it.
     */
    private fun paired(record: RemotePairingRecord): RemoteManageUi.Managing {
        val kept = memory[client.key]
        val time = now()
        return RemoteManageUi.Managing(
            // The name stays the one this device knows the peer by: it is what was pressed.
            name = name.ifEmpty { record.name },
            tv = tv || record.tv,
            owner = record.owner,
            left = if (!record.owner && record.expiresAt > 0) ((record.expiresAt - time) / 1000).coerceAtLeast(0) else -1,
            status = kept?.status,
            app = kept?.app,
            settings = kept?.settings.orEmpty(),
            files = kept?.files,
            loading = true,
            now = time,
            appVersion = appVersion,
            peer = ui.peer,
            heardAt = kept?.at ?: 0,
            remembered = kept != null,
            pairedAt = record.pairedAt,
        )
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

    /**
     * The periodic look at the device; quiet unless it went away or came back. [seen] is what
     * the page's owner has just read off this device's own daemon.
     */
    suspend fun tick(seen: ManagePeer? = null) {
        if (pairing != null) return recheck(seen)
        val before = managing ?: return
        if (before.busy || before.loading || before.reconnecting || before.starting) return
        if (before.problem == ManageProblem.REVOKED || before.problem == ManageProblem.EXPIRED) return
        val peer = look(seen)
        val m = managing ?: return
        // While a change is being confirmed the keeper does the talking.
        if (m.status != null && (m.guard?.phase == GuardPhase.CHECKING || m.guard?.phase == GuardPhase.SILENT)) return
        val away = m.problem?.stale == true || m.problem == ManageProblem.NO_PEER
        if (away) return knock(peer)
        // Never read by this page, under a problem no waiting mends: it stays until a person retries.
        if (m.status == null || m.remembered) return
        // This device went down, or the peer left the tailnet: said at once, with nothing to send.
        ManageProblem.local(peer, asked = true)?.let { said(it); return }
        when (val status = io { client.status() }) {
            is ManagedResult.Ok -> {
                heard(status.value)
                guards.heardAgain(client.peerId)
                // What it undid while it was away makes everything it showed old.
                if (m.guard?.phase == GuardPhase.REVERTED_AWAY) load()
                else if (rightsMoved(m.status, status.value)) readSettings()
                else if (now() < m.watchUntil) readFiles()
            }
            // A poll nobody asked for raises no banner over a passing refusal.
            is ManagedResult.Failed -> if (status.refusal.away || status.refusal.final) fail(status)
        }
    }

    /**
     * A device that cannot be asked is looked for again at the page's own pace: by this
     * device's network map first, then with the small question, which costs four seconds at
     * most where the status would cost twelve. Back, everything it showed is old, and is read.
     */
    private suspend fun knock(peer: ManagePeer) {
        val run = reading
        val inTheWay = ManageProblem.local(peer, asked = true)
        if (inTheWay != null) {
            if (inTheWay != managing?.problem) said(inTheWay)
            return
        }
        val reach = io { client.reach() }
        if (run != reading || managing?.busy != false) return
        when (reach) {
            is ManagedResult.Ok -> load()
            is ManagedResult.Failed -> ManageProblem.of(reach.refusal, online).let { if (it != managing?.problem) said(it) }
        }
    }

    /**
     * Full rights for a while given or ended there — or the level moved, or the files were
     * opened or closed: what the settings' rows let this device do is another class's now, and
     * they are read again.
     */
    private fun rightsMoved(before: ManagedStatus?, now: ManagedStatus): Boolean =
        before != null && (before.you.role != now.you.role || before.level != now.level || before.files != now.files)

    private fun RemoteManageUi.Managing.withStatus(status: ManagedStatus): RemoteManageUi.Managing {
        val report = status.app ?: app
        val granted = report?.permissions.orEmpty().filter { it.state == ManagedPermission.GRANTED }.map { it.id }.toSet()
        val time = now()
        return copy(
            status = status,
            app = report,
            name = name.ifEmpty { status.name },
            tv = tv || status.tv,
            owner = status.you.owner,
            left = status.you.left,
            asked = asked - granted,
            // Given: the request is answered.
            rightsAsked = rightsAsked.takeIf { status.you.elevated == null },
            // The log or the report on show goes when the device stops letting this one read it:
            // the files closed there, or full control went off.
            diag = diag?.takeIf { d -> ManagedAccess(status).gate(if (d.log) ManagedOp.DIAG_LOG else ManagedOp.DIAG_REPORT).open },
            now = time,
            heardAt = time,
            remembered = false,
        )
    }

    /**
     * The device's own word, just now: the page shows it as news, and it is kept for the page
     * that opens next. A device that answers is not away, whatever the page said of it before.
     */
    private fun heard(status: ManagedStatus) {
        update { it.withStatus(status).let { m -> if (m.problem?.stale == true) m.copy(problem = null, problemDetail = "") else m } }
        keep()
    }

    /** What the page shows goes into the memory — once all of it is this page's own reading. */
    private fun keep() {
        val m = managing ?: return
        val status = m.status ?: return
        if (whole && !m.remembered) memory.keep(client.key, ManageSnapshot(status, m.app, m.settings, m.files, m.heardAt))
    }

    /**
     * Reads the device the page shows, from the start. What this device knows by itself comes
     * first and may be all there is: with its own TailSocks down or the peer gone from the
     * tailnet, nothing is sent and the page says so at once. A peer the network map holds as
     * offline is said to be offline at once too — and asked the small question all the same,
     * unless a person [asked] already: the map can be a moment behind.
     *
     * A device that may answer is asked for its status beside that small question, which
     * gives up three times sooner (RemoteClient.reach): one that does not answer is known in
     * four seconds, not twelve.
     *
     * A device that could not be reached stays said so while it is asked again: the sentence
     * and its button wait with the page, and go when the device answers ([heard]).
     */
    private suspend fun open(seen: ManagePeer? = null, asked: Boolean = false): Boolean {
        val run = ++reading
        update { it.copy(loading = true, problem = it.problem?.takeIf { p -> p.stale }, problemDetail = "", starting = false) }
        val peer = look(seen)
        val inTheWay = ManageProblem.local(peer, asked)
        if (inTheWay == ManageProblem.OFFLINE) {
            update { it.copy(problem = inTheWay) }
            val reach = io { client.reach() }
            if (run != reading) return false
            if (reach is ManagedResult.Failed) return fail(reach)
            update { it.copy(problem = null) }
            return fill(run, ask())
        }
        if (inTheWay != null) return said(inTheWay)
        // This device is connected, whatever was said before it was looked at: what is left to doubt is the peer.
        update { if (it.problem == ManageProblem.NOT_CONNECTED) it.copy(problem = null) else it }

        val reach = scope.async(io) { client.reach() }
        val answers = ask()
        val silent = select<ManagedResult.Failed?> {
            // The status has its own say, whatever the small question brought.
            answers.status.onAwait { null }
            reach.onAwait { it.failed }
        }
        if (run != reading) return false
        if (silent != null && !answers.status.isCompleted) {
            fail(silent)
            scope.launch { late(run, answers) }
            return false
        }
        return fill(run, answers)
    }

    /** [open] for a person who asked: the device is asked whatever this device's network map says of it. */
    private suspend fun load(): Boolean = open(asked = true)

    /** Everything the page shows, read again without touching the banner: for reading back after something happened. */
    private suspend fun read(): Boolean = fill(reading, ask())

    /** The three answers a page is made of, on their way. The last two are null where the status brought none to follow. */
    private class Answers(
        val status: Deferred<ManagedResult<ManagedStatus>>,
        val settings: Deferred<ManagedResult<ManagedSettings>?>,
        val files: Deferred<ManagedResult<RemoteFiles>?>,
    )

    /**
     * Asks for everything the page shows: the status, and — the moment it is in, side by side
     * — the settings and the files. All of it off the main thread, one question handing over
     * to the next there: a page busy drawing must not be what the device waits for. Nothing
     * follows a status that did not come.
     */
    private fun ask(): Answers {
        val status = scope.async(io) { client.status() }
        val settings = scope.async(io) { if (status.await() is ManagedResult.Ok) client.settings() else null }
        val files = scope.async(io) { if (status.await() is ManagedResult.Ok) client.files() else null }
        return Answers(status, settings, files)
    }

    /** The answers are shown as they come, each in its part of the page; the first that brought none is said. */
    private suspend fun fill(run: Int, answers: Answers): Boolean {
        val status = answers.status.await()
        if (run != reading) return false
        if (status is ManagedResult.Failed) return fail(status)
        heard((status as ManagedResult.Ok).value)
        // It answers: a change it undid in silence can be said undone now.
        guards.heardAgain(client.peerId)
        val settings = answers.settings.await()
        if (run != reading) return false
        if (settings is ManagedResult.Ok) update { it.copy(settings = settings.value.settings) }
        val files = answers.files.await()
        if (run != reading) return false
        if (files is ManagedResult.Ok) update { it.copy(files = files.value.files) }
        (settings?.failed ?: files?.failed)?.let { return fail(it) }
        whole = true
        update { it.copy(loading = false) }
        keep()
        return true
    }

    /**
     * The status of a device that did not answer the small question in its four seconds, and
     * answers after all: the page takes its word — the status, or a refusal that is one.
     */
    private suspend fun late(run: Int, answers: Answers) {
        val status = answers.status.await()
        val m = managing ?: return
        if (run != reading || m.busy || m.loading) return
        when (status) {
            is ManagedResult.Ok -> {
                update { it.copy(loading = true, problem = null, problemDetail = "") }
                if (fill(run, answers)) resumeGuard()
            }
            is ManagedResult.Failed -> if (!status.refusal.away) fail(status)
        }
    }

    private suspend fun readStatus() {
        when (val status = io { client.status() }) {
            is ManagedResult.Ok -> heard(status.value)
            is ManagedResult.Failed -> fail(status)
        }
    }

    private suspend fun readSettings() {
        when (val settings = io { client.settings() }) {
            is ManagedResult.Ok -> {
                update { it.copy(settings = settings.value.settings) }
                keep()
            }
            is ManagedResult.Failed -> fail(settings)
        }
    }

    private suspend fun readFiles() {
        when (val files = io { client.files() }) {
            is ManagedResult.Ok -> {
                update { it.copy(files = files.value.files) }
                keep()
            }
            is ManagedResult.Failed -> fail(files)
        }
    }

    private fun fail(failed: ManagedResult.Failed): Boolean {
        val problem = ManageProblem.of(failed.refusal, online)
        // The pairing is over there: what the device said under it is not this device's to show any more.
        if (problem == ManageProblem.REVOKED || problem == ManageProblem.EXPIRED) memory.forget(client.key)
        return said(problem, failed.message)
    }

    /** The page says [problem], and whatever was in flight is over. */
    private fun said(problem: ManageProblem, detail: String = ""): Boolean {
        update {
            it.copy(
                problem = problem,
                problemDetail = detail,
                loading = false,
                busy = false,
                picking = false,
                reconnecting = false,
                identifying = false,
                starting = false,
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
        // The files were closed there meanwhile — switched off at the device, maybe while a
        // window of them was open here. Not an error: the device is read again, and its page
        // shows them closed where they were.
        if (refusal == ManagedRefusal.FILES_CLOSED) {
            update { it.copy(diag = null) }
            read()
            return
        }
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

    // ─── Pairing ─────────────────────────────────────────────────────────────────────────────

    /**
     * Before the digits are asked for: whether the device can be paired with at all. This
     * device's own network map answers first and may be all it takes; then the small question
     * (RemoteClient.reach), whose answer also says whether a code is on show there. Like
     * [open], a peer held as offline is said to be so at once and asked all the same, and what
     * was in the way stays said while the device is asked again.
     */
    private suspend fun check(seen: ManagePeer? = null, asked: Boolean = false) {
        val run = ++reading
        updatePairing { it.copy(checking = true, starting = false) }
        val peer = look(seen)
        val inTheWay = ManageProblem.local(peer, asked)
        if (inTheWay != null && inTheWay != ManageProblem.OFFLINE) {
            updatePairing { it.copy(checking = false, problem = inTheWay) }
            return
        }
        // Connected, and the tailnet holds the peer, whatever was said before this device was
        // looked at: what is left to doubt is the peer's answer.
        updatePairing { it.copy(problem = inTheWay ?: it.problem?.takeIf { p -> p == ManageProblem.OFFLINE || p == ManageProblem.NOT_ACCEPTING }) }
        val reach = io { client.reach() }
        if (run == reading) updatePairing { it.answered(reach) }
    }

    /** The form, for a device that says it takes a pairing; otherwise why there is none. */
    private fun RemoteManageUi.Pairing.answered(reach: ManagedResult<RemoteProbe>): RemoteManageUi.Pairing = when (reach) {
        is ManagedResult.Ok -> copy(checking = false, problem = null, codeOnShow = reach.value.pairing, tv = tv || reach.value.tv)
        is ManagedResult.Failed -> copy(checking = false, problem = ManageProblem.of(reach.refusal, peer?.presence == RemotePresence.ONLINE))
    }

    /** The pairing page's own periodic look: a device that could not be reached is asked again, and the form comes when it answers. */
    private suspend fun recheck(seen: ManagePeer?) {
        val before = pairing ?: return
        if (before.busy || before.checking || before.starting) return
        val peer = look(seen)
        if (before.problem == null) return
        val run = reading
        val inTheWay = ManageProblem.local(peer, asked = true)
        if (inTheWay != null) {
            updatePairing { if (it.problem == inTheWay) it else it.copy(problem = inTheWay) }
            return
        }
        val reach = io { client.reach() }
        if (run == reading) updatePairing { if (it.busy || it.checking) it else it.answered(reach) }
    }

    override fun setCode(text: String) =
        updatePairing { it.copy(code = text.filter { c -> c in '0'..'9' }.take(RemoteJson.CODE_LENGTH), error = null) }

    override fun pair() {
        val p = pairing ?: return
        if (p.busy || !p.form || !RemoteJson.isCode(p.code)) return
        ui = p.copy(busy = true, error = null)
        scope.launch {
            val answer = io { client.pair(p.code, p.tv) }
            if (answer.ok) {
                start()
                return@launch
            }
            val failure = answer.failure()
            val unreached = when (failure) {
                RemotePairFailure.NOT_CONNECTED -> ManagedRefusal.NOT_CONNECTED
                RemotePairFailure.NO_PEER -> ManagedRefusal.NO_PEER
                RemotePairFailure.UNREACHABLE -> ManagedRefusal.UNREACHABLE
                else -> null
            }
            if (unreached == null) {
                updatePairing { it.copy(busy = false, error = RemotePairError(failure, answer.attemptsLeft)) }
                return@launch
            }
            // Not about the code: the form goes, and the page says what is in the way.
            val peer = look()
            val problem = ManageProblem.local(peer, asked = true) ?: ManageProblem.of(unreached, peer.presence == RemotePresence.ONLINE)
            updatePairing { it.copy(busy = false, error = null, problem = problem) }
        }
    }

    /** A code read by the scanner: this device's own is typed in and sent, anything else is said. */
    fun scanned(text: String) {
        if (pairing == null) return
        scope.launch {
            val qr = io { client.parseQr(text) }
            when {
                qr == null -> updatePairing { it.copy(error = RemotePairError(RemotePairFailure.NOT_A_CODE)) }
                qr.nodeId != client.peerId -> updatePairing { it.copy(error = RemotePairError(RemotePairFailure.OTHER_DEVICE, other = qr.name)) }
                else -> {
                    updatePairing { it.copy(code = qr.code, error = null) }
                    pair()
                }
            }
        }
    }

    override fun resolve(action: ManageProblemAction) {
        when (action) {
            ManageProblemAction.RETRY -> retry()
            ManageProblemAction.PAIR_AGAIN -> {
                // The pairing is gone there. Whether a new one can be made is asked first, like any other.
                ui = unpaired()
                scope.launch { check(asked = true) }
            }
            ManageProblemAction.DISMISS -> update { it.copy(problem = null, problemDetail = "") }
            ManageProblemAction.CLOSE -> onClose()
            ManageProblemAction.START -> startOwnTailSocks()
            ManageProblemAction.FORGET -> forget()
        }
    }

    /** Asks again, whatever this device's network map says of the peer: a person wants to know. */
    private fun retry() {
        when (val now = ui) {
            is RemoteManageUi.Managing -> if (!now.loading && !now.busy && !now.starting) scope.launch { load() }
            is RemoteManageUi.Pairing -> if (!now.checking && !now.busy && !now.starting) scope.launch { check(asked = true) }
            is RemoteManageUi.Opening, is RemoteManageUi.Forgotten -> Unit
        }
    }

    /**
     * Starts this device's own TailSocks and looks until it is connected — a start takes a few
     * seconds — then reads the device as a page that has just opened would. Up or not, the page
     * says what it finds.
     */
    private fun startOwnTailSocks() {
        val start = startOwn ?: return
        if (ui.starting || !start()) return
        update { it.copy(starting = true) }
        updatePairing { it.copy(starting = true) }
        scope.launch {
            var peer = ManagePeer()
            for (attempt in 0 until START_TRIES) {
                pause(START_EVERY_MS)
                // Closed, or a look of the page's own has already found it up.
                if (!ui.starting) return@launch
                peer = io(local)
                if (peer.presence != RemotePresence.UNKNOWN) break
            }
            if (managing != null) {
                if (open(peer)) resumeGuard()
            } else {
                check(peer)
            }
        }
    }

    override fun refresh() {
        val m = managing ?: return
        if (m.busy || m.loading || m.reconnecting || m.starting || m.problem?.final == true || m.guard?.phase?.active == true) return
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
            is ManagedResult.Ok -> heard(result.value)
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

    override fun askFullRights(seconds: Long) {
        val m = managing ?: return
        if (m.owner || m.elevated != null || !m.access.gate(ManagedOp.ELEVATE_REQUEST).open || RemoteElevateLengths.of(seconds) == null) return
        change({ client.askFullRights(seconds) }) { answer ->
            val shown = ManagedShown.of(answer.result) ?: ManagedShown.WAITING
            // It gives nothing: the status is watched for the rights a person there may give.
            update { it.copy(rightsAsked = shown, watchUntil = maxOf(it.watchUntil, now() + WATCH_RIGHTS_MS)) }
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

    /**
     * Forgetting is this device's to decide, and a local thing to do: a device the page already
     * knows to be out of reach is forgotten at once, and told afterwards in case it can be
     * after all, with nobody waiting for its answer. One that may answer is asked first.
     */
    override fun forget() {
        val m = managing ?: return
        if (m.busy) return
        // Whatever of it is still on its way is no longer this page's.
        reading++
        ui = m.copy(busy = true)
        val away = m.problem?.stale == true || m.problem == ManageProblem.NO_PEER
        scope.launch {
            if (away) {
                val record = io { client.forgetHere() }
                memory.forget(client.key)
                ui = RemoteManageUi.Forgotten(m.name, m.tv)
                if (record != null) io { client.tellForgotten(record) }
                return@launch
            }
            val told = io { client.unpair() }
            memory.forget(client.key)
            when (told) {
                is ManagedResult.Ok -> onClose()
                // The token is gone from here either way; the other side was not told.
                is ManagedResult.Failed -> ui = RemoteManageUi.Forgotten(m.name, m.tv)
            }
        }
    }

    override fun close() = onClose()
}
