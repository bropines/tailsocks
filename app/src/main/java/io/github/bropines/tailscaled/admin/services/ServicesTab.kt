package io.github.bropines.tailscaled.admin.services

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.InPaneWidth
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.LoadingIndicatorCompat
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiService
import io.github.bropines.tailscaled.admin.api.ApiServiceHost
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.ui.EmptyState
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.LocalInPane
import io.github.bropines.tailscaled.ui.SheetOrPane
import io.github.bropines.tailscaled.ui.rememberFullSheetState

/**
 * Tailscale Services: each `svc:` name with its addresses, ports, tags and comment; tap for
 * the devices that host it and their approval. Creating, editing, renaming and deleting a
 * service and approving a host all go through the console's safety gates.
 */
@Composable
fun ServicesTab(
    state: Loadable<List<ApiService>>,
    canWrite: Boolean,
    onRetry: () -> Unit,
    onServiceClick: (ApiService) -> Unit,
    onCreate: () -> Unit,
    /** The service whose hosts stand in the pane beside the list, on a large window. */
    shownName: String? = null,
) {
    val ctx = LocalContext.current
    val services = state.value.orEmpty().sortedBy { it.name }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            HelpText(ctx.getString(R.string.admin_svc_help), modifier = Modifier.weight(1f))
            if (canWrite) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onCreate, shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(ctx.getString(R.string.admin_svc_new))
                }
            }
        }
        LoadProblems(state, onRetry, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        when {
            services.isEmpty() && state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
            services.isEmpty() -> EmptyState(Icons.Default.CloudQueue, ctx.getString(R.string.admin_svc_empty), Modifier.weight(1f).fillMaxWidth())
            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(services, key = { it.name }) { s -> ServiceCard(s, shown = s.name == shownName) { onServiceClick(s) } }
            }
        }
    }
}

@Composable
internal fun SmallTag(text: String, container: Color, content: Color, mono: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** One service; [shown] is the one whose hosts stand in the pane beside the list, outlined. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServiceCard(service: ApiService, shown: Boolean = false, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick)
            .then(if (shown) Modifier.semantics { selected = true } else Modifier),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        border = if (shown) BorderStroke(2.dp, scheme.primary) else null,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(scheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.CloudQueue, null, tint = scheme.primary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(service.displayName?.takeIf { it.isNotBlank() } ?: service.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (!service.displayName.isNullOrBlank()) {
                    Text(service.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                }
                Text(
                    service.addrs.joinToString("  ").ifBlank { ctx.getString(R.string.admin_svc_no_address) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (service.ports.isNotEmpty() || service.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        service.ports.forEach { SmallTag(it, scheme.secondaryContainer, scheme.onSecondaryContainer, mono = true) }
                        service.tags.forEach { SmallTag(it, scheme.tertiaryContainer, scheme.onTertiaryContainer) }
                    }
                }
                service.comment?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = scheme.outlineVariant)
        }
    }
}

/** "approved:manual" and its kin in words, as the host list shows them. */
fun approvalText(ctx: Context, level: String?): String = when (level) {
    "approved:manual" -> ctx.getString(R.string.admin_svc_host_approved)
    "approved:auto" -> ctx.getString(R.string.admin_svc_host_approved_auto)
    "not-approved", null, "" -> ctx.getString(R.string.admin_svc_host_not_approved)
    else -> level
}

fun configuredText(ctx: Context, configured: String?): String? = when (configured?.lowercase()) {
    null, "" -> null
    "ready" -> ctx.getString(R.string.admin_svc_host_ready)
    else -> configured.replace('_', ' ').replace('-', ' ')
}

/**
 * One service and its hosts: a sheet over the list on a phone, the pane beside it on a large
 * window. Strings are resolved here: the sheet's window ignores the app's language.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceSheet(
    service: ApiService,
    hosts: Loadable<List<ApiServiceHost>>?,
    allDevices: List<ApiDevice>,
    canWrite: Boolean,
    onLoadHosts: () -> Unit,
    onSetHost: (deviceId: String, deviceName: String, approved: Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberFullSheetState()
    LaunchedEffect(service.name) { if (hosts == null) onLoadHosts() }
    val ctx = LocalContext.current
    SheetOrPane(onDismiss = onDismiss, sheetState = sheetState) {
        InPaneWidth { ServiceSheetContent(ctx, service, hosts, allDevices, canWrite, onLoadHosts, onSetHost, onEdit, onDelete) }
    }
}

/** The sheet's body, apart so a preview can draw it without the sheet's window. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServiceSheetContent(
    ctx: Context,
    service: ApiService,
    hosts: Loadable<List<ApiServiceHost>>?,
    allDevices: List<ApiDevice>,
    canWrite: Boolean,
    onLoadHosts: () -> Unit,
    onSetHost: (deviceId: String, deviceName: String, approved: Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)
            // A pane stands inside the screen's insets already; a sheet reaches the bottom edge.
            .then(if (LocalInPane.current) Modifier else Modifier.navigationBarsPadding()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).background(scheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.CloudQueue, null, tint = scheme.primary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(service.displayName?.takeIf { it.isNotBlank() } ?: service.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (!service.displayName.isNullOrBlank()) Text(service.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
            }
        }

        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
            SelectionContainer {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(ctx.getString(R.string.admin_svc_field_addresses), service.addrs.joinToString("\n").ifBlank { "—" }, mono = true)
                    Field(ctx.getString(R.string.admin_svc_field_ports), service.ports.joinToString(", ").ifBlank { "—" }, mono = true)
                    Field(ctx.getString(R.string.admin_svc_field_tags), service.tags.joinToString(", ").ifBlank { "—" })
                    Field(ctx.getString(R.string.admin_svc_field_comment), service.comment?.takeIf { it.isNotBlank() } ?: "—")
                }
            }
        }

        Text(ctx.getString(R.string.admin_svc_hosts), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        HelpText(ctx.getString(R.string.admin_svc_hosts_help))
        val list = hosts?.value
        when {
            hosts != null && list == null && hosts.error != null -> LoadProblems(hosts, onLoadHosts)
            list == null -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
            list.isEmpty() -> Text(ctx.getString(R.string.admin_svc_hosts_none), style = MaterialTheme.typography.bodyMedium, color = scheme.outline)
            else -> Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    list.forEachIndexed { i, host ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = scheme.outlineVariant)
                        val device = allDevices.find { it.nodeId == host.stableNodeID || it.id == host.stableNodeID }
                        val name = device?.shortName ?: host.stableNodeID
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(value = host.isApproved, enabled = canWrite, role = Role.Switch) { onSetHost(host.stableNodeID, name, it) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (host.isApproved) Icons.Default.CheckCircle else Icons.Default.HourglassEmpty, null,
                                tint = if (host.isApproved) scheme.primary else scheme.outline, modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    listOfNotNull(approvalText(ctx, host.approvalLevel), configuredText(ctx, host.configured)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = host.isApproved, onCheckedChange = null, enabled = canWrite)
                        }
                    }
                }
            }
        }

        if (canWrite) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(ctx.getString(R.string.action_edit))
                }
                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(ctx.getString(R.string.action_delete))
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

/**
 * A new service, or an edit of [initial]. The name may change: that renames the service, which
 * the confirm step marks as HIGH. Ports default to TCP; tags come from the policy's tagOwners.
 */
@Composable
fun ServiceEditorDialog(initial: ApiService?, policyTags: List<String>, onDismiss: () -> Unit, onSave: (ApiService) -> Unit) {
    val ctx = LocalContext.current
    val form = rememberServiceForm(initial)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(if (initial == null) R.string.admin_svc_editor_new else R.string.admin_svc_editor_edit)) },
        text = { ServiceEditorContent(ctx, form, initial, policyTags) },
        confirmButton = {
            Button(onClick = { form.result(initial)?.let { onSave(it); onDismiss() } }, enabled = form.problem() == null) {
                Text(ctx.getString(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

/** The editor's fields, kept across rotation. */
class ServiceFormState(
    name: String, displayName: String, ports: String, ipv4: String, comment: String, tags: List<String>,
) {
    var name by mutableStateOf(name)
    var displayName by mutableStateOf(displayName)
    var ports by mutableStateOf(ports)
    var ipv4 by mutableStateOf(ipv4)
    var comment by mutableStateOf(comment)
    var tags by mutableStateOf(tags)

    fun problem(): ServiceForm.Problem? =
        ServiceForm.validate(ServiceForm.name(name), ServiceForm.ports(ports), ipv4, displayName.trim())

    companion object {
        /** Kept across rotation as plain strings, tags joined by newlines. */
        val Saver = listSaver<ServiceFormState, String>(
            save = { listOf(it.name, it.displayName, it.ports, it.ipv4, it.comment, it.tags.joinToString("\n")) },
            restore = { ServiceFormState(it[0], it[1], it[2], it[3], it[4], it[5].split('\n').filter { t -> t.isNotBlank() }) },
        )
    }

    fun result(initial: ApiService?): ApiService? {
        if (problem() != null) return null
        return ApiService(
            name = ServiceForm.name(name),
            displayName = displayName.trim().ifBlank { null },
            addrs = if (initial == null) listOfNotNull(ipv4.trim().ifBlank { null }) else initial.addrs,
            comment = comment.trim().ifBlank { null },
            ports = ServiceForm.ports(ports),
            tags = tags,
        )
    }
}

@Composable
fun rememberServiceForm(initial: ApiService?): ServiceFormState = rememberSaveable(initial?.name, saver = ServiceFormState.Saver) {
    ServiceFormState(
        name = initial?.name?.removePrefix(ServiceForm.PREFIX).orEmpty(),
        displayName = initial?.displayName.orEmpty(),
        ports = initial?.ports?.joinToString(", ") ?: "tcp:443",
        ipv4 = "",
        comment = initial?.comment.orEmpty(),
        tags = initial?.tags.orEmpty(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServiceEditorContent(ctx: Context, form: ServiceFormState, initial: ApiService?, policyTags: List<String>) {
    val problem = form.problem()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = form.name,
            onValueChange = { form.name = it.trim().lowercase().removePrefix(ServiceForm.PREFIX) },
            label = { Text(ctx.getString(R.string.admin_svc_field_name)) },
            prefix = { Text(ServiceForm.PREFIX) },
            isError = form.name.isNotEmpty() && problem == ServiceForm.Problem.NAME,
            supportingText = if (form.name.isNotEmpty() && problem == ServiceForm.Problem.NAME) ({ Text(ctx.getString(R.string.admin_svc_error_name)) }) else null,
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (initial != null && ServiceForm.name(form.name) != initial.name) {
            Text(ctx.getString(R.string.admin_svc_rename_warning, initial.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            value = form.displayName,
            onValueChange = { form.displayName = it },
            label = { Text(ctx.getString(R.string.admin_svc_field_display_name)) },
            isError = problem == ServiceForm.Problem.DISPLAY_NAME,
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.ports,
            onValueChange = { form.ports = it },
            label = { Text(ctx.getString(R.string.admin_svc_field_ports)) },
            isError = problem == ServiceForm.Problem.PORTS,
            supportingText = { Text(ctx.getString(if (problem == ServiceForm.Problem.PORTS) R.string.admin_svc_error_ports else R.string.admin_svc_ports_help)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (initial == null) {
            OutlinedTextField(
                value = form.ipv4,
                onValueChange = { form.ipv4 = it.trim() },
                label = { Text(ctx.getString(R.string.admin_svc_field_ipv4)) },
                isError = problem == ServiceForm.Problem.ADDRESS,
                supportingText = { Text(ctx.getString(if (problem == ServiceForm.Problem.ADDRESS) R.string.admin_svc_error_ipv4 else R.string.admin_svc_ipv4_help)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val tagChoices = (policyTags + form.tags).distinct().sorted()
        if (tagChoices.isNotEmpty()) {
            Text(ctx.getString(R.string.admin_svc_field_tags), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tagChoices.forEach { tag ->
                    val on = tag in form.tags
                    FilterChip(selected = on, onClick = { form.tags = if (on) form.tags - tag else form.tags + tag }, label = { Text(tag) })
                }
            }
        }
        OutlinedTextField(
            value = form.comment,
            onValueChange = { form.comment = it },
            label = { Text(ctx.getString(R.string.admin_svc_field_comment)) },
            minLines = 2,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
