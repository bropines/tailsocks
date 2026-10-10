package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.runtime.Composable

/**
 * The GROUPS page: groups: members from the user list, rename everywhere, delete only when unused.
 * Owned by slice C of .claude/plans/POLICY_VISUAL_PLAN.md, which replaces this body.
 */
@Composable
fun GroupsSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    SectionPending(VisualSection.GROUPS, actions)
}
