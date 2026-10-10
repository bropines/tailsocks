package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.admin.policy.visual.AccessCard
import io.github.bropines.tailscaled.admin.policy.visual.AccessRule
import io.github.bropines.tailscaled.admin.policy.visual.AccessRuleEditorContent
import io.github.bropines.tailscaled.admin.policy.visual.AccessSection
import io.github.bropines.tailscaled.admin.policy.visual.CardMoves
import io.github.bropines.tailscaled.admin.policy.visual.DestinationContent
import io.github.bropines.tailscaled.admin.policy.visual.PolicyEdits
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.RuleForms
import io.github.bropines.tailscaled.admin.policy.visual.RuleOffers
import io.github.bropines.tailscaled.admin.policy.visual.Section
import io.github.bropines.tailscaled.admin.policy.visual.SelectorField
import io.github.bropines.tailscaled.admin.policy.visual.SelectorPickerContent
import io.github.bropines.tailscaled.admin.policy.visual.SelectorSlot
import io.github.bropines.tailscaled.admin.policy.visual.SshRuleEditorContent
import io.github.bropines.tailscaled.admin.policy.visual.SshSection
import io.github.bropines.tailscaled.admin.policy.visual.SshTestEditorContent
import io.github.bropines.tailscaled.admin.policy.visual.TestEditorContent
import io.github.bropines.tailscaled.admin.policy.visual.TestsSection
import io.github.bropines.tailscaled.admin.policy.visual.VisualEnv
import io.github.bropines.tailscaled.admin.policy.visual.VisualLayout
import io.github.bropines.tailscaled.admin.policy.visual.VisualSection
import io.github.bropines.tailscaled.admin.policy.visual.loginOptions
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The visual policy editor's rule pages — Access, SSH, Tests — with the demo policy
 * (PolicyVisualDemo) and a few rules added through the engine to show what the sample lacks:
 * a grant with ports and via, an SSH rule that checks, SSH tests. Pages sit in a stand-in of
 * the shell (top bar, page chips or rail) so the renders read as the screen; editors and
 * sheets are drawn without their windows.
 */

@Preview(name = "phone", device = "spec:width=411dp,height=891dp,dpi=420")
annotation class RulesPhone

@Preview(name = "phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class RulesPhoneRu

@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
annotation class RulesTablet

@Preview(name = "tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
annotation class RulesTabletRu

@Preview(name = "portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
annotation class RulesPortrait

@Preview(name = "tablet7", device = "spec:width=600dp,height=960dp,dpi=213")
annotation class RulesSmallTablet

/** The demo policy and what the rule pages also need to show. */
private object RulesDemo {
    val text: String by lazy {
        var t = PolicyVisualDemo.text
        t = PolicyEdits.addRule(t, Section.GRANTS, PolicyEdits.grantFields(listOf("group:dev-admin"), listOf("tag:homelab", "tag:lab"), listOf("tcp:8443", "53", "icmp:*"), via = listOf("tag:exit-node")))
        t = PolicyEdits.addRule(t, Section.SSH, PolicyEdits.sshFields("check", listOf("group:guest-users"), listOf("tag:homelab"), listOf("guest"), "1h"), note = "Guests get a shell on the homelab only after a fresh sign-in")
        t = PolicyEdits.addRule(t, Section.TESTS, PolicyEdits.testFields("alice@example.com", listOf("tag:master:22", "tag:dns:53"), listOf("tag:guest:22")))
        t = PolicyEdits.addRule(t, Section.SSH_TESTS, PolicyEdits.sshTestFields("group:prod-admin", listOf("tag:master"), listOf("root"), emptyList(), listOf("guest")))
        t
    }

    fun env(policy: String = text, canWrite: Boolean = true, headscale: Boolean = false, errors: Map<PolicyPath, List<String>> = emptyMap()): VisualEnv =
        PolicyVisualDemo.env(policy = policy, base = PolicyVisualDemo.text, canWrite = canWrite, headscale = headscale, errors = errors)

    /** A policy with no access rules, for the templates. */
    val noAccess: String by lazy {
        var t = PolicyVisualDemo.text
        repeat(14) { t = PolicyEdits.removeRule(t, PolicyPath.of("acls", 0)) }
        PolicyEdits.removeRule(t, PolicyPath.of("grants", 0))
    }

    /** A policy with no tests. */
    val noTests: String by lazy { PolicyEdits.removeRule(PolicyVisualDemo.text, PolicyPath.of("tests", 0)) }

    val error = mapOf(PolicyPath.of("acls", 12) to listOf("line 165: user \"carol@example.com\" is not a member of this tailnet"))

    /** Two more ACLs: one with ports per destination, one that opens a tag to everyone (a new risk). */
    val cards: String by lazy {
        var t = text
        t = PolicyEdits.addRule(t, Section.ACLS, PolicyEdits.aclFields(listOf("tag:server", "tag:nope"), listOf("tag:master:80,443", "tag:dns:53", "tag:homelab:8000-8999,9000,9443,10000"), proto = "tcp"), note = "--- 6. Monitoring ---")
        PolicyEdits.addRule(t, Section.ACLS, PolicyEdits.aclFields(listOf("*"), listOf("tag:homelab:*")))
    }
}

@Composable
private fun Theme(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

/** An editor as its sheet would hold it: the sheet's colour, the room its handle takes. */
@Composable
private fun Sheet(dark: Boolean = true, content: @Composable () -> Unit) = Theme(dark) {
    Surface(Modifier.fillMaxSize().padding(top = 24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Box(Modifier.padding(top = 24.dp)) { content() }
    }
}

/**
 * A stand-in for slice A's shell: the top bar, then the page chips (phone) or a rail (wide),
 * then [content] — what the real shell will put around a section.
 */
@Composable
private fun Shell(page: VisualSection, wide: Boolean, dark: Boolean = true, content: @Composable () -> Unit) = Theme(dark) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "Policy", subtitle = "Draft · 3 changes", onBack = {}) {
            IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.Undo, null) }
            IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.Redo, null) }
            IconButton(onClick = {}) { Icon(Icons.Default.Code, null) }
            TextButton(onClick = {}) { Text("Review") }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(88.dp).fillMaxHeight().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    VisualSection.entries.forEach { s ->
                        Icon(s.icon, null, Modifier.size(24.dp), tint = if (s == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VisualSection.entries.take(5).forEach { s -> FilterChip(selected = s == page, onClick = {}, label = { Text(ctx.getString(s.label)) }) }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

private val phone = VisualLayout(twoPane = false)
private fun pane(path: PolicyPath? = null) = VisualLayout(twoPane = true, selected = path)

// ---- the kit ----

@PreviewTest @RulesPhone @Composable
fun KitPicker() = Sheet {
    Box(Modifier.padding(top = 16.dp)) {
        SelectorPickerContent("Can reach", SelectorSlot.ACL_DST, RulesDemo.env(), listOf("tag:lab", "autogroup:internet")) {}
    }
}

@PreviewTest @RulesPhone @Composable
fun KitPickerLogins() = Sheet(dark = false) {
    val env = RulesDemo.env()
    Box(Modifier.padding(top = 16.dp)) {
        SelectorPickerContent("As", SelectorSlot.SSH_USER, env, listOf("root"), extra = loginOptions(env.model)) {}
    }
}

@PreviewTest @RulesPhone @Composable
fun KitField() = Theme {
    val env = RulesDemo.env()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SelectorField("Who", listOf("group:prod-admin", "autogroup:member", "tag:nope", "*"), SelectorSlot.ACL_SRC, env, onChange = {})
        SelectorField("From", listOf("guest@example.com"), SelectorSlot.TEST_SRC, env, onChange = {}, single = true, help = "One user, group, tag, host or address: the test checks from there.")
    }
}

@PreviewTest @RulesPhone @Composable
fun KitDestination() = Sheet {
    Box(Modifier.padding(top = 16.dp)) {
        DestinationContent(RulesDemo.env(), SelectorSlot.ACL_DST, host = "tag:homelab", ports = "80,443,8096", single = false, onDone = { _, _ -> }, onRemove = {})
    }
}

@PreviewTest @RulesPhoneRu @Composable
fun KitDestinationTest() = Sheet(dark = false) {
    Box(Modifier.padding(top = 16.dp)) {
        DestinationContent(RulesDemo.env(), SelectorSlot.TEST_DST, host = null, hosts = listOf("tag:master", "tag:dns"), ports = "443", single = true, onDone = { _, _ -> })
    }
}

// ---- Access ----

@PreviewTest @RulesPhone @Composable
fun AccessPage() = Shell(VisualSection.ACCESS, wide = false) { AccessSection(RulesDemo.env(), PolicyVisualDemo.actions, phone) }

@PreviewTest @RulesPhone @Composable
fun AccessPageLight() = Shell(VisualSection.ACCESS, wide = false, dark = false) { AccessSection(RulesDemo.env(errors = RulesDemo.error), PolicyVisualDemo.actions, phone) }

@PreviewTest @RulesPhoneRu @Composable
fun AccessPageRu() = Shell(VisualSection.ACCESS, wide = false) { AccessSection(RulesDemo.env(), PolicyVisualDemo.actions, phone) }

@PreviewTest @RulesTablet @Composable
fun AccessTablet() = Shell(VisualSection.ACCESS, wide = true) { AccessSection(RulesDemo.env(), PolicyVisualDemo.actions, pane()) }

@PreviewTest @RulesTablet @Composable
fun AccessTabletLight() = Shell(VisualSection.ACCESS, wide = true, dark = false) {
    AccessSection(RulesDemo.env(), PolicyVisualDemo.actions, pane(PolicyPath.of("grants", 1)))
}

@PreviewTest @RulesTabletRu @Composable
fun AccessTabletRu() = Shell(VisualSection.ACCESS, wide = true) { AccessSection(RulesDemo.env(), PolicyVisualDemo.actions, pane(PolicyPath.of("acls", 9))) }

@PreviewTest @RulesPortrait @Composable
fun AccessPortrait() = Shell(VisualSection.ACCESS, wide = false) { AccessSection(RulesDemo.env(), PolicyVisualDemo.actions, phone) }

@PreviewTest @RulesSmallTablet @Composable
fun AccessSmallTablet() = Shell(VisualSection.ACCESS, wide = false, dark = false) { AccessSection(RulesDemo.env(), PolicyVisualDemo.actions, phone) }

/** Headscale: no role autogroups, no postures; the server's refusal outlined on its card, which is open. */
@PreviewTest @RulesTablet @Composable
fun AccessHeadscaleError() = Shell(VisualSection.ACCESS, wide = true) {
    AccessSection(RulesDemo.env(headscale = true, errors = RulesDemo.error), PolicyVisualDemo.actions, pane(PolicyPath.of("acls", 12)))
}

@PreviewTest @RulesPhone @Composable
fun AccessEmptyHeadscale() = Shell(VisualSection.ACCESS, wide = false, dark = false) {
    AccessSection(RulesDemo.env(policy = RulesDemo.noAccess, headscale = true), PolicyVisualDemo.actions, phone)
}

@PreviewTest @RulesPhone @Composable
fun AccessEmpty() = Shell(VisualSection.ACCESS, wide = false) { AccessSection(RulesDemo.env(policy = RulesDemo.noAccess), PolicyVisualDemo.actions, phone) }

@PreviewTest @RulesPhone @Composable
fun AccessReadOnly() = Shell(VisualSection.ACCESS, wide = false, dark = false) { AccessSection(RulesDemo.env(canWrite = false), PolicyVisualDemo.actions, phone) }

@PreviewTest @RulesPhone @Composable
fun AccessCards() = Theme {
    val env = RulesDemo.env(policy = RulesDemo.cards, errors = RulesDemo.error)
    val m = env.model!!
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The arrows: both ways, the last of its list (no way down), the first (no way up), none where nothing may be written.
        AccessCard(AccessRule.Acl(m.acls[12]), env, PolicyVisualDemo.actions, moves = CardMoves(up = true, down = true) {}) {}
        AccessCard(AccessRule.Acl(m.acls[14]), env, PolicyVisualDemo.actions, selected = true, moves = CardMoves(up = true, down = true) {}) {}
        AccessCard(AccessRule.Acl(m.acls[15]), env, PolicyVisualDemo.actions, moves = CardMoves(up = true, down = false) {}) {}
        AccessCard(AccessRule.Grant(m.grants[1]), env, PolicyVisualDemo.actions, moves = CardMoves(up = false, down = true) {}) {}
        AccessCard(AccessRule.Grant(m.grants[0]), env, PolicyVisualDemo.actions) {}
    }
}

@PreviewTest @RulesPhone @Composable
fun AccessEditorAcl() = Sheet {
    val env = RulesDemo.env()
    val rule = AccessRule.Acl(env.model!!.acls[9])
    AccessRuleEditorContent(rule, env, PolicyVisualDemo.actions, isNew = false, offers = RuleOffers(test = true, convert = true), onChange = {}, onAction = {})
}

@PreviewTest @RulesPhoneRu @Composable
fun AccessEditorAclRu() = Sheet(dark = false) {
    val env = RulesDemo.env(errors = RulesDemo.error)
    val rule = AccessRule.Acl(env.model!!.acls[12])
    AccessRuleEditorContent(rule, env, PolicyVisualDemo.actions, isNew = false, offers = RuleOffers(test = true, convert = true), onChange = {}, onAction = {})
}

@PreviewTest @RulesPhone @Composable
fun AccessEditorGrant() = Sheet {
    val env = RulesDemo.env()
    val rule = AccessRule.Grant(env.model!!.grants[1])
    AccessRuleEditorContent(rule, env, PolicyVisualDemo.actions, isNew = false, offers = RuleOffers(), onChange = {}, onAction = {})
}

@PreviewTest @RulesPhone @Composable
fun AccessEditorApps() = Sheet(dark = false) {
    val env = RulesDemo.env()
    val rule = AccessRule.Grant(env.model!!.grants[0])
    AccessRuleEditorContent(rule, env, PolicyVisualDemo.actions, isNew = false, offers = RuleOffers(), onChange = {}, onAction = {})
}

@PreviewTest @RulesPhone @Composable
fun AccessEditorNew() = Sheet {
    val env = RulesDemo.env()
    val m = env.model!!
    val form = RuleForms.template(io.github.bropines.tailscaled.admin.policy.visual.AccessTemplate.GUESTS_INTERNET, m, RuleForms.newAccessSection(m, false))
    AccessRuleEditorContent(form, env, PolicyVisualDemo.actions, isNew = true, offers = RuleOffers(), onChange = {}, onAction = {})
}

// ---- SSH ----

@PreviewTest @RulesPhone @Composable
fun SshPage() = Shell(VisualSection.SSH, wide = false) { SshSection(RulesDemo.env(), PolicyVisualDemo.actions, phone) }

@PreviewTest @RulesPhoneRu @Composable
fun SshPageRu() = Shell(VisualSection.SSH, wide = false, dark = false) { SshSection(RulesDemo.env(), PolicyVisualDemo.actions, phone) }

@PreviewTest @RulesTablet @Composable
fun SshTablet() = Shell(VisualSection.SSH, wide = true) { SshSection(RulesDemo.env(), PolicyVisualDemo.actions, pane(PolicyPath.of("ssh", 3))) }

@PreviewTest @RulesTablet @Composable
fun SshTabletLight() = Shell(VisualSection.SSH, wide = true, dark = false) { SshSection(RulesDemo.env(), PolicyVisualDemo.actions, pane()) }

@PreviewTest @RulesPhone @Composable
fun SshEditor() = Sheet {
    val env = RulesDemo.env()
    SshRuleEditorContent(env.model!!.ssh[3], env, PolicyVisualDemo.actions, isNew = false, onChange = {}, onAction = {}, onAddAccess = {})
}

// ---- Tests ----

@PreviewTest @RulesPhone @Composable
fun TestsPage() = Shell(VisualSection.TESTS, wide = false) { TestsSection(RulesDemo.env(), PolicyVisualDemo.actions, phone) }

@PreviewTest @RulesPhoneRu @Composable
fun TestsPageRu() = Shell(VisualSection.TESTS, wide = false) { TestsSection(RulesDemo.env(), PolicyVisualDemo.actions, phone) }

@PreviewTest @RulesTablet @Composable
fun TestsTablet() = Shell(VisualSection.TESTS, wide = true, dark = false) { TestsSection(RulesDemo.env(), PolicyVisualDemo.actions, pane()) }

@PreviewTest @RulesPhone @Composable
fun TestsEmpty() = Shell(VisualSection.TESTS, wide = false, dark = false) { TestsSection(RulesDemo.env(policy = RulesDemo.noTests), PolicyVisualDemo.actions, phone) }

@PreviewTest @RulesPhone @Composable
fun TestEditor() = Sheet {
    val env = RulesDemo.env()
    TestEditorContent(env.model!!.tests[0], env, PolicyVisualDemo.actions, isNew = false, onChange = {}, onAction = {})
}

@PreviewTest @RulesPhone @Composable
fun SshTestEditor() = Sheet(dark = false) {
    val env = RulesDemo.env()
    SshTestEditorContent(env.model!!.sshTests[0], env, PolicyVisualDemo.actions, isNew = false, onChange = {}, onAction = {})
}
