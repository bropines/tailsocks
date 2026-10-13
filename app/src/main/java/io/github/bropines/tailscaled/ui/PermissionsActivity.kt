package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R

import io.github.bropines.tailscaled.core.*

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.ui.theme.findActivity
import kotlinx.coroutines.delay

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

/**
 * The rows of this screen, in the order they are declared in. Access to files and the VPN
 * consent are asked for where they are needed and are not rows here; a paired device's
 * request ([PermissionAsk]) may still be about the first of them.
 */
private val RowPermissions = listOf(
    AppPermission.AUTOSTART,
    AppPermission.ALARMS,
    AppPermission.BATTERY,
    AppPermission.NOTIFICATIONS,
    AppPermission.INSTALL,
)

/** How a permission reads on this screen, in its row and on the card of a request for it. */
private class PermLook(@param:StringRes val title: Int, @param:StringRes val reason: Int, val icon: ImageVector)

/** Null for the VPN consent, which this screen never shows. */
private fun lookOf(permission: AppPermission): PermLook? {
    val title = RemotePromptRules.title(permission) ?: return null
    return when (permission) {
        AppPermission.AUTOSTART -> PermLook(title, R.string.perm_autostart_reason, Icons.Default.PowerSettingsNew)
        AppPermission.ALARMS -> PermLook(title, R.string.perm_alarms_reason, Icons.Default.Alarm)
        AppPermission.BATTERY -> PermLook(title, R.string.perm_battery_reason, Icons.Default.BatteryAlert)
        AppPermission.NOTIFICATIONS -> PermLook(title, R.string.perm_notifications_reason, Icons.Default.Notifications)
        AppPermission.INSTALL -> PermLook(title, R.string.perm_install_reason, Icons.Default.SystemUpdate)
        AppPermission.STORAGE -> PermLook(title, R.string.remote_prompt_storage_reason, Icons.Default.Folder)
        AppPermission.VPN -> null
    }
}

/** What the system says of a permission, in words: for TalkBack, and where a glyph cannot carry it. */
@Composable
private fun stateLabel(state: PermState): String = stringResource(
    when (state) {
        PermState.GRANTED -> R.string.perm_state_granted
        PermState.DENIED -> R.string.perm_state_denied
        PermState.UNKNOWN -> R.string.perm_state_unknown
        PermState.NOT_APPLICABLE -> R.string.perm_state_na
    }
)

/**
 * A paired device's request for a permission (core/RemoteDevicePrompts.kt) as this screen
 * shows it: one card above the rows, and Android's own page or dialog opened by itself, once
 * for each [serial] — the activity numbers the requests it is handed, and a rotation hands it
 * none. Nothing is granted by it: the person at the device answers the system, or does not.
 * [demo] is a render's, which has no system to ask.
 */
data class PermissionAsk(
    val permission: AppPermission,
    /** The asking device's name, as the bridge gives it. */
    val from: String,
    val serial: Int = 1,
    val demo: Demo? = null,
) {
    /** What a preview draws: the permission's [state], and a device with no page to give it on. */
    data class Demo(val state: PermState, val noPage: Boolean = false)
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
    val neverRow = remember { MutableInteractionSource() }
    val allRow = remember { MutableInteractionSource() }
    AppAlertDialog(
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
                        .tvFocusRing(neverRow, MaterialTheme.shapes.small)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(interactionSource = neverRow, indication = LocalIndication.current) { neverAgain = !neverAgain }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The row is the remote's stop; the box inside it would be a second one.
                    Checkbox(checked = neverAgain, onCheckedChange = { neverAgain = it }, modifier = Modifier.tvNoFocus())
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
                        .tvFocusRing(allRow, MaterialTheme.shapes.small)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(interactionSource = allRow, indication = LocalIndication.current) {
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
            // app: it lands in the system's own autostart screen. Where a remote starts.
            Button(
                onClick = {
                    answer()
                    openAutostartSettings(context)
                },
                modifier = Modifier.tvInitialFocus()
            ) {
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
    /** The request this screen was opened with, when it was opened with one. */
    private var ask by mutableStateOf<PermissionAsk?>(null)

    /** How many requests this screen has been handed: each has its page opened once. */
    private var askSerial = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated — a rotation, a change of language — it shows the request it showed, as
        // the same one: the page is not opened a second time.
        askSerial = savedInstanceState?.getInt(STATE_ASK_SERIAL) ?: 0
        takeAsk(intent, arrived = savedInstanceState == null)
        setContent {
            TailSocksTheme {
                PermissionsScreen(onBack = { finish() }, ask = ask)
            }
        }
    }

    /** A request that finds the screen already up (askIntent is single-top). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeAsk(intent, arrived = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ASK_SERIAL, askSerial)
    }

    private fun takeAsk(intent: Intent?, arrived: Boolean) {
        val request = askOf(intent)
        if (request != null && arrived) {
            askSerial++
            // Shown now, whichever way it came: it is not to be opened again on the next resume.
            RemoteDevicePrompts.opened(this, request.first)
        }
        ask = request?.let { (permission, from) -> PermissionAsk(permission, from, askSerial) }
    }

    companion object {
        private const val EXTRA_ASK_PERMISSION = "ask_permission"
        private const val EXTRA_ASK_FROM = "ask_from"
        private const val STATE_ASK_SERIAL = "ask_serial"

        /**
         * This screen with the request of the paired device [from] for [permission] on it:
         * for [RemoteDevicePrompts], which starts it from the service's side or leaves it
         * behind a notification. One screen at most, on top of the app's task. The activity
         * is not exported, so no other app can put a request here.
         */
        fun askIntent(context: Context, permission: AppPermission, from: String): Intent =
            Intent(context, PermissionsActivity::class.java)
                .putExtra(EXTRA_ASK_PERMISSION, permission.id)
                .putExtra(EXTRA_ASK_FROM, from)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /**
         * The request an intent carries. None for the VPN consent, which is never asked from
         * afar, and none for a task brought back from Recents: its intent is the one of a
         * request made who knows when, and a page jumping out then would be a surprise.
         */
        private fun askOf(intent: Intent?): Pair<AppPermission, String>? {
            if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            val permission = AppPermission.of(intent.getStringExtra(EXTRA_ASK_PERMISSION)) ?: return null
            if (RemotePromptRules.title(permission) == null) return null
            return permission to intent.getStringExtra(EXTRA_ASK_FROM).orEmpty()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit, ask: PermissionAsk? = null) {
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

    // Declared in a fixed order and shown by urgency: a row granted on the system
    // screen drops to the end on the way back, and what is left to do stays on top.
    // The sort is stable, so rows with the same answer keep this order.
    val batteryPrompt = Feature.BATTERY_OPTIMISATION_PROMPT.offered()
    val rows = remember(batteryPrompt) {
        RowPermissions.filter { it != AppPermission.BATTERY || batteryPrompt }.mapNotNull { p -> lookOf(p)?.let { p to it } }
    }
    val entries = rows.map { (permission, look) ->
        key(permission) {
            PermEntry(
                id = permission.id,
                title = stringResource(look.title),
                reason = stringResource(look.reason),
                icon = look.icon,
                // What the system says, in the one meaning of "granted" the app has
                // (core/AppPermissions.kt). An autostart list has no API at all: unknown.
                state = remember(refreshTick) {
                    if (inPreview) PermState.UNKNOWN else AppPermissions.state(context, permission)
                }
            ) { openPermission(context, permission) }
        }
    }.sortedBy { it.state.urgency }

    // A paired device's request stands above everything: it is why the screen is up. The
    // remote starts on its button while it has one.
    val askUi = ask?.let { rememberAskUi(it, refreshTick) }
    val asked: @Composable () -> Unit = {
        askUi?.let { PermissionAskCard(it, Modifier.padding(top = 12.dp)) }
    }
    val rowsStart = askUi?.offersOpen != true

    val window = rememberWindowLayout()
    val intro: @Composable () -> Unit = {
        Text(
            stringResource(R.string.perm_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    }
    // A phone's column, and a tablet's where two columns do not fit.
    val oneColumn: @Composable (PaddingValues) -> Unit = { padding ->
        // Held to a readable width on a tablet; see ReadableWidth.
        ReadableWidth {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            asked()
            intro()

            entries.forEachIndexed { index, entry ->
                key(entry.id) { PermissionRow(entry, first = index == 0 && rowsStart) }
            }

            Spacer(Modifier.height(16.dp))
        }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.perm_title),
                onBack = onBack
            )
        }
    ) { padding ->
        // One column on a television as well: a row's note unfolds while the remote is on
        // it, and columns balanced by height would trade rows as it moved.
        if (!window.multiColumn || window.tv) {
            oneColumn(padding)
            return@Scaffold
        }
        // From a medium window up the rows stand in columns, the way the system's own
        // settings lay out tiles, instead of a strip of five down the middle.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (columnsFor(maxWidth - window.margin * 2, PermissionColumnWidth) < 2) {
                oneColumn(padding)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = window.margin)
                        .verticalScroll(rememberScrollState())
                ) {
                    asked()
                    intro()
                    CardColumns(minColumnWidth = PermissionColumnWidth) {
                        entries.forEachIndexed { index, entry ->
                            key(entry.id) { PermissionRow(entry, spaced = false, first = index == 0 && rowsStart) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** The narrowest column of permission rows: the title, "Open" and the glyph on one line. */
private val PermissionColumnWidth = 340.dp

/**
 * One permission, in two lines. What the system says about it is a glyph at the
 * row's end, so the answers read down one column; it has words only where a
 * glyph cannot carry them — for TalkBack, and in the folded note of a row whose
 * answer the system keeps to itself. "Open" stands only where something is left
 * to do. A settled row still opens its system screen on a tap, without a button
 * pulling the eye away from the rows that need one.
 *
 * With a remote a row is one stop: OK opens it, "Open" is not a stop of its own, and the
 * note unfolds while the row has the focus — there is no long press to count on. [first]
 * is the row the remote starts on.
 */
@Composable
private fun PermissionRow(
    entry: PermEntry,
    /** The rows' own space between them, in a column; columns of rows are spaced by theirs. */
    spaced: Boolean = true,
    first: Boolean = false
) {
    val state = entry.state
    val stateLabel = stateLabel(state)
    // The reason is the one line in view. An answer the glyph cannot explain on
    // its own goes on the next line, which folds away with the rest.
    val explanation = when (state) {
        PermState.UNKNOWN -> entry.reason + "\n" + stringResource(R.string.perm_unknown_note)
        PermState.NOT_APPLICABLE -> entry.reason + "\n" + stateLabel
        else -> entry.reason
    }
    val openLabel = stringResource(R.string.perm_open)
    val needsAction = state.needsAction
    // Nothing to open where the permission does not exist on this Android.
    val canOpen = state != PermState.NOT_APPLICABLE
    val help = remember(explanation) { mutableStateOf(false) }
    // A long press unfolds the note, as on the settings rows.
    ExplainedRow(
        onClick = { if (canOpen) entry.open() else help.value = !help.value },
        help = help,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (spaced) Modifier.padding(vertical = 4.dp) else Modifier)
            .then(if (first) Modifier.tvInitialFocus() else Modifier),
        onClickLabel = if (canOpen) openLabel else null
    ) {
        ListItem(
            // The ⓘ is a target for a finger; for a remote the row unfolds the note itself.
            supportingContent = {
                Box(Modifier.tvNoFocus()) { HelpText(explanation, lines = 1, expanded = help, inClickableRow = true) }
            },
            leadingContent = { Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (needsAction) {
                        // The row opens the same screen: for a remote the button is no stop of its own.
                        TextButton(onClick = entry.open, modifier = Modifier.tvNoFocus()) { Text(openLabel) }
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

/**
 * Opens the system's screen for a row. Skins drop and rename these screens freely, so every
 * row falls back to the app info page ([AppPermissions.pages]) and, failing that, says so
 * instead of doing nothing. A television answers some of them with a placeholder that opens
 * and shows nothing (core/SafeLaunch.kt): that one is passed over like a missing page.
 */
private fun openPermission(context: Context, permission: AppPermission) {
    // A vendor's list, with a fallback and a line of its own.
    if (permission == AppPermission.AUTOSTART) return openAutostartSettings(context)
    if (!openPage(context, permission)) {
        Toast.makeText(context, context.getString(R.string.perm_open_failed), Toast.LENGTH_LONG).show()
    }
}

/**
 * The first page of [permission] the device really has; false when it has none. Started
 * from the activity, in its own task, so that Back leads here again.
 */
private fun openPage(context: Context, permission: AppPermission): Boolean =
    AppPermissions.open(context.findActivity() ?: context, permission)

// ─── A paired device's request ───────────────────────────────────────────────────────────────

/** A request as its card draws it: what the system says now, and what there is to press. */
private class AskUi(
    val ask: PermissionAsk,
    val look: PermLook,
    val state: PermState,
    /** The device has neither a dialog nor a page for it: found out by trying, or a render's. */
    val noPage: Boolean,
    val open: () -> Unit,
) {
    /** Something is left to do, and there is somewhere to do it. */
    val offersOpen: Boolean get() = state.needsAction && !noPage
}

/**
 * The card of [ask]: its state, read again whenever [refreshTick] moves — the screen is back
 * in front — and the page opened by itself, once for each request. [PermissionAsk.serial]
 * outlives a rotation, and so does the note of which one was opened. Null for a permission
 * this screen has no words for.
 */
@Composable
private fun rememberAskUi(ask: PermissionAsk, refreshTick: Int): AskUi? {
    val look = remember(ask.permission) { lookOf(ask.permission) } ?: return null
    val context = LocalContext.current
    val inPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    val state = remember(refreshTick, ask) {
        ask.demo?.state ?: if (inPreview) PermState.UNKNOWN else AppPermissions.state(context, ask.permission)
    }
    var pageMissing by rememberSaveable(ask.serial) { mutableStateOf(false) }
    val open = rememberPermissionRequest(ask.permission) { pageMissing = true }
    var opened by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ask.serial) {
        if (ask.demo != null || inPreview || opened == ask.serial) return@LaunchedEffect
        opened = ask.serial
        if (state.needsAction) open()
    }
    return AskUi(ask, look, state, noPage = ask.demo?.noPage ?: pageMissing, open = open)
}

/**
 * How [permission] is asked for: with Android's own dialog where it has one
 * ([AppPermissions.asksInDialog]) — and, once that was refused for good and the system shows
 * nothing, on the page that gives it back — otherwise on the system's page. [onNoPage] when
 * the device has none of them.
 */
@Composable
private fun rememberPermissionRequest(permission: AppPermission, onNoPage: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val noPage by rememberUpdatedState(onNoPage)
    val page = { if (!openPage(context, permission)) noPage() }
    val storage = rememberStorageAccessRequest(onUnavailable = { noPage() })
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context.findActivity()
        if (!granted && (activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))) {
            page()
        }
    }
    return {
        when {
            permission == AppPermission.AUTOSTART -> openAutostartSettings(context)
            !AppPermissions.asksInDialog(permission) -> page()
            permission == AppPermission.STORAGE -> storage()
            else -> if (!notifications.tryLaunch(Manifest.permission.POST_NOTIFICATIONS)) page()
        }
    }
}

/** How long the remote rests on the card's button before the explanation unfolds: an [ExplainedRow]'s own measure. */
private const val AskHelpRevealMillis = 700L

/** True once the remote has rested on the target of [source] for [AskHelpRevealMillis]; false again as it leaves. */
@Composable
private fun rememberRested(source: InteractionSource): Boolean {
    val focused by source.collectIsFocusedAsState()
    var rested by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused) delay(AskHelpRevealMillis)
        rested = focused
    }
    return rested
}

/**
 * Who asks for what, above the rows: the permission's one-line reason, folded, what the
 * system says of it, and "Open" while it is missing — the same page the request opened by
 * itself, for whoever went back from it. Given, the card says so and asks nothing. On a
 * device with no page for it the card says that instead, with the line that grants access
 * to files from a computer, as the Taildrive page does.
 *
 * With a remote the button is the card's one stop and where the remote starts; the
 * explanation unfolds while it rests there, as a row's does.
 */
@Composable
private fun PermissionAskCard(ui: AskUi, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = ui.state
    val stateLabel = stateLabel(state)
    val from = ui.ask.from.ifBlank { stringResource(R.string.remote_prompt_someone) }
    val reason = stringResource(ui.look.reason)
    val explanation = when (state) {
        PermState.UNKNOWN -> reason + "\n" + stringResource(R.string.perm_unknown_note)
        PermState.NOT_APPLICABLE -> reason + "\n" + stateLabel
        else -> reason
    } + "\n" + stringResource(R.string.remote_prompt_card_note)
    val tv = isTv
    val button = remember { MutableInteractionSource() }
    val rested = rememberRested(button)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ui.look.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.remote_prompt_card_title, from, stringResource(ui.look.title)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                PermStateIcon(state, stateLabel)
            }
            Spacer(Modifier.height(6.dp))
            if (state == PermState.GRANTED) {
                Text(stringResource(R.string.remote_prompt_card_granted), style = MaterialTheme.typography.bodyMedium)
            } else {
                // Under the button's watch on a television; with no button the text is a stop
                // of its own there, as an explanation with no row around it is.
                val unfoldedByButton = tv && ui.offersOpen
                CompositionLocalProvider(LocalRowHelpRevealed provides if (unfoldedByButton) rested else null) {
                    HelpText(explanation, color = LocalContentColor.current, inClickableRow = unfoldedByButton)
                }
            }
            if (ui.noPage && state != PermState.GRANTED) {
                Spacer(Modifier.height(8.dp))
                val command = storageGrantCommand(Build.VERSION.SDK_INT, context.packageName)
                val line = if (ui.ask.permission == AppPermission.STORAGE) {
                    stringResource(R.string.file_browser_grant_unavailable_format, command)
                } else {
                    stringResource(R.string.remote_prompt_card_no_page)
                }
                Text(
                    buildAnnotatedString {
                        append(line)
                        val at = line.indexOf(command)
                        if (at >= 0) addStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), at, at + command.length)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (ui.offersOpen) {
                Spacer(Modifier.height(12.dp))
                // Marked as leaving the app: it lands on a screen of the system's.
                Button(onClick = ui.open, interactionSource = button, modifier = Modifier.tvInitialFocus()) {
                    Text(stringResource(R.string.perm_open))
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(16.dp)
                    )
                }
            }
        }
    }
}
