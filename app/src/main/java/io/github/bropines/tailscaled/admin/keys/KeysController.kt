package io.github.bropines.tailscaled.admin.keys

import androidx.lifecycle.viewModelScope
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.api.OAuthClientRequest
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.console.RevealedSecret
import kotlinx.coroutines.launch

/**
 * The Keys tab's loads and writes, on the console's ViewModel: the key list, the policy's
 * tagOwners for the tag picker, and the three writes — each through [AdminConsoleViewModel.propose].
 * A created secret goes to the reveal dialog, with its QR code.
 */
class KeysController internal constructor(private val vm: AdminConsoleViewModel) {

    fun refresh(force: Boolean) {
        vm.refreshKeys(force)
        loadTagOwners(force)
    }

    /**
     * tagOwners from the policy file, read as HuJSON; a server that answers with plain JSON
     * only is read through the backend's own tag list. A failure leaves tags to be typed.
     */
    fun loadTagOwners(force: Boolean = false) {
        val b = vm.backend ?: return
        val current = vm.state.value.tagOwners
        if (current.loading || (!force && current.value != null)) return
        vm.update { it.copy(tagOwners = current.copy(loading = true)) }
        vm.viewModelScope.launch {
            val result = runCatching {
                val text = b.policyFile().text
                TagOwners.parse(text) ?: b.policyTags().associateWith { emptyList() }
            }
            if (vm.backend !== b) return@launch
            vm.update { s ->
                s.copy(
                    tagOwners = result.fold(
                        onSuccess = { Loadable(it, false, null, loadedAt = System.currentTimeMillis()) },
                        onFailure = { s.tagOwners.copy(loading = false, error = it) },
                    )
                )
            }
        }
    }

    /** [expiry] is the lifetime in words, for the confirm dialog. */
    fun createAuthKey(request: AuthKeyRequest, expiry: String) {
        val t = vm.text
        var created: ApiKey? = null
        vm.propose(KeyChanges.createAuthKey(t, request, expiry) { created = it }, after = {
            created?.key?.takeIf { it.isNotBlank() }?.let { secret ->
                vm.update {
                    it.copy(
                        revealed = RevealedSecret(
                            t.getString(R.string.admin_k_reveal_auth_title),
                            t.getString(R.string.admin_k_reveal_auth_text),
                            secret,
                            qr = true,
                        )
                    )
                }
            }
        })
    }

    fun createOAuthClient(request: OAuthClientRequest) {
        val t = vm.text
        var created: ApiKey? = null
        vm.propose(KeyChanges.createOAuthClient(t, request) { created = it }, after = after@{
            val c = created ?: return@after
            c.key?.takeIf { it.isNotBlank() }?.let { secret ->
                vm.update {
                    it.copy(
                        revealed = RevealedSecret(
                            t.getString(R.string.admin_k_reveal_client_title),
                            t.getString(R.string.admin_k_reveal_client_text, c.id),
                            secret,
                            qr = true,
                        )
                    )
                }
            }
        })
    }

    fun revoke(k: ApiKey) = vm.propose(KeyChanges.revoke(vm.text, k))
}
