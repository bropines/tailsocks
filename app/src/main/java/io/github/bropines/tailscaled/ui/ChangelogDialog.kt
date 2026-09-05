package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R

import io.github.bropines.tailscaled.core.Changelog

import android.content.Intent
import android.net.Uri
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
 * "What's new" dialog showing the newest section of the bundled CHANGELOG.md.
 *
 * Shown automatically once per app version from MainActivity, and on demand
 * from the About dialog. [onDismiss] is invoked for Close, the outside tap and
 * the "Full changelog" button; the caller records the version as seen there.
 */
@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var section by remember { mutableStateOf<Changelog.Section?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        section = withContext(Dispatchers.IO) { Changelog.latest(context) }
        loaded = true
    }

    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.6f).dp

    // Strings come from the parent context, not stringResource() — see wrapContextWithLocale().
    AlertDialog(
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
                    .verticalScroll(rememberScrollState())
            ) {
                val s = section
                when {
                    !loaded -> {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
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
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Changelog.FULL_CHANGELOG_URL)))
                } catch (_: Exception) {}
                onDismiss()
            }) { Text(context.getString(R.string.whats_new_full_changelog)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_close)) }
        }
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
