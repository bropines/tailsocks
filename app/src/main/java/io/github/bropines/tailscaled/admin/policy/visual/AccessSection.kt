package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.runtime.Composable

/**
 * The ACCESS page: acls and grants: rule cards (who → can reach what → on which ports or apps), the rule editor, Convert to grant, Add a test.
 * Owned by slice B of .claude/plans/POLICY_VISUAL_PLAN.md, which replaces this body.
 */
@Composable
fun AccessSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    SectionPending(VisualSection.ACCESS, actions)
}
