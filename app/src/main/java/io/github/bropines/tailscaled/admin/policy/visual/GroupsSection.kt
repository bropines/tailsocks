package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.HelpText

/**
 * The GROUPS page: each group with its members, their devices and where it is used. Its editor
 * picks members from the console's users (or a typed login), renames the group everywhere after
 * saying how many places change, and deletes it only once nothing uses it.
 */
@Composable
fun GroupsSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    val text = env.draft.text
    val tree = remember(text) { SourceTree.parseOrNull(text) }
    val groups = model.groups
    val places = remember(text) { groups.associate { g -> g.name to tree?.let { Definitions.places(it, g.name) }.orEmpty() } }
    var creating by remember { mutableStateOf(false) }
    val shown = shownOf(layout.selected, layout, groups) { it.origin.path }
    val add = if (env.editable) ({ creating = true; layout.onSelect(null) }) else null
    val addLabel = ctx.getString(R.string.admin_pvd_group_new)

    val items = buildList {
        add(PageItem("intro") {
            PageIntro(ctx.getString(R.string.admin_pvd_groups_help), addLabel.takeIf { groups.isNotEmpty() }, add, sectionNotes(model, Section.GROUPS), sectionErrors(env, Section.GROUPS))
        })
        if (groups.isEmpty()) add(PageItem("empty") { EmptySection(Icons.Default.Groups, ctx.getString(R.string.admin_pvd_groups_empty), addLabel.takeIf { add != null }, add) })
        groups.forEachIndexed { i, g ->
            g.origin.header?.let { h -> add(PageItem("h$i") { CommentHeading(h) }) }
            add(PageItem("g$i:${g.name}", g.origin.path) {
                GroupCard(g, env, actions, places[g.name].orEmpty().size, selected = layout.twoPane && !creating && shown == g) {
                    creating = false
                    layout.onSelect(g.origin.path)
                }
            })
        }
    }
    DefinitionsPage(
        layout = layout,
        items = items,
        editorKey = if (creating) "new" else shown?.origin?.path,
        onCloseEditor = { creating = false; layout.onSelect(null) },
        paneEmpty = Icons.Default.Groups to ctx.getString(R.string.admin_pvd_groups_pane_empty),
        scrollTo = env.focus ?: layout.selected,
    ) {
        if (creating) NewGroupForm(env, actions) { name -> creating = false; layout.onSelect(PolicyPath.of(Section.GROUPS.key, name)) }
        else shown?.let { GroupEditor(it, env, actions, layout, tree, places[it.name].orEmpty()) }
    }
}

/** "2 members · 6 devices · used in 5 places". */
private fun groupSubtitle(ctx: android.content.Context, g: NamedList, env: VisualEnv, places: Int): String = listOfNotNull(
    ctx.resources.getQuantityString(R.plurals.admin_pvd_n_members, g.values.size, g.values.size),
    VisualText.devices(ctx, Definitions.groupDevices(g.values, env.devices).size).takeIf { env.devices.isNotEmpty() },
    placesText(ctx, places),
).joinToString(" · ")

@Composable
private fun GroupCard(g: NamedList, env: VisualEnv, actions: VisualActions, places: Int, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    ElementCard(g.origin, env, actions, selected = selected, onClick = onClick) {
        DefinitionHeader(Icons.Default.Groups, g.name, groupSubtitle(ctx, g, env, places))
        if (g.values.isNotEmpty()) LimitedLabels(g.values)
    }
}

/** A group's editor: members, their devices, note, uses, rename and delete. */
@Composable
internal fun GroupEditor(g: NamedList, env: VisualEnv, actions: VisualActions, layout: VisualLayout, tree: SourceTree?, places: List<PolicyPath>) {
    val ctx = LocalContext.current
    NamedListEditor(
        kind = DefKind.GROUP,
        item = g,
        env = env,
        actions = actions,
        icon = Icons.Default.Groups,
        subtitle = groupSubtitle(ctx, g, env, places.size),
        valuesTitle = ctx.getString(R.string.admin_pvd_group_members),
        valuesHelp = ctx.getString(R.string.admin_pvd_group_members_help),
        slot = SelectorSlot.GROUP_MEMBER,
        tree = tree,
        places = places,
        deleteLabel = ctx.getString(R.string.admin_pvd_group_delete),
        onValues = { v -> actions.edit { PolicyEdits.putNamed(it, Section.GROUPS, g.name, v) } },
        onRename = { new -> if (actions.edit { PolicyEdits.rename(it, g.name, new) }) layout.onSelect(PolicyPath.of(Section.GROUPS.key, new)) },
        onDelete = {
            if (actions.edit { PolicyEdits.removeNamed(it, Section.GROUPS, g.name) }) {
                layout.onSelect(null)
                actions.notify(ctx.getString(R.string.admin_pvd_deleted, g.name), undoable = true)
            }
        },
        emptyValues = ctx.getString(R.string.admin_pvd_group_no_members),
    ) {
        if (env.devices.isNotEmpty()) {
            DevicesBlock(
                ctx.getString(R.string.admin_pvd_group_devices),
                Definitions.groupDevices(g.values, env.devices),
                ctx.getString(R.string.admin_pvd_group_no_devices),
                ctx.getString(R.string.admin_pvd_group_devices_help),
            )
        }
    }
}

/** A new group: nothing is written until it has a name the server takes and Create is tapped. */
@Composable
internal fun NewGroupForm(env: VisualEnv, actions: VisualActions, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    var name by remember { mutableStateOf("") }
    var members by remember { mutableStateOf(emptyList<String>()) }
    val problem = Definitions.checkName(DefKind.GROUP, name, model)
    EditorTop(Icons.Default.Groups, ctx.getString(R.string.admin_pvd_group_new), ctx.getString(R.string.admin_pvd_groups_empty), null, env, actions, monospace = false)
    NewNameField(DefKind.GROUP, name, { name = it }, model, showProblem = false)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectorField(ctx.getString(R.string.admin_pvd_group_members), members, SelectorSlot.GROUP_MEMBER, env, onChange = { members = it })
        HelpText(ctx.getString(R.string.admin_pvd_group_members_help))
    }
    CreateButton(ctx.getString(R.string.admin_pvd_create), enabled = problem == null, hint = ctx.getString(R.string.admin_pvd_create_needs_name)) {
        val full = DefKind.GROUP.prefix + name.trim()
        if (actions.edit { PolicyEdits.putNamed(it, Section.GROUPS, full, members) }) onCreated(full)
    }
}
