package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.SharedPreferences

/**
 * The Sharing page's stored values, read once when Settings opens. The folder chooser that
 * sets [taildropFolder] is registered by the screen itself, not by the page: the system's
 * picker answers a launcher that is composed whichever page is open.
 */
@Stable
internal class SettingsSharingState(context: Context, profilePrefs: SharedPreferences) {
    // Where received Taildrop files go (core/TaildropFolder.kt): a folder of the system's
    // picker, as on every phone, or a directory chosen in the app's own browser where the
    // system has no picker — a television, which starts with one of its own in Downloads.
    var taildropFolder by mutableStateOf(TaildropFolder.get(context))
    var shareTargets by mutableStateOf(GlobalSettings.isShareTargetsEnabled(context))
    var enableWebUI by mutableStateOf(profilePrefs.getBoolean("enable_webui", false))
    var webUIAddr by mutableStateOf(profilePrefs.getString("webui_addr", "127.0.0.1:8080") ?: "127.0.0.1:8080")
}

// F. Sharing & access
@Composable
internal fun SettingsSharingPage(env: SettingsPageEnv, state: SettingsSharingState, taildropFolderChooser: ReceiveFolderChooser) {
    with(env) {
    with(state) {
        SettingsCard(title = stringResource(R.string.settings_sect_storage)) {
            // The folder by the name the user knows it: a path on the device's storage
            // straight from the URI or the directory, any other provider's folder name
            // asked of it.
            val folder = taildropFolder
            val folderLabel by produceState(folder?.let(TaildropFolder::quickLabel), folder) {
                value = folder?.let { TaildropFolder.quickLabel(it) ?: withContext(Dispatchers.IO) { TaildropFolder.label(context, it) } }
            }
            SettingsClickableItem(
                stringResource(R.string.settings_taildrop_folder_title),
                if (folder == null) stringResource(R.string.settings_taildrop_folder_none)
                else stringResource(R.string.settings_taildrop_folder_set_format, folderLabel ?: "…"),
                Icons.Default.Folder
            ) { taildropFolderChooser.choose() }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            TaildriveDownloadFolderRow(taildropFolder)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            TaildriveParallelRow()
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            TaildriveSeekRow()
        }

        SettingsCardGap()

        SettingsCard(title = stringResource(R.string.settings_sect_share_links)) {
            SettingsSwitchItem(stringResource(R.string.settings_share_targets_title), stringResource(R.string.settings_share_targets_desc), Icons.Default.Devices, shareTargets) {
                GlobalSettings.setShareTargetsEnabled(context, it)
                shareTargets = it
            }
            if (shareTargets) {
                // Which devices the menu offers: per account, since the IDs are one tailnet's.
                val profileId = activeAccount.id
                var picks by remember(profileId) { mutableStateOf(GlobalSettings.getShareTargetPicks(context, profileId)) }
                var picking by remember { mutableStateOf(false) }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                SettingsClickableItem(
                    stringResource(R.string.share_targets_pick_row),
                    if (picks.isEmpty) stringResource(R.string.share_targets_pick_summary_auto)
                    else stringResource(R.string.share_targets_pick_summary, picks.pinned.size, picks.hidden.size),
                    Icons.Default.Checklist
                ) { picking = true }
                if (picking) ShareTargetsSheet(profileId, onChanged = { picks = it }, onDismiss = { picking = false })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SettingsClickableItem(
                stringResource(R.string.taildrive_title),
                stringResource(R.string.settings_link_taildrive_desc),
                Icons.Default.FolderShared
            ) { context.startActivity(Intent(context, TaildriveActivity::class.java)) }
            if (place(Feature.SERVE_EDIT) == Availability.OFFERED) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                SettingsClickableItem(
                    stringResource(R.string.serve_title),
                    stringResource(R.string.settings_link_serve_desc),
                    Icons.Default.Public
                ) { context.startActivity(Intent(context, ServeActivity::class.java)) }
            }
        }

        SettingsCardGap()

        SettingsCard(title = stringResource(R.string.settings_sect_web)) {
            SettingsSwitchItem(stringResource(R.string.settings_web_enable_title), stringResource(R.string.settings_web_enable_desc), Icons.Default.Web, enableWebUI) { enableWebUI = it; saveProfilePref("enable_webui", it) }
            if (enableWebUI) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                SettingsEditItem(stringResource(R.string.settings_web_address_title), webUIAddr, Icons.Default.Link) { webUIAddr = it; saveProfilePref("webui_addr", it) }
            }
        }
    }
    }
}
