package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.SlidingSegmentedChips
import io.nayuki.qrcodegen.DataTooLongException
import io.nayuki.qrcodegen.QrCode

/*
 * QR codes to show: a connect command, a served link, a peer's address, for
 * another device's camera. Drawing one needs no camera and no permission; the
 * reading side is QrScanActivity.
 */

/** Light modules on every side of the code: the standard's quiet zone, which scanners look for. */
private const val QUIET_ZONE = 4

/**
 * [text] as a QR code: the smallest version that holds it, with as much error
 * correction as that version has room for. Null when it fits in none.
 */
private fun encodeQr(text: String): QrCode? =
    try { QrCode.encodeText(text, QrCode.Ecc.LOW) } catch (_: DataTooLongException) { null }

/**
 * [text] as a square QR code, as wide as it is given, drawn in [style] —
 * Settings' choice unless a caller passes its own.
 *
 * Dark on light whatever the theme (see QrInk): a camera looks for dark on
 * light, and a code drawn in a dark theme's colours reads inverted. Modules
 * are a whole number of pixels wide, centred, not a bitmap scaled to fit, so
 * the edges stay sharp at any size; what the division leaves over widens the
 * quiet zone.
 *
 * The labels default to this composition's resources. Inside a sheet or a
 * dialog pass them in, resolved by the screen — see wrapContextWithLocale.
 */
@Composable
fun QrCodeImage(
    text: String,
    modifier: Modifier = Modifier,
    style: QrStyle = rememberQrStyle(),
    contentDescription: String = stringResource(R.string.qr_image),
    tooLong: String = stringResource(R.string.qr_too_long),
) {
    val qr = remember(text) { encodeQr(text) }
    val ink = rememberQrInk(style.palette)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(ink.background)
            // Keeps the rounded corners clear of the quiet zone.
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (qr == null) Text(tooLong, color = ink.module, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        else Spacer(
            Modifier
                .fillMaxSize()
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Image
                }
                .drawWithCache {
                    val module = maxOf(1, minOf(size.width, size.height).toInt() / (qr.size + 2 * QUIET_ZONE))
                    val left = ((size.width - module * qr.size) / 2).toInt()
                    val top = ((size.height - module * qr.size) / 2).toInt()
                    val paths = QrPaths(qr, style.shape, module.toFloat(), left.toFloat(), top.toFloat())
                    onDrawBehind {
                        drawPath(paths.modules, ink.module)
                        drawPath(paths.eyes, ink.eye)
                    }
                }
        )
    }
}

/**
 * [qr] as [shape] draws it, [m] pixels a module, its top left corner at
 * ([left], [top]): the modules in one path — one shape per module or run, so
 * neighbours meet without seams — and the three finder patterns in another,
 * for a colour of their own.
 *
 * The finder and alignment patterns stay solid in every style: rings and
 * full-width cores, softened at most. A scanner finds a code by its finder
 * patterns and corrects for perspective by its alignment patterns, measuring
 * the light and dark runs across them against a module's width. An alignment
 * pattern drawn as the other modules, its core a circle, went unfound in
 * tilted photos, and ZXing read none of those codes.
 */
private class QrPaths(private val qr: QrCode, shape: QrShape, private val m: Float, private val left: Float, private val top: Float) {
    val modules = Path().apply { fillType = PathFillType.EvenOdd }
    val eyes = Path().apply { fillType = PathFillType.EvenOdd }
    private val n = qr.size
    private val alignment = if (shape == QrShape.SQUARES) emptyList() else alignmentCentres()

    /** The dark modules the style draws one by one: all but the patterns' own. */
    private val data = BooleanArray(n * n).also { grid ->
        for (y in 0 until n) for (x in 0 until n) grid[y * n + x] = qr.getModule(x, y)
        for ((ex, ey) in eyeCorners()) for (y in ey until ey + 7) for (x in ex until ex + 7) grid[y * n + x] = false
        for ((ax, ay) in alignment) for (y in ay - 2..ay + 2) for (x in ax - 2..ax + 2) grid[y * n + x] = false
    }

    init {
        when (shape) {
            QrShape.SQUARES -> squares()
            QrShape.ROUNDED -> rounded()
            QrShape.DOTS -> dots()
        }
        // Even-odd: a ring's hole is cut out of the outer square and the core
        // filled again inside it, in one path. The outer corners stay square:
        // OpenCV's detector takes them for the code's corners, and rounding
        // them by half a module lost it one test code in five.
        val r = if (shape == QrShape.SQUARES) 0f else 1f
        for ((ex, ey) in eyeCorners()) {
            eyes.addRoundRect(cells(ex, ey, 7, 7, 0f))
            eyes.addRoundRect(cells(ex + 1, ey + 1, 5, 5, r))
            eyes.addRoundRect(cells(ex + 2, ey + 2, 3, 3, r))
        }
        // A ring with softened corners round a square core.
        for ((ax, ay) in alignment) {
            modules.addRoundRect(cells(ax - 2, ay - 2, 5, 5, 1f))
            modules.addRoundRect(cells(ax - 1, ay - 1, 3, 3, 0.5f))
            modules.addRoundRect(cells(ax, ay, 1, 1, 0f))
        }
    }

    private fun eyeCorners() = listOf(0 to 0, n - 7 to 0, 0 to n - 7)

    /** A module [data] draws; false outside the code. */
    private fun dark(x: Int, y: Int) = x in 0 until n && y in 0 until n && data[y * n + x]

    /** [w]×[h] modules from ([x], [y]), corners [radius] modules round. */
    private fun cells(x: Int, y: Int, w: Int, h: Int, radius: Float) = RoundRect(
        left + x * m, top + y * m, left + (x + w) * m, top + (y + h) * m, CornerRadius(radius * m)
    )

    /** One rectangle per run of dark modules in a row: a few hundred shapes at most. */
    private fun squares() {
        for (y in 0 until n) {
            var x = 0
            while (x < n) {
                if (!dark(x, y)) { x++; continue }
                val start = x
                while (x < n && dark(x, y)) x++
                modules.addRect(Rect(left + start * m, top + y * m, left + x * m, top + (y + 1) * m))
            }
        }
    }

    /**
     * Each dark module rounds the corners where it has no neighbour on either
     * side, and each light one fills its corners where three dark ones meet
     * round it: an inside bend is a curve too, and the modules run together
     * like a liquid. A module's centre — what a scanner samples — stays its colour.
     */
    private fun rounded() {
        val r = m / 2
        fun corner(round: Boolean) = if (round) CornerRadius(r) else CornerRadius.Zero
        for (y in 0 until n) for (x in 0 until n) {
            val px = left + x * m
            val py = top + y * m
            val up = dark(x, y - 1)
            val down = dark(x, y + 1)
            val leftOf = dark(x - 1, y)
            val rightOf = dark(x + 1, y)
            if (dark(x, y)) {
                modules.addRoundRect(
                    RoundRect(
                        px, py, px + m, py + m,
                        topLeftCornerRadius = corner(!up && !leftOf),
                        topRightCornerRadius = corner(!up && !rightOf),
                        bottomRightCornerRadius = corner(!down && !rightOf),
                        bottomLeftCornerRadius = corner(!down && !leftOf)
                    )
                )
            } else {
                if (up && leftOf && dark(x - 1, y - 1)) fillet(px, py, 1f, 1f, r)
                if (up && rightOf && dark(x + 1, y - 1)) fillet(px + m, py, -1f, 1f, r)
                if (down && leftOf && dark(x - 1, y + 1)) fillet(px, py + m, 1f, -1f, r)
                if (down && rightOf && dark(x + 1, y + 1)) fillet(px + m, py + m, -1f, -1f, r)
            }
        }
    }

    /**
     * The inside of a bend at the corner ([cx], [cy]) of a light module: the
     * corner's square of side [r] less a quarter circle. [dx] and [dy] point
     * into the module.
     */
    private fun fillet(cx: Float, cy: Float, dx: Float, dy: Float, r: Float) {
        // A cubic with control points 0.552 r out is a quarter circle to within 0.03%.
        val k = 0.5523f * r
        modules.moveTo(cx, cy)
        modules.lineTo(cx + dx * r, cy)
        modules.cubicTo(cx + dx * (r - k), cy, cx, cy + dy * (r - k), cx, cy + dy * r)
        modules.close()
    }

    /** A dot a module. */
    private fun dots() {
        val d = m * DOT
        for (y in 0 until n) for (x in 0 until n) {
            if (!dark(x, y)) continue
            val cx = left + x * m + m / 2
            val cy = top + y * m + m / 2
            modules.addOval(Rect(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2))
        }
    }

    /**
     * Where the alignment patterns' centres are: none in version 1, then a
     * grid spaced by the standard's formula, less the three corners the finder
     * patterns hold (qrcodegen keeps its own copy private).
     */
    private fun alignmentCentres(): List<Pair<Int, Int>> {
        if (qr.version == 1) return emptyList()
        val count = qr.version / 7 + 2
        val step = (qr.version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
        val positions = IntArray(count)
        positions[0] = 6
        var pos = n - 7
        for (i in count - 1 downTo 1) { positions[i] = pos; pos -= step }
        val last = count - 1
        return buildList {
            for (i in 0 until count) for (j in 0 until count) {
                if (i == 0 && j == 0 || i == 0 && j == last || i == last && j == 0) continue
                add(positions[i] to positions[j])
            }
        }
    }
}

/** A dot's diameter, in modules: a hair of light between neighbours keeps them dots. */
private const val DOT = 0.9f

/**
 * [text] as a big QR code in a sheet, for another device to scan, with the
 * text itself under it — selectable — and Copy and Share. [help] is a folded
 * line on what the code is for or whom it lets in.
 *
 *     var qr by remember { mutableStateOf<String?>(null) }
 *     IconButton(onClick = { qr = address }) { Icon(Icons.Default.QrCode2, stringResource(R.string.qr_show)) }
 *     qr?.let { QrSheet(title = name, text = it, onDismiss = { qr = null }) }
 *
 * The caller passes [title] and [help] already resolved, as every sheet does.
 */
@Composable
fun QrSheet(title: String, text: String, onDismiss: () -> Unit, help: String? = null) =
    QrSheet(title = title, variants = listOf(QrVariant("", text, help)), onDismiss = onDismiss)

/**
 * One way to put the same thing in a code: the tab's [label], the [text] the
 * code carries, and the [help] that goes with it.
 */
data class QrVariant(val label: String, val text: String, val help: String? = null)

/**
 * The same thing in more than one encoding — a link any camera opens and the
 * command a computer runs, say — one tab each; the first is shown first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrSheet(title: String, variants: List<QrVariant>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val variant = variants[selected.coerceIn(0, variants.lastIndex)]
    // Resolved here, in the screen's composition: the sheet is a window of its
    // own and would take the system language (see wrapContextWithLocale).
    val labels = QrLabels(
        image = stringResource(R.string.qr_image),
        tooLong = stringResource(R.string.qr_too_long),
        copy = stringResource(R.string.action_copy),
        share = stringResource(R.string.qr_share),
        showText = stringResource(R.string.qr_show_text),
        hideText = stringResource(R.string.qr_hide_text),
    )
    val copied = stringResource(R.string.qr_copied)
    val style = rememberQrStyle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        QrSheetContent(
            title = title,
            variants = variants,
            selected = selected,
            onSelect = { selected = it },
            labels = labels,
            style = style,
            onCopy = {
                clipboard.copyText(scope, variant.text)
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            },
            onShare = { shareText(context, variant.text, labels.share) }
        )
    }
}

/** The sheet's strings, resolved before the sheet's window exists. */
internal data class QrLabels(
    val image: String,
    val tooLong: String,
    val copy: String,
    val share: String,
    val showText: String,
    val hideText: String,
)

/** What [QrSheet] shows; apart from the sheet so a preview can render it. */
@Composable
internal fun QrSheetContent(
    title: String,
    variants: List<QrVariant>,
    selected: Int,
    onSelect: (Int) -> Unit,
    labels: QrLabels,
    style: QrStyle,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    val variant = variants[selected.coerceIn(0, variants.lastIndex)]
    val text = variant.text
    // Upright the width decides; turned, the whole code still fits on the screen.
    val maxSide = minOf(320.dp, (LocalConfiguration.current.screenHeightDp * 0.6f).dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            variant.help?.let { HelpText(it) }
        }
        if (variants.size > 1) {
            SlidingSegmentedChips(
                options = variants.map { it.label },
                selectedIndex = selected,
                onOptionSelected = onSelect,
                modifier = Modifier.fillMaxWidth()
            )
        }
        QrCodeImage(
            text = text,
            style = style,
            contentDescription = labels.image,
            tooLong = labels.tooLong,
            modifier = Modifier.widthIn(max = maxSide).fillMaxWidth()
        )
        // The text is what the code says, for whoever wants to check it; the
        // code is what the sheet is for, so the text waits behind a tap.
        var textShown by rememberSaveable { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(labels.copy)
            }
            FilledTonalButton(onClick = onShare) {
                Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(labels.share)
            }
        }
        TextButton(onClick = { textShown = !textShown }, modifier = Modifier.align(Alignment.Start)) {
            Icon(if (textShown) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (textShown) labels.hideText else labels.showText)
        }
        AnimatedVisibility(visible = textShown) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

/** Hands [text] to whatever app the user picks: a message, a note, a computer. */
private fun shareText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title))
}
