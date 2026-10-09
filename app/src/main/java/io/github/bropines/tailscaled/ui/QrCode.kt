package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
 * [text] as a square QR code, as wide as it is given.
 *
 * Black modules on white whatever the theme: a camera looks for dark on light,
 * and a code drawn in a dark theme's colours reads inverted. The modules are
 * rectangles a whole number of pixels wide, centred, not a bitmap scaled to
 * fit, so the edges stay sharp at any size; what the division leaves over
 * widens the quiet zone.
 *
 * The labels default to this composition's resources. Inside a sheet or a
 * dialog pass them in, resolved by the screen — see wrapContextWithLocale.
 */
@Composable
fun QrCodeImage(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.qr_image),
    tooLong: String = stringResource(R.string.qr_too_long),
) {
    val qr = remember(text) { encodeQr(text) }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White)
            // Keeps the rounded corners clear of the quiet zone.
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (qr == null) Text(tooLong, color = Color.Black, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
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
                    // One rectangle per run of dark modules in a row, all in one
                    // path: no seams between neighbours, a few hundred shapes at most.
                    val path = Path()
                    for (y in 0 until qr.size) {
                        var x = 0
                        while (x < qr.size) {
                            if (!qr.getModule(x, y)) { x++; continue }
                            val start = x
                            while (x < qr.size && qr.getModule(x, y)) x++
                            path.addRect(
                                Rect(
                                    (left + start * module).toFloat(), (top + y * module).toFloat(),
                                    (left + x * module).toFloat(), (top + (y + 1) * module).toFloat()
                                )
                            )
                        }
                    }
                    onDrawBehind { drawPath(path, Color.Black) }
                }
        )
    }
}

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrSheet(title: String, text: String, onDismiss: () -> Unit, help: String? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Resolved here, in the screen's composition: the sheet is a window of its
    // own and would take the system language (see wrapContextWithLocale).
    val labels = QrLabels(
        image = stringResource(R.string.qr_image),
        tooLong = stringResource(R.string.qr_too_long),
        copy = stringResource(R.string.action_copy),
        share = stringResource(R.string.qr_share),
    )
    val copied = stringResource(R.string.qr_copied)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        QrSheetContent(
            title = title,
            text = text,
            help = help,
            labels = labels,
            onCopy = {
                clipboard.copyText(scope, text)
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            },
            onShare = { shareText(context, text, labels.share) }
        )
    }
}

/** The sheet's strings, resolved before the sheet's window exists. */
internal data class QrLabels(val image: String, val tooLong: String, val copy: String, val share: String)

/** What [QrSheet] shows; apart from the sheet so a preview can render it. */
@Composable
internal fun QrSheetContent(
    title: String,
    text: String,
    help: String?,
    labels: QrLabels,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
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
            if (help != null) HelpText(help)
        }
        QrCodeImage(
            text = text,
            contentDescription = labels.image,
            tooLong = labels.tooLong,
            modifier = Modifier.widthIn(max = maxSide).fillMaxWidth()
        )
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
    }
}

/** Hands [text] to whatever app the user picks: a message, a note, a computer. */
private fun shareText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title))
}
