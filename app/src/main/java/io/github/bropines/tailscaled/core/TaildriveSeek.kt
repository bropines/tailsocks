package io.github.bropines.tailscaled.core

import android.content.Context
import android.os.Build

/**
 * Whether a Taildrive file this app hands to another one can be sought in: the person's switch
 * (Settings → Sharing & access → Storage, [GlobalSettings.isTaildriveSeekable]) on a platform that
 * has seekable descriptors (Android 8). Off, the Files provider gives a stream and TailSocks's own
 * browser sends a film or a song to its player by the Taildrive proxy's link, where the player
 * seeks with ranges of its own (TaildriveOpenPlan.roadOrder).
 */
object TaildriveSeek {
    fun contentSeeks(context: Context): Boolean =
        Build.VERSION.SDK_INT >= TaildriveOpenPlan.SEEKABLE_API && GlobalSettings.isTaildriveSeekable(context)
}
