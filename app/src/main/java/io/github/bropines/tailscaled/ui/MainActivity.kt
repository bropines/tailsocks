package io.github.bropines.tailscaled.ui
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.github.bropines.tailscaled.ui.theme.findActivity
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import io.github.bropines.tailscaled.core.AppJson
import java.lang.Runtime

import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

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

/** Settings asks the dashboard to open "Add account" (see ProfileHostinfo). */
internal const val MAIN_EXTRA_ADD_ACCOUNT = "add_account"
/** The value the new profile's "Report the real OS" checkbox starts with. */
internal const val MAIN_EXTRA_ADD_ACCOUNT_HONEST = "add_account_honest"
/** Settings asks the dashboard to open its editor (ui/MainBlocksSetting.kt). */
internal const val MAIN_EXTRA_EDIT_LAYOUT = "edit_layout"
/** Settings asks the dashboard to open its accounts menu: the Accounts tile, off the screen (ui/MainHiddenItems.kt). */
internal const val MAIN_EXTRA_OPEN_ACCOUNTS = "open_accounts"

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val showAccountSwitcher = mutableStateOf(false)
    private val showChangelog = mutableStateOf(false)
    /** Non-null while an "add account" request from Settings waits for the screen; the value is the checkbox preset. */
    private val addAccountRequest = mutableStateOf<Boolean?>(null)
    /** Set by tailsocks://exitnode (the launcher's Exit node shortcut): the screen opens its picker. */
    private val exitNodeRequest = mutableStateOf(false)
    /** Set by tailsocks://scan: the screen opens the QR scanner. */
    private val scanRequest = mutableStateOf(false)
    /** Set by Settings → Appearance → Main screen layout: the screen opens as its own editor. */
    private val editLayoutRequest = mutableStateOf(false)

    companion object {
        /**
         * The main screen, being arranged. It is the task's root and singleTask, so this
         * brings it back from under Settings rather than starting a second one.
         */
        fun editLayoutIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MAIN_EXTRA_EDIT_LAYOUT, true)

        /** The main screen with its accounts menu open, brought back the same way. */
        fun accountsIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MAIN_EXTRA_OPEN_ACCOUNTS, true)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val appPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!appPrefs.getBoolean("first_start_done", false)) {
            startActivity(Intent(this, FirstStartActivity::class.java))
            finish()
            return
        }

        // Once per launch, not per recreation: a rotation, a language or an interface-scale
        // change (recreate() in onResume) is the same launch, and repeated the permission
        // request — until the system starts denying it by itself — and the update check
        // with its toast.
        val firstCreate = savedInstanceState == null
        if (firstCreate) checkNotificationPermission()
        // Runs only past the onboarding gate above, so the wizard is never
        // interrupted; a fresh install records its version and shows nothing.
        // Must run before handleAppStartup(): that launches an IO coroutine which
        // writes last_update_time, and checkWhatsNew() reads it to tell a fresh
        // install from an upgrade.
        if (firstCreate) checkWhatsNew()
        handleAppStartup()
        // A network request on every launch, so it is the user's to switch off
        // (and off by default where a store handles updates).
        if (firstCreate) {
            if (GlobalSettings.isUpdateCheckOnLaunch(this)) checkForUpdatesSilent()
            else UpdateDownload.scope.launch { pruneUpdateDownloads(applicationContext) }
        }
        handleIntent(intent)

        setContent {
            TailSocksTheme {
                MainScreen(showAccountSwitcher, showChangelog, addAccountRequest, exitNodeRequest, scanRequest, editLayoutRequest)
            }
        }
    }

    /**
     * Decides whether to show the "What's new" dialog for this launch.
     *
     * Deliberately keyed on its own preference rather than `last_update_time`
     * from [handleAppStartup]: that one is a daemon-restart trigger and flips on
     * every reinstall, including same-version debug builds.
     */
    private fun checkWhatsNew() {
        // Base version only (v3.6.0-abc123.release -> 3.6.0): the git hash changes
        // with every commit and would re-show the dialog on each dev rebuild.
        val current = Changelog.currentVersion()
        val seen = GlobalSettings.getLastSeenChangelogVersion(this)
        // Users who already had the app before this feature existed have no
        // stored value but are upgrading, not installing fresh; the update
        // detector's timestamp tells the two apart.
        val upgradedFromOlderBuild = seen.isEmpty() &&
            getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getLong("last_update_time", 0L) != 0L
        when {
            seen.isEmpty() && !upgradedFromOlderBuild -> GlobalSettings.setLastSeenChangelogVersion(this, current)
            seen == current -> Unit
            GlobalSettings.isShowChangelogAfterUpdate(this) -> showChangelog.value = true
            else -> GlobalSettings.setLastSeenChangelogVersion(this, current)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, fromNewIntent = true)
    }

    override fun onResume() {
        super.onResume()
        val lang = GlobalSettings.getString(this, "app_locale", "sys")
        val currentLocale = resources.configuration.locales.get(0)
        val targetLocale = if (lang == "sys") {
            android.content.res.Resources.getSystem().configuration.locales.get(0)
        } else {
            java.util.Locale.forLanguageTag(lang)
        }
        if (currentLocale.language != targetLocale.language) {
            recreate()
            return
        }
        // The interface scale changed in Settings while this screen sat underneath.
        val wantedDpi = scaledDensityDpi(applicationContext.resources.configuration.densityDpi, GlobalSettings.getUiScale(this))
        if (resources.configuration.densityDpi != wantedDpi) {
            recreate()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (appctr.Appctr.isRunning()) {
                    appctr.Appctr.forceRefresh()
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleIntent(intent: Intent?, fromNewIntent: Boolean = false) {
        if (intent == null) return
        // tailsocks:// links (DeepLinks): the screen opens on top of this one.
        // The data is cleared so a recreation or a later onNewIntent does not
        // open it again (singleTask).
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == DeepLinks.SCHEME) {
            val uri = intent.data!!
            intent.data = null
            when (uri.host) {
                DeepLinks.EXIT_NODE -> exitNodeRequest.value = true
                DeepLinks.SCAN -> scanRequest.value = true
                else -> DeepLinks.intentFor(this, uri)?.let { startActivity(it) }
            }
        }
        // Tap on the "the system would not let it back" notification. A start
        // made while an activity is coming to the foreground is never refused,
        // which is the entire reason this path exists. On a cold start
        // handleAppStartup() already does it — under the same condition — so
        // only the already-open case starts anything here; the extra is removed
        // either way so a later onNewIntent does not replay it (singleTask).
        if (intent.getBooleanExtra(ServiceWatchdog.EXTRA_RESUME_SERVICE, false)) {
            intent.removeExtra(ServiceWatchdog.EXTRA_RESUME_SERVICE)
            if (fromNewIntent &&
                ProxyState.isUserLetRunning(this) && !ProxyState.isActualRunning(this)
            ) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, TailscaledService::class.java).apply { action = "START_ACTION" }
                )
            }
            ServiceWatchdog.clearRevivalRefused(this)
        }
        // Settings could not flip "Report the real OS" on a profile that is already
        // registered, so it sends the user here to create a new one with the
        // setting preset. Consumed once (singleTask replays the intent otherwise).
        if (intent.getBooleanExtra(MAIN_EXTRA_ADD_ACCOUNT, false)) {
            intent.removeExtra(MAIN_EXTRA_ADD_ACCOUNT)
            addAccountRequest.value = intent.getBooleanExtra(MAIN_EXTRA_ADD_ACCOUNT_HONEST, true)
        }
        // Settings sends the user here to arrange the screen. Consumed once, like the one above.
        // It opens a state of this screen and stores nothing, so it is safe from any sender.
        if (intent.getBooleanExtra(MAIN_EXTRA_EDIT_LAYOUT, false)) {
            intent.removeExtra(MAIN_EXTRA_EDIT_LAYOUT)
            editLayoutRequest.value = true
        }
        // Settings opens the accounts menu of a screen whose Accounts tile was taken off. Once, likewise.
        if (intent.getBooleanExtra(MAIN_EXTRA_OPEN_ACCOUNTS, false)) {
            intent.removeExtra(MAIN_EXTRA_OPEN_ACCOUNTS)
            showAccountSwitcher.value = true
        }
        // Callback of the PackageInstaller-session fallback in launchApkInstaller().
        // A session cannot install silently: it reports STATUS_PENDING_USER_ACTION
        // and hands over the confirmation dialog as EXTRA_INTENT, which the app
        // has to launch itself. Nothing did, so whenever the FileProvider path
        // failed the update simply went nowhere.
        if (intent.action == "ACTION_INSTALL_COMPLETE") {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    // This activity is exported, so any app can send this action with an
                    // intent of its own inside, to be started with TailSocks' identity: one
                    // of our unexported screens, or its own activity with a grant on our
                    // file provider. Only the system's installer dialog is let through, and
                    // nothing rides along that could hand out access to a file.
                    if (confirm != null && isSystemInstallerConfirm(confirm)) {
                        try {
                            confirm.flags = confirm.flags and GRANT_FLAGS.inv()
                            confirm.clipData = null
                            startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            Toast.makeText(this, getString(R.string.main_check_failed_format, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // this process already is the new build
                Int.MIN_VALUE -> Unit
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "installer status $status"
                    Toast.makeText(this, getString(R.string.main_check_failed_format, msg), Toast.LENGTH_LONG).show()
                }
            }
        }
        // QS tile long press / preferences simply opens the app main screen without showing account switcher
    }

    private fun handleAppStartup() {
        ProxyState.init(this)
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        // Snapshot the outage before the coroutine below starts the service and
        // the service clears the flag: "the toggle is on but nothing is running"
        // and "a revival was refused" are both signs that something outside the
        // app took the connection down and could not put it back. The ask is
        // persisted here rather than held in an activity field, because by the
        // time a recreation re-runs this the condition is already gone.
        OptionalPermissions.noteOutage(
            this,
            GlobalSettings.getBoolean(this, ServiceWatchdog.KEY_REVIVAL_REFUSED, false) ||
                (ProxyState.isUserLetRunning(this) && !ProxyState.isActualRunning(this))
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val currentUpdateTime = packageInfo.lastUpdateTime
                val savedUpdateTime = prefs.getLong("last_update_time", 0)

                if (savedUpdateTime != currentUpdateTime) {
                    // Sweep a daemon the installer orphaned. Two exclusions:
                    // in Root Mode the daemon is owned by su and is restarted
                    // through RootUtils together with its routing (killing it
                    // here would strand the iptables rules it installed), and a
                    // daemon that belongs to a service which is already running
                    // must be left alone — since the update-resume in
                    // BootReceiver, opening the app right after an update would
                    // otherwise kill the connection that had just come back.
                    if (!GlobalSettings.isRootModeEnabled(this@MainActivity) &&
                        !ProxyState.isActualRunning(this@MainActivity)
                    ) {
                        Runtime.getRuntime().exec("killall tailscaled").waitFor()
                    }
                    prefs.edit().putLong("last_update_time", currentUpdateTime).apply()
                }
            } catch (e: Exception) {}

            if (ProxyState.isUserLetRunning(this@MainActivity) && !ProxyState.isActualRunning(this@MainActivity)) {
                // The toggle is on but nothing is running: an update, an OEM task
                // killer or a refused background start took it down, never the
                // user — a manual stop clears desired_running before the teardown.
                // Start it from here: this runs with the activity in the
                // foreground, which is always an allowed foreground-service start,
                // and the daemon resumes its saved session without an auth key.
                // "Keep running in background" is not consulted; it decides only
                // whether we also come back after a reboot (BootReceiver), and
                // clearing the toggle instead used to leave the app claiming to be
                // connected while silently giving up.
                withContext(Dispatchers.Main) {
                    val intent = Intent(this@MainActivity, TailscaledService::class.java).apply { action = "START_ACTION" }
                    ContextCompat.startForegroundService(this@MainActivity, intent)
                }
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * The launch-time update check. In the process's scope with the application's context (in
     * the app's language): it used to run in a MainScope of its own that held this activity for
     * as long as a slow request took, past a rotation or a close.
     */
    private fun checkForUpdatesSilent() {
        val app = wrapContextWithLocale(applicationContext)
        UpdateDownload.scope.launch {
            pruneUpdateDownloads(app)
            try {
                val currentVersion = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0.0.0"
                // Releases, and betas too for whoever asked for them (core/UpdateCheck.kt).
                val betas = GlobalSettings.isBetaUpdates(app)
                val tag = UpdateCheck.offer(currentVersion, UpdateCheck.fetch(betas), betas)?.tag
                if (tag != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, app.getString(R.string.main_update_available_format, tag), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {}
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    showAccountSwitcher: MutableState<Boolean>,
    showChangelog: MutableState<Boolean> = remember { mutableStateOf(false) },
    addAccountRequest: MutableState<Boolean?> = remember { mutableStateOf(null) },
    exitNodeRequest: MutableState<Boolean> = remember { mutableStateOf(false) },
    scanRequest: MutableState<Boolean> = remember { mutableStateOf(false) },
    editLayoutRequest: MutableState<Boolean> = remember { mutableStateOf(false) },
    /** For previews: the arrangement to draw instead of the stored one; nothing is stored then. */
    previewLayout: MainLayout? = null,
    /** For previews: open as the editor. */
    previewEditing: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var activeAccount by remember { mutableStateOf(AccountManager.getActiveAccount(context)) }
    val accounts = remember { mutableStateOf(AccountManager.getAccounts(context)) }
    // Which account cannot be deleted; read again whenever the list changes.
    var primaryId by remember(accounts.value) { mutableStateOf(AccountManager.getPrimaryId(context)) }
    
    var showAddAccountDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameAccountDialog by remember { mutableStateOf(false) }
    var showSwitchConfirmDialog by remember { mutableStateOf<TailscaleAccount?>(null) }
    var accountToDeleteConfirm by remember { mutableStateOf<TailscaleAccount?>(null) }
    var accountToRename by remember { mutableStateOf<TailscaleAccount?>(null) }
    var accountsEditMode by remember { mutableStateOf(false) }
    // A long press on the status card puts a line there for a while. Nothing reads it
    // back and nothing is stored: it is decoration, and it clears itself.
    var statusAside by remember { mutableStateOf<String?>(null) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(statusAside) {
        val line = statusAside ?: return@LaunchedEffect
        // Long lines scroll, so they are given the time to travel once and a bit.
        delay((6000L + 70L * line.length).coerceAtMost(30000L))
        statusAside = null
    }
    
    var newAccountName by rememberSaveable { mutableStateOf("") }
    // "Report the real OS" for the profile about to be created: decided here,
    // before its first login, because the coordinator binds a node to the OS
    // it registered with (ProfileHostinfo).
    // A new profile reports the real OS unless the box is cleared (ProfileHostinfo).
    var newAccountHonest by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(addAccountRequest.value) {
        addAccountRequest.value?.let { honest ->
            newAccountHonest = honest
            showAddAccountDialog = true
            addAccountRequest.value = null
        }
    }
    // Saveable, like the dialogs and sheets below that hold nothing but a flag or a draft that
    // is not a secret: neither activity handles configuration changes, so a rotation recreates
    // the screen and used to close whatever was open, with what had been typed in it.
    var accountMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    // Read when the screen is resumed (the observer below): it changes only in the system's
    // settings, and coming back from there resumes this screen. It was a binder call every 2 s.
    var isBatteryOptimizationsIgnored by remember { mutableStateOf(true) }
    // Read from preferences on every composition of this screen, so a recreation
    // (a language change, a rotation) and a process restart both bring the ask
    // back until the user has answered it.
    var showAutostartAsk by remember { mutableStateOf(OptionalPermissions.isAutostartAskPending(context)) }

    LaunchedEffect(showAccountSwitcher.value) {
        if (showAccountSwitcher.value) {
            accountMenuExpanded = true
            showAccountSwitcher.value = false
        }
    }

    val prefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    val globalPrefs = remember { context.getSharedPreferences("tailsocks_global", Context.MODE_PRIVATE) }
    var isTunEnabled by remember { mutableStateOf(GlobalSettings.isTunModeEnabled(context)) }
    // TUN mode is on, but another VPN app took the slot: the card must not say
    // "+ TUN" over a phone that has no TailSocks VPN (TunVpnService.isDisplaced).
    var isTunDisplaced by remember { mutableStateOf(TunVpnService.isDisplaced(context)) }
    // Root Mode routes through the kernel interface, not the VpnService, so the
    // card has to name it: "Active" alone reads as plain proxy mode.
    var isRootEnabled by remember { mutableStateOf(GlobalSettings.isRootModeEnabled(context)) }
    // Whether a failing Tailscale resolver is a notice or a connection problem.
    var dnsHasFallback by remember { mutableStateOf(GlobalSettings.dnsHasFallback(context)) }
    // Root Mode leaves the default route and the device's DNS to another VPN
    // when one holds the phone. The service records that; the card and the exit
    // node row read it here, so neither promises a tunnel that is not installed.
    var isRootYielded by remember { mutableStateOf(GlobalSettings.isRootRoutingYielded(context)) }
    // The yield has two shapes and they mean opposite things to the user. When
    // the other client bypasses some apps we take the default route for exactly
    // those, so the exit node is carrying traffic — for them alone. Read as a
    // pair: this flag decides, and the yield flag only speaks when it is off.
    var isRootShared by remember { mutableStateOf(GlobalSettings.isRootRoutingShared(context)) }

    val globalPrefsListener = remember {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "tun_mode_enabled") {
                isTunEnabled = sharedPreferences.getBoolean("tun_mode_enabled", false)
            }
            if (key == TunVpnService.DISPLACED_PREF) {
                isTunDisplaced = sharedPreferences.getBoolean(TunVpnService.DISPLACED_PREF, false)
            }
            if (key == "root_routing_yielded") {
                isRootYielded = sharedPreferences.getBoolean("root_routing_yielded", false)
            }
            if (key == "root_routing_shared") {
                isRootShared = sharedPreferences.getBoolean("root_routing_shared", false)
            }
        }
    }

    // LocalInspectionMode is true only in the preview renderer, which has no
    // daemon and no native bridge: touching Appctr there loads gojni and fails.
    // In the app it is always false, so nothing below changes behaviour.
    val inPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    // A preview may hand in a whole state to draw; see LocalDemo.
    val demo = LocalDemo.current
    var proxyState by remember {
        mutableStateOf(
            when {
                demo != null -> if (demo.running) "ACTIVE" else "STOPPED"
                !inPreview && ProxyState.isActualRunning(context) -> "ACTIVE"
                else -> "STOPPED"
            }
        )
    }
    var exitNodeIp by remember(activeAccount.id) { mutableStateOf(demo?.exitNodeIp ?: prefs.getString("exit_node_ip", "") ?: "") }
    // What the daemon says about itself beyond "the service is running": its
    // backend state, the health warnings worth showing, and a summary of the
    // tailnet. The service state above cannot tell "connected" from "stuck
    // starting" or "up, but no relay reachable" — these can.
    var daemonBackend by remember { mutableStateOf(demo?.backendState ?: "") }
    var shownWarnings by remember {
        mutableStateOf(visibleWarnings(parseHealthWarnings(demo?.healthJson), System.currentTimeMillis()))
    }
    var connectionSummary by remember {
        mutableStateOf(
            demo?.statusJson?.let { json ->
                runCatching { summarize(AppJson.decodeFromString<StatusResponse>(json), demo.exitNodeIp ?: "") }.getOrNull()
            }
        )
    }
    // The other devices, from the same status read as the summary: what a large window's
    // devices card lists (MainDashboard.kt). A phone has no such card and shows none of it.
    val demoPeers = remember {
        demo?.statusJson?.let { json ->
            runCatching { listablePeers(AppJson.decodeFromString<StatusResponse>(json)) }.getOrNull()
        }
    }
    var tailnetPeers by remember { mutableStateOf(demoPeers) }
    // The same list for the managed devices, which the screen's own scope reads: replaced only
    // when what they take from it changes (managedPresence). tailnetPeers is new on every read —
    // traffic counters, handshakes — and read there, it re-ran all of MainScreen every 10 s;
    // it is now read only by what draws it, a wide Peers tile and a large window's devices card.
    var managedPeers by remember { mutableStateOf(demoPeers) }
    var reconnectingRelays by remember { mutableStateOf(false) }
    // Off for anyone who prefers the screen as it was: on a phone of the
    // right size everything, menu included, fits without scrolling, and the
    // summary's extra rows push the last menu row off it.
    // What is on the screen and where (core/MainLayout.kt). Until the person arranges it, it is
    // read off the switches the screen had before, so it is the screen they know. Re-read when
    // the screen comes back to the front: Settings can change it too.
    val remoteTileOffered = !Feature.REMOTE_MANAGEMENT_ONLY_IN_SETTINGS.offered()
    val itemOffered = rememberMainItemsOffered()
    val arrangeable = !isTv
    var layout by remember { mutableStateOf(previewLayout ?: MainLayoutStore.load(context, remoteTileOffered, arrangeable)) }
    // The screen as its own editor: a long press on it, or the row in Settings → Appearance.
    var editing by rememberSaveable { mutableStateOf(previewEditing) }
    // Too short a window for the editor's bar under the canvas: a phone on its side.
    val editRail = editing && androidx.compose.ui.platform.LocalConfiguration.current.let {
        it.screenHeightDp < 500 && it.screenWidthDp > it.screenHeightDp
    }
    var paletteOpen by remember { mutableStateOf(false) }
    var confirmLayoutReset by remember { mutableStateOf(false) }
    // Every change is stored as it is made: there is nothing to confirm, and Done only leaves.
    fun changeLayout(next: MainLayout) {
        if (next == layout) return
        layout = next
        if (previewLayout == null) MainLayoutStore.save(context, next)
    }
    // TunVpnService establishes a *full* tunnel exactly when an exit node is configured.
    // The old `tun_full_tunnel` pref was never written by anything and always read false,
    // so derive the indicator from the live exit-node state to keep the UI truthful.
    val isFullTunnel = exitNodeIp.isNotEmpty()
    // An exit node the guard took off because it is offline and will select again
    // by itself (ExitNodeGuard): the row must not read as a plain "no exit node".
    var exitNodePausedName by remember(activeAccount.id) {
        mutableStateOf(if (demo != null) "" else prefs.getString(ExitNodeGuard.KEY_PAUSED_NAME, "").orEmpty())
    }

    val profilePrefsListener = remember(activeAccount.id) {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "exit_node_ip") {
                exitNodeIp = sharedPreferences.getString("exit_node_ip", "") ?: ""
            }
            if (key == ExitNodeGuard.KEY_PAUSED_NAME) {
                exitNodePausedName = sharedPreferences.getString(ExitNodeGuard.KEY_PAUSED_NAME, "").orEmpty()
            }
        }
    }
    var isProcessing by remember { mutableStateOf(false) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    // Saveable: the bridge's last error is cleared by the read that found it (getLastError), so
    // a rotation or a recreate() for a language or scale change lost the banner for good.
    var show410Warning by rememberSaveable { mutableStateOf(false) }

    // A television cannot fill in a login form: it signs in with a code on its own screen
    // (ui/SignIn.kt), which opens by itself whenever the daemon turns out to want a login
    // and goes away by itself when it no longer does.
    val browserSignIn = Feature.BROWSER_SIGN_IN.offered()
    var signInOpen by remember { mutableStateOf(false) }
    // The code was left with the login still wanted: the card's button is where the remote
    // comes back to.
    var signInLeft by remember { mutableStateOf(false) }
    LaunchedEffect(proxyState, browserSignIn) {
        signInOpen = !browserSignIn && proxyState == "LOGGED_OUT"
        if (proxyState != "LOGGED_OUT") signInLeft = false
    }

    // Remote management has a tile of its own where its page in the settings would be three
    // levels down — a television — and wherever the person put one: it opens over this screen,
    // and the remote comes back to the tile.
    var remoteOpen by rememberSaveable { mutableStateOf(false) }
    var remoteLeft by remember { mutableStateOf(false) }
    val remoteTile = if (MainItem.REMOTE in layout) rememberRemoteTile(proxyState, remoteOpen) else null
    // The row under the cards is nearer to the status card than the cards are, and Right from it
    // would land there: the remote is sent to the first card instead.
    val menuStart = remember { FocusRequester() }

    // OK on a remote is pressed by accident, and on the start card that stops the service:
    // a television asks first. Starting asks nothing.
    val tv = isTv
    // A remote has nothing to drag with: a television keeps the arrangement made for it.
    LaunchedEffect(editLayoutRequest.value) {
        if (editLayoutRequest.value) {
            editLayoutRequest.value = false
            if (!tv) editing = true
        }
    }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    fun stopNow() {
        isProcessing = true
        context.startService(Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" })
    }
    fun requestStop() {
        if (tv) confirmStop = true else stopNow()
    }
    var siblingFound by remember { mutableStateOf<SiblingBuild.Found?>(null) }
    fun startNow() {
        isProcessing = true
        ContextCompat.startForegroundService(context, Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" })
    }

    var showExitNodeSheet by rememberSaveable { mutableStateOf(false) }
    var exitNodes by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var isExitNodesLoading by remember { mutableStateOf(false) }
    fun openExitNodeSheet() {
        showExitNodeSheet = true
        isExitNodesLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                // A failed read keeps the list the sheet already has.
                val status = DaemonStatus.readOrNull()
                if (status != null) {
                    val nodes = status.peers?.values?.filter { it.exitNodeOption == true }?.toList() ?: emptyList()
                    withContext(Dispatchers.Main) { exitNodes = nodes }
                }
            } catch (e: Exception) {}
            withContext(Dispatchers.Main) { isExitNodesLoading = false }
        }
    }
    LaunchedEffect(exitNodeRequest.value) {
        if (exitNodeRequest.value) {
            exitNodeRequest.value = false
            openExitNodeSheet()
        }
    }
    // A sheet that was open before a rotation comes back open, with its list still to read.
    LaunchedEffect(Unit) {
        if (showExitNodeSheet && exitNodes.isEmpty() && !isExitNodesLoading) openExitNodeSheet()
    }
    // The QR scanner, from the top bar and tailsocks://scan: an app link opens
    // as if tapped, a TailCat address in a new connection's editor; any other
    // text is only shown, in a sheet. A pairing code off another device's Remote
    // management screen (ui/RemoteManagedScreen.kt) opens the sheet that pairs
    // with that device, the code filled in — pairing stays the person's press.
    var scannedText by remember { mutableStateOf<String?>(null) }
    var scannedPairing by remember { mutableStateOf<RemoteQr?>(null) }
    val scanQr = rememberQrScanner { text ->
        scope.launch {
            // The bridge reads it, off the main thread like every call of its kind.
            val pairing = withContext(Dispatchers.Default) { remotePairingQr(text) }
            if (pairing != null) scannedPairing = pairing else scannedText = openScanned(context, text)
        }
    }
    val canScan = Feature.QR_SCANNER.availability().reachable
    LaunchedEffect(scanRequest.value) {
        if (scanRequest.value) {
            scanRequest.value = false
            // A link cannot open what the device does not have: no camera, no scanner.
            if (canScan) scanQr()
        }
    }
    // The devices this one manages, when it manages any (ui/ManagedOnMain.kt): read off the peer
    // list above, and again when the page a scanned code opened — which may have paired — is left.
    // Shown while their block is on the screen: the old switch in Settings → Appearance moves it too.
    val managed = rememberManagedDevices(activeAccount.id, managedPeers.takeIf { proxyState != "STOPPED" }, MainItem.MANAGED in layout, scannedPairing == null)
    // Where the menu has the tile of the other direction — who manages this device — they stand
    // beside it, in its row, while a tile there is two cards wide. A handheld has no such tile,
    // and they have a place of their own: tiles under a dashboard's cards, pills on a phone.
    // "Its row" is the first tile that takes a row, which is where the menu stands them.
    val remoteLeadsWideTiles = layout.shown(itemOffered).firstOrNull { it.item.isTile && it.wide }?.item == MainItem.REMOTE
    fun managedBeside(columns: Int) = remoteTileOffered && remoteLeadsWideTiles && columns >= 4 && managed.devices.isNotEmpty()

    fun applyExitNode(id: String, ip: String) {
        // A peer listed without its StableID: selecting it would clear the exit node.
        if (!ExitNodeApply.isSelectable(id, ip)) return
        // The confirm haptic: choosing where all traffic goes is the one
        // selection on this screen whose effect is not visible at once.
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        exitNodeIp = ip
        scope.launch(Dispatchers.IO) {
            val result = ExitNodeApply.select(context, id, ip)
            if (result is ExitNodeApply.Result.Refused) withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.settings_local_api_error_format, result.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // The watchdog lives as long as the screen, and an account switch replaces the profile's
    // store and its exit node without restarting it (the activity is not recreated): it reads
    // them through these, or it kept the first account's for good — `was_logged_in` written
    // into the wrong profile, the new profile's exit node looked up by the old one's address.
    val currentPrefs by rememberUpdatedState(prefs)
    val currentExitNodeIp by rememberUpdatedState(exitNodeIp)
    val pollLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle

    // Watchdog: Sync UI state with actual daemon status.
    //
    // Only while the screen is at least STARTED. It is the task's singleTask root, so it stays
    // composed under Settings, Peers and every other screen, and in the background for as long
    // as the service keeps the process: a bare LaunchedEffect kept polling there — every 2 s
    // the bridge, every 10 s and again every 30 s the full status with every peer, ~480 status
    // reads an hour for a screen nobody saw. The counters stay outside the block, so a stop
    // and a start carry on where they were; the first tick after a start runs at once, and the
    // status is read again at once too, so what comes back is current within one round trip.
    LaunchedEffect(pollLifecycle) {
        // The preview renderer has no bridge, and a preview's state is handed in (LocalDemo).
        if (inPreview || demo != null) return@LaunchedEffect
        var urlDetected = false
        var lastAvatarSync = 0L
        var lastSummaryAt = 0L
        var loggedOutSeconds = 0
        pollLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            // Back in front: the summary and the devices may be minutes old.
            lastSummaryAt = 0L
            // The status read runs beside the tick, as it always did — a large tailnet's status
            // takes longer than a tick — and a slow one is not stacked on by the next.
            var statusRead: kotlinx.coroutines.Job? = null
            while (true) {
                // Each of these can be a LocalAPI round trip, and LocalAPI holds a request
                // while the daemon has no backend yet: read on this thread every 2 s, a
                // daemon that was slow to start froze the screen it was starting under.
                val isProcessAlive = withContext(Dispatchers.IO) { try { appctr.Appctr.isRunning() } catch (e: Exception) { false } }
                val backendState = if (isProcessAlive) {
                    withContext(Dispatchers.IO) { try { appctr.Appctr.getBackendState() } catch (e: Exception) { "Error" } }
                } else "Stopped"
                daemonBackend = backendState
                shownWarnings = if (isProcessAlive) {
                    visibleWarnings(
                        parseHealthWarnings(withContext(Dispatchers.IO) { runCatching { appctr.Appctr.getHealthWarningsJSON() }.getOrNull() }),
                        System.currentTimeMillis()
                    )
                } else emptyList()

                if (isProcessAlive && backendState == "Running") {
                    // A login opened in a Custom Tab is done: back over the tab.
                    context.findActivity()?.let { LoginTab.finished(it) }
                    val wasLoggedIn = currentPrefs.getBoolean("was_logged_in", false)
                    if (!wasLoggedIn) {
                        currentPrefs.edit().putBoolean("was_logged_in", true).apply()
                    }
                    // A running backend means any start/login transition is over. If
                    // isProcessing was left set (no START broadcast reached this
                    // screen, e.g. a slow browser login), the sync below never ran and
                    // the "connection issue" card stayed up over a working tailnet.
                    if (isProcessing && (proxyState == "STARTING" || proxyState == "LOGGED_OUT" || proxyState == "CONNECTION_ISSUE")) {
                        isProcessing = false
                    }
                }

                // Sync state if not explicitly in transition (nextMainState, MainStatusLogic.kt).
                if (!isProcessing) {
                    val tick = nextMainState(
                        previous = proxyState,
                        alive = isProcessAlive,
                        backend = backendState,
                        loggedOutSeconds = loggedOutSeconds,
                        wasLoggedIn = currentPrefs.getBoolean("was_logged_in", false),
                        hasLoginUrl = !loginUrl.isNullOrBlank(),
                    )
                    proxyState = tick.state
                    loggedOutSeconds = tick.loggedOutSeconds
                }

                if (isProcessAlive) {
                    val url = withContext(Dispatchers.IO) { try { appctr.Appctr.getLoginURL() } catch (e: Exception) { "" } }
                    loginUrl = if (url.isNullOrBlank()) null else url

                    if (loginUrl != null) urlDetected = true
                    else if (urlDetected) {
                        show410Warning = false
                        urlDetected = false
                    }

                    val lastErr = withContext(Dispatchers.IO) { try { appctr.Appctr.getLastError() } catch (e: Exception) { "" } }
                    if (lastErr == "410_GONE") show410Warning = true

                    // The summary under the card and the devices, from a status read every
                    // 10 s, off the main thread, only while the backend is actually running —
                    // and the account's picture from that same read every 30 s (mainReadsDue).
                    val now = System.currentTimeMillis()
                    val reads = mainReadsDue(now, backendState == "Running", lastSummaryAt, lastAvatarSync)
                    if (reads.status && statusRead?.isActive != true) {
                        lastSummaryAt = now
                        if (reads.avatar) lastAvatarSync = now
                        val ipForExit = currentExitNodeIp
                        val account = activeAccount
                        statusRead = launch(Dispatchers.IO) {
                            val status = DaemonStatus.readOrNull()
                            val built = status?.let { runCatching { summarize(it, ipForExit) }.getOrNull() }
                            val peers = status?.let { runCatching { listablePeers(it) }.getOrNull() }
                            withContext(Dispatchers.Main) {
                                connectionSummary = built
                                tailnetPeers = peers
                                if (managedPresence(peers) != managedPresence(managedPeers)) managedPeers = peers
                            }
                            // Background avatar sync, off the status just read.
                            val picUrl = if (reads.avatar && status != null) avatarToSync(status, account.avatarUrl) else null
                            if (picUrl != null) {
                                try {
                                    downloadAndCacheAvatar(context, account.id, picUrl)
                                    AccountManager.updateAccountAvatar(context, account.id, picUrl)
                                    withContext(Dispatchers.Main) {
                                        accounts.value = AccountManager.getAccounts(context)
                                        activeAccount = AccountManager.getActiveAccount(context)
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                    } else if (backendState != "Running") {
                        connectionSummary = null
                        tailnetPeers = null
                        managedPeers = null
                    }
                } else {
                    loginUrl = null
                    show410Warning = false
                    urlDetected = false
                }

                delay(MAIN_TICK_MS)
            }
        }
    }

    // The one question that goes on being asked while the screen is stopped: a login opened in
    // a Custom Tab stops this screen, the tab being on top of it, and the watchdog above with
    // it — yet it is the watchdog's Running that closes the tab (LoginTab.finished). So while
    // the screen is stopped with a login link on it, the backend alone is checked at the same
    // pace, until it runs or for as long as the tab would still be closed; nothing at all
    // otherwise. Back in front, the watchdog takes over.
    LaunchedEffect(pollLifecycle) {
        if (inPreview || demo != null) return@LaunchedEffect
        pollLifecycle.currentStateFlow.collectLatest { state ->
            if (state.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) || loginUrl == null) return@collectLatest
            val until = android.os.SystemClock.elapsedRealtime() + MAIN_LOGIN_WATCH_MS
            while (android.os.SystemClock.elapsedRealtime() < until) {
                delay(MAIN_TICK_MS)
                val running = withContext(Dispatchers.IO) {
                    runCatching { appctr.Appctr.isRunning() && appctr.Appctr.getBackendState() == "Running" }.getOrDefault(false)
                }
                if (running) {
                    context.findActivity()?.let { LoginTab.finished(it) }
                    break
                }
            }
        }
    }

    DisposableEffect(prefs) {
        prefs.registerOnSharedPreferenceChangeListener(profilePrefsListener)
        // Runs as the effect is applied, before a preview is captured — it
        // would overwrite the exit node a demo handed in.
        if (demo == null) exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(profilePrefsListener)
        }
    }

    DisposableEffect(globalPrefs) {
        globalPrefs.registerOnSharedPreferenceChangeListener(globalPrefsListener)
        isTunEnabled = globalPrefs.getBoolean("tun_mode_enabled", false)
        isTunDisplaced = globalPrefs.getBoolean(TunVpnService.DISPLACED_PREF, false)
        isRootYielded = globalPrefs.getBoolean("root_routing_yielded", false)
        isRootShared = globalPrefs.getBoolean("root_routing_shared", false)
        onDispose {
            globalPrefs.unregisterOnSharedPreferenceChangeListener(globalPrefsListener)
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (previewLayout == null && !editing) layout = MainLayoutStore.load(context, remoteTileOffered, arrangeable)
                isTunEnabled = GlobalSettings.isTunModeEnabled(context)
                isTunDisplaced = TunVpnService.isDisplaced(context)
                isRootEnabled = GlobalSettings.isRootModeEnabled(context)
                dnsHasFallback = GlobalSettings.dnsHasFallback(context)
                isRootYielded = GlobalSettings.isRootRoutingYielded(context)
                isRootShared = GlobalSettings.isRootRoutingShared(context)
                // Check battery optimization status (a binder call; not in a preview, which has none).
                if (!inPreview && demo == null) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    isBatteryOptimizationsIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        pm.isIgnoringBatteryOptimizations(context.packageName)
                    } else {
                        true
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Keyed on the profile's store too: the receiver writes that profile's exit node into the
    // state that holds it, and both are replaced on an account switch (see the watchdog above).
    // The swap is two back-to-back calls on the main thread, once per switch.
    DisposableEffect(context, prefs) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    "STARTING" -> { proxyState = "STARTING"; isProcessing = true }
                    "START" -> {
                        proxyState = "ACTIVE"
                        isProcessing = false
                        exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
                        show410Warning = false
                    }
                    "STOP" -> { proxyState = "STOPPED"; isProcessing = false }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("STARTING")
            addAction("START")
            addAction("STOP")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        }
    }

    if (showAddAccountDialog) {
        var newAccountServer by rememberSaveable { mutableStateOf("") }
        // A secret: never in the saved state, which the system writes out with the task.
        var newAccountAuthKey by remember { mutableStateOf("") }
        // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
        val dlgTitle = stringResource(R.string.main_add_account_title)
        val dlgNameLabel = stringResource(R.string.main_account_name_label)
        val dlgServerLabel = stringResource(R.string.settings_login_server_title)
        val dlgServerPlaceholder = stringResource(R.string.settings_login_server_placeholder)
        val dlgAuthKeyLabel = stringResource(R.string.main_add_account_authkey_label)
        val dlgAuthKeyPlaceholder = stringResource(R.string.main_add_account_authkey_placeholder)
        // Without a key a television signs in by a code for a phone, not in a browser.
        val dlgAuthKeyHint = stringResource(
            if (Feature.BROWSER_SIGN_IN.offered()) R.string.main_add_account_authkey_hint else R.string.tv_add_account_authkey_hint
        )
        val dlgAdd = stringResource(R.string.action_add)
        val dlgCancel = stringResource(R.string.action_cancel)
        val dlgHonestTitle = stringResource(R.string.settings_honest_hostinfo_title)
        val dlgHonestDesc = stringResource(R.string.honest_hostinfo_checkbox_desc)
        AppAlertDialog(
            onDismissRequest = { showAddAccountDialog = false; newAccountHonest = true },
            title = { Text(dlgTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // PageTextField: three fields in a row are a page of them, which a remote
                    // has to cross on its way to the buttons.
                    PageTextField(
                        value = newAccountName,
                        onValueChange = { newAccountName = it },
                        label = dlgNameLabel,
                        modifier = Modifier.fillMaxWidth()
                    )
                    PageTextField(
                        value = newAccountServer,
                        onValueChange = { newAccountServer = it },
                        label = dlgServerLabel,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = dlgServerPlaceholder
                    )
                    // Optional: with a key the profile registers on its first start,
                    // without one it goes through the browser like before.
                    PageTextField(
                        value = newAccountAuthKey,
                        onValueChange = { newAccountAuthKey = it },
                        label = dlgAuthKeyLabel,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = dlgAuthKeyPlaceholder
                    )
                    Text(
                        dlgAuthKeyHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { newAccountHonest = !newAccountHonest },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // The row is the remote's stop; the box inside it would be a second one.
                        Checkbox(checked = newAccountHonest, onCheckedChange = { newAccountHonest = it }, modifier = Modifier.tvNoFocus())
                        Column(modifier = Modifier.weight(1f)) {
                            Text(dlgHonestTitle, style = MaterialTheme.typography.bodyMedium)
                            Text(dlgHonestDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newAccountName.isNotBlank()) {
                        val acc = AccountManager.addAccount(context, newAccountName)
                        val accPrefs = context.getSharedPreferences("appctr_${acc.id}", Context.MODE_PRIVATE)
                        accPrefs.edit().putBoolean("do_reset", true).apply()
                        // Written before the first start, so the node registers with it.
                        ProfileHostinfo.setHonest(context, acc.id, newAccountHonest)
                        newAccountHonest = true
                        if (newAccountServer.isNotBlank()) {
                            accPrefs.edit().putString("login_server", newAccountServer.trim()).apply()
                        }
                        // Written before the first start, like the hostinfo choice: the
                        // service reads it out of the profile when it builds StartOptions.
                        if (newAccountAuthKey.isNotBlank()) {
                            accPrefs.edit().putString("authkey", newAccountAuthKey.trim()).apply()
                        }
                        accounts.value = AccountManager.getAccounts(context)
                        AccountManager.setActiveAccount(context, acc.id)
                        activeAccount = acc
                        newAccountName = ""
                        showAddAccountDialog = false

                        if (ProxyState.isActualRunning()) {
                            context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                        }
                    }
                }) { Text(dlgAdd) }
            },
            dismissButton = { TextButton(onClick = { showAddAccountDialog = false; newAccountHonest = true }) { Text(dlgCancel) } }
        )
    }

    if (showRenameAccountDialog) {
        val targetAcc = accountToRename ?: activeAccount
        var renameText by remember(targetAcc.id) { mutableStateOf(targetAcc.name) }
        // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
        val dlgTitle = stringResource(R.string.main_rename_account_title)
        val dlgNameLabel = stringResource(R.string.main_new_name_label)
        val dlgRename = stringResource(R.string.action_rename)
        val dlgCancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { 
                showRenameAccountDialog = false
                accountToRename = null
            },
            title = { Text(dlgTitle) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(dlgNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (renameText.isNotBlank()) {
                        AccountManager.renameAccount(context, targetAcc.id, renameText)
                        accounts.value = AccountManager.getAccounts(context)
                        activeAccount = AccountManager.getActiveAccount(context)
                        showRenameAccountDialog = false
                        accountToRename = null
                    }
                }) { Text(dlgRename) }
            },
            dismissButton = { 
                TextButton(onClick = { 
                    showRenameAccountDialog = false
                    accountToRename = null
                }) { Text(dlgCancel) } 
            }
        )
    }

    if (accountToDeleteConfirm != null) {
        val targetAcc = accountToDeleteConfirm!!
        // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
        val dlgTitle = stringResource(R.string.main_delete_account_confirm_title, targetAcc.name)
        val dlgText = stringResource(R.string.main_delete_account_confirm_text)
        val dlgDelete = stringResource(R.string.action_delete)
        val dlgCancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { accountToDeleteConfirm = null },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(dlgTitle) },
            text = { Text(dlgText) },
            confirmButton = {
                Button(
                    onClick = {
                        accountToDeleteConfirm = null
                        accountMenuExpanded = false
                        // Deleting the active, running account would pull its
                        // state dir out from under the live daemon; stop first.
                        val wasActiveRunning = targetAcc.id == activeAccount.id && ProxyState.isActualRunning(context)
                        if (wasActiveRunning) {
                            context.startService(Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" })
                        }
                        scope.launch(Dispatchers.IO) {
                            if (wasActiveRunning) { try { Thread.sleep(800) } catch (_: Exception) {} }
                            AccountManager.deleteAccount(context, targetAcc.id)
                            withContext(Dispatchers.Main) {
                                activeAccount = AccountManager.getActiveAccount(context)
                                accounts.value = AccountManager.getAccounts(context)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(dlgDelete)
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToDeleteConfirm = null }) {
                    Text(dlgCancel)
                }
            }
        )
    }

    if (showSwitchConfirmDialog != null) {
        // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
        val dlgTitle = stringResource(R.string.main_switch_account_title)
        val dlgText = stringResource(R.string.main_switch_account_text, showSwitchConfirmDialog!!.name)
        val dlgRestart = stringResource(R.string.main_restart_switch)
        val dlgCancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { showSwitchConfirmDialog = null },
            title = { Text(dlgTitle) },
            text = { Text(dlgText) },
            confirmButton = {
                Button(onClick = {
                    val target = showSwitchConfirmDialog!!
                    showSwitchConfirmDialog = null
                    isProcessing = true

                    // Move core logic to Service via RESTART_ACTION
                    AccountManager.setActiveAccount(context, target.id)
                    activeAccount = target
                    context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                }) { Text(dlgRestart) }
            },
            dismissButton = { TextButton(onClick = { showSwitchConfirmDialog = null }) { Text(dlgCancel) } }
        )
    }

    if (accountMenuExpanded) {
        val sheetState = rememberFullSheetState()
        // Strings come from the parent context, not stringResource() — see wrapContextWithLocale().
        val sheetTitle = context.getString(R.string.accounts_sheet_title)
        val sheetHelp = context.getString(R.string.accounts_sheet_help)
        val primaryHelp = context.getString(R.string.accounts_primary_help)
        val sheetEdit = context.getString(R.string.action_edit)
        val sheetDone = context.getString(R.string.accounts_done)
        val sheetAdd = context.getString(R.string.accounts_add)
        val strSignedIn = context.getString(R.string.accounts_signed_in)
        val strNeverSignedIn = context.getString(R.string.accounts_never_signed_in)
        val strNotConnected = context.getString(R.string.accounts_not_connected)

        // What the active account's node is right now. Read once per opening,
        // off the main thread; the other accounts have no daemon to ask and
        // describe themselves from their own preferences instead.
        var activeLiveLine by remember(activeAccount.id) { mutableStateOf<String?>(null) }
        LaunchedEffect(activeAccount.id, proxyState) {
            if (proxyState != "ACTIVE") { activeLiveLine = null; return@LaunchedEffect }
            val line = withContext(Dispatchers.IO) {
                runCatching {
                    val st = DaemonStatus.readOrNull() ?: return@runCatching null
                    val self = st.self ?: return@runCatching null
                    // The tailnet, not the node's full name: which tailnet this
                    // account is in is what tells it apart from the others, and
                    // the fqdn is long enough to push the address off the row.
                    val tailnet = st.magicDnsSuffix?.trim('.')?.takeIf { it.isNotEmpty() }
                        ?: self.dnsName?.trim('.')?.substringAfter('.')?.takeIf { it.isNotEmpty() }
                    val ip = self.tailscaleIPs?.firstOrNull { !it.contains(':') }
                    listOfNotNull(tailnet, ip).joinToString(" · ").takeIf { it.isNotEmpty() }
                }.getOrNull()
            }
            activeLiveLine = line
        }

        // What each profile says of itself, read once per opening and for every row together,
        // off the main thread: the first look at another profile's store loads its file, and
        // whether it ever registered can read its state. Done in the frame that opened the sheet,
        // that was file I/O per row; the sheet is still sliding in when this lands.
        var accountFacts by remember { mutableStateOf<Map<String, AccountManager.AccountFacts>>(emptyMap()) }
        LaunchedEffect(accounts.value) {
            val ids = accounts.value.map { it.id }
            accountFacts = withContext(Dispatchers.IO) { ids.associateWith { AccountManager.facts(context, it) } }
        }

        // With a remote the sheet opens on the account in use — OK there closes it and
        // changes nothing — wherever that account is in the list.
        val activeIndex = accounts.value.indexOfFirst { it.id == activeAccount.id }.coerceAtLeast(0)
        val accountsListState = androidx.compose.foundation.lazy.rememberLazyListState(
            initialFirstVisibleItemIndex = if (tv) activeIndex else 0
        )
        AppModalSheet(
            onDismissRequest = { accountMenuExpanded = false; accountsEditMode = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        sheetTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    // The actions used to hide behind a long press on a row.
                    TextButton(onClick = { accountsEditMode = !accountsEditMode }) {
                        Text(if (accountsEditMode) sheetDone else sheetEdit)
                    }
                }

                HelpText(
                    if (accountsEditMode) primaryHelp else sheetHelp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
                )

                Spacer(Modifier.height(12.dp))

                androidx.compose.foundation.lazy.LazyColumn(
                    state = accountsListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(accounts.value.size) { i ->
                        val account = accounts.value[i]
                        val isActive = account.id == activeAccount.id
                        val isPrimary = account.id == primaryId
                        val facts = accountFacts[account.id]
                        AccountRow(
                            account = account,
                            facts = facts,
                            active = isActive,
                            modifier = if (isActive) Modifier.tvInitialFocus() else Modifier,
                            liveLine = if (isActive) activeLiveLine else null,
                            editing = accountsEditMode,
                            signedInLabel = strSignedIn,
                            neverSignedInLabel = strNeverSignedIn,
                            notConnectedLabel = strNotConnected,
                            onClick = {
                                if (accountsEditMode) return@AccountRow
                                accountMenuExpanded = false
                                if (!isActive) {
                                    StatusAsides.bump(context, StatusAsides.SWITCHES)
                                    if (ProxyState.isActualRunning()) showSwitchConfirmDialog = account
                                    else {
                                        AccountManager.setActiveAccount(context, account.id)
                                        activeAccount = account
                                    }
                                }
                            },
                            onRename = {
                                accountToRename = account
                                showRenameAccountDialog = true
                            },
                            primary = isPrimary,
                            onMakePrimary = {
                                AccountManager.setPrimary(context, account.id)
                                primaryId = AccountManager.getPrimaryId(context)
                            },
                            onDelete = if (isPrimary) null else {
                                { accountToDeleteConfirm = account }
                            }
                        )
                    }

                    item {
                        // A permanent row rather than a button: adding an account is
                        // part of the same list everywhere this pattern is used.
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shapedClickable(MaterialTheme.shapes.medium) {
                                    accountsEditMode = false
                                    accountMenuExpanded = false
                                    showAddAccountDialog = true
                                },
                            shape = MaterialTheme.shapes.medium,
                            color = Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    sheetAdd,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // The other way in on a television: a key sent from a phone (TvKeySetup.kt). It takes
    // the sign-in screen's place while it is open and hands back to it when it is left.
    var keySetupOpen by remember { mutableStateOf(false) }
    if (signInOpen && keySetupOpen) {
        TvKeySetupScreen(
            onSignedIn = { keySetupOpen = false; signInOpen = false },
            onClose = { keySetupOpen = false },
        )
    } else if (signInOpen) {
        // The whole screen, in place of the dashboard, which comes back when the code is
        // left or has done its work.
        TvSignIn(
            account = activeAccount.name,
            url = loginUrl,
            onClose = { signInOpen = false; signInLeft = true },
            extraAction = { TvKeySetupButton(onClick = { keySetupOpen = true }) },
        )
    } else if (remoteOpen) {
        // The page, in place of the dashboard, which comes back with the remote on its tile.
        RemoteManagedScreen(onBack = { remoteOpen = false; remoteLeft = true })
    } else
    // A window lying on its side has no height to give a bar under the canvas: there the
    // editor's controls stand beside it (MainEditRail), and the window's edge is theirs.
    Row(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.weight(1f).then(
                if (editRail) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End)) else Modifier
            ),
            topBar = {
            if (editing) {
                // The screen is its own editor: the bar says so, and its arrow is one of the ways out.
                AppTopBar(
                    title = stringResource(R.string.main_layout_edit_title),
                    subtitle = stringResource(R.string.main_layout_edit_hint),
                    onBack = { editing = false },
                )
            } else
            AppTopBar(
                title = stringResource(R.string.app_name),
                subtitle = activeAccount.name,
                onTitleClick = { accountMenuExpanded = true },
                actions = {
                    if (proxyState == "ACTIVE") {
                        IconButton(onClick = {
                            val intent = Intent(context, TailscaledService::class.java).apply { action = "REFRESH_ACTION" }
                            context.startService(intent)
                            Toast.makeText(context, context.getString(R.string.main_refreshing_config), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.main_cd_refresh_config))
                        }
                    }
                    IfOffered(Feature.QR_SCANNER) {
                        IconButton(onClick = scanQr) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.qr_scan_action))
                        }
                    }
                    IfOffered(Feature.ADMIN_CONSOLE) {
                        IconButton(onClick = {
                            context.startActivity(Intent(context, AdminApiActivity::class.java))
                        }) {
                            Icon(Icons.Default.AdminPanelSettings, contentDescription = stringResource(R.string.main_cd_admin_api))
                        }
                    }
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.main_cd_about_licenses))
                    }
                }
            )
        },
        bottomBar = {
            if (editing && !editRail) {
                MainEditBar(
                    columns = layout.columns,
                    onColumns = { changeLayout(layout.withColumns(it)) },
                    canAdd = layout.addable(itemOffered).isNotEmpty(),
                    onAdd = { paletteOpen = true },
                    onReset = { confirmLayoutReset = true },
                    onDone = { editing = false },
                )
            }
        }
    ) { paddingValues ->
        // The screen is two things: what the service is doing, and where to go
        // next. Since it can be arranged (core/MainLayout.kt) those are a list:
        // blocks — the status card, the lines and pictures around it — and tiles.
        // A phone held upright draws the list as one flow; a window with the
        // width for it puts the blocks on one side and the tiles on the other,
        // each in the list's order — before that the one column simply grew
        // wider, and eight menu cards stretched into slabs.

        // What names a tap on a phone names the remote's OK on a television.
        val touchWording = Feature.TOUCH_GESTURES_HINTS.offered()

        // Neither of these is an error: the tunnel is up and the tailnet is
        // reachable either way. Sharing means the other client bypasses some
        // apps and we took the default route for exactly those, so it is the
        // narrower claim of the two and has to be tested first.
        val sharedWithForeignVpn = isRootEnabled && isRootShared
        val yieldedToForeignVpn = isRootEnabled && isRootYielded && !sharedWithForeignVpn
        // A selected exit node carries no traffic while we are yielded,
        // so the row must not read as "traffic is routed". Shared is the
        // opposite case: it does carry traffic, for the apps the other
        // VPN left out, so it stays an active row with a narrower label.
        val exitNodeInert = exitNodeIp.isNotEmpty() && yieldedToForeignVpn
        val exitNodePartial = exitNodeIp.isNotEmpty() && sharedWithForeignVpn
        val exitNodeActive = exitNodeIp.isNotEmpty() && !exitNodeInert
        val exitNodePaused = exitNodeIp.isEmpty() && exitNodePausedName.isNotEmpty()

        // The card says what is true: the service may be up while the
        // tailnet is not — still waiting for its map, signed out, or cut
        // off from every relay — and each of those reads differently.
        val serviceUp = proxyState == "ACTIVE" || proxyState == "CONNECTION_ISSUE" ||
            (proxyState == "LOGGED_OUT" && !inPreview && ProxyState.isActualRunning())
        val cardState = when {
            proxyState == "LOGGED_OUT" && serviceUp -> "NEEDS_LOGIN"
            proxyState == "CONNECTION_ISSUE" -> "DEGRADED"
            // Signed in, but the tailnet requires an admin to approve new
            // devices — the notification already said so; the card said "Active".
            proxyState == "ACTIVE" && daemonBackend == "NeedsMachineAuth" -> "NEEDS_APPROVAL"
            proxyState == "ACTIVE" && daemonBackend == "Starting" -> "CONNECTING"
            proxyState == "ACTIVE" && warningsDegradeConnection(shownWarnings, dnsHasFallback) -> "DEGRADED"
            else -> proxyState
        }

        // The screen being arranged does nothing: its cells are covered while it is, and this
        // stops the one tap a cover cannot — the end of the very press that started arranging.
        fun act(action: () -> Unit) {
            if (!editing) action()
        }

        // What the screen says without being asked, above the status card. Not the person's
        // to move or to take off, so it is not in the layout: it goes where the card goes.
        val banners: @Composable () -> Unit = {
            Column {
                // Not where there is no battery to be optimised for.
                if (!isBatteryOptimizationsIgnored && Feature.BATTERY_OPTIMISATION_PROMPT.offered()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clickable {
                            if (!context.tryStartActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
                                Toast.makeText(context, context.getString(R.string.main_cannot_open_battery_settings), Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(stringResource(R.string.main_battery_warn_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(stringResource(R.string.main_battery_warn_desc), 
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }

                if (show410Warning) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(stringResource(R.string.main_network_sync_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(stringResource(R.string.main_network_sync_desc), 
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }

                if (proxyState == "ACTIVE" && (yieldedToForeignVpn || sharedWithForeignVpn)) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(modifier = Modifier.width(12.dp))
                            // The weight keeps the two-line Russian description off the
                            // icon instead of pushing the row wider than the screen.
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (sharedWithForeignVpn) stringResource(R.string.main_root_shared_title)
                                    else stringResource(R.string.main_root_yielded_title),
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    if (sharedWithForeignVpn) stringResource(R.string.main_root_shared_desc)
                                    else stringResource(R.string.main_root_yielded_desc),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }
        }

        val exitNodeLine: @Composable (Modifier) -> Unit = { modifier ->
            Surface(
                color = if (exitNodeActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.large,
                modifier = modifier.fillMaxWidth()
                    .shapedClickable(MaterialTheme.shapes.large) { act { openExitNodeSheet() } }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (exitNodeActive) Icons.Default.Lock else Icons.Default.Public,
                        contentDescription = null,
                        tint = if (exitNodeActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    // The weight belongs on the text, not on a trailing spacer:
                    // the description carries an IP and used to squeeze the chevron out.
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when {
                                exitNodeInert -> stringResource(R.string.main_exit_node_inert_label)
                                exitNodePartial -> stringResource(R.string.main_exit_node_partial_label)
                                exitNodeActive -> stringResource(R.string.main_traffic_routed)
                                exitNodePaused -> stringResource(R.string.main_exit_node_paused_label)
                                else -> stringResource(R.string.main_exit_node_none_label)
                            },
                            fontWeight = FontWeight.Bold,
                            color = if (exitNodeActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            when {
                                exitNodeInert -> stringResource(R.string.main_exit_node_inert_desc, exitNodeIp)
                                exitNodePartial -> stringResource(R.string.main_exit_node_partial_desc, exitNodeIp)
                                exitNodeActive -> stringResource(R.string.main_exit_node_routed_desc, connectionSummary?.exitNodeName ?: exitNodeIp)
                                exitNodePaused -> stringResource(R.string.exitguard_paused_text, exitNodePausedName)
                                else -> stringResource(if (touchWording) R.string.main_exit_node_none_desc else R.string.tv_home_exit_node_none)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (exitNodeActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        tint = if (exitNodeActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // The card and what belongs to it: it is one item of the layout. [bare] is the card
        // alone, as the canvas shows it while the screen is being arranged.
        val statusCell: @Composable ColumnScope.(Modifier, Boolean) -> Unit = { cardModifier, bare ->
            // Taken off the screen, the line still shows while an exit node carries the
            // traffic or waits to come back: that is not something to find out later.
            if (!bare && proxyState == "ACTIVE" && MainItem.EXIT_NODE !in layout && (exitNodeIp.isNotEmpty() || exitNodePaused)) {
                exitNodeLine(Modifier.padding(bottom = 16.dp))
            }
            StatusCard(
                state = cardState,
                // Where the remote starts on this screen.
                modifier = cardModifier.tvInitialFocus()
                    .then(if (remoteTileOffered) Modifier.focusProperties { right = menuStart } else Modifier),
                issueTitle = shownWarnings.firstOrNull()?.let { healthTitle(it) },
                isProcessing = isProcessing,
                isTunEnabled = isTunEnabled,
                isTunDisplaced = isTunDisplaced,
                isFullTunnel = isFullTunnel,
                isRootEnabled = isRootEnabled,
                isYieldedToForeignVpn = yieldedToForeignVpn,
                isSharedWithForeignVpn = sharedWithForeignVpn,
                aside = statusAside,
                onLongPress = {
                    if (editing) return@StatusCard
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    statusAside = StatusAsides.pick(context, statusAside)
                }
            ) {
                if (isProcessing || editing) return@StatusCard
                StatusAsides.bump(context, StatusAsides.TOGGLES)

                if (proxyState == "ACTIVE" || proxyState == "STARTING" || proxyState == "CONNECTION_ISSUE" || proxyState == "LOGGED_OUT") {
                    requestStop()
                } else {
                    // SOCKS5 lives in global settings, not in the profile store.
                    val currentSocks = GlobalSettings.getString(context, "socks5", "127.0.0.1:48115")

                    if (currentSocks.isBlank()) {
                        Toast.makeText(context, context.getString(R.string.main_error_socks5_empty), Toast.LENGTH_LONG).show()
                        return@StatusCard
                    }

                    isProcessing = true
                    // The other build of the app, when it runs and would collide with
                    // this start, is asked about first (core/SiblingBuild.kt); within a
                    // short budget, and any failure starts as before.
                    scope.launch {
                        val found = SiblingBuild.check(context)
                        if (found == null) startNow() else { isProcessing = false; siblingFound = found }
                    }
                }
            }

            if (!bare) {
                if (serviceUp && shownWarnings.isNotEmpty() && cardState != "NEEDS_LOGIN") {
                    Spacer(modifier = Modifier.height(12.dp))
                    HealthBanner(
                        warnings = shownWarnings,
                        reconnecting = reconnectingRelays,
                        dnsHasFallback = dnsHasFallback,
                        onReconnectRelays = {
                            reconnectingRelays = true
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                            scope.launch(Dispatchers.IO) {
                                val err = runCatching { appctr.Appctr.reconnectRelays() }.getOrElse { it.message ?: "" }
                                withContext(Dispatchers.Main) {
                                    reconnectingRelays = false
                                    if (!err.isNullOrBlank()) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onOpenBypass = {
                            context.startActivity(
                                Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN_SECTION, "bypass")
                            )
                        }
                    )
                }

                if (proxyState == "LOGGED_OUT") {
                    Spacer(modifier = Modifier.height(16.dp))
                    LoggedOutCard(
                        loginUrl = loginUrl,
                        onConfigureProxy = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        },
                        onStop = { requestStop() },
                        // Where signing in is a code on this screen, the card's button shows it.
                        onShowCode = if (browserSignIn) null else ({ signInOpen = true }),
                        codeWasLeft = signInLeft,
                        accountId = activeAccount.id,
                    )
                }

                if (proxyState == "CONNECTION_ISSUE") {
                    Spacer(modifier = Modifier.height(16.dp))
                    ConnectionIssueCard(
                        onConfigureProxy = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        },
                        onStop = { requestStop() }
                    )
                }
            }
        }

        // A block with nothing to say right now is not drawn, and leaves no gap.
        fun blockShows(item: MainItem): Boolean = when (item) {
            MainItem.EXIT_NODE -> proxyState == "ACTIVE"
            // The summary stays whatever the card's warnings say: it is what the card is about.
            MainItem.SUMMARY -> cardState == "ACTIVE" && connectionSummary != null
            MainItem.SPEED_GRAPH -> cardState == "ACTIVE"
            MainItem.MANAGED -> managed.devices.isNotEmpty()
            else -> true
        }
        val block: @Composable ColumnScope.(MainItem, Modifier) -> Unit = { item, cardModifier ->
            when (item) {
                MainItem.STATUS -> statusCell(cardModifier, editing)
                MainItem.EXIT_NODE -> exitNodeLine(Modifier)
                MainItem.SUMMARY -> connectionSummary?.let { ConnectionSummaryRow(it) }
                MainItem.SPEED_GRAPH -> SpeedGraphCard(Modifier.fillMaxWidth()) {
                    act { context.startActivity(Intent(context, TailnetMapActivity::class.java)) }
                }
                MainItem.TIMELINE -> ConnectionTimelineCard(Modifier.fillMaxWidth())
                MainItem.MANAGED -> ManagedPills(managed)
                else -> Unit
            }
        }
        // The banners stand above the card, and above the exit-node line when that is the
        // card's upper neighbour — where they always were.
        fun bannersBefore(drawn: List<MainItem>): MainItem {
            val card = drawn.indexOf(MainItem.STATUS)
            return if (card > 0 && drawn[card - 1] == MainItem.EXIT_NODE) MainItem.EXIT_NODE else MainItem.STATUS
        }

        // Only what the device is offered in its usual place: what a television keeps
        // for its advanced settings, or does not have, is not a tile here.
        val placed = layout.shown(itemOffered)

        // fillHeight: beside the menu, the card takes the height the status
        // column has left, so the pair ends on one line (see the two-pane layout).
        val statusPane: @Composable ColumnScope.(fillHeight: Boolean) -> Unit = { fillHeight ->
            // Beside the menu the managed devices stand under it (pills, or a dashboard's tiles),
            // where they always were; only the one flow puts them where the person moved them.
            val drawn = placed.map { it.item }.filter { !it.isTile && it != MainItem.MANAGED && blockShows(it) }
            val anchor = bannersBefore(drawn)
            drawn.forEachIndexed { i, item ->
                if (i > 0) Spacer(modifier = Modifier.height(mainGap(drawn[i - 1], item)))
                if (item == anchor) banners()
                block(item, if (fillHeight) Modifier.weight(1f) else Modifier)
            }
        }

        fun openTile(item: MainItem) {
            when (item) {
                // It opens over this screen, and the remote comes back to it.
                MainItem.REMOTE -> { remoteLeft = false; remoteOpen = true }
                MainItem.EXIT_PICKER -> openExitNodeSheet()
                MainItem.SCAN -> scanQr()
                MainItem.ACCOUNTS -> accountMenuExpanded = true
                else -> item.screen(context)?.let { context.startActivity(it) }
            }
        }
        // A tile that takes a row has room for a second line, where there is something to say.
        @Composable
        fun tileState(item: MainItem): String? = when (item) {
            MainItem.REMOTE -> remoteTile?.let { remoteTileText(it) }
            MainItem.PEERS -> tailnetPeers?.takeIf { it.isNotEmpty() && proxyState == "ACTIVE" }
                ?.let { peers -> stringResource(R.string.summary_online, peers.count { it.online == true }, peers.size) }
            MainItem.EXIT_PICKER -> if (proxyState != "ACTIVE") null
                else connectionSummary?.exitNodeName ?: exitNodeIp.ifEmpty { stringResource(R.string.tile_exit_picker_none) }
            else -> null
        }
        @Composable
        fun entryOf(tile: MainLayout.Placed): MenuEntry {
            val item = tile.item
            val spec = item.spec(tv)
            return MenuEntry(
                title = stringResource(spec.title),
                icon = spec.icon,
                icon2 = spec.icon2,
                state = if (tile.wide) tileState(item) else null,
                stateWarns = item == MainItem.REMOTE && remoteTile?.blocked == true,
                wide = tile.wide,
                focusOnShow = item == MainItem.REMOTE && remoteLeft,
                onClick = { act { openTile(item) } },
            )
        }
        val menuEntries = placed.filter { it.item.isTile }.map { entryOf(it) }
        // How many cards there are: a tile with a row of its own takes no column.
        val cardCount = menuEntries.count { !it.wide }
        // The person's count where they chose one; otherwise what the width holds.
        fun columnsOr(auto: Int) = if (layout.columns == MainLayout.AUTO) auto else layout.columns
        val menuPane: @Composable (Int, androidx.compose.ui.unit.Dp) -> Unit = { auto, cardHeight ->
            val columns = columnsOr(auto)
            MenuGrid(
                columns = columns, cardHeight = cardHeight, entries = menuEntries, start = menuStart.takeIf { remoteTileOffered },
                // Beside the status there is no canvas to hold a tile on: a long press on the
                // menu opens the editor, which is the same list drawn as one flow.
                modifier = Modifier.onLongPressAnywhere(enabled = !tv) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    editing = true
                },
                beside = if (managedBeside(columns)) ({ ManagedTileCells(managed, places = 1) }) else null
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // A foldable decides the split itself: open like a book, each half
            // is a pane; half open like a laptop, the upright half shows the
            // status and the half lying flat holds the menu, under the thumbs.
            val fold = rememberFold()
            val window = rememberWindowLayout()
            val insetStart = paddingValues.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current)
            val insetTop = paddingValues.calculateTopPadding()
            // Two panes want width and a window lying on its side. Upright, a
            // tablet's 600dp is one comfortable column; split in two it was a
            // card and a grid floating in the middle of an empty screen (a
            // Lenovo tablet, 600x960dp, 2026-09-30).
            val wideEnoughForTwoPanes = fold is Fold.Vertical || (maxWidth >= 600.dp && maxWidth > maxHeight)
            // Landscape on a phone leaves about 390dp of height, so the cards
            // give some of it back; upright there is room to spare.
            val cardHeight = if (maxHeight < 440.dp) 84.dp else 96.dp
            // A short window centres its panes, which then fill it anyway; a
            // tall one starts them under the top bar, where the eye does.
            val paneArrangement = if (maxHeight < 480.dp) Arrangement.Center else Arrangement.Top
            // One flow, top to bottom: a phone held upright — and every window while the
            // screen is being arranged, because the flow is the arrangement itself.
            val flowWindow = fold !is Fold.Horizontal && (window.isPhone || fold != null) && !wideEnoughForTwoPanes
            if (editing || flowWindow) {
                // Held to a readable width on an upright tablet; on a phone that is the
                // whole width and two menu columns.
                val columnWidth = minOf(maxWidth, ReadableContentWidth)
                val scroll = rememberScrollState()
                // Arranging, everything on the screen is there to be moved, with or without
                // something to show; in use, only what has something to show.
                val cells = if (editing) placed else placed.filter { it.item.isTile || blockShows(it.item) }
                val drawn = cells.map { it.item }
                val inserts = listOf(MainCanvasInsert(bannersBefore(drawn)) { banners() })
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier.width(columnWidth).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(12.dp))
                        if (editing && !flowWindow) {
                            Text(
                                stringResource(R.string.main_layout_panes_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                        MainCanvas(
                            cells = cells,
                            columns = columnsOr(menuColumnsFor(columnWidth - 48.dp, cells.count { !it.full })),
                            cardHeight = cardHeight,
                            editing = editing,
                            scroll = scroll,
                            gestures = !tv,
                            inserts = inserts,
                            title = { stringResource(it.spec(tv).title) },
                            // The card keeps its own long press — the line it shows for a while.
                            // Anything else held down opens the editor, and is already being dragged.
                            onLongPress = { item -> (item != MainItem.STATUS).also { if (it) editing = true } },
                            onMove = { item, target -> changeLayout(layout.move(item, target)) },
                            onStep = { item, by -> changeLayout(layout.step(item, by, itemOffered)) },
                            onRemove = { changeLayout(layout.remove(it)) },
                            onSetWide = { item, wide -> changeLayout(layout.setWide(item, wide)) },
                        ) { cell ->
                            val item = cell.item
                            when {
                                item.isTile -> {
                                    val entry = entryOf(cell)
                                    // The remote's way into the menu from the card, and back onto the tile whose page it left.
                                    val focus = Modifier
                                        .then(if (remoteTileOffered && item == drawn.firstOrNull { it.isTile }) Modifier.focusRequester(menuStart) else Modifier)
                                        .then(if (entry.focusOnShow) Modifier.tvInitialFocus() else Modifier)
                                    if (cell.wide) WideMenuCard(entry, focus)
                                    else MenuCard(title = entry.title, icon = entry.icon, icon2 = entry.icon2, modifier = focus, onClick = entry.onClick)
                                }
                                blockShows(item) -> Column(horizontalAlignment = Alignment.CenterHorizontally) { block(item, Modifier) }
                                else -> MainBlockPlaceholder(
                                    item,
                                    note = if (item == MainItem.MANAGED) R.string.main_layout_when_managing else R.string.main_layout_when_connected,
                                )
                            }
                        }
                        AnimatedVisibility(visible = editing && layout.addable(itemOffered).isNotEmpty()) {
                            MainAddCell(onClick = { paletteOpen = true }, modifier = Modifier.padding(top = 16.dp))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            } else if (fold is Fold.Horizontal) {
                val menuColumns = menuColumnsFor(maxWidth - 48.dp, cardCount)
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((fold.top - insetTop).coerceAtLeast(0.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        statusPane(false)
                    }
                    Spacer(modifier = Modifier.height(fold.bottom - fold.top))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        menuPane(menuColumns, cardHeight)
                        ManagedPills(managed, Modifier.padding(top = 10.dp))
                    }
                }
            } else if (!window.isPhone && fold == null) {
                // Not a phone either way up, and no fold to lay out around: a tablet, a
                // desktop window. A dashboard that fills it (MainDashboard.kt) instead of
                // the phone's column or pair of panes parked at the top of the window. The
                // branches around this one are a phone's and a foldable's, as they were.
                MainDashboard(
                    window = window,
                    width = maxWidth,
                    height = maxHeight,
                    status = { fillHeight -> statusPane(fillHeight) },
                    // The managed devices are tiles under the cards, unless the menu has them in
                    // its own row already: a television's, which is counted below as it always was.
                    menu = { columns, rowHeight ->
                        menuPane(columns, rowHeight)
                        if (!managedBeside(columns)) ManagedTiles(managed)
                    },
                    menuCount = cardCount,
                    wideMenuRows = menuEntries.size - cardCount,
                    peers = tailnetPeers.takeIf { proxyState != "STOPPED" },
                    connected = proxyState == "ACTIVE" || proxyState == "STARTING",
                    onOpenPeers = { id ->
                        context.startActivity(Intent(context, PeersActivity::class.java).apply { id?.let { putExtra(PeersActivity.EXTRA_PEER_ID, it) } })
                    },
                )
            } else {
                // The status column is clamped: a share of the width so it
                // keeps its proportion in landscape, but never the half of a
                // tablet, where it would be a poster with nothing on it.
                // With a fold the status column ends a margin short of the hinge
                // and the menu starts a margin past it, so neither is drawn over it.
                val statusWidth = if (fold is Fold.Vertical) (fold.start - insetStart - 36.dp).coerceAtLeast(220.dp)
                                  else (maxWidth * 0.38f).coerceIn(280.dp, 400.dp)
                val paneGap = if (fold is Fold.Vertical) (fold.end - fold.start) + 24.dp else 24.dp
                // Columns follow the width the menu actually gets, not the
                // width of the window: the same phone in landscape and a
                // tablet in portrait can have the same window and very
                // different room left over here.
                val menuWidth = maxWidth - statusWidth - paneGap - 48.dp
                val menuColumns = menuColumnsFor(menuWidth, cardCount)
                // One row for the pair, as tall as the taller of the two. The
                // card takes up whatever the status column has left, so beside
                // a taller grid the column ends where the grid does — with the
                // exit-node line above the card or without it — and a column
                // that is already taller (banners, the summary) is left alone.
                // Intrinsic heights, not a measurement: the preview renders
                // one frame, and a measured height lands only on the second.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(horizontal = 24.dp),
                    verticalArrangement = paneArrangement
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Column(
                            modifier = Modifier.width(statusWidth).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            statusPane(true)
                        }
                        Spacer(modifier = Modifier.width(paneGap))
                        Column(modifier = Modifier.weight(1f)) {
                            menuPane(menuColumns, cardHeight)
                            ManagedPills(managed, Modifier.padding(top = 10.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
        if (editRail) {
            MainEditRail(
                columns = layout.columns,
                onColumns = { changeLayout(layout.withColumns(it)) },
                canAdd = layout.addable(itemOffered).isNotEmpty(),
                onAdd = { paletteOpen = true },
                onReset = { confirmLayoutReset = true },
                onDone = { editing = false },
            )
        }
    }

    // Back leaves the editor before it leaves the app.
    androidx.activity.compose.BackHandler(enabled = editing) { editing = false }

    if (paletteOpen) {
        MainPaletteSheet(
            items = layout.addable(itemOffered),
            onAdd = { changeLayout(layout.add(it)) },
            onDismiss = { paletteOpen = false },
        )
    }

    if (confirmLayoutReset) {
        // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
        val dlgTitle = stringResource(R.string.main_layout_reset_title)
        val dlgText = stringResource(R.string.main_layout_reset_text)
        val dlgReset = stringResource(R.string.main_layout_reset)
        val dlgCancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { confirmLayoutReset = false },
            icon = { Icon(Icons.Default.RestartAlt, null) },
            title = { Text(dlgTitle) },
            text = { Text(dlgText) },
            confirmButton = {
                Button(onClick = {
                    confirmLayoutReset = false
                    if (previewLayout == null) {
                        MainLayoutStore.reset(context)
                        layout = MainLayoutStore.load(context, remoteTileOffered, arrangeable)
                    }
                }) { Text(dlgReset) }
            },
            dismissButton = { TextButton(onClick = { confirmLayoutReset = false }) { Text(dlgCancel) } }
        )
    }

    if (showChangelog.value) {
        ChangelogDialog(onDismiss = {
            GlobalSettings.setLastSeenChangelogVersion(context, Changelog.currentVersion())
            showChangelog.value = false
        })
    }

    // Queued behind "What's new" so an update never stacks two dialogs.
    if (showAutostartAsk && !showChangelog.value) {
        AutostartAskDialog(onAnswered = { showAutostartAsk = false })
    }

    scannedText?.let { ScanResultSheet(text = it, onDismiss = { scannedText = null }) }
    // The device that showed the code is in front of the person who scanned it: taken for online.
    scannedPairing?.let { qr ->
        RemoteManagePage(peerId = qr.nodeId, name = qr.name, online = true, onClose = { scannedPairing = null }, code = qr.code)
    }

    if (confirmStop) {
        // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
        val dlgTitle = stringResource(R.string.tv_stop_title)
        val dlgText = stringResource(R.string.tv_stop_text)
        val dlgStop = stringResource(R.string.main_status_action_stop)
        val dlgCancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { confirmStop = false },
            icon = { Icon(Icons.Default.PowerSettingsNew, null) },
            title = { Text(dlgTitle) },
            text = { Text(dlgText) },
            confirmButton = {
                Button(onClick = { confirmStop = false; stopNow() }) { Text(dlgStop) }
            },
            // Opens on Cancel: the second OK of a double press must not be the one that stops.
            dismissButton = {
                TextButton(onClick = { confirmStop = false }, modifier = Modifier.tvInitialFocus()) { Text(dlgCancel) }
            }
        )
    }

    siblingFound?.let { found ->
        SiblingDialog(
            found = found,
            onOpen = {
                siblingFound = null
                SiblingBuild.launchIntent(context, found.packageName)?.let { runCatching { context.startActivity(it) } }
            },
            onStartAnyway = { siblingFound = null; startNow() },
            onDismiss = { siblingFound = null },
        )
    }

    if (showAboutDialog) {
        val versionName = remember {
            try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }
            catch (e: Exception) { "?" }
        }
        val coreVer = remember {
            try { appctr.Appctr.getCoreVersion() } catch (e: Exception) { "unknown" }
        }
        // Saveable: the answer of a check outlives a rotation, so a dialog that comes back
        // still offers the update — and the download under way, which UpdateDownload holds.
        var latestVersion by rememberSaveable { mutableStateOf<String?>(null) }
        var downloadUrl by rememberSaveable { mutableStateOf<String?>(null) }
        var isCheckingUpdate by remember { mutableStateOf(false) }
        val download by UpdateDownload.progress.collectAsState()
        val isDownloading = download != null
        val downloadProgress = download?.percent ?: 0

        // The one question the dialog answers first: is an update waiting?
        val updateReady = latestVersion?.let { isVersionNewer(versionName, it) } == true

        // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
        val dlgTitle = stringResource(R.string.main_about_title)
        // A beta build says so in one word after the name (docs/RELEASING.md).
        val dlgAppName = stringResource(R.string.app_name).let { if (AppVersion.isBeta(versionName)) stringResource(R.string.main_about_beta_name, it) else it }
        val dlgAppVersion = stringResource(R.string.main_app_version, "$versionName (${BuildConfig.GIT_HASH})")
        val dlgCoreVersion = stringResource(R.string.main_core_version, coreVer)
        val dlgUpToDate = stringResource(R.string.main_update_up_to_date)
        val dlgChecking = stringResource(R.string.main_update_checking)
        val dlgCheckUpdates = stringResource(R.string.main_check_updates)
        val dlgUpdateDownload = stringResource(R.string.main_update_download)
        val dlgUpdateInstall = stringResource(R.string.main_update_install_cached)
        val dlgWhatsNew = stringResource(R.string.main_about_whats_new)
        val dlgGithub = stringResource(R.string.action_github)
        val dlgIssueList = stringResource(R.string.issue_list_action)
        val dlgCreditsHeader = stringResource(R.string.main_about_credits)
        val dlgLicense = stringResource(R.string.main_license_text).trim()
        val dlgClose = stringResource(R.string.action_close)
        // Every person this build stands on, each next to where their work lives.
        val credits = listOf(
            Triple(stringResource(R.string.main_dev_app), "https://github.com/bropines", Icons.Default.Person),
            Triple(stringResource(R.string.main_dev_patch), "https://github.com/bropines/tailsocks", Icons.Default.Build),
            Triple(stringResource(R.string.main_dev_anet_patch), "https://github.com/Asutorufa/tailscale", Icons.Default.Extension),
            Triple(stringResource(R.string.main_dev_core), "https://github.com/tailscale/tailscale", Icons.Default.Hub),
            Triple(stringResource(R.string.main_dev_claude), CLAUDE_URL, Icons.Default.AutoAwesome)
        )
        // A link opens in the browser; where none answers — a television — it shows its address with a QR code.
        val links = rememberLinkOpener()
        // Lives inside this block on purpose: closing the dialog takes it down too.
        var showAboutBackdrop by remember { mutableStateOf(false) }
        var showVibeCoding by remember { mutableStateOf(false) }
        var showIssueReport by remember { mutableStateOf(false) }
        if (showIssueReport) {
            IssueReportDialog(onDismiss = { showIssueReport = false })
        }
        if (showAboutBackdrop) {
            AboutBackdrop(onDismiss = { showAboutBackdrop = false })
        }
        if (showVibeCoding) {
            VibeCodingScene(onDismiss = { showVibeCoding = false })
        }

        AppAlertDialog(
            onDismissRequest = { showAboutDialog = false },
            icon = { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(dlgTitle) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Identity and update state — one block, read before anything else.
                    // A step above the dialog itself: AlertDialogDefaults.containerColor is
                    // surfaceContainerHigh, so a card of that colour is the dialog's own
                    // ground and nothing is drawn at all. The credits below sit a step under
                    // it, so the block that carries the versions and the update button is
                    // the one that reads first.
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(dlgAppName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                dlgAppVersion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                dlgCoreVersion,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // A copy a store keeps up to date has no GitHub check to offer.
                            if (remember { UpdateChannel.selfUpdate(context) }) {
                                Spacer(Modifier.height(12.dp))

                                // Which version this is about stays on screen while it downloads:
                                // that is the moment the user most wants to see what is being
                                // installed, and the progress line only says how far it has got.
                                if (latestVersion != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (updateReady) Icons.Default.Download else Icons.Default.CheckCircle,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            if (updateReady) context.getString(R.string.main_new_version, latestVersion!!) else dlgUpToDate,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                if (isDownloading) {
                                    LinearProgressIndicator(
                                        progress = { if (downloadProgress > 0) downloadProgress / 100f else 0f },
                                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        context.getString(R.string.main_update_downloading, downloadProgress),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    if (updateReady) {
                                        // An update is waiting: the action is a real button, not a line of text.
                                        val destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
                                        val cleanVer = latestVersion!!.removePrefix("v")
                                        val destFile = UpdateDownload.apkFile(destDir, cleanVer)

                                        // Whether it is already downloaded parses the APK: off the main thread, not in
                                        // a frame. The button reads Download for the moment that takes.
                                        var isApkCached by remember(destFile.absolutePath) { mutableStateOf(false) }
                                        LaunchedEffect(destFile.absolutePath) {
                                            isApkCached = withContext(Dispatchers.IO) { UpdateDownload.isOwnApk(context, destFile) }
                                        }

                                        Button(
                                            onClick = {
                                                if (isApkCached) {
                                                    Toast.makeText(context, context.getString(R.string.main_update_installing), Toast.LENGTH_SHORT).show()
                                                    launchApkInstaller(context, destFile)
                                                    return@Button
                                                }
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                                                    Toast.makeText(context, context.getString(R.string.main_update_grant_perm), Toast.LENGTH_LONG).show()
                                                    try {
                                                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                                    } catch (e: Exception) {
                                                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                                                    }
                                                    return@Button
                                                }
                                                // No asset matched this device's ABI: send the user to the
                                                // release page. The old fallback downloaded
                                                // latest/download/app-release.apk, a file no release has had.
                                                if (downloadUrl == null) {
                                                    links.open(UpdateCheck.page(latestVersion!!), dlgGithub)
                                                    return@Button
                                                }
                                                // In the process's scope (UpdateDownload): a rotation or a
                                                // closed dialog no longer cancels it halfway.
                                                UpdateDownload.start(context, downloadUrl!!, cleanVer, destDir)
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(if (isApkCached) Icons.Default.SystemUpdate else Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(if (isApkCached) dlgUpdateInstall else dlgUpdateDownload)
                                        }
                                    } else {
                                        // Nothing to install: the button asks, and asks again after an answer.
                                        FilledTonalButton(
                                            onClick = {
                                                isCheckingUpdate = true
                                                scope.launch(Dispatchers.IO) {
                                                    try {
                                                        // The newest release — or beta, for whoever asked for them (core/UpdateCheck.kt).
                                                        val release = UpdateCheck.latest(GlobalSettings.isBetaUpdates(context))
                                                        val apkUrl = UpdateCheck.apkFor(release, Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                                                        withContext(Dispatchers.Main) {
                                                            latestVersion = release.tag
                                                            downloadUrl = apkUrl
                                                            isCheckingUpdate = false
                                                        }
                                                    } catch (e: Exception) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(context, context.getString(R.string.main_check_failed_format, e.message), Toast.LENGTH_SHORT).show()
                                                            isCheckingUpdate = false
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !isCheckingUpdate,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (isCheckingUpdate) {
                                                ButtonBusyIndicator(18.dp, color = LocalContentColor.current)
                                                Spacer(Modifier.width(8.dp))
                                                Text(dlgChecking)
                                            } else {
                                                Text(dlgCheckUpdates)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // The project's own pages, as one pair of buttons rather than two more
                    // lines of text. Equal halves, and a label too long for its half wraps
                    // rather than truncating — "Что нового" runs out of room at the larger
                    // font scales, and half a word is worse than two lines. IntrinsicSize.Min
                    // gives both buttons the height of the taller one so the pair stays even.
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAboutDialog = false; showChangelog.value = true },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.NewReleases, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(dlgWhatsNew, textAlign = TextAlign.Center)
                        }
                        OutlinedButton(
                            onClick = {
                                val opened = links.open(
                                    latestVersion?.let { UpdateCheck.page(it) } ?: "https://github.com/bropines/tailsocks",
                                    dlgGithub
                                )
                                if (opened) showAboutDialog = false
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(dlgGithub, textAlign = TextAlign.Center)
                        }
                    }

                    // Where a problem goes. The report is built and shown before anything
                    // leaves the device, so the button next to it only opens the list.
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showIssueReport = true },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.BugReport, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.issue_report_action), textAlign = TextAlign.Center)
                        }
                        OutlinedButton(
                            onClick = { links.open("https://github.com/bropines/tailsocks/issues", dlgIssueList) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.List, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(dlgIssueList, textAlign = TextAlign.Center)
                        }
                    }

                    // The people, kept together and out of the way of the state above.
                    Column {
                        Text(
                            dlgCreditsHeader,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                        )
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                credits.forEachIndexed { index, (label, url, icon) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            // These rows are the only way to the contributors'
                                            // pages, and a one-line credit measures 36dp —
                                            // under the 48dp a finger is entitled to.
                                            .heightIn(min = 48.dp)
                                            // The first row and Claude's also answer a long press.
                                            .combinedClickable(
                                                onClick = { links.open(url, label) },
                                                onLongClick = when {
                                                    index == 0 -> ({ showAboutBackdrop = true })
                                                    url == CLAUDE_URL -> ({ showVibeCoding = true })
                                                    else -> null
                                                }
                                            )
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            icon,
                                            null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        // No maxLines: a narrow screen wraps a credit, it never truncates one.
                                        Text(label, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }

                    // Ours in a line; everyone else's a tap away, with their full texts.
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(context, LicensesActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Gavel, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.licenses_title), textAlign = TextAlign.Center)
                    }
                    Text(
                        dlgLicense,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text(dlgClose) }
            }
        )
    }

    if (showExitNodeSheet) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val maxHeight = (configuration.screenHeightDp * 0.85f).dp

        // The picker's words, resolved out here in the parent composition — see
        // wrapContextWithLocale().
        val pickerStrings = exitNodePickerStrings()
        // The daemon's raw answer per node address, for as long as the sheet is open. A
        // reopened sheet starts clean: a figure measured minutes ago is not the figure now.
        // The pings run in the sheet's own scope, not the screen's, so closing the sheet
        // also drops the answers that were still on their way to this map.
        val exitNodePings = remember { mutableStateMapOf<String, String>() }
        val sheetScope = rememberCoroutineScope()
        fun pingExitNode(ip: String) {
            // A ping already in flight is not started again: the second answer would
            // overwrite the state the first one is about to write.
            if (exitNodePings[ip] == PING_IN_FLIGHT) return
            exitNodePings[ip] = PING_IN_FLIGHT
            sheetScope.launch { exitNodePings[ip] = pingPeer(ip) }
        }

        var pingingAllExitNodes by remember { mutableStateOf(false) }
        // The node Tailscale itself recommends, asked once the list is in: the row at the
        // top names it and the node's own row carries a mark. Held by the sheet, so a
        // reopened sheet asks again — the answer follows the network.
        var bestExitNode by remember { mutableStateOf<ExitNodeSuggestion.Outcome?>(null) }
        var bestAsked by remember { mutableIntStateOf(0) }
        LaunchedEffect(isExitNodesLoading, bestAsked) {
            if (isExitNodesLoading || exitNodes.isEmpty()) return@LaunchedEffect
            bestExitNode = null
            bestExitNode = fetchBestExitNode(exitNodes)
        }
        AppModalSheet(
            onDismissRequest = { showExitNodeSheet = false },
            // Without this the sheet opens half height and settles to its content a frame
            // later, which reads as a jump; the list also arrives after the spinner, so the
            // height it settles at is held from the start by the minimum below.
            sheetState = rememberFullSheetState()
        ) {
            ExitNodePicker(
                nodes = exitNodes,
                loading = isExitNodesLoading,
                selectedIp = exitNodeIp,
                pings = exitNodePings,
                pingingAll = pingingAllExitNodes,
                best = bestExitNode,
                strings = pickerStrings,
                onPingAll = {
                    pingingAllExitNodes = true
                    StatusAsides.bump(context, StatusAsides.PINGS)
                    sheetScope.launch {
                        pingAll(exitNodes.map { it.getPrimaryIp() }, exitNodePings)
                        pingingAllExitNodes = false
                    }
                },
                onPing = { pingExitNode(it) },
                onRetryBest = { bestAsked++ },
                onChoose = { id, ip -> applyExitNode(id, ip); showExitNodeSheet = false },
                onClose = { showExitNodeSheet = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp, max = maxHeight)
                    .navigationBarsPadding()
            )
        }
    }
}

/** One destination on the main screen. */
private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

/**
 * Whether [confirm] opens a system app's screen — the package installer's dialog a
 * PackageInstaller session asks for — and not this app's own or a third party's.
 */
private fun Context.isSystemInstallerConfirm(confirm: Intent): Boolean {
    val target = runCatching { confirm.resolveActivityInfo(packageManager, 0) }.getOrNull() ?: return false
    if (target.packageName == packageName) return false
    return target.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
}

/** Where the Claude credit in About leads. */
private const val CLAUDE_URL = "https://claude.ai"

/**
 * One account in the switcher. The active one carries what its node is doing
 * right now; the others describe themselves from their own preferences, which
 * is all that can be known without a daemon to ask: where they log in, whether
 * they have ever registered, and the two things decided before the first start.
 */
@Composable
private fun AccountRow(
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
