package io.github.bropines.tailscaled.admin.api.headscale

import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminBackend
import io.github.bropines.tailscaled.admin.api.AdminCredential
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.HttpTransport
import io.github.bropines.tailscaled.admin.api.Listing
import kotlinx.coroutines.flow.StateFlow

/** Where a Headscale server keeps its policy: `policy.mode` in its config. */
enum class PolicyMode {
    /** Set through the API. */
    DATABASE,
    /** A file on the server: readable here, changed only by editing that file. */
    FILE,
    /** Not learned yet; a v1 server tells only when a write is refused. */
    UNKNOWN,
}

/** The server behind a Headscale profile, as the console last learned it. */
data class HeadscaleServer(
    val kind: BackendKind,
    /** Null for a release older than 0.27, which has no `GET /version`. */
    val version: HeadscaleVersion? = null,
    val policyMode: PolicyMode = PolicyMode.UNKNOWN,
    /** How the console's own API key appears in the API key list. */
    val ownApiKeyPrefix: String? = null,
    /** The default node key lifetime (`node.expiry`), when the server reports it; 0 is none. */
    val nodeKeyDays: Int? = null,
    /** A pending registration can be refused (0.29+); before, it can only be left to lapse. */
    val canRejectRegistration: Boolean = false,
    /** Registration takes 0.29's `hskey-authreq-…` ids; older servers a 24-character one. */
    val authIdPrefixed: Boolean = true,
)

/**
 * What only a Headscale server has, beside [AdminBackend]: registering a node from the link
 * its `tailscale up` prints, local users made and renamed here, the server's own API keys, a
 * policy check. Both Headscale backends implement it; the console's Headscale tab uses it.
 */
interface HeadscaleAdmin {
    val server: StateFlow<HeadscaleServer>

    /** Asks the server again: version, policy mode, the console's own key. */
    suspend fun refreshServer(): HeadscaleServer

    /** Registers the node waiting under [authId] (from its /register/<id> link) to the user named [userName]. */
    suspend fun registerNode(authId: String, userName: String): ApiDevice

    /** Refuses the registration waiting under [authId]; [HeadscaleServer.canRejectRegistration] only. */
    suspend fun rejectRegistration(authId: String)

    suspend fun createUser(name: String, displayName: String?, email: String?): ApiUser

    /** Renames the user: their login name, and with it every `name@` the policy uses. */
    suspend fun renameUser(userId: String, newName: String): ApiUser

    /** The server's API keys; secrets are never listed, only prefixes. */
    suspend fun listApiKeys(): Listing<HsApiKey>

    /** A new all-access API key valid until [expiresAtMs]: its secret, shown once. */
    suspend fun createApiKey(expiresAtMs: Long): String

    /** Expires the API key listed as [listedPrefix]; it stops working at once. */
    suspend fun expireApiKey(listedPrefix: String)

    /** Checks [text] as a policy against the server's users and nodes without applying it; throws when it is refused. */
    suspend fun checkPolicy(text: String)
}

/** The Headscale backends, built from a profile's settings; one place for AdminProfiles to call. */
object HeadscaleBackends {
    fun create(
        kind: BackendKind,
        credential: AdminCredential,
        transport: HttpTransport,
        baseUrl: String,
        log: (String) -> Unit,
    ): AdminBackend = when (kind) {
        BackendKind.HEADSCALE_V2 -> HeadscaleV2Backend(apiKeyOf(credential), transport, baseUrl, log = log)
        else -> HeadscaleV1Backend(apiKeyOf(credential), transport, baseUrl, log = log)
    }

    /** A Headscale API key whatever slot it was stored in (a profile switched backend keeps its secret). */
    fun apiKeyOf(credential: AdminCredential): String = when (credential) {
        is AdminCredential.HeadscaleKey -> credential.key
        is AdminCredential.ApiToken -> credential.token
        is AdminCredential.OAuthClient -> throw AdminApiException.Unauthorized(null, "Headscale takes an API key")
    }.trim()
}
