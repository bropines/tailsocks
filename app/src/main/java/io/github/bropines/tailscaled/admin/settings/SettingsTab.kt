package io.github.bropines.tailscaled.admin.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.api.RouteSelection
import io.github.bropines.tailscaled.admin.api.TailnetSettingKey
import io.github.bropines.tailscaled.admin.api.TailnetSettings
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleChanges
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet

/**
 * Every field of the tailnet's settings, each saved on its own: a credential without the
 * scope for one setting has that row disabled and saying which scope, the others keep
 * working. What opens the tailnet or starts collecting goes through the HIGH gates.
 */
@Composable
fun SettingsTab(state: ConsoleState, vm: AdminConsoleViewModel?, onManageKeys: () -> Unit) {
    val settings = state.settings.value
    if (settings == null) {
        ConfigNotLoaded(state.settings) { vm?.refresh(ConsoleTab.SETTINGS, force = true) }
        return
    }
    val ctx = LocalContext.current
    var picker by rememberSaveable { mutableStateOf<TailnetSettingKey?>(null) }
    var editLink by rememberSaveable { mutableStateOf(false) }

    fun value(key: TailnetSettingKey): Any? = ConsoleChanges.settingValue(settings, key)
    fun set(key: TailnetSettingKey, after: Any) {
        val before = value(key)
        if (before == after) return
        vm?.let { it.propose(SettingsChanges.set(it.text, key, before, after, it.tailnetLabel)) }
    }
    fun canWrite(key: TailnetSettingKey) = state.canWrite(key.scopeArea)
    /** Why a row is off, when it is its own credential's doing rather than the whole profile's. */
    fun note(key: TailnetSettingKey): String? = when {
        value(key) == null -> ctx.getString(R.string.admin_cfg_set_unreadable, key.scopeArea.scopeFor(false))
        state.writeBlock == null && !canWrite(key) -> ctx.getString(R.string.admin_cfg_scope_needed, key.scopeArea.scope)
        else -> null
    }

    @Composable
    fun SwitchRow(key: TailnetSettingKey) = ConfigSwitchRow(
        title = SettingsText.title(ctx, key),
        help = SettingsText.help(ctx, key),
        checked = value(key) as? Boolean,
        enabled = canWrite(key),
        onToggle = { set(key, it) },
        note = note(key),
    )

    @Composable
    fun PickerRow(key: TailnetSettingKey) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(SettingsText.title(ctx, key), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                HelpText(SettingsText.help(ctx, key))
                note(key)?.let { ConfigNote(it) }
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { picker = key }, enabled = canWrite(key) && value(key) != null, shape = MaterialTheme.shapes.medium) {
                Text(SettingsText.show(ctx, key, value(key)), maxLines = 1, softWrap = false)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, null)
            }
        }
    }

    @Composable
    fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoadProblems(state.settings, onRetry = { vm?.refresh(ConsoleTab.SETTINGS, force = true) })

        Card(modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onManageKeys), shape = MaterialTheme.shapes.large) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ctx.getString(R.string.admin_settings_auth_keys_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    HelpText(ctx.getString(R.string.admin_settings_auth_keys_desc))
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }

        ConfigCard(ctx.getString(R.string.admin_settings_access_title)) {
            SwitchRow(TailnetSettingKey.DEVICES_APPROVAL)
            RowDivider()
            SwitchRow(TailnetSettingKey.USERS_APPROVAL)
            RowDivider()
            PickerRow(TailnetSettingKey.DEVICES_KEY_DURATION)
            RowDivider()
            PickerRow(TailnetSettingKey.USERS_EXTERNAL_ROLE)
        }
        ConfigCard(ctx.getString(R.string.admin_settings_software_title)) {
            SwitchRow(TailnetSettingKey.DEVICES_AUTO_UPDATES)
        }
        ConfigCard(ctx.getString(R.string.admin_cfg_set_network)) {
            SwitchRow(TailnetSettingKey.HTTPS)
            RowDivider()
            PickerRow(TailnetSettingKey.ROUTE_SELECTION)
            settings.regionalRoutingOn?.let { ConfigNote(ctx.getString(R.string.admin_cfg_set_regional_routing, ConsoleText.onOff(ctx, it)), NoteTone.INFO) }
        }
        ConfigCard(ctx.getString(R.string.admin_cfg_set_logging)) {
            SwitchRow(TailnetSettingKey.NETWORK_FLOW_LOGGING)
            RowDivider()
            SwitchRow(TailnetSettingKey.POSTURE_IDENTITY)
        }
        ConfigCard(ctx.getString(R.string.admin_cfg_set_policy)) {
            SwitchRow(TailnetSettingKey.ACLS_EXTERNALLY_MANAGED)
            RowDivider()
            // An empty link is unset, not unreadable, when the policy scope lets its neighbour be read.
            val linkNote = if (settings.aclsExternallyManagedOn == null) note(TailnetSettingKey.ACLS_EXTERNAL_LINK)
            else ctx.getString(R.string.admin_cfg_scope_needed, TailnetSettingKey.ACLS_EXTERNAL_LINK.scopeArea.scope)
                .takeIf { state.writeBlock == null && !canWrite(TailnetSettingKey.ACLS_EXTERNAL_LINK) }
            LinkRow(settings, canWrite(TailnetSettingKey.ACLS_EXTERNAL_LINK), linkNote) { editLink = true }
        }
    }

    picker?.let { key ->
        val title = SettingsText.title(ctx, key)
        val options: List<PickerOption<Any>> = when (key) {
            TailnetSettingKey.DEVICES_KEY_DURATION -> (SettingsChanges.keyDurations + listOfNotNull(settings.devicesKeyDurationDays))
                .distinct().sorted().map { PickerOption(it, SettingsText.days(ctx, it)) }
            TailnetSettingKey.USERS_EXTERNAL_ROLE -> listOf(
                PickerOption("none", SettingsText.role(ctx, "none"), Icons.Default.Block),
                PickerOption("admin", SettingsText.role(ctx, "admin"), Icons.Default.AdminPanelSettings),
                PickerOption("member", SettingsText.role(ctx, "member"), Icons.Default.Group),
            )
            TailnetSettingKey.ROUTE_SELECTION -> RouteSelection.all.map { PickerOption(it, ConsoleText.routeSelection(ctx, it)) }
            else -> emptyList()
        }
        PickerSheet(
            title = title,
            options = options,
            selected = value(key),
            onPick = { set(key, it) },
            onDismiss = { picker = null },
        )
    }
    if (editLink) {
        LinkDialog(settings.aclsExternalLink.orEmpty(), onDismiss = { editLink = false }) { link ->
            editLink = false
            set(TailnetSettingKey.ACLS_EXTERNAL_LINK, link)
        }
    }
}

@Composable
private fun LinkRow(settings: TailnetSettings, canWrite: Boolean, note: String?, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(SettingsText.title(ctx, TailnetSettingKey.ACLS_EXTERNAL_LINK), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                settings.aclsExternalLink?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.admin2_settings_unknown),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            HelpText(SettingsText.help(ctx, TailnetSettingKey.ACLS_EXTERNAL_LINK))
            note?.let { ConfigNote(it) }
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onEdit, enabled = canWrite, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Default.Edit, ctx.getString(R.string.admin_cfg_set_acls_link_edit))
        }
    }
}

@Composable
private fun LinkDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    var link by rememberSaveable { mutableStateOf(current) }
    val ok = SettingsChanges.linkOk(link)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_cfg_set_acls_link)) },
        text = {
            parent.Provide {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it.trim() },
                    placeholder = { Text(ctx.getString(R.string.admin_cfg_set_acls_link_placeholder)) },
                    isError = !ok,
                    supportingText = { Text(ctx.getString(if (ok) R.string.admin_cfg_set_acls_link_help else R.string.admin_cfg_set_acls_link_bad)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(link) }, enabled = ok && link != current) { Text(ctx.getString(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}
