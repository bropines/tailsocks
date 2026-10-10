package io.github.bropines.tailscaled.admin.dns

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.CardPage
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.ReadableIf
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.DnsConfiguration
import io.github.bropines.tailscaled.admin.api.DnsResolver
import io.github.bropines.tailscaled.admin.cardPage
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.settings.CONFIG_CARD_MIN_WIDTH
import io.github.bropines.tailscaled.admin.settings.ConfigCard
import io.github.bropines.tailscaled.admin.settings.ConfigNote
import io.github.bropines.tailscaled.admin.settings.ConfigNotLoaded
import io.github.bropines.tailscaled.admin.settings.ConfigSwitchRow
import io.github.bropines.tailscaled.admin.settings.NoteTone
import io.github.bropines.tailscaled.ui.CardColumns
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.rememberWindowLayout

/**
 * The tailnet's DNS: MagicDNS and Override local DNS, the global nameservers (each with its
 * "use with an exit node" flag), split DNS and the search domains — from /dns/configuration,
 * or the four older endpoints where a server has only those (and then without what only the
 * combined one sets). Every field is checked before a change is proposed; every change goes
 * through the gates, the ones that take names or resolvers away as HIGH.
 */
@Composable
fun DnsTab(state: ConsoleState, vm: AdminConsoleViewModel?) {
    val cfg = state.dns.value
    if (cfg == null) {
        ConfigNotLoaded(state.dns) { vm?.refresh(ConsoleTab.DNS, force = true) }
        return
    }
    val ctx = LocalContext.current
    val combined = state.caps?.has(BackendFeature.DNS_CONFIGURATION) != false
    val canWrite = state.canWrite(AdminArea.DNS)
    val propose: (DnsEdit) -> Unit = { edit -> vm?.let { it.propose(DnsChanges.plan(it.text, edit, cfg, combined, it.tailnetLabel)) } }
    val overrideOn = cfg.preferences.overrideLocalDNS == true
    // Wider than a phone the cards keep to a readable column, and from an expanded window up
    // stand in columns: no switch a tablet's width from its label.
    val page = rememberWindowLayout().cardPage

    ReadableIf(page == CardPage.READABLE) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LoadProblems(state.dns, onRetry = { vm?.refresh(ConsoleTab.DNS, force = true) })
            if (!canWrite && state.writeBlock == null) ConfigNote(ctx.getString(R.string.admin_cfg_scope_missing, AdminArea.DNS.scope), NoteTone.LOCKED)

            @Composable
            fun Cards() {
                ConfigCard(ctx.getString(R.string.admin_cfg_dns_preferences)) {
                    ConfigSwitchRow(
                        title = ctx.getString(R.string.admin_cfg_dns_magic),
                        help = ctx.getString(R.string.admin_cfg_dns_magic_help),
                        checked = cfg.magicDns,
                        enabled = canWrite,
                        onToggle = { propose(DnsEdit.MagicDns(it)) },
                        note = if (!cfg.magicDns && cfg.nameservers.isEmpty()) ctx.getString(R.string.admin_cfg_dns_magic_needs_ns) else null,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val needsNs = !overrideOn && cfg.nameservers.isEmpty()
                    ConfigSwitchRow(
                        title = ctx.getString(R.string.admin_cfg_dns_override),
                        help = ctx.getString(R.string.admin_cfg_dns_override_help),
                        checked = if (combined) overrideOn else null,
                        enabled = canWrite && combined && !needsNs,
                        onToggle = { propose(DnsEdit.OverrideLocal(it)) },
                        note = when {
                            !combined -> ctx.getString(R.string.admin_cfg_dns_legacy_only)
                            needsNs -> ctx.getString(R.string.admin_cfg_dns_override_needs_ns)
                            else -> null
                        },
                    )
                }

                NameserversCard(cfg, combined, canWrite) { propose(DnsEdit.Nameservers(it)) }
                SplitDnsCard(cfg, combined, canWrite, propose)
                SearchPathsCard(cfg, canWrite) { propose(DnsEdit.SearchPaths(it)) }

                val uri = LocalUriHandler.current
                ConfigCard(ctx.getString(R.string.admin_dns_tailnet_name_title)) {
                    HelpText(ctx.getString(R.string.admin_dns_tailnet_name_desc))
                    OutlinedButton(
                        onClick = {
                            runCatching { uri.openUri("https://login.tailscale.com/admin/dns") }
                                .onFailure { Toast.makeText(ctx, ctx.getString(R.string.cannot_open_browser), Toast.LENGTH_SHORT).show() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Default.OpenInBrowser, null)
                        Spacer(Modifier.width(8.dp))
                        Text(ctx.getString(R.string.admin_dns_rename_web))
                    }
                }
            }
            if (page == CardPage.COLUMNS) CardColumns(minColumnWidth = CONFIG_CARD_MIN_WIDTH, spacing = 16.dp) { Cards() } else Cards()
        }
    }
}

fun dnsError(ctx: Context, e: DnsInputError): String = ctx.getString(
    when (e) {
        DnsInputError.EMPTY -> R.string.admin_cfg_dns_err_empty
        DnsInputError.NOT_RESOLVER -> R.string.admin_cfg_dns_err_resolver
        DnsInputError.DOH_NOT_HTTPS -> R.string.admin_cfg_dns_err_doh
        DnsInputError.BAD_DOMAIN -> R.string.admin_cfg_dns_err_domain
        DnsInputError.DUPLICATE -> R.string.admin_cfg_dns_err_duplicate
    }
)

/** The global nameservers, edited here and applied as one change. */
@Composable
private fun NameserversCard(cfg: DnsConfiguration, combined: Boolean, canWrite: Boolean, onApply: (List<DnsResolver>) -> Unit) {
    val ctx = LocalContext.current
    val draft = remember(cfg.nameservers) { mutableStateListOf(*cfg.nameservers.toTypedArray()) }
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<DnsInputError?>(null) }
    val changed = draft.toList() != cfg.nameservers

    ConfigCard(ctx.getString(R.string.admin_dns_global_ns_title)) {
        HelpText(ctx.getString(R.string.admin_cfg_dns_ns_help))
        if (draft.isEmpty()) Text(ctx.getString(R.string.admin_dns_no_custom_ns), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        draft.toList().forEachIndexed { i, r ->
            ResolverRow(
                r, combined, canWrite,
                onExitNode = { on -> draft[i] = r.copy(useWithExitNode = on) },
                onRemove = { draft.removeAt(i) },
            )
        }
        if (canWrite) {
            AddField(
                value = input,
                onValue = { input = it; error = null },
                placeholder = ctx.getString(R.string.admin_cfg_dns_ns_placeholder),
                error = error?.let { dnsError(ctx, it) },
                keyboard = KeyboardType.Uri,
                onAdd = {
                    val c = DnsInput.resolver(input, draft.map { it.address })
                    if (c.ok) { draft += DnsResolver(c.value!!, if (combined) false else null); input = "" } else error = c.error
                },
            )
            if (changed) ApplyRow(onReset = { draft.clear(); draft.addAll(cfg.nameservers) }, onApply = { onApply(draft.toList()) })
            if (changed && draft.isEmpty() && cfg.nameservers.isNotEmpty() && cfg.magicDns) {
                ConfigNote(ctx.getString(R.string.admin_cfg_dns_ns_none_magic_effect), NoteTone.WARNING)
            }
        }
    }
}

@Composable
private fun ResolverRow(r: DnsResolver, combined: Boolean, canWrite: Boolean, onExitNode: (Boolean) -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            r.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        if (combined) {
            FilterChip(
                selected = r.useWithExitNode == true,
                onClick = { onExitNode(r.useWithExitNode != true) },
                enabled = canWrite,
                label = { Text(ctx.getString(R.string.admin_cfg_dns_exit_chip), style = MaterialTheme.typography.labelSmall) },
            )
        }
        if (canWrite) IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, ctx.getString(R.string.admin_cfg_dns_remove, r.address), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AddField(value: String, onValue: (String) -> Unit, placeholder: String, error: String?, keyboard: KeyboardType, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            placeholder = { Text(placeholder) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onAdd, enabled = value.isNotBlank(), shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(top = 4.dp)) {
            Text(ctx.getString(R.string.action_add))
        }
    }
}

@Composable
private fun ApplyRow(onReset: () -> Unit, onApply: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) { Text(ctx.getString(R.string.admin_cfg_reset)) }
        Button(onClick = onApply, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Default.Done, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(ctx.getString(R.string.admin_cfg_apply))
        }
    }
}

/** Split DNS: a domain each, removed or added as its own change. */
@Composable
private fun SplitDnsCard(cfg: DnsConfiguration, combined: Boolean, canWrite: Boolean, propose: (DnsEdit) -> Unit) {
    val ctx = LocalContext.current
    var domain by rememberSaveable { mutableStateOf("") }
    var servers by rememberSaveable { mutableStateOf("") }
    var withExit by rememberSaveable { mutableStateOf(false) }
    var domainError by remember { mutableStateOf<DnsInputError?>(null) }
    var serversError by remember { mutableStateOf<DnsInputError?>(null) }

    ConfigCard(ctx.getString(R.string.admin_dns_split_title)) {
        HelpText(ctx.getString(R.string.admin_cfg_dns_split_help))
        if (cfg.splitDns.isEmpty()) Text(ctx.getString(R.string.admin_dns_no_split), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        cfg.splitDns.toSortedMap().forEach { (d, rs) ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(d, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(
                        rs.joinToString(", ") { DnsChanges.show(ctx, it) },
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canWrite) IconButton(onClick = { propose(DnsEdit.Split(d, null)) }) {
                    Icon(Icons.Default.Close, ctx.getString(R.string.admin_cfg_dns_remove, d), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (canWrite) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it.trim(); domainError = null },
                label = { Text(ctx.getString(R.string.admin_dns_domain_label)) },
                placeholder = { Text(ctx.getString(R.string.admin_dns_domain_placeholder)) },
                isError = domainError != null,
                supportingText = domainError?.let { { Text(dnsError(ctx, it)) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = servers,
                onValueChange = { servers = it; serversError = null },
                label = { Text(ctx.getString(R.string.admin_dns_ns_list_label)) },
                placeholder = { Text(ctx.getString(R.string.admin_dns_ns_list_placeholder)) },
                isError = serversError != null,
                supportingText = serversError?.let { { Text(dnsError(ctx, it)) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            if (combined) Row(
                Modifier.fillMaxWidth().toggleable(value = withExit, role = Role.Checkbox) { withExit = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = withExit, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                Text(ctx.getString(R.string.admin_cfg_dns_exit_label), style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    val d = DnsInput.domain(domain)
                    val (list, err) = DnsInput.resolvers(servers)
                    domainError = d.error
                    serversError = err
                    if (d.ok && err == null) {
                        propose(DnsEdit.Split(d.value!!, list.map { DnsResolver(it, if (combined) withExit else null) }))
                        domain = ""
                        servers = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin_dns_add_split_route))
            }
        }
    }
}

@Composable
private fun SearchPathsCard(cfg: DnsConfiguration, canWrite: Boolean, onApply: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val draft = remember(cfg.searchPaths) { mutableStateListOf(*cfg.searchPaths.toTypedArray()) }
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<DnsInputError?>(null) }
    val changed = draft.toList() != cfg.searchPaths

    ConfigCard(ctx.getString(R.string.admin_dns_search_domains_title)) {
        HelpText(ctx.getString(R.string.admin_cfg_dns_search_help))
        if (draft.isEmpty()) Text(ctx.getString(R.string.admin_dns_no_search_domains), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        draft.toList().forEachIndexed { i, p ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                if (canWrite) IconButton(onClick = { draft.removeAt(i) }) {
                    Icon(Icons.Default.Close, ctx.getString(R.string.admin_cfg_dns_remove, p), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (canWrite) {
            AddField(
                value = input,
                onValue = { input = it; error = null },
                placeholder = ctx.getString(R.string.admin_dns_search_domain_placeholder),
                error = error?.let { dnsError(ctx, it) },
                keyboard = KeyboardType.Uri,
                onAdd = {
                    val c = DnsInput.domain(input, draft)
                    if (c.ok) { draft += c.value!!; input = "" } else error = c.error
                },
            )
            if (changed) Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { draft.clear(); draft.addAll(cfg.searchPaths) }) { Text(ctx.getString(R.string.admin_cfg_reset)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onApply(draft.toList()) }, shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Done, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(ctx.getString(R.string.admin_cfg_apply))
                }
            }
        }
    }
}
