package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Outbound
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import appctr.Appctr
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// --- PEERS ---

@Serializable
private data class PingResult(
    @SerialName("Err") val err: String? = null,
    @SerialName("LatencySeconds") val latencySeconds: Double? = null,
    @SerialName("LatencyMs") val latencyMs: Double? = null
)

fun getOsVisuals(os: String?): Pair<ImageVector, Color> {
    val osLower = os?.lowercase().orEmpty()
    val icon = when {
        osLower.contains("android") -> Icons.Default.Android
        osLower.contains("windows") -> Icons.Default.DesktopWindows
        osLower.contains("linux") -> Icons.Default.Terminal
        osLower.contains("macos") || osLower.contains("darwin") -> Icons.Default.DesktopMac
        osLower.contains("ios") -> Icons.Default.PhoneIphone
        else -> Icons.Default.Devices
    }
    val color = when {
        osLower.contains("android") -> Color(0xFF3DDC84)
        osLower.contains("windows") -> Color(0xFF0078D4)
        osLower.contains("linux") -> Color(0xFFFCC624)
        osLower.contains("macos") || osLower.contains("darwin") || osLower.contains("ios") -> Color(0xFFAF52DE) // Apple Purple
        else -> Color(0xFF9E9E9E)
    }
    return icon to color
}

@Composable
fun PeerItem(peer: PeerData, isSelf: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (isSelf) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val (osIcon, osColor) = getOsVisuals(peer.os)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(osColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    osIcon,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = osColor
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
    val (osIcon, osColor) = getOsVisuals(peer.os)

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
    /** This device. Pinging yourself has no peer to answer, so the button offered
     *  an action whose only possible outcome was "Ping: Failed" — an error where
     *  nothing had gone wrong. */
    isSelf: Boolean = false,
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
        pingResult == null -> stringResource(R.string.peer_ping)
        pingResult == "Pinging..." -> stringResource(R.string.peer_pinging)
        pingResult!!.isNotBlank() && !pingResult!!.contains("Failed") && !pingResult!!.startsWith("Error") -> {
            val parsedTime = try {
                val ping = AppJson.decodeFromString<PingResult>(pingResult!!)
                if (!ping.err.isNullOrEmpty()) {
                    null
                } else {
                    val sec = ping.latencySeconds
                    if (sec != null && sec > 0) {
                        "${(sec * 1000).toInt()} ms"
                    } else {
                        val ms = ping.latencyMs
                        if (ms != null && ms > 0) "${ms.toInt()} ms" else null
                    }
                }
            } catch (e: Exception) {
                """\b\d+(?:\.\d+)?\s*ms\b""".toRegex().find(pingResult!!)?.value
            }
            if (parsedTime != null) stringResource(R.string.peer_ping_result, parsedTime)
            else stringResource(R.string.peer_ping_failed)
        }
        else -> stringResource(R.string.peer_ping_failed)
    }

    // Strings resolved in the parent composition — see wrapContextWithLocale().
    val strPeerDetailsPrev = stringResource(R.string.peer_details_prev)
    val strPeerDetailsNext = stringResource(R.string.peer_details_next)
    val strPeerSendFile = stringResource(R.string.peer_send_file)
    val strPeerPingSelf = stringResource(R.string.peer_ping_self)
    val strActionCopy = stringResource(R.string.action_copy)
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
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = strPeerDetailsPrev)
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val (osIcon, osColor) = getOsVisuals(peer.os)
                    val statusColor = if (peer.online == true) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(osIcon, null, modifier = Modifier.size(16.dp), tint = osColor)
                        Spacer(Modifier.width(8.dp))
                        // Weighted, or a long node name pushes the status dot out of the chip.
                        Text(
                            peer.getDisplayName(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    }
                }
                
                if (onNextPeer != null) {
                    IconButton(onClick = onNextPeer) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = strPeerDetailsNext)
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
                            val targetIp = peer.getPrimaryIp()
                            val out = try {
                                val res = Appctr.pingTarget(targetIp, "disco")
                                if (res.isNotBlank() && !res.startsWith("Error")) res
                                else Appctr.runTailscaleCmd("ping $targetIp")
                            } catch (e: Exception) { "Error" }
                            val pong = out.split("\n").find { it.contains("pong from") || it.contains("LatencyMs") } ?: out.ifBlank { "Failed" }
                            withContext(Dispatchers.Main) { pingResult = pong.trim() }
                        }
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isSelf,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Bolt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isSelf) strPeerPingSelf else pingText,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = onSendFileClick,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strPeerSendFile, fontWeight = FontWeight.SemiBold)
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
                                        contentDescription = strActionCopy,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(l, v))
                                        Toast.makeText(context, context.getString(R.string.copied_to_clipboard, l), Toast.LENGTH_SHORT).show()
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- FILES ---

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
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.action_delete)) }
            TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
            Button(onClick = onOpen, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.action_open)) }
        }
    }
}

@Composable
fun SentFileCard(entry: SentFileEntry) {
    val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        ListItem(headlineContent = { Text(entry.name, fontWeight = FontWeight.Medium) }, supportingContent = { Text(stringResource(R.string.files_sent_to_format, entry.target, dateStr)) },
            leadingContent = { Icon(Icons.AutoMirrored.Filled.Outbound, null, tint = MaterialTheme.colorScheme.primary) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
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
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFF607D8B)
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
        var f = File(file.Path)
        // The FileProvider only covers files/taildrop/. A Root Mode daemon
        // started by the boot script keeps writing into the pre-4.0 directory
        // until the app hands it the new one, so a path from outside is
        // migrated and re-resolved instead of failing to open.
        val root = File(context.filesDir, "taildrop").absolutePath + File.separator
        if (!f.absolutePath.startsWith(root)) {
            val accountId = AccountManager.getActiveAccount(context).id
            TaildropPaths.migrate(context, accountId)
            val moved = File(TaildropPaths.dir(context, accountId), f.name)
            if (moved.exists()) f = moved
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }, context.getString(R.string.files_open_file_chooser)))
    } catch (e: Exception) { Toast.makeText(context, context.getString(R.string.files_error_cant_open, e.message), Toast.LENGTH_SHORT).show() }
}

fun highlightLogMessage(
    timestamp: String,
    category: String,
    message: String,
    defaultColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        withStyle(style = SpanStyle(color = Color(0xFF757575))) {
            append(timestamp)
            append(" ")
        }

        val catColor = when (category) {
            "ERROR" -> Color(0xFFEF5350)
            "CORE" -> Color(0xFF42A5F5)
            "TAILSCALE" -> Color(0xFF66BB6A)
            else -> Color(0xFFFFA726)
        }
        withStyle(style = SpanStyle(color = catColor, fontWeight = FontWeight.Bold)) {
            append("[")
            append(category)
            append("] ")
        }

        val ipRegex = """\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?\b""".toRegex()
        val ipv6Regex = """\b([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b""".toRegex()
        val keyValueRegex = """\b([a-zA-Z0-9_\-]+)=([^\s]+)\b""".toRegex()
        
        val successKeywords = setOf("success", "successful", "ok", "online", "active", "connected", "working", "available", "healthy")
        val errorKeywords = setOf("error", "failed", "fail", "blocked", "offline", "unavailable", "unreachable", "exception", "panic", "warning", "warn")

        val text = message
        var lastIdx = 0
        
        val matches = (ipRegex.findAll(text) + ipv6Regex.findAll(text) + keyValueRegex.findAll(text))
            .sortedBy { it.range.first }
            .toList()

        val nonOverlappingMatches = mutableListOf<MatchResult>()
        for (match in matches) {
            if (nonOverlappingMatches.isEmpty() || match.range.first >= nonOverlappingMatches.last().range.last + 1) {
                nonOverlappingMatches.add(match)
            }
        }

        for (match in nonOverlappingMatches) {
            val start = match.range.first
            val end = match.range.last + 1
            
            appendWithKeywords(text.substring(lastIdx, start), defaultColor, successKeywords, errorKeywords)
            
            val matchText = match.value
            if (ipRegex.matches(matchText) || ipv6Regex.matches(matchText)) {
                withStyle(style = SpanStyle(color = Color(0xFF80DEEA), fontWeight = FontWeight.Medium)) {
                    append(matchText)
                }
            } else {
                val parts = matchText.split("=", limit = 2)
                if (parts.size == 2) {
                    withStyle(style = SpanStyle(color = Color(0xFFFFCC80))) {
                        append(parts[0])
                        append("=")
                    }
                    val valText = parts[1]
                    val valColor = when {
                        valText.lowercase() in successKeywords -> Color(0xFFA5D6A7)
                        valText.lowercase() in errorKeywords -> Color(0xFFEF9A9A)
                        valText.all { it.isDigit() || it == '.' || it == ':' || it == 'm' || it == 's' } -> Color(0xFFB39DDB)
                        else -> Color(0xFFEEEEEE)
                    }
                    withStyle(style = SpanStyle(color = valColor)) {
                        append(valText)
                    }
                } else {
                    append(matchText)
                }
            }
            lastIdx = end
        }
        
        if (lastIdx < text.length) {
            appendWithKeywords(text.substring(lastIdx), defaultColor, successKeywords, errorKeywords)
        }
    }
}

private fun AnnotatedString.Builder.appendWithKeywords(
    text: String,
    defaultColor: Color,
    successKeywords: Set<String>,
    errorKeywords: Set<String>
) {
    val wordRegex = """\b[a-zA-Z_]+\b""".toRegex()
    var lastIdx = 0
    val matches = wordRegex.findAll(text).toList()
    
    for (match in matches) {
        val start = match.range.first
        val end = match.range.last + 1
        
        if (start > lastIdx) {
            withStyle(style = SpanStyle(color = defaultColor)) {
                append(text.substring(lastIdx, start))
            }
        }
        
        val word = match.value
        val lowerWord = word.lowercase()
        val wordColor = when {
            lowerWord in successKeywords -> Color(0xFF81C784)
            lowerWord in errorKeywords -> Color(0xFFE57373)
            else -> defaultColor
        }
        withStyle(style = SpanStyle(color = wordColor, fontWeight = if (wordColor != defaultColor) FontWeight.Bold else FontWeight.Normal)) {
            append(word)
        }
        lastIdx = end
    }
    
    if (lastIdx < text.length) {
        withStyle(style = SpanStyle(color = defaultColor)) {
            append(text.substring(lastIdx))
        }
    }
}
