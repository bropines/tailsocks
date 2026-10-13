package io.github.bropines.tailscaled.core

import kotlinx.serialization.Serializable
import java.net.URI

/*
 * Signing a television in with a key a phone sends it (appctr/setup.go): what
 * the bridge reports, read into what each of the two screens shows, and the
 * checks the phone makes before anything is sent. Nothing here touches Android
 * or the bridge, so all of it is tested on the JVM (KeySetupTest).
 */

// ─── The television ─────────────────────────────────────────────────────────

/** The JSON of the bridge's `setupReceive…` calls: this device's session. */
@Serializable
data class SetupReceiveStatus(
    val state: String = "idle",
    val reason: String = "",
    val error: String = "",
    val id: String = "",
    val payload: String = "",
    val addresses: List<String> = emptyList(),
    val port: Int = 0,
    val failedAttempts: Int = 0,
    val expiresIn: Int = 0,
    val from: String = "",
    val senderName: String = "",
    val code: String = "",
    val keyPrefix: String = "",
    val loginServer: String = "",
) {
    /** Over, or never begun: a new session may be opened in its place. */
    val vacant: Boolean get() = state == "idle" || state == "released"

    companion object {
        /** An answer that cannot be read is a session that failed, for a reason no screen has words for. */
        fun parse(json: String?): SetupReceiveStatus =
            runCatching { AppJson.decodeFromString<SetupReceiveStatus>(json.orEmpty()) }
                .getOrDefault(SetupReceiveStatus(state = "failed", reason = "unreadable"))
    }
}

/** Where a key that was confirmed stands on its way to signing the device in (KeyHandover). */
enum class HandoverPhase { IDLE, SIGNING_IN, SIGNED_IN, FAILED }

/** The kind of key a bundle holds, as far as its first characters say. */
enum class KeyKind(val prefix: String) {
    TAILSCALE_AUTH("tskey-auth"),
    TAILSCALE_CLIENT("tskey-client"),
    HEADSCALE_AUTH("hskey-auth"),
    /** No prefix this app knows: an older Headscale key, or not a key at all. */
    OTHER("");

    companion object {
        /** From the prefix the bridge reports for a held bundle; it knows the same three. */
        fun ofPrefix(prefix: String): KeyKind = entries.firstOrNull { it != OTHER && it.prefix == prefix } ?: OTHER

        fun of(key: String): KeyKind = entries.firstOrNull { it != OTHER && key.startsWith(it.prefix + "-") } ?: OTHER
    }
}

/** Why the television has no code on screen and no key to ask about. */
enum class TvKeyStop {
    /** The device is on no local network. */
    NO_NETWORK,
    /** None of its addresses could be listened on. */
    CANNOT_LISTEN,
    /** Too many requests that were not a key. */
    TOO_MANY_ATTEMPTS,
    /** The addresses in the code are no longer the device's. */
    NETWORK_CHANGED,
    /** The daemon was stopped or restarted under the session. */
    SERVICE_STOPPED,
    /** Nobody sent a key while the code was good. */
    CODE_EXPIRED,
    /** A key came and nobody answered in time. */
    NOT_ANSWERED,
    REJECTED,
    /** Confirmed, and gone before the app took it. */
    KEY_GONE,
    /** The key was used and the device did not sign in. */
    NOT_SIGNED_IN,
    UNKNOWN,
}

/** What the television's screen shows. */
sealed interface TvKeyStep {
    /** The session is being opened. */
    data object Opening : TvKeyStep

    /** The code is up: [text] is drawn, [session] and [addresses] are what the phone names this device by. */
    data class Code(
        val text: String,
        val session: String,
        val addresses: List<String>,
        val secondsLeft: Int,
        val refused: Int,
    ) : TvKeyStep

    /** A bundle waits for the person: who sent it, the code both screens show, and what is in it — never the key. */
    data class Offer(
        val sender: String,
        val address: String,
        val checkCode: String,
        val keyKind: KeyKind,
        val server: String,
        val secondsLeft: Int,
    ) : TvKeyStep

    data object SigningIn : TvKeyStep
    data object SignedIn : TvKeyStep
    data class Stopped(val why: TvKeyStop) : TvKeyStep
}

/**
 * The television's step from the bridge's [status] — null before the first
 * answer — and from where a confirmed key stands. A key on its way wins: the
 * session that carried it is over and says nothing more.
 */
fun tvKeyStep(status: SetupReceiveStatus?, handover: HandoverPhase): TvKeyStep = when (handover) {
    HandoverPhase.SIGNING_IN -> TvKeyStep.SigningIn
    HandoverPhase.SIGNED_IN -> TvKeyStep.SignedIn
    HandoverPhase.FAILED -> TvKeyStep.Stopped(TvKeyStop.NOT_SIGNED_IN)
    HandoverPhase.IDLE -> when (status?.state) {
        null, "idle", "released" -> TvKeyStep.Opening
        "waiting" ->
            if (status.payload.isEmpty()) TvKeyStep.Stopped(TvKeyStop.UNKNOWN)
            else TvKeyStep.Code(status.payload, status.id, status.addresses, status.expiresIn, status.failedAttempts)
        "received" -> TvKeyStep.Offer(
            sender = status.senderName,
            address = status.from,
            checkCode = status.code,
            keyKind = KeyKind.ofPrefix(status.keyPrefix),
            server = status.loginServer,
            secondsLeft = status.expiresIn,
        )
        // Said yes: the key is being taken and written, in the same breath.
        "confirmed" -> TvKeyStep.SigningIn
        "rejected" -> TvKeyStep.Stopped(TvKeyStop.REJECTED)
        // The bundle's sender stays in the status after the session ended.
        "expired" -> TvKeyStep.Stopped(if (status.from.isEmpty()) TvKeyStop.CODE_EXPIRED else TvKeyStop.NOT_ANSWERED)
        "failed" -> TvKeyStep.Stopped(
            when (status.reason) {
                "no_lan_address" -> TvKeyStop.NO_NETWORK
                "listen_failed" -> TvKeyStop.CANNOT_LISTEN
                "too_many_attempts" -> TvKeyStop.TOO_MANY_ATTEMPTS
                "network_changed" -> TvKeyStop.NETWORK_CHANGED
                "daemon_stopped" -> TvKeyStop.SERVICE_STOPPED
                "not_taken" -> TvKeyStop.KEY_GONE
                else -> TvKeyStop.UNKNOWN
            }
        )
        else -> TvKeyStep.Stopped(TvKeyStop.UNKNOWN)
    }
}

/**
 * What the watcher of a handed-over key concludes from one look at the daemon:
 * whether the device is signed in, and whether it has been seen not to be.
 *
 * The second is the guard. The key is written and the service restarted; a
 * look that comes before the old daemon is down must not take its state for
 * the new one's — that would clear the key before the restart has read it.
 * So "signed in" counts only after the daemon was seen down or signed out.
 * `NeedsMachineAuth` counts too: the node is registered, the key has done its
 * work, and what is left is an admin's approval.
 */
data class HandoverLook(val armed: Boolean, val signedIn: Boolean)

fun handoverLook(armed: Boolean, daemonAlive: Boolean, backendState: String): HandoverLook {
    val registered = daemonAlive && (backendState == "Running" || backendState == "NeedsMachineAuth")
    return HandoverLook(armed = armed || !registered, signedIn = armed && registered)
}

// ─── The phone ──────────────────────────────────────────────────────────────

/** The JSON of the bridge's `setupParse`: where a scanned code would send a key. */
@Serializable
data class SetupTarget(
    val ok: Boolean = false,
    val error: String = "",
    val id: String = "",
    val addresses: List<String> = emptyList(),
    val port: Int = 0,
) {
    companion object {
        fun parse(json: String?): SetupTarget =
            runCatching { AppJson.decodeFromString<SetupTarget>(json.orEmpty()) }.getOrDefault(SetupTarget(error = "malformed"))
    }
}

/** The JSON of the bridge's `setupSend` and `setupSendStatus`. */
@Serializable
data class SetupSendStatus(val state: String = "idle", val code: String = "", val address: String = "") {
    companion object {
        fun parse(json: String?): SetupSendStatus =
            runCatching { AppJson.decodeFromString<SetupSendStatus>(json.orEmpty()) }.getOrDefault(SetupSendStatus(state = "unreadable"))
    }
}

/** The one thing left to do once a send is over. */
enum class KeySendNext {
    /** It worked: leave. */
    DONE,
    /** The television's code is spent: read its new one. */
    RESCAN,
    /** Nothing reached the television, or nothing it kept: the same fields, again. */
    RESEND,
    /** A field was refused: back to the form. */
    EDIT,
    /** Nothing more can be done from here. */
    CLOSE,
}

/** How a send ended. */
enum class KeySendEnd(val next: KeySendNext) {
    CONFIRMED(KeySendNext.DONE),
    REJECTED(KeySendNext.RESCAN),
    EXPIRED(KeySendNext.RESCAN),
    CANCELLED(KeySendNext.RESCAN),
    /** The connection dropped after the television took the bundle: only its screen knows the rest. */
    LOST(KeySendNext.CLOSE),
    TAKEN(KeySendNext.RESCAN),
    UNSUPPORTED(KeySendNext.CLOSE),
    CLOSED(KeySendNext.RESCAN),
    UNREACHABLE(KeySendNext.RESEND),
    WRONG_NETWORK(KeySendNext.RESEND),
    NO_ANSWER(KeySendNext.RESEND),
    /** A setup code, but damaged. */
    INVALID_CODE(KeySendNext.RESCAN),
    /** A setup code of a version this build does not read. */
    NEWER_CODE(KeySendNext.CLOSE),
    INVALID_KEY(KeySendNext.EDIT),
    INVALID_SERVER(KeySendNext.EDIT),
    BUSY(KeySendNext.RESEND),
    UNKNOWN(KeySendNext.CLOSE);

    /** Whether the hint about the two devices' network belongs under it. */
    val network: Boolean get() = this == UNREACHABLE || this == WRONG_NETWORK

    companion object {
        /** Why a scanned setup code opens no form, or null when it does. */
        fun ofTarget(target: SetupTarget): KeySendEnd? = when {
            target.ok && target.addresses.isNotEmpty() -> null
            target.error == "unsupported_version" -> NEWER_CODE
            else -> INVALID_CODE
        }
    }
}

/** What the phone's sheet shows. */
sealed interface KeySendStep {
    data object Form : KeySendStep
    data object Sending : KeySendStep

    /** The television holds the bundle and shows [checkCode] too; the person there decides. */
    data class Compare(val checkCode: String, val address: String) : KeySendStep

    data class Ended(val how: KeySendEnd, val address: String = "") : KeySendStep
}

fun keySendStep(status: SetupSendStatus): KeySendStep = when (status.state) {
    "idle" -> KeySendStep.Form
    "sending" -> KeySendStep.Sending
    "awaiting" -> KeySendStep.Compare(status.code, status.address)
    else -> KeySendStep.Ended(
        when (status.state) {
            "confirmed" -> KeySendEnd.CONFIRMED
            "rejected" -> KeySendEnd.REJECTED
            "expired" -> KeySendEnd.EXPIRED
            "cancelled" -> KeySendEnd.CANCELLED
            "lost" -> KeySendEnd.LOST
            "taken" -> KeySendEnd.TAKEN
            "unsupported" -> KeySendEnd.UNSUPPORTED
            "closed" -> KeySendEnd.CLOSED
            "unreachable" -> KeySendEnd.UNREACHABLE
            "wrong_network" -> KeySendEnd.WRONG_NETWORK
            "no_answer" -> KeySendEnd.NO_ANSWER
            "invalid_payload" -> KeySendEnd.INVALID_CODE
            "invalid_key" -> KeySendEnd.INVALID_KEY
            "invalid_server" -> KeySendEnd.INVALID_SERVER
            "busy" -> KeySendEnd.BUSY
            else -> KeySendEnd.UNKNOWN
        },
        status.address,
    )
}

/**
 * Whether [text] is shaped like a setup code — "TSP", a version, a colon, then
 * capitals and digits — whatever the version. Only that: the bridge reads it,
 * and says what is wrong with one it cannot use. The scanner asks this to keep
 * such a code to itself instead of handing it on as text.
 */
fun looksLikeSetupCode(text: String): Boolean = SetupCodeShape.matches(text.trim())

private val SetupCodeShape = Regex("TSP[0-9]{1,3}:[0-9A-Z]{1,160}", RegexOption.IGNORE_CASE)

/** What the phone's key field holds. */
enum class KeyField(val sendable: Boolean) {
    EMPTY(false),
    OK(true),
    /** No prefix this app knows; older Headscale keys have none, so it goes as it is. */
    UNKNOWN_KIND(true),
    /** A Tailscale API access token: it opens the admin API and signs nothing in. */
    API_TOKEN(false),
    /** A space, a line break or a character outside printable ASCII. */
    BAD_CHARACTERS(false),
    TOO_LONG(false);

    companion object {
        /** The bridge's own limit (setupMaxKey). */
        const val MAX_LENGTH = 512

        /** The bridge's check (setupKeyOK), made before the person presses Send, and two things it does not look at. */
        fun of(input: String): KeyField {
            val key = input.trim()
            return when {
                key.isEmpty() -> EMPTY
                key.any { it !in '!'..'~' } -> BAD_CHARACTERS
                key.length > MAX_LENGTH -> TOO_LONG
                key.startsWith("tskey-api-") -> API_TOKEN
                KeyKind.of(key) == KeyKind.OTHER -> UNKNOWN_KIND
                else -> OK
            }
        }
    }
}

/**
 * [key] as a screen may show it once it is entered: the prefix that says what
 * kind it is, and nothing of the rest. A key of no known kind shows nothing
 * of itself at all.
 */
fun maskAuthKey(key: String): String {
    val kind = KeyKind.of(key.trim())
    return if (kind == KeyKind.OTHER) "…" else "${kind.prefix}-…"
}

/** What the phone's login-server field holds. */
enum class ServerField(val sendable: Boolean) {
    /** The television's server stays as it is. */
    EMPTY(true),
    OK(true),
    BAD(false);

    companion object {
        /** The bridge's own limit (setupMaxServer). */
        const val MAX_LENGTH = 255

        /**
         * The bridge's check (setupServerOK) of [input] as [normalizeLoginServer]
         * would send it: an http(s) address with a host, in printable ASCII,
         * without credentials, query or fragment.
         */
        fun of(input: String): ServerField {
            val server = normalizeLoginServer(input)
            if (server.isEmpty()) return EMPTY
            if (server.length > MAX_LENGTH || server.any { it !in '!'..'~' }) return BAD
            val uri = runCatching { URI(server) }.getOrNull() ?: return BAD
            val scheme = uri.scheme?.lowercase()
            val ok = (scheme == "http" || scheme == "https") && !uri.isOpaque && !uri.host.isNullOrEmpty() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
            return if (ok) OK else BAD
        }
    }
}

/** A typed server address as it is sent: trimmed, and with https:// where no scheme was typed. */
fun normalizeLoginServer(input: String): String {
    val server = input.trim()
    return if (server.isEmpty() || "://" in server) server else "https://$server"
}

// ─── Both ───────────────────────────────────────────────────────────────────

/** The six digits both screens show, in two groups, the way they are read aloud. */
fun formatCheckCode(code: String): String =
    if (code.length == 6 && code.all { it in '0'..'9' }) "${code.take(3)} ${code.drop(3)}" else code

/** [seconds] as minutes and seconds: 9:40. */
fun formatClock(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
