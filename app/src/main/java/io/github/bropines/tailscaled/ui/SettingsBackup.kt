package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.result.ActivityResultLauncher
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A backup or a restore waiting for its file, where the app's own browser picks it. */
internal enum class BackupFile { FULL_BACKUP, FULL_RESTORE, PROFILE_EXPORT, PROFILE_IMPORT }

private const val FULL_BACKUP_NAME = "tailsocks_full_backup.enc"

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

/**
 * Backup and restore, as Settings does them: the encrypted full backup of every account and
 * setting, the plain export of the open profile, and their way back. Made by
 * [rememberSettingsBackup] at the top of the screen, so the four pickers are registered
 * whichever page is open; the page ([SettingsBackupPage]) starts a flow, the screen draws its
 * dialogs over every page ([SettingsBackupDialogs]) and, where the system has no picker, the
 * app's own file browser in place of the settings ([SettingsBackupBrowser]).
 */
@Stable
internal class SettingsBackupFlow(
    val context: Context,
    val scope: CoroutineScope,
    val activeAccount: TailscaleAccount,
    val profilePrefs: SharedPreferences,
    showBackupPasswordDialogState: MutableState<Boolean>,
    pendingBackupUriState: MutableState<Uri?>,
    browsingForState: MutableState<BackupFile?>,
) {
    // The password itself is never saved state (BackupRestore.PasswordHold keeps it in
    // the process); the dialog's visibility and the file a lost password left without
    // an archive are, so the question comes back after a recreation.
    var showBackupPasswordDialog by showBackupPasswordDialogState
    var pendingBackupUri by pendingBackupUriState

    var restorePassword by mutableStateOf("")
    var showRestorePasswordDialog by mutableStateOf(false)
    var pendingRestoreUri by mutableStateOf<Uri?>(null)
    // After a full restore: restart now, or later. What the restart writes is held in the
    // process, and the question follows it rather than saved state: asked again when
    // Settings is recreated or reopened before an answer, and not at all once the
    // process has died, which was the restart.
    var showRestartDialog by mutableStateOf(BackupRestore.PendingRestart.pending)
    var restarting by mutableStateOf(false)

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

    // The system's four pickers, registered by rememberSettingsBackup.
    lateinit var backupLauncher: ActivityResultLauncher<String>
    lateinit var restoreLauncher: ActivityResultLauncher<String>
    lateinit var fullBackupLauncher: ActivityResultLauncher<String>
    lateinit var fullRestoreLauncher: ActivityResultLauncher<String>

    // The system's picker where there is one. Where there is none — a television, as a
    // rule — or it will not start, the app's own file browser takes the settings' place
    // until the file is chosen (at the end of SettingsScreen, SettingsBackupBrowser).
    var browsingFor by browsingForState
    var systemPicker = false
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
}

@Composable
internal fun rememberSettingsBackup(
    context: Context,
    scope: CoroutineScope,
    activeAccount: TailscaleAccount,
    profilePrefs: SharedPreferences,
): SettingsBackupFlow {
    val showBackupPasswordDialog = rememberSaveable { mutableStateOf(false) }
    val pendingBackupUri = rememberSaveable { mutableStateOf<Uri?>(null) }
    val browsingFor = rememberSaveable { mutableStateOf<BackupFile?>(null) }
    val flow = remember {
        SettingsBackupFlow(context, scope, activeAccount, profilePrefs, showBackupPasswordDialog, pendingBackupUri, browsingFor)
    }
    // Set on every composition, as they were when they were the screen's own values: the
    // launchers and whether the system has a picker belong to the composition; the flow
    // reads the latest of them when a file is to be picked.
    flow.backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { flow.exportProfile(it) }
    flow.restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { flow.importProfile(it) }
    flow.fullBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { flow.writeFullBackup(it) }
    flow.fullRestoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { flow.askRestorePassword(it) }
    flow.systemPicker = Feature.SYSTEM_FILE_PICKER.offered()
    return flow
}

// I. Backup & restore
@Composable
internal fun SettingsBackupPage(flow: SettingsBackupFlow) {
    with(flow) {
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
}

/**
 * The app's own file browser, in place of the settings while a backup's file is chosen where
 * the system has no picker. [close] gives the settings back.
 */
@Composable
internal fun SettingsBackupBrowser(flow: SettingsBackupFlow, browsing: BackupFile, close: () -> Unit) {
    with(flow) {
        val source = remember { RealFileSource(context) }
        val downloads = remember { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
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
    }
}

/** The two password dialogs and the question after a full restore, over every page. */
@Composable
internal fun SettingsBackupDialogs(flow: SettingsBackupFlow) {
    with(flow) {
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
}
