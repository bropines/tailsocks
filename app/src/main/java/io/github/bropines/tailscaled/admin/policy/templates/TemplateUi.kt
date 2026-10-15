package io.github.bropines.tailscaled.admin.policy.templates

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.ConsoleLinks
import io.github.bropines.tailscaled.admin.policy.LineDiff
import io.github.bropines.tailscaled.admin.policy.LineEditKind
import io.github.bropines.tailscaled.admin.policy.DiffRow
import io.github.bropines.tailscaled.admin.policy.PolicyColors
import io.github.bropines.tailscaled.admin.policy.PolicyText
import io.github.bropines.tailscaled.admin.policy.RiskKind
import io.github.bropines.tailscaled.admin.policy.codeStyle
import io.github.bropines.tailscaled.admin.policy.highlightHuJson
import io.github.bropines.tailscaled.admin.policy.rememberPolicyColors
import io.github.bropines.tailscaled.admin.policy.templates.TemplateRules.isSelector
import io.github.bropines.tailscaled.admin.policy.visual.AccessCard
import io.github.bropines.tailscaled.admin.policy.visual.CreateButton
import io.github.bropines.tailscaled.admin.policy.visual.ErrorLines
import io.github.bropines.tailscaled.admin.policy.visual.FieldTitle
import io.github.bropines.tailscaled.admin.policy.visual.KindTag
import io.github.bropines.tailscaled.admin.policy.visual.PolicyEditException
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.PortWords
import io.github.bropines.tailscaled.admin.policy.visual.PortsEditor
import io.github.bropines.tailscaled.admin.policy.visual.Section
import io.github.bropines.tailscaled.admin.policy.visual.SelectChip
import io.github.bropines.tailscaled.admin.policy.visual.SelectorField
import io.github.bropines.tailscaled.admin.policy.visual.SelectorProblem
import io.github.bropines.tailscaled.admin.policy.visual.SelectorRules
import io.github.bropines.tailscaled.admin.policy.visual.Selectors
import io.github.bropines.tailscaled.admin.policy.visual.StatusRow
import io.github.bropines.tailscaled.admin.policy.visual.TextListField
import io.github.bropines.tailscaled.admin.policy.visual.VisualActions
import io.github.bropines.tailscaled.admin.policy.visual.VisualEnv
import io.github.bropines.tailscaled.admin.policy.visual.WarningLine
import io.github.bropines.tailscaled.admin.policy.visual.accessRules
import io.github.bropines.tailscaled.admin.policy.visual.relayNoteText
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.agoText

/*
 * The visual editor's templates on screen: the catalog (search, categories, a refresh by a pull
 * or a button), a template's page — what it does, the form its parameters make with the
 * editor's own pickers, what the checks found, exactly what Apply adds, Apply — and the cards
 * a page offers templates with. Apply is one visual edit, one step to undo; the server's check
 * on save stays the gate.
 */

/** Where the templates flow is: the catalog, or one template's page. */
sealed class TemplateNav {
    /** The catalog as it was left: what was searched, which category. */
    data class Catalog(val query: String = "", val category: String? = null) : TemplateNav()

    /** A template's page: the form's values (null: the defaults), and the catalog to go back to, when it came from one. */
    data class Page(val id: String, val values: Map<String, ParamValue>? = null, val back: Catalog? = null) : TemplateNav()
}

/** The language a template's texts are picked in: the editor's. */
internal fun language(ctx: Context): String = ctx.resources.configuration.locales[0].language

/** A category as people read it; one the app does not know, as its words. */
fun categoryName(ctx: Context, category: String): String = when (category) {
    "access" -> ctx.getString(R.string.admin_pvt_cat_access)
    "ssh" -> ctx.getString(R.string.admin_pvt_cat_ssh)
    "exit-nodes" -> ctx.getString(R.string.admin_pvt_cat_exit_nodes)
    "subnets" -> ctx.getString(R.string.admin_pvt_cat_subnets)
    "taildrive" -> ctx.getString(R.string.admin_pvt_cat_taildrive)
    "remote-management" -> ctx.getString(R.string.admin_pvt_cat_remote)
    "relays" -> ctx.getString(R.string.admin_pvt_cat_relays)
    "dns" -> ctx.getString(R.string.admin_pvt_cat_dns)
    "funnel" -> ctx.getString(R.string.admin_pvt_cat_funnel)
    "k8s" -> ctx.getString(R.string.admin_pvt_cat_k8s)
    "guests" -> ctx.getString(R.string.admin_pvt_cat_guests)
    "baseline" -> ctx.getString(R.string.admin_pvt_cat_baseline)
    else -> category.replace('-', ' ').replaceFirstChar { it.uppercase() }
}

/** The icon a template's card carries over or under a page's list; none for most. */
private fun categoryIcon(category: String): ImageVector? = when (category) {
    "remote-management" -> Icons.Default.SettingsRemote
    else -> null
}

/** Whether [t] matches what was typed in the catalog's search, in any of its words. */
internal fun matches(t: PolicyTemplate, query: String, ctx: Context): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val words = t.title.values + t.description.values + listOf(t.id, categoryName(ctx, t.category))
    return words.any { it.contains(q, ignoreCase = true) }
}

/**
 * The flow a page shows in its editor's place: [nav] says what. [onApplied] gets where the
 * editor goes after Apply ([TemplatePlan.landing]); [onShow] opens a rule a check found.
 */
@Composable
fun TemplateFlow(
    nav: TemplateNav,
    env: VisualEnv,
    actions: VisualActions,
    onNav: (TemplateNav) -> Unit,
    onApplied: (PolicyPath?) -> Unit,
    onShow: (PolicyPath) -> Unit,
) {
    val catalog = rememberTemplates(env)
    when (nav) {
        is TemplateNav.Catalog -> TemplateCatalogContent(nav, catalog, env, actions, onNav)
        is TemplateNav.Page -> {
            val loaded = catalog.templates.firstOrNull { it.template.id == nav.id }
            // A refresh may take a template away while its page is open: back to the catalog.
            if (loaded == null) TemplateCatalogContent(nav.back ?: TemplateNav.Catalog(), catalog, env, actions, onNav)
            else TemplatePageContent(loaded, nav, env, actions, onNav, onApplied, onShow)
        }
    }
}

/** The scrolling column the catalog and a page are drawn in: a sheet's or a pane's whole height. */
@Composable
private fun FlowColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

// ---------------------------------------------------------------- the catalog

/** How the published set was last asked, in a line. */
private fun statusText(ctx: Context, c: TemplateCatalog, now: Long): String = when {
    c.refreshing -> ctx.getString(R.string.admin_pvt_status_checking)
    c.error != null -> ctx.getString(R.string.admin_pvt_status_error, c.error)
    c.checkedAt != null -> ctx.getString(R.string.admin_pvt_status_checked, agoText(ctx, c.checkedAt, now))
    else -> ctx.getString(R.string.admin_pvt_status_bundled)
}

/**
 * The catalog: every template this backend takes, searched and filtered by category, each a
 * card that opens its page. Opening it asks for the published set (at most once in six
 * hours); a pull or the button asks at once.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TemplateCatalogContent(nav: TemplateNav.Catalog, catalog: TemplateCatalog, env: VisualEnv, actions: VisualActions, onNav: (TemplateNav) -> Unit, now: Long = System.currentTimeMillis()) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { actions.loadTemplates(remote = true) }
    val offered = catalog.offered(env.headscale)
    val categories = offered.map { it.template.category }.distinct()
    val shown = offered.filter { (nav.category == null || it.template.category == nav.category) && matches(it.template, nav.query, ctx) }
    PullToRefreshBox(isRefreshing = catalog.refreshing, onRefresh = { actions.loadTemplates(remote = true, force = true) }, modifier = Modifier.fillMaxSize()) {
        FlowColumn {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ctx.getString(R.string.admin_pvt_catalog), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (catalog.refreshing) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { actions.loadTemplates(remote = true, force = true) }) {
                    Icon(Icons.Default.Refresh, ctx.getString(R.string.admin_pvt_refresh))
                }
            }
            HelpText(statusText(ctx, catalog, now))
            OutlinedTextField(
                value = nav.query,
                onValueChange = { onNav(nav.copy(query = it)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text(ctx.getString(R.string.admin_pvt_search)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            if (categories.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip(selected = nav.category == null, onClick = { onNav(nav.copy(category = null)) }, label = ctx.getString(R.string.admin_pvt_cat_all))
                    categories.forEach { c ->
                        SelectChip(selected = nav.category == c, onClick = { onNav(nav.copy(category = c.takeIf { nav.category != c })) }, label = categoryName(ctx, c))
                    }
                }
            }
            if (shown.isEmpty()) {
                Text(ctx.getString(R.string.admin_pvt_none_found), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shown.forEach { lt -> CatalogCard(lt, env.headscale) { onNav(TemplateNav.Page(lt.template.id, back = nav)) } }
            }
        }
    }
}

/** One template in the catalog: its name, what it does folded, its category, and what is special about it here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogCard(lt: LoadedTemplate, headscale: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val lang = language(ctx)
    val t = lt.template
    // On a sheet or a pane, which are surfaceContainerLow themselves.
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.title.pick(lang), style = MaterialTheme.typography.titleSmall)
                HelpText(t.description.pick(lang), inClickableRow = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TemplateTags(t, lt.origin, headscale)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A template's small labels: its category, what Headscale makes of it, and that it came from the published set. */
@Composable
private fun TemplateTags(t: PolicyTemplate, origin: TemplateOrigin, headscale: Boolean) {
    val ctx = LocalContext.current
    KindTag(categoryName(ctx, t.category))
    if (headscale && t.headscale.supported == HeadscaleLevel.PARTIAL) KindTag(ctx.getString(R.string.admin_pvt_hs_partial), Icons.Default.Warning)
    if (origin == TemplateOrigin.CACHE) KindTag(ctx.getString(R.string.admin_pvt_updated), Icons.Default.NewReleases)
}

// ---------------------------------------------------------------- a template's page

/**
 * A template's page: back to the catalog when it came from there, its name and what it does,
 * the form, what the checks found, what stands in the way, exactly what Apply adds, Apply, and
 * the documentation it links to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplatePageContent(
    loaded: LoadedTemplate,
    nav: TemplateNav.Page,
    env: VisualEnv,
    actions: VisualActions,
    onNav: (TemplateNav) -> Unit,
    onApplied: (PolicyPath?) -> Unit,
    onShow: (PolicyPath) -> Unit,
) {
    val ctx = LocalContext.current
    val lang = language(ctx)
    val t = loaded.template
    val values = nav.values ?: remember(t) { TemplateEngine.defaults(t) }
    val text = env.draft.text
    val plan = remember(t, values, text, env.headscale, env.devices, lang) { TemplateEngine.plan(t, values, TemplateContext(text, env.headscale, env.view, lang)) }
    val rows = remember(plan, text) { previewRows(text, plan) }
    val title = t.title.pick(lang)
    val writesAccess = remember(t) { hasAccess(t) }

    FlowColumn {
        nav.back?.let { back ->
            TextButton(onClick = { onNav(back) }, contentPadding = PaddingValues(start = 0.dp, end = 12.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(ctx.getString(R.string.admin_pvt_back))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TemplateTags(t, loaded.origin, env.headscale)
                if (writesAccess) KindTag(ctx.getString(if (plan.accessSection == Section.GRANTS) R.string.admin_pv_kind_grant else R.string.admin_pv_kind_acl))
            }
        }
        HelpText(t.description.pick(lang))
        if (env.headscale && t.headscale.supported == HeadscaleLevel.PARTIAL) {
            WarningLine(ctx.getString(R.string.admin_pvt_hs_partial), t.headscale.note?.pick(lang))
        }
        t.params.forEach { p ->
            ParamField(p, values[p.id] ?: TemplateEngine.defaults(t).getValue(p.id), env, lang) { v -> onNav(nav.copy(values = values + (p.id to v))) }
        }
        Issues(plan, lang)
        Checks(plan, env, actions, lang, onShow)
        // What cannot be applied is not previewed: the lines above say why.
        if (plan.ready || plan.issues.any { it is TemplateIssue.Missing || it is TemplateIssue.NothingToAdd }) PreviewBlock(plan, rows)
        CreateButton(
            ctx.getString(R.string.admin_pvt_apply),
            enabled = plan.ready && rows != null && env.editable,
            hint = ctx.getString(R.string.admin_pvt_apply_hint).takeIf { plan.issues.any { it is TemplateIssue.Missing } },
        ) {
            val landing = plan.landing(text)
            val written = actions.edit { current ->
                val p = TemplateEngine.plan(t, values, TemplateContext(current, env.headscale, env.view, lang))
                if (!p.ready) throw PolicyEditException("the template's form is not complete: ${p.issues}")
                p.apply(current)
            }
            if (written) {
                actions.notify(ctx.getString(R.string.admin_pvt_applied, title), undoable = true)
                onApplied(landing)
            }
        }
        Docs(t, lang)
    }
}

/** Whether a template writes access rules: its page says in which syntax. */
private fun hasAccess(t: PolicyTemplate): Boolean = when (val f = t.fragments) {
    is kotlinx.serialization.json.JsonObject -> "access" in f
    is kotlinx.serialization.json.JsonArray -> f.any { it is kotlinx.serialization.json.JsonObject && "access" in it }
    else -> false
}

/** One parameter's field, by its kind; [onChange] gets every new value, valid or not — the plan judges it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamField(p: TemplateParam, value: ParamValue, env: VisualEnv, lang: String, onChange: (ParamValue) -> Unit) {
    val ctx = LocalContext.current
    val label = p.label.pick(lang)
    val help = p.help?.pick(lang)
    val items = (value as? ParamValue.Items)?.items.orEmpty()
    when {
        p.kind.isSelector -> {
            val slot = TemplateRules.slotOf(p) ?: return
            val allowed = TemplateRules.allowedKinds(p)
            val hosts = env.model?.hosts?.map { it.name }?.toSet().orEmpty()
            SelectorField(
                label, items, slot, env,
                onChange = { onChange(ParamValue.Items(if (p.multi) it else it.takeLast(1))) },
                help = help,
                single = !p.multi,
                check = { v ->
                    env.model?.let { m -> SelectorRules.check(v, slot, m, env.headscale) }
                        ?: SelectorProblem.NOT_ALLOWED_HERE.takeIf { allowed.isNotEmpty() && Selectors.parse(v, hosts).kind !in allowed }
                },
                filter = { allowed.isEmpty() || it.kind in allowed },
            )
        }
        p.kind == ParamKind.PORT -> {
            // The typed text as it is typed ("80,"): the value is its ports.
            var raw by remember(p.id) { mutableStateOf(items.joinToString(",")) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldTitle(label)
                help?.let { HelpText(it) }
                PortsEditor(raw, onChange = { raw = it; onChange(ParamValue.Items(PortWords.parts(it))) }, single = !p.multi)
            }
        }
        p.kind == ParamKind.BOOL -> {
            val on = (value as? ParamValue.Flag)?.on == true
            Row(
                Modifier.fillMaxWidth().toggleable(value = on, enabled = env.editable, role = Role.Switch, onValueChange = { onChange(ParamValue.Flag(it)) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    FieldTitle(label)
                    help?.let { HelpText(it, inClickableRow = true) }
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = on, onCheckedChange = null, enabled = env.editable)
            }
        }
        p.kind == ParamKind.CHOICE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldTitle(label)
            help?.let { HelpText(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.options.forEach { o ->
                    val on = o.value in items
                    SelectChip(
                        selected = on,
                        enabled = env.editable,
                        onClick = { onChange(ParamValue.Items(if (p.multi) (if (on) items - o.value else items + o.value) else listOf(o.value))) },
                        label = o.label.pick(lang),
                    )
                }
            }
        }
        p.multi -> TextListField(
            label, items, env, onChange = { onChange(ParamValue.Items(it)) },
            placeholder = placeholder(p),
            help = help,
            check = { v -> ctx.getString(R.string.admin_pvt_text_problem).takeIf { !TemplateEngine.valueOk(p, v) } },
        )
        else -> {
            val v = items.firstOrNull().orEmpty()
            val bad = v.isNotEmpty() && !TemplateEngine.valueOk(p, v)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldTitle(label)
                help?.let { HelpText(it) }
                OutlinedTextField(
                    value = v,
                    onValueChange = { onChange(ParamValue.Items(listOf(it.trim()).filter { s -> s.isNotEmpty() })) },
                    singleLine = true,
                    enabled = env.editable,
                    isError = bad,
                    placeholder = { Text(placeholder(p)) },
                    supportingText = if (bad) ({ Text(ctx.getString(R.string.admin_pvt_text_problem)) }) else null,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** What a text field of [p]'s kind looks like filled in. */
private fun placeholder(p: TemplateParam): String = when (p.kind) {
    ParamKind.HOST -> "100.64.0.10"
    ParamKind.CIDR -> "192.168.1.0/24"
    else -> when (p.format) {
        "login" -> "alice@example.com"
        "duration" -> "12h"
        "tag-name" -> "server"
        else -> ""
    }
}

/** What stands in the way of Apply, besides an empty field: each with its icon and why. */
@Composable
private fun Issues(plan: TemplatePlan, lang: String) {
    val ctx = LocalContext.current
    plan.issues.forEach { i ->
        when (i) {
            is TemplateIssue.NotOnHeadscale -> WarningLine(ctx.getString(R.string.admin_pvt_hs_none), plan.template.headscale.note?.pick(lang) ?: ctx.getString(R.string.admin_pvt_not_on_headscale_help))
            is TemplateIssue.SelfWithoutOwner -> WarningLine(ctx.getString(R.string.admin_pvt_self_tags), ctx.getString(R.string.admin_pvt_self_tags_help, i.sources.joinToString(", ")))
            is TemplateIssue.BadValue -> WarningLine(ctx.getString(R.string.admin_pvt_bad_value, i.param.label.pick(lang), i.value))
            is TemplateIssue.Conflict -> WarningLine(ctx.getString(R.string.admin_pvt_conflict, i.key, i.existing, i.wanted), ctx.getString(R.string.admin_pvt_conflict_help))
            is TemplateIssue.Unwritable -> ErrorLines(listOf(ctx.getString(R.string.admin_pvt_unwritable, i.message)))
            is TemplateIssue.Missing, TemplateIssue.NothingToAdd, TemplateIssue.Unreadable -> Unit
        }
    }
}

/** What the checks found: the rules that allow it already (to open), what is kept, which devices will not take part. */
@Composable
private fun Checks(plan: TemplatePlan, env: VisualEnv, actions: VisualActions, lang: String, onShow: (PolicyPath) -> Unit) {
    val ctx = LocalContext.current
    val m = env.model ?: return
    val rules = remember(m) { accessRules(m) }
    plan.checks.filter { it.holds }.forEach { c ->
        val note = c.check.note?.pick(lang)
        when (c.check.kind) {
            CheckKind.ACCESS_OPEN -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                note?.let { NoteRow(Icons.Default.CheckCircle, it, MaterialTheme.colorScheme.primary) }
                c.paths.mapNotNull { p -> rules.firstOrNull { it.origin.path == p } }.forEach { r -> AccessCard(r, env, actions) { onShow(r.origin.path) } }
            }
            CheckKind.RELAY_DEVICES -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (c.broad.isNotEmpty()) WarningLine(PolicyText.riskTitle(ctx, RiskKind.RELAY_BROAD), PolicyText.riskHelp(ctx, RiskKind.RELAY_BROAD))
                c.relayNotes.forEach { n -> StatusRow(Icons.Default.ErrorOutline, relayNoteText(ctx, n), MaterialTheme.colorScheme.error) }
                note?.let { HelpText(it) }
            }
            CheckKind.GRANT_APP, CheckKind.NODE_ATTR -> note?.let { NoteRow(Icons.Default.Info, it, MaterialTheme.colorScheme.primary) }
        }
    }
}

/** A check's note: an icon, its first sentence, the rest folded. */
@Composable
private fun NoteRow(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(18.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        HelpText(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * What Apply adds, as the policy will hold it: the lines it writes in the file's own layout,
 * with a line around each change; null when the engine cannot write it here.
 */
internal fun previewRows(text: String, plan: TemplatePlan): List<DiffRow>? {
    if (plan.ops.isEmpty()) return emptyList()
    val after = try {
        plan.preview(text)
    } catch (e: PolicyEditException) {
        return null
    } catch (e: IllegalStateException) {
        return null
    }
    return LineDiff.rows(LineDiff.diff(LineDiff.lines(text), LineDiff.lines(after)), context = 1)
        .dropWhile { it.edit == null }.dropLastWhile { it.edit == null }
}

/** The preview's block: what Apply adds, or why there is nothing to show yet. */
@Composable
private fun PreviewBlock(plan: TemplatePlan, rows: List<DiffRow>?) {
    val ctx = LocalContext.current
    val colors = rememberPolicyColors()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ctx.getString(R.string.admin_pvt_preview), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        when {
            plan.issues.any { it is TemplateIssue.Missing } -> HelpText(ctx.getString(R.string.admin_pvt_preview_fill))
            plan.issues.any { it is TemplateIssue.NothingToAdd } -> HelpText(ctx.getString(R.string.admin_pvt_nothing))
            rows == null -> ErrorLines(listOf(ctx.getString(R.string.admin_pvt_unwritable, "")))
            else -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 10.dp, horizontal = 8.dp)) { rows.forEach { DiffLine(it, colors) } }
            }
        }
        if (plan.kept > 0) HelpText(ctx.resources.getQuantityString(R.plurals.admin_pvt_kept, plan.kept, plan.kept))
    }
}

/** One line of the preview: a +, a − or nothing before it, so it reads without its colour; a tab is two spaces. */
@Composable
private fun DiffLine(row: DiffRow, colors: PolicyColors) {
    val scheme = MaterialTheme.colorScheme
    val e = row.edit
    if (e == null) {
        Text("⋯", style = codeStyle(), color = scheme.outline, modifier = Modifier.padding(start = 18.dp))
        return
    }
    val (marker, bg) = when (e.kind) {
        LineEditKind.INSERT -> "+" to scheme.primaryContainer.copy(alpha = 0.55f)
        LineEditKind.DELETE -> "−" to scheme.errorContainer.copy(alpha = 0.55f)
        LineEditKind.EQUAL -> " " to Color.Transparent
    }
    Row(Modifier.fillMaxWidth().background(bg, MaterialTheme.shapes.small).padding(horizontal = 4.dp)) {
        Text(marker, style = codeStyle(), color = if (e.kind == LineEditKind.EQUAL) scheme.outline else scheme.onSurface, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(
            highlightHuJson(e.text.replace("\t", "  "), colors),
            style = codeStyle().copy(color = if (e.kind == LineEditKind.EQUAL) scheme.onSurfaceVariant else scheme.onSurface),
            textDecoration = if (e.kind == LineEditKind.DELETE) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The documentation a template links to: each opens in the browser. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Docs(t: PolicyTemplate, lang: String) {
    val ctx = LocalContext.current
    val docs = t.docs.filter { TemplateRules.docUrl(it.url) }
    if (docs.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(ctx.getString(R.string.admin_pvt_read_more), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            docs.forEach { d ->
                TextButton(onClick = { ConsoleLinks.open(ctx, d.url) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(d.title.pick(lang))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the cards pages offer them with

/**
 * A template a page offers: among the templates of an empty page ([inTemplates], under their
 * heading), or over or under its list, where it says it is a template.
 */
@Composable
fun TemplateSuggestionCard(t: PolicyTemplate, inTemplates: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val lang = language(ctx)
    val icon = categoryIcon(t.category).takeIf { !inTemplates }
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!inTemplates) Text(ctx.getString(R.string.admin_pv_templates), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(t.title.pick(lang), style = MaterialTheme.typography.titleSmall)
                // Among an empty page's templates, what it does in three lines; its page says the rest.
                if (inTemplates) HelpText(t.description.pick(lang).substringBefore("\n\n"), lines = 3, inClickableRow = true)
                else HelpText(t.description.pick(lang), inClickableRow = true)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The way into the catalog from a page. */
@Composable
fun AllTemplatesCard(onClick: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Category, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ctx.getString(R.string.admin_pvt_all), style = MaterialTheme.typography.titleSmall)
                HelpText(ctx.getString(R.string.admin_pvt_all_help), inClickableRow = true)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The templates [catalog] suggests on [page] in [slot] for this policy, in the catalog's order. */
@Composable
fun rememberSuggested(env: VisualEnv, page: String, slot: String): List<PolicyTemplate> {
    val catalog = rememberTemplates(env)
    val ctx = LocalContext.current
    val lang = language(ctx)
    val text = env.draft.text
    return remember(catalog, text, env.headscale, page, slot) {
        val c = TemplateContext(text, env.headscale, language = lang)
        catalog.templates.map { it.template }.filter { TemplateEngine.suggested(it, page, slot, c) }
    }
}
