package io.github.bropines.tailscaled.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.BuildConfig
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The main screen's About dialog: the versions, the update check and its download, the
 * project's pages, the credits and the licence. Opened from the main screen's top bar
 * (ui/MainActivity.kt); all its state lives inside it, so closing it takes it down.
 */

/**
 * [scope] is the main screen's: the update check runs there, as it always has, so a check
 * still in flight when the dialog is closed finishes rather than being cancelled. [onWhatsNew]
 * closes this dialog and opens "What's new".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MainAboutDialog(scope: CoroutineScope, onDismiss: () -> Unit, onWhatsNew: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }
        catch (e: Exception) { "?" }
    }
    val coreVer = remember {
        try { appctr.Appctr.getCoreVersion() } catch (e: Exception) { "unknown" }
    }
    // Saveable: the answer of a check outlives a rotation, so a dialog that comes back
    // still offers the update — and the download under way, which UpdateDownload holds.
    var latestVersion by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val download by UpdateDownload.progress.collectAsState()
    val isDownloading = download != null
    val downloadProgress = download?.percent ?: 0

    // The one question the dialog answers first: is an update waiting?
    val updateReady = latestVersion?.let { isVersionNewer(versionName, it) } == true

    // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
    val dlgTitle = stringResource(R.string.main_about_title)
    // A beta build says so in one word after the name (docs/RELEASING.md).
    val dlgAppName = stringResource(R.string.app_name).let { if (AppVersion.isBeta(versionName)) stringResource(R.string.main_about_beta_name, it) else it }
    val dlgAppVersion = stringResource(R.string.main_app_version, "$versionName (${BuildConfig.GIT_HASH})")
    val dlgCoreVersion = stringResource(R.string.main_core_version, coreVer)
    val dlgUpToDate = stringResource(R.string.main_update_up_to_date)
    val dlgChecking = stringResource(R.string.main_update_checking)
    val dlgCheckUpdates = stringResource(R.string.main_check_updates)
    val dlgUpdateDownload = stringResource(R.string.main_update_download)
    val dlgUpdateInstall = stringResource(R.string.main_update_install_cached)
    val dlgWhatsNew = stringResource(R.string.main_about_whats_new)
    val dlgGithub = stringResource(R.string.action_github)
    val dlgIssueList = stringResource(R.string.issue_list_action)
    val dlgCreditsHeader = stringResource(R.string.main_about_credits)
    val dlgLicense = stringResource(R.string.main_license_text).trim()
    val dlgClose = stringResource(R.string.action_close)
    // Every person this build stands on, each next to where their work lives.
    val credits = listOf(
        Triple(stringResource(R.string.main_dev_app), "https://github.com/bropines", Icons.Default.Person),
        Triple(stringResource(R.string.main_dev_patch), "https://github.com/bropines/tailsocks", Icons.Default.Build),
        Triple(stringResource(R.string.main_dev_anet_patch), "https://github.com/Asutorufa/tailscale", Icons.Default.Extension),
        Triple(stringResource(R.string.main_dev_core), "https://github.com/tailscale/tailscale", Icons.Default.Hub),
        Triple(stringResource(R.string.main_dev_claude), CLAUDE_URL, Icons.Default.AutoAwesome)
    )
    // A link opens in the browser; where none answers — a television — it shows its address with a QR code.
    val links = rememberLinkOpener()
    // Lives inside this block on purpose: closing the dialog takes it down too.
    var showAboutBackdrop by remember { mutableStateOf(false) }
    var showVibeCoding by remember { mutableStateOf(false) }
    var showIssueReport by remember { mutableStateOf(false) }
    if (showIssueReport) {
        IssueReportDialog(onDismiss = { showIssueReport = false })
    }
    if (showAboutBackdrop) {
        AboutBackdrop(onDismiss = { showAboutBackdrop = false })
    }
    if (showVibeCoding) {
        VibeCodingScene(onDismiss = { showVibeCoding = false })
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(dlgTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Identity and update state — one block, read before anything else.
                // A step above the dialog itself: AlertDialogDefaults.containerColor is
                // surfaceContainerHigh, so a card of that colour is the dialog's own
                // ground and nothing is drawn at all. The credits below sit a step under
                // it, so the block that carries the versions and the update button is
                // the one that reads first.
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(dlgAppName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            dlgAppVersion,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            dlgCoreVersion,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // A copy a store keeps up to date has no GitHub check to offer.
                        if (remember { UpdateChannel.selfUpdate(context) }) {
                            Spacer(Modifier.height(12.dp))

                            // Which version this is about stays on screen while it downloads:
                            // that is the moment the user most wants to see what is being
                            // installed, and the progress line only says how far it has got.
                            if (latestVersion != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (updateReady) Icons.Default.Download else Icons.Default.CheckCircle,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (updateReady) context.getString(R.string.main_new_version, latestVersion!!) else dlgUpToDate,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            if (isDownloading) {
                                LinearProgressIndicator(
                                    progress = { if (downloadProgress > 0) downloadProgress / 100f else 0f },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    context.getString(R.string.main_update_downloading, downloadProgress),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                if (updateReady) {
                                    // An update is waiting: the action is a real button, not a line of text.
                                    val destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
                                    val cleanVer = latestVersion!!.removePrefix("v")
                                    val destFile = UpdateDownload.apkFile(destDir, cleanVer)

                                    // Whether it is already downloaded parses the APK: off the main thread, not in
                                    // a frame. The button reads Download for the moment that takes.
                                    var isApkCached by remember(destFile.absolutePath) { mutableStateOf(false) }
                                    LaunchedEffect(destFile.absolutePath) {
                                        isApkCached = withContext(Dispatchers.IO) { UpdateDownload.isOwnApk(context, destFile) }
                                    }

                                    Button(
                                        onClick = {
                                            if (isApkCached) {
                                                Toast.makeText(context, context.getString(R.string.main_update_installing), Toast.LENGTH_SHORT).show()
                                                launchApkInstaller(context, destFile)
                                                return@Button
                                            }
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                                                Toast.makeText(context, context.getString(R.string.main_update_grant_perm), Toast.LENGTH_LONG).show()
                                                try {
                                                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                                } catch (e: Exception) {
                                                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                                                }
                                                return@Button
                                            }
                                            // No asset matched this device's ABI: send the user to the
                                            // release page. The old fallback downloaded
                                            // latest/download/app-release.apk, a file no release has had.
                                            if (downloadUrl == null) {
                                                links.open(UpdateCheck.page(latestVersion!!), dlgGithub)
                                                return@Button
                                            }
                                            // In the process's scope (UpdateDownload): a rotation or a
                                            // closed dialog no longer cancels it halfway.
                                            UpdateDownload.start(context, downloadUrl!!, cleanVer, destDir)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(if (isApkCached) Icons.Default.SystemUpdate else Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (isApkCached) dlgUpdateInstall else dlgUpdateDownload)
                                    }
                                } else {
                                    // Nothing to install: the button asks, and asks again after an answer.
                                    FilledTonalButton(
                                        onClick = {
                                            isCheckingUpdate = true
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    // The newest release — or beta, for whoever asked for them (core/UpdateCheck.kt).
                                                    val release = UpdateCheck.latest(GlobalSettings.isBetaUpdates(context))
                                                    val apkUrl = UpdateCheck.apkFor(release, Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                                                    withContext(Dispatchers.Main) {
                                                        latestVersion = release.tag
                                                        downloadUrl = apkUrl
                                                        isCheckingUpdate = false
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(context, context.getString(R.string.main_check_failed_format, e.message), Toast.LENGTH_SHORT).show()
                                                        isCheckingUpdate = false
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !isCheckingUpdate,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (isCheckingUpdate) {
                                            ButtonBusyIndicator(18.dp, color = LocalContentColor.current)
                                            Spacer(Modifier.width(8.dp))
                                            Text(dlgChecking)
                                        } else {
                                            Text(dlgCheckUpdates)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // The project's own pages, as one pair of buttons rather than two more
                // lines of text. Equal halves, and a label too long for its half wraps
                // rather than truncating — "Что нового" runs out of room at the larger
                // font scales, and half a word is worse than two lines. IntrinsicSize.Min
                // gives both buttons the height of the taller one so the pair stays even.
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onWhatsNew,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.NewReleases, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(dlgWhatsNew, textAlign = TextAlign.Center)
                    }
                    OutlinedButton(
                        onClick = {
                            val opened = links.open(
                                latestVersion?.let { UpdateCheck.page(it) } ?: "https://github.com/bropines/tailsocks",
                                dlgGithub
                            )
                            if (opened) onDismiss()
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(dlgGithub, textAlign = TextAlign.Center)
                    }
                }

                // Where a problem goes. The report is built and shown before anything
                // leaves the device, so the button next to it only opens the list.
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showIssueReport = true },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.BugReport, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.issue_report_action), textAlign = TextAlign.Center)
                    }
                    OutlinedButton(
                        onClick = { links.open("https://github.com/bropines/tailsocks/issues", dlgIssueList) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(dlgIssueList, textAlign = TextAlign.Center)
                    }
                }

                // The people, kept together and out of the way of the state above.
                Column {
                    Text(
                        dlgCreditsHeader,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                    )
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            credits.forEachIndexed { index, (label, url, icon) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // These rows are the only way to the contributors'
                                        // pages, and a one-line credit measures 36dp —
                                        // under the 48dp a finger is entitled to.
                                        .heightIn(min = 48.dp)
                                        // The first row and Claude's also answer a long press.
                                        .combinedClickable(
                                            onClick = { links.open(url, label) },
                                            onLongClick = when {
                                                index == 0 -> ({ showAboutBackdrop = true })
                                                url == CLAUDE_URL -> ({ showVibeCoding = true })
                                                else -> null
                                            }
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        icon,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    // No maxLines: a narrow screen wraps a credit, it never truncates one.
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }

                // Ours in a line; everyone else's a tap away, with their full texts.
                OutlinedButton(
                    onClick = { context.startActivity(Intent(context, LicensesActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Gavel, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.licenses_title), textAlign = TextAlign.Center)
                }
                Text(
                    dlgLicense,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(dlgClose) }
        }
    )
}

/** Where the Claude credit in About leads. */
private const val CLAUDE_URL = "https://claude.ai"
