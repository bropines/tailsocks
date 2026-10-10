package io.github.bropines.tailscaled.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.StatusAsides
import io.github.bropines.tailscaled.ui.theme.findActivity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * TailCat's add button, and what a paw on it leads to.
 */

/** The last few taps on the paw, each throwing a handful of paw prints. */
internal class PawBursts {
    val at = FloatArray(4) { -1e6f }
    val combo = IntArray(4)
    private var next = 0

    fun add(time: Float, combo: Int) {
        at[next] = time
        this.combo[next] = combo
        next = (next + 1) % at.size
    }
}

/**
 * A tap adds a connection. A long press turns the plus into a paw, and taps on the paw count
 * up a combo instead; the paw turns back after a moment of rest, and the tenth tap in a row
 * lets out what it was hiding.
 */
@Composable
internal fun TailcatAddButton(onAdd: () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val vibes = remember { if (inPreview) null else PawVibes(context) }
    var paw by remember { mutableStateOf(false) }
    var combo by remember { mutableIntStateOf(0) }
    // Kept apart from the count, so a lost combo fades out still showing its number.
    var comboAlive by remember { mutableStateOf(false) }
    val clock = remember { mutableFloatStateOf(0f) }
    val lastTap = remember { mutableFloatStateOf(-1e6f) }
    val bursts = remember { PawBursts() }
    var showFrom by remember { mutableStateOf<Long?>(null) }
    var refusing by remember { mutableStateOf(false) }
    val fab = remember { mutableStateOf(Offset.Unspecified) }

    LaunchedEffect(paw, combo, comboAlive) {
        if (!paw) return@LaunchedEffect
        if (comboAlive) {
            delay(PawHype.COMBO_WINDOW_MS)
            comboAlive = false
        } else {
            delay(PawHype.PAW_IDLE_MS)
            paw = false
        }
    }
    // The badge, the prints and the charge move on their own clock while the paw is out,
    // and until the last prints have landed.
    LaunchedEffect(paw) {
        val start = withFrameNanos { it } - (clock.floatValue * 1e6f).toLong()
        val tick: (Long) -> Unit = { clock.floatValue = (it - start) / 1e6f }
        while (paw || clock.floatValue < lastTap.floatValue + PawHype.BURST_MS) withFrameNanos(tick)
    }

    showFrom?.let { tappedAt ->
        PawShowOverlay(origin = { fab.value }, tappedAt = tappedAt, vibes = vibes, onDismiss = { showFrom = null })
    }
    if (refusing) PawNoOverlay(vibes = vibes, onDismiss = { refusing = false })

    TailcatAddButtonFace(
        paw = paw,
        combo = combo,
        comboShown = comboAlive,
        now = clock,
        lastTap = lastTap,
        bursts = bursts,
        modifier = Modifier.onGloballyPositioned {
            val p = it.positionOnScreen()
            fab.value = Offset(p.x + it.size.width / 2f, p.y + it.size.height / 2f)
        },
        onClick = {
            if (!paw) {
                onAdd()
                return@TailcatAddButtonFace
            }
            combo = if (comboAlive) combo + 1 else 1
            comboAlive = true
            lastTap.floatValue = clock.floatValue
            bursts.add(clock.floatValue, combo)
            when {
                combo >= PawHype.GOAL -> {
                    vibes?.thump() ?: view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    comboAlive = false
                    paw = false
                    val launch = if (inPreview) 1 else StatusAsides.bump(context, LAUNCHES)
                    if (PawHype.refuses(launch)) refusing = true else showFrom = SystemClock.uptimeMillis()
                }
                combo == PawHype.GOAL - 1 -> vibes?.charge() ?: view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                else -> vibes?.tap(combo) ?: view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        },
        onLongClick = {
            if (!paw) {
                paw = true
                comboAlive = false
                combo = 0
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    )
}

/**
 * The button as it looks: a plus or a paw, the combo badge over it, and the paw prints and
 * the charge drawn round it. [now] and [lastTap] are ms on the paw's clock, read only while
 * drawing, so the hype animates without recomposing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TailcatAddButtonFace(
    paw: Boolean,
    combo: Int,
    comboShown: Boolean,
    now: FloatState,
    lastTap: FloatState,
    bursts: PawBursts,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val printColors = remember(scheme) { arrayOf(scheme.primary, scheme.tertiary, scheme.secondary) }
    val density = LocalDensity.current
    val ring = remember(density) { Stroke(width = with(density) { 2.5.dp.toPx() }) }
    // Only a combo still going holds a charge: a lapsed one starts again from one.
    val live = paw && comboShown
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // The prints fly from behind the button and behind the badge.
        modifier = Modifier.drawBehind {
            val fab = Offset(size.width - 28.dp.toPx(), size.height - 28.dp.toPx())
            drawPrints(bursts, now.floatValue, fab, printColors)
        }
    ) {
        AnimatedVisibility(
            visible = comboShown && combo >= 2,
            enter = scaleIn(initialScale = 0.5f) + fadeIn(),
            exit = scaleOut(targetScale = 0.5f) + fadeOut()
        ) {
            ComboBadge(combo, now, lastTap)
        }
        Box(
            modifier = modifier
                .size(56.dp)
                .drawBehind {
                    val t = now.floatValue
                    val charge = if (live) PawHype.charge(combo, t) else 0f
                    if (charge > 0f) drawCharge(charge, t, scheme.primary, ring)
                }
                .graphicsLayer { translationX = if (live) PawHype.tremble(combo, now.floatValue).dp.toPx() else 0f }
        ) {
            // One tap from the goal the button runs hot.
            val charged = live && combo >= PawHype.GOAL - 1
            val container = if (charged) scheme.primary else FloatingActionButtonDefaults.containerColor
            Surface(
                shape = FloatingActionButtonDefaults.shape,
                color = container,
                contentColor = contentColorFor(container),
                shadowElevation = 6.dp
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = paw,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.3f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.3f) + fadeOut())
                        },
                        label = "paw"
                    ) { isPaw ->
                        if (isPaw) {
                            Icon(
                                Icons.Default.Pets,
                                null,
                                modifier = Modifier.graphicsLayer {
                                    val k = PawHype.pawScale(now.floatValue - lastTap.floatValue)
                                    scaleX = k
                                    scaleY = k
                                    rotationZ = if (combo % 2 == 0) -8f else 8f
                                }
                            )
                        } else {
                            Icon(Icons.Default.Add, stringResource(R.string.tailcat_add))
                        }
                    }
                }
            }
        }
    }
}

/** The count, swelling and rattling harder as it climbs, and hotter in colour. */
@Composable
private fun ComboBadge(combo: Int, now: FloatState, lastTap: FloatState) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when {
        combo >= PawHype.GOAL - 1 -> scheme.primary to scheme.onPrimary
        combo >= 5 -> scheme.tertiary to scheme.onTertiary
        else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier.graphicsLayer {
            val k = PawHype.badgeScale(combo, now.floatValue - lastTap.floatValue)
            scaleX = k
            scaleY = k
            rotationZ = PawHype.badgeTilt(combo, now.floatValue)
            transformOrigin = TransformOrigin(0.7f, 1f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Pets, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("×$combo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        }
    }
}

/** Paw prints thrown out of the button by each tap, more and farther as the combo climbs. */
private fun DrawScope.drawPrints(bursts: PawBursts, now: Float, from: Offset, colors: Array<Color>) {
    val dp = density
    for (b in bursts.at.indices) {
        val age = now - bursts.at[b]
        if (age < 0f || age > PawHype.BURST_MS) continue
        val combo = bursts.combo[b]
        val u = age / PawHype.BURST_MS
        val out = PawShow.easeOutCubic(u)
        val n = PawHype.burstSize(combo)
        for (j in 0 until n) {
            // Up and to the left, away from the corner the button sits in, fanned evenly.
            val spread = (j + 0.5f) / n - 0.5f + (PawShow.noise(combo, j) - 0.5f) * 0.3f / n
            val a = (-125f + spread * 130f) * (PI / 180).toFloat()
            val reach = (46f + 22f * PawShow.noise(combo, j + 50) + 5f * combo) * dp
            val x = from.x + cos(a) * reach * out
            val y = from.y + sin(a) * reach * out
            val size = (4.2f + 1.2f * PawShow.noise(combo, j + 90)) * dp * (1f - 0.3f * u)
            val color = colors[(j + combo) % colors.size].copy(alpha = 1f - u * u)
            drawPrint(x, y, size, a + (PI / 2).toFloat(), color)
        }
    }
}

/** A paw print: a pad and four toes, turned to [heading]. */
private fun DrawScope.drawPrint(x: Float, y: Float, size: Float, heading: Float, color: Color) {
    val c = cos(heading)
    val s = sin(heading)
    drawOval(color, Offset(x - size, y - size * 0.82f), Size(size * 2f, size * 1.64f))
    for (t in 0..3) {
        val tx = (t - 1.5f) * size * 0.78f
        val ty = -size * (if (t == 0 || t == 3) 1.25f else 1.6f)
        drawCircle(color, size * 0.42f, Offset(x + tx * c - ty * s, y + tx * s + ty * c))
    }
}

/** One tap from the goal: a ring breathes round the button and sparks are drawn into it. */
private fun DrawScope.drawCharge(charge: Float, now: Float, color: Color, ring: Stroke) {
    val dp = density
    drawCircle(color.copy(alpha = 0.25f + 0.35f * charge), radius = (34f + 5f * charge) * dp, style = ring)
    for (j in 0 until 8) {
        val phase = (now / 520f + j / 8f) % 1f
        val a = (j * 45f + now * 0.12f) * (PI / 180).toFloat()
        val d = (72f - 40f * phase) * dp
        drawCircle(
            color.copy(alpha = 0.7f * sin(PI.toFloat() * phase)),
            radius = 2.6f * dp,
            center = Offset(center.x + cos(a) * d, center.y + sin(a) * d)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The show.

/**
 * The cat, full screen on the theme's own background (black under AMOLED), until a calm tap,
 * Back, or the app leaving the screen. [tappedAt] is the uptime of the tap that summoned it.
 */
@Composable
private fun PawShowOverlay(origin: () -> Offset, tappedAt: Long, vibes: PawVibes?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val inPreview = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    val calm = remember { !inPreview && !animationsOn(context) }
    val guard = remember { TapGuard(tappedAt) }
    val time = remember { mutableFloatStateOf(0f) }
    val gone = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    val stage = remember { mutableStateOf(Offset.Zero) }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            vibes?.stop()
            scope.launch {
                gone.animateTo(1f, tween(if (calm) 0 else 200))
                onDismiss()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        if (!inPreview) StatusAsides.bump(context, StatusAsides.SPINS)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onDismiss() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // One clock for the picture and the motor: each frame buzzes for the letter it crossed.
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var previous = -1f
        val tick: (Long) -> Unit = { now ->
            val t = (now - start) / 1e6f
            if (!leaving) {
                val cue = PawShow.lastCue(previous, t)
                if (cue >= 0) vibes?.letter(cue)
            }
            previous = t
            time.floatValue = t
        }
        try {
            while (true) withFrameNanos(tick)
        } finally {
            vibes?.stop()
        }
    }

    Dialog(
        onDismissRequest = leave,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val description = stringResource(R.string.tailcat_paw_show_cd)
        val close = stringResource(R.string.action_close)
        PawShowStage(
            time = time,
            calm = calm,
            origin = {
                val o = origin()
                if (o.isSpecified) o - stage.value else Offset.Unspecified
            },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { stage.value = it.positionOnScreen() }
                .graphicsLayer {
                    // Without motion the show fades in instead of opening as a circle.
                    alpha = (if (calm) min(1f, time.floatValue / 200f) else 1f) * (1f - gone.value)
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = close
                ) { if (guard.tap(SystemClock.uptimeMillis())) leave() }
                .semantics { contentDescription = description }
        )
    }
}

/**
 * Every fifth launch: "NO!" stamped over the theme's background instead of the show, then the
 * long version opens in whatever plays YouTube links. Taps are swallowed (the combo is still
 * going); Back, or the app leaving the screen, ends it without the link.
 */
@Composable
private fun PawNoOverlay(vibes: PawVibes?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val calm = remember { !animationsOn(context) }
    val time = remember { mutableFloatStateOf(0f) }
    var done by remember { mutableStateOf(false) }
    val finish: () -> Unit = {
        if (!done) {
            done = true
            onDismiss()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) finish() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var echoed = false
        val tick: (Long) -> Unit = { now ->
            time.floatValue = (now - start) / 1e6f
            // The tenth tap's thump, and a second one as the stamp settles: "no-NO".
            if (!echoed && time.floatValue >= 180f) {
                echoed = true
                vibes?.thump()
            }
        }
        while (time.floatValue < PawHype.NO_HOLD_MS) withFrameNanos(tick)
        if (done) return@LaunchedEffect
        val activity = context.findActivity()
        runCatching {
            (activity ?: context).startActivity(
                Intent(Intent.ACTION_VIEW, PawHype.NO_LINK.toUri()).apply { if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
        finish()
    }

    Dialog(
        onDismissRequest = finish,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        PawNoStage(
            time = time,
            calm = calm,
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        )
    }
}

/** "NO!" at [time] ms after it was summoned, about [NO_WIDTH] of the stage wide once it settles. */
@Composable
internal fun PawNoStage(time: FloatState, calm: Boolean, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Black)
    val measurer = rememberTextMeasurer()
    val natural = remember(style) { measurer.measure(NO_TEXT, style).size.width.toFloat() }
    BoxWithConstraints(
        modifier.background(MaterialTheme.colorScheme.surface).clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val fit = with(LocalDensity.current) { maxWidth.toPx() } * NO_WIDTH / natural.coerceAtLeast(1f)
        Text(
            NO_TEXT,
            style = style,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .wrapContentSize(unbounded = true)
                .graphicsLayer {
                    val t = time.floatValue
                    val s = fit * (if (calm) 1f else PawHype.noScale(t))
                    scaleX = s
                    scaleY = s
                    rotationZ = if (calm) -7f else PawHype.noTilt(t)
                }
                .semantics { contentDescription = NO_TEXT }
        )
    }
}

/**
 * The show at [time] ms, drawn; [origin] is where on this canvas the circle opens from. Time is
 * read only while drawing, so the show plays without recomposing.
 */
@Composable
internal fun PawShowStage(
    time: FloatState,
    calm: Boolean,
    modifier: Modifier = Modifier,
    origin: () -> Offset = { Offset.Unspecified }
) {
    val stage = remember { PawStage() }
    val scene = remember { PawScene() }
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) {
        PawColors(
            background = scheme.surface.toArgb(),
            ink = scheme.onSurface.toArgb(),
            accent = scheme.primary.toArgb(),
            accent2 = scheme.tertiary.toArgb(),
            tint = scheme.primaryContainer.toArgb()
        )
    }
    Canvas(modifier) {
        PawShow.sceneAt(time.floatValue, calm, scene)
        // Where the circle opens from matters only while it opens.
        val o = if (scene.reveal < 1f) origin() else Offset.Unspecified
        val ox = if (o.isSpecified) o.x else size.width - 44.dp.toPx()
        val oy = if (o.isSpecified) o.y else size.height - 44.dp.toPx()
        drawIntoCanvas { stage.draw(it.nativeCanvas, size.width, size.height, scene, colors, ox, oy) }
    }
}

private fun animationsOn(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

// ---------------------------------------------------------------------------------------------
// The motor.

/**
 * The buzzes: one per sung letter, as long as its syllable; taps on the paw that grow with
 * the combo, a rising charge on the ninth and a thump on the tenth. Every effect is built
 * once; without a motor all of it is silent, and before Android 8 it buzzes at one strength.
 */
internal class PawVibes(context: Context) {
    private val vibrator: Vibrator? = run {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        v?.takeIf { it.hasVibrator() }
    }
    private val levels = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator?.hasAmplitudeControl() == true

    // Typed as Any so that nothing names VibrationEffect below Android 8.
    private val letters: Array<Any?> = Array(PawShow.CUES) { c ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) oneShot(PawShow.BUZZ_MS[c], PawShow.BUZZ_LEVEL[c]) else null
    }
    private val taps: Array<Any?> = Array(PawHype.GOAL) { n ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) oneShot(12 + n, 50 + n * 20) else null
    }
    private val chargeEffect: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (levels) VibrationEffect.createWaveform(longArrayOf(30, 30, 30, 30, 30, 40), intArrayOf(40, 70, 105, 145, 195, 255), -1)
        else VibrationEffect.createWaveform(longArrayOf(0, 20, 40, 25, 25, 35), -1)
    } else null
    private val thumpEffect: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) oneShot(80, 255) else null

    // Letters are media, taps are touch: each follows its own intensity setting.
    private val media: Any? = attributes(media = true)
    private val touch: Any? = attributes(media = false)

    fun letter(cue: Int) = play(letters[cue], PawShow.BUZZ_MS[cue].toLong(), media)
    fun tap(combo: Int) = play(taps[(combo - 1).coerceIn(0, taps.size - 1)], 12L + combo, touch)
    fun charge() = play(chargeEffect, 120L, touch)
    fun thump() = play(thumpEffect, 80L, touch)

    fun stop() {
        try {
            vibrator?.cancel()
        } catch (_: RuntimeException) {
        }
    }

    private fun oneShot(ms: Int, level: Int): Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createOneShot(ms.toLong(), if (levels) level else VibrationEffect.DEFAULT_AMPLITUDE)
        } else null

    private fun attributes(media: Boolean): Any? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            VibrationAttributes.createForUsage(if (media) VibrationAttributes.USAGE_MEDIA else VibrationAttributes.USAGE_TOUCH)
        else -> AudioAttributes.Builder()
            .setUsage(if (media) AudioAttributes.USAGE_GAME else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build()
    }

    private fun play(effect: Any?, fallbackMs: Long, attributes: Any?) {
        val v = vibrator ?: return
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    v.vibrate(effect as VibrationEffect, attributes as VibrationAttributes)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    @Suppress("DEPRECATION") v.vibrate(effect as VibrationEffect, attributes as AudioAttributes)
                else ->
                    @Suppress("DEPRECATION") v.vibrate(fallbackMs, attributes as AudioAttributes)
            }
        } catch (_: RuntimeException) {
            // A motor that refuses is no reason to stop the show.
        }
    }
}

/** Launches of the show, refusals included; the SPINS aside counts only what was shown. */
private const val LAUNCHES = "paw_launches"
private const val NO_TEXT = "NO!"
/** The settled stamp's share of the stage's width. */
private const val NO_WIDTH = 0.72f
