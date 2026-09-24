package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.TailcatConnection
import io.github.bropines.tailscaled.core.TailcatConnections
import io.github.bropines.tailscaled.core.TailcatService
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TailcatActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TailSocksTheme { TailcatScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailcatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val statuses by TailcatService.statuses.collectAsState()
    val logs by TailcatService.logs.collectAsState()

    var connections by remember { mutableStateOf(TailcatConnections.load(context)) }
    var clientKey by remember { mutableStateOf(GlobalSettings.getString(context, TailcatService.PREF_CLIENT_KEY, "")) }
    var generating by remember { mutableStateOf(false) }
    // Which cards are open, by connection id; survives rotation.
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    // The dialog: null when closed, a blank connection for "Add", the connection for "Edit".
    var editing by remember { mutableStateOf<TailcatConnection?>(null) }
    var deleting by remember { mutableStateOf<TailcatConnection?>(null) }
    var viewing by remember { mutableStateOf<TailcatConnection?>(null) }

    fun isRunning(id: String) = statuses[id]?.state == TailcatService.State.RUNNING

    fun copy(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, R.string.tailcat_copied, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tailcat_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editing = TailcatConnection(name = context.getString(R.string.tailcat_name_default, connections.size + 1))
                },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.tailcat_add)) }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HelpText(stringResource(R.string.tailcat_intro), lines = 3)

            if (connections.isEmpty()) {
                Text(
                    stringResource(R.string.tailcat_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            connections.forEach { conn ->
                TailcatConnectionCard(
                    connection = conn,
                    status = statuses[conn.id] ?: TailcatService.Status(),
                    log = logs[conn.id].orEmpty(),
                    expanded = conn.id in expanded,
                    onToggleExpanded = { expanded = if (conn.id in expanded) expanded - conn.id else expanded + conn.id },
                    onRunningChange = { run ->
                        if (run) TailcatService.start(context, conn.id) else TailcatService.stop(context, conn.id)
                    },
                    onFullOutput = { viewing = conn },
                    onEdit = { editing = conn },
                    onDelete = { deleting = conn }
                )
            }

            ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.tailcat_client_key), style = MaterialTheme.typography.titleSmall)
                    HelpText(stringResource(R.string.tailcat_client_key_desc))
                    if (clientKey.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                clientKey,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { copy("tailcat client key", clientKey) }) {
                                Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy))
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                generating = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { TailcatService.generateClientKey(context) }
                                    generating = false
                                    result.onSuccess { clientKey = it }
                                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                }
                            },
                            enabled = !generating,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.tailcat_client_key_create))
                        }
                    }
                }
            }

            // Room to scroll the last card out from under the button.
            Spacer(Modifier.height(72.dp))
        }
    }

    editing?.let { initial ->
        TailcatConnectionDialog(
            initial = initial,
            isNew = connections.none { it.id == initial.id },
            onDismiss = { editing = null },
            onSave = { saved ->
                val updated = if (connections.any { it.id == saved.id }) connections.map { if (it.id == saved.id) saved else it }
                              else connections + saved
                TailcatConnections.save(context, updated)
                connections = updated
                editing = null
                // A running connection takes its new settings at once.
                if (isRunning(saved.id)) TailcatService.restart(context, saved.id)
            }
        )
    }

    viewing?.let { conn ->
        val lines = logs[conn.id].orEmpty()
        TailcatOutputDialog(
            name = conn.name,
            log = lines,
            onCopy = { copy("tailcat output", lines.joinToString("\n")) },
            onDismiss = { viewing = null }
        )
    }

    deleting?.let { conn ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.tailcat_delete_confirm, conn.name)) },
            confirmButton = {
                TextButton(onClick = {
                    if (isRunning(conn.id)) TailcatService.stop(context, conn.id)
                    val updated = connections.filter { it.id != conn.id }
                    TailcatConnections.save(context, updated)
                    connections = updated
                    TailcatService.forget(conn.id)
                    deleting = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun TailcatConnectionCard(
    connection: TailcatConnection,
    status: TailcatService.Status,
    log: List<String>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRunningChange: (Boolean) -> Unit,
    onFullOutput: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = status.state == TailcatService.State.RUNNING
    val statusText = when (status.state) {
        TailcatService.State.RUNNING -> stringResource(R.string.tailcat_status_running, status.detail)
        TailcatService.State.EXITED -> status.detail
        TailcatService.State.STOPPED -> stringResource(R.string.tailcat_status_stopped, TailcatConnections.summary(connection.ports))
    }
    val statusColor = when (status.state) {
        TailcatService.State.RUNNING -> MaterialTheme.colorScheme.primary
        TailcatService.State.EXITED -> MaterialTheme.colorScheme.error
        TailcatService.State.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    var menuOpen by remember { mutableStateOf(false) }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column {
            // The header toggles the output; everything else about the
            // connection is in the menu, reachable with the card closed.
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        connection.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                }
                Switch(checked = running, onCheckedChange = onRunningChange)
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.tailcat_more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tailcat_full_output)) },
                            leadingIcon = { Icon(Icons.Default.OpenInFull, null) },
                            onClick = { menuOpen = false; onFullOutput() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuOpen = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.tailcat_output),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onFullOutput) { Icon(Icons.Default.OpenInFull, stringResource(R.string.tailcat_full_output)) }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp)
                    ) {
                        // Capped, and scrolling inside, so a long run of output
                        // never pushes the cards below out of reach.
                        TailcatOutput(log, Modifier.heightIn(max = 240.dp))
                    }
                }
            }
        }
    }
}

/**
 * A connection's output, newest at the bottom. It stays on the newest line as
 * output arrives, like a terminal, until scrolled up; scrolling back to the
 * end resumes that.
 */
@Composable
private fun TailcatOutput(log: List<String>, modifier: Modifier = Modifier, selectable: Boolean = false) {
    val scroll = rememberScrollState()
    var follow by remember { mutableStateOf(true) }
    // Only a scroll by the user decides whether to follow: when it ends, follow
    // if it ended at the bottom. A scroll made here ends there, so it keeps it.
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = scroll.maxValue - scroll.value < 24
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.collect { max -> if (follow) scroll.scrollTo(max) }
    }
    val text: @Composable () -> Unit = {
        Text(
            if (log.isEmpty()) stringResource(R.string.tailcat_no_output) else log.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
    Box(modifier.verticalScroll(scroll)) {
        if (selectable) SelectionContainer { text() } else text()
    }
}

/** The whole of a connection's output, full screen, selectable, with Copy all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TailcatOutputDialog(name: String, log: List<String>, onCopy: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) } },
                    actions = {
                        IconButton(onClick = onCopy, enabled = log.isNotEmpty()) {
                            Icon(Icons.Default.ContentCopy, stringResource(R.string.tailcat_copy_all))
                        }
                    }
                )
            }
        ) { padding ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.padding(padding).fillMaxSize()) {
                TailcatOutput(log, Modifier.fillMaxSize(), selectable = true)
            }
        }
    }
}

@Composable
private fun TailcatConnectionDialog(
    initial: TailcatConnection,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (TailcatConnection) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    var address by remember { mutableStateOf(initial.address) }
    var ports by remember { mutableStateOf(initial.ports) }
    // The address is what lets a client in: hidden unless asked for.
    var showAddress by remember { mutableStateOf(false) }

    val addressValid = TailcatConnections.isValidAddress(address)
    val portsValid = TailcatConnections.parsePorts(ports) != null
    val clash = remember(ports) {
        if (portsValid) TailcatConnections.clashes(context, ports, except = initial.id).firstOrNull() else null
    }
    val canSave = name.isNotBlank() && addressValid && portsValid && clash == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.tailcat_add else R.string.tailcat_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.tailcat_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.tailcat_address)) },
                    placeholder = { Text("tc…") },
                    singleLine = true,
                    isError = address.isNotEmpty() && !addressValid,
                    visualTransformation = if (showAddress) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showAddress = !showAddress }) {
                            Icon(if (showAddress) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ports,
                    onValueChange = { ports = it },
                    label = { Text(stringResource(R.string.tailcat_ports)) },
                    placeholder = { Text("8080") },
                    supportingText = {
                        Text(
                            if (clash != null) stringResource(R.string.tailcat_err_port_clash, clash.first, clash.second.name)
                            else stringResource(R.string.tailcat_ports_hint)
                        )
                    },
                    singleLine = true,
                    isError = (ports.isNotEmpty() && !portsValid) || clash != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(name = name.trim(), address = address.trim(), ports = ports.trim())) },
                enabled = canSave
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
