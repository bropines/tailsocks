package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.MainBlock

/** What a block is called in the list, and the line under it where the name alone does not say. */
private data class BlockRow(val block: MainBlock, val title: Int, val desc: Int? = null)

/**
 * The blocks this device's main screen can have. A card a television keeps in
 * its advanced settings, or does not have at all, is not offered here either:
 * a switch for something that is never drawn would be a lie.
 */
@Composable
private fun offeredBlocks(): Pair<List<BlockRow>, List<BlockRow>> {
    val pictures = listOf(
        BlockRow(MainBlock.EXIT_NODE, R.string.main_block_exit_node),
        BlockRow(MainBlock.SPEED_GRAPH, R.string.main_block_speed_graph, R.string.main_block_speed_graph_desc),
        BlockRow(MainBlock.CONNECTION_TIMELINE, R.string.main_block_timeline, R.string.main_block_timeline_desc),
        BlockRow(MainBlock.TAILNET_MAP, R.string.main_block_tailnet_map, R.string.main_block_tailnet_map_desc),
    )
    val cards = listOfNotNull(
        BlockRow(MainBlock.CONSOLE, R.string.menu_console).takeIf { Feature.CONSOLE.offered() },
        BlockRow(MainBlock.PEERS, R.string.menu_peers),
        BlockRow(MainBlock.LOGS, R.string.menu_logs).takeIf { Feature.LOGS.offered() },
        BlockRow(MainBlock.FILES, R.string.menu_files),
        BlockRow(MainBlock.DNS, R.string.menu_dns).takeIf { Feature.DNS_SCREEN.offered() },
        BlockRow(MainBlock.NETCHECK, R.string.menu_netcheck),
        BlockRow(MainBlock.SERVE, R.string.menu_serve).takeIf { Feature.SERVE_EDIT.offered() },
    )
    return pictures to cards
}

/** Settings → Appearance: one row that opens the list of the main screen's blocks. */
@Composable
fun MainBlocksRow() {
    var open by remember { mutableStateOf(false) }
    SettingsClickableItem(
        title = stringResource(R.string.settings_main_blocks_title),
        subtitle = stringResource(R.string.settings_main_blocks_desc),
        icon = Icons.Default.Dashboard,
        onClick = { open = true },
    )
    if (open) MainBlocksDialog(onDismiss = { open = false })
}

@Composable
private fun MainBlocksDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(MainBlock.shown(context)) }
    val (pictures, cards) = offeredBlocks()
    AppAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.main_blocks_done)) } },
        title = { Text(stringResource(R.string.settings_main_blocks_title)) },
        text = {
            // The main screen reads the choice when it comes back to the front, so
            // every switch is stored as it is flipped: there is nothing to confirm.
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                BlockGroup(stringResource(R.string.main_blocks_group_pictures))
                for (row in pictures) BlockSwitch(row, row.block in shown) { on ->
                    row.block.setShown(context, on)
                    shown = MainBlock.shown(context)
                }
                BlockGroup(stringResource(R.string.main_blocks_group_cards))
                for (row in cards) BlockSwitch(row, row.block in shown) { on ->
                    row.block.setShown(context, on)
                    shown = MainBlock.shown(context)
                }
            }
        },
    )
}

@Composable
private fun BlockGroup(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun BlockSwitch(row: BlockRow, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        // The whole row is the switch: one target for a finger and one stop for a remote.
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(row.title), style = MaterialTheme.typography.bodyLarge)
            row.desc?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
