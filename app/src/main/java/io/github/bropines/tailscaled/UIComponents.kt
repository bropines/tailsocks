package io.github.bropines.tailscaled

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import appctr.Appctr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// --- ПИРЫ ---

@Composable
fun PeerItem(peer: PeerData, isSelf: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (isSelf) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(
                    when (peer.os?.lowercase()) {
                        "android" -> Icons.Default.Android
                        "windows" -> Icons.Default.DesktopWindows
                        "linux" -> Icons.Default.Terminal
                        else -> Icons.Default.Devices
                    },
                    null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                val displayName = peer.getDisplayName()
                val primaryIp = peer.getPrimaryIp()
                Text(displayName, fontWeight = FontWeight.Bold)
                if (displayName != primaryIp) {
                    Text(primaryIp, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!peer.tags.isNullOrEmpty()) {
                    Text(
                        text = peer.tags.joinToString(", "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (peer.online == true || isSelf) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
            }
        }
    }
}

@Composable
fun PeerShareItem(peer: PeerData, enabled: Boolean, onClick: () -> Unit) {
    val osIcon = when (peer.os?.lowercase()) {
        "android" -> Icons.Default.Android
        "windows" -> Icons.Default.DesktopWindows
        "linux" -> Icons.Default.Terminal
        "darwin", "macos", "ios" -> Icons.Default.Devices
        else -> Icons.Default.Devices
    }
    val osColor = when (peer.os?.lowercase()) {
        "android" -> Color(0xFF3DDC84)
        "windows" -> Color(0xFF0078D4)
        "linux" -> Color(0xFFFCC624)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(osColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    osIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = osColor
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    peer.getDisplayName(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    peer.os ?: "Device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (peer.online == true) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50))
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDetailsModal(
    peer: PeerData,
    onDismiss: () -> Unit,
    onSendFileClick: () -> Unit = {},
    onPrevPeer: (() -> Unit)? = null,
    onNextPeer: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pingResult by remember { mutableStateOf<String?>(null) }
    var dragAmount by remember { mutableFloatStateOf(0f) }

    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.85f).dp

    val pingText = when {
        pingResult == null -> "Ping"
        pingResult == "Pinging..." -> "Pinging..."
        pingResult!!.contains("pong from") -> {
            val parts = pingResult!!.split(" ")
            val time = parts.find { it.contains("ms") } ?: parts.lastOrNull()?.trim() ?: ""
            "Ping: $time"
        }
        else -> "Ping: Failed"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .padding(top = 4.dp, bottom = 16.dp)
                .pointerInput(peer.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragAmount > 100f) {
                                onPrevPeer?.invoke()
                            } else if (dragAmount < -100f) {
                                onNextPeer?.invoke()
                            }
                            dragAmount = 0f
                        },
                        onHorizontalDrag = { _, dragAmountDelta ->
                            dragAmount += dragAmountDelta
                        }
                    )
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onPrevPeer != null) {
                    IconButton(onClick = onPrevPeer) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous")
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val osIcon = when (peer.os?.lowercase()) {
                        "android" -> Icons.Default.Android
                        "windows" -> Icons.Default.DesktopWindows
                        "linux" -> Icons.Default.Terminal
                        "darwin", "macos", "ios" -> Icons.Default.Devices
                        else -> Icons.Default.Devices
                    }
                    val statusColor = if (peer.online == true) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(osIcon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(peer.getDisplayName(), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    }
                }
                
                if (onNextPeer != null) {
                    IconButton(onClick = onNextPeer) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        pingResult = "Pinging..."
                        scope.launch(Dispatchers.IO) {
                            val out = try { Appctr.runTailscaleCmd("ping ${peer.getPrimaryIp()}") } catch (e: Exception) { "Error" }
                            val pong = out.split("\n").find { it.contains("pong from") } ?: "Failed"
                            withContext(Dispatchers.Main) { pingResult = pong.trim() }
                        }
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Bolt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(pingText, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onSendFileClick,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send File", fontWeight = FontWeight.SemiBold)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        items(peer.getDetailsList()) { (l, v) ->
                            val isTechnical = l in listOf("IPv4", "IPv6", "Allowed IPs", "Node ID", "Tailscale Version", "Current Addr", "Peer API")
                            
                            ListItem(
                                headlineContent = { 
                                    Text(
                                        l, 
                                        style = MaterialTheme.typography.bodySmall, 
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                    ) 
                                },
                                supportingContent = { 
                                    Text(
                                        v, 
                                        fontFamily = if (isTechnical) FontFamily.Monospace else FontFamily.Default,
                                        fontSize = if (isTechnical) 13.sp else 14.sp,
                                        fontWeight = if (isTechnical) FontWeight.Normal else FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(top = 2.dp)
                                    ) 
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(l, v))
                                        Toast.makeText(context, "$l copied to clipboard!", Toast.LENGTH_SHORT).show()
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- ФАЙЛЫ ---

@Composable
fun FileCard(file: TaildropFile, onOpen: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(file.ModTime * 1000))
    val sizeStr = formatFileSize(file.Size)
    val ext = file.Name.substringAfterLast('.', "").lowercase()

    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FileIcon(ext)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(file.Name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$dateStr • $sizeStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            TextButton(onClick = onSave) { Text("Save") }
            Button(onClick = onOpen, shape = RoundedCornerShape(12.dp)) { Text("Open") }
        }
    }
}

@Composable
fun SentFileCard(entry: SentFileEntry) {
    val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        ListItem(headlineContent = { Text(entry.name, fontWeight = FontWeight.Medium) }, supportingContent = { Text("To: ${entry.target} • $dateStr") },
            leadingContent = { Icon(Icons.Default.Outbound, null, tint = MaterialTheme.colorScheme.primary) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

@Composable
fun FileIcon(extension: String) {
    val (icon, color) = when (extension) {
        "jpg", "jpeg", "png", "webp", "gif" -> Icons.Default.Image to Color(0xFF4CAF50)
        "mp4", "mkv", "mov" -> Icons.Default.VideoFile to Color(0xFFFF9800)
        "mp3", "wav" -> Icons.Default.AudioFile to Color(0xFFE91E63)
        "pdf" -> Icons.Default.PictureAsPdf to Color(0xFFF44336)
        "zip", "rar", "7z" -> Icons.Default.FolderZip to Color(0xFF9C27B0)
        else -> Icons.Default.InsertDriveFile to Color(0xFF607D8B)
    }
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(text, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge)
    }
}

fun openTaildropFile(context: Context, file: TaildropFile) {
    try {
        val f = File(file.Path)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }, "Open file"))
    } catch (e: Exception) { Toast.makeText(context, "Can't open: ${e.message}", Toast.LENGTH_SHORT).show() }
}
