package io.github.bropines.tailscaled.core

/**
 * When Taildrive has to be set up again: once for every run of the daemon, as soon as that run is Running.
 *
 * The daemon is a process the bridge relaunches — a switch between proxy mode and TUN
 * (`SetNativeTun`, `ClearNativeTun`), a change of a setting the daemon only reads at start — and every relaunch begins
 * with `Stop()`, which tears down what the bridge holds for the old run: the Taildrive file server (whose
 * address the old daemon was told) and the local proxy. The new daemon has neither, so its shares
 * still list (they are in its preferences) but a read finds no file server and answers HTTP 500, until
 * Taildrive is applied once more.
 *
 * The service used to apply it once per `startTailscale()`, which a relaunch from the TUN service never
 * goes through. The bridge stamps each run with its start time (`getDaemonStartTime`); remembering the stamp
 * Taildrive was last applied for is all it takes to see that a run is a new one.
 */
object TaildriveRun {
    /** No run has had Taildrive applied. */
    const val NONE = -1L

    /**
     * Whether Taildrive is to be applied now: the daemon is up and its backend Running (the network map, with
     * `drive:share` in it, is in), and [run] is not the one it was last applied for ([appliedFor]).
     */
    fun due(running: Boolean, backendState: String, appliedFor: Long, run: Long): Boolean =
        running && backendState == "Running" && run != appliedFor
}
