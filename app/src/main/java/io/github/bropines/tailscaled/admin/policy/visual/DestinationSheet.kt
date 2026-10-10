package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.settings.Provide
import io.github.bropines.tailscaled.admin.settings.rememberParentLocals
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.rememberFullSheetState
import kotlinx.coroutines.launch

/*
 * Destinations with ports: an ACL's `dst` ("tag:web:80,443") and a test's `accept`/`deny`
 * ("tag:web:443", one port). A chip per destination with its ports in words; a tap opens the
 * destination's sheet (host, ports, Remove); Add opens the picker and then asks the ports once
 * for everything just picked.
 */

/**
 * The destinations of an ACL ([single] false) or of a test ([single] true: one port each) as
 * chips. Picking hosts in the picker adds them — after their ports are asked — and unticking a
 * host there removes every destination on it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DestinationsField(
    title: String,
    dst: List<String>,
    slot: SelectorSlot,
    env: VisualEnv,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    single: Boolean = false,
    help: String? = null,
) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var asking by remember { mutableStateOf<List<String>>(emptyList()) }
    val split = dst.map(Selectors::hostPorts)
    val hosts = split.map { it.host }.distinct()
    val model = env.model
    fun problem(d: String): SelectorProblem? = model?.let {
        if (single) SelectorRules.checkTestDestination(d, it, env.headscale) else SelectorRules.checkAclDestination(d, it, env.headscale)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldTitle(title)
        help?.let { HelpText(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            split.forEachIndexed { i, hp ->
                SelectorChip(
                    value = hp.host,
                    env = env,
                    onClick = { editing = i },
                    suffix = " · " + portWordsShort(ctx, hp.ports),
                    problem = problem(dst[i]),
                    removable = false,
                )
            }
            if (env.editable) AddChip(onClick = { picking = true })
        }
        ProblemLines(dst.mapNotNull { d -> problem(d)?.let { Selectors.hostPorts(d).host to it } })
    }
    if (picking) {
        SelectorPickerSheet(
            title = title,
            slot = slot,
            env = env,
            selected = hosts,
            onDone = { picked ->
                val removed = hosts - picked.toSet()
                val kept = dst.filter { Selectors.hostPorts(it).host !in removed }
                val added = picked.filter { it !in hosts }
                if (kept != dst) onChange(kept)
                when {
                    added.isEmpty() -> Unit
                    // The internet has no ports to choose: an exit node forwards whatever it is sent.
                    !single && added.all { it == "autogroup:internet" } -> onChange(kept + added.map { "$it:*" })
                    else -> asking = added
                }
            },
            onDismiss = { picking = false },
        )
    }
    if (asking.isNotEmpty()) {
        val added = asking
        DestinationSheet(
            env = env,
            slot = slot,
            host = null,
            hosts = added,
            ports = "",
            single = single,
            onDone = { _, ports -> onChange(dst.filter { Selectors.hostPorts(it).host !in added } + added.map { "$it:$ports" }) },
            onDismiss = { asking = emptyList() },
        )
    }
    editing?.let { i ->
        val hp = split.getOrNull(i) ?: return@let
        DestinationSheet(
            env = env,
            slot = slot,
            host = hp.host,
            ports = hp.ports.orEmpty(),
            single = single,
            onDone = { host, ports -> onChange(dst.toMutableList().also { it[i] = "${host ?: hp.host}:$ports" }) },
            onRemove = { onChange(dst.filterIndexed { j, _ -> j != i }) },
            onDismiss = { editing = null },
        )
    }
}

/**
 * One destination as a sheet: [host] and its ports, with Remove; or, [host] null, the ports
 * of [hosts] just picked. Nothing is written until Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationSheet(
    env: VisualEnv,
    slot: SelectorSlot,
    host: String?,
    ports: String,
    single: Boolean,
    onDone: (host: String?, ports: String) -> Unit,
    onDismiss: () -> Unit,
    hosts: List<String> = emptyList(),
    onRemove: (() -> Unit)? = null,
) {
    val parent = rememberParentLocals()
    val sheetState = rememberFullSheetState()
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) {
        then()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        parent.Provide {
            DestinationContent(
                env = env,
                slot = slot,
                host = host,
                hosts = hosts,
                ports = ports,
                single = single,
                onDone = { h, p -> close { onDone(h, p) } },
                onRemove = onRemove?.let { r -> { close(r) } },
            )
        }
    }
}

/** What [DestinationSheet] holds, without the sheet: a preview can draw it. */
@Composable
fun DestinationContent(
    env: VisualEnv,
    slot: SelectorSlot,
    host: String?,
    ports: String,
    single: Boolean,
    onDone: (host: String?, ports: String) -> Unit,
    hosts: List<String> = emptyList(),
    onRemove: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var h by remember { mutableStateOf(host) }
    var p by remember { mutableStateOf(ports) }
    var picking by remember { mutableStateOf(false) }
    val model = env.model
    val hostProblem = h?.let { v -> model?.let { SelectorRules.check(v, slot, it, env.headscale) } }?.takeIf { it != SelectorProblem.UNDEFINED }
    val portProblem = portsProblem(p, single)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val title = when {
            host != null -> ctx.getString(R.string.admin_pv_dest_title)
            else -> ctx.getString(R.string.admin_pv_dest_ports_for, hosts.joinToString(", ") { VisualText.label(ctx, it) })
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (h != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldTitle(ctx.getString(R.string.admin_pv_dest_host))
                SelectorChip(value = h!!, env = env, onClick = { picking = true }, problem = hostProblem, removable = false)
                VisualText.help(ctx, h!!)?.let { HelpText(it) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldTitle(ctx.getString(if (single) R.string.admin_pv_dest_port else R.string.admin_pv_dest_ports))
            PortsEditor(p, onChange = { p = it }, single = single)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onRemove != null && env.editable) {
                TextButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(ctx.getString(R.string.admin_pv_dest_remove), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onDone(h, p.replace(" ", "")) },
                enabled = env.editable && portProblem == null && hostProblem == null,
                shape = MaterialTheme.shapes.medium,
            ) { Text(ctx.getString(R.string.admin_pv_picker_done)) }
        }
    }
    if (picking) {
        SelectorPickerSheet(
            title = ctx.getString(R.string.admin_pv_dest_host),
            slot = slot,
            env = env,
            selected = listOfNotNull(h),
            onDone = { picked -> picked.lastOrNull()?.let { h = it } },
            onDismiss = { picking = false },
            single = true,
        )
    }
}

/** A row of labels for destinations on a card: a host with its ports in words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DestinationLabels(dst: List<HostPorts>, env: VisualEnv, showPorts: Boolean, max: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val hosts = env.model?.hosts?.map { it.name }?.toSet().orEmpty()
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val shown = if (dst.size > max) dst.take((max - 1).coerceAtLeast(1)) else dst
        shown.forEach { hp -> SelectorLabel(hp.host, suffix = if (showPorts) " · " + portWordsShort(ctx, hp.ports) else null, hosts = hosts) }
        if (shown.size < dst.size) MoreLabel(dst.size - shown.size)
    }
}
