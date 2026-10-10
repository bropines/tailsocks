package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.HelpText

private val DEFAULT_POSTURE_PATH = PolicyPath.of(Section.DEFAULT_SRC_POSTURE.key)

/**
 * The POSTURE page (postures, defaultSrcPosture; Tailscale only): each posture with its
 * conditions, and the postures every rule without one of its own requires. Conditions are lines
 * of the policy's own syntax (`node:os == 'macos'`), edited one per row and checked by the
 * server; an object-form posture's `onFailure` is shown and left to the JSON editor.
 */
@Composable
fun PosturesSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    val text = env.draft.text
    val tree = remember(text) { SourceTree.parseOrNull(text) }
    val postures = model.postures
    val places = remember(text) { postures.associate { p -> p.name to tree?.let { Definitions.places(it, p.name) }.orEmpty() } }
    var creating by remember { mutableStateOf(false) }
    val shown = shownOf(layout.selected, layout, postures) { it.origin.path }
    val add = if (env.editable) ({ creating = true; layout.onSelect(null) }) else null
    val addLabel = ctx.getString(R.string.admin_pvd_posture_new)

    val items = buildList {
        add(PageItem("intro") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PageIntro(
                    ctx.getString(R.string.admin_pvd_postures_help), addLabel.takeIf { postures.isNotEmpty() }, add,
                    sectionNotes(model, Section.POSTURES), sectionErrors(env, Section.POSTURES),
                )
                if (env.headscale) WarningLine(ctx.getString(R.string.admin_pvd_not_on_headscale))
            }
        })
        if (postures.isNotEmpty() || model.defaultSrcPosture.isNotEmpty()) {
            add(PageItem("default", DEFAULT_POSTURE_PATH) { DefaultPostureCard(model, env, actions) })
        }
        if (postures.isEmpty()) add(PageItem("empty") { EmptySection(Icons.Default.VerifiedUser, ctx.getString(R.string.admin_pvd_postures_empty), addLabel.takeIf { add != null }, add) })
        postures.forEachIndexed { i, p ->
            p.origin.header?.let { add(PageItem("h$i") { CommentHeading(it) }) }
            add(PageItem("p$i:${p.name}", p.origin.path) {
                PostureCard(p, env, actions, places[p.name].orEmpty().size, selected = layout.twoPane && !creating && shown == p) {
                    creating = false
                    layout.onSelect(p.origin.path)
                }
            })
        }
    }
    DefinitionsPage(
        layout = layout,
        items = items,
        editorKey = if (creating) "new" else shown?.origin?.path,
        onCloseEditor = { creating = false; layout.onSelect(null) },
        paneEmpty = Icons.Default.VerifiedUser to ctx.getString(R.string.admin_pvd_postures_pane_empty),
        scrollTo = env.focus ?: layout.selected,
    ) {
        if (creating) NewPostureForm(env, actions) { name -> creating = false; layout.onSelect(PolicyPath.of(Section.POSTURES.key, name)) }
        else shown?.let { PostureEditor(it, env, actions, layout, tree, places[it.name].orEmpty()) }
    }
}

/**
 * The default posture, edited in place: a setting of the whole policy rather than an element
 * to open, with the server's messages about it under the field.
 */
@Composable
private fun DefaultPostureCard(model: PolicyModel, env: VisualEnv, actions: VisualActions) {
    val ctx = LocalContext.current
    val focused = env.focus == DEFAULT_POSTURE_PATH
    OutlinedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = if (focused || env.errors[DEFAULT_POSTURE_PATH] != null) {
            androidx.compose.foundation.BorderStroke(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        } else CardDefaults.outlinedCardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectorField(
                ctx.getString(R.string.admin_pvd_default_posture),
                model.defaultSrcPosture,
                SelectorSlot.POSTURE,
                env,
                onChange = { v -> actions.edit { Definitions.setDefaultPosture(it, v) } },
            )
            HelpText(ctx.getString(if (model.defaultSrcPosture.isEmpty()) R.string.admin_pvd_default_posture_none else R.string.admin_pvd_default_posture_help))
            ErrorLines(env.errors[DEFAULT_POSTURE_PATH].orEmpty())
        }
    }
}

private fun postureSubtitle(ctx: android.content.Context, p: Posture, places: Int): String = listOfNotNull(
    ctx.resources.getQuantityString(R.plurals.admin_pvd_n_conditions, p.assertions.size, p.assertions.size),
    placesText(ctx, places),
).joinToString(" · ")

@Composable
private fun PostureCard(p: Posture, env: VisualEnv, actions: VisualActions, places: Int, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    ElementCard(p.origin, env, actions, selected = selected, onClick = onClick) {
        DefinitionHeader(Icons.Default.VerifiedUser, p.name, postureSubtitle(ctx, p, places))
        if (p.assertions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                p.assertions.take(3).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (p.assertions.size > 3) {
                    Text(ctx.getString(R.string.admin_pvd_more, p.assertions.size - 3), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * A posture's editor: its name (renamed everywhere), its conditions a row each — changed,
 * removed, one more added — its `onFailure` shown, note, uses, delete.
 */
@Composable
internal fun PostureEditor(p: Posture, env: VisualEnv, actions: VisualActions, layout: VisualLayout, tree: SourceTree?, places: List<PolicyPath>) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    val editable = env.editable && p.origin.editable
    val list = p.assertions
    val base = env.draft.text
    fun write(next: List<String>) = actions.editAt(base) { Definitions.setAssertions(it, p, next) }
    val blank: (String) -> String? = { v -> if (v.isBlank()) ctx.getString(R.string.admin_pvd_posture_condition_empty) else null }

    EditorTop(Icons.Default.VerifiedUser, p.name, postureSubtitle(ctx, p, places.size), p.origin, env, actions)
    if (env.headscale) WarningLine(ctx.getString(R.string.admin_pvd_not_on_headscale))
    NameField(DefKind.POSTURE, p.name, model, places.size, editable) { new ->
        if (actions.edit { PolicyEdits.rename(it, p.name, new) }) layout.onSelect(PolicyPath.of(Section.POSTURES.key, new))
    }
    EditorBlock(ctx.getString(R.string.admin_pvd_posture_conditions), ctx.getString(R.string.admin_pvd_posture_conditions_help)) {
        list.forEachIndexed { i, a ->
            Row(verticalAlignment = Alignment.Top) {
                CommitField(
                    value = a,
                    label = ctx.getString(R.string.admin_pvd_posture_condition, i + 1),
                    onCommit = { v -> write(list.toMutableList().also { it[i] = v.trim() }) },
                    modifier = Modifier.weight(1f),
                    enabled = editable,
                    problem = blank,
                    wrap = true,
                )
                if (editable) {
                    IconButton(onClick = { write(list.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Default.Close, ctx.getString(R.string.admin_pvd_remove_row))
                    }
                }
            }
        }
        if (editable) {
            key(list.size) {
                CommitField(
                    value = "",
                    label = ctx.getString(R.string.admin_pvd_posture_add_condition),
                    onCommit = { v -> write(list + v.trim()) },
                    placeholder = "node:os == 'macos'",
                    problem = blank,
                    resetAfterCommit = true,
                    wrap = true,
                )
            }
        }
    }
    if (p.objectForm && tree != null) {
        (tree.at(p.origin.path + "onFailure"))?.let { v ->
            Text(
                ctx.getString(R.string.admin_pvd_posture_on_failure, tree.slice(v)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    NoteField(p.origin, tree, editable, actions)
    PlacesBlock(places, model, tree, actions)
    EditorBottom(p.origin, actions, ctx.getString(R.string.admin_pvd_posture_delete).takeIf { editable }, {
        if (actions.edit { PolicyEdits.removeNamed(it, Section.POSTURES, p.name) }) {
            layout.onSelect(null)
            actions.notify(ctx.getString(R.string.admin_pvd_deleted, p.name), undoable = true)
        }
    }, places.size)
}

/** A new posture: a name and its first condition, written on Create. */
@Composable
internal fun NewPostureForm(env: VisualEnv, actions: VisualActions, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    var name by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("") }
    val problem = Definitions.checkName(DefKind.POSTURE, name, model)
    EditorTop(Icons.Default.VerifiedUser, ctx.getString(R.string.admin_pvd_posture_new), ctx.getString(R.string.admin_pvd_postures_empty), null, env, actions, monospace = false)
    NewNameField(DefKind.POSTURE, name, { name = it }, model, showProblem = false)
    OutlinedTextField(
        value = condition,
        onValueChange = { condition = it },
        label = { Text(ctx.getString(R.string.admin_pvd_posture_condition, 1)) },
        placeholder = { Text("node:os == 'macos'") },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    HelpText(ctx.getString(R.string.admin_pvd_posture_conditions_help))
    CreateButton(ctx.getString(R.string.admin_pvd_create), enabled = problem == null && condition.isNotBlank(), hint = ctx.getString(R.string.admin_pvd_posture_create_hint)) {
        val full = DefKind.POSTURE.prefix + name.trim()
        if (actions.edit { PolicyEdits.putNamed(it, Section.POSTURES, full, listOf(condition.trim())) }) onCreated(full)
    }
}
