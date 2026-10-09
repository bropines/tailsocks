package io.github.bropines.tailscaled.admin.attention

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.console.ConsoleChanges
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClassifier
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.DiffLine
import io.github.bropines.tailscaled.admin.safety.PlannedChange

/**
 * The attention list's own wording for the two changes Tailscale has no verb for. There is no
 * "reject": a device or user waiting for approval is turned away by deleting it, so these are
 * the delete changes — the same kinds, HIGH with the typed name — saying so in their title.
 * Approving goes through [ConsoleChanges] as it does from the device and user sheets.
 */
object AttentionChanges {

    fun rejectDevice(ctx: Context, d: ApiDevice, selfNodeId: String?): PlannedChange = PlannedChange(
        change = AdminChange(
            kind = ChangeKind.DEVICE_DELETE,
            changeClass = ChangeClassifier.classify(ChangeKind.DEVICE_DELETE),
            target = ConsoleChanges.deviceTarget(d, selfNodeId),
            title = ctx.getString(R.string.admin_attention_reject_device, d.shortName),
            effect = ctx.getString(R.string.admin_attention_reject_device_effect),
            diff = listOf(
                DiffLine(
                    ctx.getString(R.string.admin2_diff_device),
                    listOfNotNull(d.name.ifBlank { null }, d.user?.takeIf { it.isNotBlank() }, d.ipv4).joinToString(" · "),
                    null,
                )
            ),
        ),
        apply = { it.deleteDevice(d.pathId) },
        verify = { b -> runCatching { b.getDevice(d.pathId) }.exceptionOrNull() is AdminApiException.NotFound },
    )

    fun rejectUser(ctx: Context, u: ApiUser): PlannedChange = PlannedChange(
        change = AdminChange(
            kind = ChangeKind.USER_DELETE,
            changeClass = ChangeClassifier.classify(ChangeKind.USER_DELETE),
            target = ConsoleChanges.userTarget(u),
            title = ctx.getString(R.string.admin_attention_reject_user, u.name),
            effect = ctx.getString(R.string.admin_attention_reject_user_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_status), ConsoleText.status(ctx, u.userStatus, u.status), null)),
        ),
        apply = { it.deleteUser(u.id) },
        verify = { b -> runCatching { b.getUser(u.id) }.exceptionOrNull() is AdminApiException.NotFound },
    )
}
