package io.github.bropines.tailscaled.admin.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.AdminApiActivity
import io.github.bropines.tailscaled.admin.attention.AttentionItem
import io.github.bropines.tailscaled.admin.attention.AttentionKind
import io.github.bropines.tailscaled.admin.attention.AttentionText
import io.github.bropines.tailscaled.admin.profile.AdminProfile

/**
 * The "Admin alerts" channel and what goes on it: one notification per device or user waiting
 * for approval (with Approve and Reject when the profile may write), one for keys about to
 * expire, one when the console's credential is refused, and the result of an action taken
 * from a notification. Tagged by profile and item, so each can be replaced or withdrawn.
 *
 * Every function takes the Context to resolve words in: a locale-wrapped one in the background.
 */
object AttentionNotifier {
    private const val TAG = "AdminAttention"
    const val CHANNEL_ID = "admin_alerts"
    private const val ID = 1
    /** A result stays in the shade for a day at most: it is a receipt, not a task. */
    private const val RESULT_TIMEOUT_MS = 24L * 3600 * 1000
    private const val MAX_LINES = 5

    fun itemTag(profileId: String, notifyKey: String) = "admin-attention/$profileId/$notifyKey"
    private fun resultTag(profileId: String, notifyKey: String) = "admin-attention/$profileId/$notifyKey/result"
    private fun expiryTag(profileId: String) = "admin-attention/$profileId/expiry"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.admin_attention_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.admin_attention_channel_desc)
            }
        )
    }

    /** Whether a notification on this channel would be shown: the permission, the app switch, the channel. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** The system's notification settings for this app, where a refused permission or a muted channel is turned back on. */
    fun settingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }

    /** The console on [profileId]'s "Needs attention". */
    fun consoleIntent(context: Context, profileId: String): PendingIntent {
        val intent = Intent(context, AdminApiActivity::class.java)
            .putExtra(AttentionLaunch.EXTRA_PROFILE_ID, profileId)
            .setData(Uri.parse("tailsocks-admin://attention/${Uri.encode(profileId)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, ("console/$profileId").hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(context: Context, profile: AdminProfile, item: AttentionItem, action: String): PendingIntent {
        val intent = AttentionActionActivity.intent(context, profile.id, item.kind, item.targetId, item.targetName, item.notifyKey, action)
        return PendingIntent.getActivity(
            context, "${profile.id}/${item.notifyKey}/$action".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun builder(context: Context, profile: AdminProfile) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_qs_tile)
        .setSubText(profile.displayName)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(consoleIntent(context, profile.id))
        .setAutoCancel(true)

    /**
     * A device or user waiting for approval. [actions] only when the profile may write it: a
     * read-only profile, a phone without a screen lock or a read-only credential get "Open
     * console" alone. The actions open a confirmation, never act from here.
     */
    fun postApproval(context: Context, profile: AdminProfile, item: AttentionItem, actions: Boolean) {
        val b = builder(context, profile)
            .setContentTitle(AttentionText.what(context, item, System.currentTimeMillis()))
            .setContentText(listOfNotNull(item.targetName, AttentionText.context(context, item)).joinToString(" · "))
        if (actions) {
            b.addAction(0, context.getString(R.string.admin_attention_approve), actionIntent(context, profile, item, AttentionActionActivity.ACTION_APPROVE))
            b.addAction(0, context.getString(R.string.admin_attention_reject), actionIntent(context, profile, item, AttentionActionActivity.ACTION_REJECT))
        } else {
            b.addAction(0, context.getString(R.string.admin_attention_open_console), consoleIntent(context, profile.id))
        }
        notify(context, itemTag(profile.id, item.notifyKey), b)
    }

    /** The console's credential no longer works: nothing else can be checked until it is replaced. */
    fun postCredentialRefused(context: Context, profile: AdminProfile, item: AttentionItem) {
        val b = builder(context, profile)
            .setContentTitle(context.getString(R.string.admin_attention_credential_refused))
            .setContentText(context.getString(R.string.admin_attention_credential_refused_desc))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.admin_attention_credential_refused_desc)))
        notify(context, itemTag(profile.id, item.notifyKey), b)
    }

    /** Every key about to expire, in one notification: posted again only when one of them is new. */
    fun postExpiry(context: Context, profile: AdminProfile, items: List<AttentionItem>, now: Long) {
        if (items.isEmpty()) return cancelExpiry(context, profile.id)
        val lines = items.map { "${AttentionText.name(context, it)} · ${AttentionText.what(context, it, now)}" }
        val style = NotificationCompat.InboxStyle()
        lines.take(MAX_LINES).forEach { style.addLine(it) }
        if (lines.size > MAX_LINES) {
            style.setSummaryText(context.resources.getQuantityString(R.plurals.admin_attention_notif_more, lines.size - MAX_LINES, lines.size - MAX_LINES))
        }
        val b = builder(context, profile)
            .setContentTitle(context.resources.getQuantityString(R.plurals.admin_attention_notif_expiry_title, items.size, items.size))
            .setContentText(lines.first())
            .setNumber(items.size)
            .setStyle(style)
        notify(context, expiryTag(profile.id), b)
    }

    fun cancelExpiry(context: Context, profileId: String) = cancel(context, expiryTag(profileId))

    /**
     * After keys were renewed or removed: the expiry notification, if it is still in the
     * shade, lists what is left — or goes. One the person already swiped away stays away.
     */
    fun refreshExpiryIfShown(context: Context, profile: AdminProfile, items: List<AttentionItem>, now: Long) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val shown = runCatching { nm.activeNotifications.any { it.tag == expiryTag(profile.id) && it.id == ID } }.getOrDefault(false)
        if (shown) postExpiry(context, profile, items, now)
    }

    fun cancelItem(context: Context, profileId: String, notifyKey: String) = cancel(context, itemTag(profileId, notifyKey))

    /**
     * What an action from a notification ended in. It takes the notification's place once the
     * change applied; [keepOriginal] leaves the one with the actions for another try.
     */
    fun postResult(context: Context, profile: AdminProfile, notifyKey: String, title: String, text: String, keepOriginal: Boolean = false) {
        if (!keepOriginal) cancelItem(context, profile.id, notifyKey)
        val b = builder(context, profile)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setTimeoutAfter(RESULT_TIMEOUT_MS)
        notify(context, resultTag(profile.id, notifyKey), b)
    }

    /** Whether a remembered key is withdrawn with its notification once it is resolved. */
    fun ownsNotification(kind: AttentionKind): Boolean =
        kind == AttentionKind.DEVICE_APPROVAL || kind == AttentionKind.USER_APPROVAL || kind == AttentionKind.CREDENTIAL_REFUSED

    private fun notify(context: Context, tag: String, b: NotificationCompat.Builder) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Without POST_NOTIFICATIONS on 13+ the system drops it; the caller checked canPost first.
        try {
            nm.notify(tag, ID, b.build())
        } catch (e: Exception) {
            Log.w(TAG, "notify: ${e.javaClass.simpleName}")
        }
    }

    private fun cancel(context: Context, tag: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(tag, ID)
    }
}
