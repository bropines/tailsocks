package io.github.bropines.tailscaled.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.PredictiveBackContainer
import io.github.bropines.tailscaled.core.TailcatConnection
import io.github.bropines.tailscaled.core.TailcatConnections
import io.github.bropines.tailscaled.core.TailcatKey
import io.github.bropines.tailscaled.core.TailcatService
import io.github.bropines.tailscaled.core.TailcatStatus
import io.github.bropines.tailscaled.core.stateLine
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Tailcat: local ports carried to tailcat servers (see TailcatService).
 * Laid out like Serve & Funnel — a card for this device's identity, then a
 * card per connection with its switch and its actions in the open.
 *
 * The connection list, the editor's checks and the output view come from
 * PR #9 by seffs.
 */
class TailcatActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                TailcatScreen(onBack = { finish() })
            }
        }
    }
}

/** How many output lines an open card shows; the rest is behind Full output. */
private const val INLINE_OUTPUT_LINES = 12

@Composable
fun TailcatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val clipboard = LocalClipboardManager.current
    var connections by remember { mutableStateOf(if (inPreview) emptyList() else TailcatConnections.load(context)) }
    val statuses by TailcatService.statuses.collectAsState()
    var publicKey by remember { mutableStateOf(if (inPreview) null else TailcatKey.public(context)) }
    var editor by remember { mutableStateOf<TailcatConnection?>(null) }
    var deleting by remember { mutableStateOf<TailcatConnection?>(null) }
    var fullOutputFor by remember { mutableStateOf<TailcatConnection?>(null) }
    var openId by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf("") }
    var confirmNewKey by remember { mutableStateOf(false) }

    fun reload() { connections = TailcatConnections.load(context) }
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, context.getString(R.string.tailcat_copied), Toast.LENGTH_SHORT).show()
    }
    fun running(id: String) = statuses[id]?.let { it.state != "failed" } == true

    if (!inPreview) LaunchedEffect(Unit) { withContext(Dispatchers.IO) { TailcatService.refreshStatuses() } }
    // The output of the open card or the full view, read while it is on screen.
    val watched = fullOutputFor?.id ?: openId
    if (!inPreview && watched != null) LaunchedEffect(watched) {
        while (true) {
            output = withContext(Dispatchers.IO) { runCatching { Appctr.tailcatLog(watched) }.getOrDefault("") }
            delay(1000)
        }
    }

    PredictiveBackContainer(onBack = onBack, popsInAppState = false) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.tailcat_title),
                    onBack = onBack,
                    actions = {
                        if (statuses.keys.any { running(it) }) {
                            IconButton(onClick = { TailcatService.stopAll(context) }) {
                                Icon(Icons.Default.StopCircle, stringResource(R.string.tailcat_stop_all))
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    editor = TailcatConnection(name = context.getString(R.string.tailcat_name_default, connections.size + 1))
                }) { Icon(Icons.Default.Add, stringResource(R.string.tailcat_add)) }
            }
        ) { padding ->
            ReadableWidth {
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { HelpText(stringResource(R.string.tailcat_intro), modifier = Modifier.padding(horizontal = 4.dp)) }
                    item {
                        KeyCard(
                            publicKey = publicKey,
                            onCreate = { publicKey = TailcatKey.create(context) },
                            onReplace = { confirmNewKey = true },
                            onCopy = { copy(it) }
                        )
                    }
                    item { SectionHeading(stringResource(R.string.tailcat_heading_connections)) }
                    if (connections.isEmpty()) {
                        item { EmptyConnectionsCard() }
                    } else items(connections, key = { it.id }) { conn ->
                        val status = statuses[conn.id]
                        ConnectionCard(
                            conn = conn,
                            status = status,
                            on = running(conn.id),
                            open = openId == conn.id,
                            output = if (openId == conn.id) output else "",
                            onToggleOpen = { openId = if (openId == conn.id) null else conn.id; output = "" },
                            onSwitch = { on -> if (on) TailcatService.start(context, conn.id) else TailcatService.stop(context, conn.id) },
                            onEdit = { editor = conn },
                            onDelete = { deleting = conn },
                            onCopyLocal = { copy(it) },
                            onFullOutput = { fullOutputFor = conn }
                        )
                    }
                }
            }
        }
    }

    editor?.let { initial ->
        ConnectionEditorSheet(
            initial = initial,
            isNew = connections.none { it.id == initial.id },
            onDismiss = { editor = null },
            onSave = { conn ->
                editor = null
                TailcatConnections.put(context, conn)
                TailcatService.forget(conn.id)
                reload()
                // A running connection takes its new settings at once.
                if (running(conn.id)) TailcatService.restart(context, conn.id)
            }
        )
    }

    deleting?.let { conn ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.tailcat_delete_confirm, conn.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    if (running(conn.id)) TailcatService.stop(context, conn.id)
                    TailcatConnections.delete(context, conn.id)
                    TailcatService.forget(conn.id)
                    if (openId == conn.id) openId = null
                    reload()
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (confirmNewKey) {
        AlertDialog(
            onDismissRequest = { confirmNewKey = false },
            title = { Text(stringResource(R.string.tailcat_client_key_replace)) },
            text = { Text(stringResource(R.string.tailcat_client_key_replace_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmNewKey = false; publicKey = TailcatKey.create(context) }) {
                    Text(stringResource(R.string.tailcat_client_key_replace))
                }
            },
            dismissButton = { TextButton(onClick = { confirmNewKey = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    fullOutputFor?.let { conn ->
        AlertDialog(
            onDismissRequest = { fullOutputFor = null },
            title = { Text(conn.name) },
            text = {
                SelectionContainer {
                    Text(
                        output.ifEmpty { stringResource(R.string.tailcat_no_output) },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = { TextButton(onClick = { copy(output) }) { Text(stringResource(R.string.tailcat_copy_all)) } },
            dismissButton = { TextButton(onClick = { fullOutputFor = null }) { Text(stringResource(R.string.action_close)) } }
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

/** This device's identity: the key a server lists in --allow, or a way to make one. */
@Composable
private fun KeyCard(publicKey: String?, onCreate: () -> Unit, onReplace: () -> Unit, onCopy: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(Icons.Default.Key, scheme.secondaryContainer, scheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.tailcat_client_key), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (publicKey != null) TextButton(onClick = onReplace) { Text(stringResource(R.string.tailcat_client_key_replace)) }
            }
            if (publicKey == null) {
                Text(stringResource(R.string.tailcat_client_key_none), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                HelpText(stringResource(R.string.tailcat_client_key_desc))
                FilledTonalButton(onClick = onCreate) { Text(stringResource(R.string.tailcat_client_key_create)) }
            } else {
                SelectionContainer {
                    Text(
                        publicKey,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HelpText(stringResource(R.string.tailcat_client_key_desc))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    OutlinedButton(onClick = { onCopy(publicKey) }) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.tailcat_copy_key))
                    }
                    OutlinedButton(onClick = { onCopy("tailcat serve --allow=$publicKey <port>") }) {
                        Icon(Icons.Default.Terminal, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.tailcat_copy_command))
                    }
                }
            }
        }
    }
}

@Composable
private fun IconBox(icon: ImageVector, container: Color, tint: Color, shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium) {
    Box(Modifier.size(36.dp).clip(shape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = tint)
    }
}

@Composable
private fun EmptyConnectionsCard() {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Pets, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.tailcat_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.tailcat_empty_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** A small label: a port mapping, how many connections are open. */
@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = container) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = content, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

@Composable
private fun CardAction(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** "8080" or "18080 → 8080", the way a mapping reads on a card. */
private fun mappingLabel(spec: String): String {
    val local = spec.substringBefore(':', "")
    val remote = spec.substringAfter(':', spec)
    return if (local.isEmpty() || local == remote) remote else "${if (local == "0") "auto" else local} → $remote"
}

@Composable
private fun ConnectionCard(
    conn: TailcatConnection,
    status: TailcatStatus?,
    on: Boolean,
    open: Boolean,
    output: String,
    onToggleOpen: () -> Unit,
    onSwitch: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopyLocal: (String) -> Unit,
    onFullOutput: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val failed = status?.state == "failed" || status?.state == "error"
    val forwarding = status?.state == "forwarding"
    // Open connections, shown once one has lasted a second: a request that
    // comes and goes in milliseconds would only make the tag blink.
    val active = status?.active ?: 0
    var shownActive by remember { mutableStateOf(0) }
    LaunchedEffect(active) {
        if (active > 0 && shownActive == 0) delay(1000)
        shownActive = active
    }
    val (container, tint) = when {
        failed -> scheme.errorContainer to scheme.onErrorContainer
        forwarding -> scheme.primaryContainer to scheme.onPrimaryContainer
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    val line = if (status != null) stateLine(context, status) else stringResource(R.string.tailcat_state_stopped)
    // What an app is pointed at: the first port this connection listens on.
    val local = status?.listening?.firstOrNull()
        ?: TailcatConnections.localPorts(conn.ports).firstOrNull()?.let { "127.0.0.1:$it" }
    Card(
        onClick = onToggleOpen,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = if (on) scheme.surfaceContainerHigh else scheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(Icons.Default.Pets, container, tint, if (forwarding) CircleShape else MaterialTheme.shapes.medium)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(conn.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(
                    checked = on,
                    onCheckedChange = onSwitch,
                    modifier = Modifier.padding(end = 6.dp).semantics {
                        contentDescription = context.getString(if (on) R.string.tailcat_cd_stop else R.string.tailcat_cd_start, conn.name)
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.padding(end = 10.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TailcatConnections.specs(conn.ports).forEach { Tag(mappingLabel(it), scheme.surfaceVariant, scheme.onSurfaceVariant) }
                if (shownActive > 0) Tag(context.getString(R.string.tailcat_active, shownActive), scheme.secondaryContainer, scheme.onSecondaryContainer)
            }
            if (open) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = scheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 10.dp)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        val lines = output.lines().filter { it.isNotBlank() }
                        Text(
                            if (lines.isEmpty()) stringResource(R.string.tailcat_no_output) else lines.takeLast(INLINE_OUTPUT_LINES).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = scheme.onSurfaceVariant
                        )
                        if (lines.size > INLINE_OUTPUT_LINES) {
                            TextButton(onClick = onFullOutput, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.tailcat_full_output)) }
                        }
                    }
                }
            }
            // Actions in the open, as on Serve's cards: delete on the far left,
            // away from the everyday ones on the right.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CardAction(Icons.Default.Delete, stringResource(R.string.action_delete), tint = scheme.error, onClick = onDelete)
                Spacer(Modifier.weight(1f))
                CardAction(Icons.Default.Terminal, stringResource(R.string.tailcat_full_output), onClick = onFullOutput)
                if (local != null) CardAction(Icons.Default.ContentCopy, stringResource(R.string.tailcat_copy_local), onClick = { onCopyLocal("http://$local") })
                CardAction(Icons.Default.Edit, stringResource(R.string.action_edit), onClick = onEdit)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionEditorSheet(
    initial: TailcatConnection,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (TailcatConnection) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial.name) }
    var address by remember { mutableStateOf(initial.address) }
    var ports by remember { mutableStateOf(initial.ports) }
    // Hidden by default: knowing the address is what lets a client in.
    var showAddress by remember { mutableStateOf(isNew) }

    val addressError = remember(address) {
        if (address.isBlank()) null
        else runCatching { Appctr.tailcatCheckAddress(address.trim()) }.getOrDefault("").ifEmpty { null }
    }
    val portsError = remember(ports) {
        if (ports.isBlank()) null
        else runCatching { Appctr.tailcatCheckMappings(ports) }.getOrDefault("").ifEmpty { null }
    }
    val clash = remember(ports) { TailcatConnections.clashes(context, ports, except = initial.id).firstOrNull() }
    val canSave = name.isNotBlank() && address.isNotBlank() && ports.isNotBlank() && addressError == null && portsError == null && clash == null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(if (isNew) R.string.tailcat_new else R.string.tailcat_edit), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.tailcat_name)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                label = { Text(stringResource(R.string.tailcat_address)) },
                placeholder = { Text(stringResource(R.string.tailcat_address_hint)) },
                singleLine = true,
                visualTransformation = if (showAddress) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showAddress = !showAddress }) {
                        Icon(
                            if (showAddress) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(if (showAddress) R.string.tailcat_hide_address else R.string.tailcat_show_address)
                        )
                    }
                },
                isError = addressError != null,
                supportingText = addressError?.let { { Text(stringResource(R.string.tailcat_err_address)) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = ports,
                onValueChange = { ports = it },
                label = { Text(stringResource(R.string.tailcat_ports)) },
                placeholder = { Text("8080, 18080:80") },
                singleLine = true,
                isError = portsError != null || clash != null,
                supportingText = {
                    Text(
                        when {
                            portsError != null -> stringResource(R.string.tailcat_err_ports, portsError)
                            clash != null -> stringResource(R.string.tailcat_err_port_clash, clash.first, clash.second.name)
                            else -> stringResource(R.string.tailcat_ports_hint)
                        }
                    )
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(initial.copy(name = name.trim(), address = address.trim(), ports = ports.trim())) },
                    enabled = canSave
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}
