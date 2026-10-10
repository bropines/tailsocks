package io.github.bropines.tailscaled.admin.users

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.api.UserStatus
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.keys.Chip
import io.github.bropines.tailscaled.admin.keys.relativeText
import io.github.bropines.tailscaled.admin.parseIso
import io.github.bropines.tailscaled.ui.EmptyState
import io.github.bropines.tailscaled.ui.ListDetailLayout
import io.github.bropines.tailscaled.ui.PaneEmptyState
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet
import io.github.bropines.tailscaled.ui.rememberWindowLayout

private const val ANY = "\u0000any"

/** Whether [u] is who the console acts as, or who this phone is signed in as. */
internal fun ConsoleState.isOwnUser(u: ApiUser): Boolean =
    (caps?.ownUserId != null && u.id == caps.ownUserId) ||
        (phoneInTailnet && self.loginName != null && u.loginName.equals(self.loginName, ignoreCase = true))

/**
 * The Users tab: members and shared users, searchable and filtered by role and status; the
 * open invites and device shares; a sheet per user with the changes it allows, each through
 * the safety gates. [now] is a parameter so a preview draws the same "last seen" every time.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UsersTab(state: ConsoleState, vm: AdminConsoleViewModel?, now: Long = remember { System.currentTimeMillis() }) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var roleWire by rememberSaveable { mutableStateOf<String?>(null) }
    var statusWire by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedUserId by rememberSaveable { mutableStateOf<String?>(null) }
    val requested = vm?.usersTab?.openUser?.collectAsState()?.value
    LaunchedEffect(requested) {
        if (requested != null) {
            selectedUserId = requested
            vm.usersTab.openUser.value = null
        }
    }
    var invitesOpen by rememberSaveable { mutableStateOf(false) }
    var choosingDevice by rememberSaveable { mutableStateOf(false) }
    var shareDeviceId by rememberSaveable { mutableStateOf<String?>(null) }

    val filter = UserFilter(query, roleWire?.let { UserRole.of(it) }, statusWire?.let { UserStatus.of(it) })
    val all = state.users.value.orEmpty()
    val users = remember(all, filter) { UserList.apply(all, filter) }
    val inviteAccess = state.inviteAccess(BackendFeature.USER_INVITES)
    val shareAccess = state.inviteAccess(BackendFeature.DEVICE_INVITES)

    // A large window shows a user beside the list, where a phone opens a sheet: the one picked
    // while the tailnet still has them, the first row otherwise — never an empty pane while
    // there is a row.
    val window = rememberWindowLayout()
    val twoPane = window.listDetail
    val paneUser = if (!twoPane) null else all.firstOrNull { it.id == selectedUserId } ?: users.firstOrNull()

    // One user, as the sheet over the list on a phone and as the pane beside it on a large window.
    val userDetail: @Composable (ApiUser) -> Unit = { user ->
        UserDetailSheet(
            user = user,
            own = state.isOwnUser(user),
            canWrite = state.canWrite(AdminArea.USERS),
            tailscale = (state.caps?.backend ?: BackendKind.TAILSCALE) == BackendKind.TAILSCALE,
            now = now,
            onDismiss = { selectedUserId = null },
            onAction = { action, role ->
                val c = vm?.usersTab ?: return@UserDetailSheet
                when (action) {
                    UserAction.APPROVE -> c.approve(user)
                    UserAction.CHANGE_ROLE -> role?.let { c.setRole(user, it) }
                    UserAction.SUSPEND -> c.suspend(user)
                    UserAction.RESTORE -> c.restore(user)
                    UserAction.DELETE -> c.delete(user)
                }
            },
        )
    }

    val list: @Composable () -> Unit = {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "problems") { LoadProblems(state.users, { vm?.refresh(ConsoleTab.USERS, force = true) }) }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(ctx.getString(R.string.admin_u_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, ctx.getString(R.string.admin_u_search_clear)) } }
                    } else null,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "filters") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filter.role != null,
                        onClick = { picking = "role" },
                        label = { Text(filter.role?.let { ConsoleText.role(ctx, it) } ?: ctx.getString(R.string.admin_u_filter_role)) },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = filter.status != null,
                        onClick = { picking = "status" },
                        label = { Text(filter.status?.let { ConsoleText.status(ctx, it) } ?: ctx.getString(R.string.admin_u_filter_status)) },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
                    )
                }
            }
            if (inviteAccess != InviteAccess.NOT_SUPPORTED || shareAccess != InviteAccess.NOT_SUPPORTED) {
                item(key = "invites") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (inviteAccess != InviteAccess.NOT_SUPPORTED) {
                            val open = state.userInvites.value?.size
                            EntryCard(
                                icon = Icons.Default.MailOutline,
                                title = ctx.getString(R.string.admin_u_invites),
                                subtitle = when {
                                    open != null -> ctx.resources.getQuantityString(R.plurals.admin_u_invites_open, open, open)
                                    state.userInvites.error != null -> ctx.getString(R.string.admin_u_invites_unavailable)
                                    else -> ctx.getString(R.string.admin_u_invites_loading)
                                },
                                modifier = Modifier.weight(1f),
                            ) { invitesOpen = true }
                        }
                        if (shareAccess != InviteAccess.NOT_SUPPORTED) {
                            EntryCard(
                                icon = Icons.Default.Share,
                                title = ctx.getString(R.string.admin_u_share_device),
                                subtitle = ctx.getString(R.string.admin_u_share_device_sub),
                                modifier = Modifier.weight(1f),
                            ) { choosingDevice = true }
                        }
                    }
                }
            }
            when {
                all.isEmpty() && state.users.loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                users.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        Icons.Default.Group,
                        ctx.getString(if (all.isEmpty()) R.string.admin_users_no_users else R.string.admin_u_none_match),
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        actionLabel = if (filter.isActive) ctx.getString(R.string.admin_u_clear_filters) else null,
                        onAction = {
                            query = ""
                            roleWire = null
                            statusWire = null
                        },
                    )
                }
                else -> items(users, key = { it.id }) { u ->
                    UserRow(u, own = state.isOwnUser(u), now = now, shown = u.id == paneUser?.id) { selectedUserId = u.id }
                }
            }
        }
    }
    if (twoPane) {
        ListDetailLayout(
            window = window,
            twoPane = true,
            modifier = Modifier.fillMaxSize(),
            list = list,
            detail = {
                if (paneUser != null) key(paneUser.id) { userDetail(paneUser) }
                else PaneEmptyState(Icons.Default.Group, ctx.getString(R.string.tablet_admin_user_pane_empty))
            },
        )
    } else {
        list()
    }

    when (picking) {
        "role" -> PickerSheet(
            title = ctx.getString(R.string.admin_u_filter_role),
            options = listOf(PickerOption(ANY, ctx.getString(R.string.admin_u_filter_any))) +
                UserList.filterRoles.map { PickerOption(it.wire, ConsoleText.role(ctx, it)) },
            selected = roleWire ?: ANY,
            onPick = { roleWire = it.takeIf { v -> v != ANY } },
            onDismiss = { picking = null },
        )
        "status" -> PickerSheet(
            title = ctx.getString(R.string.admin_u_filter_status),
            options = listOf(PickerOption(ANY, ctx.getString(R.string.admin_u_filter_any))) +
                UserList.filterStatuses.map { PickerOption(it.wire, ConsoleText.status(ctx, it)) },
            selected = statusWire ?: ANY,
            onPick = { statusWire = it.takeIf { v -> v != ANY } },
            onDismiss = { picking = null },
        )
    }

    selectedUserId?.let { id ->
        val user = all.firstOrNull { it.id == id }
        // Gone after a refresh (deleted, say): the sheet closes with it.
        LaunchedEffect(user == null) { if (user == null) selectedUserId = null }
        // Two-pane, the pane shows them instead.
        if (user != null && !twoPane) userDetail(user)
    }

    if (invitesOpen) {
        InvitesSheet(state = state, vm = vm, access = inviteAccess, onDismiss = { invitesOpen = false })
    }

    if (choosingDevice) {
        LaunchedEffect(Unit) { if (state.devices.value == null) vm?.refresh(ConsoleTab.DEVICES) }
        val devices = state.devices.value.orEmpty().filter { !it.isShared }.sortedBy { it.shortName.lowercase() }
        PickerSheet(
            title = ctx.getString(R.string.admin_u_share_pick),
            options = devices.map { d -> PickerOption(d.pathId, d.shortName, Icons.Default.Devices, listOfNotNull(d.os, d.ipv4, d.user).joinToString(" · ")) },
            onPick = { shareDeviceId = it },
            onDismiss = { choosingDevice = false },
        )
    }

    shareDeviceId?.let { id ->
        val device = state.devices.value?.firstOrNull { it.pathId == id }
        LaunchedEffect(device == null) { if (device == null) shareDeviceId = null }
        if (device != null) DeviceShareSheet(state, vm, device, shareAccess) { shareDeviceId = null }
    }
}

/** A tappable card that opens a sheet: invites, device sharing. */
@Composable
private fun EntryCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Online", or when the user was last seen, in words. */
internal fun presenceText(ctx: android.content.Context, u: ApiUser, now: Long): String? = when {
    u.currentlyConnected == true -> ctx.getString(R.string.admin_u_online)
    else -> parseIso(u.lastSeen)?.time?.let { ctx.getString(R.string.admin_u_last_seen, relativeText(ctx, it - now)) }
}

/** One user; [shown] is the one whose details stand in the pane beside the list, outlined. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserRow(user: ApiUser, own: Boolean, now: Long, shown: Boolean = false, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick)
            .then(if (shown) Modifier.semantics { selected = true } else Modifier),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        border = if (shown) BorderStroke(2.dp, scheme.primary) else null,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(user)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(user.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(user.loginName, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    UserChips(user, own)
                }
                presenceText(ctx, user, now)?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        if (user.currentlyConnected == true) {
                            Icon(Icons.Default.Circle, null, Modifier.size(8.dp), tint = scheme.primary)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(it, style = MaterialTheme.typography.labelSmall, color = if (user.currentlyConnected == true) scheme.primary else scheme.outline)
                    }
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

/** Role, a status other than active, "shared" and "you", each in words with an icon where it warns. */
@Composable
internal fun UserChips(user: ApiUser, own: Boolean) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val role = user.userRole
    Chip(
        ConsoleText.role(ctx, role, user.role),
        if (role.isPrivileged) scheme.primaryContainer else scheme.surfaceContainerHighest,
        if (role.isPrivileged) scheme.onPrimaryContainer else scheme.onSurface,
        if (role.isPrivileged) Icons.Default.AdminPanelSettings else null,
    )
    val status = user.userStatus
    if (status != UserStatus.ACTIVE) {
        val warn = status == UserStatus.SUSPENDED || status == UserStatus.OVER_BILLING_LIMIT
        Chip(
            ConsoleText.status(ctx, status, user.status),
            when {
                warn -> scheme.errorContainer
                status == UserStatus.NEEDS_APPROVAL -> scheme.tertiaryContainer
                else -> scheme.surfaceContainerHighest
            },
            when {
                warn -> scheme.onErrorContainer
                status == UserStatus.NEEDS_APPROVAL -> scheme.onTertiaryContainer
                else -> scheme.onSurfaceVariant
            },
            when (status) {
                UserStatus.SUSPENDED, UserStatus.OVER_BILLING_LIMIT -> Icons.Default.Block
                UserStatus.NEEDS_APPROVAL -> Icons.Default.Schedule
                else -> null
            },
        )
    }
    if (user.type == "shared") Chip(ConsoleText.userType(ctx, user.type), scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Default.Share)
    if (own) Chip(ctx.getString(R.string.admin2_user_you), scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Default.Person)
}

/** Initials on a colour from the theme's palette, picked by the login so it stays put. */
@Composable
internal fun UserAvatar(user: ApiUser, size: Int = 40) {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
    )
    val (bg, fg) = palette[Math.floorMod(user.loginName.hashCode(), palette.size)]
    Box(Modifier.size(size.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(
            (user.name.firstOrNull() ?: '?').uppercaseChar().toString(),
            color = fg,
            style = if (size > 48) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
