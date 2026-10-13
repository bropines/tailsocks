package io.github.bropines.tailscaled.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.settings.Provide
import io.github.bropines.tailscaled.admin.settings.rememberParentLocals
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.PickerKind
import io.github.bropines.tailscaled.core.RealFileSource
import io.github.bropines.tailscaled.core.ReceiveFolder
import io.github.bropines.tailscaled.core.TaildropFolder
import io.github.bropines.tailscaled.core.TaildropSave
import io.github.bropines.tailscaled.core.choosePicker
import io.github.bropines.tailscaled.core.openAllFilesAccessSettings
import io.github.bropines.tailscaled.core.tryLaunch
import io.github.bropines.tailscaled.core.tryStartActivity
import io.github.bropines.tailscaled.models.TaildropFile
import io.github.bropines.tailscaled.ui.theme.findActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The three things Taildrop asks storage for — files to send, the folder received files go
 * to, a place for one file of the inbox — each with whatever picker the device has
 * (core/TaildropPick.kt): the system's, as on every phone, or the app's own browser
 * (ui/FileBrowser.kt) where there is none, which is a television. A screen remembers one
 * of these and calls it from a click; the browser, when it is the one, opens in a window
 * of its own over that screen, so the screen needs no place for it.
 */

/**
 * The app's browser over the whole screen, in a window of its own: the parent's language
 * provided again inside (see wrapContextWithLocale()), and a television's safe area kept
 * clear, which the theme does for a screen and cannot for a window.
 */
@Composable
private fun PickerWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val parent = rememberParentLocals()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        parent.Provide {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(if (isTv) Modifier.padding(TvSafeArea) else Modifier) { content() }
            }
        }
    }
}

/** Asks for files to send; see [rememberSendFilePicker]. */
@Stable
class SendFilePicker internal constructor(private val onPick: (to: String?) -> Unit) {
    /**
     * Opens the picker. [to] is the device the files are for, when it is known already: the
     * app's browser then says so in its title and on its button.
     */
    fun pick(to: String? = null) = onPick(to)
}

/**
 * A picker of files to send: the system's (GET_CONTENT, any type), or the app's browser on
 * a device without one. [onPicked] gets `content:` URIs from the first and `file:` ones
 * from the second — sendTaildropFiles() reads both — and an empty list when nothing was
 * chosen. With [multiple] off it is one file at most.
 */
@Composable
fun rememberSendFilePicker(multiple: Boolean = true, onPicked: (List<Uri>) -> Unit): SendFilePicker {
    val context = LocalContext.current
    val offered = Feature.SYSTEM_FILE_PICKER.offered()
    val result by rememberUpdatedState(onPicked)
    val system: ActivityResultLauncher<String> =
        if (multiple) rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { result(it) }
        else rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { result(listOfNotNull(it)) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    if (browsing) {
        val source = remember { RealFileSource(context) }
        val to = target
        val done: (List<Uri>) -> Unit = { browsing = false; result(it) }
        val sendLabel: @Composable (Int) -> String = { n -> pluralStringResource(R.plurals.taildrop_pick_send_files, n, n) }
        PickerWindow(onDismiss = { done(emptyList()) }) {
            PickFilesScreen(
                source = source,
                onPicked = { files -> done(files.map(Uri::fromFile)) },
                onCancel = { done(emptyList()) },
                multiple = multiple,
                title = if (to != null) stringResource(R.string.taildrop_pick_send_to_format, to) else stringResource(R.string.taildrop_pick_files_title),
                // Without a device yet the files are only chosen: the browser's own "Choose N files".
                confirmLabel = sendLabel.takeIf { to != null },
            )
        }
    }
    return remember(offered, system) {
        SendFilePicker { to ->
            if (choosePicker(offered) { system.tryLaunch("*/*") } == PickerKind.IN_APP) {
                target = to
                browsing = true
            }
        }
    }
}

/** Asks for the folder received files go to; see [rememberReceiveFolderChooser]. */
@Stable
class ReceiveFolderChooser internal constructor(private val onChoose: () -> Unit) {
    fun choose() = onChoose()
}

/**
 * The chooser of the Taildrop receive folder, for any screen that has a row or a button for
 * it: the system's folder picker gives a SAF folder, the app's browser — where there is no
 * such picker — a directory. Either way the choice is stored ([TaildropFolder.set]) before
 * [onChosen] is told what it is.
 */
@Composable
fun rememberReceiveFolderChooser(onChosen: (ReceiveFolder) -> Unit): ReceiveFolderChooser {
    val context = LocalContext.current
    val offered = Feature.SYSTEM_FILE_PICKER.offered()
    val result by rememberUpdatedState(onChosen)
    fun chosen(folder: ReceiveFolder) {
        TaildropFolder.set(context, folder)
        result(folder)
    }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) chosen(ReceiveFolder.Tree(uri.toString()))
    }
    var browsing by rememberSaveable { mutableStateOf(false) }
    if (browsing) {
        val source = remember { RealFileSource(context) }
        // It opens where files go now, or where a television would start putting them.
        val start = remember { (TaildropFolder.get(context) as? ReceiveFolder.Dir)?.dir ?: TaildropFolder.defaultDir() }
        PickerWindow(onDismiss = { browsing = false }) {
            PickFolderScreen(
                source = source,
                onPicked = { dir -> browsing = false; chosen(ReceiveFolder.Dir(dir)) },
                onCancel = { browsing = false },
                start = start,
                writable = true,
                title = stringResource(R.string.taildrop_pick_folder_title),
                confirmLabel = stringResource(R.string.taildrop_pick_folder_confirm),
            )
        }
    }
    return remember(offered, system) {
        ReceiveFolderChooser { if (choosePicker(offered) { system.tryLaunch(null) } == PickerKind.IN_APP) browsing = true }
    }
}

/** Saves a copy of one file of the inbox where the user says; see [rememberSaveCopyPicker]. */
@Stable
class SaveCopyPicker internal constructor(private val onSave: (TaildropFile) -> Unit) {
    fun save(file: TaildropFile) = onSave(file)
}

/**
 * Save for a file of the inbox that the receive folder did not take: the system's Save
 * dialog, or a folder picked in the app's browser on a device without that dialog. The
 * copy is made off the main thread and noted in the history; [onSaved] follows a copy that
 * was made, a toast says why one was not.
 */
@Composable
fun rememberSaveCopyPicker(onSaved: () -> Unit): SaveCopyPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val offered = Feature.SYSTEM_FILE_PICKER.offered()
    val saved by rememberUpdatedState(onSaved)
    // Not kept across a recreation: the answer to a request nobody remembers is dropped.
    var file by remember { mutableStateOf<TaildropFile?>(null) }
    var browsing by remember { mutableStateOf(false) }

    fun copy(inApp: Boolean, write: (TaildropFile) -> TaildropSave.Result) {
        val f = file ?: return
        file = null
        browsing = false
        scope.launch(Dispatchers.IO) {
            val result = write(f)
            withContext(Dispatchers.Main) {
                when (result) {
                    is TaildropSave.Result.Saved -> {
                        // The system's dialog showed where the copy went; the app's browser is gone by now.
                        if (inApp) Toast.makeText(context, context.getString(R.string.taildrop_saved_to_format, result.folder), Toast.LENGTH_SHORT).show()
                        saved()
                    }
                    is TaildropSave.Result.Failed ->
                        Toast.makeText(context, context.getString(R.string.files_save_error, result.reason), Toast.LENGTH_SHORT).show()
                    TaildropSave.Result.NoFolder -> Unit
                }
            }
        }
    }

    val system = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri == null) file = null else copy(inApp = false) { TaildropSave.copyToDocument(context, it, uri) }
    }
    val pending = file
    if (browsing && pending != null) {
        val source = remember { RealFileSource(context) }
        // It opens in Downloads, not in the receive folder: this Save is for a file that
        // folder did not take, and Downloads is where the app may write with no grant.
        val start = remember { TaildropFolder.defaultDir()?.parentFile }
        val cancel = { browsing = false; file = null }
        PickerWindow(onDismiss = cancel) {
            PickFolderScreen(
                source = source,
                onPicked = { dir: File -> copy(inApp = true) { TaildropSave.copyToDir(context, it, dir) } },
                onCancel = cancel,
                start = start,
                writable = true,
                title = stringResource(R.string.taildrop_save_copy_title_format, pending.Name),
                confirmLabel = stringResource(R.string.taildrop_save_copy_confirm),
            )
        }
    }
    return remember(offered, system) {
        SaveCopyPicker { f ->
            file = f
            if (choosePicker(offered) { system.tryLaunch(f.Name) } == PickerKind.IN_APP) browsing = true
        }
    }
}

/**
 * Asks for the storage access a receive folder chosen by path may need: the Storage
 * permission's dialog below Android 11, the page for access to all files from it.
 * [onUnavailable] when the device has no page to give it on — what is left then is adb.
 * The answer is not reported: the screen reads the folder's state again when it is back
 * in front.
 */
@Composable
fun rememberStorageAccessRequest(onUnavailable: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val unavailable by rememberUpdatedState(onUnavailable)
    val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val activity = context.findActivity()
        // Refused for good, the system showed nothing: only the app's own page gives it back.
        if (results.values.any { !it } && (activity == null || permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) })) {
            val page = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            if (!context.tryStartActivity(page)) unavailable()
        }
    }
    return {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) dialog.launch(permissions)
        else if (!context.openAllFilesAccessSettings()) unavailable()
    }
}
