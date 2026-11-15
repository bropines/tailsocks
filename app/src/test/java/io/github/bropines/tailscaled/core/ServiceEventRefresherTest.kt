package io.github.bropines.tailscaled.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The service's event refresher against fake actions, on the real clock with short intervals:
 * events read at once, the safety net reads without them, the share sheet's reads keep their
 * gap (but a take-down does not wait for it), and nothing runs once stopped or torn down.
 */
class ServiceEventRefresherTest {

    private val revisions = MutableStateFlow(Revisions())
    private var seq = 0L

    private val live = AtomicBoolean(true)
    private val daemonRunning = AtomicBoolean(true)
    private val cardReads = AtomicInteger()
    private val widgetRequests = AtomicInteger()
    private val shareReads = AtomicInteger()
    private val shareTakeDowns = AtomicInteger()
    private val forgets = mutableListOf<Boolean>()

    private val actions = object : ServiceEventRefresher.Actions {
        override fun live() = live.get()
        override fun interactive() = true
        override fun refreshCard(current: () -> Boolean): Long {
            cardReads.incrementAndGet()
            return 0L
        }
        override fun forgetExitNodeName(onlyUnresolved: Boolean): Boolean {
            synchronized(forgets) { forgets += onlyUnresolved }
            return false
        }
        override fun syncShareTargets(allowRead: Boolean, current: () -> Boolean): ServiceEventRefresher.ShareSync {
            if (!daemonRunning.get()) {
                shareTakeDowns.incrementAndGet()
                return ServiceEventRefresher.ShareSync.DONE
            }
            if (!allowRead) return ServiceEventRefresher.ShareSync.WANTS_READ
            shareReads.incrementAndGet()
            return ServiceEventRefresher.ShareSync.READ
        }
        override fun requestWidgets() {
            widgetRequests.incrementAndGet()
        }

        override fun exitNodeGuardMayRun() = guardGate.get()

        override fun checkExitNode(mayRead: (ExitNodeGuard.Watch) -> Boolean, current: () -> Boolean): ExitNodeGuard.Look {
            guardLooks.incrementAndGet()
            val w = guardWatch.get()
            if (!mayRead(w)) return ExitNodeGuard.Look(w, false, 0L)
            guardReads.incrementAndGet()
            val after = guardAfterReads.poll() ?: w
            guardWatch.set(after)
            return ExitNodeGuard.Look(after, true, 0L)
        }
    }

    // The exit-node guard's side: the state it is in, what a read moves it to, and the counts.
    private val guardGate = AtomicBoolean(true)
    private val guardWatch = AtomicReference(ExitNodeGuard.Watch.IDLE)
    /** Where each read leaves the guard, in order; once empty, where it was. */
    private val guardAfterReads = ConcurrentLinkedQueue<ExitNodeGuard.Watch>()
    private val guardLooks = AtomicInteger()
    private val guardReads = AtomicInteger()

    private fun refresher(
        safetyMs: Long = 60_000L,
        shareGapMs: Long = 60_000L,
        guardFloorMs: Long = 60_000L,
        guardNextLook: (ExitNodeGuard.Watch, Long) -> Long? = { w, _ -> if (w == ExitNodeGuard.Watch.IDLE) null else 60_000L },
        guardGateRetryMs: Long = 60_000L,
    ) = ServiceEventRefresher(
        actions = actions,
        revisions = revisions,
        clock = { System.nanoTime() / 1_000_000 },
        dispatcher = Dispatchers.Default,
        wakeDelay = { now, last, _ -> (last + safetyMs - now).coerceAtLeast(0L) },
        shareGapMs = shareGapMs,
        guardFloorMs = guardFloorMs,
        guardNextLook = guardNextLook,
        guardGateRetryMs = guardGateRetryMs,
    ).also { started = it }

    private var started: ServiceEventRefresher? = null

    @After
    fun tearDown() {
        started?.stop()
    }

    private fun emit(kind: DaemonEventKind) = revisions.update { it.with(DaemonEvent(kind, ++seq)) }

    private fun eventually(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("condition not met in $timeoutMs ms")
            Thread.sleep(10)
        }
    }

    /**
     * The collector takes the revision it starts from before it subscribes, so an event emitted
     * before then is not news to it. A fixed sleep was the wait, and a busy machine can outlast it;
     * the flow's own count of subscribers says when it is listening.
     */
    private fun awaitCollector() = eventually { revisions.subscriptionCount.value >= 1 }

    @Test
    fun `a start reads the card and the share sheet once`() {
        refresher().start()
        eventually { cardReads.get() == 1 && shareReads.get() == 1 }
        Thread.sleep(200)
        assertEquals(1, cardReads.get())
        assertEquals(1, shareReads.get())
    }

    @Test
    fun `a health event reads the card at once and nothing else`() {
        refresher().start()
        eventually { cardReads.get() == 1 && shareReads.get() == 1 && widgetRequests.get() == 1 }
        awaitCollector()
        val widgets = widgetRequests.get()
        emit(DaemonEventKind.HEALTH)
        eventually { cardReads.get() == 2 }
        Thread.sleep(100)
        assertEquals(1, shareReads.get())
        assertEquals(widgets, widgetRequests.get())
    }

    @Test
    fun `prefs forget the exit node name, a netmap only retries an unresolved one`() {
        refresher().start()
        eventually { cardReads.get() == 1 }
        awaitCollector()
        emit(DaemonEventKind.PREFS)
        eventually { synchronized(forgets) { forgets.size == 1 } }
        emit(DaemonEventKind.NETMAP)
        eventually { synchronized(forgets) { forgets.size == 2 } }
        assertEquals(listOf(false, true), synchronized(forgets) { forgets.toList() })
    }

    @Test
    fun `with no event the safety net reads the card again`() {
        refresher(safetyMs = 150L).start()
        eventually { cardReads.get() >= 3 }
        // The net also asks the widgets (the screen is on here).
        assertTrue(widgetRequests.get() >= 2)
    }

    @Test
    fun `no safety read before its time`() {
        // The net is far off from the sleep: the first read can be noticed late on a busy machine.
        refresher(safetyMs = 3_000L).start()
        eventually { cardReads.get() == 1 }
        Thread.sleep(500)
        assertEquals(1, cardReads.get())
    }

    @Test
    fun `a netmap burst costs one share read per gap, and the last ask is carried out`() {
        // The gap is long next to the burst and the sleeps (about 250 ms): the first read may be
        // noticed late on a busy machine, and the burst must still fall inside its gap.
        refresher(shareGapMs = 1_500L).start()
        eventually { shareReads.get() == 1 }
        awaitCollector()
        repeat(5) {
            emit(DaemonEventKind.NETMAP)
            Thread.sleep(20)
        }
        Thread.sleep(100)
        assertEquals("inside the gap nothing is read", 1, shareReads.get())
        eventually(timeoutMs = 5_000L) { shareReads.get() == 2 }
        Thread.sleep(600)
        assertEquals("the burst folded into one read", 2, shareReads.get())
    }

    @Test
    fun `taking the share sheet down does not wait out the gap`() {
        refresher(shareGapMs = 60_000L).start()
        eventually { shareReads.get() == 1 }
        awaitCollector()
        daemonRunning.set(false)
        emit(DaemonEventKind.STATE)
        eventually(timeoutMs = 1_000L) { shareTakeDowns.get() == 1 }
    }

    @Test
    fun `a stopped refresher reads nothing`() {
        val r = refresher(safetyMs = 100L)
        r.start()
        eventually { cardReads.get() >= 1 }
        r.stop()
        Thread.sleep(50)
        val after = cardReads.get()
        emit(DaemonEventKind.STATE)
        Thread.sleep(400)
        assertEquals(after, cardReads.get())
        assertEquals(false, r.requestCard())
    }

    @Test
    fun `nothing is read once a teardown began`() {
        refresher(safetyMs = 100L).start()
        eventually { cardReads.get() >= 1 }
        live.set(false)
        Thread.sleep(50)
        val after = cardReads.get()
        emit(DaemonEventKind.STATE)
        Thread.sleep(400)
        assertEquals(after, cardReads.get())
    }

    @Test
    fun `a card asked for outside the events is read`() {
        val r = refresher()
        assertEquals(false, r.requestCard())
        r.start()
        eventually { cardReads.get() == 1 }
        Thread.sleep(50) // the worker waits for asks
        assertTrue(r.requestCard())
        eventually { cardReads.get() == 2 }
    }

    // The exit-node guard's worker. The fake guard reads only when the worker's mayRead allows
    // it, so guardReads counts exactly the full-status reads the plan let through.

    @Test
    fun `with no exit node the guard looks but never reads`() {
        refresher().start()
        eventually { guardLooks.get() == 1 }
        awaitCollector()
        for (kind in DaemonEventKind.entries) emit(kind)
        eventually { guardLooks.get() >= 2 }
        Thread.sleep(200)
        assertEquals(0, guardReads.get())
    }

    @Test
    fun `a start reads once, then an online node is not read without an event`() {
        guardWatch.set(ExitNodeGuard.Watch.UNJUDGED)
        guardAfterReads += ExitNodeGuard.Watch.WATCHING
        refresher(guardFloorMs = 0L).start()
        eventually { guardReads.get() == 1 }
        Thread.sleep(500)
        assertEquals(1, guardReads.get())
    }

    @Test
    fun `a netmap event reads, a health event does not while the node is judged`() {
        guardWatch.set(ExitNodeGuard.Watch.UNJUDGED)
        guardAfterReads += ExitNodeGuard.Watch.WATCHING
        refresher(guardFloorMs = 0L).start()
        eventually { guardReads.get() == 1 }
        awaitCollector()
        val looks = guardLooks.get()
        emit(DaemonEventKind.HEALTH)
        eventually { guardLooks.get() > looks }
        assertEquals(1, guardReads.get())
        emit(DaemonEventKind.NETMAP)
        eventually { guardReads.get() == 2 }
    }

    @Test
    fun `a health event reads while the node could not be judged`() {
        guardWatch.set(ExitNodeGuard.Watch.UNJUDGED)
        refresher(guardFloorMs = 0L).start()
        eventually { guardReads.get() == 1 }
        awaitCollector()
        emit(DaemonEventKind.HEALTH)
        eventually { guardReads.get() == 2 }
    }

    @Test
    fun `a netmap burst folds into one guard read after the floor`() {
        guardWatch.set(ExitNodeGuard.Watch.UNJUDGED)
        guardAfterReads += ExitNodeGuard.Watch.WATCHING
        // The floor is long next to the burst and the sleeps, as in the share sheet's test.
        refresher(guardFloorMs = 1_500L).start()
        eventually { guardReads.get() == 1 }
        awaitCollector()
        repeat(5) {
            emit(DaemonEventKind.NETMAP)
            Thread.sleep(20)
        }
        Thread.sleep(100)
        assertEquals("inside the floor nothing is read", 1, guardReads.get())
        eventually(timeoutMs = 5_000L) { guardReads.get() == 2 }
        Thread.sleep(600)
        assertEquals("the burst folded into one read", 2, guardReads.get())
    }

    @Test
    fun `an outage being timed is read again when the grace ends, with no event`() {
        guardWatch.set(ExitNodeGuard.Watch.UNJUDGED)
        // The read at the grace's end finds it past the grace: warned, then the long fallback.
        guardAfterReads += listOf(ExitNodeGuard.Watch.TIMING, ExitNodeGuard.Watch.WARNED)
        refresher(
            guardNextLook = { w, _ -> if (w == ExitNodeGuard.Watch.TIMING) 150L else 60_000L },
        ).start()
        eventually { guardReads.get() == 2 }
        Thread.sleep(400)
        assertEquals(2, guardReads.get())
    }

    @Test
    fun `a paused node is looked for on the fallback`() {
        guardWatch.set(ExitNodeGuard.Watch.PAUSED)
        refresher(guardNextLook = { w, _ -> if (w == ExitNodeGuard.Watch.PAUSED) 100L else null }).start()
        eventually { guardReads.get() >= 3 }
    }

    @Test
    fun `the guard does not look while the backend is not Running, and does once asked`() {
        guardGate.set(false)
        guardWatch.set(ExitNodeGuard.Watch.UNJUDGED)
        val r = refresher(guardFloorMs = 0L)
        r.start()
        awaitCollector()
        Thread.sleep(200)
        assertEquals(0, guardLooks.get())
        guardGate.set(true)
        assertTrue(r.requestExitNodeGuard())
        eventually { guardReads.get() == 1 }
    }

    @Test
    fun `the guard reads nothing once a teardown began or the refresher stopped`() {
        guardWatch.set(ExitNodeGuard.Watch.UNJUDGED)
        val r = refresher(guardFloorMs = 0L, guardNextLook = { _, _ -> 100L })
        r.start()
        eventually { guardReads.get() >= 1 }
        live.set(false)
        Thread.sleep(50)
        val after = guardReads.get()
        emit(DaemonEventKind.NETMAP)
        Thread.sleep(400)
        assertEquals(after, guardReads.get())
        r.stop()
        assertEquals(false, r.requestExitNodeGuard())
    }
}
