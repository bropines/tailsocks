package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

@Serializable
data class LogEntry(
    @SerialName("timestamp") val timestamp: String = "",
    @SerialName("level") val level: String = "",
    @SerialName("category") val category: String = "",
    @SerialName("message") val message: String = ""
)

class LogsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                LogsScreen(onBack = { finish() })
            }
        }
    }
}

fun getDebugHeader(context: Context): String {
    val verName = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "unknown" }
    val coreVer = try { Appctr.getCoreVersion() } catch (e: Exception) { "unknown" }
    val activeAccount = AccountManager.getActiveAccount(context)
    val prefs = context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE)
    
    val hostname = prefs.getString("hostname", "") ?: ""
    // Proxy, DNS and route options are global settings — reading them from the
    // per-profile store always missed and reported defaults that were never used.
    val socks5 = GlobalSettings.getString(context, "socks5", "127.0.0.1:48115")
    val httpProxy = GlobalSettings.getString(context, "httpproxy", "")
    val dnsProxy = GlobalSettings.getString(context, "dns_proxy", "")
    val acceptRoutes = GlobalSettings.getBoolean(context, "accept_routes", false)
    val acceptDNS = GlobalSettings.getBoolean(context, "accept_dns", true)
    val lanAccess = GlobalSettings.isLanAccessEnabled(context)
    val rootMode = GlobalSettings.isRootModeEnabled(context)
    val rootTun = rootMode && GlobalSettings.isRootTunEnabled(context)
    val exitNodeSet = prefs.getString("exit_node_id", "")?.isNotEmpty() == true
    val authKeySet = prefs.getString("authkey", "")?.isNotEmpty() == true

    return """
        --- TAILSOCKS DEBUG INFO ---
        App Version: $verName
        Tailscale Core: $coreVer
        Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})
        Arch: ${Build.SUPPORTED_ABIS.joinToString(", ")}
        
        Settings:
        hostname: $hostname
        socks5: $socks5
        httpProxy: $httpProxy
        dnsProxy: $dnsProxy
        acceptRoutes: $acceptRoutes
        acceptDNS: $acceptDNS
        lanAccess: ${if (lanAccess) "Enabled (0.0.0.0)" else "Disabled (loopback)"}
        rootMode: ${if (rootMode) "Enabled" else "Disabled"}
        rootTun: ${if (rootTun) "tailscale0 (kernel)" else "Off"}
        exitNode: ${if (exitNodeSet) "Enabled" else "Disabled"}
        authKey: ${if (authKeySet) "Present" else "Empty"}
        ----------------------------
        
    """.trimIndent()
}

/**
 * Tail reader for the Root Mode daemon log (`<dataDir>/logs/tailscaled.log`).
 *
 * The daemon appends as root and the file grows without bound; hundreds of KB
 * of netmap dumps are normal. Reading and parsing the whole file on every
 * 2-second refresh made the Logs screen crawl, so only the last [TAIL_BYTES]
 * are read, and the parse is reused until the file's length or mtime changes.
 */
private object RootDaemonLog {
    private const val TAIL_BYTES = 128 * 1024
    private const val MAX_ENTRIES = 300

    /** `2006/01/02 15:04:05 message`: what Go's log package writes with LstdFlags. */
    private val lineRegex = Regex("""^(\d{4}/\d{2}/\d{2}) (\d{2}:\d{2}:\d{2}) (.*)$""")

    private class Snapshot(val length: Long, val lastModified: Long, val entries: List<LogEntry>)

    @Volatile
    private var cached: Snapshot? = null

    /** Drops the cached parse, e.g. after the file was truncated. */
    fun invalidate() { cached = null }

    /** Parsed entries from the end of the file; empty if it is missing or unreadable. */
    fun tailEntries(file: File): List<LogEntry> {
        if (!file.exists()) { cached = null; return emptyList() }
        val length = file.length()
        val lastModified = file.lastModified()
        cached?.let { if (it.length == length && it.lastModified == lastModified) return it.entries }
        val text = try {
            readTail(file, TAIL_BYTES)
        } catch (e: Exception) {
            android.util.Log.e("LogsActivity", "Error reading root log file: ${e.message}")
            return emptyList()
        }
        val entries = parse(text).takeLast(MAX_ENTRIES)
        cached = Snapshot(length, lastModified, entries)
        return entries
    }

    /**
     * Returns at most the last [maxBytes] of [file] as text. When the read does
     * not start at the beginning of the file the partial first line is dropped,
     * so the result always begins on a line boundary.
     */
    fun readTail(file: File, maxBytes: Int): String {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = maxOf(0L, length - maxBytes.coerceAtLeast(0))
            val buf = ByteArray((length - start).toInt())
            raf.seek(start)
            raf.readFully(buf)
            val text = String(buf, Charsets.UTF_8)
            if (start == 0L) return text
            val nl = text.indexOf('\n')
            return if (nl >= 0) text.substring(nl + 1) else ""
        }
    }

    /**
     * Splits daemon output into entries. A line that does not start with the
     * Go log date and time is a continuation (multi-line netmap dumps, panics)
     * and is appended to the previous entry. The old code took the first 19
     * characters of every line as its timestamp, which rendered continuation
     * lines as `netmap: self: [B06o [ROOT] netmap: self: [B06oh] ...`.
     */
    fun parse(text: String): List<LogEntry> {
        val timestamps = ArrayList<String>()
        val messages = ArrayList<StringBuilder>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val m = lineRegex.matchEntire(line)
            when {
                m != null -> {
                    timestamps.add(m.groupValues[2])
                    messages.add(StringBuilder(m.groupValues[3]))
                }
                messages.isNotEmpty() -> messages.last().append('\n').append(line)
                else -> {
                    timestamps.add("")
                    messages.add(StringBuilder(line))
                }
            }
        }
        return List(timestamps.size) { i ->
            val message = messages[i].toString()
            LogEntry(timestamp = timestamps[i], level = levelOf(message), category = "ROOT", message = message)
        }
    }

    private fun levelOf(message: String): String {
        val lower = message.lowercase()
        return when {
            lower.contains("error") || lower.contains("failed") || lower.contains("panic") -> "ERROR"
            lower.contains("warn") -> "WARN"
            else -> "INFO"
        }
    }
}

/**
 * Seconds since local midnight for an `HH:MM:SS` timestamp, or -1 when there is
 * none. Both sources carry local wall-clock time (the Go buffer emits `15:04:05`,
 * [RootDaemonLog.parse] reduces the daemon's `2006/01/02 15:04:05` to the time
 * part), so this is the one key the merged list can be ordered by.
 */
private fun timestampSortKey(timestamp: String): Int {
    if (timestamp.length < 8) return -1
    val t = timestamp.takeLast(8)
    if (t[2] != ':' || t[5] != ':') return -1
    val h = t.substring(0, 2).toIntOrNull() ?: return -1
    val m = t.substring(3, 5).toIntOrNull() ?: return -1
    val s = t.substring(6, 8).toIntOrNull() ?: return -1
    return h * 3600 + m * 60 + s
}

private const val ROOT_LOG_SECTION_HEADER = "\n--- ROOT DAEMON LOGS (tailscaled.log) ---\n"

/**
 * Upper bound on what Copy hands to the clipboard. A ClipData travels in a
 * single Binder transaction (about 1 MB, strings as UTF-16), so a daemon log
 * of a few hundred KB made setPrimaryClip fail and nothing was copied at all.
 */
private const val CLIPBOARD_LOG_LIMIT = 400 * 1024

private const val CLIPBOARD_TRUNCATED_MARKER =
    "[... log is large: only the tail is on the clipboard; Save exports the complete log ...]\n"

/** Everything, for Save: the debug header, the Go buffer and the whole daemon log. */
fun buildFullLogString(context: Context): String {
    val header = getDebugHeader(context)
    val goLogs = try { Appctr.getLogs() } catch (e: Exception) { "" }
    val logFile = RootUtils.rootDaemonLogFile(context)
    val rootLogs = if (logFile.exists()) {
        try { ROOT_LOG_SECTION_HEADER + logFile.readText() } catch (e: Exception) { "" }
    } else ""
    return header + goLogs + rootLogs
}

/** Drops the beginning of [text] so that at most [maxChars] remain, cutting at a line boundary. */
private fun cutToTail(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.length - maxChars.coerceAtLeast(0)
    val nl = text.indexOf('\n', cut)
    return if (nl >= 0) text.substring(nl + 1) else text.substring(cut)
}

/**
 * Text for the clipboard: like [buildFullLogString] but capped at
 * [CLIPBOARD_LOG_LIMIT] characters. The debug header is always kept; the Go
 * buffer gets at most half of the remaining budget and the daemon log the
 * rest, each cut from the front at a line boundary so only whole lines are
 * pasted. The daemon file is read as a tail, never whole.
 *
 * @return the text and whether anything was left out.
 */
fun buildClipboardLogString(context: Context): Pair<String, Boolean> {
    val header = getDebugHeader(context)
    val budget = CLIPBOARD_LOG_LIMIT - header.length - ROOT_LOG_SECTION_HEADER.length - CLIPBOARD_TRUNCATED_MARKER.length
    var truncated = false

    var goLogs = try { Appctr.getLogs() } catch (e: Exception) { "" }
    if (goLogs.length > budget / 2) {
        goLogs = cutToTail(goLogs, budget / 2)
        truncated = true
    }

    val logFile = RootUtils.rootDaemonLogFile(context)
    val rootLogs = if (logFile.exists()) {
        try {
            val remaining = budget - goLogs.length
            val tail = RootDaemonLog.readTail(logFile, remaining)
            if (logFile.length() > remaining) truncated = true
            ROOT_LOG_SECTION_HEADER + tail
        } catch (e: Exception) { "" }
    } else ""

    val text = buildString {
        append(header)
        if (truncated) append(CLIPBOARD_TRUNCATED_MARKER)
        append(goLogs)
        append(rootLogs)
    }
    return text to truncated
}

/**
 * Empties the daemon log. The app can only truncate the file itself when it is
 * app-writable, which it never is in practice (the daemon creates it as root
 * and `writeText("")` fails with EACCES), so it otherwise goes through su.
 * Blocking; call from Dispatchers.IO.
 */
private fun clearRootDaemonLogFile(context: Context): Boolean {
    val logFile = RootUtils.rootDaemonLogFile(context)
    val ok = when {
        !logFile.exists() -> true
        logFile.canWrite() && runCatching { logFile.writeText("") }.isSuccess -> true
        else -> RootUtils.clearRootDaemonLog(context)
    }
    RootDaemonLog.invalidate()
    return ok
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var allLogs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    
    var isAutoScroll by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    
    var scale by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()

    val isRootMode = remember { GlobalSettings.isRootModeEnabled(context) }
    val categoryItems = remember(isRootMode) {
        val list = mutableListOf(
            SegmentedChipItem("ALL", Icons.AutoMirrored.Filled.List),
            SegmentedChipItem("ERROR", Icons.Default.Error, containerColor = Color(0xFFEF5350).copy(alpha = 0.25f), contentColor = Color(0xFFEF5350)),
            SegmentedChipItem("CORE", Icons.Default.Memory, containerColor = Color(0xFF42A5F5).copy(alpha = 0.25f), contentColor = Color(0xFF1E88E5)),
            SegmentedChipItem("TAILSCALE", Icons.Default.VpnLock, containerColor = Color(0xFF66BB6A).copy(alpha = 0.25f), contentColor = Color(0xFF43A047))
        )
        if (isRootMode) {
            list.add(SegmentedChipItem("ROOT", Icons.Default.Terminal, containerColor = Color(0xFF9C27B0).copy(alpha = 0.25f), contentColor = Color(0xFF9C27B0)))
        }
        list.add(SegmentedChipItem("OTHER", Icons.Default.Category, containerColor = Color(0xFFFFA726).copy(alpha = 0.25f), contentColor = Color(0xFFFB8C00)))
        list.toList()
    }
    val categories = remember(categoryItems) { categoryItems.map { it.title } }

    val displayedLogs = remember(allLogs, selectedCategory, searchQuery) {
        allLogs.filter { log ->
            val matchCategory = selectedCategory == "ALL" || log.category == selectedCategory
            val matchQuery = searchQuery.isEmpty() || log.message.contains(searchQuery, ignoreCase = true)
            matchCategory && matchQuery
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val fullLog = buildFullLogString(context)
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        OutputStreamWriter(os).use { writer -> writer.write(fullLog) }
                    }
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.logs_saved), Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    fun loadLogsData(manual: Boolean = false) {
        if (manual) isRefreshing = true
        coroutineScope.launch(Dispatchers.IO) {
            var jsonString = try { Appctr.getLogsJSON() } catch (e: Exception) { "[]" }
            var logsList: List<LogEntry> = if (jsonString.isBlank()) emptyList()
                else runCatching { AppJson.decodeFromString<List<LogEntry>>(jsonString) }.getOrDefault(emptyList())

            if (GlobalSettings.isRootModeEnabled(context)) {
                val parsed = RootDaemonLog.tailEntries(RootUtils.rootDaemonLogFile(context))
                if (parsed.isNotEmpty()) {
                    // sortedBy is stable: entries from one source keep their
                    // original order when they share a second.
                    logsList = (logsList + parsed).sortedBy { timestampSortKey(it.timestamp) }
                }
            }

            withContext(Dispatchers.Main) {
                allLogs = logsList
                if (manual) isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            loadLogsData()
            delay(2000)
        }
    }

    // Follow the tail only while the reader is at the tail. isAutoScroll used to
    // be set once and never cleared, so every refresh tick (2 s) yanked the list
    // back to the bottom while the user was reading further up — "the log is
    // stuck at the bottom". A manual scroll away from the end switches following
    // off; scrolling back to the end switches it on again.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) isAutoScroll = isAtBottom
    }

    LaunchedEffect(displayedLogs.size) {
        if (isAutoScroll && displayedLogs.isNotEmpty()) {
            listState.animateScrollToItem(displayedLogs.size - 1)
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
                    TopAppBar(
                        title = { Text(stringResource(R.string.logs_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                        },
                        actions = {
                            IconButton(onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    Appctr.flushDNS()
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, context.getString(R.string.logs_dns_flushed), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) { Icon(Icons.Default.CleaningServices, contentDescription = stringResource(R.string.logs_cd_flush_dns)) }

                            IconButton(onClick = {
                                // Reads the root daemon log and calls JNI — off the main thread.
                                coroutineScope.launch(Dispatchers.IO) {
                                    val (text, truncated) = buildClipboardLogString(context)
                                    withContext(Dispatchers.Main) {
                                        try {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("TailSocks Logs", text))
                                            if (truncated) {
                                                Toast.makeText(context, context.getString(R.string.logs_copied_tail), Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, context.getString(R.string.logs_copied), Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy)) }
                            
                            IconButton(onClick = { saveFileLauncher.launch("tailsocks_logs_${System.currentTimeMillis()}.txt") }) { Icon(Icons.Default.Save, contentDescription = stringResource(R.string.action_save)) }

                            if (GlobalSettings.isRootModeEnabled(context)) {
                                IconButton(onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val ok = clearRootDaemonLogFile(context)
                                        withContext(Dispatchers.Main) {
                                            if (ok) {
                                                allLogs = allLogs.filter { it.category != "ROOT" }
                                                Toast.makeText(context, context.getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, context.getString(R.string.logs_root_clear_failed), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }) { Icon(Icons.Default.DeleteForever, contentDescription = stringResource(R.string.action_clear)) }
                            }
                        }
                    )
                    
                    CompactSearchBar(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholderText = stringResource(R.string.logs_search_placeholder),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    val selectedCategoryIndex = categories.indexOf(selectedCategory).coerceAtLeast(0)
                    ScrollableSlidingSegmentedChips(
                        items = categoryItems,
                        selectedIndex = selectedCategoryIndex,
                        onOptionSelected = { idx ->
                            selectedCategory = categories[idx]
                            isAutoScroll = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        height = 36.dp
                    )
                }
            },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                coroutineScope.launch(Dispatchers.IO) {
                    Appctr.clearLogs()
                    // The ROOT entries come from the daemon file; leaving it alone
                    // made them reappear on the next refresh tick right after "Cleared".
                    val rootOk = !GlobalSettings.isRootModeEnabled(context) || clearRootDaemonLogFile(context)
                    withContext(Dispatchers.Main) {
                        if (rootOk) {
                            allLogs = emptyList()
                            Toast.makeText(context, context.getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
                        } else {
                            allLogs = allLogs.filter { it.category == "ROOT" }
                            Toast.makeText(context, context.getString(R.string.logs_root_clear_failed), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_clear)) }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { loadLogsData(true) },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp).pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(0.5f, 4f) }
                    }
                ) {
                    items(displayedLogs) { log ->
                        val defaultColor = MaterialTheme.colorScheme.onSurface
                        val highlightedText = remember(log, defaultColor) {
                            highlightLogMessage(log.timestamp, log.category, log.message, defaultColor)
                        }
                        Text(
                            text = highlightedText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = (12 * scale).sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
}
