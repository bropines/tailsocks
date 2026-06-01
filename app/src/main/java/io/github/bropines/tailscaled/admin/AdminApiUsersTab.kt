package io.github.bropines.tailscaled.admin
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun UsersTabContent(
    users: List<ApiUser>
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
                UserRow(user = user)
            }
        }
    }
}

@Composable
fun UserRow(user: ApiUser) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                    fontWeight = FontWeight.Bold
                )
                Text(
                    user.loginName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
