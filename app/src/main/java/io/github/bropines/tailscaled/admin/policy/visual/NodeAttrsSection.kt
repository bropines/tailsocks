package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.runtime.Composable

/**
 * The ATTRIBUTES page: nodeAttrs: targets and attributes (funnel, drive, mullvad, NextDNS…), app capabilities shown.
 * Owned by slice C of .claude/plans/POLICY_VISUAL_PLAN.md, which replaces this body.
 */
@Composable
fun NodeAttrsSection(env: VisualEnv, actions: VisualActions, layout: VisualLayout) {
    SectionPending(VisualSection.ATTRIBUTES, actions)
}
