package io.github.bropines.tailscaled.admin.policy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.PolicyPreview
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.settings.ConfigLoading
import io.github.bropines.tailscaled.admin.settings.ConfigNote
import io.github.bropines.tailscaled.admin.settings.NoteTone
import io.github.bropines.tailscaled.admin.settings.Provide
import io.github.bropines.tailscaled.admin.settings.rememberParentLocals
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet
import io.github.bropines.tailscaled.ui.rememberFullSheetState

/**
 * "Who can reach…?" over the saved policy: a device and a port (/acl/preview type=ipport), or
 * a user (type=user). The device sheet opens it with [initialDevice]; the policy tab with
 * nothing chosen. Nothing here changes the policy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhoCanReachSheet(
    state: ConsoleState,
    vm: AdminConsoleViewModel?,
    initialDevice: ApiDevice? = null,
    initialUser: String? = null,
    onDismiss: () -> Unit,
) {
    val parent = rememberParentLocals()
    LaunchedEffect(Unit) {
        vm?.refresh(ConsoleTab.DEVICES)
        vm?.refresh(ConsoleTab.USERS)
        if (state.policy.file.value == null) vm?.policy?.load()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        parent.Provide { WhoCanReachContent(state, vm, initialDevice, initialUser) }
    }
}

/** The sheet's body, apart so a preview can draw it without the sheet's window. */
@Composable
fun WhoCanReachContent(state: ConsoleState, vm: AdminConsoleViewModel?, initialDevice: ApiDevice? = null, initialUser: String? = null) {
    val ctx = LocalContext.current
    val devices = state.devices.value.orEmpty().filter { !it.isShared }
    val users = state.users.value.orEmpty()
    val selfDevice = devices.firstOrNull { state.phoneInTailnet && it.nodeId == state.self.nodeId }
    var byUser by rememberSaveable { mutableStateOf(initialUser != null) }
    var deviceId by rememberSaveable { mutableStateOf((initialDevice ?: selfDevice)?.pathId) }
    var port by rememberSaveable { mutableStateOf("22") }
    var user by rememberSaveable { mutableStateOf(initialUser ?: state.self.loginName.takeIf { state.phoneInTailnet }.orEmpty()) }
    var pickDevice by rememberSaveable { mutableStateOf(false) }
    var pickUser by rememberSaveable { mutableStateOf(false) }
    val device = devices.firstOrNull { it.pathId == deviceId } ?: initialDevice
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val ready = if (byUser) user.isNotBlank() && '@' in user else device?.let { it.ipv4 ?: it.ipv6 } != null && portNumber != null

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(ctx.getString(R.string.admin_cfg_reach_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HelpText(ctx.getString(R.string.admin_cfg_reach_help))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !byUser, onClick = { byUser = false },
                label = { Text(ctx.getString(R.string.admin_cfg_reach_by_device)) },
                leadingIcon = { Icon(Icons.Default.Devices, null, Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = byUser, onClick = { byUser = true },
                label = { Text(ctx.getString(R.string.admin_cfg_reach_by_user)) },
                leadingIcon = { Icon(Icons.Default.Person, null, Modifier.size(18.dp)) },
            )
        }
        if (!byUser) {
            OutlinedButton(onClick = { pickDevice = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Text(
                    device?.let { "${it.shortName} · ${it.ipv4 ?: it.ipv6}" } ?: ctx.getString(R.string.admin_cfg_reach_pick_device),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Icon(Icons.Default.ArrowDropDown, null)
            }
            OutlinedTextField(
                value = port,
                onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                label = { Text(ctx.getString(R.string.admin_cfg_reach_port)) },
                isError = port.isNotEmpty() && portNumber == null,
                supportingText = if (port.isNotEmpty() && portNumber == null) ({ Text(ctx.getString(R.string.admin_cfg_reach_port_invalid)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it.trim() },
                label = { Text(ctx.getString(R.string.admin_cfg_reach_user)) },
                trailingIcon = if (users.isNotEmpty()) ({ TextButton(onClick = { pickUser = true }) { Text(ctx.getString(R.string.admin_cfg_reach_choose)) } }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = {
                if (byUser) vm?.policy?.whoCanReach(PolicyPreviewType.USER, user, user)
                else device?.let { d -> portNumber?.let { vm?.policy?.whoCanReach(d, it) } }
            },
            enabled = ready && state.policy.reach.loading.not(),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) { Text(ctx.getString(R.string.admin_cfg_reach_check)) }

        val query = state.policy.reachQuery
        val reach = state.policy.reach
        if (query != null) {
            LoadProblems(reach, onRetry = { vm?.policy?.whoCanReach(query.type, query.previewFor, query.label) })
            when {
                reach.loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { ConfigLoading() }
                reach.value != null -> ReachResult(query, reach.value)
            }
        }
    }

    // Pickers resolve their words here, in the parent; they are windows of their own.
    if (pickDevice) {
        PickerSheet(
            title = ctx.getString(R.string.admin_cfg_reach_pick_device),
            options = devices.map { d ->
                PickerOption(d.pathId, d.shortName, supporting = listOfNotNull(d.ipv4, if (d.nodeId == selfDevice?.nodeId) ctx.getString(R.string.admin_cfg_reach_this_phone) else null).joinToString(" · "))
            },
            selected = deviceId,
            onPick = { deviceId = it },
            onDismiss = { pickDevice = false },
        )
    }
    if (pickUser) {
        PickerSheet(
            title = ctx.getString(R.string.admin_cfg_reach_user),
            options = users.filter { it.loginName.isNotBlank() }.map { PickerOption(it.loginName, it.name, supporting = it.loginName) },
            selected = user.takeIf { it.isNotBlank() },
            onPick = { user = it },
            onDismiss = { pickUser = false },
        )
    }
}

@Composable
private fun ReachResult(query: ReachQuery, preview: PolicyPreview) {
    val ctx = LocalContext.current
    Text(
        ctx.getString(if (query.type == PolicyPreviewType.USER) R.string.admin_cfg_reach_result_user else R.string.admin_cfg_reach_result_ipport, query.label),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    if (preview.matches.isEmpty()) {
        ConfigNote(
            ctx.getString(if (query.type == PolicyPreviewType.USER) R.string.admin_cfg_reach_none_user else R.string.admin_cfg_reach_none_ipport),
            NoteTone.INFO,
        )
        return
    }
    preview.matches.forEach { m ->
        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ctx.getString(R.string.admin_cfg_reach_from), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(56.dp))
                    Text(m.users.joinToString(", "), style = codeStyle(), modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ctx.getString(R.string.admin_cfg_reach_to), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(56.dp))
                    Text(m.ports.joinToString(", "), style = codeStyle(), modifier = Modifier.weight(1f))
                }
                m.lineNumber?.let {
                    Spacer(Modifier.size(2.dp))
                    Text(ctx.getString(R.string.admin_cfg_reach_rule_line, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
