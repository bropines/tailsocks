package io.github.bropines.tailscaled.admin.console

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.KeyType
import io.github.bropines.tailscaled.admin.api.RouteSelection
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.api.UserStatus
import io.github.bropines.tailscaled.admin.profile.MissingCredentialException
import io.github.bropines.tailscaled.admin.safety.AuditResult
import io.github.bropines.tailscaled.admin.safety.ChangeOutcome
import io.github.bropines.tailscaled.admin.safety.Refusal
import io.github.bropines.tailscaled.admin.secure.UnlockFailure

/**
 * The console's words for API values and outcomes. Every function takes the Context to resolve
 * in — the activity's (or a locale-wrapped one), never the bare Application before Android 13,
 * which would answer in the system language.
 */
object ConsoleText {

    fun error(ctx: Context, e: Throwable): String {
        val base = when (e) {
            is AdminApiException.Unauthorized -> ctx.getString(R.string.admin2_error_unauthorized)
            is AdminApiException.PaymentRequired -> ctx.getString(R.string.admin2_error_payment)
            is AdminApiException.Forbidden -> e.missingScope?.let { ctx.getString(R.string.admin2_error_forbidden_scope, it) }
                ?: ctx.getString(R.string.admin2_error_forbidden)
            is AdminApiException.NotFound -> ctx.getString(R.string.admin2_error_not_found)
            is AdminApiException.Conflict -> ctx.getString(R.string.admin2_error_conflict)
            is AdminApiException.PreconditionFailed -> ctx.getString(R.string.admin2_error_precondition)
            is AdminApiException.BadRequest -> e.apiMessage?.let { ctx.getString(R.string.admin2_error_bad_request, it) }
                ?: ctx.getString(R.string.admin2_error_bad_request_plain)
            is AdminApiException.RateLimited -> ctx.getString(R.string.admin2_error_rate_limited)
            is AdminApiException.Server -> ctx.getString(R.string.admin2_error_server, e.status)
            is AdminApiException.Network -> ctx.getString(R.string.admin2_error_network, e.detail)
            is AdminApiException.Decode -> ctx.getString(R.string.admin2_error_decode, e.what)
            is AdminApiException.Unsupported -> ctx.getString(
                if (e.feature == BackendFeature.POLICY_WRITE) R.string.admin_hs_policy_file_readonly else R.string.admin2_error_unsupported
            )
            is AdminApiException.Unexpected -> ctx.getString(R.string.admin2_error_other, e.apiMessage ?: e.message.orEmpty())
            is MissingCredentialException -> ctx.getString(R.string.admin2_credential_unreadable)
            else -> ctx.getString(R.string.admin2_error_other, e.message ?: e.javaClass.simpleName)
        }
        val id = (e as? AdminApiException)?.requestId
        return if (id != null) base + " · " + ctx.getString(R.string.admin2_error_request_id, id) else base
    }

    fun refusal(ctx: Context, r: Refusal): String = ctx.getString(
        when (r) {
            Refusal.READ_ONLY_PROFILE -> R.string.admin2_refused_read_only
            Refusal.NO_SCREEN_LOCK -> R.string.admin2_unlock_no_lock
            Refusal.NOT_ALLOWED -> R.string.admin2_refused_not_allowed
            Refusal.OWN_CREDENTIAL -> R.string.admin2_refused_own_credential
            Refusal.OWN_USER -> R.string.admin2_refused_own_user
            Refusal.SHARED_DEVICE -> R.string.admin2_refused_shared
            Refusal.POLICY_PIPELINE -> R.string.admin2_refused_policy
            Refusal.NOT_CONFIRMED, Refusal.TYPED_MISMATCH, Refusal.NOT_UNLOCKED -> R.string.admin2_refused_gates
        }
    )

    fun unlockFailure(ctx: Context, f: UnlockFailure): String = ctx.getString(
        when (f) {
            UnlockFailure.NO_SCREEN_LOCK -> R.string.admin2_unlock_no_lock
            UnlockFailure.PROMPT_UNAVAILABLE -> R.string.admin2_unlock_unavailable
            UnlockFailure.LOCKOUT -> R.string.admin2_unlock_lockout
            UnlockFailure.NOT_PROVEN -> R.string.admin2_unlock_not_proven
            UnlockFailure.ERROR -> R.string.admin2_unlock_error
        }
    )

    fun outcome(ctx: Context, title: String, out: ChangeOutcome): String = when (out) {
        is ChangeOutcome.Applied -> when (out.verified) {
            true -> ctx.getString(R.string.admin2_outcome_verified, title)
            false -> ctx.getString(R.string.admin2_outcome_mismatch, title)
            null -> ctx.getString(R.string.admin2_outcome_applied, title)
        }
        is ChangeOutcome.Refused -> refusal(ctx, out.reason)
        is ChangeOutcome.Failed -> ctx.getString(R.string.admin2_outcome_failed, title, error(ctx, out.error))
    }

    fun role(ctx: Context, role: UserRole, wire: String? = null): String = when (role) {
        UserRole.OWNER -> ctx.getString(R.string.admin2_role_owner)
        UserRole.ADMIN -> ctx.getString(R.string.admin2_role_admin)
        UserRole.IT_ADMIN -> ctx.getString(R.string.admin2_role_it_admin)
        UserRole.NETWORK_ADMIN -> ctx.getString(R.string.admin2_role_network_admin)
        UserRole.BILLING_ADMIN -> ctx.getString(R.string.admin2_role_billing_admin)
        UserRole.AUDITOR -> ctx.getString(R.string.admin2_role_auditor)
        UserRole.MEMBER -> ctx.getString(R.string.admin2_role_member)
        UserRole.UNKNOWN -> wire?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.admin2_role_member)
    }

    fun status(ctx: Context, status: UserStatus, wire: String? = null): String = when (status) {
        UserStatus.ACTIVE -> ctx.getString(R.string.admin2_status_active)
        UserStatus.IDLE -> ctx.getString(R.string.admin2_status_idle)
        UserStatus.SUSPENDED -> ctx.getString(R.string.admin2_status_suspended)
        UserStatus.NEEDS_APPROVAL -> ctx.getString(R.string.admin2_status_needs_approval)
        UserStatus.OVER_BILLING_LIMIT -> ctx.getString(R.string.admin2_status_over_billing_limit)
        UserStatus.UNKNOWN -> wire?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.admin2_status_active)
    }

    fun userType(ctx: Context, type: String?): String = when (type) {
        "shared" -> ctx.getString(R.string.admin2_user_type_shared)
        else -> ctx.getString(R.string.admin2_user_type_member)
    }

    fun keyType(ctx: Context, type: KeyType): String = ctx.getString(
        when (type) {
            KeyType.AUTH -> R.string.admin2_key_type_auth
            KeyType.API -> R.string.admin2_key_type_api
            KeyType.CLIENT -> R.string.admin2_key_type_client
            KeyType.FEDERATED -> R.string.admin2_key_type_federated
            KeyType.UNKNOWN -> R.string.admin2_key_type_unknown
        }
    )

    fun routeSelection(ctx: Context, value: String?): String = when (value) {
        RouteSelection.FAILOVER -> ctx.getString(R.string.admin2_route_failover)
        RouteSelection.REGIONAL -> ctx.getString(R.string.admin2_route_regional)
        RouteSelection.REGIONAL_FAILOVER -> ctx.getString(R.string.admin2_route_regional_failover)
        null, "" -> ctx.getString(R.string.admin2_settings_unknown)
        else -> value
    }

    fun auditResult(ctx: Context, r: AuditResult): String = ctx.getString(
        when (r) {
            AuditResult.VERIFIED -> R.string.admin2_result_verified
            AuditResult.APPLIED -> R.string.admin2_result_applied
            AuditResult.MISMATCH -> R.string.admin2_result_mismatch
            AuditResult.FAILED -> R.string.admin2_result_failed
            AuditResult.REFUSED -> R.string.admin2_result_refused
        }
    )

    fun webhookProvider(ctx: Context, provider: String?): String = when (provider) {
        "slack" -> ctx.getString(R.string.admin2_webhook_provider_slack)
        "mattermost" -> ctx.getString(R.string.admin2_webhook_provider_mattermost)
        "googlechat" -> ctx.getString(R.string.admin2_webhook_provider_googlechat)
        "discord" -> ctx.getString(R.string.admin2_webhook_provider_discord)
        else -> ctx.getString(R.string.admin2_webhook_provider_plain)
    }

    fun onOff(ctx: Context, on: Boolean?): String = when (on) {
        true -> ctx.getString(R.string.admin2_on)
        false -> ctx.getString(R.string.admin2_off)
        null -> ctx.getString(R.string.admin2_settings_unknown)
    }

    /** A list as one diff value; null (shown as "nothing") when empty. */
    fun list(items: Collection<String>): String? = items.takeIf { it.isNotEmpty() }?.joinToString(", ")
}
