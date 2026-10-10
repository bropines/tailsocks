package io.github.bropines.tailscaled.admin.policy.visual

import android.content.Context
import io.github.bropines.tailscaled.R

/** The visual editor's words for selectors and problems, resolved in the Context given. */
object VisualText {

    /** A selector as a chip shows it: plain words for `*` and autogroups, the selector itself otherwise. */
    fun label(ctx: Context, value: String): String = labelRes(value)?.let(ctx::getString) ?: value

    /** What a selector means, for the picker's second line; null for names the file itself defines. */
    fun help(ctx: Context, value: String): String? = helpRes(value)?.let(ctx::getString)

    private fun labelRes(value: String): Int? = when (value) {
        "*" -> R.string.admin_pv_sel_any
        "autogroup:member" -> R.string.admin_pv_ag_member
        "autogroup:tagged" -> R.string.admin_pv_ag_tagged
        "autogroup:self" -> R.string.admin_pv_ag_self
        "autogroup:internet" -> R.string.admin_pv_ag_internet
        "autogroup:nonroot" -> R.string.admin_pv_ag_nonroot
        "autogroup:shared" -> R.string.admin_pv_ag_shared
        "autogroup:admin" -> R.string.admin_pv_ag_admin
        "autogroup:owner" -> R.string.admin_pv_ag_owner
        "autogroup:it-admin" -> R.string.admin_pv_ag_it_admin
        "autogroup:network-admin" -> R.string.admin_pv_ag_network_admin
        "autogroup:billing-admin" -> R.string.admin_pv_ag_billing_admin
        "autogroup:auditor" -> R.string.admin_pv_ag_auditor
        "autogroup:danger-all" -> R.string.admin_pv_ag_danger_all
        else -> null
    }

    private fun helpRes(value: String): Int? = when (value) {
        "*" -> R.string.admin_pv_sel_any_help
        "autogroup:member" -> R.string.admin_pv_ag_member_help
        "autogroup:tagged" -> R.string.admin_pv_ag_tagged_help
        "autogroup:self" -> R.string.admin_pv_ag_self_help
        "autogroup:internet" -> R.string.admin_pv_ag_internet_help
        "autogroup:nonroot" -> R.string.admin_pv_ag_nonroot_help
        "autogroup:shared" -> R.string.admin_pv_ag_shared_help
        "autogroup:admin", "autogroup:owner", "autogroup:it-admin", "autogroup:network-admin",
        "autogroup:billing-admin", "autogroup:auditor" -> R.string.admin_pv_ag_role_help
        "autogroup:danger-all" -> R.string.admin_pv_ag_danger_all_help
        else -> null
    }

    fun problem(ctx: Context, p: SelectorProblem): String = ctx.getString(
        when (p) {
            SelectorProblem.EMPTY -> R.string.admin_pv_problem_empty
            SelectorProblem.NOT_ALLOWED_HERE -> R.string.admin_pv_problem_not_here
            SelectorProblem.UNDEFINED -> R.string.admin_pv_problem_undefined
            SelectorProblem.NOT_ON_HEADSCALE -> R.string.admin_pv_problem_headscale
            SelectorProblem.BAD_PORTS -> R.string.admin_pv_problem_ports
            SelectorProblem.NEEDS_ONE_PORT -> R.string.admin_pv_problem_one_port
            SelectorProblem.UNKNOWN_FORM -> R.string.admin_pv_problem_unknown
        }
    )

    fun issue(ctx: Context, i: ShapeIssue): String = ctx.getString(
        when (i) {
            ShapeIssue.NOT_A_LIST, ShapeIssue.NOT_STRINGS, ShapeIssue.NOT_A_STRING, ShapeIssue.NOT_AN_OBJECT -> R.string.admin_pv_issue_list
            ShapeIssue.DUPLICATE_KEY -> R.string.admin_pv_issue_duplicate
            ShapeIssue.ODD_CASE -> R.string.admin_pv_issue_case
        }
    )

    /** The picker's heading over options of [kind]. */
    fun group(ctx: Context, kind: SelectorKind): String = ctx.getString(
        when (kind) {
            SelectorKind.GROUP, SelectorKind.EXTERNAL -> R.string.admin_pv_group_groups
            SelectorKind.TAG -> R.string.admin_pv_group_tags
            SelectorKind.USER, SelectorKind.USER_DOMAIN, SelectorKind.LOCALPART -> R.string.admin_pv_group_users
            SelectorKind.HOST, SelectorKind.IP, SelectorKind.CIDR, SelectorKind.IP_RANGE, SelectorKind.SERVICE, SelectorKind.UNKNOWN -> R.string.admin_pv_group_hosts
            SelectorKind.IPSET -> R.string.admin_pv_group_ipsets
            SelectorKind.POSTURE -> R.string.admin_pv_group_postures
            SelectorKind.ANY, SelectorKind.AUTOGROUP -> R.string.admin_pv_group_everyone
        }
    )

    fun devices(ctx: Context, n: Int): String = ctx.resources.getQuantityString(R.plurals.admin_pv_devices, n, n)
}
