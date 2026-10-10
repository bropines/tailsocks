package io.github.bropines.tailscaled.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/*
 * The show behind TailCat's paw as a pure function of time. One clock decides how far the
 * cat has turned, which letter is sung, what the camera, the letters and the effects do, and
 * when the motor buzzes, so none of them can drift from the others. Nothing here touches
 * Android: the stage draws a [PawScene], the overlay buzzes on [PawShow.lastCue], and the JVM
 * tests read the same tables.
 *
 * The rhythm: U I I A I slowly, a pause, U I I A I fast, a pause, round again. The cat turns
 * through a run and stands dead still, facing you, in a pause. Each cycle adds something:
 * speed lines, a hop and a colour swell; then rays and two backing singers; then three more
 * on a higher riser, letters flying out of the cat and confetti on the A.
 */

internal object PawShow {
    const val WORD = "UIIAI"
    const val LETTERS = 5

    /** The circle opens, the cat drops in, lands, and gives you a slow blink. */
    const val INTRO_MS = 1000f
    private const val REVEAL_MS = 300f
    private const val DROP_MS = 300f
    private const val LAND_MS = 420f

    /** Each letter's length, ms: the slow run, then the fast one. U and A carry, the I's are quick. */
    private val LENGTH = arrayOf(
        floatArrayOf(360f, 180f, 180f, 480f, 360f),
        floatArrayOf(160f, 80f, 80f, 220f, 160f)
    )
    private val PAUSE_AFTER = floatArrayOf(560f, 580f)

    val RUN_START = FloatArray(2)
    val RUN_END = FloatArray(2)
    private val ONSET = Array(2) { FloatArray(LETTERS) }

    /** A whole cycle: slow run, pause, fast run, pause. */
    val CYCLE_MS: Float

    init {
        var at = 0f
        for (r in 0..1) {
            RUN_START[r] = at
            for (i in 0 until LETTERS) {
                ONSET[r][i] = at
                at += LENGTH[r][i]
            }
            RUN_END[r] = at
            at += PAUSE_AFTER[r]
        }
        CYCLE_MS = at
    }

    fun onset(run: Int, letter: Int) = ONSET[run][letter]
    fun length(run: Int, letter: Int) = LENGTH[run][letter]

    // ---------------------------------------------------------------------------------------
    // Haptics: one pulse per letter, cue c = run * 5 + letter. Each is a fraction of its letter,
    // as long as the syllable sounds, so the motor is still most of the time.

    const val CUES = 2 * LETTERS
    val BUZZ_MS = intArrayOf(70, 32, 32, 120, 55, 45, 30, 30, 70, 40)
    val BUZZ_LEVEL = intArrayOf(170, 120, 120, 255, 150, 180, 130, 130, 255, 160)

    /** When cue [cue] of cycle [cycle] fires, ms from the start of the show. */
    fun cueAt(cycle: Int, cue: Int) = INTRO_MS + cycle * CYCLE_MS + ONSET[cue / LETTERS][cue % LETTERS]

    /**
     * The latest cue in (from, to], or −1. A frame that spans two onsets buzzes only for the
     * later one: a motor told twice in the same instant plays the second anyway.
     */
    fun lastCue(from: Float, to: Float): Int {
        if (to < INTRO_MS || to <= from) return -1
        val last = floor((to - INTRO_MS) / CYCLE_MS).toInt()
        val first = maxOf(0, floor((maxOf(from, INTRO_MS) - INTRO_MS) / CYCLE_MS).toInt())
        for (k in last downTo maxOf(first, last - 1)) {
            for (c in CUES - 1 downTo 0) {
                val at = cueAt(k, c)
                if (at <= to && at > from) return c
            }
        }
        return -1
    }

    // ---------------------------------------------------------------------------------------
    // Escalation.

    const val TOP_LEVEL = 3
    fun level(cycle: Int) = cycle.coerceIn(0, TOP_LEVEL)

    private val SLOW_TURNS = intArrayOf(2, 2, 3, 3)
    private const val FAST_TURNS = 3
    fun turns(run: Int, level: Int) = if (run == 0) SLOW_TURNS[level] else FAST_TURNS

    /** Two backing singers land in the pause after the fast run of cycle 1, three more a cycle later. */
    const val CHOIR = 5
    private const val CHOIR_LAND_LOCAL = 3000f
    val CHOIR_LAND = floatArrayOf(
        INTRO_MS + 1 * CYCLE_MS + CHOIR_LAND_LOCAL,
        INTRO_MS + 1 * CYCLE_MS + CHOIR_LAND_LOCAL + 70f,
        INTRO_MS + 2 * CYCLE_MS + CHOIR_LAND_LOCAL,
        INTRO_MS + 2 * CYCLE_MS + CHOIR_LAND_LOCAL + 70f,
        INTRO_MS + 2 * CYCLE_MS + CHOIR_LAND_LOCAL + 140f
    )

    /**
     * Every third cycle from the fourth, one of the choir turns the wrong way round: the back
     * row's middle cat first, then each of the others in turn. −1 when nobody does.
     */
    fun culprit(cycle: Int): Int =
        if (cycle >= 3 && cycle % 3 == 0) CULPRITS[(cycle / 3 - 1) % CULPRITS.size] else -1

    private val CULPRITS = intArrayOf(3, 1, 4, 0, 2)

    /** Where each of the choir stands across the stage, −1 left … 1 right: who is beside whom. */
    private val PLACE = floatArrayOf(-0.6f, 0.6f, -0.55f, 0f, 0.55f)

    /** How high a falling cat starts, in cat units: above the top of the screen. */
    const val FALL_FROM = 900f
    private const val HOP = 24f

    // ---------------------------------------------------------------------------------------
    // The scene.

    /** Fills [out] with what the show looks like [t] ms after it opened. */
    fun sceneAt(t: Float, calm: Boolean = false, out: PawScene = PawScene()): PawScene {
        out.reset()
        out.t = t
        out.calm = calm
        out.reveal = easeOutCubic(t / REVEAL_MS)
        if (t < INTRO_MS) intro(t, out) else cycle(t, out)
        choir(t, out)
        if (calm) out.still()
        return out
    }

    private fun intro(t: Float, s: PawScene) {
        s.cycle = -1
        s.local = t
        val drop = (t - (LAND_MS - DROP_MS)) / DROP_MS
        s.lift = when {
            drop < 0f -> FALL_FROM
            drop < 1f -> FALL_FROM * (1f - drop * drop)
            else -> 0f
        }
        val landed = t - LAND_MS
        if (landed >= 0f) {
            s.squash = 0.16f * bop(landed)
            s.zoom = 0.03f * punch(landed)
            s.dustAge = landed
        }
        // The slow blink cats give someone they trust.
        s.blink = blinkAt(t - 620f, close = 80f, hold = 100f, open = 100f)
    }

    private fun cycle(t: Float, s: PawScene) {
        val since = t - INTRO_MS
        val k = floor(since / CYCLE_MS).toInt()
        val local = since - k * CYCLE_MS
        val level = level(k)
        s.cycle = k
        s.level = level
        s.local = local

        val run = when {
            local < RUN_END[0] -> 0
            local >= RUN_START[1] && local < RUN_END[1] -> 1
            else -> -1
        }
        val shown = if (local < RUN_START[1]) 0 else 1
        s.run = run
        s.shownRun = shown
        for (i in 0 until LETTERS) {
            val age = local - ONSET[shown][i]
            s.stampAge[i] = if (age >= 0f) age else -1f
            if (run >= 0 && age >= 0f && age < LENGTH[run][i]) {
                s.letter = i
                s.letterAge = age
            }
        }

        // The turn.
        if (run >= 0) {
            val length = RUN_END[run] - RUN_START[run]
            val r = (local - RUN_START[run]) / length
            val turns = 360f * turns(run, level)
            s.angle = turns * spin(r)
            s.speed = turns * spinRate(r) / length * 1000f
        }

        // The mouth sings the letter.
        if (s.letter >= 0) {
            val length = LENGTH[run][s.letter]
            s.mouth = when (WORD[s.letter]) {
                'U' -> MOUTH_U
                'A' -> MOUTH_A
                else -> MOUTH_I
            }
            val a = s.letterAge
            s.mouthOpen = smooth(a / min(45f, length * 0.3f)) * (1f - smooth((a - length * 0.72f) / (length * 0.28f)))
        }

        // Accents: every onset squashes the cat and punches the camera; a stop lands both.
        val strength = LEVEL_STRENGTH[level]
        var squash = 0f
        var zoom = 0f
        var roll = 0f
        for (i in 0 until LETTERS) {
            val age = s.stampAge[i]
            if (age < 0f) continue
            squash += BOP[i] * strength * bop(age)
            zoom += PUNCH[i] * strength * punch(age)
            if (level >= 3) roll += (if (i % 2 == 0) 1.3f else -1.3f) * punch(age)
        }
        val stopped = local - RUN_END[shown]
        if (stopped >= 0f) {
            squash += 0.07f * bop(stopped)
            zoom += 0.02f * strength * punch(stopped)
            s.dustAge = stopped
        }

        // From the second cycle the cat hops on the A and lands before the last I.
        if (level >= 1) {
            val a = local - ONSET[shown][3]
            val length = LENGTH[shown][3]
            val hop = if (shown == 0) HOP else HOP * 0.6f
            if (a >= 0f && a < length && run == shown) {
                s.lift = hop * sin(PI.toFloat() * a / length)
            }
            val landed = a - length
            if (landed >= 0f) {
                squash += 0.06f * bop(landed)
                if (stopped < 0f) s.dustAge = landed
            }
        }
        s.squash = squash
        s.zoom = zoom
        s.roll = roll

        // A hard shake on the A, at the top level only.
        if (level >= 3) {
            val a = local - ONSET[shown][3]
            if (a >= 0f) {
                val e = exp(-a / 150f) * 4f
                s.shakeX = e * sin(a * 0.19f)
                s.shakeY = e * cos(a * 0.23f)
            }
        }

        // The colour swells once a run, towards the A, and ebbs through the pause: far slower
        // than the 3 Hz a flash may not exceed, and soft besides.
        if (level >= 1) {
            val rise = smooth((local - RUN_START[shown]) / (ONSET[shown][3] - RUN_START[shown]))
            val ebb = if (stopped >= 0f) 1f - smooth(stopped / PAUSE_AFTER[shown]) else 1f
            s.pulse = rise * ebb * PULSE_LEVEL[level]
        }
        if (level >= 1) s.whirl = (abs(s.speed) / 1300f).coerceAtMost(1f)
        if (level >= 2) s.rays = if (k == 2) smooth(local / 600f) else 1f
        if (level >= 3) {
            for (i in 0 until LETTERS) {
                val age = s.stampAge[i]
                s.echoAge[i] = if (age in 0f..ECHO_MS) age else -1f
            }
        }
        if (level >= 3) {
            val a = local - ONSET[shown][3]
            if (a in 0f..CONFETTI_MS) {
                s.confettiAge = a
                s.confettiSeed = k * 2 + shown
            }
        }

        // A blink in one of the pauses, and the stare.
        val blinkRun = k % 2
        s.blink = blinkAt(local - RUN_END[blinkRun] - 230f, close = 50f, hold = 30f, open = 60f)
    }

    private fun choir(t: Float, s: PawScene) {
        val k = s.cycle
        for (j in 0 until CHOIR) {
            val land = CHOIR_LAND[j]
            val fall = (t - (land - DROP_MS)) / DROP_MS
            if (fall < 0f) {
                s.choirIn[j] = false
                continue
            }
            s.choirIn[j] = true
            val landed = t - land
            if (landed < 0f) {
                s.choirLift[j] = FALL_FROM * (1f - fall * fall)
                s.choirSquash[j] = 0f
                s.choirDir[j] = 1f
                continue
            }
            s.choirLift[j] = s.lift
            s.choirSquash[j] = s.squash + 0.16f * bop(landed)
        }
        val culprit = culprit(k)
        if (culprit < 0) return
        s.choirDir[culprit] = -1f
        // In the last pause the culprit looks round, and the rest side-eye it — those not
        // standing right above or below it, who cannot look up or down.
        val after = s.local - RUN_END[1]
        if (after < 0f) return
        val u = after / PAUSE_AFTER[1]
        val env = smooth(u / 0.15f) * (1f - smooth((u - 0.85f) / 0.15f))
        for (j in 0 until CHOIR) {
            val toward = PLACE[culprit] - PLACE[j]
            if (j != culprit && abs(toward) > 0.15f) s.choirGlance[j] = if (toward > 0f) env else -env
        }
        val look = sin(2f * PI.toFloat() * u) * env
        s.choirGlance[culprit] = look
        s.choirTurn[culprit] = 28f * look
    }

    // ---------------------------------------------------------------------------------------
    // Shapes in time.

    const val MOUTH_SHUT = 0
    const val MOUTH_U = 1
    const val MOUTH_I = 2
    const val MOUTH_A = 3

    const val ECHO_MS = 460f
    const val CONFETTI_MS = 1100f

    /** How hard each letter lands: U carries, the I's tick, the A hits. */
    private val BOP = floatArrayOf(0.06f, 0.035f, 0.035f, 0.09f, 0.05f)
    private val PUNCH = floatArrayOf(0.025f, 0.014f, 0.014f, 0.045f, 0.02f)
    private val LEVEL_STRENGTH = floatArrayOf(0.6f, 0.8f, 1f, 1.15f)
    private val PULSE_LEVEL = floatArrayOf(0f, 0.6f, 0.85f, 1f)

    /** Accelerates over [ACCEL] of a run, cruises, and brakes over [BRAKE] past the front and back. */
    private const val ACCEL = 0.1f
    private const val BRAKE = 0.16f
    private const val CRUISE = 1f / (1f - ACCEL / 2f - BRAKE + BRAKE / 8f)

    /**
     * How far through its turns a run is at [r] (0…1), 0…1: a quick start, an even spin, and a
     * stop that overshoots the front a little and rocks back onto it exactly as the run ends.
     */
    fun spin(r: Float): Float {
        val v = CRUISE
        return when {
            r <= 0f -> 0f
            r < ACCEL -> v * r * r / (2f * ACCEL)
            r < 1f - BRAKE -> v * ACCEL / 2f + v * (r - ACCEL)
            r < 1f -> {
                val s = (r - (1f - BRAKE)) / BRAKE
                val m = v * BRAKE
                v * ACCEL / 2f + v * (1f - BRAKE - ACCEL) +
                    (s * s * s - 2f * s * s + s) * m + (-2f * s * s * s + 3f * s * s) * m / 8f
            }
            else -> 1f
        }
    }

    /** d[spin]/dr. */
    fun spinRate(r: Float): Float {
        val v = CRUISE
        return when {
            r <= 0f || r >= 1f -> 0f
            r < ACCEL -> v * r / ACCEL
            r < 1f - BRAKE -> v
            else -> {
                val s = (r - (1f - BRAKE)) / BRAKE
                val m = v * BRAKE
                ((3f * s * s - 4f * s + 1f) * m + (-6f * s * s + 6f * s) * m / 8f) / BRAKE
            }
        }
    }

    /** A squash that comes on fast, rebounds into a stretch and settles. */
    fun bop(age: Float): Float = when {
        age < 0f || age > 700f -> 0f
        age < 22f -> age / 22f
        else -> exp(-(age - 22f) / 85f) * cos(2f * PI.toFloat() * (age - 22f) / 230f)
    }

    /** A camera punch: in at once, out slowly. */
    fun punch(age: Float): Float = when {
        age < 0f || age > 800f -> 0f
        age < 16f -> age / 16f
        else -> exp(-(age - 16f) / 120f)
    }

    private fun blinkAt(a: Float, close: Float, hold: Float, open: Float): Float = when {
        a < 0f -> 0f
        a < close -> a / close
        a < close + hold -> 1f
        a < close + hold + open -> 1f - (a - close - hold) / open
        else -> 0f
    }

    fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    fun easeOutCubic(x: Float): Float {
        val c = 1f - x.coerceIn(0f, 1f)
        return 1f - c * c * c
    }

    /** A repeatable random number in 0…1 for item [i] of [seed]. */
    fun noise(seed: Int, i: Int): Float {
        var h = seed * 0x27d4eb2d + i * 0x165667b1 + 0x5bd1e995
        h = (h xor (h ushr 15)) * 0x2c1b3c6d
        h = (h xor (h ushr 12)) * 0x297a2d39
        h = h xor (h ushr 15)
        return (h ushr 8) / 16777216f
    }
}

/** What the show looks like at one instant; filled by [PawShow.sceneAt], reused frame to frame. */
internal class PawScene {
    var t = 0f
    var calm = false
    /** −1 in the intro. */
    var cycle = -1
    var level = 0
    /** ms into the cycle, or into the intro. */
    var local = 0f
    /** 0…1, how far the circle the show opens in has grown. */
    var reveal = 1f

    /** The run being sung, −1 in a pause or the intro. */
    var run = -1
    /** The run whose letters are on screen. */
    var shownRun = -1
    /** The letter being sung, −1 when none is. */
    var letter = -1
    var letterAge = 0f
    /** ms since each letter of [shownRun] was sung, −1 for one still to come. */
    val stampAge = FloatArray(PawShow.LETTERS)
    /** ms since each letter flew out of the cat, −1 for none in flight. */
    val echoAge = FloatArray(PawShow.LETTERS)

    // The cat.
    var angle = 0f
    /** Degrees a second. */
    var speed = 0f
    var lift = 0f
    var squash = 0f
    var mouth = PawShow.MOUTH_SHUT
    var mouthOpen = 0f
    var blink = 0f

    // The camera and the effects.
    var zoom = 0f
    var roll = 0f
    var shakeX = 0f
    var shakeY = 0f
    var pulse = 0f
    var whirl = 0f
    var rays = 0f
    var dustAge = -1f
    var confettiAge = -1f
    var confettiSeed = 0

    // The choir.
    val choirIn = BooleanArray(PawShow.CHOIR)
    val choirLift = FloatArray(PawShow.CHOIR)
    val choirSquash = FloatArray(PawShow.CHOIR)
    val choirDir = FloatArray(PawShow.CHOIR)
    val choirGlance = FloatArray(PawShow.CHOIR)
    /** Degrees a choir cat has turned of its own accord. */
    val choirTurn = FloatArray(PawShow.CHOIR)

    fun reset() {
        cycle = -1; level = 0; local = 0f; reveal = 1f
        run = -1; shownRun = -1; letter = -1; letterAge = 0f
        stampAge.fill(-1f); echoAge.fill(-1f)
        angle = 0f; speed = 0f; lift = 0f; squash = 0f
        mouth = PawShow.MOUTH_SHUT; mouthOpen = 0f; blink = 0f
        zoom = 0f; roll = 0f; shakeX = 0f; shakeY = 0f
        pulse = 0f; whirl = 0f; rays = 0f; dustAge = -1f; confettiAge = -1f; confettiSeed = 0
        choirIn.fill(false); choirLift.fill(0f); choirSquash.fill(0f); choirDir.fill(1f); choirGlance.fill(0f)
        choirTurn.fill(0f)
    }

    /** Reduced motion: the cat faces you and sings without moving; nothing on screen travels. */
    fun still() {
        reveal = 1f
        angle = 0f; speed = 0f; lift = 0f; squash = 0f
        zoom = 0f; roll = 0f; shakeX = 0f; shakeY = 0f
        pulse = 0f; whirl = 0f; rays = 0f; dustAge = -1f; confettiAge = -1f
        echoAge.fill(-1f)
        choirLift.fill(0f); choirSquash.fill(0f); choirDir.fill(1f); choirTurn.fill(0f)
    }
}

/**
 * Whether a tap on the show closes it. The taps that summoned the cat tend to run on past the
 * tenth, so a tap counts only once the intro is over and after a calm moment without one.
 */
internal class TapGuard(private val openedAt: Long) {
    private var last = openedAt

    fun tap(now: Long): Boolean {
        val close = now - openedAt >= SETTLE_MS && now - last >= CALM_MS
        last = now
        return close
    }

    companion object {
        const val SETTLE_MS = 1000L
        const val CALM_MS = 700L
    }
}

/**
 * The combo on the paw, also a function of time: the badge swells and shakes harder with
 * every tap, the paw squishes, each tap throws paw prints, and the ninth charges the button.
 */
internal object PawHype {
    const val GOAL = 10
    /** How long a combo waits for the next tap. */
    const val COMBO_WINDOW_MS = 1200L
    /** How long an idle paw stays a paw. */
    const val PAW_IDLE_MS = 2500L
    const val BURST_MS = 650f

    fun badgeScale(combo: Int, sinceTap: Float): Float {
        val base = 1f + 0.055f * (min(combo, GOAL - 1) - 2).coerceAtLeast(0)
        val pop = if (sinceTap < 0f) 0f else 0.34f * exp(-sinceTap / 110f) * cos(sinceTap / 38f)
        return base * (1f + pop)
    }

    /** Degrees: nothing for a small combo, a rattle by the ninth tap. */
    fun badgeTilt(combo: Int, now: Float): Float {
        val amp = 1.6f * (min(combo, GOAL - 1) - 4).coerceAtLeast(0)
        return amp * sin(now * 2f * PI.toFloat() / 95f)
    }

    fun pawScale(sinceTap: Float): Float =
        if (sinceTap < 0f) 1f else 1f - 0.24f * exp(-sinceTap / 90f) * cos(sinceTap / 32f)

    /** 0…1 while the button holds a charge — at one tap from the goal — else 0. */
    fun charge(combo: Int, now: Float): Float =
        if (combo < GOAL - 1) 0f else 0.5f + 0.5f * sin(now * 2f * PI.toFloat() / 280f)

    /** A charged button trembles, dp. */
    fun tremble(combo: Int, now: Float): Float =
        if (combo < GOAL - 1) 0f else 1.4f * sin(now * 2f * PI.toFloat() / 41f)

    fun burstSize(combo: Int) = 2 + min(combo, GOAL) / 2
}
