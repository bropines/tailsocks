package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.StatFs
import io.github.bropines.tailscaled.BuildConfig
import java.io.File

/**
 * The app as [RemoteDeviceDelegate] needs it: every setting where Settings itself keeps it and
 * applied the way it applies it, every list read the way the app's own screens read it. What
 * this refuses, it refuses in a sentence written for the person holding the other device.
 *
 * Preferences of three kinds meet here: app-wide ones (GlobalSettings), the active profile's
 * (`appctr_<id>`, where Taildrive and the offered routes live), and the files of
 * [RemoteLevel]. A change that the daemon has to hear of goes through the service's own
 * "apply settings", as a change made on the Settings screen does.
 */
internal class AppRemoteDeviceHost(context: Context) : RemoteDeviceHost {
    private val context = context.applicationContext

    private fun profilePrefs(): SharedPreferences =
        context.getSharedPreferences("appctr_${AccountManager.getActiveAccount(context).id}", Context.MODE_PRIVATE)

    private fun apply() = TailscaledService.requestApplySettings(context)

    private fun storageGranted() = FileBrowse.storageAccess(context) == StorageAccess.GRANTED

    override fun switchOn(key: String): Boolean = when (key) {
        RemoteDeviceKeys.TUN_MODE -> GlobalSettings.isTunModeEnabled(context)
        RemoteDeviceKeys.AUTO_RECONNECT -> GlobalSettings.isAutoReconnectEnabled(context)
        RemoteDeviceKeys.AUTO_START -> GlobalSettings.isAutoStartEnabled(context)
        RemoteDeviceKeys.KEEP_AWAKE -> GlobalSettings.getBoolean(context, KEEP_AWAKE_PREF, false)
        // What the service itself assumes of a profile that has never been asked.
        RemoteDeviceKeys.TAILDRIVE -> profilePrefs().getBoolean(TAILDRIVE_PREF, true)
        RemoteDeviceKeys.TAILDRIVE_PROXY -> profilePrefs().getBoolean(TAILDRIVE_PROXY_PREF, false)
        RemoteDeviceKeys.ADVERTISE_EXIT_NODE -> profilePrefs().getBoolean(EXIT_NODE_PREF, false)
        RemoteDeviceKeys.FULL_CONTROL -> RemoteLevel.isFullControl(context)
        else -> throw RemoteRefusal("not a remote setting")
    }

    override fun setSwitch(key: String, on: Boolean) {
        when (key) {
            RemoteDeviceKeys.TUN_MODE -> {
                if (GlobalSettings.isRootModeEnabled(context)) throw RemoteRefusal("Root Mode is on; the mode is changed on the device")
                // The consent is a dialog only a person at the device can answer, and the
                // tunnel does not start without it.
                if (on && runCatching { VpnService.prepare(context) }.getOrNull() != null) {
                    throw RemoteRefusal("TUN mode needs the VPN consent, which is given on the device")
                }
                GlobalSettings.setTunModeEnabled(context, on)
                apply()
            }
            RemoteDeviceKeys.AUTO_RECONNECT -> GlobalSettings.setAutoReconnectEnabled(context, on)
            RemoteDeviceKeys.AUTO_START -> GlobalSettings.setAutoStartEnabled(context, on)
            RemoteDeviceKeys.KEEP_AWAKE -> GlobalSettings.setBoolean(context, KEEP_AWAKE_PREF, on)
            RemoteDeviceKeys.TAILDRIVE -> {
                // Shares are read by path: without the access they would be listed and empty.
                if (on && !storageGranted()) throw RemoteRefusal("sharing folders needs access to storage on the device first")
                profilePrefs().edit().putBoolean(TAILDRIVE_PREF, on).apply()
                apply()
            }
            RemoteDeviceKeys.TAILDRIVE_PROXY -> {
                profilePrefs().edit().putBoolean(TAILDRIVE_PROXY_PREF, on).apply()
                apply()
            }
            RemoteDeviceKeys.ADVERTISE_EXIT_NODE -> {
                profilePrefs().edit().putBoolean(EXIT_NODE_PREF, on).apply()
                apply()
            }
            RemoteDeviceKeys.FULL_CONTROL -> {
                if (on) throw RemoteRefusal("full control is switched on at the device")
                if (!RemoteLevel.setFullControl(context, false)) throw RemoteRefusal("the switch could not be saved on the device")
            }
            else -> throw RemoteRefusal("not a remote setting")
        }
    }

    override fun routes(): String = profilePrefs().getString(ROUTES_PREF, "").orEmpty()
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

    override fun setRoutes(routes: String) {
        profilePrefs().edit().putString(ROUTES_PREF, routes).apply()
        apply()
    }

    override fun exitNode(): String = profilePrefs().getString("exit_node_id", "").orEmpty()

    // ─── Received files ──────────────────────────────────────────────────────────────────────

    override fun folderDefault() {
        val folder = TaildropFolder.get(context)
        if (folder == null || (folder is ReceiveFolder.Dir && folder.isDefault)) throw RemoteRefusal("it is the default already")
        if (TaildropFolder.state(context, folder) == TaildropFolder.State.READY) {
            throw RemoteRefusal("the folder works; it is changed on the device")
        }
        TaildropFolder.reset(context)
        folderProbe = null
    }

    private fun inbox(): TaildropInbox.Snapshot =
        TaildropInbox.list(context, TaildropPaths.ensureDir(context, AccountManager.getActiveAccount(context).id))

    override fun saveReceived() {
        val inbox = inbox()
        val files = inbox.files ?: throw RemoteRefusal("the received files could not be listed")
        if (files.isEmpty()) throw RemoteRefusal("no received file is waiting")
        if (inbox.folder == null) throw RemoteRefusal("the device has no folder to save into")
        var saved = 0
        var failure: String? = null
        for (file in files) {
            when (val result = TaildropInbox.save(context, file)) {
                is TaildropSave.Result.Saved -> saved++
                is TaildropSave.Result.Failed -> failure = failure ?: result.reason
                TaildropSave.Result.NoFolder -> failure = failure ?: "no folder"
            }
        }
        if (saved == 0) throw RemoteRefusal("nothing could be saved: ${failure ?: "unknown"}")
    }

    /** Whether the folder can be written is found out by writing; not on every poll of a status. */
    private class FolderProbe(val key: String, val state: TaildropFolder.State, val at: Long)

    @Volatile
    private var folderProbe: FolderProbe? = null

    private fun folderState(folder: ReceiveFolder): TaildropFolder.State {
        val key = "$folder|${FileBrowse.storageAccess(context)}"
        val now = android.os.SystemClock.elapsedRealtime()
        folderProbe?.takeIf { it.key == key && now - it.at < FOLDER_PROBE_MS }?.let { return it.state }
        return TaildropFolder.state(context, folder).also { folderProbe = FolderProbe(key, it, now) }
    }

    /** Bytes free where a received file lands: the folder when it is a directory, else the inbox. */
    private fun freeFor(folder: ReceiveFolder?, inbox: File): Long {
        val dir = (folder as? ReceiveFolder.Dir)?.dir?.takeIf { it.isDirectory } ?: inbox
        return runCatching { StatFs(dir.path).availableBytes }.getOrDefault(-1)
    }

    // ─── The report ──────────────────────────────────────────────────────────────────────────

    /** A television has no battery to optimise: the permission's own screen leaves that row out there, by the same rule. */
    private fun permissionState(permission: AppPermission): PermState =
        if (permission == AppPermission.BATTERY &&
            !DeviceProfile.availability(Feature.BATTERY_OPTIMISATION_PROMPT, DeviceProfile.deviceClass(context), Capabilities.NONE).reachable
        ) PermState.NOT_APPLICABLE
        else runCatching { AppPermissions.state(context, permission) }.getOrDefault(PermState.UNKNOWN)

    /** Whether the device has a way to ask for a permission is asked of the package manager; not with every status. */
    private val askable = HashMap<AppPermission, Pair<Long, Boolean>>()

    private fun canAsk(permission: AppPermission): Boolean = synchronized(askable) {
        val now = android.os.SystemClock.elapsedRealtime()
        askable[permission]?.takeIf { now - it.first < ASKABLE_MS }?.second
            ?: AppPermissions.canAsk(context, permission).also { askable[permission] = now to it }
    }

    override fun report(): RemoteDeviceReport {
        val account = AccountManager.getActiveAccount(context)
        val inboxDir = TaildropPaths.ensureDir(context, account.id)
        // The inbox as TaildropInbox.list reads it, without its probe of the folder: this is
        // read with every status a paired device polls, and that probe writes a file.
        val history = TaildropHistory.read(context)
        val waiting = runCatching {
            val json = if (BuildConfig.IS_DEV) appctr.Appctr.getTaildropFilesFromAPI() else appctr.Appctr.getWaitingFiles(inboxDir.absolutePath)
            TaildropInbox.withReceivedTimes(TaildropInbox.decodeFiles(json), history)
        }.getOrNull()
        val folder = TaildropFolder.get(context)
        val prefs = profilePrefs()
        val root = GlobalSettings.isRootModeEnabled(context)
        val tun = GlobalSettings.isTunModeEnabled(context)
        return RemoteDeviceReport(
            permissions = AppPermission.entries.map { permission ->
                RemoteDeviceReports.permission(permission, permissionState(permission)) { canAsk(permission) }
            },
            asked = RemoteDevicePrompts.asked(context),
            receive = RemoteDeviceReceive.of(
                label = folder?.let { TaildropFolder.quickLabel(it) ?: runCatching { TaildropFolder.label(context, it) }.getOrNull() },
                state = folder?.let(::folderState),
                isDefault = folder == null || (folder is ReceiveFolder.Dir && folder.isDefault),
                free = freeFor(folder, inboxDir),
                waiting = waiting?.size ?: 0,
            ),
            saved = RemoteDeviceReports.saved(waiting.orEmpty(), history),
            folders = RemoteShares.parse(prefs.getString(SHARES_PREF, "[]")),
            device = RemoteDeviceFacts(
                model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                android = Build.VERSION.RELEASE.orEmpty(),
                sdk = Build.VERSION.SDK_INT,
                build = BuildConfig.GIT_HASH,
                mode = RemoteDeviceReports.mode(root, tun),
                engine = if (tun && !root) GlobalSettings.getTunEngine(context) else "",
            ),
        )
    }

    // ─── On the screen ───────────────────────────────────────────────────────────────────────

    override fun askPermission(permission: AppPermission, from: String): RemoteAskRoute {
        when (permissionState(permission)) {
            PermState.GRANTED -> throw RemoteRefusal("already granted")
            PermState.NOT_APPLICABLE -> throw RemoteRefusal("not needed on this device")
            else -> Unit
        }
        // Asked afresh, not from the report's memory: a page may have appeared since.
        if (!AppPermissions.canAsk(context, permission)) {
            throw RemoteRefusal("this device has no settings page for it; it can only be granted with adb")
        }
        return RemoteDevicePrompts.ask(context, permission, from)
    }

    override fun identify(from: String): RemoteAskRoute = RemoteDevicePrompts.identify(context, from)

    override fun notice(from: String, op: String, detail: String) = RemoteDevicePrompts.notice(context, from, op, detail)

    override fun elevateAsk(nodeId: String, from: String, seconds: Long): RemoteAskRoute {
        if (!RemoteElevate.isAllowed(context)) throw RemoteRefusal("this device does not give guests full rights")
        return RemoteDevicePrompts.elevateAsk(context, nodeId, from, seconds)
    }

    override fun elevations(list: List<RemoteElevation>) = RemoteElevationNotice.show(context, list)

    // ─── The storage by name, shares, diagnostics ────────────────────────────────────────────

    private fun source(): FileSource = RealFileSource(context)

    override fun storageList(path: String, offset: Int): RemoteStorageListing {
        val source = source()
        if (path.isNotEmpty()) return RemoteStorage.list(source, path, offset)
        return RemoteStorage.roots(
            source,
            // A volume's own name, as the system gives it; the device's own storage in plain words.
            name = { root -> root.label ?: if (root.kind == RootKind.PRIMARY) "Internal storage" else root.dir.name },
            space = { dir -> runCatching { StatFs(dir.path).let { it.availableBytes to it.totalBytes } }.getOrDefault(0L to 0L) },
        )
    }

    override fun storageFile(path: String): RemoteStorageFile = RemoteStorage.file(source(), path)

    override fun shareAdd(name: String, path: String) {
        if (!storageGranted()) throw RemoteRefusal("sharing a folder needs access to storage on the device first")
        val source = source()
        val dir = RemoteStorage.resolve(source, path)
        if (source.describe(dir)?.isDirectory != true) throw RemoteRefusal("that is not a folder on the device")
        val prefs = profilePrefs()
        val shares = RemoteShares.add(RemoteShares.parse(prefs.getString(SHARES_PREF, "[]")), name, dir.path)
        prefs.edit().putString(SHARES_PREF, RemoteShares.encode(shares)).apply()
        apply()
    }

    override fun shareRemove(name: String) {
        val prefs = profilePrefs()
        val shares = RemoteShares.remove(RemoteShares.parse(prefs.getString(SHARES_PREF, "[]")), name)
        prefs.edit().putString(SHARES_PREF, RemoteShares.encode(shares)).apply()
        apply()
    }

    override fun diagReport(): String = Diagnostics.report(context)

    private companion object {
        /** "Keep the connection awake": the wake lock, and what brings the service back after a reboot (BootReceiver). */
        const val KEEP_AWAKE_PREF = "force_bg"
        const val TAILDRIVE_PREF = "taildrive_enabled"
        const val TAILDRIVE_PROXY_PREF = "taildrive_proxy_enabled"
        const val SHARES_PREF = "taildrive_shares"
        const val EXIT_NODE_PREF = "advertise_exit_node"
        const val ROUTES_PREF = "advertise_routes"
        const val FOLDER_PROBE_MS = 30_000L
        const val ASKABLE_MS = 60_000L
    }
}

/** The bridge's delegate for a managed device: everything of [RemoteDeviceDelegate], over the three switches of [base]. */
internal fun remoteDeviceDelegate(context: Context, base: appctr.RemoteDelegate): appctr.RemoteDelegate =
    RemoteDeviceDelegate(base, AppRemoteDeviceHost(context))
