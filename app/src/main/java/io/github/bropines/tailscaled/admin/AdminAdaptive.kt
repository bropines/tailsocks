package io.github.bropines.tailscaled.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Webhook
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.ui.Fold
import io.github.bropines.tailscaled.ui.LocalInPane
import io.github.bropines.tailscaled.ui.ReadableWidth
import io.github.bropines.tailscaled.ui.WindowLayout
import io.github.bropines.tailscaled.ui.WindowWidthClass

/*
 * The console on a large window. Its twelve tabs are siblings inside one Activity, so from an
 * expanded window up they stand in a rail down the side — the one in-Activity rail of the app —
 * instead of a row of chips scrolled across the top; the list tabs show the detail of what is
 * picked in a pane beside the list (ListDetailLayout) where a phone opens a sheet, and the tabs
 * of cards stand in columns. A phone, either way up, keeps the console it had.
 */

/**
 * Whether the tabs stand in a rail: from an expanded window up, not on a phone — and not on a
 * foldable open like a book, where the two halves are the list and the detail, and a rail on
 * the first half would push the list across the hinge.
 */
internal val WindowLayout.adminRail: Boolean
    get() = !isPhone && widthClass >= WindowWidthClass.EXPANDED && fold !is Fold.Vertical

/** The rail with names beside the icons, on a large window; under them below that. */
internal val WindowLayout.adminRailWide: Boolean get() = widthClass >= WindowWidthClass.LARGE

/**
 * How a tab that is a page of cards (DNS, Settings, the Headscale server, the web links) stands
 * in the window: as on a phone; in one column held to a readable width on a medium window, where
 * two columns would leave an upright tablet's lower half empty; or in as many columns as fit,
 * from an expanded window up.
 */
internal enum class CardPage { PHONE, READABLE, COLUMNS }

internal val WindowLayout.cardPage: CardPage
    get() = when {
        isPhone -> CardPage.PHONE
        widthClass >= WindowWidthClass.EXPANDED -> CardPage.COLUMNS
        else -> CardPage.READABLE
    }

/** The widest a detail made for a sheet stands in a pane: a bottom sheet's own limit. */
internal val PaneContentMaxWidth: Dp = 640.dp

/** The tab's name, as the chip row and the wide rail show it. */
internal fun tabLabel(tab: ConsoleTab): Int = when (tab) {
    ConsoleTab.ATTENTION -> R.string.admin_attention_tab
    ConsoleTab.DEVICES -> R.string.admin_tab_devices
    ConsoleTab.DNS -> R.string.admin_tab_dns
    ConsoleTab.POLICY -> R.string.admin_cfg_tab_policy
    ConsoleTab.USERS -> R.string.admin_tab_users
    ConsoleTab.KEYS -> R.string.admin_k_tab
    ConsoleTab.SERVICES -> R.string.admin_tab_services
    ConsoleTab.WEBHOOKS -> R.string.admin_tab_webhooks
    ConsoleTab.LOGS -> R.string.admin_tab_logs
    ConsoleTab.WEB -> R.string.admin_tab_web_links
    ConsoleTab.SETTINGS -> R.string.admin_tab_settings
    ConsoleTab.SERVER -> R.string.admin_hs_tab
}

/** The name under the icon in the narrow rail: some 80dp, so the long ones say less. */
private fun shortTabLabel(tab: ConsoleTab): Int = when (tab) {
    ConsoleTab.ATTENTION -> R.string.tablet_admin_rail_attention
    ConsoleTab.USERS -> R.string.tablet_admin_rail_users
    ConsoleTab.WEB -> R.string.tablet_admin_rail_web
    else -> tabLabel(tab)
}

private fun tabIcon(tab: ConsoleTab): ImageVector = when (tab) {
    ConsoleTab.ATTENTION -> Icons.Default.NotificationImportant
    ConsoleTab.DEVICES -> Icons.Default.Devices
    ConsoleTab.DNS -> Icons.Default.Dns
    ConsoleTab.POLICY -> Icons.Default.Policy
    ConsoleTab.USERS -> Icons.Default.Group
    ConsoleTab.KEYS -> Icons.Default.VpnKey
    ConsoleTab.SERVICES -> Icons.Default.CloudQueue
    ConsoleTab.WEBHOOKS -> Icons.Default.Webhook
    ConsoleTab.LOGS -> Icons.Default.History
    ConsoleTab.WEB -> Icons.Default.Language
    ConsoleTab.SETTINGS -> Icons.Default.Settings
    ConsoleTab.SERVER -> Icons.Default.Storage
}

/**
 * The console's tabs down the side of the window: [wide], a 220dp column of icon-and-name rows;
 * otherwise an 88dp rail, the name under the icon. It scrolls when a short window cannot hold
 * all twelve. One tab is [selected]; the rail is a group of tabs to a screen reader.
 */
@Composable
internal fun AdminTabRail(
    tabs: List<ConsoleTab>,
    selected: Int,
    wide: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Column(
        modifier
            .width(if (wide) 220.dp else 88.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .selectableGroup()
            .padding(horizontal = if (wide) 12.dp else 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (wide) 2.dp else 4.dp),
    ) {
        tabs.forEachIndexed { i, tab ->
            val on = i == selected
            val scheme = MaterialTheme.colorScheme
            val tint = if (on) scheme.onSecondaryContainer else scheme.onSurfaceVariant
            val pick = Modifier.selectable(selected = on, role = Role.Tab) { onSelect(i) }
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
                    Icon(tabIcon(tab), null, tint = tint)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        ctx.getString(tabLabel(tab)),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) scheme.onSecondaryContainer else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                    ) { Icon(tabIcon(tab), null, tint = tint) }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ctx.getString(shortTabLabel(tab)),
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

/**
 * A detail that is a sheet on a phone, in its pane: the top margin the sheet's drag handle
 * gave it, and no wider than the sheet was ([PaneContentMaxWidth]), centred — a page of
 * full-width buttons and label–value rows laid out for a sheet reads the same in a pane on any
 * window. Outside a pane it is [content] and nothing else.
 */
@Composable
internal fun InPaneWidth(content: @Composable () -> Unit) {
    if (!LocalInPane.current) {
        content()
        return
    }
    Box(Modifier.fillMaxSize().padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = PaneContentMaxWidth).fillMaxHeight()) { content() }
    }
}

/** [content] held to a readable width, centred, when [readable]; as it is otherwise. */
@Composable
internal fun ReadableIf(readable: Boolean, content: @Composable () -> Unit) {
    if (readable) ReadableWidth { content() } else content()
}
