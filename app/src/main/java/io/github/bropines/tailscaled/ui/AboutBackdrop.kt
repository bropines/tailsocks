package io.github.bropines.tailscaled.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/*
 * The full-screen scene the About dialog opens (MainScreen in MainActivity.kt): a fixed
 * palette, the two figures drawn on a Canvas, and the timeline that walks them in.
 */

// ---------------------------------------------------------------------------------------------
// About: full-screen backdrop.

/** Fixed palette: the scene is the same in both colour schemes. */
private val BACKDROP_SKY_TOP = Color(0xFF6F777F)
private val BACKDROP_SKY_BOTTOM = Color(0xFFAAB1B8)
private val BACKDROP_HAZE = Color(0xFFD5D9DE)
private val BACKDROP_INK = Color(0xFF262A30)
private val BACKDROP_PALE = Color(0xFFEEF0F3)

private fun DrawScope.drawBackdropWalker(x: Float, y: Float, s: Float, step: Float, color: Color) {
    fun px(u: Float) = x + u * s
    fun py(u: Float) = y + u * s
    val body = Path().apply {
        moveTo(px(6f), py(0f))
        val n = 14
        for (i in 0..n) {
            val a = PI.toFloat() * (1f - i / n.toFloat() * 0.8f)
            val r = if (i % 2 == 1) 30f else 24f
            lineTo(px(28f + r * cos(a)), py(-6f - r * sin(a)))
        }
        lineTo(px(58f), py(-14f))
        lineTo(px(66f), py(-7f))
        lineTo(px(60f), py(-3f))
        lineTo(px(54f), py(0f))
        close()
    }
    drawPath(body, color)
    val lift = sin(step) * 2f
    drawRect(color, topLeft = Offset(px(16f), py(-2f)), size = Size(5f * s, (7f + lift) * s))
    drawRect(color, topLeft = Offset(px(40f), py(-2f)), size = Size(5f * s, (7f - lift) * s))
    drawCircle(BACKDROP_PALE.copy(alpha = color.alpha), radius = 1.4f * s, center = Offset(px(56f), py(-9f)))
    drawLine(color, Offset(px(40f), py(-26f)), Offset(px(58f), py(-44f)), strokeWidth = 1.8f * s, cap = StrokeCap.Round)
    drawCircle(color, radius = 7f * s, center = Offset(px(61f), py(-49f)))
}

private fun DrawScope.drawBackdropStander(x: Float, y: Float, s: Float, color: Color) {
    fun px(u: Float) = x + u * s
    fun py(u: Float) = y + u * s
    drawOval(color, topLeft = Offset(px(30f), py(-78f)), size = Size(78f * s, 40f * s))
    val neck = Path().apply {
        moveTo(px(40f), py(-72f))
        lineTo(px(12f), py(-118f))
        lineTo(px(28f), py(-122f))
        lineTo(px(52f), py(-58f))
        close()
    }
    drawPath(neck, color)
    val head = Path().apply {
        moveTo(px(10f), py(-122f))
        lineTo(px(30f), py(-124f))
        lineTo(px(24f), py(-108f))
        lineTo(px(-4f), py(-98f))
        lineTo(px(-8f), py(-104f))
        close()
    }
    drawPath(head, color)
    drawLine(color, Offset(px(22f), py(-122f)), Offset(px(26f), py(-134f)), strokeWidth = 3f * s, cap = StrokeCap.Round)
    val w = 5f * s
    drawLine(color, Offset(px(44f), py(-50f)), Offset(px(40f), py(0f)), w, StrokeCap.Round)
    drawLine(color, Offset(px(56f), py(-50f)), Offset(px(58f), py(0f)), w, StrokeCap.Round)
    drawLine(color, Offset(px(92f), py(-50f)), Offset(px(88f), py(0f)), w, StrokeCap.Round)
    drawLine(color, Offset(px(102f), py(-50f)), Offset(px(108f), py(0f)), w, StrokeCap.Round)
    val tail = Path().apply {
        moveTo(px(106f), py(-70f))
        quadraticTo(px(126f), py(-60f), px(122f), py(-24f))
    }
    drawPath(tail, color, style = Stroke(width = 4f * s, cap = StrokeCap.Round))
}

/** A value that rises from 0 to 1 between [from] and [to] seconds, and holds. */
private fun rampAt(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

/**
 * Full-screen scene over the About dialog. Its own window, so it sits above the dialog
 * rather than under it; it is disposed with the dialog's block, so closing the dialog ends
 * it. A tap anywhere lets it go.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AboutBackdrop(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val leaveSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val clearing = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    var frameNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { frameNs = it - start }
    }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                clearing.animateTo(1f, leaveSpec)
                onDismiss()
            }
        }
    }

    Dialog(
        onDismissRequest = leave,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { leave() }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val t = frameNs / 1e9f
                val cover = rampAt(t, 0f, 1.8f) * (1f - clearing.value)
                if (cover <= 0.005f) return@Canvas
                val w = size.width
                val h = size.height

                drawRect(Brush.verticalGradient(listOf(BACKDROP_SKY_TOP, BACKDROP_SKY_BOTTOM)), alpha = cover)

                // Something large, far off, that comes and goes.
                val far = rampAt(t, 3f, 5.5f) * (1f - rampAt(t, 8f, 10.5f)) * cover
                if (far > 0.01f) {
                    val fs = w / 320f
                    val fx = w * 0.52f
                    val fy = h * 0.60f
                    drawBackdropStander(fx, fy, fs * 1.04f, BACKDROP_PALE.copy(alpha = far * 0.18f))
                    drawBackdropStander(fx, fy, fs, BACKDROP_PALE.copy(alpha = far * 0.5f))
                }

                // Something small that comes in from the left and stops.
                val walk = rampAt(t, 1f, 10f)
                val eased = walk * (2f - walk)
                val ws = w / 300f
                val wx = -80f * ws + (w * 0.30f + 80f * ws) * eased
                val moving = walk < 1f
                val bob = if (moving) abs(sin(t * 6f)) * 1.5f * ws else 0f
                drawBackdropWalker(wx, h * 0.70f - bob, ws, if (moving) t * 6f else 0f, BACKDROP_INK.copy(alpha = cover))

                // Drifting haze on top of everything.
                for (i in 0 until 7) {
                    val speed = 0.012f + 0.006f * i
                    val cx = w * (((i * 0.37f + t * speed) % 1.3f) - 0.15f)
                    val cy = h * (0.12f + 0.12f * i) + sin(t * 0.4f + i) * h * 0.02f
                    val radius = w * (0.32f + 0.08f * (i % 3))
                    val center = Offset(cx, cy)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(BACKDROP_HAZE.copy(alpha = 0.55f * cover), Color.Transparent),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Deliberately not in strings.xml, and deliberately not translated: this is a
                // line from a Russian film, quoted as itself, in the one place the app is
                // allowed a private joke. An English reader sees the title and year below and
                // can look it up; an English rendering of the line would not be the line.
                Text(
                    "Если лошадь ляжет спать, она захлебнётся в тумане?",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    color = BACKDROP_PALE,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        alpha = rampAt(frameNs / 1e9f, 5.5f, 7.5f) * (1f - clearing.value)
                    }
                )
                Text(
                    "«Ёжик в тумане», 1975",
                    style = MaterialTheme.typography.labelMedium,
                    color = BACKDROP_PALE.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        alpha = rampAt(frameNs / 1e9f, 8f, 9.5f) * (1f - clearing.value)
                    }
                )
            }
        }
    }
}
