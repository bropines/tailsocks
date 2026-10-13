package io.github.bropines.tailscaled.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Remote management as the bridge speaks it (appctr/remote*.go): the documents the managed
 * device's own screen reads, the answers the managing phone gets, and what each of them means
 * to a screen. Nothing here touches the bridge — core/RemoteManage.kt does — so all of it runs
 * in a plain JVM test.
 */

/** The class a pairing has on the managed device: the same person's device, or anyone else's. */
const val REMOTE_CLASS_OWNER = "owner"
const val REMOTE_CLASS_GUEST = "guest"

/** RemoteStateJSON: whether the endpoint is on and can be reached, and the code on show. */
@Serializable
data class RemoteState(
    /** A run has named the data directory; false only before the first start in this process. */
    val available: Boolean = false,
    val enabled: Boolean = false,
    val listening: Boolean = false,
    val port: Int = 0,
    /** Why the port could not be opened; empty while it is open. */
    val error: String = "",
    /** The last change to the pairings is not on disk yet and is being retried. */
    val unsaved: Boolean = false,
    /** [INBOUND_NETSTACK], [INBOUND_NONE] or [INBOUND_UNKNOWN]. */
    val inbound: String = INBOUND_UNKNOWN,
    val delegate: Boolean = false,
    val paired: Int = 0,
    val pairing: RemoteCode? = null,
    val lastPairing: RemoteOutcome? = null,
    val policy: RemotePolicy = RemotePolicy(),
    /** Changes whenever this, the paired list or the action log may have. */
    val rev: Long = 0,
) {
    companion object {
        /** Peers reach the endpoint: the daemon passes their connections on to it, in every mode. */
        const val INBOUND_NETSTACK = "netstack"
        /** They cannot: a daemon on a real TUN that does not keep the port — one older than the app. */
        const val INBOUND_NONE = "none"
        /** The daemon does not answer. */
        const val INBOUND_UNKNOWN = "unknown"
    }
}

/** A pairing code on show. */
@Serializable
data class RemoteCode(
    val code: String = "",
    /** The same code with this node's id and name, for the phone's scanner. */
    val qr: String = "",
    /** Seconds the code has left. */
    val left: Long = 0,
    val expires: Long = 0,
    val attemptsLeft: Int = 0,
    val lastWrong: RemoteAttempt? = null,
)

/** Who made a wrong guess, as the tailnet names them — nothing the caller claimed. */
@Serializable
data class RemoteAttempt(
    val name: String = "",
    val login: String = "",
    val nodeId: String = "",
    val time: Long = 0,
)

/** How the last code ended. */
@Serializable
data class RemoteOutcome(
    /** [PAIRED], [EXPIRED], [LOCKED] or [CANCELLED]. */
    val result: String = "",
    val time: Long = 0,
    val name: String = "",
    val login: String = "",
    val nodeId: String = "",
    @SerialName("class") val role: String = "",
) {
    /**
     * Whether the line this outcome gives is still true next to the [paired] list. "X is paired as
     * an owner" is about a pairing: once that pairing is gone — forgotten, revoked, run out — the
     * line goes with it. A code that expired or was locked says nothing about who is paired.
     */
    fun stillStands(paired: List<RemotePairedDevice>): Boolean =
        result != PAIRED || paired.any { it.nodeId == nodeId }

    companion object {
        const val PAIRED = "paired"
        const val EXPIRED = "expired"
        const val LOCKED = "locked"
        const val CANCELLED = "cancelled"
    }
}

/** The numbers of the rights model, as the bridge holds them. */
@Serializable
data class RemotePolicy(
    val allowGuests: Boolean = true,
    val codeSeconds: Long = 0,
    val codeAttempts: Int = 0,
    /** How long a guest's pairing lasts; 0 is until revoked. */
    val guestSeconds: Long = 0,
    val ownerSeconds: Long = 0,
    val maxPairings: Int = 0,
)

/** RemotePairedJSON: one device that may manage this one. */
@Serializable
data class RemotePairedDevice(
    val nodeId: String = "",
    val name: String = "",
    val login: String = "",
    @SerialName("class") val role: String = "",
    val created: Long = 0,
    /** Unix seconds; 0 until the device has made an authenticated request. */
    val lastUsed: Long = 0,
    /** Seconds the pairing has left; -1 for until revoked. */
    val left: Long = -1,
    val expires: Long = 0,
) {
    val owner: Boolean get() = role == REMOTE_CLASS_OWNER
}

/** RemoteActionsJSON: one line of what paired devices changed and what was refused. */
@Serializable
data class RemoteEvent(
    val time: Long = 0,
    val nodeId: String = "",
    val name: String = "",
    @SerialName("class") val role: String = "",
    val op: String = "",
    val detail: String = "",
    val ok: Boolean = false,
)

/** RemoteRevoke and RemoteRevokeAll. */
@Serializable
data class RemoteRevoked(
    /** The pairings are gone and that is on disk. */
    val ok: Boolean = false,
    val removed: Int = 0,
    /** "not_paired", or [NOT_SAVED]: refused from now on, but back with the app's next start until the write succeeds. */
    val error: String = "",
) {
    companion object {
        const val NOT_SAVED = "not_saved"
    }
}

/** RemoteStartPairing. */
@Serializable
data class RemoteCodeAnswer(val ok: Boolean = false, val error: String = "")

/** RemoteProbe: whether a peer is a TailSocks that offers remote management. */
@Serializable
data class RemoteProbe(
    val ok: Boolean = false,
    val error: String = "",
    val api: Int = 0,
    /** "tv", "phone", "tablet" or "". */
    val deviceClass: String = "",
    val name: String = "",
    /** The peer is showing a code right now. */
    val pairing: Boolean = false,
) {
    val tv: Boolean get() = ok && deviceClass == DEVICE_TV

    companion object {
        const val DEVICE_TV = "tv"
    }
}

/** RemoteParseQR: a pairing code read from the managed device's screen. */
@Serializable
data class RemoteQr(
    val ok: Boolean = false,
    val nodeId: String = "",
    val code: String = "",
    val name: String = "",
)

/** RemotePair. */
@Serializable
data class RemotePairAnswer(
    val ok: Boolean = false,
    val error: String = "",
    val attemptsLeft: Int? = null,
    val status: Int = 0,
    val token: String = "",
    @SerialName("class") val role: String = "",
    /** The pairing's lifetime in seconds; -1 for until revoked. The bridge leaves a zero out. */
    val left: Long = 0,
    val name: String = "",
) {
    companion object {
        /** This side's own word: the device paired, and the token could not be kept here. */
        const val NOT_STORED = "not_stored"
    }
}

/** RemoteGet and RemotePost: the peer's HTTP status and body, or status 0 and why nothing came back. */
@Serializable
data class RemoteAnswer(
    val status: Int = 0,
    val body: String = "",
    val error: String = "",
    val message: String = "",
)

/** The body of every refusal. */
@Serializable
data class RemoteErrorBody(val error: String = "", val message: String = "")

/** GET /v1/status. */
@Serializable
data class RemoteStatus(
    val running: Boolean = false,
    val backendState: String = "",
    /** The daemon's version, and the app's. */
    val version: String = "",
    val appVersion: String = "",
    val deviceClass: String = "",
    val name: String = "",
    val dnsName: String = "",
    val nodeId: String = "",
    val exitNode: RemoteExitNode? = null,
    val health: List<String> = emptyList(),
    val since: Long = 0,
    val you: RemoteYou = RemoteYou(),
)

/** What the caller is to the managed device. */
@Serializable
data class RemoteYou(
    @SerialName("class") val role: String = "",
    /** Seconds the pairing has left; -1 for until revoked. */
    val left: Long = -1,
) {
    val owner: Boolean get() = role == REMOTE_CLASS_OWNER
}

@Serializable
data class RemoteExitNode(
    val id: String = "",
    val name: String = "",
    val online: Boolean = false,
    val selected: Boolean = false,
    val country: String = "",
    val city: String = "",
)

@Serializable
data class RemoteExitNodes(val nodes: List<RemoteExitNode> = emptyList())

/** One entry of GET /v1/settings. */
@Serializable
data class RemoteSetting(
    val key: String = "",
    /** "bool" ("true" or "false") or "text". */
    val kind: String = "",
    /** The setting can be changed, and by this caller. */
    val writable: Boolean = false,
    val value: String = "",
    /** Set, and [value] empty, when the app could not say. */
    val error: String = "",
) {
    val on: Boolean get() = value == "true"
}

@Serializable
data class RemoteSettings(val settings: List<RemoteSetting> = emptyList())

/** One received Taildrop file: a name, never a path and never contents. */
@Serializable
data class RemoteFile(val name: String = "", val size: Long = 0, val modTime: Long = 0)

@Serializable
data class RemoteFiles(val files: List<RemoteFile> = emptyList())

/** The paths of the managed device's API. */
object RemotePaths {
    const val STATUS = "/v1/status"
    const val EXIT_NODES = "/v1/exit-nodes"
    const val EXIT_NODE = "/v1/exit-node"
    const val SETTINGS = "/v1/settings"
    const val ACTIONS = "/v1/actions"
    const val FILES = "/v1/files"
    const val FILES_DELETE = "/v1/files/delete"
    const val UNPAIR = "/v1/unpair"
}

/** The three switches a paired owner may turn, and the one action. */
object RemoteKeys {
    const val ACCEPT_DNS = "accept_dns"
    const val ACCEPT_ROUTES = "accept_routes"
    const val EXIT_NODE_LAN = "exit_node_lan_access"
    const val DEVICE_CLASS = "device_class"
    const val APP_VERSION = "app_version"
    const val ACTION_RECONNECT = "reconnect"

    val SWITCHES = listOf(ACCEPT_DNS, ACCEPT_ROUTES, EXIT_NODE_LAN)
}

/**
 * The bridge's documents, read leniently: a document that is not what it should be is the
 * empty one, never an exception — these are polled, and an older bridge may lack a field.
 */
object RemoteJson {
    fun state(json: String): RemoteState = decode(json) ?: RemoteState()
    fun paired(json: String): List<RemotePairedDevice> = decode(json) ?: emptyList()
    fun actions(json: String): List<RemoteEvent> = decode(json) ?: emptyList()
    fun revoked(json: String): RemoteRevoked = decode(json) ?: RemoteRevoked(error = "bad_answer")
    fun code(json: String): RemoteCodeAnswer = decode(json) ?: RemoteCodeAnswer(error = "bad_answer")
    fun probe(json: String): RemoteProbe = decode(json) ?: RemoteProbe(error = "bad_answer")
    fun pair(json: String): RemotePairAnswer = decode(json) ?: RemotePairAnswer(error = "bad_answer")
    fun answer(json: String): RemoteAnswer = decode(json) ?: RemoteAnswer(error = "bad_answer")

    /** A scanned pairing code; null for anything that is not one. */
    fun qr(json: String): RemoteQr? = decode<RemoteQr>(json)?.takeIf { it.ok && it.nodeId.isNotEmpty() && isCode(it.code) }

    fun exitNodeBody(id: String): String = buildJsonObject { put("id", id) }.toString()
    fun settingBody(key: String, on: Boolean): String = buildJsonObject { put("key", key); put("value", on.toString()) }.toString()
    fun actionBody(action: String): String = buildJsonObject { put("action", action) }.toString()
    fun fileBody(name: String): String = buildJsonObject { put("name", name) }.toString()

    /** Six digits: what a pairing code looks like. Anything else is never sent. */
    fun isCode(text: String): Boolean = text.length == CODE_LENGTH && text.all { it in '0'..'9' }

    /** What a StableNodeID may look like — the bridge's own rule (remoteIsNodeID). */
    fun isNodeId(text: String): Boolean =
        text.isNotEmpty() && text.length <= 64 && text.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '-' || it == '_' }

    const val CODE_LENGTH = 6

    internal inline fun <reified T> decode(json: String): T? =
        if (json.isBlank()) null else runCatching { AppJson.decodeFromString<T>(json) }.getOrNull()
}

/** Why a request to a managed device brought nothing back, as far as a screen tells them apart. */
enum class RemoteFailure {
    /** This phone's own TailSocks is not connected: nothing was sent. */
    NOT_CONNECTED,
    /** The device is not in this tailnet any more. */
    NO_PEER,
    /** No answer: the device is off, or switched management off, or runs a mode nothing can reach. */
    UNREACHABLE,
    /** The token was refused: the pairing was revoked or replaced. */
    UNAUTHORIZED,
    /** The token was refused and the pairing was a guest's whose time is up. */
    EXPIRED,
    /** Not for this class of pairing. */
    FORBIDDEN,
    NOT_FOUND,
    /** The managed device's app said no; its words come along. */
    REFUSED,
    /** Too many requests. */
    SLOW_DOWN,
    /** The managed device's app did not take the request: nothing was changed. */
    UNAVAILABLE,
    /** The app there has not answered: the change may still be applied. */
    PENDING,
    /** Anything else. */
    FAILED,
}

/** A request's outcome: the decoded body, or why there is none. */
sealed interface RemoteResult<out T> {
    data class Ok<T>(val value: T) : RemoteResult<T>

    /** [message] is the managed device's own sentence, when it sent one. */
    data class Failed(val failure: RemoteFailure, val message: String = "") : RemoteResult<Nothing>
}

/** What an answer that is not a 200 means. */
fun RemoteAnswer.failure(): RemoteResult.Failed {
    val told = RemoteJson.decode<RemoteErrorBody>(body)
    val kind = when (status) {
        0 -> when (error) {
            "not_connected" -> RemoteFailure.NOT_CONNECTED
            "no_peer" -> RemoteFailure.NO_PEER
            else -> RemoteFailure.UNREACHABLE
        }
        401 -> RemoteFailure.UNAUTHORIZED
        403 -> RemoteFailure.FORBIDDEN
        404 -> RemoteFailure.NOT_FOUND
        409 -> RemoteFailure.REFUSED
        429 -> RemoteFailure.SLOW_DOWN
        503 -> RemoteFailure.UNAVAILABLE
        504 -> RemoteFailure.PENDING
        else -> RemoteFailure.FAILED
    }
    // Only a refusal's sentence is for a person; the rest are the API's own words.
    return RemoteResult.Failed(kind, if (kind == RemoteFailure.REFUSED) told?.message.orEmpty() else "")
}

/** Why a pairing attempt did not pair. */
enum class RemotePairFailure {
    /** Not the code on the screen; attempts are left. */
    WRONG,
    /** That was the last guess the code allowed: it is dead. */
    LOCKED,
    /** No code is on show. */
    CLOSED,
    /** The code was already used. */
    USED,
    /** The managed device does not let this one pair at all. */
    NOT_ALLOWED,
    /** It already holds as many pairings as it keeps. */
    FULL,
    SLOW_DOWN,
    NOT_CONNECTED,
    NO_PEER,
    UNREACHABLE,
    /** It paired, and the token could not be kept on this device: the pairing was given back. */
    NOT_STORED,
    /** What was scanned is another device's code. */
    OTHER_DEVICE,
    /** What was scanned is not a pairing code. */
    NOT_A_CODE,
    FAILED,
}

fun RemotePairAnswer.failure(): RemotePairFailure = when (error) {
    RemotePairAnswer.NOT_STORED -> RemotePairFailure.NOT_STORED
    "wrong" -> RemotePairFailure.WRONG
    "locked" -> RemotePairFailure.LOCKED
    "closed" -> RemotePairFailure.CLOSED
    "used" -> RemotePairFailure.USED
    "not_allowed" -> RemotePairFailure.NOT_ALLOWED
    "full" -> RemotePairFailure.FULL
    "slow_down" -> RemotePairFailure.SLOW_DOWN
    "not_connected" -> RemotePairFailure.NOT_CONNECTED
    "no_peer" -> RemotePairFailure.NO_PEER
    "unreachable" -> RemotePairFailure.UNREACHABLE
    else -> RemotePairFailure.FAILED
}

/** The one thing offered next to a problem. */
enum class RemoteProblemAction { RETRY, PAIR_AGAIN, REFRESH, DISMISS, CLOSE }

/**
 * What went wrong while managing a device, as the managing screen says it: one sentence and
 * one action each.
 */
enum class RemoteProblem(val action: RemoteProblemAction) {
    NOT_CONNECTED(RemoteProblemAction.RETRY),
    NO_PEER(RemoteProblemAction.CLOSE),
    OFFLINE(RemoteProblemAction.RETRY),
    /** In the tailnet and not answering on the port: management is off there, or the mode keeps peers out. */
    NOT_ACCEPTING(RemoteProblemAction.RETRY),
    REVOKED(RemoteProblemAction.PAIR_AGAIN),
    EXPIRED(RemoteProblemAction.PAIR_AGAIN),
    OWNER_ONLY(RemoteProblemAction.DISMISS),
    GONE(RemoteProblemAction.REFRESH),
    REFUSED(RemoteProblemAction.DISMISS),
    SLOW_DOWN(RemoteProblemAction.RETRY),
    BUSY(RemoteProblemAction.RETRY),
    PENDING(RemoteProblemAction.REFRESH),
    FAILED(RemoteProblemAction.RETRY);

    /** The pairing is gone or the device is: nothing on the screen can be used any more. */
    val final: Boolean get() = this == REVOKED || this == EXPIRED || this == NO_PEER

    /** The device cannot be asked: what the screen shows of it is what it said before. */
    val stale: Boolean get() = this == NOT_CONNECTED || this == OFFLINE || this == NOT_ACCEPTING

    companion object {
        /**
         * [peerOnline] is what this phone's own network map says of the device. No answer from
         * a device that is online means the port is closed to us — nobody is merely away.
         */
        fun of(failure: RemoteFailure, peerOnline: Boolean): RemoteProblem = when (failure) {
            RemoteFailure.NOT_CONNECTED -> NOT_CONNECTED
            RemoteFailure.NO_PEER -> NO_PEER
            RemoteFailure.UNREACHABLE -> if (peerOnline) NOT_ACCEPTING else OFFLINE
            RemoteFailure.UNAUTHORIZED -> REVOKED
            RemoteFailure.EXPIRED -> EXPIRED
            RemoteFailure.FORBIDDEN -> OWNER_ONLY
            RemoteFailure.NOT_FOUND -> GONE
            RemoteFailure.REFUSED -> REFUSED
            RemoteFailure.SLOW_DOWN -> SLOW_DOWN
            RemoteFailure.UNAVAILABLE -> BUSY
            RemoteFailure.PENDING -> PENDING
            RemoteFailure.FAILED -> FAILED
        }
    }
}

/** Whether a paired device can reach this one right now, as the managed device's screen says it. */
enum class RemoteReach {
    /** The switch is off, and so is the endpoint. */
    OFF,
    /** The switch is off and the running service has not closed the endpoint yet. */
    CLOSING,
    /** The switch is on and the running service has not opened the endpoint yet. */
    APPLYING,
    READY,
    /** The daemon is not running or does not answer. */
    NOT_RUNNING,
    /** The running daemon does not pass the port on to the app; a restart of it does. */
    NOT_FORWARDED,
    /** The port could not be opened: something else holds it. */
    PORT_BUSY;

    /** A code shown now could be used. */
    val canPair: Boolean get() = this == READY

    companion object {
        fun of(switchOn: Boolean, state: RemoteState): RemoteReach = when {
            // Off is said only once the bridge agrees: the switch is a file, the endpoint a socket.
            !switchOn -> if (state.enabled || state.listening) CLOSING else OFF
            !state.available || state.inbound == RemoteState.INBOUND_UNKNOWN -> NOT_RUNNING
            state.inbound == RemoteState.INBOUND_NONE -> NOT_FORWARDED
            state.listening -> READY
            state.error.isNotEmpty() -> PORT_BUSY
            else -> APPLYING
        }
    }
}

/** The unit [remoteDuration] counts in. */
enum class RemoteDurationUnit { DAYS, HOURS, MINUTES }

/** [seconds] rounded up to one unit — 21 hours, 35 minutes — never zero while time is left. */
fun remoteDuration(seconds: Long): Pair<Long, RemoteDurationUnit> = when {
    seconds >= 2 * 86_400 -> (seconds + 86_399) / 86_400 to RemoteDurationUnit.DAYS
    seconds >= 2 * 3_600 -> (seconds + 3_599) / 3_600 to RemoteDurationUnit.HOURS
    else -> maxOf(1L, (seconds + 59) / 60) to RemoteDurationUnit.MINUTES
}

/** A countdown, m:ss. */
fun remoteCountdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** A pairing code set in two groups of three, the way it is read out. */
fun remoteCodeGroups(code: String): String =
    if (code.length == RemoteJson.CODE_LENGTH) code.substring(0, 3) + " " + code.substring(3) else code
