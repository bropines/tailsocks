package io.github.bropines.tailscaled.admin.policy.visual

import android.content.Context
import androidx.annotation.StringRes
import io.github.bropines.tailscaled.R

/** How far along Tailscale says a feature is: shown beside its name, so a person knows it may change. */
enum class FeatureStage { ALPHA, BETA }

/**
 * An application capability the editor knows by name: what it lets the devices do ([help],
 * when there is more to say than the name), whether Headscale takes it, and how far along it is.
 * Its parameters stay as written and are edited in JSON.
 */
data class KnownCap(
    val name: String,
    @param:StringRes val label: Int,
    @param:StringRes val help: Int? = null,
    val headscale: Boolean = true,
    val stage: FeatureStage? = null,
)

/**
 * The application capabilities of Tailscale's policy syntax, in the two places they are
 * written: a grant's `app` (between its sources and destinations) and a device attribute's
 * `app` (on the device itself). Anything else is kept and shown by its name.
 */
object KnownCaps {

    /** Grant capabilities (grants-app-capabilities, peer relays, Tailscale PAM). */
    val GRANT: List<KnownCap> = listOf(
        KnownCap(PeerRelays.CAP, R.string.admin_pv_app_relay, R.string.admin_pvc_cap_relay_help),
        KnownCap("tailscale.com/cap/drive", R.string.admin_pv_app_drive),
        KnownCap("tailscale.com/cap/kubernetes", R.string.admin_pv_app_kubernetes),
        KnownCap("tailscale.com/cap/tsidp", R.string.admin_pv_app_tsidp),
        KnownCap("tailscale.com/cap/webui", R.string.admin_pvc_cap_webui),
        KnownCap("tailscale.com/cap/secrets", R.string.admin_pvc_cap_secrets),
        // Headscale refuses every tailscale.com capability it does not list (validateCapabilityName).
        KnownCap("tailscale.com/cap/pam", R.string.admin_pvc_cap_pam, R.string.admin_pvc_cap_pam_help, headscale = false, stage = FeatureStage.BETA),
        KnownCap("tailscale.com/cap/tailsql", R.string.admin_pvc_cap_tailsql, headscale = false),
        KnownCap("tailscale.com/cap/golink", R.string.admin_pvc_cap_golink, headscale = false),
    )

    /** Device attribute capabilities: Headscale's nodeAttrs take no `app` at all. */
    val NODE: List<KnownCap> = listOf(
        KnownCap("tailscale.com/visible-groups", R.string.admin_pvc_node_groups, R.string.admin_pvc_node_groups_help, headscale = false, stage = FeatureStage.ALPHA),
        KnownCap("tailscale.com/app-connectors", R.string.admin_pvc_node_connectors, R.string.admin_pvc_node_connectors_help, headscale = false),
    )

    fun grant(name: String): KnownCap? = GRANT.firstOrNull { it.name == name }

    fun node(name: String): KnownCap? = NODE.firstOrNull { it.name == name }

    /**
     * Whether Headscale refuses [name] in a grant: a name with a scheme, or one in the
     * tailscale.com domain that it does not list. Capabilities of other domains it passes on.
     */
    fun grantRefusedByHeadscale(name: String): Boolean =
        "://" in name || (name.startsWith("tailscale.com/") && grant(name)?.headscale != true)

    /** A capability's name in words, with its stage: "Group visibility (alpha)"; the last part of the name when unknown. */
    fun label(ctx: Context, cap: KnownCap?, name: String): String {
        cap ?: return name.substringAfterLast('/')
        val words = ctx.getString(cap.label)
        return cap.stage?.let { ctx.getString(R.string.admin_pvc_staged, words, stage(ctx, it)) } ?: words
    }

    fun stage(ctx: Context, s: FeatureStage): String = ctx.getString(
        when (s) {
            FeatureStage.ALPHA -> R.string.admin_pvc_alpha
            FeatureStage.BETA -> R.string.admin_pvc_beta
        }
    )
}
