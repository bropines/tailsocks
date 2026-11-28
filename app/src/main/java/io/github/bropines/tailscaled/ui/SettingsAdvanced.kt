package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import android.content.Intent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The Advanced page's row for remote management, as the row the remote comes back to. */
internal const val REMOTE_ROW = "remote"

/** And its row for what this device exposes (ExposureScreen). */
internal const val EXPOSURE_ROW = "exposure"

// Advanced: what this device is not offered in its usual place and can still do — the
// pages that left the hub, and the screens that left the main screen. The lists come
// from the gate (`place`), so the page holds whatever the device keeps for it.
//
// advancedPages, advancedLinks: what the gate keeps for this page on this device.
// advancedRow: the row a television's remote comes back to, which [rowFocus] is put on.
// onExposure, onRemote: the two rows that open a page of their own over Settings; the
// screen records the row, steps the remote out of the page and opens it.
@Composable
internal fun SettingsAdvancedPage(
    env: SettingsPageEnv,
    advancedPages: List<SettingsCategory>,
    advancedLinks: List<AdvancedTool>,
    advancedRow: String?,
    rowFocus: FocusRequester,
    onExposure: () -> Unit,
    onRemote: () -> Unit,
) {
    val tv = isTv
    with(env) {
        // On every device, first: everything that listens or can be reached here, in one list.
        SettingsCard(title = stringResource(R.string.exposure_card)) {
            SettingsClickableItem(
                title = stringResource(R.string.exposure_title),
                subtitle = stringResource(R.string.exposure_entry_desc),
                icon = Icons.Default.GppMaybe,
                modifier = if (tv && advancedRow == EXPOSURE_ROW) Modifier.focusRequester(rowFocus) else Modifier,
                onClick = onExposure
            )
        }
        SettingsCardGap()
        if (advancedPages.isNotEmpty()) {
            SettingsCard(title = stringResource(R.string.tv_settings_more)) {
                advancedPages.forEachIndexed { index, page ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    // Keyed: a group that remembers the remote's row (tvFocusGroup) tells rows apart by their place in the composition.
                    key(page.id) {
                        SettingsClickableItem(
                            title = stringResource(page.titleRes),
                            subtitle = stringResource(page.descRes),
                            icon = page.icon,
                            // Where Back from that page puts the remote again.
                            modifier = if (tv && page.id == advancedRow) Modifier.focusRequester(rowFocus) else Modifier,
                            onClick = { open(page.id) }
                        )
                    }
                }
            }
        }
        if (advancedLinks.isNotEmpty()) {
            if (advancedPages.isNotEmpty()) SettingsCardGap()
            SettingsCard(title = stringResource(R.string.settings_sect_tools)) {
                advancedLinks.forEachIndexed { index, tool ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    key(tool.titleRes) {
                        SettingsClickableItem(
                            title = stringResource(tool.titleRes),
                            subtitle = stringResource(tool.descRes),
                            icon = tool.icon,
                            onClick = { context.startActivity(Intent(context, tool.screen)) }
                        )
                    }
                }
            }
        }
        // On every device: what is still an experiment. Off until a person turns it on.
        if (advancedPages.isNotEmpty() || advancedLinks.isNotEmpty()) SettingsCardGap()
        SettingsCard(title = stringResource(R.string.remote_beta)) {
            SettingsClickableItem(
                title = stringResource(R.string.remote_title),
                subtitle = stringResource(R.string.settings_remote_entry_desc),
                icon = Icons.Default.SettingsRemote,
                modifier = if (tv && advancedRow == REMOTE_ROW) Modifier.focusRequester(rowFocus) else Modifier,
                onClick = onRemote
            )
            // The devices this one manages: nothing until it is paired with one (ui/RemoteManageDevices.kt).
            RemoteDevices()
        }
    }
}
