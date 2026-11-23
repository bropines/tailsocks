package io.github.bropines.tailscaled.core

/*
 * What the foreground service refreshes when the daemon tells it something changed, as plain
 * functions. The collector that acts on them is ServiceEventRefresher; the reads and writes are
 * the service's own (TailscaledService).
 *
 * Until the bridge sent change events (core/DaemonEvents.kt, appctr/events.go) all of this ran
 * in the service's refresh tick, every 15 seconds for as long as the service was up, screen off
 * included. The tick keeps what has to count ticks or look at the device — the Root Mode routing
 * re-checks and the DNS gate, the auto-reconnect check, the TUN rebuild check — and everything
 * below only happens when an event, a timer the state calls for, or the safety net asks for it.
 * The exit-node guard is at the end of the file.
 */

/** One daemon-facing refresh of the service. */
internal enum class ServiceRefresh {
    /**
     * The notification's card and the coordinator's OS refusal: backend state and health
     * warnings, read from the bridge's memory.
     */
    CARD,

    /** Forget the exit node's resolved name, so the card resolves it again: the prefs changed. */
    FORGET_EXIT_NODE_NAME,

    /**
     * Try again for an exit node whose name was not found: a node that was not in the netmap
     * may be now. A name already found stays (renames of the exit node were never followed).
     */
    RETRY_EXIT_NODE_NAME,

    /** The system share sheet's devices (ShareTargets): the full status, peers included. */
    SHARE_TARGETS,

    /** The home-screen widgets (WidgetUpdates), which repaint only what changed. */
    WIDGETS,
}

/** With no card read for this long while the service runs, the card is read anyway. */
internal const val SERVICE_SAFETY_MS = 30_000L

/**
 * The share sheet's devices are read off the full status at most this often (ShareTargets'
 * own interval): a burst of netmap events in a large tailnet costs one read, and the last
 * change of the burst is published when the gap ends.
 */
internal const val SHARE_TARGETS_GAP_MS = 60_000L

/**
 * An exit node whose name was not found is looked for again on a netmap event at most this
 * often: while it is missing from a busy tailnet's netmap, every event would otherwise be a
 * full status read.
 */
internal const val EXIT_NODE_NAME_RETRY_MS = 10_000L

/**
 * The refreshes the changed kinds call for.
 *
 * - `state` (backend state, login link, the bridge attaching or losing a daemon): the card,
 *   the share sheet (published only while Running, taken down otherwise), the widgets' status.
 * - `health`: the card (a settled warning makes it "connection problem") and the OS refusal.
 * - `prefs`: the card names the exit node, which the prefs carry — its name is forgotten and
 *   read again; the widgets show the exit node too.
 * - `netmap`: the share sheet lists the peers; an exit node not found by name may be in the
 *   netmap now; the exit-node widget lists the exit nodes and whether they are online.
 * - `routes`: nothing here; the tick's TUN rebuild check follows it.
 *
 * Widgets only while the screen is on ([interactive]), as the tick did: the service's screen-on
 * receiver repaints them the moment it wakes, and the card's own state changes request them
 * whatever the screen does (TailscaledService.showCard).
 */
internal fun serviceRefreshesFor(changed: Set<DaemonEventKind>, interactive: Boolean): Set<ServiceRefresh> {
    val out = mutableSetOf<ServiceRefresh>()
    for (kind in changed) when (kind) {
        DaemonEventKind.STATE -> {
            out += ServiceRefresh.CARD
            out += ServiceRefresh.SHARE_TARGETS
            out += ServiceRefresh.WIDGETS
        }
        DaemonEventKind.HEALTH -> out += ServiceRefresh.CARD
        DaemonEventKind.PREFS -> {
            out += ServiceRefresh.CARD
            out += ServiceRefresh.FORGET_EXIT_NODE_NAME
            out += ServiceRefresh.WIDGETS
        }
        DaemonEventKind.NETMAP -> {
            out += ServiceRefresh.SHARE_TARGETS
            out += ServiceRefresh.RETRY_EXIT_NODE_NAME
            out += ServiceRefresh.WIDGETS
        }
        // The TUN rebuild check on the tick reads it (tunRouteCheckDue); nothing here shows routes.
        DaemonEventKind.ROUTES -> Unit
    }
    if (!interactive) out -= ServiceRefresh.WIDGETS
    return out
}

/**
 * The safety net's refreshes: the card (its read is the bridge's memory, and it drives the
 * bridge's own re-check of a stale "DNS unavailable" warning, appctr/recovery.go), and the
 * widgets while the screen is on. Never the full status: a missed netmap event is followed by
 * a state event whenever the bridge re-attaches, and nothing else drops one (the revisions
 * keep every kind that changed).
 */
internal fun serviceSafetyNetRefreshes(interactive: Boolean): Set<ServiceRefresh> =
    if (interactive) setOf(ServiceRefresh.CARD, ServiceRefresh.WIDGETS) else setOf(ServiceRefresh.CARD)

/**
 * How long the card worker waits for an event before it reads the card anyway. One monotonic
 * clock; 0 for "never" in [warningSettlesAtMs].
 *
 * - [SERVICE_SAFETY_MS] after the last card read: the safety net. Counted from the last read,
 *   not the last event of any kind, so a stream of netmap events (which do not read the card)
 *   never holds it off.
 * - The moment a health warning that was too young to show becomes visible
 *   (ui.warningSettlesAtMs, converted to this clock): a health event comes when the warning
 *   appears, still hidden, and nothing else would say "look again" five seconds later.
 */
internal fun serviceCardWakeDelayMs(nowMs: Long, lastCardReadAtMs: Long, warningSettlesAtMs: Long): Long {
    var at = lastCardReadAtMs + SERVICE_SAFETY_MS
    if (warningSettlesAtMs != 0L && warningSettlesAtMs > lastCardReadAtMs) at = minOf(at, warningSettlesAtMs)
    return (at - nowMs).coerceAtLeast(0L)
}

/**
 * Whether a netmap event may look for an unresolved exit node name again ([lastFetchAtMs] on the
 * wall clock, 0 for never). A resolved name is never dropped by a netmap event.
 */
internal fun exitNodeNameRetryDue(nameResolved: Boolean, nowMs: Long, lastFetchAtMs: Long): Boolean =
    !nameResolved && nowMs - lastFetchAtMs >= EXIT_NODE_NAME_RETRY_MS

/*
 * The exit-node guard (ExitNodeGuard): whether the selected exit node is still online. Its one
 * fact is in the full status, so every look that judges it is a full read — hundreds of peers
 * with Mullvad. Until the events it read every 20 seconds of the 15-second tick (in practice
 * every 30) for as long as a node was selected, the screen off included; now the daemon's events
 * say when the answer may have changed, and a timer runs only while something is being timed.
 */

/** Why the guard's worker looks. */
internal enum class GuardTrigger {
    /** A run began, the service was asked to start again, the network or the CPU (Doze) came back. */
    START,

    /** The backend state changed or the bridge attached to or lost a daemon. */
    STATE,

    /** The health warnings changed — losing the coordination server is one ("not-in-map-poll"). */
    HEALTH,

    /** The daemon's prefs changed: the exit node chosen or cleared, here or elsewhere. */
    PREFS,

    /** A peer came or went or changed its online flag: perhaps the exit node. */
    NETMAP,

    /** The timed look the guard's state called for came due ([exitNodeGuardNextLookMs]). */
    TIMER,
}

/** The triggers the changed kinds are: one for one. */
internal fun exitNodeGuardTriggersFor(changed: Set<DaemonEventKind>): Set<GuardTrigger> =
    changed.mapNotNullTo(mutableSetOf()) {
        when (it) {
            DaemonEventKind.STATE -> GuardTrigger.STATE
            DaemonEventKind.HEALTH -> GuardTrigger.HEALTH
            DaemonEventKind.PREFS -> GuardTrigger.PREFS
            DaemonEventKind.NETMAP -> GuardTrigger.NETMAP
            // Subnet routes say nothing about the exit node: an exit-node offer is a netmap change.
            DaemonEventKind.ROUTES -> null
        }
    }

/**
 * Whether [triggers] are worth a full status read in [watch].
 *
 * - [ExitNodeGuard.Watch.IDLE]: never. Nothing selected, nothing paused — the look is the
 *   stored choice alone.
 * - Every other state reads on START, STATE, PREFS, NETMAP and TIMER: a node's online flag
 *   travels in a netmap event, the choice in a prefs event, a new daemon or a new backend
 *   state may bring a new netmap, and TIMER is what the state itself asked for.
 * - HEALTH reads only in [ExitNodeGuard.Watch.UNJUDGED]. Losing or regaining the coordination
 *   server shows as a health warning, not as a state event (the backend stays Running), and
 *   only a guard that could not judge is waiting for that server: elsewhere losing it changes
 *   nothing the guard says until a netmap event or the timer, and regaining it brings a netmap.
 *   Health warnings come and go with every relay hiccup; they are not worth a read each.
 */
internal fun exitNodeGuardReads(triggers: Set<GuardTrigger>, watch: ExitNodeGuard.Watch): Boolean = when (watch) {
    ExitNodeGuard.Watch.IDLE -> false
    ExitNodeGuard.Watch.UNJUDGED -> triggers.isNotEmpty()
    else -> triggers.any { it != GuardTrigger.HEALTH }
}

/**
 * With an exit node online and nothing changing, the guard reads anyway after this long. Only a
 * missed or misread event leaves it to the net, and the cost of that is a person not told their
 * exit node is dead — so a few minutes, not never: five is twelve reads an hour where there were
 * a hundred and twenty.
 */
internal const val EXIT_GUARD_SAFETY_MS = 5 * 60_000L

/**
 * The timed look while the guard waits for something to come back: a node offline past the
 * grace (its notification goes when it returns), a paused node (selected again when it
 * returns), a node not yet judged (no netmap, or the coordination server out of touch). The
 * netmap event announces a return; this is the fallback, at the cadence the 20-second read on
 * the 15-second tick actually had, so nothing is noticed later than before if every event
 * were lost.
 */
internal const val EXIT_GUARD_AWAIT_MS = 30_000L

/**
 * The least time between two reads asked for by events. The bridge already folds a netmap burst
 * into at most one event per 2 s; a large tailnet that keeps changing (Mullvad: hundreds of
 * peers) can still send one every few seconds for as long as it likes. 30 s is the old
 * cadence: no stream of events makes the guard read more often than it did when it polled, and
 * an event inside the floor is carried out when the floor ends — never dropped — so an outage
 * is seen at most 30 s late, as before. Timed looks (the grace, the fallback, the net) are not
 * held back by it.
 */
internal const val EXIT_GUARD_FLOOR_MS = 30_000L

/**
 * Whether a look may read now: [triggers] call for it in [watch] ([exitNodeGuardReads]) and,
 * unless the timer is among them, [floorMs] has passed since the last read. A timed look is
 * what the state asked for at a time it chose; only event-driven reads are spaced out.
 */
internal fun exitNodeGuardMayRead(
    triggers: Set<GuardTrigger>,
    watch: ExitNodeGuard.Watch,
    nowMs: Long,
    lastReadAtMs: Long,
    floorMs: Long = EXIT_GUARD_FLOOR_MS,
): Boolean = exitNodeGuardReads(triggers, watch) &&
    (GuardTrigger.TIMER in triggers || nowMs - lastReadAtMs >= floorMs)

/**
 * How long after a look the next timed one is due in [watch]; null for none ([offlineForMs]:
 * how long the selected node has been seen offline).
 *
 * - IDLE: none — nothing is watched; a prefs event (or a start) is what selects a node.
 * - WATCHING: the safety net, [EXIT_GUARD_SAFETY_MS].
 * - TIMING: the end of the grace, so the warning comes [ExitNodeGuard.OFFLINE_GRACE_MS] after
 *   the node was first seen offline even if no other event arrives.
 * - WARNED, PAUSED, UNJUDGED: [EXIT_GUARD_AWAIT_MS].
 */
internal fun exitNodeGuardNextLookMs(watch: ExitNodeGuard.Watch, offlineForMs: Long): Long? = when (watch) {
    ExitNodeGuard.Watch.IDLE -> null
    ExitNodeGuard.Watch.WATCHING -> EXIT_GUARD_SAFETY_MS
    ExitNodeGuard.Watch.TIMING -> (ExitNodeGuard.OFFLINE_GRACE_MS - offlineForMs).coerceAtLeast(0L)
    ExitNodeGuard.Watch.WARNED, ExitNodeGuard.Watch.PAUSED, ExitNodeGuard.Watch.UNJUDGED -> EXIT_GUARD_AWAIT_MS
}

/**
 * When the guard looks next, on one monotonic clock; null to wait for an event alone.
 *
 * [previousDueAtMs] is the timed look already scheduled. A look that read schedules afresh
 * from [nowMs]; one that did not (a health event outside UNJUDGED, a trigger held back by the
 * floor) keeps the one scheduled, so a stream of unread events never postpones the safety net.
 * [heldBack] — the look wanted a read and the floor refused it — brings the next look forward
 * to the floor's end ([lastReadAtMs] + [floorMs]), where the read is carried out.
 */
internal fun exitNodeGuardDueAt(
    watch: ExitNodeGuard.Watch,
    offlineForMs: Long,
    read: Boolean,
    heldBack: Boolean,
    nowMs: Long,
    lastReadAtMs: Long,
    previousDueAtMs: Long?,
    floorMs: Long = EXIT_GUARD_FLOOR_MS,
    nextLook: (ExitNodeGuard.Watch, Long) -> Long? = ::exitNodeGuardNextLookMs,
): Long? {
    val next = nextLook(watch, offlineForMs)
    var due = when {
        next == null -> null
        read || previousDueAtMs == null -> nowMs + next
        else -> previousDueAtMs
    }
    if (heldBack) {
        val floorEnd = lastReadAtMs + floorMs
        due = if (due == null) floorEnd else minOf(due, floorEnd)
    }
    return due
}
