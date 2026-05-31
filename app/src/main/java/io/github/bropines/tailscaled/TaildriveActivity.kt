package io.github.bropines.tailscaled

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class TaildriveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                TaildriveScreen(onBack = { finish() })
            }
        }
    }
}

data class LocalShare(
    val name: String,
    val path: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaildriveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val prefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }

    var isEnabled by remember { mutableStateOf(prefs.getBoolean("taildrive_enabled", false)) }
    var sharesJson = prefs.getString("taildrive_shares", "[]") ?: "[]"
    val gson = Gson()
    val listType = object : TypeToken<ArrayList<LocalShare>>() {}.type
    val initialShares: ArrayList<LocalShare> = try {
        gson.fromJson(sharesJson, listType) ?: ArrayList()
    } catch (e: Exception) {
        ArrayList()
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
            prefs.edit().putString("taildrive_proxy_password", generated).commit()
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
                        Toast.makeText(context, "Share name already exists", Toast.LENGTH_SHORT).show()
                    } else {
                        shares.add(newShare)
                        saveShares(prefs, shares)
                        triggerServiceSettingsUpdate(context)
                    }
                }
            } else {
                Toast.makeText(context, "Could not resolve physical folder path. Please enter manually.", Toast.LENGTH_LONG).show()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Taildrive Shares")
                        Text(activeAccount.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (isEnabled && hasStoragePermission) {
                FloatingActionButton(onClick = {
                    dirPickerLauncher.launch(null)
                }) {
                    Icon(Icons.Default.Add, "Add Share")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Card
            if (!hasStoragePermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Storage Permission Required", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Taildrive needs permission to access all files in order to expose shared directories to your Tailnet.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                requestStoragePermission(context)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
            }

            // Enable Toggle Row
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
                        Text("Enable Taildrive", fontWeight = FontWeight.Bold)
                        Text(
                            "Share local directories with other nodes in your Tailnet.",
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
                                prefs.edit().putBoolean("taildrive_enabled", checked).commit()
                                triggerServiceSettingsUpdate(context)
                            }
                        }
                    )
                }
            }

            // Enable TailDrive Proxy Card
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
                            Text("Enable TailDrive Proxy", fontWeight = FontWeight.Bold)
                            Text(
                                "Access remote shared directories from local apps.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isProxyEnabled,
                            onCheckedChange = { checked ->
                                isProxyEnabled = checked
                                prefs.edit().putBoolean("taildrive_proxy_enabled", checked).commit()
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
                                prefs.edit().putString("taildrive_proxy_ip", ip).commit()
                                triggerServiceSettingsUpdate(context)
                            },
                            label = { Text("Local Proxy IP") },
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
                                proxyPort = cleanPort
                                prefs.edit().putString("taildrive_proxy_port", cleanPort).commit()
                                triggerServiceSettingsUpdate(context)
                            },
                            label = { Text("Local Proxy Port") },
                            singleLine = true,
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
                                Text("Require Authentication", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Secure local proxy using Basic Authentication.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isProxyAuthEnabled,
                                onCheckedChange = { checked ->
                                    isProxyAuthEnabled = checked
                                    prefs.edit().putBoolean("taildrive_proxy_auth_enabled", checked).commit()
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
                                    prefs.edit().putString("taildrive_proxy_username", user).commit()
                                    triggerServiceSettingsUpdate(context)
                                },
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = proxyPassword,
                                onValueChange = { pass ->
                                    proxyPassword = pass
                                    prefs.edit().putString("taildrive_proxy_password", pass).commit()
                                    triggerServiceSettingsUpdate(context)
                                },
                                label = { Text("Password") },
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
                                    Text("WebDAV URL (Tap to Copy)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
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
                                        Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy URL",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text("Shared Folders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            if (!isEnabled) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Taildrive is disabled", color = MaterialTheme.colorScheme.outline)
                }
            } else if (shares.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("No folders shared yet", color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            dirPickerLauncher.launch(null)
                        }) {
                            Text("Share a folder")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                                                    Toast.makeText(context, "Share name already exists", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    shares[index] = updatedShare
                                                    saveShares(prefs, shares)
                                                    triggerServiceSettingsUpdate(context)
                                                }
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = {
                                        shares.remove(share)
                                        saveShares(prefs, shares)
                                        triggerServiceSettingsUpdate(context)
                                    }) {
                                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                    }
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
                title = { Text(if (editingShare != null) "Edit Folder Share" else "Add Folder Share") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dialogNameInput,
                            onValueChange = { dialogNameInput = it.replace(Regex("[^a-zA-Z0-9_]"), "") },
                            label = { Text("Share Name") },
                            placeholder = { Text("Example: downloads") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = dialogPathInput,
                            onValueChange = { dialogPathInput = it },
                            label = { Text("Physical Local Path") },
                            placeholder = { Text("/storage/emulated/0/Download") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (dialogNameInput.isBlank() || dialogPathInput.isBlank()) {
                                Toast.makeText(context, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                            } else {
                                val file = File(dialogPathInput)
                                if (!file.exists() || !file.isDirectory) {
                                    Toast.makeText(context, "Path does not exist or is not a folder", Toast.LENGTH_SHORT).show()
                                } else {
                                    onDialogSubmit?.invoke(LocalShare(dialogNameInput, dialogPathInput))
                                    showAddDialog = false
                                    editingShare = null
                                }
                            }
                        }
                    ) {
                        Text(if (editingShare != null) "Save" else "Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showAddDialog = false
                        editingShare = null
                    }) {
                        Text("Cancel")
                    }
                }
            )
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
        Toast.makeText(context, "Please grant storage permission in App Settings", Toast.LENGTH_LONG).show()
    }
}

private fun saveShares(prefs: SharedPreferences, shares: List<LocalShare>) {
    val json = Gson().toJson(shares)
    prefs.edit().putString("taildrive_shares", json).commit()
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
