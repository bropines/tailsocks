package io.github.bropines.tailscaled.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    @SerialName("ID") val id: Long = 0,
    @SerialName("LoginName") val loginName: String? = null,
    @SerialName("DisplayName") val displayName: String? = null,
    @SerialName("ProfilePicURL") val profilePicUrl: String? = null
)

@Serializable
data class StatusResponse(
    @SerialName("Self") val self: PeerData? = null,
    @SerialName("Peer") val peers: Map<String, PeerData>? = null,
    @SerialName("User") val users: Map<String, UserProfile>? = null,
    @SerialName("MagicDNSSuffix") val magicDnsSuffix: String? = null
)

@Serializable
data class PeerData(
    @SerialName("ID") val id: String? = null,
    @SerialName("UserID") val userID: Long? = null,
    @SerialName("HostName") val hostName: String? = null,
    @SerialName("DNSName") val dnsName: String? = null,
    @SerialName("OS") val os: String? = null,
    @SerialName("TailscaleIPs") val tailscaleIPs: List<String>? = null,
    @SerialName("AllowedIPs") val allowedIPs: List<String>? = null,
    @SerialName("Addrs") val addrs: List<String>? = null,
    @SerialName("CurAddr") val curAddr: String? = null,
    @SerialName("Online") val online: Boolean? = null,
    @SerialName("Active") val active: Boolean? = null,
    @SerialName("Relay") val relay: String? = null,
    @SerialName("PeerRelay") val peerRelay: String? = null,
    @SerialName("Created") val created: String? = null,
    @SerialName("LastWrite") val lastWrite: String? = null,
    @SerialName("LastSeen") val lastSeen: String? = null,
    @SerialName("LastHandshake") val lastHandshake: String? = null,
    @SerialName("KeyExpiry") val keyExpiry: String? = null,
    /** The node key ("nodekey:…"), which is what the Admin API also calls the device by
     *  (ApiDevice.nodeKey): the one field the two sides can be matched on without a name.
     *  There is deliberately no Version field: ipnstate.PeerStatus has none, and the control
     *  plane strips a peer's version from the Hostinfo it distributes, so the daemon cannot
     *  say what a peer runs — only the Admin API can (see PeerVersionSource). */
    @SerialName("PublicKey") val publicKey: String? = null,
    @SerialName("ExitNode") val exitNode: Boolean? = null,
    @SerialName("ExitNodeOption") val exitNodeOption: Boolean? = null,
    @SerialName("RxBytes") val rxBytes: Long? = null,
    @SerialName("TxBytes") val txBytes: Long? = null,
    @SerialName("InNetworkMap") val inNetworkMap: Boolean? = null,
    @SerialName("InMagicSock") val inMagicSock: Boolean? = null,
    @SerialName("InEngine") val inEngine: Boolean? = null,
    @SerialName("PeerAPIURL") val peerApiUrl: List<String>? = null,
    @SerialName("TaildropTarget") val taildropTarget: Int? = null,
    @SerialName("NoFileSharingReason") val noFileSharingReason: String? = null,
    @SerialName("Capabilities") val capabilities: List<String>? = null,
    @SerialName("ShareeNode") val shareeNode: Boolean? = null,
    @SerialName("Tags") val tags: List<String>? = null
) {
    fun getPrimaryIp(): String = tailscaleIPs?.firstOrNull() ?: "0.0.0.0"

    fun getDisplayName(): String {
        val dns = dnsName?.split(".")?.firstOrNull()
        if (!dns.isNullOrEmpty()) return dns
        if (!hostName.isNullOrEmpty()) return hostName
        return getPrimaryIp()
    }

    /**
     * The peer's properties as rows. [tailscaleVersion] is the one row this status did not
     * supply: it comes from the Admin API when the user has set that up (PeerVersionSource),
     * and when it is null the row is simply not there — not "Unknown", not a dash. The label
     * lives here with its siblings and the row keeps its old place in the order.
     */
    fun getDetailsList(tailscaleVersion: String? = null): List<PeerDetail> {
        fun formatTime(t: String?): String {
            if (t.isNullOrEmpty() || t.startsWith("0001-01-01")) return "Never"
            return t.replace("T", " ").substringBefore(".").removeSuffix("Z")
        }

        val displaySeen = if (lastSeen != null && lastSeen.contains("0001-01-01")) "Active now" else formatTime(lastSeen)

        val list = mutableListOf(
            PeerDetail(PeerDetailId.MACHINE_NAME, "Machine Name", getDisplayName()),
            PeerDetail(PeerDetailId.DNS_NAME, "DNS Name", dnsName ?: "N/A"),
            PeerDetail(PeerDetailId.OS, "OS", os ?: "Unknown"),
            PeerDetail(PeerDetailId.IPV4, "IPv4", getPrimaryIp()),
            PeerDetail(PeerDetailId.IPV6, "IPv6", tailscaleIPs?.getOrNull(1) ?: "N/A"),
            PeerDetail(PeerDetailId.ALLOWED_IPS, "Allowed IPs", allowedIPs?.joinToString(", ") ?: "N/A")
        )
        if (tailscaleVersion != null) {
            list.add(PeerDetail(PeerDetailId.VERSION, "Tailscale Version", tailscaleVersion))
        }
        list.add(PeerDetail(PeerDetailId.NODE_ID, "Node ID", id ?: "N/A"))

        // Relay is shown only while there is no direct endpoint. The daemon keeps Relay
        // populated with the home DERP region even when CurAddr is set and the traffic is
        // going direct — `tailscale status` hides it in exactly that case — and a "Relay
        // (DERP): fra" row under a "Direct" status chip is the sheet contradicting itself.
        if (curAddr.isNullOrEmpty()) {
            list.add(PeerDetail(PeerDetailId.RELAY, "Relay (DERP)", relay?.takeIf { it.isNotEmpty() } ?: "Direct"))
        }

        list.addAll(
            listOf(
                PeerDetail(PeerDetailId.CUR_ADDR, "Current Addr", curAddr?.takeIf { it.isNotEmpty() } ?: "N/A"),
                PeerDetail(PeerDetailId.KEY_EXPIRY, "Key Expiry", formatTime(keyExpiry)),
                PeerDetail(PeerDetailId.CREATED, "Created", formatTime(created)),
                PeerDetail(PeerDetailId.LAST_SEEN, "Last Seen", displaySeen),
                PeerDetail(PeerDetailId.LAST_WRITE, "Last Write", formatTime(lastWrite)),
                PeerDetail(PeerDetailId.LAST_HANDSHAKE, "Last Handshake", formatTime(lastHandshake)),
                PeerDetail(PeerDetailId.RX_BYTES, "Rx Bytes", rxBytes?.toString() ?: "0"),
                PeerDetail(PeerDetailId.TX_BYTES, "Tx Bytes", txBytes?.toString() ?: "0"),
                PeerDetail(PeerDetailId.IS_EXIT_NODE, "Is Exit Node", exitNode?.toString() ?: "false"),
                PeerDetail(PeerDetailId.EXIT_NODE_OPTION, "Exit Node Option", exitNodeOption?.toString() ?: "false"),
                PeerDetail(PeerDetailId.IN_NETWORK_MAP, "In Network Map", inNetworkMap?.toString() ?: "false"),
                PeerDetail(PeerDetailId.IN_MAGICSOCK, "In MagicSock (P2P)", inMagicSock?.toString() ?: "false"),
                PeerDetail(PeerDetailId.IN_WG_ENGINE, "In WG Engine", inEngine?.toString() ?: "false"),
                PeerDetail(PeerDetailId.CAPABILITIES, "Capabilities", capabilities?.size?.toString() ?: "0"),
                PeerDetail(PeerDetailId.TAILDROP_TARGET, "Taildrop Target", taildropTarget?.toString() ?: "Unknown")
            )
        )

        if (!tags.isNullOrEmpty()) {
            list.add(PeerDetail(PeerDetailId.TAGS, "Tags", tags.joinToString(", ")))
        }

        if (!noFileSharingReason.isNullOrEmpty()) {
            list.add(PeerDetail(PeerDetailId.NO_FILE_SHARING, "No File Sharing", noFileSharingReason))
        }
        if (!peerApiUrl.isNullOrEmpty()) {
            list.add(PeerDetail(PeerDetailId.PEER_API, "Peer API", peerApiUrl.first()))
        }

        return list
    }
}

/**
 * Which field of [PeerData] a detail row carries. The UI groups, formats and decides what is
 * worth copying by this, never by [PeerDetail.label]: the label is display text, and the day
 * it is translated every lookup keyed on it would miss silently — no compile error, every
 * row falling into the "other" group with its copy icon and its monospace face gone.
 */
enum class PeerDetailId {
    MACHINE_NAME, DNS_NAME, OS, IPV4, IPV6, ALLOWED_IPS, VERSION, NODE_ID,
    RELAY, CUR_ADDR, KEY_EXPIRY, CREATED, LAST_SEEN, LAST_WRITE, LAST_HANDSHAKE,
    RX_BYTES, TX_BYTES, IS_EXIT_NODE, EXIT_NODE_OPTION, IN_NETWORK_MAP, IN_MAGICSOCK,
    IN_WG_ENGINE, CAPABILITIES, TAILDROP_TARGET, TAGS, NO_FILE_SHARING, PEER_API
}

/** One row of [PeerData.getDetailsList]: what the row is, what it is called on screen, and
 *  what it says. */
data class PeerDetail(val id: PeerDetailId, val label: String, val value: String)
