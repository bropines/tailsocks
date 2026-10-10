package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.Manifest
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        setContent {
            TailSocksTheme {
                TaildriveScreen(onBack = { finish() })
            }
        }
    }
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
 */
internal class TaildriveDemo(val shares: List<LocalShare>, val storageAccess: Boolean = true, val proxy: Boolean = false)

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
    var proxyIp by remember { mutableStateOf(prefs.getString("taildrive_proxy_ip", "127.0.0.1") ?: "127.0.0.1") }
    var proxyPort by remember { mutableStateOf(prefs.getString("taildrive_proxy_port", "33445") ?: "33445") }
    var isProxyAuthEnabled by remember { mutableStateOf(prefs.getBoolean("taildrive_proxy_auth_enabled", false)) }
    var proxyUsername by remember { mutableStateOf(prefs.getString("taildrive_proxy_username", "tailsocks") ?: "tailsocks") }
    var proxyPassword by remember {
        val pass = prefs.getString("taildrive_proxy_password", "") ?: ""
        mutableStateOf(pass)
    }
    // One switch for the field and the URL card under it, which carries the same password.
    var showPassword by rememberSaveable { mutableStateOf(false) }

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

    val showAddShareDialogWithPath = { path: String, onSubmit: (LocalShare) -> Unit ->
        dialogPathInput = path
        dialogNameInput = if (path.isNotEmpty()) File(path).name.replace(Regex("[^a-zA-Z0-9_]"), "") else ""
        onDialogSubmit = onSubmit
        showAddDialog = true
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
    val dirPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val rawPath = getAbsolutePathFromDocumentUri(context, uri)
            if (rawPath != null) {
                showAddShareDialogWithPath(rawPath) { newShare ->
                    if (shares.any { it.name.lowercase() == newShare.name.lowercase() }) {
                        Toast.makeText(context, context.getString(R.string.taildrive_err_name_exists), Toast.LENGTH_SHORT).show()
                    } else {
                        shares.add(newShare)
                        saveShares(prefs, shares)
                        triggerServiceSettingsUpdate(context)
                    }
                }
            } else {
                Toast.makeText(context, context.getString(R.string.taildrive_err_resolve_path), Toast.LENGTH_LONG).show()
                showAddShareDialogWithPath("") { newShare ->
                    shares.add(newShare)
                    saveShares(prefs, shares)
                    triggerServiceSettingsUpdate(context)
                }
            }
        }
    }

    // Update permission status when returning to activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !inPreview) {
                hasStoragePermission = checkStoragePermission(context)
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
        val strTaildriveAddPickerTitle = stringResource(R.string.taildrive_add_picker_title)
        val strTaildriveAddPickerDesc = stringResource(R.string.taildrive_add_picker_desc)
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
                            dirPickerLauncher.launch(null)
                        },
                        modifier = Modifier.fillMaxWidth()
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
                            showAddShareDialogWithPath("") { newShare ->
                                if (shares.any { it.name.lowercase() == newShare.name.lowercase() }) {
                                    Toast.makeText(context, context.getString(R.string.taildrive_err_name_exists), Toast.LENGTH_SHORT).show()
                                } else {
                                    shares.add(newShare)
                                    saveShares(prefs, shares)
                                    triggerServiceSettingsUpdate(context)
                                }
                            }
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

    val mainContent = @Composable { paddingValues: PaddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp)
        ) {
            // Permission Card
            if (!hasStoragePermission) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.taildrive_perm_required), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            HelpText(
                                stringResource(R.string.taildrive_perm_desc),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    requestStoragePermission(context)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(stringResource(R.string.taildrive_grant_perm))
                            }
                        }
                    }
                }
            }

            // The switches, as rows in the style Settings uses. One item, so they
            // stack at the rows' own spacing rather than at the list's.
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Shown as it is in effect, not as it is stored. Without
                    // all-files access the server still runs, but the folders it
                    // offers are closed to it, so an ON here promised what the
                    // device was not doing. Tapping it then asks for the access,
                    // which is what turning it on takes anyway.
                    SettingsSwitchItem(
                        title = stringResource(R.string.taildrive_enable_title),
                        subtitle = stringResource(
                            if (hasStoragePermission) R.string.taildrive_enable_desc
                            else R.string.taildrive_enable_needs_access
                        ),
                        icon = Icons.Default.FolderShared,
                        checked = isEnabled && hasStoragePermission
                    ) { checked ->
                        if (checked && !checkStoragePermission(context)) {
                            requestStoragePermission(context)
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
                                requestStoragePermission(context)
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

            item {
                Text(stringResource(R.string.taildrive_shared_folders), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            if (!isEnabled) {
                item {
                    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.taildrive_disabled_msg), color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else if (shares.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(stringResource(R.string.taildrive_empty_shares), color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                dirPickerLauncher.launch(null)
                            }) {
                                Text(stringResource(R.string.taildrive_share_a_folder))
                            }
                        }
                    }
                }
            } else {
                items(shares) { share ->
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
                                    shares.remove(share)
                                    saveShares(prefs, shares)
                                    triggerServiceSettingsUpdate(context)
                                }) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
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
                    onBack = onBack
                )
            },
            floatingActionButton = {
                if (isEnabled && hasStoragePermission) {
                    FloatingActionButton(onClick = { showChoiceDialog = true }) {
                        Icon(Icons.Default.Add, stringResource(R.string.taildrive_cd_add))
                    }
                }
            }
        ) { padding ->
            // Held to a readable width on a tablet; see ReadableWidth.
            ReadableWidth {
            mainContent(padding)
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            mainContent(PaddingValues(0.dp))
            if (isEnabled && hasStoragePermission) {
                FloatingActionButton(
                    onClick = { showChoiceDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.Add, stringResource(R.string.taildrive_cd_add))
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

private fun isFullStoragePath(path: String) = path == "/storage/emulated/0" || path == "/storage/emulated/0/"

private fun checkStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun requestStoragePermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            context.startActivity(intent)
        }
    } else {
        Toast.makeText(context, context.getString(R.string.taildrive_err_grant_storage), Toast.LENGTH_LONG).show()
    }
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
