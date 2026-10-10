package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.runtime.Composable

/**
 * The POSTURE page: postures and defaultSrcPosture.
 * Owned by slice C of .claude/plans/POLICY_VISUAL_PLAN.md, which replaces this body.
 */
@Composable
fun PosturesSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    SectionPending(VisualSection.POSTURE, actions)
}
