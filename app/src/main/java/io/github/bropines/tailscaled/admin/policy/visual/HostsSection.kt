package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.runtime.Composable

/**
 * The HOSTS page: hosts (name → address) and ipsets (add and remove operations).
 * Owned by slice C of .claude/plans/POLICY_VISUAL_PLAN.md, which replaces this body.
 */
@Composable
fun HostsSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    SectionPending(VisualSection.HOSTS, actions)
}
