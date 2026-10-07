package io.github.bropines.tailscaled.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
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
import io.github.bropines.tailscaled.core.TailcatImport
import io.github.bropines.tailscaled.core.TailcatKey
import io.github.bropines.tailscaled.core.TailcatServer
import io.github.bropines.tailscaled.core.TailcatServerConfig
import io.github.bropines.tailscaled.core.TailcatServerStatus
import io.github.bropines.tailscaled.core.TailcatService
import io.github.bropines.tailscaled.core.TailcatStatus
import io.github.bropines.tailscaled.core.serverStateLine
import io.github.bropines.tailscaled.core.stateLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many output lines an open card shows; the rest is in Logs, under TAILCAT. */
private const val INLINE_OUTPUT_LINES = 12

/**
 * Tailcat: local ports and a SOCKS5 proxy carried to tailcat servers (see
 * TailcatService), the second side of the Serve tile (ServeHost). Laid out
 * like Serve & Funnel — a card for this device's identity, then a card per
 * connection with its switch and its actions in the open.
 *
 * The connection list, the editor's checks and the output view come from
 * PR #9 by seffs.
 */
@Composable
fun TailcatScreen(onBack: () -> Unit, page: ServePage? = null, importText: String? = null) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    // The preview renderer has no storage and no bridge: a demo, when there is one, stands in.
    val demo = LocalDemo.current?.tailcat
    val clipboard = LocalClipboard.current
    var connections by remember { mutableStateOf(if (inPreview) demo?.connections.orEmpty() else TailcatConnections.load(context)) }
    val liveStatuses by TailcatService.statuses.collectAsState()
    val statuses = demo?.statuses ?: liveStatuses
    var publicKey by remember { mutableStateOf(if (inPreview) demo?.publicKey else TailcatKey.public(context)) }
    var editor by remember { mutableStateOf<TailcatConnection?>(null) }
    var deleting by remember { mutableStateOf<TailcatConnection?>(null) }
    var openId by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf("") }
    var confirmNewKey by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val liveServerStatus by TailcatService.serverStatus.collectAsState()
    val serverStatus = if (demo != null) demo.serverStatus else liveServerStatus
    var serverAddress by remember { mutableStateOf(if (inPreview) demo?.serverAddress else TailcatServer.address(context)) }
    var serverConfig by remember { mutableStateOf(if (inPreview) demo?.serverConfig ?: TailcatServerConfig() else TailcatServer.load(context)) }
    var creatingServer by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf(false) }
    var confirmNewAddress by remember { mutableStateOf(false) }
    /** The connection whose QR code is shown, or the server's. */
    var qrConnection by remember { mutableStateOf<TailcatConnection?>(null) }
    var serverQr by remember { mutableStateOf(false) }
    val serverOn = serverStatus?.let { it.state != "failed" } == true

    fun reload() { connections = TailcatConnections.load(context) }
    fun copy(text: String) {
        clipboard.copyText(scope, text)
        Toast.makeText(context, context.getString(R.string.tailcat_copied), Toast.LENGTH_SHORT).show()
    }
    fun running(id: String) = statuses[id]?.let { it.state != "failed" } == true
    fun openLogs() = context.startActivity(LogsActivity.intent(context, TAILCAT_CATEGORY))
    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.tailcat_share_address)))
    }
    /** Makes the server's identity, or a new one; a running server moves to it. */
    fun createServerAddress() {
        creatingServer = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { TailcatServer.create(context) } }
            creatingServer = false
            result.onSuccess { addr ->
                serverAddress = addr
                TailcatService.forgetServerFailure()
                if (serverOn) TailcatService.startServer(context)
            }.onFailure { e ->
                Toast.makeText(context, context.getString(R.string.tailcat_server_create_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
    fun openInBrowser(address: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$address"))) }
            .onFailure { Toast.makeText(context, context.getString(R.string.tailcat_no_browser), Toast.LENGTH_SHORT).show() }
    }

    if (!inPreview) LaunchedEffect(Unit) { withContext(Dispatchers.IO) { TailcatService.refreshStatuses() } }
    /** A new connection's editor, filled in from an address or connect command; false when [text] holds none. */
    fun importConnection(text: String): Boolean {
        val parsed = TailcatConnections.parseImport(text) ?: return false
        editor = TailcatConnection(
            name = context.getString(R.string.tailcat_name_default, connections.size + 1),
            address = parsed.address,
            ports = parsed.ports,
            socks = parsed.socks ?: 0
        )
        return true
    }
    // A code or link that opened nothing is shown as it is; [scannedHelp] says
    // why when it was a tailcat/add link whose text holds no address.
    var scannedText by remember { mutableStateOf<String?>(null) }
    var scannedHelp by remember { mutableStateOf<String?>(null) }
    /** Opens the editor from a tailcat/add link's text, or shows that text when it holds no address. */
    fun importLink(text: String) {
        if (importConnection(text)) return
        if (text.isBlank()) {
            Toast.makeText(context, context.getString(R.string.tailcat_import_none_link), Toast.LENGTH_SHORT).show()
        } else {
            scannedText = text
            scannedHelp = context.getString(R.string.tailcat_import_no_address_help)
        }
    }
    // A tailsocks://tailcat/add link: a new connection's editor, filled in.
    if (!inPreview && importText != null) LaunchedEffect(importText) { importLink(importText) }
    // A scanned code: a TailCat address, or a tailcat/add link, opens the editor
    // here; another app link opens its screen; any other text is only shown.
    val scanQr = rememberQrScanner { text ->
        when (val code = ScannedCode.of(context, text)) {
            is ScannedCode.AppLink -> {
                val add = DeepLinks.tailcatImportText(code.uri)
                if (add == null) DeepLinks.open(context, code.uri) else importLink(add)
            }
            is ScannedCode.Tailcat -> importConnection(code.text)
            is ScannedCode.Text -> {
                scannedText = code.text
                scannedHelp = null
            }
        }
    }
    // The output of the open card, read while it is on screen.
    val watched = openId
    if (!inPreview && watched != null) LaunchedEffect(watched) {
        while (true) {
            output = withContext(Dispatchers.IO) { runCatching { Appctr.tailcatLog(watched) }.getOrDefault("") }
            delay(1000)
        }
    }

    val actions: @Composable RowScope.() -> Unit = {
        IconButton(onClick = scanQr) {
            Icon(Icons.Default.QrCodeScanner, stringResource(R.string.qr_scan_action))
        }
        if (statuses.keys.any { running(it) } || serverOn) {
            IconButton(onClick = { TailcatService.stopAll(context); serverConfig = TailcatServer.load(context) }) {
                Icon(Icons.Default.StopCircle, stringResource(R.string.tailcat_stop_all))
            }
        }
    }
    if (page != null) SideEffect { page.actions.value = actions }

    // The screen's pieces, the same in the phone's list and in a large window's columns.
    val intro: @Composable () -> Unit = {
        HelpText(stringResource(R.string.tailcat_intro), modifier = Modifier.padding(horizontal = 4.dp))
    }
    val keyCard: @Composable () -> Unit = {
        KeyCard(
            publicKey = publicKey,
            onCreate = { publicKey = TailcatKey.create(context) },
            onReplace = { confirmNewKey = true },
            onCopy = { copy(it) }
        )
    }
    val connectionCard: @Composable (TailcatConnection) -> Unit = { conn ->
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
            onCopy = { copy(it) },
            onOpen = { openInBrowser(it) },
            onQr = { qrConnection = conn },
            onLogs = { openLogs() }
        )
    }
    val serverCard: @Composable () -> Unit = {
        ServerCard(
            address = serverAddress,
            config = serverConfig,
            status = serverStatus,
            on = serverOn,
            creating = creatingServer,
            onCreate = { createServerAddress() },
            onNewAddress = { confirmNewAddress = true },
            onSwitch = { on ->
                if (on) TailcatService.startServer(context) else TailcatService.stopServer(context)
                serverConfig = TailcatServer.load(context)
            },
            onEdit = { editingServer = true },
            onCopy = { copy(it) },
            onShare = { share(it) },
            onQr = { serverQr = true },
            onLogs = { openLogs() }
        )
    }
    // A large window: this device's own side (its key, its server) in a column of its own,
    // the connections beside it in as many columns as fit; a medium one keeps the phone's
    // order with the connections in columns. A phone keeps its list, exactly as it was.
    val window = rememberWindowLayout()

    val scaffold: @Composable () -> Unit = {
        Scaffold(
            topBar = { if (page == null) AppTopBar(title = stringResource(R.string.tailcat_title), onBack = onBack, actions = actions) },
            // As a page the host's Scaffold has taken the system bars already.
            contentWindowInsets = if (page != null) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            floatingActionButton = {
                TailcatAddButton(onAdd = {
                    editor = TailcatConnection(name = context.getString(R.string.tailcat_name_default, connections.size + 1))
                })
            }
        ) { padding ->
            val line = StaggeredGridItemSpan.FullLine
            when {
                window.listDetail -> SideBySide(window, Modifier.padding(padding).fillMaxSize(), start = { side ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = side.with(top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { intro() }
                        item { keyCard() }
                        item { SectionHeading(stringResource(R.string.tailcat_server_heading)) }
                        item { serverCard() }
                    }
                }, end = { side ->
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Adaptive(320.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = side.with(top = 8.dp, bottom = 96.dp),
                        verticalItemSpacing = 12.dp,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item(span = line) { SectionHeading(stringResource(R.string.tailcat_heading_connections)) }
                        if (connections.isEmpty()) item(span = line) { EmptyConnectionsCard(onScan = scanQr) }
                        else gridItems(connections, key = { it.id }) { conn -> connectionCard(conn) }
                    }
                })
                window.multiColumn -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(320.dp),
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(start = window.margin, end = window.margin, top = 8.dp, bottom = 96.dp),
                    verticalItemSpacing = 12.dp,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(span = line) { intro() }
                    item(span = line) { keyCard() }
                    item(span = line) { SectionHeading(stringResource(R.string.tailcat_heading_connections)) }
                    if (connections.isEmpty()) item(span = line) { EmptyConnectionsCard(onScan = scanQr) }
                    else gridItems(connections, key = { it.id }) { conn -> connectionCard(conn) }
                    item(span = line) { SectionHeading(stringResource(R.string.tailcat_server_heading)) }
                    item(span = line) { serverCard() }
                }
                else -> ReadableWidth {
                    LazyColumn(
                        modifier = Modifier.padding(padding).fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { intro() }
                        item { keyCard() }
                        item { SectionHeading(stringResource(R.string.tailcat_heading_connections)) }
                        if (connections.isEmpty()) {
                            item { EmptyConnectionsCard(onScan = scanQr) }
                        } else items(connections, key = { it.id }) { conn -> connectionCard(conn) }
                        item { SectionHeading(stringResource(R.string.tailcat_server_heading)) }
                        item { serverCard() }
                    }
                }
            }
        }
    }
    if (page != null) scaffold() else PredictiveBackContainer(onBack = onBack, popsInAppState = false) { scaffold() }

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

    if (editingServer) {
        ServerEditorSheet(
            initial = serverConfig,
            onDismiss = { editingServer = false },
            onSave = { config ->
                editingServer = false
                TailcatServer.save(context, config)
                serverConfig = config
                TailcatService.forgetServerFailure()
                // A running server takes its new settings at once.
                if (serverOn) TailcatService.startServer(context)
            }
        )
    }

    // The address is in both codes and under them: the card keeps it hidden,
    // the sheet is what a tap on purpose opens.
    // A link any camera opens in TailSocks first, the command a computer runs
    // second; the app's own scanner reads either.
    qrConnection?.let { conn ->
        val command = conn.command()
        QrSheet(
            title = conn.name,
            variants = listOf(
                QrVariant(stringResource(R.string.qr_tab_link), DeepLinks.tailcatAddLink(command), stringResource(R.string.qr_tailcat_connection_link_help)),
                QrVariant(stringResource(R.string.qr_tab_command), command, stringResource(R.string.qr_tailcat_connection_help)),
            ),
            onDismiss = { qrConnection = null }
        )
    }
    scannedText?.let { ScanResultSheet(text = it, help = scannedHelp, onDismiss = { scannedText = null }) }
    val qrAddress = serverAddress
    if (serverQr && qrAddress != null) {
        val command = TailcatServer.clientCommand(qrAddress, serverConfig)
        QrSheet(
            title = stringResource(R.string.qr_tailcat_server_title),
            variants = listOf(
                QrVariant(stringResource(R.string.qr_tab_link), DeepLinks.tailcatAddLink(command), stringResource(R.string.qr_tailcat_server_link_help)),
                QrVariant(stringResource(R.string.qr_tab_command), command, stringResource(R.string.qr_tailcat_server_help)),
            ),
            onDismiss = { serverQr = false }
        )
    }

    if (confirmNewAddress) {
        AlertDialog(
            onDismissRequest = { confirmNewAddress = false },
            title = { Text(stringResource(R.string.tailcat_server_new_address)) },
            text = { Text(stringResource(R.string.tailcat_server_new_address_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmNewAddress = false; createServerAddress() }) {
                    Text(stringResource(R.string.tailcat_server_new_address))
                }
            },
            dismissButton = { TextButton(onClick = { confirmNewAddress = false }) { Text(stringResource(R.string.action_cancel)) } }
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
private fun EmptyConnectionsCard(onScan: () -> Unit) {
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
            // The QR code another phone's TailCat shows: the quickest way to a first connection.
            OutlinedButton(onClick = onScan, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.qr_scan_action))
            }
        }
    }
}

/** A small label: a port mapping, the proxy, how many connections are open; some do something on a tap. */
@Composable
private fun Tag(text: String, container: Color, content: Color, onClick: (() -> Unit)? = null) {
    val label = @Composable {
        Text(text, style = MaterialTheme.typography.labelSmall, color = content, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
    }
    if (onClick != null) Surface(onClick = onClick, shape = MaterialTheme.shapes.small, color = container) { label() }
    else Surface(shape = MaterialTheme.shapes.small, color = container) { label() }
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
    onCopy: (String) -> Unit,
    onOpen: (String) -> Unit,
    onQr: () -> Unit,
    onLogs: () -> Unit
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
    val specs = TailcatConnections.specs(conn.ports)
    // Where each mapping listens: what the bridge reports, in the same order,
    // or the port it asks for; "auto" has no port until it runs.
    fun listenAt(i: Int, spec: String): String? = status?.listening?.getOrNull(i)
        ?: (if (':' in spec) spec.substringBefore(':') else spec).toIntOrNull()?.takeIf { it > 0 }?.let { "127.0.0.1:$it" }
    // What an app is pointed at: the first port this connection listens on.
    val local = specs.indices.firstNotNullOfOrNull { listenAt(it, specs[it]) }
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
                // A tap opens the port in a browser, as `tailcat browse` does.
                specs.forEachIndexed { i, spec ->
                    val at = listenAt(i, spec)
                    Tag(mappingLabel(spec), scheme.surfaceVariant, scheme.onSurfaceVariant, onClick = at?.let { { onOpen(it) } })
                }
                // A tap copies the proxy's address with its password, for the app it goes into.
                conn.socksUrl()?.let { url ->
                    Tag(stringResource(R.string.tailcat_socks_tag, conn.socks), scheme.tertiaryContainer, scheme.onTertiaryContainer, onClick = { onCopy(url) })
                }
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
                            TextButton(onClick = onLogs, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.tailcat_full_output)) }
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
                CardAction(Icons.Default.Terminal, stringResource(R.string.tailcat_full_output), onClick = onLogs)
                if (local != null) CardAction(Icons.Default.ContentCopy, stringResource(R.string.tailcat_copy_local), onClick = { onCopy("http://$local") })
                // The connection for another device, as a server's card shares it.
                CardAction(Icons.Default.QrCode2, stringResource(R.string.qr_show), onClick = onQr)
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
    val clipboard = LocalClipboard.current
    val pasteScope = rememberCoroutineScope()
    val sheetState = rememberFullSheetState()
    var name by remember { mutableStateOf(initial.name) }
    var address by remember { mutableStateOf(initial.address) }
    var ports by remember { mutableStateOf(initial.ports) }
    var socks by remember { mutableStateOf(if (initial.socks == 0) "" else initial.socks.toString()) }
    // Hidden by default: knowing the address is what lets a client in.
    var showAddress by remember { mutableStateOf(isNew) }

    /** The connection as the fields have it, with credentials once it has a proxy. */
    fun edited(): TailcatConnection {
        val port = socks.trim().toIntOrNull() ?: if (socks.isBlank()) 0 else -1
        val withCreds = port > 0 && initial.socksUser.isEmpty()
        return initial.copy(
            name = name.trim(), address = address.trim(), ports = ports.trim(), socks = port,
            socksUser = if (withCreds) generateSecureToken(8) else initial.socksUser,
            socksPass = if (withCreds) generateSecureToken(16) else initial.socksPass
        )
    }
    // Checked as the service checks before a start, against every other
    // connection's ports, running or not.
    val problem = remember(address, ports, socks) {
        if (address.isBlank() || (ports.isBlank() && socks.isBlank())) null
        else TailcatConnections.problem(context, edited())
    }
    val addressError = problem is TailcatConnections.Problem.Address
    val portsError = (problem as? TailcatConnections.Problem.Ports)?.detail
    val socksError = problem == TailcatConnections.Problem.Socks
    val clash = problem as? TailcatConnections.Problem.Clash
    // A clash shows under the field whose port it is.
    val socksClash = clash?.takeIf { it.port == socks.trim().toIntOrNull() }
    val portsClash = clash?.takeIf { socksClash == null }
    val canSave = name.isNotBlank() && address.isNotBlank() && (ports.isNotBlank() || socks.isNotBlank()) && problem == null

    /** Address, ports and proxy from a pasted or scanned connection; the name stays. */
    fun fill(parsed: TailcatImport) {
        address = parsed.address
        if (parsed.ports.isNotEmpty()) ports = parsed.ports
        parsed.socks?.let { socks = it.toString() }
    }
    // Resolved out here, in the screen's composition — see wrapContextWithLocale.
    val scanLabel = stringResource(R.string.qr_scan_action)
    val noTailcat = stringResource(R.string.qr_scan_no_tailcat)
    // A scanned code fills the fields as Paste does: a TailCat address or
    // connect command, or a tailcat/add link carrying one.
    val scanQr = rememberQrScanner { text ->
        val importText = when (val code = ScannedCode.of(context, text)) {
            is ScannedCode.AppLink -> DeepLinks.tailcatImportText(code.uri)
            is ScannedCode.Tailcat -> code.text
            is ScannedCode.Text -> null
        }
        val parsed = importText?.let { TailcatConnections.parseImport(it) }
        if (parsed == null) Toast.makeText(context, noTailcat, Toast.LENGTH_SHORT).show() else fill(parsed)
    }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (isNew) R.string.tailcat_new else R.string.tailcat_edit),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                // The QR code another device's TailCat shows.
                IconButton(onClick = scanQr) { Icon(Icons.Default.QrCodeScanner, scanLabel) }
                // An address, or the commands a server's card copies: address,
                // ports and proxy in one go.
                TextButton(onClick = {
                    pasteScope.launch {
                        val parsed = clipboard.readText(context)?.let { TailcatConnections.parseImport(it) }
                        if (parsed == null) {
                            Toast.makeText(context, context.getString(R.string.tailcat_import_none), Toast.LENGTH_SHORT).show()
                        } else {
                            fill(parsed)
                        }
                    }
                }) {
                    Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tailcat_import))
                }
            }
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
                isError = addressError,
                supportingText = if (addressError) { { Text(stringResource(R.string.tailcat_err_address)) } } else null,
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
                isError = portsError != null || portsClash != null,
                supportingText = {
                    Text(
                        when {
                            portsError != null -> stringResource(R.string.tailcat_err_ports, portsError)
                            portsClash != null -> stringResource(R.string.tailcat_err_port_clash, portsClash.port, portsClash.name)
                            else -> stringResource(R.string.tailcat_ports_hint)
                        }
                    )
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = socks,
                onValueChange = { v -> socks = v.filter { it.isDigit() }.take(5) },
                label = { Text(stringResource(R.string.tailcat_socks)) },
                placeholder = { Text("1080") },
                singleLine = true,
                isError = socksError || socksClash != null,
                supportingText = {
                    when {
                        socksError -> Text(stringResource(R.string.tailcat_err_socks))
                        socksClash != null -> Text(stringResource(R.string.tailcat_err_port_clash, socksClash.port, socksClash.name))
                        else -> HelpText(stringResource(R.string.tailcat_socks_hint))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(edited()) },
                    enabled = canSave
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

/**
 * This phone as a tailcat server: first a way to make its address, then the
 * address, what it serves and to whom, with its switch and actions in the open.
 */
@Composable
private fun ServerCard(
    address: String?,
    config: TailcatServerConfig,
    status: TailcatServerStatus?,
    on: Boolean,
    creating: Boolean,
    onCreate: () -> Unit,
    onNewAddress: () -> Unit,
    onSwitch: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onQr: () -> Unit,
    onLogs: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val failed = status?.state == "failed" || status?.state == "error"
    val serving = status?.state == "serving"
    // Hidden, as in a connection's editor: with "let in anyone" the address
    // is all a client needs.
    var showAddress by remember { mutableStateOf(false) }
    val (container, tint) = when {
        failed -> scheme.errorContainer to scheme.onErrorContainer
        serving -> scheme.primaryContainer to scheme.onPrimaryContainer
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = if (on) scheme.surfaceContainerHigh else scheme.surfaceContainerLow)
    ) {
        if (address == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(Icons.Default.Router, scheme.secondaryContainer, scheme.onSecondaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.tailcat_server_title), style = MaterialTheme.typography.titleMedium)
                }
                HelpText(stringResource(R.string.tailcat_server_desc))
                FilledTonalButton(onClick = onCreate, enabled = !creating) {
                    if (creating) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tailcat_server_creating))
                    } else Text(stringResource(R.string.tailcat_server_create))
                }
            }
            return@Card
        }
        val line = if (status != null) serverStateLine(context, status) else stringResource(R.string.tailcat_state_stopped)
        Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(Icons.Default.Router, container, tint, if (serving) CircleShape else MaterialTheme.shapes.medium)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tailcat_server_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
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
                    enabled = !creating,
                    modifier = Modifier.padding(end = 6.dp).semantics {
                        contentDescription = context.getString(if (on) R.string.tailcat_server_cd_stop else R.string.tailcat_server_cd_start)
                    }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        if (showAddress) address else "tc" + "•".repeat(24),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = scheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showAddress = !showAddress }, modifier = Modifier.padding(end = 6.dp)) {
                    Icon(
                        if (showAddress) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        stringResource(if (showAddress) R.string.tailcat_hide_address else R.string.tailcat_show_address),
                        modifier = Modifier.size(20.dp),
                        tint = scheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.padding(end = 10.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TailcatConnections.specs(config.ports).forEach { Tag(it, scheme.surfaceVariant, scheme.onSurfaceVariant) }
                if (config.exitNode) Tag(stringResource(R.string.tailcat_server_tag_exit), scheme.tertiaryContainer, scheme.onTertiaryContainer)
                val keys = config.allowedKeys().size
                when {
                    config.allowAll -> Tag(stringResource(R.string.tailcat_server_tag_anyone), scheme.errorContainer, scheme.onErrorContainer)
                    keys == 0 -> Tag(stringResource(R.string.tailcat_server_tag_nobody), scheme.errorContainer, scheme.onErrorContainer)
                    else -> Tag(stringResource(R.string.tailcat_server_tag_keys, keys), scheme.surfaceVariant, scheme.onSurfaceVariant)
                }
                if ((status?.active ?: 0) > 0) Tag(context.getString(R.string.tailcat_active, status!!.active), scheme.secondaryContainer, scheme.onSecondaryContainer)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CardAction(Icons.Default.Autorenew, stringResource(R.string.tailcat_server_new_address), onClick = onNewAddress)
                // Seven actions in all: on a narrow screen the right-hand ones
                // scroll rather than push the last off the card.
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        CardAction(Icons.Default.Terminal, stringResource(R.string.tailcat_full_output), onClick = onLogs)
                        // The commands, not the bare address: a computer runs them as
                        // they are, another TailSocks imports them in a connection's editor.
                        val command = TailcatServer.clientCommand(address, config)
                        CardAction(Icons.Default.Share, stringResource(R.string.tailcat_share_address), onClick = { onShare(command) })
                        CardAction(Icons.Default.QrCode2, stringResource(R.string.qr_show), onClick = onQr)
                        CardAction(Icons.Default.Code, stringResource(R.string.tailcat_server_copy_command), onClick = { onCopy(command) })
                        CardAction(Icons.Default.ContentCopy, stringResource(R.string.tailcat_copy_address), onClick = { onCopy(address) })
                        CardAction(Icons.Default.Edit, stringResource(R.string.action_edit), onClick = onEdit)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerEditorSheet(
    initial: TailcatServerConfig,
    onDismiss: () -> Unit,
    onSave: (TailcatServerConfig) -> Unit
) {
    val sheetState = rememberFullSheetState()
    var ports by remember { mutableStateOf(initial.ports) }
    var exitNode by remember { mutableStateOf(initial.exitNode) }
    var allowed by remember { mutableStateOf(initial.allowed) }
    var allowAll by remember { mutableStateOf(initial.allowAll) }
    fun edited() = initial.copy(ports = ports.trim(), exitNode = exitNode, allowed = allowed.trim(), allowAll = allowAll)
    val problem = remember(ports, exitNode, allowed) { TailcatServer.problem(edited()) }
    val portsError = (problem as? TailcatServer.Problem.Ports)?.detail
    val keysError = (problem as? TailcatServer.Problem.Keys)?.detail

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
            Text(stringResource(R.string.tailcat_server_edit), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = ports,
                onValueChange = { ports = it },
                label = { Text(stringResource(R.string.tailcat_ports)) },
                placeholder = { Text("5555, 8080") },
                singleLine = true,
                isError = portsError != null,
                supportingText = {
                    Text(
                        when {
                            portsError != null -> stringResource(R.string.tailcat_err_ports, portsError)
                            problem == TailcatServer.Problem.Nothing -> stringResource(R.string.tailcat_server_err_nothing)
                            else -> stringResource(R.string.tailcat_server_ports_hint)
                        }
                    )
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            SwitchRow(
                title = stringResource(R.string.tailcat_server_exit_node),
                help = stringResource(R.string.tailcat_server_exit_node_desc),
                checked = exitNode,
                onChange = { exitNode = it }
            )
            OutlinedTextField(
                value = allowed,
                onValueChange = { allowed = it },
                label = { Text(stringResource(R.string.tailcat_server_allowed)) },
                placeholder = { Text("nodekey:…") },
                minLines = 2,
                maxLines = 6,
                enabled = !allowAll,
                isError = keysError != null,
                supportingText = {
                    if (keysError != null) Text(stringResource(R.string.tailcat_server_err_keys, keysError))
                    else HelpText(stringResource(R.string.tailcat_server_allowed_hint))
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            SwitchRow(
                title = stringResource(R.string.tailcat_server_allow_all),
                help = stringResource(R.string.tailcat_server_allow_all_desc),
                checked = allowAll,
                warning = true,
                onChange = { allowAll = it }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(edited()) }, enabled = problem == null) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

/** A switch with its title and a folded explanation, for the server's editor. */
@Composable
private fun SwitchRow(title: String, help: String, checked: Boolean, warning: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (warning && checked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            HelpText(help)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
