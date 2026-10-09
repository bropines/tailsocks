package io.github.bropines.tailscaled.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.bropines.tailscaled.R
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * Behind a long press on the Claude credit in About: «Learning to vibe-code, Potter?» —
 * «Yes. So what?». A laptop writes its own code while a wand waves over the keys. All of it
 * is drawn here; no film still.
 */

/** Fixed palette: a night scene in either colour scheme. */
private val SKY_TOP = Color(0xFF0E1230)
private val SKY_BOTTOM = Color(0xFF2A1B4A)
private val STAR = Color(0xFFFFF4D6)
private val LAPTOP = Color(0xFF3A3F4B)
private val LAPTOP_EDGE = Color(0xFF5B6272)
private val SCREEN = Color(0xFF12161F)
private val SPARK = Color(0xFFFFD36E)
private val WAND = Color(0xFF6B4226)
private val GLASSES = Color(0xFF1B1B1B)
private val CODE_COLOURS = listOf(Color(0xFF7FB4FF), Color(0xFFC792EA), Color(0xFF9CDC8C), Color(0xFFFFCB6B), Color(0xFF89DDFF))

/** A line of "code": its indent and the lengths of its tokens, as fractions of the screen. */
private class CodeLine(val indent: Int, val tokens: List<Float>)

private val CODE: List<CodeLine> = run {
    val rnd = kotlin.random.Random(42)
    List(40) { i ->
        val indent = listOf(0, 1, 1, 2, 2, 1, 0, 1)[i % 8]
        CodeLine(indent, List(1 + rnd.nextInt(4)) { 0.06f + rnd.nextFloat() * 0.16f })
    }
}

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float)

private val STARS = run {
    val rnd = kotlin.random.Random(7)
    List(70) { Star(rnd.nextFloat(), rnd.nextFloat() * 0.55f, 0.6f + rnd.nextFloat() * 1.6f, rnd.nextFloat() * 6.28f) }
}

/** Where the wand's tip is [t] seconds in, as fractions of the laptop's keyboard box: a slow figure-eight. */
private fun wandTip(t: Float): Offset = Offset(0.5f + 0.32f * sin(t * 1.3f), 0.45f + 0.22f * sin(t * 2.6f))

@Composable
internal fun VibeCodingScene(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    var t by remember { mutableFloatStateOf(0f) }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                shown.animateTo(0f, tween(250))
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(300)) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t = (it - start) / 1e9f }
    }
    val question = stringResource(R.string.egg_vibe_question)
    val answer = stringResource(R.string.egg_vibe_answer)

    Dialog(
        onDismissRequest = leave,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = shown.value }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { leave() }
                .semantics { contentDescription = "$question $answer" }
        ) {
            Canvas(Modifier.fillMaxSize()) { drawVibeScene(t) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 96.dp),
                horizontalAlignment = Alignment.Start
            ) {
                AnimatedVisibility(visible = t > 0.6f, enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.8f)) {
                    Bubble(question, fromLeft = true)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 96.dp),
                horizontalAlignment = Alignment.End
            ) {
                AnimatedVisibility(visible = t > 2.2f, enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.8f)) {
                    Bubble(answer, fromLeft = false)
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, fromLeft: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (fromLeft) Color(0xFFF3F0E6) else SPARK,
        contentColor = Color(0xFF1B1B1B),
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(max = 300.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

private fun DrawScope.drawVibeScene(t: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(SKY_TOP, SKY_BOTTOM)))
    for (s in STARS) {
        val twinkle = 0.45f + 0.55f * (0.5f + 0.5f * sin(t * 2f + s.phase))
        drawCircle(STAR.copy(alpha = twinkle), s.r * density, Offset(s.x * w, s.y * h))
    }

    // The laptop, centred a little below the middle.
    val lw = min(w * 0.82f, 520f * density)
    val cx = w / 2f
    val screenH = lw * 0.6f
    val screenTop = h * 0.5f - screenH * 0.75f
    val screenLeft = cx - lw / 2f
    drawRoundRect(LAPTOP_EDGE, Offset(screenLeft, screenTop), Size(lw, screenH), CornerRadius(14f * density))
    val inset = 10f * density
    val sl = screenLeft + inset
    val st = screenTop + inset
    val sw = lw - inset * 2
    val sh = screenH - inset * 2
    drawRoundRect(SCREEN, Offset(sl, st), Size(sw, sh), CornerRadius(6f * density))

    // Code that writes itself: one token every 90 ms, scrolling once the screen is full.
    val lineH = sh / 11f
    val typed = (t * 11f).toInt()
    var tokensLeft = typed
    val lines = mutableListOf<Pair<CodeLine, Int>>()
    for (line in CODE) {
        if (tokensLeft <= 0) break
        val n = min(line.tokens.size, tokensLeft)
        lines += line to n
        tokensLeft -= n
    }
    val visible = lines.takeLast(10)
    visible.forEachIndexed { row, (line, n) ->
        var x = sl + 12f * density + line.indent * 18f * density
        val y = st + 10f * density + row * lineH
        for (k in 0 until n) {
            val len = line.tokens[k] * sw
            drawRoundRect(
                CODE_COLOURS[(row * 3 + k + typed / 7) % CODE_COLOURS.size],
                Offset(x, y),
                Size(len, lineH * 0.42f),
                CornerRadius(3f * density)
            )
            x += len + 8f * density
        }
    }
    // A blinking cursor after the last token.
    visible.lastOrNull()?.let { (line, n) ->
        val row = visible.lastIndex
        val x = sl + 12f * density + line.indent * 18f * density +
            line.tokens.take(n).sumOf { (it * sw).toDouble() }.toFloat() + n * 8f * density
        if (sin(t * 8f) > 0f) {
            drawRect(STAR, Offset(x, st + 10f * density + row * lineH), Size(3f * density, lineH * 0.5f))
        }
    }

    // The base: a keyboard deck seen from the front.
    val baseTop = screenTop + screenH
    val deck = Path().apply {
        moveTo(screenLeft - lw * 0.06f, baseTop + lw * 0.16f)
        lineTo(screenLeft + lw * 1.06f, baseTop + lw * 0.16f)
        lineTo(screenLeft + lw, baseTop)
        lineTo(screenLeft, baseTop)
        close()
    }
    drawPath(deck, LAPTOP)
    drawPath(deck, LAPTOP_EDGE, style = Stroke(2f * density))
    for (r in 0 until 3) for (c in 0 until 12) {
        val fx = (c + 0.5f) / 12f
        val fy = (r + 0.6f) / 3.6f
        val rowW = lw * (1f + 0.12f * fy)
        val kx = cx - rowW / 2f + fx * rowW
        val ky = baseTop + fy * lw * 0.16f
        drawRect(LAPTOP_EDGE, Offset(kx - 6f * density, ky - 2f * density), Size(12f * density, 5f * density))
    }

    // Round glasses resting on the deck.
    val gy = baseTop + lw * 0.115f
    val gx = cx - lw * 0.3f
    val gr = lw * 0.035f
    drawCircle(GLASSES, gr, Offset(gx - gr * 1.15f, gy), style = Stroke(2.5f * density))
    drawCircle(GLASSES, gr, Offset(gx + gr * 1.15f, gy), style = Stroke(2.5f * density))
    drawLine(GLASSES, Offset(gx - gr * 0.15f, gy), Offset(gx + gr * 0.15f, gy), 2.5f * density)

    // The wand over the keys, and the sparks it leaves.
    val boxL = screenLeft
    val boxT = baseTop - lw * 0.25f
    fun tipAt(time: Float): Offset = wandTip(time).let { Offset(boxL + it.x * lw, boxT + it.y * lw * 0.3f) }
    for (i in 1..14) {
        val past = t - i * 0.06f
        if (past < 0f) continue
        val p = tipAt(past)
        val drift = i * 3f * density
        drawCircle(SPARK.copy(alpha = (1f - i / 15f) * 0.85f), (4.5f - i * 0.25f).coerceAtLeast(1f) * density, Offset(p.x, p.y - drift))
    }
    val tip = tipAt(t)
    val handle = Offset(tip.x + lw * 0.32f, tip.y + lw * 0.22f)
    drawLine(WAND, handle, tip, 7f * density, StrokeCap.Round)
    drawCircle(SPARK, 6f * density, tip)
    val glow = 0.5f + 0.5f * abs(sin(t * 5f))
    drawCircle(SPARK.copy(alpha = 0.25f * glow), 16f * density, tip)
    for (k in 0 until 4) {
        val a = (t * 3f + k * PI.toFloat() / 2f)
        drawLine(
            SPARK.copy(alpha = 0.7f),
            Offset(tip.x + cos(a) * 9f * density, tip.y + sin(a) * 9f * density),
            Offset(tip.x + cos(a) * 15f * density, tip.y + sin(a) * 15f * density),
            2f * density,
            StrokeCap.Round
        )
    }
}
