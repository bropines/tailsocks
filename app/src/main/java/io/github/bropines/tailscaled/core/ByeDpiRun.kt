package io.github.bropines.tailscaled.core

/** How the DPI bypass stands, for whoever shows it. */
sealed class ByeDpiStatus {
    object Stopped : ByeDpiStatus()

    /**
     * Started and not known to have failed. What runs is not always what was
     * typed: [ignored] are the tokens of the user's string that are not in
     * effect, and with [builtIn] the built-in profile runs instead of a string
     * that was empty or had nothing the allow-list and the core would take.
     */
    data class Active(
        val address: Pair<String, Int>,
        val builtIn: Boolean,
        val ignored: List<String>,
    ) : ByeDpiStatus()

    /**
     * The proxy is not there and nobody stopped it: nothing listens on the
     * address the daemon was given, so the control connection has no way out
     * rather than a way around the bypass. [code] is the core's.
     */
    data class Failed(val code: Int) : ByeDpiStatus()
}

/**
 * One start of the proxy without the thread, the log and the native call
 * around it: which arguments the core gets, what happens when it refuses
 * them, what its exit code means. Apart from ByeDpiProxy so that it runs in a
 * plain JVM test.
 */
internal class ByeDpiRun(
    private val address: Pair<String, Int>,
    private val noIpv6: Boolean,
    customFlags: String,
) {
    private val choice = ByeDpiFlags.choose(customFlags)

    /** The desync options of the latest attempt. */
    var flags: List<String> = choice.flags
        private set

    var status = ByeDpiStatus.Active(address, choice.builtIn, choice.ignored)
        private set

    /**
     * Runs the core through [start], which blocks for as long as the proxy
     * lives and returns its exit code. The allow-list checks which options
     * reach the core, not what their values mean, so the core can still refuse
     * a string: `-d split`, `-t 999`, an option left without its value. The
     * built-in profile then runs on the same address — the daemon was told
     * that address already — and [onRefused] hears which flags were refused,
     * with [status] saying so by then. Without this the proxy stayed down
     * behind a switch that was on. Returns the code of the last attempt.
     */
    fun execute(start: (Array<String>) -> Int, onRefused: (List<String>) -> Unit): Int {
        val code = start(ByeDpiFlags.arguments(address.first, address.second, noIpv6, flags))
        if (code != REFUSED || status.builtIn) return code

        val refused = flags
        flags = ByeDpiFlags.DEFAULT.split(" ")
        status = ByeDpiStatus.Active(address, builtIn = true, ignored = status.ignored + refused)
        onRefused(refused)
        return start(ByeDpiFlags.arguments(address.first, address.second, noIpv6, flags))
    }

    /**
     * What the end of the run means. A run that was [asked] to stop has
     * stopped, whatever its code; one that ended by itself has failed, also
     * with a clean code — the loop leaves like that when accept() breaks.
     */
    fun ended(code: Int, asked: Boolean): ByeDpiStatus =
        if (asked) ByeDpiStatus.Stopped else ByeDpiStatus.Failed(code)

    companion object {
        /** jniStartProxy's answer when the core did not take the arguments
         *  (RUN_BAD_ARGS in byedpi-jni.c). */
        const val REFUSED = -2
    }
}
