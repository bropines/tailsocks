package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

/*
 * A received file in the Taildrop inbox, and the icon its extension picks.
 */

// --- FILES ---

/**
 * A received file in the Taildrop inbox. [sender] is the device it came from, when the
 * history knows it (files received before it was logged have none). [note] is one more
 * line about it — where it was saved, or that saving it automatically failed ([noteIsError]).
 * The date is left out while [TaildropFile.ModTime] is unknown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileCard(
    file: TaildropFile,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    sender: String? = null,
    note: String? = null,
    noteIsError: Boolean = false
) {
    val locale = LocalConfiguration.current.locales[0]
    val dateStr = remember(file.ModTime, locale) {
        file.ModTime.takeIf { it > 0 }?.let { SimpleDateFormat("d MMM, HH:mm", locale).format(Date(it * 1000)) }
    }
    val sizeStr = formatFileSize(file.Size)
    val ext = file.Name.substringAfterLast('.', "").lowercase()
    val from = sender?.let { stringResource(R.string.taildrop_from_format, it) }

    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FileIcon(ext)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(file.Name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(dateStr, sizeStr, from).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (noteIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
        // Wraps rather than overflows: three labels in Russian do not fit a 360dp phone.
        FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.action_delete)) }
            TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
            Button(onClick = onOpen, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.action_open)) }
        }
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
    Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).background(color.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
    }
}
