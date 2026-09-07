package io.github.bropines.tailscaled.admin

import android.content.Context
import io.github.bropines.tailscaled.core.AccountManager
import io.github.bropines.tailscaled.core.GlobalSettings

/**
 * What the Admin Console keeps about one tailnet's access to the Tailscale Admin API, and the
 * one place that knows where it keeps it. The console reads these into its own per-field
 * state (it edits them one at a time); anything else that wants an Admin API client — the
 * peer sheet's version lookup — reads them through [read] and builds the client through
 * [newClient], so the two cannot drift apart on which preference file, which key or which
 * proxy the call goes out through.
 *
 * Storage: `admin_api_keys` is an app-wide preference file keyed by tailnet name — the token
 * itself under the bare tailnet name, everything else under `<tailnet>_<field>` — and the
 * tailnet name the active profile last saw is `last_known_tailnet` in that profile's own
 * `appctr_<accountId>` file.
 */
data class AdminApiSettings(
    val tailnet: String,
    /** "TOKEN" or "OAUTH": which pair of fields below is the one the console was set up with. */
    val authType: String,
    val token: String,
    val clientId: String,
    val clientSecret: String,
    val proxyMode: String,
    val proxyHost: String,
    val proxyPort: Int,
    val proxyUser: String,
    val proxyPass: String
) {
    /** Whether the console would open on its dashboard rather than on its setup screen. The
     *  fields the other auth type left behind do not count: a token that was cleared while
     *  stale OAuth fields stayed in the file is "not configured", and the console says so. */
    val hasCredentials: Boolean
        get() = if (authType == AUTH_TYPE_TOKEN) token.isNotBlank()
        else clientId.isNotBlank() && clientSecret.isNotBlank()

    /** A client set up exactly the way the console's dashboard sets up its own. */
    fun newClient(context: Context): TailscaleApiClient = newAdminApiClient(
        context, tailnet, token, clientId, clientSecret,
        proxyMode, proxyHost, proxyPort, proxyUser, proxyPass
    )

    companion object {
        const val PREFS_NAME = "admin_api_keys"
        const val AUTH_TYPE_TOKEN = "TOKEN"
        const val DEFAULT_PROXY_MODE = "CONTROL_PLANE"

        /** The tailnet name the active profile last saw — its MagicDNS suffix, written by the
         *  console when it opens or when the user typed one in. Blank when neither happened. */
        fun lastKnownTailnet(context: Context): String {
            val account = AccountManager.getActiveAccount(context)
            return context.getSharedPreferences("appctr_${account.id}", Context.MODE_PRIVATE)
                .getString("last_known_tailnet", "") ?: ""
        }

        /** The stored settings for [tailnet], with the console's defaults where nothing is stored. */
        fun read(context: Context, tailnet: String): AdminApiSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            fun str(key: String, default: String = "") = prefs.getString(key, default) ?: default
            return AdminApiSettings(
                tailnet = tailnet,
                authType = str("${tailnet}_auth_type", AUTH_TYPE_TOKEN),
                token = str(tailnet),
                clientId = str("${tailnet}_oauth_client_id"),
                clientSecret = str("${tailnet}_oauth_client_secret"),
                proxyMode = str("${tailnet}_proxy_mode", DEFAULT_PROXY_MODE),
                proxyHost = str("${tailnet}_proxy_host"),
                proxyPort = prefs.getInt("${tailnet}_proxy_port", 0),
                proxyUser = str("${tailnet}_proxy_user"),
                proxyPass = str("${tailnet}_proxy_pass")
            )
        }
    }
}

/**
 * The Admin API client as the console's dashboard builds it: the per-tailnet credentials and
 * proxy choice from the caller, the local SOCKS5 endpoint and the control-plane proxy — the
 * two things a "LOCAL_SOCKS5" or "CONTROL_PLANE" proxy mode resolves to — from the app-wide
 * settings. Both auth pairs are handed over whatever the auth type; the client itself picks
 * OAuth whenever it has a client secret and the token otherwise.
 */
fun newAdminApiClient(
    context: Context,
    tailnet: String,
    token: String,
    clientId: String,
    clientSecret: String,
    proxyMode: String,
    proxyHost: String,
    proxyPort: Int,
    proxyUser: String,
    proxyPass: String
): TailscaleApiClient = TailscaleApiClient(
    token = token,
    tailnetName = tailnet,
    proxyMode = proxyMode,
    proxyHost = proxyHost,
    proxyPort = proxyPort,
    proxyUser = proxyUser,
    proxyPass = proxyPass,
    localSocksAddr = GlobalSettings.getString(context, "socks5", "127.0.0.1:48115"),
    localSocksUser = GlobalSettings.getString(context, "socks5_user", ""),
    localSocksPass = GlobalSettings.getString(context, "socks5_pass", ""),
    clientId = clientId,
    clientSecret = clientSecret,
    controlProxyUrl = GlobalSettings.getControlProxyUrl(context)
)
