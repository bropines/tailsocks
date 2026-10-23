package io.github.bropines.tailscaled.ui

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.TaildriveOpenPlan

/**
 * Settings → Sharing & access → Storage: whether another app gets a Taildrive file it can seek in
 * or a stream (core/TaildriveSeek.kt). Before Android 8 there is no seeking to switch on.
 */
@Composable
internal fun TaildriveSeekRow() {
    val context = LocalContext.current
    val possible = Build.VERSION.SDK_INT >= TaildriveOpenPlan.SEEKABLE_API
    var on by remember { mutableStateOf(possible && GlobalSettings.isTaildriveSeekable(context)) }
    SettingsSwitchItem(
        stringResource(R.string.taildrive_seek_title),
        stringResource(if (on) R.string.taildrive_seek_desc_on else R.string.taildrive_seek_desc_off),
        Icons.Default.FastForward,
        checked = on,
        enabled = possible,
    ) {
        on = it
        GlobalSettings.setTaildriveSeekable(context, it)
    }
}
