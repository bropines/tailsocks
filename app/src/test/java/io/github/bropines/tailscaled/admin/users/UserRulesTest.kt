package io.github.bropines.tailscaled.admin.users

import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.ApiUserInvite
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.TailscaleBackend
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.api.UserStatus
import io.github.bropines.tailscaled.admin.console.ConsoleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserRulesTest {

    private val alex = ApiUser("uALEX", "Alex", "alex@example.com", type = "member", role = "owner", status = "active", currentlyConnected = true)
    private val sam = ApiUser("uSAM", "Sam", "sam@example.com", type = "member", role = "network-admin", status = "active")
    private val jordan = ApiUser("uJORDAN", "", "jordan@example.com", type = "member", role = "member", status = "needs-approval")
    private val casey = ApiUser("uCASEY", "Casey", "casey@example.com", type = "member", role = "member", status = "suspended")
    private val robin = ApiUser("uROBIN", "Robin", "robin@example.net", type = "shared", role = "member", status = "idle")
    private val all = listOf(alex, sam, jordan, casey, robin)

    @Test
    fun rolesAndStatusesFromTheirWireNames() {
        assertEquals(UserRole.IT_ADMIN, UserRole.of("it-admin"))
        assertEquals(UserRole.NETWORK_ADMIN, UserRole.of("network-admin"))
        assertEquals(UserRole.BILLING_ADMIN, UserRole.of("billing-admin"))
        assertEquals("the old console's spelling is not a role", UserRole.UNKNOWN, UserRole.of("itadmin"))
        assertEquals(UserStatus.NEEDS_APPROVAL, UserStatus.of("needs-approval"))
        assertEquals(UserStatus.OVER_BILLING_LIMIT, UserStatus.of("over-billing-limit"))
        assertEquals(UserStatus.UNKNOWN, UserStatus.of("pending"))
        assertEquals(UserRole.MEMBER, ApiUserInvite(id = "1", role = "member").userRole)
    }

    @Test
    fun everyValueHasItsOwnExplanation() {
        val roles = UserRole.entries.map(UserText::roleHelp)
        assertEquals(roles.size, roles.toSet().size)
        val statuses = UserStatus.entries.map(UserText::statusHelp)
        assertEquals(statuses.size, statuses.toSet().size)
        assertEquals(UserLock.entries.size, UserLock.entries.map(UserText::lockReason).toSet().size)
    }

    @Test
    fun searchMatchesNameLoginAndId() {
        assertEquals(listOf(sam), UserList.apply(all, UserFilter("SAM")))
        assertEquals(listOf(robin), UserList.apply(all, UserFilter("example.net")))
        assertEquals(listOf(jordan), UserList.apply(all, UserFilter("ujordan")))
        assertEquals(all.size, UserList.apply(all, UserFilter("  ")).size)
    }

    @Test
    fun filtersByRoleAndStatus() {
        assertEquals(listOf(jordan, casey, robin).toSet(), UserList.apply(all, UserFilter(role = UserRole.MEMBER)).toSet())
        assertEquals(listOf(casey), UserList.apply(all, UserFilter(role = UserRole.MEMBER, status = UserStatus.SUSPENDED)))
        assertTrue(UserList.apply(all, UserFilter(query = "alex", status = UserStatus.IDLE)).isEmpty())
        assertFalse(UserFilter().isActive)
        assertTrue(UserFilter(status = UserStatus.IDLE).isActive)
    }

    @Test
    fun waitingForApprovalFirstThenOnlineThenByName() {
        assertEquals(listOf(jordan, alex, casey, robin, sam), UserList.apply(all, UserFilter()))
    }

    @Test
    fun whatIsOfferedOnWhom() {
        assertEquals(UserLock.OWN, UserList.lock(sam, own = true))
        assertEquals(UserLock.SHARED, UserList.lock(robin, own = false))
        assertEquals(UserLock.OWNER, UserList.lock(alex, own = false))
        assertNull(UserList.lock(sam, own = false))
        assertTrue(UserList.actions(sam, own = true).isEmpty())
        assertTrue(UserList.actions(robin, own = false).isEmpty())
        assertTrue(UserList.actions(alex, own = false).isEmpty())
        assertEquals(listOf(UserAction.APPROVE, UserAction.CHANGE_ROLE, UserAction.SUSPEND, UserAction.DELETE), UserList.actions(jordan, own = false))
        assertEquals(listOf(UserAction.CHANGE_ROLE, UserAction.RESTORE, UserAction.DELETE), UserList.actions(casey, own = false))
    }

    @Test
    fun ownerIsNeitherAssignedNorInvited() {
        assertFalse(UserRole.OWNER in UserRole.assignable)
        assertFalse(UserRole.OWNER in UserList.inviteRoles)
        assertEquals(UserRole.MEMBER, UserList.inviteRoles.first())
    }

    @Test
    fun onlyAUserOwnedKeyInvites() {
        fun state(kind: CredentialKind, features: Set<BackendFeature> = TailscaleBackend.FEATURES) =
            ConsoleState(caps = Capabilities(BackendKind.TAILSCALE, kind, features))
        assertEquals(InviteAccess.ALLOWED, state(CredentialKind.API_TOKEN).inviteAccess(BackendFeature.USER_INVITES))
        assertEquals(InviteAccess.NEEDS_PERSONAL_TOKEN, state(CredentialKind.OAUTH_CLIENT).inviteAccess(BackendFeature.DEVICE_INVITES))
        assertEquals(
            InviteAccess.NOT_SUPPORTED,
            state(CredentialKind.HEADSCALE_API_KEY, setOf(BackendFeature.DEVICES, BackendFeature.USERS)).inviteAccess(BackendFeature.USER_INVITES),
        )
    }
}
