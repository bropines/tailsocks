package io.github.bropines.tailscaled.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.RouteSelection
import io.github.bropines.tailscaled.admin.api.TailnetSettingKey
import io.github.bropines.tailscaled.admin.api.TailnetSettings
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet

/**
 * The tailnet's settings, each saved on its own: a credential without the scope for one
 * setting fails that row, not the screen, and every change goes through the safety pipeline
 * (HIGH for what opens the tailnet). regionalRoutingOn is read-only now; routeSelection
 * replaced it.
 */
@Composable
fun TailnetSettingsTabContent(
    state: Loadable<TailnetSettings>,
    canWrite: (TailnetSettingKey) -> Boolean,
    onRetry: () -> Unit,
    onSet: (key: TailnetSettingKey, label: String, value: Any, show: (Any?) -> String) -> Unit,
    onManageKeysClick: () -> Unit,
) {
    val settings = state.value
    if (settings == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            LoadProblems(state, onRetry)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (state.loading) LoadingIndicatorCompat() }
        }
        return
    }
    // A sheet is a window of its own; strings come from here, the parent (wrapContextWithLocale).
    val ctx = LocalContext.current
    val onOff: (Any?) -> String = { ConsoleText.onOff(ctx, it as? Boolean) }
    fun daysLabel(days: Int): String = ctx.resources.getQuantityString(R.plurals.pickers_days, days, days)
    fun roleLabel(role: String?): String = when (role) {
        "none" -> ctx.getString(R.string.pickers_role_none)
        "admin" -> ctx.getString(R.string.pickers_role_admin)
        "member" -> ctx.getString(R.string.pickers_role_member)
        null, "" -> ctx.getString(R.string.admin2_settings_unknown)
        else -> role
    }

    var durationPicker by remember { mutableStateOf(false) }
    var rolePicker by remember { mutableStateOf(false) }
    var routePicker by remember { mutableStateOf(false) }

    if (durationPicker) {
        val label = ctx.getString(R.string.admin_settings_key_expiry_title)
        PickerSheet(
            title = label,
            options = listOf(1, 7, 30, 90, 180).map { PickerOption(it, daysLabel(it)) },
            selected = settings.devicesKeyDurationDays,
            onPick = { onSet(TailnetSettingKey.DEVICES_KEY_DURATION, label, it) { v -> (v as? Int)?.let(::daysLabel) ?: ctx.getString(R.string.admin2_settings_unknown) } },
            onDismiss = { durationPicker = false },
        )
    }
    if (rolePicker) {
        val label = ctx.getString(R.string.admin_settings_external_tailnets_title)
        PickerSheet(
            title = ctx.getString(R.string.admin_settings_allowed_role),
            options = listOf(
                PickerOption("none", roleLabel("none"), Icons.Default.Block),
                PickerOption("admin", roleLabel("admin"), Icons.Default.AdminPanelSettings),
                PickerOption("member", roleLabel("member"), Icons.Default.Group),
            ),
            selected = settings.usersRoleAllowedToJoinExternalTailnets,
            onPick = { onSet(TailnetSettingKey.USERS_EXTERNAL_ROLE, label, it) { v -> roleLabel(v as? String) } },
            onDismiss = { rolePicker = false },
        )
    }
    if (routePicker) {
        val label = ctx.getString(R.string.admin2_settings_route_selection_title)
        PickerSheet(
            title = label,
            options = RouteSelection.all.map { PickerOption(it, ConsoleText.routeSelection(ctx, it)) },
            selected = settings.routeSelection,
            onPick = { onSet(TailnetSettingKey.ROUTE_SELECTION, label, it) { v -> ConsoleText.routeSelection(ctx, v as? String) } },
            onDismiss = { routePicker = false },
        )
    }

    @Composable
    fun SwitchRow(key: TailnetSettingKey, titleRes: Int, descRes: Int, value: Boolean?) {
        val title = ctx.getString(titleRes)
        val enabled = canWrite(key) && value != null
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .toggleable(value = value == true, enabled = enabled, role = Role.Switch) { onSet(key, title, it, onOff) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                HelpText(ctx.getString(descRes), inClickableRow = true)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = value == true, onCheckedChange = null, enabled = enabled)
        }
    }

    @Composable
    fun PickerRow(title: String, desc: String?, value: String, enabled: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (desc != null) HelpText(desc)
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium) {
                Text(value, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, null)
            }
        }
    }

    @Composable
    fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoadProblems(state, onRetry)

        Card(modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onManageKeysClick() }, shape = MaterialTheme.shapes.large) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ctx.getString(R.string.admin_settings_auth_keys_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    HelpText(ctx.getString(R.string.admin_settings_auth_keys_desc))
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }

        Section(ctx.getString(R.string.admin_settings_access_title)) {
            SwitchRow(TailnetSettingKey.DEVICES_APPROVAL, R.string.admin_settings_device_approval_title, R.string.admin_settings_device_approval_desc, settings.devicesApprovalOn)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(TailnetSettingKey.USERS_APPROVAL, R.string.admin_settings_user_approval_title, R.string.admin_settings_user_approval_desc, settings.usersApprovalOn)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PickerRow(
                ctx.getString(R.string.admin_settings_key_expiry_title),
                ctx.getString(R.string.admin_settings_key_expiry_desc),
                settings.devicesKeyDurationDays?.let(::daysLabel) ?: ctx.getString(R.string.admin2_settings_unknown),
                canWrite(TailnetSettingKey.DEVICES_KEY_DURATION),
            ) { durationPicker = true }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PickerRow(
                ctx.getString(R.string.admin_settings_external_tailnets_title),
                ctx.getString(R.string.admin_settings_external_tailnets_desc),
                roleLabel(settings.usersRoleAllowedToJoinExternalTailnets),
                canWrite(TailnetSettingKey.USERS_EXTERNAL_ROLE),
            ) { rolePicker = true }
        }

        Section(ctx.getString(R.string.admin_settings_software_title)) {
            SwitchRow(TailnetSettingKey.DEVICES_AUTO_UPDATES, R.string.admin_settings_auto_updates_title, R.string.admin_settings_auto_updates_desc, settings.devicesAutoUpdatesOn)
        }

        Section(ctx.getString(R.string.admin_settings_network_title)) {
            SwitchRow(TailnetSettingKey.HTTPS, R.string.admin2_settings_https_title, R.string.admin2_settings_https_desc, settings.httpsEnabled)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PickerRow(
                ctx.getString(R.string.admin2_settings_route_selection_title),
                ctx.getString(R.string.admin2_settings_route_selection_desc),
                ConsoleText.routeSelection(ctx, settings.routeSelection),
                canWrite(TailnetSettingKey.ROUTE_SELECTION),
            ) { routePicker = true }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(TailnetSettingKey.NETWORK_FLOW_LOGGING, R.string.admin_settings_flow_logging_title, R.string.admin_settings_flow_logging_desc, settings.networkFlowLoggingOn)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(TailnetSettingKey.POSTURE_IDENTITY, R.string.admin_settings_posture_title, R.string.admin_settings_posture_desc, settings.postureIdentityCollectionOn)
        }

        if (settings.aclsExternallyManagedOn == true) {
            HelpText(ctx.getString(R.string.admin2_settings_acls_external) + (settings.aclsExternalLink?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""))
        }
    }
}
