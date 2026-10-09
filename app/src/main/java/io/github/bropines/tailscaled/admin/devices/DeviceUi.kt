package io.github.bropines.tailscaled.admin.devices

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.StatusTag
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.ui.agoText
import io.github.bropines.tailscaled.ui.copyText
import io.github.bropines.tailscaled.ui.getOsVisuals
import io.github.bropines.tailscaled.ui.rememberFullSheetState
import kotlinx.coroutines.CoroutineScope
import java.text.DateFormat
import java.util.Date

/** The clock the previews run on: 2026-10-09 12:00 UTC, so "last seen" does not age between renders. */
internal const val PREVIEW_NOW = 1_791_547_200_000L

/** The caller's context, configuration and resources, to provide again inside a window of its own. */
internal class ParentLocale(val context: Context, val configuration: Configuration, val resources: Resources)

@Composable
internal fun parentLocale() = ParentLocale(LocalContext.current, LocalConfiguration.current, LocalResources.current)

/** [content] with [parent]'s context, configuration and resources: inside a dialog or a sheet. */
@Composable
internal fun WithLocale(parent: ParentLocale, content: @Composable () -> Unit) = CompositionLocalProvider(
    LocalContext provides parent.context,
    LocalConfiguration provides parent.configuration,
    LocalResources provides parent.resources,
    content = content,
)

/**
 * A full-height sheet whose content reads strings in the app's language: a sheet is a window of
 * its own, and on some ROMs that window answers in the system language (see wrapContextWithLocale).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val parent = parentLocale()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        WithLocale(parent, content)
    }
}

/** The OS icon in a tinted circle. */
@Composable
internal fun OsAvatar(os: String?, size: Dp = 40.dp) {
    val (icon, color) = getOsVisuals(os)
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size / 2))
    }
}

/** "Online" or "Last seen 3 h ago", in words with a filled or hollow dot — never the colour alone. */
internal fun presence(ctx: Context, d: ApiDevice, now: Long): String = when {
    d.isOnline -> ctx.getString(R.string.admin2_device_online)
    else -> DeviceQueries.instant(d.lastSeen)?.let { ctx.getString(R.string.admin2_device_last_seen, agoText(ctx, it, now)) }
        ?: ctx.getString(R.string.admin_dev_never_seen)
}

@Composable
internal fun PresenceLine(d: ApiDevice, now: Long, suffix: String? = null, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (d.isOnline) Icons.Default.Circle else Icons.Default.RadioButtonUnchecked, null,
            Modifier.size(10.dp),
            tint = if (d.isOnline) scheme.primary else scheme.outline,
        )
        Spacer(Modifier.width(5.dp))
        val text = presence(ctx, d, now)
        Text(
            if (suffix.isNullOrBlank()) text else ctx.getString(R.string.admin_dev_status_line, text, suffix),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Linux 1.104.0": the system and the client's version, short. */
internal fun systemLine(d: ApiDevice): String? =
    listOfNotNull(DeviceQueries.osName(d.os), DeviceQueries.shortVersion(d.clientVersion)).joinToString(" ").ifBlank { null }

internal fun badgeLabel(ctx: Context, b: DeviceBadge): String = when (b.kind) {
    BadgeKind.THIS_PHONE -> ctx.getString(R.string.admin2_device_this_phone)
    BadgeKind.SHARED -> ctx.getString(R.string.admin2_device_shared)
    BadgeKind.NEEDS_APPROVAL -> ctx.getString(R.string.admin_dev_badge_needs_approval)
    BadgeKind.LOCK_ERROR -> ctx.getString(R.string.admin_dev_badge_lock_error)
    BadgeKind.MULTIPLE_CONNECTIONS -> ctx.getString(R.string.admin_dev_badge_multiple)
    BadgeKind.KEY_EXPIRED -> ctx.getString(R.string.admin_dev_badge_key_expired)
    BadgeKind.KEY_EXPIRING -> if (b.days <= 0) ctx.getString(R.string.admin_dev_badge_key_today)
    else ctx.resources.getQuantityString(R.plurals.admin_dev_badge_key_expiring, b.days, b.days)
    BadgeKind.ROUTES_PENDING -> ctx.getString(R.string.admin_dev_badge_routes_pending)
    BadgeKind.EXIT_NODE -> ctx.getString(R.string.admin_dev_badge_exit)
    BadgeKind.SUBNET_ROUTER -> ctx.getString(R.string.admin_dev_badge_router)
    BadgeKind.UPDATE -> ctx.getString(R.string.admin_dev_badge_update)
}

private fun badgeIcon(kind: BadgeKind): ImageVector = when (kind) {
    BadgeKind.THIS_PHONE -> Icons.Default.PhoneAndroid
    BadgeKind.SHARED -> Icons.Default.Share
    BadgeKind.NEEDS_APPROVAL -> Icons.Default.HowToReg
    BadgeKind.LOCK_ERROR, BadgeKind.MULTIPLE_CONNECTIONS -> Icons.Default.Warning
    BadgeKind.KEY_EXPIRED -> Icons.Default.TimerOff
    BadgeKind.KEY_EXPIRING -> Icons.Default.HourglassBottom
    BadgeKind.ROUTES_PENDING -> Icons.AutoMirrored.Filled.AltRoute
    BadgeKind.EXIT_NODE -> Icons.Default.Public
    BadgeKind.SUBNET_ROUTER -> Icons.Default.Lan
    BadgeKind.UPDATE -> Icons.Default.SystemUpdate
}

/** A badge: an icon and its words, on a colour that only adds to them. */
@Composable
internal fun BadgeChip(badge: DeviceBadge) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (badge.kind) {
        BadgeKind.THIS_PHONE -> scheme.primaryContainer to scheme.onPrimaryContainer
        BadgeKind.LOCK_ERROR, BadgeKind.MULTIPLE_CONNECTIONS, BadgeKind.KEY_EXPIRED -> scheme.errorContainer to scheme.onErrorContainer
        BadgeKind.NEEDS_APPROVAL, BadgeKind.KEY_EXPIRING, BadgeKind.ROUTES_PENDING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        BadgeKind.SHARED, BadgeKind.EXIT_NODE, BadgeKind.SUBNET_ROUTER -> scheme.secondaryContainer to scheme.onSecondaryContainer
        BadgeKind.UPDATE -> scheme.surfaceContainerHighest to scheme.onSurface
    }
    StatusTag(badgeLabel(ctx, badge), container, content, badgeIcon(badge.kind))
}

/** A tag as a chip: its name without the "tag:" prefix, after a label icon that says what it is. */
@Composable
internal fun TagChip(tag: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.small, color = scheme.surfaceContainerHighest, contentColor = scheme.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
            Text(tag.removePrefix("tag:"), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** A date in the language of [ctx], medium length, with the time. */
internal fun dateText(ctx: Context, millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, ctx.resources.configuration.locales[0]).format(Date(millis))

/** "Oct 2, 2026 14:05 (7 days ago)". */
internal fun whenText(ctx: Context, millis: Long, now: Long): String =
    ctx.getString(R.string.admin_dev_when, dateText(ctx, millis), agoText(ctx, millis, now))

/** Puts [text] on the clipboard; below Android 13, which shows its own confirmation, a toast says so. */
internal fun copyToClipboard(ctx: Context, clipboard: Clipboard, scope: CoroutineScope, label: String, text: String) {
    clipboard.copyText(scope, text)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(ctx, ctx.getString(R.string.admin_dev_copied, label), Toast.LENGTH_SHORT).show()
    }
}

/** The copy icon for a row that copies when tapped. */
@Composable
internal fun CopyMark() {
    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
}
