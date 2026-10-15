package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.admin.InPaneWidth
import io.github.bropines.tailscaled.admin.policy.templates.ParamValue
import io.github.bropines.tailscaled.admin.policy.templates.TemplateCatalogContent
import io.github.bropines.tailscaled.admin.policy.templates.TemplateFlow
import io.github.bropines.tailscaled.admin.policy.templates.TemplateNav
import io.github.bropines.tailscaled.admin.policy.templates.TemplateSources
import io.github.bropines.tailscaled.admin.policy.visual.AccessSection
import io.github.bropines.tailscaled.admin.policy.visual.VisualEnv
import io.github.bropines.tailscaled.admin.policy.visual.VisualLayout
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The visual policy editor's templates (policy-templates/, the set built into the app): the
 * catalog — every template, one category, a search that finds nothing, the published set out
 * of reach — and a template's page — empty, filled with what Apply adds, with the device list's
 * word on relays. A phone opens them as a sheet, which the renderer does not draw, so it is
 * drawn on its own; a tablet shows them in the pane beside the Access page's list. Render this
 * class alone: `--tests '*PolicyTemplatesPreviews*'`.
 */

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class TplPhone

@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
annotation class TplTablet

/** A page's whole length, as its sheet scrolls through it. */
@Preview(name = "sheet", device = "spec:width=411dp,height=1700dp,dpi=420")
@Preview(name = "sheet-ru", device = "spec:width=411dp,height=1700dp,dpi=420", locale = "ru")
annotation class TplSheet

private object TplDemo {
    /** When the previews say "now": the published set was checked two hours before it. */
    const val NOW = 1_760_000_000_000L
    const val CHECKED = NOW - 2 * 60 * 60 * 1000L

    fun env(headscale: Boolean = false): VisualEnv = PolicyVisualDemo.env(headscale = headscale)
}

@Composable
private fun TplTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** The flow as its bottom sheet holds it over the page: the sheet's colour, its handle; the flow scrolls itself. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(dark: Boolean = true, content: @Composable ColumnScope.() -> Unit) = TplTheme(dark) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                Column(Modifier.weight(1f), content = content)
            }
        }
    }
}

/** A tablet: the Access page's list, and the flow in the pane beside it, as the page puts it there. */
@Composable
private fun Tablet(dark: Boolean = true, env: VisualEnv = TplDemo.env(), content: @Composable () -> Unit) = TplTheme(dark) {
    ListDetailLayout(
        list = { AccessSection(env, PolicyVisualDemo.actions, VisualLayout()) },
        detail = { InPaneWidth { content() } },
        twoPane = true,
    )
}

/** The built-in set as the catalog shows it after a check two hours ago. */
@Composable
private fun Catalog(nav: TemplateNav.Catalog = TemplateNav.Catalog(), env: VisualEnv = TplDemo.env(), error: String? = null) {
    val catalog = TemplateSources.bundled(LocalContext.current).copy(checkedAt = TplDemo.CHECKED, error = error)
    TemplateCatalogContent(nav, catalog, env.copy(templates = catalog), PolicyVisualDemo.actions, onNav = {}, now = TplDemo.NOW)
}

@Composable
private fun Page(id: String, values: Map<String, List<String>> = emptyMap(), env: VisualEnv = TplDemo.env(), back: Boolean = false) = TemplateFlow(
    TemplateNav.Page(id, values.mapValues { ParamValue.Items(it.value) }.takeIf { values.isNotEmpty() }, TemplateNav.Catalog().takeIf { back }),
    env, PolicyVisualDemo.actions, onNav = {}, onApplied = {}, onShow = {},
)

// ---- the catalog ----

/** Every template, checked for new ones two hours ago. */
@PreviewTest @TplPhone @Composable
fun TemplatesCatalog() = Sheet { Catalog() }

/** One category picked, light. */
@PreviewTest @TplPhone @Composable
fun TemplatesCatalogRelays() = Sheet(dark = false) { Catalog(TemplateNav.Catalog(category = "relays")) }

/** On Headscale, the published set out of reach: what Headscale does not take is not offered, and the line says why nothing new came. */
@PreviewTest @TplPhone @Composable
fun TemplatesCatalogHeadscale() = Sheet { Catalog(env = TplDemo.env(headscale = true), error = "no route to bropines.github.io") }

/** A search that finds nothing. */
@PreviewTest @TplPhone @Composable
fun TemplatesCatalogNothing() = Sheet(dark = false) { Catalog(TemplateNav.Catalog(query = "kubernetes")) }

@PreviewTest @TplTablet @Composable
fun TemplatesCatalogTablet() = Tablet { Catalog() }

// ---- a template's page ----

/** Opened from the catalog, nothing chosen yet: Apply waits, and so does the preview. */
@PreviewTest @TplPhone @Composable
fun TemplatePageEmpty() = Sheet { Page("group-to-tag", back = true) }

/** Filled in: exactly what Apply adds, in the file's own layout. */
@PreviewTest @TplSheet @Composable
fun TemplatePageFilled() = Sheet { Page("group-to-tag", mapOf("group" to listOf("group:prod-admin"), "tag" to listOf("tag:homelab"), "ports" to listOf("22", "443")), back = true) }

/** Relays for everyone, a phone among the relays: the lint's warning and the device that will not relay. */
@PreviewTest @TplSheet @Composable
fun TemplatePageRelays() = Sheet(dark = false) { Page("relays-site", mapOf("relays" to listOf("tag:exit-node", "autogroup:member"), "clients" to listOf("autogroup:member"))) }

@PreviewTest @TplTablet @Composable
fun TemplatePageTablet() = Tablet {
    Page("tailsocks-remote", mapOf("managers" to listOf("group:prod-admin"), "managed" to listOf("group:guest-users", "tag:android")), back = true)
}

@PreviewTest @TplTablet @Composable
fun TemplatePageTabletLight() = Tablet(dark = false) { Page("guests-exit-nodes", mapOf("guests" to listOf("group:guest-users"))) }
