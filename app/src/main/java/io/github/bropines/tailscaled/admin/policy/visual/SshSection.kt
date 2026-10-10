package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.runtime.Composable

/**
 * The SSH page: SSH rules: who → can SSH to → as which users, accept or check with its period.
 * Owned by slice B of .claude/plans/POLICY_VISUAL_PLAN.md, which replaces this body.
 */
@Composable
fun SshSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    SectionPending(VisualSection.SSH, actions)
}
