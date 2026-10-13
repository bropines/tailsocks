package io.github.bropines.tailscaled.admin

import android.content.Context
import android.content.Intent
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.notify.AttentionLaunch
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.profile.AdminProfiles
import io.github.bropines.tailscaled.core.DeviceProfile
import io.github.bropines.tailscaled.core.Feature

/**
 * The way from "the tailnet's policy does not let this device use Taildrive" to the place where
 * that is put right: the admin console's policy editor, on the Attributes page, over the profile of
 * this device's tailnet. Offered only where the console is (not on a television) and only for a
 * profile that may write the policy; everywhere else the screens say what the policy needs and
 * leave the editing to whoever administers the tailnet.
 */
object TaildrivePolicyFix {

    /**
     * Extra of [intent]: the console opens on the policy editor, the Attributes page, with the
     * Taildrive template there. AdminApiActivity reads it; nothing else may.
     */
    const val EXTRA_OPEN = "io.github.bropines.tailscaled.admin.OPEN_TAILDRIVE_POLICY"

    /**
     * Whether [profile] can be asked to write the policy: Tailscale's own control plane (Taildrive
     * is Tailscale's feature; Headscale does not compute the grants it needs), not a read-only
     * profile, with a credential stored and no write or read of the policy refused to it before.
     */
    fun canEditPolicy(profile: AdminProfile, hasCredential: Boolean): Boolean =
        profile.backend == BackendKind.TAILSCALE &&
            !profile.readOnly &&
            hasCredential &&
            AdminArea.POLICY !in profile.deniedWrites &&
            AdminArea.POLICY !in profile.deniedReads

    /**
     * The admin profile through which this device's tailnet policy can be edited, or null when
     * there is none or the device has no console. Reads preferences only; the credential is
     * not opened.
     */
    fun profileFor(context: Context): AdminProfile? {
        if (!DeviceProfile.availability(context, Feature.ADMIN_CONSOLE).reachable) return null
        val profile = AdminProfiles.forTailnet(context, null) ?: return null
        return profile.takeIf { canEditPolicy(it, AdminProfiles.hasCredential(context, it)) }
    }

    /** The console, on [profile], at the policy editor's Attributes page. */
    fun intent(context: Context, profile: AdminProfile): Intent =
        Intent(context, AdminApiActivity::class.java)
            .putExtra(AttentionLaunch.EXTRA_PROFILE_ID, profile.id)
            .putExtra(EXTRA_OPEN, true)
}
