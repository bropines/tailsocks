package io.github.bropines.tailscaled.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Draws a [PawScene]: the theme's background with its colour swell, the rays, the choir on its
 * risers, the cat in its whirl of speed lines, the dust, the letters and the confetti, all
 * through one camera that punches on the beats. The choir stands in rows behind and above the
 * cat, so that turning side on — when a cat is longest — nobody walks through anybody.
 * Nothing is allocated while drawing.
 */

/** The theme's colours, ARGB. [background] is the surface the show stands on. */
internal class PawColors(
    val background: Int,
    val ink: Int,
    val accent: Int,
    val accent2: Int,
    val tint: Int
) {
    val dark = luminance(background) < 0.4f
    /** AMOLED: the background stays black, whatever swells. */
    val black = background and 0xFFFFFF == 0
}

/**
 * Where everything stands on a frame of [width] × [height] units, scaled to fit the screen.
 * The choir: two on the lower riser (0, 1), three on the upper one (2–4).
 */
private class PawLayout(
    val width: Float,
    val height: Float,
    val pivotX: Float,
    val pivotY: Float,
    val mainX: Float,
    val mainGround: Float,
    val mainScale: Float,
    val choirX: FloatArray,
    val pairGround: Float,
    val trioGround: Float,
    val pairScale: Float,
    val trioScale: Float,
    val riserX: Float,
    val riserHalf: Float,
    val wordX: Float,
    val wordBase: Float,
    val letterSize: Float,
    val letterGap: Float
) {
    fun choirGround(j: Int) = if (j < 2) pairGround else trioGround
    fun choirScale(j: Int) = if (j < 2) pairScale else trioScale
}

/** A phone held upright: the choir above the cat, the word below. */
private val PORTRAIT = PawLayout(
    width = 400f, height = 720f, pivotX = 200f, pivotY = 400f,
    mainX = 200f, mainGround = 470f, mainScale = 1.22f,
    choirX = floatArrayOf(82f, 318f, 92f, 200f, 308f),
    pairGround = 252f, trioGround = 150f, pairScale = 0.48f, trioScale = 0.4f,
    riserX = 200f, riserHalf = 172f,
    wordX = 200f, wordBase = 640f, letterSize = 88f, letterGap = 66f
)

/** On its side: the cat and its choir on the left, the word to the right. */
private val LANDSCAPE = PawLayout(
    width = 800f, height = 420f, pivotX = 400f, pivotY = 250f,
    mainX = 250f, mainGround = 372f, mainScale = 0.98f,
    choirX = floatArrayOf(132f, 368f, 158f, 250f, 342f),
    pairGround = 212f, trioGround = 126f, pairScale = 0.4f, trioScale = 0.33f,
    riserX = 250f, riserHalf = 150f,
    wordX = 600f, wordBase = 316f, letterSize = 100f, letterGap = 74f
)

private val CHOIR_COAT = arrayOf(CatCoats.GINGER, CatCoats.TUXEDO, CatCoats.CREAM, CatCoats.GREY, CatCoats.SMOKE)

private const val CONFETTI_GOLD = 0xFFF2C94C.toInt()

/** How far the background leans towards the tint at the height of a swell. */
private const val PULSE_MIX = 0.2f

private const val DEG = (PI / 180).toFloat()

internal class PawStage {
    private val cat = CatPainter()
    private val pose = CatPose()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val pool = Paint(Paint.ANTI_ALIAS_FLAG)
    private var poolInk = 0
    private val word = PawShow.WORD.toCharArray()
    private val path = Path()
    private val rect = RectF()
    private var l = PORTRAIT

    /**
     * Draws [s] on a [width] × [height] canvas. The show opens as a circle growing from
     * ([originX], [originY]), the button it was summoned from.
     */
    fun draw(canvas: Canvas, width: Float, height: Float, s: PawScene, c: PawColors, originX: Float, originY: Float) {
        l = if (width > height * 1.15f) LANDSCAPE else PORTRAIT
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        if (s.reveal < 1f) {
            val reach = max(hypot(originX, originY), hypot(width - originX, height - originY))
            path.reset()
            path.addCircle(originX, originY, reach * s.reveal, Path.Direction.CW)
            canvas.clipPath(path)
        }
        canvas.drawColor(if (c.black) c.background else mix(c.background, c.tint, s.pulse * PULSE_MIX))
        if (poolInk != c.ink) {
            poolInk = c.ink
            pool.shader = RadialGradient(0f, 0f, 1f, c.ink, c.ink and 0xFFFFFF, Shader.TileMode.CLAMP)
        }

        val u = min(width / l.width, height / l.height)
        canvas.translate((width - l.width * u) / 2f, (height - l.height * u) / 2f)
        canvas.scale(u, u)
        // The camera.
        canvas.translate(l.pivotX + s.shakeX, l.pivotY + s.shakeY)
        canvas.rotate(s.roll)
        canvas.scale(1f + s.zoom, 1f + s.zoom)
        canvas.translate(-l.pivotX, -l.pivotY)

        if (s.rays > 0f) drawRays(canvas, s, c)
        drawEchoes(canvas, s, c)
        drawRiser(canvas, s, c, l.trioGround, PawShow.CHOIR_LAND[2])
        for (j in 2..4) if (s.choirIn[j]) drawChoir(canvas, s, c, j)
        drawRiser(canvas, s, c, l.pairGround, PawShow.CHOIR_LAND[0])
        for (j in 0..1) if (s.choirIn[j]) drawChoir(canvas, s, c, j)

        drawPool(canvas, c, l.mainX, l.mainGround, l.mainScale)
        if (s.whirl > 0f) drawWhirl(canvas, s, c, back = true)
        canvas.save()
        canvas.translate(l.mainX, l.mainGround)
        canvas.scale(l.mainScale, l.mainScale)
        cat.draw(canvas, CatCoats.GREY, pose.set(s.angle, s.lift, s.squash, s.mouth, s.mouthOpen, s.blink, 0f))
        canvas.restore()
        if (s.whirl > 0f) drawWhirl(canvas, s, c, back = false)
        if (s.dustAge >= 0f) drawDust(canvas, c, s.dustAge)

        drawWord(canvas, s, c)
        if (s.confettiAge >= 0f) drawConfetti(canvas, s, c)
        canvas.restore()
    }

    private fun drawChoir(canvas: Canvas, s: PawScene, c: PawColors, j: Int) {
        val x = l.choirX[j]
        val ground = l.choirGround(j)
        val scale = l.choirScale(j)
        drawPool(canvas, c, x, ground, scale)
        canvas.save()
        canvas.translate(x, ground)
        canvas.scale(scale, scale)
        cat.draw(
            canvas,
            CHOIR_COAT[j],
            pose.set(
                s.angle * s.choirDir[j] + s.choirTurn[j],
                s.choirLift[j],
                s.choirSquash[j],
                s.mouth,
                s.mouthOpen,
                s.blink,
                s.choirGlance[j]
            ),
            detail = false
        )
        canvas.restore()
    }

    /** A choir stands on risers. Each fades in just before the first of its row lands. */
    private fun drawRiser(canvas: Canvas, s: PawScene, c: PawColors, ground: Float, land: Float) {
        val shown = if (s.calm) (if (s.t >= land) 1f else 0f) else PawShow.smooth((s.t - (land - 420f)) / 260f)
        if (shown <= 0f) return
        fill.color = c.ink
        fill.alpha = ((if (c.dark) 18 else 14) * shown).toInt()
        rect.set(l.riserX - l.riserHalf, ground - 6f, l.riserX + l.riserHalf, ground + 20f)
        canvas.drawRoundRect(rect, 10f, 10f, fill)
        line.color = c.ink
        line.alpha = ((if (c.dark) 40 else 30) * shown).toInt()
        line.strokeWidth = 1.6f
        canvas.drawLine(rect.left + 8f, ground - 5f, rect.right - 8f, ground - 5f, line)
    }

    /** A soft pool of light under a cat, so it stands on something even on black. */
    private fun drawPool(canvas: Canvas, c: PawColors, x: Float, ground: Float, scale: Float) {
        pool.alpha = if (c.dark) 34 else 22
        canvas.save()
        canvas.translate(x, ground)
        canvas.scale(130f * scale, 18f * scale)
        canvas.drawCircle(0f, 0f, 1f, pool)
        canvas.restore()
    }

    /** Rays behind the cat, turning slowly: well under a pass a second, so they never strobe. */
    private fun drawRays(canvas: Canvas, s: PawScene, c: PawColors) {
        val cx = l.mainX
        val cy = l.mainGround - 90f * l.mainScale
        val turn = s.t * 0.012f
        path.reset()
        val rays = 16
        for (i in 0 until rays) {
            val a0 = (turn + i * 360f / rays) * DEG
            val a1 = a0 + 360f / rays / 2f * DEG
            path.moveTo(cx, cy)
            path.lineTo(cx + 1200f * cos(a0), cy + 1200f * sin(a0))
            path.lineTo(cx + 1200f * cos(a1), cy + 1200f * sin(a1))
            path.close()
        }
        fill.color = c.accent
        fill.alpha = ((if (c.black) 11 else if (c.dark) 16 else 20) * s.rays).toInt()
        canvas.drawPath(path, fill)
    }

    /**
     * Speed lines round the turning cat: rings at several heights, each a pair of arcs that
     * swing round with it. The far halves go behind the cat; the near halves pass in front of
     * the body only, never across the face.
     */
    private fun drawWhirl(canvas: Canvas, s: PawScene, c: PawColors, back: Boolean) {
        line.color = c.ink
        line.strokeWidth = 3.2f
        val sweep = 26f + 50f * s.whirl
        val k = l.mainScale / 1.22f
        for (j in 0..5) {
            if (!back && j > 2) break
            val ringY = l.mainGround - (18f + j * 27f) * l.mainScale
            val rx = (104f + 12f * (j % 3)) * k
            val ry = rx * 0.2f
            rect.set(l.mainX - rx, ringY - ry, l.mainX + rx, ringY + ry)
            canvas.save()
            if (back) canvas.clipRect(-l.width, -l.height, l.width * 2f, ringY)
            else canvas.clipRect(-l.width, ringY, l.width * 2f, l.height * 2f)
            for (n in 0..1) {
                val start = -s.angle * 1.1f - j * 47f + n * 180f
                // The head of each line is the brightest, its tail fades.
                for (seg in 0..2) {
                    line.alpha = (255 * s.whirl * (0.34f - seg * 0.1f)).toInt()
                    canvas.drawArc(rect, start + seg * sweep / 3f, sweep / 3f, false, line)
                }
            }
            canvas.restore()
        }
    }

    /** Dust kicked up where the cat lands or stops: soft puffs rolling out to the sides. */
    private fun drawDust(canvas: Canvas, c: PawColors, age: Float) {
        val u = age / 600f
        if (u >= 1f) return
        val out = PawShow.easeOutCubic(u)
        val scale = l.mainScale
        pool.alpha = ((if (c.dark) 70 else 54) * (1f - u) * (1f - u)).toInt()
        for (j in 0..5) {
            val side = if (j % 2 == 0) -1f else 1f
            val row = j / 2
            val dx = side * (44f + 22f * row) * (0.35f + 0.65f * out) * scale
            val dy = -(4f + 6f * row) * out * scale
            val r = (10f + 2f * row) * (0.6f + 0.9f * u) * scale
            canvas.save()
            canvas.translate(l.mainX + dx, l.mainGround - 5f * scale + dy)
            canvas.scale(r, r * 0.8f)
            canvas.drawCircle(0f, 0f, 1f, pool)
            canvas.restore()
        }
    }

    /** At the top level each letter also flies out of the cat, up behind the choir, fading. */
    private fun drawEchoes(canvas: Canvas, s: PawScene, c: PawColors) {
        val fromX = l.mainX
        val fromY = l.mainGround - 110f * l.mainScale
        letter.textSize = l.letterSize
        for (i in 0 until PawShow.LETTERS) {
            val age = s.echoAge[i]
            if (age < 0f) continue
            val u = age / PawShow.ECHO_MS
            val a = (-90f + (i - 2) * 24f) * DEG
            val d = 260f * l.mainScale * PawShow.easeOutCubic(u)
            val k = 0.6f + 0.8f * u
            letter.color = c.accent
            letter.alpha = (120 * (1f - u) * PawShow.smooth(u * 5f)).toInt()
            canvas.save()
            canvas.translate(fromX + d * cos(a), fromY + d * sin(a))
            canvas.rotate((i - 2) * 9f * u)
            canvas.scale(k, k)
            canvas.drawText(word, i, 1, 0f, l.letterSize * 0.36f, letter)
            canvas.restore()
        }
    }

    /** U I I A I: each letter stamps onto its slot as it is sung. */
    private fun drawWord(canvas: Canvas, s: PawScene, c: PawColors) {
        letter.textSize = l.letterSize
        val base = l.wordBase
        val mid = base - l.letterSize * 0.36f
        for (i in 0 until PawShow.LETTERS) {
            val x = l.wordX + (i - 2) * l.letterGap
            // The slot, waiting.
            letter.color = c.ink
            letter.alpha = if (c.dark) 26 else 20
            canvas.drawText(word, i, 1, x, base, letter)

            val age = s.stampAge[i]
            if (age < 0f) continue
            val singing = s.letter == i && s.run == s.shownRun
            val k = if (s.calm) 1f else stampScale(age)
            val appear = if (s.calm) 1f else min(1f, age / 30f)
            canvas.save()
            canvas.translate(x, mid)
            canvas.scale(k, k)
            // On a light background the stamp leaves a shadow.
            if (!c.dark) {
                letter.color = c.ink
                letter.alpha = (30 * appear).toInt()
                canvas.drawText(word, i, 1, 3f, base - mid + 4f, letter)
            }
            letter.color = if (singing) c.accent else c.ink
            letter.alpha = (255 * appear).toInt()
            canvas.drawText(word, i, 1, 0f, base - mid, letter)
            canvas.restore()

            // The impact: a ring that spreads from the stamp. The fast run rings only its A,
            // or the rings would pile up.
            val ringed = s.shownRun == 0 || i == 3
            if (!s.calm && s.level >= 1 && ringed && age in 50f..330f) {
                val r = (age - 50f) / 280f
                line.color = c.accent
                line.alpha = (90 * (1f - r)).toInt()
                line.strokeWidth = 3f * (1f - r) + 0.5f
                canvas.drawCircle(x, mid, (34f + 26f * PawShow.easeOutCubic(r)) * l.letterSize / 88f, line)
            }
        }
    }

    /** Comes down big and fast, lands a hair small, and settles. */
    private fun stampScale(age: Float): Float = when {
        age < 60f -> {
            val r = age / 60f
            1.9f + (0.93f - 1.9f) * r * r
        }
        else -> 1f - 0.07f * exp(-(age - 60f) / 60f) * cos((age - 60f) / 22f)
    }

    /** On the A at the top level: confetti out of the top of the cat, falling back down. */
    private fun drawConfetti(canvas: Canvas, s: PawScene, c: PawColors) {
        val age = s.confettiAge / 1000f
        val life = s.confettiAge / PawShow.CONFETTI_MS
        val fade = 1f - PawShow.smooth((life - 0.65f) / 0.35f)
        val x0 = l.mainX
        val y0 = l.mainGround - 200f * l.mainScale
        val k = l.mainScale / 1.22f
        for (j in 0 until 24) {
            val seed = s.confettiSeed
            val vx = (PawShow.noise(seed, j * 4) - 0.5f) * 620f * k
            val vy = -(380f + PawShow.noise(seed, j * 4 + 1) * 420f) * k
            val x = x0 + vx * age
            val y = y0 + vy * age + 0.5f * 1100f * k * age * age
            val spin = (PawShow.noise(seed, j * 4 + 2) - 0.5f) * 1400f * age
            // The theme's own two accents, and two warm colours that are a party on any of them.
            fill.color = when (j % 4) {
                0 -> c.accent
                1 -> c.accent2
                2 -> CONFETTI_GOLD
                else -> CatCoats.GINGER.fur
            }
            fill.alpha = (255 * fade).toInt()
            val w = 5f + 3f * PawShow.noise(seed, j * 4 + 3)
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(spin)
            canvas.scale(1f, cos(spin * 0.05f))
            canvas.drawRect(-w, -w * 0.6f, w, w * 0.6f, fill)
            canvas.restore()
        }
    }
}

/** [a] moved towards [b] by [f], ARGB, opaque. */
internal fun mix(a: Int, b: Int, f: Float): Int {
    if (f <= 0f) return a
    val t = f.coerceAtMost(1f)
    fun ch(shift: Int) = (((a shr shift) and 0xFF) + ((((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t)).toInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Relative luminance, 0…1, as WCAG reckons it. */
internal fun luminance(color: Int): Float {
    fun lin(shift: Int): Float {
        val v = ((color shr shift) and 0xFF) / 255f
        return if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    return 0.2126f * lin(16) + 0.7152f * lin(8) + 0.0722f * lin(0)
}
