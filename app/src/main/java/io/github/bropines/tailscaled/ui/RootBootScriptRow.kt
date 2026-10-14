package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.RootBootScript

/**
 * The row under "Autostart Magisk/KernelSU Service" while a boot script is
 * installed: the reinstall button, what the last reinstall said, and — when the
 * installed copy is not the one this build writes ([RootBootScript]) — a folded
 * note that it is outdated, which the button next to it fixes. The result of a
 * reinstall takes the note's place: it is the newer news.
 */
@Composable
fun BootScriptReinstallRow(
    script: RootBootScript.State,
    reinstallStatus: String?,
    reinstallOk: Boolean,
    onReinstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The status is a whole sentence and the button never
        // shrinks: without the weight they were drawn over each other.
        when {
            reinstallStatus != null -> Text(
                text = reinstallStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (reinstallOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            script.status == RootBootScript.Status.OUTDATED -> HelpText(
                text = script.writtenBy
                    ?.let { stringResource(R.string.settings_root_script_outdated, it) }
                    ?: stringResource(R.string.settings_root_script_outdated_unknown),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            else -> Spacer(Modifier.width(1.dp))
        }

        Spacer(Modifier.width(12.dp))

        OutlinedButton(
            onClick = onReinstall,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.settings_root_script_reinstall),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
