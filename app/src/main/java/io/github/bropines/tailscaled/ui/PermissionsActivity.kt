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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    AlertDialog(
        onDismissRequest = { answer() },
        icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null) },
        title = { Text(stringResource(R.string.perm_ask_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.perm_ask_text), style = MaterialTheme.typography.bodyMedium)
                // The whole row toggles, so the label is a target too, not just
                // the 20dp box.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { neverAgain = !neverAgain }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = neverAgain, onCheckedChange = { neverAgain = it })
                    Text(
                        stringResource(R.string.perm_ask_never),
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
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            answer()
                            context.startActivity(Intent(context, PermissionsActivity::class.java))
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.perm_ask_all),
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
                Text(stringResource(R.string.perm_ask_grant))
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
                Text(stringResource(R.string.perm_ask_later))
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

    val alarmsState = remember(refreshTick) { exactAlarmsState(context) }
    val batteryState = remember(refreshTick) { batteryOptimisationState(context) }
    val notificationsState = remember(refreshTick) { notificationsState(context) }
    val installState = remember(refreshTick) { installUnknownAppsState(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.perm_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
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

            PermissionRow(
                title = stringResource(R.string.perm_autostart_title),
                reason = stringResource(R.string.perm_autostart_reason),
                // The OEM screens expose no API at all, so claiming either answer
                // would be a guess; say so instead.
                state = PermState.UNKNOWN,
                icon = Icons.Default.PowerSettingsNew
            ) { openAutostartSettings(context) }

            PermissionRow(
                title = stringResource(R.string.perm_alarms_title),
                reason = stringResource(R.string.perm_alarms_reason),
                state = alarmsState,
                icon = Icons.Default.Alarm
            ) {
                openFirst(
                    context,
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")
                    ),
                    appDetails(context)
                )
            }

            PermissionRow(
                title = stringResource(R.string.perm_battery_title),
                reason = stringResource(R.string.perm_battery_reason),
                state = batteryState,
                icon = Icons.Default.BatteryAlert
            ) {
                openFirst(
                    context,
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    appDetails(context)
                )
            }

            PermissionRow(
                title = stringResource(R.string.perm_notifications_title),
                reason = stringResource(R.string.perm_notifications_reason),
                state = notificationsState,
                icon = Icons.Default.Notifications
            ) {
                openFirst(
                    context,
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    appDetails(context)
                )
            }

            PermissionRow(
                title = stringResource(R.string.perm_install_title),
                reason = stringResource(R.string.perm_install_reason),
                state = installState,
                icon = Icons.Default.SystemUpdate
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

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    reason: String,
    state: PermState,
    icon: ImageVector,
    onOpen: () -> Unit
) {
    val label = stringResource(
        when (state) {
            PermState.GRANTED -> R.string.perm_state_granted
            PermState.DENIED -> R.string.perm_state_denied
            PermState.UNKNOWN -> R.string.perm_state_unknown
            PermState.NOT_APPLICABLE -> R.string.perm_state_na
        }
    )
    val labelColor = when (state) {
        PermState.GRANTED -> MaterialTheme.colorScheme.primary
        PermState.DENIED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = {
                Column {
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = labelColor
                    )
                }
            },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = {
                if (state != PermState.NOT_APPLICABLE) {
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.perm_open)) }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
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
