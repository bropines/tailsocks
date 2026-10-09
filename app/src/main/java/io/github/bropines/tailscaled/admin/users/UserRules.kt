package io.github.bropines.tailscaled.admin.users

import androidx.annotation.StringRes
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.api.UserStatus

/*
 * What the Users tab decides without a screen: search and filters, the order of the list,
 * which actions a user is offered, and the words that explain roles and statuses.
 */

/** The list's search text and its two filters; null means any. */
data class UserFilter(val query: String = "", val role: UserRole? = null, val status: UserStatus? = null) {
    val isActive: Boolean get() = query.isNotBlank() || role != null || status != null
}

/** What may be done to one user from here. */
enum class UserAction { APPROVE, CHANGE_ROLE, SUSPEND, RESTORE, DELETE }

/** Why a user is offered nothing; null when something is offered. */
enum class UserLock {
    /** The credential's own user, or the one this phone is signed in as. */
    OWN,
    /** A user of another tailnet, here through a shared device. */
    SHARED,
    /** The owner: only an ownership transfer, in the admin console, changes them. */
    OWNER,
}

object UserList {

    fun matches(u: ApiUser, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return listOfNotNull(u.displayName, u.loginName, u.id).any { it.contains(q, ignoreCase = true) }
    }

    /**
     * The users [filter] lets through: waiting for approval first (what an admin opens the
     * list for), then those online, then by name.
     */
    fun apply(users: List<ApiUser>, filter: UserFilter): List<ApiUser> =
        users.filter { u ->
            matches(u, filter.query) &&
                (filter.role == null || u.userRole == filter.role) &&
                (filter.status == null || u.userStatus == filter.status)
        }.sortedWith(
            compareBy<ApiUser> { it.userStatus != UserStatus.NEEDS_APPROVAL }
                .thenBy { it.currentlyConnected != true }
                .thenBy { it.name.lowercase() }
        )

    /** Why nothing is offered for [u], or null. */
    fun lock(u: ApiUser, own: Boolean): UserLock? = when {
        own -> UserLock.OWN
        u.type == "shared" -> UserLock.SHARED
        u.userRole == UserRole.OWNER -> UserLock.OWNER
        else -> null
    }

    /** The actions the detail sheet offers for [u], in the order it shows them. */
    fun actions(u: ApiUser, own: Boolean): List<UserAction> {
        if (lock(u, own) != null) return emptyList()
        return buildList {
            if (u.userStatus == UserStatus.NEEDS_APPROVAL) add(UserAction.APPROVE)
            add(UserAction.CHANGE_ROLE)
            if (u.userStatus == UserStatus.SUSPENDED) add(UserAction.RESTORE) else add(UserAction.SUSPEND)
            add(UserAction.DELETE)
        }
    }

    /** The roles a filter offers: every role the API has. */
    val filterRoles: List<UserRole> = listOf(
        UserRole.OWNER, UserRole.ADMIN, UserRole.NETWORK_ADMIN, UserRole.IT_ADMIN,
        UserRole.BILLING_ADMIN, UserRole.AUDITOR, UserRole.MEMBER,
    )

    val filterStatuses: List<UserStatus> = listOf(
        UserStatus.NEEDS_APPROVAL, UserStatus.ACTIVE, UserStatus.IDLE, UserStatus.SUSPENDED, UserStatus.OVER_BILLING_LIMIT,
    )

    /** What an invite may give: every role but owner, member first. */
    val inviteRoles: List<UserRole> = listOf(
        UserRole.MEMBER, UserRole.ADMIN, UserRole.NETWORK_ADMIN, UserRole.IT_ADMIN, UserRole.BILLING_ADMIN, UserRole.AUDITOR,
    )
}

/** The one-sentence explanations, by value; the screen resolves them. */
object UserText {

    @StringRes
    fun roleHelp(role: UserRole): Int = when (role) {
        UserRole.OWNER -> R.string.admin_u_role_owner_help
        UserRole.ADMIN -> R.string.admin_u_role_admin_help
        UserRole.NETWORK_ADMIN -> R.string.admin_u_role_network_admin_help
        UserRole.IT_ADMIN -> R.string.admin_u_role_it_admin_help
        UserRole.BILLING_ADMIN -> R.string.admin_u_role_billing_admin_help
        UserRole.AUDITOR -> R.string.admin_u_role_auditor_help
        UserRole.MEMBER -> R.string.admin_u_role_member_help
        UserRole.UNKNOWN -> R.string.admin_u_role_unknown_help
    }

    @StringRes
    fun statusHelp(status: UserStatus): Int = when (status) {
        UserStatus.ACTIVE -> R.string.admin_u_status_active_help
        UserStatus.IDLE -> R.string.admin_u_status_idle_help
        UserStatus.SUSPENDED -> R.string.admin_u_status_suspended_help
        UserStatus.NEEDS_APPROVAL -> R.string.admin_u_status_needs_approval_help
        UserStatus.OVER_BILLING_LIMIT -> R.string.admin_u_status_over_billing_limit_help
        UserStatus.UNKNOWN -> R.string.admin_u_status_unknown_help
    }

    @StringRes
    fun lockReason(lock: UserLock): Int = when (lock) {
        UserLock.OWN -> R.string.admin_u_lock_own
        UserLock.SHARED -> R.string.admin_u_lock_shared
        UserLock.OWNER -> R.string.admin_u_lock_owner
    }
}
