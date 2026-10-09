package io.github.bropines.tailscaled.admin.devices

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiDevice

/** A warning in a dialog: an icon and the whole text, which a dialog has room for. */
@Composable
internal fun DialogWarning(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

// ------------------------------------------------------------------ rename

/**
 * The device's own label, pre-filled — never "host.tail1234", which made a rename into a dotted
 * name. Emptied, it says that the name goes back to the hostname's, and the button says so too.
 */
@Composable
internal fun RenameDialog(device: ApiDevice, onDismiss: () -> Unit, onRename: (String) -> Unit, onReset: () -> Unit) {
    val parent = parentLocale()
    val ctx = parent.context
    var name by rememberSaveable(device.pathId) { mutableStateOf(device.shortName) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_dev_rename_title, device.shortName)) },
        text = { WithLocale(parent) { RenameFields(device, name) { name = it } } },
        confirmButton = {
            Button(onClick = { if (trimmed.isEmpty()) onReset() else onRename(trimmed) }, enabled = DeviceNames.renameAllowed(device, trimmed)) {
                Text(ctx.getString(if (trimmed.isEmpty()) R.string.admin_dev_rename_reset else R.string.admin_dev_rename_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

@Composable
internal fun RenameFields(device: ApiDevice, name: String, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val trimmed = name.trim()
    val invalid = trimmed.isNotEmpty() && !DeviceNames.isValid(trimmed)
    OutlinedTextField(
        value = name,
        onValueChange = { onChange(it.replace(' ', '-')) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        label = { Text(ctx.getString(R.string.admin_dev_rename_label)) },
        isError = invalid,
        supportingText = {
            Text(
                when {
                    trimmed.isEmpty() -> ctx.getString(R.string.admin_dev_rename_reset_help, device.hostname?.takeIf { it.isNotBlank() } ?: device.shortName)
                    invalid -> ctx.getString(R.string.admin_dev_rename_invalid)
                    else -> ctx.getString(R.string.admin2_device_rename_help, device.dnsSuffix ?: "ts.net")
                }
            )
        },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ------------------------------------------------------------------ tags

/**
 * Tags as chips — the policy's and the device's own — each on or off, plus a field that adds a
 * new one. The warnings follow the choice as it is made: tagging a person's device, or taking
 * every tag off a tagged one.
 */
@Composable
internal fun TagsDialog(device: ApiDevice, policyTags: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val parent = parentLocale()
    val ctx = parent.context
    var selected by rememberSaveable(device.pathId) { mutableStateOf(device.tags) }
    var extra by rememberSaveable(device.pathId) { mutableStateOf(emptyList<String>()) }
    var typed by rememberSaveable(device.pathId) { mutableStateOf("") }
    val offered = (policyTags + device.tags + extra).distinct().sorted()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_dev_tags_title, device.shortName)) },
        text = {
            WithLocale(parent) {
                TagsFields(
                    device = device,
                    offered = offered,
                    selected = selected,
                    typed = typed,
                    onToggle = { tag -> selected = if (tag in selected) selected - tag else selected + tag },
                    onTyped = { typed = it },
                    onAdd = { tag ->
                        if (tag !in offered) extra = extra + tag
                        if (tag !in selected) selected = selected + tag
                        typed = ""
                    },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(selected) }, enabled = selected.toSet() != device.tags.toSet()) {
                Text(ctx.getString(R.string.admin_dev_tags_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsFields(
    device: ApiDevice,
    offered: List<String>,
    selected: List<String>,
    typed: String,
    onToggle: (String) -> Unit,
    onTyped: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    val ctx = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (device.tags.isEmpty() && selected.isNotEmpty()) {
            DialogWarning(ctx.getString(R.string.admin_dev_tags_owner_warning, device.user?.takeIf { it.isNotBlank() } ?: device.shortName))
        }
        if (device.tags.isNotEmpty() && selected.isEmpty()) DialogWarning(ctx.getString(R.string.admin_dev_tags_clear_warning))
        TagPicker(offered, selected, typed, onToggle, onTyped, onAdd)
    }
}

/** The chips and the field that adds one; shared by the device's tags and the bulk editor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagPicker(
    offered: List<String>,
    selected: List<String>,
    typed: String,
    onToggle: (String) -> Unit,
    onTyped: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val candidate = BulkTags.normalize(typed)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (offered.isEmpty()) {
            Text(ctx.getString(R.string.admin2_device_tags_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                offered.forEach { tag ->
                    FilterChip(selected = tag in selected, onClick = { onToggle(tag) }, label = { Text(tag.removePrefix("tag:")) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = typed,
                onValueChange = { onTyped(it.replace(" ", "")) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                label = { Text(ctx.getString(R.string.admin2_device_tags_new)) },
                placeholder = { Text("tag:server") },
                isError = typed.isNotBlank() && candidate == null,
                supportingText = { Text(ctx.getString(R.string.admin2_device_tags_invalid)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = candidate != null, onClick = { candidate?.let(onAdd) }) { Text(ctx.getString(R.string.action_add)) }
        }
    }
}

// ------------------------------------------------------------------ IPv4

internal fun ipv4Problem(ctx: Context, check: Ipv4Check): String? = when (check.problem) {
    null -> null
    Ipv4Problem.MALFORMED -> ctx.getString(R.string.admin_dev_ip_malformed)
    Ipv4Problem.OUTSIDE_RANGE -> ctx.getString(R.string.admin_dev_ip_outside)
    Ipv4Problem.RESERVED -> ctx.getString(R.string.admin_dev_ip_reserved)
    Ipv4Problem.TAKEN -> ctx.getString(R.string.admin_dev_ip_taken, check.takenBy.orEmpty())
    Ipv4Problem.UNCHANGED -> ctx.getString(R.string.admin_dev_ip_unchanged)
}

/**
 * A new IPv4 address, checked as it is typed: inside 100.64.0.0/10, not one Tailscale keeps,
 * not another device's or service's. Continue hands it to the gates (HIGH).
 */
@Composable
internal fun Ipv4Dialog(device: ApiDevice, taken: Map<String, String>, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    val parent = parentLocale()
    val ctx = parent.context
    var text by rememberSaveable(device.pathId) { mutableStateOf(device.ipv4.orEmpty()) }
    val check = Ipv4Rules.check(text, device.ipv4, taken)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.admin_dev_ip_title, device.shortName)) },
        text = { WithLocale(parent) { Ipv4Fields(text, check) { text = it } } },
        confirmButton = { Button(onClick = { onSet(text.trim()) }, enabled = check.ok) { Text(ctx.getString(R.string.admin_dev_ip_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

@Composable
internal fun Ipv4Fields(text: String, check: Ipv4Check, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val problem = ipv4Problem(ctx, check)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(15)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            label = { Text(ctx.getString(R.string.admin_dev_ip_label)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            isError = problem != null && check.problem != Ipv4Problem.UNCHANGED,
            supportingText = problem?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(ctx.getString(R.string.admin_dev_ip_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
