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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
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
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

/**
 * One log line as the Go bridge reports it. [unix] is epoch milliseconds and
 * the key the merged list is ordered by; [timestamp] is the `HH:MM:SS` shown.
 */
@Serializable
data class LogEntry(
    @SerialName("unix") val unix: Long = 0L,
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
 * The category of the daemon's own lines: its stdout in Proxy mode (tagged by
 * the Go bridge), its file in Root Mode (parsed here). CORE is the app, ROOT
 * the app's Root Mode routing decisions, OTHER the DPI bypass. Clearing works
 * by this split: the daemon's lines, the app's, or everything.
 */
private const val DAEMON_CATEGORY = "TAILSCALE"

/** What one Clear removes. */
private enum class ClearScope { ALL, APP, DAEMON }

/**
 * Tail reader for the Root Mode daemon log (`<dataDir>/logs/tailscaled.log`).
 * Its lines are the daemon's, so they join the TAILSCALE category, the same
 * tab the daemon's stdout fills in Proxy mode; until 4.1.1 they were a
 * separate ROOT category and shared that tab with the app's routing lines.
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
     *
     * The date and time are local wall-clock, the same clock the Go buffer
     * stamps its entries with, and become the entry's [LogEntry.unix]; a line
     * without a stamp inherits the previous entry's so it stays in place.
     */
    fun parse(text: String): List<LogEntry> {
        // Not thread-safe, hence one per parse: two refresh ticks can overlap on IO.
        val stampFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
        val unixes = ArrayList<Long>()
        val timestamps = ArrayList<String>()
        val messages = ArrayList<StringBuilder>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val m = lineRegex.matchEntire(line)
            when {
                m != null -> {
                    val unix = try { stampFormat.parse("${m.groupValues[1]} ${m.groupValues[2]}")?.time ?: 0L } catch (e: Exception) { 0L }
                    unixes.add(unix)
                    timestamps.add(m.groupValues[2])
                    messages.add(StringBuilder(m.groupValues[3]))
                }
                messages.isNotEmpty() -> messages.last().append('\n').append(line)
                else -> {
                    unixes.add(0L)
                    timestamps.add("")
                    messages.add(StringBuilder(line))
                }
            }
        }
        return List(timestamps.size) { i ->
            val message = messages[i].toString()
            LogEntry(unix = unixes[i], timestamp = timestamps[i], level = levelOf(message), category = DAEMON_CATEGORY, message = message)
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

/** Preference: whether the Logs screen also shows the process's own logcat. */
private const val LOGCAT_PREF = "logs_include_logcat"
private const val LOGCAT_CATEGORY = "LOGCAT"

/**
 * The app's own logcat: what the Kotlin side writes with android.util.Log, plus
 * the platform's lines for this process. An app may read its own without any
 * permission; `--pid` keeps it to this process. Optional, because Compose and
 * the platform are chatty, and off by default.
 */
private object LogcatSource {
    private const val MAX_LINES = 600

    /** `-v epoch`: `1757340000.123  pid  tid L Tag: message` */
    private val lineRegex = Regex("""^\s*(\d+)\.(\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.*?)\s*:\s?(.*)$""")

    fun rawText(): String = try {
        val pid = android.os.Process.myPid()
        val proc = ProcessBuilder("logcat", "-d", "-v", "epoch", "--pid=$pid", "-t", MAX_LINES.toString())
            .redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        text
    } catch (e: Exception) {
        ""
    }

    /** Entries newer than [since] (epoch millis); the level follows logcat's priority letter. */
    fun entries(since: Long): List<LogEntry> {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        val out = ArrayList<LogEntry>()
        for (line in rawText().lineSequence()) {
            val m = lineRegex.matchEntire(line) ?: continue
            val unix = m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong()
            if (unix <= since) continue
            val level = when (m.groupValues[3]) {
                "E", "F" -> "ERROR"
                "W" -> "WARN"
                else -> "INFO"
            }
            out += LogEntry(
                unix = unix,
                timestamp = time.format(Date(unix)),
                level = level,
                category = LOGCAT_CATEGORY,
                message = "${m.groupValues[4]}: ${m.groupValues[5]}"
            )
        }
        return out
    }
}

/** One row of the list: an entry, or the divider that opens a new day. */
private sealed interface LogRow {
    data class Entry(val log: LogEntry) : LogRow
    data class Day(val label: String) : LogRow
}

/**
 * Inserts a day divider before the first entry of each day, but only when the
 * list spans more than one day. Every line shows just `HH:MM:SS`, and the Go
 * buffer lives as long as the app process — with the phone on for two days,
 * yesterday's "16:43" and today's "11:27" are otherwise indistinguishable.
 */
private fun withDayDividers(logs: List<LogEntry>, today: String, yesterday: String): List<LogRow> {
    if (logs.isEmpty()) return emptyList()
    val cal = Calendar.getInstance()
    fun dayOf(unix: Long): Long {
        cal.timeInMillis = unix
        return cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
    }
    val days = HashSet<Long>()
    for (log in logs) if (log.unix != 0L) days.add(dayOf(log.unix))
    if (days.size < 2) return logs.map { LogRow.Entry(it) }

    val now = System.currentTimeMillis()
    val todayDay = dayOf(now)
    val yesterdayDay = dayOf(now - 86_400_000L)
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val rows = ArrayList<LogRow>(logs.size + days.size)
    var lastDay = -1L
    for (log in logs) {
        if (log.unix != 0L) {
            val day = dayOf(log.unix)
            if (day != lastDay) {
                lastDay = day
                rows.add(LogRow.Day(when (day) {
                    todayDay -> today
                    yesterdayDay -> yesterday
                    else -> dateFormat.format(Date(log.unix))
                }))
            }
        }
        rows.add(LogRow.Entry(log))
    }
    return rows
}

/**
 * An entry longer than [FOLD_OVER_LINES] lines — a netmap dump, a panic — is
 * shown as its first [FOLDED_LINES] lines until tapped, so one dump does not
 * push a screen of real lines out of view.
 */
private const val FOLD_OVER_LINES = 6
private const val FOLDED_LINES = 3

/** Identity of an entry across refreshes, for the set of unfolded ones. */
private fun foldKey(log: LogEntry): Long = log.unix * 31 + log.message.hashCode()

private const val ROOT_LOG_SECTION_HEADER = "\n--- ROOT DAEMON LOGS (tailscaled.log) ---\n"
private const val LOGCAT_SECTION_HEADER = "\n--- LOGCAT (this process) ---\n"

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
    val logcat = if (GlobalSettings.getBoolean(context, LOGCAT_PREF, false)) LOGCAT_SECTION_HEADER + LogcatSource.rawText() else ""
    return header + goLogs + rootLogs + logcat
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
    var unfolded by remember { mutableStateOf(emptySet<Long>()) }
    var clearMenuOpen by remember { mutableStateOf(false) }
    var includeLogcat by remember { mutableStateOf(GlobalSettings.getBoolean(context, LOGCAT_PREF, false)) }
    // Clearing cannot empty logcat itself (that is the system's buffer); it hides what came before.
    var logcatSince by remember { mutableStateOf(0L) }
    
    var isAutoScroll by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    
    var scale by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()

    val isRootMode = remember { GlobalSettings.isRootModeEnabled(context) }
    val categoryItems = remember(isRootMode, includeLogcat) {
        val list = mutableListOf(
            SegmentedChipItem("ALL", Icons.AutoMirrored.Filled.List),
            SegmentedChipItem("ERROR", Icons.Default.Error, containerColor = Color(0xFFEF5350).copy(alpha = 0.25f), contentColor = Color(0xFFEF5350)),
            SegmentedChipItem("CORE", Icons.Default.Memory, containerColor = Color(0xFF42A5F5).copy(alpha = 0.25f), contentColor = Color(0xFF1E88E5)),
            SegmentedChipItem("TAILSCALE", Icons.Default.VpnLock, containerColor = Color(0xFF66BB6A).copy(alpha = 0.25f), contentColor = Color(0xFF43A047))
        )
        if (isRootMode) {
            // The app's Root Mode work: tiers, rules, the daemon's launch. The
            // daemon's own lines are under TAILSCALE, as in Proxy mode.
            list.add(SegmentedChipItem("ROOT", Icons.Default.Terminal, containerColor = Color(0xFF9C27B0).copy(alpha = 0.25f), contentColor = Color(0xFF9C27B0)))
        }
        list.add(SegmentedChipItem("OTHER", Icons.Default.Category, containerColor = Color(0xFFFFA726).copy(alpha = 0.25f), contentColor = Color(0xFFFB8C00)))
        if (includeLogcat) {
            list.add(SegmentedChipItem(LOGCAT_CATEGORY, Icons.Default.BugReport, containerColor = Color(0xFF26A69A).copy(alpha = 0.25f), contentColor = Color(0xFF26A69A)))
        }
        list.toList()
    }
    val categories = remember(categoryItems) { categoryItems.map { it.title } }

    val displayedLogs = remember(allLogs, selectedCategory, searchQuery) {
        allLogs.filter { log ->
            val matchCategory = when (selectedCategory) {
                "ALL" -> true
                // An error is a level as much as a category: the daemon file's
                // lines are all ROOT and the app's own logAndroid("ERROR", "CORE", …)
                // are CORE, and the ERROR chip used to show neither.
                "ERROR" -> log.category == "ERROR" || log.level == "ERROR"
                else -> log.category == selectedCategory
            }
            val matchQuery = searchQuery.isEmpty() || log.message.contains(searchQuery, ignoreCase = true)
            matchCategory && matchQuery
        }
    }
    val todayLabel = stringResource(R.string.logs_day_today)
    val yesterdayLabel = stringResource(R.string.logs_day_yesterday)
    val rows = remember(displayedLogs, todayLabel, yesterdayLabel) {
        withDayDividers(displayedLogs, todayLabel, yesterdayLabel)
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
                    // Both sources carry epoch millis, so the merge is by that.
                    // Until 4.1.1 this sorted by seconds since midnight, which put
                    // yesterday's "16:43" from the Go buffer (alive as long as the
                    // app process) after today's "11:27" from the daemon file
                    // (only its last minutes are read) — the bottom of the screen
                    // was stale. sortedBy is stable: entries from one source keep
                    // their original order when they share a millisecond.
                    logsList = (logsList + parsed).sortedBy { it.unix }
                }
            }
            if (includeLogcat) {
                val lines = LogcatSource.entries(logcatSince)
                if (lines.isNotEmpty()) logsList = (logsList + lines).sortedBy { it.unix }
            }

            withContext(Dispatchers.Main) {
                allLogs = logsList
                if (manual) isRefreshing = false
            }
        }
    }

    /**
     * One Clear for both sources. The daemon's lines live in the Go buffer in
     * Proxy mode and in the root-owned file in Root Mode; the app's only in the
     * buffer. Two buttons with different, unstated scopes (the whole buffer plus
     * the file, or the file alone) used to do this.
     */
    fun clearLogs(scope: ClearScope) {
        coroutineScope.launch(Dispatchers.IO) {
            val fileOk = scope == ClearScope.APP ||
                !GlobalSettings.isRootModeEnabled(context) || clearRootDaemonLogFile(context)
            when (scope) {
                ClearScope.ALL -> Appctr.clearLogs()
                ClearScope.APP -> Appctr.clearLogsWhere(DAEMON_CATEGORY, true)
                ClearScope.DAEMON -> Appctr.clearLogsWhere(DAEMON_CATEGORY, false)
            }
            if (scope != ClearScope.DAEMON) logcatSince = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                allLogs = when (scope) {
                    ClearScope.ALL -> if (fileOk) emptyList() else allLogs.filter { it.category == DAEMON_CATEGORY }
                    ClearScope.APP -> allLogs.filter { it.category == DAEMON_CATEGORY }
                    ClearScope.DAEMON -> if (fileOk) allLogs.filter { it.category != DAEMON_CATEGORY } else allLogs
                }
                val message = if (fileOk) R.string.logs_cleared else R.string.logs_root_clear_failed
                Toast.makeText(context, context.getString(message), if (fileOk) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
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
    // off; scrolling back to the end, or the arrow button, switches it on again.
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

    LaunchedEffect(rows.size) {
        if (isAutoScroll && rows.isNotEmpty()) {
            listState.animateScrollToItem(rows.size - 1)
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

                            IconButton(onClick = {
                                includeLogcat = !includeLogcat
                                GlobalSettings.setBoolean(context, LOGCAT_PREF, includeLogcat)
                                if (!includeLogcat && selectedCategory == LOGCAT_CATEGORY) selectedCategory = "ALL"
                            }) {
                                Icon(
                                    Icons.Default.BugReport,
                                    contentDescription = stringResource(R.string.logs_cd_logcat),
                                    tint = if (includeLogcat) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
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
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Back to the live tail after reading further up; following resumes.
                AnimatedVisibility(visible = !isAutoScroll && rows.isNotEmpty()) {
                    SmallFloatingActionButton(onClick = {
                        isAutoScroll = true
                        coroutineScope.launch { listState.scrollToItem(rows.size - 1) }
                    }) { Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.logs_cd_jump_to_end)) }
                }
                // The scopes slide out beside the button instead of a menu: one
                // tap opens, the next clears and folds them back.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(
                        visible = clearMenuOpen,
                        enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
                        exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut()
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((scope, label) in listOf(
                                ClearScope.ALL to R.string.logs_clear_all,
                                ClearScope.APP to R.string.logs_clear_app,
                                ClearScope.DAEMON to R.string.logs_clear_daemon
                            )) {
                                FilledTonalButton(
                                    onClick = { clearMenuOpen = false; clearLogs(scope) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) { Text(stringResource(label), maxLines = 1) }
                            }
                        }
                    }
                    FloatingActionButton(onClick = { clearMenuOpen = !clearMenuOpen }) {
                        Icon(
                            if (clearMenuOpen) Icons.Default.Close else Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_clear)
                        )
                    }
                }
            }
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
                    // Room under the last line for the buttons, which otherwise cover the tail.
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp).pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(0.5f, 4f) }
                    }
                ) {
                    items(rows, contentType = { it::class }) { row ->
                        when (row) {
                            is LogRow.Day -> DayDivider(row.label)
                            is LogRow.Entry -> {
                                val key = foldKey(row.log)
                                LogEntryRow(
                                    log = row.log,
                                    scale = scale,
                                    expanded = key in unfolded,
                                    onToggle = { unfolded = if (key in unfolded) unfolded - key else unfolded + key }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun DayDivider(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LogEntryRow(log: LogEntry, scale: Float, expanded: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val defaultColor = MaterialTheme.colorScheme.onSurface
    val hintColor = MaterialTheme.colorScheme.primary
    val lineCount = remember(log.message) { log.message.count { it == '\n' } + 1 }
    val foldable = lineCount > FOLD_OVER_LINES
    val text = remember(log, defaultColor, expanded) {
        val shown = if (foldable && !expanded) log.message.lineSequence().take(FOLDED_LINES).joinToString("\n") else log.message
        val body = highlightLogMessage(log.timestamp, log.category, shown, defaultColor)
        if (!foldable) body else buildAnnotatedString {
            append(body)
            withStyle(SpanStyle(color = hintColor, fontStyle = FontStyle.Italic)) {
                append('\n')
                append(
                    if (expanded) context.getString(R.string.logs_collapse)
                    else context.getString(R.string.logs_expand_more, lineCount - FOLDED_LINES)
                )
            }
        }
    }
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = (12 * scale).sp,
        modifier = Modifier
            .then(if (foldable) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(vertical = 2.dp)
    )
}
