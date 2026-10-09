package io.github.bropines.tailscaled.admin

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.devices.DeviceQueries
import io.github.bropines.tailscaled.admin.devices.DeviceSort

/*
 * The Devices tab lives in admin/devices/ (DevicesTab, DeviceDetailSheet). What stays here is
 * what the other tabs share.
 */

/** The list's order by its old names: "name", "name_desc", "last_seen" (online first), "created". */
internal fun sortDevices(devices: List<ApiDevice>, sortBy: String): List<ApiDevice> = when (sortBy) {
    "name_desc" -> DeviceQueries.sorted(devices, DeviceSort.NAME).reversed()
    "last_seen" -> DeviceQueries.sorted(devices, DeviceSort.LAST_SEEN)
    "created" -> DeviceQueries.sorted(devices, DeviceSort.CREATED)
    else -> DeviceQueries.sorted(devices, DeviceSort.NAME)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LoadingIndicatorCompat(modifier: Modifier = Modifier) = LoadingIndicator(modifier)

/** A small label with an icon: a status that does not depend on its colour to be read. */
@Composable
internal fun StatusTag(text: String, container: Color, content: Color, icon: ImageVector? = null) {
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
