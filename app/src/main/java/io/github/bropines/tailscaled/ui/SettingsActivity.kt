package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.AdminApiActivity
import io.github.bropines.tailscaled.admin.profile.AdminProfiles
import io.github.bropines.tailscaled.admin.profile.AuthType
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.ui.theme.findActivity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import appctr.Appctr
import java.net.URLEncoder

/** What the remote is sent to after Settings changes what it shows; see `focus` in [SettingsScreen]. */
private enum class SettingsFocus { HUB, PAGE, ROW }

/** A backup or a restore waiting for its file, where the app's own browser picks it. */
private enum class BackupFile { FULL_BACKUP, FULL_RESTORE, PROFILE_EXPORT, PROFILE_IMPORT }

private const val FULL_BACKUP_NAME = "tailsocks_full_backup.enc"

/**
 * Work Settings starts that has to finish once started: a Root Mode switch and the
 * restart after a full restore. The screen's own scope is cancelled by Back, a rotation
 * or a language change; the blocking `su` calls of a switch ran to the end regardless,
 * and only the service restart at its end — the one suspending step — was dropped,
 * leaving the toggle on the new mode and no daemon behind it. Lives as long as the
 * process. A failure is logged: with no screen to report to, it must not end the process.
 */
private val settingsWork = CoroutineScope(
    SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, e -> android.util.Log.e("SettingsActivity", "Settings background work failed", e) }
)

/**
 * Removes the empty file a full backup left when its password was lost under the picker
 * and the second ask was cancelled. A document the provider will not delete stays.
 */
private fun discardBackupFile(context: Context, uri: Uri) {
    runCatching {
        if (uri.scheme == "file") uri.path?.let { File(it).delete() }
        else android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
    }
}

/** [name] in [dir], or the first "name (n).ext" that is free: a backup saved by path never overwrites another. */
private fun freeFile(dir: File, name: String): File {
    val base = name.substringBeforeLast('.')
    val extension = name.substringAfterLast('.', "")
    var file = File(dir, name)
    var n = 1
    while (file.exists()) {
        file = File(dir, if (extension.isEmpty()) "$base ($n)" else "$base ($n).$extension")
        n++
    }
    return file
}

class SettingsActivity : ComponentActivity() {
    companion object {
        /** Open on this section instead of the list, e.g. "bypass" from a health banner. */
        const val EXTRA_OPEN_SECTION = "open_section"

        /** With it or alone: open on this row of a page, an entry of ui/SettingsCatalog.kt by its id. */
        const val EXTRA_OPEN_ROW = "open_row"

        /** Start with this typed into the search of the settings. */
        const val EXTRA_SEARCH = "search"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var appTheme by remember { mutableStateOf(GlobalSettings.getAppTheme(context)) }
            var themePreset by remember { mutableStateOf(GlobalSettings.getThemePreset(context)) }
            var dynamicColor by remember { mutableStateOf(GlobalSettings.isDynamicColorEnabled(context)) }
            var amoledMode by remember { mutableStateOf(GlobalSettings.getBoolean(context, "amoled_mode", false)) }

            TailSocksTheme(
                appTheme = appTheme,
                themePreset = themePreset,
                dynamicColorEnabled = dynamicColor,
                amoledModeEnabled = amoledMode
            ) {
                SettingsScreen(
                    onBack = { finish() },
                    initialSection = intent?.getStringExtra(EXTRA_OPEN_SECTION),
                    initialRow = intent?.getStringExtra(EXTRA_OPEN_ROW),
                    initialSearch = intent?.getStringExtra(EXTRA_SEARCH),
                    currentTheme = appTheme,
                    onThemeChange = { 
                        appTheme = it
                        GlobalSettings.setAppTheme(context, it)
                    },
                    currentPreset = themePreset,
                    onPresetChange = {
                        themePreset = it
                        GlobalSettings.setThemePreset(context, it)
                    },
                    currentDynamicColor = dynamicColor,
                    onDynamicColorChange = {
                        dynamicColor = it
                        GlobalSettings.setDynamicColorEnabled(context, it)
                    },
                    currentAmoledMode = amoledMode,
                    onAmoledModeChange = {
                        amoledMode = it
                        GlobalSettings.setBoolean(context, "amoled_mode", it)
                    }
                )
            }
        }
    }
}

/**
 * Where a settings list was left. Deliberately not snapshot state: it is read once, when a
 * list is composed, and written on every scrolled frame — as state it would recompose the
 * very list that is scrolling. The settings surface is composed more than once at a time
 * (the open section, the hub the back gesture uncovers underneath it, and for an instant
 * both hubs as the pop lands), so each instance restores the position from here rather than
 * sharing one LazyListState between two live LazyColumns.
 */
private class ScrollAnchor(var index: Int = 0, var offset: Int = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    currentPreset: String,
    onPresetChange: (String) -> Unit,
    currentDynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    currentAmoledMode: Boolean,
    onAmoledModeChange: (Boolean) -> Unit,
    initialSection: String? = null,
    initialRow: String? = null,
    initialSearch: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val profilePrefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    
    // Scroll positions of the two levels, kept outside the surfaces that draw them —
    // see ScrollAnchor. Without this the hub the finger uncovers during a back gesture is
    // a fresh LazyColumn at the top, and so is the one the pop lands on: a category low in
    // the list came back to a screen scrolled somewhere the user had never been.
    val hubAnchor = remember { ScrollAnchor() }
    val sectionAnchors = remember { mutableMapOf<String, Int>() }

    // What this device is offered, and where (core/DeviceProfile.kt). A handheld is offered
    // everything in its usual place, so there every list below is the one it always was. A
    // television keeps some of it for the Advanced page and hides what it cannot do.
    val device = LocalDeviceClass.current
    val capabilities = if (device == DeviceClass.HANDHELD) Capabilities.ALL else rememberCapabilities()
    fun place(feature: Feature?): Availability =
        if (feature == null) Availability.OFFERED else DeviceProfile.availability(feature, device, capabilities)
    val advancedPages = remember(device, capabilities) { advancedPageCandidates.filter { place(it.feature) == Availability.ADVANCED } }
    val advancedLinks = remember(device, capabilities) { advancedTools.filter { place(it.feature) == Availability.ADVANCED } }
    // The last row: what the device keeps aside and, on every device, what is an experiment.
    val advanced = if (advancedPages.isEmpty() && advancedLinks.isEmpty()) experimentalCategory else advancedCategory
    val hubCategories = remember(device, capabilities) {
        settingsCategories.filter { place(it.feature) == Availability.OFFERED } + advanced
    }
    // The page a page was opened from, when that is not the hub: Back returns there.
    fun parentOf(section: String?): String? = advancedCategory.id.takeIf { advancedPages.any { it.id == section } }

    // Two-level navigation: null == the category hub, otherwise the id of the
    // open section (see settingsCategories). rememberSaveable, because the App
    // Language row calls recreate() and a plain remember would drop the user
    // back to the hub mid-edit. It starts on the page that was asked for, as this
    // device has it (settingsLanding).
    val landing = remember(initialSection, hubCategories, advancedPages) {
        settingsLanding(initialSection, hubCategories.map { it.id }, advancedPages.map { it.id })
    }
    var openSection by rememberSaveable { mutableStateOf(landing) }
    // The remote management page (RemoteManagedScreen), which takes the whole screen while open.
    var remoteOpen by rememberSaveable { mutableStateOf(false) }
    // What this device exposes (ExposureScreen): a page of the same kind.
    var exposureOpen by rememberSaveable { mutableStateOf(false) }

    // A television's remote. It needs something in focus at every moment, and Settings swaps
    // what is on screen under it — one page for another, a page for the file browser. So
    // each swap says where the remote goes next: into the page that opened, or back onto the
    // row it was opened from; and the effect below sends it there once that is on screen.
    // Off a television nothing asks and nothing is sent.
    val tv = isTv
    val hubFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    var pageHasFocus by remember { mutableStateOf(false) }
    // Whether the list still has to be given the remote for the first time. A press that comes
    // before it is lost — it only puts the remote on the first row, which a request that
    // follows would put there again — and a request that waits for an effect and a frame, as
    // `focus` does, leaves room for the first few presses of a held key. So the row that is
    // to have it asks as it is placed, the first moment a request can succeed.
    val hubStart = remember { booleanArrayOf(openSection == null) }
    val hubPlaced: () -> Unit = {
        if (hubStart[0] && runCatching { hubFocus.requestFocus() }.getOrDefault(false)) hubStart[0] = false
    }
    // The same for a page that was asked for by name, which the remote starts on.
    val pageStart = remember { booleanArrayOf(openSection != null) }
    val pagePlaced: () -> Unit = {
        if (pageStart[0] && runCatching { pageFocus.requestFocus() }.getOrDefault(false)) pageStart[0] = false
    }
    // The rows to come back to: the hub's, and the Advanced page's.
    var hubRow by rememberSaveable { mutableStateOf(landing?.let { parentOf(it) ?: it }) }
    var advancedRow by rememberSaveable { mutableStateOf(landing?.takeIf { parentOf(it) != null }) }
    var focusAsked by remember { mutableStateOf<Pair<SettingsFocus, Int>?>(null) }
    // The list of sections on screen, for bringing a row of it back into view.
    val hubList = remember { arrayOfNulls<androidx.compose.foundation.lazy.LazyListState>(1) }
    fun focus(target: SettingsFocus) {
        if (tv) focusAsked = target to (focusAsked?.second ?: 0) + 1
    }
    // Before a page with the remote on it is swapped for another, the remote steps back
    // onto the list of sections, to the row the page belongs to. A stop that is removed
    // from under the remote leaves it nowhere, and Compose then puts it on the first stop
    // of the screen — the top of that list, which scrolls there to show it.
    fun stepOutOfPage() {
        if (tv && pageHasFocus) runCatching { hubFocus.requestFocus() }
    }
    if (tv) {
        LaunchedEffect(focusAsked) {
            val target = focusAsked?.first ?: return@LaunchedEffect
            // What was asked for is composed by now, and placed a frame later.
            withFrameNanos { }
            if (target == SettingsFocus.HUB) {
                // A row scrolled out of a lazy list is not there to be asked: bring it back first.
                val list = hubList[0]
                val index = hubCategories.indexOfFirst { it.id == (hubRow ?: hubCategories.first().id) }
                if (list != null && index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                    list.scrollToItem(index)
                    withFrameNanos { }
                }
            }
            val requester = when (target) {
                SettingsFocus.HUB -> hubFocus
                SettingsFocus.PAGE -> pageFocus
                SettingsFocus.ROW -> rowFocus
            }
            // False is no failure: a group that puts the remote back on the row it left
            // (tvFocusGroup) answers the request itself and cancels this one. Only a row
            // that is not on screen at all — the requester has nothing to ask — falls
            // back to the page.
            val asked = runCatching { requester.requestFocus() }
            if (asked.isFailure && target != SettingsFocus.PAGE) runCatching { pageFocus.requestFocus() }
        }
        // The list, or the page that was asked for, takes the remote as it is first placed
        // (hubPlaced, pagePlaced), not a few frames later. This is the second try, for a page
        // that could not be asked yet.
        LaunchedEffect(Unit) {
            if (openSection == null) return@LaunchedEffect
            withFrameNanos { }
            if (pageStart[0]) focus(SettingsFocus.PAGE)
        }
    }
    fun open(section: String) {
        if (parentOf(section) == null) hubRow = section else advancedRow = section
        if (section != openSection) stepOutOfPage()
        openSection = section
        focus(SettingsFocus.PAGE)
    }
    // The search of the settings (SettingsSearch.kt): its field stands over the list of sections, and
    // a result opens a page through open(), on its row.
    val search = rememberSettingsSearch(initialRow, initialSearch) { open(it) }
    // What every page is handed (SettingsPageEnv). Made on each composition, as the page
    // closures were, so that `place` and `open` read the screen as it is now.
    val env = SettingsPageEnv(context, scope, activeAccount, profilePrefs, ::place, ::open)
    // The theme rows of Appearance and of Advanced → Theme and icon (SettingsAppearance.kt).
    val theme = SettingsThemeChoice(
        currentTheme, onThemeChange, currentPreset, onPresetChange,
        currentDynamicColor, onDynamicColorChange, currentAmoledMode, onAmoledModeChange
    )

    // Global Settings
    // The Sharing page's values, and the chooser of its Taildrop folder: registered here,
    // where it is composed whichever page is open, for the system's picker to answer.
    val sharing = remember { SettingsSharingState(context, profilePrefs) }
    val taildropFolderChooser = rememberReceiveFolderChooser { sharing.taildropFolder = it }
    val background = remember { SettingsBackgroundState(context) }
    // The control proxy: its row on the Censorship bypass page, its dialog over the screen.
    val bypass = remember { SettingsBypassState(context) }
    
    val proxies = remember { SettingsProxiesState(context, activeAccount.name) }
    val dns = remember { SettingsDnsState(context) }
    
    val diagnostics = remember { SettingsDiagnosticsState(context) }

    // TUN Mode State
    var tunModeEnabled by remember { mutableStateOf(GlobalSettings.isTunModeEnabled(context)) }
    var tunEngine by remember { mutableStateOf(GlobalSettings.getTunEngine(context)) }
    var tunExcludedCIDRs by remember { mutableStateOf(GlobalSettings.getTunExcludedCIDRs(context)) }
    var tunExcludedApps by remember { mutableStateOf(GlobalSettings.getTunExcludedApps(context)) }
    var tunAddress by remember { mutableStateOf(GlobalSettings.getTunAddress(context)) }
    var tunIpv6Enabled by remember { mutableStateOf(GlobalSettings.isTunIpv6Enabled(context)) }
    var tunSwapInPlace by remember { mutableStateOf(GlobalSettings.isTunSwapInPlace(context)) }
    var tunTransparent by remember { mutableStateOf(GlobalSettings.getTunTransparent(context)) }

    val excludedAppsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tunExcludedApps = GlobalSettings.getTunExcludedApps(context)
    }

    // The Account page (SettingsAccount.kt). The Android/Linux choice is keyed on the account,
    // as it always was; the account itself is the one open when Settings opened.
    val honestHostinfo = remember(activeAccount.id) { mutableStateOf(ProfileHostinfo.isHonest(context, activeAccount.id)) }
    val account = remember { SettingsAccountState(context, profilePrefs, honestHostinfo) }

    // The password itself is never saved state (BackupRestore.PasswordHold keeps it in
    // the process); the dialog's visibility and the file a lost password left without
    // an archive are, so the question comes back after a recreation.
    var showBackupPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var pendingBackupUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    var restorePassword by remember { mutableStateOf("") }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    // After a full restore: restart now, or later. What the restart writes is held in the
    // process, and the question follows it rather than saved state: asked again when
    // Settings is recreated or reopened before an answer, and not at all once the
    // process has died, which was the restart.
    var showRestartDialog by remember { mutableStateOf(BackupRestore.PendingRestart.pending) }
    var restarting by remember { mutableStateOf(false) }


    // Where a backup goes and where one comes from is a Uri either way: the system's picker
    // hands one back, and a file picked in the app's own browser is one as well (file://),
    // which the content resolver opens just the same. So each of the four is a function of one.
    fun exportProfile(uri: Uri?) {
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val backupObj = kotlinx.serialization.json.buildJsonObject {
                        put("manifest", AppJson.encodeToJsonElement(BackupFormat.current(context)))
                        put("account", AppJson.encodeToJsonElement(activeAccount))
                        put("settings", BackupRestore.prefsToJson(profilePrefs.all))
                        // The app-wide settings (proxies, DPI bypass, TUN, Root
                        // Mode, recovery, appearance) used to survive only in the
                        // encrypted full backup. Secrets and device-bound entries
                        // stay out — see GlobalSettings.EXPORTED_KEYS.
                        put("global", BackupRestore.prefsToJson(GlobalSettings.exportable(context)))
                    }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(backupObj.toString().toByteArray()) }
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_backup_saved), Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_backup_failed_format, e.message), Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    fun importProfile(uri: Uri?) {
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val jsonBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (jsonBytes != null) {
                        val jsonString = String(jsonBytes)
                        // AppJson throws on blank/malformed where Gson returned null;
                        // either way the outer catch turns it into the restore-failed toast.
                        val backupData = AppJson.decodeFromString<JsonObject>(jsonString)

                        // Same provenance rule as the full backup: never apply an
                        // export produced by a build newer than this one.
                        val manifest = (backupData["manifest"] as? JsonObject)?.let {
                            BackupFormat.fromJson(AppJson.encodeToString(it))
                        }
                        val refusal: String? = when (val c = BackupFormat.check(context, manifest)) {
                            is BackupFormat.Compatibility.Ok -> null
                            is BackupFormat.Compatibility.Legacy -> null
                            is BackupFormat.Compatibility.FormatTooNew ->
                                context.getString(R.string.settings_restore_format_too_new, c.backupVersion, c.supported)
                            is BackupFormat.Compatibility.AppTooNew ->
                                context.getString(R.string.settings_restore_app_too_new, c.backupVersion, c.installedVersion)
                            is BackupFormat.Compatibility.WrongPackage ->
                                context.getString(R.string.settings_restore_wrong_package, c.backupPackage)
                        }
                        if (refusal != null) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, refusal, Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }

                        val settings = backupData["settings"] as? JsonObject
                        if (settings != null) {
                            val editor = profilePrefs.edit()
                            settings.forEach { (k, element) ->
                                val prim = element as? JsonPrimitive ?: return@forEach
                                when {
                                    // A quoted value is a string even if it reads like a
                                    // bool/number, so this branch must come first.
                                    prim.isString -> editor.putString(k, prim.content)
                                    prim.booleanOrNull != null -> editor.putBoolean(k, prim.booleanOrNull!!)
                                    // Gson decoded every JSON number to Double and wrote it
                                    // as a float; keep that lossy-but-identical behaviour.
                                    prim.floatOrNull != null -> editor.putFloat(k, prim.floatOrNull!!)
                                }
                            }
                            editor.apply()

                            // Optional section: files written before app-wide
                            // settings were exported simply have no `global`, and
                            // restore exactly as they did. Keys the file omits keep
                            // their current value; keys outside the allow-list are
                            // dropped by GlobalSettings.importValues.
                            (backupData["global"] as? JsonObject)?.let { globals ->
                                GlobalSettings.importValues(context, BackupRestore.jsonToPrefValues(globals))
                            }

                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, context.getString(R.string.settings_restored_success), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_restore_failed_format, e.message), Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    // The password comes from its dialog by way of BackupRestore.PasswordHold, or from the
    // dialog shown again by the branch below. Without one nothing is written: the file
    // the picker created is kept for the second answer, and the password is asked again.
    fun writeFullBackup(uri: Uri?, password: CharArray? = BackupRestore.PasswordHold.take()) {
        if (uri == null) {
            BackupRestore.PasswordHold.clear()
            return
        }
        if (password == null || password.isEmpty()) {
            pendingBackupUri = uri
            showBackupPasswordDialog = true
            return
        }
        scope.launch(Dispatchers.IO) {
            val tempStatesDir = File(context.cacheDir, "temp_states_backup")
            var useTempStates = false
            try {
                val statesDir = File(context.filesDir, "states")
                if (statesDir.exists() && RootUtils.isRootAvailable()) {
                    val uid = context.applicationInfo.uid
                    val cmd = "rm -rf \"${tempStatesDir.absolutePath}\" && " +
                              "mkdir -p \"${tempStatesDir.absolutePath}\" && " +
                              "cp -R \"${statesDir.absolutePath}/\"* \"${tempStatesDir.absolutePath}/\" && " +
                              "chown -R $uid:$uid \"${tempStatesDir.absolutePath}\" && " +
                              "chmod -R u+rwX \"${tempStatesDir.absolutePath}\""
                    try {
                        val process = Runtime.getRuntime().exec("su")
                        process.outputStream.use { os ->
                            os.write(("$cmd\nexit\n").toByteArray())
                            os.flush()
                        }
                        process.waitFor()
                        if (tempStatesDir.exists() && tempStatesDir.list()?.isNotEmpty() == true) {
                            useTempStates = true
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SettingsActivity", "Failed to copy states using root", e)
                    }
                }

                val baos = java.io.ByteArrayOutputStream()
                java.util.zip.ZipOutputStream(baos).use { zos ->
                    // Written first so a restore can identify the archive
                    // before touching anything.
                    zos.putNextEntry(java.util.zip.ZipEntry(BackupFormat.MANIFEST_ENTRY))
                    zos.write(BackupFormat.toJson(BackupFormat.current(context)).toByteArray())
                    zos.closeEntry()

                    val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                    if (prefsDir.exists()) {
                        prefsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val entryName = "shared_prefs/${file.name}"
                            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    val targetStatesDir = if (useTempStates) tempStatesDir else statesDir
                    if (targetStatesDir.exists()) {
                        targetStatesDir.walkTopDown().filter { it.isFile && !NetmapCache.isCachePath(it.relativeTo(targetStatesDir).path) }.forEach { file ->
                            val entryName = "files/states/${file.relativeTo(targetStatesDir).path}"
                            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    val historyFile = File(context.filesDir, "sent_history.json")
                    if (historyFile.exists()) {
                        zos.putNextEntry(java.util.zip.ZipEntry("files/sent_history.json"))
                        historyFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                val zipBytes = baos.toByteArray()
                val encryptedBytes = BackupCrypto.encrypt(zipBytes, password)
                context.contentResolver.openOutputStream(uri)?.use { it.write(encryptedBytes) }
                withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_full_backup_saved), Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_full_backup_failed_format, e.message), Toast.LENGTH_LONG).show() }
            } finally {
                password.fill('\u0000')
                try {
                    tempStatesDir.deleteRecursively()
                } catch (e: Exception) {}
            }
        }
    }

    fun askRestorePassword(uri: Uri?) {
        if (uri != null) {
            pendingRestoreUri = uri
            showRestorePasswordDialog = true
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { exportProfile(it) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { importProfile(it) }
    val fullBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { writeFullBackup(it) }
    val fullRestoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { askRestorePassword(it) }

    // The system's picker where there is one. Where there is none — a television, as a
    // rule — or it will not start, the app's own file browser takes the settings' place
    // until the file is chosen (at the end of this function).
    var browsingFor by rememberSaveable { mutableStateOf<BackupFile?>(null) }
    val systemPicker = Feature.SYSTEM_FILE_PICKER.offered()
    val profileBackupName = "tailsocks_backup_${activeAccount.name}.json"
    fun pickBackupFile(what: BackupFile) {
        val launched = systemPicker && when (what) {
            BackupFile.FULL_BACKUP -> fullBackupLauncher.tryLaunch(FULL_BACKUP_NAME)
            BackupFile.FULL_RESTORE -> fullRestoreLauncher.tryLaunch("*/*")
            BackupFile.PROFILE_EXPORT -> backupLauncher.tryLaunch(profileBackupName)
            BackupFile.PROFILE_IMPORT -> restoreLauncher.tryLaunch("application/json")
        }
        if (!launched) browsingFor = what
    }

    fun performRestore(uri: Uri, passwordStr: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val encryptedBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (encryptedBytes == null) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_read_backup_failed), Toast.LENGTH_LONG).show() }
                    return@launch
                }
                val decryptedBytes = BackupCrypto.decrypt(encryptedBytes, passwordStr.toCharArray())

                // Refuse an archive this build cannot honour before writing a
                // single file: a half-applied restore is worse than none.
                val manifest = BackupFormat.readManifest(decryptedBytes)
                val refusal: String? = when (val c = BackupFormat.check(context, manifest)) {
                    is BackupFormat.Compatibility.Ok -> null
                    is BackupFormat.Compatibility.Legacy -> null
                    is BackupFormat.Compatibility.FormatTooNew ->
                        context.getString(R.string.settings_restore_format_too_new, c.backupVersion, c.supported)
                    is BackupFormat.Compatibility.AppTooNew ->
                        context.getString(R.string.settings_restore_app_too_new, c.backupVersion, c.installedVersion)
                    is BackupFormat.Compatibility.WrongPackage ->
                        context.getString(R.string.settings_restore_wrong_package, c.backupPackage)
                }
                if (refusal != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, refusal, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Prior to restore, if root is available, change ownership of existing states to app
                if (RootUtils.isRootAvailable()) {
                    val uid = context.applicationInfo.uid
                    val statesDir = File(context.filesDir, "states")
                    if (statesDir.exists()) {
                        val cmd = "chown -R $uid:$uid \"${statesDir.absolutePath}\" && " +
                                  "chmod -R u+rwX \"${statesDir.absolutePath}\""
                        try {
                            val process = Runtime.getRuntime().exec("su")
                            process.outputStream.use { os ->
                                os.write(("$cmd\nexit\n").toByteArray())
                                os.flush()
                            }
                            process.waitFor()
                        } catch (e: Exception) {
                            android.util.Log.e("SettingsActivity", "Failed to chown states before restore", e)
                        }
                    }
                }

                // Only preference files, daemon state and the sent-files history may be
                // restored (BackupRestore.restoreTarget); every entry is checked before
                // a single file is written.
                val targets = BackupRestore.restoreTargets(
                    decryptedBytes,
                    prefsDir = File(context.applicationInfo.dataDir, "shared_prefs").canonicalFile,
                    statesDir = File(context.filesDir, "states").canonicalFile,
                    filesDir = context.filesDir.canonicalFile,
                )
                BackupRestore.writeRestore(decryptedBytes, targets)
                // Written, but this process still holds the old settings in memory and
                // would write them back: the restore is finished by a restart, which
                // the dialog offers (BackupRestore.PendingRestart).
                BackupRestore.PendingRestart.hold(decryptedBytes, targets)
                withContext(Dispatchers.Main) { showRestartDialog = true }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.settings_full_restore_failed), Toast.LENGTH_LONG).show() }
            } finally {
                restorePassword = ""
                pendingRestoreUri = null
            }
        }
    }

    fun saveGlobalPref(key: String, value: Any?) {
        when (value) {
            is String -> GlobalSettings.setString(context, key, value)
            is Boolean -> GlobalSettings.setBoolean(context, key, value)
        }
        context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
    }

    fun saveProfilePref(key: String, value: Any?, triggerService: Boolean = true) {
        val editor = profilePrefs.edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
        }
        editor.apply()
        if (triggerService) {
            context.startService(Intent(context, TailscaledService::class.java).apply { action = "APPLY_SETTINGS" })
        }
    }

    SettingsLogoutDialog(env, account)

    // ---------------------------------------------------------------------
    // The 11 section detail pages of the target structure, in their A..K
    // order. Every row below is the original row, moved: the labels, the
    // gates and the onChange side effects are the ones the old tabs had.
    // ---------------------------------------------------------------------

    // B. Tunnel mode
    //
    // One three-way selector owns both mode keys. Proxy / VPN (TUN) / Root are
    // mutually exclusive here: `tun_mode_enabled` and `root_mode_enabled` are
    // never written true together. Root keeps the old carry-over — enabling it
    // while TUN was on forces `root_tun_enabled`, so a user who had a tunnel
    // keeps one, now the native kernel interface. Only the rows of the selected
    // mode are composed, which is why the TUN rows no longer carry the
    // "unavailable in Root mode" notes: in Root mode they are not there at all.
    //
    // withRoot: whether Root is among the modes to pick. It is wherever Root Mode is
    // offered in its usual place; a device that keeps it for the advanced settings reaches
    // this same page with it through Advanced → Root mode. A device that runs in Root Mode
    // sees it either way: the mode in use is never hidden.
    val sectionTunnel: @Composable (Boolean) -> Unit = { withRoot ->
        var rootModeEnabled by remember { mutableStateOf(GlobalSettings.isRootModeEnabled(context)) }
        var rootTunEnabled by remember { mutableStateOf(GlobalSettings.isRootTunEnabled(context)) }
        var rootYieldLan by remember { mutableStateOf(GlobalSettings.isRootYieldLanEnabled(context)) }
        var serviceScriptInstalled by remember { mutableStateOf(false) }
        // Whether the installed copy is the one this build writes (RootBootScript).
        var serviceScript by remember { mutableStateOf(RootBootScript.State(RootBootScript.Status.ABSENT)) }
        var cliInstalled by remember { mutableStateOf(false) }
        var killDaemonOnStop by remember { mutableStateOf(GlobalSettings.shouldKillRootDaemonOnStop(context)) }
        var rootVpnBypass by remember { mutableStateOf(GlobalSettings.isRootVpnBypassEnabled(context)) }
        var rootTakeDevice by remember { mutableStateOf(GlobalSettings.isRootTakeDeviceAnyway(context)) }
        // What the service actually installed last time. It changes while this
        // screen is open — another VPN starts, ours yields — so it is watched
        // rather than read once. Shared is the middle case: the other client
        // bypasses some apps and we took the default route for those alone.
        var rootRoutingYielded by remember { mutableStateOf(GlobalSettings.isRootRoutingYielded(context)) }
        var rootRoutingShared by remember { mutableStateOf(GlobalSettings.isRootRoutingShared(context)) }
        var showRootWarningDialog by remember { mutableStateOf(false) }
        var showTunWarningDialog by remember { mutableStateOf(false) }
        var showTakeDeviceDialog by remember { mutableStateOf(false) }
        // Android hands the VPN slot to one app at a time and revokes it from
        // whoever held it, without asking that app. It does not say who that is,
        // so we can only report that the slot is taken — by AdGuard, a private
        // DNS app, another tunnel — and let the user decide.
        // Read afresh each time the dialog is raised, not once per composition.
        val foreignVpnActive = remember(showTunWarningDialog) {
            runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                // A one-off look when the dialog opens; allNetworks is deprecated
                // only in favour of callbacks, which a dialog does not need.
                @Suppress("DEPRECATION")
                cm.allNetworks.any { n ->
                    cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                } && !TunVpnService.isRunning
            }.getOrDefault(false)
        }
        var isRootBusy by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            val prefs = context.getSharedPreferences("tailsocks_global", Context.MODE_PRIVATE)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
                if (key == "root_routing_yielded") {
                    rootRoutingYielded = store.getBoolean("root_routing_yielded", false)
                }
                if (key == "root_routing_shared") {
                    rootRoutingShared = store.getBoolean("root_routing_shared", false)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            rootRoutingYielded = prefs.getBoolean("root_routing_yielded", false)
            rootRoutingShared = prefs.getBoolean("root_routing_shared", false)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        // Root wins the display if an older install left both keys set; from the
        // first tap on, the selector keeps them exclusive.
        val selectedMode = if (rootModeEnabled) 2 else if (tunModeEnabled) 1 else 0

        // Probing these spawns a root shell, which must never run on the
        // composition thread — it blocks the UI until su answers.
        LaunchedEffect(rootModeEnabled) {
            if (!rootModeEnabled) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                val script = RootUtils.serviceScriptState(context)
                val cli = RootUtils.isTailscaleCliInstalled()
                withContext(Dispatchers.Main) {
                    serviceScript = script
                    serviceScriptInstalled = script.installed
                    cliInstalled = cli
                }
            }
        }

        // Exactly what the old "Enable Native Root Mode" switch ran when it was
        // switched off. The selector calls it for both of the other two modes, so
        // the teardown lives in one place instead of being duplicated per branch.
        fun disableRootMode() {
            rootModeEnabled = false
            val hadRouting = GlobalSettings.isRootRoutingInstalled(context)
            GlobalSettings.setRootModeEnabled(context, false)
            val hadScript = serviceScriptInstalled
            serviceScriptInstalled = false
            Toast.makeText(context, context.getString(R.string.settings_root_mode_disabled_toast), Toast.LENGTH_SHORT).show()
            // The application context: the work outlives this screen (settingsWork).
            val app = context.applicationContext
            settingsWork.launch {
                if (hadScript) RootUtils.setServiceScriptInstalled(app, false)
                // Take the system rules and the daemon down here rather
                // than hoping a later stop does it: nothing else knows
                // Root Mode was ever on once the setting is cleared.
                // One root shell removes the routing, kills the daemon and
                // hands its state back to the app (it used to be three).
                RootUtils.stopRootDaemon("${app.filesDir.absolutePath}/tailscaled.sock", handBackTo = app)
                if (hadRouting) {
                    GlobalSettings.setRootRoutingInstalled(app, false)
                    GlobalSettings.setRootRoutingYielded(app, false)
                    GlobalSettings.setRootRoutingShared(app, false)
                }
                // Whether the user wants a connection, not whether this process
                // happens to own a daemon: in Root Mode the daemon is a separate
                // root process the app only attaches to, so Appctr.isRunning()
                // reported false here and switching modes quietly did nothing
                // until the service was toggled by hand. startService needs no
                // main thread, and no suspension point is left to cancel it.
                if (ProxyState.isUserLetRunning(app)) {
                    app.startService(Intent(app, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                }
            }
        }

        if (showTunWarningDialog) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strSettingsTunWarningTitle = stringResource(R.string.settings_tun_warning_title)
            val strSettingsTunWarningBodyBusy = stringResource(R.string.settings_tun_warning_body_busy)
            val strSettingsTunWarningBody = stringResource(R.string.settings_tun_warning_body)
            val strSettingsTunWarningConfirm = stringResource(R.string.settings_tun_warning_confirm)
            val strSettingsRootWarningDialogCancel = stringResource(R.string.settings_root_warning_dialog_cancel)
            AppAlertDialog(
                onDismissRequest = { showTunWarningDialog = false },
                icon = { Icon(Icons.Default.VpnLock, contentDescription = null) },
                title = { Text(strSettingsTunWarningTitle) },
                text = {
                    Text(
                        text = if (foreignVpnActive) strSettingsTunWarningBodyBusy
                               else strSettingsTunWarningBody,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        showTunWarningDialog = false
                        // Same order the selector used: Root down first, then the
                        // TUN key, so no APPLY_SETTINGS lands with both set.
                        if (rootModeEnabled) disableRootMode()
                        if (!tunModeEnabled) {
                            tunModeEnabled = true
                            saveGlobalPref("tun_mode_enabled", true)
                        }
                    }) {
                        Text(strSettingsTunWarningConfirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTunWarningDialog = false }) {
                        Text(strSettingsRootWarningDialogCancel)
                    }
                }
            )
        }

        if (showRootWarningDialog) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strSettingsRootWarningDialogTitle = stringResource(R.string.settings_root_warning_dialog_title)
            val strSettingsRootWarningDialogBody = stringResource(R.string.settings_root_warning_dialog_body)
            val strSettingsRootWarningDialogConfirm = stringResource(R.string.settings_root_warning_dialog_confirm)
            val strSettingsRootWarningDialogCancel = stringResource(R.string.settings_root_warning_dialog_cancel)
            AppAlertDialog(
                onDismissRequest = { showRootWarningDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = strSettingsRootWarningDialogTitle,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                text = {
                    Text(
                        text = strSettingsRootWarningDialogBody,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRootWarningDialog = false
                            isRootBusy = true
                            scope.launch {
                                val granted = withContext(Dispatchers.IO) { RootUtils.isRootAvailable() }
                                isRootBusy = false
                                if (granted) {
                                    rootModeEnabled = true
                                    GlobalSettings.setRootModeEnabled(context, true)
                                    if (GlobalSettings.isTunModeEnabled(context)) {
                                        GlobalSettings.setRootTunEnabled(context, true)
                                        rootTunEnabled = true
                                        // Root takes the tunnel over, so the
                                        // Android VpnService flag goes off with
                                        // it — the two mode keys are never both
                                        // true. The RESTART_ACTION below stops
                                        // the running VpnService.
                                        GlobalSettings.setTunModeEnabled(context, false)
                                        tunModeEnabled = false
                                    }
                                    Toast.makeText(context, context.getString(R.string.settings_root_mode_enabled_toast), Toast.LENGTH_SHORT).show()
                                    if (ProxyState.isUserLetRunning(context)) {
                                        val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                                        context.startService(intent)
                                    }
                                } else {
                                    Toast.makeText(context, context.getString(R.string.settings_root_access_unavailable), Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(strSettingsRootWarningDialogConfirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRootWarningDialog = false }) {
                        Text(strSettingsRootWarningDialogCancel)
                    }
                }
            )
        }

        if (showTakeDeviceDialog) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strTakeDeviceTitle = stringResource(R.string.settings_root_take_device_dialog_title)
            val strTakeDeviceBody = stringResource(R.string.settings_root_take_device_dialog_body)
            val strTakeDeviceConfirm = stringResource(R.string.settings_root_take_device_confirm)
            val strTakeDeviceCancel = stringResource(R.string.settings_root_warning_dialog_cancel)
            AppAlertDialog(
                onDismissRequest = { showTakeDeviceDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = strTakeDeviceTitle,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                text = {
                    Text(
                        text = strTakeDeviceBody,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showTakeDeviceDialog = false
                            rootTakeDevice = true
                            GlobalSettings.setRootTakeDeviceAnyway(context, true)
                            // The decision is read when routing is applied, and
                            // that only happens on a fresh run — APPLY_SETTINGS
                            // never reinstalls the rules.
                            if (ProxyState.isUserLetRunning(context)) {
                                val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                                context.startService(intent)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(strTakeDeviceConfirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTakeDeviceDialog = false }) {
                        Text(strTakeDeviceCancel)
                    }
                }
            )
        }

        // A mode is a commitment — it decides how every packet leaves the phone —
        // so it is picked from a list that states what each one does, not from a
        // tab strip that looks like a view switcher. The chosen one expands with
        // its own settings underneath.
        fun selectMode(index: Int) {
            if (index == selectedMode) return
            if (index == 2) {
                // Root is never enabled straight from a tap: the warning dialog
                // owns the su probe and the writes that follow it.
                showRootWarningDialog = true
                return
            }
            if (index == 1) {
                // Nor is TUN: taking the VPN slot kicks out whatever holds it,
                // and the user finds out when their ad blocker goes dark.
                showTunWarningDialog = true
                return
            }
            // Root goes down first, the TUN key is written second — the same
            // order the two old switches produced, so no APPLY_SETTINGS ever
            // lands with Root Mode still on and a TUN start pending.
            if (rootModeEnabled) disableRootMode()
            val wantTun = index == 1
            if (tunModeEnabled != wantTun) {
                tunModeEnabled = wantTun
                saveGlobalPref("tun_mode_enabled", wantTun)
            }
        }

        @Composable
        fun ModeOption(index: Int, icon: ImageVector, title: String, desc: String, danger: Boolean = false) {
            val selected = index == selectedMode
            val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            val help = remember(desc) { mutableStateOf(false) }
            ExplainedRow(
                onClick = { selectMode(index) },
                help = help,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .settingAnchor(title, shape = MaterialTheme.shapes.large),
                shape = MaterialTheme.shapes.large,
                color = if (selected) accent.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                border = if (selected) BorderStroke(1.dp, accent.copy(alpha = 0.45f)) else null,
                clipped = false,
                onLongClick = { help.value = !help.value }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        HelpText(desc, expanded = help, inClickableRow = true)
                    }
                    RadioButton(
                        selected = selected,
                        onClick = { selectMode(index) },
                        // The card picks the mode: for a remote the button is no stop of its own.
                        modifier = Modifier.tvNoFocus(),
                        colors = RadioButtonDefaults.colors(selectedColor = accent)
                    )
                }
            }
        }

        ModeOption(
            index = 0,
            icon = Icons.Default.SwapHoriz,
            title = stringResource(R.string.settings_tunnel_mode_proxy),
            desc = stringResource(R.string.settings_tunnel_mode_proxy_desc)
        )
        ModeOption(
            index = 1,
            icon = Icons.Default.VpnLock,
            title = stringResource(R.string.settings_tunnel_mode_vpn),
            desc = stringResource(R.string.settings_tunnel_mode_vpn_desc)
        )

        AnimatedVisibility(visible = selectedMode == 1) {
            TunSettingsCard(
                engine = tunEngine,
                onEngineChange = {
                    tunEngine = it
                    saveGlobalPref("tun_engine", it)
                },
                socks5 = proxies.socks5,
                onOpenProxies = { open("proxies") },
                ipv6 = tunIpv6Enabled,
                onIpv6Change = {
                    tunIpv6Enabled = it
                    saveGlobalPref("tun_ipv6_enabled", it)
                },
                tunAddress = tunAddress,
                onTunAddressChange = {
                    tunAddress = it
                    saveGlobalPref("tun_address", it)
                },
                excludedCidrs = tunExcludedCIDRs,
                onExcludedCidrsChange = {
                    tunExcludedCIDRs = it
                    saveGlobalPref("tun_excluded_cidrs", it)
                },
                excludedAppsCount = tunExcludedApps.size,
                onOpenExcludedApps = {
                    excludedAppsLauncher.launch(Intent(context, TunExcludedAppsActivity::class.java))
                },
                swapInPlace = tunSwapInPlace,
                onSwapInPlaceChange = {
                    tunSwapInPlace = it
                    saveGlobalPref("tun_swap_in_place", it)
                },
                transparent = tunTransparent,
                onTransparentChange = {
                    tunTransparent = it
                    saveGlobalPref("tun_transparent", it)
                }
            )
        }

        if (withRoot || rootModeEnabled) {
            ModeOption(
                index = 2,
                icon = Icons.Default.Security,
                title = stringResource(R.string.settings_tunnel_mode_root),
                desc = stringResource(R.string.settings_tunnel_mode_root_desc),
                danger = true
            )
        }

        if (selectedMode == 2) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                HelpText(
                    stringResource(R.string.settings_root_banner_warning),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
            }

            SettingsCard(title = stringResource(R.string.settings_root_sect_title)) {
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_tun_title),
                    subtitle = if (rootTunEnabled)
                        stringResource(R.string.settings_root_tun_desc_native)
                        else stringResource(R.string.settings_root_tun_desc_proxy),
                    icon = Icons.Default.VpnLock,
                    checked = rootTunEnabled
                ) { enabled ->
                    rootTunEnabled = enabled
                    val hadRouting = GlobalSettings.isRootRoutingInstalled(context)
                    GlobalSettings.setRootTunEnabled(context, enabled)
                    // Finishes even if Settings is left meanwhile (settingsWork).
                    val app = context.applicationContext
                    settingsWork.launch {
                        // Leaving native TUN drops tailscale0, so its policy
                        // routing has to go with it — the refresh loop no longer
                        // looks at Root Mode routing once this is off.
                        if (!enabled && hadRouting) {
                            RootUtils.cleanupTailscale0Routing()
                            GlobalSettings.setRootRoutingInstalled(app, false)
                            GlobalSettings.setRootRoutingYielded(app, false)
                            GlobalSettings.setRootRoutingShared(app, false)
                        }
                        if (ProxyState.isUserLetRunning(app)) {
                            app.startService(Intent(app, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                // The same list TUN mode uses: in Root Mode it takes the app's
                // uid out of the device-wide DNS redirect and off the exit node.
                SettingsClickableItem(
                    title = stringResource(R.string.settings_tun_excluded_apps_title),
                    subtitle = stringResource(R.string.settings_tun_excluded_apps_desc, tunExcludedApps.size),
                    icon = Icons.Default.Apps
                ) {
                    excludedAppsLauncher.launch(Intent(context, TunExcludedAppsActivity::class.java))
                }

                // Two ways the LAN stays on the real network while an exit node is
                // on. The switch: the prefixes this device sits on become rules
                // that hand them back to the platform (RootUtils.localReturnRulesInstall).
                // The list, the same one TUN mode uses: throw routes in the daemon's
                // table (RootUtils.localBypassRoutes), where a more specific accepted
                // route still wins. The service watches both keys and re-applies.
                AnimatedVisibility(visible = rootTunEnabled) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                        SettingsSwitchItem(
                            title = stringResource(R.string.settings_root_yield_lan_title),
                            subtitle = stringResource(R.string.settings_root_yield_lan_desc),
                            icon = Icons.Default.Router,
                            checked = rootYieldLan
                        ) { enabled ->
                            rootYieldLan = enabled
                            saveGlobalPref("root_yield_lan", enabled)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                        SettingsEditItem(
                            title = stringResource(R.string.settings_tun_excluded_cidrs_title),
                            value = tunExcludedCIDRs,
                            icon = Icons.Default.Block,
                            placeholder = "192.168.0.0/16, 10.0.0.0/8",
                            description = stringResource(R.string.settings_root_excluded_cidrs_desc),
                            onSave = {
                                tunExcludedCIDRs = it
                                saveGlobalPref("tun_excluded_cidrs", it)
                            }
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_service_title),
                    subtitle = stringResource(R.string.settings_root_service_desc),
                    icon = Icons.Default.Build,
                    checked = serviceScriptInstalled,
                    enabled = !isRootBusy
                ) { install ->
                    isRootBusy = true
                    scope.launch {
                        // Read back rather than assumed: the state shown is the file's.
                        val (success, state) = withContext(Dispatchers.IO) {
                            val ok = RootUtils.setServiceScriptInstalled(context, install)
                            ok to RootUtils.serviceScriptState(context)
                        }
                        isRootBusy = false
                        serviceScript = state
                        if (success) {
                            serviceScriptInstalled = install
                            Toast.makeText(context, context.getString(if (install) R.string.settings_root_service_script_installed else R.string.settings_root_service_script_removed), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.settings_root_service_script_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                if (serviceScriptInstalled) {
                    val coroutineScope = rememberCoroutineScope()
                    val msgOk = stringResource(R.string.settings_root_script_reinstalled)
                    val msgFail = stringResource(R.string.error_generic, "reinstall failed")
                    var reinstallStatus by remember { mutableStateOf<String?>(null) }
                    var isReinstallOk by remember { mutableStateOf(true) }

                    BootScriptReinstallRow(
                        script = serviceScript,
                        reinstallStatus = reinstallStatus,
                        reinstallOk = isReinstallOk,
                        onReinstall = {
                            coroutineScope.launch(Dispatchers.IO) {
                                val success = RootUtils.setServiceScriptInstalled(context, true)
                                val state = RootUtils.serviceScriptState(context)
                                withContext(Dispatchers.Main) {
                                    serviceScript = state
                                    isReinstallOk = success
                                    reinstallStatus = if (success) msgOk else msgFail
                                    Toast.makeText(context, if (success) msgOk else msgFail, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_cli_title),
                    subtitle = stringResource(R.string.settings_root_cli_desc),
                    icon = Icons.Default.Terminal,
                    checked = cliInstalled,
                    enabled = !isRootBusy
                ) { install ->
                    isRootBusy = true
                    scope.launch {
                        val success = withContext(Dispatchers.IO) {
                            RootUtils.setTailscaleCliInstalled(context, install)
                        }
                        isRootBusy = false
                        if (success) {
                            cliInstalled = install
                            val message = if (install) {
                                context.getString(R.string.settings_root_cli_installed, RootPaths.current.systemCli)
                            } else {
                                context.getString(R.string.settings_root_cli_removed)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.settings_root_cli_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_vpn_bypass_title),
                    subtitle = stringResource(R.string.settings_root_vpn_bypass_desc),
                    icon = Icons.Default.Shield,
                    checked = rootVpnBypass
                ) { enabled ->
                    rootVpnBypass = enabled
                    GlobalSettings.setRootVpnBypassEnabled(context, enabled)
                    if (ProxyState.isUserLetRunning(context)) {
                        val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                        context.startService(intent)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                // Taking the device breaks another app's tunnel, so switching it
                // on asks first; giving the device back needs no confirmation.
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_take_device_title),
                    subtitle = stringResource(R.string.settings_root_take_device_desc),
                    icon = Icons.Default.PriorityHigh,
                    checked = rootTakeDevice
                ) { enabled ->
                    if (enabled) {
                        showTakeDeviceDialog = true
                    } else {
                        rootTakeDevice = false
                        GlobalSettings.setRootTakeDeviceAnyway(context, false)
                        if (ProxyState.isUserLetRunning(context)) {
                            val intent = Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                            context.startService(intent)
                        }
                    }
                }

                // Three outcomes, and the override only silences the two that
                // say we stepped aside. Shared answers first: it is the one the
                // yield flag also claims, and it claims less than the truth.
                if ((rootRoutingYielded || rootRoutingShared) && !rootTakeDevice) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        HelpText(
                            if (rootRoutingShared) stringResource(R.string.settings_root_shared_banner)
                            else stringResource(R.string.settings_root_yielded_banner)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                SettingsSwitchItem(
                    title = stringResource(R.string.settings_root_kill_daemon_title),
                    subtitle = stringResource(R.string.settings_root_kill_daemon_desc),
                    icon = Icons.Default.Dangerous,
                    checked = killDaemonOnStop
                ) {
                    killDaemonOnStop = it
                    GlobalSettings.setKillRootDaemonOnStop(context, it)
                }
            }
        }
    }

    // I. Backup & restore
    val sectionBackup: @Composable () -> Unit = {
        SettingsCard(title = stringResource(R.string.settings_sect_backup_files)) {
            // The encrypted archive carries shared_prefs/ and files/states, so
            // restoring it replaces every account and every setting at once.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showBackupPasswordDialog = true },
                    modifier = Modifier.weight(1f).settingAnchor(stringResource(R.string.settings_backup_full_title)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Archive, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.settings_backup_full_title), 
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                OutlinedButton(
                    onClick = { pickBackupFile(BackupFile.FULL_RESTORE) },
                    modifier = Modifier.weight(1f).settingAnchor(stringResource(R.string.settings_restore_full_title)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.SettingsBackupRestore, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.settings_restore_full_title), 
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 12.dp))

            // The JSON pair is scoped to the account that is open right now.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { pickBackupFile(BackupFile.PROFILE_EXPORT) },
                    modifier = Modifier.weight(1f).settingAnchor(stringResource(R.string.settings_profile_export_title)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Backup, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.settings_profile_export_title),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                OutlinedButton(
                    onClick = { pickBackupFile(BackupFile.PROFILE_IMPORT) },
                    modifier = Modifier.weight(1f).settingAnchor(stringResource(R.string.settings_profile_import_title)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.SettingsBackupRestore, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.settings_profile_import_title),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }

    // Back means "up one level": out of an open section to the hub — from a page the
    // Advanced page opened, to that page first — and only from the hub out of the
    // Activity. The toolbar arrow keeps the crossfade below;
    // the gesture draws its own transition and sets [poppedByGesture] so the
    // crossfade stands down instead of replaying a pop the finger already showed.
    var poppedByGesture by remember { mutableStateOf(false) }
    val popSection: () -> Unit = {
        val parent = parentOf(openSection)
        openSection = parent
        focus(if (parent == null) SettingsFocus.HUB else SettingsFocus.ROW)
    }
    val popSectionByGesture: () -> Unit = {
        poppedByGesture = true
        popSection()
    }
    // Armed for exactly one transition. The reset runs after the composition that
    // consumed it, and transitionSpec is only consulted when the target changes,
    // so it cannot cancel the transition it just configured.
    LaunchedEffect(openSection) { poppedByGesture = false }

    // One whole rendering of the screen at a given level: the category hub when
    // [section] is null, that category's page otherwise. It takes the level as a
    // parameter instead of reading `openSection` because the back gesture needs
    // two levels on screen at once — the section being dragged away, and the hub
    // coming back underneath it.
    // backable: whether a section shows the arrow that returns to the hub. Beside
    // the hub, as the right pane of the wide layout, there is nothing to return
    // to — the hub is already on screen — so the arrow would be a lie.
    // live: whether this rendering is the one in use, not one fading out or lying
    // under a back gesture. Only that one is where a television's remote is sent.
    val window = rememberWindowLayout()
    val settingsSurface: @Composable (String?, Boolean, Boolean) -> Unit = { section, backable, live ->
        val openCategory = if (section == advanced.id) advanced else settingsPages.firstOrNull { it.id == section }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                SettingsSearchBar(search, shown = section == null, live = live) {
                AppTopBar(
                    title = if (openCategory != null) stringResource(openCategory.titleRes) else stringResource(R.string.settings_title),
                    onBack = if (section == null || backable) {
                        { if (section != null) popSection() else onBack() }
                    } else null
                )
                }
            }
        ) { padding ->
            // While the search is in use its results stand where the list of sections does.
            if (section == null && search.active) SettingsSearchResults(search, Modifier.padding(padding)) else
            if (section == null) {
                val hubState = rememberLazyListState(hubAnchor.index, hubAnchor.offset)
                // Every hub instance starts where the last one was left and writes back
                // where it is now, so the copy under the finger, the copy on top of it and
                // the copy that lands are all at the same place in the list.
                LaunchedEffect(hubState) {
                    snapshotFlow { hubState.firstVisibleItemIndex to hubState.firstVisibleItemScrollOffset }
                        .collect { (index, offset) ->
                            hubAnchor.index = index
                            hubAnchor.offset = offset
                        }
                }
                ReadableWidth {
                // The row the remote starts on and comes back to: the one last opened.
                val returnRow = hubRow ?: hubCategories.first().id
                if (tv && live) SideEffect { hubList[0] = hubState }
                LazyColumn(
                    state = hubState,
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .tvFocusGroup(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(hubCategories, key = { it.id }) { category ->
                        SettingsClickableItem(
                            title = stringResource(category.titleRes),
                            subtitle = stringResource(category.descRes),
                            icon = category.icon,
                            modifier = if (tv && live && category.id == returnRow) {
                                Modifier.focusRequester(hubFocus).onGloballyPositioned { if (hubStart[0]) hubPlaced() }
                            } else Modifier,
                            onClick = { open(category.id) }
                        )
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
                }
            } else {
                // Hoisted for the same reason as the hub's: the back container swaps its own
                // structure the moment a pop lands, and a section that jumps back to the top
                // while it is still fading out is worse than the pop it is showing.
                // Beside the list one pane serves every section in turn, and the state it had for the
                // last one is not the next one's: each section is its own, and one opened from the list
                // starts at its top. A search result or a link that names a row scrolls to it from there.
                val sectionScroll = if (backable) rememberScrollState(sectionAnchors[section] ?: 0)
                else key(section) { rememberScrollState() }
                LaunchedEffect(sectionScroll, section) {
                    snapshotFlow { sectionScroll.value }.collect { sectionAnchors[section] = it }
                }
                val sectionBody: @Composable () -> Unit = {
                    // The row a search result was opened on is found by the rows of this page (settingAnchor).
                    SettingsRowTarget(search, section, live) {
                    when (section) {
                        "account" -> SettingsAccountPage(env, account, diagnostics.extraArgs)
                        "tunnel" -> sectionTunnel(place(Feature.ROOT_MODE) == Availability.OFFERED)
                        rootPage.id -> sectionTunnel(true)
                        "proxies" -> SettingsProxiesPage(env, proxies)
                        "dns" -> SettingsDnsPage(env, dns)
                        "bypass" -> SettingsBypassPage(context, bypass)
                        "sharing" -> SettingsSharingPage(env, sharing, taildropFolderChooser)
                        "background" -> SettingsBackgroundPage(env, background)
                        "appearance" -> SettingsAppearancePage(env, theme) { remoteOpen = true }
                        themePage.id -> SettingsThemePage(theme)
                        "backup" -> sectionBackup()
                        "automation" -> SettingsAutomationPage(env)
                        "diagnostics" -> SettingsDiagnosticsPage(env, diagnostics)
                        advancedCategory.id -> SettingsAdvancedPage(
                            env, advancedPages, advancedLinks, advancedRow, rowFocus,
                            onExposure = {
                                advancedRow = EXPOSURE_ROW
                                stepOutOfPage()
                                exposureOpen = true
                            },
                            onRemote = {
                                advancedRow = REMOTE_ROW
                                stepOutOfPage()
                                remoteOpen = true
                            }
                        )
                    }
                    }
                }
                // For a television's remote the page is one group: somewhere it can be
                // sent into as a whole, that remembers the row it left, and that scrolls
                // to its head and its tail, which have no row of their own to stop on.
                val remote = if (tv && live) {
                    Modifier
                        .focusRequester(pageFocus)
                        .onGloballyPositioned { if (pageStart[0]) pagePlaced() }
                        .onFocusChanged { pageHasFocus = it.hasFocus }
                        .tvScrollPastStops(sectionScroll)
                        .tvFocusGroup()
                } else Modifier
                // A phone's page, and any page a tablet has no columns for.
                val oneColumn: @Composable () -> Unit = {
                    ReadableWidth {
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .then(remote)
                            .verticalScroll(sectionScroll)
                            .padding(16.dp)
                    ) {
                        sectionBody()
                        Spacer(Modifier.height(32.dp))
                    }
                    }
                }
                // On a tablet a page of several cards stands them in two columns instead of
                // one 720dp strip down the middle of the pane: beside the categories from two
                // cards, alone on a medium window from three — two cards there would be two
                // half-empty columns. Never on a phone, upright or turned: its two-pane keeps
                // the single column it always had.
                val cards = openCategory?.cards ?: 1
                if (!window.isPhone && cards >= (if (backable) 3 else 2)) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        if (columnsFor(maxWidth - window.margin * 2, SettingsColumnWidth, maxColumns = 2) < 2) {
                            oneColumn()
                        } else {
                            Column(
                                modifier = Modifier
                                    .padding(padding)
                                    .fillMaxSize()
                                    .then(remote)
                                    .verticalScroll(sectionScroll)
                                    .padding(horizontal = window.margin, vertical = 16.dp)
                            ) {
                                CompositionLocalProvider(LocalSettingsColumns provides true) {
                                    if (openCategory?.splits == true) sectionBody()
                                    else CardColumns(minColumnWidth = SettingsColumnWidth, maxColumns = 2) { sectionBody() }
                                }
                                Spacer(Modifier.height(32.dp))
                            }
                        }
                    }
                } else {
                    oneColumn()
                }
            }
        }
    }

    // The crossfade between the hub and a section: Material 3's own *default effects*
    // spring. Read here rather than inside `transitionSpec`, which is not a composable
    // lambda and so cannot reach `MaterialTheme` itself.
    val sectionFadeSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()

    // The crossfade sits outside the back container, so that opening a section
    // still fades and each level keeps its own container: the one drawn for a
    // section installs the gesture, the one drawn for the hub does not.
    // Wide windows — a tablet, a phone on its side — show the hub and a section
    // at once, the way a settings screen reads on a large display: the list stays
    // put and the right side follows it. Nothing to push or pop there, so none of
    // the transition or predictive-back machinery below is involved; back simply
    // leaves, as it does from the hub.
    // A foldable held open like a book gets the two panes on its two halves
    // whatever its width, with the hinge itself as the divider. The width is the
    // content's, not the window's size class: a phone on its side (some 890dp) is
    // two panes here, and always has been.
    //
    // While a backup's file is being chosen in the app's own browser, the browser is the
    // screen: it takes the Back key for itself, and gives the settings back as it closes.
    val browsing = browsingFor
    if (remoteOpen) {
        // A page of its own, with its own Back: it hands the settings back as it closes.
        RemoteManagedScreen(onBack = { remoteOpen = false; focus(SettingsFocus.ROW) })
    } else if (exposureOpen) {
        ExposureScreen(onBack = { exposureOpen = false; focus(SettingsFocus.ROW) })
    } else if (browsing != null) {
        val source = remember { RealFileSource(context) }
        val downloads = remember { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
        val close = { browsingFor = null; focus(SettingsFocus.PAGE) }
        when (browsing) {
            BackupFile.FULL_BACKUP, BackupFile.PROFILE_EXPORT -> PickFolderScreen(
                source = source,
                onPicked = { dir ->
                    close()
                    if (browsing == BackupFile.FULL_BACKUP) writeFullBackup(Uri.fromFile(freeFile(dir, FULL_BACKUP_NAME)))
                    else exportProfile(Uri.fromFile(freeFile(dir, profileBackupName)))
                },
                // A full backup's password waits in the process only while its folder is chosen.
                onCancel = { BackupRestore.PasswordHold.clear(); close() },
                start = downloads,
                writable = true,
                title = stringResource(R.string.tv_settings_backup_save_title),
                confirmLabel = stringResource(R.string.tv_settings_backup_save_here)
            )
            BackupFile.FULL_RESTORE, BackupFile.PROFILE_IMPORT -> PickFilesScreen(
                source = source,
                onPicked = { files ->
                    close()
                    val uri = Uri.fromFile(files.first())
                    if (browsing == BackupFile.FULL_RESTORE) askRestorePassword(uri) else importProfile(uri)
                },
                onCancel = close,
                start = downloads,
                multiple = false,
                title = stringResource(R.string.tv_settings_backup_pick_title)
            )
        }
    } else
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    if (maxWidth >= 840.dp || window.fold is Fold.Vertical) {
        val shown = openSection ?: hubCategories.first().id
        // On a television Back leaves a page before it leaves Settings: from a page of
        // the Advanced page to that page, from any other to its row in the list, the
        // page staying where it is — and from the list, as everywhere, out.
        BackHandler(enabled = tv && pageHasFocus) {
            val parent = parentOf(openSection)
            if (parent != null) {
                stepOutOfPage()
                openSection = parent
                focus(SettingsFocus.ROW)
            } else {
                focus(SettingsFocus.HUB)
            }
        }
        ListDetailLayout(
            list = { settingsSurface(null, true, true) },
            detail = { settingsSurface(shown, false, true) },
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            window = window,
            twoPane = true,
            listWidth = 340.dp,
            style = PaneStyle.FLAT
        )
    } else
    AnimatedContent(
        targetState = openSection,
        transitionSpec = {
            if (poppedByGesture) {
                // The finger already played this one: the section shrank away and
                // the hub came back up underneath it. Fading them into each other
                // on top of that would show the section again, full size.
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(sectionFadeSpec) togetherWith fadeOut(sectionFadeSpec)
            }
        },
        label = "settings_section",
        // The two levels overlap while they crossfade, and half-transparent over
        // half-transparent would let the window background show through between
        // them; this is the ground they fade over.
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) { section ->
        PredictiveBackContainer(
            // Only the section pop is ours to draw. On the hub back leaves the
            // Activity, so nothing is intercepted there and the platform keeps the
            // gesture and its real cross-activity animation.
            // openSection as well as this slot's own section: AnimatedContent keeps the
            // outgoing slot alive for the whole exit fade, and that slot was invoked with a
            // section, so a handler keyed on it alone stayed armed on a screen that is
            // already leaving — a back gesture in that window was swallowed instead of
            // closing the Activity, and it re-ran the pop with nothing left to pop, which
            // latched poppedByGesture and cost the next section its fade.
            onBack = if (section != null && openSection != null) popSectionByGesture else null,
            modifier = Modifier.fillMaxSize(),
            // What the finger uncovers is the real hub, not a hint of it — for the eye
            // only: it is a second live copy of every category row, and a screen reader
            // reaches the hub through the one that lands.
            previousContent = {
                Box(Modifier.fillMaxSize().clearAndSetSemantics { }) { settingsSurface(parentOf(section), true, false) }
            }
        ) {
            settingsSurface(section, true, section == openSection)
        }
    }
    }

    SettingsControlProxyDialog(context, bypass)

    if (showBackupPasswordDialog) {
        var tempPassword by remember { mutableStateOf("") }
        var isPasswordVisible by remember { mutableStateOf(false) }
        // Strings resolved in the parent composition — see wrapContextWithLocale().
        val strSettingsBackupPasswordTitle = stringResource(R.string.settings_backup_password_title)
        val strSettingsBackupPasswordText = stringResource(R.string.settings_backup_password_text)
        val strSettingsPasswordLabel = stringResource(R.string.settings_password_label)
        val strSettingsBackupAction = stringResource(R.string.settings_backup_action)
        val strActionCancel = stringResource(R.string.action_cancel)
        // Cancelling the second ask leaves no archive behind: the empty file the picker
        // created goes too.
        val cancelBackup = {
            showBackupPasswordDialog = false
            pendingBackupUri?.let { uri -> scope.launch(Dispatchers.IO) { discardBackupFile(context, uri) } }
            pendingBackupUri = null
        }
        AppAlertDialog(
            onDismissRequest = cancelBackup,
            title = { Text(strSettingsBackupPasswordTitle) },
            text = {
                Column {
                    Text(strSettingsBackupPasswordText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempPassword,
                        onValueChange = { tempPassword = it },
                        label = { Text(strSettingsPasswordLabel) },
                        singleLine = true,
                        maxLines = 1,
                        shape = MaterialTheme.shapes.medium,
                        visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempPassword.isNotBlank()) {
                            showBackupPasswordDialog = false
                            val chosen = pendingBackupUri
                            if (chosen != null) {
                                // The file is already chosen: a recreation under the
                                // picker lost the first answer.
                                pendingBackupUri = null
                                writeFullBackup(chosen, tempPassword.toCharArray())
                            } else {
                                BackupRestore.PasswordHold.put(tempPassword.toCharArray())
                                pickBackupFile(BackupFile.FULL_BACKUP)
                            }
                        } else {
                            Toast.makeText(context, context.getString(R.string.settings_password_empty), Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text(strSettingsBackupAction) }
            },
            dismissButton = {
                TextButton(onClick = cancelBackup) { Text(strActionCancel) }
            }
        )
    }

    if (showRestorePasswordDialog && pendingRestoreUri != null) {
        var tempPassword by remember { mutableStateOf("") }
        var isPasswordVisible by remember { mutableStateOf(false) }
        // Strings resolved in the parent composition — see wrapContextWithLocale().
        val strSettingsRestorePasswordTitle = stringResource(R.string.settings_restore_password_title)
        val strSettingsRestorePasswordText = stringResource(R.string.settings_restore_password_text)
        val strSettingsPasswordLabel = stringResource(R.string.settings_password_label)
        val strSettingsRestoreAction = stringResource(R.string.settings_restore_action)
        val strActionCancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { 
                showRestorePasswordDialog = false
                pendingRestoreUri = null
            },
            title = { Text(strSettingsRestorePasswordTitle) },
            text = {
                Column {
                    Text(strSettingsRestorePasswordText, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempPassword,
                        onValueChange = { tempPassword = it },
                        label = { Text(strSettingsPasswordLabel) },
                        singleLine = true,
                        maxLines = 1,
                        shape = MaterialTheme.shapes.medium,
                        visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempPassword.isNotBlank()) {
                            val uri = pendingRestoreUri!!
                            showRestorePasswordDialog = false
                            performRestore(uri, tempPassword)
                        } else {
                            Toast.makeText(context, context.getString(R.string.settings_password_empty), Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text(strSettingsRestoreAction) }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showRestorePasswordDialog = false
                    pendingRestoreUri = null
                }) { Text(strActionCancel) }
            }
        )
    }

    if (showRestartDialog && BackupRestore.PendingRestart.pending) {
        // Neither a tap outside nor Back closes it: the restore is only finished by a
        // restart, and "Later" says what waiting means.
        val strTitle = stringResource(R.string.settings_restore_restart_title)
        val strBody = stringResource(R.string.settings_restore_restart_body)
        val strNow = stringResource(R.string.settings_restore_restart_now)
        val strLater = stringResource(R.string.settings_restore_restart_later)
        val strRestarting = stringResource(R.string.settings_restore_restarting)
        AppAlertDialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(strTitle) },
            text = { Text(strBody, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    enabled = !restarting,
                    onClick = {
                        restarting = true
                        // Stops the connection, writes the restored files again and
                        // relaunches the app in a new process; it does not come back.
                        val app = context.applicationContext
                        settingsWork.launch { BackupRestore.PendingRestart.restartNow(app) }
                    }
                ) { Text(if (restarting) strRestarting else strNow) }
            },
            dismissButton = {
                TextButton(
                    enabled = !restarting,
                    onClick = {
                        BackupRestore.PendingRestart.drop()
                        showRestartDialog = false
                    }
                ) { Text(strLater) }
            }
        )
    }
}

