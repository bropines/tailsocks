package io.github.bropines.tailscaled.admin.users

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiDeviceInvite
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.ApiUserInvite
import io.github.bropines.tailscaled.admin.api.DeviceInviteRequest
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.api.UserStatus
import io.github.bropines.tailscaled.admin.console.ConsoleChanges
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClassifier
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.ChangeTarget
import io.github.bropines.tailscaled.admin.safety.DiffLine
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.TargetType

/**
 * The Users tab's writes beyond the per-user ones in [ConsoleChanges]: those again with what
 * the user's devices go through, and the invites — to the tailnet and to one device.
 */
object UserChanges {

    private fun PlannedChange.withWarnings(extra: List<String>): PlannedChange =
        if (extra.isEmpty()) this else PlannedChange(change.copy(warnings = change.warnings + extra), apply, verify, undo, isUndo)

    private fun devicesWarning(ctx: Context, u: ApiUser, res: Int): List<String> =
        u.deviceCount?.takeIf { it > 0 }?.let { listOf(ctx.resources.getQuantityString(res, it, it)) }.orEmpty()

    fun suspend(ctx: Context, u: ApiUser): PlannedChange =
        ConsoleChanges.suspendUser(ctx, u).withWarnings(devicesWarning(ctx, u, R.plurals.admin_u_warn_suspend_devices))

    /** HIGH, as giving access back is: with the status before and after shown. */
    fun restore(ctx: Context, u: ApiUser): PlannedChange {
        val base = ConsoleChanges.restoreUser(ctx, u)
        val diff = DiffLine(ctx.getString(R.string.admin2_diff_status), ConsoleText.status(ctx, u.userStatus, u.status), ConsoleText.status(ctx, UserStatus.ACTIVE))
        return PlannedChange(base.change.copy(diff = listOf(diff)), base.apply, base.verify, base.undo, base.isUndo)
    }

    fun delete(ctx: Context, u: ApiUser): PlannedChange =
        ConsoleChanges.deleteUser(ctx, u).withWarnings(devicesWarning(ctx, u, R.plurals.admin_u_warn_delete_devices))

    /** A role change, with a warning when it hands out or takes away admin rights. */
    fun setRole(ctx: Context, u: ApiUser, role: UserRole): PlannedChange {
        val extra = buildList {
            if (role.isPrivileged && !u.userRole.isPrivileged) add(ctx.getString(R.string.admin_u_warn_role_up))
            if (!role.isPrivileged && u.userRole.isPrivileged) add(ctx.getString(R.string.admin_u_warn_role_down))
        }
        val base = ConsoleChanges.setUserRole(ctx, u, role)
        // What the new role may do, said where it is confirmed.
        val effect = ctx.getString(R.string.admin_u_change_role_effect, ConsoleText.role(ctx, role), ctx.getString(UserText.roleHelp(role)))
        return PlannedChange(base.change.copy(effect = effect), base.apply, base.verify, base.undo, base.isUndo).withWarnings(extra)
    }

    // ------------------------------------------------------------------ invites to the tailnet

    private fun inviteName(ctx: Context, inv: ApiUserInvite): String =
        inv.email?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.admin_u_invite_link_only, inv.id)

    /**
     * A new invite. Typed back for a role above member: the email, or the tailnet's name for
     * a link — whoever holds the link joins with that role.
     */
    fun createInvite(ctx: Context, email: String?, role: UserRole, tailnetLabel: String, onCreated: (ApiUserInvite) -> Unit): PlannedChange {
        var created: ApiUserInvite? = null
        val mail = email?.trim()?.takeIf { it.isNotEmpty() }
        val roleName = ConsoleText.role(ctx, role)
        return PlannedChange(
            change = AdminChange(
                kind = ChangeKind.USER_INVITE_CREATE,
                changeClass = ChangeClassifier.userInvite(role),
                target = ChangeTarget(TargetType.INVITE, "new", mail ?: tailnetLabel),
                title = ctx.getString(R.string.admin_u_change_invite_create),
                effect = if (mail != null) ctx.getString(R.string.admin_u_change_invite_email_effect, mail, roleName)
                else ctx.getString(R.string.admin_u_change_invite_link_effect, roleName),
                diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_role), null, roleName)),
                warnings = buildList {
                    if (role != UserRole.MEMBER) add(ctx.getString(R.string.admin_u_warn_invite_role, roleName))
                    if (mail == null) add(ctx.getString(R.string.admin_u_warn_invite_link))
                },
            ),
            apply = { b -> b.createUserInvite(mail, role).also { created = it }.let(onCreated) },
            verify = verify@{ b ->
                val id = created?.id?.takeIf { it.isNotBlank() } ?: return@verify false
                b.listUserInvites().items.any { it.id == id }
            },
        )
    }

    fun resendInvite(ctx: Context, inv: ApiUserInvite): PlannedChange = PlannedChange(
        change = AdminChange(
            kind = ChangeKind.USER_INVITE_RESEND,
            changeClass = ChangeClassifier.classify(ChangeKind.USER_INVITE_RESEND),
            target = ChangeTarget(TargetType.INVITE, inv.id, inviteName(ctx, inv)),
            title = ctx.getString(R.string.admin_u_change_invite_resend, inviteName(ctx, inv)),
            effect = ctx.getString(R.string.admin_u_change_invite_resend_effect),
        ),
        apply = { it.resendUserInvite(inv.id) },
    )

    fun deleteInvite(ctx: Context, inv: ApiUserInvite): PlannedChange = PlannedChange(
        change = AdminChange(
            kind = ChangeKind.USER_INVITE_DELETE,
            changeClass = ChangeClassifier.classify(ChangeKind.USER_INVITE_DELETE),
            target = ChangeTarget(TargetType.INVITE, inv.id, inv.email?.takeIf { it.isNotBlank() } ?: inv.id),
            title = ctx.getString(R.string.admin_u_change_invite_delete, inviteName(ctx, inv)),
            effect = ctx.getString(R.string.admin_u_change_invite_delete_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_role), ConsoleText.role(ctx, inv.userRole, inv.role), null)),
        ),
        apply = { it.deleteUserInvite(inv.id) },
        verify = { b -> b.listUserInvites().items.none { it.id == inv.id } },
    )

    // ------------------------------------------------------------------ device shares

    /** Sharing [d] outside the tailnet. A link that can be accepted a thousand times is HIGH. */
    fun createDeviceInvite(
        ctx: Context,
        d: ApiDevice,
        request: DeviceInviteRequest,
        selfNodeId: String?,
        onCreated: (ApiDeviceInvite) -> Unit,
    ): PlannedChange {
        var created: ApiDeviceInvite? = null
        val mail = request.email?.trim()?.takeIf { it.isNotEmpty() }
        return PlannedChange(
            change = AdminChange(
                kind = ChangeKind.DEVICE_INVITE_CREATE,
                changeClass = ChangeClassifier.deviceInvite(request.multiUse),
                target = ConsoleChanges.deviceTarget(d, selfNodeId),
                title = ctx.getString(R.string.admin_u_change_share_create, d.shortName),
                effect = if (mail != null) ctx.getString(R.string.admin_u_change_share_email_effect, mail)
                else ctx.getString(R.string.admin_u_change_share_link_effect),
                warnings = buildList {
                    if (request.multiUse) add(ctx.getString(R.string.admin_u_warn_share_multi))
                    if (request.allowExitNode) add(ctx.getString(R.string.admin_u_warn_share_exit))
                },
            ),
            apply = { b -> b.createDeviceInvite(d.pathId, request.copy(email = mail)).also { created = it }.let(onCreated) },
            verify = verify@{ b ->
                val id = created?.id?.takeIf { it.isNotBlank() } ?: return@verify false
                b.listDeviceInvites(d.pathId).items.any { it.id == id }
            },
        )
    }

    /** Typed back: the email or the acceptor's login when there is one, else the invite's id. */
    fun deleteDeviceInvite(ctx: Context, d: ApiDevice, inv: ApiDeviceInvite): PlannedChange {
        val who = inv.acceptedBy?.loginName?.takeIf { it.isNotBlank() } ?: inv.email?.takeIf { it.isNotBlank() }
        return PlannedChange(
            change = AdminChange(
                kind = ChangeKind.DEVICE_INVITE_DELETE,
                changeClass = ChangeClassifier.classify(ChangeKind.DEVICE_INVITE_DELETE),
                target = ChangeTarget(TargetType.INVITE, inv.id, who ?: inv.id),
                title = ctx.getString(R.string.admin_u_change_share_delete, d.shortName),
                effect = ctx.getString(R.string.admin_u_change_share_delete_effect),
                diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_device), d.shortName + (who?.let { " → $it" } ?: ""), null)),
                warnings = if (inv.isAccepted) listOf(ctx.getString(R.string.admin_u_warn_share_accepted, who ?: inv.id)) else emptyList(),
            ),
            apply = { it.deleteDeviceInvite(inv.id) },
            verify = { b -> b.listDeviceInvites(d.pathId).items.none { it.id == inv.id } },
        )
    }
}
