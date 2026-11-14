package io.github.bropines.tailscaled.core

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/** What changed in the daemon, as the bridge tells it (appctr/events.go). */
enum class DaemonEventKind(val wire: String) {
    /** Backend state, the login link, or the bridge attaching to or losing a daemon. */
    STATE("state"),

    /** The set of health warnings. */
    HEALTH("health"),

    /** The prefs the app shows: exit node, routes, DNS, hostname. */
    PREFS("prefs"),

    /** Peers added or removed, or a peer's online, exit-node offer, address or name. */
    NETMAP("netmap");

    companion object {
        fun fromWire(kind: String): DaemonEventKind? = entries.firstOrNull { it.wire == kind }
    }
}

/** One change event: its kind and the bridge's sequence number, which grows with every event. */
data class DaemonEvent(val kind: DaemonEventKind, val seq: Long)

/**
 * The last sequence number seen of each kind, 0 for none. A collector that was away — a screen
 * that was stopped — compares the revisions it last handled with these to learn what changed
 * meanwhile, without having been there for the events.
 */
data class Revisions(val state: Long = 0, val health: Long = 0, val prefs: Long = 0, val netmap: Long = 0) {
    operator fun get(kind: DaemonEventKind): Long = when (kind) {
        DaemonEventKind.STATE -> state
        DaemonEventKind.HEALTH -> health
        DaemonEventKind.PREFS -> prefs
        DaemonEventKind.NETMAP -> netmap
    }

    /** These revisions with [event] recorded, or unchanged when it is no newer than what they hold. */
    fun with(event: DaemonEvent): Revisions {
        if (event.seq <= this[event.kind]) return this
        return when (event.kind) {
            DaemonEventKind.STATE -> copy(state = event.seq)
            DaemonEventKind.HEALTH -> copy(health = event.seq)
            DaemonEventKind.PREFS -> copy(prefs = event.seq)
            DaemonEventKind.NETMAP -> copy(netmap = event.seq)
        }
    }

    /** The kinds that have a newer event here than in [earlier]. */
    fun changedSince(earlier: Revisions): Set<DaemonEventKind> =
        DaemonEventKind.entries.filterTo(mutableSetOf()) { this[it] > earlier[it] }
}

/**
 * The app's one source of "the daemon changed": the bridge's change events, registered once for
 * the process (TailSocksApp.onCreate) and observed by the screens.
 *
 * The bridge holds the only subscription to the daemon's bus and the latest state in memory;
 * what it sends here is only the kind of change and a sequence number. Whoever cares reads the
 * state afresh through the getters it always used (Appctr.getBackendState, the health JSON,
 * [DaemonStatus]) — there is no second copy of the state on this side to go stale.
 *
 * The listener runs on the bridge's dispatcher thread and does nothing but record and emit:
 * [events] drops its oldest entry rather than ever block that thread, and [revision] keeps,
 * whatever was dropped, the fact that something of each kind changed.
 */
object DaemonEvents {
    private const val TAG = "DaemonEvents"

    private val _events = MutableSharedFlow<DaemonEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every event as it arrives, to whoever is collecting at the time. */
    val events: SharedFlow<DaemonEvent> = _events.asSharedFlow()

    private val _revision = MutableStateFlow(Revisions())

    /** The last sequence number of each kind: what changed while a collector was away. */
    val revision: StateFlow<Revisions> = _revision.asStateFlow()

    private val registered = AtomicBoolean(false)

    /** Hands the bridge the listener, once per process. */
    fun register() {
        if (!registered.compareAndSet(false, true)) return
        try {
            appctr.Appctr.setDaemonEventListener(object : appctr.DaemonEventListener {
                override fun onDaemonEvent(kind: String, seq: Long) = deliver(kind, seq)
            })
        } catch (e: Throwable) {
            registered.set(false)
            Log.w(TAG, "could not register the daemon event listener", e)
        }
    }

    /**
     * Records one event from the bridge. Never blocks: a revision update is a compare-and-set,
     * and [events] drops its oldest entry when nobody keeps up. An unknown kind (a newer bridge)
     * is ignored.
     */
    internal fun deliver(kind: String, seq: Long) {
        val k = DaemonEventKind.fromWire(kind) ?: return
        val event = DaemonEvent(k, seq)
        _revision.update { it.with(event) }
        _events.tryEmit(event)
    }
}
