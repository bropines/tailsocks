package io.github.bropines.tailscaled.core

/**
 * One refresh tick at a time, and no request lost to that rule.
 *
 * The tick runs on a thread of its own and can take seconds (the MagicDNS
 * health probe alone waits up to 3 s + 6 s after a network change). A request
 * for a pass that arrives meanwhile — a routing re-check after another VPN
 * came up, Doze ending — used to be dropped, and the running tick may already
 * have been past the routing apply, so the change waited for the next
 * scheduled tick, 15 s later. Now such a request is remembered and the next
 * tick starts as soon as the running one ends. Requests that arrive during
 * one tick fold into one extra tick.
 */
internal class TickGate {
    private var running = false
    private var requested = false

    /** True when a tick may start now; otherwise the request is kept for [end]. */
    @Synchronized fun tryBegin(): Boolean {
        if (running) {
            requested = true
            return false
        }
        running = true
        requested = false
        return true
    }

    /** Ends the running tick; true when a request arrived meanwhile and the next one should start at once. */
    @Synchronized fun end(): Boolean {
        running = false
        val again = requested
        requested = false
        return again
    }
}
