package io.github.bropines.tailscaled.ui

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import kotlinx.serialization.json.*

/*
 * The main screen's destinations: one entry per screen, the grid of cards they stand in and
 * how many columns a width holds. MainScreen in MainActivity.kt builds the entries and
 * chooses the layout; the cards themselves are drawn here.
 */

/**
 * A destination on the main screen; [icon2], when set, stands beside [icon] for a screen of two parts.
 *
 * A [wide] entry has a row of its own across the grid — under the cards unless the person put it
 * elsewhere — and a second line: its [state], which [stateWarns] sets apart (what it says is not
 * a state to be happy with). It takes no part in how many columns the cards have. [focusOnShow] puts the remote on it as it appears:
 * the page it opened has just been left.
 */
data class MenuEntry(
    val title: String,
    val icon: ImageVector,
    val icon2: ImageVector? = null,
    val state: String? = null,
    val stateWarns: Boolean = false,
    val wide: Boolean = false,
    val focusOnShow: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The destinations, [columns] to a row.
 *
 * The cards are given a height instead of taking one from their content: a row
 * of them used to grow as wide as the screen and stay as short as the text,
 * which is what made landscape look like a stretched portrait. A row that comes
 * up short is padded with empty weight, so the last card is the width of the
 * others and not the width of the row.
 */
/**
 * How many menu columns [width] holds: as many cards of at least 100dp as fit
 * with MenuGrid's 16dp gaps, between two and four, then down to a count that
 * divides the [entries] evenly — eight cards as 4+4 or 2+2+2+2, never a row
 * left with two cards and an empty hole.
 */
fun menuColumnsFor(width: Dp, entries: Int = 8): Int {
    val fit = ((width + 16.dp) / 116.dp).toInt().coerceIn(2, 4)
    return (fit downTo 2).firstOrNull { entries % it == 0 } ?: 2
}

@Composable
fun MenuGrid(
    columns: Int,
    entries: List<MenuEntry>,
    cardHeight: Dp = 96.dp,
    modifier: Modifier = Modifier,
    /** Asks the first card to answer it: where the remote comes in on the grid from the status card. */
    start: FocusRequester? = null,
    /** Tiles that share the row of the [MenuEntry.wide] entry, each taking a weight of it; null leaves that entry the row. */
    beside: (@Composable RowScope.() -> Unit)? = null,
) {
    // In the order given: cards side by side until a row is full or an entry that takes the
    // row comes, which is a row by itself — after the cards, where nobody moved it.
    val rows = buildList<List<MenuEntry>> {
        var row = mutableListOf<MenuEntry>()
        for (entry in entries) {
            if (entry.wide) {
                if (row.isNotEmpty()) add(row)
                add(listOf(entry))
                row = mutableListOf()
            } else {
                row += entry
                if (row.size == columns.coerceAtLeast(1)) {
                    add(row)
                    row = mutableListOf()
                }
            }
        }
        if (row.isNotEmpty()) add(row)
    }
    val firstWide = entries.firstOrNull { it.wide }
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(modifier = Modifier.height(16.dp))
            val startHere = if (start != null && rowIndex == 0) Modifier.focusRequester(start) else Modifier
            val wide = row.singleOrNull()?.takeIf { it.wide }
            if (wide != null) {
                // One row whether it is shared or not, tile for tile and as tall as the taller of them:
                // the entry is the same tile when what stood beside it goes away, and a remote that
                // was on it stays on it.
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WideMenuCard(
                        wide,
                        Modifier.weight(1f).fillMaxHeight().then(startHere).then(if (wide.focusOnShow) Modifier.tvInitialFocus() else Modifier)
                    )
                    // The shared tiles stand beside the first such row only: a second row is its own.
                    if (wide === firstWide) beside?.invoke(this)
                }
            } else {
                // A minimum, not a fixed height: at a large system font the label
                // needs more room, and the row grows to its tallest card so the
                // cards in it stay the same height.
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.forEachIndexed { i, entry ->
                        if (i > 0) Spacer(modifier = Modifier.width(16.dp))
                        MenuCard(
                            title = entry.title,
                            icon = entry.icon,
                            icon2 = entry.icon2,
                            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = cardHeight)
                                .then(if (i == 0) startHere else Modifier),
                            onClick = entry.onClick
                        )
                    }
                    repeat(columns - row.size) {
                        Spacer(modifier = Modifier.width(16.dp))
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * A destination across the width of the grid: its icon and name, and under the name what state it
 * is in. For what a device would not find in the settings, and wants to see the state of.
 */
@Composable
fun WideMenuCard(entry: MenuEntry, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = entry.onClick,
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = if (entry.stateWarns) colors.outline else colors.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.state != null) {
                    Text(
                        text = entry.state,
                        color = if (entry.stateWarns) colors.tertiary else colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun MenuCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, icon2: ImageVector? = null, onClick: () -> Unit) {
    // Surface(onClick): the ripple is clipped to the rounded card. As a
    // clickable modifier ahead of the shape it drew a rectangle past the corners.
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        Column(
            // Fills what it is given: in the grid that is a fixed height, and
            // the icon and its label sit in the middle of the card rather than
            // at the top of it. Where the height is free, this is the size of
            // the content, exactly as before.
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // The label below names the tile: an icon with the same words was read out twice.
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                if (icon2 != null) Icon(imageVector = icon2, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
