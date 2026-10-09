package io.github.bropines.tailscaled.admin.users

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiDeviceInvite
import io.github.bropines.tailscaled.admin.api.ApiUserInvite
import io.github.bropines.tailscaled.admin.api.DeviceInviteRequest
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.formatExpires
import io.github.bropines.tailscaled.admin.keys.Chip
import io.github.bropines.tailscaled.admin.keys.KeyRules
import io.github.bropines.tailscaled.admin.keys.LocaleDialog
import io.github.bropines.tailscaled.admin.keys.LocaleSheet
import io.github.bropines.tailscaled.admin.keys.SwitchRow
import io.github.bropines.tailscaled.admin.keys.relativeText
import io.github.bropines.tailscaled.admin.parseIso
import io.github.bropines.tailscaled.admin.secure.SensitiveClipboard
import io.github.bropines.tailscaled.ui.HelpText

/** An invite link is a bearer credential: copied marked sensitive, like a key. */
private fun copyLink(ctx: Context, link: String) {
    SensitiveClipboard.copy(ctx, ctx.getString(R.string.admin_u_invite_link), link)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(ctx, ctx.getString(R.string.admin2_secret_copied), Toast.LENGTH_SHORT).show()
}

/** Why the sheet cannot create, in a card of its own; nothing when it can. */
@Composable
private fun AccessNote(access: InviteAccess) {
    if (access == InviteAccess.ALLOWED) return
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        HelpText(
            ctx.getString(if (access == InviteAccess.NEEDS_PERSONAL_TOKEN) R.string.admin_u_invites_need_token else R.string.admin2_error_unsupported),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SheetColumn(content: @Composable () -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun SmallAction(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    TextButton(onClick = onClick) {
        Icon(icon, null, Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(text, color = color)
    }
}

// ---------------------------------------------------------------------- invites to the tailnet

/**
 * The tailnet's open invites: whom they went to, with what role, when; copy a link, resend
 * an email, delete one. Inviting takes a personal token, which the sheet says when the
 * profile runs on an OAuth client.
 */
@Composable
internal fun InvitesSheet(state: ConsoleState, vm: AdminConsoleViewModel?, access: InviteAccess, now: Long = System.currentTimeMillis(), onDismiss: () -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm?.usersTab?.loadInvites() }
    val manage = access == InviteAccess.ALLOWED && state.canWrite(AdminArea.USERS)
    LocaleSheet(onDismiss) { _ ->
        InvitesContent(
            invites = state.userInvites,
            access = access,
            manage = manage,
            now = now,
            onRetry = { vm?.usersTab?.loadInvites(force = true) },
            onCreate = { creating = true },
            onResend = { vm?.usersTab?.resendInvite(it) },
            onDelete = { vm?.usersTab?.deleteInvite(it) },
        )
    }
    if (creating) {
        CreateInviteDialog(onDismiss = { creating = false }) { email, role ->
            creating = false
            vm?.usersTab?.createInvite(email, role)
        }
    }
}

/** The invites sheet's body, apart so a preview can draw it without a window. */
@Composable
fun InvitesContent(
    invites: Loadable<List<ApiUserInvite>>,
    access: InviteAccess,
    manage: Boolean,
    now: Long,
    onRetry: () -> Unit,
    onCreate: () -> Unit,
    onResend: (ApiUserInvite) -> Unit,
    onDelete: (ApiUserInvite) -> Unit,
) {
    val ctx = LocalContext.current
    SheetColumn {
        Text(ctx.getString(R.string.admin_u_invites_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HelpText(ctx.getString(R.string.admin_u_invites_help))
        AccessNote(access)
        LoadProblems(invites, onRetry)
        val list = invites.value.orEmpty()
        if (list.isEmpty() && invites.value != null) {
            Text(ctx.getString(R.string.admin_u_invites_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
        list.forEach { inv -> InviteCard(inv, manage, now, onResend = { onResend(inv) }, onDelete = { onDelete(inv) }) }
        if (manage) {
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin_u_invite_new))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InviteCard(inv: ApiUserInvite, manage: Boolean, now: Long, onResend: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (inv.emailed) Icons.Default.MailOutline else Icons.Default.Link, null, tint = scheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        inv.email?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.admin_u_invite_link_only, inv.id),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    parseIso(inv.lastEmailSentAt)?.time?.let {
                        Text(ctx.getString(R.string.admin_u_invite_sent, relativeText(ctx, it - now)), style = MaterialTheme.typography.bodySmall, color = scheme.outline)
                    }
                }
                Chip(ConsoleText.role(ctx, inv.userRole, inv.role), scheme.surfaceContainerHighest, scheme.onSurface)
            }
            FlowRow {
                inv.inviteUrl?.takeIf { it.isNotBlank() }?.let { link ->
                    SmallAction(Icons.Default.ContentCopy, ctx.getString(R.string.admin_u_copy_link)) { copyLink(ctx, link) }
                }
                if (manage && inv.emailed) SmallAction(Icons.AutoMirrored.Filled.Send, ctx.getString(R.string.admin_u_resend), onClick = onResend)
                if (manage) SmallAction(Icons.Default.Delete, ctx.getString(R.string.admin_u_invite_delete), danger = true, onClick = onDelete)
            }
        }
    }
}

/** What the new-invite form holds; saved across rotation by [Saver]. */
@Stable
class InviteForm(email: String = "", role: UserRole = UserRole.MEMBER) {
    var email by mutableStateOf(email)
    var role by mutableStateOf(role)
    val emailOk: Boolean get() = email.isBlank() || KeyRules.isEmail(email)

    companion object {
        val Saver: Saver<InviteForm, Any> = listSaver(
            save = { listOf(it.email, it.role.wire) },
            restore = { v -> InviteForm(v[0] as String, UserRole.of(v[1] as String)) },
        )
    }
}

/**
 * A new invite: an email to send it to, or none for a link to pass on yourself, and the role
 * it gives. Roles as a radio list with a few words each; owner is not among them.
 */
@Composable
fun CreateInviteDialog(onDismiss: () -> Unit, onCreate: (email: String?, role: UserRole) -> Unit) {
    val ctx = LocalContext.current
    val form = rememberSaveable(saver = InviteForm.Saver) { InviteForm() }
    LocaleDialog(
        onDismiss = onDismiss,
        title = ctx.getString(R.string.admin_u_invite_new),
        icon = Icons.Default.PersonAdd,
        confirmButton = {
            Button(enabled = form.emailOk, onClick = { onCreate(form.email.trim().ifBlank { null }, form.role) }) { Text(ctx.getString(R.string.admin_u_invite_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    ) { InviteFields(form) }
}

/** The new-invite form's fields, apart so a preview can draw them without a dialog window. */
@Composable
fun InviteFields(form: InviteForm) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EmailField(form.email, form.emailOk, ctx.getString(R.string.admin_u_invite_email_help)) { form.email = it }
        Text(ctx.getString(R.string.admin_users_role), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            UserList.inviteRoles.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .selectable(selected = r == form.role, role = Role.RadioButton) { form.role = r }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = r == form.role, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(ConsoleText.role(ctx, r), style = MaterialTheme.typography.bodyLarge)
                        Text(ctx.getString(UserText.roleHelp(r)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmailField(value: String, ok: Boolean, help: String, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(ctx.getString(R.string.admin_u_invite_email)) },
        supportingText = { Text(if (ok) help else ctx.getString(R.string.admin_u_invite_email_bad)) },
        isError = !ok,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------- device shares

/**
 * One device's shares with people outside the tailnet: who accepted, what is still open, a
 * new share as an email or a link. Creating takes a personal token; deleting does not.
 */
@Composable
internal fun DeviceShareSheet(
    state: ConsoleState,
    vm: AdminConsoleViewModel?,
    device: ApiDevice,
    access: InviteAccess,
    now: Long = System.currentTimeMillis(),
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(device.pathId) { vm?.usersTab?.loadDeviceInvites(device) }
    val canWrite = state.canWrite(AdminArea.DEVICE_INVITES)
    LocaleSheet(onDismiss) { _ ->
        DeviceShareContent(
            device = device,
            invites = state.deviceInvites[device.pathId] ?: Loadable(loading = true),
            access = access,
            canCreate = access == InviteAccess.ALLOWED && canWrite,
            canDelete = canWrite,
            now = now,
            onRetry = { vm?.usersTab?.loadDeviceInvites(device, force = true) },
            onCreate = { creating = true },
            onDelete = { vm?.usersTab?.deleteDeviceInvite(device, it) },
        )
    }
    if (creating) {
        CreateShareDialog(device, onDismiss = { creating = false }) { request ->
            creating = false
            vm?.usersTab?.createDeviceInvite(device, request)
        }
    }
}

/** The share sheet's body, apart so a preview can draw it without a window. */
@Composable
fun DeviceShareContent(
    device: ApiDevice,
    invites: Loadable<List<ApiDeviceInvite>>,
    access: InviteAccess,
    canCreate: Boolean,
    canDelete: Boolean,
    now: Long,
    onRetry: () -> Unit,
    onCreate: () -> Unit,
    onDelete: (ApiDeviceInvite) -> Unit,
) {
    val ctx = LocalContext.current
    SheetColumn {
        Text(ctx.getString(R.string.admin_u_share_title, device.shortName), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HelpText(ctx.getString(R.string.admin_u_share_help))
        AccessNote(access)
        LoadProblems(invites, onRetry)
        val list = invites.value.orEmpty().sortedBy { it.isAccepted }
        if (list.isEmpty() && invites.value != null) {
            Text(ctx.getString(R.string.admin_u_share_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
        list.forEach { inv -> ShareCard(inv, canDelete, now) { onDelete(inv) } }
        if (canCreate) {
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin_u_share_new))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareCard(inv: ApiDeviceInvite, canDelete: Boolean, now: Long, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val who = inv.acceptedBy?.loginName?.takeIf { it.isNotBlank() } ?: inv.email?.takeIf { it.isNotBlank() }
    Surface(shape = MaterialTheme.shapes.large, color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (inv.email.isNullOrBlank() && !inv.isAccepted) Icons.Default.Link else Icons.Default.MailOutline, null, tint = scheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(who ?: ctx.getString(R.string.admin_u_invite_link_only, inv.id), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inv.created?.let { Text(ctx.getString(R.string.admin_u_share_created, formatExpires(it)), style = MaterialTheme.typography.bodySmall, color = scheme.outline) }
                    parseIso(inv.lastEmailSentAt)?.time?.let {
                        Text(ctx.getString(R.string.admin_u_invite_sent, relativeText(ctx, it - now)), style = MaterialTheme.typography.bodySmall, color = scheme.outline)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (inv.isAccepted) Chip(ctx.getString(R.string.admin_u_share_accepted), scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Default.CheckCircle)
                else Chip(ctx.getString(R.string.admin_u_share_pending), scheme.surfaceContainerHighest, scheme.onSurface, Icons.Default.Schedule)
                if (inv.multiUse == true) Chip(ctx.getString(R.string.admin_u_share_multi), scheme.tertiaryContainer, scheme.onTertiaryContainer, Icons.Default.Repeat)
                if (inv.allowExitNode == true) Chip(ctx.getString(R.string.admin_u_share_exit), scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.AutoMirrored.Filled.ExitToApp)
            }
            FlowRow {
                if (!inv.isAccepted || inv.multiUse == true) inv.inviteUrl?.takeIf { it.isNotBlank() }?.let { link ->
                    SmallAction(Icons.Default.ContentCopy, ctx.getString(R.string.admin_u_copy_link)) { copyLink(ctx, link) }
                }
                if (canDelete) SmallAction(Icons.Default.Delete, ctx.getString(R.string.admin_u_share_delete), danger = true, onClick = onDelete)
            }
        }
    }
}

/** What the new-share form holds; saved across rotation by [Saver]. */
@Stable
class ShareForm(email: String = "", multiUse: Boolean = false, exitNode: Boolean = false) {
    var email by mutableStateOf(email)
    var multiUse by mutableStateOf(multiUse)
    var exitNode by mutableStateOf(exitNode)
    val emailOk: Boolean get() = email.isBlank() || KeyRules.isEmail(email)
    val request: DeviceInviteRequest get() = DeviceInviteRequest(email.trim().ifBlank { null }, multiUse, exitNode)

    companion object {
        val Saver: Saver<ShareForm, Any> = listSaver(
            save = { listOf(it.email, it.multiUse, it.exitNode) },
            restore = { v -> ShareForm(v[0] as String, v[1] as Boolean, v[2] as Boolean) },
        )
    }
}

/** A new share of [device]: an email or a link, one acceptance or many, exit node or not. */
@Composable
fun CreateShareDialog(device: ApiDevice, onDismiss: () -> Unit, onCreate: (DeviceInviteRequest) -> Unit) {
    val ctx = LocalContext.current
    val form = rememberSaveable(saver = ShareForm.Saver) { ShareForm() }
    LocaleDialog(
        onDismiss = onDismiss,
        title = ctx.getString(R.string.admin_u_share_title, device.shortName),
        icon = Icons.Default.Share,
        confirmButton = {
            Button(enabled = form.emailOk, onClick = { onCreate(form.request) }) { Text(ctx.getString(R.string.admin_u_share_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    ) { ShareFields(form) }
}

/** The new-share form's fields, apart so a preview can draw them without a dialog window. */
@Composable
fun ShareFields(form: ShareForm) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EmailField(form.email, form.emailOk, ctx.getString(R.string.admin_u_share_email_help)) { form.email = it }
        SwitchRow(ctx.getString(R.string.admin_u_share_multi), ctx.getString(R.string.admin_u_share_multi_help), form.multiUse) { form.multiUse = it }
        SwitchRow(ctx.getString(R.string.admin_u_share_exit), ctx.getString(R.string.admin_u_share_exit_help), form.exitNode) { form.exitNode = it }
    }
}
