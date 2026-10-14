package io.github.bropines.tailscaled.core

/**
 * Whether a start adopts the root daemon it finds, waits for it, or replaces it.
 *
 * A root daemon outlives the app: the boot script starts one, and one keeps
 * running when the app dies or detaches. A start used to probe its socket once
 * and kill whatever did not answer — so a daemon that was still coming up was
 * killed and replaced, and one that had not opened its socket yet got a second
 * daemon started beside it. The socket is not there or refuses connections until
 * the daemon listens (`ipnserver` comes up after the network monitor and the
 * router cleanup), and LocalAPI requests are held until its backend exists.
 *
 * Now a daemon that exists is given time: the start probes again until the
 * daemon answers with a state, the process is gone, or [BUDGET_MS] has passed —
 * only then is it replaced, as before. A normal start does not wait: with no
 * daemon process for this socket it starts one at once, and a daemon that
 * answers the first probe is adopted at once.
 */
object RootAdoption {
    /** How long a daemon that exists but does not answer yet is waited for. */
    const val BUDGET_MS = 15_000L
    /** Pause between two probes of the socket. */
    const val PROBE_INTERVAL_MS = 400L
    /** How long one probe waits for the status before it counts as "not yet". */
    const val PROBE_TIMEOUT_MS = 2_000
    /** How often the wait asks root whether the process is still there. */
    const val PROCESS_RECHECK_MS = 2_000L

    /** What one look at the socket found. */
    enum class Probe {
        /** It answered with a backend state other than NoState. */
        READY,
        /** It accepted the connection but had no state to give yet. */
        STARTING,
        /** No socket file, or nobody listening on it. */
        ABSENT,
    }

    enum class Step { ADOPT, START_FRESH, WAIT, GIVE_UP }

    /**
     * The next step of a start that found [probe] on the socket, with the daemon
     * process for that socket [processAlive] and [elapsedMs] spent waiting so far.
     */
    fun next(probe: Probe, processAlive: Boolean, elapsedMs: Long, budgetMs: Long = BUDGET_MS): Step = when {
        probe == Probe.READY -> Step.ADOPT
        !processAlive -> Step.START_FRESH
        elapsedMs >= budgetMs -> Step.GIVE_UP
        else -> Step.WAIT
    }

    private val backendState = Regex("\"BackendState\"\\s*:\\s*\"([A-Za-z]*)\"")

    /**
     * What a status answer says. NoState is the backend before it has started;
     * the start used to treat it as "unconfigured" and kill the daemon, though
     * it is also what a daemon reports on its way up. A body without a state —
     * an error text, nothing at all — is a daemon that is not ready either.
     */
    fun classify(statusBody: String?): Probe {
        val state = statusBody?.let { backendState.find(it)?.groupValues?.get(1) }
        return if (state.isNullOrEmpty() || state == "NoState") Probe.STARTING else Probe.READY
    }
}
