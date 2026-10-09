package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.TailcatConnections

/**
 * What a scanned QR code holds, to this app. Its own links and TailCat
 * addresses are acted on the way a tap would act on them; anything else is
 * only shown ([ScanResultSheet]) — a code is whatever someone printed, and
 * nothing it says is opened without the user's tap.
 */
sealed interface ScannedCode {
    /** A `tailsocks://` link the app knows: it opens as if tapped (DeepLinks.open). */
    data class AppLink(val uri: Uri) : ScannedCode

    /** A TailCat address or connect command, for a new connection's editor. */
    data class Tailcat(val text: String) : ScannedCode

    /** Anything else. */
    data class Text(val text: String) : ScannedCode

    companion object {
        fun of(context: Context, text: String): ScannedCode {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty() && trimmed.none { it.isWhitespace() }) {
                // A code made for capitals (QR's compact alphanumeric mode) says TAILSOCKS://.
                val uri = Uri.parse(trimmed).normalizeScheme()
                if (DeepLinks.knows(context, uri)) return AppLink(uri)
            }
            if (TailcatConnections.parseImport(trimmed) != null) return Tailcat(trimmed)
            return Text(text)
        }
    }
}

/**
 * What the main screen does with a scanned code: an app link opens as if
 * tapped, a TailCat address opens a new connection's editor, filled in.
 * Returns the text when it is neither, for [ScanResultSheet].
 */
fun openScanned(context: Context, text: String): String? {
    when (val code = ScannedCode.of(context, text)) {
        is ScannedCode.AppLink -> DeepLinks.open(context, code.uri)
        is ScannedCode.Tailcat -> context.startActivity(ServeActivity.tailcatIntent(context, importText = code.text))
        is ScannedCode.Text -> return code.text
    }
    return null
}

/** [text] as a web address to offer to open: http or https with a host, nothing else. */
internal fun webLink(text: String): Uri? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val uri = Uri.parse(trimmed).normalizeScheme()
    return uri.takeIf { (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrEmpty() }
}

/**
 * A scanned code that is neither an app link nor a TailCat address: its text,
 * selectable, with Copy, and Open for a web link — the user decides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultSheet(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val web = remember(text) { webLink(text) }
    // Resolved here, in the screen's composition: the sheet is a window of its
    // own and would take the system language (see wrapContextWithLocale).
    val labels = ScanResultLabels(
        title = stringResource(R.string.qr_scan_result_title),
        help = stringResource(if (web != null) R.string.qr_scan_result_web_help else R.string.qr_scan_result_help),
        copy = stringResource(R.string.action_copy),
        open = stringResource(R.string.qr_scan_open),
    )
    val copied = stringResource(R.string.qr_copied)
    val noApp = stringResource(R.string.qr_scan_no_app)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        ScanResultContent(
            text = text,
            labels = labels,
            onCopy = {
                clipboard.copyText(scope, text)
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            },
            onOpen = web?.let { uri ->
                {
                    val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                    runCatching { context.startActivity(view) }
                        .onSuccess { onDismiss() }
                        .onFailure { Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show() }
                }
            }
        )
    }
}

/** The result sheet's strings, resolved before the sheet's window exists. */
internal data class ScanResultLabels(val title: String, val help: String, val copy: String, val open: String)

/** What [ScanResultSheet] shows; apart from the sheet so a preview can render it. */
@Composable
internal fun ScanResultContent(text: String, labels: ScanResultLabels, onCopy: () -> Unit, onOpen: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(labels.title, style = MaterialTheme.typography.titleLarge)
            HelpText(labels.help)
        }
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
            if (onOpen != null) {
                FilledTonalButton(onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(labels.open)
                }
            }
        }
    }
}
