package io.github.bropines.tailscaled.admin.console

import androidx.lifecycle.viewModelScope
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminCredential
import io.github.bropines.tailscaled.admin.headscale.HeadscaleProfiles
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.profile.AdminProfiles
import io.github.bropines.tailscaled.admin.profile.AdminProxySettings
import io.github.bropines.tailscaled.admin.profile.AuthType
import io.github.bropines.tailscaled.admin.profile.BaseUrlRules
import io.github.bropines.tailscaled.admin.secure.CredentialVault
import io.github.bropines.tailscaled.admin.secure.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The admin profiles as the console edits them: new, edit, switch, delete. A typed credential
 * is checked against the server before it is stored, and a stored one is never read back into
 * the editor. Turning a profile's read-only off takes the write unlock.
 */
class ConsoleProfiles internal constructor(private val vm: AdminConsoleViewModel) {

    private val state get() = vm.state.value

    fun startNew() = vm.update {
        it.copy(draft = ProfileDraft(), phase = if (it.active == null) ConsolePhase.SETUP else ConsolePhase.EDIT_PROFILE)
    }

    fun editActive() {
        val p = state.active ?: return
        vm.viewModelScope.launch {
            val vault = AdminProfiles.vault(vm.app)
            val (hasSecret, hasProxyPass) = withContext(Dispatchers.IO) {
                vault.has(p.id, p.authType.slot) to vault.has(p.id, CredentialVault.Slot.PROXY_PASSWORD)
            }
            vm.update {
                it.copy(
                    phase = ConsolePhase.EDIT_PROFILE,
                    draft = ProfileDraft(
                        id = p.id, name = p.name, backend = p.backend, baseUrl = p.baseUrl, tailnet = p.tailnet,
                        authType = p.authType, oauthClientId = p.oauthClientId, hasStoredSecret = hasSecret,
                        readOnly = p.readOnly, wasReadOnly = p.readOnly, proxy = p.proxy, hasStoredProxyPassword = hasProxyPass,
                    ),
                )
            }
        }
    }

    /** Edits a field; any message from the last attempt goes away with it. */
    fun change(edit: (ProfileDraft) -> ProfileDraft) = vm.update { s ->
        s.copy(draft = s.draft?.let(edit)?.copy(error = null, offerUnchecked = false))
    }

    fun cancel() = vm.leaveEditor()

    fun switchTo(id: String) {
        if (id == state.active?.id) return
        vm.viewModelScope.launch { vm.activateById(id) }
    }

    fun delete(id: String) {
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) {
                AdminProfiles.vault(vm.app).removeProfile(id)
                AdminProfiles.store(vm.app).remove(id)
            }
            vm.update { it.copy(draft = null) }
            vm.reload()
        }
    }

    /** Validates, then saves — after the write unlock when read-only is being switched off. */
    fun save(unchecked: Boolean = false) {
        val d = state.draft ?: return
        validate(d)?.let { msg ->
            vm.update { it.copy(draft = d.copy(error = msg)) }
            return
        }
        if (d.wasReadOnly && !d.readOnly) {
            vm.update { it.copy(draft = d.copy(awaitingUnlock = true)) }
            return
        }
        persist(d, unchecked)
    }

    /** The write unlock asked for by [save]; nothing is stored without it. */
    fun onUnlockForWrites(result: UnlockResult) {
        val d = state.draft ?: return
        vm.update { it.copy(draft = d.copy(awaitingUnlock = false)) }
        when (result) {
            is UnlockResult.Granted -> if (result.grant.consume()) persist(d, unchecked = false)
            UnlockResult.Cancelled -> vm.say(vm.text.getString(R.string.admin2_unlock_cancelled))
            is UnlockResult.Failed -> vm.say(ConsoleText.unlockFailure(vm.text, result.reason))
        }
    }

    private fun validate(d: ProfileDraft): String? {
        val t = vm.text
        when (val url = BaseUrlRules.check(d.baseUrl)) {
            is BaseUrlRules.Result.Invalid -> return t.getString(
                when (url.reason) {
                    BaseUrlRules.Reason.HTTPS_REQUIRED -> R.string.admin2_profile_url_https
                    BaseUrlRules.Reason.NO_CREDENTIALS_IN_URL -> R.string.admin2_profile_url_userinfo
                    BaseUrlRules.Reason.EMPTY, BaseUrlRules.Reason.MALFORMED -> R.string.admin2_profile_url_malformed
                }
            )
            is BaseUrlRules.Result.Ok -> Unit
        }
        if (d.authType == AuthType.OAUTH_CLIENT && d.oauthClientId.isBlank()) return t.getString(R.string.admin2_profile_client_id_required)
        val authChanged = d.id != null && state.profiles.firstOrNull { it.id == d.id }?.authType != d.authType
        if (d.secret.isBlank() && (!d.hasStoredSecret || authChanged)) return t.getString(R.string.admin2_profile_secret_required)
        if (d.proxy.mode == AdminProxySettings.MODE_CUSTOM_SOCKS5 && (d.proxy.host.isBlank() || d.proxy.port !in 1..65535)) {
            return t.getString(R.string.admin_proxy_socks5_required)
        }
        return null
    }

    private fun persist(d: ProfileDraft, unchecked: Boolean) {
        val existing = d.id?.let { id -> state.profiles.firstOrNull { it.id == id } }
        val id = d.id ?: UUID.randomUUID().toString()
        val newSecret = d.secret.trim()
        val credentialChanged = newSecret.isNotEmpty() || existing?.authType != d.authType || existing.oauthClientId != d.oauthClientId.trim()
        val profile = AdminProfile(
            id = id,
            name = d.name.trim(),
            backend = d.backend,
            baseUrl = (BaseUrlRules.check(d.baseUrl) as BaseUrlRules.Result.Ok).url,
            tailnet = d.tailnet.trim().ifBlank { "-" },
            tailnetDnsName = existing?.tailnetDnsName.orEmpty(),
            authType = d.authType,
            oauthClientId = d.oauthClientId.trim(),
            readOnly = d.readOnly,
            proxy = d.proxy.copy(host = d.proxy.host.trim(), user = d.proxy.user.trim()),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            legacyTailnetKey = existing?.legacyTailnetKey,
            // A new credential has its own limits to learn.
            deniedReads = if (credentialChanged) emptySet() else existing?.deniedReads.orEmpty(),
            deniedWrites = if (credentialChanged) emptySet() else existing?.deniedWrites.orEmpty(),
        )
        vm.update { it.copy(draft = d.copy(checking = true, error = null, offerUnchecked = false)) }
        vm.viewModelScope.launch {
            // A Headscale server is asked which API it has; the profile takes the backend that fits.
            val detected = HeadscaleProfiles.withDetectedBackend(vm.app, vm.text, profile, newSecret, d.proxyPassword.ifBlank { null }, unchecked)
            val toSave = when (detected) {
                is HeadscaleProfiles.Detection.Ok -> detected.profile
                is HeadscaleProfiles.Detection.Failed -> {
                    vm.update { it.copy(draft = d.copy(checking = false, error = detected.message, offerUnchecked = detected.offerUnchecked)) }
                    return@launch
                }
            }
            if (newSecret.isNotEmpty() && !unchecked) {
                val problem = tryCredential(toSave, newSecret, d.proxyPassword.ifBlank { null })
                if (problem != null) {
                    vm.update { it.copy(draft = d.copy(checking = false, error = problem.first, offerUnchecked = problem.second)) }
                    return@launch
                }
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val vault = AdminProfiles.vault(vm.app)
                    if (newSecret.isNotEmpty()) {
                        AuthType.entries.forEach { if (it != d.authType) vault.put(id, it.slot, "") }
                        check(vault.put(id, d.authType.slot, newSecret))
                    }
                    if (d.proxyPassword.isNotBlank() || d.proxy.mode != AdminProxySettings.MODE_CUSTOM_SOCKS5) {
                        vault.put(id, CredentialVault.Slot.PROXY_PASSWORD, if (d.proxy.mode == AdminProxySettings.MODE_CUSTOM_SOCKS5) d.proxyPassword else "")
                    }
                    val store = AdminProfiles.store(vm.app)
                    check(store.save(toSave))
                    store.setActive(id)
                }.isSuccess
            }
            if (!ok) {
                vm.update { it.copy(draft = d.copy(checking = false, error = vm.text.getString(R.string.admin2_error_other, "keystore"))) }
                return@launch
            }
            // Whoever just typed a working credential is the admin; the console opens.
            vm.markViewUnlocked()
            vm.update { it.copy(draft = null) }
            vm.reload(preferId = id)
        }
    }

    /**
     * Tries [secret] against the server: the credential's own key entry, or the device list
     * when the secret does not name its key. A 403 means it works and lacks a scope — fine.
     * Returns the message and whether saving without the check should be offered.
     */
    private suspend fun tryCredential(profile: AdminProfile, secret: String, proxyPassword: String?): Pair<String, Boolean>? {
        val credential = when (profile.authType) {
            AuthType.API_TOKEN -> AdminCredential.ApiToken(secret)
            AuthType.OAUTH_CLIENT -> AdminCredential.OAuthClient(profile.oauthClientId, secret)
            AuthType.HEADSCALE_KEY -> AdminCredential.HeadscaleKey(secret)
        }
        return try {
            val backend = withContext(Dispatchers.IO) { AdminProfiles.newBackend(vm.app, profile, credential, proxyPassword) }
            if (credential.keyId != null) backend.refreshCapabilities() else backend.listDevices()
            null
        } catch (e: AdminApiException.Forbidden) {
            null
        } catch (e: AdminApiException.Unauthorized) {
            vm.text.getString(R.string.admin2_profile_check_failed, ConsoleText.error(vm.text, e)) to false
        } catch (e: Exception) {
            vm.text.getString(R.string.admin2_profile_check_failed, ConsoleText.error(vm.text, e)) to true
        }
    }
}
