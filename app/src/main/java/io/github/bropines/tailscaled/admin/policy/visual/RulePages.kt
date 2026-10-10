package io.github.bropines.tailscaled.admin.policy.visual

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.InPaneWidth
import io.github.bropines.tailscaled.admin.policy.PolicyText
import io.github.bropines.tailscaled.admin.settings.Provide
import io.github.bropines.tailscaled.admin.settings.rememberParentLocals
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.ListDetailLayout
import io.github.bropines.tailscaled.ui.SheetOrPane
import io.github.bropines.tailscaled.ui.readableWidth
import kotlinx.coroutines.delay

/*
 * What the Access, SSH and Tests pages share: the list beside or under its editor, the editor's
 * frame and parts (title, the checks' findings, the note, "More", the row of actions), and the
 * words for which devices a rule covers.
 */

/**
 * A rule page: [list] alone with the editor as a sheet over it while [sheetOpen] (a phone,
 * a small tablet), or [list] beside [editor] in a pane on a two-pane window. The editor draws
 * itself through [EditorFrame], which is a sheet or the pane depending on where it is called.
 */
@Composable
internal fun RulePage(layout: VisualLayout, sheetOpen: Boolean, list: @Composable () -> Unit, editor: @Composable () -> Unit) {
    if (layout.twoPane) {
        ListDetailLayout(list = list, detail = editor, modifier = Modifier.fillMaxSize(), twoPane = true)
    } else {
        Box(Modifier.fillMaxSize()) { list() }
        if (sheetOpen) editor()
    }
}

/**
 * The list of a rule page: cards under a header, held to a readable width on a medium window,
 * room at the bottom for the shell's snackbar.
 */
@Composable
internal fun RuleList(layout: VisualLayout, state: LazyListState, content: LazyListScope.() -> Unit) {
    LazyColumn(
        state = state,
        modifier = if (layout.twoPane) Modifier.fillMaxSize() else Modifier.fillMaxSize().readableWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = if (layout.twoPane) 8.dp else 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * The top of a rule page: how many there are, the Add button, and what order means here,
 * folded. [extra] adds a second button (Tests: "From a rule").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageHeader(
    title: String,
    help: String?,
    env: VisualEnv,
    addLabel: String,
    onAdd: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
    notes: List<String> = emptyList(),
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                // Wide enough to stay on one line: the buttons wrap under it instead of squeezing it.
                modifier = Modifier.widthIn(min = 180.dp).weight(1f).padding(vertical = 8.dp).semantics { heading() },
            )
            if (env.editable) {
                // The buttons wrap as one, so the main one never ends up alone on a line.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    extra?.invoke()
                    FilledTonalButton(onClick = onAdd, shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(start = 12.dp, end = 16.dp)) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(addLabel)
                    }
                }
            }
        }
        help?.let { HelpText(it) }
        notes.forEach { CommentNote(it) }
    }
}

/** The comment over a whole section of the file (`// Tests guard the guests`), on its page. */
@Composable
private fun CommentNote(text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.AutoMirrored.Filled.Notes, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            HelpText(noteText(text), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The notes over [sections] in the file: what the person wrote about them as a whole. */
internal fun sectionNotes(m: PolicyModel, vararg sections: Section): List<String> =
    m.sections.filter { it.section in sections }.mapNotNull { it.origin.note }

/** A heading between two runs of lists that are not comments: "Grants" after the acls, "SSH tests" after the tests. */
@Composable
internal fun ListDivider(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            action?.invoke()
        }
    }
}

/** A small label on a card: "ACL", "Grant", "Accept", "Check every 12 h". */
@Composable
internal fun KindTag(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, strong: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier, shape = MaterialTheme.shapes.small, color = if (strong) scheme.tertiaryContainer else scheme.surfaceContainerHighest) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(it, null, Modifier.size(14.dp), tint = if (strong) scheme.onTertiaryContainer else scheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = if (strong) scheme.onTertiaryContainer else scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** A line of small text with its icon under a card's rows: coverage, a reminder. */
@Composable
internal fun CardFootnote(icon: ImageVector, text: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---- the editor ----

/**
 * An editor's frame: a sheet over the list on a phone, the pane beside it on a two-pane window,
 * no wider than a sheet; words in the app's language either way (a sheet is a window of its own).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorFrame(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val parent = rememberParentLocals()
    SheetOrPane(onDismiss) {
        parent.Provide { InPaneWidth { content() } }
    }
}

/** The scrolling column every editor's content is. */
@Composable
internal fun EditorColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/**
 * An editor's top: [title], [tags] beside it, "JSON" to the element's line, then what the
 * checks found on it — the server's refusal, a new risk — and, for an element the visual editor
 * does not change, why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorHeader(title: String, origin: Origin?, env: VisualEnv, actions: VisualActions, tags: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags()
                    if (origin != null && origin.line > 0) KindTag(ctx.getString(R.string.admin_pv_line, origin.line))
                }
            }
            if (origin != null && origin.line > 0) {
                TextButton(onClick = { actions.openJson(origin.line) }) {
                    Icon(Icons.Default.Code, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(ctx.getString(R.string.admin_pv_json))
                }
            }
        }
        if (origin == null) return@Column
        env.errors[origin.path].orEmpty().forEach { m -> Finding(Icons.Default.ErrorOutline, m, MaterialTheme.colorScheme.error) }
        env.risks[origin.path].orEmpty().forEach { r ->
            Finding(Icons.Default.Warning, PolicyText.riskTitle(ctx, r.kind), MaterialTheme.colorScheme.tertiary, PolicyText.riskHelp(ctx, r.kind))
        }
        if (!origin.editable) {
            Finding(
                Icons.Default.Lock,
                origin.issues.firstOrNull()?.let { VisualText.issue(ctx, it) } ?: ctx.getString(R.string.admin_pv_read_only),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (origin.extra.isNotEmpty()) {
            Finding(Icons.Default.Code, ctx.getString(R.string.admin_pv_more_in_json, origin.extra.joinToString(", ")), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One finding in an editor's header: an icon and its words, the explanation folded under them. */
@Composable
private fun Finding(icon: ImageVector, text: String, tint: Color, help: String? = null) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
                help?.let { HelpText(it) }
            }
        }
    }
}

/**
 * Optional fields under one fold: open from the start when any of them is set ([open]), so
 * nothing the rule says is hidden.
 */
@Composable
internal fun MoreSection(open: Boolean, summary: String?, content: @Composable ColumnScope.() -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(open) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = expanded, role = Role.Switch, onValueChange = { expanded = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(ctx.getString(R.string.admin_pv_more_options), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (!expanded && summary != null) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
        }
    }
}

/**
 * The comment above an element as a text field, written to the file a second after typing
 * stops, when the field loses focus or when the check is tapped — not on every key: each write
 * is an undo step. When the element has no note but a heading glued to it, the field edits
 * that heading and says so.
 */
@Composable
internal fun NoteField(origin: Origin, env: VisualEnv, actions: VisualActions) {
    val ctx = LocalContext.current
    val heading = origin.note == null && origin.header != null
    val saved = origin.note ?: origin.header.orEmpty()
    var text by remember(origin.path) { mutableStateOf(saved) }
    var focused by remember { mutableStateOf(false) }
    val changed = text.trim() != saved.trim()
    fun save() {
        if (text.trim() != saved.trim()) actions.edit { PolicyEdits.setComment(it, origin.path, text.trim().ifEmpty { null }) }
    }
    // Undo or the JSON editor changed the comment: show it, unless the person is typing over it.
    LaunchedEffect(saved) { if (!focused) text = saved }
    LaunchedEffect(text) {
        if (focused && changed) {
            delay(1000)
            save()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = env.editable && origin.editable,
            label = { Text(ctx.getString(if (heading) R.string.admin_pv_note_heading else R.string.admin_pv_note)) },
            placeholder = { Text(ctx.getString(R.string.admin_pv_note_placeholder)) },
            trailingIcon = if (changed) ({ IconButton(onClick = ::save) { Icon(Icons.Default.Check, ctx.getString(R.string.admin_pv_note_save)) } }) else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            maxLines = 5,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                if (focused && !f.isFocused) save()
                focused = f.isFocused
            },
        )
        HelpText(ctx.getString(if (heading) R.string.admin_pv_note_heading_help else R.string.admin_pv_note_help))
    }
}

/** One action of an editor's bottom row: an icon and its word, never hidden in a menu. */
@Composable
internal fun EditorAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    OutlinedButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(start = 12.dp, end = 16.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled && danger) tint else androidx.compose.ui.graphics.Color.Unspecified)
    }
}

/** The editor's actions, wrapped: Delete, Duplicate, Move up and down, and what the page adds. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorActions(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

/**
 * A card that is not in the list: the form of a new rule, written once it has what the server
 * needs. It says so, so that an editor with nothing saved does not look broken.
 */
@Composable
internal fun NewFormNote(text: String) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(14.dp))
    }
}

// ---- coverage ----

/** One side of a rule in words: "3 devices", "the internet", "5 devices and their own devices". */
internal fun coverageSide(ctx: Context, c: Coverage): String {
    val parts = mutableListOf<String>()
    if (c.devices.isNotEmpty() || c.unresolved.isEmpty()) parts += VisualText.devices(ctx, c.devices.size)
    if ("autogroup:internet" in c.unresolved) parts += ctx.getString(R.string.admin_pv_cov_internet)
    if ("autogroup:self" in c.unresolved) parts += ctx.getString(R.string.admin_pv_cov_self)
    if (c.unresolved.any { it != "autogroup:internet" && it != "autogroup:self" }) parts += ctx.getString(R.string.admin_pv_cov_more)
    return joinAnd(ctx, parts)
}

/** "a", "a and b", "a, b and c" in the app's language. */
internal fun joinAnd(ctx: Context, parts: List<String>): String = when (parts.size) {
    0 -> ""
    1 -> parts[0]
    else -> ctx.getString(R.string.admin_pv_and, parts.dropLast(1).joinToString(", "), parts.last())
}

/** "2 devices → 13 devices and the internet": what a rule connects, as the phone can tell. */
internal fun coverageLine(ctx: Context, from: Coverage, to: Coverage): String = "${coverageSide(ctx, from)} → ${coverageSide(ctx, to)}"

/**
 * The coverage of a rule in an editor: the line, and under it, on a tap, the devices on each
 * side by name. Nothing when the console has no device list.
 */
@Composable
internal fun CoverageBlock(title: String, from: Coverage, to: Coverage, env: VisualEnv, footer: @Composable () -> Unit = {}) {
    if (env.devices.isEmpty()) return
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldTitle(title)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).toggleable(value = open, role = Role.Switch, onValueChange = { open = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Devices, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(coverageLine(ctx, from, to), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            DeviceNames(ctx.getString(R.string.admin_pv_cov_from), from)
            DeviceNames(ctx.getString(R.string.admin_pv_cov_to), to)
        }
        HelpText(ctx.getString(R.string.admin_pv_cov_help))
        footer()
    }
}

@Composable
private fun DeviceNames(label: String, c: Coverage) {
    val ctx = LocalContext.current
    val names = c.devices.map { it.shortName } + c.unresolved.map { VisualText.label(ctx, it) }
    CardRow(label) {
        Text(names.joinToString(", ").ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A card's coverage footnote, when there is a device list to count on. */
@Composable
internal fun CoverageFootnote(from: Coverage, to: Coverage, env: VisualEnv, modifier: Modifier = Modifier) {
    if (env.devices.isEmpty()) return
    val ctx = LocalContext.current
    val line = coverageLine(ctx, from, to)
    CardFootnote(Icons.Default.Devices, line, modifier.semantics { contentDescription = ctx.getString(R.string.admin_pv_cov_said, line) })
}

// ---- the rows of a page ----

/** One row of a rule page's list: a heading comment, an element's card, a divider between lists. */
internal sealed class PageRow(val key: String) {
    /** A heading over a run of [section]; a rule added "here" goes to [insertAt]. */
    class Heading(val text: String, val section: Section, val insertAt: Int, key: String) : PageRow(key)
    class Item<T>(val item: T, val path: PolicyPath) : PageRow("i:$path")
    class Divider(val text: String, val section: Section?) : PageRow("d:$text")
}

/** [items] of [section], in file order, with the headings their comments make. */
internal fun <T> pageRows(items: List<T>, section: Section, origin: (T) -> Origin): List<PageRow> {
    val headers = items.map { origin(it).header != null }
    val indexes = items.map { (origin(it).path.last as? PathStep.Index)?.index ?: 0 }
    return items.flatMapIndexed { i, item ->
        val o = origin(item)
        listOfNotNull(
            o.header?.let { h ->
                val end = RuleForms.insertAfterRun(headers, i) - 1
                PageRow.Heading(h, section, indexes[end] + 1, "h:${o.path}")
            },
            PageRow.Item(item, o.path),
        )
    }
}

/** Where Move up and Move down may take the element at [i] of a list with these [headers]. */
internal fun movesOf(headers: List<Boolean>, i: Int) = RuleMoves(up = RuleForms.moveUp(headers, i) != null, down = RuleForms.moveDown(headers, i) != null)

/**
 * The list scrolls to [target] when it changes: an element opened from elsewhere (a server
 * error, the JSON cursor) or just added.
 */
@Composable
internal fun ScrollTo(state: LazyListState, rows: List<PageRow>, target: PolicyPath?, before: Int = 1) {
    LaunchedEffect(target) {
        val i = target?.let { t -> rows.indexOfFirst { it is PageRow.Item<*> && it.path == t } } ?: -1
        // [before] items (the page's header) come first; the rows follow them.
        if (i >= 0) state.animateScrollToItem(i + before)
    }
}

/**
 * After a rule is deleted, until the next edit: the offer to add a test that keeps closed what
 * it opened — Undo is the snackbar's.
 */
@Composable
internal fun DeletedOffer(text: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.admin_pv_not_now)) }
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}
