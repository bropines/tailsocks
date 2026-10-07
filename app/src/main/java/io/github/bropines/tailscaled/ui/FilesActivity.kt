package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween

class FilesActivity : ComponentActivity() {
    companion object {
        /** Boolean extra: open on the TailDrop inbox rather than on Taildrive. Set by the
         *  "file received" notification (TaildropEvents). */
        const val EXTRA_OPEN_TAILDROP = "open_taildrop"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openTaildrop = intent?.getBooleanExtra(EXTRA_OPEN_TAILDROP, false) == true
        setContent { TailSocksTheme { FilesScreen(onBack = { finish() }, openTaildrop = openTaildrop) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilesScreen(onBack: () -> Unit, openTaildrop: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // In a preview the inbox, the tailnet and the history come from LocalDemo, parsed here
    // and now: nothing started from LaunchedEffect would land before the picture is taken.
    val demo = LocalDemo.current
    val inPreview = LocalInspectionMode.current
    val mainPagerState = rememberPagerState(initialPage = if (openTaildrop) 1 else 0, pageCount = { 2 })
    // The daemon's verdict per peer, in the app's language; resolved here because the
    // sheets' own windows follow the system locale (wrapContextWithLocale()).
    val taildropStrings = remember { TaildropReasonStrings.from(context) }

    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val taildropDir = remember(activeAccount.id) { TaildropPaths.ensureDir(context, activeAccount.id) }

    var history by remember { mutableStateOf(demo?.taildropHistoryJson?.let(TaildropHistory::decode).orEmpty()) }
    var files by remember { mutableStateOf(withReceivedTimes(demo?.taildropFilesJson?.let(::decodeTaildropFiles).orEmpty(), history)) }
    // The default folder. With one, received files go straight there (TaildropSave) and the
    // inbox lists them as saved; without, the inbox offers to choose one until told not to.
    var taildropFolder by remember {
        mutableStateOf(if (demo != null) demo.taildropFolder?.let(Uri::parse) else GlobalSettings.getTaildropRootUri(context))
    }
    var folderHintDismissed by remember { mutableStateOf(demo == null && GlobalSettings.isTaildropFolderHintDismissed(context)) }
    // Every peer a send picker may list (taildropPickerPeers); the page splits them.
    var pickerPeers by remember {
        mutableStateOf(
            demo?.statusJson
                ?.let { runCatching { AppJson.decodeFromString<StatusResponse>(it) }.getOrNull() }
                ?.let { taildropPickerPeers(it, taildropStrings) }
                .orEmpty()
        )
    }
    // Whether a load has come back, so an empty section before the first answer does not
    // say "no files" about a list nobody has read yet.
    var loaded by remember { mutableStateOf(demo != null) }
    var isLoading by remember { mutableStateOf(false) }
    val targets = remember(pickerPeers, history) { taildropTargets(pickerPeers, taildropStrings, history) }

    var isSendingFile by remember { mutableStateOf(false) }
    var sendProgressText by remember { mutableStateOf("") }
    var showAllDevices by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var openEntry by remember { mutableStateOf<TaildropHistoryEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun reloadHistory() {
        scope.launch(Dispatchers.IO) {
            val h = TaildropHistory.read(context)
            withContext(Dispatchers.Main) { history = h }
        }
    }

    fun refreshData() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            // 1. Incoming files, with the history they are matched against: it says where a
            // file came from, when, and which received files the default folder took.
            val folder = GlobalSettings.getTaildropRootUri(context)
            val newHistory = TaildropHistory.read(context)
            try {
                val json = if (BuildConfig.IS_DEV) {
                    Appctr.getTaildropFilesFromAPI()
                } else {
                    Appctr.getWaitingFiles(taildropDir.absolutePath)
                }
                val newFiles = withReceivedTimes(decodeTaildropFiles(json), newHistory)
                withContext(Dispatchers.Main) { files = newFiles; history = newHistory; taildropFolder = folder }
            } catch (e: Exception) {
                android.util.Log.e("FilesActivity", "Failed to load waiting files", e)
            }

            // 2. Peers
            try {
                val pJson = Appctr.getStatusFromAPI()
                if (!pJson.startsWith("Error") && pJson.isNotBlank()) {
                    val status = AppJson.decodeFromString<StatusResponse>(pJson)
                    val newPeers = taildropPickerPeers(status, taildropStrings)
                    withContext(Dispatchers.Main) { pickerPeers = newPeers }
                } else {
                    android.util.Log.w("FilesActivity", "Status source error: $pJson")
                }
            } catch (e: Exception) {
                android.util.Log.e("FilesActivity", "Failed to parse status JSON", e)
            }

            withContext(Dispatchers.Main) { history = newHistory; taildropFolder = folder; isLoading = false; loaded = true }
        }
    }

    LaunchedEffect(activeAccount.id, mainPagerState.currentPage) { if (demo == null && !inPreview) refreshData() }

    // A file the daemon has just finished writing: the service hears it on the IPN bus
    // (TaildropEvents) and says so; the list is re-read from disk. No polling of our own.
    // The preview renderer has no service to hear from.
    if (!inPreview) DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == TaildropEvents.ACTION_RECEIVED) refreshData()
            }
        }
        val filter = android.content.IntentFilter(TaildropEvents.ACTION_RECEIVED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { try { context.unregisterReceiver(receiver) } catch (e: Exception) {} }
    }

    // While the Taildrop page — the inbox is its first section — is on screen and the
    // activity is resumed, TaildropEvents skips the "File received" notification: the
    // broadcast above already shows the file where the user is looking. Anything else
    // (paused, TailDrive, screen closed) re-arms it. A resume also re-reads the default
    // folder, which may have been chosen in Settings meanwhile.
    val lifecycleOwner = LocalLifecycleOwner.current
    val taildropShown = mainPagerState.currentPage == 1
    if (!inPreview) DisposableEffect(lifecycleOwner, taildropShown) {
        var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        fun publish() { TaildropEvents.inboxVisible = taildropShown && resumed }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    publish()
                    taildropFolder = GlobalSettings.getTaildropRootUri(context)
                }
                Lifecycle.Event.ON_PAUSE -> { resumed = false; publish() }
                else -> {}
            }
        }
        publish()
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            TaildropEvents.inboxVisible = false
        }
    }

    fun sendTo(peer: PeerData, uris: List<Uri>) {
        isSendingFile = true
        scope.launch(Dispatchers.IO) {
            val failures = sendTaildropFiles(context, uris, peer, TaildropSource.FILES) { sendProgressText = it }
                .mapNotNull { o -> o.error?.let { context.getString(R.string.share_failed_format, o.name, it) } }
            withContext(Dispatchers.Main) {
                isSendingFile = false
                if (failures.isEmpty()) Toast.makeText(context, context.getString(R.string.files_sent), Toast.LENGTH_SHORT).show()
                else Toast.makeText(context, failures.joinToString("\n"), Toast.LENGTH_LONG).show()
                refreshData()
            }
        }
    }

    // The files for a send. Picked for a device (a row of the Send section) they go straight
    // to it; picked from the send button, the device sheet opens with them. The device is
    // also kept by ID, for an activity recreated while the picker was open; if the list is
    // not back by the time the files are, the sheet asks again rather than guessing.
    var pickFor by remember { mutableStateOf<PeerData?>(null) }
    var pickForId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val target = pickFor ?: pickForId?.let { id -> pickerPeers.firstOrNull { it.id == id } }
        pickFor = null
        pickForId = null
        when {
            uris.isEmpty() -> Unit
            target != null -> sendTo(target, uris)
            else -> pendingUris = uris
        }
    }
    fun pickFilesFor(peer: PeerData?) {
        pickFor = peer
        pickForId = peer?.id
        filePicker.launch("*/*")
    }

    var isSavingFile by remember { mutableStateOf(false) }
    var fileToSaveManual by remember { mutableStateOf<TaildropFile?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = fileToSaveManual
        if (uri != null && file != null) {
            scope.launch(Dispatchers.IO) {
                if (saveFileToUri(context, file, uri)) {
                    TaildropHistory.markSaved(context, file, savedLocationOf(context, uri) ?: file.Name)
                    reloadHistory()
                }
                withContext(Dispatchers.Main) { fileToSaveManual = null }
            }
        }
    }

    /**
     * Save, for a file still in the inbox. With a default folder it goes there the way a new
     * arrival does — moved, under a free name — and its card turns into a saved one; if that
     * fails, or without a folder, the system's Save dialog asks where, and the file stays.
     */
    fun handleSaveRequest(file: TaildropFile) {
        if (GlobalSettings.getTaildropRootUri(context) == null) { fileToSaveManual = file; saveLauncher.launch(file.Name); return }
        isSavingFile = true
        scope.launch(Dispatchers.IO) {
            val result = TaildropSave.moveToFolder(context, file)
            withContext(Dispatchers.Main) {
                isSavingFile = false
                when (result) {
                    is TaildropSave.Result.Saved ->
                        Toast.makeText(context, context.getString(R.string.taildrop_saved_to_format, result.folder), Toast.LENGTH_SHORT).show()
                    is TaildropSave.Result.Failed -> {
                        Toast.makeText(context, context.getString(R.string.files_auto_save_failed, result.reason), Toast.LENGTH_LONG).show()
                        fileToSaveManual = file
                        saveLauncher.launch(file.Name)
                    }
                    TaildropSave.Result.NoFolder -> { fileToSaveManual = file; saveLauncher.launch(file.Name) }
                }
                refreshData()
            }
        }
    }

    // The inbox's hint goes straight to the picker Settings uses, and the choice is the same.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            GlobalSettings.setTaildropRootUri(context, uri)
            taildropFolder = uri
            scope.launch(Dispatchers.IO) {
                val label = TaildropSave.folderLabel(context, uri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.taildrop_folder_chosen_format, label), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun hideSaved(entry: TaildropHistoryEntry) {
        // Off the list now; the write follows.
        history = history.map { if (it == entry) it.copy(dismissedAt = System.currentTimeMillis()) else it }
        scope.launch(Dispatchers.IO) {
            TaildropHistory.dismiss(context, entry)
            reloadHistory()
        }
    }

    fun deleteFile(file: TaildropFile) {
        scope.launch(Dispatchers.IO) {
            if (Appctr.deleteTaildropFileFromAPI(file.Name)) {
                TaildropHistory.markDeleted(context, file)
                refreshData()
            }
        }
    }

    fun exportHistory() {
        val chooserTitle = context.getString(R.string.taildrop_history_export_chooser)
        scope.launch(Dispatchers.IO) {
            val csv = runCatching { TaildropHistory.exportCsv(context) }
            withContext(Dispatchers.Main) {
                csv.mapCatching { context.startActivity(TaildropHistory.exportIntent(context, it, chooserTitle)) }
                    .onFailure {
                        Toast.makeText(context, context.getString(R.string.taildrop_history_export_failed_format, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    PredictiveBackContainer(
        onBack = onBack,
        // Back here only closes the Activity, so the container installs no callback and
        // the platform animates across to the real screen underneath.
        popsInAppState = false
    ) {
        Scaffold(
            topBar = {
                Column {
                    AppTopBar(
                        title = stringResource(R.string.files_hub_title),
                        subtitle = activeAccount.name,
                        onBack = onBack,
                        actions = {
                            IconButton(onClick = { refreshData() }) { Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh)) }
                        }
                    )

                    // Continuous Drag-Bound Sliding Pill Selector (TailDrive vs TailDrop)
                    val pagePosition = (mainPagerState.currentPage + mainPagerState.currentPageOffsetFraction).coerceIn(0f, 1f)

                    SlidingSegmentedChips(
                        items = listOf(
                            SegmentedChipItem("TailDrive", Icons.Default.Storage),
                            SegmentedChipItem("TailDrop", Icons.AutoMirrored.Filled.Send)
                        ),
                        selectedIndex = mainPagerState.currentPage,
                        onOptionSelected = { index ->
                            scope.launch { mainPagerState.animateScrollToPage(index) }
                        },
                        positionOffset = pagePosition,
                        modifier = Modifier
                            .readableWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        height = 44.dp
                    )
                }
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = mainPagerState.currentPage == 1,
                    enter = fadeIn(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(150))
                ) {
                    FloatingActionButton(onClick = { pickFilesFor(null) }) {
                        Icon(Icons.Default.FileUpload, stringResource(R.string.action_send))
                    }
                }
            }
        ) { padding ->
            // Held to a readable width on a tablet; see ReadableWidth.
            ReadableWidth {
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { refreshData() },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            HorizontalPager(state = mainPagerState, modifier = Modifier.fillMaxSize()) { mainPage ->
                if (mainPage == 0) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TaildriveTabContent()
                    }
                } else {
                    TaildropPage(
                        files = files,
                        history = history,
                        targets = targets,
                        loaded = loaded,
                        onOpenFile = { openTaildropFile(context, it) },
                        onSaveFile = { handleSaveRequest(it) },
                        onDeleteFile = { deleteFile(it) },
                        onOpenSaved = { openSavedTaildropFile(context, it) },
                        onShowSavedInFolder = { showSavedTaildropFolder(context, it) },
                        onHideSaved = { hideSaved(it) },
                        showFolderHint = taildropFolder == null && !folderHintDismissed,
                        onChooseFolder = { folderPicker.launch(null) },
                        onDismissFolderHint = {
                            folderHintDismissed = true
                            GlobalSettings.setTaildropFolderHintDismissed(context)
                        },
                        onSendTo = { pickFilesFor(it) },
                        onAllDevices = { showAllDevices = true },
                        onAllHistory = { showHistory = true },
                        onEntry = { openEntry = it }
                    )
                }
            }
            if (isSavingFile) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (isSendingFile) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.3f)), contentAlignment = Alignment.Center) {
                Card(shape = MaterialTheme.shapes.large) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        LoadingIndicator(); Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.files_sending)); Text(sendProgressText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // The Send section's "All devices": a pick there opens the file picker for it.
        if (showAllDevices) {
            val title = stringResource(R.string.taildrop_section_send)
            TaildropSheet(onDismiss = { showAllDevices = false }) { hide ->
                TaildropDevicesSheetContent(title, targets) { peer -> hide(); pickFilesFor(peer) }
            }
        }

        // Files picked from the send button, waiting for a device.
        if (pendingUris.isNotEmpty()) {
            val title = pluralStringResource(R.plurals.taildrop_send_files_to, pendingUris.size, pendingUris.size)
            TaildropSheet(onDismiss = { pendingUris = emptyList() }) { hide ->
                TaildropDevicesSheetContent(title, targets) { peer ->
                    val uris = pendingUris
                    hide()
                    sendTo(peer, uris)
                }
            }
        }

        if (showHistory) {
            TaildropSheet(onDismiss = { showHistory = false }) {
                TaildropHistorySheetContent(
                    history = history,
                    onEntry = { openEntry = it },
                    onExport = { exportHistory() },
                    onClear = { confirmClear = true }
                )
            }
        }

        // Over the History sheet when opened from it.
        openEntry?.let { entry ->
            TaildropSheet(onDismiss = { openEntry = null }) { hide ->
                TaildropEntryDetails(entry) {
                    hide()
                    scope.launch(Dispatchers.IO) {
                        TaildropHistory.remove(context, entry)
                        reloadHistory()
                    }
                }
            }
        }

        if (confirmClear) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strTitle = stringResource(R.string.taildrop_history_clear_title)
            val strText = pluralStringResource(R.plurals.taildrop_history_clear_text, history.size, history.size)
            val strClear = stringResource(R.string.taildrop_history_clear)
            val strCancel = stringResource(R.string.action_cancel)
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(strTitle) },
                text = { Text(strText) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmClear = false
                            showHistory = false
                            scope.launch(Dispatchers.IO) {
                                TaildropHistory.clear(context)
                                reloadHistory()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(strClear) }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(strCancel) } }
            )
        }
            }
        }
}
}

/** A received file the default folder took, in a viewer — the app's grant on the folder passed on. */
private fun openSavedTaildropFile(context: Context, entry: TaildropHistoryEntry) {
    val uri = entry.savedUri?.let(Uri::parse) ?: return
    try {
        val view = TaildropSave.viewIntent(uri, entry.mime ?: mimeTypeOf(entry.savedTo ?: entry.name))
        // The chooser carries the grant over to the app picked (Intent.createChooser).
        context.startActivity(Intent.createChooser(view, context.getString(R.string.files_open_file_chooser)))
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.files_error_cant_open, e.message), Toast.LENGTH_SHORT).show()
    }
}

/**
 * The folder a saved file is in, in the system's file manager. Where nothing takes a folder,
 * the toast at least says where it is.
 */
private fun showSavedTaildropFolder(context: Context, entry: TaildropHistoryEntry) {
    val intent = entry.savedUri?.let { TaildropSave.folderIntent(Uri.parse(it)) }
    try {
        if (intent == null) throw ActivityNotFoundException()
        context.startActivity(intent)
    } catch (e: Exception) {
        val folder = entry.savedTo?.substringBeforeLast('/', "")?.ifEmpty { null } ?: entry.savedTo.orEmpty()
        Toast.makeText(context, context.getString(R.string.taildrop_folder_unavailable_format, folder), Toast.LENGTH_LONG).show()
    }
}

/** The bridge's waiting-files JSON; an empty list for nothing or anything unreadable. */
private fun decodeTaildropFiles(json: String?): List<TaildropFile> =
    if (json.isNullOrBlank()) emptyList()
    else runCatching { AppJson.decodeFromString<List<TaildropFile>>(json) }.getOrDefault(emptyList())

/** Copies a received file to [destUri]; false, with a toast, when that failed. */
private suspend fun saveFileToUri(context: Context, file: TaildropFile, destUri: Uri): Boolean =
    try {
        File(file.Path).inputStream().use { input ->
            val output = context.contentResolver.openOutputStream(destUri) ?: throw java.io.IOException(destUri.toString())
            output.use { input.copyTo(it); it.flush() }
        }
        true
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.files_save_error, e.message), Toast.LENGTH_SHORT).show() }
        false
    }

/**
 * Where a saved file went, for the history: the path under the storage root when the
 * external-storage provider holds it — it names its documents by path ("primary:Download/
 * a.jpg") — and otherwise the document's display name, all an opaque provider tells.
 */
private fun savedLocationOf(context: Context, uri: Uri): String? {
    val path = if (uri.authority == "com.android.externalstorage.documents") {
        runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()?.substringAfter(':', "")?.takeIf { it.isNotEmpty() }
    } else null
    return path ?: runCatching { getFileName(context, uri) }.getOrNull()
}
