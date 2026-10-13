package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.ui.theme.findActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

class TaildriveActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE)
        setContent {
            TailSocksTheme {
                when (page) {
                    PAGE_BROWSE -> TaildriveBrowsePage(onClose = { finish() })
                    PAGE_PICK_FOLDER -> TaildrivePickFolderPage(
                        onPicked = { dir ->
                            setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, dir.path))
                            finish()
                        },
                        onCancel = { finish() }
                    )
                    else -> TaildriveScreen(onBack = { finish() })
                }
            }
        }
    }

    // The Taildrive screen lives in two places (this Activity, and the first page of the Files hub),
    // and the pages below are full screens with a bar of their own. So they are this Activity again
    // with something else to show: Back closes it, and the screen that asked is where it was.
    companion object {
        private const val EXTRA_PAGE = "page"
        private const val PAGE_BROWSE = "browse"
        private const val PAGE_PICK_FOLDER = "pick_folder"

        /** The answer of the folder chooser: the folder's path, with RESULT_OK. */
        const val EXTRA_PATH = "path"

        /** The other devices' shares. */
        fun browseIntent(context: Context): Intent =
            Intent(context, TaildriveActivity::class.java).putExtra(EXTRA_PAGE, PAGE_BROWSE)

        /** The app's own folder chooser, for a device with no system one; answers with [EXTRA_PATH]. */
        fun pickFolderIntent(context: Context): Intent =
            Intent(context, TaildriveActivity::class.java).putExtra(EXTRA_PAGE, PAGE_PICK_FOLDER)
    }
}

/** The other devices' shares. The proxy switch the page may ask for is the screen's own. */
@Composable
private fun TaildriveBrowsePage(onClose: () -> Unit) {
    val context = LocalContext.current
    TaildriveBrowserPage(onClose = onClose, onEnableProxy = { enableDriveProxy(context) })
}

@Composable
private fun TaildrivePickFolderPage(onPicked: (File) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    PickFolderScreen(
        source = remember { RealFileSource(context) },
        onPicked = onPicked,
        onCancel = onCancel,
        title = stringResource(R.string.taildrive_pick_title),
        confirmLabel = stringResource(R.string.taildrive_pick_confirm)
    )
}

@Serializable
data class LocalShare(
    val name: String = "",
    val path: String = ""
)

/**
 * What a preview shows instead of the stored shares, the system's answer on
 * all-files access and the stored proxy switch. Only previews provide [LocalTaildriveDemo]; in the app it is
 * null and the screen reads its preferences as always.
 *
 * [proxyBind] is where the proxy listens, [lan] this device's address on the local network and
 * [proxyPassword] turns the proxy's login on and [revealLogin] shows it; [grantCommand] is the line
 * a device with no settings page for the access is told to run.
 */
internal class TaildriveDemo(
    val shares: List<LocalShare>,
    val storageAccess: Boolean = true,
    val proxy: Boolean = false,
    val proxyBind: String = "127.0.0.1",
    val lan: String? = null,
    val proxyPassword: String? = null,
    val revealLogin: Boolean = false,
    val grantCommand: String? = null,
)

internal val LocalTaildriveDemo = staticCompositionLocalOf<TaildriveDemo?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaildriveScreen(onBack: () -> Unit) {
    TaildriveTabContent(onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaildriveTabContent(onBack: (() -> Unit)? = null) {
    PredictiveBackContainer(
        onBack = onBack,
        // Back here only closes the Activity, so the container installs no callback and
        // the platform animates across to the real screen underneath.
        popsInAppState = false
    ) {
        val context = LocalContext.current
    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val prefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }

    var isEnabled by remember { mutableStateOf(prefs.getBoolean("taildrive_enabled", true)) }
    val sharesJson = prefs.getString("taildrive_shares", "[]") ?: "[]"
    // Parsing the shares JSON is expensive; key it on the stored value so it
    // runs only when the prefs actually change instead of on every recomposition frame.
    val initialShares: ArrayList<LocalShare> = remember(sharesJson) {
        if (sharesJson.isBlank()) ArrayList()
        else runCatching { ArrayList(AppJson.decodeFromString<List<LocalShare>>(sharesJson)) }
            .getOrDefault(ArrayList())
    }
    val demo = LocalTaildriveDemo.current
    val shares = remember { mutableStateListOf<LocalShare>().apply { addAll(demo?.shares ?: initialShares) } }

    // The preview renderer has no package manager to ask; see MainScreen.
    val inPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    var hasStoragePermission by remember { mutableStateOf(demo?.storageAccess ?: (!inPreview && checkStoragePermission(context))) }
    var isProxyEnabled by remember { mutableStateOf(demo?.proxy ?: prefs.getBoolean("taildrive_proxy_enabled", false)) }
    var proxyIp by remember { mutableStateOf(demo?.proxyBind ?: prefs.getString("taildrive_proxy_ip", "127.0.0.1") ?: "127.0.0.1") }
    var proxyPort by remember { mutableStateOf(prefs.getString("taildrive_proxy_port", "33445") ?: "33445") }
    var isProxyAuthEnabled by remember { mutableStateOf(demo?.proxyPassword != null || prefs.getBoolean("taildrive_proxy_auth_enabled", false)) }
    var proxyUsername by remember { mutableStateOf(prefs.getString("taildrive_proxy_username", "tailsocks") ?: "tailsocks") }
    var proxyPassword by remember {
        val pass = demo?.proxyPassword ?: prefs.getString("taildrive_proxy_password", "") ?: ""
        mutableStateOf(pass)
    }
    // One switch for the field and the URL card under it, which carries the same password.
    var showPassword by rememberSaveable { mutableStateOf(demo?.revealLogin ?: false) }

    // The proxy switch is also turned on from the page of the other devices' shares, which is
    // another window of this Activity: the screen underneath hears it here.
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == DriveProxy.KEY_ENABLED) isProxyEnabled = changed.getBoolean(key, false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // What this device has: no clipboard to hand the address over with, no system folder picker.
    val clipboardOffered = Feature.CLIPBOARD_ACTIONS.offered()
    val pickerOffered = Feature.SYSTEM_FILE_PICKER.offered()
    val tv = isTv
    // Where the remote goes back to after a screen of another window: the one it left.
    val entryFocus = remember { FocusRequester() }
    val grantFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }

    // Generate secure random password on first-time auth enable
    LaunchedEffect(isProxyEnabled, isProxyAuthEnabled) {
        if (isProxyEnabled && isProxyAuthEnabled && proxyPassword.isEmpty()) {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val generated = (1..8).map { chars.random() }.joinToString("")
            proxyPassword = generated
            prefs.edit().putString("taildrive_proxy_password", generated).apply()
            triggerServiceSettingsUpdate(context)
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }

    var dialogPathInput by remember { mutableStateOf("") }
    var dialogNameInput by remember { mutableStateOf("") }
    var onDialogSubmit: ((LocalShare) -> Unit)? by remember { mutableStateOf(null) }

    // [name] is a suggestion from outside, for a folder that was chosen and not typed.
    fun showAddShareDialogWithPath(path: String, name: String? = null, onSubmit: (LocalShare) -> Unit) {
        dialogPathInput = path
        dialogNameInput = name ?: if (path.isNotEmpty()) File(path).name.replace(Regex("[^a-zA-Z0-9_]"), "") else ""
        onDialogSubmit = onSubmit
        showAddDialog = true
    }

    val addNewShare: (LocalShare) -> Unit = { newShare ->
        if (shares.any { it.name.lowercase() == newShare.name.lowercase() }) {
            Toast.makeText(context, context.getString(R.string.taildrive_err_name_exists), Toast.LENGTH_SHORT).show()
        } else {
            shares.add(newShare)
            saveShares(prefs, shares)
            triggerServiceSettingsUpdate(context)
        }
    }

    var removing by remember { mutableStateOf<LocalShare?>(null) }
    val removeShare: (LocalShare) -> Unit = { share ->
        val index = shares.indexOf(share)
        shares.remove(share)
        saveShares(prefs, shares)
        triggerServiceSettingsUpdate(context)
        // The cards are not keyed, so the remote stays on the one that took the place of the
        // removed; after the last one there is none, and it goes to the button that adds.
        if (tv && index >= shares.size) runCatching { addFocus.requestFocus() }
    }

    var editingShare: LocalShare? by remember { mutableStateOf<LocalShare?>(null) }

    val showEditShareDialog = { share: LocalShare, onSubmit: (LocalShare) -> Unit ->
        editingShare = share
        dialogNameInput = share.name
        dialogPathInput = share.path
        onDialogSubmit = onSubmit
        showAddDialog = true
    }

    // Launcher for directory picker (SAF)
    // Below Android 11 the access is the Storage permission itself, asked for here; once it is
    // refused for good only the app's settings can give it back.
    // The line to run from a computer, shown when no page of the device's Settings took the user there.
    var grantCommand by remember { mutableStateOf(demo?.grantCommand) }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasStoragePermission = checkStoragePermission(context)
        if (!hasStoragePermission) {
            val activity = context.findActivity()
            if (activity == null || LEGACY_STORAGE.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) {
                if (!openAppSettings(context)) grantCommand = storageGrantCommand(Build.VERSION.SDK_INT, context.packageName)
            }
        }
    }
    val askStorage: () -> Unit = {
        returnFocus = grantFocus
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The app's own page of All files access; a television has that one and often no other.
            grantCommand = if (context.openAllFilesAccessSettings()) null else storageGrantCommand(Build.VERSION.SDK_INT, context.packageName)
        } else storageLauncher.launch(LEGACY_STORAGE)
    }
    LaunchedEffect(hasStoragePermission) { if (hasStoragePermission) grantCommand = null }

    val dirPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val rawPath = getAbsolutePathFromDocumentUri(context, uri)
            if (rawPath != null) {
                showAddShareDialogWithPath(rawPath) { newShare -> addNewShare(newShare) }
            } else {
                Toast.makeText(context, context.getString(R.string.taildrive_err_resolve_path), Toast.LENGTH_LONG).show()
                showAddShareDialogWithPath("") { newShare -> addNewShare(newShare) }
            }
        }
    }

    // The app's own folder chooser: where the system has none (a television often has not), and
    // when asking it fails. It answers with a path, which is what a share is.
    val inAppPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = result.data?.getStringExtra(TaildriveActivity.EXTRA_PATH)
        if (result.resultCode == Activity.RESULT_OK && path != null) {
            showAddShareDialogWithPath(path, suggestShareName(path, shares.map { it.name })) { newShare -> addNewShare(newShare) }
        }
    }
    val chooseFolder: () -> Unit = {
        returnFocus = addFocus
        if (!pickerOffered || !dirPickerLauncher.tryLaunch(null)) inAppPicker.launch(TaildriveActivity.pickFolderIntent(context))
    }
    val browseShares: () -> Unit = {
        returnFocus = entryFocus
        context.startActivity(TaildriveActivity.browseIntent(context))
    }

    // Update permission status when returning to activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !inPreview) {
                hasStoragePermission = checkStoragePermission(context)
                // The pause below took the focus away. A remote has no finger to touch the screen
                // again with: put it back on what it left to open the other window.
                val back = returnFocus
                returnFocus = null
                if (tv && back != null) runCatching { back.requestFocus() }
            }
            // A proxy field applies when it loses focus, and hiding the keyboard
            // does not take it away; switching apps to try the proxy should not
            // leave the typed value unapplied.
            if (event == Lifecycle.Event.ON_PAUSE) {
                focusManager.clearFocus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showChoiceDialog by remember { mutableStateOf(false) }

    if (showChoiceDialog) {
        // Strings resolved in the parent composition — see wrapContextWithLocale().
        val strTaildriveCdAdd = stringResource(R.string.taildrive_cd_add)
        val strTaildriveAddChoose = stringResource(R.string.taildrive_add_choose)
        // Where the system has no folder picker, the app's own takes its place on the same card.
        val strTaildriveAddPickerTitle = stringResource(if (pickerOffered) R.string.taildrive_add_picker_title else R.string.taildrive_add_browse_title)
        val strTaildriveAddPickerDesc = stringResource(if (pickerOffered) R.string.taildrive_add_picker_desc else R.string.taildrive_add_browse_desc)
        val strTaildriveAddManualTitle = stringResource(R.string.taildrive_add_manual_title)
        val strTaildriveAddManualDesc = stringResource(R.string.taildrive_add_manual_desc)
        val strActionCancel = stringResource(R.string.action_cancel)
        AlertDialog(
            onDismissRequest = { showChoiceDialog = false },
            title = { Text(strTaildriveCdAdd) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strTaildriveAddChoose, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedCard(
                        onClick = {
                            showChoiceDialog = false
                            chooseFolder()
                        },
                        modifier = Modifier.fillMaxWidth().driveInitialFocus()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text(strTaildriveAddPickerTitle, fontWeight = FontWeight.Bold)
                                Text(strTaildriveAddPickerDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    OutlinedCard(
                        onClick = {
                            showChoiceDialog = false
                            showAddShareDialogWithPath("") { newShare -> addNewShare(newShare) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text(strTaildriveAddManualTitle, fontWeight = FontWeight.Bold)
                                Text(strTaildriveAddManualDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChoiceDialog = false }) {
                    Text(strActionCancel)
                }
            }
        )
    }

    removing?.let { target ->
        RemoveShareDialog(
            name = target.name,
            onConfirm = {
                removing = null
                removeShare(target)
            },
            onDismiss = { removing = null }
        )
    }

    // The page's pieces: items of a phone's list, columns on a larger window.
    val permissionCard: @Composable () -> Unit = {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.taildrive_perm_required), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                HelpText(
                    stringResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) R.string.taildrive_perm_desc else R.string.taildrive_perm_desc_storage),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        askStorage()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    // The remote starts here while the access is missing; the row to the other
                    // devices' shares takes over once it is given.
                    modifier = Modifier.driveInitialFocus(enabled = onBack != null, requester = grantFocus)
                ) {
                    Text(stringResource(R.string.taildrive_grant_perm))
                }
                // No page of Settings took the user to the access: say what to run from a computer.
                grantCommand?.let { command ->
                    Spacer(Modifier.height(12.dp))
                    val format = stringResource(R.string.file_browser_grant_unavailable_format, command)
                    val textColor = MaterialTheme.colorScheme.onErrorContainer
                    Text(
                        buildAnnotatedString {
                            append(format)
                            val at = format.indexOf(command)
                            if (at >= 0) addStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), at, at + command.length)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )
                }
            }
        }
    }

    // The switches, as rows in the style Settings uses, stacked at the rows' own spacing.
    val switches: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Shown as it is in effect, not as it is stored. Without
            // all-files access the server still runs, but the folders it
            // offers are closed to it, so an ON here promised what the
            // device was not doing. Tapping it then asks for the access,
            // which is what turning it on takes anyway.
            SettingsSwitchItem(
                title = stringResource(R.string.taildrive_enable_title),
                subtitle = stringResource(
                    when {
                        hasStoragePermission -> R.string.taildrive_enable_desc
                        // "Tap to allow" is a finger's word; a remote presses OK.
                        Feature.TOUCH_GESTURES_HINTS.offered() -> R.string.taildrive_enable_needs_access
                        else -> R.string.taildrive_enable_needs_access_tv
                    }
                ),
                icon = Icons.Default.FolderShared,
                checked = isEnabled && hasStoragePermission
            ) { checked ->
                if (checked && !checkStoragePermission(context)) {
                    askStorage()
                } else {
                    isEnabled = checked
                    prefs.edit().putBoolean("taildrive_enabled", checked).apply()
                    triggerServiceSettingsUpdate(context)
                }
            }

            // Read off the list itself, so deleting the "sdcard" share below
            // turns this off as well — a remembered copy kept it on.
            val isFullStorageShared = shares.any { isFullStoragePath(it.path) }
            SettingsSwitchItem(
                title = stringResource(R.string.taildrive_share_full_storage),
                subtitle = stringResource(R.string.taildrive_share_full_storage_desc),
                icon = Icons.Default.SdCard,
                checked = isFullStorageShared
            ) { checked ->
                if (checked) {
                    if (!checkStoragePermission(context)) {
                        askStorage()
                    }
                    if (!shares.any { isFullStoragePath(it.path) }) {
                        shares.add(LocalShare("sdcard", "/storage/emulated/0"))
                        saveShares(prefs, shares)
                        triggerServiceSettingsUpdate(context)
                    }
                } else {
                    shares.removeAll { isFullStoragePath(it.path) }
                    saveShares(prefs, shares)
                    triggerServiceSettingsUpdate(context)
                }
            }

            // Local WebDAV proxy to the tailnet's shares
            SettingsSwitchItem(
                title = stringResource(R.string.taildrive_enable_proxy_title),
                subtitle = stringResource(R.string.taildrive_enable_proxy_desc),
                icon = Icons.Default.Lan,
                checked = isProxyEnabled
            ) { checked ->
                isProxyEnabled = checked
                prefs.edit().putBoolean("taildrive_proxy_enabled", checked).apply()
                triggerServiceSettingsUpdate(context)
            }

            if (isProxyEnabled) {
                Column(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProxySettingField(
                        value = proxyIp,
                        label = stringResource(R.string.taildrive_proxy_ip),
                        placeholder = "127.0.0.1",
                        keyboardType = KeyboardType.Uri
                    ) { ip ->
                        proxyIp = ip
                        prefs.edit().putString("taildrive_proxy_ip", ip).apply()
                        triggerServiceSettingsUpdate(context)
                    }

                    ProxySettingField(
                        value = proxyPort,
                        label = stringResource(R.string.taildrive_proxy_port),
                        keyboardType = KeyboardType.Number,
                        accept = { port ->
                            val cleanPort = port.filter { it.isDigit() }
                            val num = cleanPort.toIntOrNull()
                            cleanPort.takeIf { it.length <= 5 && (num == null || num <= 65535) }
                        }
                    ) { port ->
                        proxyPort = port
                        prefs.edit().putString("taildrive_proxy_port", port).apply()
                        triggerServiceSettingsUpdate(context)
                    }
                }

                SettingsSwitchItem(
                    title = stringResource(R.string.taildrive_require_auth),
                    subtitle = stringResource(R.string.taildrive_require_auth_desc),
                    icon = Icons.Default.Lock,
                    checked = isProxyAuthEnabled
                ) { checked ->
                    isProxyAuthEnabled = checked
                    prefs.edit().putBoolean("taildrive_proxy_auth_enabled", checked).apply()
                    triggerServiceSettingsUpdate(context)
                }

                if (isProxyAuthEnabled) {
                    Column(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProxySettingField(
                            value = proxyUsername,
                            label = stringResource(R.string.taildrive_username)
                        ) { user ->
                            proxyUsername = user
                            prefs.edit().putString("taildrive_proxy_username", user).apply()
                            triggerServiceSettingsUpdate(context)
                        }

                        ProxySettingField(
                            value = proxyPassword,
                            label = stringResource(R.string.taildrive_password),
                            keyboardType = KeyboardType.Password,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = stringResource(
                                            if (showPassword) R.string.taildrive_hide_password
                                            else R.string.taildrive_show_password
                                        )
                                    )
                                }
                            }
                        ) { pass ->
                            proxyPassword = pass
                            prefs.edit().putString("taildrive_proxy_password", pass).apply()
                            triggerServiceSettingsUpdate(context)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (!clipboardOffered) {
                    // A screen with no clipboard has nowhere to paste a copied address, so it shows it:
                    // readable from across the room, and as a code. See DriveAddressCard.
                    val lan = demo?.lan ?: remember(isProxyEnabled) { if (inPreview) null else DriveAddresses.lanAddress() }
                    val address = remember(proxyIp, proxyPort, isProxyAuthEnabled, proxyUsername, proxyPassword, lan) {
                        DriveAddresses.of(proxyIp, proxyPort, isProxyAuthEnabled, proxyUsername, proxyPassword, lan)
                    }
                    DriveAddressCard(address, revealLogin = showPassword, onToggleLogin = { showPassword = !showPassword })
                } else {
                    // Copyable URL Card. Built from the stored values, not the
                    // ones still being typed: it names what the proxy serves.
                    val hasCredentials = isProxyAuthEnabled && proxyUsername.isNotEmpty() && proxyPassword.isNotEmpty()
                    val formattedUrl = remember(proxyIp, proxyPort, hasCredentials, proxyUsername, proxyPassword) {
                        if (hasCredentials) {
                            "http://$proxyUsername:$proxyPassword@$proxyIp:$proxyPort"
                        } else {
                            "http://$proxyIp:$proxyPort"
                        }
                    }
                    // The password field hides the password; printing it in the
                    // URL right under it would undo that. The copy stays whole.
                    val shownUrl = if (hasCredentials && !showPassword) {
                        "http://$proxyUsername:••••••••@$proxyIp:$proxyPort"
                    } else formattedUrl
                    val copyUrl = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("WebDAV URL", formattedUrl)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, context.getString(R.string.taildrive_copied_url), Toast.LENGTH_SHORT).show()
                    }

                    // The whole card copies, as its label says; the icon stays as
                    // the visible and spoken affordance.
                    Card(
                        onClick = copyUrl,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.taildrive_webdav_url_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = shownUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            IconButton(onClick = copyUrl) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.action_copy),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Adding a share is possible once sharing is on and the access is there.
    val canAdd = isEnabled && hasStoragePermission

    val sharesHeading: @Composable () -> Unit = {
        val heading = stringResource(R.string.taildrive_shared_folders)
        if (tv) {
            // A remote reaches a button in the heading in one step from the top; the floating
            // one sits in a corner it would have to cross the whole list to find.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (canAdd) {
                    FilledTonalButton(onClick = { showChoiceDialog = true }, modifier = Modifier.driveInitialFocus(enabled = false, requester = addFocus)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.taildrive_cd_add))
                    }
                }
            }
        } else {
            Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }

    val sharingOffNote: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.taildrive_disabled_msg), color = MaterialTheme.colorScheme.outline)
        }
    }

    val noSharesNote: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.taildrive_empty_shares), color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { chooseFolder() }) {
                    Text(stringResource(R.string.taildrive_share_a_folder))
                }
            }
        }
    }

    val shareCard: @Composable (LocalShare) -> Unit = { share ->
        Card(
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(share.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    Text(share.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    IconButton(onClick = {
                        showEditShareDialog(share) { updatedShare ->
                            val index = shares.indexOf(share)
                            if (index != -1) {
                                if (shares.any { it != share && it.name.lowercase() == updatedShare.name.lowercase() }) {
                                    Toast.makeText(context, context.getString(R.string.taildrive_err_name_exists), Toast.LENGTH_SHORT).show()
                                } else {
                                    shares[index] = updatedShare
                                    saveShares(prefs, shares)
                                    triggerServiceSettingsUpdate(context)
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Default.Edit, stringResource(R.string.action_edit), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        // A remote presses OK on whatever it is on: ask first, there.
                        if (tv) removing = share else removeShare(share)
                    }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    val window = rememberWindowLayout()
    // On a television the way into the other devices' shares is a row of the page itself; elsewhere
    // it sits in the bar or floats, and the controls stay where they were. In the Files hub it is a
    // small button over Add, and the list leaves room under it.
    val browseRow: @Composable () -> Unit = {
        BrowseSharesRow(
            onClick = browseShares,
            modifier = Modifier.driveInitialFocus(enabled = onBack != null && hasStoragePermission, requester = entryFocus)
        )
    }
    val bottomRoom = if (onBack == null && !tv) 136.dp else 88.dp
    val mainContent = @Composable { paddingValues: PaddingValues ->
        if (window.multiColumn) {
            // From a medium window up, the switches in one column and the shared folders in
            // the other, the permission banner across both: one column across the window put
            // every switch a window's width away from its label, over a screen of nothing.
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = window.margin, end = window.margin, top = 12.dp, bottom = bottomRoom),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!hasStoragePermission) permissionCard()
                CardColumns(minColumnWidth = 320.dp, maxColumns = 2, spacing = 24.dp) {
                    if (tv) Column { browseRow(); switches() } else switches()
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        sharesHeading()
                        when {
                            !isEnabled -> sharingOffNote()
                            shares.isEmpty() -> noSharesNote()
                            else -> shares.forEach { shareCard(it) }
                        }
                    }
                }
            }
        } else LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = bottomRoom)
        ) {
            // Permission Card
            if (!hasStoragePermission) {
                item { permissionCard() }
            }

            if (tv) item { browseRow() }

            // One item, so the switches stack at the rows' own spacing rather than at the list's.
            item { switches() }

            item { sharesHeading() }

            if (!isEnabled) {
                item { sharingOffNote() }
            } else if (shares.isEmpty()) {
                item { noSharesNote() }
            } else {
                items(shares) { share -> shareCard(share) }
            }
        }

        // Custom Add/Edit Share Dialog
        if (showAddDialog && onDialogSubmit != null) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strTaildriveEditTitle = stringResource(R.string.taildrive_edit_title)
            val strTaildriveAddTitle = stringResource(R.string.taildrive_add_title)
            val strTaildriveShareName = stringResource(R.string.taildrive_share_name)
            val strTaildriveShareNamePlaceholder = stringResource(R.string.taildrive_share_name_placeholder)
            val strTaildrivePhysicalPath = stringResource(R.string.taildrive_physical_path)
            val strTaildrivePhysicalPathPlaceholder = stringResource(R.string.taildrive_physical_path_placeholder)
            val strActionSave = stringResource(R.string.action_save)
            val strActionAdd = stringResource(R.string.action_add)
            val strActionCancel = stringResource(R.string.action_cancel)
            // A folder that was chosen arrives with its name and path filled in: a remote starts on
            // Save, not in a field that opens the keyboard. Decided once, as the dialog opens —
            // typing must not take the focus from the field being typed in.
            val startOnSave = remember { dialogNameInput.isNotBlank() && dialogPathInput.isNotBlank() }
            AlertDialog(
                onDismissRequest = {
                    showAddDialog = false
                    editingShare = null
                },
                title = { Text(if (editingShare != null) strTaildriveEditTitle else strTaildriveAddTitle) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dialogNameInput,
                            onValueChange = { dialogNameInput = it.replace(Regex("[^a-zA-Z0-9_]"), "") },
                            label = { Text(strTaildriveShareName) },
                            placeholder = { Text(strTaildriveShareNamePlaceholder) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = dialogPathInput,
                            onValueChange = { dialogPathInput = it },
                            label = { Text(strTaildrivePhysicalPath) },
                            placeholder = { Text(strTaildrivePhysicalPathPlaceholder) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        modifier = Modifier.driveInitialFocus(enabled = startOnSave),
                        onClick = {
                            if (dialogNameInput.isBlank() || dialogPathInput.isBlank()) {
                                Toast.makeText(context, context.getString(R.string.taildrive_err_fields_empty), Toast.LENGTH_SHORT).show()
                            } else {
                                val file = File(dialogPathInput)
                                if (!file.exists() || !file.isDirectory) {
                                    Toast.makeText(context, context.getString(R.string.taildrive_err_path_invalid), Toast.LENGTH_SHORT).show()
                                } else {
                                    onDialogSubmit?.invoke(LocalShare(dialogNameInput, dialogPathInput))
                                    showAddDialog = false
                                    editingShare = null
                                }
                            }
                        }
                    ) {
                        Text(if (editingShare != null) strActionSave else strActionAdd)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showAddDialog = false
                        editingShare = null
                    }) {
                        Text(strActionCancel)
                    }
                }
            )
        }
    }

    if (onBack != null) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.taildrive_title),
                    subtitle = activeAccount.name,
                    onBack = onBack,
                    actions = {
                        // The icon is the whole addition on a phone or a tablet. A television has the row.
                        if (!tv) BrowseSharesButton(onClick = browseShares)
                    }
                )
            },
            floatingActionButton = {
                // A television has the button in the heading of the list instead.
                if (canAdd && !tv) {
                    FloatingActionButton(onClick = { showChoiceDialog = true }) {
                        Icon(Icons.Default.Add, stringResource(R.string.taildrive_cd_add))
                    }
                }
            }
        ) { padding ->
            // Held to a readable width on a phone on its side; see ReadableWidth. A larger
            // window lays the page out in columns of its own.
            if (window.multiColumn) mainContent(padding) else ReadableWidth { mainContent(padding) }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            mainContent(PaddingValues(0.dp))
            if (!tv) {
                // The page has no bar here: the way into the other devices' shares is a small
                // button over Add, which stays where it was.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BrowseSharesFab(onClick = browseShares)
                    if (canAdd) {
                        FloatingActionButton(onClick = { showChoiceDialog = true }) {
                            Icon(Icons.Default.Add, stringResource(R.string.taildrive_cd_add))
                        }
                    }
                }
            }
        }
    }
}
}

/**
 * A proxy setting typed in place. Each value handed to [onCommit] restarts the
 * drive proxy through a full settings apply — a pause, the routes, in TUN mode
 * the tunnel — so applying per keystroke did that ten times for one address.
 * The text is held here while it is typed and committed once: on Done, when the
 * field loses focus, or when it leaves the screen. [accept] reshapes or, with
 * null, refuses a keystroke.
 */
@Composable
private fun ProxySettingField(
    value: String,
    label: String,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    accept: (String) -> String? = { it },
    onCommit: (String) -> Unit
) {
    // A field in the page takes the remote's focus whenever its arrows pass over it, and the
    // keyboard comes up with the focus: the arrows then belong to the keyboard and the page cannot be
    // left. On a television the field is a row, and OK opens it.
    if (isTv) {
        ProxySettingRow(value, label, keyboardType, visualTransformation, trailingIcon, accept, onCommit)
        return
    }
    // Keyed on the stored value, so a change made elsewhere — the generated
    // password — replaces the draft instead of hiding behind it.
    var draft by remember(value) { mutableStateOf(value) }
    // What was last handed out. A focused field that leaves the screen can lose
    // focus and be disposed in the same pass; this keeps that to one apply.
    val committed = remember(value) { CommittedText(value) }
    val focusManager = LocalFocusManager.current
    val commit = {
        if (draft != committed.text) {
            committed.text = draft
            onCommit(draft)
        }
    }
    val latestCommit by rememberUpdatedState(commit)
    DisposableEffect(Unit) { onDispose { latestCommit() } }
    OutlinedTextField(
        value = draft,
        onValueChange = { typed -> accept(typed)?.let { draft = it } },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) commit() }
    )
}

private class CommittedText(var text: String)

/**
 * [ProxySettingField] for a remote: a row with the setting's name and value, which OK opens as a
 * dialog around the field. What is typed is committed with Save, once. The words are resolved
 * here, in the screen's composition, for the dialog's window does not follow the app's language.
 */
@Composable
private fun ProxySettingRow(
    value: String,
    label: String,
    keyboardType: KeyboardType,
    visualTransformation: VisualTransformation,
    trailingIcon: (@Composable () -> Unit)?,
    accept: (String) -> String?,
    onCommit: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    val save = stringResource(R.string.action_save)
    val cancel = stringResource(R.string.action_cancel)
    val shown = visualTransformation.filter(AnnotatedString(value)).text.text
    Surface(
        onClick = { editing = true },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(shown, style = MaterialTheme.typography.bodyLarge)
            }
            trailingIcon?.invoke()
        }
    }
    if (editing) {
        var draft by remember { mutableStateOf(value) }
        val done = {
            if (draft != value) onCommit(draft)
            editing = false
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { typed -> accept(typed)?.let { draft = it } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { done() }),
                    visualTransformation = visualTransformation,
                    // It is what was asked for by pressing the row: the keyboard may come up.
                    modifier = Modifier.fillMaxWidth().driveInitialFocus()
                )
            },
            confirmButton = { TextButton(onClick = done) { Text(save) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(cancel) } }
        )
    }
}

private val LEGACY_STORAGE = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

/**
 * Whether the daemon can open the shared folders by path: all-files access from Android 11,
 * the Storage permission (read and write, with legacy storage on Android 10) below that.
 */
private fun checkStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * The app's own settings page, where a Storage permission refused for good is given back; false
 * when the device has none (a television may not), and the caller says what to run from a computer.
 */
private fun openAppSettings(context: Context): Boolean {
    Toast.makeText(context, context.getString(R.string.taildrive_err_grant_storage), Toast.LENGTH_LONG).show()
    return context.tryStartActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun saveShares(prefs: SharedPreferences, shares: List<LocalShare>) {
    val json = AppJson.encodeToString(shares)
    prefs.edit().putString("taildrive_shares", json).apply()
}

private fun triggerServiceSettingsUpdate(context: Context) {
    // Notify the running TailscaledService to reload preferences and apply shares.
    val intent = Intent(context, TailscaledService::class.java).apply {
        action = TailscaledService.ACTION_APPLY_SETTINGS
    }
    context.startService(intent)
}

/**
 * What the screen's proxy switch does when it is turned on, for the page of the other devices'
 * shares, which cannot show a file without the proxy: store the switch for the active profile and
 * have the service apply it.
 */
private fun enableDriveProxy(context: Context) {
    val account = AccountManager.getActiveAccount(context)
    context.getSharedPreferences("appctr_${account.id}", Context.MODE_PRIVATE)
        .edit().putBoolean(DriveProxy.KEY_ENABLED, true).apply()
    triggerServiceSettingsUpdate(context)
}

private fun getAbsolutePathFromDocumentUri(context: Context, uri: Uri): String? {
    if ("com.android.externalstorage.documents" == uri.authority) {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            if ("primary" == type.lowercase()) {
                return "/storage/emulated/0/" + if (split.size > 1) split[1] else ""
            } else {
                return "/storage/$type/" + if (split.size > 1) split[1] else ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return null
}
