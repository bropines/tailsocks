package io.github.bropines.tailscaled.core

import java.util.concurrent.Executor

/**
 * At most one start sequence in flight, and a START that arrives while one is
 * in flight joins it.
 *
 * A start command is decided on the main thread and carried out on another:
 * `onStartCommand` sees that the bridge is not attached (`Appctr.isRunning()`
 * is false) and launches a start sequence, which in Root Mode spends seconds in
 * su before the bridge attaches. Every START that arrived in that window used
 * to launch a sequence of its own, and two sequences both found no daemon
 * and both launched one. The second daemon's start-up cleanup then removed the
 * first one's addresses from `tailscale0`, the kernel dropped table 52's routes
 * with them, and the survivor ran with no tailnet address while the card said
 * Active. Measured on a rooted phone: a launch after a force-stop, where the
 * activity's own START and the watchdog's revive arrived within a second.
 *
 * The rule here is per service instance and per stop generation, which is the
 * question the start sequence itself asks (`stopGeneration` in the service):
 *  - a request with the same owner and generation as the sequence in flight or
 *    queued joins it — nothing new is launched;
 *  - a request after a stop (a newer generation) is admitted, but runs only
 *    after the superseded sequence has ended: the [executor] is serial, so a
 *    sequence that is still abandoning cannot tear down what the new one
 *    launches, and two sequences never probe and launch side by side;
 *  - once a sequence has ended, the next request starts again.
 *
 * The gate is process-wide (the service keeps one in its companion): a new
 * service instance created while the previous one's start is still unwinding
 * queues behind it rather than running beside it.
 */
internal class StartGate(private val executor: Executor) {
    private class Pending(val owner: Any, val generation: Int, val token: Long)

    /** The latest admitted sequence that has not ended yet, running or queued. */
    private var latest: Pending? = null
    /** Sequences admitted and not ended yet; more than one only across a stop. */
    private var outstanding = 0
    private var nextToken = 0L

    /**
     * Runs [body] as a new start sequence for [owner] at stop [generation], or
     * joins the one already admitted for the same pair. True when a sequence
     * was admitted, false when the request joined one. [onAdmitted] runs on the
     * caller's thread, only for an admitted request, before [body] is queued:
     * what must happen while the caller still holds its start window goes there.
     */
    fun request(owner: Any, generation: Int, onAdmitted: () -> Unit = {}, body: () -> Unit): Boolean {
        val token = admit(owner, generation) ?: return false
        try {
            onAdmitted()
            executor.execute {
                try {
                    body()
                } finally {
                    finish(token)
                }
            }
        } catch (t: Throwable) {
            // A rejected execution must not leave the gate shut for good.
            finish(token)
            throw t
        }
        return true
    }

    /** Whether any start sequence is running or waiting to run. */
    val inFlight: Boolean @Synchronized get() = outstanding > 0

    @Synchronized private fun admit(owner: Any, generation: Int): Long? {
        val l = latest
        if (l != null && l.owner === owner && l.generation == generation) return null
        val token = ++nextToken
        latest = Pending(owner, generation, token)
        outstanding++
        return token
    }

    @Synchronized private fun finish(token: Long) {
        outstanding--
        if (latest?.token == token) latest = null
    }
}
