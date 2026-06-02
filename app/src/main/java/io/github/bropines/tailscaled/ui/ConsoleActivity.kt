package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig
import androidx.compose.ui.res.stringResource

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import appctr.Appctr
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ConsoleActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ФИКС КЛАВИАТУРЫ: Говорим Android, что Compose сам разберется с отступами
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
    val horizontalScrollState = rememberScrollState() // Для горизонтального скролла
    val focusRequester = remember { FocusRequester() }

    val prefs = remember { context.getSharedPreferences("console_presets", Context.MODE_PRIVATE) }
    val historyFile = remember { File(context.filesDir, "console_history.dat") }
    val cmdHistoryFile = remember { File(context.filesDir, "console_cmd_history.dat") }

    var outputText by remember { mutableStateOf("$ ") }
    var currentCommand by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }

    // ЗУМ
    var scale by remember { mutableFloatStateOf(1f) }
    var softWrap by remember { mutableStateOf(false) }

    val commandHistory = remember { mutableStateListOf<String>() }
    var historyPointer by remember { mutableStateOf(-1) }

    var customPresets by remember { 
        mutableStateOf(prefs.getStringSet("commands", emptySet<String>())?.toList()?.sorted() ?: emptyList<String>()) 
    }
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var newPresetCmd by remember { mutableStateOf("") }

    fun saveCommandHistory() {
        try { cmdHistoryFile.writeText(commandHistory.joinToString("\n")) } catch (e: Exception) {}
    }

    LaunchedEffect(Unit) {
        if (historyFile.exists()) {
            try { outputText = historyFile.readText() } catch (e: Exception) {}
            verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
        }
        if (cmdHistoryFile.exists()) {
            try {
                val lines = cmdHistoryFile.readLines()
                commandHistory.addAll(lines)
            } catch (e: Exception) {}
        }
        if (initialCmd.isNotEmpty()) currentCommand = initialCmd
        focusRequester.requestFocus()
    }

    DisposableEffect(Unit) {
        onDispose {
            try { historyFile.writeText(outputText) } catch (e: Exception) {}
            saveCommandHistory()
        }
    }

    fun executeCmd(cmd: String) {
        if (cmd.isBlank()) return
        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
            commandHistory.add(cmd)
            saveCommandHistory()
        }
        historyPointer = -1
        isExecuting = true
        
        val isLocalAPI = cmd.startsWith("/")
        if (isLocalAPI) {
            outputText += "\n$ LocalAPI $cmd"
        } else {
            outputText += "\n$ tailscale $cmd"
        }
        
        coroutineScope.launch(Dispatchers.IO) {
            val result = try { 
                if (isLocalAPI) {
                    // Парсим команду вида "/GET /localapi/v0/status [body]"
                    val parts = cmd.trim().split(" ", limit = 3)
                    val method = parts[0].removePrefix("/").uppercase()
                    val path = if (parts.size > 1) parts[1] else "/"
                    val body = if (parts.size > 2) parts[2] else ""
                    Appctr.doLocalAPIRequest(method, path, body)
                } else {
                    Appctr.runTailscaleCmd(cmd) 
                }
            } catch (e: Exception) { "Error: ${e.message}" }
            
            withContext(Dispatchers.Main) {
                outputText += "\n$result\n$ "
                isExecuting = false
                currentCommand = ""
                verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
                focusRequester.requestFocus()
            }
        }
    }

    Scaffold(
        // ФИКС КЛАВИАТУРЫ: клавиатура (imePadding)
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
                                if (softWrap) Icons.Default.WrapText else Icons.Default.FormatAlignLeft, 
                                contentDescription = stringResource(R.string.console_cd_toggle_wrap), 
                                tint = if (softWrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            ) 
                        }
                        IconButton(onClick = {
                            outputText = "$ "
                            if (historyFile.exists()) historyFile.delete()
                        }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.console_clear_desc), tint = MaterialTheme.colorScheme.error) }
                    }
                )
                if (isExecuting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        item { TextButton(onClick = { showAddPresetDialog = true }) { Text(stringResource(R.string.console_add_preset)) } }
                        val basePresets = listOf("status", "/GET /localapi/v0/status", "/GET /localapi/v0/prefs", "netcheck", "ping 8.8.8.8")
                        items(basePresets + customPresets) { preset ->
                            Surface(
                                shape = ButtonDefaults.elevatedShape,
                                color = ButtonDefaults.elevatedButtonColors().containerColor,
                                shadowElevation = 2.dp,
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .combinedClickable(
                                        onClick = { executeCmd(preset) },
                                        onLongClick = {
                                            currentCommand = preset
                                            focusRequester.requestFocus()
                                        }
                                    )
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = preset,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = ButtonDefaults.elevatedButtonColors().contentColor
                                    )
                                }
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            IconButton(onClick = {
                                if (commandHistory.isNotEmpty()) {
                                    if (historyPointer == -1) historyPointer = commandHistory.size - 1
                                    else if (historyPointer > 0) historyPointer--
                                    currentCommand = commandHistory[historyPointer]
                                }
                            }) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.console_cd_history_up)) }
                            IconButton(onClick = {
                                if (commandHistory.isNotEmpty() && historyPointer != -1) {
                                    if (historyPointer < commandHistory.size - 1) {
                                        historyPointer++
                                        currentCommand = commandHistory[historyPointer]
                                    } else {
                                        historyPointer = -1
                                        currentCommand = ""
                                    }
                                }
                            }) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.console_cd_history_down)) }
                        }
                        OutlinedTextField(
                            value = currentCommand,
                            onValueChange = { currentCommand = it },
                            modifier = Modifier.weight(1f).focusRequester(focusRequester),
                            placeholder = { Text(stringResource(R.string.console_placeholder)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { executeCmd(currentCommand) }),
                            shape = RoundedCornerShape(24.dp)
                        )
                        IconButton(onClick = { executeCmd(currentCommand) }) { Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.console_cd_run), tint = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    ) { padding ->
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
                text = outputText,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = (14 * scale).sp,
                softWrap = softWrap,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!softWrap) Modifier.horizontalScroll(horizontalScrollState) else Modifier)
                    .verticalScroll(verticalScrollState)
                    .padding(16.dp)
            )
        }
    }

    if (showAddPresetDialog) {
        AlertDialog(
            onDismissRequest = { showAddPresetDialog = false },
            title = { Text(stringResource(R.string.console_new_preset_title)) },
            text = { OutlinedTextField(value = newPresetCmd, onValueChange = { newPresetCmd = it }, label = { Text(stringResource(R.string.console_new_preset_label)) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (newPresetCmd.isNotBlank()) {
                        val current = prefs.getStringSet("commands", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        current.add(newPresetCmd.trim())
                        prefs.edit().putStringSet("commands", current).apply()
                        customPresets = current.toList().sorted()
                        newPresetCmd = ""
                    }
                    showAddPresetDialog = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { showAddPresetDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}