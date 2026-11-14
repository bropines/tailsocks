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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import io.github.bropines.tailscaled.core.AppJson
import java.lang.Runtime

import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

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

/** URI grants a forwarded installer confirmation must not carry (see handleIntent). */
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
