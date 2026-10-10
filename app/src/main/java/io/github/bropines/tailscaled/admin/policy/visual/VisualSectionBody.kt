package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.bropines.tailscaled.R

/**
 * One page of the visual editor. Each page is its own composable in its own file, owned by one
 * implementation slice (see .claude/plans/POLICY_VISUAL_PLAN.md, "Slices"); the shell calls this.
 */
@Composable
fun VisualSectionBody(section: VisualSection, env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    when (section) {
        VisualSection.ACCESS -> AccessSection(env, actions, layout)
        VisualSection.SSH -> SshSection(env, actions, layout)
        VisualSection.APPROVERS -> ApproversSection(env, actions, layout)
        VisualSection.TESTS -> TestsSection(env, actions, layout)
        VisualSection.GROUPS -> GroupsSection(env, actions, layout)
        VisualSection.TAGS -> TagOwnersSection(env, actions, layout)
        VisualSection.HOSTS -> HostsSection(env, actions, layout)
        VisualSection.ATTRIBUTES -> NodeAttrsSection(env, actions, layout)
        VisualSection.POSTURE -> PosturesSection(env, actions, layout)
        VisualSection.RELAYS -> RelaysSection(env, actions, layout)
        VisualSection.NETWORK -> NetworkSection(env, actions, layout)
    }
}

/** A page whose slice has not landed yet: its place, and the way to the JSON editor. */
@Composable
fun SectionPending(section: VisualSection, actions: VisualActions) {
    val ctx = LocalContext.current
    EmptySection(
        icon = Icons.Default.Construction,
        text = ctx.getString(R.string.admin_pv_section_pending, ctx.getString(section.label)),
        actionLabel = ctx.getString(R.string.admin_pv_edit_in_json),
        onAction = { actions.openJson(1) },
    )
}
