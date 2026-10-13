package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import appctr.Appctr
import appctr.RemoteDelegate
import io.github.bropines.tailscaled.BuildConfig
import io.github.bropines.tailscaled.admin.secure.KeyValueStore
import io.github.bropines.tailscaled.admin.secure.KeystoreSecretBox
import io.github.bropines.tailscaled.admin.secure.SecretBox
import io.github.bropines.tailscaled.models.StatusResponse
import io.github.bropines.tailscaled.ui.updateAllWidgets
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Remote management, experimental: one device's TailSocks — a television, typically — managed
 * from another over the tailnet, after a pairing confirmed with a code from the managed
 * device's own screen. The endpoint, the pairings and the lists of what may be asked are the
 * bridge's; appctr/remote.go is where to read first, its header is the threat model.
 *
 * This file is the app's half:
 *   - the switch. Off unless a person turned it on on this device, and kept where no backup
 *     reaches, so a restore cannot turn it on either;
 *   - RemoteSettingsDelegate, through which the bridge reads and changes the few settings a
 *     paired device may touch. The bridge filters first; this refuses again, by its own list;
 *   - on the managing phone, RemoteTokenStore — the token a managed device issued, sealed —
 *     and RemoteSession, the requests made with it.
 *
 * Everything on disk is under files/remote/: the bridge's pairings (<profile>.json), the
 * switch and the tokens. That directory is out of cloud backup and device transfer
 * (res/xml/backup_rules.xml, data_extraction_rules.xml), and the app's own backup never
 * carried it — it takes shared_prefs whole, which is why nothing of this is a preference.
 */
object RemoteManage {
    private const val TAG = "RemoteManage"
    private const val DIR = "remote"

    /** Its presence is the switch. Not `<profile>.json`, so no profile's pairings can be taken for it. */
    private const val SWITCH_FILE = "managed.on"
    private const val TOKENS_FILE = "tokens.sealed"
    private const val KEY_ALIAS = "tailsocks_remote_tokens"

    private val registered = AtomicBoolean(false)

    private fun dir(context: Context) = File(context.filesDir, DIR)

    /** Whether paired devices may manage this one. */
    fun isEnabled(context: Context): Boolean = File(dir(context), SWITCH_FILE).isFile

    /**
     * Turns remote management on or off and tells a running service, which opens or closes
     * the endpoint without a restart. False when the switch could not be written.
     */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val file = File(dir(context), SWITCH_FILE)
        val done = try {
            if (enabled) {
                file.parentFile?.mkdirs()
                file.createNewFile() || file.isFile
            } else {
                !file.exists() || file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "The switch could not be written: ${e.message}")
            false
        }
        if (done && ProxyState.isActualRunning(context)) TailscaledService.requestApplySettings(context)
        return done
    }

    /**
     * The switch, for StartOptions.remoteManagement — and the app's half follows it: registered
     * with the bridge while the switch is on, taken away when it is off. Called wherever the
     * service builds its options, so a change reaches the bridge with the settings it applies.
     */
    fun startOption(context: Context): Boolean {
        val on = isEnabled(context)
        try {
            if (on && registered.compareAndSet(false, true)) {
                // The three switches here; everything else a paired device may ask is core/RemoteDevice.kt's.
                val app = context.applicationContext
                Appctr.setRemoteDelegate(remoteDeviceDelegate(app, RemoteSettingsDelegate(AppRemoteHost(app))))
            } else if (!on && registered.compareAndSet(true, false)) {
                Appctr.setRemoteDelegate(null)
            }
        } catch (e: Throwable) {
            registered.set(false)
            Log.w(TAG, "The bridge did not take the remote delegate: ${e.message}")
        }
        return on
    }

    /** The managing phone's tokens: one sealed file, under a Keystore key of its own. */
    fun tokens(context: Context): RemoteTokenStore =
        RemoteTokenStore(FileKeyValueStore(File(dir(context), TOKENS_FILE)), KeystoreSecretBox(KEY_ALIAS))

    /**
     * Why peers cannot reach the endpoint when the bridge reports no way in, read from the
     * settings that put the daemon in that mode — so the screen can name what to switch.
     */
    fun blockedBy(context: Context): RemoteBlocker = when {
        GlobalSettings.isRootModeEnabled(context) && GlobalSettings.isRootTunEnabled(context) -> RemoteBlocker.ROOT_TUN
        GlobalSettings.isTunModeEnabled(context) && GlobalSettings.getTunEngine(context) == TunEngines.NATIVE -> RemoteBlocker.NATIVE_TUN
        else -> RemoteBlocker.OTHER
    }
}

// ─── The managed device: what a paired device may read and change ────────────────────────────

/** A refusal. Its message is shown on the managing device: short, and never a secret. */
class RemoteRefusal(message: String) : Exception(message)

/**
 * The app as [RemoteSettingsDelegate] needs it: the three switches, the exit node, a restart
 * and the two facts. Every change throws [RemoteRefusal] when it cannot be made.
 */
interface RemoteHost {
    fun switchOn(key: String): Boolean
    fun setSwitch(key: String, on: Boolean)
    /** [id] is a peer's StableNodeID, or empty for no exit node. */
    fun setExitNode(id: String)
    /** Schedules the restart and returns: the bridge waits for no more than an answer. */
    fun reconnect()
    fun deviceClass(): String
    fun appVersion(): String
}

/**
 * The bridge's way into the app's settings. The bridge enforces its own allow-lists before it
 * calls (appctr/remote_policy.go); this one knows the same keys and refuses any other, so the
 * two cannot drift apart without one of them saying so. Called on a thread of the bridge's,
 * one call at a time.
 */
class RemoteSettingsDelegate(private val host: RemoteHost) : RemoteDelegate {

    override fun getSetting(key: String?): String = when (key) {
        in RemoteKeys.SWITCHES -> asked { host.switchOn(key!!).toString() }
        RemoteKeys.DEVICE_CLASS -> asked { host.deviceClass() }
        RemoteKeys.APP_VERSION -> asked { host.appVersion() }
        else -> throw RemoteRefusal("not a remote setting")
    }

    override fun setSetting(key: String?, value: String?) {
        when (key) {
            in RemoteKeys.SWITCHES -> {
                val on = bool(value)
                asked { host.setSwitch(key!!, on) }
            }
            Appctr.RemoteKeyExitNode -> {
                val id = value.orEmpty()
                if (id.isNotEmpty() && !RemoteJson.isNodeId(id)) throw RemoteRefusal("not an exit node")
                asked { host.setExitNode(id) }
            }
            else -> throw RemoteRefusal("not a remote setting")
        }
    }

    override fun performAction(name: String?) {
        if (name != RemoteKeys.ACTION_RECONNECT) throw RemoteRefusal("not a remote action")
        asked { host.reconnect() }
    }

    /** Requests are RemoteDeviceDelegate's (core/RemoteDevice.kt), which stands in front of this one. */
    override fun request(name: String?, args: String?): String = throw RemoteRefusal("not a remote request")

    /**
     * What the app answers, or its refusal. Anything else it throws stays on this device: an
     * exception's text travels to the managing one, and only a [RemoteRefusal]'s was written
     * for that.
     */
    private inline fun <T> asked(block: () -> T): T = try {
        block()
    } catch (e: RemoteRefusal) {
        throw e
    } catch (e: Exception) {
        Log.w("RemoteManage", "A remote request failed in the app: ${e.javaClass.simpleName}")
        throw RemoteRefusal("the app could not do it")
    }

    private fun bool(value: String?): Boolean = when (value) {
        "true" -> true
        "false" -> false
        else -> throw RemoteRefusal("not a value for this setting")
    }
}

/**
 * The settings where Settings itself keeps them, applied the way it applies them: MagicDNS and
 * subnet routes are app-wide preferences sent with the service's settings; LAN access through an
 * exit node is the profile's node switch (ClientPrefs), which goes to the daemon first so that a
 * refusal stores nothing; the exit node is the profile's id and address, as the main screen
 * writes them.
 */
internal class AppRemoteHost(private val context: Context) : RemoteHost {
    private val main = Handler(Looper.getMainLooper())

    private fun profilePrefs(): SharedPreferences =
        context.getSharedPreferences("appctr_${AccountManager.getActiveAccount(context).id}", Context.MODE_PRIVATE)

    /** What the extra arguments force LAN access to, which then wins over the switch; null when they say nothing. */
    private fun lanAccessForced(): Boolean? = runCatching {
        val extra = GlobalSettings.getString(context, "extra_args_raw", "").trim()
        if (extra.isEmpty()) null else ClientPrefs.overrides(Appctr.extraArgsPrefsJSON(extra)).exitNodeAllowLan
    }.getOrNull()

    private fun daemonRunning(): Boolean =
        Appctr.isRunning() && runCatching { Appctr.getBackendState() }.getOrDefault("") == "Running"

    override fun switchOn(key: String): Boolean = when (key) {
        RemoteKeys.ACCEPT_DNS -> GlobalSettings.getBoolean(context, "accept_dns", true)
        RemoteKeys.ACCEPT_ROUTES -> GlobalSettings.getBoolean(context, "accept_routes", false)
        RemoteKeys.EXIT_NODE_LAN -> lanAccessForced()
            ?: ClientPrefs.stored(profilePrefs().all).exitNodeAllowLan
            ?: runCatching { ClientPrefs.live(Appctr.getPrefsJSON()) }.getOrNull()?.exitNodeAllowLan
            ?: false
        else -> throw RemoteRefusal("not a remote setting")
    }

    override fun setSwitch(key: String, on: Boolean) {
        when (key) {
            RemoteKeys.ACCEPT_DNS -> GlobalSettings.setBoolean(context, "accept_dns", on)
            RemoteKeys.ACCEPT_ROUTES -> GlobalSettings.setBoolean(context, "accept_routes", on)
            RemoteKeys.EXIT_NODE_LAN -> {
                if (lanAccessForced() != null) throw RemoteRefusal("set by an extra argument on the device")
                // Not during a login: the settings sync keeps its hands off the prefs then, and so does this.
                if (daemonRunning()) {
                    val patch = ClientPrefs.maskedPrefsJson(ClientPrefs.Values(exitNodeAllowLan = on))
                    if (Appctr.setPrefs(patch) != "OK") throw RemoteRefusal("the daemon refused the change")
                }
                profilePrefs().edit().putBoolean(ClientPrefs.EXIT_NODE_LAN, on).apply()
            }
            else -> throw RemoteRefusal("not a remote setting")
        }
        TailscaledService.requestApplySettings(context)
    }

    override fun setExitNode(id: String) {
        // The address is what the app's own screens show and compare; the daemon routes by the id.
        val ip = if (id.isEmpty()) "" else addressOf(id) ?: throw RemoteRefusal("not one of this device's peers")
        if (Appctr.isRunning()) {
            val patch = buildJsonObject { put("ExitNodeID", id); put("ExitNodeIDSet", true) }.toString()
            if (Appctr.setPrefs(patch) != "OK") throw RemoteRefusal("the daemon refused the change")
        }
        profilePrefs().edit().putString("exit_node_ip", ip).putString("exit_node_id", id).apply()
        if (!Appctr.isRunning()) TailscaledService.requestApplySettings(context)
        updateAllWidgets(context)
        // A TUN device carries the default route only behind an exit node: let it look again.
        if (GlobalSettings.isTunModeEnabled(context)) {
            try {
                context.startService(Intent(context, TunVpnService::class.java).apply { action = TunVpnService.ACTION_START })
            } catch (e: Exception) {
                Log.w(TAG, "The tunnel was not told about the exit node: ${e.message}")
            }
        }
    }

    private fun addressOf(id: String): String? = runCatching {
        AppJson.decodeFromString<StatusResponse>(Appctr.getStatusFromAPI())
            .peers?.values?.firstOrNull { it.id == id }?.tailscaleIPs?.firstOrNull()
    }.getOrNull()

    override fun reconnect() {
        if (!ProxyState.isUserLetRunning(context)) throw RemoteRefusal("the device is stopped")
        // A moment later, not now: the restart closes the endpoint, and the answer to this very
        // request has to leave first. A Stop pressed on the device meanwhile is final.
        main.postDelayed({
            if (!ProxyState.isUserLetRunning(context)) return@postDelayed
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" }
                )
            } catch (e: Exception) {
                Log.w(TAG, "The restart asked for remotely did not start: ${e.message}")
            }
        }, RESTART_DELAY_MS)
    }

    override fun deviceClass(): String = when {
        DeviceProfile.deviceClass(context) == DeviceClass.TV -> RemoteProbe.DEVICE_TV
        context.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
        else -> "phone"
    }

    override fun appVersion(): String = BuildConfig.VERSION_NAME

    private companion object {
        const val TAG = "RemoteManage"
        const val RESTART_DELAY_MS = 800L
    }
}

// ─── The managing phone: tokens and requests ─────────────────────────────────────────────────

/** A [KeyValueStore] in one JSON file, replaced whole and atomically. For a handful of entries. */
class FileKeyValueStore(private val file: File) : KeyValueStore {
    private val format = MapSerializer(String.serializer(), String.serializer())

    private fun read(): Map<String, String> =
        if (!file.isFile) emptyMap()
        else runCatching { AppJson.decodeFromString(format, file.readText()) }.getOrDefault(emptyMap())

    override fun getString(key: String): String? = synchronized(LOCK) { read()[key] }

    override fun putString(key: String, value: String?): Boolean = synchronized(LOCK) {
        val next = read().toMutableMap()
        if (value == null) next.remove(key) else next[key] = value
        try {
            if (next.isEmpty()) return@synchronized !file.exists() || file.delete()
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(AppJson.encodeToString(format, next))
            tmp.renameTo(file) || run { tmp.delete(); false }
        } catch (e: Exception) {
            false
        }
    }

    override fun keys(): Set<String> = synchronized(LOCK) { read().keys }

    private companion object {
        /** One lock for every instance: they are made per use and may name the same file. */
        val LOCK = Any()
    }
}

/**
 * What the phone keeps of one pairing: the token, and what the managed device said the pairing
 * is. [expiresAt] is this phone's clock, milliseconds, 0 for until revoked — only for telling a
 * pairing that ran out from one that was revoked; the managed device's own clock decides.
 */
@Serializable
data class RemotePairingRecord(
    val token: String,
    val name: String = "",
    val owner: Boolean = false,
    val expiresAt: Long = 0,
    /** The device said it is a television when it was paired: for its glyph while it is away. */
    val tv: Boolean = false,
)

/**
 * The tokens of the devices this phone manages, sealed (admin/secure/SecretBox.kt), one per
 * profile and peer: a StableNodeID means something only within one control server, and the
 * blob is bound to both, so it does not open under another peer's name.
 */
class RemoteTokenStore(private val store: KeyValueStore, private val box: SecretBox) {

    private fun key(profileId: String, nodeId: String) = "$profileId/$nodeId"
    private fun aad(profileId: String, nodeId: String) = "tailsocks-remote/$profileId/$nodeId"

    /** Seals and stores a pairing, replacing the peer's earlier one. False when it did not land. */
    fun put(profileId: String, nodeId: String, record: RemotePairingRecord): Boolean {
        if (!RemoteJson.isNodeId(nodeId) || record.token.isEmpty()) return false
        return try {
            store.putString(
                key(profileId, nodeId),
                box.seal(AppJson.encodeToString(RemotePairingRecord.serializer(), record), aad(profileId, nodeId))
            )
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The peer's pairing, or null when there is none. One that is stored and cannot be opened —
     * another phone's key, a reset Keystore — is no pairing either, and is dropped: pairing
     * again takes a code from the managed device's screen, which is the point of the code.
     */
    fun get(profileId: String, nodeId: String): RemotePairingRecord? {
        val blob = store.getString(key(profileId, nodeId)) ?: return null
        val record = try {
            AppJson.decodeFromString(RemotePairingRecord.serializer(), box.open(blob, aad(profileId, nodeId)))
                .takeIf { it.token.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
        if (record == null) remove(profileId, nodeId)
        return record
    }

    fun has(profileId: String, nodeId: String): Boolean = store.getString(key(profileId, nodeId)) != null

    fun remove(profileId: String, nodeId: String): Boolean = store.putString(key(profileId, nodeId), null)

    /** Drops every token of a profile, for when the account is deleted. The devices keep their side until revoked there. */
    fun removeProfile(profileId: String) {
        store.keys().filter { it.startsWith("$profileId/") }.forEach { store.putString(it, null) }
    }
}

/** The bridge's client calls, so that [RemoteSession] can be given a made-up device. */
interface RemotePeerApi {
    fun probe(peerId: String): RemoteProbe
    fun pair(peerId: String, code: String): RemotePairAnswer
    fun get(peerId: String, token: String, path: String): RemoteAnswer
    fun post(peerId: String, token: String, path: String, body: String): RemoteAnswer
    /** A scanned pairing code, or null when the text is not one. */
    fun parseQr(text: String): RemoteQr?
}

/** The real one. Every call is a request through this phone's daemon: never on the main thread. */
object BridgeRemotePeerApi : RemotePeerApi {
    override fun probe(peerId: String) = RemoteJson.probe(Appctr.remoteProbe(peerId))
    override fun pair(peerId: String, code: String) = RemoteJson.pair(Appctr.remotePair(peerId, code))
    override fun get(peerId: String, token: String, path: String) = RemoteJson.answer(Appctr.remoteGet(peerId, token, path))
    override fun post(peerId: String, token: String, path: String, body: String) =
        RemoteJson.answer(Appctr.remotePost(peerId, token, path, body))
    override fun parseQr(text: String) = RemoteJson.qr(Appctr.remoteParseQR(text))
}

/**
 * One managed device as this phone talks to it: [peerId] is its StableNodeID, [profileId] the
 * account the pairing belongs to. Holds no state but the token store; blocking, so every call
 * belongs off the main thread.
 *
 * A token the device refuses is forgotten at once — the pairing is gone there, whatever the
 * reason, and keeping the token would only send it again.
 */
class RemoteSession(
    private val api: RemotePeerApi,
    private val tokens: RemoteTokenStore,
    private val profileId: String,
    val peerId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The stored pairing, if this phone holds one for the device. */
    fun pairing(): RemotePairingRecord? = tokens.get(profileId, peerId)

    fun probe(): RemoteProbe = api.probe(peerId)

    /**
     * Pairs with the six digits from the device's screen and keeps the token. A pairing whose
     * token could not be stored is given back at once: the device would otherwise list a phone
     * that cannot use it.
     */
    fun pair(code: String, tv: Boolean = false): RemotePairAnswer {
        if (!RemoteJson.isCode(code)) return RemotePairAnswer(error = "bad_request")
        val answer = api.pair(peerId, code)
        if (!answer.ok) return answer
        val record = RemotePairingRecord(
            token = answer.token,
            name = answer.name,
            owner = answer.role == REMOTE_CLASS_OWNER,
            expiresAt = if (answer.left >= 0) now() + answer.left * 1000 else 0,
            tv = tv,
        )
        if (!tokens.put(profileId, peerId, record)) {
            api.post(peerId, answer.token, RemotePaths.UNPAIR, "")
            return RemotePairAnswer(error = RemotePairAnswer.NOT_STORED)
        }
        return answer
    }

    fun status(): RemoteResult<RemoteStatus> = get(RemotePaths.STATUS)
    fun exitNodes(): RemoteResult<RemoteExitNodes> = get(RemotePaths.EXIT_NODES)
    fun settings(): RemoteResult<RemoteSettings> = get(RemotePaths.SETTINGS)
    fun files(): RemoteResult<RemoteFiles> = get(RemotePaths.FILES)

    fun setExitNode(id: String): RemoteResult<Unit> = post(RemotePaths.EXIT_NODE, RemoteJson.exitNodeBody(id))
    fun setSwitch(key: String, on: Boolean): RemoteResult<Unit> = post(RemotePaths.SETTINGS, RemoteJson.settingBody(key, on))
    fun reconnect(): RemoteResult<Unit> = post(RemotePaths.ACTIONS, RemoteJson.actionBody(RemoteKeys.ACTION_RECONNECT))
    fun deleteFile(name: String): RemoteResult<Unit> = post(RemotePaths.FILES_DELETE, RemoteJson.fileBody(name))

    /**
     * Forgets the device: asks it to drop the pairing, and drops the token here whatever it
     * answers — forgetting is this phone's to decide. The result says whether the device heard.
     */
    fun unpair(): RemoteResult<Unit> {
        val record = pairing() ?: return RemoteResult.Ok(Unit)
        val answer = api.post(peerId, record.token, RemotePaths.UNPAIR, "")
        tokens.remove(profileId, peerId)
        return when (answer.status) {
            // 401: the pairing was gone there already, which is what was asked for.
            200, 401 -> RemoteResult.Ok(Unit)
            else -> answer.failure()
        }
    }

    private inline fun <reified T> get(path: String): RemoteResult<T> {
        val record = pairing() ?: return RemoteResult.Failed(RemoteFailure.UNAUTHORIZED)
        val answer = api.get(peerId, record.token, path)
        if (answer.status != 200) return refused(answer, record)
        return RemoteJson.decode<T>(answer.body)?.let { RemoteResult.Ok(it) } ?: RemoteResult.Failed(RemoteFailure.FAILED)
    }

    private fun post(path: String, body: String): RemoteResult<Unit> {
        val record = pairing() ?: return RemoteResult.Failed(RemoteFailure.UNAUTHORIZED)
        val answer = api.post(peerId, record.token, path, body)
        return if (answer.status == 200) RemoteResult.Ok(Unit) else refused(answer, record)
    }

    /**
     * The device says only "unauthorized" for a pairing that is gone, whatever ended it. A
     * guest's pairing whose time is up by this phone's clock is taken to have run out.
     */
    private fun refused(answer: RemoteAnswer, record: RemotePairingRecord): RemoteResult.Failed {
        val failed = answer.failure()
        if (failed.failure != RemoteFailure.UNAUTHORIZED) return failed
        tokens.remove(profileId, peerId)
        val ranOut = !record.owner && record.expiresAt in 1..now()
        return if (ranOut) RemoteResult.Failed(RemoteFailure.EXPIRED) else failed
    }
}
