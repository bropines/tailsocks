package io.github.bropines.tailscaled.ui

import android.text.format.Formatter
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.settings.ParentLocals
import io.github.bropines.tailscaled.admin.settings.rememberParentLocals
import io.github.bropines.tailscaled.core.FileSource
import io.github.bropines.tailscaled.core.ManagedCaps
import io.github.bropines.tailscaled.core.ManagedExitNode
import io.github.bropines.tailscaled.core.ManagedKeys
import io.github.bropines.tailscaled.core.ManagedOp
import io.github.bropines.tailscaled.core.RemoteElevateLengths
import io.github.bropines.tailscaled.core.ManagedPermission
import io.github.bropines.tailscaled.core.ManagedSetting
import io.github.bropines.tailscaled.core.clearOnly
import io.github.bropines.tailscaled.core.isShareName
import io.github.bropines.tailscaled.core.setting
import io.github.bropines.tailscaled.core.shareNameOf
import java.io.File

/*
 * The windows the managing page (ui/RemoteManagePage.kt) opens over itself: the exit nodes of
 * the managed device, a question before something that cannot be taken back, the two small
 * forms, the device's storage and its log. Each is a window of its own, which answers in the
 * system's language unless told otherwise: their words are resolved in the page, or the
 * page's language is provided inside (InPageLanguage).
 */

@Composable
internal fun ManageWindows(
    ui: RemoteManageUi.Managing,
    actions: RemoteManageActions,
    hooks: RemoteManageHooks,
    asked: ManageAsk?,
    onAsked: (ManageAsk?) -> Unit,
) {
    val locals = rememberParentLocals()
    val close = { onAsked(null) }

    if (ui.picking) ExitNodeSheet(ui, actions, locals)
    ui.diag?.let { DiagWindow(ui.name, it, actions, hooks, locals) }

    // A folder chosen in the device's storage, waiting for the share's name.
    var sharing by remember { mutableStateOf<File?>(null) }
    // A file chosen there, waiting for a yes: it may be a film.
    var fetching by remember { mutableStateOf<Pair<File, Long>?>(null) }

    when (asked) {
        null -> Unit
        is ManageAsk.DeleteFile -> Confirm(
            title = stringResource(R.string.remote_file_confirm, asked.name),
            text = null,
            yes = stringResource(R.string.action_delete),
            destructive = true,
            onYes = { actions.deleteFile(asked.name) },
            onClose = close,
        )
        is ManageAsk.RemoveShare -> Confirm(
            title = stringResource(R.string.rmanage_share_remove_title, asked.name),
            text = stringResource(R.string.rmanage_share_remove_text, ui.name),
            yes = stringResource(R.string.rmanage_share_remove_action),
            destructive = true,
            onYes = { actions.removeShare(asked.name) },
            onClose = close,
        )
        ManageAsk.FullControlOff -> Confirm(
            title = stringResource(R.string.rmanage_full_off_title, ui.name),
            text = stringResource(R.string.rmanage_full_off_text),
            yes = stringResource(R.string.rmanage_full_off_action),
            destructive = true,
            onYes = { actions.setSwitch(ManagedKeys.FULL_CONTROL, false) },
            onClose = close,
        )
        ManageAsk.Forget -> Confirm(
            title = stringResource(R.string.remote_forget),
            text = stringResource(R.string.remote_forget_confirm, ui.name),
            yes = stringResource(R.string.remote_forget_action),
            destructive = true,
            onYes = actions::forget,
            onClose = close,
        )
        ManageAsk.Routes -> RoutesDialog(ui, actions, close)
        ManageAsk.FullRights -> RightsDialog(ui, actions, close)
        ManageAsk.BrowseFiles, ManageAsk.BrowseFolder -> {
            val storage = hooks.storage
            if (storage == null) LaunchedEffect(asked) { close() }
            else {
                val folder = asked == ManageAsk.BrowseFolder
                // The device's own grant is asked for there, as the Permissions group asks it; the
                // answer comes back to the page. Where it cannot be asked the browser says so.
                val grant = { actions.askPermission(ManagedPermission.STORAGE); close() }
                    .takeIf { ui.app?.permissions?.firstOrNull { it.id == ManagedPermission.STORAGE }?.ask == true && ui.access.gate(ManagedOp.PERMISSION_REQUEST).open }
                StorageWindow(
                    title = stringResource(if (folder) R.string.rmanage_share_pick_title else R.string.rmanage_browse_title, ui.name),
                    confirm = stringResource(R.string.rmanage_share_pick_confirm),
                    folder = folder,
                    source = storage,
                    locals = locals,
                    onPicked = { file ->
                        if (folder) sharing = file else fetching = file to (storage.describe(file)?.size ?: 0L)
                        close()
                    },
                    name = ui.name,
                    onGrant = grant,
                    onClose = close,
                )
            }
        }
    }

    sharing?.let { folder -> ShareNameDialog(folder, onShare = { name -> actions.addShare(name, folder.path) }, onClose = { sharing = null }) }
    fetching?.let { (file, size) ->
        val context = LocalContext.current
        Confirm(
            title = stringResource(R.string.rmanage_fetch_title, file.name),
            text = stringResource(R.string.rmanage_fetch_text, Formatter.formatShortFileSize(context, size)),
            yes = stringResource(R.string.rmanage_fetch_confirm),
            destructive = false,
            onYes = { actions.fetch(file.path) },
            onClose = { fetching = null },
        )
    }
}

/** One question with the answer that changes nothing in focus. Its words come resolved: the dialog is a window of its own. */
@Composable
private fun Confirm(title: String, text: String?, yes: String, destructive: Boolean, onYes: () -> Unit, onClose: () -> Unit) {
    val no = stringResource(R.string.action_cancel)
    AppAlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = text?.let { body -> { Text(body) } },
        confirmButton = {
            TextButton(
                onClick = { onClose(); onYes() },
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(yes) }
        },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.tvInitialFocus()) { Text(no) } }
    )
}

// ─── Exit nodes ──────────────────────────────────────────────────────────────────────────────

/** The managed device's own exit nodes, "None" first and its own pick marked. One that is offline cannot be chosen. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun ExitNodeSheet(ui: RemoteManageUi.Managing, actions: RemoteManageActions, locals: ParentLocals) {
    AppModalSheet(onDismissRequest = actions::closeExitNodes) {
        InPageLanguage(locals) { ExitNodeList(ui, actions) }
    }
}

@Composable
internal fun ExitNodeList(ui: RemoteManageUi.Managing, actions: RemoteManageActions) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            stringResource(R.string.remote_exit_pick_title, ui.name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
        )
        val nodes = ui.exitNodes
        if (nodes == null) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.remote_loading, ui.name), style = MaterialTheme.typography.bodyMedium)
            }
            return
        }
        val selected = nodes.firstOrNull { it.selected }?.id ?: ui.exitNode?.id.orEmpty()
        val suggest = ui.access.has(ManagedCaps.SUGGEST)
        ExitNodeRow(stringResource(R.string.remote_exit_none), null, selected.isEmpty(), enabled = !ui.busy, first = selected.isEmpty()) {
            actions.pickExitNode("")
        }
        // The device's own pick stands first: it is the one most often wanted.
        nodes.sortedByDescending { suggest && it.suggested }.forEach { node ->
            ExitNodeRow(
                title = node.label,
                note = exitNodeNote(node),
                selected = node.id == selected,
                enabled = !ui.busy && (node.online || node.id == selected),
                first = node.id == selected,
                tag = if (suggest && node.suggested) stringResource(R.string.rmanage_exit_suggested) else null,
            ) { actions.pickExitNode(node.id) }
        }
        if (nodes.isEmpty()) {
            Text(
                stringResource(R.string.remote_exit_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun exitNodeNote(node: ManagedExitNode): String? = listOfNotNull(
    node.place.ifEmpty { null },
    if (node.online) null else stringResource(R.string.remote_exit_offline_tag),
).joinToString(" · ").ifEmpty { null }

@Composable
private fun ExitNodeRow(title: String, note: String?, selected: Boolean, enabled: Boolean, first: Boolean, tag: String? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            // The remote starts on the node in use.
            .then(if (first) Modifier.tvInitialFocus() else Modifier)
            .tvFocusRing(source, shape)
            .clip(shape)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (tag != null) {
            Spacer(Modifier.width(8.dp))
            ManageTag(tag, Modifier.padding(end = 8.dp), strong = true)
        }
    }
}

// ─── The two small forms ─────────────────────────────────────────────────────────────────────

/** The subnets the device offers. At the basic level they can only be withdrawn: a list takes Full control. */
@Composable
private fun RoutesDialog(ui: RemoteManageUi.Managing, actions: RemoteManageActions, onClose: () -> Unit) {
    val row = ui.settings.setting(ManagedKeys.ADVERTISE_ROUTES, ManagedSetting.CIDRS)
    if (row == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val title = stringResource(R.string.rmanage_routes)
    val label = stringResource(R.string.rmanage_routes_label)
    val help = stringResource(R.string.rmanage_routes_help)
    val bad = stringResource(R.string.rmanage_routes_bad)
    val full = stringResource(R.string.rmanage_routes_full)
    val save = stringResource(if (row.clearOnly) R.string.rmanage_routes_clear else R.string.action_save)
    val cancel = stringResource(R.string.action_cancel)
    var text by remember { mutableStateOf(row.value.replace(",", ", ")) }
    var wrong by remember { mutableStateOf(false) }
    val submit = {
        if (actions.setRoutes(if (row.clearOnly) "" else text)) onClose() else wrong = true
    }
    AppAlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (row.clearOnly) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                    Text(full, style = MaterialTheme.typography.bodyMedium)
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; wrong = false },
                        label = { Text(label) },
                        isError = wrong,
                        supportingText = { Text(if (wrong) bad else help) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().tvInitialFocus().tvDownLeavesField()
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = !row.clearOnly || row.value.isNotEmpty()) { Text(save) } },
        dismissButton = { TextButton(onClick = onClose) { Text(cancel) } }
    )
}

/**
 * For how long to ask the managed device for full rights: the lengths it gives, those this
 * pairing still has. Asking gives nothing; someone there confirms with the device's lock.
 */
@Composable
private fun RightsDialog(ui: RemoteManageUi.Managing, actions: RemoteManageActions, onClose: () -> Unit) {
    val title = stringResource(R.string.rmanage_rights_dialog_title, ui.name)
    val text = stringResource(R.string.rmanage_rights_dialog_text, ui.name)
    val send = stringResource(R.string.rmanage_rights_send)
    val cancel = stringResource(R.string.action_cancel)
    val lengths = RemoteElevateLengths.offered(ui.left).map { it to remoteElevateLengthText(it, ui.left) }
    val first = RemoteElevateLengths.DEFAULT.takeIf { d -> lengths.any { it.first == d } } ?: RemoteElevateLengths.UNTIL_PAIRING_ENDS
    var choice by remember { mutableStateOf(first) }
    AppAlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                lengths.forEach { (seconds, label) ->
                    // The remote starts on the length that is chosen already.
                    RemoteChoiceRow(label, selected = seconds == choice, modifier = if (seconds == first) Modifier.tvInitialFocus() else Modifier) { choice = seconds }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onClose(); actions.askFullRights(choice) }) { Text(send) } },
        dismissButton = { TextButton(onClick = onClose) { Text(cancel) } }
    )
}

/** The name a folder is shared under: made of the folder's own, and held to what the device takes. */
@Composable
private fun ShareNameDialog(folder: File, onShare: (String) -> Unit, onClose: () -> Unit) {
    val title = stringResource(R.string.rmanage_share_name_title)
    val label = stringResource(R.string.rmanage_share_name_label)
    val rule = stringResource(R.string.rmanage_share_name_rule)
    val share = stringResource(R.string.rmanage_share_name_action)
    val cancel = stringResource(R.string.action_cancel)
    var name by remember(folder) { mutableStateOf(shareNameOf(folder.name)) }
    val valid = isShareName(name)
    val submit = {
        if (valid) {
            onClose()
            onShare(name)
        }
    }
    AppAlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(folder.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(label) },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { Text(rule) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().tvInitialFocus().tvDownLeavesField()
                )
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = valid) { Text(share) } },
        dismissButton = { TextButton(onClick = onClose) { Text(cancel) } }
    )
}

// ─── Full-screen windows ─────────────────────────────────────────────────────────────────────

/** A page over the page, in a window of its own: the page's language inside, a television's safe area kept clear. */
@Composable
internal fun PageWindow(locals: ParentLocals, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        InPageLanguage(locals) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(if (isTv) Modifier.padding(LocalTvSafeArea.current) else Modifier) { content() }
            }
        }
    }
}

/**
 * The managed device's storage in the app's own browser (ui/FileBrowser.kt), which reads
 * whatever FileSource it is given: here, the device's answers. For a file to be sent to this
 * device, or a folder to share there.
 */
@Composable
private fun StorageWindow(
    title: String,
    confirm: String,
    folder: Boolean,
    source: FileSource,
    locals: ParentLocals,
    onPicked: (File) -> Unit,
    name: String,
    onGrant: (() -> Unit)?,
    onClose: () -> Unit,
) {
    PageWindow(locals, onClose) {
        if (folder) {
            PickFolderScreen(source = source, onPicked = onPicked, onCancel = onClose, title = title, confirmLabel = confirm, onGrantAccess = onGrant, remoteName = name)
        } else {
            PickFilesScreen(
                source = source,
                onPicked = { files -> files.firstOrNull()?.let(onPicked) },
                onCancel = onClose,
                multiple = false,
                title = title,
                onGrantAccess = onGrant,
                remoteName = name,
            )
        }
    }
}

/** The device's log or report: read here, and handed to another app or the clipboard where there is one. */
@Composable
private fun DiagWindow(name: String, diag: RemoteDiagState, actions: RemoteManageActions, hooks: RemoteManageHooks, locals: ParentLocals) {
    PageWindow(locals, actions::closeDiag) { DiagContent(name, diag, actions, hooks) }
}

@Composable
internal fun DiagContent(name: String, diag: RemoteDiagState, actions: RemoteManageActions, hooks: RemoteManageHooks) {
    val title = stringResource(if (diag.log) R.string.rmanage_diag_log_title else R.string.rmanage_diag_report_title, name)
    val ready = !diag.loading && diag.text.isNotEmpty()
    var copied by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            AppTopBar(
                title = title,
                subtitle = if (copied) stringResource(R.string.rmanage_copied) else null,
                onBack = actions::closeDiag,
                actions = {
                    hooks.copyText?.let { copy ->
                        IconButton(onClick = { copy(diag.text); copied = true }, enabled = ready) {
                            Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy))
                        }
                    }
                    hooks.shareText?.let { share ->
                        IconButton(onClick = { share(title, diag.text) }, enabled = ready) {
                            Icon(Icons.Default.Share, stringResource(R.string.rmanage_share))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                diag.loading -> Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.remote_loading, name), style = MaterialTheme.typography.bodyMedium)
                }
                diag.text.isEmpty() -> Text(
                    stringResource(R.string.rmanage_diag_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
                else -> {
                    val scroll = rememberScrollState()
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        if (diag.log && diag.scrubbed > 0) {
                            ManageLine(pluralStringResource(R.plurals.rmanage_diag_scrubbed, diag.scrubbed, diag.scrubbed))
                        }
                        // Nothing in the text for a remote to stop on: the pane is the stop, and scrolls under the arrows.
                        Box(Modifier.weight(1f).fillMaxWidth().tvInitialFocus().tvReadingPane(scroll).verticalScroll(scroll)) {
                            val lines: @Composable () -> Unit = {
                                Text(
                                    diag.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                            }
                            if (isTv) lines() else SelectionContainer { lines() }
                        }
                    }
                }
            }
        }
    }
}
