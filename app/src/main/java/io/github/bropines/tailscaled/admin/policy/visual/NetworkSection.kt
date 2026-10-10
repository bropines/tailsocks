package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.runtime.Composable

/**
 * The NETWORK page: derpMap summary, randomizeClientPort, disableIPv4, OneCGNATRoute, and every section the editor only shows.
 * Owned by slice A of .claude/plans/POLICY_VISUAL_PLAN.md, which replaces this body.
 */
@Composable
fun NetworkSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    SectionPending(VisualSection.NETWORK, actions)
}
