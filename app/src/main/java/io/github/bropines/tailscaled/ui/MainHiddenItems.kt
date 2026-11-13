package io.github.bropines.tailscaled.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.MainItem
import io.github.bropines.tailscaled.core.MainLayoutStore

/*
 * Settings → Appearance: what the person took off the main screen, still within reach.
 *
 * A tile taken off the screen was, for half of them, the only way to its screen: the peers,
 * the files, the tailnet map, Taildrive, Tailcat, the exit-node list, the scanner, the
 * accounts. Here each opens as its tile would, and one press puts it back on the screen
 * where the editor's palette would (MainLayout.add). A block opens nothing; it can only go
 * back. Not on a television: its screen is not the person's to arrange (MainBlocksRow).
 */

/**
 * The row, with how many things are off the screen; it opens the sheet. [openHere] is
 * Settings' own way to what it already shows — remote management, a section of its own —
 * true when it took the item; anything else opens as from the main screen. [onRestored]
 * tells Settings the layout changed, for the switches beside this row that read it.
 */
@Composable
fun MainHiddenRow(remoteTile: Boolean, openHere: (MainItem) -> Boolean, onRestored: () -> Unit) {
    if (isTv) return
    val context = LocalContext.current
    val offered = rememberMainItemsOffered()
    var version by remember { mutableIntStateOf(0) }
    val hidden = remember(version) { MainLayoutStore.load(context, remoteTile).addable(offered) }
    var open by remember { mutableStateOf(false) }
    SettingsClickableItem(
        title = stringResource(R.string.main_hidden_title),
        subtitle = if (hidden.isEmpty()) stringResource(R.string.main_hidden_none)
        else pluralStringResource(R.plurals.main_hidden_count, hidden.size, hidden.size),
        icon = Icons.Default.VisibilityOff,
        onClick = { version++; open = true },
    )
    if (open) {
        MainHiddenSheet(
            items = hidden,
            onOpen = { item ->
                open = false
                if (!openHere(item)) item.openAsTile(context)
            },
            onRestore = { item ->
                MainLayoutStore.restore(context, remoteTile, item)
                version++
                onRestored()
            },
            onDismiss = { open = false },
        )
    }
}

/** Opens [this] as its tile on the main screen would, from outside that screen. */
internal fun MainItem.openAsTile(context: Context) {
    when (this) {
        MainItem.EXIT_PICKER -> DeepLinks.open(context, Uri.parse("${DeepLinks.SCHEME}://${DeepLinks.EXIT_NODE}"))
        MainItem.SCAN -> DeepLinks.open(context, Uri.parse("${DeepLinks.SCHEME}://${DeepLinks.SCAN}"))
        MainItem.ACCOUNTS -> context.startActivity(MainActivity.accountsIntent(context))
        else -> screen(context)?.let { context.startActivity(it) }
    }
}

/** Whether [this] opens anything: the blocks do not, they live on the main screen only. */
internal val MainItem.opens: Boolean get() = isTile

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MainHiddenSheet(
    items: List<MainItem>,
    onOpen: (MainItem) -> Unit,
    onRestore: (MainItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = paletteRows(items)
    val restoreLabel = stringResource(R.string.main_hidden_restore)
    val openLabel = stringResource(R.string.main_hidden_open)
    AppModalSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.main_hidden_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
            )
            Text(
                stringResource(R.string.main_hidden_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.main_hidden_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                )
                return@Column
            }
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp).weight(1f, fill = false)) {
                for ((group, members) in rows.groupBy { it.group }) {
                    item(key = "group:$group") {
                        Text(
                            group,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.animateItem().padding(start = 12.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                        )
                    }
                    items(members.size, key = { members[it].item.id }) { i ->
                        val row = members[i]
                        val opens = row.item.opens
                        // A block has nothing to open: the whole row puts it back.
                        Surface(
                            onClick = { if (opens) onOpen(row.item) else onRestore(row.item) },
                            shape = MaterialTheme.shapes.medium,
                            color = Color.Transparent,
                            modifier = Modifier.animateItem().fillMaxWidth().semantics {
                                contentDescription = "${if (opens) openLabel else restoreLabel}: ${row.title}. ${row.desc}"
                            },
                        ) {
                            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(row.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        row.desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                IconButton(onClick = { onRestore(row.item) }) {
                                    Icon(
                                        Icons.Default.AddToHomeScreen,
                                        contentDescription = "$restoreLabel: ${row.title}",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
