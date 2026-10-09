package io.github.bropines.tailscaled.admin.logs

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiAuditLogEntry

/**
 * The audit log's API values in the reader's language: actions, what they acted on, where
 * they came from, who did them, which property changed. A value this app does not know yet
 * reads as the API's own word made legible ("SUPPORT_EMAIL" → "Support email") rather than
 * as nothing.
 */
object AuditText {

    fun action(ctx: Context, wire: String?): String = when (wire?.uppercase()) {
        "LOGIN" -> ctx.getString(R.string.admin_log_action_login)
        "LOGOUT" -> ctx.getString(R.string.admin_log_action_logout)
        "CREATE" -> ctx.getString(R.string.admin_log_action_create)
        "UPDATE" -> ctx.getString(R.string.admin_log_action_update)
        "DELETE" -> ctx.getString(R.string.admin_log_action_delete)
        "CANCEL" -> ctx.getString(R.string.admin_log_action_cancel)
        "REVOKE" -> ctx.getString(R.string.admin_log_action_revoke)
        "APPROVE" -> ctx.getString(R.string.admin_log_action_approve)
        "SUSPEND" -> ctx.getString(R.string.admin_log_action_suspend)
        "RESTORE" -> ctx.getString(R.string.admin_log_action_restore)
        "ENABLE" -> ctx.getString(R.string.admin_log_action_enable)
        "DISABLE" -> ctx.getString(R.string.admin_log_action_disable)
        "ACCEPT" -> ctx.getString(R.string.admin_log_action_accept)
        "EXPIRED" -> ctx.getString(R.string.admin_log_action_expired)
        "PUSH_USER", "PUSH_GROUP" -> ctx.getString(R.string.admin_log_action_push)
        "VERIFY" -> ctx.getString(R.string.admin_log_action_verify)
        "JOIN_WAITLIST" -> ctx.getString(R.string.admin_log_action_join_waitlist)
        "INVITE" -> ctx.getString(R.string.admin_log_action_invite)
        "JOIN" -> ctx.getString(R.string.admin_log_action_join)
        "LEAVE" -> ctx.getString(R.string.admin_log_action_leave)
        "RESEND" -> ctx.getString(R.string.admin_log_action_resend)
        "MIGRATE_AUTH_PROVIDER" -> ctx.getString(R.string.admin_log_action_migrate)
        null, "" -> ctx.getString(R.string.admin_log_unknown)
        else -> humanize(wire)
    }

    fun targetType(ctx: Context, wire: String?): String = when (wire?.uppercase()) {
        "TAILNET" -> ctx.getString(R.string.admin_log_target_tailnet)
        "USER" -> ctx.getString(R.string.admin_log_target_user)
        "GROUP" -> ctx.getString(R.string.admin_log_target_group)
        "NODE" -> ctx.getString(R.string.admin_log_target_node)
        "API_KEY" -> ctx.getString(R.string.admin_log_target_api_key)
        "INVITE" -> ctx.getString(R.string.admin_log_target_invite)
        "SHARE" -> ctx.getString(R.string.admin_log_target_share)
        "BILLING" -> ctx.getString(R.string.admin_log_target_billing)
        "ADMIN_CONSOLE" -> ctx.getString(R.string.admin_log_target_admin_console)
        "WEB_INTERFACE" -> ctx.getString(R.string.admin_log_target_web_interface)
        "WEBHOOK_ENDPOINT" -> ctx.getString(R.string.admin_log_target_webhook)
        "FAILED_REQUEST" -> ctx.getString(R.string.admin_log_target_failed_request)
        null, "" -> ctx.getString(R.string.admin_log_unknown)
        else -> humanize(wire)
    }

    fun origin(ctx: Context, wire: String?): String? = when (wire?.uppercase()) {
        "ADMIN_CONSOLE" -> ctx.getString(R.string.admin_log_origin_admin_console)
        "CONFIG_API" -> ctx.getString(R.string.admin_log_origin_api)
        "CONTROL" -> ctx.getString(R.string.admin_log_origin_control)
        "IDENTITY_PROVIDER" -> ctx.getString(R.string.admin_log_origin_idp)
        "NODE" -> ctx.getString(R.string.admin_log_origin_node)
        "SUPPORT_REQUEST" -> ctx.getString(R.string.admin_log_origin_support)
        "SECURITY_NOTIFICATION", "LEGAL_NOTIFICATION" -> ctx.getString(R.string.admin_log_origin_notification)
        "STRIPE" -> "Stripe"
        "BORDER0_API" -> "Border0"
        null, "" -> null
        else -> humanize(wire)
    }

    fun actorType(ctx: Context, wire: String?): String? = when (wire?.uppercase()) {
        "USER" -> ctx.getString(R.string.admin_log_actor_user)
        "NODE" -> ctx.getString(R.string.admin_log_actor_node)
        "AUTOMATED_WORKER" -> ctx.getString(R.string.admin_log_actor_automated)
        "OAUTH_CLIENT" -> ctx.getString(R.string.admin_log_actor_oauth_client)
        "SCIM" -> "SCIM"
        "MULLVAD" -> "Mullvad"
        "LOGSTREAM" -> ctx.getString(R.string.admin_log_actor_logstream)
        "SECRET_SCANNER" -> ctx.getString(R.string.admin_log_actor_secret_scanner)
        "PAM_CONNECTOR", "PAM_SERVICE_ACCOUNT" -> ctx.getString(R.string.admin_log_actor_pam)
        null, "" -> null
        else -> humanize(wire)
    }

    fun property(ctx: Context, wire: String?): String? = when (wire?.uppercase()) {
        null, "" -> null
        "ACL" -> ctx.getString(R.string.admin_log_prop_acl)
        "ACL_TAGS" -> ctx.getString(R.string.admin_log_prop_tags)
        "ADDRESS" -> ctx.getString(R.string.admin_log_prop_address)
        "ALLOWED_IPS" -> ctx.getString(R.string.admin_log_prop_routes)
        "AUTO_APPROVED_ROUTES" -> ctx.getString(R.string.admin_log_prop_auto_routes)
        "ATTRIBUTES" -> ctx.getString(R.string.admin_log_prop_attributes)
        "DNS_CONFIG" -> ctx.getString(R.string.admin_log_prop_dns)
        "EMAIL", "ACCOUNT_EMAIL" -> ctx.getString(R.string.admin_log_prop_email)
        "SECURITY_EMAIL" -> ctx.getString(R.string.admin_log_prop_security_email)
        "SUPPORT_EMAIL" -> ctx.getString(R.string.admin_log_prop_support_email)
        "EXIT_NODE" -> ctx.getString(R.string.admin_log_prop_exit_node)
        "FEATURE" -> ctx.getString(R.string.admin_log_prop_feature)
        "FILE_SHARING" -> "Taildrop"
        "HTTPS" -> "HTTPS"
        "KEY_EXPIRY_TIME", "KEY_EXPIRY" -> ctx.getString(R.string.admin_log_prop_key_expiry)
        "MAGIC_DNS" -> "MagicDNS"
        "MACHINE_AUTH_NEEDED", "MACHINE_APPROVAL_NEEDED" -> ctx.getString(R.string.admin_log_prop_device_approval)
        "USER_APPROVAL_REQUIRED" -> ctx.getString(R.string.admin_log_prop_user_approval)
        "MACHINE_NAME" -> ctx.getString(R.string.admin_log_prop_name)
        "MAX_KEY_DURATION" -> ctx.getString(R.string.admin_log_prop_key_duration)
        "NETWORK_FLOW_LOGGING", "LOG_EXIT_FLOWS" -> ctx.getString(R.string.admin_log_prop_flow_logs)
        "NODE_SHARE" -> ctx.getString(R.string.admin_log_prop_node_share)
        "TAILNET_INVITE" -> ctx.getString(R.string.admin_log_target_invite)
        "POSTURE_IDENTITY", "COLLECT_POSTURE_IDENTITY", "POSTURE_INTEGRATION" -> ctx.getString(R.string.admin_log_prop_posture)
        "USER_ROLE" -> ctx.getString(R.string.admin_log_prop_role)
        "SUBSCRIBED_EVENTS" -> ctx.getString(R.string.admin_log_prop_webhook_events)
        "SECRET" -> ctx.getString(R.string.admin_log_prop_secret)
        "TKA" -> "Tailnet Lock"
        "ROUTE_SELECTION", "GEOSTEERING" -> ctx.getString(R.string.admin_log_prop_route_selection)
        "LOGSTREAM_ENDPOINT" -> ctx.getString(R.string.admin_log_actor_logstream)
        "COLLECT_SERVICES" -> ctx.getString(R.string.admin_log_prop_services)
        "MULLVAD_VPN" -> "Mullvad VPN"
        else -> humanize(wire)
    }

    /** "NODE.UPDATE.MACHINE_NAME" → "Device · Updated · Name". */
    fun event(ctx: Context, event: String): String {
        val parts = event.split('.')
        return listOfNotNull(
            targetType(ctx, parts.getOrNull(0)),
            action(ctx, parts.getOrNull(1)),
            parts.getOrNull(2)?.let { property(ctx, it) },
        ).joinToString(" · ")
    }

    /** One entry's headline: what happened, to what kind of thing ("Updated · Device"). */
    fun title(ctx: Context, e: ApiAuditLogEntry): String =
        ctx.getString(R.string.admin_log_entry_title, action(ctx, e.action), targetType(ctx, e.target?.type))

    /** "SUPPORT_EMAIL" → "Support email". */
    fun humanize(wire: String): String =
        wire.lowercase().replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

    /** The events the filter offers: what an admin looks for on a phone, not all hundred. */
    val events: List<String> = listOf(
        "NODE.CREATE", "NODE.APPROVE", "NODE.DELETE", "NODE.LOGIN", "NODE.LOGOUT",
        "NODE.UPDATE.MACHINE_NAME", "NODE.UPDATE.ACL_TAGS", "NODE.UPDATE.ALLOWED_IPS", "NODE.UPDATE.EXIT_NODE",
        "NODE.DISABLE.KEY_EXPIRY", "NODE.ENABLE.KEY_EXPIRY", "NODE.EXPIRED.KEY_EXPIRY_TIME",
        "USER.CREATE", "USER.APPROVE", "USER.SUSPEND", "USER.RESTORE", "USER.DELETE", "USER.UPDATE.USER_ROLE",
        "API_KEY.CREATE", "API_KEY.REVOKE", "API_KEY.EXPIRED",
        "TAILNET.UPDATE.ACL", "TAILNET.UPDATE.DNS_CONFIG", "TAILNET.UPDATE.MAX_KEY_DURATION",
        "TAILNET.ENABLE.MACHINE_APPROVAL_NEEDED", "TAILNET.DISABLE.MACHINE_APPROVAL_NEEDED",
        "SHARE.CREATE", "SHARE.DELETE", "ADMIN_CONSOLE.LOGIN",
    )
}
