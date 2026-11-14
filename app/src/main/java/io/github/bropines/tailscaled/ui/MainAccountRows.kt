package io.github.bropines.tailscaled.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/*
 * The rows of the main screen's account switcher (the sheet in MainScreen, MainActivity.kt):
 * one account with what is known of it, its picture or initial, and the download that caches
 * the picture the tailnet gives.
 */

fun downloadAndCacheAvatar(context: Context, accountId: String, urlStr: String) {
    try {
        val url = java.net.URL(urlStr)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.doInput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.connect()
        val input = connection.inputStream
        val avatarsDir = java.io.File(context.filesDir, "avatars").apply { mkdirs() }
        val targetFile = java.io.File(avatarsDir, "$accountId.png")
        val output = java.io.FileOutputStream(targetFile)
        input.use { inStream ->
            output.use { outStream ->
                inStream.copyTo(outStream)
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("AvatarSync", "Failed to download avatar: ${e.message}")
    }
}

/**
 * One account in the switcher. The active one carries what its node is doing
 * right now; the others describe themselves from their own preferences, which
 * is all that can be known without a daemon to ask: where they log in, whether
 * they have ever registered, and the two things decided before the first start.
 */
@Composable
internal fun AccountRow(
    account: TailscaleAccount,
    /** Null until the sheet has read them (off the main thread): the line under the name waits. */
    facts: AccountManager.AccountFacts?,
    active: Boolean,
    liveLine: String?,
    editing: Boolean,
    signedInLabel: String,
    neverSignedInLabel: String,
    notConnectedLabel: String,
    onClick: () -> Unit,
    onRename: () -> Unit,
    primary: Boolean,
    onMakePrimary: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val subtitle = when {
        active && !liveLine.isNullOrEmpty() -> liveLine
        // Empty, not absent: the row keeps its height while the facts are read.
        facts == null -> ""
        active -> listOf(facts.loginServer, notConnectedLabel).joinToString(" · ")
        else -> listOf(
            facts.loginServer,
            if (facts.registered) signedInLabel else neverSignedInLabel
        ).joinToString(" · ")
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shapedClickable(MaterialTheme.shapes.medium, enabled = !editing, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AccountAvatar(account, active)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // Glyphs, not words: an auth key means this account logs in on
                    // its own, the robot means it registers as an Android device.
                    if (facts?.hasAuthKey == true) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = context.getString(R.string.accounts_badge_authkey),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    if (facts?.honestOs == true) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Android,
                            contentDescription = context.getString(R.string.accounts_badge_honest),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (editing) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The main account wears its star; any other can take it. The star is a
                    // button on both, so the remote's way along the row is the same on each.
                    FilledTonalIconButton(
                        onClick = { if (!primary) onMakePrimary() },
                        modifier = Modifier.size(38.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = if (primary) IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) else IconButtonDefaults.filledTonalIconButtonColors()
                    ) {
                        Icon(
                            if (primary) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = context.getString(if (primary) R.string.accounts_primary else R.string.accounts_make_primary),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onRename,
                        modifier = Modifier.size(38.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = context.getString(R.string.action_rename),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    if (onDelete != null) {
                        FilledTonalIconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(38.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = context.getString(R.string.action_delete),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            } else if (active) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** The account's picture, or its initial when the tailnet has not given us one. */
@Composable
private fun AccountAvatar(account: TailscaleAccount, active: Boolean) {
    val context = LocalContext.current
    val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { 38.dp.roundToPx() }
    // Read and decoded off the main thread, at about the size it is drawn (avatarSampleSize):
    // the tailnet's pictures are several hundred pixels for a 38 dp circle, and decoding them
    // whole in composition was file I/O and an image decode per row in the frame that opened
    // the sheet. The initial stands in until it is there.
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, account.id, account.avatarUrl, targetPx) {
        value = withContext(Dispatchers.IO) {
            val f = java.io.File(context.filesDir, "avatars/${account.id}.png")
            if (!f.exists()) return@withContext null
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = avatarSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
                }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { image ->
        androidx.compose.foundation.Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
        return
    }
    val letter = account.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
