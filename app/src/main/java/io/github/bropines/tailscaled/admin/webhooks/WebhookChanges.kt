package io.github.bropines.tailscaled.admin.webhooks

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiWebhook
import io.github.bropines.tailscaled.admin.api.WebhookEvents
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClassifier
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.ChangeTarget
import io.github.bropines.tailscaled.admin.safety.DiffLine
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.TargetType
import java.net.URI
import java.util.Locale

enum class WebhookUrlError { EMPTY, NOT_URL, NOT_HTTPS, BAD_PORT, CREDENTIALS }

/** The events in groups, the order the console shows them: all eighteen of the schema. */
object WebhookGroups {
    data class Group(val titleRes: Int, val events: List<String>)

    val all: List<Group> = listOf(
        Group(R.string.admin_cfg_wh_group_devices, listOf("nodeCreated", "nodeNeedsApproval", "nodeApproved", "nodeKeyExpiringInOneDay", "nodeKeyExpired", "nodeDeleted")),
        Group(R.string.admin_cfg_wh_group_lock, listOf("nodeSigned", "nodeNeedsSignature")),
        Group(R.string.admin_cfg_wh_group_users, listOf("userCreated", "userNeedsApproval", "userApproved", "userSuspended", "userRestored", "userDeleted", "userRoleUpdated")),
        Group(R.string.admin_cfg_wh_group_policy, listOf("policyUpdate")),
        Group(R.string.admin_cfg_wh_group_routing, listOf("subnetIPForwardingNotEnabled", "exitNodeIPForwardingNotEnabled")),
    )

    /** What a new webhook starts with: the events that ask someone to act. */
    val defaults = listOf("nodeNeedsApproval", "userNeedsApproval", "nodeKeyExpiringInOneDay", "nodeKeyExpired")
}

object WebhookText {
    fun event(ctx: Context, wire: String): String = when (wire) {
        "nodeCreated" -> ctx.getString(R.string.admin_cfg_wh_ev_node_created)
        "nodeNeedsApproval" -> ctx.getString(R.string.admin_cfg_wh_ev_node_needs_approval)
        "nodeApproved" -> ctx.getString(R.string.admin_cfg_wh_ev_node_approved)
        "nodeKeyExpiringInOneDay" -> ctx.getString(R.string.admin_cfg_wh_ev_node_key_expiring)
        "nodeKeyExpired" -> ctx.getString(R.string.admin_cfg_wh_ev_node_key_expired)
        "nodeDeleted" -> ctx.getString(R.string.admin_cfg_wh_ev_node_deleted)
        "nodeSigned" -> ctx.getString(R.string.admin_cfg_wh_ev_node_signed)
        "nodeNeedsSignature" -> ctx.getString(R.string.admin_cfg_wh_ev_node_needs_signature)
        "policyUpdate" -> ctx.getString(R.string.admin_cfg_wh_ev_policy_update)
        "userCreated" -> ctx.getString(R.string.admin_cfg_wh_ev_user_created)
        "userNeedsApproval" -> ctx.getString(R.string.admin_cfg_wh_ev_user_needs_approval)
        "userSuspended" -> ctx.getString(R.string.admin_cfg_wh_ev_user_suspended)
        "userRestored" -> ctx.getString(R.string.admin_cfg_wh_ev_user_restored)
        "userDeleted" -> ctx.getString(R.string.admin_cfg_wh_ev_user_deleted)
        "userApproved" -> ctx.getString(R.string.admin_cfg_wh_ev_user_approved)
        "userRoleUpdated" -> ctx.getString(R.string.admin_cfg_wh_ev_user_role_updated)
        "subnetIPForwardingNotEnabled" -> ctx.getString(R.string.admin_cfg_wh_ev_subnet_forwarding)
        "exitNodeIPForwardingNotEnabled" -> ctx.getString(R.string.admin_cfg_wh_ev_exit_forwarding)
        else -> wire
    }

    fun urlError(ctx: Context, e: WebhookUrlError): String = ctx.getString(
        when (e) {
            WebhookUrlError.EMPTY -> R.string.admin_cfg_wh_url_empty
            WebhookUrlError.NOT_URL -> R.string.admin_cfg_wh_url_bad
            WebhookUrlError.NOT_HTTPS -> R.string.admin_cfg_wh_url_https
            WebhookUrlError.BAD_PORT -> R.string.admin_cfg_wh_url_port
            WebhookUrlError.CREDENTIALS -> R.string.admin_cfg_wh_url_credentials
        }
    )
}

object WebhookInput {
    /**
     * An endpoint Tailscale will post to: https, a host, on port 443 or 80 (the only ones its
     * senders use), no credentials in the URL. Null when it is fine.
     */
    fun urlError(raw: String): WebhookUrlError? {
        val s = raw.trim()
        if (s.isEmpty()) return WebhookUrlError.EMPTY
        if (s.any { it.isWhitespace() }) return WebhookUrlError.NOT_URL
        val uri = runCatching { URI(s) }.getOrNull() ?: return WebhookUrlError.NOT_URL
        if (uri.scheme == null || uri.host.isNullOrBlank()) return WebhookUrlError.NOT_URL
        if (!uri.scheme.equals("https", ignoreCase = true)) return WebhookUrlError.NOT_HTTPS
        if (uri.userInfo != null) return WebhookUrlError.CREDENTIALS
        if (uri.port != -1 && uri.port != 443 && uri.port != 80) return WebhookUrlError.BAD_PORT
        if ('.' !in uri.host && !uri.host.lowercase(Locale.ROOT).startsWith("[")) return WebhookUrlError.NOT_URL
        return null
    }

    fun host(url: String): String = runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
}

/** The webhook changes as [PlannedChange]s; the secret a create or a rotate returns goes to [onSecret]. */
object WebhookChanges {

    private fun target(w: ApiWebhook) = ChangeTarget(TargetType.WEBHOOK, w.endpointId, WebhookInput.host(w.endpointUrl))

    private fun events(ctx: Context, list: List<String>) = ConsoleText.list(list.map { WebhookText.event(ctx, it) })

    fun create(ctx: Context, url: String, provider: String, subscriptions: List<String>, tailnetLabel: String, onCreated: (ApiWebhook) -> Unit): PlannedChange {
        val subs = WebhookEvents.all.filter { it in subscriptions }
        return PlannedChange(
            change = AdminChange(
                ChangeKind.WEBHOOK_CREATE, ChangeClassifier.classify(ChangeKind.WEBHOOK_CREATE),
                ChangeTarget(TargetType.TAILNET, "-", tailnetLabel),
                title = ctx.getString(R.string.admin2_change_webhook_create),
                effect = ctx.getString(R.string.admin_cfg_wh_create_effect, WebhookInput.host(url)),
                diff = listOf(
                    DiffLine(ctx.getString(R.string.admin2_diff_url), null, url),
                    DiffLine(ctx.getString(R.string.admin2_webhook_provider), null, ConsoleText.webhookProvider(ctx, provider)),
                    DiffLine(ctx.getString(R.string.admin_cfg_wh_events), null, events(ctx, subs)),
                ),
            ),
            apply = { onCreated(it.createWebhook(url, provider, subs)) },
            verify = { b -> b.listWebhooks().items.any { it.endpointUrl == url && it.subscriptions.toSet() == subs.toSet() } },
        )
    }

    fun updateSubscriptions(ctx: Context, w: ApiWebhook, subscriptions: List<String>, undo: Boolean = false): PlannedChange {
        val subs = WebhookEvents.all.filter { it in subscriptions } + subscriptions.filter { it !in WebhookEvents.all }
        return PlannedChange(
            change = AdminChange(
                ChangeKind.WEBHOOK_UPDATE, ChangeClassifier.classify(ChangeKind.WEBHOOK_UPDATE), target(w),
                title = ctx.getString(R.string.admin_cfg_wh_update_title, WebhookInput.host(w.endpointUrl)),
                effect = ctx.getString(R.string.admin_cfg_wh_update_effect),
                diff = listOf(DiffLine(ctx.getString(R.string.admin_cfg_wh_events), events(ctx, w.subscriptions), events(ctx, subs))),
            ),
            apply = { it.updateWebhookSubscriptions(w.endpointId, subs) },
            verify = { b -> b.listWebhooks().items.firstOrNull { it.endpointId == w.endpointId }?.subscriptions?.toSet() == subs.toSet() },
            undo = if (undo) null else updateSubscriptions(ctx, w.copy(subscriptions = subs), w.subscriptions, undo = true),
            isUndo = undo,
        )
    }

    fun test(ctx: Context, w: ApiWebhook): PlannedChange = PlannedChange(
        change = AdminChange(
            ChangeKind.WEBHOOK_TEST, ChangeClassifier.classify(ChangeKind.WEBHOOK_TEST), target(w),
            title = ctx.getString(R.string.admin2_change_webhook_test, WebhookInput.host(w.endpointUrl)),
            effect = w.endpointUrl,
        ),
        apply = { it.testWebhook(w.endpointId) },
    )

    fun rotate(ctx: Context, w: ApiWebhook, onRotated: (ApiWebhook) -> Unit): PlannedChange = PlannedChange(
        change = AdminChange(
            ChangeKind.WEBHOOK_ROTATE, ChangeClassifier.classify(ChangeKind.WEBHOOK_ROTATE), target(w),
            title = ctx.getString(R.string.admin_cfg_wh_rotate_title, WebhookInput.host(w.endpointUrl)),
            effect = ctx.getString(R.string.admin_cfg_wh_rotate_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin_cfg_wh_secret), ctx.getString(R.string.admin_cfg_wh_secret_current), ctx.getString(R.string.admin_cfg_wh_secret_new))),
            warnings = listOf(ctx.getString(R.string.admin_cfg_wh_rotate_warning)),
        ),
        apply = { onRotated(it.rotateWebhookSecret(w.endpointId)) },
    )

    fun delete(ctx: Context, w: ApiWebhook): PlannedChange = PlannedChange(
        change = AdminChange(
            ChangeKind.WEBHOOK_DELETE, ChangeClassifier.classify(ChangeKind.WEBHOOK_DELETE), target(w),
            title = ctx.getString(R.string.admin2_change_webhook_delete, WebhookInput.host(w.endpointUrl)),
            effect = ctx.getString(R.string.admin2_change_webhook_delete_effect),
            diff = listOf(
                DiffLine(ctx.getString(R.string.admin2_diff_url), w.endpointUrl, null),
                DiffLine(ctx.getString(R.string.admin_cfg_wh_events), events(ctx, w.subscriptions), null),
            ),
        ),
        apply = { it.deleteWebhook(w.endpointId) },
        verify = { b -> b.listWebhooks().items.none { it.endpointId == w.endpointId } },
    )
}
