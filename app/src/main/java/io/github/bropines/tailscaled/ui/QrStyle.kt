package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.SlidingSegmentedChips
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/*
 * How QrCodeImage draws a code: the modules' shape and whose colours. The
 * finder patterns — the three corner squares a scanner looks for first — and
 * the alignment patterns are drawn whole in every style (QrPaths), and the
 * colours are always dark on light.
 */

/** The modules' shape. [id] is what GlobalSettings stores. */
enum class QrShape(val id: String) {
    /** Square modules, joined into runs: the plain code. */
    SQUARES("squares"),
    /** Neighbouring modules flow into one another; a lone one is round. */
    ROUNDED("rounded"),
    /** A dot a module. */
    DOTS("dots");

    companion object {
        fun fromId(id: String?): QrShape = entries.firstOrNull { it.id == id } ?: ROUNDED
    }
}

/** Whose colours a code takes. [id] is what GlobalSettings stores. */
enum class QrPalette(val id: String) {
    /** The theme's main colour, deepened until it reads, on a light tint of it. */
    THEME("theme"),
    /** Black on white. */
    MONO("mono");

    companion object {
        fun fromId(id: String?): QrPalette = entries.firstOrNull { it.id == id } ?: THEME
    }
}

data class QrStyle(val shape: QrShape = QrShape.ROUNDED, val palette: QrPalette = QrPalette.THEME)

/** The style Settings chose, read once: a sheet opened later reads it again. */
@Composable
fun rememberQrStyle(): QrStyle {
    val context = LocalContext.current
    return remember { GlobalSettings.getQrStyle(context) }
}

/**
 * The colours of one code: [module] for the modules, [eye] for the finder
 * patterns, on [background].
 *
 * Always dark on light, in a dark theme too: many camera apps never try a
 * code inverted. Both inks keep at least [MIN_CONTRAST] against the
 * background (WCAG's ratio, the one for small text at AAA), the modules
 * [MODULE_CONTRAST]; the theme's colour gives only its hue and chroma.
 */
data class QrInk(val module: Color, val eye: Color, val background: Color) {
    companion object {
        val MONO = QrInk(Color.Black, Color.Black, Color.White)

        /** Inks of [primary]'s hue: deep modules, eyes near the light scheme's primary, a pale card. */
        fun of(primary: Color): QrInk {
            val base = Oklch.of(primary)
            val background = Oklch(BACKGROUND_LIGHTNESS, minOf(base.c, BACKGROUND_CHROMA), base.h).toColor()
            val module = deepen(base, background, MODULE_CONTRAST)
            // A grey theme has no colour for the eyes to show: they match the modules.
            val eye = if (base.c < NEUTRAL_CHROMA) module else deepen(base, background, MIN_CONTRAST)
            // The lightness search cannot miss — black is the floor, against a
            // background this light — but the guarantee is the point.
            return if (contrast(module, background) < MODULE_CONTRAST || contrast(eye, background) < MIN_CONTRAST) MONO
            else QrInk(module, eye, background)
        }
    }
}

/** The theme's inks for [palette]. */
@Composable
fun rememberQrInk(palette: QrPalette): QrInk {
    val primary = MaterialTheme.colorScheme.primary
    return remember(palette, primary) { if (palette == QrPalette.MONO) QrInk.MONO else QrInk.of(primary) }
}

/** The least contrast any ink keeps with the background. */
private const val MIN_CONTRAST = 7f

/** The modules', higher: they are most of the code. */
private const val MODULE_CONTRAST = 12f

private const val BACKGROUND_LIGHTNESS = 0.975
private const val BACKGROUND_CHROMA = 0.025
private const val NEUTRAL_CHROMA = 0.02

/** WCAG contrast of two opaque colours: 1 for the same, 21 for black on white. */
private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return maxOf(la, lb) / minOf(la, lb)
}

/**
 * [base] at the highest lightness, no higher than its own, with [min]
 * contrast against [background]. Lightness alone moves: the hue stays, and
 * so does the chroma where sRGB has room for it.
 */
private fun deepen(base: Oklch, background: Color, min: Float): Color {
    base.toColor().let { if (contrast(it, background) >= min) return it }
    var lo = 0.0
    var hi = base.l
    repeat(24) {
        val mid = (lo + hi) / 2
        if (contrast(Oklch(mid, base.c, base.h).toColor(), background) >= min) lo = mid else hi = mid
    }
    return Oklch(lo, base.c, base.h).toColor()
}

/**
 * A colour as Oklab's lightness, chroma and hue (Björn Ottosson, 2020): a
 * space where lightness can change and the colour still looks like itself,
 * which HSL's does not — a light theme's pastel primary went electric there.
 */
private class Oklch(val l: Double, val c: Double, val h: Double) {
    /** The colour in sRGB, chroma reduced until it fits. */
    fun toColor(): Color {
        var rgb = linearRgb(c)
        if (!rgb.inGamut()) {
            var lo = 0.0
            var hi = c
            repeat(20) {
                val mid = (lo + hi) / 2
                if (linearRgb(mid).inGamut()) lo = mid else hi = mid
            }
            rgb = linearRgb(lo)
        }
        return Color(gamma(rgb[0]), gamma(rgb[1]), gamma(rgb[2]))
    }

    private fun linearRgb(chroma: Double): DoubleArray {
        val a = chroma * cos(h)
        val b = chroma * sin(h)
        val l3 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m3 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s3 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
            -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
            -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076127010 * s3,
        )
    }

    private fun DoubleArray.inGamut() = all { it in -1e-4..1.0001 }

    companion object {
        fun of(color: Color): Oklch {
            val r = linear(color.red)
            val g = linear(color.green)
            val b = linear(color.blue)
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            val ok = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
            val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
            val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
            return Oklch(ok, hypot(a, bb), atan2(bb, a))
        }

        private fun linear(v: Float): Double =
            if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

        private fun gamma(v: Double): Float {
            val x = v.coerceIn(0.0, 1.0)
            return (if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055).toFloat().coerceIn(0f, 1f)
        }
    }
}

/** The sample in Settings: short enough for large modules, long enough for an alignment pattern. */
private const val QR_SAMPLE = "github.com/bropines/tailsocks"

/**
 * Settings → Appearance: the codes' shape and colours, with a sample that
 * follows both and the theme being picked. A choice applies to the next code
 * a sheet shows.
 */
@Composable
internal fun QrStyleSetting() {
    val context = LocalContext.current
    var style by remember { mutableStateOf(GlobalSettings.getQrStyle(context)) }
    fun choose(next: QrStyle) {
        style = next
        GlobalSettings.setQrStyle(context, next)
    }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.qr_style_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HelpText(stringResource(R.string.qr_style_note))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            QrCodeImage(
                text = QR_SAMPLE,
                style = style,
                contentDescription = stringResource(R.string.qr_style_sample),
                modifier = Modifier.size(84.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SlidingSegmentedChips(
                    options = listOf(
                        stringResource(R.string.qr_style_squares),
                        stringResource(R.string.qr_style_rounded),
                        stringResource(R.string.qr_style_dots)
                    ),
                    selectedIndex = style.shape.ordinal,
                    onOptionSelected = { choose(style.copy(shape = QrShape.entries[it])) },
                    modifier = Modifier.fillMaxWidth(),
                    height = 38.dp
                )
                SlidingSegmentedChips(
                    options = listOf(stringResource(R.string.qr_palette_theme), stringResource(R.string.qr_palette_mono)),
                    selectedIndex = style.palette.ordinal,
                    onOptionSelected = { choose(style.copy(palette = QrPalette.entries[it])) },
                    modifier = Modifier.fillMaxWidth(),
                    height = 38.dp
                )
            }
        }
    }
}
