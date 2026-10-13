package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R

import io.github.bropines.tailscaled.core.Changelog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "What's new" dialog showing the newest section of the bundled CHANGELOG.md, in Russian from
 * CHANGELOG_RU.md when the app is (and the file has that version).
 *
 * Shown automatically once per app version from MainActivity, and on demand
 * from the About dialog. [onDismiss] is invoked for Close, the outside tap and
 * the "Full changelog" button, where that opens a browser (where none does the button shows
 * the address and the dialog stays); the caller records the version as seen there.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var section by remember { mutableStateOf<Changelog.Section?>(null) }
    var loaded by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    // The app's language, which the activity's configuration carries (see wrapContextWithLocale).
    val language = configuration.locales[0].language
    LaunchedEffect(language) {
        section = withContext(Dispatchers.IO) { Changelog.latest(context, language) }
        loaded = true
    }

    val maxHeight = (configuration.screenHeightDp * 0.6f).dp
    // A television has no browser as often as not: then the full list is an address and a QR code.
    val links = rememberLinkOpener()
    val fullChangelog = context.getString(R.string.whats_new_full_changelog)
    val scroll = rememberScrollState()
    val focusSpent = remember { mutableStateOf(false) }

    // Strings come from the parent context, not stringResource() — see wrapContextWithLocale().
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NewReleases, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    val s = section
                    Text(
                        if (s != null) context.getString(R.string.whats_new_title, s.version)
                        else context.getString(R.string.main_about_whats_new)
                    )
                    val date = section?.date.orEmpty()
                    if (date.isNotEmpty()) {
                        Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    // The text is the one stop a remote has in it, so its arrows read the list.
                    .tvReadingPane(scroll, focusOnce = focusSpent)
                    .verticalScroll(scroll)
            ) {
                val s = section
                when {
                    !loaded -> {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            LoadingIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                    s == null -> {
                        Text(context.getString(R.string.whats_new_unavailable), color = MaterialTheme.colorScheme.outline)
                    }
                    else -> ChangelogSectionBody(s)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (links.open(Changelog.FULL_CHANGELOG_URL, fullChangelog)) onDismiss()
            }) { Text(fullChangelog) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_close)) } }
    )
}

@Composable
private fun ChangelogSectionBody(section: Changelog.Section) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeFg = MaterialTheme.colorScheme.onSurfaceVariant
    section.groups.forEachIndexed { index, group ->
        if (index > 0) Spacer(Modifier.height(12.dp))
        if (group.title.isNotEmpty()) {
            Text(
                group.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
        }
        group.items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (item.level * 16).dp, top = 2.dp, bottom = 2.dp)
            ) {
                Text(
                    if (item.level == 0) "•" else "–",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                val rendered = remember(item.text, codeBg, codeFg) {
                    Changelog.inlineMarkdown(item.text, codeBackground = codeBg, codeColor = codeFg)
                }
                Text(rendered, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
