package io.github.asutorufa.tailscaled

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ConsoleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ФИКС КЛАВИАТУРЫ: Говорим Android, что Compose сам разберется с отступами
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val initialCmd = intent?.getStringExtra("CMD") ?: ""
        
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                ConsoleScreen(initialCmd = initialCmd, onBack = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(initialCmd: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState() // Для горизонтального скролла
    val focusRequester = remember { FocusRequester() }

    val prefs = remember { context.getSharedPreferences("console_presets", Context.MODE_PRIVATE) }
    val historyFile = remember { File(context.filesDir, "console_history.dat") }

    var outputText by remember { mutableStateOf("$ ") }
    var currentCommand by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }

    // ЗУМ
    var scale by remember { mutableFloatStateOf(1f) }

    val commandHistory = remember { mutableStateListOf<String>() }
    var historyPointer by remember { mutableStateOf(-1) }

    var customPresets by remember { 
        mutableStateOf(prefs.getStringSet("commands", emptySet<String>())?.toList()?.sorted() ?: emptyList<String>()) 
    }
    var showAddPresetDialog by remember { mutableStateOf(false) }
    var newPresetCmd by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (historyFile.exists()) {
            try { outputText = historyFile.readText() } catch (e: Exception) {}
            verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
        }
        if (initialCmd.isNotEmpty()) currentCommand = initialCmd
        focusRequester.requestFocus()
    }

    DisposableEffect(Unit) {
        onDispose {
            try { historyFile.writeText(outputText) } catch (e: Exception) {}
        }
    }

    fun executeCmd(cmd: String) {
        if (cmd.isBlank()) return
        if (commandHistory.isEmpty() || commandHistory.last() != cmd) commandHistory.add(cmd)
        historyPointer = -1
        isExecuting = true
        outputText += "\n$ tailscale $cmd"
        
        coroutineScope.launch(Dispatchers.IO) {
            val result = try { Appctr.runTailscaleCmd(cmd) } catch (e: Exception) { "Error: ${e.message}" }
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
        // ФИКС КЛАВИАТУРЫ: системные бары и клавиатура (imePadding)
        modifier = Modifier
            .systemBarsPadding()
            .imePadding(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Tailscale Terminal") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                )
                if (isExecuting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                Column {
                    LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        item { TextButton(onClick = { showAddPresetDialog = true }) { Text("+ Add") } }
                        val basePresets = listOf("status", "netcheck", "ping 8.8.8.8")
                        items(basePresets + customPresets) { preset ->
                            ElevatedButton(onClick = { executeCmd(preset) }, modifier = Modifier.padding(horizontal = 4.dp)) { Text(preset) }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            if (commandHistory.isNotEmpty()) {
                                if (historyPointer == -1) historyPointer = commandHistory.size - 1
                                else if (historyPointer > 0) historyPointer--
                                currentCommand = commandHistory[historyPointer]
                            }
                        }) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "History Up") }
                        OutlinedTextField(
                            value = currentCommand,
                            onValueChange = { currentCommand = it },
                            modifier = Modifier.weight(1f).focusRequester(focusRequester),
                            placeholder = { Text("Command...") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { executeCmd(currentCommand) }),
                            shape = RoundedCornerShape(24.dp)
                        )
                        IconButton(onClick = { executeCmd(currentCommand) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = MaterialTheme.colorScheme.primary) }
                        IconButton(onClick = {
                            outputText = "$ "
                            if (historyFile.exists()) historyFile.delete()
                        }) { Icon(Icons.Default.Delete, contentDescription = "Clear", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    ) { padding ->
        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF1C1B1F))
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 4f)
                    }
                }
        ) {
            Text(
                text = outputText,
                color = Color(0xFFE6E1E5),
                fontFamily = FontFamily.Monospace,
                fontSize = (14 * scale).sp,
                softWrap = false, // <-- ФИКС ТЕРМИНАЛА: Отключаем перенос строк
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState) // <-- Добавляем скролл вбок
                    .verticalScroll(verticalScrollState)
                    .padding(16.dp)
            )
        }
    }

    if (showAddPresetDialog) {
        AlertDialog(
            onDismissRequest = { showAddPresetDialog = false },
            title = { Text("New Preset") },
            text = { OutlinedTextField(value = newPresetCmd, onValueChange = { newPresetCmd = it }, label = { Text("e.g. up --ssh") }, singleLine = true) },
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
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddPresetDialog = false }) { Text("Cancel") } }
        )
    }
}