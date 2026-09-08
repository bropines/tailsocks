package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import androidx.compose.ui.platform.LocalHapticFeedback
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// The scrollback lives in a single String that is appended to on every command
// and re-loaded from disk on every open; without a cap it grew without bound
// both on disk and in memory. Keep only the tail — on load, on every append and
// when persisting.
private const val MAX_HISTORY_LINES = 4000
private const val MAX_HISTORY_BYTES = 200 * 1024

/** How many entered commands are kept, and how many the history menu shows. */
private const val MAX_CMD_HISTORY = 200
private const val HISTORY_MENU_ITEMS = 12

/** The idle prompt; a command's echo replaces it rather than following it. */
private const val PROMPT = "$ "

/** A command that ran at least this long gets a dim `# 1.2 s` line after its output. */
private const val ELAPSED_MARK_MS = 1000L

private val BASE_PRESETS = listOf("status", "/GET /localapi/v0/status", "/GET /localapi/v0/prefs", "netcheck", "ping 8.8.8.8")

private fun capScrollback(text: String): String {
    var capped = text
    val lines = capped.split("\n")
    if (lines.size > MAX_HISTORY_LINES) {
        capped = lines.takeLast(MAX_HISTORY_LINES).joinToString("\n")
    }
    // A handful of very long lines can still blow the size budget, so trim bytes
    // too (a split multibyte char at the cut is harmless for a console log).
    val bytes = capped.toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_HISTORY_BYTES) {
        capped = String(bytes, bytes.size - MAX_HISTORY_BYTES, MAX_HISTORY_BYTES, Charsets.UTF_8)
    }
    return capped
}

/** The scrollback with its trailing idle prompt removed, ready for a command echo. */
private fun String.atPrompt(): String = when {
    endsWith(PROMPT) -> dropLast(PROMPT.length)
    isEmpty() -> ""
    else -> this + "\n"
}

private fun formatElapsed(ms: Long): String =
    if (ms < 60_000) String.format(Locale.US, "%.1f s", ms / 1000.0)
    else String.format(Locale.US, "%d min %d s", ms / 60_000, (ms % 60_000) / 1000)

/**
 * Colours the scrollback so the commands stand out from what they printed:
 * prompt lines in the primary colour, `Error:` lines in the error colour, the
 * elapsed-time marks dimmed. The whole text is one String capped at 200 KB, so
 * this walks at most a few thousand lines and is remembered per text.
 */
private fun styleScrollback(text: String, prompt: Color, error: Color, dim: Color, default: Color): AnnotatedString =
    buildAnnotatedString {
        var start = 0
        while (true) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            val line = text.substring(start, end)
            val style = when {
                line.startsWith(PROMPT) -> SpanStyle(color = prompt, fontWeight = FontWeight.Bold)
                line.startsWith("Error:") || line.startsWith("error:") -> SpanStyle(color = error)
                line.startsWith("# ") -> SpanStyle(color = dim, fontStyle = FontStyle.Italic)
                else -> SpanStyle(color = default)
            }
            withStyle(style) { append(line) }
            if (nl < 0) break
            append('\n')
            start = nl + 1
        }
    }

class ConsoleActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // KEYBOARD FIX: Allow Compose to handle window insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val initialCmd = intent?.getStringExtra("CMD") ?: ""
        
        setContent {
            TailSocksTheme {
                ConsoleScreen(initialCmd = initialCmd, onBack = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConsoleScreen(initialCmd: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState() // For horizontal scroll
    val focusRequester = remember { FocusRequester() }

    val prefs = remember { context.getSharedPreferences("console_presets", Context.MODE_PRIVATE) }
    val historyFile = remember { File(context.filesDir, "console_history.dat") }
    val cmdHistoryFile = remember { File(context.filesDir, "console_cmd_history.dat") }

    var outputText by remember { mutableStateOf(PROMPT) }
    val haptic = LocalHapticFeedback.current
    var currentCommand by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }

    // ZOOM
    var scale by remember { mutableFloatStateOf(1f) }
    var softWrap by remember { mutableStateOf(false) }

    val commandHistory = remember { mutableStateListOf<String>() }
    var historyMenuOpen by remember { mutableStateOf(false) }

    var customPresets by remember { 
        mutableStateOf(prefs.getStringSet("commands", emptySet<String>())?.toList()?.sorted() ?: emptyList<String>()) 
    }
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var newPresetCmd by remember { mutableStateOf("") }
    /** A custom preset that was long-pressed: insert into the field, or delete. */
    var presetMenuFor by remember { mutableStateOf<String?>(null) }

    fun saveCommandHistory() {
        // Snapshot on the caller thread (the list is Compose state), write off it:
        // this is called straight from the Run button and the IME Done action.
        val snapshot = commandHistory.joinToString("\n")
        coroutineScope.launch(Dispatchers.IO) {
            try { cmdHistoryFile.writeText(snapshot) } catch (e: Exception) {}
        }
    }

    /**
     * Persists the scrollback now rather than only on dispose: a process death
     * (reinstall, low memory) between commands used to lose everything since the
     * screen was last closed.
     */
    fun saveScrollback() {
        val snapshot = outputText
        coroutineScope.launch(Dispatchers.IO) {
            try { historyFile.writeText(capScrollback(snapshot)) } catch (e: Exception) {}
        }
    }

    fun saveCustomPresets(presets: Set<String>) {
        prefs.edit().putStringSet("commands", presets).apply()
        customPresets = presets.toList().sorted()
    }

    LaunchedEffect(Unit) {
        // Read the persisted scrollback/command history off the UI dispatcher;
        // only the resulting state writes stay on main.
        val (savedOutput, savedCmds) = withContext(Dispatchers.IO) {
            val out = if (historyFile.exists()) try { historyFile.readText() } catch (e: Exception) { null } else null
            val cmds = if (cmdHistoryFile.exists()) try { cmdHistoryFile.readLines() } catch (e: Exception) { null } else null
            out to cmds
        }
        if (savedOutput != null) {
            // A file written by a build without the cap can be arbitrarily large.
            outputText = capScrollback(savedOutput)
        }
        if (savedCmds != null) commandHistory.addAll(savedCmds.filter { it.isNotBlank() }.takeLast(MAX_CMD_HISTORY))
        if (initialCmd.isNotEmpty()) currentCommand = initialCmd
        focusRequester.requestFocus()
    }

    // Follow the tail once layout has measured the new text. Calling
    // animateScrollTo(maxValue) right after changing outputText used the maxValue
    // of the previous layout, so after a long command (netcheck) the view stopped
    // at the command's echo line with the output below the fold.
    LaunchedEffect(Unit) {
        snapshotFlow { verticalScrollState.maxValue }.collect { max ->
            verticalScrollState.animateScrollTo(max)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // onDispose runs on main; snapshot here and persist off it. The write
            // is wrapped NonCancellable so it still completes if the composition
            // scope is cancelled mid-write during disposal.
            val snapshot = outputText
            val cmds = commandHistory.toList()
            coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                try { historyFile.writeText(capScrollback(snapshot)) } catch (e: Exception) {}
                try { cmdHistoryFile.writeText(cmds.joinToString("\n")) } catch (e: Exception) {}
            }
        }
    }

    fun executeCmd(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty() || isExecuting) return
        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
            commandHistory.remove(cmd)
            commandHistory.add(cmd)
            while (commandHistory.size > MAX_CMD_HISTORY) commandHistory.removeAt(0)
            saveCommandHistory()
        }
        isExecuting = true

        val isLocalAPI = cmd.startsWith("/")
        // The echo takes the idle prompt's line instead of adding one under it.
        outputText = capScrollback(outputText.atPrompt() + PROMPT + (if (isLocalAPI) "LocalAPI $cmd" else "tailscale $cmd"))
        val started = SystemClock.elapsedRealtime()

        coroutineScope.launch(Dispatchers.IO) {
            val result = try { 
                if (isLocalAPI) {
                    // Parse command format like "/GET /localapi/v0/status [body]"
                    val parts = cmd.split(" ", limit = 3)
                    val method = parts[0].removePrefix("/").uppercase()
                    val path = if (parts.size > 1) parts[1] else "/"
                    val body = if (parts.size > 2) parts[2] else ""
                    Appctr.doLocalAPIRequest(method, path, body)
                } else {
                    Appctr.runTailscaleCmd(cmd) 
                }
            } catch (e: Exception) { "Error: ${e.message}" }
            val elapsed = SystemClock.elapsedRealtime() - started
            
            withContext(Dispatchers.Main) {
                val body = result.trimEnd()
                outputText = capScrollback(buildString {
                    append(outputText).append('\n')
                    if (body.isNotEmpty()) append(body).append('\n')
                    if (elapsed >= ELAPSED_MARK_MS) append("# ").append(formatElapsed(elapsed)).append('\n')
                    append(PROMPT)
                })
                isExecuting = false
                currentCommand = ""
                saveScrollback()
                focusRequester.requestFocus()
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
            // KEYBOARD FIX: Keyboard insets (imePadding)
            modifier = Modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.console_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                    },
                    actions = {
                        IconButton(onClick = { softWrap = !softWrap }) { 
                            Icon(
                                if (softWrap) Icons.AutoMirrored.Filled.WrapText else Icons.AutoMirrored.Filled.FormatAlignLeft, 
                                contentDescription = stringResource(R.string.console_cd_toggle_wrap), 
                                tint = if (softWrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            ) 
                        }
                        IconButton(onClick = {
                            // The scrollback is capped at 200 KB, under the Binder transaction limit.
                            try {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("TailSocks Console", outputText))
                                Toast.makeText(context, context.getString(R.string.console_copied), Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                            }
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.console_cd_copy)) }
                        IconButton(onClick = {
                            outputText = PROMPT
                            coroutineScope.launch(Dispatchers.IO) {
                                try { if (historyFile.exists()) historyFile.delete() } catch (e: Exception) {}
                            }
                        }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.console_clear_desc), tint = MaterialTheme.colorScheme.error) }
                    }
                )
                if (isExecuting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            // One row of preset chips and one input row. The history arrows used to
            // stand in a column beside the field and made this bar almost twice as
            // tall as the field; with the keyboard up the output had a third of the
            // screen. History is now a menu on the field's leading icon, Run its
            // trailing icon.
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            TextButton(
                                onClick = { showAddPresetDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.height(32.dp)
                            ) { Text(stringResource(R.string.console_add_preset)) }
                        }
                        items(BASE_PRESETS) { preset ->
                            PresetChip(
                                command = preset,
                                onClick = { executeCmd(preset) },
                                onLongClick = {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    currentCommand = preset
                                    focusRequester.requestFocus()
                                }
                            )
                        }
                        items(customPresets) { preset ->
                            PresetChip(
                                command = preset,
                                onClick = { executeCmd(preset) },
                                onLongClick = {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    presetMenuFor = preset
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = currentCommand,
                        onValueChange = { currentCommand = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .focusRequester(focusRequester),
                        placeholder = { Text(stringResource(R.string.console_placeholder)) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { executeCmd(currentCommand) }),
                        shape = RoundedCornerShape(24.dp),
                        leadingIcon = {
                            Box {
                                IconButton(onClick = { historyMenuOpen = true }, enabled = commandHistory.isNotEmpty()) {
                                    Icon(Icons.Default.History, contentDescription = stringResource(R.string.console_cd_history))
                                }
                                DropdownMenu(expanded = historyMenuOpen, onDismissRequest = { historyMenuOpen = false }) {
                                    commandHistory.asReversed().take(HISTORY_MENU_ITEMS).forEach { past ->
                                        DropdownMenuItem(
                                            text = { Text(past, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            onClick = {
                                                historyMenuOpen = false
                                                currentCommand = past
                                                focusRequester.requestFocus()
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        trailingIcon = {
                            IconButton(onClick = { executeCmd(currentCommand) }, enabled = !isExecuting) {
                                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.console_cd_run), tint = if (isExecuting) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        val promptColor = MaterialTheme.colorScheme.primary
        val errorColor = MaterialTheme.colorScheme.error
        val dimColor = MaterialTheme.colorScheme.outline
        val defaultColor = MaterialTheme.colorScheme.onSurface
        val styled = remember(outputText, promptColor, errorColor, dimColor, defaultColor) {
            styleScrollback(outputText, promptColor, errorColor, dimColor, defaultColor)
        }
        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 4f)
                    }
                }
        ) {
            Text(
                text = styled,
                fontFamily = FontFamily.Monospace,
                fontSize = (14 * scale).sp,
                softWrap = softWrap,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!softWrap) Modifier.horizontalScroll(horizontalScrollState) else Modifier)
                    .verticalScroll(verticalScrollState)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }

    if (showAddPresetDialog) {
        // Strings resolved in the parent composition — see wrapContextWithLocale().
        val strConsoleNewPresetTitle = stringResource(R.string.console_new_preset_title)
        val strConsoleNewPresetLabel = stringResource(R.string.console_new_preset_label)
        val strActionSave = stringResource(R.string.action_save)
        val strActionCancel = stringResource(R.string.action_cancel)
        AlertDialog(
            onDismissRequest = { showAddPresetDialog = false },
            title = { Text(strConsoleNewPresetTitle) },
            text = { OutlinedTextField(value = newPresetCmd, onValueChange = { newPresetCmd = it }, label = { Text(strConsoleNewPresetLabel) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (newPresetCmd.isNotBlank()) {
                        val current = prefs.getStringSet("commands", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        current.add(newPresetCmd.trim())
                        saveCustomPresets(current)
                        newPresetCmd = ""
                    }
                    showAddPresetDialog = false
                }) { Text(strActionSave) }
            },
            dismissButton = { TextButton(onClick = { showAddPresetDialog = false }) { Text(strActionCancel) } }
        )
    }

    presetMenuFor?.let { preset ->
        val strTitle = stringResource(R.string.console_preset_dialog_title)
        val strInsert = stringResource(R.string.console_preset_insert)
        val strDelete = stringResource(R.string.action_delete)
        val strCancel = stringResource(R.string.action_cancel)
        AlertDialog(
            onDismissRequest = { presetMenuFor = null },
            title = { Text(strTitle) },
            text = { Text(preset, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = {
                    currentCommand = preset
                    presetMenuFor = null
                    focusRequester.requestFocus()
                }) { Text(strInsert) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val current = prefs.getStringSet("commands", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        current.remove(preset)
                        saveCustomPresets(current)
                        presetMenuFor = null
                    }) { Text(strDelete, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { presetMenuFor = null }) { Text(strCancel) }
                }
            }
        )
    }
}
}

/** A command shortcut: tap runs it, long press hands it to the caller. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(command: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Text(
            text = command,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}
