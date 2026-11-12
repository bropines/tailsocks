package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTrafficTest {
    private fun peer(id: String, rx: Long, tx: Long) = PeerData(id = id, hostName = id, rxBytes = rx, txBytes = tx)

    private fun status(vararg peers: PeerData) = StatusResponse(peers = peers.associateBy { "nodekey:${it.id}" })

    @Test
    fun theFirstReadingMeasuresNothing() {
        val s = PeerTrafficSampler().add(1_000, status(peer("a", 5_000_000, 1_000_000)))
        assertFalse(s.measured)
        assertEquals(TrafficRate.ZERO, s.total)
        assertTrue(s.history.isEmpty())
    }

    @Test
    fun aRateIsTheDifferenceOverTheTimeThatPassed() {
        val sampler = PeerTrafficSampler()
        sampler.add(1_000, status(peer("a", 1_000, 500), peer("b", 0, 0)))
        val s = sampler.add(3_000, status(peer("a", 21_000, 4_500), peer("b", 2_000, 0)))
        assertTrue(s.measured)
        assertEquals(TrafficRate(10_000, 2_000), s.peers["a"])
        assertEquals(TrafficRate(1_000, 0), s.peers["b"])
        assertEquals(TrafficRate(11_000, 2_000), s.total)
        assertEquals(listOf(TrafficPoint(3_000, 11_000, 2_000)), s.history)
    }

    @Test
    fun anIrregularIntervalIsDividedByItsOwnLength() {
        val sampler = PeerTrafficSampler()
        sampler.add(0, status(peer("a", 0, 0)))
        assertEquals(1_000, sampler.add(500, status(peer("a", 500, 0))).total.rxBps)
        assertEquals(1_000, sampler.add(4_500, status(peer("a", 4_500, 0))).total.rxBps)
    }

    @Test
    fun aCounterThatWentDownIsANewBaselineNotANegativeRate() {
        val sampler = PeerTrafficSampler()
        sampler.add(0, status(peer("a", 9_000_000, 9_000_000), peer("b", 100, 100)))
        // The daemon restarted: a's counters start over while b's session carried on.
        val restarted = sampler.add(2_000, status(peer("a", 4_000, 0), peer("b", 2_100, 100)))
        assertNull(restarted.peers["a"])
        assertEquals(TrafficRate(1_000, 0), restarted.total)
        // And the reading after it is measured from the new baseline.
        val next = sampler.add(4_000, status(peer("a", 8_000, 2_000), peer("b", 2_100, 100)))
        assertEquals(TrafficRate(2_000, 1_000), next.peers["a"])
    }

    @Test
    fun oneCounterGoingDownVoidsTheOtherToo() {
        val sampler = PeerTrafficSampler()
        sampler.add(0, status(peer("a", 1_000, 9_000)))
        val s = sampler.add(1_000, status(peer("a", 50_000, 10)))
        assertEquals(TrafficRate.ZERO, s.total)
    }

    @Test
    fun aPeerSeenForTheFirstTimeBringsNoRateAndOneThatLeftTakesNoneAway() {
        val sampler = PeerTrafficSampler()
        sampler.add(0, status(peer("a", 1_000_000, 0)))
        // b appears with a long past; a is gone. Neither is traffic of these two seconds.
        val s = sampler.add(2_000, status(peer("b", 7_000_000, 7_000_000)))
        assertTrue(s.measured)
        assertEquals(TrafficRate.ZERO, s.total)
        assertTrue(s.peers.isEmpty())
        // a comes back: a first sight again, not a difference against its old counters.
        val back = sampler.add(4_000, status(peer("a", 3_000_000, 0), peer("b", 7_002_000, 7_000_000)))
        assertNull(back.peers["a"])
        assertEquals(TrafficRate(1_000, 0), back.total)
    }

    @Test
    fun readingsTooFarApartAreNotCompared() {
        val sampler = PeerTrafficSampler(maxGapMs = 10_000)
        sampler.add(0, status(peer("a", 0, 0)))
        sampler.add(2_000, status(peer("a", 2_000, 0)))
        // The screen was away for a minute and a gigabyte went by.
        val resumed = sampler.add(62_000, status(peer("a", 1_000_000_000, 0)))
        assertFalse(resumed.measured)
        assertEquals(TrafficRate.ZERO, resumed.total)
        assertEquals(1_000, sampler.add(64_000, status(peer("a", 1_000_002_000, 0))).total.rxBps)
    }

    @Test
    fun aClockSetBackStartsOver() {
        val sampler = PeerTrafficSampler()
        sampler.add(10_000, status(peer("a", 0, 0)))
        sampler.add(12_000, status(peer("a", 2_000, 0)))
        val s = sampler.add(11_000, status(peer("a", 9_000, 0)))
        assertFalse(s.measured)
        assertTrue(s.history.isEmpty())
    }

    @Test
    fun theHistoryIsBoundedByTimeAndByCount() {
        val byTime = PeerTrafficSampler(historyMs = 10_000)
        var last = TrafficSnapshot()
        for (i in 0..20) last = byTime.add(i * 2_000L, status(peer("a", i * 1_000L, 0)))
        assertEquals(listOf(30_000L, 32_000L, 34_000L, 36_000L, 38_000L, 40_000L), last.history.map { it.atMs })
        assertTrue(last.history.all { it.rxBps == 500L })

        val byCount = PeerTrafficSampler(maxPoints = 4)
        for (i in 0..20) last = byCount.add(i * 100L, status(peer("a", 0, 0)))
        assertEquals(4, last.history.size)
        assertEquals(2_000L, last.history.last().atMs)
    }

    @Test
    fun resetForgetsTheBaselineAndTheHistory() {
        val sampler = PeerTrafficSampler()
        sampler.add(0, status(peer("a", 0, 0)))
        sampler.add(2_000, status(peer("a", 2_000, 0)))
        sampler.reset()
        assertEquals(TrafficSnapshot(), sampler.snapshot)
        assertFalse(sampler.add(4_000, status(peer("a", 9_000, 0))).measured)
    }

    @Test
    fun aPeerWithoutAnIdIsMeasuredUnderItsNodeKey() {
        val sampler = PeerTrafficSampler()
        fun reading(rx: Long) = StatusResponse(peers = mapOf("nodekey:x" to PeerData(hostName = "x", rxBytes = rx)))
        sampler.add(0, reading(0))
        val s = sampler.add(1_000, reading(3_000))
        assertEquals(3_000, s.total.rxBps)
        assertEquals(TrafficRate(3_000, 0), s.peers["nodekey:x"])
        assertEquals(TrafficRate(3_000, 0), s.rateOf(PeerData(publicKey = "nodekey:x")))
    }

    @Test
    fun missingCountersAndAnEmptyStatusAreZero() {
        val sampler = PeerTrafficSampler()
        sampler.add(0, StatusResponse())
        val s = sampler.add(2_000, StatusResponse(peers = mapOf("k" to PeerData(id = "a"))))
        assertTrue(s.measured)
        assertEquals(TrafficRate.ZERO, s.total)
    }

    @Test
    fun topTalkersAreTheBusiestAndOnlyTheBusy() {
        val a = peer("a", 0, 0)
        val b = peer("b", 0, 0)
        val c = peer("c", 0, 0)
        val d = peer("d", 0, 0)
        val sampler = PeerTrafficSampler()
        sampler.add(0, status(a, b, c, d))
        val s = sampler.add(1_000, status(peer("a", 100, 0), peer("b", 5_000, 4_000), peer("c", 0, 0), peer("d", 0, 700)))
        assertEquals(listOf("b", "d", "a"), s.topTalkers(listOf(a, b, c, d), 3).map { it.first.id })
        assertEquals(listOf("b", "d"), s.topTalkers(listOf(a, b, c, d), 2).map { it.first.id })
        assertTrue(TrafficSnapshot().topTalkers(listOf(a, b), 3).isEmpty())
    }

    @Test
    fun theGraphCeilingIsRoundAndNeverBelowTheFloor() {
        assertEquals(10_000, graphCeiling(0))
        assertEquals(10_000, graphCeiling(9_999))
        assertEquals(10_000, graphCeiling(10_000))
        assertEquals(20_000, graphCeiling(10_001))
        assertEquals(50_000, graphCeiling(20_001))
        assertEquals(100_000, graphCeiling(50_001))
        assertEquals(5_000_000, graphCeiling(2_580_000))
        assertTrue(graphCeiling(Long.MAX_VALUE / 2) >= Long.MAX_VALUE / 2)
    }
}
