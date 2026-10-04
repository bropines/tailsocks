package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R

import io.github.bropines.tailscaled.core.*

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/**
 * Optional permissions: the ones the app works without, but works better with.
 *
 * Also owns the "the system stopped us in the background" ask. The state lives in
 * preferences rather than in an activity field: the ask is raised while the
 * outage is still visible (before the service is restarted) but has to survive a
 * language change, a rotation and process death, all of which happen long after
 * the condition that raised it has gone.
 */
object OptionalPermissions {

    /** "Don't ask again" — permanent; nothing in the app ever clears it. */
    private const val KEY_AUTOSTART_ASK_NEVER = "autostart_ask_never"

    /** An outage was seen and the user has not answered the ask yet. */
    private const val KEY_AUTOSTART_ASK_PENDING = "autostart_ask_pending"

    /** Blocks a second ask in this process once the user has answered one. */
    @Volatile
    private var answeredInThisProcess = false

    /** Whether the modal should be on screen right now. */
    fun isAutostartAskPending(context: Context): Boolean =
        !GlobalSettings.getBoolean(context, KEY_AUTOSTART_ASK_NEVER, false) &&
            GlobalSettings.getBoolean(context, KEY_AUTOSTART_ASK_PENDING, false)

    /**
     * Records an outage, at most once per outage and never after "Don't ask
     * again". Raising it twice for the same outage is prevented by the pending
     * flag itself; raising it again after the user answered, by the process
     * guard.
     */
    fun noteOutage(context: Context, outage: Boolean) {
        if (!outage || answeredInThisProcess) return
        if (GlobalSettings.getBoolean(context, KEY_AUTOSTART_ASK_NEVER, false)) return
        if (GlobalSettings.getBoolean(context, KEY_AUTOSTART_ASK_PENDING, false)) return
        GlobalSettings.setBoolean(context, KEY_AUTOSTART_ASK_PENDING, true)
        android.util.Log.i("OptionalPermissions", "Autostart ask raised")
    }

    /**
     * The user acted on the ask — granted, postponed or muted it. The outage is
     * settled either way, so the matching notification goes with it.
     */
    fun answerAutostartAsk(context: Context, neverAgain: Boolean = false) {
        answeredInThisProcess = true
        GlobalSettings.setBoolean(context, KEY_AUTOSTART_ASK_PENDING, false)
        if (neverAgain) GlobalSettings.setBoolean(context, KEY_AUTOSTART_ASK_NEVER, true)
        ServiceWatchdog.clearRevivalRefused(context)
    }
}

/** What the system can tell us about one optional permission. */
private enum class PermState { GRANTED, DENIED, UNKNOWN, NOT_APPLICABLE }

/**
 * Where a row goes on the screen: what is missing first — the reason to open
 * the screen at all — then what nobody can vouch for, then what is settled.
 */
private val PermState.urgency: Int
    get() = when (this) {
        PermState.DENIED -> 0
        PermState.UNKNOWN -> 1
        PermState.GRANTED, PermState.NOT_APPLICABLE -> 2
    }

/** One row: what it is, why it helps, what the system says, where to change it. */
private class PermEntry(
    /** Stable across reorders, so a row keeps its own unfolded note when it moves. */
    val id: String,
    val title: String,
    val reason: String,
    val icon: ImageVector,
    val state: PermState,
    val open: () -> Unit
)

/**
 * The ask itself: a modal, so it costs no dashboard space, and three answers, so
 * "no" can be said once and for good.
 */
@Composable
fun AutostartAskDialog(onAnswered: () -> Unit) {
    val context = LocalContext.current
    // Checked once, honoured for every answer: "Later" with it ticked is the
    // permanent no, and so is granting, which needs no further asking anyway.
    var neverAgain by remember { mutableStateOf(false) }
    fun answer() {
        OptionalPermissions.answerAutostartAsk(context, neverAgain)
        onAnswered()
    }
    // Strings resolved in the parent composition — see wrapContextWithLocale().
    val strPermAskTitle = stringResource(R.string.perm_ask_title)
    val strPermAskText = stringResource(R.string.perm_ask_text)
    val strPermAskNever = stringResource(R.string.perm_ask_never)
    val strPermAskAll = stringResource(R.string.perm_ask_all)
    val strPermAskGrant = stringResource(R.string.perm_ask_grant)
    val strPermAskLater = stringResource(R.string.perm_ask_later)
    AlertDialog(
        onDismissRequest = { answer() },
        icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null) },
        title = { Text(strPermAskTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strPermAskText, style = MaterialTheme.typography.bodyMedium)
                // The whole row toggles, so the label is a target too, not just
                // the 20dp box.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { neverAgain = !neverAgain }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = neverAgain, onCheckedChange = { neverAgain = it })
                    Text(
                        strPermAskNever,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                // Leaves for another screen of ours, so a chevron rather than the
                // outward arrow the system-settings action carries.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable {
                            answer()
                            context.startActivity(Intent(context, PermissionsActivity::class.java))
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        strPermAskAll,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        confirmButton = {
            // Filled, because it is the answer we want, and marked as leaving the
            // app: it lands in the system's own autostart screen.
            Button(onClick = {
                answer()
                openAutostartSettings(context)
            }) {
                Text(strPermAskGrant)
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(16.dp)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { answer() }) {
                Text(strPermAskLater)
            }
        }
    )
}

class PermissionsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                PermissionsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Every one of these screens is a system screen, so the only moment the
    // answers can have changed is when the user comes back from one.
    var refreshTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Each of these asks a system service, and the preview renderer has none
    // of them (see MainScreen): there every row shows as unknown.
    val inPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    fun probe(read: () -> PermState) = if (inPreview) PermState.UNKNOWN else read()
    val alarmsState = remember(refreshTick) { probe { exactAlarmsState(context) } }
    val batteryState = remember(refreshTick) { probe { batteryOptimisationState(context) } }
    val notificationsState = remember(refreshTick) { probe { notificationsState(context) } }
    val installState = remember(refreshTick) { probe { installUnknownAppsState(context) } }

    // Declared in a fixed order and shown by urgency: a row granted on the system
    // screen drops to the end on the way back, and what is left to do stays on top.
    // The sort is stable, so rows with the same answer keep this order.
    val entries = listOf(
        PermEntry(
            id = "autostart",
            title = stringResource(R.string.perm_autostart_title),
            reason = stringResource(R.string.perm_autostart_reason),
            icon = Icons.Default.PowerSettingsNew,
            // The OEM screens expose no API at all, so claiming either answer
            // would be a guess; say so instead.
            state = PermState.UNKNOWN
        ) { openAutostartSettings(context) },
        PermEntry(
            id = "alarms",
            title = stringResource(R.string.perm_alarms_title),
            reason = stringResource(R.string.perm_alarms_reason),
            icon = Icons.Default.Alarm,
            state = alarmsState
        ) {
            openFirst(
                context,
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                ),
                appDetails(context)
            )
        },
        PermEntry(
            id = "battery",
            title = stringResource(R.string.perm_battery_title),
            reason = stringResource(R.string.perm_battery_reason),
            icon = Icons.Default.BatteryAlert,
            state = batteryState
        ) {
            openFirst(
                context,
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                appDetails(context)
            )
        },
        PermEntry(
            id = "notifications",
            title = stringResource(R.string.perm_notifications_title),
            reason = stringResource(R.string.perm_notifications_reason),
            icon = Icons.Default.Notifications,
            state = notificationsState
        ) {
            openFirst(
                context,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                appDetails(context)
            )
        },
        PermEntry(
            id = "install",
            title = stringResource(R.string.perm_install_title),
            reason = stringResource(R.string.perm_install_reason),
            icon = Icons.Default.SystemUpdate,
            state = installState
        ) {
            openFirst(
                context,
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ),
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
                appDetails(context)
            )
        }
    ).sortedBy { it.state.urgency }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.perm_title),
                onBack = onBack
            )
        }
    ) { padding ->
        // Held to a readable width on a tablet; see ReadableWidth.
        ReadableWidth {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.perm_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            entries.forEach { entry ->
                key(entry.id) { PermissionRow(entry) }
            }

            Spacer(Modifier.height(16.dp))
        }
        }
    }
}

/**
 * One permission, in two lines. What the system says about it is a glyph at the
 * row's end, so the answers read down one column; it has words only where a
 * glyph cannot carry them — for TalkBack, and in the folded note of a row whose
 * answer the system keeps to itself. "Open" stands only where something is left
 * to do. A settled row still opens its system screen on a tap, without a button
 * pulling the eye away from the rows that need one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PermissionRow(entry: PermEntry) {
    val state = entry.state
    val stateLabel = stringResource(
        when (state) {
            PermState.GRANTED -> R.string.perm_state_granted
            PermState.DENIED -> R.string.perm_state_denied
            PermState.UNKNOWN -> R.string.perm_state_unknown
            PermState.NOT_APPLICABLE -> R.string.perm_state_na
        }
    )
    // The reason is the one line in view. An answer the glyph cannot explain on
    // its own goes on the next line, which folds away with the rest.
    val explanation = when (state) {
        PermState.UNKNOWN -> entry.reason + "\n" + stringResource(R.string.perm_unknown_note)
        PermState.NOT_APPLICABLE -> entry.reason + "\n" + stateLabel
        else -> entry.reason
    }
    val openLabel = stringResource(R.string.perm_open)
    val needsAction = state == PermState.DENIED || state == PermState.UNKNOWN
    // Nothing to open where the permission does not exist on this Android.
    val canOpen = state != PermState.NOT_APPLICABLE
    val help = remember(explanation) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            // A long press unfolds the note, as on the settings rows.
            .combinedClickable(
                onClickLabel = if (canOpen) openLabel else null,
                onClick = { if (canOpen) entry.open() else help.value = !help.value },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    help.value = !help.value
                }
            )
    ) {
        ListItem(
            supportingContent = { HelpText(explanation, lines = 1, expanded = help, inClickableRow = true) },
            leadingContent = { Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (needsAction) {
                        TextButton(onClick = entry.open) { Text(openLabel) }
                    }
                    PermStateIcon(state, stateLabel)
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        ) { Text(entry.title) }
    }
}

/** The answer as a glyph, tinted like the words it replaced; [description] is what TalkBack says. */
@Composable
private fun PermStateIcon(state: PermState, description: String) {
    val (glyph, tint) = when (state) {
        PermState.GRANTED -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        PermState.DENIED -> Icons.Default.Cancel to MaterialTheme.colorScheme.error
        PermState.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline to MaterialTheme.colorScheme.outline
        PermState.NOT_APPLICABLE -> Icons.Default.RemoveCircleOutline to MaterialTheme.colorScheme.outline
    }
    Icon(glyph, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
}

/** Before Android 12 an exact alarm needs no permission at all. */
private fun exactAlarmsState(context: Context): PermState = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> PermState.NOT_APPLICABLE
    ServiceWatchdog.canScheduleExact(context) -> PermState.GRANTED
    else -> PermState.DENIED
}

private fun batteryOptimisationState(context: Context): PermState {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return PermState.UNKNOWN
    return try {
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) PermState.GRANTED else PermState.DENIED
    } catch (e: Exception) {
        PermState.UNKNOWN
    }
}

private fun notificationsState(context: Context): PermState =
    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) PermState.GRANTED else PermState.DENIED

/** Before Android 8 "unknown sources" is one device-wide switch, not an app permission. */
private fun installUnknownAppsState(context: Context): PermState = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O -> PermState.NOT_APPLICABLE
    context.packageManager.canRequestPackageInstalls() -> PermState.GRANTED
    else -> PermState.DENIED
}

private fun appDetails(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

/**
 * Starts the first intent the device accepts. Skins drop and rename these screens
 * freely, so every row falls back to the app info page and, failing that, says so
 * instead of doing nothing.
 */
private fun openFirst(context: Context, vararg intents: Intent) {
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (e: Exception) {
            // Not on this device; try the next fallback.
        }
    }
    Toast.makeText(context, context.getString(R.string.perm_open_failed), Toast.LENGTH_LONG).show()
}
