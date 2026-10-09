package io.github.bropines.tailscaled.admin.policy

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType
import io.github.bropines.tailscaled.admin.console.ConsoleText

/** The policy screens' words for the pipeline's findings, resolved in the Context given. */
object PolicyText {

    fun issue(ctx: Context, i: HuIssue): String = when (i.kind) {
        HuIssueKind.UNTERMINATED_STRING -> ctx.getString(R.string.admin_cfg_issue_string, i.line)
        HuIssueKind.UNTERMINATED_COMMENT -> ctx.getString(R.string.admin_cfg_issue_comment, i.line)
        HuIssueKind.UNCLOSED -> ctx.getString(R.string.admin_cfg_issue_unclosed, i.text, i.otherLine ?: i.line)
        HuIssueKind.MISMATCHED -> ctx.getString(R.string.admin_cfg_issue_mismatched, i.line, i.text, i.otherLine ?: i.line)
        HuIssueKind.UNEXPECTED ->
            if (i.text.isBlank()) ctx.getString(R.string.admin_cfg_issue_end, i.line)
            else ctx.getString(R.string.admin_cfg_issue_unexpected, i.line, i.text)
        HuIssueKind.MISSING_COLON -> ctx.getString(R.string.admin_cfg_issue_colon, i.line)
        HuIssueKind.MISSING_COMMA -> ctx.getString(R.string.admin_cfg_issue_comma, i.line)
        HuIssueKind.NOT_AN_OBJECT -> ctx.getString(R.string.admin_cfg_issue_not_object)
        HuIssueKind.EMPTY -> ctx.getString(R.string.admin_cfg_issue_empty)
    }

    fun riskTitle(ctx: Context, kind: RiskKind): String = ctx.getString(
        when (kind) {
            RiskKind.WILDCARD_SOURCE -> R.string.admin_cfg_risk_wildcard_src
            RiskKind.WILDCARD_DESTINATION -> R.string.admin_cfg_risk_wildcard_dst
            RiskKind.DANGER_ALL -> R.string.admin_cfg_risk_danger_all
            RiskKind.SSH_WILDCARD -> R.string.admin_cfg_risk_ssh_wildcard
            RiskKind.SSH_ROOT -> R.string.admin_cfg_risk_ssh_root
            RiskKind.AUTOAPPROVER_BROAD -> R.string.admin_cfg_risk_autoapprover
            RiskKind.AUTOAPPROVER_WIDE_ROUTE -> R.string.admin_cfg_risk_wide_route
            RiskKind.FUNNEL_BROAD -> R.string.admin_cfg_risk_funnel
            RiskKind.TESTS_REMOVED -> R.string.admin_cfg_risk_tests
        }
    )

    fun riskHelp(ctx: Context, kind: RiskKind): String = ctx.getString(
        when (kind) {
            RiskKind.WILDCARD_SOURCE -> R.string.admin_cfg_risk_wildcard_src_help
            RiskKind.WILDCARD_DESTINATION -> R.string.admin_cfg_risk_wildcard_dst_help
            RiskKind.DANGER_ALL -> R.string.admin_cfg_risk_danger_all_help
            RiskKind.SSH_WILDCARD -> R.string.admin_cfg_risk_ssh_wildcard_help
            RiskKind.SSH_ROOT -> R.string.admin_cfg_risk_ssh_root_help
            RiskKind.AUTOAPPROVER_BROAD -> R.string.admin_cfg_risk_autoapprover_help
            RiskKind.AUTOAPPROVER_WIDE_ROUTE -> R.string.admin_cfg_risk_wide_route_help
            RiskKind.FUNNEL_BROAD -> R.string.admin_cfg_risk_funnel_help
            RiskKind.TESTS_REMOVED -> R.string.admin_cfg_risk_tests_help
        }
    )

    /** One probe's loss as a sentence: what this phone's user stops reaching, or who stops reaching it. */
    fun lostLine(ctx: Context, p: AccessProbe): String = when (p.type) {
        PolicyPreviewType.USER -> ctx.getString(R.string.admin_cfg_access_lost_user, p.previewFor, p.lost.joinToString(", "))
        PolicyPreviewType.IP_PORT -> ctx.getString(R.string.admin_cfg_access_lost_ipport, p.previewFor, p.lost.joinToString(", "))
    }

    /** Why the access check did not run, or not all of it. */
    fun accessUnchecked(ctx: Context, a: AccessReport): String = when {
        a.skipped == AccessSkip.NOT_IN_TAILNET -> ctx.getString(R.string.admin_cfg_access_skipped)
        else -> ctx.getString(R.string.admin_cfg_access_failed, a.probes.firstNotNullOfOrNull { it.error }?.let { ConsoleText.error(ctx, it) } ?: "")
    }

    private val LINE = Regex("(?i)\\bline\\s*(\\d+)|:(\\d+):\\d+")

    /** Line numbers a server message mentions ("line 12, column 3", "policy.hujson:12:3"), for jumping to them. */
    fun lineHints(message: String?): List<Int> =
        message?.let { m -> LINE.findAll(m).mapNotNull { r -> (r.groupValues[1].ifEmpty { r.groupValues[2] }).toIntOrNull() }.distinct().take(5).toList() }.orEmpty()
}
