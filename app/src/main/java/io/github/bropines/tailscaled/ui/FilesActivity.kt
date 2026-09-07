package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
    val mainPagerState = rememberPagerState(initialPage = if (openTaildrop) 1 else 0, pageCount = { 2 })
    val taildropPagerState = rememberPagerState(pageCount = { 3 })
    // The daemon's verdict per peer, in the app's language; resolved here because the picker
    // sheet's own window follows the system locale (wrapContextWithLocale()).
    val taildropStrings = remember { TaildropReasonStrings.from(context) }

    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val taildropDir = remember(activeAccount.id) { TaildropPaths.ensureDir(context, activeAccount.id) }

    var files by remember { mutableStateOf<List<TaildropFile>>(emptyList()) }
    var sentFiles by remember { mutableStateOf<List<SentFileEntry>>(emptyList()) }
    var peers by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var selfPeer by remember { mutableStateOf<PeerData?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    var selectedUriForSend by remember { mutableStateOf<Uri?>(null) }
    var showPeerPicker by remember { mutableStateOf(false) }
    var isSendingFile by remember { mutableStateOf(false) }
    var sendProgressText by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) { selectedUriForSend = uri; showPeerPicker = true }
    }

    var isSavingFile by remember { mutableStateOf(false) }
    var fileToSaveManual by remember { mutableStateOf<TaildropFile?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null && fileToSaveManual != null) {
            scope.launch(Dispatchers.IO) { saveFileToUri(context, fileToSaveManual!!, uri); withContext(Dispatchers.Main) { fileToSaveManual = null } }
        }
    }

    fun refreshData() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            // 1. Load incoming files
            try {
                val json = if (BuildConfig.IS_DEV) {
                    Appctr.getTaildropFilesFromAPI()
                } else {
                    Appctr.getWaitingFiles(taildropDir.absolutePath)
                }
                val newFiles: List<TaildropFile> = if (json.isNullOrBlank()) emptyList()
                    else runCatching { AppJson.decodeFromString<List<TaildropFile>>(json) }.getOrDefault(emptyList())
                withContext(Dispatchers.Main) { files = newFiles }
            } catch (e: Exception) {
                android.util.Log.e("FilesActivity", "Failed to load waiting files", e)
            }

            // 2. Load peers status
            try {
                val pJson = if (BuildConfig.IS_DEV) {
                    Appctr.getStatusFromAPI()
                } else {
                    Appctr.getStatusFromAPI()
                }
                
                if (!pJson.startsWith("Error") && pJson.isNotBlank()) {
                    val status = AppJson.decodeFromString<StatusResponse>(pJson)
                    // Same list and order as the Share sheet: peers the daemon refuses stay
                    // on it, disabled, with the daemon's reason (taildropPickerPeers).
                    val newPeers = taildropPickerPeers(status, taildropStrings)
                    withContext(Dispatchers.Main) {
                        peers = newPeers
                        selfPeer = status.self
                    }
                } else {
                    android.util.Log.w("FilesActivity", "Status source error: $pJson")
                }
            } catch (e: Exception) {
                android.util.Log.e("FilesActivity", "Failed to parse status JSON", e)
            }

            // 3. Load sent history
            try {
                val hf = File(context.filesDir, "sent_history.json")
                if (hf.exists()) {
                    val historyText = hf.readText()
                    val newHistory: List<SentFileEntry> = if (historyText.isBlank()) emptyList()
                        else runCatching { AppJson.decodeFromString<List<SentFileEntry>>(historyText) }.getOrDefault(emptyList())
                    withContext(Dispatchers.Main) { sentFiles = newHistory }
                }
            } catch (e: Exception) {
                android.util.Log.e("FilesActivity", "Failed to load sent history", e)
            }

            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    LaunchedEffect(activeAccount.id) { refreshData() }
    LaunchedEffect(taildropPagerState.currentPage, mainPagerState.currentPage) { refreshData() }

    // A file the daemon has just finished writing: the service hears it on the IPN bus
    // (TaildropEvents) and says so; the list is re-read from disk. No polling of our own.
    DisposableEffect(Unit) {
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

    // While the Inbox page is on screen and the activity is resumed, TaildropEvents skips
    // the "File received" notification: the broadcast above already shows the file where
    // the user is looking. Anything else (paused, another page, screen closed) re-arms it.
    val lifecycleOwner = LocalLifecycleOwner.current
    val inboxPageShown = mainPagerState.currentPage == 1 && taildropPagerState.currentPage == 0
    DisposableEffect(lifecycleOwner, inboxPageShown) {
        var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        fun publish() { TaildropEvents.inboxVisible = inboxPageShown && resumed }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { resumed = true; publish() }
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

    fun handleSaveRequest(file: TaildropFile) {
        val rootUri = GlobalSettings.getTaildropRootUri(context)
        if (rootUri != null) {
            scope.launch(Dispatchers.IO) {
                isSavingFile = true
                try {
                    val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: throw Exception("Folder access error")
                    rootDoc.findFile(file.Name)?.delete()
                    val newFile = rootDoc.createFile("*/*", file.Name) ?: throw Exception("Create failed")
                    saveFileToUri(context, file, newFile.uri)
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.files_saved), Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.files_auto_save_failed, e.message), Toast.LENGTH_SHORT).show(); fileToSaveManual = file; saveLauncher.launch(file.Name) }
                } finally { withContext(Dispatchers.Main) { isSavingFile = false } }
            }
        } else { fileToSaveManual = file; saveLauncher.launch(file.Name) }
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
                    TopAppBar(
                        title = {
                            Column {
                                Text(stringResource(R.string.files_hub_title))
                                Text(activeAccount.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
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
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        height = 44.dp
                    )

                    // Sub-tabs: Smooth drag-bound sliding pill for TailDrop sub-pages
                    AnimatedVisibility(
                        visible = mainPagerState.currentPage == 1,
                        enter = fadeIn(animationSpec = tween(250)) + expandVertically(animationSpec = tween(250)),
                        exit = fadeOut(animationSpec = tween(200)) + shrinkVertically(animationSpec = tween(200))
                    ) {
                        val fileTabs = listOf(
                            stringResource(R.string.files_tab_inbox),
                            stringResource(R.string.files_tab_devices),
                            stringResource(R.string.files_tab_history)
                        )
                        val dropSubOffset = (taildropPagerState.currentPage + taildropPagerState.currentPageOffsetFraction).coerceIn(0f, 2f)

                        SlidingSegmentedChips(
                            options = fileTabs,
                            selectedIndex = taildropPagerState.currentPage,
                            onOptionSelected = { index ->
                                scope.launch { taildropPagerState.animateScrollToPage(index) }
                            },
                            positionOffset = dropSubOffset,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp),
                            height = 36.dp
                        )
                    }
                }
            },
            floatingActionButton = { 
                AnimatedVisibility(
                    visible = mainPagerState.currentPage == 1,
                    enter = fadeIn(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(150))
                ) {
                    FloatingActionButton(onClick = { filePickerLauncher.launch("*/*") }) {
                        Icon(Icons.Default.FileUpload, stringResource(R.string.action_send))
                    } 
                }
            }
        ) { padding ->
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
                    HorizontalPager(state = taildropPagerState, modifier = Modifier.fillMaxSize()) { page ->
                        when (page) {
                            0 -> if (files.isEmpty() && !isLoading) EmptyState(Icons.Default.Inbox, stringResource(R.string.files_empty_inbox)) 
                                else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(files) { f -> FileCard(f, { openTaildropFile(context, f) }, { handleSaveRequest(f) }, {
                                        scope.launch(Dispatchers.IO) {
                                            val deleted = Appctr.deleteTaildropFileFromAPI(f.Name)
                                            if (deleted) refreshData()
                                        }
                                    }) }
                                }
                            1 -> if (peers.isEmpty() && selfPeer == null && !isLoading) EmptyState(Icons.Default.Devices, stringResource(R.string.files_empty_devices)) 
                                else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                                    if (selfPeer != null) {
                                        item { PeerItem(selfPeer!!, true) {} }
                                    }
                                    items(peers) { p -> 
                                        PeerItem(p, false) { Toast.makeText(context, context.getString(R.string.files_use_fab_to_send), Toast.LENGTH_SHORT).show() }
                                    }
                                }
                            2 -> if (sentFiles.isEmpty() && !isLoading) EmptyState(Icons.Default.History, stringResource(R.string.files_empty_history)) 
                                else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(sentFiles) { e -> SentFileCard(e) }
                                }
                        }
                    }
                }
            }
            if (isSavingFile) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (isSendingFile) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.3f)), contentAlignment = Alignment.Center) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { 
                        LoadingIndicator(); Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.files_sending)); Text(sendProgressText, style = MaterialTheme.typography.bodySmall) 
                    }
                }
            }
        }

        if (showPeerPicker && selectedUriForSend != null) {
            // Strings resolved in the parent composition — see wrapContextWithLocale().
            val strFilesSelectDevice = stringResource(R.string.files_select_device)
            val strFilesNoDevicesFound = stringResource(R.string.files_no_devices_found)
            val strActionRefresh = stringResource(R.string.action_refresh)
            ModalBottomSheet(onDismissRequest = { showPeerPicker = false }, containerColor = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    Text(strFilesSelectDevice, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (peers.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(100.dp), Alignment.Center) { 
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(strFilesNoDevicesFound, color = MaterialTheme.colorScheme.outline)
                                TextButton(onClick = { refreshData() }) { Text(strActionRefresh) }
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            this@LazyColumn.items(peers) { p -> 
                                PeerShareItem(p, !isSendingFile, taildropStatusOf(p, taildropStrings)) {
                                    showPeerPicker = false; isSendingFile = true
                                    scope.launch(Dispatchers.IO) {
                                        sendSingleFileInActivity(context, selectedUriForSend!!, p) { sendProgressText = it }
                                        withContext(Dispatchers.Main) { isSendingFile = false; selectedUriForSend = null; refreshData() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

private suspend fun saveFileToUri(context: Context, file: TaildropFile, destUri: Uri) {
    try { File(file.Path).inputStream().use { input -> context.contentResolver.openOutputStream(destUri)?.use { output -> input.copyTo(output); output.flush() } } }
    catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.files_save_error, e.message), Toast.LENGTH_SHORT).show() } }
}

private suspend fun sendSingleFileInActivity(context: Context, uri: Uri, peer: PeerData, onProgress: (String) -> Unit) {
    try {
        // StableNodeID or nothing — see taildropTargetId (ShareActivity.kt).
        val target = taildropTargetId(context, peer)
        val originalName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
        onProgress(context.getString(R.string.files_preparing_format, originalName))
        val outDir = File(context.cacheDir, "taildrop_out").apply { mkdirs() }
        val tmp = File(outDir, originalName)
        context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o); o.flush() } }
        onProgress(context.getString(R.string.files_uploading))
        val res = Appctr.sendFileFromAPI(target, tmp.absolutePath)
        tmp.delete()

        // "OK" is a 2xx from the peer; every failure starts with "Error" and carries the
        // peer's HTTP status and body ("HTTP 404: node not found") or the local reason.
        if (res == "OK") {
            logSentFile(context, originalName, peer.getDisplayName())
            withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.files_sent), Toast.LENGTH_SHORT).show() }
        } else withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.files_error_format, res.removePrefix("Error: ")), Toast.LENGTH_LONG).show() }
    } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.files_failed_format, e.message), Toast.LENGTH_SHORT).show() } }
}
