package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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

/**
 * TailDrive and TailDrop. [openTaildrop] opens it on the Taildrop page; [openEntryAt] on that
 * page with the history entry of that time open — in a sheet on a phone, beside the inbox on
 * a large window — if the history known when the screen is composed (a demo's) holds one.
 * [inboxOpen] starts the Taildrop inbox unfolded, for a preview; the app opens it folded.
 * [inboxSeenAt] and [folderStateAtStart] are a preview's too: when the inbox was last looked
 * at — on a television what came after it is listed as new — and whether the default
 * folder can be written; the app remembers the one and finds out the other.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilesScreen(
    onBack: () -> Unit,
    openTaildrop: Boolean = false,
    openEntryAt: Long? = null,
    inboxOpen: Boolean = false,
    inboxSeenAt: Long? = null,
    folderStateAtStart: TaildropFolder.State? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // In a preview the inbox, the tailnet and the history come from LocalDemo, parsed here
    // and now: nothing started from LaunchedEffect would land before the picture is taken.
    val demo = LocalDemo.current
    val inPreview = LocalInspectionMode.current
    val mainPagerState = rememberPagerState(initialPage = if (openTaildrop || openEntryAt != null) 1 else 0, pageCount = { 2 })
    // The daemon's verdict per peer, in the app's language; resolved here because the
    // sheets' own windows follow the system locale (wrapContextWithLocale()).
    val taildropStrings = remember { TaildropReasonStrings.from(context) }

    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val taildropDir = remember(activeAccount.id) { TaildropPaths.ensureDir(context, activeAccount.id) }

    var history by remember { mutableStateOf(demo?.taildropHistoryJson?.let(TaildropHistory::decode).orEmpty()) }
    var files by remember {
        mutableStateOf(TaildropInbox.withReceivedTimes(demo?.taildropFilesJson?.let(TaildropInbox::decodeFiles).orEmpty(), history))
    }
    // The default folder (TaildropFolder). With one, received files go straight there
    // (TaildropSave) and the inbox lists them as saved; without, the inbox offers to choose
    // one until told not to. A demo names it as it is stored: a tree URI, or a path.
    var taildropFolder by remember {
        mutableStateOf(
            when {
                demo != null -> demo.taildropFolder?.let { if (it.startsWith("/")) ReceiveFolder.Dir(File(it)) else ReceiveFolder.Tree(it) }
                inPreview -> null
                else -> TaildropFolder.get(context)
            }
        )
    }
    // Whether files can go there now; read with the inbox, off this thread.
    var folderState by remember { mutableStateOf(folderStateAtStart ?: taildropFolder?.let { TaildropFolder.State.READY }) }
    var folderHintDismissed by remember { mutableStateOf(demo == null && GlobalSettings.isTaildropFolderHintDismissed(context)) }
    // A television has no notification to say that a file came, so its inbox sets apart what
    // arrived since it was last looked at; the moment is kept by TaildropInbox.
    val tv = isTv
    var inboxSeen by remember {
        mutableStateOf(inboxSeenAt ?: if (inPreview || demo != null) Long.MAX_VALUE else TaildropInbox.seenAt(context))
    }
    // Files arriving right now, as the service tracks them from the IPN bus (TaildropEvents).
    val liveIncoming by TaildropEvents.incoming.collectAsState()
    val incoming = demo?.taildropIncoming ?: liveIncoming
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
    var openEntry by remember { mutableStateOf(openEntryAt?.let { at -> history.firstOrNull { it.timestamp == at } }) }
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
            val inbox = TaildropInbox.list(context, taildropDir)
            withContext(Dispatchers.Main) {
                inbox.files?.let { files = it }
                history = inbox.history
                taildropFolder = inbox.folder
                folderState = inbox.folderState
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

            withContext(Dispatchers.Main) { isLoading = false; loaded = true }
        }
    }

    /** The default folder and whether it can be written, read again: either may change while another screen is in front. */
    fun refreshFolder() {
        scope.launch(Dispatchers.IO) {
            val folder = TaildropFolder.get(context)
            val state = folder?.let { TaildropFolder.state(context, it) }
            withContext(Dispatchers.Main) { taildropFolder = folder; folderState = state }
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
    // folder, which may have been chosen in Settings meanwhile — or allowed, on the page
    // the inbox sent the user to. Leaving the inbox is also when what it showed stops
    // being new.
    val lifecycleOwner = LocalLifecycleOwner.current
    val taildropShown = mainPagerState.currentPage == 1
    if (!inPreview) DisposableEffect(lifecycleOwner, taildropShown) {
        var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        fun publish() {
            val visible = taildropShown && resumed
            if (TaildropEvents.inboxVisible && !visible) TaildropInbox.markSeen(context)
            TaildropEvents.inboxVisible = visible
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    publish()
                    inboxSeen = TaildropInbox.seenAt(context)
                    refreshFolder()
                }
                Lifecycle.Event.ON_PAUSE -> { resumed = false; publish() }
                else -> {}
            }
        }
        publish()
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            resumed = false
            publish()
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
    // The system's picker, or the app's own browser where there is none (TaildropPickers).
    val filePicker = rememberSendFilePicker { uris ->
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
        filePicker.pick(to = peer?.getDisplayName())
    }

    var isSavingFile by remember { mutableStateOf(false) }
    val saveCopy = rememberSaveCopyPicker { reloadHistory() }

    /**
     * Save, for a file still in the inbox. With a default folder it goes there the way a new
     * arrival does — moved, under a free name — and its card turns into a saved one; if that
     * fails, or without a folder, the system's Save dialog asks where — the app's browser
     * on a device without that dialog — and the file stays.
     */
    fun handleSaveRequest(file: TaildropFile) {
        if (TaildropFolder.get(context) == null) { saveCopy.save(file); return }
        isSavingFile = true
        scope.launch(Dispatchers.IO) {
            val result = TaildropInbox.save(context, file)
            withContext(Dispatchers.Main) {
                isSavingFile = false
                when (result) {
                    is TaildropSave.Result.Saved ->
                        Toast.makeText(context, context.getString(R.string.taildrop_saved_to_format, result.folder), Toast.LENGTH_SHORT).show()
                    is TaildropSave.Result.Failed -> {
                        Toast.makeText(context, context.getString(R.string.files_auto_save_failed, result.reason), Toast.LENGTH_LONG).show()
                        saveCopy.save(file)
                    }
                    TaildropSave.Result.NoFolder -> saveCopy.save(file)
                }
                refreshData()
            }
        }
    }

    // The inbox's hint and its folder line go straight to the chooser Settings uses, and the
    // choice is the same.
    val folderChooser = rememberReceiveFolderChooser { folder ->
        taildropFolder = folder
        scope.launch(Dispatchers.IO) {
            val label = TaildropFolder.label(context, folder)
            val state = TaildropFolder.state(context, folder)
            withContext(Dispatchers.Main) {
                folderState = state
                Toast.makeText(context, context.getString(R.string.taildrop_folder_chosen_format, label), Toast.LENGTH_SHORT).show()
            }
        }
    }
    // What a folder chosen by path may be missing; where the device has no page to give it on,
    // the line says what to run from a computer instead.
    var grantUnavailable by remember { mutableStateOf(false) }
    val requestStorageAccess = rememberStorageAccessRequest { grantUnavailable = true }

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
            if (TaildropInbox.delete(context, file)) refreshData()
        }
    }
    // With a remote, Delete is asked about first: it is the first button of a card, where the
    // focus lands by itself after another screen, and one OK too many is a file gone.
    var confirmDelete by remember { mutableStateOf<TaildropFile?>(null) }

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

    // What leaves the app, offered where the device has somewhere for it to go: a file
    // manager to show a folder in — the system's picker is one —, a clipboard, and an app
    // that takes the history's export. A phone or a tablet has all three.
    val canShowFolders = Feature.SYSTEM_FILE_PICKER.offered()
    val canCopy = Feature.CLIPBOARD_ACTIONS.offered()
    val canExport = if (!tv) true else if (inPreview) false else remember { TaildropHistory.hasExportTarget(context) }

    fun deleteEntry(entry: TaildropHistoryEntry) {
        scope.launch(Dispatchers.IO) {
            TaildropHistory.remove(context, entry)
            reloadHistory()
        }
    }

    // From an expanded window up the Taildrop page is two panes, and an entry of the history
    // opens in the one beside the inbox rather than in a sheet; see TaildropPage.
    val window = rememberWindowLayout()
    val twoPane = window.listDetail
    // With a remote, Back is the way out of an entry's pane as well; a tablet has the pane's
    // close button under a finger, and its Back has always closed the screen.
    if (!inPreview) BackHandler(enabled = tv && twoPane && openEntry != null) { openEntry = null }

    val folder = taildropFolder
    val folderLine = if (folder is ReceiveFolder.Dir) {
        TaildropFolderLine(
            label = TaildropFolder.pathLabel(folder.dir),
            state = folderState ?: TaildropFolder.State.READY,
            onChange = { folderChooser.choose() },
            onAllow = requestStorageAccess,
            grantNote = if (grantUnavailable) {
                stringResource(R.string.file_browser_grant_unavailable_format, FileBrowse.adbGrantCommand(context.packageName))
            } else null
        )
    } else null

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
                        // Over the pages' content, as wide as it is: a phone's column, a larger
                        // window's whole width inside its margins.
                        modifier = if (window.isPhone) {
                            Modifier.readableWidth().padding(horizontal = 24.dp, vertical = 6.dp)
                        } else {
                            Modifier.fillMaxWidth().padding(horizontal = window.margin, vertical = 6.dp)
                        },
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
            val pages: @Composable () -> Unit = {
                PullToRefreshBox(
                    isRefreshing = isLoading,
                    onRefresh = { refreshData() },
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    // A remote does not pull — the top bar's button refreshes — and the scroll
                    // that follows its focus down a list is taken for a pull nobody lets go
                    // of: the arrow came out and stayed over the page.
                    enabled = !tv
                ) {
                    HorizontalPager(state = mainPagerState, modifier = Modifier.fillMaxSize()) { mainPage ->
                        if (mainPage == 0) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                TaildriveTabContent()
                            }
                        } else {
                            TaildropPage(
                                incoming = incoming,
                                files = files,
                                history = history,
                                targets = targets,
                                loaded = loaded,
                                onOpenFile = { openWaitingTaildropFile(context, it, tv) },
                                onSaveFile = { handleSaveRequest(it) },
                                onDeleteFile = { if (tv) confirmDelete = it else deleteFile(it) },
                                onOpenSaved = { openSavedTaildropFile(context, it, tv) },
                                onShowSavedInFolder = if (canShowFolders) ({ showSavedTaildropFolder(context, it) }) else null,
                                onCopySavedFolderPath = if (canCopy) ({ copySavedTaildropFolderPath(context, it) }) else null,
                                onHideSaved = { hideSaved(it) },
                                showFolderHint = taildropFolder == null && !folderHintDismissed,
                                onChooseFolder = { folderChooser.choose() },
                                onDismissFolderHint = {
                                    folderHintDismissed = true
                                    GlobalSettings.setTaildropFolderHintDismissed(context)
                                },
                                onSendTo = { pickFilesFor(it) },
                                onAllDevices = { showAllDevices = true },
                                onAllHistory = { showHistory = true },
                                onEntry = { openEntry = it },
                                window = window,
                                openEntry = openEntry,
                                onCloseEntry = { openEntry = null },
                                onDeleteEntry = { deleteEntry(it) },
                                inboxOpenAtStart = inboxOpen,
                                folderLine = folderLine,
                                newSince = inboxSeen.takeIf { tv }
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
            }
            // A phone's pages are held to a readable width on its side; see ReadableWidth. A
            // larger window's pages lay themselves out across it (TaildropPage, TaildriveTabContent).
            if (window.isPhone) ReadableWidth { pages() } else pages()

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
            TaildropSheet(onDismiss = { showHistory = false }) { hide ->
                TaildropHistorySheetContent(
                    history = history,
                    // Two-pane, the entry opens in the pane, which the sheet would cover.
                    onEntry = { openEntry = it; if (twoPane) hide() },
                    onExport = if (canExport) ({ exportHistory() }) else null,
                    onClear = { confirmClear = true }
                )
            }
        }

        // Over the History sheet when opened from it. Two-pane, TaildropPage draws it in a pane.
        if (!twoPane) openEntry?.let { entry ->
            TaildropEntrySheet(entry, onDismiss = { openEntry = null }, onDelete = { deleteEntry(entry) })
        }

        confirmDelete?.let { file ->
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strTitle = stringResource(R.string.taildrop_delete_confirm_title_format, file.Name)
            val strText = stringResource(R.string.taildrop_delete_confirm_text)
            val strDelete = stringResource(R.string.action_delete)
            val strCancel = stringResource(R.string.action_cancel)
            AppAlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text(strTitle) },
                text = { Text(strText) },
                confirmButton = {
                    TextButton(
                        onClick = { confirmDelete = null; deleteFile(file) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(strDelete) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(strCancel) } }
            )
        }

        if (confirmClear) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strTitle = stringResource(R.string.taildrop_history_clear_title)
            val strText = pluralStringResource(R.plurals.taildrop_history_clear_text, history.size, history.size)
            val strClear = stringResource(R.string.taildrop_history_clear)
            val strCancel = stringResource(R.string.action_cancel)
            AppAlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(strTitle) },
                text = { Text(strText) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmClear = false
                            showHistory = false
                            openEntry = null
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
