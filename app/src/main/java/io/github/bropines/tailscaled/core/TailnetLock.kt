package io.github.bropines.tailscaled.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tailnet Lock as this node sees it: GET /localapi/v0/tka/status (ipnstate.TailnetLockStatus),
 * read the way `tailscale lock status` reads it. Keys arrive as the daemon marshals them —
 * "nlpub:<hex>" for tailnet-lock keys, "nodekey:<hex>" for node keys — and are shown as the
 * CLI shows them, "tlpub:<hex>", which is also what the admin console and `tailscale lock add`
 * take.
 */
@Serializable
data class TailnetLockStatus(
    @SerialName("Enabled") val enabled: Boolean = false,
    /** This node's tailnet-lock key; all zeroes before the node has logged in. */
    @SerialName("PublicKey") val publicKey: String? = null,
    /** This node's node key; absent while it waits for a login. */
    @SerialName("NodeKey") val nodeKey: String? = null,
    @SerialName("NodeKeySigned") val nodeKeySigned: Boolean = false,
    @SerialName("TrustedKeys") val trustedKeys: List<TailnetLockKey>? = null,
    /** Peers dropped from the netmap because their node key carries no valid signature. */
    @SerialName("FilteredPeers") val filteredPeers: List<TailnetLockPeer>? = null,
) {
    /** This node has a tailnet-lock key at all (it has logged in). */
    val hasKey: Boolean get() = !TailnetLock.isZeroKey(publicKey)

    /** This node's key is one of the trusted signing keys: it can sign other nodes. */
    val isTrustedSigner: Boolean
        get() = enabled && hasKey && trustedKeys.orEmpty().any { TailnetLock.sameKey(it.key, publicKey) }

    /** Lock is on and this node is not signed: it cannot reach anyone until a signer signs it. */
    val isLockedOut: Boolean get() = enabled && nodeKey != null && hasKey && !nodeKeySigned

    val lockedOutPeers: List<TailnetLockPeer> get() = if (enabled) filteredPeers.orEmpty() else emptyList()
}

@Serializable
data class TailnetLockKey(
    @SerialName("Key") val key: String = "",
    @SerialName("Votes") val votes: Int = 0,
)

@Serializable
data class TailnetLockPeer(
    /** The node's MagicDNS name. */
    @SerialName("Name") val name: String = "",
    @SerialName("StableID") val stableId: String = "",
    @SerialName("TailscaleIPs") val tailscaleIps: List<String>? = null,
    @SerialName("NodeKey") val nodeKey: String = "",
) {
    /** The first label of the MagicDNS name, as the peer list shows nodes. */
    val shortName: String get() = name.trimEnd('.').substringBefore('.').ifEmpty { stableId }
}

object TailnetLock {
    const val STATUS_PATH = "/localapi/v0/tka/status"
    const val SIGN_PATH = "/localapi/v0/tka/sign"

    private const val NL_PREFIX = "nlpub:"
    private const val CLI_PREFIX = "tlpub:"

    /** The status from the daemon's answer; null for an error or anything unreadable. */
    fun parse(json: String): TailnetLockStatus? =
        if (json.isBlank() || json.startsWith("Error")) null
        else runCatching { AppJson.decodeFromString<TailnetLockStatus>(json) }.getOrNull()

    private fun hex(key: String?): String = key.orEmpty().substringAfter(':').lowercase()

    fun isZeroKey(key: String?): Boolean = hex(key).let { it.isEmpty() || it.all { c -> c == '0' } }

    fun sameKey(a: String?, b: String?): Boolean = !isZeroKey(a) && hex(a) == hex(b)

    /** A tailnet-lock key the way the CLI and the admin console write it: "tlpub:<hex>". */
    fun cliKey(key: String?): String =
        if (key == null) "" else if (key.startsWith(NL_PREFIX)) CLI_PREFIX + key.removePrefix(NL_PREFIX) else key

    /** A long key cut for a row: its prefix, the first and the last few digits. */
    fun shortKey(key: String): String {
        val prefix = key.substringBefore(':', "")
        val body = key.substringAfter(':')
        if (body.length <= 12) return key
        return (if (prefix.isEmpty()) "" else "$prefix:") + body.take(6) + "…" + body.takeLast(4)
    }

    /** What a signing node runs to let this one in, as `tailscale lock status` prints it. */
    fun signCommand(status: TailnetLockStatus): String? =
        status.nodeKey?.takeIf { status.hasKey }?.let { "tailscale lock sign $it ${cliKey(status.publicKey)}" }

    /**
     * The body of POST /localapi/v0/tka/sign for [nodeKey], exactly what
     * `tailscale lock sign <node-key>` sends: with no rotation key given the CLI passes the
     * zero key's 32 bytes as RotationPublic, so this does too.
     */
    fun signRequest(nodeKey: String): String = buildJsonObject {
        put("NodeKey", nodeKey)
        put("RotationPublic", ZERO_ROTATION_KEY)
    }.toString()

    /** 32 zero bytes, base64 — how Go's encoding/json writes the zero NLPublic's verifier. */
    private const val ZERO_ROTATION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
}
