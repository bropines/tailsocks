package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.MainItem
import io.github.bropines.tailscaled.core.MainLayout
import io.github.bropines.tailscaled.core.MainLayoutStore

/*
 * What stands around the canvas while the main screen is being arranged (ui/MainCanvas.kt):
 * the bar under it, the sheet of what can be added, the stand-in for a block that has nothing
 * to show right now — and, for a television, the whole editor as a list, because a remote has
 * nothing to drag with.
 */

// ─── The bar under the canvas ────────────────────────────────────────────────────────────────

/**
 * The editor's bar: how many columns the tiles stand in, and the three things to do — put
 * everything back, add something, finish. Every change is stored as it is made, so Done only
 * leaves.
 */
@Composable
internal fun MainEditBar(
    columns: Int,
    onColumns: (Int) -> Unit,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.main_layout_columns),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
                for (choice in 0..MainLayout.MAX_COLUMNS) {
                    val spoken = if (choice == MainLayout.AUTO) stringResource(R.string.main_layout_columns_auto)
                                 else stringResource(R.string.main_layout_columns_cd, choice)
                    FilterChip(
                        selected = columns == choice,
                        onClick = { onColumns(choice) },
                        label = { Text(if (choice == MainLayout.AUTO) stringResource(R.string.main_layout_columns_auto) else choice.toString()) },
                        modifier = Modifier.semantics { contentDescription = spoken },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReset) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.main_layout_reset), maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = onAdd, enabled = canAdd) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.main_layout_add), maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onDone) { Text(stringResource(R.string.main_blocks_done), maxLines = 1) }
            }
        }
    }
}

/** The plus at the end of the canvas: an empty place, drawn as one, that opens the list of what can fill it. */
@Composable
internal fun MainAddCell(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outline
    val shape = MaterialTheme.shapes.large
    Box(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .drawBehind {
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
                )
            }
            .shapedClickable(shape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.main_layout_add), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * A block that is on the screen and has nothing to draw right now — the exit-node line and
 * the graph of a connection that is not up — as the canvas shows it: by name, so that it can
 * still be moved and taken off.
 */
@Composable
internal fun MainBlockPlaceholder(item: MainItem, modifier: Modifier = Modifier) {
    val spec = item.spec()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(spec.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(spec.title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.main_layout_when_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─── What can be added ───────────────────────────────────────────────────────────────────────

/** A row of the sheet, its words already resolved: the sheet is a window of its own (see wrapContextWithLocale). */
internal data class PaletteRow(val item: MainItem, val title: String, val desc: String, val icon: ImageVector, val group: String)

/** [items] as the sheet lists them, named in the screen's language. */
@Composable
internal fun paletteRows(items: List<MainItem>, tv: Boolean = isTv): List<PaletteRow> = items.map { item ->
    val spec = item.spec(tv)
    PaletteRow(item, stringResource(spec.title), stringResource(spec.desc), spec.icon, stringResource(item.group.title()))
}

/**
 * Everything that is not on the main screen and could be, by kind. A press adds one and the
 * sheet stays: whoever opens it usually wants more than one thing, and each lands on the canvas
 * behind it at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainPaletteSheet(items: List<MainItem>, onAdd: (MainItem) -> Unit, onDismiss: () -> Unit) {
    val rows = paletteRows(items)
    val title = stringResource(R.string.main_layout_palette_title)
    val empty = stringResource(R.string.main_layout_palette_empty)
    val add = stringResource(R.string.main_layout_add)
    AppModalSheet(onDismissRequest = onDismiss) {
        MainPaletteContent(rows, title, empty, add, onAdd, Modifier.navigationBarsPadding())
    }
}

@Composable
internal fun MainPaletteContent(
    rows: List<PaletteRow>,
    title: String,
    empty: String,
    addLabel: String,
    onAdd: (MainItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
        )
        if (rows.isEmpty()) {
            Text(
                empty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            )
            return@Column
        }
        Spacer(Modifier.height(4.dp))
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
                    Surface(
                        onClick = { onAdd(row.item) },
                        shape = MaterialTheme.shapes.medium,
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.animateItem().fillMaxWidth().semantics { contentDescription = "$addLabel: ${row.title}. ${row.desc}" },
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

// ─── A television: the editor as a list ──────────────────────────────────────────────────────

/**
 * The main screen's arrangement for a remote: what is on it, in its order, each row a switch
 * with two buttons that move it; under those, what could be added. Dragging is a finger's way
 * and this is the same model reached with four arrows. It reads and stores the layout itself —
 * it opens from Settings, and the main screen reads the layout again when it comes back.
 */
@Composable
internal fun MainLayoutListDialog(onDismiss: () -> Unit) {
    // The parent's context: the dialog is a window of its own and would answer in the system's language.
    val context = LocalContext.current
    val tv = isTv
    val remoteTile = !Feature.REMOTE_MANAGEMENT_ONLY_IN_SETTINGS.offered()
    val offered = rememberMainItemsOffered()
    var layout by remember { mutableStateOf(MainLayoutStore.load(context, remoteTile)) }
    fun change(next: MainLayout) {
        if (next == layout) return
        layout = next
        MainLayoutStore.save(context, next)
    }
    val titleText = stringResource(R.string.settings_main_blocks_title)
    val done = stringResource(R.string.main_blocks_done)
    val reset = stringResource(R.string.main_layout_reset)
    val always = stringResource(R.string.main_layout_always)
    val earlier = stringResource(R.string.main_layout_move_earlier)
    val later = stringResource(R.string.main_layout_move_later)
    AppAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(done) } },
        dismissButton = {
            TextButton(onClick = {
                MainLayoutStore.reset(context)
                layout = MainLayoutStore.load(context, remoteTile)
            }) { Text(reset) }
        },
        title = { Text(titleText) },
        text = {
            val shown = layout.shown(offered)
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                shown.forEachIndexed { i, placed ->
                    val item = placed.item
                    val spec = item.spec(tv)
                    LayoutListRow(
                        title = context.getString(spec.title),
                        note = if (item.removable) null else always,
                        checked = true,
                        onToggle = if (item.removable) ({ change(layout.remove(item)) }) else null,
                        earlier = earlier.takeIf { i > 0 },
                        later = later.takeIf { i < shown.lastIndex },
                        onStep = { change(layout.step(item, it, offered)) },
                    )
                }
                val addable = layout.addable(offered)
                if (addable.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                for (item in addable) {
                    val spec = item.spec(tv)
                    LayoutListRow(
                        title = context.getString(spec.title),
                        note = context.getString(spec.desc),
                        checked = false,
                        onToggle = { change(layout.add(item)) },
                        earlier = null,
                        later = null,
                        onStep = {},
                    )
                }
            }
        },
    )
}

/**
 * One item of the list. The words and the switch are one target — one stop for a remote —
 * and the two arrows beside it are the other two; an arrow that has nowhere to move the row
 * keeps its place, empty, so the rows stay in line.
 */
@Composable
private fun LayoutListRow(
    title: String,
    note: String?,
    checked: Boolean,
    onToggle: (() -> Unit)?,
    earlier: String?,
    later: String?,
    onStep: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.medium)
                .then(
                    if (onToggle == null) Modifier
                    else Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
                )
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onToggle != null) {
                Spacer(Modifier.width(12.dp))
                Switch(checked = checked, onCheckedChange = null)
            }
        }
        if (earlier != null) IconButton(onClick = { onStep(-1) }) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = earlier) }
        else if (checked) Spacer(Modifier.size(48.dp))
        if (later != null) IconButton(onClick = { onStep(+1) }) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = later) }
        else if (checked) Spacer(Modifier.size(48.dp))
    }
}
