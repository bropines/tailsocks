package io.github.bropines.tailscaled.admin.profile

import android.content.Context
import android.util.Log
import io.github.bropines.tailscaled.admin.api.Access
import io.github.bropines.tailscaled.admin.api.AdminBackend
import io.github.bropines.tailscaled.admin.api.AdminCredential
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.BridgeTransport
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.FallbackTransport
import io.github.bropines.tailscaled.admin.api.HttpTransport
import io.github.bropines.tailscaled.admin.api.OAuthTokenCache
import io.github.bropines.tailscaled.admin.api.TailscaleBackend
import io.github.bropines.tailscaled.admin.api.headscale.HeadscaleBackends
import io.github.bropines.tailscaled.admin.secure.CredentialVault
import io.github.bropines.tailscaled.admin.secure.KeystoreSecretBox
import io.github.bropines.tailscaled.admin.secure.PrefsKeyValueStore
import io.github.bropines.tailscaled.admin.secure.SecretUnavailableException
import io.github.bropines.tailscaled.core.AccountManager
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.NetAddr
import java.util.UUID

/** A profile with no usable credential: none stored, or one that no longer opens. */
class MissingCredentialException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Where the console's profiles and secrets live, and the one place that turns a profile into
 * a backend — the console, the Serve screen's "publish in the tailnet" and the peer list's
 * version lookup all go through here, so they cannot disagree on credential, proxy or route.
 */
object AdminProfiles {
    private const val TAG = "AdminProfiles"
    const val VAULT_PREFS = "admin_vault"

    @Volatile
    private var migrated = false

    fun store(context: Context): AdminProfileStore {
        ensureMigrated(context)
        return rawStore(context)
    }

    fun vault(context: Context): CredentialVault = CredentialVault(
        PrefsKeyValueStore(context.applicationContext.getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE)),
        KeystoreSecretBox(),
    )

    private fun rawStore(context: Context) = AdminProfileStore(
        PrefsKeyValueStore(context.applicationContext.getSharedPreferences(AdminProfileStore.PREFS_NAME, Context.MODE_PRIVATE))
    )

    /**
     * Moves the pre-4.9 plain-text credentials into profiles once per process, and deletes the
     * old file when every secret landed. Cheap after the first call.
     */
    fun ensureMigrated(context: Context) {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            val app = context.applicationContext
            val legacy = app.getSharedPreferences(LegacyMigration.LEGACY_PREFS, Context.MODE_PRIVATE)
            val snapshot = legacy.all
            var done = true
            if (snapshot.isNotEmpty()) {
                val result = runCatching {
                    LegacyMigration.migrate(
                        legacy = snapshot,
                        store = rawStore(app),
                        vault = vault(app),
                        lastKnownTailnet = lastKnownTailnet(app),
                        newId = { UUID.randomUUID().toString() },
                        now = System.currentTimeMillis(),
                    )
                }.onFailure { Log.e(TAG, "Admin credential migration failed: ${it.javaClass.simpleName}") }.getOrNull()
                done = result?.complete == true
                if (done) {
                    Log.i(TAG, "Migrated ${result!!.created.size} admin profile(s); removing the plain-text file")
                    legacy.edit().clear().commit()
                    app.deleteSharedPreferences(LegacyMigration.LEGACY_PREFS)
                }
            }
            migrated = done
        }
    }

    /** The MagicDNS suffix the active TailSocks profile last saw; blank when none. */
    fun lastKnownTailnet(context: Context): String {
        val account = AccountManager.getActiveAccount(context)
        return context.getSharedPreferences("appctr_${account.id}", Context.MODE_PRIVATE)
            .getString("last_known_tailnet", "")?.trim().orEmpty()
    }

    /** The profile for the tailnet with this MagicDNS suffix (see [AdminProfileStore.forTailnet]). */
    fun forTailnet(context: Context, dnsSuffix: String?): AdminProfile? =
        store(context).forTailnet(dnsSuffix ?: lastKnownTailnet(context))

    /** Whether [profile] has its secret stored (whether or not it still opens). */
    fun hasCredential(context: Context, profile: AdminProfile): Boolean =
        vault(context).has(profile.id, profile.authType.slot)

    /** The decrypted credential; throws [MissingCredentialException] when there is none to use. */
    fun credential(context: Context, profile: AdminProfile): AdminCredential {
        val secret = try {
            vault(context).get(profile.id, profile.authType.slot)
        } catch (e: SecretUnavailableException) {
            throw MissingCredentialException("the stored credential cannot be opened", e)
        } ?: throw MissingCredentialException("no credential stored")
        return when (profile.authType) {
            AuthType.API_TOKEN -> AdminCredential.ApiToken(secret)
            AuthType.OAUTH_CLIENT -> AdminCredential.OAuthClient(profile.oauthClientId, secret)
            AuthType.HEADSCALE_KEY -> AdminCredential.HeadscaleKey(secret)
        }
    }

    /**
     * The backend for [profile]. Blocking (Keystore): call it off the main thread. What a
     * personal token was refused before is remembered in the profile and applied up front.
     */
    fun newBackend(context: Context, profile: AdminProfile): AdminBackend =
        newBackend(context, profile, credential(context, profile), null)

    /**
     * A backend for a profile not saved yet, or a credential not stored yet: the profile editor
     * checks what was typed with it. [proxyPassword] overrides the stored one when not null.
     */
    fun newBackend(context: Context, profile: AdminProfile, credential: AdminCredential, proxyPassword: String?): AdminBackend {
        val transport = transport(context, profile, proxyPassword)
        val log: (String) -> Unit = { Log.i("AdminApi", "[${profile.displayName}] $it") }
        return when (profile.backend) {
            BackendKind.HEADSCALE_V2, BackendKind.HEADSCALE_V1 ->
                HeadscaleBackends.create(profile.backend, credential, transport, profile.baseUrl, log)
            BackendKind.TAILSCALE -> {
                val remembered = rememberedCapabilities(profile, credential)
                TailscaleBackend(
                    credential = credential,
                    transport = transport,
                    baseUrl = profile.baseUrl,
                    tailnet = profile.tailnet,
                    initialCapabilities = remembered,
                    log = log,
                    tokenCache = OAuthTokenCache.shared,
                )
            }
        }
    }

    private fun rememberedCapabilities(profile: AdminProfile, credential: AdminCredential): Capabilities? {
        if (profile.deniedReads.isEmpty() && profile.deniedWrites.isEmpty()) return null
        val access = profile.deniedWrites.associateWith { Access.READ } + profile.deniedReads.associateWith { Access.NONE }
        return Capabilities(
            backend = profile.backend,
            credential = credential.kind,
            features = TailscaleBackend.FEATURES,
            access = access,
            ownKeyId = credential.keyId,
        )
    }

    /** The proxy as the bridge takes it, or "" for a direct connection. */
    fun proxyUrl(context: Context, profile: AdminProfile, proxyPassword: String? = null): String {
        val p = profile.proxy
        return when (p.mode) {
            AdminProxySettings.MODE_CONTROL_PLANE -> GlobalSettings.getControlProxyUrl(context).trim()
                .let { if (it.isNotEmpty() && !it.contains("://")) "socks5://$it" else it }
            AdminProxySettings.MODE_LOCAL_SOCKS5 -> {
                val addr = GlobalSettings.getString(context, "socks5", "127.0.0.1:48115").ifBlank { "127.0.0.1:48115" }
                socksUrl(
                    NetAddr.dialableHost(addr), NetAddr.port(addr) ?: 48115,
                    GlobalSettings.getString(context, "socks5_user", ""),
                    GlobalSettings.getString(context, "socks5_pass", ""),
                )
            }
            AdminProxySettings.MODE_CUSTOM_SOCKS5 -> if (p.host.isNotBlank() && p.port > 0) {
                val pass = proxyPassword
                    ?: runCatching { vault(context).get(profile.id, CredentialVault.Slot.PROXY_PASSWORD) }.getOrNull().orEmpty()
                socksUrl(p.host, p.port, p.user, pass)
            } else ""
            else -> ""
        }
    }

    private fun transport(context: Context, profile: AdminProfile, proxyPassword: String?): HttpTransport {
        val proxy = proxyUrl(context, profile, proxyPassword)
        val primary = BridgeTransport(proxy)
        // The control proxy may be down while the network is fine: reads may then go directly.
        // Never writes — see FallbackTransport.
        val fallback = if (proxy.isNotEmpty() && profile.proxy.mode == AdminProxySettings.MODE_CONTROL_PLANE) BridgeTransport("") else null
        return FallbackTransport(primary, fallback) { req, e ->
            Log.w("AdminApi", "$req failed through the control proxy (${e.message}); reading directly")
        }
    }

    private fun socksUrl(host: String, port: Int, user: String, pass: String): String {
        val auth = if (user.isNotEmpty()) "${GlobalSettings.pctEncodeUserInfo(user)}:${GlobalSettings.pctEncodeUserInfo(pass)}@" else ""
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "socks5://$auth$h:$port"
    }
}
