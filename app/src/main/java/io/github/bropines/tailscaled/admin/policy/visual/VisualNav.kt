package io.github.bropines.tailscaled.admin.policy.visual

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.ScrollableSlidingSegmentedChips
import io.github.bropines.tailscaled.core.SegmentedChipItem
import io.github.bropines.tailscaled.ui.Fold
import io.github.bropines.tailscaled.ui.WindowLayout
import io.github.bropines.tailscaled.ui.WindowWidthClass

/*
 * The visual editor's page navigation: a row of chips under the top bar on a phone and a
 * medium window, the console's chip row in look; a rail down the side from an expanded window
 * up, as the console's own tab rail — names beside the icons on a large window, under them
 * below that. A page whose cards the server refused, or that gained a risk, carries the icon
 * that says so.
 */

/** What a page holds that needs a look: a refusal from the server outranks a new risk. */
enum class PageMark { ERROR, RISK }

/** One page as the navigation shows it. */
data class PageEntry(val section: VisualSection, val count: Int?, val mark: PageMark?)

/**
 * The pages offered for [model] on this backend, with their counts and marks. The page the
 * editor is on stays offered even when it would not be (a Headscale file that just lost its
 * last posture), so the navigation never loses its selection.
 */
fun pageEntries(model: PolicyModel?, headscale: Boolean, current: VisualSection, env: VisualEnv?): List<PageEntry> {
    val errors = env?.errors?.keys.orEmpty().map { VisualSection.of(it) }.toSet()
    val risks = env?.risks?.keys.orEmpty().map { VisualSection.of(it) }.toSet()
    return VisualSection.entries.filter { it == current || it.available(headscale, model) }.map { s ->
        PageEntry(
            section = s,
            count = model?.let { s.count(it) }?.takeIf { it > 0 },
            mark = when (s) {
                in errors -> PageMark.ERROR
                in risks -> PageMark.RISK
                else -> null
            },
        )
    }
}

/**
 * Whether the pages stand in a rail: from an expanded window up, not on a phone, and not on a
 * foldable open like a book — there the chips sit over the list's half, as the console does.
 */
val WindowLayout.visualRail: Boolean get() = !isPhone && widthClass >= WindowWidthClass.EXPANDED && fold !is Fold.Vertical

/** The rail with names beside the icons, on a large window. */
val WindowLayout.visualRailWide: Boolean get() = widthClass >= WindowWidthClass.LARGE

private fun markIcon(mark: PageMark?): ImageVector? = when (mark) {
    PageMark.ERROR -> Icons.Default.ErrorOutline
    PageMark.RISK -> Icons.Default.Warning
    null -> null
}

/** The page's name under its icon in the narrow rail: some 80dp, so the long ones say less. */
@StringRes
private fun shortLabel(s: VisualSection): Int = when (s) {
    VisualSection.APPROVERS -> R.string.admin_pv_shell_short_approvers
    VisualSection.HOSTS -> R.string.admin_pv_shell_short_hosts
    VisualSection.ATTRIBUTES -> R.string.admin_pv_shell_short_attributes
    VisualSection.POSTURE -> R.string.admin_pv_shell_short_posture
    VisualSection.RELAYS -> R.string.admin_pv_derp_page_short
    VisualSection.NETWORK -> R.string.admin_pv_shell_short_network
    else -> s.label
}

/** The pages as a row of chips scrolled across, each with its count and its mark. */
@Composable
fun VisualChipRow(pages: List<PageEntry>, current: VisualSection, onSelect: (VisualSection) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    ScrollableSlidingSegmentedChips(
        items = pages.map { SegmentedChipItem(ctx.getString(it.section.label), icon = markIcon(it.mark), count = it.count) },
        selectedIndex = pages.indexOfFirst { it.section == current }.coerceAtLeast(0),
        onOptionSelected = { i -> pages.getOrNull(i)?.let { onSelect(it.section) } },
        modifier = modifier,
        height = 40.dp,
    )
}

/**
 * The pages down the side of the window: [wide], a 240dp column of icon-and-name rows with the
 * count at the end; otherwise an 88dp rail, the short name under the icon. A mark replaces the
 * page's icon, so it reads without its colour. One page is selected; the rail is a group of
 * tabs to a screen reader.
 */
@Composable
fun VisualRail(pages: List<PageEntry>, current: VisualSection, wide: Boolean, onSelect: (VisualSection) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(
        modifier
            .width(if (wide) 240.dp else 88.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .selectableGroup()
            .padding(horizontal = if (wide) 12.dp else 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (wide) 2.dp else 4.dp),
    ) {
        pages.forEach { page ->
            val on = page.section == current
            val scheme = MaterialTheme.colorScheme
            val tint = when (page.mark) {
                PageMark.ERROR -> scheme.error
                PageMark.RISK -> scheme.tertiary
                null -> if (on) scheme.onSecondaryContainer else scheme.onSurfaceVariant
            }
            val icon = markIcon(page.mark) ?: page.section.icon
            val state = when (page.mark) {
                PageMark.ERROR -> ctx.getString(R.string.admin_pv_shell_mark_error)
                PageMark.RISK -> ctx.getString(R.string.admin_pv_shell_mark_risk)
                null -> null
            }
            val pick = Modifier
                .selectable(selected = on, role = Role.Tab) { onSelect(page.section) }
                .semantics { if (state != null) stateDescription = state }
            if (wide) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(CircleShape)
                        .background(if (on) scheme.secondaryContainer else Color.Transparent)
                        .then(pick)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = tint)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        ctx.getString(page.section.label),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) scheme.onSecondaryContainer else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    page.count?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = if (on) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).then(pick).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(width = 56.dp, height = 32.dp).clip(CircleShape)
                            .background(if (on) scheme.secondaryContainer else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, tint = tint) }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ctx.getString(shortLabel(page.section)),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) scheme.onSurface else scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
