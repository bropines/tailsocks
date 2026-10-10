package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The paw and what it leads to, without a device: the show is a function of time, so each
 * frame is the stage at a fixed t. Filmstrips across one cycle at each level of escalation,
 * the intro, single frames in the dark, light and AMOLED themes, the still version for
 * reduced motion, the cats on a turntable, and the button at combo 1, 5, 9 and 10.
 */

private enum class Look { DARK, LIGHT, AMOLED }

@Composable
private fun Themed(look: Look, content: @Composable () -> Unit) {
    TailSocksTheme(
        appTheme = if (look == Look.LIGHT) "light" else "dark",
        dynamicColorEnabled = false,
        amoledModeEnabled = look == Look.AMOLED
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** A clock stopped at [t]: the renderer takes the first frame, so a preview passes its time. */
@Composable
private fun frozen(t: Float) = remember(t) { mutableFloatStateOf(t) }

/** ms into cycle [cycle] of the show. */
private fun at(cycle: Int, local: Float) = PawShow.INTRO_MS + cycle * PawShow.CYCLE_MS + local

private val CYCLE_FRAMES = floatArrayOf(40f, 200f, 420f, 600f, 760f, 960f, 1150f, 1350f, 1500f, 1800f, 2150f, 2300f, 2480f, 2600f, 2760f, 3100f)

@Composable
private fun Frame(t: Float, label: String, width: Dp = 190.dp, height: Dp = 342.dp, calm: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PawShowStage(time = frozen(t), calm = calm, modifier = Modifier.size(width, height))
    }
}

@Composable
private fun Filmstrip(cycle: Int, look: Look, calm: Boolean = false) = Themed(look) {
    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0..3) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (col in 0..3) {
                val local = CYCLE_FRAMES[row * 4 + col]
                val s = PawShow.sceneAt(at(cycle, local))
                val sung = if (s.letter >= 0) " " + PawShow.WORD[s.letter] else ""
                Frame(at(cycle, local), "${local.toInt()} ms$sung  ${s.angle.toInt()}°", calm = calm)
            }
        }
    }
}

private const val STRIP = "spec:width=790dp,height=1470dp,dpi=160"

@PreviewTest @Preview(name = "strip-cycle0-light", device = STRIP) @Composable
fun PawStripCycle0Light() = Filmstrip(0, Look.LIGHT)

@PreviewTest @Preview(name = "strip-cycle1-dark", device = STRIP) @Composable
fun PawStripCycle1Dark() = Filmstrip(1, Look.DARK)

@PreviewTest @Preview(name = "strip-cycle2-light", device = STRIP) @Composable
fun PawStripCycle2Light() = Filmstrip(2, Look.LIGHT)

@PreviewTest @Preview(name = "strip-cycle3-dark", device = STRIP) @Composable
fun PawStripCycle3Dark() = Filmstrip(3, Look.DARK)

@PreviewTest @Preview(name = "strip-cycle4-amoled", device = STRIP) @Composable
fun PawStripCycle4Amoled() = Filmstrip(4, Look.AMOLED)

@PreviewTest @Preview(name = "strip-calm", device = STRIP) @Composable
fun PawStripCalm() = Filmstrip(3, Look.DARK, calm = true)

/** The fast run every other frame at 60 Hz: does the turn read as a turn at full speed? */
@PreviewTest @Preview(name = "strip-fast-run", device = "spec:width=790dp,height=1080dp,dpi=160") @Composable
fun PawStripFastRun() = Themed(Look.DARK) {
    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0..4) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (col in 0..4) {
                val local = PawShow.RUN_START[1] - 20f + (row * 5 + col) * 1000f / 30f
                val s = PawShow.sceneAt(at(4, local))
                val sung = if (s.letter >= 0) " " + PawShow.WORD[s.letter] else ""
                Frame(at(4, local), "${local.toInt()}$sung ${s.angle.toInt()}°", width = 150.dp, height = 190.dp)
            }
        }
    }
}

private val INTRO_FRAMES = floatArrayOf(0f, 80f, 160f, 240f, 300f, 360f, 420f, 450f, 520f, 640f, 740f, 880f)

@PreviewTest @Preview(name = "strip-intro", device = "spec:width=790dp,height=1110dp,dpi=160") @Composable
fun PawStripIntro() = Themed(Look.DARK) {
    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (col in 0..3) {
                val t = INTRO_FRAMES[row * 4 + col]
                Frame(t, "${t.toInt()} ms")
            }
        }
    }
}

// Single frames, phone size.

private const val PHONE = "spec:width=393dp,height=852dp,dpi=420"

@PreviewTest @Preview(name = "frame-dark-a", device = PHONE) @Composable
fun PawFrameDarkA() = Themed(Look.DARK) {
    PawShowStage(time = frozen(at(4, PawShow.onset(0, 3) + 200f)), calm = false, modifier = Modifier.fillMaxSize())
}

@PreviewTest @Preview(name = "frame-light-stare", device = PHONE) @Composable
fun PawFrameLightStare() = Themed(Look.LIGHT) {
    PawShowStage(time = frozen(at(4, PawShow.RUN_END[0] + 120f)), calm = false, modifier = Modifier.fillMaxSize())
}

@PreviewTest @Preview(name = "frame-amoled-fast", device = PHONE) @Composable
fun PawFrameAmoledFast() = Themed(Look.AMOLED) {
    PawShowStage(time = frozen(at(5, PawShow.onset(1, 2) + 30f)), calm = false, modifier = Modifier.fillMaxSize())
}

@PreviewTest @Preview(name = "frame-culprit", device = PHONE) @Composable
fun PawFrameCulprit() = Themed(Look.DARK) {
    PawShowStage(time = frozen(at(3, PawShow.RUN_END[1] + 150f)), calm = false, modifier = Modifier.fillMaxSize())
}

@PreviewTest @Preview(name = "frame-culprit-later", device = PHONE) @Composable
fun PawFrameCulpritLater() = Themed(Look.LIGHT) {
    PawShowStage(time = frozen(at(6, PawShow.RUN_END[1] + 120f)), calm = false, modifier = Modifier.fillMaxSize())
}

@PreviewTest @Preview(name = "frame-calm", device = PHONE) @Composable
fun PawFrameCalm() = Themed(Look.LIGHT) {
    PawShowStage(time = frozen(at(4, PawShow.onset(0, 3) + 100f)), calm = true, modifier = Modifier.fillMaxSize())
}

@PreviewTest @Preview(name = "frame-landscape", device = "spec:width=852dp,height=393dp,dpi=420") @Composable
fun PawFrameLandscape() = Themed(Look.DARK) {
    PawShowStage(time = frozen(at(4, PawShow.onset(0, 1) + 60f)), calm = false, modifier = Modifier.fillMaxSize())
}

// The cats, on a turntable.

private val COATS = listOf(CatCoats.GREY, CatCoats.GINGER, CatCoats.TUXEDO, CatCoats.CREAM, CatCoats.SMOKE)

@PreviewTest @Preview(name = "turntable", device = "spec:width=1000dp,height=1000dp,dpi=160") @Composable
fun PawTurntable() = Themed(Look.LIGHT) {
    val painter = remember { CatPainter() }
    Canvas(Modifier.fillMaxSize()) {
        val cell = size.width / 8f
        for ((row, coat) in COATS.withIndex()) {
            for (col in 0..7) {
                val pose = CatPose().set(col * 45f, 0f, 0f, if (row == 0) PawShow.MOUTH_SHUT else (row % 3) + 1, 1f, 0f, 0f)
                translate(cell * (col + 0.5f), cell * 1.6f * (row + 0.85f)) {
                    drawIntoCanvas {
                        it.nativeCanvas.save()
                        it.nativeCanvas.scale(cell / 190f, cell / 190f)
                        painter.draw(it.nativeCanvas, coat, pose)
                        it.nativeCanvas.restore()
                    }
                }
            }
        }
    }
}

@PreviewTest @Preview(name = "faces", device = "spec:width=1000dp,height=300dp,dpi=320") @Composable
fun PawFaces() = Themed(Look.DARK) {
    val painter = remember { CatPainter() }
    Canvas(Modifier.fillMaxSize()) {
        val cell = size.width / 5f
        val poses = listOf(
            CatPose().set(0f, 0f, 0f, PawShow.MOUTH_SHUT, 0f, 0f, 0f),
            CatPose().set(0f, 0f, 0f, PawShow.MOUTH_U, 1f, 0f, 0f),
            CatPose().set(0f, 0f, 0f, PawShow.MOUTH_I, 1f, 0f, 0f),
            CatPose().set(0f, 0f, 0f, PawShow.MOUTH_A, 1f, 0f, 0f),
            CatPose().set(0f, 0f, 0f, PawShow.MOUTH_SHUT, 0f, 1f, 0f)
        )
        for ((i, pose) in poses.withIndex()) {
            translate(cell * (i + 0.5f), size.height * 0.93f) {
                drawIntoCanvas {
                    it.nativeCanvas.save()
                    it.nativeCanvas.scale(size.height / 230f, size.height / 230f)
                    painter.draw(it.nativeCanvas, CatCoats.GREY, pose)
                    it.nativeCanvas.restore()
                }
            }
        }
    }
}

// The button: a paw at combo 1, 5 and 9 (charged), and the burst of the tenth tap.

@Composable
private fun PawButtonAt(combo: Int, sinceTap: Float, label: String) {
    val bursts = PawBursts()
    val now = 10_000f
    // The recent taps, a short combo's worth apart.
    for (n in maxOf(1, combo - 3)..combo) bursts.add(now - sinceTap - (combo - n) * 160f, n)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.size(190.dp, 260.dp), contentAlignment = Alignment.BottomEnd) {
            Box(Modifier.padding(24.dp)) {
                TailcatAddButtonFace(
                    paw = combo < PawHype.GOAL,
                    combo = combo,
                    comboShown = combo < PawHype.GOAL,
                    now = frozen(now),
                    lastTap = frozen(now - sinceTap),
                    bursts = bursts
                )
            }
        }
    }
}

@Composable
private fun ButtonStates(look: Look) = Themed(look) {
    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PawButtonAt(1, 120f, "1")
        PawButtonAt(5, 90f, "5")
        PawButtonAt(9, 140f, "9")
        PawButtonAt(10, 200f, "10")
    }
}

@PreviewTest @Preview(name = "button-dark", device = "spec:width=790dp,height=290dp,dpi=320") @Composable
fun PawButtonDark() = ButtonStates(Look.DARK)

@PreviewTest @Preview(name = "button-light", device = "spec:width=790dp,height=290dp,dpi=320") @Composable
fun PawButtonLight() = ButtonStates(Look.LIGHT)
