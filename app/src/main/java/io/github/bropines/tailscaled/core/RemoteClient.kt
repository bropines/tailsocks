package io.github.bropines.tailscaled.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The managing device's requests to one managed device — every route of the contract
 * (.claude/plans/tv/remote-api.md), with each refusal named by what a person can do about it.
 *
 * All of it goes through RemotePeerApi (core/RemoteManage.kt), the bridge's two calls: that
 * interface is the seam. BridgeRemotePeerApi is the tailnet; FakeManagedDevice
 * (core/RemoteClientFake.kt) is a device made of the contract's own examples, for tests and
 * previews. Pairing and forgetting stay RemoteSession's; this adds what came after it.
 */

/** Why a request brought nothing back, as far as what happens next differs. */
enum class ManagedRefusal {
    /** This device's own TailSocks is not connected: nothing was sent. */
    NOT_CONNECTED,
    /** The device is not in this tailnet any more. */
    NO_PEER,
    /** No answer at all. */
    UNREACHABLE,
    /** The token was refused: the pairing is gone there. It is forgotten here too. */
    UNAUTHORIZED,
    /** The same, for a guest's pairing whose time is up by this device's clock. */
    EXPIRED,
    /** Not for this class of caller. */
    FORBIDDEN,
    /** For this caller only while Full control is on there, and it is off. */
    NEEDS_FULL,
    NOT_FOUND,
    /** The device's app said no; its sentence comes along. */
    REFUSED,
    /** A confirmation sent before the change has settled; `after` says how long to wait. */
    TOO_EARLY,
    /** Another change still waits to be confirmed. */
    GUARD_PENDING,
    SLOW_DOWN,
    /** The app there was not asked. Nothing was changed. */
    UNAVAILABLE,
    /** The app was asked and has not answered. The change may still be applied. */
    PENDING,
    /** The app answered something the device did not understand. What it did is not known. */
    GARBLED,
    /** Anything else: an error there, or a request this build got wrong. */
    FAILED;

    /** Nothing came back and nothing can be said of the device: what it showed last is all there is. */
    val away: Boolean get() = this == NOT_CONNECTED || this == UNREACHABLE

    /** The pairing or the device is gone: nothing more can be asked. */
    val final: Boolean get() = this == UNAUTHORIZED || this == EXPIRED || this == NO_PEER

    /** A change may or may not have been made: read the device again before saying either. */
    val unclear: Boolean get() = this == PENDING || this == GARBLED
}

/** A request's outcome: the decoded body, or why there is none. */
sealed interface ManagedResult<out T> {
    data class Ok<T>(val value: T) : ManagedResult<T>

    /** [message] is the device's own sentence for a [ManagedRefusal.REFUSED]; [after] the seconds of a [ManagedRefusal.TOO_EARLY]. */
    data class Failed(val refusal: ManagedRefusal, val message: String = "", val after: Long = 0) : ManagedResult<Nothing>

    val failed: Failed? get() = this as? Failed
    val valueOrNull: T? get() = (this as? Ok)?.value
}

/** The body of a refusal, with what `too_early` adds to it. */
@Serializable
private data class ManagedErrorBody(val error: String = "", val message: String = "", val after: Long = 0)

/** What an answer that is not a 200 means (contract §1). The word decides where one status has several. */
fun RemoteAnswer.refusal(): ManagedResult.Failed {
    val told = RemoteJson.decode<ManagedErrorBody>(body) ?: ManagedErrorBody()
    val kind = when (status) {
        0 -> when (error) {
            "not_connected" -> ManagedRefusal.NOT_CONNECTED
            "no_peer" -> ManagedRefusal.NO_PEER
            "bad_request" -> ManagedRefusal.FAILED
            else -> ManagedRefusal.UNREACHABLE
        }
        401 -> ManagedRefusal.UNAUTHORIZED
        403 -> if (told.error == "needs_full") ManagedRefusal.NEEDS_FULL else ManagedRefusal.FORBIDDEN
        404 -> ManagedRefusal.NOT_FOUND
        409 -> when (told.error) {
            "too_early" -> ManagedRefusal.TOO_EARLY
            "guard_pending" -> ManagedRefusal.GUARD_PENDING
            else -> ManagedRefusal.REFUSED
        }
        429 -> ManagedRefusal.SLOW_DOWN
        502 -> ManagedRefusal.GARBLED
        503 -> ManagedRefusal.UNAVAILABLE
        504 -> ManagedRefusal.PENDING
        else -> ManagedRefusal.FAILED
    }
    return ManagedResult.Failed(
        refusal = kind,
        // Only a refusal's sentence was written for a person; the rest are the API's own words.
        message = if (kind == ManagedRefusal.REFUSED) told.message else "",
        after = if (kind == ManagedRefusal.TOO_EARLY) told.after.coerceAtLeast(1) else 0,
    )
}

/** The paths that came after the first build's (RemotePaths). */
object ManagedPaths {
    const val CONFIRM = "/v1/confirm"
    const val PERMISSION_REQUEST = "/v1/permissions/request"
    const val IDENTIFY = "/v1/identify"
    const val STORAGE_LIST = "/v1/storage/list"
    const val STORAGE_SEND = "/v1/storage/send"
    const val SHARES_ADD = "/v1/shares/add"
    const val SHARES_REMOVE = "/v1/shares/remove"
    const val DIAG_REPORT = "/v1/diag/report"
    const val DIAG_LOG = "/v1/diag/log"
}

/**
 * One managed device as this one talks to it. Blocking: every call is a request through this
 * device's daemon and belongs off the main thread. Stateless but for the token store.
 *
 * It sends what it is told to. Whether a device knows a route or a field — `"guard"`, above
 * all — is the caller's to check first (ManagedAccess): an unknown field is a 400 there.
 */
class RemoteClient(
    private val api: RemotePeerApi,
    private val tokens: RemoteTokenStore,
    private val profileId: String,
    val peerId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val session = RemoteSession(api, tokens, profileId, peerId, now)

    /** The stored pairing, if this device holds one for the peer. */
    fun pairing(): RemotePairingRecord? = session.pairing()

    fun pair(code: String, tv: Boolean = false): RemotePairAnswer = session.pair(code, tv)

    /** A scanned pairing code, or null when the text is not one. */
    fun parseQr(text: String): RemoteQr? = runCatching { api.parseQr(text) }.getOrNull()

    fun status(): ManagedResult<ManagedStatus> = get(RemotePaths.STATUS)
    fun exitNodes(): ManagedResult<ManagedExitNodes> = get(RemotePaths.EXIT_NODES)
    fun settings(): ManagedResult<ManagedSettings> = get(RemotePaths.SETTINGS)
    fun files(): ManagedResult<RemoteFiles> = get(RemotePaths.FILES)

    /** [id] is a StableNodeID, or empty for none. [guard] only to a device with the capability. */
    fun setExitNode(id: String, guard: Boolean = false): ManagedResult<ManagedChanged> =
        change(RemotePaths.EXIT_NODE) { put("id", id); if (guard) put("guard", true) }

    fun setSetting(key: String, value: String, guard: Boolean = false): ManagedResult<ManagedChanged> =
        change(RemotePaths.SETTINGS) { put("key", key); put("value", value); if (guard) put("guard", true) }

    /** Keeps a guarded change. 404 says the device has already put the old value back. */
    fun confirm(id: String): ManagedResult<Unit> = send(ManagedPaths.CONFIRM) { put("id", id) }

    fun action(name: String): ManagedResult<Unit> = send(RemotePaths.ACTIONS) { put("action", name) }

    fun deleteFile(name: String): ManagedResult<Unit> = send(RemotePaths.FILES_DELETE) { put("name", name) }

    /** Asks the device to bring a permission's page in front of whoever is at it. It grants nothing. */
    fun askPermission(id: String): ManagedResult<ManagedPermissionAnswer> =
        post(ManagedPaths.PERMISSION_REQUEST) { put("permission", id) }

    /** The device shows "Managed from <this device>" for a few seconds. */
    fun identify(): ManagedResult<ManagedIdentified> = post(ManagedPaths.IDENTIFY) {}

    /** [path] empty for the roots. */
    fun storageList(path: String): ManagedResult<ManagedStorageList> = post(ManagedPaths.STORAGE_LIST) { put("path", path) }

    /** The device sends the file to this one with its own Taildrop; `status.fetch` follows it. */
    fun storageSend(path: String): ManagedResult<ManagedStorageSent> = post(ManagedPaths.STORAGE_SEND) { put("path", path) }

    fun addShare(name: String, path: String): ManagedResult<Unit> = send(ManagedPaths.SHARES_ADD) { put("name", name); put("path", path) }

    fun removeShare(name: String): ManagedResult<Unit> = send(ManagedPaths.SHARES_REMOVE) { put("name", name) }

    fun report(): ManagedResult<ManagedReport> = get(ManagedPaths.DIAG_REPORT)
    fun log(): ManagedResult<ManagedLog> = get(ManagedPaths.DIAG_LOG)

    /**
     * Forgets the device: asks it to drop the pairing, and drops the token here whatever it
     * answers. A failure says only that the device was not told.
     */
    fun unpair(): ManagedResult<Unit> = when (session.unpair()) {
        is RemoteResult.Ok -> ManagedResult.Ok(Unit)
        is RemoteResult.Failed -> ManagedResult.Failed(ManagedRefusal.UNREACHABLE)
    }

    private inline fun <reified T> get(path: String): ManagedResult<T> = call(path, null)

    private inline fun <reified T> post(path: String, body: JsonObjectBuilder.() -> Unit): ManagedResult<T> =
        call(path, buildJsonObject(body).toString())

    /** A change's answer is `{}` or its guard; an empty body is the first. */
    private fun change(path: String, body: JsonObjectBuilder.() -> Unit): ManagedResult<ManagedChanged> =
        when (val answer = raw(path, buildJsonObject(body).toString())) {
            is ManagedResult.Ok -> ManagedResult.Ok(RemoteJson.decode<ManagedChanged>(answer.value) ?: ManagedChanged())
            is ManagedResult.Failed -> answer
        }

    /** A request whose answer carries nothing worth reading. */
    private fun send(path: String, body: JsonObjectBuilder.() -> Unit): ManagedResult<Unit> =
        when (val answer = raw(path, buildJsonObject(body).toString())) {
            is ManagedResult.Ok -> ManagedResult.Ok(Unit)
            is ManagedResult.Failed -> answer
        }

    private inline fun <reified T> call(path: String, body: String?): ManagedResult<T> =
        when (val answer = raw(path, body)) {
            // A 200 that is not the document it should be: what the device did is not known.
            is ManagedResult.Ok -> RemoteJson.decode<T>(answer.value)?.let { ManagedResult.Ok(it) }
                ?: ManagedResult.Failed(ManagedRefusal.GARBLED)
            is ManagedResult.Failed -> answer
        }

    /** The body of a 200, or the refusal. [body] null is a GET. */
    private fun raw(path: String, body: String?): ManagedResult<String> {
        val record = pairing() ?: return ManagedResult.Failed(ManagedRefusal.UNAUTHORIZED)
        val answer = if (body == null) api.get(peerId, record.token, path) else api.post(peerId, record.token, path, body)
        if (answer.status == 200) return ManagedResult.Ok(answer.body)
        val failed = answer.refusal()
        if (failed.refusal != ManagedRefusal.UNAUTHORIZED) return failed
        // The pairing is gone there, whatever ended it: keeping the token would only send it again.
        tokens.remove(profileId, peerId)
        val ranOut = !record.owner && record.expiresAt in 1..now()
        return if (ranOut) ManagedResult.Failed(ManagedRefusal.EXPIRED) else failed
    }
}
