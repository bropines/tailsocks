package io.github.bropines.tailscaled.core

/*
 * What the foreground service refreshes when the daemon tells it something changed, as plain
 * functions. The collector that acts on them is ServiceEventRefresher; the reads and writes are
 * the service's own (TailscaledService).
 *
 * Until the bridge sent change events (core/DaemonEvents.kt, appctr/events.go) all of this ran
 * in the service's refresh tick, every 15 seconds for as long as the service was up, screen off
 * included. The tick keeps what has to count ticks or look at the device — the Root Mode routing
 * re-checks and the DNS gate, the auto-reconnect check, the exit-node guard, the TUN rebuild
 * check — and everything below only happens when an event or the safety net asks for it.
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
