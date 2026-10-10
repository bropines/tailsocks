package io.github.bropines.tailscaled.admin.policy.visual

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.bropines.tailscaled.R

/**
 * The visual editor's pages, in the order the navigation shows them: what the policy allows
 * first (access, SSH, approvals, tests), then what it defines (groups, tags, hosts, device
 * attributes, postures), then the network options and anything the editor only shows.
 */
enum class VisualSection(@param:StringRes val label: Int, val icon: ImageVector, val sections: Set<Section>) {
    ACCESS(R.string.admin_pv_section_access, Icons.Default.Policy, setOf(Section.ACLS, Section.GRANTS)),
    SSH(R.string.admin_pv_section_ssh, Icons.Default.Terminal, setOf(Section.SSH)),
    APPROVERS(R.string.admin_pv_section_approvers, Icons.Default.ThumbUp, setOf(Section.AUTO_APPROVERS)),
    TESTS(R.string.admin_pv_section_tests, Icons.AutoMirrored.Filled.FactCheck, setOf(Section.TESTS, Section.SSH_TESTS)),
    GROUPS(R.string.admin_pv_section_groups, Icons.Default.Groups, setOf(Section.GROUPS)),
    TAGS(R.string.admin_pv_section_tags, Icons.AutoMirrored.Filled.Label, setOf(Section.TAG_OWNERS)),
    HOSTS(R.string.admin_pv_section_hosts, Icons.Default.Dns, setOf(Section.HOSTS, Section.IPSETS)),
    ATTRIBUTES(R.string.admin_pv_section_attributes, Icons.Default.Hub, setOf(Section.NODE_ATTRS)),
    POSTURE(R.string.admin_pv_section_posture, Icons.Default.VerifiedUser, setOf(Section.POSTURES, Section.DEFAULT_SRC_POSTURE)),
    NETWORK(
        R.string.admin_pv_section_network, Icons.Default.Tune,
        setOf(Section.DERP_MAP, Section.RANDOMIZE_CLIENT_PORT, Section.DISABLE_IPV4, Section.ONE_CGNAT_ROUTE, Section.EXTERNAL_TAILNETS, Section.ATTR_CONFIG),
    ),
    ;

    /** How many elements the page lists, for the count beside its name. */
    fun count(m: PolicyModel): Int = when (this) {
        ACCESS -> m.acls.size + m.grants.size
        SSH -> m.ssh.size
        APPROVERS -> m.autoApprovers?.let { it.routes.size + it.services.size + (if (it.exitNode.isNotEmpty()) 1 else 0) } ?: 0
        TESTS -> m.tests.size + m.sshTests.size
        GROUPS -> m.groups.size
        TAGS -> m.tagOwners.size
        HOSTS -> m.hosts.size + m.ipsets.size
        ATTRIBUTES -> m.nodeAttrs.size
        POSTURE -> m.postures.size
        NETWORK -> m.sections.count { it.section == null || it.section in sections }
    }

    /**
     * Whether the page is offered: Headscale's policy has no postures, so that page shows only
     * when a file has them anyway (the server will refuse it, and the page says why).
     */
    fun available(headscale: Boolean, m: PolicyModel?): Boolean = when {
        !headscale -> true
        this == POSTURE -> m != null && count(m) > 0
        else -> true
    }

    companion object {
        /** The page an element is on: where [VisualActions.show] goes. Unknown top-level keys are on NETWORK. */
        fun of(path: PolicyPath): VisualSection {
            val key = (path.steps.firstOrNull() as? PathStep.Key)?.name ?: return ACCESS
            val section = Section.of(key) ?: return NETWORK
            return entries.firstOrNull { section in it.sections } ?: NETWORK
        }
    }
}
