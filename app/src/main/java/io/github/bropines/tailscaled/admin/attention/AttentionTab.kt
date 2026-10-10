package io.github.bropines.tailscaled.admin.attention

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.LoadingIndicatorCompat
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.console.WriteBlock
import io.github.bropines.tailscaled.admin.notify.AttentionChecks
import io.github.bropines.tailscaled.admin.notify.AttentionNotifier
import io.github.bropines.tailscaled.admin.ConsoleLinks
import io.github.bropines.tailscaled.ui.CardColumns
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet
import io.github.bropines.tailscaled.ui.ReadableContentWidth
import io.github.bropines.tailscaled.ui.rememberWindowLayout
import kotlinx.coroutines.flow.MutableStateFlow

/** A fixed "now" for previews, whose expiry lines must not drift with the calendar. */
val LocalAttentionNow = staticCompositionLocalOf<Long?> { null }

/** What the rows can do; each one ends at the console's safety pipeline or opens something. */
class AttentionActions(
    val approveDevice: (ApiDevice) -> Unit = {},
    val rejectDevice: (ApiDevice) -> Unit = {},
    val approveUser: (ApiUser) -> Unit = {},
    val rejectUser: (ApiUser) -> Unit = {},
    val approveRoutes: (AttentionItem) -> Unit = {},
    val openDevice: (ApiDevice) -> Unit = {},
    val openUser: (ApiUser) -> Unit = {},
    val openKeys: () -> Unit = {},
    val replaceCredential: () -> Unit = {},
    val setChecks: (Boolean, List<AttentionItem>) -> Unit = { _, _ -> },
    val setInterval: (Int) -> Unit = {},
    val allowNotifications: () -> Unit = {},
)

/**
 * The console's first tab, wired to the session: [vm] is null in previews, which then draw
 * [demo]. The sheets it opens belong to the dashboard ([onOpenDevice] and the rest).
 */
@Composable
fun AttentionTab(
    state: ConsoleState,
    vm: AdminConsoleViewModel?,
    onRetry: () -> Unit,
    onOpenDevice: (ApiDevice) -> Unit,
    onOpenUser: (ApiUser) -> Unit,
    onOpenKeys: () -> Unit,
    onReplaceCredential: () -> Unit,
    demo: AttentionUiState? = null,
) {
    val ctx = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val session = vm?.attention
    val flow = session?.state ?: remember { MutableStateFlow(demo ?: AttentionUiState()) }
    val attention by flow.collectAsState()

    if (session != null && !inPreview) {
        // Back from the system's notification settings: say what they are now.
        LifecycleResumeEffect(session) {
            session.checkNotifications()
            onPauseOrDispose { }
        }
    }
    val askPermission = if (!inPreview && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { session?.checkNotifications() }
    } else null
    fun permissionMissing() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    val actions = remember(session, onOpenDevice, onOpenUser, onOpenKeys, onReplaceCredential) {
        AttentionActions(
            approveDevice = { session?.approveDevice(it) },
            rejectDevice = { session?.rejectDevice(it) },
            approveUser = { session?.approveUser(it) },
            rejectUser = { session?.rejectUser(it) },
            approveRoutes = { session?.approveRoutes(it) },
            openDevice = onOpenDevice,
            openUser = onOpenUser,
            openKeys = onOpenKeys,
            replaceCredential = onReplaceCredential,
            setChecks = { on, items ->
                session?.setChecks(on, items)
                // Asked once, when the person turns it on; after a refusal the settings are the way.
                if (on && permissionMissing()) askPermission?.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            setInterval = { session?.setInterval(it) },
            allowNotifications = { if (!inPreview) runCatching { ctx.startActivity(AttentionNotifier.settingsIntent(ctx)) } },
        )
    }
    val clock = remember(state.devices.loadedAt, state.users.loadedAt, state.keys.loadedAt, attention.routesReadAt) { System.currentTimeMillis() }
    AttentionTabContent(state, attention, LocalAttentionNow.current ?: clock, onRetry, actions)
}

/** The list itself, from plain state: what the preview draws and the tab shows. */
@Composable
fun AttentionTabContent(
    state: ConsoleState,
    attention: AttentionUiState,
    now: Long,
    onRetry: () -> Unit,
    actions: AttentionActions,
) {
    val ctx = LocalContext.current
    val input = remember(state, attention) { AttentionSession.input(state, attention) }
    val items = remember(input, now) { Attention.compute(input, now) }
    val sections = remember(items) { items.groupBy { it.kind.section }.toSortedMap(compareBy { it.ordinal }) }
    val devicesRead = state.devices.value != null
    val usersReadable = AttentionSession.readable(state, BackendFeature.USERS, AdminArea.USERS)
    val keysReadable = AttentionSession.readable(state, BackendFeature.KEYS, AdminArea.AUTH_KEYS)
    val notChecked = listOfNotNull(
        ctx.getString(R.string.admin_attention_source_users).takeIf { !usersReadable || state.users.error is AdminApiException.Forbidden },
        ctx.getString(R.string.admin_attention_source_keys).takeIf { !keysReadable || state.keys.error is AdminApiException.Forbidden },
    )

    // From a medium window up the sections stand side by side, each a column of its rows.
    if (rememberWindowLayout().multiColumn) {
        AttentionColumns(state, attention, now, onRetry, actions, items, sections, devicesRead, usersReadable, keysReadable, notChecked)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") {
            if (items.isNotEmpty()) Text(
                ctx.resources.getQuantityString(R.plurals.admin_attention_count, items.size, items.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
            ) else Spacer(Modifier.height(4.dp))
        }
        item(key = "problems") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // A refused credential is the list's own first row, with the way to fix it.
                if (state.devices.error !is AdminApiException.Unauthorized) LoadProblems(state.devices, onRetry)
                if (usersReadable) Problems(state.users, onRetry)
                if (keysReadable) Problems(state.keys, onRetry)
            }
        }
        when {
            items.isEmpty() && !devicesRead && state.devices.error == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
            }
            items.isEmpty() && devicesRead -> item(key = "calm") { CalmCard() }
            else -> sections.forEach { (section, list) ->
                item(key = "section-${section.name}") {
                    Text(
                        AttentionText.section(ctx, section),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                items(list, key = { it.id }) { item -> AttentionRow(item, now, writeBlocked(ctx, state, item.kind.area), actions) }
            }
        }
        if (attention.scanning || attention.scanTotal > attention.scanned || notChecked.isNotEmpty()) item(key = "notes") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                if (attention.scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(ctx.getString(R.string.admin_attention_scanning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (attention.scanTotal > attention.scanned) {
                    HelpText(ctx.resources.getQuantityString(R.plurals.admin_attention_scan_capped, attention.scanTotal, attention.scanned, attention.scanTotal))
                }
                if (notChecked.isNotEmpty()) HelpText(ctx.getString(R.string.admin_attention_not_checked, notChecked.joinToString(", ")))
            }
        }
        item(key = "checks") {
            BackgroundChecksCard(
                checks = attention.checks,
                readOnlyProfile = state.active?.readOnly == true,
                notificationsAllowed = attention.notificationsAllowed,
                onToggle = { actions.setChecks(it, items) },
                onInterval = actions.setInterval,
                onAllow = actions.allowNotifications,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * [AttentionTabContent] on a window wider than a phone: the same rows, a section to a column
 * block in as many columns as fit (CardColumns), the background checks as one more block —
 * instead of one column of rows the width of a tablet with half of it empty below.
 */
@Composable
private fun AttentionColumns(
    state: ConsoleState,
    attention: AttentionUiState,
    now: Long,
    onRetry: () -> Unit,
    actions: AttentionActions,
    items: List<AttentionItem>,
    sections: Map<AttentionSection, List<AttentionItem>>,
    devicesRead: Boolean,
    usersReadable: Boolean,
    keysReadable: Boolean,
    notChecked: List<String>,
) {
    val ctx = LocalContext.current
    val checks: @Composable (Modifier) -> Unit = { modifier ->
        BackgroundChecksCard(
            checks = attention.checks,
            readOnlyProfile = state.active?.readOnly == true,
            notificationsAllowed = attention.notificationsAllowed,
            onToggle = { actions.setChecks(it, items) },
            onInterval = actions.setInterval,
            onAllow = actions.allowNotifications,
            modifier = modifier,
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (items.isNotEmpty()) Text(
            ctx.resources.getQuantityString(R.plurals.admin_attention_count, items.size, items.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        ) else Spacer(Modifier.height(4.dp))
        if (state.devices.error !is AdminApiException.Unauthorized) LoadProblems(state.devices, onRetry)
        if (usersReadable) Problems(state.users, onRetry)
        if (keysReadable) Problems(state.keys, onRetry)
        when {
            items.isEmpty() && !devicesRead && state.devices.error == null ->
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
            // Nothing to do: the calm message, and the checks that will say when there is, held
            // to a readable width in the middle rather than a third of the window at its edge.
            items.isEmpty() && devicesRead -> Column(Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = ReadableContentWidth)) {
                CalmCard()
                checks(Modifier)
            }
            else -> CardColumns(minColumnWidth = 320.dp) {
                sections.forEach { (section, list) ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            AttentionText.section(ctx, section),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                        )
                        list.forEach { item -> AttentionRow(item, now, writeBlocked(ctx, state, item.kind.area), actions) }
                    }
                }
                checks(Modifier.padding(top = 8.dp))
            }
        }
        if (attention.scanning || attention.scanTotal > attention.scanned || notChecked.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                if (attention.scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(ctx.getString(R.string.admin_attention_scanning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (attention.scanTotal > attention.scanned) {
                    HelpText(ctx.resources.getQuantityString(R.plurals.admin_attention_scan_capped, attention.scanTotal, attention.scanned, attention.scanTotal))
                }
                if (notChecked.isNotEmpty()) HelpText(ctx.getString(R.string.admin_attention_not_checked, notChecked.joinToString(", ")))
            }
        }
    }
}

/**
 * A list's load problem, except a 403 — that area is simply not checked, as the note says —
 * and a 401, which the credential's own row reports.
 */
@Composable
private fun <T> Problems(state: Loadable<T>, onRetry: () -> Unit) {
    if (state.error is AdminApiException.Forbidden || state.error is AdminApiException.Unauthorized) return
    LoadProblems(state, onRetry)
}

/** Why a row's change is not offered, in the words the console's banners use; null when it is. */
private fun writeBlocked(ctx: android.content.Context, state: ConsoleState, area: AdminArea?): String? {
    if (area == null) return null
    return when {
        state.writeBlock == WriteBlock.READ_ONLY_PROFILE -> ctx.getString(R.string.admin2_readonly_profile_title)
        state.writeBlock == WriteBlock.NO_SCREEN_LOCK -> ctx.getString(R.string.admin2_readonly_no_lock_title)
        !state.canWrite(area) -> ctx.getString(R.string.admin2_readonly_scope_title)
        else -> null
    }
}

@Composable
private fun CalmCard() {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.TaskAlt, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(ctx.getString(R.string.admin_attention_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            ctx.getString(R.string.admin_attention_empty_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private class Look(val icon: ImageVector, val urgent: Boolean)

private fun lookOf(item: AttentionItem, now: Long): Look = when (item.kind) {
    AttentionKind.DEVICE_APPROVAL -> Look(Icons.Default.HowToReg, false)
    AttentionKind.USER_APPROVAL -> Look(Icons.Default.PersonAdd, false)
    AttentionKind.CREDENTIAL_REFUSED -> Look(Icons.Default.KeyOff, true)
    AttentionKind.CREDENTIAL_EXPIRING -> Look(Icons.Default.Key, false)
    AttentionKind.DEVICE_KEY_EXPIRING ->
        if ((item.expiresAt ?: Long.MAX_VALUE) <= now) Look(Icons.Default.TimerOff, true) else Look(Icons.Default.Timer, false)
    AttentionKind.AUTH_KEY_EXPIRING -> Look(Icons.Default.VpnKey, false)
    AttentionKind.TAILNET_LOCK_ERROR -> Look(Icons.Default.GppBad, true)
    AttentionKind.MULTIPLE_CONNECTIONS -> Look(Icons.Default.ContentCopy, true)
    AttentionKind.ROUTES_PENDING -> Look(Icons.AutoMirrored.Filled.AltRoute, false)
    AttentionKind.UPDATE_AVAILABLE -> Look(Icons.Default.SystemUpdate, false)
}

/**
 * One thing that needs attention: who or what, what is the matter, and its action — or why
 * the action is not offered. A tap on the row opens what it is about.
 */
@Composable
private fun AttentionRow(item: AttentionItem, now: Long, blocked: String?, actions: AttentionActions) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val look = lookOf(item, now)
    val device = item.device
    val user = item.user
    val open: (() -> Unit)? = when (item.kind) {
        AttentionKind.USER_APPROVAL -> user?.let { { actions.openUser(it) } }
        AttentionKind.AUTH_KEY_EXPIRING -> actions.openKeys
        AttentionKind.CREDENTIAL_EXPIRING, AttentionKind.CREDENTIAL_REFUSED -> actions.replaceCredential
        else -> device?.let { { actions.openDevice(it) } }
    }
    val (container, content) = if (look.urgent) scheme.errorContainer to scheme.onErrorContainer else scheme.secondaryContainer to scheme.onSecondaryContainer

    // A change gets its buttons under the text; opening something is one word at the end of the row.
    val writes = item.kind.area != null
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).then(if (open != null) Modifier.clickable(onClick = open) else Modifier),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = if (writes) 12.dp else 4.dp, top = 14.dp, bottom = if (writes) 8.dp else 14.dp)) {
            Row(verticalAlignment = if (writes) Alignment.Top else Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
                    Icon(look.icon, null, tint = content, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        AttentionText.name(ctx, item),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        AttentionText.what(ctx, item, now),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (look.urgent) scheme.error else scheme.onSurface,
                    )
                    AttentionText.context(ctx, item)?.let { more ->
                        when (item.kind) {
                            AttentionKind.MULTIPLE_CONNECTIONS, AttentionKind.TAILNET_LOCK_ERROR ->
                                HelpText(more, inClickableRow = open != null, modifier = Modifier.padding(top = 2.dp))
                            else -> Text(
                                more,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = if (item.kind == AttentionKind.ROUTES_PENDING) FontFamily.Monospace else null,
                                color = scheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                if (!writes) OpenAction(item, actions, open)
            }
            if (writes) Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WriteActions(item, blocked, actions)
            }
        }
    }
}

/** The one action of a row that changes nothing itself: open the device, the keys, the profile. */
@Composable
private fun OpenAction(item: AttentionItem, actions: AttentionActions, open: (() -> Unit)?) {
    val ctx = LocalContext.current
    when (item.kind) {
        AttentionKind.CREDENTIAL_EXPIRING, AttentionKind.CREDENTIAL_REFUSED ->
            TextButton(onClick = actions.replaceCredential) { Text(ctx.getString(R.string.admin_attention_replace)) }
        AttentionKind.AUTH_KEY_EXPIRING ->
            TextButton(onClick = actions.openKeys) { Text(ctx.getString(R.string.admin_attention_open_keys)) }
        // The row opens the device; the button, the console's page with its Start update.
        AttentionKind.UPDATE_AVAILABLE -> item.device?.takeIf { !it.isShared }?.let(ConsoleLinks::machine)?.let { url ->
            TextButton(onClick = { ConsoleLinks.open(ctx, url) }) { Text(ctx.getString(R.string.admin_update_action)) }
        } ?: if (open != null) TextButton(onClick = open) { Text(ctx.getString(R.string.admin_attention_open)) } else Unit
        else -> if (open != null) TextButton(onClick = open) { Text(ctx.getString(R.string.admin_attention_open)) }
    }
}

/** Approve and Reject, or Approve for routes — or why they are not offered. */
@Composable
private fun WriteActions(item: AttentionItem, blocked: String?, actions: AttentionActions) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    if (blocked != null) {
        Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = scheme.outline)
        Text(blocked, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        return
    }
    when (item.kind) {
        AttentionKind.DEVICE_APPROVAL -> item.device?.let { d ->
            TextButton(onClick = { actions.rejectDevice(d) }, colors = ButtonDefaults.textButtonColors(contentColor = scheme.error)) {
                Text(ctx.getString(R.string.admin_attention_reject))
            }
            FilledTonalButton(onClick = { actions.approveDevice(d) }, shape = MaterialTheme.shapes.medium) { Text(ctx.getString(R.string.admin_attention_approve)) }
        }
        AttentionKind.USER_APPROVAL -> item.user?.let { u ->
            TextButton(onClick = { actions.rejectUser(u) }, colors = ButtonDefaults.textButtonColors(contentColor = scheme.error)) {
                Text(ctx.getString(R.string.admin_attention_reject))
            }
            FilledTonalButton(onClick = { actions.approveUser(u) }, shape = MaterialTheme.shapes.medium) { Text(ctx.getString(R.string.admin_attention_approve)) }
        }
        AttentionKind.ROUTES_PENDING ->
            FilledTonalButton(onClick = { actions.approveRoutes(item) }, shape = MaterialTheme.shapes.medium) { Text(ctx.getString(R.string.admin_attention_approve)) }
        else -> Unit
    }
}

/**
 * The profile's background check: the switch, how often, and what stands in the way of its
 * notifications. Strings are resolved here: the interval picker is a sheet, a window of its
 * own that would answer in the system language.
 */
@Composable
fun BackgroundChecksCard(
    checks: AttentionChecks,
    readOnlyProfile: Boolean,
    notificationsAllowed: Boolean,
    onToggle: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var picking by remember { mutableStateOf(false) }
    fun label(minutes: Int) = ctx.getString(
        when (minutes) {
            15 -> R.string.admin_attention_interval_15
            360 -> R.string.admin_attention_interval_360
            else -> R.string.admin_attention_interval_60
        }
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .toggleable(value = checks.enabled, role = Role.Switch, onValueChange = onToggle)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (checks.enabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff, null, tint = scheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ctx.getString(R.string.admin_attention_checks_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    HelpText(ctx.getString(R.string.admin_attention_checks_desc), inClickableRow = true)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = checks.enabled, onCheckedChange = null)
            }
            if (checks.enabled) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ctx.getString(R.string.admin_attention_checks_interval),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = 36.dp),
                    )
                    OutlinedButton(onClick = { picking = true }, shape = MaterialTheme.shapes.medium) {
                        Text(label(checks.intervalMinutes), maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                }
                if (readOnlyProfile) {
                    HelpText(ctx.getString(R.string.admin_attention_checks_read_only), modifier = Modifier.padding(start = 36.dp, bottom = 4.dp))
                }
                if (!notificationsAllowed) {
                    Row(Modifier.fillMaxWidth().padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ctx.getString(R.string.admin_attention_checks_notifications_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onAllow) { Text(ctx.getString(R.string.admin_attention_checks_allow)) }
                    }
                }
            }
        }
    }

    if (picking) {
        PickerSheet(
            title = ctx.getString(R.string.admin_attention_checks_interval),
            options = AttentionChecks.INTERVALS.map { PickerOption(it, label(it), Icons.Default.Schedule) },
            selected = checks.intervalMinutes,
            onPick = onInterval,
            onDismiss = { picking = false },
        )
    }
}
