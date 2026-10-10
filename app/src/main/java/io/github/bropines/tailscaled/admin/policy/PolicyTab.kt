package io.github.bropines.tailscaled.admin.policy

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.safety.ReadOnlyBanner
import io.github.bropines.tailscaled.admin.settings.ConfigLoading
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.ReadableContentWidth
import io.github.bropines.tailscaled.ui.rememberWindowLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The policy file: read with its comments as the server keeps them, coloured, numbered and
 * searchable; edited in [PolicyEditorHost]; asked "who can reach…" in [WhoCanReachSheet].
 * Read-only, and saying why, when the policy is managed outside the console or the
 * credential may only read it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PolicyTab(state: ConsoleState, vm: AdminConsoleViewModel?) {
    val ctx = LocalContext.current
    val policy = state.policy
    val file = policy.file.value
    val colors = rememberPolicyColors()
    val lines = remember(file?.text, colors) { file?.let { highlightLines(it.text, colors) }.orEmpty() }
    val plain = remember(file?.text) { file?.text?.let { LineDiff.lines(it) }.orEmpty() }
    var query by rememberSaveable { mutableStateOf("") }
    var current by rememberSaveable { mutableIntStateOf(0) }
    var reachOpen by rememberSaveable { mutableStateOf(false) }
    val hits = remember(plain, query) { plain.indices.filter { matchesIn(plain[it], query).isNotEmpty() } }
    val list = rememberLazyListState()

    val external = state.settings.value?.aclsExternallyManagedOn == true
    val externalLink = state.settings.value?.aclsExternalLink?.takeIf { it.isNotBlank() }
    val credentialMayWrite = state.caps?.canWrite(AdminArea.POLICY) != false
    val canEdit = file != null && state.canWrite(AdminArea.POLICY) && !external

    val headerItems = 2
    LaunchedEffect(current, hits) {
        hits.getOrNull(current)?.let { list.animateScrollToItem(headerItems + it) }
    }

    if (file == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            LoadProblems(policy.file, onRetry = { vm?.policy?.load(force = true) })
            if (policy.file.loading) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { ConfigLoading() }
        }
        return
    }

    val gutter = gutterWidth(plain.size)
    // Wider than a phone the file keeps the width — code wants it — and the card over it, words
    // and buttons, keeps to a readable one: no revert button a tablet's width from its line.
    val readable = if (rememberWindowLayout().multiColumn) Modifier.widthIn(max = ReadableContentWidth) else Modifier
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(readable.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadProblems(policy.file, onRetry = { vm?.policy?.load(force = true) })
                when {
                    external -> ExternalBanner(externalLink)
                    !credentialMayWrite && state.writeBlock == null -> ReadOnlyBanner(
                        ctx.getString(R.string.admin_cfg_policy_ro_scope_title), ctx.getString(R.string.admin_cfg_policy_ro_scope_text),
                    )
                }
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Policy, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ctx.getString(R.string.admin_cfg_policy_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    ctx.resources.getQuantityString(R.plurals.admin_cfg_policy_lines, plain.size, plain.size) +
                                        (file.etag?.let { " · " + ctx.getString(R.string.admin_cfg_policy_version, shortEtag(it)) } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HelpText(ctx.getString(R.string.admin_cfg_policy_help))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (canEdit) Button(onClick = { vm?.policy?.openEditor() }, shape = MaterialTheme.shapes.medium) {
                                Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(ctx.getString(R.string.admin_cfg_policy_edit))
                            }
                            OutlinedButton(onClick = { reachOpen = true }, shape = MaterialTheme.shapes.medium) {
                                Icon(Icons.Default.TravelExplore, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(ctx.getString(R.string.admin_cfg_reach_open))
                            }
                        }
                        val prev = policy.previous
                        if (prev != null && canEdit) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ctx.getString(R.string.admin_cfg_policy_revert), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    HelpText(ctx.getString(R.string.admin_cfg_policy_revert_help, formatTime(prev.savedAt)))
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { vm?.policy?.revert() }, shape = MaterialTheme.shapes.medium) {
                                    Icon(Icons.Default.Restore, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(ctx.getString(R.string.admin_cfg_policy_revert_action))
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            SearchBar(query, hits.size, current, onQuery = { query = it; current = 0 }, onMove = { d ->
                if (hits.isNotEmpty()) current = (current + d).mod(hits.size)
            })
        }
        items(lines.size) { i ->
            val first = i == 0
            val last = i == lines.lastIndex
            val shape = when {
                first && last -> MaterialTheme.shapes.medium
                first -> MaterialTheme.shapes.medium.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
                last -> MaterialTheme.shapes.medium.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp))
                else -> null
            }
            CodeLine(
                number = i + 1,
                text = withMatches(lines[i], plain[i], query, if (hits.getOrNull(current) == i) matchesIn(plain[i], query).firstOrNull() else null, colors),
                gutter = gutter,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, shape ?: RectangleShape)
                    .padding(top = if (first) 8.dp else 0.dp, bottom = if (last) 8.dp else 0.dp),
            )
        }
    }

    PolicyEditorHost(state, vm)
    if (reachOpen) WhoCanReachSheet(state, vm, onDismiss = { reachOpen = false; vm?.policy?.clearReach() })
}

@Composable
private fun ExternalBanner(link: String?) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ReadOnlyBanner(ctx.getString(R.string.admin_cfg_policy_ro_external_title), ctx.getString(R.string.admin_cfg_policy_ro_external_text))
        if (link != null) OutlinedButton(
            onClick = { runCatching { uri.openUri(link) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.cannot_open_browser), Toast.LENGTH_SHORT).show() } },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.OpenInBrowser, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(link, maxLines = 1)
        }
    }
}

@Composable
private fun SearchBar(query: String, count: Int, current: Int, onQuery: (String) -> Unit, onMove: (Int) -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text(ctx.getString(R.string.admin_cfg_policy_search)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (query.isNotEmpty()) ({
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, ctx.getString(R.string.admin_cfg_policy_search_clear)) }
            }) else null,
            supportingText = if (query.isNotBlank()) ({
                Text(
                    if (count == 0) ctx.getString(R.string.admin_cfg_policy_search_none)
                    else ctx.getString(R.string.admin_cfg_policy_search_count, current + 1, count)
                )
            }) else null,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onMove(-1) }, enabled = count > 0) { Icon(Icons.Default.KeyboardArrowUp, ctx.getString(R.string.admin_cfg_policy_search_prev)) }
        IconButton(onClick = { onMove(1) }, enabled = count > 0) { Icon(Icons.Default.KeyboardArrowDown, ctx.getString(R.string.admin_cfg_policy_search_next)) }
    }
}

/** The code's text style: the body's small size in a fixed-width face. */
@Composable
fun codeStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

/** How wide the line numbers of a [lines]-line file are. */
@Composable
fun gutterWidth(lines: Int): Dp {
    val measurer = rememberTextMeasurer()
    val style = codeStyle()
    val density = LocalDensity.current
    val digits = lines.coerceAtLeast(1).toString().length.coerceAtLeast(2)
    return remember(digits, style) { with(density) { measurer.measure("0".repeat(digits), style).size.width.toDp() } }
}

/** One numbered line of code; the number dimmed, outside the text's selection. */
@Composable
fun CodeLine(number: Int, text: AnnotatedString, gutter: Dp, modifier: Modifier = Modifier, marker: String? = null) {
    val style = codeStyle()
    Row(modifier.padding(horizontal = 8.dp)) {
        Text(number.toString(), style = style, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.End, modifier = Modifier.width(gutter))
        Spacer(Modifier.width(8.dp))
        if (marker != null) {
            Text(marker, style = style, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = style.copy(color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.weight(1f))
    }
}

/** [line] with the search's matches marked, [currentMatch] stronger. */
private fun withMatches(line: AnnotatedString, plain: String, query: String, currentMatch: IntRange?, c: PolicyColors): AnnotatedString {
    val ranges = matchesIn(plain, query)
    if (ranges.isEmpty() || plain.length != line.length) return line
    val b = AnnotatedString.Builder(line)
    ranges.forEach { r -> b.addStyle(SpanStyle(background = if (r == currentMatch) c.currentMatch else c.match), r.first, r.last + 1) }
    return b.toAnnotatedString()
}

/** An ETag's first characters, enough to tell two versions apart. */
fun shortEtag(etag: String): String = etag.trim().removePrefix("W/").trim('"').take(8)

private fun formatTime(ms: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))
