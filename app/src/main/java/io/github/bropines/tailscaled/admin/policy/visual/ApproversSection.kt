package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.runtime.Composable

/**
 * The APPROVERS page: autoApprovers: routes, exit nodes and services approved without an admin.
 * Owned by slice C of .claude/plans/POLICY_VISUAL_PLAN.md, which replaces this body.
 */
@Composable
fun ApproversSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    SectionPending(VisualSection.APPROVERS, actions)
}
