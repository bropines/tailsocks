package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ByeDpiRunTest {

    private val address = Pair("127.9.8.7", 40123)
    private val builtIn = ByeDpiFlags.DEFAULT.split(" ")

    /** Stands in for the native core: records what it was started with and
     *  answers with the next of [codes]. */
    private class Core(vararg codes: Int) {
        private val answers = codes.toMutableList()
        val started = mutableListOf<List<String>>()
        val refused = mutableListOf<List<String>>()

        fun start(args: Array<String>): Int {
            started += args.toList()
            return answers.removeAt(0)
        }
    }

    private fun args(vararg flags: String, noIpv6: Boolean = false) =
        ByeDpiFlags.arguments(address.first, address.second, noIpv6, flags.toList()).toList()

    private fun ByeDpiRun.execute(core: Core) = execute(core::start) { core.refused += it }

    @Test
    fun flagsTheCoreTakesRunOnce() {
        val run = ByeDpiRun(address, noIpv6 = false, customFlags = "-s1 -d3+s")
        val core = Core(0)
        assertEquals(ByeDpiStatus.Active(address, builtIn = false, ignored = emptyList()), run.status)

        assertEquals(0, run.execute(core))

        assertEquals(listOf(args("-s1", "-d3+s")), core.started)
        assertEquals(emptyList<List<String>>(), core.refused)
        assertEquals(ByeDpiStatus.Active(address, builtIn = false, ignored = emptyList()), run.status)
        assertEquals(ByeDpiStatus.Stopped, run.ended(0, asked = true))
    }

    @Test
    fun flagsTheCoreRefusesGiveWayToTheBuiltInProfileOnTheSameAddress() {
        val run = ByeDpiRun(address, noIpv6 = false, customFlags = "-s 1 -d split -r")
        val core = Core(ByeDpiRun.REFUSED, 0)

        assertEquals(0, run.execute(core))

        assertEquals(listOf(args("-s", "1", "-d", "split", "-r"), args(*builtIn.toTypedArray())), core.started)
        assertEquals(listOf(listOf("-s", "1", "-d", "split", "-r")), core.refused)
        // The card says the proxy runs, and that it does not run what was typed.
        assertEquals(
            ByeDpiStatus.Active(address, builtIn = true, ignored = listOf("-s", "1", "-d", "split", "-r")),
            run.status
        )
    }

    @Test
    fun theStatusIsAlreadyRightWhenTheRefusalIsAnnounced() {
        val run = ByeDpiRun(address, noIpv6 = false, customFlags = "-t 999")
        var seen: ByeDpiStatus? = null
        val codes = mutableListOf(ByeDpiRun.REFUSED, 0)

        run.execute({ codes.removeAt(0) }) { seen = run.status }

        assertEquals(ByeDpiStatus.Active(address, builtIn = true, ignored = listOf("-t", "999")), seen)
    }

    @Test
    fun whatTheAllowListDroppedStaysReportedAfterARefusal() {
        val run = ByeDpiRun(address, noIpv6 = false, customFlags = "-i 0.0.0.0 -t 999")
        assertEquals(ByeDpiStatus.Active(address, builtIn = false, ignored = listOf("-i", "0.0.0.0")), run.status)

        run.execute(Core(ByeDpiRun.REFUSED, 0))

        assertEquals(
            ByeDpiStatus.Active(address, builtIn = true, ignored = listOf("-i", "0.0.0.0", "-t", "999")),
            run.status
        )
    }

    @Test
    fun forceIpv4HoldsForTheBuiltInProfileToo() {
        val run = ByeDpiRun(address, noIpv6 = true, customFlags = "-d split")
        val core = Core(ByeDpiRun.REFUSED, 0)

        run.execute(core)

        assertEquals(
            listOf(args("-d", "split", noIpv6 = true), args(*builtIn.toTypedArray(), noIpv6 = true)),
            core.started
        )
    }

    @Test
    fun theBuiltInProfileIsNotTriedTwice() {
        // An empty string starts with the built-in profile: nothing is left to fall back on.
        val empty = ByeDpiRun(address, noIpv6 = false, customFlags = "")
        val core = Core(ByeDpiRun.REFUSED)
        assertEquals(ByeDpiRun.REFUSED, empty.execute(core))
        assertEquals(listOf(args(*builtIn.toTypedArray())), core.started)
        assertEquals(emptyList<List<String>>(), core.refused)

        // The user's flags, then the built-in ones, and then it is a failure.
        val typed = ByeDpiRun(address, noIpv6 = false, customFlags = "-d split")
        val twice = Core(ByeDpiRun.REFUSED, ByeDpiRun.REFUSED)
        assertEquals(ByeDpiRun.REFUSED, typed.execute(twice))
        assertEquals(2, twice.started.size)
        assertEquals(ByeDpiStatus.Failed(ByeDpiRun.REFUSED), typed.ended(ByeDpiRun.REFUSED, asked = false))
    }

    @Test
    fun aStartThatFailsForAnotherReasonIsNotRetried() {
        // -1: no socket to listen on. Other flags would not change that.
        val run = ByeDpiRun(address, noIpv6 = false, customFlags = "-s1")
        val core = Core(-1)

        assertEquals(-1, run.execute(core))

        assertEquals(1, core.started.size)
        assertEquals(emptyList<List<String>>(), core.refused)
        assertEquals(ByeDpiStatus.Failed(-1), run.ended(-1, asked = false))
    }

    @Test
    fun aRunNobodyStoppedHasFailedWhateverItsCode() {
        val run = ByeDpiRun(address, noIpv6 = false, customFlags = "")
        // The loop left by itself, with a clean code: the proxy is gone all the same.
        assertEquals(ByeDpiStatus.Failed(0), run.ended(0, asked = false))
        assertEquals(ByeDpiStatus.Failed(-1), run.ended(-1, asked = false))
        // A stop is a stop, also one that overtook a failing start.
        assertEquals(ByeDpiStatus.Stopped, run.ended(0, asked = true))
        assertEquals(ByeDpiStatus.Stopped, run.ended(ByeDpiRun.REFUSED, asked = true))
    }
}
