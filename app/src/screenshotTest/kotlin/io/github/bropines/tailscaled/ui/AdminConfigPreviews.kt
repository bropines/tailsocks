package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.AdminDashboard
import io.github.bropines.tailscaled.admin.api.ApiWebhook
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.DnsResolver
import io.github.bropines.tailscaled.admin.api.PolicyFile
import io.github.bropines.tailscaled.admin.api.PolicyPreview
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType
import io.github.bropines.tailscaled.admin.api.PolicyRuleMatch
import io.github.bropines.tailscaled.admin.api.PolicyValidation
import io.github.bropines.tailscaled.admin.api.TailnetSettingKey
import io.github.bropines.tailscaled.admin.api.TailscaleBackend
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.dns.DnsChanges
import io.github.bropines.tailscaled.admin.dns.DnsEdit
import io.github.bropines.tailscaled.admin.policy.AccessProbe
import io.github.bropines.tailscaled.admin.policy.AccessReport
import io.github.bropines.tailscaled.admin.policy.ConflictContent
import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.LineDiff
import io.github.bropines.tailscaled.admin.policy.PolicyChangeTexts
import io.github.bropines.tailscaled.admin.policy.PolicyChanges
import io.github.bropines.tailscaled.admin.policy.PolicyConflict
import io.github.bropines.tailscaled.admin.policy.PolicyEditorScreen
import io.github.bropines.tailscaled.admin.policy.PolicyEditorState
import io.github.bropines.tailscaled.admin.policy.PolicyLint
import io.github.bropines.tailscaled.admin.policy.PolicyReview
import io.github.bropines.tailscaled.admin.policy.PolicyReviewScreen
import io.github.bropines.tailscaled.admin.policy.PolicyState
import io.github.bropines.tailscaled.admin.policy.PolicyStep
import io.github.bropines.tailscaled.admin.policy.PolicyText
import io.github.bropines.tailscaled.admin.policy.ReachQuery
import io.github.bropines.tailscaled.admin.policy.StoredPolicy
import io.github.bropines.tailscaled.admin.policy.WhoCanReachContent
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeConfirmButton
import io.github.bropines.tailscaled.admin.safety.ChangeConfirmContent
import io.github.bropines.tailscaled.admin.settings.SettingsChanges
import io.github.bropines.tailscaled.admin.webhooks.CreateWebhookContent
import io.github.bropines.tailscaled.admin.webhooks.WebhookChanges
import io.github.bropines.tailscaled.admin.webhooks.WebhookSheetContent
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * The configuration tabs — policy, DNS, webhooks, settings — over AdminDemo's invented
 * tailnet, with the policy editor, its review and the 412 choice drawn without their windows.
 */

object AdminConfigDemo {
    val policy = """
        // Home tailnet policy. Comments survive the console.
        {
          "groups": {
            "group:admins": ["alex@example.com", "sam@example.com"],
          },
          "tagOwners": {
            "tag:server": ["group:admins"],
            "tag:exit":   ["group:admins"],
            "tag:iot":    ["group:admins"],
          },
          "acls": [
            // Admins reach everything.
            {"action": "accept", "src": ["group:admins"], "dst": ["*:*"]},
            // Everyone reaches the NAS over SMB and HTTPS.
            {"action": "accept", "src": ["autogroup:member"], "dst": ["tag:server:445,443"]},
            {"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:internet:*"]},
          ],
          "ssh": [
            {"action": "check", "src": ["group:admins"], "dst": ["tag:server"], "users": ["root", "autogroup:nonroot"]},
          ],
          "autoApprovers": {
            "exitNode": ["tag:exit"],
          },
          "tests": [
            {"src": "robin@example.net", "deny": ["tag:server:22"]},
          ],
        }
    """.trimIndent()

    /** The edit: the IoT tag opened to everyone, SSH widened, the one test dropped. */
    val edited = policy
        .replace(
            """{"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:internet:*"]},""",
            """{"action": "accept", "src": ["autogroup:member"], "dst": ["autogroup:internet:*"]},
    {"action": "accept", "src": ["*"], "dst": ["tag:iot:*"]},""",
        )
        .replace(""""users": ["root", "autogroup:nonroot"]}""", """"users": ["root", "autogroup:nonroot"]},
    {"action": "accept", "src": ["autogroup:member"], "dst": ["*"], "users": ["autogroup:nonroot"]}""")
        .replace("""    {"src": "robin@example.net", "deny": ["tag:server:22"]},
""", "")

    val file = PolicyFile(policy, "\"e0b2816b418c\"")

    private val diff = LineDiff.diff(LineDiff.lines(policy), LineDiff.lines(edited))

    val review = PolicyReview(
        base = file,
        candidate = edited,
        running = null,
        local = emptyList(),
        validation = PolicyValidation.OK,
        diff = diff,
        risks = PolicyLint.introduced(HuJson.parse(policy), HuJson.parse(edited)),
        access = AccessReport(
            listOf(
                AccessProbe(
                    PolicyPreviewType.USER, "alex@example.com",
                    PolicyPreview(listOf(PolicyRuleMatch(listOf("group:admins"), listOf("*:*"), 14))),
                    PolicyPreview(listOf(PolicyRuleMatch(listOf("group:admins"), listOf("*:*"), 14))),
                ),
                AccessProbe(
                    PolicyPreviewType.IP_PORT, "100.101.34.12:22",
                    PolicyPreview(listOf(PolicyRuleMatch(listOf("group:admins"), listOf("*:*"), 14))),
                    PolicyPreview(emptyList()),
                ),
            )
        ),
    )

    val failedReview = PolicyReview(
        base = file,
        candidate = edited,
        running = null,
        local = emptyList(),
        validation = PolicyValidation(
            false, "test(s) failed",
            listOf("robin@example.net: address \"tag:server:22\": want: Drop, got: Accept"),
        ),
    )

    val runningReview = review.copy(running = PolicyStep.ACCESS, access = null)

    val webhook = ApiWebhook(
        "12345", "https://hooks.example.com/tailscale", "slack", "alex@example.com", "2026-09-02T10:00:00Z", "2026-10-01T08:30:00Z",
        subscriptions = listOf("nodeNeedsApproval", "userNeedsApproval", "nodeKeyExpiringInOneDay"),
    )

    val state: ConsoleState = AdminDemo.state.copy(
        policy = PolicyState(
            file = Loadable(file, loadedAt = 1),
            previous = StoredPolicy(policy.replace("tag:server:445,443", "tag:server:443"), 1_791_500_000_000L),
        ),
        webhooks = Loadable(
            listOf(
                webhook,
                ApiWebhook("67890", "https://ops.example.org/hooks/tailscale-events", "", created = "2026-03-11T10:00:00Z", subscriptions = listOf("policyUpdate", "userRoleUpdated")),
            ),
            loadedAt = 1,
        ),
        settings = AdminDemo.state.settings.copy(
            value = AdminDemo.state.settings.value!!.copy(aclsExternallyManagedOn = false, postureIdentityCollectionOn = false),
        ),
    )

    /** A scoped OAuth client that may read everything but change only DNS. */
    val scopedState = state.copy(
        caps = Capabilities.fromScopes(BackendKind.TAILSCALE, TailscaleBackend.FEATURES, listOf("all:read", "dns")),
        settings = state.settings.copy(value = state.settings.value!!.copy(networkFlowLoggingOn = null)),
    )
}

@Composable
private fun Cfg(dark: Boolean = true, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", themePreset = "emerald", dynamicColorEnabled = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** A dialog as the gates draw it, without the window the renderer does not have. */
@Composable
private fun Frame(title: String, icon: ImageVector, confirm: @Composable () -> Unit = {}, cancel: Boolean = true, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(icon, null, Modifier.align(Alignment.CenterHorizontally), tint = MaterialTheme.colorScheme.secondary)
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (cancel) TextButton(onClick = {}) { Text(stringResource(R.string.action_cancel)) }
                    confirm()
                }
            }
        }
    }
}

@Composable
private fun Confirm(change: AdminChange, typed: String) = Frame(
    change.title, if (change.isHighRisk) Icons.Default.Warning else Icons.Default.Lock,
    confirm = { ChangeConfirmButton(change, typed) {} },
) { ChangeConfirmContent(change, typed) {} }

// ---------------------------------------------------------------- policy

@PreviewTest @AdminGeometries @Composable
fun AdminPolicyPreview() = Cfg { AdminDashboard(AdminConfigDemo.state, null, {}, startTab = ConsoleTab.POLICY) }

@PreviewTest @AdminPhone @Composable
fun AdminPolicyExternalPreview() = Cfg(dark = false) {
    val s = AdminConfigDemo.state
    AdminDashboard(
        s.copy(settings = s.settings.copy(value = s.settings.value!!.copy(aclsExternallyManagedOn = true, aclsExternalLink = "https://github.com/example/tailnet-policy"))),
        null, {}, startTab = ConsoleTab.POLICY,
    )
}

@PreviewTest @AdminGeometries @Composable
fun AdminPolicyEditorPreview() = Cfg {
    val broken = AdminConfigDemo.edited.replace("\"tag:iot:*\"]},", "\"tag:iot:*\"},")
    PolicyEditorScreen(PolicyEditorState(AdminConfigDemo.file, broken), null) {}
}

@PreviewTest @AdminGeometries @Composable
fun AdminPolicyReviewPreview() = Cfg {
    PolicyReviewScreen(AdminConfigDemo.state, PolicyEditorState(AdminConfigDemo.file, AdminConfigDemo.edited, review = AdminConfigDemo.review), AdminConfigDemo.review, null)
}

@PreviewTest @AdminPhone @Composable
fun AdminPolicyReviewFailedPreview() = Cfg(dark = false) {
    val r = AdminConfigDemo.failedReview
    PolicyReviewScreen(AdminConfigDemo.state, PolicyEditorState(AdminConfigDemo.file, r.candidate, review = r), r, null)
}

@PreviewTest @AdminPhone @Composable
fun AdminPolicyReviewRunningPreview() = Cfg {
    val r = AdminConfigDemo.runningReview
    PolicyReviewScreen(AdminConfigDemo.state, PolicyEditorState(AdminConfigDemo.file, r.candidate, review = r), r, null)
}

@PreviewTest @AdminGeometries @Composable
fun AdminPolicyConfirmPreview() = Cfg {
    val ctx = LocalContext.current
    val r = AdminConfigDemo.review
    val texts = PolicyChangeTexts(
        title = ctx.getString(R.string.admin_cfg_policy_change_title),
        effect = ctx.getString(R.string.admin_cfg_policy_change_effect),
        phrase = ctx.getString(R.string.admin_cfg_policy_phrase),
        linesLabel = ctx.getString(R.string.admin_cfg_policy_lines_label),
        linesBefore = "30", linesAfter = "31 (+2 −1)",
        warnings = r.risks.orEmpty().take(2).map { PolicyText.riskTitle(ctx, it.kind) + ": " + it.detail } + PolicyText.lostLine(ctx, r.access!!.probes[1]),
    )
    Confirm(PolicyChanges.plan(r, texts, "tail4a2c9.ts.net").change, typed = ctx.getString(R.string.admin_cfg_policy_phrase).dropLast(3))
}

@PreviewTest @AdminGeometries @Composable
fun AdminPolicyConflictPreview() = Cfg {
    val c = AdminConfigDemo
    Frame(stringResource(R.string.admin_cfg_conflict_title), Icons.Default.Warning, cancel = false) {
        ConflictContent(PolicyConflict(c.file, c.edited, c.file.copy(etag = "\"f00\""), c.edited, emptyList(), false)) {}
    }
}

@PreviewTest @AdminPhone @Composable
fun AdminPolicyConflictOverlapPreview() = Cfg(dark = false) {
    val c = AdminConfigDemo
    Frame(stringResource(R.string.admin_cfg_conflict_title), Icons.Default.Warning, cancel = false) {
        ConflictContent(PolicyConflict(c.file, c.edited, c.file.copy(etag = "\"f00\""), null, listOf(14..15), false)) {}
    }
}

@PreviewTest @AdminGeometries @Composable
fun AdminWhoCanReachPreview() = Cfg {
    val s = AdminConfigDemo.state.copy(
        policy = AdminConfigDemo.state.policy.copy(
            reachQuery = ReachQuery(PolicyPreviewType.IP_PORT, "100.72.5.101:443", "homelab-nas:443"),
            reach = Loadable(
                PolicyPreview(
                    listOf(
                        PolicyRuleMatch(listOf("group:admins"), listOf("*:*"), 14),
                        PolicyRuleMatch(listOf("autogroup:member"), listOf("tag:server:445,443"), 16),
                    )
                ),
                loadedAt = 1,
            ),
        ),
    )
    WhoCanReachContent(s, null, initialDevice = AdminDemo.devices.first { it.shortName == "homelab-nas" })
}

// ---------------------------------------------------------------- DNS

@PreviewTest @AdminGeometries @Composable
fun AdminConfigDnsPreview() = Cfg(dark = false) { AdminDashboard(AdminConfigDemo.state, null, {}, startTab = ConsoleTab.DNS) }

@PreviewTest @AdminPhone @Composable
fun AdminConfigDnsLegacyPreview() = Cfg {
    val s = AdminConfigDemo.state
    AdminDashboard(s.copy(caps = s.caps!!.copy(features = s.caps.features - BackendFeature.DNS_CONFIGURATION)), null, {}, startTab = ConsoleTab.DNS)
}

@PreviewTest @AdminGeometries @Composable
fun AdminDnsConfirmMagicOffPreview() = Cfg {
    val ctx = LocalContext.current
    Confirm(DnsChanges.plan(ctx, DnsEdit.MagicDns(false), AdminDemo.state.dns.value!!, true, "tail4a2c9.ts.net").change, typed = "tail4a2c9")
}

@PreviewTest @AdminPhone @Composable
fun AdminDnsConfirmLastNameserverPreview() = Cfg(dark = false) {
    val ctx = LocalContext.current
    val cfg = AdminDemo.state.dns.value!!.copy(nameservers = listOf(DnsResolver("1.1.1.1", true)))
    Confirm(DnsChanges.plan(ctx, DnsEdit.Nameservers(emptyList()), cfg, true, "tail4a2c9.ts.net").change, typed = "")
}

// ---------------------------------------------------------------- webhooks

@PreviewTest @AdminGeometries @Composable
fun AdminConfigWebhooksPreview() = Cfg { AdminDashboard(AdminConfigDemo.state, null, {}, startTab = ConsoleTab.WEBHOOKS) }

@PreviewTest @AdminGeometries @Composable
fun AdminWebhookSheetPreview() = Cfg { WebhookSheetContent(AdminConfigDemo.webhook, canWrite = true, vm = null) }

@PreviewTest @AdminPhone @Composable
fun AdminWebhookCreatePreview() = Cfg(dark = false) {
    Frame(stringResource(R.string.admin_webhooks_add_title), Icons.Default.Lock) {
        val events = remember { mutableStateListOf("nodeNeedsApproval", "userNeedsApproval", "nodeKeyExpiringInOneDay", "nodeKeyExpired") }
        CreateWebhookContent("http://hooks.example.com:8080/x", {}, "discord", {}, events, io.github.bropines.tailscaled.admin.webhooks.WebhookUrlError.NOT_HTTPS)
    }
}

@PreviewTest @AdminPhone @Composable
fun AdminWebhookRotatePreview() = Cfg {
    val ctx = LocalContext.current
    Confirm(WebhookChanges.rotate(ctx, AdminConfigDemo.webhook) {}.change, typed = "hooks.example.com")
}

// ---------------------------------------------------------------- settings

@PreviewTest @AdminGeometries @Composable
fun AdminConfigSettingsPreview() = Cfg { AdminDashboard(AdminConfigDemo.state, null, {}, startTab = ConsoleTab.SETTINGS) }

@PreviewTest @AdminPhone @Composable
fun AdminSettingsScopedPreview() = Cfg(dark = false) { AdminDashboard(AdminConfigDemo.scopedState, null, {}, startTab = ConsoleTab.SETTINGS) }

@PreviewTest @AdminGeometries @Composable
fun AdminSettingsConfirmHighPreview() = Cfg {
    val ctx = LocalContext.current
    Confirm(SettingsChanges.set(ctx, TailnetSettingKey.DEVICES_APPROVAL, true, false, "tail4a2c9.ts.net").change, typed = "tail4a2c9.ts.net")
}

@PreviewTest @AdminPhone @Composable
fun AdminSettingsConfirmFlowLogsPreview() = Cfg(dark = false) {
    val ctx = LocalContext.current
    Confirm(SettingsChanges.set(ctx, TailnetSettingKey.NETWORK_FLOW_LOGGING, false, true, "tail4a2c9.ts.net").change, typed = "")
}
