package io.github.bropines.tailscaled.core

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * The foreground service's daemon-facing refreshes, done when the daemon says something changed
 * rather than on the refresh tick (see ServiceRefreshPlan.kt for what each event calls for).
 *
 * It lives exactly as long as the service's running state: [start] where the tick is started,
 * [stop] where the tick is cancelled — both on the service's main thread, and [stop] after the
 * teardown flag is set, so a teardown that has begun is never followed by a refresh: every
 * worker asks [Actions.live] before it reads and passes a `current` check that the service asks
 * again before it publishes anything it read.
 *
 * Four coroutines per run, on [dispatcher] (never the main thread — the reads are JNI calls
 * into the bridge, and the full status is a LocalAPI round trip):
 *  - the collector turns [revisions] into refreshes. A StateFlow of the last sequence per kind,
 *    so a burst folds into one pass and no kind is ever lost to a dropped event;
 *  - the card worker reads the card when asked (conflated: however many asks arrive during a
 *    read, one more read follows), and on its own when [SERVICE_SAFETY_MS] pass without a read
 *    or a young health warning comes of age ([wakeDelay]);
 *  - the share-sheet worker publishes the devices, the full-status read at most once per
 *    [shareGapMs] with the last ask of a burst carried out when the gap ends; taking them down
 *    (the daemon is not Running) costs no read and is never held back by the gap;
 *  - the exit-node guard's worker looks when an event may have changed its answer, reading the
 *    full status only when the guard's state calls for it ([exitNodeGuardReads]) and at most
 *    once per [guardFloorMs] for events (the last ask of a burst carried out when the floor
 *    ends), and on its own when the state asks for a timed look — the grace running out, the
 *    fallback while something is awaited, the safety net ([exitNodeGuardDueAt]).
 * One worker per refresh, so a refresh never runs twice at once; the widgets coalesce their own
 * requests (WidgetUpdates.request).
 */
internal class ServiceEventRefresher(
    private val actions: Actions,
    private val revisions: StateFlow<Revisions> = DaemonEvents.revision,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val wakeDelay: (nowMs: Long, lastReadAtMs: Long, settlesAtMs: Long) -> Long = ::serviceCardWakeDelayMs,
    private val shareGapMs: Long = SHARE_TARGETS_GAP_MS,
    private val guardFloorMs: Long = EXIT_GUARD_FLOOR_MS,
    private val guardNextLook: (ExitNodeGuard.Watch, Long) -> Long? = ::exitNodeGuardNextLookMs,
    private val guardGateRetryMs: Long = EXIT_GUARD_AWAIT_MS,
) {
    /** What the service does for each refresh. Called on [dispatcher]'s threads. */
    interface Actions {
        /** The service is running and no teardown has begun. */
        fun live(): Boolean

        /** The screen is on (widgets are refreshed only then). */
        fun interactive(): Boolean

        /**
         * Reads the backend state and the health warnings, checks the coordinator's OS refusal
         * and publishes the card — the publish only while [current] still holds. Returns when a
         * warning too young to show becomes visible, on [clock], or 0 for none.
         */
        fun refreshCard(current: () -> Boolean): Long

        /**
         * Drops the exit node's cached name — only an unresolved one when [onlyUnresolved], and
         * only when a retry is due. True when something was dropped and the card should be read.
         */
        fun forgetExitNodeName(onlyUnresolved: Boolean): Boolean

        /** Brings the share sheet's devices in line; see [ShareSync]. */
        fun syncShareTargets(allowRead: Boolean, current: () -> Boolean): ShareSync

        /** Asks the widgets for a pass. */
        fun requestWidgets()

        /** The guard may look at all: the backend is Running and a network is up. */
        fun exitNodeGuardMayRun(): Boolean

        /**
         * One look by the exit-node guard ([ExitNodeGuard.check]), reading the full status only
         * when [mayRead] allows it for the state the guard is in; nothing is posted once
         * [current] turns false.
         */
        fun checkExitNode(mayRead: (ExitNodeGuard.Watch) -> Boolean, current: () -> Boolean): ExitNodeGuard.Look
    }

    enum class ShareSync {
        /** Nothing to read: taken down, switched off, or not running. */
        DONE,

        /** The full status was read and the devices published. */
        READ,

        /** A read is wanted but [allowRead] was false: still inside the gap. */
        WANTS_READ,
    }

    private class Run(val scope: CoroutineScope) {
        val card = Channel<Unit>(Channel.CONFLATED)
        val share = Channel<Unit>(Channel.CONFLATED)
        val guard = Channel<Unit>(Channel.CONFLATED)
        /** The guard's triggers since its last look; the channel only wakes it. */
        val guardTriggers = AtomicReference<Set<GuardTrigger>>(emptySet())

        fun askGuard(triggers: Set<GuardTrigger>): Boolean {
            if (triggers.isEmpty()) return true
            guardTriggers.updateAndGet { it + triggers }
            return guard.trySend(Unit).isSuccess
        }
    }

    @Volatile private var run: Run? = null

    /**
     * Starts a run unless one is going; false when one was. Everything is read once at the
     * start.
     */
    @Synchronized
    fun start(): Boolean {
        if (run != null) return false
        val r = Run(CoroutineScope(SupervisorJob() + dispatcher))
        run = r
        r.scope.launch { collect(r) }
        r.scope.launch { cardWorker(r) }
        r.scope.launch { shareWorker(r) }
        r.scope.launch { guardWorker(r) }
        r.share.trySend(Unit)
        r.scope.launch { guarded(Unit) { if (actions.live() && actions.interactive()) actions.requestWidgets() } }
        return true
    }

    /** Ends the run. A read in flight finishes, but publishes nothing (its `current` turns false). */
    @Synchronized
    fun stop() {
        run?.scope?.cancel()
        run = null
    }

    /**
     * Asks for a card read outside the events (Doze ended, the network came back). False when
     * no run is going, for the caller to read by itself.
     */
    fun requestCard(): Boolean = run?.card?.trySend(Unit)?.isSuccess == true

    /** Asks for the share sheet again (its switch was turned on). */
    fun requestShareTargets(): Boolean = run?.share?.trySend(Unit)?.isSuccess == true

    /**
     * Asks the exit-node guard to look (a second start, the network or the CPU back). Held to
     * the floor like an event. False when no run is going.
     */
    fun requestExitNodeGuard(): Boolean = run?.askGuard(setOf(GuardTrigger.START)) == true

    private suspend fun collect(r: Run) {
        var seen = revisions.value
        revisions.collect { rev ->
            val changed = rev.changedSince(seen)
            seen = rev
            if (changed.isEmpty() || !actions.live()) return@collect
            guarded(Unit) { dispatch(r, serviceRefreshesFor(changed, actions.interactive())) }
            r.askGuard(exitNodeGuardTriggersFor(changed))
        }
    }

    private fun dispatch(r: Run, refreshes: Set<ServiceRefresh>) {
        var card = ServiceRefresh.CARD in refreshes
        if (ServiceRefresh.FORGET_EXIT_NODE_NAME in refreshes && actions.forgetExitNodeName(onlyUnresolved = false)) card = true
        if (ServiceRefresh.RETRY_EXIT_NODE_NAME in refreshes && actions.forgetExitNodeName(onlyUnresolved = true)) card = true
        if (card) r.card.trySend(Unit)
        if (ServiceRefresh.SHARE_TARGETS in refreshes) r.share.trySend(Unit)
        if (ServiceRefresh.WIDGETS in refreshes) actions.requestWidgets()
    }

    private suspend fun cardWorker(r: Run) {
        val job = currentCoroutineContext().job
        val current = { job.isActive && actions.live() }
        var lastReadAt: Long
        var settlesAt = 0L
        var plan = setOf(ServiceRefresh.CARD)
        while (true) {
            if (actions.live()) {
                settlesAt = guarded(0L) { actions.refreshCard(current) }
                if (ServiceRefresh.WIDGETS in plan && current()) guarded(Unit) { actions.requestWidgets() }
            }
            lastReadAt = clock()
            val wait = wakeDelay(clock(), lastReadAt, settlesAt)
            val asked = if (wait <= 0L) r.card.tryReceive().isSuccess
                else withTimeoutOrNull(wait) { r.card.receive() } != null
            // No ask in time: the safety net, or a warning that has come of age.
            plan = if (asked) setOf(ServiceRefresh.CARD) else serviceSafetyNetRefreshes(actions.interactive())
        }
    }

    private suspend fun shareWorker(r: Run) {
        val job = currentCoroutineContext().job
        val current = { job.isActive && actions.live() }
        // Far enough in the past that the first read is never held back, without overflowing.
        var lastReadAt = Long.MIN_VALUE / 2
        while (true) {
            r.share.receive()
            while (actions.live()) {
                val allow = clock() - lastReadAt >= shareGapMs
                val outcome = guarded(ShareSync.DONE) { actions.syncShareTargets(allow, current) }
                if (outcome == ShareSync.READ) lastReadAt = clock()
                if (outcome != ShareSync.WANTS_READ) break
                // Inside the gap: wait it out, or for another ask, which may be a take-down
                // that need not wait.
                val left = lastReadAt + shareGapMs - clock()
                if (left > 0L) withTimeoutOrNull(left) { r.share.receive() }
            }
        }
    }

    private suspend fun guardWorker(r: Run) {
        val job = currentCoroutineContext().job
        val current = { job.isActive && actions.live() }
        // Far enough in the past that the first read is never held back, without overflowing.
        var lastReadAt = Long.MIN_VALUE / 2
        var dueAt: Long? = null
        var triggers: Set<GuardTrigger> = setOf(GuardTrigger.START)
        while (true) {
            if (actions.live() && guarded(false) { actions.exitNodeGuardMayRun() }) {
                val t = triggers
                val look = guarded(null) {
                    actions.checkExitNode({ w -> exitNodeGuardMayRead(t, w, clock(), lastReadAt, guardFloorMs) }, current)
                }
                if (look != null) {
                    val now = clock()
                    if (look.read) lastReadAt = now
                    val heldBack = !look.read && exitNodeGuardReads(t, look.watch)
                    dueAt = exitNodeGuardDueAt(
                        look.watch, look.offlineForMs, look.read, heldBack, now, lastReadAt, dueAt,
                        guardFloorMs, guardNextLook,
                    )
                } else {
                    // The look failed (logged): again on the fallback, never in a loop.
                    dueAt = clock() + guardGateRetryMs
                }
            } else {
                // Not Running, no network, or a teardown begun: nothing to judge. A state event
                // or the network coming back asks again; until then the gate is looked at now and
                // then, which reads nothing (the backend state is the bridge's memory).
                dueAt = clock() + guardGateRetryMs
            }
            val wait = dueAt?.let { it - clock() }
            val asked = when {
                wait == null -> { r.guard.receive(); true }
                wait <= 0L -> r.guard.tryReceive().isSuccess
                else -> withTimeoutOrNull(wait) { r.guard.receive() } != null
            }
            triggers = r.guardTriggers.getAndSet(emptySet())
            if (!asked || dueAt?.let { clock() >= it } == true) triggers = triggers + GuardTrigger.TIMER
        }
    }

    /** One failed refresh is logged and skipped; it must not end the worker for the rest of the run. */
    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Refresh failed: ${e.message}")
        fallback
    }

    private companion object {
        const val TAG = "ServiceEventRefresher"
    }
}
