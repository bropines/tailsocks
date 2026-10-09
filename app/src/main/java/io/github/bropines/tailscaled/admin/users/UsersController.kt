package io.github.bropines.tailscaled.admin.users

import androidx.lifecycle.viewModelScope
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiDeviceInvite
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.ApiUserInvite
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.DeviceInviteRequest
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleChanges
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.console.RevealedSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Who may send invites from this profile. Tailscale lets only a user-owned key invite (an
 * invite needs an inviting user); an OAuth client may list them and delete device shares.
 */
enum class InviteAccess { ALLOWED, NEEDS_PERSONAL_TOKEN, NOT_SUPPORTED }

fun ConsoleState.inviteAccess(feature: BackendFeature): InviteAccess {
    val c = caps ?: return InviteAccess.ALLOWED
    return when {
        !c.has(feature) -> InviteAccess.NOT_SUPPORTED
        c.credential == CredentialKind.OAUTH_CLIENT -> InviteAccess.NEEDS_PERSONAL_TOKEN
        else -> InviteAccess.ALLOWED
    }
}

/**
 * The Users tab's loads and writes on the console's ViewModel: invites to the tailnet and to
 * one device, and the per-user changes, every write through [AdminConsoleViewModel.propose].
 */
class UsersController internal constructor(private val vm: AdminConsoleViewModel) {
    /** A user another screen asked to open (the attention tab); UsersTab takes it and clears it. */
    val openUser = MutableStateFlow<String?>(null)


    private val state get() = vm.state.value

    fun loadInvites(force: Boolean = false) {
        val b = vm.backend ?: return
        if (state.caps?.has(BackendFeature.USER_INVITES) == false) return
        val current = state.userInvites
        if (current.loading || (!force && current.fresh(System.currentTimeMillis(), FRESH_MS))) return
        vm.update { it.copy(userInvites = current.copy(loading = true)) }
        vm.viewModelScope.launch {
            val r = runCatching { b.listUserInvites() }
            if (vm.backend !== b) return@launch
            vm.update { s ->
                s.copy(
                    userInvites = r.fold(
                        onSuccess = { Loadable(it.items, false, null, it.issues, System.currentTimeMillis()) },
                        onFailure = { s.userInvites.copy(loading = false, error = it) },
                    )
                )
            }
        }
    }

    fun loadDeviceInvites(d: ApiDevice, force: Boolean = false) {
        val b = vm.backend ?: return
        val id = d.pathId
        val current = state.deviceInvites[id] ?: Loadable()
        if (current.loading || (!force && current.fresh(System.currentTimeMillis(), FRESH_MS))) return
        vm.update { it.copy(deviceInvites = it.deviceInvites + (id to current.copy(loading = true, error = null))) }
        vm.viewModelScope.launch {
            val r = runCatching { b.listDeviceInvites(id) }
            if (vm.backend !== b) return@launch
            vm.update { s ->
                val prev = s.deviceInvites[id] ?: Loadable()
                s.copy(
                    deviceInvites = s.deviceInvites + (id to r.fold(
                        onSuccess = { Loadable(it.items, false, null, it.issues, System.currentTimeMillis()) },
                        onFailure = { prev.copy(loading = false, error = it) },
                    ))
                )
            }
        }
    }

    // ------------------------------------------------------------------ users

    fun approve(u: ApiUser) = vm.propose(ConsoleChanges.approveUser(vm.text, u))
    fun setRole(u: ApiUser, role: UserRole) = vm.propose(UserChanges.setRole(vm.text, u, role))
    fun suspend(u: ApiUser) = vm.propose(UserChanges.suspend(vm.text, u))
    fun restore(u: ApiUser) = vm.propose(UserChanges.restore(vm.text, u))
    fun delete(u: ApiUser) = vm.propose(UserChanges.delete(vm.text, u))

    // ------------------------------------------------------------------ invites

    /** Refused here, before the gates, when the credential cannot invite: the server would refuse anyway. */
    private fun mayInvite(feature: BackendFeature): Boolean {
        val access = state.inviteAccess(feature)
        if (access == InviteAccess.ALLOWED) return true
        vm.say(vm.text.getString(if (access == InviteAccess.NEEDS_PERSONAL_TOKEN) R.string.admin_u_invites_need_token else R.string.admin2_error_unsupported))
        return false
    }

    fun createInvite(email: String?, role: UserRole) {
        if (!mayInvite(BackendFeature.USER_INVITES)) return
        val t = vm.text
        var created: ApiUserInvite? = null
        vm.propose(UserChanges.createInvite(t, email, role, vm.tailnetLabel) { created = it }, after = {
            created?.inviteUrl?.takeIf { it.isNotBlank() }?.let { link ->
                vm.update {
                    it.copy(revealed = RevealedSecret(t.getString(R.string.admin_u_reveal_invite_title), t.getString(R.string.admin_u_reveal_invite_text), link, qr = true, once = false))
                }
            }
        })
    }

    fun resendInvite(inv: ApiUserInvite) {
        if (mayInvite(BackendFeature.USER_INVITES)) vm.propose(UserChanges.resendInvite(vm.text, inv))
    }

    fun deleteInvite(inv: ApiUserInvite) {
        if (mayInvite(BackendFeature.USER_INVITES)) vm.propose(UserChanges.deleteInvite(vm.text, inv))
    }

    fun createDeviceInvite(d: ApiDevice, request: DeviceInviteRequest) {
        if (!mayInvite(BackendFeature.DEVICE_INVITES)) return
        val t = vm.text
        var created: ApiDeviceInvite? = null
        val self = state.self.nodeId.takeIf { state.phoneInTailnet }
        vm.propose(UserChanges.createDeviceInvite(t, d, request, self) { created = it }, after = {
            loadDeviceInvites(d, force = true)
            created?.inviteUrl?.takeIf { it.isNotBlank() }?.let { link ->
                vm.update {
                    it.copy(
                        revealed = RevealedSecret(
                            t.getString(R.string.admin_u_reveal_share_title, d.shortName), t.getString(R.string.admin_u_reveal_share_text), link,
                            qr = true, once = false,
                        )
                    )
                }
            }
        })
    }

    /** Allowed with an OAuth client too: `device_invites` covers deleting. */
    fun deleteDeviceInvite(d: ApiDevice, inv: ApiDeviceInvite) =
        vm.propose(UserChanges.deleteDeviceInvite(vm.text, d, inv), after = { loadDeviceInvites(d, force = true) })

    private companion object {
        const val FRESH_MS = 60_000L
    }
}
