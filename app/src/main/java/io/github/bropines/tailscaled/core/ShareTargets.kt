package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.models.StatusResponse
import io.github.bropines.tailscaled.models.TaildropDirection
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import io.github.bropines.tailscaled.ui.ShareActivity
import io.github.bropines.tailscaled.ui.TaildropReasonStrings
import io.github.bropines.tailscaled.ui.TaildropStatus
import io.github.bropines.tailscaled.ui.taildropPickerPeers
import io.github.bropines.tailscaled.ui.taildropStatusOf
import kotlinx.serialization.decodeFromString

/** A device the system share sheet can offer: one sharing shortcut. */
internal data class ShareCandidate(
    val peerId: String,
    val label: String,
    val phone: Boolean,
    /** The daemon will take a file for it now; otherwise it is offline. */
    val available: Boolean,
)

/**
 * The share sheet's row: the devices a file can go to now first — those sent to most
 * recently ahead, the rest in the picker's own order — then the offline ones only when
 * they were sent to before, at most [limit]. [recentPeerIds] is newest first.
 */
internal fun pickShareTargets(candidates: List<ShareCandidate>, recentPeerIds: List<String>, limit: Int): List<ShareCandidate> {
    val recency = recentPeerIds.distinct().withIndex().associate { it.value to it.index }
    val byRecency = compareBy<ShareCandidate> { recency[it.peerId] ?: Int.MAX_VALUE }
    val online = candidates.filter { it.available }.sortedWith(byRecency)
    val offline = candidates.filter { !it.available && it.peerId in recency }.sortedWith(byRecency)
    return (online + offline).take(limit.coerceAtLeast(0))
}

/** The devices sent to successfully, newest first, by StableNodeID. */
internal fun recentRecipients(history: List<TaildropHistoryEntry>): List<String> =
    history.filter { it.direction == TaildropDirection.SENT && it.ok }.mapNotNull { it.peerId }.distinct()

/**
 * Settings → Sharing & access → Devices in the share menu: the tailnet's devices as Direct
 * Share targets of the system share sheet, so one tap sends the file (Android 10 and up —
 * the `<share-target>` of res/xml/shortcuts.xml; older versions asked a ChooserTargetService,
 * which this app does not ship).
 *
 * Dynamic sharing shortcuts, one per device, published from the service's refresh tick while
 * the daemon is Running and taken down whenever it is not, the switch is off, or the service
 * stops. An id names the account as well as the device ([shortcutId]), and [ShareActivity]
 * checks both against the current netmap before sending: a shortcut the system still shows
 * after an account switch never sends to the other account's device.
 */
object ShareTargets {
    private const val TAG = "ShareTargets"
    const val CATEGORY = "io.github.bropines.tailscaled.category.TAILDROP_TARGET"
    private const val ID_PREFIX = "taildrop/"

    /** The full status is no per-tick read; the device list changes rarely. */
    private const val SYNC_INTERVAL_MS = 60_000L

    /** The share sheet shows a handful per app; more only crowds the launcher's own limit. */
    private const val MAX_TARGETS = 8

    /** What was published last; null while unknown (a fresh process may find old ones). */
    @Volatile private var published: List<ShareCandidate>? = null
    @Volatile private var publishedProfile: String? = null
    @Volatile private var lastSyncAt = 0L

    private val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun shortcutId(profileId: String, peerId: String) = "$ID_PREFIX$profileId/$peerId"

    /** The device a shortcut names, when it belongs to [profileId]; null otherwise. */
    fun peerIdFor(shortcutId: String?, profileId: String): String? {
        val rest = shortcutId?.removePrefix(ID_PREFIX)?.takeIf { it != shortcutId } ?: return null
        val prefix = "$profileId/"
        return rest.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotEmpty() }
    }

    /** From the refresh tick, off the main thread. [running]: the daemon is Running. */
    fun sync(context: Context, running: Boolean) {
        if (!supported) return
        if (!running || !GlobalSettings.isShareTargetsEnabled(context)) {
            if (published?.isEmpty() != true) clear(context)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < SYNC_INTERVAL_MS) return
        lastSyncAt = now
        try {
            val status = AppJson.decodeFromString<StatusResponse>(Appctr.getStatusJSON(true))
            val profile = AccountManager.getActiveAccount(context).id
            val strings = TaildropReasonStrings.from(context)
            val candidates = taildropPickerPeers(status, strings).mapNotNull { p ->
                val id = p.id?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val st = taildropStatusOf(p, strings)
                if (st is TaildropStatus.Blocked) return@mapNotNull null
                val os = p.iconOs()?.lowercase().orEmpty()
                ShareCandidate(id, p.getDisplayName(), os.contains("android") || os.contains("ios"), st is TaildropStatus.Available)
            }
            val targets = pickShareTargets(candidates, recentRecipients(TaildropHistory.read(context)), limit(context))
            if (targets == published && profile == publishedProfile) return
            publish(context, profile, targets)
        } catch (e: Exception) {
            Log.w(TAG, "Share targets not refreshed: ${e.message}")
        }
    }

    /** The next tick publishes again, without waiting out the interval (the switch turned on). */
    fun invalidate() {
        lastSyncAt = 0L
    }

    /** Takes every device shortcut down. Safe from any thread. */
    fun clear(context: Context) {
        if (!supported) return
        runCatching {
            val ours = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith(ID_PREFIX) }
            if (ours.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ours)
            published = emptyList()
            publishedProfile = null
            lastSyncAt = 0L
        }.onFailure { Log.w(TAG, "Share targets not cleared: ${it.message}") }
    }

    /** The launcher's per-activity limit counts the static shortcuts as well. */
    private fun limit(context: Context): Int {
        val static = runCatching { ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_MANIFEST).size }.getOrDefault(4)
        return minOf(MAX_TARGETS, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - static)
    }

    private fun publish(context: Context, profile: String, targets: List<ShareCandidate>) {
        val ids = targets.map { shortcutId(profile, it.peerId) }
        val stale = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith(ID_PREFIX) && it !in ids }
        if (stale.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, stale)
        // pushDynamicShortcut is the call the system does not rate-limit for a background app.
        targets.forEachIndexed { rank, t ->
            ShortcutManagerCompat.pushDynamicShortcut(context, build(context, ids[rank], t, rank))
        }
        published = targets
        publishedProfile = profile
        Log.i(TAG, "Published ${targets.size} share targets")
    }

    private fun build(context: Context, id: String, t: ShareCandidate, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(t.label)
            .setLongLabel(t.label)
            .setIcon(IconCompat.createWithResource(context, if (t.phone) R.drawable.ic_share_target_phone else R.drawable.ic_share_target_computer))
            // The share sheet sends its own SEND intent with this id; a launcher that shows
            // the shortcut (see below) opens the file picker for that device.
            .setIntent(
                Intent(context, ShareActivity::class.java)
                    .setAction(ShareActivity.ACTION_PICK_AND_SEND)
                    .putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, id)
            )
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .setRank(rank)
            // Not excluded from the launcher: the platform keeps such a shortcut only when
            // its AppSearch backend is on (ShortcutPackage.pushDynamicShortcut), which it is
            // not by default, so the share sheet never saw one (measured on Android 16).
            // androidx drops it before the system on Android 12 and lower. So the devices
            // also show in the launcher's long-press menu, where a tap picks a file for them.
            .build()
}
