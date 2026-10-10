package io.github.bropines.tailscaled.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The cat, drawn in 3D: a short-legged cat made of an ellipsoid body, a big round head, four
 * stubby legs and a tail, turned about its vertical axis and projected with a little
 * perspective. Whatever sits on its surface — eyes, stripes, ears, the muzzle — has a
 * direction it faces and is seen only from that side, so it turns as one body rather than as
 * a flat picture.
 *
 * Cat space: x to the cat's left as it faces you, y down with the ground at 0, z towards you;
 * an azimuth is measured from +z towards +x. The caller moves the canvas to the spot under
 * the cat and scales it; the cat is about 200 units tall. Nothing is allocated while drawing,
 * so a choir of them can turn at 120 frames a second.
 */

private fun argb(v: Long) = v.toInt()

/** A coat, ARGB. [stripe] 0 is a cat without tabby marks. */
internal class CatCoat(
    val fur: Int,
    val shade: Int,
    val stripe: Int,
    val cream: Int,
    val iris: Int,
    val ink: Int,
    /** How much of the chest is white, 0…1. */
    val bib: Float = 0.5f
)

internal object CatCoats {
    val GREY = CatCoat(argb(0xFFB4BBC4), argb(0xFF8B929C), argb(0xFF5D646D), argb(0xFFF5F2ED), argb(0xFFD6D982), argb(0xFF252930))
    val GINGER = CatCoat(argb(0xFFF3AF6C), argb(0xFFD78A4D), argb(0xFFBE6832), argb(0xFFFCF1E3), argb(0xFFA5CF68), argb(0xFF3B2A1F))
    val TUXEDO = CatCoat(argb(0xFF464A53), argb(0xFF30333A), 0, argb(0xFFF4F4F2), argb(0xFFE6C95C), argb(0xFF121316), bib = 1f)
    val CREAM = CatCoat(argb(0xFFF3E9DB), argb(0xFFDCC9B2), argb(0xFFD0B592), argb(0xFFFFFCF7), argb(0xFF92C4EA), argb(0xFF3C342B))
    val SMOKE = CatCoat(argb(0xFF8E939B), argb(0xFF6C717A), argb(0xFF494E56), argb(0xFFE9E7E3), argb(0xFFE3B95C), argb(0xFF22252A))
}

/** How the cat stands at one instant. */
internal class CatPose {
    /** Degrees about the vertical axis, 0 faces the viewer, growing turns it to its left. */
    var angle = 0f
    /** Units off the ground. */
    var lift = 0f
    /** Positive flattens, negative stretches. */
    var squash = 0f
    var mouth = PawShow.MOUTH_SHUT
    var mouthOpen = 0f
    /** 0 open … 1 shut. */
    var blink = 0f
    /** Where the pupils look, −1 left … 1 right. */
    var glance = 0f

    fun set(angle: Float, lift: Float, squash: Float, mouth: Int, mouthOpen: Float, blink: Float, glance: Float): CatPose {
        this.angle = angle
        this.lift = lift
        this.squash = squash
        this.mouth = mouth
        this.mouthOpen = mouthOpen
        this.blink = blink
        this.glance = glance
        return this
    }
}

private const val DEG = (PI / 180).toFloat()
private const val CAMERA = 900f
/** The height the perspective is centred on. */
private const val EYE_LEVEL = -90f

private const val OUTLINE = 3f

// The body: an ellipsoid.
private const val BODY_Y = -50f
private const val BODY_Z = -14f
private const val BODY_AX = 50f
private const val BODY_AY = 33f
private const val BODY_AZ = 66f

// The head: a big sphere, squashed a little.
private const val HEAD_Y = -114f
private const val HEAD_Z = 34f
private const val HEAD_R = 57f
private const val HEAD_RY = 50f

// Stubby legs.
private val LEG_X = floatArrayOf(-24f, 24f, -24f, 24f)
private val LEG_Z = floatArrayOf(28f, 28f, -52f, -52f)
private const val LEG_TOP = -38f
private const val LEG_R = 11.5f
private const val PAW_H = 12f

// The ears, the eyes, the muzzle.
private const val EAR_AZ = 40f
private const val EAR_EL = 44f
private const val EAR_H = 40f
private const val EAR_W = 20f
private const val EYE_AZ = 30f
private const val EYE_EL = 6f
private const val EYE_R = 14f
private const val PAD_AZ = 13f
private const val PAD_EL = -24f
private const val PAD_R = 12.5f

// The tail sweeps back and up; face on, the cat hides it.
private val TAIL_X = floatArrayOf(0f, 4f, 10f, 18f, 28f)
private val TAIL_Y = floatArrayOf(-58f, -70f, -86f, -106f, -122f)
private val TAIL_Z = floatArrayOf(-74f, -102f, -124f, -136f, -134f)

private val MOUTH_DARK = argb(0xFF5A2E39)
private val TONGUE = argb(0xFFE98A9A)
private val EAR_INNER = argb(0xFFEFAAB6)
private val NOSE = argb(0xFFE58B9B)
private val PUPIL = argb(0xFF111316)
private const val SHADOW = 0x2E000000

internal class CatPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 13f
        pathEffect = DashPathEffect(floatArrayOf(5f, 9f), 2f)
    }
    private val path = Path()
    private val clip = Path()
    private val rect = RectF()

    // The view: the turn, and the point last projected.
    private var cosA = 1f
    private var sinA = 0f
    private var px = 0f
    private var py = 0f
    private var pk = 1f

    // Scratch for sorting and for an ear's outline.
    private val legDepth = FloatArray(4)
    private val legOrder = IntArray(4)
    private val partDepth = FloatArray(3)
    private val partOrder = IntArray(3)
    private val hx = FloatArray(4)
    private val hy = FloatArray(4)
    private val hullOrder = IntArray(4)
    private val hull = IntArray(9)

    private fun depth(x: Float, z: Float) = -x * sinA + z * cosA

    private fun project(x: Float, y: Float, z: Float) {
        val k = CAMERA / (CAMERA - depth(x, z))
        pk = k
        px = (x * cosA + z * sinA) * k
        py = (y - EYE_LEVEL) * k + EYE_LEVEL
    }

    /** How squarely a surface facing azimuth [az] faces the viewer, −1…1. */
    private fun facing(az: Float) = cos(az * DEG) * cosA - sin(az * DEG) * sinA

    private fun headFacing(az: Float, el: Float) = facing(az) * cos(el * DEG)

    private fun projectHead(az: Float, el: Float) {
        val c = cos(el * DEG)
        project(HEAD_R * c * sin(az * DEG), HEAD_Y - HEAD_RY * sin(el * DEG), HEAD_Z + HEAD_R * c * cos(az * DEG))
    }

    /**
     * Draws the cat on [canvas], whose origin is the ground under it. [detail] off leaves out
     * the whiskers and toes, which a small cat has no room for.
     */
    fun draw(canvas: Canvas, coat: CatCoat, pose: CatPose, detail: Boolean = true) {
        val a = pose.angle * DEG
        cosA = cos(a)
        sinA = sin(a)

        // The shadow stays on the ground and shrinks as the cat rises.
        val lifted = (1f - pose.lift / 220f).coerceIn(0.3f, 1f)
        val half = sqrt(sq(64f * cosA) + sq(100f * sinA)) * lifted
        fill.color = SHADOW
        fill.alpha = (0x2E * lifted).toInt()
        rect.set(-half, -7f * lifted, half, 7f * lifted)
        canvas.drawOval(rect, fill)

        canvas.save()
        canvas.translate(0f, -pose.lift)
        canvas.scale(1f + 0.6f * pose.squash, 1f - pose.squash)

        // The legs never cover the body, so they go first, the far ones before the near.
        for (i in 0..3) {
            legDepth[i] = depth(LEG_X[i], LEG_Z[i])
            legOrder[i] = i
        }
        sortBy(legOrder, legDepth, 4)
        for (n in 0..3) drawLeg(canvas, coat, legOrder[n], detail)

        // The rest by depth. Side on, the head sits over the front of the body and the tail
        // over its back, hence the small leads they are given.
        partDepth[0] = depth(0f, BODY_Z)
        partDepth[1] = depth(0f, HEAD_Z) + 20f
        partDepth[2] = depth(TAIL_X[2], TAIL_Z[2]) + 10f
        for (i in 0..2) partOrder[i] = i
        sortBy(partOrder, partDepth, 3)
        for (n in 0..2) when (partOrder[n]) {
            0 -> drawBody(canvas, coat)
            1 -> drawHead(canvas, coat, pose, detail)
            else -> drawTail(canvas, coat)
        }
        canvas.restore()
    }

    private fun drawLeg(canvas: Canvas, coat: CatCoat, i: Int, detail: Boolean) {
        project(LEG_X[i], LEG_TOP, LEG_Z[i])
        val top = py
        project(LEG_X[i], 0f, LEG_Z[i])
        val x = px
        val bottom = py
        val k = pk
        val r = LEG_R * k
        fill.color = coat.fur
        rect.set(x - r, top, x + r, bottom)
        canvas.drawRoundRect(rect, r, r, fill)
        fill.color = coat.cream
        rect.set(x - r, bottom - PAW_H * k, x + r, bottom)
        canvas.drawRoundRect(rect, r, r * 0.8f, fill)
        line.color = coat.ink
        line.strokeWidth = OUTLINE
        rect.set(x - r, top, x + r, bottom)
        canvas.drawRoundRect(rect, r, r, line)
        // Toes, on the side of the paw that points forward.
        val toes = facing(0f)
        if (detail && toes > 0.25f) {
            line.strokeWidth = 1.8f
            line.alpha = (255 * toes).toInt()
            for (side in -1..1 step 2) {
                val tx = x + side * 0.3f * r * 2f * toes
                canvas.drawLine(tx, bottom - PAW_H * k * 0.55f, tx, bottom - 1.5f, line)
            }
            line.alpha = 255
        }
    }

    private fun drawBody(canvas: Canvas, coat: CatCoat) {
        project(0f, BODY_Y, BODY_Z)
        val cx = px
        val cy = py
        val k = pk
        val rx = sqrt(sq(BODY_AX * cosA) + sq(BODY_AZ * sinA)) * k
        val ry = BODY_AY * k
        clip.reset()
        clip.addOval(cx - rx, cy - ry, cx + rx, cy + ry, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        shadeOval(canvas, coat, cx, cy, rx, ry)

        // The white chest, between the front legs.
        val chest = facing(0f)
        if (chest > 0.05f) {
            project(0f, BODY_Y + 10f, BODY_Z + BODY_AZ * 0.92f)
            fill.color = coat.cream
            fill.alpha = (255 * (chest * 3f).coerceAtMost(1f)).toInt()
            val w = (10f + 18f * coat.bib) * chest * pk
            val h = (22f + 16f * coat.bib) * pk
            rect.set(px - w, py - h, px + w, py + h * 1.4f)
            canvas.drawOval(rect, fill)
            fill.alpha = 255
        }
        // The stripes run across the back from flank to flank.
        if (coat.stripe != 0) {
            var w = -0.78f
            while (w < 0.4f) {
                bodyStripe(canvas, coat.stripe, w)
                w += 0.22f
            }
        }
        canvas.restore()
        line.color = coat.ink
        line.strokeWidth = OUTLINE
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawOval(rect, line)
    }

    /** One tabby band round the body at [w] (−1 rear … 1 front), over the top from flank to flank. */
    private fun bodyStripe(canvas: Canvas, color: Int, w: Float) {
        val r = sqrt((1f - w * w).coerceAtLeast(0f))
        line.color = color
        line.strokeWidth = 5.5f
        var lastX = 0f
        var lastY = 0f
        var lastF = 0f
        for (i in 0..12) {
            val t = (16f + 148f * i / 12f) * DEG
            val x = BODY_AX * r * cos(t)
            val yo = -BODY_AY * r * sin(t)
            val zo = BODY_AZ * w
            project(x, BODY_Y + yo, BODY_Z + zo)
            // The surface's normal, and how squarely it faces the viewer.
            val nx = x / (BODY_AX * BODY_AX)
            val ny = yo / (BODY_AY * BODY_AY)
            val nz = zo / (BODY_AZ * BODY_AZ)
            val f = depth(nx, nz) / sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            if (i > 0) segment(canvas, lastX, lastY, px, py, min(lastF, f))
            lastX = px
            lastY = py
            lastF = f
        }
        line.alpha = 255
    }

    /** A stroke segment on a surface that faces the viewer by [f], fading out at the edge. */
    private fun segment(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, f: Float) {
        if (f <= 0.04f) return
        line.alpha = (255 * (f * 4f).coerceAtMost(1f)).toInt()
        canvas.drawLine(x0, y0, x1, y1, line)
    }

    /** A stroke along the head's surface from ([az0], [el0]) to ([az1], [el1]). */
    private fun headStroke(canvas: Canvas, color: Int, az0: Float, el0: Float, az1: Float, el1: Float, width: Float = 4.5f) {
        line.color = color
        line.strokeWidth = width
        var lastX = 0f
        var lastY = 0f
        var lastF = 0f
        for (i in 0..8) {
            val f = i / 8f
            val az = az0 + (az1 - az0) * f
            val el = el0 + (el1 - el0) * f
            projectHead(az, el)
            val facing = headFacing(az, el)
            if (i > 0) segment(canvas, lastX, lastY, px, py, min(lastF, facing))
            lastX = px
            lastY = py
            lastF = facing
        }
        line.alpha = 255
    }

    /** A round fur shape inside the current clip: its shadow side low and to the right, lit above. */
    private fun shadeOval(canvas: Canvas, coat: CatCoat, cx: Float, cy: Float, rx: Float, ry: Float) {
        fill.color = coat.shade
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawOval(rect, fill)
        fill.color = coat.fur
        val lx = cx - 0.1f * rx
        val ly = cy - 0.17f * ry
        rect.set(lx - rx * 0.97f, ly - ry * 0.93f, lx + rx * 0.97f, ly + ry * 0.93f)
        canvas.drawOval(rect, fill)
    }

    private fun drawTail(canvas: Canvas, coat: CatCoat) {
        path.reset()
        project(TAIL_X[0], TAIL_Y[0], TAIL_Z[0])
        path.moveTo(px, py)
        for (i in 1 until TAIL_X.size - 1) {
            project(TAIL_X[i], TAIL_Y[i], TAIL_Z[i])
            val cx = px
            val cy = py
            project(TAIL_X[i + 1], TAIL_Y[i + 1], TAIL_Z[i + 1])
            if (i < TAIL_X.size - 2) path.quadTo(cx, cy, (cx + px) / 2f, (cy + py) / 2f)
            else path.quadTo(cx, cy, px, py)
        }
        line.color = coat.ink
        line.strokeWidth = 19f
        canvas.drawPath(path, line)
        line.color = coat.fur
        line.strokeWidth = 13f
        canvas.drawPath(path, line)
        if (coat.stripe != 0) {
            dash.color = coat.stripe
            canvas.drawPath(path, dash)
        }
    }

    private fun drawHead(canvas: Canvas, coat: CatCoat, pose: CatPose, detail: Boolean) {
        for (side in -1..1 step 2) drawEar(canvas, coat, side.toFloat())

        project(0f, HEAD_Y, HEAD_Z)
        val cx = px
        val cy = py
        val k = pk
        val rx = HEAD_R * k
        val ry = HEAD_RY * k
        val front = facing(0f)
        // The muzzle goes in twice: outlined under the head, so that only the part sticking
        // out past it shows an edge, and plain over it, as the face.
        drawMuzzle(canvas, coat, pose, outline = true)

        clip.reset()
        clip.addOval(cx - rx, cy - ry, cx + rx, cy + ry, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        shadeOval(canvas, coat, cx, cy, rx, ry)
        if (coat.stripe != 0) {
            // The M on the forehead, lines on the cheeks, rings round the back of the head.
            headStroke(canvas, coat.stripe, -15f, 60f, -10f, 34f)
            headStroke(canvas, coat.stripe, 0f, 68f, 0f, 38f)
            headStroke(canvas, coat.stripe, 15f, 60f, 10f, 34f)
            for (side in -1..1 step 2) {
                headStroke(canvas, coat.stripe, 64f * side, 6f, 86f * side, 2f)
                headStroke(canvas, coat.stripe, 62f * side, -10f, 84f * side, -14f)
            }
            var el = 44f
            while (el > 0f) {
                headStroke(canvas, coat.stripe, 116f, el, 180f, el + 4f, 5f)
                headStroke(canvas, coat.stripe, 180f, el + 4f, 244f, el, 5f)
                el -= 18f
            }
        } else if (coat.bib >= 1f) {
            // A tuxedo's white blaze between the eyes.
            fill.color = coat.cream
            fill.alpha = (255 * (front * 3f).coerceIn(0f, 1f)).toInt()
            projectHead(0f, -6f)
            rect.set(px - 12f * front * k, py - 26f * k, px + 12f * front * k, py + 22f * k)
            if (front > 0f) canvas.drawOval(rect, fill)
            fill.alpha = 255
        }
        canvas.restore()

        if (front >= 0f) drawMuzzle(canvas, coat, pose, outline = false)
        for (side in -1..1 step 2) drawEye(canvas, coat, pose, side.toFloat())
        if (front >= 0f) drawMouth(canvas, coat, pose, detail)

        line.color = coat.ink
        line.strokeWidth = OUTLINE
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawOval(rect, line)
    }

    private fun drawEar(canvas: Canvas, coat: CatCoat, side: Float) {
        val az = EAR_AZ * side
        val c = cos(EAR_EL * DEG)
        val bx = HEAD_R * c * sin(az * DEG)
        val by = HEAD_Y - HEAD_RY * sin(EAR_EL * DEG)
        val bz = HEAD_Z + HEAD_R * c * cos(az * DEG)
        val tx = cos(az * DEG)
        val tz = -sin(az * DEG)
        // A cup, not a sheet: a point behind the base keeps it from going thin side on.
        project(bx + 5f * side, by - EAR_H, bz - 2f)
        hx[0] = px; hy[0] = py
        project(bx - tx * EAR_W, by + 4f, bz - tz * EAR_W)
        hx[1] = px; hy[1] = py
        project(bx + tx * EAR_W, by + 4f, bz + tz * EAR_W)
        hx[2] = px; hy[2] = py
        project(bx - sin(az * DEG) * 15f, by - 12f, bz - cos(az * DEG) * 15f)
        hx[3] = px; hy[3] = py
        hullPath(4)
        fill.color = coat.fur
        canvas.drawPath(path, fill)
        val front = facing(az)
        if (front > 0.1f) {
            val mx = (hx[0] + hx[1] + hx[2]) / 3f
            val my = (hy[0] + hy[1] + hy[2]) / 3f + 4f
            clip.reset()
            clip.moveTo(mx + (hx[1] - mx) * 0.6f, my + (hy[1] - my) * 0.6f)
            clip.lineTo(mx + (hx[0] - mx) * 0.62f, my + (hy[0] - my) * 0.62f)
            clip.lineTo(mx + (hx[2] - mx) * 0.6f, my + (hy[2] - my) * 0.6f)
            clip.close()
            fill.color = EAR_INNER
            fill.alpha = (255 * (front * 2.5f).coerceAtMost(1f)).toInt()
            canvas.drawPath(clip, fill)
            fill.alpha = 255
        }
        line.color = coat.ink
        line.strokeWidth = OUTLINE
        canvas.drawPath(path, line)
    }

    /** The two whisker pads and the chin; side on, the profile of the snout. */
    private fun drawMuzzle(canvas: Canvas, coat: CatCoat, pose: CatPose, outline: Boolean) {
        val jaw = if (pose.mouth == PawShow.MOUTH_A) 7f * pose.mouthOpen else 0f
        fill.color = coat.cream
        line.color = coat.ink
        line.strokeWidth = OUTLINE
        // The chin, under the pads; it drops when the cat sings an A.
        projectHead(0f, PAD_EL - 14f)
        val chinK = pk
        rect.set(px - 10f * chinK, py - 8f * chinK, px + 10f * chinK, py + (6f + jaw) * chinK)
        if (outline) canvas.drawOval(rect, line)
        canvas.drawOval(rect, fill)
        for (side in -1..1 step 2) {
            val az = PAD_AZ * side
            projectHead(az, PAD_EL)
            val r = PAD_R * pk
            // A pad facing away is behind the head and the other pad.
            if (headFacing(az, PAD_EL) < -0.2f) continue
            if (outline) canvas.drawCircle(px, py, r, line)
            canvas.drawCircle(px, py, r, fill)
        }
    }

    private fun drawEye(canvas: Canvas, coat: CatCoat, pose: CatPose, side: Float) {
        val az = EYE_AZ * side
        val f = headFacing(az, EYE_EL)
        if (f <= 0.04f) return
        projectHead(az, EYE_EL)
        val cx = px
        val cy = py
        val k = pk
        // The meme's stare: a touch uneven, one eye a little bigger than the other.
        val big = if (side > 0f) 1.06f else 1f
        val ex = EYE_R * big * f * k
        val ey = EYE_R * big * 1.08f * k
        val alpha = (255 * (f * 4f).coerceAtMost(1f)).toInt()

        if (pose.blink >= 0.98f) {
            line.color = coat.ink
            line.strokeWidth = 3.2f
            line.alpha = alpha
            canvas.drawLine(cx - ex, cy + ey * 0.15f, cx + ex, cy + ey * 0.15f, line)
            line.alpha = 255
            return
        }
        clip.reset()
        clip.addOval(cx - ex, cy - ey, cx + ex, cy + ey, Path.Direction.CW)
        fill.color = coat.iris
        fill.alpha = alpha
        canvas.drawPath(clip, fill)
        canvas.save()
        canvas.clipPath(clip)
        // A wide, dark pupil: dilated, which is what makes the stare blank rather than cross.
        val gx = cx + pose.glance * ex * 0.36f
        fill.color = PUPIL
        fill.alpha = alpha
        rect.set(gx - ex * 0.66f, cy - ey * 0.8f, gx + ex * 0.66f, cy + ey * 0.8f)
        canvas.drawOval(rect, fill)
        fill.color = -1
        fill.alpha = (alpha * 0.95f).toInt()
        canvas.drawCircle(gx + ex * 0.3f, cy - ey * 0.36f, ey * 0.24f, fill)
        fill.alpha = (alpha * 0.7f).toInt()
        canvas.drawCircle(gx - ex * 0.28f, cy + ey * 0.34f, ey * 0.1f, fill)
        // A lid a little way down: the cat is calm about all of this.
        val lid = cy - ey + ey * 2f * (0.1f + 0.9f * pose.blink)
        fill.color = coat.fur
        fill.alpha = alpha
        canvas.drawRect(cx - ex, cy - ey, cx + ex, lid, fill)
        canvas.restore()
        line.color = coat.ink
        line.alpha = alpha
        line.strokeWidth = 2.6f
        canvas.drawPath(clip, line)
        line.strokeWidth = 2.6f
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawLine(cx - ex, lid, cx + ex, lid, line)
        canvas.restore()
        line.alpha = 255
        fill.alpha = 255
    }

    private fun drawMouth(canvas: Canvas, coat: CatCoat, pose: CatPose, detail: Boolean) {
        val front = facing(0f)
        val a = (255 * (front * 3f).coerceIn(0f, 1f)).toInt()
        // The nose, between the pads and above them.
        projectHead(0f, PAD_EL + 11f)
        val k = pk
        val nx = px
        val ny = py
        val w = 6.5f * front.coerceAtLeast(0.45f) * k
        val h = 5f * k
        path.reset()
        path.moveTo(nx - w, ny - h * 0.6f)
        path.quadTo(nx, ny - h * 1.1f, nx + w, ny - h * 0.6f)
        path.quadTo(nx + w * 0.4f, ny + h * 0.4f, nx, ny + h * 0.8f)
        path.quadTo(nx - w * 0.4f, ny + h * 0.4f, nx - w, ny - h * 0.6f)
        path.close()
        fill.color = NOSE
        fill.alpha = a
        canvas.drawPath(path, fill)

        line.color = coat.ink
        line.alpha = a
        line.strokeWidth = 2.2f
        val open = if (pose.mouth == PawShow.MOUTH_SHUT) 0f else pose.mouthOpen
        projectHead(0f, PAD_EL - 4f)
        val mx = px
        val my = py
        val squeeze = front.coerceAtLeast(0.35f)
        if (open < 0.08f) {
            // Shut: down from the nose and out to either side, flat, the corners a little down.
            canvas.drawLine(nx, ny + h * 0.8f, mx, my - 2f * k, line)
            canvas.drawLine(mx, my - 2f * k, mx - 7f * squeeze * k, my + 0.5f * k, line)
            canvas.drawLine(mx, my - 2f * k, mx + 7f * squeeze * k, my + 0.5f * k, line)
        } else {
            canvas.drawLine(nx, ny + h * 0.8f, mx, my - 2.5f * k, line)
            val ox = when (pose.mouth) {
                PawShow.MOUTH_U -> 4.5f
                PawShow.MOUTH_I -> 9f
                else -> 8f
            }
            val oy = when (pose.mouth) {
                PawShow.MOUTH_U -> 6.5f
                PawShow.MOUTH_I -> 3.6f
                else -> 11f
            }
            val rx = ox * squeeze * k * (0.6f + 0.4f * open)
            val ry = oy * k * open
            val top = my - 2.5f * k
            rect.set(mx - rx, top, mx + rx, top + ry * 2f)
            fill.color = MOUTH_DARK
            fill.alpha = a
            canvas.drawOval(rect, fill)
            if (pose.mouth != PawShow.MOUTH_I) {
                clip.reset()
                clip.addOval(rect, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                fill.color = TONGUE
                fill.alpha = a
                canvas.drawCircle(mx, top + ry * 2.1f, rx * 0.85f, fill)
                canvas.restore()
            }
            canvas.drawOval(rect, line)
        }
        line.alpha = 255
        fill.alpha = 255

        if (!detail) return
        // Whiskers, out from the sides of the pads that can be seen.
        for (side in -1..1 step 2) {
            val f = facing(45f * side)
            if (f <= 0.1f) continue
            projectHead(PAD_AZ * 2.2f * side, PAD_EL - 2f)
            val ox = px
            val oy = py
            val dir = if (ox > nx) 1f else -1f
            val len = 36f * k * f
            line.color = coat.ink
            line.strokeWidth = 1.6f
            line.alpha = (140 * (f * 2f).coerceAtMost(1f)).toInt()
            for (tilt in -1..1) {
                val r = tilt * 12f * DEG
                val sy = oy + tilt * 2.2f * k
                canvas.drawLine(ox, sy, ox + dir * len * cos(r), sy + len * sin(r), line)
            }
            line.alpha = 255
        }
    }

    /** Puts the convex hull of the first [n] points of hx/hy into [path]. */
    private fun hullPath(n: Int) {
        for (i in 0 until n) hullOrder[i] = i
        for (i in 1 until n) {
            val p = hullOrder[i]
            var j = i - 1
            while (j >= 0 && (hx[hullOrder[j]] > hx[p] || (hx[hullOrder[j]] == hx[p] && hy[hullOrder[j]] > hy[p]))) {
                hullOrder[j + 1] = hullOrder[j]
                j--
            }
            hullOrder[j + 1] = p
        }
        var m = 0
        for (i in 0 until n) {
            val p = hullOrder[i]
            while (m >= 2 && cross(hull[m - 2], hull[m - 1], p) <= 0f) m--
            hull[m++] = p
        }
        val lower = m + 1
        for (i in n - 2 downTo 0) {
            val p = hullOrder[i]
            while (m >= lower && cross(hull[m - 2], hull[m - 1], p) <= 0f) m--
            hull[m++] = p
        }
        path.reset()
        path.moveTo(hx[hull[0]], hy[hull[0]])
        for (i in 1 until m - 1) path.lineTo(hx[hull[i]], hy[hull[i]])
        path.close()
    }

    private fun cross(o: Int, a: Int, b: Int) =
        (hx[a] - hx[o]) * (hy[b] - hy[o]) - (hy[a] - hy[o]) * (hx[b] - hx[o])
}

/** Sorts the first [n] of [order] by [key], ascending; n is tiny. */
private fun sortBy(order: IntArray, key: FloatArray, n: Int) {
    for (i in 1 until n) {
        val p = order[i]
        var j = i - 1
        while (j >= 0 && key[order[j]] > key[p]) {
            order[j + 1] = order[j]
            j--
        }
        order[j + 1] = p
    }
}

private fun sq(x: Float) = x * x
