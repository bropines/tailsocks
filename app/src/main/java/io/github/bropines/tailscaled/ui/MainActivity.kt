package io.github.bropines.tailscaled.ui
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import io.github.bropines.tailscaled.core.AppJson
import java.lang.Runtime

import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

fun isVersionNewer(current: String, latest: String): Boolean {
    val cleanCurrent = current.removePrefix("v").substringBefore("-").replace(Regex("[^0-9.]"), "")
    val cleanLatest = latest.removePrefix("v").substringBefore("-").replace(Regex("[^0-9.]"), "")
    val c = cleanCurrent.split(".").map { it.toIntOrNull() ?: 0 }
    val l = cleanLatest.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(c.size, l.size)) {
        val cVal = c.getOrNull(i) ?: 0
        val lVal = l.getOrNull(i) ?: 0
        if (lVal > cVal) return true
        if (lVal < cVal) return false
    }
    // Equal base numeric versions are not treated as an update. The old dev/debug
    // branch here also returned false, so it was dead and has been dropped.
    return false
}

fun launchApkInstaller(context: Context, apkFile: java.io.File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(context, context.getString(R.string.main_update_grant_perm), Toast.LENGTH_LONG).show()
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        }
        return
    }

    try {
        val apkUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(installIntent)
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "FileProvider install intent failed, falling back to PackageInstaller session", e)
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.openWrite("tailsocks_update", 0, apkFile.length()).use { output ->
                apkFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                session.fsync(output)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                action = "ACTION_INSTALL_COMPLETE"
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent, flags)

            session.commit(pendingIntent.intentSender)
            session.close()
        } catch (fallbackEx: Exception) {
            Toast.makeText(context, context.getString(R.string.main_installer_failed_format, fallbackEx.message), Toast.LENGTH_SHORT).show()
        }
    }
}

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

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val showAccountSwitcher = mutableStateOf(false)
    private val showChangelog = mutableStateOf(false)

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

        checkNotificationPermission()
        // Runs only past the onboarding gate above, so the wizard is never
        // interrupted; a fresh install records its version and shows nothing.
        // Must run before handleAppStartup(): that launches an IO coroutine which
        // writes last_update_time, and checkWhatsNew() reads it to tell a fresh
        // install from an upgrade.
        if (savedInstanceState == null) checkWhatsNew()
        handleAppStartup()
        checkForUpdatesSilent()
        handleIntent(intent)

        setContent {
            TailSocksTheme {
                MainScreen(showAccountSwitcher, showChangelog)
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
                    if (confirm != null) {
                        try {
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

    private fun checkForUpdatesSilent() {
        val scope = kotlinx.coroutines.MainScope()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
                val connection = java.net.URL("https://api.github.com/repos/bropines/tailsocks/releases/latest").openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = AppJson.parseToJsonElement(response).jsonObject
                    val tag = json["tag_name"]!!.jsonPrimitive.content
                    if (isVersionNewer(currentVersion, tag)) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, getString(R.string.main_update_available_format, tag), Toast.LENGTH_LONG).show()
                        }
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
    showChangelog: MutableState<Boolean> = remember { mutableStateOf(false) }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var activeAccount by remember { mutableStateOf(AccountManager.getActiveAccount(context)) }
    val accounts = remember { mutableStateOf(AccountManager.getAccounts(context)) }
    
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var showRenameAccountDialog by remember { mutableStateOf(false) }
    var showSwitchConfirmDialog by remember { mutableStateOf<TailscaleAccount?>(null) }
    var accountOptionsModal by remember { mutableStateOf<TailscaleAccount?>(null) }
    var accountToDeleteConfirm by remember { mutableStateOf<TailscaleAccount?>(null) }
    var accountToRename by remember { mutableStateOf<TailscaleAccount?>(null) }
    var editingAccountId by remember { mutableStateOf<String?>(null) }
    
    var newAccountName by remember { mutableStateOf("") }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
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
    // Root Mode routes through the kernel interface, not the VpnService, so the
    // card has to name it: "Active" alone reads as plain proxy mode.
    var isRootEnabled by remember { mutableStateOf(GlobalSettings.isRootModeEnabled(context)) }
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
            if (key == "root_routing_yielded") {
                isRootYielded = sharedPreferences.getBoolean("root_routing_yielded", false)
            }
            if (key == "root_routing_shared") {
                isRootShared = sharedPreferences.getBoolean("root_routing_shared", false)
            }
        }
    }

    var proxyState by remember { mutableStateOf(if (ProxyState.isActualRunning(context)) "ACTIVE" else "STOPPED") }
    var exitNodeIp by remember(activeAccount.id) { mutableStateOf(prefs.getString("exit_node_ip", "") ?: "") }
    // TunVpnService establishes a *full* tunnel exactly when an exit node is configured.
    // The old `tun_full_tunnel` pref was never written by anything and always read false,
    // so derive the indicator from the live exit-node state to keep the UI truthful.
    val isFullTunnel = exitNodeIp.isNotEmpty()

    val profilePrefsListener = remember(activeAccount.id) {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "exit_node_ip") {
                exitNodeIp = sharedPreferences.getString("exit_node_ip", "") ?: ""
            }
        }
    }
    var isProcessing by remember { mutableStateOf(false) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var show410Warning by remember { mutableStateOf(false) }

    var showExitNodeSheet by remember { mutableStateOf(false) }
    var exitNodes by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var isExitNodesLoading by remember { mutableStateOf(false) }

    fun applyExitNode(id: String, ip: String) {
        exitNodeIp = ip
        val editor = prefs.edit()
        editor.putString("exit_node_ip", ip)
        editor.putString("exit_node_id", id)
        editor.apply()
        
        scope.launch(Dispatchers.IO) {
            if (appctr.Appctr.isRunning()) {
                appctr.Appctr.setPrefs("{\"ExitNodeID\": \"$id\", \"ExitNodeIDSet\": true}")
            } else if (ProxyState.isActualRunning(context)) {
                TailscaledService.requestApplySettings(context)
            }
            updateAllWidgets(context)
            if (GlobalSettings.isTunModeEnabled(context)) {
                context.startService(Intent(context, TunVpnService::class.java).apply {
                    action = TunVpnService.ACTION_START
                })
            }
        }
    }

    // Watchdog: Sync UI state with actual daemon status
    LaunchedEffect(Unit) {
        var urlDetected = false
        var lastAvatarSync = 0L
        var loggedOutSeconds = 0
        while (true) {
            val isProcessAlive = try { appctr.Appctr.isRunning() } catch (e: Exception) { false }
            val backendState = if (isProcessAlive) {
                try { appctr.Appctr.getBackendState() } catch (e: Exception) { "Error" }
            } else "Stopped"

            if (isProcessAlive && backendState == "Running") {
                val wasLoggedIn = prefs.getBoolean("was_logged_in", false)
                if (!wasLoggedIn) {
                    prefs.edit().putBoolean("was_logged_in", true).apply()
                }
                // A running backend means any start/login transition is over. If
                // isProcessing was left set (no START broadcast reached this
                // screen, e.g. a slow browser login), the sync below never ran and
                // the "connection issue" card stayed up over a working tailnet.
                if (isProcessing && (proxyState == "STARTING" || proxyState == "LOGGED_OUT" || proxyState == "CONNECTION_ISSUE")) {
                    isProcessing = false
                }
            }

            // Sync state if not explicitly in transition
            if (!isProcessing) {
                proxyState = if (isProcessAlive) {
                    if (backendState == "NeedsLogin" || backendState == "NoState") {
                        loggedOutSeconds += 2
                        if (loggedOutSeconds >= 10) {
                            if (prefs.getBoolean("was_logged_in", false) && loginUrl.isNullOrBlank()) {
                                "CONNECTION_ISSUE"
                            } else {
                                "LOGGED_OUT"
                            }
                        } else {
                            if (proxyState == "STOPPED") "ACTIVE" else proxyState
                        }
                    } else {
                        loggedOutSeconds = 0
                        "ACTIVE"
                    }
                } else {
                    loggedOutSeconds = 0
                    "STOPPED"
                }
            }

            if (isProcessAlive) {
                val url = try { appctr.Appctr.getLoginURL() } catch (e: Exception) { "" }
                loginUrl = if (url.isNullOrBlank()) null else url
                
                if (loginUrl != null) urlDetected = true
                else if (urlDetected) {
                    show410Warning = false
                    urlDetected = false
                }

                val lastErr = try { appctr.Appctr.getLastError() } catch (e: Exception) { "" }
                if (lastErr == "410_GONE") show410Warning = true

                // Background avatar sync
                val now = System.currentTimeMillis()
                if (now - lastAvatarSync > 30000) { // Every 30 seconds
                    lastAvatarSync = now
                    scope.launch(Dispatchers.IO) {
                        try {
                            val pJson = appctr.Appctr.getStatusFromAPI()
                            if (!pJson.startsWith("Error") && pJson.isNotBlank()) {
                                val status = AppJson.decodeFromString<StatusResponse>(pJson)
                                val selfUserId = status.self?.userID
                                val selfUser = status.users?.get(selfUserId?.toString()) ?: status.users?.values?.firstOrNull()
                                val picUrl = selfUser?.profilePicUrl
                                if (!picUrl.isNullOrEmpty() && picUrl != activeAccount.avatarUrl) {
                                    downloadAndCacheAvatar(context, activeAccount.id, picUrl)
                                    AccountManager.updateAccountAvatar(context, activeAccount.id, picUrl)
                                    withContext(Dispatchers.Main) {
                                        accounts.value = AccountManager.getAccounts(context)
                                        activeAccount = AccountManager.getActiveAccount(context)
                                    }
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
            } else {
                loginUrl = null
                show410Warning = false
                urlDetected = false
            }

            // Check battery optimization status
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            isBatteryOptimizationsIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }

            delay(2000)
        }
    }

    DisposableEffect(prefs) {
        prefs.registerOnSharedPreferenceChangeListener(profilePrefsListener)
        exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(profilePrefsListener)
        }
    }

    DisposableEffect(globalPrefs) {
        globalPrefs.registerOnSharedPreferenceChangeListener(globalPrefsListener)
        isTunEnabled = globalPrefs.getBoolean("tun_mode_enabled", false)
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
                isTunEnabled = GlobalSettings.isTunModeEnabled(context)
                isRootEnabled = GlobalSettings.isRootModeEnabled(context)
                isRootYielded = GlobalSettings.isRootRoutingYielded(context)
                isRootShared = GlobalSettings.isRootRoutingShared(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(context) {
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
        var newAccountServer by remember { mutableStateOf("") }
        // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
        val dlgTitle = stringResource(R.string.main_add_account_title)
        val dlgNameLabel = stringResource(R.string.main_account_name_label)
        val dlgServerLabel = stringResource(R.string.settings_login_server_title)
        val dlgServerPlaceholder = stringResource(R.string.settings_login_server_placeholder)
        val dlgAdd = stringResource(R.string.action_add)
        val dlgCancel = stringResource(R.string.action_cancel)
        AlertDialog(
            onDismissRequest = { showAddAccountDialog = false },
            title = { Text(dlgTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newAccountName,
                        onValueChange = { newAccountName = it },
                        label = { Text(dlgNameLabel) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newAccountServer,
                        onValueChange = { newAccountServer = it },
                        label = { Text(dlgServerLabel) },
                        placeholder = { Text(dlgServerPlaceholder) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newAccountName.isNotBlank()) {
                        val acc = AccountManager.addAccount(context, newAccountName)
                        val accPrefs = context.getSharedPreferences("appctr_${acc.id}", Context.MODE_PRIVATE)
                        accPrefs.edit().putBoolean("do_reset", true).apply()
                        if (newAccountServer.isNotBlank()) {
                            accPrefs.edit().putString("login_server", newAccountServer.trim()).apply()
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
            dismissButton = { TextButton(onClick = { showAddAccountDialog = false }) { Text(dlgCancel) } }
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
        AlertDialog(
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

    if (accountOptionsModal != null) {
        val targetAcc = accountOptionsModal!!
        // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
        val dlgSubtitle = stringResource(R.string.main_account_options_subtitle, targetAcc.name)
        val dlgSwitchTo = stringResource(R.string.main_switch_to_account)
        val dlgRename = stringResource(R.string.action_rename)
        val dlgDelete = stringResource(R.string.action_delete)
        val dlgCancel = stringResource(R.string.action_cancel)
        AlertDialog(
            onDismissRequest = { accountOptionsModal = null },
            icon = { Icon(Icons.Default.ManageAccounts, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(targetAcc.name, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        dlgSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    
                    if (targetAcc.id != activeAccount.id) {
                        FilledTonalButton(
                            onClick = {
                                val accToSwitch = targetAcc
                                accountOptionsModal = null
                                accountMenuExpanded = false
                                if (ProxyState.isActualRunning()) showSwitchConfirmDialog = accToSwitch
                                else { 
                                    AccountManager.setActiveAccount(context, accToSwitch.id)
                                    activeAccount = accToSwitch 
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(dlgSwitchTo, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    
                    OutlinedButton(
                        onClick = {
                            accountToRename = targetAcc
                            accountOptionsModal = null
                            showRenameAccountDialog = true
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(dlgRename, fontWeight = FontWeight.SemiBold)
                    }
                    
                    if (targetAcc.id != "default") {
                        Button(
                            onClick = {
                                accountToDeleteConfirm = targetAcc
                                accountOptionsModal = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(dlgDelete, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { accountOptionsModal = null }) {
                    Text(dlgCancel)
                }
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
        AlertDialog(
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
        AlertDialog(
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
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // Strings come from the parent context, not stringResource() — see wrapContextWithLocale().
        ModalBottomSheet(
            onDismissRequest = { accountMenuExpanded = false },
            sheetState = sheetState
        ) {
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            val activeAvatarFile = remember(activeAccount.id) { java.io.File(context.filesDir, "avatars/${activeAccount.id}.png") }
            val activeBitmap = remember(activeAvatarFile) {
                if (activeAvatarFile.exists()) {
                    try {
                        android.graphics.BitmapFactory.decodeFile(activeAvatarFile.absolutePath)
                    } catch (e: Exception) { null }
                } else null
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (activeBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = activeBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 12.dp)
                            .size(48.dp)
                            .clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 12.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ManageAccounts,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Text(
                    context.getString(R.string.main_switch_account_header),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))

                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(accounts.value.size) { i ->
                        val account = accounts.value[i]
                        val isActive = account.id == activeAccount.id
                        val isEditing = editingAccountId == account.id
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .combinedClickable(
                                        onClick = {
                                            if (editingAccountId != null) {
                                                editingAccountId = null
                                            } else {
                                                accountMenuExpanded = false
                                                if (account.id != activeAccount.id) {
                                                    if (ProxyState.isActualRunning()) showSwitchConfirmDialog = account
                                                    else { AccountManager.setActiveAccount(context, account.id); activeAccount = account }
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            editingAccountId = if (isEditing) null else account.id
                                        }
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                border = if (isActive) androidx.compose.foundation.BorderStroke(
                                    1.5.dp,
                                    MaterialTheme.colorScheme.primary
                                ) else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val avatarFile = remember(account.id) { java.io.File(context.filesDir, "avatars/${account.id}.png") }
                                    val bitmap = remember(avatarFile) {
                                        if (avatarFile.exists()) {
                                            try {
                                                android.graphics.BitmapFactory.decodeFile(avatarFile.absolutePath)
                                            } catch (e: Exception) { null }
                                        } else null
                                    }

                                    if (bitmap != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    } else {
                                        val nameLower = account.name.lowercase()
                                        val (smartIcon, smartColor) = when {
                                            nameLower.contains("github") -> Icons.Default.Hub to Color(0xFFFCC624)
                                            nameLower.contains("headscale") -> Icons.Default.Cloud to Color(0xFF0078D4)
                                            nameLower.contains("google") || nameLower.contains("gmail") -> Icons.Default.Email to Color(0xFFE91E63)
                                            else -> Icons.Default.AccountCircle to (if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(smartColor.copy(alpha = 0.12f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                smartIcon,
                                                null,
                                                tint = smartColor,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        account.name,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isActive) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.Check,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = isEditing,
                                enter = fadeIn() + expandHorizontally(),
                                exit = fadeOut() + shrinkHorizontally()
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalIconButton(
                                        onClick = {
                                            editingAccountId = null
                                            accountToRename = account
                                            showRenameAccountDialog = true
                                        },
                                        modifier = Modifier.size(40.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = context.getString(R.string.action_rename),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    if (account.id != "default") {
                                        FilledTonalIconButton(
                                            onClick = {
                                                editingAccountId = null
                                                accountToDeleteConfirm = account
                                            },
                                            modifier = Modifier.size(40.dp),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                                            ),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = context.getString(R.string.action_delete),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = {
                        editingAccountId = null
                        accountMenuExpanded = false
                        showAddAccountDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        context.getString(R.string.action_add),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

        Scaffold(
            topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { accountMenuExpanded = true }) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // The account name is free text; weighted so the caret stays visible.
                            Text(
                                activeAccount.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
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
                    IconButton(onClick = {
                        context.startActivity(Intent(context, AdminApiActivity::class.java))
                    }) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = stringResource(R.string.main_cd_admin_api))
                    }
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.main_cd_about_licenses))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            if (!isBatteryOptimizationsIgnored) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clickable {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
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
                    shape = RoundedCornerShape(16.dp),
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

            // Neither of these is an error: the tunnel is up and the tailnet is
            // reachable either way. Sharing means the other client bypasses some
            // apps and we took the default route for exactly those, so it is the
            // narrower claim of the two and has to be tested first.
            val sharedWithForeignVpn = isRootEnabled && isRootShared
            val yieldedToForeignVpn = isRootEnabled && isRootYielded && !sharedWithForeignVpn
            if (proxyState == "ACTIVE" && (yieldedToForeignVpn || sharedWithForeignVpn)) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
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

            if (proxyState == "ACTIVE") {
                // A selected exit node carries no traffic while we are yielded,
                // so the row must not read as "traffic is routed". Shared is the
                // opposite case: it does carry traffic, for the apps the other
                // VPN left out, so it stays an active row with a narrower label.
                val exitNodeInert = exitNodeIp.isNotEmpty() && yieldedToForeignVpn
                val exitNodePartial = exitNodeIp.isNotEmpty() && sharedWithForeignVpn
                val exitNodeActive = exitNodeIp.isNotEmpty() && !exitNodeInert
                Surface(
                    color = if (exitNodeActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clickable {
                        showExitNodeSheet = true
                        isExitNodesLoading = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val pJson = appctr.Appctr.getStatusFromAPI()
                                if (!pJson.startsWith("Error") && pJson.isNotBlank()) {
                                    val status = AppJson.decodeFromString<StatusResponse>(pJson)
                                    val nodes = status.peers?.values?.filter { it.exitNodeOption == true }?.toList() ?: emptyList()
                                    withContext(Dispatchers.Main) { exitNodes = nodes }
                                }
                            } catch (e: Exception) {}
                            withContext(Dispatchers.Main) { isExitNodesLoading = false }
                        }
                    }
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
                                    else -> stringResource(R.string.main_exit_node_none_label)
                                },
                                fontWeight = FontWeight.Bold,
                                color = if (exitNodeActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                when {
                                    exitNodeInert -> stringResource(R.string.main_exit_node_inert_desc, exitNodeIp)
                                    exitNodePartial -> stringResource(R.string.main_exit_node_partial_desc, exitNodeIp)
                                    exitNodeActive -> stringResource(R.string.main_exit_node_routed_desc, exitNodeIp)
                                    else -> stringResource(R.string.main_exit_node_none_desc)
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

            StatusCard(
                state = if (proxyState == "CONNECTION_ISSUE" || (proxyState == "LOGGED_OUT" && ProxyState.isActualRunning())) "ACTIVE" else proxyState,
                isProcessing = isProcessing,
                isTunEnabled = isTunEnabled,
                isFullTunnel = isFullTunnel,
                isRootEnabled = isRootEnabled,
                isYieldedToForeignVpn = yieldedToForeignVpn,
                isSharedWithForeignVpn = sharedWithForeignVpn
            ) {
                if (isProcessing) return@StatusCard

                if (proxyState == "ACTIVE" || proxyState == "STARTING" || proxyState == "CONNECTION_ISSUE" || proxyState == "LOGGED_OUT") {
                    isProcessing = true
                    val intent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
                    context.startService(intent)
                } else {
                    // SOCKS5 lives in global settings, not in the profile store.
                    val currentSocks = GlobalSettings.getString(context, "socks5", "127.0.0.1:48115")

                    if (currentSocks.isBlank()) {
                        Toast.makeText(context, context.getString(R.string.main_error_socks5_empty), Toast.LENGTH_LONG).show()
                        return@StatusCard
                    }

                    isProcessing = true
                    val intent = Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
                    ContextCompat.startForegroundService(context, intent)
                }
            }

            if (proxyState == "LOGGED_OUT") {
                Spacer(modifier = Modifier.height(16.dp))
                LoggedOutCard(
                    loginUrl = loginUrl,
                    onConfigureProxy = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    },
                    onStop = {
                        isProcessing = true
                        val intent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
                        context.startService(intent)
                    }
                )
            }

            if (proxyState == "CONNECTION_ISSUE") {
                Spacer(modifier = Modifier.height(16.dp))
                ConnectionIssueCard(
                    onConfigureProxy = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    },
                    onStop = {
                        isProcessing = true
                        val intent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
                        context.startService(intent)
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = stringResource(R.string.menu_console), icon = Icons.Default.PlayArrow, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, ConsoleActivity::class.java))
                }
                MenuCard(title = stringResource(R.string.menu_peers), icon = Icons.Default.Share, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, PeersActivity::class.java))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = stringResource(R.string.menu_logs), icon = Icons.AutoMirrored.Filled.List, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, LogsActivity::class.java))
                }
                MenuCard(title = stringResource(R.string.menu_files), icon = Icons.Default.Folder, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, FilesActivity::class.java))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = stringResource(R.string.menu_dns), icon = Icons.Default.Language, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, DnsActivity::class.java))
                }
                MenuCard(title = stringResource(R.string.menu_netcheck), icon = Icons.Default.Refresh, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, NetcheckActivity::class.java))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = stringResource(R.string.menu_settings), icon = Icons.Default.Settings, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                }
                MenuCard(title = stringResource(R.string.menu_serve), icon = Icons.Default.Public, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, ServeActivity::class.java))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
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

    if (showAboutDialog) {
        val versionName = remember {
            try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }
            catch (e: Exception) { "?" }
        }
        val coreVer = remember {
            try { appctr.Appctr.getCoreVersion() } catch (e: Exception) { "unknown" }
        }
        var latestVersion by remember { mutableStateOf<String?>(null) }
        var downloadUrl by remember { mutableStateOf<String?>(null) }
        var isCheckingUpdate by remember { mutableStateOf(false) }
        var isDownloading by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableIntStateOf(0) }

        // The one question the dialog answers first: is an update waiting?
        val updateReady = latestVersion?.let { isVersionNewer(versionName, it) } == true

        // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
        val dlgTitle = stringResource(R.string.main_about_title)
        val dlgAppName = stringResource(R.string.app_name)
        val dlgAppVersion = stringResource(R.string.main_app_version, versionName)
        val dlgCoreVersion = stringResource(R.string.main_core_version, coreVer)
        val dlgUpToDate = stringResource(R.string.main_update_up_to_date)
        val dlgChecking = stringResource(R.string.main_update_checking)
        val dlgCheckUpdates = stringResource(R.string.main_check_updates)
        val dlgUpdateDownload = stringResource(R.string.main_update_download)
        val dlgUpdateInstall = stringResource(R.string.main_update_install_cached)
        val dlgWhatsNew = stringResource(R.string.main_about_whats_new)
        val dlgGithub = stringResource(R.string.action_github)
        val dlgCreditsHeader = stringResource(R.string.main_about_credits)
        val dlgLicense = stringResource(R.string.main_license_text).trim()
        val dlgClose = stringResource(R.string.action_close)
        // Every person this build stands on, each next to where their work lives.
        val credits = listOf(
            Triple(stringResource(R.string.main_dev_app), "https://github.com/bropines", Icons.Default.Person),
            Triple(stringResource(R.string.main_dev_patch), "https://github.com/bropines/tailsocks", Icons.Default.Build),
            Triple(stringResource(R.string.main_dev_anet_patch), "https://github.com/Asutorufa/tailscale", Icons.Default.Extension),
            Triple(stringResource(R.string.main_dev_core), "https://github.com/tailscale/tailscale", Icons.Default.Hub)
        )
        val openLink: (String) -> Unit = { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "No handler for $url", e)
            }
        }
        // Lives inside this block on purpose: closing the dialog takes it down too.
        var showAboutBackdrop by remember { mutableStateOf(false) }
        if (showAboutBackdrop) {
            AboutBackdrop(onDismiss = { showAboutBackdrop = false })
        }

        AlertDialog(
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
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
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
                                    val destFile = java.io.File(destDir, "tailsocks-update-$cleanVer.apk")

                                    var isApkCached by remember(destFile.absolutePath) {
                                        mutableStateOf(
                                            if (destFile.exists() && destFile.length() > 0) {
                                                try {
                                                    val pInfo = context.packageManager.getPackageArchiveInfo(destFile.absolutePath, 0)
                                                    pInfo != null && pInfo.packageName == context.packageName
                                                } catch (e: Exception) {
                                                    false
                                                }
                                            } else false
                                        )
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
                                            val targetUrl = downloadUrl ?: "https://github.com/bropines/tailsocks/releases/latest/download/app-release.apk"
                                            isDownloading = true
                                            downloadProgress = 0
                                            scope.launch(Dispatchers.IO) {
                                                val tempFile = java.io.File(destDir, "tailsocks-update-$cleanVer.tmp")
                                                try {
                                                    val url = java.net.URL(targetUrl)
                                                    val conn = url.openConnection() as java.net.HttpURLConnection
                                                    conn.instanceFollowRedirects = true
                                                    conn.connect()
                                                    val totalLength = conn.contentLength

                                                    conn.inputStream.use { input ->
                                                        tempFile.outputStream().use { output ->
                                                            val buffer = ByteArray(8192)
                                                            var read: Int
                                                            var totalRead = 0L
                                                            while (input.read(buffer).also { read = it } != -1) {
                                                                output.write(buffer, 0, read)
                                                                totalRead += read
                                                                if (totalLength > 0) {
                                                                    val pct = (totalRead * 100 / totalLength).toInt()
                                                                    withContext(Dispatchers.Main) { downloadProgress = pct }
                                                                }
                                                            }
                                                        }
                                                    }

                                                    val pInfo = context.packageManager.getPackageArchiveInfo(tempFile.absolutePath, 0)
                                                    if (pInfo != null && pInfo.packageName == context.packageName) {
                                                        if (destFile.exists()) destFile.delete()
                                                        tempFile.renameTo(destFile)
                                                        withContext(Dispatchers.Main) {
                                                            isDownloading = false
                                                            isApkCached = true
                                                            Toast.makeText(context, context.getString(R.string.main_update_installing), Toast.LENGTH_SHORT).show()
                                                            launchApkInstaller(context, destFile)
                                                        }
                                                    } else {
                                                        if (tempFile.exists()) tempFile.delete()
                                                        withContext(Dispatchers.Main) {
                                                            isDownloading = false
                                                            Toast.makeText(context, context.getString(R.string.main_check_failed_format, "Corrupted APK downloaded"), Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    if (tempFile.exists()) tempFile.delete()
                                                    withContext(Dispatchers.Main) {
                                                        isDownloading = false
                                                        Toast.makeText(context, context.getString(R.string.main_check_failed_format, e.message), Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
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
                                                    val connection = java.net.URL("https://api.github.com/repos/bropines/tailsocks/releases/latest").openConnection() as java.net.HttpURLConnection
                                                    connection.requestMethod = "GET"
                                                    connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                                                    if (connection.responseCode == 200) {
                                                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                                                        val json = AppJson.parseToJsonElement(response).jsonObject
                                                        val tag = json["tag_name"]!!.jsonPrimitive.content
                                                        var foundApkUrl: String? = null
                                                        var anyApkUrl: String? = null
                                                        val primaryAbi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else ""
                                                        val assets = json["assets"]?.jsonArray
                                                        if (assets != null) {
                                                            for (asset in assets) {
                                                                val obj = asset.jsonObject
                                                                val name = (obj["name"]?.jsonPrimitive?.content ?: "").lowercase()
                                                                val url = obj["browser_download_url"]?.jsonPrimitive?.content ?: ""
                                                                if (name.endsWith(".apk")) {
                                                                    if (anyApkUrl == null) anyApkUrl = url
                                                                    if (primaryAbi.isNotEmpty() && name.contains(primaryAbi.lowercase())) {
                                                                        foundApkUrl = url
                                                                        break
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        withContext(Dispatchers.Main) {
                                                            latestVersion = tag
                                                            downloadUrl = foundApkUrl ?: anyApkUrl
                                                            isCheckingUpdate = false
                                                        }
                                                    } else { throw Exception("HTTP ${connection.responseCode}") }
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
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = LocalContentColor.current
                                            )
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
                                openLink(
                                    if (latestVersion != null) "https://github.com/bropines/tailsocks/releases/latest"
                                    else "https://github.com/bropines/tailsocks"
                                )
                                showAboutDialog = false
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(dlgGithub, textAlign = TextAlign.Center)
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
                                            // The first row also answers a long press.
                                            .combinedClickable(
                                                onClick = { openLink(url) },
                                                onLongClick = if (index == 0) ({ showAboutBackdrop = true }) else null
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

        // Strings for the latency chips, resolved out here in the parent composition — see
        // wrapContextWithLocale().
        val pingStrings = ExitNodePingStrings(
            tapToMeasure = stringResource(R.string.peer_conn_tap_to_measure),
            pinging = stringResource(R.string.peer_pinging),
            resultFormat = stringResource(R.string.peer_ping_result),
            failed = stringResource(R.string.peer_conn_ping_failed)
        )
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

        // Strings come from the parent context, not stringResource() — see wrapContextWithLocale().
        ModalBottomSheet(
            onDismissRequest = { showExitNodeSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .navigationBarsPadding()
            ) {
                Text(
                    context.getString(R.string.main_select_exit_node),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (isExitNodesLoading) {
                    Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) { LoadingIndicator() }
                } else if (exitNodes.isEmpty()) {
                    // An exit node can be set while the list is empty — the peers
                    // have not loaded, or the node stopped offering one. Without a
                    // way out of that the traffic stays routed through a node the
                    // picker cannot even show, so the clear action is offered here
                    // too, not only alongside the list.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(context.getString(R.string.main_no_exit_nodes), color = MaterialTheme.colorScheme.outline)
                        if (exitNodeIp.isNotEmpty()) {
                            Button(
                                onClick = { applyExitNode("", ""); showExitNodeSheet = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(context.getString(R.string.main_exit_node_none))
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
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
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                item {
                                    val isSelected = exitNodeIp.isEmpty()
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                            .clickable { applyExitNode("", ""); showExitNodeSheet = false },
                                        shape = RoundedCornerShape(14.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                        ),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
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
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Clear,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(Modifier.width(16.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    context.getString(R.string.main_exit_node_none),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    context.getString(R.string.main_route_traffic_directly),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                        }
                                    }
                                }

                                items(exitNodes) { node ->
                                    val nodeIp = node.getPrimaryIp()
                                    val isSelected = if (exitNodeIp.isNotEmpty()) nodeIp == exitNodeIp else false
                                    // Parsed once per answer, not once per recomposition.
                                    val pingRaw = exitNodePings[nodeIp]
                                    val ping = remember(pingRaw) { pingStateOf(pingRaw) }
                                    val (osIcon, osColor) = getOsVisuals(node.os).let { (icon, color) ->
                                        if (icon == Icons.Default.Devices) Icons.Default.VpnKey to MaterialTheme.colorScheme.primary
                                        else icon to color
                                    }
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                            .clickable { applyExitNode(node.id ?: "", node.getPrimaryIp()); showExitNodeSheet = false },
                                        shape = RoundedCornerShape(14.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                        ),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
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
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                        else osColor.copy(alpha = 0.12f)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    osIcon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else osColor
                                                )
                                            }
                                            Spacer(Modifier.width(16.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    node.getDisplayName(),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    nodeIp,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            // Its own click target: measuring a node and
                                            // choosing it are two different taps, and the
                                            // row stays choosable while a ping is in flight.
                                            ExitNodePingChip(
                                                ping = ping,
                                                strings = pingStrings,
                                                onPing = { pingExitNode(nodeIp) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(state: String, isProcessing: Boolean, isTunEnabled: Boolean, isFullTunnel: Boolean, isRootEnabled: Boolean = false, isYieldedToForeignVpn: Boolean = false, isSharedWithForeignVpn: Boolean = false, onToggle: () -> Unit) {
    val backgroundColor = when (state) {
        "ACTIVE" -> MaterialTheme.colorScheme.primaryContainer
        "STARTING" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when (state) {
        "ACTIVE" -> MaterialTheme.colorScheme.onPrimaryContainer
        "STARTING" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = backgroundColor,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 130.dp)
            .alpha(if (isProcessing) 0.6f else 1f)
            .clickable(enabled = !isProcessing) { onToggle() },
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (state == "ACTIVE" && isTunEnabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = when(state) {
                        "ACTIVE" -> Icons.Default.CheckCircle
                        "STARTING" -> Icons.Default.Refresh
                        else -> Icons.Default.CheckCircle
                    },
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when(state) {
                    "ACTIVE" -> when {
                        isRootEnabled -> "${stringResource(R.string.main_status_active)} + Root"
                        isTunEnabled -> "${stringResource(R.string.main_status_active)} + TUN"
                        else -> stringResource(R.string.main_status_active)
                    }
                    "STARTING" -> stringResource(R.string.main_status_starting)
                    else -> stringResource(R.string.status_stopped)
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    isProcessing -> stringResource(R.string.main_status_please_wait)
                    state == "ACTIVE" -> when {
                        // Root wins the label: with it on, the VpnService is not
                        // what carries the traffic even if the TUN switch is set.
                        // Yielded, it carries the tailnet and nothing else; shared,
                        // it carries the whole tunnel for a part of the phone.
                        isRootEnabled && isSharedWithForeignVpn -> stringResource(R.string.main_status_active_root_shared_desc)
                        isRootEnabled && isYieldedToForeignVpn -> stringResource(R.string.main_status_active_root_yielded_desc)
                        isRootEnabled -> stringResource(R.string.main_status_active_root_desc)
                        isTunEnabled -> if (isFullTunnel) stringResource(R.string.main_tun_full_tunnel_desc)
                                        else stringResource(R.string.main_tun_split_tunnel_desc)
                        else -> stringResource(R.string.main_status_active_desc)
                    }
                    state == "STARTING" -> stringResource(R.string.main_status_starting_desc)
                    else -> stringResource(R.string.tap_to_start)
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(0.6f),
                color = contentColor
            )
        }
    }
}

@Composable
fun MenuCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoggedOutCard(
    loginUrl: String?,
    onConfigureProxy: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val activeAccount = remember { io.github.bropines.tailscaled.core.AccountManager.getActiveAccount(context) }
    val profilePrefs = remember(activeAccount) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    var enteredKey by remember { mutableStateOf(profilePrefs.getString("authkey", "") ?: "") }
    var showKeyInput by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(R.string.main_logged_out_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            
            Text(
                text = stringResource(R.string.main_logged_out_desc),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (loginUrl != null) {
                    Button(
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
                            } catch (e: Exception) {
                                Toast.makeText(context, context.getString(R.string.cannot_open_browser), Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.main_logged_out_btn_login),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(modifier = Modifier.size(20.dp))
                    }
                }

                OutlinedButton(
                    onClick = { showKeyInput = !showKeyInput },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Key, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (showKeyInput) "Hide Key" else "Use Key",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            AnimatedVisibility(visible = showKeyInput) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = enteredKey,
                        onValueChange = { enteredKey = it },
                        label = { Text(stringResource(R.string.main_logged_out_authkey_label), fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            profilePrefs.edit().putString("authkey", enteredKey).apply()
                            val stopIntent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
                            context.startService(stopIntent)
                            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                            mainHandler.postDelayed({
                                val startIntent = Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
                                ContextCompat.startForegroundService(context, startIntent)
                            }, 500)
                            Toast.makeText(context, context.getString(R.string.main_key_saved_restarting), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(stringResource(R.string.main_submit_key), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onConfigureProxy,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.main_proxy_setup), fontSize = 11.sp)
                }

                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.main_logged_out_btn_stop_short), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun ConnectionIssueCard(
    onConfigureProxy: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(R.string.main_conn_issue_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            Text(
                text = stringResource(R.string.main_conn_issue_desc),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onConfigureProxy,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.main_conn_issue_btn_proxy),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.main_logged_out_btn_stop_short),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Exit-node latency chips. The measurement and its parsing are the peer sheet's own —
// pingPeer() and pingStateOf() in UIComponents.kt — so the two cannot read a pong differently.

/** Strings the chip shows or announces, resolved by the parent — see wrapContextWithLocale(). */
private data class ExitNodePingStrings(
    val tapToMeasure: String,
    val pinging: String,
    val resultFormat: String,
    val failed: String
)

/**
 * The trailing chip of an exit-node row: the ping icon before anything is measured, the
 * indicator while the round trip is out, then the figure — or a dash when it did not come
 * back. Same colours as the peer sheet's connection card: a measured figure earns the
 * secondary container, everything before it sits a step above the row.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExitNodePingChip(
    ping: PeerPingState,
    strings: ExitNodePingStrings,
    onPing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val measured = ping as? PeerPingState.Measured
    val inFlight = ping == PeerPingState.InFlight
    val failed = ping == PeerPingState.Failed
    val description = when {
        measured != null -> strings.resultFormat.format(measured.latency)
        inFlight -> strings.pinging
        failed -> strings.failed
        else -> strings.tapToMeasure
    }
    val container = when {
        measured != null -> MaterialTheme.colorScheme.secondaryContainer
        failed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when {
        measured != null -> MaterialTheme.colorScheme.onSecondaryContainer
        failed -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onPing,
        enabled = !inFlight,
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = modifier
            .heightIn(min = 32.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (inFlight) {
                LoadingIndicator(modifier = Modifier.size(18.dp), color = content)
            } else {
                Icon(
                    Icons.Default.NetworkPing,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = content
                )
            }
            when {
                measured != null -> Text(
                    measured.latency,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                failed -> Text("—", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// About: full-screen backdrop.

/** Fixed palette: the scene is the same in both colour schemes. */
private val BACKDROP_SKY_TOP = Color(0xFF6F777F)
private val BACKDROP_SKY_BOTTOM = Color(0xFFAAB1B8)
private val BACKDROP_HAZE = Color(0xFFD5D9DE)
private val BACKDROP_INK = Color(0xFF262A30)
private val BACKDROP_PALE = Color(0xFFEEF0F3)

private fun DrawScope.drawBackdropWalker(x: Float, y: Float, s: Float, step: Float, color: Color) {
    fun px(u: Float) = x + u * s
    fun py(u: Float) = y + u * s
    val body = Path().apply {
        moveTo(px(6f), py(0f))
        val n = 14
        for (i in 0..n) {
            val a = PI.toFloat() * (1f - i / n.toFloat() * 0.8f)
            val r = if (i % 2 == 1) 30f else 24f
            lineTo(px(28f + r * cos(a)), py(-6f - r * sin(a)))
        }
        lineTo(px(58f), py(-14f))
        lineTo(px(66f), py(-7f))
        lineTo(px(60f), py(-3f))
        lineTo(px(54f), py(0f))
        close()
    }
    drawPath(body, color)
    val lift = sin(step) * 2f
    drawRect(color, topLeft = Offset(px(16f), py(-2f)), size = Size(5f * s, (7f + lift) * s))
    drawRect(color, topLeft = Offset(px(40f), py(-2f)), size = Size(5f * s, (7f - lift) * s))
    drawCircle(BACKDROP_PALE.copy(alpha = color.alpha), radius = 1.4f * s, center = Offset(px(56f), py(-9f)))
    drawLine(color, Offset(px(40f), py(-26f)), Offset(px(58f), py(-44f)), strokeWidth = 1.8f * s, cap = StrokeCap.Round)
    drawCircle(color, radius = 7f * s, center = Offset(px(61f), py(-49f)))
}

private fun DrawScope.drawBackdropStander(x: Float, y: Float, s: Float, color: Color) {
    fun px(u: Float) = x + u * s
    fun py(u: Float) = y + u * s
    drawOval(color, topLeft = Offset(px(30f), py(-78f)), size = Size(78f * s, 40f * s))
    val neck = Path().apply {
        moveTo(px(40f), py(-72f))
        lineTo(px(12f), py(-118f))
        lineTo(px(28f), py(-122f))
        lineTo(px(52f), py(-58f))
        close()
    }
    drawPath(neck, color)
    val head = Path().apply {
        moveTo(px(10f), py(-122f))
        lineTo(px(30f), py(-124f))
        lineTo(px(24f), py(-108f))
        lineTo(px(-4f), py(-98f))
        lineTo(px(-8f), py(-104f))
        close()
    }
    drawPath(head, color)
    drawLine(color, Offset(px(22f), py(-122f)), Offset(px(26f), py(-134f)), strokeWidth = 3f * s, cap = StrokeCap.Round)
    val w = 5f * s
    drawLine(color, Offset(px(44f), py(-50f)), Offset(px(40f), py(0f)), w, StrokeCap.Round)
    drawLine(color, Offset(px(56f), py(-50f)), Offset(px(58f), py(0f)), w, StrokeCap.Round)
    drawLine(color, Offset(px(92f), py(-50f)), Offset(px(88f), py(0f)), w, StrokeCap.Round)
    drawLine(color, Offset(px(102f), py(-50f)), Offset(px(108f), py(0f)), w, StrokeCap.Round)
    val tail = Path().apply {
        moveTo(px(106f), py(-70f))
        quadraticTo(px(126f), py(-60f), px(122f), py(-24f))
    }
    drawPath(tail, color, style = Stroke(width = 4f * s, cap = StrokeCap.Round))
}

/** A value that rises from 0 to 1 between [from] and [to] seconds, and holds. */
private fun rampAt(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)

/**
 * Full-screen scene over the About dialog. Its own window, so it sits above the dialog
 * rather than under it; it is disposed with the dialog's block, so closing the dialog ends
 * it. A tap anywhere lets it go.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AboutBackdrop(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val leaveSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val clearing = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    var frameNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { frameNs = it - start }
    }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                clearing.animateTo(1f, leaveSpec)
                onDismiss()
            }
        }
    }

    Dialog(
        onDismissRequest = leave,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { leave() }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val t = frameNs / 1e9f
                val cover = rampAt(t, 0f, 1.8f) * (1f - clearing.value)
                if (cover <= 0.005f) return@Canvas
                val w = size.width
                val h = size.height

                drawRect(Brush.verticalGradient(listOf(BACKDROP_SKY_TOP, BACKDROP_SKY_BOTTOM)), alpha = cover)

                // Something large, far off, that comes and goes.
                val far = rampAt(t, 3f, 5.5f) * (1f - rampAt(t, 8f, 10.5f)) * cover
                if (far > 0.01f) {
                    val fs = w / 320f
                    val fx = w * 0.52f
                    val fy = h * 0.60f
                    drawBackdropStander(fx, fy, fs * 1.04f, BACKDROP_PALE.copy(alpha = far * 0.18f))
                    drawBackdropStander(fx, fy, fs, BACKDROP_PALE.copy(alpha = far * 0.5f))
                }

                // Something small that comes in from the left and stops.
                val walk = rampAt(t, 1f, 10f)
                val eased = walk * (2f - walk)
                val ws = w / 300f
                val wx = -80f * ws + (w * 0.30f + 80f * ws) * eased
                val moving = walk < 1f
                val bob = if (moving) abs(sin(t * 6f)) * 1.5f * ws else 0f
                drawBackdropWalker(wx, h * 0.70f - bob, ws, if (moving) t * 6f else 0f, BACKDROP_INK.copy(alpha = cover))

                // Drifting haze on top of everything.
                for (i in 0 until 7) {
                    val speed = 0.012f + 0.006f * i
                    val cx = w * (((i * 0.37f + t * speed) % 1.3f) - 0.15f)
                    val cy = h * (0.12f + 0.12f * i) + sin(t * 0.4f + i) * h * 0.02f
                    val radius = w * (0.32f + 0.08f * (i % 3))
                    val center = Offset(cx, cy)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(BACKDROP_HAZE.copy(alpha = 0.55f * cover), Color.Transparent),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Deliberately not in strings.xml, and deliberately not translated: this is a
                // line from a Russian film, quoted as itself, in the one place the app is
                // allowed a private joke. An English reader sees the title and year below and
                // can look it up; an English rendering of the line would not be the line.
                Text(
                    "Если лошадь ляжет спать, она захлебнётся в тумане?",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    color = BACKDROP_PALE,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        alpha = rampAt(frameNs / 1e9f, 5.5f, 7.5f) * (1f - clearing.value)
                    }
                )
                Text(
                    "«Ёжик в тумане», 1975",
                    style = MaterialTheme.typography.labelMedium,
                    color = BACKDROP_PALE.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        alpha = rampAt(frameNs / 1e9f, 8f, 9.5f) * (1f - clearing.value)
                    }
                )
            }
        }
    }
}
