package io.github.bropines.tailscaled.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.api.UserStatus
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.ui.EmptyState
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet
import io.github.bropines.tailscaled.ui.rememberFullSheetState

@Composable
fun UsersTabContent(
    state: Loadable<List<ApiUser>>,
    isOwn: (ApiUser) -> Boolean,
    onRetry: () -> Unit,
    onUserClick: (ApiUser) -> Unit,
) {
    val ctx = LocalContext.current
    // Waiting for approval first: that is what an admin opens this list for.
    val users = remember(state.value) {
        state.value.orEmpty().sortedWith(compareBy<ApiUser> { it.userStatus != UserStatus.NEEDS_APPROVAL }.thenBy { it.name.lowercase() })
    }
    Column(Modifier.fillMaxSize()) {
        LoadProblems(state, onRetry, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        when {
            users.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
            users.isEmpty() -> EmptyState(Icons.Default.Group, ctx.getString(R.string.admin_users_no_users))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(users, key = { it.id }) { user -> UserRow(user = user, own = isOwn(user), onClick = { onUserClick(user) }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserRow(user: ApiUser, own: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onClick() },
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(user = user)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(user.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(user.loginName, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    val role = user.userRole
                    StatusTag(
                        ConsoleText.role(ctx, role, user.role),
                        if (role.isPrivileged) scheme.primaryContainer else scheme.surfaceContainerHighest,
                        if (role.isPrivileged) scheme.onPrimaryContainer else scheme.onSurface,
                        if (role.isPrivileged) Icons.Default.AdminPanelSettings else null,
                    )
                    val status = user.userStatus
                    if (status != UserStatus.ACTIVE) {
                        StatusTag(
                            ConsoleText.status(ctx, status, user.status),
                            if (status == UserStatus.SUSPENDED) scheme.errorContainer else scheme.tertiaryContainer,
                            if (status == UserStatus.SUSPENDED) scheme.onErrorContainer else scheme.onTertiaryContainer,
                            when (status) {
                                UserStatus.SUSPENDED -> Icons.Default.Block
                                UserStatus.NEEDS_APPROVAL -> Icons.Default.Schedule
                                else -> null
                            },
                        )
                    }
                    if (own) StatusTag(ctx.getString(R.string.admin2_user_you), scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Default.Person)
                }
            }
            user.deviceCount?.let { n ->
                Text(
                    ctx.resources.getQuantityString(R.plurals.admin2_user_devices, n, n),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** Initials on a colour from the theme's palette, picked by the login so it stays put. */
@Composable
fun UserAvatar(user: ApiUser) {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
    )
    val (bg, fg) = palette[Math.floorMod(user.loginName.hashCode(), palette.size)]
    Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(
            (user.name.firstOrNull() ?: '?').uppercaseChar().toString(),
            color = fg,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * One user and the changes offered on them, each handed to the safety pipeline. The user the
 * console acts as — or this phone's own login — gets no actions: the runner would refuse
 * them anyway, and a disabled button with the reason says so first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailBottomSheet(
    user: ApiUser,
    own: Boolean,
    canWrite: Boolean,
    onDismiss: () -> Unit,
    onRoleChange: (UserRole) -> Unit,
    onApprove: () -> Unit,
    onSuspend: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberFullSheetState()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    // Resolved through the parent's context; the sheet's own ignores the app locale.
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var rolePicker by remember { mutableStateOf(false) }
    val actionable = canWrite && !own

    if (rolePicker) {
        PickerSheet(
            title = ctx.getString(R.string.admin_users_select_role_title),
            options = UserRole.assignable.map { PickerOption(it, ConsoleText.role(ctx, it)) },
            selected = user.userRole,
            onPick = { if (it != user.userRole) onRoleChange(it) },
            onDismiss = { rolePicker = false },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            UserAvatar(user = user)
            Text(user.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow(ctx.getString(R.string.admin_users_login_name), user.loginName)
                    user.displayName?.takeIf { it.isNotBlank() }?.let { DetailRow(ctx.getString(R.string.admin_users_display_name), it) }
                    DetailRow(ctx.getString(R.string.admin_users_created_at), formatExpires(user.created))
                    DetailRow(ctx.getString(R.string.admin_users_role), ConsoleText.role(ctx, user.userRole, user.role))
                    DetailRow(ctx.getString(R.string.admin_users_status), ConsoleText.status(ctx, user.userStatus, user.status))
                    DetailRow(ctx.getString(R.string.admin_users_type), ConsoleText.userType(ctx, user.type))
                    DetailRow(ctx.getString(R.string.admin_users_devices_owned), (user.deviceCount ?: 0).toString())
                    user.lastSeen?.let { DetailRow(ctx.getString(R.string.pickers_sort_last_seen), formatExpires(it)) }
                }
            }

            if (own) {
                HelpText(ctx.getString(R.string.admin2_refused_own_user), modifier = Modifier.padding(horizontal = 24.dp))
            }

            if (actionable) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (user.userStatus == UserStatus.NEEDS_APPROVAL) {
                        Button(onClick = onApprove, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.CheckCircle, null)
                            Spacer(Modifier.width(8.dp))
                            Text(ctx.getString(R.string.admin_users_approve))
                        }
                    }
                    if (user.userRole != UserRole.OWNER) {
                        OutlinedButton(onClick = { rolePicker = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.ManageAccounts, null)
                            Spacer(Modifier.width(8.dp))
                            Text(ctx.getString(R.string.admin_users_change_role))
                        }
                    }
                    if (user.userStatus == UserStatus.SUSPENDED) {
                        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.AutoMirrored.Filled.Undo, null)
                            Spacer(Modifier.width(8.dp))
                            Text(ctx.getString(R.string.admin_users_restore))
                        }
                    } else if (user.userRole != UserRole.OWNER) {
                        OutlinedButton(onClick = onSuspend, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.Block, null)
                            Spacer(Modifier.width(8.dp))
                            Text(ctx.getString(R.string.admin_users_suspend))
                        }
                    }
                    if (user.userRole != UserRole.OWNER) {
                        Button(
                            onClick = onDelete,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text(ctx.getString(R.string.admin_users_delete))
                        }
                    }
                }
            }
        }
    }
}
