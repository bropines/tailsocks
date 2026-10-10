package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.ui.HelpText

/**
 * The TAGS page (tagOwners): each tag with who may assign it, the devices that carry it and
 * where rules use it. Tags that devices carry or rules use without an owner stand on top, each
 * one tap from being added with Admins as owners. The editor renames a tag everywhere, picks its
 * owners, and deletes it only once no rule uses it.
 */
@Composable
fun TagOwnersSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    val text = env.draft.text
    val tree = remember(text) { SourceTree.parseOrNull(text) }
    val tags = model.tagOwners
    val places = remember(text) { tags.associate { t -> t.name to tree?.let { Definitions.places(it, t.name) }.orEmpty() } }
    val unowned = remember(text, env.devices) { Definitions.unownedTags(model, env.devices, tree) }
    var creating by remember { mutableStateOf(false) }
    val shown = shownOf(layout.selected, layout, tags) { it.origin.path }
    val add = if (env.editable) ({ creating = true; layout.onSelect(null) }) else null
    val addLabel = ctx.getString(R.string.admin_pvd_tag_new)

    fun addUnowned(tag: String) {
        if (actions.edit { PolicyEdits.putNamed(it, Section.TAG_OWNERS, tag, Definitions.defaultOwners(env.headscale)) }) {
            creating = false
            layout.onSelect(PolicyPath.of(Section.TAG_OWNERS.key, tag))
            actions.notify(ctx.getString(R.string.admin_pvd_unowned_added, tag), undoable = true)
        }
    }

    val items = buildList {
        add(PageItem("intro") {
            PageIntro(ctx.getString(R.string.admin_pvd_tags_help), addLabel.takeIf { tags.isNotEmpty() }, add, sectionNotes(model, Section.TAG_OWNERS), sectionErrors(env, Section.TAG_OWNERS))
        })
        if (unowned.isNotEmpty()) add(PageItem("unowned") { UnownedBanner(unowned, env, ::addUnowned) })
        if (tags.isEmpty()) add(PageItem("empty") { EmptySection(Icons.AutoMirrored.Filled.Label, ctx.getString(R.string.admin_pvd_tags_empty), addLabel.takeIf { add != null }, add) })
        tags.forEachIndexed { i, t ->
            t.origin.header?.let { h -> add(PageItem("h$i") { CommentHeading(h) }) }
            add(PageItem("t$i:${t.name}", t.origin.path) {
                TagCard(t, env, actions, places[t.name].orEmpty().size, selected = layout.twoPane && !creating && shown == t) {
                    creating = false
                    layout.onSelect(t.origin.path)
                }
            })
        }
    }
    DefinitionsPage(
        layout = layout,
        items = items,
        editorKey = if (creating) "new" else shown?.origin?.path,
        onCloseEditor = { creating = false; layout.onSelect(null) },
        paneEmpty = Icons.AutoMirrored.Filled.Label to ctx.getString(R.string.admin_pvd_tags_pane_empty),
        scrollTo = env.focus ?: layout.selected,
    ) {
        if (creating) NewTagForm(env, actions) { name -> creating = false; layout.onSelect(PolicyPath.of(Section.TAG_OWNERS.key, name)) }
        else shown?.let { TagEditor(it, env, actions, layout, tree, places[it.name].orEmpty()) }
    }
}

/** "3 devices · used in 7 places". */
private fun tagSubtitle(ctx: android.content.Context, tag: String, env: VisualEnv, places: Int): String = listOfNotNull(
    VisualText.devices(ctx, Definitions.tagDevices(tag, env.devices).size).takeIf { env.devices.isNotEmpty() },
    placesText(ctx, places),
).joinToString(" · ").ifEmpty { ctx.getString(R.string.admin_pvd_unused) }

@Composable
private fun TagCard(t: NamedList, env: VisualEnv, actions: VisualActions, places: Int, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    ElementCard(t.origin, env, actions, selected = selected, onClick = onClick) {
        DefinitionHeader(Icons.AutoMirrored.Filled.Label, t.name, tagSubtitle(ctx, t.name, env, places))
        LimitedLabels(t.values, caption = ctx.getString(R.string.admin_pvd_tag_owned_by), empty = { AdminsOnly() })
    }
}

/** An empty owner list, said in words: no one but admins assigns the tag. */
@Composable
private fun AdminsOnly() {
    val ctx = LocalContext.current
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(ctx.getString(R.string.admin_pvd_tag_admins_only), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Tags in use without an owner: the server refuses rules that use one, and no one can assign it.
 * Each is added with one tap, with Admins as owners on Tailscale (none on Headscale, which takes
 * no autogroup there), and opened to change them.
 */
@Composable
private fun UnownedBanner(unowned: List<UnownedTag>, env: VisualEnv, onAdd: (String) -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.tertiaryContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, scheme.tertiary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = scheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(
                    ctx.resources.getQuantityString(R.plurals.admin_pvd_unowned_title, unowned.size, unowned.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HelpText(ctx.getString(if (env.headscale) R.string.admin_pvd_unowned_help_hs else R.string.admin_pvd_unowned_help))
            unowned.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.tag, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                        val what = listOfNotNull(
                            VisualText.devices(ctx, u.devices).takeIf { u.devices > 0 },
                            placesText(ctx, u.places),
                        ).joinToString(" · ")
                        if (what.isNotEmpty()) Text(what, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    if (env.editable) {
                        FilledTonalButton(onClick = { onAdd(u.tag) }, shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(ctx.getString(R.string.admin_pvd_unowned_add))
                        }
                    }
                }
            }
        }
    }
}

/** A tag's editor: owners, the devices carrying it, note, uses, rename and delete. */
@Composable
internal fun TagEditor(t: NamedList, env: VisualEnv, actions: VisualActions, layout: VisualLayout, tree: SourceTree?, places: List<PolicyPath>) {
    val ctx = LocalContext.current
    val carriers = Definitions.tagDevices(t.name, env.devices)
    val carried = carriers.size.takeIf { it > 0 }
    NamedListEditor(
        kind = DefKind.TAG,
        item = t,
        env = env,
        actions = actions,
        icon = Icons.AutoMirrored.Filled.Label,
        subtitle = tagSubtitle(ctx, t.name, env, places.size),
        valuesTitle = ctx.getString(R.string.admin_pvd_tag_owners),
        valuesHelp = ctx.getString(R.string.admin_pvd_tag_owners_help),
        slot = SelectorSlot.TAG_OWNER,
        tree = tree,
        places = places,
        deleteLabel = ctx.getString(R.string.admin_pvd_tag_delete),
        onValues = { v -> actions.edit { PolicyEdits.putNamed(it, Section.TAG_OWNERS, t.name, v) } },
        onRename = { new -> if (actions.edit { PolicyEdits.rename(it, t.name, new) }) layout.onSelect(PolicyPath.of(Section.TAG_OWNERS.key, new)) },
        onDelete = {
            if (actions.edit { PolicyEdits.removeNamed(it, Section.TAG_OWNERS, t.name) }) {
                layout.onSelect(null)
                actions.notify(ctx.getString(R.string.admin_pvd_deleted, t.name), undoable = true)
            }
        },
        emptyValues = ctx.getString(R.string.admin_pvd_tag_owners_empty),
        deleteNote = carried?.let { ctx.resources.getQuantityString(R.plurals.admin_pvd_tag_delete_carried, it, it) },
        renameNote = carried?.let { ctx.resources.getQuantityString(R.plurals.admin_pvd_tag_rename_carried, it, it, t.name) },
    ) {
        if (env.devices.isNotEmpty()) {
            DevicesBlock(ctx.getString(R.string.admin_pvd_tag_devices), carriers, ctx.getString(R.string.admin_pvd_tag_no_devices))
        }
    }
}

/** A new tag, owned by Admins until the person picks others; written on Create. */
@Composable
internal fun NewTagForm(env: VisualEnv, actions: VisualActions, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    var name by remember { mutableStateOf("") }
    var owners by remember { mutableStateOf(Definitions.defaultOwners(env.headscale)) }
    val problem = Definitions.checkName(DefKind.TAG, name, model)
    EditorTop(Icons.AutoMirrored.Filled.Label, ctx.getString(R.string.admin_pvd_tag_new), ctx.getString(R.string.admin_pvd_tags_empty), null, env, actions, monospace = false)
    NewNameField(DefKind.TAG, name, { name = it }, model, showProblem = false)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectorField(ctx.getString(R.string.admin_pvd_tag_owners), owners, SelectorSlot.TAG_OWNER, env, onChange = { owners = it })
        HelpText(ctx.getString(if (owners.isEmpty()) R.string.admin_pvd_tag_owners_empty else R.string.admin_pvd_tag_owners_help))
    }
    CreateButton(ctx.getString(R.string.admin_pvd_create), enabled = problem == null, hint = ctx.getString(R.string.admin_pvd_create_needs_name)) {
        val full = DefKind.TAG.prefix + name.trim()
        if (actions.edit { PolicyEdits.putNamed(it, Section.TAG_OWNERS, full, owners) }) onCreated(full)
    }
}
