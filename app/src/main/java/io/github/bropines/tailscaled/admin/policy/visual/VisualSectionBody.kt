package io.github.bropines.tailscaled.admin.policy.visual

import androidx.compose.runtime.Composable

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
        VisualSection.PEER_RELAYS -> PeerRelaysSection(env, actions, layout)
        VisualSection.RELAYS -> RelaysSection(env, actions, layout)
        VisualSection.NETWORK -> NetworkSection(env, actions, layout)
    }
}
