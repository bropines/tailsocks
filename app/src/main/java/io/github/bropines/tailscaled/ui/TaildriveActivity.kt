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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val shares = remember { mutableStateListOf<LocalShare>().apply { addAll(initialShares) } }

    var hasStoragePermission by remember { mutableStateOf(checkStoragePermission(context)) }
    var isProxyEnabled by remember { mutableStateOf(prefs.getBoolean("taildrive_proxy_enabled", false)) }
    var proxyIp by remember { mutableStateOf(prefs.getString("taildrive_proxy_ip", "127.0.0.1") ?: "127.0.0.1") }
    var proxyPort by remember { mutableStateOf(prefs.getString("taildrive_proxy_port", "33445") ?: "33445") }
    var isProxyAuthEnabled by remember { mutableStateOf(prefs.getBoolean("taildrive_proxy_auth_enabled", false)) }
    var proxyUsername by remember { mutableStateOf(prefs.getString("taildrive_proxy_username", "tailsocks") ?: "tailsocks") }
    var proxyPassword by remember {
        val pass = prefs.getString("taildrive_proxy_password", "") ?: ""
        mutableStateOf(pass)
    }

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
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = checkStoragePermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showChoiceDialog by remember { mutableStateOf(false) }

    if (showChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showChoiceDialog = false },
            title = { Text(stringResource(R.string.taildrive_cd_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.taildrive_add_choose), style = MaterialTheme.typography.bodyMedium)
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
                                Text(stringResource(R.string.taildrive_add_picker_title), fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.taildrive_add_picker_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(stringResource(R.string.taildrive_add_manual_title), fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.taildrive_add_manual_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChoiceDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
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
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.taildrive_perm_required), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.taildrive_perm_desc), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(16.dp))
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

            // Enable Toggle Row
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
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
                            Text(stringResource(R.string.taildrive_enable_title), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.taildrive_enable_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                if (checked && !checkStoragePermission(context)) {
                                    requestStoragePermission(context)
                                } else {
                                    isEnabled = checked
                                    prefs.edit().putBoolean("taildrive_enabled", checked).apply()
                                    triggerServiceSettingsUpdate(context)
                                }
                            }
                        )
                    }
                }
            }

            // Share Full Internal Storage Toggle Card
            item {
                var isFullStorageShared by remember {
                    mutableStateOf(shares.any { it.path == "/storage/emulated/0" || it.path == "/storage/emulated/0/" })
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isFullStorageShared) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.SdCard, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.taildrive_share_full_storage), fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Stream entire internal memory (/storage/emulated/0) via Taildrive",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isFullStorageShared,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    if (!checkStoragePermission(context)) {
                                        requestStoragePermission(context)
                                    }
                                    if (!shares.any { it.path == "/storage/emulated/0" || it.path == "/storage/emulated/0/" }) {
                                        shares.add(LocalShare("sdcard", "/storage/emulated/0"))
                                        saveShares(prefs, shares)
                                        triggerServiceSettingsUpdate(context)
                                    }
                                    isFullStorageShared = true
                                } else {
                                    shares.removeAll { it.path == "/storage/emulated/0" || it.path == "/storage/emulated/0/" }
                                    saveShares(prefs, shares)
                                    triggerServiceSettingsUpdate(context)
                                    isFullStorageShared = false
                                }
                            }
                        )
                    }
                }
            }

            // Enable TailDrive Proxy Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.taildrive_enable_proxy_title), fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.taildrive_enable_proxy_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isProxyEnabled,
                                onCheckedChange = { checked ->
                                    isProxyEnabled = checked
                                    prefs.edit().putBoolean("taildrive_proxy_enabled", checked).apply()
                                    triggerServiceSettingsUpdate(context)
                                }
                            )
                        }

                        if (isProxyEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(12.dp))

                            // IP Input Field
                            OutlinedTextField(
                                value = proxyIp,
                                onValueChange = { ip ->
                                    proxyIp = ip
                                    prefs.edit().putString("taildrive_proxy_ip", ip).apply()
                                    triggerServiceSettingsUpdate(context)
                                },
                                label = { Text(stringResource(R.string.taildrive_proxy_ip)) },
                                placeholder = { Text("127.0.0.1") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Port Input Field
                            OutlinedTextField(
                                value = proxyPort,
                                onValueChange = { port ->
                                    val cleanPort = port.filter { it.isDigit() }
                                    if (cleanPort.length <= 5) {
                                        val num = cleanPort.toIntOrNull()
                                        if (num == null || num <= 65535) {
                                            proxyPort = cleanPort
                                            prefs.edit().putString("taildrive_proxy_port", cleanPort).apply()
                                            triggerServiceSettingsUpdate(context)
                                        }
                                    }
                                },
                                label = { Text(stringResource(R.string.taildrive_proxy_port)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Require Authentication Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(stringResource(R.string.taildrive_require_auth), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        stringResource(R.string.taildrive_require_auth_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = isProxyAuthEnabled,
                                    onCheckedChange = { checked ->
                                        isProxyAuthEnabled = checked
                                        prefs.edit().putBoolean("taildrive_proxy_auth_enabled", checked).apply()
                                        triggerServiceSettingsUpdate(context)
                                    }
                                )
                            }

                            if (isProxyAuthEnabled) {
                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = proxyUsername,
                                    onValueChange = { user ->
                                        proxyUsername = user
                                        prefs.edit().putString("taildrive_proxy_username", user).apply()
                                        triggerServiceSettingsUpdate(context)
                                    },
                                    label = { Text(stringResource(R.string.taildrive_username)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = proxyPassword,
                                    onValueChange = { pass ->
                                        proxyPassword = pass
                                        prefs.edit().putString("taildrive_proxy_password", pass).apply()
                                        triggerServiceSettingsUpdate(context)
                                    },
                                    label = { Text(stringResource(R.string.taildrive_password)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Copyable URL Card
                            val formattedUrl = remember(proxyIp, proxyPort, isProxyAuthEnabled, proxyUsername, proxyPassword) {
                                if (isProxyAuthEnabled && proxyUsername.isNotEmpty() && proxyPassword.isNotEmpty()) {
                                    "http://$proxyUsername:$proxyPassword@$proxyIp:$proxyPort"
                                } else {
                                    "http://$proxyIp:$proxyPort"
                                }
                            }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                shape = RoundedCornerShape(12.dp),
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
                                            text = formattedUrl,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("WebDAV URL", formattedUrl)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, context.getString(R.string.taildrive_copied_url), Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
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
                        shape = RoundedCornerShape(12.dp),
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
            AlertDialog(
                onDismissRequest = { 
                    showAddDialog = false
                    editingShare = null
                },
                title = { Text(if (editingShare != null) stringResource(R.string.taildrive_edit_title) else stringResource(R.string.taildrive_add_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dialogNameInput,
                            onValueChange = { dialogNameInput = it.replace(Regex("[^a-zA-Z0-9_]"), "") },
                            label = { Text(stringResource(R.string.taildrive_share_name)) },
                            placeholder = { Text(stringResource(R.string.taildrive_share_name_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = dialogPathInput,
                            onValueChange = { dialogPathInput = it },
                            label = { Text(stringResource(R.string.taildrive_physical_path)) },
                            placeholder = { Text(stringResource(R.string.taildrive_physical_path_placeholder)) },
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
                        Text(if (editingShare != null) stringResource(R.string.action_save) else stringResource(R.string.action_add))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showAddDialog = false
                        editingShare = null
                    }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }

    if (onBack != null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.taildrive_title))
                            Text(activeAccount.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
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
            mainContent(padding)
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
