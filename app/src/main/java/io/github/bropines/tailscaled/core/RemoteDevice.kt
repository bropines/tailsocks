package io.github.bropines.tailscaled.core

import android.os.Build
import android.util.Log
import appctr.RemoteDelegate
import io.github.bropines.tailscaled.models.TaildropDirection
import io.github.bropines.tailscaled.models.TaildropFile
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/*
 * Remote management, the managed device's half beyond the three switches of
 * core/RemoteManage.kt: everything the bridge asks of the app once a paired device is let do
 * more than look (appctr/remote_policy.go has the lists, appctr/remote.go the reasons).
 *
 * What is in this file runs in a plain JVM test: the names shared with the bridge, the
 * documents that cross it, the delegate that turns the bridge's calls into [RemoteDeviceHost]
 * calls and refuses what it does not know, and the rules that need no Android — which path of
 * the storage may be named, what a share may be called, how a permission's state is said.
 * The Android half is core/RemoteDeviceHost.kt; what it puts on the screen,
 * core/RemoteDevicePrompts.kt.
 *
 * The bridge filters first, by its own lists; this refuses again, by these. A test on each
 * side holds both to one file, appctr/testdata/remote_allowlist.json, so neither list can grow
 * without the other.
 */

/** The names of settings, actions and requests this half knows, as the bridge spells them. */
object RemoteDeviceKeys {
    const val TUN_MODE = "tun_mode"
    const val AUTO_RECONNECT = "auto_reconnect"
    const val AUTO_START = "auto_start"
    const val KEEP_AWAKE = "keep_awake"
    const val TAILDRIVE = "taildrive_enabled"
    const val TAILDRIVE_PROXY = "taildrive_proxy_enabled"
    const val ADVERTISE_EXIT_NODE = "advertise_exit_node"
    const val ADVERTISE_ROUTES = "advertise_routes"
    const val FULL_CONTROL = "full_control"

    /** The switches read and written here; the three of [RemoteKeys.SWITCHES] stay with RemoteSettingsDelegate. */
    val SWITCHES = listOf(TUN_MODE, AUTO_RECONNECT, AUTO_START, KEEP_AWAKE, TAILDRIVE, TAILDRIVE_PROXY, ADVERTISE_EXIT_NODE, FULL_CONTROL)

    /** Appctr.RemoteKeyExitNode. Written through RemoteSettingsDelegate; read here, for a guarded change. */
    const val EXIT_NODE = "exit_node"

    const val ACTION_FOLDER_DEFAULT = "receive_folder_default"
    const val ACTION_SAVE_RECEIVED = "save_received"

    /** The actions carried out here; "reconnect" stays with RemoteSettingsDelegate, the relays are the bridge's own. */
    val ACTIONS = listOf(ACTION_FOLDER_DEFAULT, ACTION_SAVE_RECEIVED)

    const val REQUEST_REPORT = "report"
    const val REQUEST_ASK = "ask_permission"
    const val REQUEST_IDENTIFY = "identify"
    const val REQUEST_NOTICE = "notice"
    const val REQUEST_STORAGE_LIST = "storage_list"
    const val REQUEST_STORAGE_FILE = "storage_file"
    const val REQUEST_SHARE_ADD = "share_add"
    const val REQUEST_SHARE_REMOVE = "share_remove"
    const val REQUEST_DIAG_REPORT = "diag_report"

    val REQUESTS = listOf(
        REQUEST_REPORT, REQUEST_ASK, REQUEST_IDENTIFY, REQUEST_NOTICE, REQUEST_STORAGE_LIST, REQUEST_STORAGE_FILE,
        REQUEST_SHARE_ADD, REQUEST_SHARE_REMOVE, REQUEST_DIAG_REPORT,
    )

    /** Two refusals that are the API's own words rather than a sentence: the bridge answers 404 and 403 for them. */
    const val NOT_FOUND = "not_found"
    const val FORBIDDEN = "forbidden"
}

// ─── What the app reports of itself ──────────────────────────────────────────────────────────

/** One permission in the report: [state] is [PermState.wire], [ask] whether the device has a way to put it in front of a person. */
@Serializable
data class RemoteDevicePermission(val id: String = "", val state: String = "", val ask: Boolean = false)

/** How a permission's state is said to a paired device. */
val PermState.wire: String
    get() = when (this) {
        PermState.GRANTED -> "granted"
        PermState.DENIED -> "missing"
        PermState.UNKNOWN -> "unknown"
        PermState.NOT_APPLICABLE -> "na"
    }

/** A request for a permission that still waits for somebody at the device. */
@Serializable
data class RemoteDeviceAsked(val id: String = "", val result: String = "", val age: Long = 0)

/** Where received files go; [state] is one of the RECEIVE_ words below. */
@Serializable
data class RemoteDeviceReceive(
    val state: String = RECEIVE_INBOX,
    val folder: String = "",
    /** The folder does not work and is not this device's default: [RemoteDeviceKeys.ACTION_FOLDER_DEFAULT] would help. */
    val reset: Boolean = false,
    /** Bytes free where received files land; -1 when it cannot be told. */
    val free: Long = -1,
    /** How many received files wait inside the app. */
    val waiting: Int = 0,
) {
    companion object {
        const val RECEIVE_READY = "ready"
        const val RECEIVE_NEEDS_ACCESS = "needs_access"
        const val RECEIVE_UNAVAILABLE = "unavailable"
        const val RECEIVE_INBOX = "inbox"

        /** The folder as a paired device is told of it. [isDefault]: nobody chose it. Pure. */
        fun of(label: String?, state: TaildropFolder.State?, isDefault: Boolean, free: Long, waiting: Int): RemoteDeviceReceive {
            val word = when (state) {
                null -> RECEIVE_INBOX
                TaildropFolder.State.READY -> RECEIVE_READY
                TaildropFolder.State.NEEDS_ACCESS -> RECEIVE_NEEDS_ACCESS
                TaildropFolder.State.UNAVAILABLE -> RECEIVE_UNAVAILABLE
            }
            return RemoteDeviceReceive(
                state = word,
                folder = if (state == null) "" else label.orEmpty(),
                reset = !isDefault && (word == RECEIVE_NEEDS_ACCESS || word == RECEIVE_UNAVAILABLE),
                free = free,
                waiting = waiting,
            )
        }
    }
}

/** A received file the receive folder took. */
@Serializable
data class RemoteDeviceSaved(val name: String = "", val size: Long = 0, val time: Long = 0, val folder: String = "")

/** A folder shared with the tailnet. */
@Serializable
data class RemoteDeviceFolder(val name: String = "", val path: String = "")

/** What the device is. */
@Serializable
data class RemoteDeviceFacts(
    val model: String = "",
    val android: String = "",
    val sdk: Int = 0,
    val build: String = "",
    /** "proxy", "tun" or "root"; [engine] is the TUN engine, empty outside TUN mode. */
    val mode: String = "",
    val engine: String = "",
)

/** The whole report, as the bridge reads it (appctr/remote_app.go, remoteParseReport). */
@Serializable
data class RemoteDeviceReport(
    val permissions: List<RemoteDevicePermission> = emptyList(),
    val asked: RemoteDeviceAsked? = null,
    val receive: RemoteDeviceReceive = RemoteDeviceReceive(),
    val saved: List<RemoteDeviceSaved> = emptyList(),
    val folders: List<RemoteDeviceFolder> = emptyList(),
    val device: RemoteDeviceFacts? = null,
)

/** The pure parts of putting a report together. */
object RemoteDeviceReports {
    /**
     * A permission as the report says it. [canAsk] is asked only where there is something to
     * ask for: one that is held, or that this device does not have, is never askable.
     */
    fun permission(permission: AppPermission, state: PermState, canAsk: () -> Boolean): RemoteDevicePermission =
        RemoteDevicePermission(permission.id, state.wire, state.needsAction && canAsk())

    /**
     * The received files the folder took, newest first, as [TaildropInbox.savedEntries] finds
     * them: the history keeps "folder/name" in one string, and an arrival's time in
     * milliseconds.
     */
    fun saved(waiting: List<TaildropFile>, history: List<TaildropHistoryEntry>): List<RemoteDeviceSaved> =
        TaildropInbox.savedEntries(waiting, history).map { entry ->
            val where = entry.savedTo.orEmpty()
            RemoteDeviceSaved(
                name = where.substringAfterLast('/').ifEmpty { entry.name },
                size = entry.size ?: 0,
                time = entry.timestamp / 1000,
                folder = where.substringBeforeLast('/', ""),
            )
        }

    /** The mode the device runs in, as its own diagnostics say it. */
    fun mode(root: Boolean, tun: Boolean): String = if (root) "root" else if (tun) "tun" else "proxy"
}

// ─── A request for something to be shown at the device ───────────────────────────────────────

/** How a request from afar reaches a person at the device; [word] is what the bridge is answered. */
enum class RemoteAskRoute(val word: String) {
    /** The page, or the line, is in front of the person now. */
    SHOWN("shown"),

    /** Not on screen, and Android will not let it be put there: a notification and a line say so. */
    NOTIFIED("notified"),

    /** Not on screen, and nothing can be shown at all: it waits for someone to open TailSocks. */
    WAITING("waiting");

    companion object {
        /**
         * What Android lets an app do that wants to put a page in front of a person:
         *  - with one of its own screens up ([onScreen]) it may start an activity, on every Android;
         *  - before Android 10 (API 29) it may start one from the background as well — a
         *    foreground service is enough;
         *  - from Android 10 a start from the background is dropped without a word unless the
         *    app holds one of a few exemptions, none of which this one has or should ask for
         *    (drawing over other apps; a full-screen notification, which is for calls and
         *    alarms and which the manifest does not declare). A foreground service is not one.
         *    What is left is a notification — the person's own press starts the activity — and,
         *    where notifications are off ([canNotify] false), nothing but waiting for
         *    TailSocks to be opened: Android drops a background toast of such an app too.
         * A television is no different in this; it only shows its notifications less readily.
         */
        fun of(sdk: Int, onScreen: Boolean, canNotify: Boolean): RemoteAskRoute = when {
            onScreen || sdk < Build.VERSION_CODES.Q -> SHOWN
            canNotify -> NOTIFIED
            else -> WAITING
        }
    }
}

/** The same for "say whose hands you are in", which has nothing to wait for. */
fun RemoteAskRoute.identifyWord(): String = if (this == RemoteAskRoute.WAITING) "unseen" else word

// ─── The storage by name ─────────────────────────────────────────────────────────────────────

/** A volume: [kind] is one of the ROOT_ words, by which a manager names the device's own storage; [name] is the system's for the others. */
@Serializable
data class RemoteStorageRoot(val kind: String = ROOT_VOLUME, val name: String = "", val path: String = "", val free: Long = 0, val total: Long = 0) {
    companion object {
        const val ROOT_PRIMARY = "primary"
        const val ROOT_REMOVABLE = "removable"
        const val ROOT_VOLUME = "volume"

        /** What a volume of the in-app browser is to a paired device. */
        fun kindOf(root: BrowseRoot): String = when {
            root.kind == RootKind.PRIMARY -> ROOT_PRIMARY
            root.removable -> ROOT_REMOVABLE
            else -> ROOT_VOLUME
        }
    }
}

@Serializable
data class RemoteStorageEntry(val name: String = "", val dir: Boolean = false, val size: Long = 0, val time: Long = 0)

/** A listing as the bridge reads it: [roots] for the empty path, [entries] for a folder. */
@Serializable
data class RemoteStorageListing(
    val path: String = "",
    val parent: String = "",
    val roots: List<RemoteStorageRoot> = emptyList(),
    val entries: List<RemoteStorageEntry> = emptyList(),
    /** How many entries of the folder come before [entries]; [truncated]: more come after them. */
    val offset: Int = 0,
    val truncated: Boolean = false,
    /** The app has no access to storage, and sees only its own files here. */
    val limited: Boolean = false,
)

@Serializable
data class RemoteStorageFile(val path: String = "", val name: String = "", val size: Long = 0)

/**
 * Which part of the device's storage a paired device may name, and nothing else: the volumes
 * the in-app browser starts from ([FileSource.roots] — shared storage and removable drives),
 * never the app's own private directory, and never a path that leaves a volume by ".." or
 * through a link. A path is resolved on the disk first and judged afterwards, so what is
 * listed or sent is where the path really leads.
 */
object RemoteStorage {
    /** As many entries as one answer carries (the bridge's remoteMaxEntries). */
    const val MAX_ENTRIES = 500

    /** The volumes: the primary storage and the others. The Downloads shortcut is inside the first. */
    fun volumes(source: FileSource): List<BrowseRoot> =
        source.roots().filter { it.kind == RootKind.PRIMARY || it.kind == RootKind.VOLUME }

    /**
     * [path] as a place on a volume, with its links resolved; refused when it is not an
     * absolute, clean path or leads off the volumes.
     */
    fun resolve(source: FileSource, path: String): File {
        val asked = File(path)
        if (!asked.isAbsolute || FileBrowse.clean(asked).path != path) throw RemoteRefusal(RemoteDeviceKeys.FORBIDDEN)
        val real = FileBrowse.clean(source.canonical(asked))
        val inside = volumes(source).any { FileBrowse.isWithin(real, FileBrowse.clean(source.canonical(it.dir))) }
        if (!inside) throw RemoteRefusal(RemoteDeviceKeys.FORBIDDEN)
        return real
    }

    /** The volumes for the empty path; [space] gives a volume's free and total bytes. */
    fun roots(source: FileSource, name: (BrowseRoot) -> String, space: (File) -> Pair<Long, Long>): RemoteStorageListing =
        RemoteStorageListing(
            roots = volumes(source).map { root ->
                val (free, total) = space(root.dir)
                RemoteStorageRoot(RemoteStorageRoot.kindOf(root), name(root), FileBrowse.clean(root.dir).path, free, total)
            },
            limited = source.needsAllFilesAccess,
        )

    /**
     * What the folder at [path] holds: names, sizes and dates, nothing hidden, in the order of
     * the app's own browser. At most [MAX_ENTRIES] of them, from the one at [offset]: a long
     * folder is read in parts.
     */
    fun list(source: FileSource, path: String, offset: Int = 0): RemoteStorageListing {
        val from = offset.coerceAtLeast(0)
        val dir = resolve(source, path)
        return when (val listing = source.list(dir, showHidden = false)) {
            is Listing.Unreadable -> throw RemoteRefusal(
                when (listing.reason) {
                    Listing.Reason.NOT_FOUND -> RemoteDeviceKeys.NOT_FOUND
                    Listing.Reason.NO_ACCESS -> "the app has no access to that folder; it needs access to storage"
                    Listing.Reason.DENIED -> "the device does not let the app read that folder"
                }
            )
            is Listing.Entries -> {
                val volumes = volumes(source)
                val top = volumes.any { FileBrowse.same(FileBrowse.clean(source.canonical(it.dir)), dir) }
                RemoteStorageListing(
                    path = dir.path,
                    parent = if (top) "" else FileBrowse.parentOf(dir)?.path.orEmpty(),
                    entries = listing.entries.drop(from).take(MAX_ENTRIES).map {
                        RemoteStorageEntry(it.name, it.isDirectory, if (it.isDirectory) 0 else it.size, it.modified / 1000)
                    },
                    offset = from,
                    truncated = listing.entries.size > from + MAX_ENTRIES,
                    limited = listing.limited,
                )
            }
        }
    }

    /** The file at [path], when it is one the device may send: on a volume, a regular file. */
    fun file(source: FileSource, path: String): RemoteStorageFile {
        val file = resolve(source, path)
        val entry = source.describe(file) ?: throw RemoteRefusal(RemoteDeviceKeys.NOT_FOUND)
        if (entry.isDirectory) throw RemoteRefusal("that is a folder, not a file")
        return RemoteStorageFile(file.path, file.name, entry.size)
    }
}

// ─── Shared folders ──────────────────────────────────────────────────────────────────────────

/**
 * The list of shared folders as the app stores it (the profile's `taildrive_shares`, a JSON
 * array of name and path) and the two changes a paired device may ask for. The rules are the
 * Taildrive page's: a name is letters, digits and the underscore, and Taildrive takes `Music`
 * and `music` for one.
 */
object RemoteShares {
    private val NAME = Regex("^[A-Za-z0-9_]{1,40}$")

    fun parse(json: String?): List<RemoteDeviceFolder> =
        if (json.isNullOrBlank()) emptyList()
        else runCatching { AppJson.decodeFromString<List<RemoteDeviceFolder>>(json) }.getOrDefault(emptyList())
            .filter { it.name.isNotEmpty() && it.path.isNotEmpty() }

    fun encode(shares: List<RemoteDeviceFolder>): String = AppJson.encodeToString(shares)

    /** [shares] with one more; refused when the name is not one, is taken, or the folder is shared already. */
    fun add(shares: List<RemoteDeviceFolder>, name: String, path: String): List<RemoteDeviceFolder> {
        if (!NAME.matches(name)) throw RemoteRefusal("not a name a share can have")
        if (shares.any { it.name.equals(name, ignoreCase = true) }) throw RemoteRefusal("a share of that name exists")
        if (shares.any { FileBrowse.same(File(it.path), File(path)) }) throw RemoteRefusal("that folder is shared already")
        return shares + RemoteDeviceFolder(name, path)
    }

    /** [shares] without the one called [name]; "not_found" when there is none. */
    fun remove(shares: List<RemoteDeviceFolder>, name: String): List<RemoteDeviceFolder> {
        if (shares.none { it.name.equals(name, ignoreCase = true) }) throw RemoteRefusal(RemoteDeviceKeys.NOT_FOUND)
        return shares.filterNot { it.name.equals(name, ignoreCase = true) }
    }
}

// ─── The delegate ────────────────────────────────────────────────────────────────────────────

/** The app as [RemoteDeviceDelegate] needs it. Every change throws [RemoteRefusal] when it cannot be made. */
interface RemoteDeviceHost {
    /** One of [RemoteDeviceKeys.SWITCHES]. */
    fun switchOn(key: String): Boolean
    fun setSwitch(key: String, on: Boolean)

    /** The routes the device offers, prefixes separated by commas; the bridge has checked what is written. */
    fun routes(): String
    fun setRoutes(routes: String)

    /** The StableNodeID of the exit node the app has stored, empty for none. */
    fun exitNode(): String

    fun folderDefault()
    fun saveReceived()
    fun report(): RemoteDeviceReport

    /** Brings [permission]'s page in front of whoever is at the device, as far as Android lets it. */
    fun askPermission(permission: AppPermission, from: String): RemoteAskRoute

    /** Says "managed from [from]" on the screen. */
    fun identify(from: String): RemoteAskRoute

    /** A paired device changed something: [op] and [detail] are the line of the log. */
    fun notice(from: String, op: String, detail: String)

    /** The volumes for an empty [path]; else the folder there, from its entry number [offset]. */
    fun storageList(path: String, offset: Int): RemoteStorageListing
    fun storageFile(path: String): RemoteStorageFile
    fun shareAdd(name: String, path: String)
    fun shareRemove(name: String)
    fun diagReport(): String
}

@Serializable
private data class RemoteRequestArgs(
    val permission: String = "",
    val from: String = "",
    val op: String = "",
    val detail: String = "",
    val path: String = "",
    val name: String = "",
    val offset: Int = 0,
)

/**
 * The bridge's way into everything of this file. What it does not know itself goes to [base]
 * — RemoteSettingsDelegate, with the three switches, the exit node, the reconnect and the two
 * facts — and what neither knows is refused. Called on a thread of the bridge's, one call at
 * a time.
 */
class RemoteDeviceDelegate(private val base: RemoteDelegate, private val host: RemoteDeviceHost) : RemoteDelegate {

    override fun getSetting(key: String?): String = when (key) {
        in RemoteDeviceKeys.SWITCHES -> asked { host.switchOn(key!!).toString() }
        RemoteDeviceKeys.ADVERTISE_ROUTES -> asked { host.routes() }
        RemoteDeviceKeys.EXIT_NODE -> asked { host.exitNode() }
        else -> base.getSetting(key)
    }

    override fun setSetting(key: String?, value: String?) {
        when (key) {
            in RemoteDeviceKeys.SWITCHES -> {
                val on = when (value) {
                    "true" -> true
                    "false" -> false
                    else -> throw RemoteRefusal("not a value for this setting")
                }
                // The bridge refuses it first; said here as well, where it matters most.
                if (key == RemoteDeviceKeys.FULL_CONTROL && on) throw RemoteRefusal("full control is switched on at the device")
                asked { host.setSwitch(key!!, on) }
            }
            RemoteDeviceKeys.ADVERTISE_ROUTES -> {
                val routes = value.orEmpty()
                if (routes.any { it != '.' && it != ':' && it != '/' && it != ',' && !it.isLetterOrDigit() }) {
                    throw RemoteRefusal("not a list of routes")
                }
                asked { host.setRoutes(routes) }
            }
            else -> base.setSetting(key, value)
        }
    }

    override fun performAction(name: String?) {
        when (name) {
            RemoteDeviceKeys.ACTION_FOLDER_DEFAULT -> asked { host.folderDefault() }
            RemoteDeviceKeys.ACTION_SAVE_RECEIVED -> asked { host.saveReceived() }
            else -> base.performAction(name)
        }
    }

    override fun request(name: String?, args: String?): String {
        val a = if (args.isNullOrBlank()) RemoteRequestArgs()
        else runCatching { AppJson.decodeFromString<RemoteRequestArgs>(args) }.getOrNull() ?: throw RemoteRefusal("not a request")
        return when (name) {
            RemoteDeviceKeys.REQUEST_REPORT -> asked { AppJson.encodeToString(host.report()) }
            RemoteDeviceKeys.REQUEST_ASK -> {
                // The VPN consent is reported and never asked for from afar: it is what lets a tunnel start.
                val permission = AppPermission.of(a.permission)?.takeIf { it != AppPermission.VPN }
                    ?: throw RemoteRefusal("not a permission that can be asked for")
                asked { host.askPermission(permission, a.from).word }
            }
            RemoteDeviceKeys.REQUEST_IDENTIFY -> asked { host.identify(a.from).identifyWord() }
            RemoteDeviceKeys.REQUEST_NOTICE -> asked { host.notice(a.from, a.op, a.detail); "" }
            RemoteDeviceKeys.REQUEST_STORAGE_LIST -> asked { AppJson.encodeToString(host.storageList(a.path, a.offset)) }
            RemoteDeviceKeys.REQUEST_STORAGE_FILE -> asked { AppJson.encodeToString(host.storageFile(a.path)) }
            RemoteDeviceKeys.REQUEST_SHARE_ADD -> asked { host.shareAdd(a.name, a.path); "" }
            RemoteDeviceKeys.REQUEST_SHARE_REMOVE -> asked { host.shareRemove(a.name); "" }
            RemoteDeviceKeys.REQUEST_DIAG_REPORT -> asked { host.diagReport() }
            else -> throw RemoteRefusal("not a remote request")
        }
    }

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
        Log.w("RemoteDevice", "A remote request failed in the app: ${e.javaClass.simpleName}")
        throw RemoteRefusal("the app could not do it")
    }
}
