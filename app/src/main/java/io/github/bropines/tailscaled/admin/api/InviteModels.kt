package io.github.bropines.tailscaled.admin.api

import kotlinx.serialization.Serializable

/*
 * Invitations and trust credentials as the OpenAPI schema (api/v2, 2026-10) defines them. Every
 * field optional, as in AdminModels: a field the server leaves out costs nothing.
 */

/** An open invitation to join the tailnet with a role. Accepted invites leave the list. */
@Serializable
data class ApiUserInvite(
    val id: String = "",
    val role: String? = null,
    val tailnetId: Long? = null,
    val inviterId: Long? = null,
    /** Empty when the invite was not emailed: [inviteUrl] is then the only way to hand it over. */
    val email: String? = null,
    val lastEmailSentAt: String? = null,
    /** Anyone with it can accept; absent for an email in the tailnet's own domain. */
    val inviteUrl: String? = null,
) {
    val userRole: UserRole get() = UserRole.of(role)
    val emailed: Boolean get() = !email.isNullOrBlank()
}

/** Who accepted a device share. */
@Serializable
data class ApiInviteAcceptor(val id: Long? = null, val loginName: String? = null, val profilePicUrl: String? = null)

/** A device shared with a user outside the tailnet, accepted or not. */
@Serializable
data class ApiDeviceInvite(
    val id: String = "",
    val created: String? = null,
    val tailnetId: Long? = null,
    val deviceId: Long? = null,
    val sharerId: Long? = null,
    /** Up to 1000 acceptances instead of one. */
    val multiUse: Boolean? = null,
    val allowExitNode: Boolean? = null,
    val email: String? = null,
    val lastEmailSentAt: String? = null,
    val inviteUrl: String? = null,
    val accepted: Boolean? = null,
    val acceptedBy: ApiInviteAcceptor? = null,
) {
    val isAccepted: Boolean get() = accepted == true
}

/** A device share to create: emailed when [email] is set, otherwise a link to hand over. */
data class DeviceInviteRequest(
    val email: String? = null,
    val multiUse: Boolean = false,
    val allowExitNode: Boolean = false,
)

/**
 * An OAuth client (trust credential) to create. [tags] are mandatory when a write scope
 * reaches devices:core or auth_keys: the keys and devices it makes carry them.
 */
data class OAuthClientRequest(
    val description: String,
    val scopes: List<String>,
    val tags: List<String> = emptyList(),
)
