package io.github.bropines.tailscaled.admin.policy

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.safety.SafetyStep
import io.github.bropines.tailscaled.admin.settings.NoteTone
import io.github.bropines.tailscaled.admin.settings.ConfigNote
import io.github.bropines.tailscaled.admin.settings.Provide
import io.github.bropines.tailscaled.admin.settings.rememberParentLocals
import io.github.bropines.tailscaled.ui.AppTopBar
import io.github.bropines.tailscaled.ui.HelpText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The editor over the whole screen while [ConsoleState.policy] has one open: the text, then
 * the review of it, and the choice a 412 asks for. A window of its own, with the parent's
 * locale provided again inside.
 */
@Composable
fun PolicyEditorHost(state: ConsoleState, vm: AdminConsoleViewModel?) {
    val editor = state.policy.editor ?: return
    val ctx = LocalContext.current
    val parent = rememberParentLocals()
    var askDiscard by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        when {
            editor.review != null -> vm?.policy?.backToEditor()
            editor.changed -> askDiscard = true
            else -> vm?.policy?.closeEditor()
        }
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        parent.Provide {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val review = editor.review
                if (review == null) PolicyEditorScreen(editor, vm, onClose = close)
                else PolicyReviewScreen(state, editor, review, vm)
            }
        }
    }
    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(ctx.getString(R.string.admin_cfg_policy_discard_title)) },
            text = { Text(ctx.getString(R.string.admin_cfg_policy_discard_text)) },
            confirmButton = {
                TextButton(onClick = { askDiscard = false; vm?.policy?.closeEditor() }) { Text(ctx.getString(R.string.admin_cfg_policy_discard)) }
            },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text(ctx.getString(R.string.admin_cfg_policy_keep_editing)) } },
        )
    }
    state.policy.conflict?.let { c ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            icon = { Icon(Icons.Default.Warning, null) },
            title = { Text(ctx.getString(R.string.admin_cfg_conflict_title)) },
            text = { ConflictContent(c) { vm?.policy?.resolve(it) } },
            confirmButton = {},
        )
    }
}

/** The 412's choice, apart so a preview can draw it without a dialog window. */
@Composable
fun ConflictContent(c: PolicyConflict, onChoice: (ConflictChoice) -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(ctx.getString(R.string.admin_cfg_conflict_text), style = MaterialTheme.typography.bodyMedium)
        if (c.merged != null) {
            ConfigNote(ctx.getString(R.string.admin_cfg_conflict_mergeable), NoteTone.INFO)
        } else {
            val lines = c.conflicts.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
            ConfigNote(ctx.getString(R.string.admin_cfg_conflict_overlap, lines), NoteTone.WARNING)
        }
        Spacer(Modifier.size(2.dp))
        if (c.merged != null) Button(onClick = { onChoice(ConflictChoice.REAPPLY) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Text(ctx.getString(R.string.admin_cfg_conflict_reapply))
        }
        OutlinedButton(onClick = { onChoice(ConflictChoice.EDIT) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Text(ctx.getString(R.string.admin_cfg_conflict_edit))
        }
        TextButton(onClick = { onChoice(ConflictChoice.DISCARD) }, modifier = Modifier.fillMaxWidth()) {
            Text(ctx.getString(R.string.admin_cfg_conflict_discard))
        }
    }
}

/**
 * The text itself: monospace, coloured as it is typed, numbered, not wrapped — a line of the
 * file is a line on screen, so the numbers stay true. Under the bar, the local check of what
 * is typed so far.
 */
@Composable
fun PolicyEditorScreen(editor: PolicyEditorState, vm: AdminConsoleViewModel?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val colors = rememberPolicyColors()
    val transformation = remember(colors) { HuJsonTransformation(colors) }
    var tfv by remember { mutableStateOf(TextFieldValue(editor.text)) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val inPreview = LocalInspectionMode.current

    var jump by remember { mutableStateOf<Int?>(null) }

    // The text replaced from outside (a conflict resolved): the field follows.
    LaunchedEffect(editor.text) { if (editor.text != tfv.text) tfv = TextFieldValue(editor.text) }
    // A line asked for by the review (an error, a finding): taken once.
    LaunchedEffect(editor.goToLine) {
        editor.goToLine?.let { jump = it }
        if (editor.goToLine != null) vm?.policy?.lineShown()
    }
    LaunchedEffect(jump, layout != null) {
        val line = jump ?: return@LaunchedEffect
        val l = layout ?: return@LaunchedEffect
        tfv = tfv.copy(selection = TextRange(HuJson.Lines(tfv.text).startOf(line)))
        vScroll.animateScrollTo(l.getLineTop((line - 1).coerceIn(0, l.lineCount - 1)).toInt())
        if (!inPreview) runCatching { focus.requestFocus() }
        jump = null
    }
    // Checked off the main thread, a moment after typing stops; the first answer synchronously.
    val firstIssues = remember { HuJson.check(editor.text) }
    val issues by produceState(firstIssues, tfv.text) {
        delay(350)
        value = withContext(Dispatchers.Default) { HuJson.check(tfv.text) }
    }
    val lineCount = remember(tfv.text) { tfv.text.count { it == '\n' } + 1 }
    val numbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val style = codeStyle()
    val gutter = gutterWidth(lineCount)

    Scaffold(
        topBar = {
            AppTopBar(
                title = ctx.getString(if (editor.isRevert) R.string.admin_cfg_policy_revert_editor else R.string.admin_cfg_policy_editor),
                subtitle = ctx.getString(R.string.admin_cfg_policy_editor_base, editor.base.etag?.let(::shortEtag) ?: "—"),
                onBack = onClose,
                actions = {
                    TextButton(onClick = { vm?.policy?.review() }, enabled = issues.isEmpty() && editor.changed) {
                        Text(ctx.getString(R.string.admin_cfg_policy_review))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()) {
            LocalCheckStrip(issues, onGoTo = { jump = it })
            HorizontalDivider()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewportHeight = maxHeight
                val viewportWidth = maxWidth
                Row(Modifier.fillMaxSize().verticalScroll(vScroll).padding(vertical = 8.dp)) {
                    Text(
                        numbers, style = style, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.End,
                        modifier = Modifier.padding(start = 8.dp).width(gutter),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                        BasicTextField(
                            value = tfv,
                            onValueChange = {
                                tfv = it
                                vm?.policy?.edit(it.text)
                            },
                            textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
                            visualTransformation = transformation,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                            onTextLayout = { layout = it },
                            modifier = Modifier
                                .focusRequester(focus)
                                .widthIn(min = (viewportWidth - gutter - 24.dp).coerceAtLeast(0.dp))
                                .heightIn(min = (viewportHeight - 16.dp).coerceAtLeast(0.dp))
                                .padding(end = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** What the local check says about the text so far, with a way to its line. */
@Composable
private fun LocalCheckStrip(issues: List<HuIssue>, onGoTo: (Int) -> Unit) {
    val ctx = LocalContext.current
    val issue = issues.firstOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (issue == null) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (issue == null) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, Modifier.size(18.dp),
            tint = if (issue == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (issue == null) ctx.getString(R.string.admin_cfg_policy_local_ok) else PolicyText.issue(ctx, issue),
            style = MaterialTheme.typography.bodySmall,
            color = if (issue == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        if (issue != null) TextButton(onClick = { onGoTo(issue.line) }) { Text(ctx.getString(R.string.admin_cfg_policy_goto, issue.line)) }
    }
}

private enum class StepState { WAITING, RUNNING, PASSED, WARNED, FAILED }

/**
 * The review: each check as it ends, then what the server said, what this phone stands to
 * lose, what the change opens, and the diff — and the button that takes it to the gates.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PolicyReviewScreen(state: ConsoleState, editor: PolicyEditorState, review: PolicyReview, vm: AdminConsoleViewModel?) {
    val ctx = LocalContext.current
    val diff = review.diff
    val rows = remember(diff) { diff?.let { LineDiff.rows(it) }.orEmpty() }
    val canSend = review.passed && review.candidate == editor.text && state.canWrite(AdminArea.POLICY) &&
        state.settings.value?.aclsExternallyManagedOn != true && state.safety == SafetyStep.Idle
    val goTo: (Int) -> Unit = { vm?.policy?.backToEditor(it) }

    fun stepState(step: PolicyStep): StepState = when {
        review.running == step -> StepState.RUNNING
        review.failedAt == step -> StepState.FAILED
        step == PolicyStep.LOCAL && review.local != null -> StepState.PASSED
        step == PolicyStep.SERVER && review.validation?.ok == true -> StepState.PASSED
        step == PolicyStep.DIFF && review.diff != null -> StepState.PASSED
        step == PolicyStep.RISKS && review.risks != null -> if (review.risks.isEmpty()) StepState.PASSED else StepState.WARNED
        step == PolicyStep.ACCESS && review.access != null -> if (review.access.ran && review.access.lostCount == 0) StepState.PASSED else StepState.WARNED
        else -> StepState.WAITING
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = ctx.getString(R.string.admin_cfg_review_title),
                subtitle = diff?.let { ctx.getString(R.string.admin_cfg_review_counts, LineDiff.added(it), LineDiff.removed(it)) },
                onBack = { vm?.policy?.backToEditor() },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    HelpText(ctx.getString(R.string.admin_cfg_review_apply_help, ctx.getString(R.string.admin_cfg_policy_phrase)))
                    Spacer(Modifier.size(6.dp))
                    Button(
                        onClick = { vm?.policy?.apply() },
                        enabled = canSend,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text(ctx.getString(if (editor.isRevert) R.string.admin_cfg_review_apply_revert else R.string.admin_cfg_review_apply)) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        StepRow(ctx.getString(R.string.admin_cfg_step_local), stepState(PolicyStep.LOCAL))
                        StepRow(ctx.getString(R.string.admin_cfg_step_server), stepState(PolicyStep.SERVER))
                        StepRow(ctx.getString(R.string.admin_cfg_step_diff), stepState(PolicyStep.DIFF))
                        StepRow(ctx.getString(R.string.admin_cfg_step_risks), stepState(PolicyStep.RISKS))
                        StepRow(ctx.getString(R.string.admin_cfg_step_access), stepState(PolicyStep.ACCESS))
                    }
                }
            }
            editor.sendError?.let { err -> item { Problem(ctx.getString(R.string.admin_cfg_review_not_sent), listOf(err)) } }
            review.local?.firstOrNull()?.let { issue ->
                item { Problem(ctx.getString(R.string.admin_cfg_step_local_failed), listOf(PolicyText.issue(ctx, issue)), listOf(issue.line), goTo) }
            }
            review.validationError?.let { e ->
                item {
                    Problem(ctx.getString(R.string.admin_cfg_step_server_unreachable), listOf(ConsoleText.error(ctx, e))) {
                        TextButton(onClick = { vm?.policy?.review() }) { Text(ctx.getString(R.string.admin2_retry)) }
                    }
                }
            }
            review.validation?.takeIf { !it.ok }?.let { v ->
                val messages = listOfNotNull(v.message) + v.details
                item { Problem(ctx.getString(R.string.admin_cfg_step_server_failed), messages, messages.flatMap { PolicyText.lineHints(it) }.distinct(), goTo) }
            }
            review.access?.let { access ->
                item { AccessCard(access) }
            }
            review.risks?.let { risks ->
                item {
                    Text(ctx.getString(R.string.admin_cfg_risks_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (risks.isEmpty()) item { ConfigNote(ctx.getString(R.string.admin_cfg_risks_none), NoteTone.INFO) }
                items(risks) { r -> RiskCard(r, goTo) }
            }
            if (diff != null) {
                item {
                    Text(ctx.getString(R.string.admin_cfg_diff_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (!LineDiff.changed(diff)) item { ConfigNote(ctx.getString(R.string.admin_cfg_diff_none), NoteTone.INFO) }
                else items(rows) { row -> DiffRowView(row) }
            }
        }
    }
}

@Composable
private fun StepRow(label: String, s: StepState) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val scheme = MaterialTheme.colorScheme
        val (icon: ImageVector?, tint, word) = when (s) {
            StepState.WAITING -> Triple(Icons.Default.RadioButtonUnchecked, scheme.outline, R.string.admin_cfg_step_waiting)
            StepState.RUNNING -> Triple(null, scheme.primary, R.string.admin_cfg_step_running)
            StepState.PASSED -> Triple(Icons.Default.CheckCircle, scheme.primary, R.string.admin_cfg_step_passed)
            StepState.WARNED -> Triple(Icons.Default.Warning, scheme.tertiary, R.string.admin_cfg_step_warned)
            StepState.FAILED -> Triple(Icons.Default.ErrorOutline, scheme.error, R.string.admin_cfg_step_failed)
        }
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = tint)
        else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(ctx.getString(word), style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** A step's failure: what it said, and the lines it names, each a way back into the editor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Problem(title: String, lines: List<String>, gotoLines: List<Int> = emptyList(), onGoTo: (Int) -> Unit = {}, action: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            lines.forEach { Text(it, style = codeStyle()) }
            if (gotoLines.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                gotoLines.forEach { line -> AssistChip(onClick = { onGoTo(line) }, label = { Text(ctx.getString(R.string.admin_cfg_policy_goto, line)) }) }
            }
            action?.invoke()
        }
    }
}

@Composable
private fun AccessCard(access: AccessReport) {
    val ctx = LocalContext.current
    val lost = access.probes.filter { it.lost.isNotEmpty() }
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = if (lost.isNotEmpty()) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ctx.getString(R.string.admin_cfg_access_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            when {
                lost.isNotEmpty() -> lost.forEach { ConfigNote(PolicyText.lostLine(ctx, it), NoteTone.WARNING) }
                access.ran -> ConfigNote(ctx.getString(R.string.admin_cfg_access_kept), NoteTone.INFO)
            }
            if (!access.ran) ConfigNote(PolicyText.accessUnchecked(ctx, access), NoteTone.WARNING)
            HelpText(ctx.getString(R.string.admin_cfg_access_help))
        }
    }
}

@Composable
private fun RiskCard(r: RiskFinding, onGoTo: (Int) -> Unit) {
    val ctx = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(PolicyText.riskTitle(ctx, r.kind), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                r.line?.let { line -> TextButton(onClick = { onGoTo(line) }) { Text(ctx.getString(R.string.admin_cfg_policy_goto, line)) } }
            }
            Text("${r.section}: ${r.detail}", style = codeStyle())
            HelpText(PolicyText.riskHelp(ctx, r.kind), color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

/** One row of the diff: a +/− marker and both line numbers, so it reads without its colour. */
@Composable
private fun DiffRowView(row: DiffRow) {
    val ctx = LocalContext.current
    val style = codeStyle()
    val e = row.edit
    if (e == null) {
        Text(
            ctx.resources.getQuantityString(R.plurals.admin_cfg_diff_folded, row.skipped, row.skipped),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
        return
    }
    val scheme = MaterialTheme.colorScheme
    val (marker, bg, fg) = when (e.kind) {
        LineEditKind.INSERT -> Triple("+", scheme.primaryContainer, scheme.onPrimaryContainer)
        LineEditKind.DELETE -> Triple("−", scheme.errorContainer, scheme.onErrorContainer)
        LineEditKind.EQUAL -> Triple(" ", scheme.surface, scheme.onSurface)
    }
    Row(Modifier.fillMaxWidth().background(bg, MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text((e.oldLine ?: "").toString(), style = style, color = scheme.outline, textAlign = TextAlign.End, modifier = Modifier.width(36.dp))
        Spacer(Modifier.width(4.dp))
        Text((e.newLine ?: "").toString(), style = style, color = scheme.outline, textAlign = TextAlign.End, modifier = Modifier.width(36.dp))
        Spacer(Modifier.width(6.dp))
        Text(marker, style = style, color = fg, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(
            e.text,
            style = style,
            color = fg,
            textDecoration = if (e.kind == LineEditKind.DELETE) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
    }
}
