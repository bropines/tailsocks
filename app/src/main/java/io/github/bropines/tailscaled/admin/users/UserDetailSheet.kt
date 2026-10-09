package io.github.bropines.tailscaled.admin.users

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.DetailRow
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.formatExpires
import io.github.bropines.tailscaled.admin.keys.LocaleSheet
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet

/** Where ownership is transferred: the Users page of Tailscale's admin console. */
const val OWNER_TRANSFER_URL = "https://login.tailscale.com/admin/users"

/**
 * One user: who they are, what their role and status mean, and the changes offered on them.
 * The user the console acts as, a user of another tailnet and the owner get no actions, with
 * the reason in words; the safety runner refuses the first anyway.
 */
@Composable
internal fun UserDetailSheet(
    user: ApiUser,
    own: Boolean,
    canWrite: Boolean,
    tailscale: Boolean,
    now: Long,
    onDismiss: () -> Unit,
    onAction: (UserAction, UserRole?) -> Unit,
) {
    val ctx = LocalContext.current
    var rolePicker by rememberSaveable { mutableStateOf(false) }

    if (rolePicker) {
        PickerSheet(
            title = ctx.getString(R.string.admin_u_role_pick, user.name),
            options = UserRole.assignable.map { PickerOption(it, ConsoleText.role(ctx, it), supporting = ctx.getString(roleShort(it))) },
            selected = user.userRole,
            onPick = { if (it != user.userRole) onAction(UserAction.CHANGE_ROLE, it) },
            onDismiss = { rolePicker = false },
        )
    }

    LocaleSheet(onDismiss) { _ ->
        UserDetailContent(user, own, canWrite, tailscale, now, onAction, onPickRole = { rolePicker = true })
    }
}

/** The user sheet's body, apart so a preview can draw it without a window. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserDetailContent(
    user: ApiUser,
    own: Boolean,
    canWrite: Boolean,
    tailscale: Boolean,
    now: Long,
    onAction: (UserAction, UserRole?) -> Unit,
    onPickRole: () -> Unit,
) {
    val ctx = LocalContext.current
    val lock = UserList.lock(user, own)
    val actions = if (canWrite) UserList.actions(user, own) else emptyList()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        UserAvatar(user, size = 56)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(user.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(user.loginName, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) { UserChips(user, own) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column {
                    DetailRow(ctx.getString(R.string.admin_users_role), ConsoleText.role(ctx, user.userRole, user.role))
                    HelpText(ctx.getString(UserText.roleHelp(user.userRole)))
                }
                Column {
                    DetailRow(ctx.getString(R.string.admin_users_status), ConsoleText.status(ctx, user.userStatus, user.status))
                    HelpText(ctx.getString(UserText.statusHelp(user.userStatus)))
                }
                DetailRow(ctx.getString(R.string.admin_u_type), ConsoleText.userType(ctx, user.type))
                user.deviceCount?.let { DetailRow(ctx.getString(R.string.admin_u_devices), ctx.resources.getQuantityString(R.plurals.admin2_user_devices, it, it)) }
                presenceText(ctx, user, now)?.let { DetailRow(ctx.getString(R.string.admin_u_presence), it) }
                user.created?.let { DetailRow(ctx.getString(R.string.admin_u_joined), formatExpires(it)) }
            }
        }

        if (lock != null) {
            HelpText(ctx.getString(UserText.lockReason(lock)), modifier = Modifier.fillMaxWidth())
            if (lock == UserLock.OWNER && tailscale) ConsoleLink()
        } else if (!canWrite) {
            HelpText(ctx.getString(R.string.admin_u_cannot_write), modifier = Modifier.fillMaxWidth())
        }

        if (actions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action ->
                    when (action) {
                        UserAction.APPROVE -> Button(onClick = { onAction(action, null) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            ActionLabel(Icons.Default.CheckCircle, ctx.getString(R.string.admin_u_approve))
                        }
                        UserAction.CHANGE_ROLE -> {
                            OutlinedButton(onClick = onPickRole, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                                ActionLabel(Icons.Default.ManageAccounts, ctx.getString(R.string.admin_u_change_role))
                            }
                            if (tailscale) {
                                HelpText(ctx.getString(R.string.admin_u_owner_transfer), modifier = Modifier.fillMaxWidth())
                                ConsoleLink()
                            }
                        }
                        UserAction.SUSPEND -> OutlinedButton(onClick = { onAction(action, null) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            ActionLabel(Icons.Default.Block, ctx.getString(R.string.admin_u_suspend))
                        }
                        UserAction.RESTORE -> OutlinedButton(onClick = { onAction(action, null) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            ActionLabel(Icons.AutoMirrored.Filled.Undo, ctx.getString(R.string.admin_u_restore))
                        }
                        UserAction.DELETE -> Button(
                            onClick = { onAction(action, null) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
                        ) {
                            ActionLabel(Icons.Default.Delete, ctx.getString(R.string.admin_u_delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionLabel(icon: ImageVector, text: String) {
    Icon(icon, null, Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text)
}

/** Opens the Users page of the admin console, where the owner role is handed over. */
@Composable
private fun ConsoleLink() {
    val ctx = LocalContext.current
    TextButton(onClick = {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OWNER_TRANSFER_URL))) }
    }) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(ctx.getString(R.string.admin_u_open_console))
    }
}

/** A role in a few words, for the picker's second line. */
private fun roleShort(role: UserRole): Int = when (role) {
    UserRole.OWNER -> R.string.admin_u_role_owner_short
    UserRole.ADMIN -> R.string.admin_u_role_admin_short
    UserRole.NETWORK_ADMIN -> R.string.admin_u_role_network_admin_short
    UserRole.IT_ADMIN -> R.string.admin_u_role_it_admin_short
    UserRole.BILLING_ADMIN -> R.string.admin_u_role_billing_admin_short
    UserRole.AUDITOR -> R.string.admin_u_role_auditor_short
    UserRole.MEMBER, UserRole.UNKNOWN -> R.string.admin_u_role_member_short
}
