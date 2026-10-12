package io.github.bropines.tailscaled.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.NetmapCache
import io.github.bropines.tailscaled.core.ProxyState
import io.github.bropines.tailscaled.core.TailscaledService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Censorship bypass: the daemon's saved network map (core/NetmapCache).
 * Beside what gets control through, what gets the tailnet up while control is
 * out of reach.
 */
@Composable
fun NetmapCacheCard() {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(NetmapCache.isEnabled(context)) }
    var report by remember { mutableStateOf<NetmapCache.Report?>(null) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!inPreview) report = withContext(Dispatchers.IO) { NetmapCache.report() }
    }
    NetmapCacheCardContent(
        enabled = enabled,
        report = report,
        clearing = clearing,
        onToggle = { on ->
            enabled = on
            NetmapCache.setEnabled(context, on)
            // The daemon reads it at start, hence RESTART rather than APPLY.
            if (ProxyState.isUserLetRunning(context)) {
                context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
            }
        },
        onClear = {
            clearing = true
            // Resolved here, in the activity's locale, not in the coroutine.
            val done = context.getString(R.string.core_netmap_cache_cleared)
            val failed = context.getString(R.string.core_netmap_cache_clear_failed)
            scope.launch {
                val error = withContext(Dispatchers.IO) { NetmapCache.clear(context) }
                clearing = false
                Toast.makeText(context, if (error == null) done else String.format(failed, error), Toast.LENGTH_LONG).show()
                report = withContext(Dispatchers.IO) { NetmapCache.report() }
            }
        },
    )
}

/** The card itself, state in and events out, so a preview can show every verdict. */
@Composable
internal fun NetmapCacheCardContent(
    enabled: Boolean,
    report: NetmapCache.Report?,
    clearing: Boolean,
    onToggle: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    SettingsCard(title = stringResource(R.string.core_netmap_cache_section)) {
        HelpText(
            stringResource(R.string.core_netmap_cache_help),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        SettingsSwitchItem(
            title = stringResource(R.string.core_netmap_cache_title),
            subtitle = stringResource(R.string.core_netmap_cache_desc),
            icon = Icons.Default.OfflineBolt,
            checked = enabled,
            onCheckedChange = onToggle
        )
        NetmapCacheVerdict(report)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        SettingsClickableItem(
            title = stringResource(R.string.core_netmap_cache_clear_title),
            subtitle = stringResource(R.string.core_netmap_cache_clear_desc),
            icon = Icons.Default.DeleteSweep,
            enabled = !clearing,
            onClick = onClear
        )
    }
}

/** What the running daemon says, in words with an icon of its own — never colour alone. */
@Composable
private fun NetmapCacheVerdict(report: NetmapCache.Report?) {
    val (icon, text) = when {
        report == null || !report.known -> Icons.Default.QuestionMark to R.string.core_netmap_cache_status_unknown
        report.withoutControl -> Icons.Default.CloudOff to R.string.core_netmap_cache_status_offline
        report.granted -> Icons.Default.CheckCircle to R.string.core_netmap_cache_status_granted
        else -> Icons.Default.Block to R.string.core_netmap_cache_status_withheld
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
