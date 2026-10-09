package io.github.bropines.tailscaled.admin.attention

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.console.ConsoleText

/**
 * The attention list's words, shared by the home screen and the notifications. Every function
 * takes the Context to resolve in: the activity's, or a locale-wrapped one in the background.
 */
object AttentionText {
    private const val HOUR_MS = 3600L * 1000
    private const val DAY_MS = 24 * HOUR_MS

    /** "in 3 days", "in 5 hours", "2 days ago" — rounded the way a person would count. */
    fun relative(ctx: Context, at: Long, now: Long): String {
        val left = at - now
        val r = ctx.resources
        return if (left >= 0) when {
            left >= DAY_MS -> (left / DAY_MS).toInt().let { r.getQuantityString(R.plurals.admin_attention_in_days, it, it) }
            left >= HOUR_MS -> (left / HOUR_MS).toInt().let { r.getQuantityString(R.plurals.admin_attention_in_hours, it, it) }
            else -> ctx.getString(R.string.admin_attention_within_hour)
        } else {
            val ago = -left
            when {
                ago >= DAY_MS -> (ago / DAY_MS).toInt().let { r.getQuantityString(R.plurals.admin_attention_days_ago, it, it) }
                ago >= HOUR_MS -> (ago / HOUR_MS).toInt().let { r.getQuantityString(R.plurals.admin_attention_hours_ago, it, it) }
                else -> ctx.getString(R.string.admin_attention_just_now)
            }
        }
    }

    fun section(ctx: Context, s: AttentionSection): String = ctx.getString(
        when (s) {
            AttentionSection.APPROVALS -> R.string.admin_attention_section_approvals
            AttentionSection.CREDENTIAL -> R.string.admin_attention_section_credential
            AttentionSection.EXPIRY -> R.string.admin_attention_section_expiry
            AttentionSection.PROBLEMS -> R.string.admin_attention_section_problems
            AttentionSection.ROUTES -> R.string.admin_attention_section_routes
            AttentionSection.UPDATES -> R.string.admin_attention_section_updates
        }
    )

    /** What is the matter with the item, in one line. */
    fun what(ctx: Context, item: AttentionItem, now: Long): String {
        val rel = item.expiresAt?.let { relative(ctx, it, now) }.orEmpty()
        return when (item.kind) {
            AttentionKind.DEVICE_APPROVAL -> ctx.getString(R.string.admin_attention_device_approval)
            AttentionKind.USER_APPROVAL -> ctx.getString(R.string.admin_attention_user_approval)
            AttentionKind.CREDENTIAL_REFUSED -> ctx.getString(R.string.admin_attention_credential_refused_short)
            AttentionKind.CREDENTIAL_EXPIRING -> ctx.getString(R.string.admin_attention_credential_expiring, rel)
            AttentionKind.DEVICE_KEY_EXPIRING -> ctx.getString(
                if ((item.expiresAt ?: Long.MAX_VALUE) <= now) R.string.admin_attention_device_key_expired else R.string.admin_attention_device_key_expiring,
                rel,
            )
            AttentionKind.AUTH_KEY_EXPIRING -> ctx.getString(R.string.admin_attention_auth_key_expiring, rel)
            AttentionKind.TAILNET_LOCK_ERROR -> ctx.getString(R.string.admin_attention_lock_error)
            AttentionKind.MULTIPLE_CONNECTIONS -> ctx.getString(R.string.admin_attention_multiple)
            AttentionKind.ROUTES_PENDING -> ctx.getString(
                if (item.pendingRoutes.all { it == "0.0.0.0/0" || it == "::/0" }) R.string.admin_attention_exit_node else R.string.admin_attention_routes
            )
            AttentionKind.UPDATE_AVAILABLE -> item.device?.clientVersion?.substringBefore('-')?.takeIf { it.isNotBlank() }
                ?.let { ctx.getString(R.string.admin_attention_update, it) } ?: ctx.getString(R.string.admin_attention_update_plain)
        }
    }

    /** Who or what it is about beyond the name: owner and system for a device, type for a key. */
    fun context(ctx: Context, item: AttentionItem): String? = when (item.kind) {
        AttentionKind.TAILNET_LOCK_ERROR -> item.device?.tailnetLockError?.let { ctx.getString(R.string.admin_attention_lock_error_desc, it) }
        AttentionKind.MULTIPLE_CONNECTIONS -> ctx.getString(R.string.admin_attention_multiple_desc)
        AttentionKind.ROUTES_PENDING -> item.pendingRoutes.takeUnless { r -> r.all { it == "0.0.0.0/0" || it == "::/0" } }
            ?.map { if (it == "0.0.0.0/0" || it == "::/0") ctx.getString(R.string.admin_attention_exit_node_route) else it }
            ?.distinct()?.joinToString(", ")
        AttentionKind.AUTH_KEY_EXPIRING -> item.key?.let { k ->
            listOfNotNull(ConsoleText.keyType(ctx, k.type), k.id.takeIf { it != item.targetName }).joinToString(" · ")
        }
        AttentionKind.USER_APPROVAL -> item.user?.displayName?.takeIf { it.isNotBlank() && it != item.targetName }
        AttentionKind.CREDENTIAL_REFUSED, AttentionKind.CREDENTIAL_EXPIRING -> null
        else -> item.device?.let { d ->
            listOfNotNull(d.os?.takeIf { it.isNotBlank() }, d.user?.takeIf { it.isNotBlank() && !d.isTagged }, d.tags.takeIf { it.isNotEmpty() }?.joinToString(", "))
                .joinToString(" · ").ifBlank { null }
        }
    }

    /** The name a row leads with: the console's credential has none of its own. */
    fun name(ctx: Context, item: AttentionItem): String = when (item.kind) {
        AttentionKind.CREDENTIAL_EXPIRING, AttentionKind.CREDENTIAL_REFUSED -> ctx.getString(R.string.admin_attention_credential_name)
        else -> item.targetName
    }
}
