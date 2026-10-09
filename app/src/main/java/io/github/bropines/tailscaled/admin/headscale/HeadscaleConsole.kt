package io.github.bropines.tailscaled.admin.headscale

import androidx.lifecycle.viewModelScope
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.api.headscale.HeadscaleAdmin
import io.github.bropines.tailscaled.admin.api.headscale.HeadscaleServer
import io.github.bropines.tailscaled.admin.api.headscale.HsApiKey
import io.github.bropines.tailscaled.admin.api.headscale.RegistrationLink
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.console.RevealedSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

/** The Headscale tab's own state: the server, its API keys, a registration link being looked at. */
data class HeadscaleUiState(
    val server: Loadable<HeadscaleServer> = Loadable(),
    val apiKeys: Loadable<List<HsApiKey>> = Loadable(),
    /** A link scanned or pasted, waiting for a user to be chosen. */
    val link: RegistrationLink? = null,
    /** Why the last scanned or pasted text was not a registration link. */
    val linkProblem: RegistrationLink.Problem? = null,
)

/**
 * The console's Headscale part, beside the ViewModel's own state (like ConsoleProfiles): it
 * loads what only a Headscale server has and turns the tab's actions into planned changes,
 * which go through the same [AdminConsoleViewModel.propose] as everything else.
 */
class HeadscaleConsole internal constructor(private val vm: AdminConsoleViewModel) {

    private val _state = MutableStateFlow(HeadscaleUiState())
    val state: StateFlow<HeadscaleUiState> = _state.asStateFlow()

    private var loadedFor: Any? = null

    /** The server, its API keys. Another profile's backend starts from nothing. */
    fun refresh(force: Boolean) {
        val backend = vm.backend
        val hs = backend as? HeadscaleAdmin ?: return
        if (loadedFor !== backend) {
            loadedFor = backend
            _state.value = HeadscaleUiState()
        }
        val now = System.currentTimeMillis()
        val s = _state.value
        if (!force && s.server.fresh(now, FRESH_MS) && s.apiKeys.fresh(now, FRESH_MS)) return
        _state.update { it.copy(server = it.server.copy(loading = true), apiKeys = it.apiKeys.copy(loading = true)) }
        vm.viewModelScope.launch {
            val server = runCatching { hs.refreshServer() }
            if (vm.backend !== backend) return@launch
            _state.update {
                it.copy(server = server.fold({ v -> Loadable(v, loadedAt = System.currentTimeMillis()) }, { e -> it.server.copy(loading = false, error = e) }))
            }
            val keys = runCatching { hs.listApiKeys() }
            if (vm.backend !== backend) return@launch
            _state.update {
                it.copy(
                    apiKeys = keys.fold(
                        { l -> Loadable(l.items.sortedByDescending { k -> k.createdAt.orEmpty() }, issues = l.issues, loadedAt = System.currentTimeMillis()) },
                        { e -> it.apiKeys.copy(loading = false, error = e) },
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------ registration

    /** A scanned code or pasted text: kept when it is a registration link, explained when not. */
    fun onLinkText(text: String) {
        when (val parsed = RegistrationLink.parse(text)) {
            is RegistrationLink.Parsed.Ok -> _state.update { it.copy(link = parsed.link, linkProblem = null) }
            is RegistrationLink.Parsed.Invalid -> _state.update { it.copy(link = null, linkProblem = parsed.problem) }
        }
    }

    fun clearLink() = _state.update { it.copy(link = null, linkProblem = null) }

    /** The host of the profile's server, what a link should point at. */
    val serverHost: String? get() = vm.state.value.active?.baseUrl?.let { runCatching { URI(it).host }.getOrNull() }

    fun register(link: RegistrationLink, user: ApiUser) {
        val t = vm.text
        val change = HeadscaleChanges.register(t, link, user, serverHost) { device ->
            vm.say(t.getString(R.string.admin_hs_registered, device.shortName, user.loginName))
        }
        vm.propose(change, after = { clearLink() })
    }

    fun reject(link: RegistrationLink) = vm.propose(HeadscaleChanges.reject(vm.text, link), after = { clearLink() })

    // ------------------------------------------------------------------ users and keys

    fun createUser(name: String, displayName: String?, email: String?) =
        vm.propose(HeadscaleChanges.createUser(vm.text, name, displayName, email))

    fun renameUser(user: ApiUser, newName: String) = vm.propose(HeadscaleChanges.renameUser(vm.text, user, newName))

    /** A pre-auth key for [user] (or tagged); the secret is revealed like any new auth key. */
    fun createPreAuthKey(request: AuthKeyRequest) {
        vm.createAuthKey(request)
        vm.refreshKeys()
    }

    fun createApiKey(days: Int) {
        val t = vm.text
        var secret: String? = null
        val label = serverHost ?: vm.tailnetLabel
        vm.propose(HeadscaleChanges.createApiKey(t, label, days, System.currentTimeMillis()) { secret = it }, after = {
            secret?.let { s ->
                vm.update { it.copy(revealed = RevealedSecret(t.getString(R.string.admin_hs_apikey_secret_title), t.getString(R.string.admin_hs_apikey_secret_text), s)) }
            }
        })
    }

    fun expireApiKey(key: HsApiKey) = vm.propose(HeadscaleChanges.expireApiKey(vm.text, key))

    companion object {
        private const val FRESH_MS = 60_000L
    }
}
