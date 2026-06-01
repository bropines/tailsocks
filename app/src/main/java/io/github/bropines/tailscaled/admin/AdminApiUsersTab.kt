package io.github.bropines.tailscaled.admin
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun UsersTabContent(
    users: List<ApiUser>,
    onUserClick: (ApiUser) -> Unit
) {
    if (users.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No users found", color = MaterialTheme.colorScheme.outline)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(users) { user ->
                UserRow(user = user, onClick = { onUserClick(user) })
            }
        }
    }
}

@Composable
fun UserRow(user: ApiUser, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(user = user)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    user.displayName?.takeIf { it.isNotBlank() } ?: user.loginName.substringBefore("@"),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    user.loginName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Role Badge
                    val roleLabel = user.role ?: "member"
                    val isPrivileged = roleLabel == "owner" || roleLabel.contains("admin")
                    Text(
                        text = roleLabel.uppercase(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isPrivileged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isPrivileged) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )

                    // Status Badge
                    val statusLabel = user.status ?: "active"
                    val statusColor = when (statusLabel) {
                        "active" -> Color(0xFF4CAF50)
                        "suspended" -> MaterialTheme.colorScheme.error
                        else -> Color(0xFFFF9800)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(statusLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (user.deviceCount != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        user.deviceCount.toString(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("devices", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
fun UserAvatar(user: ApiUser) {
    val name = user.displayName?.takeIf { it.isNotBlank() } ?: user.loginName
    val firstChar = name.firstOrNull()?.uppercaseChar() ?: '?'
    
    val colors = listOf(
        Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7),
        Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF009688),
        Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFFF5722)
    )
    val colorIndex = Math.abs(user.loginName.hashCode()) % colors.size
    val bgColor = colors[colorIndex]

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = firstChar.toString(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailBottomSheet(
    user: ApiUser,
    onDismiss: () -> Unit,
    onRoleChange: (String) -> Unit,
    onApprove: () -> Unit,
    onSuspend: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.85f).dp

    var showRoleDialog by remember { mutableStateOf(false) }
    var showSuspendConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showApproveConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            UserAvatar(user = user)

            Text(
                user.displayName?.takeIf { it.isNotBlank() } ?: user.loginName.substringBefore("@"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Quick Actions: Change User Role
            OutlinedButton(
                onClick = { showRoleDialog = true },
                modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth()
            ) {
                Icon(Icons.Default.ManageAccounts, null)
                Spacer(Modifier.width(8.dp))
                Text("Change User Role")
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow("Login Name", user.loginName)
                    DetailRow("Display Name", user.displayName ?: "N/A")
                    DetailRow("Created At", formatExpires(user.created))
                    DetailRow("Role", user.role ?: "member")
                    DetailRow("Status", user.status ?: "active")
                    DetailRow("User Type", user.type ?: "N/A")
                    DetailRow("Devices Owned", user.deviceCount?.toString() ?: "0")
                }
            }

            // Administrative Actions
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (user.status == "pending" || user.status == "needs_approval") {
                    Button(
                        onClick = { showApproveConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Approve User")
                    }
                }

                if (user.status == "suspended") {
                    Button(
                        onClick = { showRestoreConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50), contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Undo, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Restore User Access")
                    }
                } else {
                    OutlinedButton(
                        onClick = { showSuspendConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Icon(Icons.Default.Block, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Suspend User")
                    }
                }

                Button(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete User")
                }
            }
        }
    }

    if (showRoleDialog) {
        val roles = listOf("owner", "admin", "member", "itadmin", "billingadmin", "auditor")
        AlertDialog(
            onDismissRequest = { showRoleDialog = false },
            title = { Text("Select User Role") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    roles.forEach { role ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onRoleChange(role)
                                    showRoleDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (user.role ?: "member").lowercase() == role,
                                onClick = {
                                    onRoleChange(role)
                                    showRoleDialog = false
                                }
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(role.uppercase(), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showRoleDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showApproveConfirm) {
        AlertDialog(
            onDismissRequest = { showApproveConfirm = false },
            title = { Text("Approve User?") },
            text = { Text("This will approve the user account and allow them to authenticate devices on the tailnet.") },
            confirmButton = {
                Button(onClick = { showApproveConfirm = false; onApprove() }) { Text("Approve") }
            },
            dismissButton = {
                TextButton(onClick = { showApproveConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showSuspendConfirm) {
        AlertDialog(
            onDismissRequest = { showSuspendConfirm = false },
            title = { Text("Suspend User?") },
            text = { Text("Are you sure you want to suspend this user? They will lose access to all tailnet resources and their active keys will be blocked.") },
            confirmButton = {
                Button(onClick = { showSuspendConfirm = false; onSuspend() }) { Text("Suspend") }
            },
            dismissButton = {
                TextButton(onClick = { showSuspendConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restore User?") },
            text = { Text("This will restore the user access and re-enable their tailnet connectivity.") },
            confirmButton = {
                Button(onClick = { showRestoreConfirm = false; onRestore() }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete User?") },
            text = { Text("Permanently delete this user account? This action is irreversible.") },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
