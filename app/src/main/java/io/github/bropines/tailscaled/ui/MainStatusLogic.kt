package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.core.DaemonEventKind
import io.github.bropines.tailscaled.models.HealthWarning
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse

/*
 * The decisions the main screen's refresh loop (MainScreen in MainActivity.kt) makes, as plain
 * functions: they used to be written inline in a 2-second loop, where nothing could test them.
 * The loop still does every read and every write; these only say what follows from what it read,
 * and when it next has to look.
 *
 * Since the bridge tells the app when the daemon changes (core/DaemonEvents.kt, appctr/events.go),
 * the loop reads when told: a state, health or prefs event re-reads the bridge's cheap state, a
 * netmap or prefs event the full status. With nothing pending and nothing changing it sleeps for
 * [MAIN_SAFETY_MS] at a time — a missed event (a daemon restart, the bridge re-attaching) must
 * never leave a stale screen for good — and wakes sooner only while something is time-bound:
 * a start or stop in flight, a "logged out" waiting out its grace period, a health warning not
 * yet settled ([mainWakeDelayMs]).
 */

/** The pace of the start watchdog: while a start, stop or login is in flight, the loop looks this often. */
internal const val MAIN_TICK_MS = 2_000L

/**
 * How long the start watchdog lasts after a start, stop or login was pressed. The broadcasts and
 * the state events end it sooner; this only bounds a transition that never reports back, which
 * then falls to the safety net instead of a 2-second loop for the rest of the screen's life.
 */
internal const val MAIN_START_WATCH_MS = 120_000L

/** The safety net: with no event of any kind for this long, the cheap state is read anyway. */
internal const val MAIN_SAFETY_MS = 30_000L

/** How often, at most, the account's picture is compared with the one the tailnet gives. */
internal const val MAIN_AVATAR_EVERY_MS = 30_000L

/**
 * How long a stopped main screen with a login link on it keeps checking for the login to go
 * through, so the Custom Tab it opened can be closed: LoginTab's own window for that.
 */
internal const val MAIN_LOGIN_WATCH_MS = 15 * 60_000L

/** How long "no login, no backend" has to last before the card says so. */
internal const val MAIN_LOGGED_OUT_AFTER_MS = 10_000L

/**
 * The card's state after one read, and since when the backend has said NeedsLogin/NoState
 * (a monotonic clock, 0 when it does not).
 */
internal data class MainTick(val state: String, val loggedOutSinceMs: Long)

/**
 * The status card's state after one read of the bridge, from what it read. Only called while no
 * start, stop or login is in flight (`isProcessing`): then the broadcasts own the state.
 *
 * - No daemon: STOPPED.
 * - A daemon with a backend that is anything but NeedsLogin/NoState: ACTIVE — the card's own
 *   warnings say the rest (Starting, health).
 * - NeedsLogin or NoState: a daemon between states says so for a moment on every start, so the
 *   card waits [MAIN_LOGGED_OUT_AFTER_MS] from the first read that saw it before believing it,
 *   keeping what it showed (STOPPED turns ACTIVE: the daemon is there). After that, a node that
 *   has been logged in before and is not being offered a login link is a CONNECTION_ISSUE — it
 *   lost its control server, not its login — and anything else is LOGGED_OUT. Reads come when
 *   events do, so the grace period is measured on the clock ([nowMs], monotonic and positive),
 *   not counted in reads; [mainWakeDelayMs] wakes the loop when it ends.
 *
 * [hasLoginUrl] is the link the previous read found: the loop reads the new one after this.
 */
internal fun nextMainState(
    previous: String,
    alive: Boolean,
    backend: String,
    loggedOutSinceMs: Long,
    nowMs: Long,
    wasLoggedIn: Boolean,
    hasLoginUrl: Boolean,
): MainTick {
    if (!alive) return MainTick("STOPPED", 0)
    if (backend != "NeedsLogin" && backend != "NoState") return MainTick("ACTIVE", 0)
    val since = if (loggedOutSinceMs == 0L) nowMs else loggedOutSinceMs
    val state = when {
        nowMs - since < MAIN_LOGGED_OUT_AFTER_MS -> if (previous == "STOPPED") "ACTIVE" else previous
        wasLoggedIn && !hasLoginUrl -> "CONNECTION_ISSUE"
        else -> "LOGGED_OUT"
    }
    return MainTick(state, since)
}

/** Whether these changes call for a read of the bridge's cheap state (backend, health, login link). */
internal fun mainWantsStateRead(changed: Set<DaemonEventKind>): Boolean =
    DaemonEventKind.STATE in changed || DaemonEventKind.HEALTH in changed || DaemonEventKind.PREFS in changed

/**
 * Whether these changes call for a read of the full status: the peers (the summary's online
 * count, the devices), or the prefs — the summary names the exit node, which the status says.
 */
internal fun mainWantsStatusRead(changed: Set<DaemonEventKind>): Boolean =
    DaemonEventKind.NETMAP in changed || DaemonEventKind.PREFS in changed

/**
 * Whether the account's picture is compared with the tailnet's on this status read: at most once
 * per [MAIN_AVATAR_EVERY_MS], off a status the summary read fetched anyway, never off a fetch of
 * its own. [lastAvatarAtMs] 0: never yet.
 */
internal fun avatarDue(nowMs: Long, lastAvatarAtMs: Long): Boolean =
    lastAvatarAtMs == 0L || nowMs - lastAvatarAtMs >= MAIN_AVATAR_EVERY_MS

/**
 * When the first of [all] that is hidden only for being too young (visibleWarnings' settle time)
 * comes of age, in the warnings' own clock (Unix ms); 0 when none is waiting. A health event comes
 * when a warning appears, and the screen reads it then — still hidden; nothing else would tell the
 * screen to look again five seconds later.
 */
internal fun warningSettlesAtMs(all: List<HealthWarning>, nowMs: Long): Long =
    all.asSequence()
        .filter { it.brokenSinceMs != 0L && nowMs - it.brokenSinceMs < HEALTH_SETTLE_MS }
        .filter { visibleWarnings(listOf(it), it.brokenSinceMs + HEALTH_SETTLE_MS).isNotEmpty() }
        .minOfOrNull { it.brokenSinceMs + HEALTH_SETTLE_MS } ?: 0L

/**
 * How long the refresh loop sleeps before it reads the cheap state again unless an event wakes it
 * first. All times on one monotonic clock; a 0 "since"/"at" means the condition does not hold.
 *
 * - [MAIN_SAFETY_MS] after the last read, always: the safety net.
 * - [MAIN_TICK_MS] after the last read while a start, stop or login has been in flight
 *   ([processingSinceMs]) for less than [MAIN_START_WATCH_MS]: the start watchdog.
 * - The end of the logged-out grace period ([loggedOutSinceMs] + [MAIN_LOGGED_OUT_AFTER_MS]),
 *   unless a read at or after it has already decided.
 * - The moment a young health warning becomes visible ([warningSettlesAtMs], converted).
 */
internal fun mainWakeDelayMs(
    nowMs: Long,
    lastReadAtMs: Long,
    processingSinceMs: Long,
    loggedOutSinceMs: Long,
    warningSettlesAtMs: Long,
): Long {
    var at = lastReadAtMs + MAIN_SAFETY_MS
    if (processingSinceMs != 0L && nowMs - processingSinceMs < MAIN_START_WATCH_MS) {
        at = minOf(at, lastReadAtMs + MAIN_TICK_MS)
    }
    if (loggedOutSinceMs != 0L) {
        val decides = loggedOutSinceMs + MAIN_LOGGED_OUT_AFTER_MS
        if (decides > lastReadAtMs) at = minOf(at, decides)
    }
    if (warningSettlesAtMs != 0L && warningSettlesAtMs > lastReadAtMs) at = minOf(at, warningSettlesAtMs)
    return (at - nowMs).coerceAtLeast(0L)
}

/**
 * The picture the tailnet has for this node's user, when it is not [currentUrl] already: the
 * account's picture to fetch and store. Null when there is none or nothing changed. The user is
 * Self's; a status without one (shared nodes, an old daemon) falls back to its first user.
 */
internal fun avatarToSync(status: StatusResponse, currentUrl: String?): String? {
    val selfUser = status.users?.get(status.self?.userID?.toString()) ?: status.users?.values?.firstOrNull()
    return selfUser?.profilePicUrl?.takeIf { it.isNotEmpty() && it != currentUrl }
}

/**
 * What the managed devices take from the peer list (ManagedOnMain.kt `managedNow`): each
 * peer's id, name and whether it is online. Two lists with the same answer here draw the same
 * entries, so the screen hands the devices a new list only when this changes — the peer list
 * itself is new on every read (traffic counters, handshakes), and reading it at the top of
 * MainScreen re-ran the whole screen on every status read.
 */
internal fun managedPresence(peers: List<PeerData>?): List<Triple<String?, String, Boolean>>? =
    peers?.map { Triple(it.id, it.getDisplayName(), it.online == true) }

/**
 * The `inSampleSize` that decodes a [width]×[height] picture to no less than [targetPx] on its
 * shorter side: the largest power of two that keeps it there. An account picture is drawn
 * 38 dp wide; the tailnet hands out pictures of several hundred pixels.
 */
internal fun avatarSampleSize(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1
    val shorter = minOf(width, height)
    var sample = 1
    while (shorter / (sample * 2) >= targetPx) sample *= 2
    return sample
}
