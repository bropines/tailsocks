package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.runtime.Composable

/**
 * The TESTS page: tests and sshTests: what must stay allowed or denied, added by hand or from a rule.
 * Owned by slice B of .claude/plans/POLICY_VISUAL_PLAN.md, which replaces this body.
 */
@Composable
fun TestsSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    SectionPending(VisualSection.TESTS, actions)
}
