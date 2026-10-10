package io.github.bropines.tailscaled.admin.policy.visual

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.PolicyPreview
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.WriteBlock
import io.github.bropines.tailscaled.admin.policy.HuIssue
import io.github.bropines.tailscaled.admin.policy.PolicyEditorState
import io.github.bropines.tailscaled.admin.policy.PolicyRefusal
import io.github.bropines.tailscaled.admin.policy.PolicyText
import io.github.bropines.tailscaled.admin.policy.shortEtag
import io.github.bropines.tailscaled.admin.safety.ReadOnlyBanner
import io.github.bropines.tailscaled.ui.AppTopBar
import io.github.bropines.tailscaled.ui.Fold
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.ReadableContentWidth
import io.github.bropines.tailscaled.ui.readableWidth
import io.github.bropines.tailscaled.ui.rememberWindowLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The visual policy editor's frame: the top bar (undo, redo, the switch to JSON, Review), the
 * page navigation, what stands over every page (read-only, a section written twice, the
 * server's refusal), the page itself, and the snackbar the pages report through. It turns the
 * console's state into a VisualEnv and the console's actions into VisualActions, so the pages
 * know nothing of the view model; the text both editors share stays in the console.
 */

private const val RISK_DELAY_MS = 300L

/**
 * The policy editor's visual view over [editor] (PolicyEditorHost shows it while
 * [PolicyEditorState.visual] is set). [vm] is null in previews: everything draws, nothing acts.
 */
@Composable
fun VisualEditorScreen(state: ConsoleState, editor: PolicyEditorState, vm: AdminConsoleViewModel?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val window = rememberWindowLayout()
    val rail = window.visualRail
    val twoPane = window.listDetail
    val listHalf = (window.fold as? Fold.Vertical)?.start
    // Wider than a phone yet one pane: the pages keep to a readable column, the chips over them too.
    val held = when {
        listHalf != null -> Modifier.width(listHalf)
        !twoPane && window.multiColumn -> Modifier.readableWidth()
        else -> Modifier.fillMaxWidth()
    }

    val external = state.settings.value?.aclsExternallyManagedOn == true
    val canWrite = state.canWrite(AdminArea.POLICY) && !external
    val headscale = (state.caps?.backend ?: state.active?.backend ?: BackendKind.TAILSCALE) != BackendKind.TAILSCALE

    val draft = remember(editor.text, editor.undoStack, editor.redoStack) { editor.draft }
    val model = draft.model
    // New risks against the saved file, off the main thread a moment after an edit; the first synchronously.
    val baseText = editor.base.text
    val firstRisks = remember { VisualEnv.risks(baseText, editor.text) }
    val risks by produceState(firstRisks, baseText, editor.text) {
        delay(RISK_DELAY_MS)
        value = withContext(Dispatchers.Default) { VisualEnv.risks(baseText, editor.text) }
    }
    // The server's refusal lands on cards only while the text is the one it refused.
    val refusal = editor.refusal
    val refusalCurrent = refusal?.candidate == editor.text
    val errors = remember(editor.text, refusal) {
        if (refusal != null && refusalCurrent) VisualEnv.errors(editor.text, refusal.messages) else emptyMap()
    }
    val env = VisualEnv(
        draft = draft,
        devices = state.devices.value.orEmpty(),
        users = state.users.value.orEmpty(),
        headscale = headscale,
        canWrite = canWrite,
        risks = risks,
        errors = errors,
        focus = editor.focus,
    )

    val page = editor.page
    val pages = remember(model, headscale, page, errors, risks) { pageEntries(model, headscale, page, env) }
    val onSelect = remember(vm) { { p: PolicyPath? -> vm?.policy?.select(p); Unit } }
    // Two panes: what the shell was asked to show stands in the pane when nothing is picked.
    val shown = editor.selected ?: editor.focus?.takeIf { twoPane && VisualSection.of(it) == page }
    val layout = VisualLayout(twoPane = twoPane, selected = shown, onSelect = onSelect)

    val snackbar = remember { SnackbarHostState() }
    val actions = rememberShellActions(vm, snackbar)

    Scaffold(
        topBar = {
            AppTopBar(
                title = ctx.getString(
                    when {
                        !canWrite -> R.string.admin_pv_shell_view_title
                        editor.isRevert -> R.string.admin_cfg_policy_revert_editor
                        else -> R.string.admin_cfg_tab_policy
                    }
                ),
                // Short: the bar holds four actions on a phone.
                subtitle = ctx.getString(R.string.admin_cfg_policy_version, editor.base.etag?.let(::shortEtag) ?: "—"),
                onBack = onClose,
                actions = {
                    if (canWrite) {
                        IconButton(onClick = { vm?.policy?.undo() }, enabled = draft.canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, ctx.getString(R.string.admin_pv_shell_undo))
                        }
                        IconButton(onClick = { vm?.policy?.redo() }, enabled = draft.canRedo) {
                            Icon(Icons.AutoMirrored.Filled.Redo, ctx.getString(R.string.admin_pv_shell_redo))
                        }
                    }
                    IconButton(onClick = { vm?.policy?.setView(false, jsonLineFor(editor)) }) {
                        Icon(Icons.Default.Code, ctx.getString(R.string.admin_pv_shell_to_json))
                    }
                    if (canWrite) {
                        TextButton(onClick = { vm?.policy?.review() }, enabled = draft.issue == null && editor.changed) {
                            Text(ctx.getString(R.string.admin_cfg_policy_review))
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val notices: @Composable () -> Unit = {
            Notices(state, editor, model, canWrite, external, errors.keys.toList(), refusalCurrent, vm, held)
        }
        val body: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier) {
                if (model == null) {
                    Box(held.fillMaxHeight()) { UnparsableState(draft.issue, draft.canUndo, vm) }
                } else if (rail || twoPane) {
                    VisualSectionBody(page, env, actions, layout)
                } else {
                    Box(held.fillMaxHeight()) { VisualSectionBody(page, env, actions, layout) }
                }
            }
        }
        val pick: (VisualSection) -> Unit = { vm?.policy?.showPage(it) }
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()) {
            if (rail && model != null) {
                Row(Modifier.fillMaxSize()) {
                    VisualRail(pages, page, window.visualRailWide, pick)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        notices()
                        body(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            } else {
                if (model != null) VisualChipRow(pages, page, pick, held.padding(horizontal = window.margin, vertical = 8.dp))
                notices()
                body(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The console's actions as the pages ask for them; a refused edit and an undoable delete answer in the snackbar. */
@Composable
private fun rememberShellActions(vm: AdminConsoleViewModel?, snackbar: SnackbarHostState): VisualActions {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Resolved here, in the editor's locale, and kept current for actions that outlive a recomposition.
    val refused by rememberUpdatedState(ctx.getString(R.string.admin_pv_shell_refused))
    val json by rememberUpdatedState(ctx.getString(R.string.admin_pv_shell_json))
    val undo by rememberUpdatedState(ctx.getString(R.string.admin_pv_shell_undo))
    return remember(vm, snackbar, scope) {
        object : VisualActions {
            override fun edit(op: (String) -> String): Boolean {
                val policy = vm?.policy ?: return false
                policy.visualEdit(op) ?: return true
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val r = snackbar.showSnackbar(refused, json, withDismissAction = true, duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) policy.setView(false, vm.state.value.policy.editor?.let(::jsonLineFor), remember = false)
                }
                return false
            }

            override fun openJson(line: Int) {
                vm?.policy?.setView(false, line, remember = false)
            }

            override fun show(path: PolicyPath) {
                vm?.policy?.show(path)
            }

            override fun previewDraft(type: PolicyPreviewType, previewFor: String, onResult: (Result<PolicyPreview>) -> Unit) {
                val policy = vm?.policy ?: return super.previewDraft(type, previewFor, onResult)
                policy.previewDraft(type, previewFor, onResult)
            }

            override fun notify(message: String, undoable: Boolean) {
                // Undo steps back the change this message is about, and nothing made after it.
                val after = vm?.state?.value?.policy?.editor?.text
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val r = snackbar.showSnackbar(
                        message,
                        actionLabel = if (undoable) undo else null,
                        withDismissAction = !undoable,
                        duration = if (undoable) SnackbarDuration.Long else SnackbarDuration.Short,
                    )
                    if (r == SnackbarResult.ActionPerformed && undoable && vm?.state?.value?.policy?.editor?.text == after) vm?.policy?.undo()
                }
            }
        }
    }
}

/**
 * The line the JSON view opens on from the visual one: the element open or brought into view,
 * else the first section of the page shown.
 */
fun jsonLineFor(editor: PolicyEditorState): Int? {
    (editor.selected ?: editor.focus)?.let { p -> PolicyEditorState.lineOf(editor.text, p)?.let { return it } }
    val model = editor.draft.model ?: return editor.draft.issue?.line
    val sections = model.sections
    val first = if (editor.page == VisualSection.NETWORK) sections.firstOrNull { it.section == null || it.section in editor.page.sections }
    else sections.firstOrNull { it.section in editor.page.sections }
    return first?.origin?.line
}

/** An element in a few words, for a button that goes to it: "Access rule 4", "group:admins". */
fun elementLabel(ctx: Context, path: PolicyPath): String {
    val s = path.steps
    val key = (s.firstOrNull() as? PathStep.Key)?.name ?: return path.toString()
    val index = (s.getOrNull(1) as? PathStep.Index)?.index
    val numbered = when (Section.of(key)) {
        Section.ACLS -> R.string.admin_pv_shell_el_acl
        Section.GRANTS -> R.string.admin_pv_shell_el_grant
        Section.SSH -> R.string.admin_pv_shell_el_ssh
        Section.NODE_ATTRS -> R.string.admin_pv_shell_el_attr
        Section.TESTS -> R.string.admin_pv_shell_el_test
        Section.SSH_TESTS -> R.string.admin_pv_shell_el_ssh_test
        else -> null
    }
    return when {
        numbered != null && index != null -> ctx.getString(numbered, index + 1)
        s.size >= 2 -> (s.last() as? PathStep.Key)?.name ?: path.toString()
        else -> key
    }
}

// ---------------------------------------------------------------- over every page

@Composable
private fun Notices(
    state: ConsoleState,
    editor: PolicyEditorState,
    model: PolicyModel?,
    canWrite: Boolean,
    external: Boolean,
    places: List<PolicyPath>,
    refusalCurrent: Boolean,
    vm: AdminConsoleViewModel?,
    held: Modifier,
) {
    val ctx = LocalContext.current
    val margin = rememberWindowLayout().margin
    val duplicates = model?.duplicateSections.orEmpty()
    val refusal = editor.refusal
    if (canWrite && duplicates.isEmpty() && refusal == null) return
    Column(held.padding(start = margin, end = margin, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!canWrite) {
            val (title, text) = when {
                external -> R.string.admin_cfg_policy_ro_external_title to R.string.admin_cfg_policy_ro_external_text
                state.writeBlock == WriteBlock.NO_SCREEN_LOCK -> R.string.admin2_readonly_no_lock_title to R.string.admin2_readonly_no_lock_desc
                state.writeBlock == WriteBlock.READ_ONLY_PROFILE -> R.string.admin2_readonly_profile_title to R.string.admin2_readonly_profile_desc
                else -> R.string.admin_cfg_policy_ro_scope_title to R.string.admin_cfg_policy_ro_scope_text
            }
            // As the console draws the same reasons: a read-only profile has its own icon.
            if (state.writeBlock == WriteBlock.READ_ONLY_PROFILE && !external) ReadOnlyBanner(ctx.getString(title), ctx.getString(text), icon = Icons.Default.VisibilityOff)
            else ReadOnlyBanner(ctx.getString(title), ctx.getString(text))
        }
        if (duplicates.isNotEmpty()) {
            val keys = duplicates.sorted()
            // Where the clash shows: the second copy of the first key written twice.
            val sections = model?.sections.orEmpty()
            val first = sections.firstOrNull { it.key in duplicates }?.key
            val line = sections.filter { it.key == first }.getOrNull(1)?.origin?.line ?: 1
            NoticeCard(
                icon = Icons.Default.Warning,
                title = ctx.getString(R.string.admin_pv_shell_duplicate_title, keys.joinToString(", ")),
                text = ctx.getString(R.string.admin_pv_shell_duplicate_text),
                actionLabel = ctx.getString(R.string.admin_pv_edit_in_json),
                onAction = { vm?.policy?.setView(false, line, remember = false) },
            )
        }
        if (refusal != null) RefusalBanner(refusal, if (refusalCurrent) places else emptyList(), stale = !refusalCurrent) { vm?.policy?.show(it) }
    }
}

/** A warning over the page: an icon so it reads without its colour, a title, the explanation folded, an action. */
@Composable
private fun NoticeCard(icon: ImageVector, title: String, text: String?, actionLabel: String, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container = scheme.tertiaryContainer
    val content = scheme.onTertiaryContainer
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                text?.let { HelpText(it, color = content) }
            }
            TextButton(onClick = onAction, colors = ButtonDefaults.textButtonColors(contentColor = content)) { Text(actionLabel) }
        }
    }
}

/**
 * What the server said when it refused the policy: where it lands among the cards, the way
 * from one of those to the next, and its words, folded. Once the text is edited the cards lose
 * their marks — a fix may have moved them — and the banner says to review again.
 */
@Composable
private fun RefusalBanner(refusal: PolicyRefusal, places: List<PolicyPath>, stale: Boolean, onShow: (PolicyPath) -> Unit) {
    val ctx = LocalContext.current
    var index by remember(refusal, places) { mutableIntStateOf(0) }
    val status = when {
        stale -> ctx.getString(R.string.admin_pv_shell_refusal_stale)
        places.isEmpty() -> ctx.getString(R.string.admin_pv_shell_refusal_unplaced)
        else -> ctx.resources.getQuantityString(R.plurals.admin_pv_shell_refusal_places, places.size, places.size)
    }
    val scheme = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ctx.getString(R.string.admin_pv_shell_refusal_title), style = MaterialTheme.typography.titleSmall)
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
                if (places.size > 1) {
                    IconButton(onClick = { index = (index - 1).mod(places.size); onShow(places[index]) }) {
                        Icon(Icons.Default.KeyboardArrowUp, ctx.getString(R.string.admin_pv_shell_refusal_prev))
                    }
                    IconButton(onClick = { index = (index + 1).mod(places.size); onShow(places[index]) }) {
                        Icon(Icons.Default.KeyboardArrowDown, ctx.getString(R.string.admin_pv_shell_refusal_next))
                    }
                } else if (places.size == 1) {
                    IconButton(onClick = { onShow(places.first()) }) {
                        Icon(Icons.Default.KeyboardArrowDown, ctx.getString(R.string.admin_pv_shell_show, elementLabel(ctx, places.first())))
                    }
                }
            }
            if (refusal.messages.isNotEmpty()) {
                HelpText(
                    refusal.messages.joinToString("\n"),
                    modifier = Modifier.padding(start = 32.dp, top = 4.dp, end = 8.dp),
                    color = scheme.onErrorContainer,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- a text that does not read

/**
 * The text does not parse (typed so in the JSON view: a visual edit never leaves it so): what
 * is wrong, in words, the way to its line, and the undo that takes the typing back.
 */
@Composable
private fun UnparsableState(issue: HuIssue?, canUndo: Boolean, vm: AdminConsoleViewModel?) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Text(
            ctx.getString(R.string.admin_pv_shell_unparsable_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            ctx.getString(R.string.admin_pv_shell_unparsable_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = ReadableContentWidth),
        )
        if (issue != null) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(PolicyText.issue(ctx, issue), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        Button(onClick = { vm?.policy?.setView(false, issue?.line, remember = false) }, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Default.Code, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(issue?.let { ctx.getString(R.string.admin_pv_shell_open_json_at, it.line) } ?: ctx.getString(R.string.admin_pv_edit_in_json))
        }
        if (canUndo) OutlinedButton(onClick = { vm?.policy?.undo() }, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(ctx.getString(R.string.admin_pv_shell_undo_last))
        }
    }
}
