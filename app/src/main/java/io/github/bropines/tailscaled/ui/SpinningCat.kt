package io.github.bropines.tailscaled.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The spinning cat, drawn in 3D: a short-legged grey tabby made of an ellipsoid body, a
 * round head, four legs and a tail, turned about its vertical axis and projected with a
 * little perspective. Whatever sits on its surface — eyes, stripes, ears — has a direction
 * it faces and is seen only from that side, so the cat turns as one body rather than as
 * a flat picture.
 *
 * Cat space: x to the cat's left as it faces the viewer, y down with the ground at 0,
 * z towards the viewer. An azimuth is measured from +z towards +x.
 */

private val FUR_TOP = Color(0xFFB4BAC2)
private val FUR_BOTTOM = Color(0xFF858B94)
private val STRIPE = Color(0xFF5A6068)
private val CREAM = Color(0xFFF5F2ED)
private val INK = Color(0xFF2A2E34)
private val NOSE = Color(0xFFE6919F)
private val EAR_INNER = Color(0xFFEFAAB6)
private val EYE_RIM = Color(0xFFDCE0C8)

private const val DEG = (PI / 180).toFloat()

/** Where the camera stands: farther means flatter. */
private const val CAMERA = 1100f

/** The height the perspective is centred on, in cat units. */
private const val EYE_LEVEL = -88f

private class Point3(val x: Float, val y: Float, val z: Float)

private class CatView(size: Size, angle: Float) {
    val s = min(size.width / 300f, size.height / 205f)
    private val cx = size.width / 2f
    private val groundY = size.height / 2f + 92f * s
    private val a = angle * DEG
    val cosA = cos(a)
    val sinA = sin(a)

    fun depth(x: Float, z: Float) = -x * sinA + z * cosA
    fun scale(x: Float, z: Float) = CAMERA / (CAMERA - depth(x, z))

    fun at(x: Float, y: Float, z: Float): Offset {
        val k = scale(x, z)
        return Offset(cx + (x * cosA + z * sinA) * k * s, groundY + ((y - EYE_LEVEL) * k + EYE_LEVEL) * s)
    }

    fun at(p: Point3) = at(p.x, p.y, p.z)

    /** How squarely a surface facing azimuth [az] (degrees) faces the viewer, −1…1. */
    fun facing(az: Float) = cos(az * DEG + a)
}

// The body: an ellipsoid.
private const val BODY_Y = -64f
private const val BODY_Z = -18f
private const val BODY_AX = 56f
private const val BODY_AY = 40f
private const val BODY_AZ = 80f

// The head: a sphere squashed a little.
private const val HEAD_Y = -112f
private const val HEAD_Z = 50f
private const val HEAD_R = 50f
private const val HEAD_RY = 44f

private fun headPoint(az: Float, el: Float) = Point3(
    HEAD_R * cos(el * DEG) * sin(az * DEG),
    HEAD_Y - HEAD_RY * sin(el * DEG),
    HEAD_Z + HEAD_R * cos(el * DEG) * cos(az * DEG)
)

// The snout: a small round muzzle on the front of the head, which side on is the profile.
private const val SNOUT_R = 17f
private const val SNOUT_RY = 12.5f
private val SNOUT = headPoint(0f, -20f).let { Point3(it.x, it.y, it.z - 6f) }

/** A point on the snout's surface at azimuth [az], [dy] below its centre. */
private fun snoutPoint(az: Float, dy: Float) =
    Point3(SNOUT.x + SNOUT_R * sin(az * DEG), SNOUT.y + dy, SNOUT.z + SNOUT_R * cos(az * DEG))

/** How squarely the head's surface at ([az], [el]) faces the viewer. */
private fun CatView.headFacing(az: Float, el: Float) = facing(az) * cos(el * DEG)

/** A cross-section of the body at [w] (−1 rear … 1 front), [t] degrees round from the left flank over the top. */
private fun bodyRing(w: Float, t: Float): Pair<Point3, Point3> {
    val r = sqrt((1f - w * w).coerceAtLeast(0f))
    val x = BODY_AX * r * cos(t * DEG)
    val yo = -BODY_AY * r * sin(t * DEG)
    val zo = BODY_AZ * w
    val point = Point3(x, BODY_Y + yo, BODY_Z + zo)
    val n = Point3(x / (BODY_AX * BODY_AX), yo / (BODY_AY * BODY_AY), zo / (BODY_AZ * BODY_AZ))
    return point to n
}

private fun CatView.normalFacing(n: Point3): Float {
    val len = sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
    return if (len == 0f) 0f else depth(n.x, n.z) / len
}

/** Draws [points] as one stroke wherever their surface faces the viewer, fading at the edge. */
private fun DrawScope.surfaceStroke(points: List<Pair<Offset, Float>>, color: Color, width: Float) {
    for (i in 1 until points.size) {
        val (p0, f0) = points[i - 1]
        val (p1, f1) = points[i]
        val f = min(f0, f1)
        if (f <= 0.04f) continue
        drawLine(color.copy(alpha = color.alpha * (f * 4f).coerceAtMost(1f)), p0, p1, width, StrokeCap.Round)
    }
}

/** A stroke along the head's surface through ([az], [el]) pairs, interpolated. */
private fun DrawScope.headStroke(v: CatView, vararg azEl: Float, color: Color = STRIPE, width: Float = 4.5f) {
    val samples = mutableListOf<Pair<Offset, Float>>()
    for (i in 0 until azEl.size / 2 - 1) {
        val az0 = azEl[i * 2]
        val el0 = azEl[i * 2 + 1]
        val az1 = azEl[i * 2 + 2]
        val el1 = azEl[i * 2 + 3]
        for (k in 0..8) {
            if (i > 0 && k == 0) continue
            val f = k / 8f
            val az = az0 + (az1 - az0) * f
            val el = el0 + (el1 - el0) * f
            samples += v.at(headPoint(az, el)) to v.headFacing(az, el)
        }
    }
    surfaceStroke(samples, color, width * v.s)
}

/** The convex hull of a handful of points, as a closed path. */
private fun hullPath(points: List<Offset>): Path {
    val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val hull = mutableListOf<Offset>()
    for (pass in 0..1) {
        val start = hull.size
        for (p in if (pass == 0) sorted else sorted.reversed()) {
            while (hull.size >= start + 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f) hull.removeAt(hull.size - 1)
            hull += p
        }
        hull.removeAt(hull.size - 1)
    }
    return Path().apply {
        hull.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }
}

private fun DrawScope.ellipseAt(center: Offset, rx: Float, ry: Float, color: Color, outline: Boolean, s: Float) {
    if (rx <= 0f || ry <= 0f) return
    val topLeft = Offset(center.x - rx, center.y - ry)
    val size = Size(rx * 2f, ry * 2f)
    drawOval(color, topLeft, size)
    if (outline) drawOval(INK, topLeft, size, style = Stroke(3f * s))
}

private fun DrawScope.drawLeg(v: CatView, lx: Float, lz: Float) {
    val k = v.scale(lx, lz)
    val top = v.at(lx, -42f, lz)
    val bottom = v.at(lx, 0f, lz)
    val r = 12.5f * k * v.s
    val paw = 14f * k * v.s
    val outline = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                Rect(bottom.x - r, top.y, bottom.x + r, bottom.y),
                androidx.compose.ui.geometry.CornerRadius(r, r)
            )
        )
    }
    drawPath(outline, Brush.verticalGradient(listOf(FUR_TOP, FUR_BOTTOM), startY = top.y, endY = bottom.y))
    drawRoundRect(
        CREAM,
        topLeft = Offset(bottom.x - r, bottom.y - paw),
        size = Size(r * 2f, paw),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
    )
    drawPath(outline, INK, style = Stroke(3f * v.s))
    // Toes, on the side of the paw that points forward.
    val toes = v.facing(0f)
    if (toes > 0.25f) {
        for (dx in floatArrayOf(-0.33f, 0.33f)) {
            val x = bottom.x + dx * r * 2f * toes * 0.9f
            drawLine(INK.copy(alpha = toes), Offset(x, bottom.y - paw * 0.55f), Offset(x, bottom.y - 1.5f * v.s), 1.8f * v.s, StrokeCap.Round)
        }
    }
}

private val TAIL = listOf(
    Point3(0f, -76f, -94f),
    Point3(6f, -100f, -114f),
    Point3(20f, -126f, -122f),
    Point3(40f, -150f, -112f),
    Point3(54f, -164f, -96f)
)

private fun DrawScope.drawTail(v: CatView) {
    val pts = TAIL.map { v.at(it) }
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size - 1) {
            val mid = Offset((pts[i].x + pts[i + 1].x) / 2f, (pts[i].y + pts[i + 1].y) / 2f)
            quadraticTo(pts[i].x, pts[i].y, mid.x, mid.y)
        }
        lineTo(pts.last().x, pts.last().y)
    }
    val s = v.s
    drawPath(path, INK, style = Stroke(19f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, FUR_TOP, style = Stroke(13f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(
        path,
        STRIPE,
        style = Stroke(13f * s, cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * s, 10f * s), 2f * s))
    )
}

private fun DrawScope.drawBody(v: CatView) {
    val k = v.scale(0f, BODY_Z)
    val c = v.at(0f, BODY_Y, BODY_Z)
    val rx = sqrt((BODY_AX * v.cosA).let { it * it } + (BODY_AZ * v.sinA).let { it * it }) * k * v.s
    val ry = BODY_AY * k * v.s
    val topLeft = Offset(c.x - rx, c.y - ry)
    val size = Size(rx * 2f, ry * 2f)
    drawOval(Brush.verticalGradient(listOf(FUR_TOP, FUR_BOTTOM), startY = c.y - ry, endY = c.y + ry), topLeft, size)

    // The stripes run across the back from flank to flank.
    for (w in floatArrayOf(-0.78f, -0.56f, -0.34f, -0.12f, 0.1f, 0.32f)) {
        val samples = (0..16).map { i ->
            val (p, n) = bodyRing(w, 18f + 144f * i / 16f)
            v.at(p) to v.normalFacing(n)
        }
        surfaceStroke(samples, STRIPE, 5.5f * v.s)
    }
    // The white chest, between the front legs.
    val chest = v.facing(0f)
    if (chest > 0.05f) {
        val p = v.at(0f, BODY_Y + 8f, BODY_Z + BODY_AZ * 0.93f)
        ellipseAt(p, 21f * chest * k * v.s, 24f * k * v.s, CREAM.copy(alpha = (chest * 3f).coerceAtMost(1f)), false, v.s)
    }
    drawOval(INK, topLeft, size, style = Stroke(3f * v.s))
}

private fun DrawScope.drawSnout(v: CatView, k: Float) {
    val s = v.s
    val c = v.at(SNOUT)
    val topLeft = Offset(c.x - SNOUT_R * k * s, c.y - SNOUT_RY * k * s)
    val size = Size(SNOUT_R * 2f * k * s, SNOUT_RY * 2f * k * s)
    drawOval(CREAM, topLeft, size)
    // Face on, the muzzle is a patch of white fur; side on, it is a shape with an edge.
    val edge = (1f - abs(v.facing(0f)) * 1.4f).coerceIn(0f, 1f)
    if (edge > 0f) drawOval(INK.copy(alpha = edge), topLeft, size, style = Stroke(3f * s))
}

private fun DrawScope.drawSnoutFace(v: CatView, k: Float) {
    val s = v.s
    val front = v.facing(0f)
    // The nose, on top of the snout's tip; side on it is the tip.
    val n = v.at(snoutPoint(0f, -6f))
    val w = 6.5f * front.coerceAtLeast(0.45f) * k * s
    val h = 5f * k * s
    val nose = Path().apply {
        moveTo(n.x - w, n.y - h * 0.6f)
        quadraticTo(n.x, n.y - h * 1.1f, n.x + w, n.y - h * 0.6f)
        quadraticTo(n.x + w * 0.4f, n.y + h * 0.4f, n.x, n.y + h * 0.8f)
        quadraticTo(n.x - w * 0.4f, n.y + h * 0.4f, n.x - w, n.y - h * 0.6f)
        close()
    }
    drawPath(nose, NOSE.copy(alpha = ((front + 0.35f) * 3f).coerceIn(0f, 1f)))
    // The mouth: down from the nose, then out to either side.
    val ink = INK.copy(alpha = (front * 3f).coerceIn(0f, 1f))
    val m0 = v.at(snoutPoint(0f, -2f))
    val m1 = v.at(snoutPoint(0f, 3f))
    drawLine(ink, m0, m1, 2.2f * s, StrokeCap.Round)
    // Not a smile: the line goes out flat and turns a little down.
    for (side in floatArrayOf(-1f, 1f)) {
        if (v.facing(26f * side) <= 0.05f) continue
        val end = v.at(snoutPoint(26f * side, 6.5f))
        drawLine(ink, m1, end, 2.2f * s, StrokeCap.Round)
    }
    // Whiskers, out from the sides of the snout that can be seen.
    for (side in floatArrayOf(-1f, 1f)) {
        val f = v.facing(45f * side)
        if (f <= 0.1f) continue
        val o = v.at(snoutPoint(45f * side, 1f))
        val dir = if (v.at(snoutPoint(90f * side, 1f)).x > v.at(SNOUT).x) 1f else -1f
        val len = 40f * k * s * f
        for (tilt in floatArrayOf(-12f, 0f, 12f)) {
            val r = tilt * DEG
            val start = Offset(o.x, o.y + tilt * 0.15f * k * s)
            drawLine(
                INK.copy(alpha = 0.55f * (f * 2f).coerceAtMost(1f)),
                start,
                Offset(start.x + dir * len * cos(r), start.y + len * sin(r)),
                1.6f * s,
                StrokeCap.Round
            )
        }
    }
}

private fun DrawScope.drawHead(v: CatView, blink: Boolean) {
    val s = v.s
    // Ears first, so the head covers where they join it.
    for (side in floatArrayOf(-1f, 1f)) {
        val az = 36f * side
        val base = headPoint(az, 50f)
        val tx = cos(az * DEG)
        val tz = -sin(az * DEG)
        val tip = v.at(base.x + 6f * side, base.y - 36f, base.z + 2f)
        val b0 = v.at(base.x - tx * 18f, base.y + 4f, base.z - tz * 18f)
        val b1 = v.at(base.x + tx * 18f, base.y + 4f, base.z + tz * 18f)
        // An ear is a cup, not a sheet: a point behind its base keeps it from going thin side on.
        val back = v.at(base.x - sin(az * DEG) * 15f, base.y - 12f, base.z - cos(az * DEG) * 15f)
        val ear = hullPath(listOf(b0, tip, b1, back))
        drawPath(ear, FUR_TOP)
        val front = v.facing(az)
        if (front > 0.1f) {
            val mid = Offset((b0.x + b1.x + tip.x) / 3f, (b0.y + b1.y + tip.y) / 3f + 4f * s)
            fun toward(p: Offset, f: Float) = Offset(mid.x + (p.x - mid.x) * f, mid.y + (p.y - mid.y) * f)
            val inner = Path().apply {
                val a = toward(b0, 0.62f)
                val b = toward(tip, 0.62f)
                val c = toward(b1, 0.62f)
                moveTo(a.x, a.y)
                lineTo(b.x, b.y)
                lineTo(c.x, c.y)
                close()
            }
            drawPath(inner, EAR_INNER.copy(alpha = (front * 2.5f).coerceAtMost(1f)))
        }
        drawPath(ear, INK, style = Stroke(3f * s, join = StrokeJoin.Round))
    }

    val k = v.scale(0f, HEAD_Z)
    val c = v.at(0f, HEAD_Y, HEAD_Z)
    val rx = HEAD_R * k * s
    val ry = HEAD_RY * k * s
    // Turned away, the snout is behind the head, which covers all but what sticks out.
    if (v.facing(0f) < 0f) drawSnout(v, k)
    drawOval(
        Brush.verticalGradient(listOf(FUR_TOP, FUR_BOTTOM), startY = c.y - ry * 1.1f, endY = c.y + ry * 1.6f),
        Offset(c.x - rx, c.y - ry),
        Size(rx * 2f, ry * 2f)
    )

    // Tabby marks: the M on the forehead, lines on the cheeks, rings round the back.
    headStroke(v, -16f, 60f, -11f, 34f)
    headStroke(v, 0f, 66f, 0f, 38f)
    headStroke(v, 16f, 60f, 11f, 34f)
    for (side in floatArrayOf(-1f, 1f)) {
        headStroke(v, 64f * side, 8f, 84f * side, 4f)
        headStroke(v, 62f * side, -8f, 82f * side, -12f)
    }
    for (el in floatArrayOf(44f, 26f, 8f)) headStroke(v, 118f, el, 180f, el + 4f, 242f, el)

    // The face.
    val front = v.facing(0f)
    if (front >= 0f) drawSnout(v, k)
    // The meme's stare: big dark eyes in a pale rim, lids half down, one a touch larger.
    for (side in floatArrayOf(-1f, 1f)) {
        val f = v.headFacing(29f * side, 6f)
        if (f <= 0.04f) continue
        val eye = v.at(headPoint(29f * side, 6f))
        val big = if (side > 0f) 1.08f else 1f
        val ex = 10.5f * big * f * k * s
        val ey = 11.5f * big * k * s
        val alpha = (f * 4f).coerceAtMost(1f)
        if (blink) {
            drawLine(INK.copy(alpha = alpha), Offset(eye.x - ex, eye.y), Offset(eye.x + ex, eye.y), 3f * s, StrokeCap.Round)
            continue
        }
        // Pale fur round the eye, the eye itself, and a pale lid over its top third.
        ellipseAt(eye, ex * 1.3f, ey * 1.22f, EYE_RIM.copy(alpha = alpha * 0.9f), false, s)
        val ball = Path().apply { addOval(Rect(eye.x - ex, eye.y - ey, eye.x + ex, eye.y + ey)) }
        drawPath(ball, INK.copy(alpha = alpha))
        if (f > 0.3f) {
            drawCircle(Color.White.copy(alpha = 0.9f), 2.6f * k * s * f, Offset(eye.x + 3.6f * f * k * s, eye.y - 0.5f * k * s))
        }
        val lid = eye.y - ey + ey * 0.62f
        clipPath(ball) {
            drawRect(EYE_RIM.copy(alpha = alpha), Offset(eye.x - ex, eye.y - ey), Size(ex * 2f, lid - (eye.y - ey)))
        }
        drawLine(INK.copy(alpha = alpha), Offset(eye.x - ex * 1.08f, lid), Offset(eye.x + ex * 1.08f, lid), 2.8f * s, StrokeCap.Round)
    }
    if (front >= 0f) drawSnoutFace(v, k)

    drawOval(INK, Offset(c.x - rx, c.y - ry), Size(rx * 2f, ry * 2f), style = Stroke(3f * s))
}

private val LEGS = listOf(-27f to 34f, 27f to 34f, -27f to -68f, 27f to -68f)

/** The cat, turned [angle] degrees about its vertical axis; 0 faces the viewer. */
private fun DrawScope.drawCatOnce(angle: Float, blink: Boolean) {
    val v = CatView(size, angle)
    // The legs never cover the body, so they go first, the far ones before the near.
    for ((lx, lz) in LEGS.sortedBy { (x, z) -> v.depth(x, z) }) drawLeg(v, lx, lz)
    // The rest by depth. Side on, the head sits over the front of the body and the tail
    // over its back, hence the small leads they are given.
    val parts = listOf<Pair<Float, () -> Unit>>(
        v.depth(0f, BODY_Z) to { drawBody(v) },
        v.depth(0f, HEAD_Z) + 20f to { drawHead(v, blink) },
        TAIL.map { v.depth(it.x, it.z) }.average().toFloat() + 10f to { drawTail(v) }
    )
    for ((_, draw) in parts.sortedBy { it.first }) draw()
}

/**
 * The cat with its shadow, turned [angle] degrees. [speed], in degrees a second, adds a
 * blur of fainter copies behind a fast turn.
 */
internal fun DrawScope.drawSpinningCat(angle: Float, blink: Boolean = false, speed: Float = 0f) {
    val v = CatView(size, angle)
    val ground = v.at(0f, 0f, BODY_Z)
    val half = sqrt((62f * v.cosA).let { it * it } + (100f * v.sinA).let { it * it }) * v.s
    drawOval(
        Color.Black.copy(alpha = 0.16f),
        topLeft = Offset(ground.x - half, ground.y - 7f * v.s),
        size = Size(half * 2f, 14f * v.s)
    )
    if (abs(speed) > 500f) {
        val step = speed / 160f
        for ((i, alpha) in listOf(3 to 0.07f, 2 to 0.12f, 1 to 0.2f)) {
            drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint().apply { this.alpha = alpha }) }
            drawCatOnce(angle - step * i, blink)
            drawIntoCanvas { it.restore() }
        }
    }
    drawCatOnce(angle, blink)
}
