package io.github.bropines.tailscaled.admin.profile

import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.TailscaleBackend
import io.github.bropines.tailscaled.admin.secure.CredentialVault
import kotlinx.serialization.Serializable
import java.net.URI

enum class AuthType(val slot: CredentialVault.Slot) {
    API_TOKEN(CredentialVault.Slot.API_TOKEN),
    OAUTH_CLIENT(CredentialVault.Slot.OAUTH_SECRET),
    HEADSCALE_KEY(CredentialVault.Slot.HEADSCALE_KEY),
}

/** How the console reaches the API; the password, when there is one, is in the vault. */
@Serializable
data class AdminProxySettings(
    /** CONTROL_PLANE (the daemon's control proxy), DIRECT, LOCAL_SOCKS5 (our own listener), CUSTOM_SOCKS5. */
    val mode: String = MODE_CONTROL_PLANE,
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
) {
    companion object {
        const val MODE_CONTROL_PLANE = "CONTROL_PLANE"
        const val MODE_DIRECT = "DIRECT"
        const val MODE_LOCAL_SOCKS5 = "LOCAL_SOCKS5"
        const val MODE_CUSTOM_SOCKS5 = "CUSTOM_SOCKS5"
        val modes = listOf(MODE_CONTROL_PLANE, MODE_DIRECT, MODE_LOCAL_SOCKS5, MODE_CUSTOM_SOCKS5)
    }
}

/**
 * One control server and credential the console manages, independent of the TailSocks
 * profiles: an admin may run the console for a tailnet this phone is not a node of. Secrets are
 * never here — they are in the [CredentialVault] under [id].
 */
@Serializable
data class AdminProfile(
    val id: String,
    val name: String,
    val backend: BackendKind = BackendKind.TAILSCALE,
    /** HTTPS, or HTTP to this device only; see [BaseUrlRules]. */
    val baseUrl: String = TailscaleBackend.DEFAULT_BASE_URL,
    /** The tailnet in API paths: "-" (the credential's own) or a Tailnet ID. */
    val tailnet: String = "-",
    /**
     * The tailnet's MagicDNS suffix ("tail1234.ts.net") when known — how the Serve screen and
     * the peer list find the profile for the tailnet this phone is on. Learned from the device
     * list; not an API identifier.
     */
    val tailnetDnsName: String = "",
    val authType: AuthType = AuthType.API_TOKEN,
    /** An OAuth client's id; not secret. */
    val oauthClientId: String = "",
    /** Never write through this profile, whatever the credential allows. */
    val readOnly: Boolean = false,
    val proxy: AdminProxySettings = AdminProxySettings(),
    val createdAt: Long = 0,
    /** The pre-4.9 per-tailnet key this profile was migrated from; keeps a second migration from duplicating it. */
    val legacyTailnetKey: String? = null,
    /** What a personal token was refused, kept between sessions so the console does not ask again. */
    val deniedReads: Set<AdminArea> = emptySet(),
    val deniedWrites: Set<AdminArea> = emptySet(),
) {
    val displayName: String get() = name.ifBlank { tailnetDnsName.ifBlank { baseUrl } }
}

object BaseUrlRules {
    sealed class Result {
        data class Ok(val url: String) : Result()
        data class Invalid(val reason: Reason) : Result()
    }

    enum class Reason { EMPTY, MALFORMED, HTTPS_REQUIRED, NO_CREDENTIALS_IN_URL }

    /**
     * A control server's address as the console may use it: `https://`, or `http://` only to
     * this device itself (a local Headscale, a tunnel). A secret is never sent in clear over a
     * network. Path kept (a server behind a reverse proxy at /headscale), trailing slash
     * dropped; no user info, query or fragment.
     */
    fun check(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Invalid(Reason.EMPTY)
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return Result.Invalid(Reason.MALFORMED)
        }
        val scheme = uri.scheme?.lowercase() ?: return Result.Invalid(Reason.MALFORMED)
        val host = uri.host?.lowercase() ?: return Result.Invalid(Reason.MALFORMED)
        if (uri.rawUserInfo != null) return Result.Invalid(Reason.NO_CREDENTIALS_IN_URL)
        if (uri.rawQuery != null || uri.rawFragment != null) return Result.Invalid(Reason.MALFORMED)
        when (scheme) {
            "https" -> Unit
            "http" -> if (!isLoopback(host)) return Result.Invalid(Reason.HTTPS_REQUIRED)
            else -> return Result.Invalid(Reason.MALFORMED)
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val h = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return Result.Ok("$scheme://$h$port${(uri.rawPath ?: "").trimEnd('/')}")
    }

    fun isLoopback(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        if (h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        val parts = h.split('.')
        return parts.size == 4 && parts[0] == "127" && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    /** Whether [url] is Tailscale's own API, where the Tailscale backend's defaults apply. */
    fun isTailscaleCloud(url: String): Boolean = url.trimEnd('/').equals(TailscaleBackend.DEFAULT_BASE_URL, ignoreCase = true)
}
