package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.models.TaildropDirection
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import io.github.bropines.tailscaled.ui.ShareActivity
import io.github.bropines.tailscaled.ui.TaildropReasonStrings
import io.github.bropines.tailscaled.ui.TaildropStatus
import io.github.bropines.tailscaled.ui.taildropPickerPeers
import io.github.bropines.tailscaled.ui.taildropStatusOf

/** A device the system share sheet can offer: one sharing shortcut. */
internal data class ShareCandidate(
    val peerId: String,
    val label: String,
    val os: ShareTargetOs,
    /** The daemon will take a file for it now; otherwise it is offline. */
    val available: Boolean,
)

/** The OS a share target's icon shows: the glyph and colour of the app's own device list. */
internal enum class ShareTargetOs(@DrawableRes val icon: Int) {
    ANDROID(R.drawable.ic_share_target_android),
    WINDOWS(R.drawable.ic_share_target_windows),
    LINUX(R.drawable.ic_share_target_linux),
    MACOS(R.drawable.ic_share_target_macos),
    IOS(R.drawable.ic_share_target_ios),
    OTHER(R.drawable.ic_share_target_other),
}

/** The order of ui/PeerRow.kt getOsVisuals, so a device looks the same in the sheet and in the app. */
internal fun shareTargetOsOf(os: String?): ShareTargetOs {
    val o = os?.lowercase().orEmpty()
    return when {
        o.contains("android") -> ShareTargetOs.ANDROID
        o.contains("windows") -> ShareTargetOs.WINDOWS
        o.contains("linux") -> ShareTargetOs.LINUX
        o.contains("macos") || o.contains("darwin") -> ShareTargetOs.MACOS
        o.contains("ios") -> ShareTargetOs.IOS
        else -> ShareTargetOs.OTHER
    }
}

/** What the user chose for one device in Settings → Devices in the share menu. */
enum class ShareTargetMode { AUTO, PINNED, HIDDEN }

/** The user's choices for one account's devices, by StableNodeID; a device in neither set is [ShareTargetMode.AUTO]. */
data class ShareTargetPicks(val pinned: Set<String> = emptySet(), val hidden: Set<String> = emptySet()) {
    fun modeOf(peerId: String) = when (peerId) {
        in hidden -> ShareTargetMode.HIDDEN
        in pinned -> ShareTargetMode.PINNED
        else -> ShareTargetMode.AUTO
    }

    fun with(peerId: String, mode: ShareTargetMode) = ShareTargetPicks(
        pinned = if (mode == ShareTargetMode.PINNED) pinned + peerId else pinned - peerId,
        hidden = if (mode == ShareTargetMode.HIDDEN) hidden + peerId else hidden - peerId,
    )

    val isEmpty get() = pinned.isEmpty() && hidden.isEmpty()
}

/**
 * The share sheet's row: the pinned devices first, whether or not they are online; then the
 * devices a file can go to now — those sent to most recently ahead, the rest in the picker's
 * own order — then the offline ones only when they were sent to before, at most [limit].
 * Hidden devices never show. [recentPeerIds] is newest first.
 */
internal fun pickShareTargets(
    candidates: List<ShareCandidate>,
    recentPeerIds: List<String>,
    limit: Int,
    picks: ShareTargetPicks = ShareTargetPicks(),
): List<ShareCandidate> {
    val recency = recentPeerIds.distinct().withIndex().associate { it.value to it.index }
    val byRecency = compareBy<ShareCandidate> { recency[it.peerId] ?: Int.MAX_VALUE }
    val shown = candidates.filter { picks.modeOf(it.peerId) != ShareTargetMode.HIDDEN }
    val (pinned, auto) = shown.partition { picks.modeOf(it.peerId) == ShareTargetMode.PINNED }
    val pinnedRow = pinned.sortedWith(compareBy<ShareCandidate> { !it.available }.then(byRecency))
    val online = auto.filter { it.available }.sortedWith(byRecency)
    val offline = auto.filter { !it.available && it.peerId in recency }.sortedWith(byRecency)
    return (pinnedRow + online + offline).take(limit.coerceAtLeast(0))
}

/**
 * The share sheet's short labels for [names], in order. The sheet cuts a label after about ten
 * characters, so a tailnet of `personal-pinus-pc`, `personal-pinus-macbook-air` showed one
 * `personal-pi…` after another. A name loses the longest prefix, ending at a `-`, that it
 * shares with another name of the row — `pc`, `macbook-air` — and keeps the rest; a name that
 * shares none, or would be left empty or the same as another, stays whole. The long label
 * (shown where there is room) is always the full name.
 */
internal fun shareTargetLabels(names: List<String>): List<String> {
    val short = names.mapIndexed { i, name ->
        var cut = 0
        var at = name.indexOf('-')
        while (at in 0 until name.length - 1) {
            val prefix = name.substring(0, at + 1)
            if (names.withIndex().any { (j, other) -> j != i && other.startsWith(prefix) }) cut = at + 1
            at = name.indexOf('-', at + 1)
        }
        name.substring(cut)
    }
    val counts = short.groupingBy { it }.eachCount()
    return short.mapIndexed { i, label -> if (label.isEmpty() || counts.getValue(label) > 1) names[i] else label }
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
 * Dynamic sharing shortcuts, one per device, published by the service's event refresher (ServiceEventRefresher) while
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

    /** From the event refresher, off the main thread. [running]: the daemon is Running. */
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
            val status = DaemonStatus.readOrThrow()
            val profile = AccountManager.getActiveAccount(context).id
            val strings = TaildropReasonStrings.from(context)
            val candidates = taildropPickerPeers(status, strings).mapNotNull { p ->
                val id = p.id?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val st = taildropStatusOf(p, strings)
                if (st is TaildropStatus.Blocked) return@mapNotNull null
                ShareCandidate(id, p.getDisplayName(), shareTargetOsOf(p.iconOs()), st is TaildropStatus.Available)
            }
            val picks = GlobalSettings.getShareTargetPicks(context, profile)
            val targets = pickShareTargets(candidates, recentRecipients(TaildropHistory.read(context)), capacity(context), picks)
            if (targets == published && profile == publishedProfile) return
            publish(context, profile, targets)
        } catch (e: Exception) {
            Log.w(TAG, "Share targets not refreshed: ${e.message}")
        }
    }

    /** The next refresh publishes again, without waiting out the interval (the switch turned on). */
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

    /**
     * How many devices are published at most. The launcher's per-activity limit counts the
     * static shortcuts as well; the share sheet itself may show fewer of them.
     */
    fun capacity(context: Context): Int {
        val static = runCatching { ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_MANIFEST).size }.getOrDefault(4)
        return minOf(MAX_TARGETS, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - static).coerceAtLeast(0)
    }

    private fun publish(context: Context, profile: String, targets: List<ShareCandidate>) {
        val ids = targets.map { shortcutId(profile, it.peerId) }
        val stale = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith(ID_PREFIX) && it !in ids }
        if (stale.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, stale)
        // pushDynamicShortcut is the call the system does not rate-limit for a background app.
        val labels = shareTargetLabels(targets.map { it.label })
        targets.forEachIndexed { rank, t ->
            ShortcutManagerCompat.pushDynamicShortcut(context, build(context, ids[rank], t, labels[rank], rank))
        }
        published = targets
        publishedProfile = profile
        Log.i(TAG, "Published ${targets.size} share targets")
    }

    private fun build(context: Context, id: String, t: ShareCandidate, label: String, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            // Both labels get the trimmed name: the share sheet shows the long label when one
            // is set (ShortcutInfo.getLabel), so a full long label put `personal-pi…` back.
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, t.os.icon))
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
