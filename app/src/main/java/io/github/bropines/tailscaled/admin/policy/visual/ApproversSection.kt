package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.HuObject
import io.github.bropines.tailscaled.admin.policy.PolicyLint
import io.github.bropines.tailscaled.admin.policy.PolicyText
import io.github.bropines.tailscaled.admin.policy.RiskFinding
import io.github.bropines.tailscaled.admin.policy.RiskKind
import io.github.bropines.tailscaled.ui.HelpText

/** What the auto approval page's editor shows: a route, the exit nodes, a service, or a form for a new one. */
private sealed class Approval {
    data class Route(val item: NamedList) : Approval()
    data class Exit(val approvers: List<String>, val origin: Origin) : Approval()
    data class Service(val item: NamedList) : Approval()

    val path: PolicyPath
        get() = when (this) {
            is Route -> item.origin.path
            is Exit -> origin.path
            is Service -> item.origin.path
        }
}

private enum class NewApproval { ROUTE, EXIT, SERVICE }

private val EXIT_PATH = PolicyPath.of(Section.AUTO_APPROVERS.key, "exitNode")

/**
 * The APPROVERS page (autoApprovers): subnet routes, exit nodes and — on Tailscale — services
 * that devices may advertise without an admin approving each, and who those devices are. Wide
 * routes and approvals for everyone carry the lint's own warning in their editor.
 */
@Composable
fun ApproversSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    val text = env.draft.text
    val tree = remember(text) { SourceTree.parseOrNull(text) }
    val policy = remember(text) { HuJson.parseOrNull(text) }
    val findings = remember(text) { Definitions.findings(text) }
    val aa = model.autoApprovers
    // A section the editor cannot read field by field (not an object, a key spelled otherwise) is shown, not edited.
    val pageEnv = if (aa != null && !aa.origin.editable) env.copy(canWrite = false) else env
    val routes = aa?.routes.orEmpty()
    val services = aa?.services.orEmpty()
    val exit = tree?.let { Definitions.originAt(it, EXIT_PATH) }?.let { Approval.Exit(aa?.exitNode.orEmpty(), it) }
    val showServices = !env.headscale || services.isNotEmpty()
    val all: List<Approval> = routes.map { Approval.Route(it) } + listOfNotNull(exit) + services.map { Approval.Service(it) }
    var creating by remember { mutableStateOf<NewApproval?>(null) }
    val shown = shownOf(layout.selected, layout, all) { it.path }
    fun open(kind: NewApproval) { creating = kind; layout.onSelect(null) }
    fun card(a: Approval): @Composable () -> Unit = {
        ApprovalCard(a, pageEnv, actions, policy, selected = layout.twoPane && creating == null && shown == a) {
            creating = null
            layout.onSelect(a.path)
        }
    }

    val items = buildList {
        add(PageItem("intro") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PageIntro(ctx.getString(R.string.admin_pvd_approvers_help), null, null, sectionNotes(model, Section.AUTO_APPROVERS), sectionErrors(env, Section.AUTO_APPROVERS))
                if (aa != null && !aa.origin.editable) {
                    ElementCard(aa.origin, env, actions) { DefinitionHeader(Icons.Default.ThumbUp, Section.AUTO_APPROVERS.key, null) }
                }
            }
        })
        add(PageItem("routes") {
            SubsectionHeader(ctx.getString(R.string.admin_pvd_routes_title), ctx.getString(R.string.admin_pvd_route_new).takeIf { pageEnv.editable }, { open(NewApproval.ROUTE) })
        })
        if (routes.isEmpty()) add(PageItem("routes-empty") { EmptyLine(ctx.getString(R.string.admin_pvd_routes_empty)) })
        routes.forEachIndexed { i, r ->
            r.origin.header?.let { add(PageItem("rh$i") { CommentHeading(it) }) }
            add(PageItem("route$i:${r.name}", r.origin.path, card(Approval.Route(r))))
        }
        add(PageItem("exit") {
            SubsectionHeader(ctx.getString(R.string.admin_pvd_exit_title), ctx.getString(R.string.admin_pvd_exit_new).takeIf { pageEnv.editable && exit == null }, { open(NewApproval.EXIT) })
        })
        if (exit == null) add(PageItem("exit-empty") { EmptyLine(ctx.getString(R.string.admin_pvd_exit_empty)) })
        else add(PageItem("exit-card", exit.path, card(exit)))
        if (showServices) {
            add(PageItem("services") {
                SubsectionHeader(
                    ctx.getString(R.string.admin_pvd_services_title),
                    ctx.getString(R.string.admin_pvd_service_new).takeIf { pageEnv.editable && !env.headscale },
                    { open(NewApproval.SERVICE) },
                    help = ctx.getString(R.string.admin_pvd_not_on_headscale).takeIf { env.headscale },
                )
            })
            if (services.isEmpty()) add(PageItem("services-empty") { EmptyLine(ctx.getString(R.string.admin_pvd_services_empty)) })
            services.forEachIndexed { i, s ->
                s.origin.header?.let { add(PageItem("sh$i") { CommentHeading(it) }) }
                add(PageItem("svc$i:${s.name}", s.origin.path, card(Approval.Service(s))))
            }
        }
    }
    DefinitionsPage(
        layout = layout,
        items = items,
        editorKey = creating?.let { "new-$it" } ?: shown?.path,
        onCloseEditor = { creating = null; layout.onSelect(null) },
        paneEmpty = Icons.Default.ThumbUp to ctx.getString(R.string.admin_pvd_approvers_pane_empty),
        scrollTo = env.focus ?: layout.selected,
    ) {
        val created: (PolicyPath) -> Unit = { creating = null; layout.onSelect(it) }
        when (creating) {
            NewApproval.ROUTE -> NewRouteForm(pageEnv, actions, created)
            NewApproval.EXIT -> NewExitForm(pageEnv, actions, created)
            NewApproval.SERVICE -> NewServiceForm(pageEnv, actions, created)
            null -> when (val a = shown) {
                is Approval.Route -> RouteEditor(a.item, pageEnv, actions, layout, tree, policy, findings[a.path].orEmpty())
                is Approval.Exit -> ExitEditor(a, pageEnv, actions, layout, tree, policy, findings[a.path].orEmpty())
                is Approval.Service -> ServiceEditor(a.item, pageEnv, actions, layout, tree, policy)
                null -> Unit
            }
        }
    }
}

/** The devices that may advertise something without approval: those of these users, groups and tags. */
private fun qualifying(approvers: List<String>, policy: HuObject?, env: VisualEnv) =
    RuleCoverage.of(approvers, policy, env.view).devices.toList()

private fun approversSubtitle(ctx: android.content.Context, approvers: List<String>, policy: HuObject?, env: VisualEnv): String? =
    if (env.devices.isEmpty() || approvers.isEmpty()) null
    else qualifying(approvers, policy, env).size.let { n -> ctx.resources.getQuantityString(R.plurals.admin_pvd_qualify, n, n) }

@Composable
private fun ApprovalCard(a: Approval, env: VisualEnv, actions: VisualActions, policy: HuObject?, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val (icon, title, approvers) = when (a) {
        is Approval.Route -> Triple(Icons.Default.Lan, a.item.name, a.item.values)
        is Approval.Exit -> Triple(Icons.AutoMirrored.Filled.ExitToApp, ctx.getString(R.string.admin_pvd_exit_card), a.approvers)
        is Approval.Service -> Triple(Icons.Default.Apps, a.item.name, a.item.values)
    }
    val origin = when (a) {
        is Approval.Route -> a.item.origin
        is Approval.Exit -> a.origin
        is Approval.Service -> a.item.origin
    }
    ElementCard(origin, env, actions, selected = selected, onClick = onClick) {
        DefinitionHeader(icon, title, approversSubtitle(ctx, approvers, policy, env), monospace = a !is Approval.Exit)
        LimitedLabels(
            approvers,
            caption = ctx.getString(R.string.admin_pvd_approved_for),
            empty = { Text(ctx.getString(R.string.admin_pvd_approvers_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
}

/** The approvers of one approval as chips, an empty list explained, and the devices that qualify. */
@Composable
private fun ApproversBlock(approvers: List<String>, env: VisualEnv, policy: HuObject?, onChange: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectorField(ctx.getString(R.string.admin_pvd_approvers), approvers, SelectorSlot.APPROVER, env, onChange = onChange)
        HelpText(ctx.getString(if (approvers.isEmpty()) R.string.admin_pvd_approvers_empty else R.string.admin_pvd_approvers_help_short))
    }
    if (env.devices.isNotEmpty() && approvers.isNotEmpty()) {
        DevicesBlock(ctx.getString(R.string.admin_pvd_approver_devices), qualifying(approvers, policy, env), ctx.getString(R.string.admin_pvd_approver_no_devices))
    }
}

/** A route's editor: its prefix (a key, renamed in place), the lint's warnings, approvers, note, delete. */
@Composable
private fun RouteEditor(r: NamedList, env: VisualEnv, actions: VisualActions, layout: VisualLayout, tree: SourceTree?, policy: HuObject?, risks: List<RiskFinding>) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    val editable = env.editable && r.origin.editable
    val path = r.origin.path
    EditorTop(Icons.Default.Lan, r.name, approversSubtitle(ctx, r.values, policy, env), r.origin, env, actions, risks = risks)
    CommitField(
        value = r.name,
        label = ctx.getString(R.string.admin_pvd_route_prefix),
        onCommit = { new ->
            if (actions.editAt(env.draft.text) { Definitions.renameRoute(it, r.name, new) }) layout.onSelect(path.parent() + new.trim())
        },
        enabled = editable,
        problem = { v -> routeProblem(ctx, v, model, r.name) },
        supporting = ctx.getString(R.string.admin_pvd_route_prefix_help),
    )
    ApproversBlock(r.values, if (editable) env else env.copy(canWrite = false), policy) { v -> actions.edit { Definitions.setList(it, path, v) } }
    NoteField(r.origin, tree, editable, actions)
    EditorBottom(r.origin, actions, ctx.getString(R.string.admin_pvd_route_delete).takeIf { editable }, {
        if (actions.edit { SourceEdits.remove(it, path) }) {
            layout.onSelect(null)
            actions.notify(ctx.getString(R.string.admin_pvd_deleted, r.name), undoable = true)
        }
    })
}

/** What is wrong with [v] as a route: not a prefix, or one the page already approves. */
private fun routeProblem(ctx: android.content.Context, v: String, model: PolicyModel, current: String? = null): String? {
    Definitions.checkRoute(v)?.let { return addressProblem(ctx, it) }
    val taken = model.autoApprovers?.routes?.any { it.name == v.trim() && it.name != current } == true
    return if (taken) ctx.getString(R.string.admin_pvd_route_taken) else null
}

@Composable
private fun ExitEditor(a: Approval.Exit, env: VisualEnv, actions: VisualActions, layout: VisualLayout, tree: SourceTree?, policy: HuObject?, risks: List<RiskFinding>) {
    val ctx = LocalContext.current
    val editable = env.editable && a.origin.editable
    EditorTop(Icons.AutoMirrored.Filled.ExitToApp, ctx.getString(R.string.admin_pvd_exit_card), approversSubtitle(ctx, a.approvers, policy, env), a.origin, env, actions, monospace = false, risks = risks)
    HelpText(ctx.getString(R.string.admin_pvd_exit_help))
    ApproversBlock(a.approvers, if (editable) env else env.copy(canWrite = false), policy) { v -> actions.edit { Definitions.setList(it, EXIT_PATH, v) } }
    NoteField(a.origin, tree, editable, actions)
    EditorBottom(a.origin, actions, ctx.getString(R.string.admin_pvd_exit_delete).takeIf { editable }, {
        if (actions.edit { PolicyEdits.setExitNodeApprovers(it, emptyList()) }) {
            layout.onSelect(null)
            actions.notify(ctx.getString(R.string.admin_pvd_exit_deleted), undoable = true)
        }
    })
}

/** A service's approvals: a named list whose uses (rules reaching the service) do not block removing it. */
@Composable
private fun ServiceEditor(s: NamedList, env: VisualEnv, actions: VisualActions, layout: VisualLayout, tree: SourceTree?, policy: HuObject?) {
    val ctx = LocalContext.current
    val places = remember(env.draft.text) { tree?.let { Definitions.places(it, s.name) }.orEmpty() }
    NamedListEditor(
        kind = DefKind.SERVICE,
        item = s,
        env = env,
        actions = actions,
        icon = Icons.Default.Apps,
        subtitle = approversSubtitle(ctx, s.values, policy, env).orEmpty(),
        valuesTitle = ctx.getString(R.string.admin_pvd_approvers),
        valuesHelp = ctx.getString(R.string.admin_pvd_service_help),
        slot = SelectorSlot.APPROVER,
        tree = tree,
        places = places,
        deleteLabel = ctx.getString(R.string.admin_pvd_service_delete),
        onValues = { v -> actions.edit { Definitions.setList(it, s.origin.path, v) } },
        onRename = { new -> if (actions.edit { PolicyEdits.rename(it, s.name, new) }) layout.onSelect(s.origin.path.parent() + new) },
        onDelete = {
            if (actions.edit { PolicyEdits.setServiceApprovers(it, s.name, emptyList()) }) {
                layout.onSelect(null)
                actions.notify(ctx.getString(R.string.admin_pvd_deleted, s.name), undoable = true)
            }
        },
        emptyValues = ctx.getString(R.string.admin_pvd_approvers_empty),
        blockDelete = false,
    ) {
        if (env.devices.isNotEmpty() && s.values.isNotEmpty()) {
            DevicesBlock(ctx.getString(R.string.admin_pvd_approver_devices), qualifying(s.values, policy, env), ctx.getString(R.string.admin_pvd_approver_no_devices))
        }
    }
}

// ---- new approvals: written on Create, with at least one approver ----

@Composable
private fun NewRouteForm(env: VisualEnv, actions: VisualActions, onCreated: (PolicyPath) -> Unit) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    var route by remember { mutableStateOf("") }
    var approvers by remember { mutableStateOf(emptyList<String>()) }
    val problem = routeProblem(ctx, route, model)
    EditorTop(Icons.Default.Lan, ctx.getString(R.string.admin_pvd_route_new), ctx.getString(R.string.admin_pvd_approvers_help), null, env, actions, monospace = false)
    OutlinedTextField(
        value = route,
        onValueChange = { route = it },
        singleLine = true,
        label = { Text(ctx.getString(R.string.admin_pvd_route_prefix)) },
        placeholder = { Text("192.168.1.0/24") },
        isError = route.isNotBlank() && problem != null,
        supportingText = { Text(if (route.isNotBlank() && problem != null) problem else ctx.getString(R.string.admin_pvd_route_prefix_help)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    if (problem == null && PolicyLint.isWideRoute(route.trim())) {
        WarningLine(PolicyText.riskTitle(ctx, RiskKind.AUTOAPPROVER_WIDE_ROUTE), PolicyText.riskHelp(ctx, RiskKind.AUTOAPPROVER_WIDE_ROUTE))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectorField(ctx.getString(R.string.admin_pvd_approvers), approvers, SelectorSlot.APPROVER, env, onChange = { approvers = it })
        HelpText(ctx.getString(R.string.admin_pvd_approvers_help_short))
    }
    CreateButton(ctx.getString(R.string.admin_pvd_create), enabled = problem == null && approvers.isNotEmpty(), hint = ctx.getString(R.string.admin_pvd_route_create_hint)) {
        val r = route.trim()
        if (actions.edit { PolicyEdits.setRouteApprovers(it, r, approvers) }) onCreated(PolicyPath.of(Section.AUTO_APPROVERS.key, "routes", r))
    }
}

@Composable
private fun NewExitForm(env: VisualEnv, actions: VisualActions, onCreated: (PolicyPath) -> Unit) {
    val ctx = LocalContext.current
    var approvers by remember { mutableStateOf(emptyList<String>()) }
    EditorTop(Icons.AutoMirrored.Filled.ExitToApp, ctx.getString(R.string.admin_pvd_exit_card), null, null, env, actions, monospace = false)
    HelpText(ctx.getString(R.string.admin_pvd_exit_help))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectorField(ctx.getString(R.string.admin_pvd_approvers), approvers, SelectorSlot.APPROVER, env, onChange = { approvers = it })
        HelpText(ctx.getString(R.string.admin_pvd_approvers_help_short))
    }
    CreateButton(ctx.getString(R.string.admin_pvd_create), enabled = approvers.isNotEmpty(), hint = ctx.getString(R.string.admin_pvd_approvers_create_hint)) {
        if (actions.edit { PolicyEdits.setExitNodeApprovers(it, approvers) }) onCreated(EXIT_PATH)
    }
}

@Composable
private fun NewServiceForm(env: VisualEnv, actions: VisualActions, onCreated: (PolicyPath) -> Unit) {
    val ctx = LocalContext.current
    val model = env.model ?: return
    var name by remember { mutableStateOf("") }
    var approvers by remember { mutableStateOf(emptyList<String>()) }
    val problem = Definitions.checkName(DefKind.SERVICE, name, model)
    EditorTop(Icons.Default.Apps, ctx.getString(R.string.admin_pvd_service_new), ctx.getString(R.string.admin_pvd_service_help), null, env, actions, monospace = false)
    NewNameField(DefKind.SERVICE, name, { name = it }, model, showProblem = false)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectorField(ctx.getString(R.string.admin_pvd_approvers), approvers, SelectorSlot.APPROVER, env, onChange = { approvers = it })
        HelpText(ctx.getString(R.string.admin_pvd_approvers_help_short))
    }
    CreateButton(ctx.getString(R.string.admin_pvd_create), enabled = problem == null && approvers.isNotEmpty(), hint = ctx.getString(R.string.admin_pvd_service_create_hint)) {
        val full = DefKind.SERVICE.prefix + name.trim()
        if (actions.edit { PolicyEdits.setServiceApprovers(it, full, approvers) }) onCreated(PolicyPath.of(Section.AUTO_APPROVERS.key, "services", full))
    }
}
