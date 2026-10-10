package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The add button's show is a function of time; these pin down its rhythm, that the picture,
 * the letters and the motor keep to one clock, the limits on flashing, and the tap guard.
 */
class PawTimelineTest {

    private val cycle = PawShow.CYCLE_MS

    private fun at(k: Int, local: Float) = PawShow.INTRO_MS + k * cycle + local

    @Test
    fun aCycleIsSlowRunPauseFastRunPause() {
        assertEquals(0f, PawShow.RUN_START[0])
        assertEquals(1560f, PawShow.RUN_END[0])
        assertEquals(2120f, PawShow.RUN_START[1])
        assertEquals(2820f, PawShow.RUN_END[1])
        assertEquals(3400f, cycle)
        for (i in 0 until PawShow.LETTERS) {
            assertTrue("the fast run is faster", PawShow.length(1, i) < PawShow.length(0, i))
        }
        // U and A carry, the two I's between them are quick, the last one trails.
        for (r in 0..1) {
            for (i in 1..2) assertTrue(PawShow.length(r, i) < PawShow.length(r, 0))
            assertTrue(PawShow.length(r, 4) > PawShow.length(r, 1))
        }
        for (r in 0..1) {
            val a = PawShow.length(r, 3)
            for (i in 0 until PawShow.LETTERS) assertTrue("A is the longest", PawShow.length(r, i) <= a)
        }
    }

    @Test
    fun theCatTurnsOnlyWhileALetterIsSungAndStandsDeadStillBetween() {
        for (k in 0..7) {
            var t = 0f
            while (t < cycle) {
                val s = PawShow.sceneAt(at(k, t))
                if (s.run < 0) {
                    assertEquals("still in the pause at $k/$t", 0f, s.angle)
                    assertEquals(0f, s.speed)
                    assertEquals(0f, s.lift)
                    assertEquals(-1, s.letter)
                    assertEquals(PawShow.MOUTH_SHUT, s.mouth)
                } else {
                    assertTrue("a letter is sung while it turns, $k/$t", s.letter >= 0)
                }
                t += 1f
            }
        }
    }

    @Test
    fun eachRunEndsFacingFrontAfterWholeTurns() {
        for (k in 0..5) for (r in 0..1) {
            val turns = PawShow.turns(r, PawShow.level(k))
            val end = PawShow.sceneAt(at(k, PawShow.RUN_END[r] - 0.01f))
            assertEquals(360f * turns, end.angle, 0.5f)
            // It overshoots the front a little and rocks back onto it.
            var most = 0f
            var t = PawShow.RUN_START[r]
            while (t < PawShow.RUN_END[r]) {
                most = maxOf(most, PawShow.sceneAt(at(k, t)).angle)
                t += 0.5f
            }
            val over = most - 360f * turns
            assertTrue("overshoot $over", over in 3f..25f)
        }
    }

    @Test
    fun theSpinIsSmooth() {
        assertEquals(0f, PawShow.spin(0f))
        assertEquals(1f, PawShow.spin(1f), 1e-5f)
        // The rate is the slope, with no jump at the joins.
        var r = 0.001f
        while (r < 0.999f) {
            val slope = (PawShow.spin(r + 1e-4f) - PawShow.spin(r - 1e-4f)) / 2e-4f
            assertEquals("rate at $r", PawShow.spinRate(r), slope, 0.02f)
            r += 0.001f
        }
    }

    @Test
    fun lettersFollowTheTablesAndNeverDrift() {
        // Including a cycle an hour in.
        for (k in listOf(0, 1, 2, 3, 7, 1000)) {
            for (c in 0 until PawShow.CUES) {
                val onset = PawShow.cueAt(k, c)
                val s = PawShow.sceneAt(onset + 2f)
                assertEquals("letter of cue $c, cycle $k", c % PawShow.LETTERS, s.letter)
                assertEquals(c / PawShow.LETTERS, s.run)
                assertEquals(s.letter, s.stampAge.indexOfLast { it >= 0f })
                val before = PawShow.sceneAt(onset - 2f)
                if (c % PawShow.LETTERS > 0) assertEquals(c % PawShow.LETTERS - 1, before.letter)
                else assertEquals(-1, before.letter)
            }
        }
    }

    @Test
    fun everyLetterBuzzesOnceInOrderAtAnyFrameRate() {
        for (hz in listOf(30f, 60f, 90f, 120f, 144f)) {
            val random = Random(hz.toInt())
            val fired = mutableListOf<Pair<Float, Int>>()
            var previous = -1f
            var t = 0f
            val end = at(12, 0f)
            while (t < end) {
                val cue = PawShow.lastCue(previous, t)
                if (cue >= 0) fired += t to cue
                previous = t
                // Frames come late now and then.
                t += 1000f / hz * (if (random.nextInt(20) == 0) 1.6f else 1f)
            }
            assertEquals("$hz Hz", 12 * PawShow.CUES, fired.size)
            fired.forEachIndexed { n, (time, cue) ->
                assertEquals(n % PawShow.CUES, cue)
                val onset = PawShow.cueAt(n / PawShow.CUES, cue)
                assertTrue("$hz Hz: cue $cue at $time for $onset", time >= onset && time - onset < 1000f / hz * 1.6f + 0.01f)
                // The picture at that frame shows the letter the motor plays.
                assertEquals(cue % PawShow.LETTERS, PawShow.sceneAt(time).letter)
            }
        }
    }

    @Test
    fun nothingBuzzesInTheIntroOrInAPause() {
        assertEquals(-1, PawShow.lastCue(-1f, PawShow.INTRO_MS - 0.1f))
        for (k in 0..3) for (r in 0..1) {
            val pauseStart = at(k, PawShow.RUN_END[r])
            val pauseEnd = at(k, if (r == 0) PawShow.RUN_START[1] else cycle)
            assertEquals(-1, PawShow.lastCue(pauseStart, pauseEnd - 0.1f))
        }
    }

    @Test
    fun buzzesAreShortPulsesNotADrone() {
        var on = 0
        for (c in 0 until PawShow.CUES) {
            val length = PawShow.length(c / PawShow.LETTERS, c % PawShow.LETTERS)
            val buzz = PawShow.BUZZ_MS[c]
            assertTrue("cue $c leaves a gap", length - buzz >= 30f)
            assertTrue("cue $c is felt", buzz >= 30)
            assertTrue(PawShow.BUZZ_LEVEL[c] in 1..255)
            on += buzz
        }
        for (r in 0..1) {
            val a = r * PawShow.LETTERS + 3
            for (i in 0 until PawShow.LETTERS) {
                assertTrue("A hits hardest", PawShow.BUZZ_LEVEL[r * PawShow.LETTERS + i] <= PawShow.BUZZ_LEVEL[a])
                assertTrue("A buzzes longest", PawShow.BUZZ_MS[r * PawShow.LETTERS + i] <= PawShow.BUZZ_MS[a])
            }
        }
        assertTrue("the motor rests most of the cycle", on / cycle < 0.2f)
    }

    @Test
    fun theColourSwellsTwiceACycleAtMost() {
        // Count the swells: rises that end in a peak.
        for (k in 0..6) {
            var peaks = 0
            var rising = false
            var last = PawShow.sceneAt(at(k, 0f)).pulse
            var t = 1f
            while (t <= cycle) {
                val p = PawShow.sceneAt(at(k, t)).pulse
                assertTrue(p in 0f..1f)
                if (p > last + 1e-6f) rising = true
                if (p < last - 1e-6f && rising) {
                    peaks++
                    rising = false
                }
                last = p
                t += 1f
            }
            assertTrue("cycle $k: $peaks swells", peaks <= 2)
            // Two swells in 3.4 s is well under the three flashes a second that are allowed.
            assertTrue(peaks / (cycle / 1000f) < 1f)
        }
        // And the swell is too soft to count as a flash at all: under a tenth of luminance,
        // for the light presets, where the surface and its container differ the most.
        val presets = listOf(
            0xFFFEF7FF to 0xFFEADDFF, 0xFFFFFBFD to 0xFFECDCFF, 0xFFF6FBF7 to 0xFF7BF8D3,
            0xFFFDFCFF to 0xFFD4E3FF, 0xFFFFFBF8 to 0xFFFFDDB3, 0xFFF9F9FB to 0xFFE2E2E6,
            0xFFF9FAFB to 0xFFDCE1FF
        )
        for ((surface, tint) in presets) {
            val swollen = mix(surface.toInt(), tint.toInt(), 0.2f)
            assertTrue(abs(luminance(surface.toInt()) - luminance(swollen)) < 0.1f)
        }
    }

    @Test
    fun theShowEscalates() {
        val first = PawShow.sceneAt(at(0, PawShow.onset(0, 3) + 100f))
        assertEquals(0f, first.whirl)
        assertEquals(0f, first.pulse)
        assertEquals(0f, first.rays)
        assertFalse(first.choirIn.any { it })

        val second = PawShow.sceneAt(at(1, PawShow.onset(0, 3) + 100f))
        assertTrue(second.whirl > 0f && second.pulse > 0f && second.lift > 0f)

        val third = PawShow.sceneAt(at(2, PawShow.onset(0, 3) + 100f))
        assertTrue(third.rays > 0f)
        assertTrue(third.choirIn[0] && third.choirIn[1])
        assertFalse(third.choirIn[2])

        val top = PawShow.sceneAt(at(3, PawShow.onset(0, 3) + 100f))
        assertTrue(top.choirIn.all { it })
        assertTrue(top.confettiAge >= 0f)
        assertTrue(top.echoAge.any { it >= 0f })
    }

    @Test
    fun theChoirSingsInStepAndOneOfThemSometimesTurnsTheWrongWay() {
        val culprits = mutableSetOf<Int>()
        for (k in 3..18) {
            val culprit = PawShow.culprit(k)
            assertEquals(k % 3 == 0, culprit >= 0)
            if (culprit >= 0) culprits += culprit
            val s = PawShow.sceneAt(at(k, PawShow.onset(0, 2) + 50f))
            for (j in 0 until PawShow.CHOIR) {
                assertEquals(if (j == culprit) -1f else 1f, s.choirDir[j])
                assertEquals(s.lift, s.choirLift[j])
            }
            // Facing front again by the pause, whichever way it went; then it looks round.
            val pause = PawShow.sceneAt(at(k, PawShow.RUN_END[1] + 150f))
            assertEquals(0f, pause.angle)
            if (culprit >= 0) {
                assertTrue(abs(pause.choirTurn[culprit]) > 10f)
                assertTrue(pause.choirGlance.count { it != 0f } >= 3)
            }
        }
        assertEquals("each of them gets a turn", PawShow.CHOIR, culprits.size)
        // Nobody misbehaves before the whole choir is there.
        assertEquals(-1, PawShow.culprit(0))
        assertTrue(PawShow.sceneAt(at(3, 0f)).choirIn.all { it })
    }

    @Test
    fun withoutMotionNothingMovesButTheSongGoesOn() {
        var sung = 0
        var t = 0f
        while (t < at(5, 0f)) {
            val s = PawShow.sceneAt(t, calm = true)
            assertEquals(0f, s.angle)
            assertEquals(0f, s.lift)
            assertEquals(0f, s.squash)
            assertEquals(0f, s.zoom)
            assertEquals(0f, s.roll)
            assertEquals(0f, s.shakeX)
            assertEquals(0f, s.pulse)
            assertEquals(0f, s.whirl)
            assertEquals(0f, s.rays)
            assertEquals(-1f, s.confettiAge)
            assertTrue(s.echoAge.all { it < 0f })
            assertTrue(s.choirLift.all { it == 0f })
            assertTrue(s.choirTurn.all { it == 0f })
            if (s.letter >= 0) sung++
            t += 5f
        }
        assertTrue(sung > 0)
    }

    @Test
    fun aSceneDependsOnTimeAlone() {
        val reused = PawScene()
        val times = floatArrayOf(at(3, 900f), 120f, at(0, 2500f), at(6, 3100f), at(2, 3050f))
        for (t in times) {
            PawShow.sceneAt(at(4, 2400f), out = reused)
            PawShow.sceneAt(t, out = reused)
            val fresh = PawShow.sceneAt(t)
            assertEquals(fresh.angle, reused.angle)
            assertEquals(fresh.letter, reused.letter)
            assertEquals(fresh.squash, reused.squash)
            assertEquals(fresh.zoom, reused.zoom)
            assertEquals(fresh.dustAge, reused.dustAge)
            assertEquals(fresh.confettiAge, reused.confettiAge)
            assertTrue(fresh.stampAge.contentEquals(reused.stampAge))
            assertTrue(fresh.echoAge.contentEquals(reused.echoAge))
            assertTrue(fresh.choirIn.contentEquals(reused.choirIn))
            assertTrue(fresh.choirGlance.contentEquals(reused.choirGlance))
            assertTrue(fresh.choirTurn.contentEquals(reused.choirTurn))
        }
    }

    @Test
    fun tapsThatRunOnPastTheTenthDoNotClose() {
        val guard = TapGuard(openedAt = 10_000L)
        // Taps 11, 12, 13… in the same rhythm, and a slower straggler in the intro.
        for (now in longArrayOf(10_150, 10_300, 10_480, 10_650, 10_990)) assertFalse(guard.tap(now))
        // A calm tap once the cat is singing closes it.
        assertTrue(guard.tap(11_800))
    }

    @Test
    fun aCalmTapDuringTheIntroDoesNotClose() {
        val guard = TapGuard(openedAt = 0L)
        assertFalse(guard.tap(800))
        assertFalse(guard.tap(1_200))
        assertTrue(guard.tap(2_000))
    }

    @Test
    fun theComboBuildsUp() {
        for (combo in 3 until PawHype.GOAL - 1) {
            assertTrue(PawHype.badgeScale(combo + 1, 1000f) > PawHype.badgeScale(combo, 1000f))
        }
        assertEquals(0f, PawHype.badgeTilt(4, 123f))
        assertTrue(abs(PawHype.badgeTilt(9, 24f)) > 5f)
        assertEquals(0f, PawHype.charge(8, 70f))
        assertTrue(PawHype.charge(9, 70f) > 0f)
        assertTrue(PawHype.pawScale(0f) < 0.8f)
        assertEquals(1f, PawHype.pawScale(2000f), 0.01f)
    }

    @Test
    fun everyFifthLaunchSaysNo() {
        assertEquals(listOf(5, 10, 15), (0..16).filter { PawHype.refuses(it) })
        // The stamp lands big and crooked, and is still by the time the link opens.
        assertTrue(PawHype.noScale(0f) > 2f)
        assertEquals(1f, PawHype.noScale(PawHype.NO_HOLD_MS), 0.01f)
        assertEquals(-7f, PawHype.noTilt(PawHype.NO_HOLD_MS), 0.05f)
        assertTrue(PawHype.NO_LINK.startsWith("https://"))
    }
}
