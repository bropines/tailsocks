package io.github.bropines.tailscaled.admin

import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who is offered the way into the policy editor from a Taildrive screen. */
class TaildrivePolicyFixTest {

    private val profile = AdminProfile(id = "p", name = "Home")

    @Test
    fun aTailscaleProfileWithACredentialMayEditThePolicy() {
        assertTrue(TaildrivePolicyFix.canEditPolicy(profile, hasCredential = true))
    }

    @Test
    fun noCredentialNoEditing() {
        assertFalse(TaildrivePolicyFix.canEditPolicy(profile, hasCredential = false))
    }

    @Test
    fun aReadOnlyProfileIsNotOffered() {
        assertFalse(TaildrivePolicyFix.canEditPolicy(profile.copy(readOnly = true), hasCredential = true))
    }

    @Test
    fun aCredentialThatWasRefusedThePolicyIsNotOffered() {
        assertFalse(TaildrivePolicyFix.canEditPolicy(profile.copy(deniedWrites = setOf(AdminArea.POLICY)), hasCredential = true))
        assertFalse(TaildrivePolicyFix.canEditPolicy(profile.copy(deniedReads = setOf(AdminArea.POLICY)), hasCredential = true))
        // Another area refused says nothing about the policy.
        assertTrue(TaildrivePolicyFix.canEditPolicy(profile.copy(deniedWrites = setOf(AdminArea.DNS)), hasCredential = true))
    }

    @Test
    fun headscaleHasNoTaildrive() {
        assertFalse(TaildrivePolicyFix.canEditPolicy(profile.copy(backend = BackendKind.HEADSCALE_V2), hasCredential = true))
        assertFalse(TaildrivePolicyFix.canEditPolicy(profile.copy(backend = BackendKind.HEADSCALE_V1), hasCredential = true))
    }
}
