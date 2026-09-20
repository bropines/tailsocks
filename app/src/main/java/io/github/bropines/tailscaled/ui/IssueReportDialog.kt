package io.github.bropines.tailscaled.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

private const val ISSUES_NEW = "https://github.com/bropines/tailsocks/issues/new"

/** GitHub takes the issue form over the URL, but a browser will not carry an unbounded one. */
private const val BODY_LIMIT = 4000

/**
 * What to send when something is wrong: the diagnostics, visible before they leave the
 * device, and the three ways out — the clipboard, a share target, or GitHub's new-issue
 * form with the report already in it.
 *
 * The report is built once, off the main thread, and shown in full: nothing is sent
 * anywhere the reader has not seen first.
 */
@Composable
fun IssueReportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) { Diagnostics.report(context) }
    }

    val title = stringResource(R.string.issue_report_title)
    val intro = stringResource(R.string.issue_report_intro)
    val gathering = stringResource(R.string.issue_report_gathering)
    val copyLabel = stringResource(R.string.issue_report_copy)
    val shareLabel = stringResource(R.string.issue_report_share)
    val openLabel = stringResource(R.string.issue_report_open)
    val copied = stringResource(R.string.issue_report_copied)
    val close = stringResource(R.string.action_close)
    val template = stringResource(R.string.issue_report_template)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BugReport, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HelpText(intro)
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                ) {
                    Text(
                        report ?: gathering,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { report?.let { copyToClipboard(context, it, copied) } },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size18())
                        Spacer(Modifier.width(6.dp))
                        Text(copyLabel, textAlign = TextAlign.Center)
                    }
                    OutlinedButton(
                        onClick = { report?.let { shareReport(context, it) } },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size18())
                        Spacer(Modifier.width(6.dp))
                        Text(shareLabel, textAlign = TextAlign.Center)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val body = template + "\n\n```\n" + (report ?: "") + "\n```"
                    openIssueForm(context, body)
                    onDismiss()
                },
                enabled = report != null
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size18())
                Spacer(Modifier.width(6.dp))
                Text(openLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(close) } }
    )
}

private fun Modifier.size18() = this.then(Modifier.height(18.dp).width(18.dp))

private fun copyToClipboard(context: Context, text: String, confirmation: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("TailSocks diagnostics", text))
    Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
}

private fun shareReport(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "TailSocks diagnostics")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

/**
 * GitHub's new-issue form, pre-filled. The body is capped: a URL long enough to carry a
 * whole log is one the browser drops on the floor, and a truncated report that arrives
 * beats a complete one that does not.
 */
private fun openIssueForm(context: Context, body: String) {
    val trimmed = if (body.length > BODY_LIMIT) body.take(BODY_LIMIT) + "\n… truncated, paste the rest" else body
    val url = ISSUES_NEW +
        "?labels=bug" +
        "&title=" + URLEncoder.encode("", "UTF-8") +
        "&body=" + URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_NEW))) } }
}
