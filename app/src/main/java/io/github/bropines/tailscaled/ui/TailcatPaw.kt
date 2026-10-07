package io.github.bropines.tailscaled.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.StatusAsides
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/*
 * TailCat's add button, and what a paw on it leads to.
 */

/** How long a combo waits for the next tap. */
private const val COMBO_WINDOW_MS = 1200L

/** How long an idle paw stays a paw. */
private const val PAW_IDLE_MS = 2500L

private const val COMBO_GOAL = 10

/**
 * A tap adds a connection. A long press turns the plus into a paw, and taps on the paw
 * count up a combo instead; the paw turns back after a moment of rest.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TailcatAddButton(onAdd: () -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var paw by remember { mutableStateOf(false) }
    var combo by remember { mutableIntStateOf(0) }
    // Kept apart from the count, so a lost combo fades out still showing its number.
    var comboAlive by remember { mutableStateOf(false) }
    var spinning by remember { mutableStateOf(false) }
    val squish = remember { Animatable(1f) }

    LaunchedEffect(paw, combo, comboAlive) {
        if (!paw) return@LaunchedEffect
        if (comboAlive) {
            delay(COMBO_WINDOW_MS)
            comboAlive = false
        } else {
            delay(PAW_IDLE_MS)
            paw = false
        }
    }

    if (spinning) OiiaScene(onDismiss = { spinning = false })

    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            visible = comboAlive && combo >= 2,
            enter = scaleIn(initialScale = 0.5f) + fadeIn(),
            exit = scaleOut(targetScale = 0.5f) + fadeOut()
        ) {
            ComboBadge(combo)
        }
        Surface(
            shape = FloatingActionButtonDefaults.shape,
            color = FloatingActionButtonDefaults.containerColor,
            contentColor = contentColorFor(FloatingActionButtonDefaults.containerColor),
            shadowElevation = 6.dp
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .combinedClickable(
                        onClick = {
                            if (!paw) {
                                onAdd()
                                return@combinedClickable
                            }
                            combo = if (comboAlive) combo + 1 else 1
                            comboAlive = true
                            scope.launch {
                                squish.snapTo(0.78f)
                                squish.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow))
                            }
                            if (combo >= COMBO_GOAL) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                comboAlive = false
                                paw = false
                                spinning = true
                            } else {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        },
                        onLongClick = {
                            if (!paw) {
                                paw = true
                                comboAlive = false
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                        }
                    ),
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
                                scaleX = squish.value
                                scaleY = squish.value
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

@Composable
private fun ComboBadge(combo: Int) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(combo) {
        pop.snapTo(1.3f)
        pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.graphicsLayer {
            scaleX = pop.value
            scaleY = pop.value
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

// ---------------------------------------------------------------------------------------------
// U I I A I.

private const val WORD = "UIIAI"

/** How long each letter of [WORD] lasts at the slow pace, seconds. */
private val LETTER_SECONDS = floatArrayOf(0.40f, 0.21f, 0.21f, 0.50f, 0.40f)

/**
 * One beat of the loop. A sung beat lights [letter], an index into [WORD]; the cat makes
 * [turns] turns through the whole run of letters it belongs to, which starts at
 * [runStart] and lasts [runSeconds].
 */
private class Beat(
    val letter: Int?,
    val start: Float,
    val seconds: Float,
    val runStart: Float,
    val runSeconds: Float,
    val turns: Int
)

/** The cat stands, sings slowly, stops, sings fast, and stands again as the loop comes round. */
private val OIIA: List<Beat> = buildList {
    var at = 0f
    fun stand(seconds: Float) {
        add(Beat(null, at, seconds, at, seconds, 0))
        at += seconds
    }
    fun sing(pace: Float, turns: Int) {
        val runStart = at
        val runSeconds = LETTER_SECONDS.sum() / pace
        LETTER_SECONDS.forEachIndexed { i, s ->
            add(Beat(i, at, s / pace, runStart, runSeconds, turns))
            at += s / pace
        }
    }
    stand(0.6f)
    sing(1f, 2)
    stand(0.5f)
    sing(2f, 2)
}

private val OIIA_LOOP = OIIA.last().let { it.start + it.seconds }

/** Where [t] seconds of playing falls: its beat, and the time within the loop. */
private class Moment(val beat: Beat, val time: Float) {
    val progress get() = ((time - beat.start) / beat.seconds).coerceIn(0f, 1f)
}

private fun momentAt(t: Float): Moment {
    val time = ((t % OIIA_LOOP) + OIIA_LOOP) % OIIA_LOOP
    return Moment(OIIA.lastOrNull { it.start <= time } ?: OIIA.first(), time)
}

/**
 * How far the cat has turned, in degrees: a run of letters turns it whole times, easing in
 * and out so it faces the viewer again when the run ends.
 */
private fun angleAt(m: Moment): Float {
    val beat = m.beat
    if (beat.letter == null) return 0f
    val r = ((m.time - beat.runStart) / beat.runSeconds).coerceIn(0f, 1f)
    return 360f * beat.turns * (r - sin(2f * PI.toFloat() * r) / (2f * PI.toFloat()))
}

/** How fast the cat is turning, degrees a second. */
private fun speedAt(m: Moment): Float {
    val beat = m.beat
    if (beat.letter == null) return 0f
    val r = ((m.time - beat.runStart) / beat.runSeconds).coerceIn(0f, 1f)
    return 360f * beat.turns * (1f - cos(2f * PI.toFloat() * r)) / beat.runSeconds
}

/** The cat blinks once, early in the first stand. */
private fun blinkAt(m: Moment) = m.beat === OIIA.first() && (m.time - m.beat.start) in 0.3f..0.42f

/** How long the motor rests between two letters, so they do not run together, ms. */
private const val PART_MS = 40L

/** A tap closes the cat only after this long without one: the taps that summoned it run on. */
private const val CALM_MS = 700L

/** The loop as a vibration: each letter buzzes for as long as it lasts, the rest is still. */
private fun oiiaWaveform(): Pair<LongArray, IntArray> {
    val timings = mutableListOf<Long>()
    val levels = mutableListOf<Int>()
    fun add(ms: Long, level: Int) {
        if (ms <= 0) return
        if (levels.lastOrNull() == level) timings[timings.lastIndex] += ms
        else {
            timings += ms
            levels += level
        }
    }
    for (b in OIIA) {
        // Measured from the loop's start, so rounding never adds up along the loop.
        val ms = ((b.start + b.seconds) * 1000).roundToLong() - (b.start * 1000).roundToLong()
        if (b.letter == null) add(ms, 0)
        else {
            add(ms - PART_MS, if (WORD[b.letter] == 'A') 255 else 180)
            add(PART_MS, 0)
        }
    }
    return timings.toLongArray() to levels.toIntArray()
}

/**
 * Starts the loop's vibration, repeating until cancelled. A motor without amplitude
 * control gives every letter the same strength.
 */
private fun startOiiaBuzz(context: Context): Vibrator? {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator == null || !vibrator.hasVibrator()) return null
    val (timings, levels) = oiiaWaveform()
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitudes = if (vibrator.hasAmplitudeControl()) levels
            else IntArray(levels.size) { if (levels[it] > 0) 255 else 0 }
            val effect = VibrationEffect.createWaveform(timings, amplitudes, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
            }
        } else {
            // Before Android 8 a pattern alternates off and on, off first.
            val pattern = mutableListOf(0L)
            var on = false
            for (k in timings.indices) {
                val letter = levels[k] > 0
                if (letter == on) pattern[pattern.lastIndex] += timings[k]
                else {
                    pattern += timings[k]
                    on = letter
                }
            }
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern.toLongArray(), 0)
        }
    }
    return vibrator
}

/**
 * The cat, full screen on the theme's own surface (black under AMOLED), until a calm tap,
 * Back, or the app leaving the screen.
 */
@Composable
private fun OiiaScene(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val buzz = remember { mutableStateOf<Vibrator?>(null) }
    var lastTap by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val shown = remember { Animatable(0f) }
    val gone = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    var t by remember { mutableFloatStateOf(0f) }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            buzz.value?.cancel()
            scope.launch {
                gone.animateTo(1f, tween(250))
                onDismiss()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        StatusAsides.bump(context, StatusAsides.SPINS)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onDismiss() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(250)) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        try {
            buzz.value = startOiiaBuzz(context)
            while (true) withFrameNanos { now -> t = (now - start) / 1e9f }
        } finally {
            buzz.value?.cancel()
        }
    }

    Dialog(
        onDismissRequest = leave,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val description = stringResource(R.string.oiia_cd)
        val moment = momentAt(t)
        val p = moment.progress
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = shown.value * (1f - gone.value) }
                .background(MaterialTheme.colorScheme.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    val now = SystemClock.uptimeMillis()
                    val calm = now - lastTap >= CALM_MS
                    lastTap = now
                    if (calm) leave()
                }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val ink = MaterialTheme.colorScheme.onSurface
                Canvas(Modifier.size(width = 320.dp, height = 224.dp)) {
                    drawSpinningCat(angleAt(moment), blinkAt(moment), speedAt(moment))
                }
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    WORD.forEachIndexed { j, letter ->
                        val active = moment.beat.letter == j
                        Text(
                            letter.toString(),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Black,
                            color = if (active) ink else ink.copy(alpha = 0.2f),
                            modifier = Modifier.graphicsLayer {
                                val k = if (active) 1f + 0.3f * sin(PI.toFloat() * p) else 1f
                                scaleX = k
                                scaleY = k
                            }
                        )
                    }
                }
            }
        }
    }
}
