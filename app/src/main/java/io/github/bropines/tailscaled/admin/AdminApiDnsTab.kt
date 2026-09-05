package io.github.bropines.tailscaled.admin
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

@Composable
fun DnsTabContent(
    magicDns: Boolean,
    nameservers: List<String>,
    splitDns: Map<String, List<String>>,
    searchPaths: List<String>,
    onMagicDnsChanged: (Boolean) -> Unit,
    onApplyNameservers: (List<String>) -> Unit,
    onUpdateSplitDns: (String, List<String>?) -> Unit,
    onApplySearchPaths: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val nsListState = remember(nameservers) { mutableStateListOf(*nameservers.toTypedArray()) }
    val searchPathsState = remember(searchPaths) { mutableStateListOf(*searchPaths.toTypedArray()) }

    var newNs by remember { mutableStateOf("") }
    var newSearchPath by remember { mutableStateOf("") }

    // Split DNS Form State
    var splitDomain by remember { mutableStateOf("") }
    var splitNameservers by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // MagicDNS Status Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.admin_dns_magic_dns_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.admin_dns_magic_dns_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Switch(checked = magicDns, onCheckedChange = onMagicDnsChanged)
            }
        }

        // Global Nameservers Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.admin_dns_global_ns_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                if (nsListState.isEmpty()) {
                    Text(stringResource(R.string.admin_dns_no_custom_ns), color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        nsListState.forEach { ns ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // The address is arbitrary text: give it the slack
                                // and keep the delete button on the row.
                                Text(
                                    ns,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { nsListState.remove(ns) }) {
                                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newNs,
                        onValueChange = { newNs = it },
                        placeholder = { Text(stringResource(R.string.admin_dns_ns_placeholder)) },
                        singleLine = true,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Button(
                        onClick = {
                            if (newNs.isNotBlank() && !nsListState.contains(newNs.trim())) {
                                nsListState.add(newNs.trim())
                                newNs = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.action_add))
                    }
                }

                val listChanged = nsListState.toList() != nameservers
                Button(
                    onClick = { onApplyNameservers(nsListState.toList()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = listChanged,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Done, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.admin_dns_apply_ns))
                }
            }
        }

        // Split DNS Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.admin_dns_split_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                if (splitDns.isEmpty()) {
                    Text(stringResource(R.string.admin_dns_no_split), color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        splitDns.forEach { (domain, ns) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(domain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(ns.joinToString(", "), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onUpdateSplitDns(domain, null) }) {
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))

                Text(stringResource(R.string.admin_dns_add_split_route), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = splitDomain,
                    onValueChange = { splitDomain = it },
                    label = { Text(stringResource(R.string.admin_dns_domain_label)) },
                    placeholder = { Text(stringResource(R.string.admin_dns_domain_placeholder)) },
                    singleLine = true,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = splitNameservers,
                    onValueChange = { splitNameservers = it },
                    label = { Text(stringResource(R.string.admin_dns_ns_list_label)) },
                    placeholder = { Text(stringResource(R.string.admin_dns_ns_list_placeholder)) },
                    singleLine = true,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                Button(
                    onClick = {
                        val nsList = splitNameservers.split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        if (splitDomain.isBlank() || nsList.isEmpty()) {
                            Toast.makeText(context, context.getString(R.string.admin_dns_domain_and_ns_required), Toast.LENGTH_SHORT).show()
                        } else {
                            onUpdateSplitDns(splitDomain.trim(), nsList)
                            splitDomain = ""
                            splitNameservers = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.admin_dns_add_split_route))
                }
            }
        }

        // DNS Search Domains Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.admin_dns_search_domains_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                if (searchPathsState.isEmpty()) {
                    Text(stringResource(R.string.admin_dns_no_search_domains), color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        searchPathsState.forEach { path ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // The search domain is arbitrary text: give it the
                                // slack and keep the delete button on the row.
                                Text(
                                    path,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { searchPathsState.remove(path) }) {
                                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newSearchPath,
                        onValueChange = { newSearchPath = it },
                        placeholder = { Text(stringResource(R.string.admin_dns_search_domain_placeholder)) },
                        singleLine = true,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Button(
                        onClick = {
                            if (newSearchPath.isNotBlank() && !searchPathsState.contains(newSearchPath.trim())) {
                                searchPathsState.add(newSearchPath.trim())
                                newSearchPath = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.action_add))
                    }
                }

                val pathsChanged = searchPathsState.toList() != searchPaths
                Button(
                    onClick = { onApplySearchPaths(searchPathsState.toList()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = pathsChanged,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Done, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.admin_dns_apply_search))
                }
            }
        }

        // Tailnet Name Rename Card
        val uriHandler = LocalUriHandler.current
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.admin_dns_tailnet_name_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.admin_dns_tailnet_name_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        try {
                            uriHandler.openUri("https://login.tailscale.com/admin/settings/general")
                        } catch (e: Exception) {
                            Toast.makeText(context, context.getString(R.string.cannot_open_browser), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.admin_dns_rename_web))
                }
            }
        }
    }
}
