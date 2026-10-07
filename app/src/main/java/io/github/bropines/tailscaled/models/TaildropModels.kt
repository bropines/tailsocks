package io.github.bropines.tailscaled.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One Taildrop transfer in the history file (files/sent_history.json — the name predates
 * received files and stays, for backups and older builds).
 *
 * Builds up to 4.7.3 wrote only [name], [peerName] (as "target") and [timestamp], and only
 * for a sent file that arrived; every other field is optional and defaults to what such an
 * entry meant, so an old file reads as a list of successful sends. A field nobody knew at
 * the time is null, never a made-up value: the details sheet shows only what exists.
 */
@Serializable
data class TaildropHistoryEntry(
    val name: String,
    /** The other device's name: where a sent file went, where a received one came from. */
    @SerialName("target") val peerName: String,
    /** When the transfer ended (or failed), epoch milliseconds. */
    val timestamp: Long,
    val direction: TaildropDirection = TaildropDirection.SENT,
    /** Whether the file arrived. Always true for a received file: only finished ones are logged. */
    val ok: Boolean = true,
    /** Bytes. */
    val size: Long? = null,
    val mime: String? = null,
    /** Hex SHA-256 of what was sent, hashed while the file was copied out for the daemon. */
    val sha256: String? = null,
    /** The other device's StableNodeID, Tailscale address and OS, as the daemon reported them. */
    val peerId: String? = null,
    val peerIp: String? = null,
    val peerOs: String? = null,
    /** How the daemon reached the device when the transfer ended, and through what. */
    val route: TaildropRoute? = null,
    /** The direct endpoint, the peer relay's address or the DERP region code. */
    val routeAddress: String? = null,
    /** The transfer itself, without the copy before it. */
    val durationMs: Long? = null,
    /** Why a send failed, as the bridge or the peer put it. */
    val error: String? = null,
    /** The peer's HTTP status when it answered a send with an error. */
    val httpStatus: Int? = null,
    /** 2 and up when the same file went to the same device again within a minute. */
    val attempt: Int? = null,
    /** The screen a send started from. */
    val source: TaildropSource? = null,
    /** Where a received file sits on disk, so saving or deleting it finds this entry. */
    val path: String? = null,
    /** The folder and name a received file was last saved as, and when. */
    val savedTo: String? = null,
    val savedAt: Long? = null,
    /**
     * The saved copy as a document of the default Taildrop folder, which the app keeps a
     * grant on: what Open and Show in folder use. Null for a copy saved anywhere else.
     */
    val savedUri: String? = null,
    /** Why the automatic save into the default folder failed; the file then stayed in the inbox. */
    val saveError: String? = null,
    /** When a saved file was hidden from the inbox. The file itself is left alone. */
    val dismissedAt: Long? = null,
    /** When a received file was deleted from the inbox. */
    val deletedAt: Long? = null
) {
    /** A send that did not arrive. */
    val failed: Boolean get() = direction == TaildropDirection.SENT && !ok
}

@Serializable
enum class TaildropDirection {
    @SerialName("sent") SENT,
    @SerialName("received") RECEIVED
}

@Serializable
enum class TaildropSource {
    @SerialName("files") FILES,
    @SerialName("share") SHARE,
    @SerialName("peers") PEERS
}

@Serializable
enum class TaildropRoute {
    @SerialName("direct") DIRECT,
    @SerialName("peer_relay") PEER_RELAY,
    @SerialName("derp") DERP
}

/** A file in the inbox, as the bridge's waiting-files JSON lists it. [ModTime] is epoch seconds, 0 when unknown. */
@Serializable
data class TaildropFile(
    val Name: String = "",
    val Size: Long = 0,
    val ModTime: Long = 0,
    val Path: String = ""
)
