package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse

/*
 * The decisions the main screen's watchdog (MainScreen in MainActivity.kt) makes on each tick,
 * as plain functions: they used to be written inline in the loop, where nothing could test them.
 * The loop still does every read and every write; these only say what follows from what it read.
 */

/** The pace of the watchdog's tick, and so what one tick adds to [MainTick.loggedOutSeconds]. */
internal const val MAIN_TICK_MS = 2_000L

/** How often the full status (peers included) is read for the summary and the devices. */
internal const val MAIN_STATUS_EVERY_MS = 10_000L

/** How often the account's picture is compared with the one the tailnet gives. */
internal const val MAIN_AVATAR_EVERY_MS = 30_000L

/**
 * How long a stopped main screen with a login link on it keeps checking for the login to go
 * through, so the Custom Tab it opened can be closed: LoginTab's own window for that.
 */
internal const val MAIN_LOGIN_WATCH_MS = 15 * 60_000L

/** How long "no login, no backend" has to last before the card says so, in seconds. */
internal const val MAIN_LOGGED_OUT_AFTER_S = 10

/** The card's state after one tick, and the counter the next tick starts from. */
internal data class MainTick(val state: String, val loggedOutSeconds: Int)

/**
 * The status card's state after one tick of the watchdog, from what the tick read. Only called
 * while no start, stop or login is in flight (`isProcessing`): then the broadcasts own the state.
 *
 * - No daemon: STOPPED.
 * - A daemon with a backend that is anything but NeedsLogin/NoState: ACTIVE — the card's own
 *   warnings say the rest (Starting, health).
 * - NeedsLogin or NoState: a daemon between states says so for a moment on every start, so the
 *   card waits [MAIN_LOGGED_OUT_AFTER_S] seconds of ticks before believing it, keeping what it
 *   showed (STOPPED turns ACTIVE: the daemon is there). After that, a node that has been logged
 *   in before and is not being offered a login link is a CONNECTION_ISSUE — it lost its control
 *   server, not its login — and anything else is LOGGED_OUT.
 *
 * [hasLoginUrl] is the link the previous tick read: the loop reads the new one after this.
 */
internal fun nextMainState(
    previous: String,
    alive: Boolean,
    backend: String,
    loggedOutSeconds: Int,
    wasLoggedIn: Boolean,
    hasLoginUrl: Boolean,
): MainTick {
    if (!alive) return MainTick("STOPPED", 0)
    if (backend != "NeedsLogin" && backend != "NoState") return MainTick("ACTIVE", 0)
    val seconds = loggedOutSeconds + (MAIN_TICK_MS / 1000).toInt()
    val state = when {
        seconds < MAIN_LOGGED_OUT_AFTER_S -> if (previous == "STOPPED") "ACTIVE" else previous
        wasLoggedIn && !hasLoginUrl -> "CONNECTION_ISSUE"
        else -> "LOGGED_OUT"
    }
    return MainTick(state, seconds)
}

/** Which of the watchdog's slower reads a tick makes. */
internal data class MainReads(val status: Boolean, val avatar: Boolean)

/**
 * Whether this tick reads the full status, and whether the account's picture is checked
 * against it. One read serves both: the picture is taken from the status the summary read
 * fetched, never from a second fetch of its own. Only a running backend has a status worth
 * reading. A [lastStatusAtMs] of 0 — the screen has just come back to the front — reads at once.
 */
internal fun mainReadsDue(nowMs: Long, running: Boolean, lastStatusAtMs: Long, lastAvatarAtMs: Long): MainReads {
    val status = running && (lastStatusAtMs == 0L || nowMs - lastStatusAtMs > MAIN_STATUS_EVERY_MS)
    return MainReads(status = status, avatar = status && nowMs - lastAvatarAtMs > MAIN_AVATAR_EVERY_MS)
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
 * MainScreen re-ran the whole screen every ten seconds.
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
