package io.github.bropines.tailscaled.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * A managed device as the managing one reads it: every answer of the device's API with the
 * fields that came after the first build, and the rules that turn them into what a screen
 * offers — which capability a thing needs, whether this caller may use it, and the one reason
 * when it may not. The contract is .claude/plans/tv/remote-api.md; core/RemoteModels.kt has the
 * documents of the first build and of the managed device's own screen.
 *
 * Everything here is read leniently. A field the device does not send is its default, a field
 * this build does not know is skipped, and a word it does not know — a capability, a setting's
 * key, a permission's id — is ignored where it is used: a device may be newer than the manager.
 */

/** The names in `status.caps`: what a device's build can do, whoever asks. */
object ManagedCaps {
    const val REPORT = "report"
    const val PERMISSIONS = "permissions"
    const val ONEWAY = "oneway"
    const val SUGGEST = "suggest"
    const val CAN = "can"
    const val DEVICE = "device"
    const val GUARD = "guard"
    const val MODE = "mode"
    const val IDENTIFY = "identify"
    const val STORAGE = "storage"
    const val SHARES = "shares"
    const val ADVERTISE = "advertise"
    const val DIAG = "diag"
    const val RELAYS = "relays"
    const val SAVE = "save"
    /** A guest may ask for full rights for a while, and is told while it has them (`you.elevated`). */
    const val ELEVATE = "elevate"
    /** The device has a switch for network access to its files, and says how it stands for this caller (`status.files`). */
    const val FILES = "files"
}

/** `status.level`: what the device lets its owners do. Chosen at the device, never from afar. */
object ManagedLevel {
    const val BASIC = "basic"
    const val FULL = "full"
}

/**
 * `status.files`: whether the device's network access to files lets this caller at its storage,
 * its shared folders, its log and its report. Told to an owner and to a guest with full rights;
 * switched on at the device, never from afar.
 */
object ManagedFiles {
    const val OPEN = "open"
    const val CLOSED = "closed"
}

/** GET /v1/status. The fields after [you] are absent on the first build. */
@Serializable
data class ManagedStatus(
    val running: Boolean = false,
    val backendState: String = "",
    /** The daemon's version, and the app's. */
    val version: String = "",
    val appVersion: String = "",
    val deviceClass: String = "",
    val name: String = "",
    val dnsName: String = "",
    val nodeId: String = "",
    val exitNode: ManagedExitNode? = null,
    val health: List<String> = emptyList(),
    val since: Long = 0,
    val you: ManagedYou = ManagedYou(),
    /** Null on the first build, which announces nothing: it gets exactly the first build's set. */
    val caps: List<String>? = null,
    val level: String = "",
    /** [ManagedFiles.OPEN] or [ManagedFiles.CLOSED]; empty from a build without the switch, and for a guest. */
    val files: String = "",
    /** A change this caller made that still waits for its confirmation. */
    val guard: ManagedPendingGuard? = null,
    val device: ManagedDeviceInfo? = null,
    val advertise: ManagedAdvertise? = null,
    /** This caller's last "send me this file". */
    val fetch: ManagedFetch? = null,
    /** What the app says of itself; absent when it did not answer in time — keep the last one. */
    val app: ManagedApp? = null,
) {
    val tv: Boolean get() = deviceClass == RemoteProbe.DEVICE_TV
}

/**
 * What the caller is to the device, and what it may use right now. A guest given full rights
 * for a while is an owner until they end: its class says so, and [elevated] says until when.
 */
@Serializable
data class ManagedYou(
    @SerialName("class") val role: String = "",
    /** Seconds the pairing has left; -1 for until revoked. */
    val left: Long = -1,
    /** The ops this caller may use; null where the device does not say (no `can` capability). */
    val can: List<String>? = null,
    val elevated: ManagedElevated? = null,
) {
    val owner: Boolean get() = role == REMOTE_CLASS_OWNER
}

/**
 * Full rights for a while: [left] seconds, ending at [until] by the device's clock, given for
 * [seconds]; [full] with full control; [noFiles] given while the device's files were closed, and
 * never including them.
 */
@Serializable
data class ManagedElevated(val left: Long = 0, val until: Long = 0, val seconds: Long = 0, val full: Boolean = false, val noFiles: Boolean = false)

/** POST /v1/elevate/request: what the device did with the question — "shown", "notified" or "waiting". */
@Serializable
data class ManagedElevateAsked(val result: String = "")

@Serializable
data class ManagedExitNode(
    val id: String = "",
    val name: String = "",
    val online: Boolean = false,
    val selected: Boolean = false,
    val country: String = "",
    val city: String = "",
    /** The one the device's own daemon would pick; at most one node carries it. */
    val suggested: Boolean = false,
) {
    val label: String get() = name.ifEmpty { id }
    val place: String get() = listOf(city, country).filter { it.isNotEmpty() }.joinToString(", ")
}

@Serializable
data class ManagedExitNodes(val nodes: List<ManagedExitNode> = emptyList())

/** `status.guard`: [left] seconds until the device reverts, [after] until a confirmation is taken. */
@Serializable
data class ManagedPendingGuard(val id: String = "", val key: String = "", val left: Long = 0, val after: Long = 0)

/** The guard a change's answer carries: revert in [seconds] unless confirmed, not before [after]. */
@Serializable
data class ManagedGuard(val id: String = "", val key: String = "", val seconds: Long = 60, val after: Long = 5)

/** The answer to a change: empty, or the guard it was made under. */
@Serializable
data class ManagedChanged(val guard: ManagedGuard? = null)

/** `status.device`. */
@Serializable
data class ManagedDeviceInfo(
    val model: String = "",
    val android: String = "",
    val sdk: Int = 0,
    /** The app's commit. */
    val build: String = "",
    /** "proxy", "tun" or "root". */
    val mode: String = "",
    /** "native", "hev" or "". */
    val engine: String = "",
    /** Unix seconds; 0 when the key does not expire. */
    val keyExpiry: Long = 0,
)

/** `status.advertise`: what the device offers the tailnet, and what the tailnet made of it. */
@Serializable
data class ManagedAdvertise(val exitNode: ManagedOffer? = null, val routes: List<ManagedRoute> = emptyList())

@Serializable
data class ManagedOffer(val on: Boolean = false, val approved: Boolean = false)

@Serializable
data class ManagedRoute(val cidr: String = "", val approved: Boolean = false)

/** `status.fetch`: a file the device is sending to this caller with its own Taildrop. */
@Serializable
data class ManagedFetch(
    val id: String = "",
    val name: String = "",
    val size: Long = 0,
    /** [SENDING], [SENT] or [FAILED]. */
    val state: String = "",
    /** Why it failed, in the daemon's words. */
    val error: String = "",
    /** Seconds since it ended; 0 while it runs. The device drops it ten minutes after. */
    val age: Long = 0,
) {
    companion object {
        const val SENDING = "sending"
        const val SENT = "sent"
        const val FAILED = "failed"
    }
}

/** `status.app`. */
@Serializable
data class ManagedApp(
    val permissions: List<ManagedPermission> = emptyList(),
    /** A request for a permission that still waits for a person at the device. */
    val asked: ManagedAsked? = null,
    val receive: ManagedReceive? = null,
    /** The last received files the receive folder took. They are not in GET /v1/files. */
    val saved: List<ManagedSavedFile> = emptyList(),
    /** The Taildrive shares; null for a guest, who is told only [folderCount]. */
    val folders: List<ManagedFolder>? = null,
    val folderCount: Int = 0,
)

@Serializable
data class ManagedPermission(
    val id: String = "",
    /** [GRANTED], [MISSING], [UNKNOWN] or [NA]. */
    val state: String = "",
    /** The device can be asked to put its page in front of a person, and by this caller. */
    val ask: Boolean = false,
) {
    companion object {
        const val GRANTED = "granted"
        const val MISSING = "missing"
        const val UNKNOWN = "unknown"
        const val NA = "na"

        const val STORAGE = "storage"

        /** The ids this build has words for, in the order the device lists them. */
        val KNOWN = listOf(STORAGE, "notifications", "battery", "vpn", "alarms", "install", "autostart")
    }
}

/** A permission request waiting at the device: [result] is "notified" or "waiting", [age] seconds. */
@Serializable
data class ManagedAsked(val id: String = "", val result: String = "", val age: Long = 0)

/** Where received Taildrop files go. */
@Serializable
data class ManagedReceive(
    /** [READY], [NEEDS_ACCESS], [UNAVAILABLE], [INBOX] or [UNKNOWN]. */
    val state: String = UNKNOWN,
    /** A label the device's user knows the folder by, never a path; empty for the inbox. */
    val folder: String = "",
    /** The folder does not work and this caller may send `receive_folder_default`. */
    val reset: Boolean = false,
    /** Bytes free where received files land; -1 when it cannot be told. */
    val free: Long = -1,
    /** How many received files wait inside the app: the count of GET /v1/files. */
    val waiting: Int = 0,
) {
    companion object {
        const val READY = "ready"
        const val NEEDS_ACCESS = "needs_access"
        const val UNAVAILABLE = "unavailable"
        const val INBOX = "inbox"
        const val UNKNOWN = "unknown"
    }
}

@Serializable
data class ManagedSavedFile(val name: String = "", val size: Long = 0, val time: Long = 0, val folder: String = "")

@Serializable
data class ManagedFolder(val name: String = "", val path: String = "")

/** One row of GET /v1/settings. */
@Serializable
data class ManagedSetting(
    val key: String = "",
    /** [BOOL], [TEXT] or [CIDRS]. */
    val kind: String = "",
    /** The setting can be changed, and by this caller. */
    val writable: Boolean = false,
    val value: String = "",
    /** Set, with an empty [value], when the app could not read this one setting. */
    val error: String = "",
    /** The one value this caller may write now; null for either. */
    val only: String? = null,
    /** "full" when the other value takes Full control; [NEEDS_FILES] when it is written neither way while the files are closed. */
    val needs: String = "",
    /** [GUARD_OPTIONAL], [GUARD_ALWAYS] or empty. */
    val guard: String = "",
) {
    val on: Boolean get() = value == "true"

    companion object {
        const val BOOL = "bool"
        const val TEXT = "text"
        const val CIDRS = "cidrs"
        const val GUARD_OPTIONAL = "optional"
        const val GUARD_ALWAYS = "always"
        /** [needs] of a setting that belongs to the device's files, while they are closed to this caller. */
        const val NEEDS_FILES = "files"
    }
}

@Serializable
data class ManagedSettings(val settings: List<ManagedSetting> = emptyList())

/** The settings this build has words for. A key that is not here is skipped. */
object ManagedKeys {
    const val ACCEPT_DNS = "accept_dns"
    const val ACCEPT_ROUTES = "accept_routes"
    const val EXIT_NODE_LAN = "exit_node_lan_access"
    const val AUTO_RECONNECT = "auto_reconnect"
    const val AUTO_START = "auto_start"
    const val KEEP_AWAKE = "keep_awake"
    const val TUN_MODE = "tun_mode"
    const val TAILDRIVE = "taildrive_enabled"
    const val TAILDRIVE_PROXY = "taildrive_proxy_enabled"
    const val ADVERTISE_EXIT_NODE = "advertise_exit_node"
    const val ADVERTISE_ROUTES = "advertise_routes"
    const val FULL_CONTROL = "full_control"

    /** What a guarded change of the exit node is filed under. */
    const val EXIT_NODE = "exit_node"

    const val ACTION_RECONNECT = "reconnect"
    const val ACTION_RECEIVE_DEFAULT = "receive_folder_default"
    const val ACTION_RELAYS = "reconnect_relays"
    const val ACTION_SAVE_RECEIVED = "save_received"

    /** The three switches of the first build; a device without `caps` is shown these and no other. */
    val FIRST_BUILD = listOf(ACCEPT_DNS, ACCEPT_ROUTES, EXIT_NODE_LAN)

    /** How the device uses the tailnet. */
    val CONNECTION = FIRST_BUILD

    /** How the device keeps itself running, and what it offers the tailnet. */
    val DEVICE = listOf(TUN_MODE, AUTO_RECONNECT, AUTO_START, KEEP_AWAKE, ADVERTISE_EXIT_NODE)

    /** Taildrive as a whole. */
    val SHARING = listOf(TAILDRIVE, TAILDRIVE_PROXY)
}

/** POST /v1/permissions/request. */
@Serializable
data class ManagedPermissionAnswer(val permission: String = "", val result: String = "")

/** How a request to show something on the device ended: a permission's page, or "Managed from …". */
enum class ManagedShown {
    /** In front of the person now. */
    SHOWN,
    /** A notification and a line on the screen; the page opens from there. */
    NOTIFIED,
    /** Nothing could be shown: someone has to open TailSocks there first. */
    WAITING;

    companion object {
        /** `unseen` is identify's word for what a permission request calls `waiting`. */
        fun of(result: String): ManagedShown? = when (result) {
            "shown" -> SHOWN
            "notified" -> NOTIFIED
            "waiting", "unseen" -> WAITING
            else -> null
        }
    }
}

/** POST /v1/identify. */
@Serializable
data class ManagedIdentified(val result: String = "")

/** POST /v1/storage/list: the roots for an empty path, a folder's entries otherwise. */
@Serializable
data class ManagedStorageList(
    val path: String = "",
    /** Absent at a root. */
    val parent: String? = null,
    val roots: List<ManagedStorageRoot>? = null,
    val entries: List<ManagedStorageEntry> = emptyList(),
    /** Where in the folder these entries start. */
    val offset: Int = 0,
    /** The folder holds more after these: ask again from [offset] plus what came. */
    val truncated: Boolean = false,
    /** The app there has no storage access and sees only its own files. */
    val limited: Boolean = false,
)

/**
 * A place the device's storage starts from. [kind] is the stable id; [name] is "Internal
 * storage" in English for the primary one — which a manager names itself — and the system's
 * own description of the volume, in the managed device's language, for the rest.
 */
@Serializable
data class ManagedStorageRoot(
    /** [PRIMARY], [REMOVABLE] or [VOLUME]; a kind this build does not know is a volume. */
    val kind: String = "",
    val name: String = "",
    val path: String = "",
    val free: Long = -1,
    val total: Long = -1,
) {
    companion object {
        const val PRIMARY = "primary"
        const val REMOVABLE = "removable"
        const val VOLUME = "volume"
    }
}

@Serializable
data class ManagedStorageEntry(val name: String = "", val dir: Boolean = false, val size: Long = 0, val time: Long = 0)

/** POST /v1/storage/send: the transfer the device started; follow it in `status.fetch`. */
@Serializable
data class ManagedStorageSent(val id: String = "", val name: String = "", val size: Long = 0)

/** GET /v1/diag/report. */
@Serializable
data class ManagedReport(val text: String = "")

/** GET /v1/diag/log: the last lines, oldest first, and how many secrets the device replaced. */
@Serializable
data class ManagedLog(val lines: List<String> = emptyList(), val scrubbed: Int = 0)

// ─── Who may do what ─────────────────────────────────────────────────────────────────────────

/**
 * The things a manager can ask of a device, beyond looking. [word] is the op's name in
 * `you.can`; [cap] the capability a build must announce for it — null for what the first
 * build already did; [owner] and [full] the lowest class and level the contract gives it;
 * [files] whether the device's network access to files closes it.
 */
enum class ManagedOp(
    val word: String,
    val cap: String?,
    val owner: Boolean,
    val full: Boolean = false,
    val guestOnly: Boolean = false,
    val files: Boolean = false,
) {
    EXIT_NODE_SET("exit_node.set", null, owner = false),
    SETTINGS_SET("settings.set", null, owner = true),
    ACTION("action", null, owner = true),
    FILES_DELETE("files.delete", null, owner = true),
    PERMISSION_REQUEST("permission.request", ManagedCaps.PERMISSIONS, owner = true),
    FOLDERS("folders", ManagedCaps.REPORT, owner = true, files = true),
    IDENTIFY("identify", ManagedCaps.IDENTIFY, owner = false),
    SHARES_REMOVE("shares.remove", ManagedCaps.SHARES, owner = true, files = true),
    SHARES_ADD("shares.add", ManagedCaps.SHARES, owner = true, full = true, files = true),
    STORAGE_LIST("storage.list", ManagedCaps.STORAGE, owner = true, full = true, files = true),
    STORAGE_SEND("storage.send", ManagedCaps.STORAGE, owner = true, full = true, files = true),
    DIAG_REPORT("diag.report", ManagedCaps.DIAG, owner = true, full = true, files = true),
    DIAG_LOG("diag.log", ManagedCaps.DIAG, owner = true, full = true, files = true),
    /** A guest's alone — an owner has nothing to ask for — and in `you.can` only while it may ask. */
    ELEVATE_REQUEST("elevate.request", ManagedCaps.ELEVATE, owner = false, guestOnly = true),
}

/** The one reason a thing that exists is not this caller's to use. */
enum class ManagedDenial {
    /** The device's network access to files is off (or this caller's full rights leave it out); it is switched on there. */
    FILES_CLOSED,
    /** Full control is off on the device; it is switched on there. */
    NEEDS_FULL,
    OWNER_ONLY,
}

/** Whether a thing is drawn at all, and whether it answers. */
sealed interface ManagedGate {
    /** The device's build does not have it: nothing is drawn. */
    data object Absent : ManagedGate
    data object Open : ManagedGate
    /** It is there and not for this caller now: drawn disabled, with [why]. */
    data class Closed(val why: ManagedDenial) : ManagedGate

    val offered: Boolean get() = this != Absent
    val open: Boolean get() = this == Open
    val denial: ManagedDenial? get() = (this as? Closed)?.why
}

/**
 * What a device announces and what it lets this caller do, read from one status.
 *
 * A device without `caps` is the first build: it has what [ManagedOp]s carry no capability
 * for, and nothing else. A device that announces a capability and not the op in `you.can`
 * gets the thing drawn disabled — because the device's files are closed to this caller, when
 * the op is one of the files' and the caller is an owner; because Full control is off there,
 * when the op takes Full control and the caller is an owner; because the caller is not an owner
 * otherwise. A guest is told "only an owner" even of what also takes Full control: switching
 * that on at the device would not let a guest in.
 */
class ManagedAccess(
    private val caps: Set<String>?,
    private val can: Set<String>?,
    val owner: Boolean,
    val level: String,
    /** The device says its files are closed to this caller (`status.files`). */
    val filesClosed: Boolean = false,
) {
    constructor(status: ManagedStatus) : this(
        caps = status.caps?.toSet(),
        // The list means something only from a build that says it sends one.
        can = status.you.can?.toSet()?.takeIf { status.caps?.contains(ManagedCaps.CAN) == true },
        owner = status.you.owner,
        level = if (status.caps?.contains(ManagedCaps.CAN) == true) status.level else "",
        filesClosed = status.caps?.contains(ManagedCaps.FILES) == true && status.files == ManagedFiles.CLOSED,
    )

    /** The first build: no capabilities announced at all. */
    val firstBuild: Boolean get() = caps == null

    /** Full control is on. False where the device does not tell its level. */
    val full: Boolean get() = level == ManagedLevel.FULL

    /** The device tells its level: [ManagedLevel.BASIC] or [ManagedLevel.FULL]. */
    val levelKnown: Boolean get() = level == ManagedLevel.BASIC || level == ManagedLevel.FULL

    fun has(cap: String): Boolean = caps?.contains(cap) == true

    fun gate(op: ManagedOp): ManagedGate {
        if (op.cap != null && !has(op.cap)) return ManagedGate.Absent
        // What only a guest does is nothing to draw for an owner.
        if (op.guestOnly && owner) return ManagedGate.Absent
        val allowed = can?.contains(op.word)
            // No list: the class decides, as on the first build, and the level where it is told.
            ?: ((owner || !op.owner) && !(op.full && level == ManagedLevel.BASIC) && !(op.files && filesClosed))
        return if (allowed) ManagedGate.Open else ManagedGate.Closed(denial(op))
    }

    private fun denial(op: ManagedOp): ManagedDenial = when {
        // The files first: they close what Full control would open.
        owner && op.files && filesClosed -> ManagedDenial.FILES_CLOSED
        owner && op.full && level == ManagedLevel.BASIC -> ManagedDenial.NEEDS_FULL
        else -> ManagedDenial.OWNER_ONLY
    }

    /** Whether a change of [setting] is sent with `"guard": true`: only to a device that knows the field. */
    fun asksGuard(setting: ManagedSetting): Boolean = has(ManagedCaps.GUARD) && setting.guard == ManagedSetting.GUARD_OPTIONAL

    /** The exit node is always guarded where the device can guard. */
    val guardsExitNode: Boolean get() = has(ManagedCaps.GUARD)

    companion object {
        /** Before the device has answered: nothing announced, nothing allowed beyond looking. */
        val NONE = ManagedAccess(caps = null, can = null, owner = false, level = "")
    }
}

/** Why a switch cannot be moved from here; one sentence each. */
enum class ManagedLock {
    /** It answers. */
    NONE,
    /** The app there could not read it. */
    UNKNOWN,
    /** The caller is a guest. */
    OWNER_ONLY,
    /** Not changeable from afar at all. */
    READ_ONLY,
    /** Part of the device's files, which are closed to this caller: neither way until they are opened there. */
    FILES_CLOSED,
    /** Turning it on takes Full control, which is switched on at the device. */
    NEEDS_FULL,
    /** It is on, and can only be turned off at the device. */
    OFF_AT_DEVICE,
    /** It is off, and can only be turned on at the device. */
    ON_AT_DEVICE,
}

/** Whether this caller can move [this] switch now, and if not, why (contract §6.1). */
fun ManagedSetting.lock(owner: Boolean): ManagedLock = when {
    error.isNotEmpty() -> ManagedLock.UNKNOWN
    !writable -> when {
        !owner -> ManagedLock.OWNER_ONLY
        needs == ManagedSetting.NEEDS_FILES -> ManagedLock.FILES_CLOSED
        else -> ManagedLock.READ_ONLY
    }
    only != null && value == only -> when {
        needs == ManagedLevel.FULL -> ManagedLock.NEEDS_FULL
        only == "true" -> ManagedLock.OFF_AT_DEVICE
        else -> ManagedLock.ON_AT_DEVICE
    }
    else -> ManagedLock.NONE
}

/**
 * A list of subnets that may be cleared from here and not written: the device is at the
 * basic level, where offering subnets takes Full control (contract §6.1).
 */
val ManagedSetting.clearOnly: Boolean get() = kind == ManagedSetting.CIDRS && only == null && needs == ManagedLevel.FULL

/**
 * The switches of [keys] a device has, in that order: rows of the kind "bool" whose key this
 * build knows. The first build is shown its three and nothing a later device might add.
 */
fun List<ManagedSetting>.switches(keys: List<String>, access: ManagedAccess): List<ManagedSetting> {
    val wanted = if (access.firstBuild) keys.filter { it in ManagedKeys.FIRST_BUILD } else keys
    return wanted.mapNotNull { key -> firstOrNull { it.key == key && it.kind == ManagedSetting.BOOL } }
}

fun List<ManagedSetting>.setting(key: String, kind: String): ManagedSetting? = firstOrNull { it.key == key && it.kind == kind }

/** Whether [mine] is an older release than [theirs]; false when either cannot be read as one. */
fun isOlderAppVersion(mine: String, theirs: String): Boolean {
    fun parts(v: String): List<Int>? = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
    val a = parts(mine) ?: return false
    val b = parts(theirs) ?: return false
    if (a.isEmpty() || b.isEmpty()) return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

/** A Taildrive share's name as the device takes it: 1–40 of letters, digits and the underscore. */
fun isShareName(name: String): Boolean =
    name.length in 1..40 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }

/** A share name made of a folder's: what is not allowed becomes an underscore; "share" for nothing. */
fun shareNameOf(folder: String): String =
    folder.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_') it else '_' }
        .joinToString("").trim('_').take(40).ifEmpty { "share" }

/** [text] as the comma-separated prefixes `advertise_routes` takes, or null when one of them is not a prefix. */
fun cidrList(text: String): String? {
    val parts = text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size > 16) return null
    for (p in parts) {
        val bits = p.substringAfter('/', "").toIntOrNull() ?: return null
        val addr = p.substringBefore('/')
        val v6 = ':' in addr
        if (bits < 0 || bits > (if (v6) 128 else 32)) return null
        // The default routes are the exit node's switch, not a subnet.
        if (bits == 0) return null
        val shaped = if (v6) addr.all { it.isLetterOrDigit() || it == ':' } && addr.length >= 2
        else addr.split('.').let { o -> o.size == 4 && o.all { (it.toIntOrNull() ?: -1) in 0..255 } }
        if (!shaped) return null
    }
    return parts.joinToString(",")
}
