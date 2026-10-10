package io.github.bropines.tailscaled.admin.policy.visual

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.InPaneWidth
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.policy.PolicyText
import io.github.bropines.tailscaled.admin.policy.RiskFinding
import io.github.bropines.tailscaled.admin.settings.Provide
import io.github.bropines.tailscaled.admin.settings.rememberParentLocals
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.ListDetailLayout
import io.github.bropines.tailscaled.ui.LocalInPane
import io.github.bropines.tailscaled.ui.PaneEmptyState
import io.github.bropines.tailscaled.ui.ReadableWidth
import io.github.bropines.tailscaled.ui.SheetOrPane

/*
 * What the definitions pages share — groups, tags, hosts and IP sets, auto approval, device
 * attributes, postures: the page with its list and its editor (a sheet on a phone, the pane
 * beside the list on a large window), and the editor's parts — a name renamed everywhere it is
 * used after a confirmation, a field that writes when it is left, the note, where an element is
 * used, the devices it covers, a delete that waits until nothing uses it — with NamedListEditor,
 * the editor of a named list (a group's members, a tag's owners, a service's approvers), built
 * from them.
 */

/** One row of a definitions page's list; [path] is the element it shows, for scrolling to it. */
internal class PageItem(val key: String, val path: PolicyPath? = null, val content: @Composable () -> Unit)

/**
 * A definitions page: [items] in a list held to a readable width, and the editor of what is
 * open — [editorKey] non-null — as a sheet over the list on a phone, or in the pane beside it
 * when [VisualLayout.twoPane] (the pane shows [paneEmpty] while there is nothing to open). The
 * list brings [scrollTo] into view when it changes and is off screen.
 */
@Composable
internal fun DefinitionsPage(
    layout: VisualLayout,
    items: List<PageItem>,
    editorKey: Any?,
    onCloseEditor: () -> Unit,
    paneEmpty: Pair<ImageVector, String>,
    scrollTo: PolicyPath?,
    state: LazyListState = rememberLazyListState(),
    editor: @Composable ColumnScope.() -> Unit,
) {
    val target = scrollTo?.let { p -> items.indexOfFirst { it.path == p } }?.takeIf { it >= 0 }
    LaunchedEffect(target) {
        if (target != null && state.layoutInfo.visibleItemsInfo.none { it.index == target }) state.animateScrollToItem(target)
    }
    val list: @Composable () -> Unit = {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.key }) { it.content() }
        }
    }
    if (layout.twoPane) {
        ListDetailLayout(
            modifier = Modifier.fillMaxSize(),
            twoPane = true,
            list = list,
            detail = {
                // A pane starts each element's editor at its top.
                if (editorKey != null) key(editorKey) { DefsEditorFrame(onCloseEditor) { editor() } }
                else PaneEmptyState(paneEmpty.first, paneEmpty.second)
            },
        )
    } else {
        ReadableWidth { list() }
        // Keyed inside the sheet: a rename moves the editor to the new name without closing it.
        if (editorKey != null) DefsEditorFrame(onCloseEditor) { key(editorKey) { editor() } }
    }
}

/** An editor's frame: a full-height sheet on a phone, the pane's scrolling column on a large window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefsEditorFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val parent = rememberParentLocals()
    SheetOrPane(onDismiss = onDismiss) {
        parent.Provide { InPaneWidth { DefsEditorColumn(content = content) } }
    }
}

/** The editor's scrolling column, apart so that a preview can draw an editor without its sheet. */
@Composable
internal fun DefsEditorColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            // A pane stands inside the screen's insets already; a sheet reaches the bottom edge.
            .then(if (LocalInPane.current) Modifier else Modifier.navigationBarsPadding()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

// ---- the list ----

/**
 * The top of a page: what it is for, folded to two lines, the file's own comments over its
 * sections, and the button that adds to it (none when nothing may be written).
 */
@Composable
internal fun PageIntro(help: String, addLabel: String?, onAdd: (() -> Unit)?, notes: List<String> = emptyList(), errors: List<String> = emptyList()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HelpText(help, Modifier.weight(1f))
            if (addLabel != null && onAdd != null) {
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(onClick = onAdd, shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(start = 12.dp, end = 16.dp)) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(addLabel, maxLines = 1)
                }
            }
        }
        notes.forEach { CommentHeading(it) }
        ErrorLines(errors)
    }
}

/** A part of a page with a title of its own (Hosts, IP sets; Routes, Exit nodes, Services), its add action on the right. */
@Composable
internal fun SubsectionHeader(title: String, addLabel: String? = null, onAdd: (() -> Unit)? = null, help: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (addLabel != null && onAdd != null) {
                TextButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(addLabel, maxLines = 1)
                }
            }
        }
        help?.let { HelpText(it) }
    }
}

/** A quiet line where a part of a page has nothing yet. */
@Composable
internal fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/**
 * The first row of a definition's card or editor: its icon on a tonal square, its name (machine
 * text in monospace) and a line of what it holds and where it is used.
 */
@Composable
internal fun DefinitionHeader(icon: ImageVector, title: String, subtitle: String?, large: Boolean = false, monospace: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(if (large) 44.dp else 36.dp).background(scheme.secondaryContainer, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(if (large) 24.dp else 20.dp), tint = scheme.onSecondaryContainer) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                fontWeight = if (large) null else FontWeight.SemiBold,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                maxLines = if (large) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Selector labels, at most [max] of them and "+N more" after, behind an inline [caption]
 * ("Owned by", "Applies to") when there is one; [empty] stands in for an empty list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LimitedLabels(
    values: List<String>,
    hosts: Set<String> = emptySet(),
    max: Int = 8,
    caption: String? = null,
    empty: (@Composable () -> Unit)? = null,
    label: @Composable (String) -> Unit = { SelectorLabel(it, hosts = hosts) },
) {
    val ctx = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 2.dp),
            )
        }
        if (values.isEmpty()) empty?.invoke()
        values.take(max).forEach { label(it) }
        if (values.size > max) {
            Text(
                ctx.getString(R.string.admin_pvd_more, values.size - max),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

/** Server messages about a section or an element, each with the error icon. */
@Composable
internal fun ErrorLines(messages: List<String>) {
    messages.forEach { m ->
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.ErrorOutline, null, Modifier.padding(top = 1.dp).size(16.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(6.dp))
            Text(m, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** A warning in the editor, before the server is asked: icon, title, the explanation folded. */
@Composable
internal fun WarningLine(title: String, help: String? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, null, Modifier.padding(top = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            help?.let { HelpText(it) }
        }
    }
}

// ---- the editor ----

/** A titled part of an editor: [title] over [content], an explanation folded under the title. */
@Composable
internal fun EditorBlock(title: String, help: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            help?.let { HelpText(it) }
        }
        content()
    }
}

/**
 * An editor's top: the element's name and what it is, the server's messages about it, its risks
 * ([risks], by default those it adds to the saved policy) and — when the visual editor may only
 * show it — why, with the way to the JSON editor.
 */
@Composable
internal fun EditorTop(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    origin: Origin?,
    env: VisualEnv,
    actions: VisualActions,
    monospace: Boolean = true,
    risks: List<RiskFinding>? = null,
) {
    val ctx = LocalContext.current
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DefinitionHeader(icon, title, subtitle, large = true, monospace = monospace)
        if (origin != null) {
            ErrorLines(env.errors[origin.path].orEmpty())
            (risks ?: env.risks[origin.path].orEmpty()).distinctBy { it.kind }.forEach { r ->
                WarningLine(PolicyText.riskTitle(ctx, r.kind), PolicyText.riskHelp(ctx, r.kind))
            }
            if (!origin.editable) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        HelpText(origin.issues.firstOrNull()?.let { VisualText.issue(ctx, it) } ?: ctx.getString(R.string.admin_pv_read_only), Modifier.weight(1f))
                        TextButton(onClick = { actions.openJson(origin.line) }) { Text(ctx.getString(R.string.admin_pv_edit_in_json)) }
                    }
                }
            }
        }
    }
}

/**
 * [op] as an edit of the text it was composed against ([base], the draft the editor showed),
 * and nothing otherwise. A field writes as its editor closes, and by then a delete or a move may
 * have changed what its path points at: a host deleted would come back, the rule after a deleted
 * one would get its attribute. Every edit that a field makes goes through here.
 */
internal fun VisualActions.editAt(base: String, op: (String) -> String): Boolean {
    // The engine hands its edits "\n" line endings (PolicyDraft keeps a file's "\r\n" around them).
    val seen = base.replace("\r\n", "\n")
    return edit { t -> if (t == seen) op(t) else t }
}

/**
 * A text field that writes [onCommit] when it is left — Done on the keyboard, the check at its
 * end, focus moving away, the editor closing — and only a value [problem] has nothing against:
 * an edit per field, not per keystroke, so each is one step to undo. With [resetAfterCommit] it
 * adds rather than changes: empty again after each value it writes.
 */
@Composable
internal fun CommitField(
    value: String,
    label: String?,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    problem: (String) -> String? = { null },
    placeholder: String? = null,
    supporting: String? = null,
    monospace: Boolean = true,
    singleLine: Boolean = true,
    prefix: String? = null,
    resetAfterCommit: Boolean = false,
    /** One logical line that may wrap on screen: no line breaks are taken, Done writes it. */
    wrap: Boolean = false,
) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(value) }
    val changed = text != value
    val error = if (changed) problem(text) else null
    val latest by rememberUpdatedState(Triple(text, value, onCommit))
    val check by rememberUpdatedState(problem)
    fun commit() {
        val (t, v, c) = latest
        if (t != v && check(t) == null) {
            c(t)
            // A field that adds rows starts empty again for the next one.
            if (resetAfterCommit) text = v
        }
    }
    DisposableEffect(Unit) { onDispose { commit() } }
    OutlinedTextField(
        value = text,
        onValueChange = { text = if (wrap) it.replace("\n", " ") else it },
        enabled = enabled,
        singleLine = singleLine && !wrap,
        maxLines = if (wrap) 4 else Int.MAX_VALUE,
        label = label?.let { { Text(it) } },
        prefix = prefix?.let { { Text(it, fontFamily = FontFamily.Monospace) } },
        placeholder = placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        trailingIcon = if (changed && error == null) {
            { IconButton(onClick = { commit(); focus.clearFocus() }) { Icon(Icons.Default.Check, ctx.getString(R.string.admin_pvd_apply)) } }
        } else null,
        textStyle = if (monospace) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = !monospace,
            capitalization = if (monospace) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            imeAction = if (singleLine || wrap) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commit() },
    )
}

/**
 * The name of a group, tag, host, IP set, posture or service, with its prefix fixed in front.
 * A rename rewrites the definition and every reference ([places] of them) after asking —
 * [confirmNote] adds what else the person should know; it is not written as the name is typed,
 * nor when the editor closes.
 */
@Composable
internal fun NameField(kind: DefKind, current: String, model: PolicyModel, places: Int, enabled: Boolean, confirmNote: String? = null, onRename: (String) -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val bare = current.removePrefix(kind.prefix)
    var text by remember(current) { mutableStateOf(bare) }
    var confirming by remember { mutableStateOf(false) }
    val changed = text.trim() != bare
    val problem = if (changed) Definitions.checkName(kind, text, model, current) else null
    val renamed = kind.prefix + text.trim()
    fun submit() {
        if (!changed || problem != null) return
        focus.clearFocus()
        if (places > 0 || confirmNote != null) confirming = true else onRename(renamed)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        enabled = enabled,
        singleLine = true,
        label = { Text(ctx.getString(R.string.admin_pvd_name)) },
        prefix = kind.prefix.takeIf { it.isNotEmpty() }?.let { { Text(it, fontFamily = FontFamily.Monospace) } },
        isError = problem != null,
        supportingText = when {
            problem != null -> { { Text(nameProblem(ctx, kind, problem)) } }
            changed && places > 0 -> { { Text(ctx.resources.getQuantityString(R.plurals.admin_pvd_rename_hint, places, places)) } }
            else -> null
        },
        trailingIcon = if (changed && problem == null) {
            { IconButton(onClick = ::submit) { Icon(Icons.Default.Check, ctx.getString(R.string.admin_pvd_rename)) } }
        } else null,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    if (confirming) {
        // The dialog is a window of its own: its words come from this composition's context.
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(ctx.getString(R.string.admin_pvd_rename_title, current)) },
            text = {
                val what = if (places > 0) ctx.resources.getQuantityString(R.plurals.admin_pvd_rename_text, places, current, renamed, places)
                else ctx.getString(R.string.admin_pvd_rename_text_unused, current, renamed)
                Text(listOfNotNull(what, confirmNote).joinToString("\n\n"))
            },
            confirmButton = { TextButton(onClick = { confirming = false; onRename(renamed) }) { Text(ctx.getString(R.string.admin_pvd_rename)) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(ctx.getString(R.string.action_cancel)) } },
        )
    }
}

/** A new element's name: the same field without a rename, for a form that writes on Create. */
@Composable
internal fun NewNameField(kind: DefKind, text: String, onChange: (String) -> Unit, model: PolicyModel, showProblem: Boolean) {
    val ctx = LocalContext.current
    val problem = Definitions.checkName(kind, text, model).takeIf { showProblem || text.isNotEmpty() }
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(ctx.getString(R.string.admin_pvd_name)) },
        prefix = kind.prefix.takeIf { it.isNotEmpty() }?.let { { Text(it, fontFamily = FontFamily.Monospace) } },
        isError = problem != null && problem != NameProblem.EMPTY,
        supportingText = problem?.takeIf { it != NameProblem.EMPTY }?.let { { Text(nameProblem(ctx, kind, it)) } },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The comment above an element, as one field: its note, or — when the comment over it heads a
 * run of elements — that heading, which the label says. Absent for an element that shares its
 * line with others (the file has nowhere to put a comment of its own).
 */
@Composable
internal fun NoteField(origin: Origin, tree: SourceTree?, enabled: Boolean, actions: VisualActions) {
    val ctx = LocalContext.current
    if (tree != null && !Definitions.canComment(tree, origin.path)) return
    val heading = origin.note == null && origin.header != null
    CommitField(
        value = origin.note ?: origin.header.orEmpty(),
        label = ctx.getString(if (heading) R.string.admin_pvd_heading else R.string.admin_pvd_note),
        onCommit = { c ->
            val op: (String) -> String = { PolicyEdits.setComment(it, origin.path, c.trim().ifEmpty { null }) }
            if (tree != null) actions.editAt(tree.text, op) else actions.edit(op)
        },
        enabled = enabled,
        supporting = ctx.getString(if (heading) R.string.admin_pvd_heading_help else R.string.admin_pvd_note_help),
        monospace = false,
        singleLine = false,
    )
}

/**
 * Where an element is used, one tappable row per rule or definition in file order, each opening
 * it — the first few, and the rest a tap away; a quiet line when nothing uses it.
 */
@Composable
internal fun PlacesBlock(places: List<PolicyPath>, model: PolicyModel, tree: SourceTree?, actions: VisualActions) {
    val ctx = LocalContext.current
    var all by remember(places) { mutableStateOf(places.size <= PLACES_FOLDED + 1) }
    EditorBlock(ctx.getString(R.string.admin_pvd_used_in) + if (places.isNotEmpty()) " · ${places.size}" else "") {
        if (places.isEmpty()) {
            Text(ctx.getString(R.string.admin_pvd_unused), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (if (all) places else places.take(PLACES_FOLDED)).forEach { p ->
            val origin = Definitions.originOf(model, p)
            val line = origin?.line ?: tree?.let { Definitions.lineOf(it, p) }
            val about = (origin?.note ?: origin?.header)?.lineSequence()?.firstOrNull()?.let(::headingTitle)
            val second = listOfNotNull(about, line?.let { ctx.getString(R.string.admin_pvd_line, it) }).joinToString(" · ")
            Surface(
                onClick = { actions.show(p) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(VisualSection.of(p).icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(placeLabel(ctx, p), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (second.isNotEmpty()) {
                            Text(second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!all) {
            TextButton(onClick = { all = true }) { Text(ctx.getString(R.string.admin_pvd_show_all, places.size)) }
        }
    }
}

/** Rows of uses shown before "Show all": a group used in sixteen rules would push its editor's end off screen. */
private const val PLACES_FOLDED = 5

/** The devices an element covers, by name, or [none] when it covers none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DevicesBlock(title: String, devices: List<ApiDevice>, none: String, help: String? = null) {
    val ctx = LocalContext.current
    EditorBlock(title + if (devices.isNotEmpty()) " · ${devices.size}" else "", help) {
        if (devices.isEmpty()) {
            Text(none, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                devices.take(MAX_DEVICES).forEach { d ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Devices, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(d.shortName, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
                if (devices.size > MAX_DEVICES) {
                    Text(
                        ctx.getString(R.string.admin_pvd_more, devices.size - MAX_DEVICES),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}

private const val MAX_DEVICES = 12

/** One action of an editor's bottom row: an icon and a word, never in a menu. */
internal data class DefsEditorAction(val icon: ImageVector, val label: String, val enabled: Boolean = true, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * The editor's last rows: the delete — refused, with why, while [places] still use the element —
 * [more] actions, and the way to the element's line in the JSON editor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorBottom(
    origin: Origin?,
    actions: VisualActions,
    deleteLabel: String?,
    onDelete: (() -> Unit)?,
    places: Int = 0,
    deleteNote: String? = null,
    more: List<DefsEditorAction> = emptyList(),
) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (deleteLabel != null && onDelete != null && places > 0) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Lock, null, Modifier.padding(top = 1.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                HelpText(ctx.resources.getQuantityString(R.plurals.admin_pvd_delete_blocked, places, places), Modifier.weight(1f))
            }
        } else if (deleteNote != null && deleteLabel != null && onDelete != null) {
            HelpText(deleteNote)
        }
        val all = buildList {
            if (deleteLabel != null && onDelete != null) add(DefsEditorAction(Icons.Default.Delete, deleteLabel, enabled = places == 0, danger = true, onClick = onDelete))
            addAll(more)
            if (origin != null) add(DefsEditorAction(Icons.Default.Code, ctx.getString(R.string.admin_pv_edit_in_json)) { actions.openJson(origin.line) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            all.forEach { a ->
                val tint = if (a.danger && a.enabled) MaterialTheme.colorScheme.error else Color.Unspecified
                OutlinedButton(
                    onClick = a.onClick,
                    enabled = a.enabled,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                    colors = if (a.danger) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.outlinedButtonColors(),
                    border = if (a.danger && a.enabled) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) else ButtonDefaults.outlinedButtonBorder(a.enabled),
                ) {
                    Icon(a.icon, null, Modifier.size(18.dp), tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint)
                    Spacer(Modifier.width(6.dp))
                    Text(a.label, maxLines = 1)
                }
            }
        }
    }
}

/** The new element form's button: writes nothing until [enabled]. */
@Composable
internal fun CreateButton(label: String, enabled: Boolean, hint: String? = null, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
        if (hint != null && !enabled) HelpText(hint)
    }
}

/**
 * The editor of a named list — a group's members, a tag's owners, a service's approvers: its
 * name (renamed everywhere after asking), the list as selector chips with the picker for [slot],
 * [extra] (the devices it covers), its note, where it is used, and a delete that waits until
 * nothing uses it.
 */
@Composable
internal fun NamedListEditor(
    kind: DefKind,
    item: NamedList,
    env: VisualEnv,
    actions: VisualActions,
    icon: ImageVector,
    subtitle: String,
    valuesTitle: String,
    valuesHelp: String?,
    slot: SelectorSlot,
    tree: SourceTree?,
    places: List<PolicyPath>,
    deleteLabel: String,
    onValues: (List<String>) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    emptyValues: String? = null,
    deleteNote: String? = null,
    renameNote: String? = null,
    /** False where the list is not a definition others point at (a service's approvers): its uses do not block a delete. */
    blockDelete: Boolean = true,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val model = env.model ?: return
    val editable = env.editable && item.origin.editable
    val fieldEnv = if (editable) env else env.copy(canWrite = false)
    EditorTop(icon, item.name, subtitle, item.origin, env, actions)
    NameField(kind, item.name, model, places.size, editable, renameNote, onRename)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectorField(valuesTitle, item.values, slot, fieldEnv, onChange = onValues)
        if (item.values.isEmpty() && emptyValues != null) HelpText(emptyValues)
        else valuesHelp?.let { HelpText(it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), content = extra)
    NoteField(item.origin, tree, editable, actions)
    PlacesBlock(places, model, tree, actions)
    EditorBottom(item.origin, actions, deleteLabel.takeIf { editable }, onDelete, if (blockDelete) places.size else 0, deleteNote)
}

// ---- words ----

fun nameProblem(ctx: Context, kind: DefKind, p: NameProblem): String = ctx.getString(
    when (p) {
        NameProblem.EMPTY -> R.string.admin_pvd_name_empty
        NameProblem.BAD_START -> R.string.admin_pvd_name_tag_start
        NameProblem.TAKEN -> R.string.admin_pvd_name_taken
        NameProblem.BAD_CHARS -> when (kind) {
            DefKind.TAG -> R.string.admin_pvd_name_tag_chars
            DefKind.SERVICE -> R.string.admin_pvd_name_service_chars
            DefKind.HOST -> R.string.admin_pvd_name_host_chars
            else -> R.string.admin_pvd_name_chars
        }
    }
)

fun addressProblem(ctx: Context, p: AddressProblem): String = ctx.getString(
    when (p) {
        AddressProblem.EMPTY -> R.string.admin_pvd_addr_empty
        AddressProblem.NOT_AN_ADDRESS -> R.string.admin_pvd_addr_bad
        AddressProblem.NEEDS_PREFIX -> R.string.admin_pvd_addr_needs_prefix
        AddressProblem.BAD_PREFIX_LENGTH -> R.string.admin_pvd_addr_bits
    }
)

/** "used in 3 places", or null for an element nothing uses. */
fun placesText(ctx: Context, n: Int): String? = if (n == 0) null else ctx.resources.getQuantityString(R.plurals.admin_pvd_places, n, n)

/** An element in words, for the rows that lead to it: "Access rule 3", "Owners of tag:web". */
fun placeLabel(ctx: Context, path: PolicyPath): String {
    val s = path.steps
    val section = (s.firstOrNull() as? PathStep.Key)?.name
    val key = (s.getOrNull(1) as? PathStep.Key)?.name
    val n = (s.getOrNull(1) as? PathStep.Index)?.index?.plus(1)
    return when (section) {
        "acls" -> ctx.getString(R.string.admin_pvd_place_acl, n ?: 0)
        "grants" -> ctx.getString(R.string.admin_pvd_place_grant, n ?: 0)
        "ssh" -> ctx.getString(R.string.admin_pvd_place_ssh, n ?: 0)
        "nodeAttrs" -> ctx.getString(R.string.admin_pvd_place_attr, n ?: 0)
        "tests" -> ctx.getString(R.string.admin_pvd_place_test, n ?: 0)
        "sshTests" -> ctx.getString(R.string.admin_pvd_place_ssh_test, n ?: 0)
        "tagOwners" -> ctx.getString(R.string.admin_pvd_place_owners, key.orEmpty())
        "groups" -> ctx.getString(R.string.admin_pvd_place_members, key.orEmpty())
        "ipsets" -> ctx.getString(R.string.admin_pvd_place_ipset, key.orEmpty())
        "postures" -> ctx.getString(R.string.admin_pvd_place_posture, key.orEmpty())
        "defaultSrcPosture" -> ctx.getString(R.string.admin_pvd_place_default_posture)
        "autoApprovers" -> when (key) {
            "exitNode" -> ctx.getString(R.string.admin_pvd_place_exit)
            "routes" -> ctx.getString(R.string.admin_pvd_place_route, (s.getOrNull(2) as? PathStep.Key)?.name.orEmpty())
            "services" -> ctx.getString(R.string.admin_pvd_place_service, (s.getOrNull(2) as? PathStep.Key)?.name.orEmpty())
            else -> ctx.getString(R.string.admin_pv_section_approvers)
        }
        else -> path.toString()
    }
}

/** The page's sections' own comments (the comment over `"groups": {`), as headings over the list. */
fun defsSectionNotes(model: PolicyModel, vararg sections: Section): List<String> =
    model.sections.filter { it.section in sections }.mapNotNull { it.origin.note ?: it.origin.header }

/** Server messages about a whole section rather than one element of it. */
fun sectionErrors(env: VisualEnv, vararg sections: Section): List<String> =
    sections.flatMap { env.errors[PolicyPath.of(it.key)].orEmpty() }.distinct()

/** The element the editor shows: the selected one when it is on this page, else on two panes the first. */
internal fun <T> shownOf(selected: PolicyPath?, layout: VisualLayout, items: List<T>, path: (T) -> PolicyPath): T? =
    items.firstOrNull { path(it) == selected } ?: if (layout.twoPane) items.firstOrNull() else null
