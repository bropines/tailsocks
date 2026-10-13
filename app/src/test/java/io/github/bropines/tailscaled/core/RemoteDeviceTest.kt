package io.github.bropines.tailscaled.core

import appctr.RemoteDelegate
import io.github.bropines.tailscaled.models.TaildropDirection
import io.github.bropines.tailscaled.models.TaildropFile
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The managed device's half of remote management beyond the three switches: the delegate's own
 * lists and what it hands the app, the documents it gives the bridge, and the rules that need
 * no Android — a permission's state as it is said, what Android lets a request do, which
 * path of the storage may be named, what a share may be called.
 */
class RemoteDeviceTest {

    // ─── The delegate ────────────────────────────────────────────────────────────────────────

    /** The three switches' delegate, as far as this one's fallthrough needs it. */
    private class FakeBase : RemoteDelegate {
        val calls = mutableListOf<String>()
        override fun getSetting(key: String?): String {
            if (key != "accept_dns") throw RemoteRefusal("not a remote setting")
            calls += "base get $key"
            return "true"
        }
        override fun setSetting(key: String?, value: String?) {
            if (key != "accept_dns" && key != "exit_node") throw RemoteRefusal("not a remote setting")
            calls += "base set $key=$value"
        }
        override fun performAction(name: String?) {
            if (name != "reconnect") throw RemoteRefusal("not a remote action")
            calls += "base action $name"
        }
        override fun request(name: String?, args: String?): String = throw RemoteRefusal("not a remote request")
    }

    /** An app that records what was asked of it. */
    private class FakeHost : RemoteDeviceHost {
        val switches = RemoteDeviceKeys.SWITCHES.associateWith { false }.toMutableMap()
        val calls = mutableListOf<String>()
        var offered = "192.168.5.0/24"
        var refuse: String? = null
        var route = RemoteAskRoute.SHOWN

        private fun note(call: String) {
            refuse?.let { throw RemoteRefusal(it) }
            calls += call
        }
        override fun switchOn(key: String) = switches.getValue(key)
        override fun setSwitch(key: String, on: Boolean) { note("set $key=$on"); switches[key] = on }
        override fun routes() = offered
        override fun setRoutes(routes: String) { note("routes '$routes'"); offered = routes }
        override fun exitNode() = "nFRAexitCNTRL"
        override fun folderDefault() = note("folder default")
        override fun saveReceived() = note("save received")
        override fun report() = RemoteDeviceReport(
            permissions = listOf(RemoteDevicePermission("storage", "missing", true)),
            receive = RemoteDeviceReceive("ready", "Download/Taildrop", free = 10, waiting = 1),
            saved = listOf(RemoteDeviceSaved("a.mp4", 5, 1_791_727_380, "Download/Taildrop")),
            folders = listOf(RemoteDeviceFolder("Downloads", "/storage/emulated/0/Download")),
            device = RemoteDeviceFacts("Xiaomi MiTV", "11", 30, "0e629420", "proxy", ""),
        )
        override fun askPermission(permission: AppPermission, from: String): RemoteAskRoute { note("ask ${permission.id} from $from"); return route }
        override fun identify(from: String): RemoteAskRoute { note("identify $from"); return route }
        override fun notice(from: String, op: String, detail: String) = note("notice $from $op $detail")
        override fun storageList(path: String, offset: Int): RemoteStorageListing {
            note("list '$path' from $offset")
            return RemoteStorageListing(path = path, entries = listOf(RemoteStorageEntry("film.mkv", false, 7, 9)), offset = offset)
        }
        override fun storageFile(path: String): RemoteStorageFile { note("file '$path'"); return RemoteStorageFile(path, File(path).name, 7) }
        override fun shareAdd(name: String, path: String) = note("share $name=$path")
        override fun shareRemove(name: String) = note("unshare $name")
        override fun diagReport(): String { note("diag"); return "--- TAILSOCKS DIAGNOSTICS ---" }
    }

    private val base = FakeBase()
    private val host = FakeHost()
    private val d = RemoteDeviceDelegate(base, host)

    private fun refused(block: () -> Unit): String {
        try {
            block()
        } catch (e: RemoteRefusal) {
            return e.message.orEmpty()
        }
        fail("not refused")
        return ""
    }

    private fun json(text: String): JsonObject = AppJson.parseToJsonElement(text).jsonObject

    @Test
    fun theDelegateReadsAndWritesItsOwnSwitchesAndPassesTheRestOn() {
        for (key in RemoteDeviceKeys.SWITCHES) assertEquals(key, "false", d.getSetting(key))
        assertEquals("192.168.5.0/24", d.getSetting("advertise_routes"))
        // The exit node is read here — for a guarded change — and written by the three switches' delegate.
        assertEquals("nFRAexitCNTRL", d.getSetting("exit_node"))
        assertEquals("true", d.getSetting("accept_dns"))

        d.setSetting("tun_mode", "true")
        d.setSetting("auto_reconnect", "true")
        d.setSetting("taildrive_enabled", "false")
        d.setSetting("advertise_routes", "10.0.0.0/8,fd7a:115c:a1e0::/48")
        d.setSetting("advertise_routes", "")
        d.setSetting("accept_dns", "false")
        d.setSetting("exit_node", "nFRAexitCNTRL")
        d.performAction("receive_folder_default")
        d.performAction("save_received")
        d.performAction("reconnect")
        assertEquals(
            listOf("set tun_mode=true", "set auto_reconnect=true", "set taildrive_enabled=false", "routes '10.0.0.0/8,fd7a:115c:a1e0::/48'", "routes ''", "folder default", "save received"),
            host.calls
        )
        assertEquals(listOf("base get accept_dns", "base set accept_dns=false", "base set exit_node=nFRAexitCNTRL", "base action reconnect"), base.calls)
    }

    @Test
    fun theDelegateRefusesEverythingElseItself() {
        // The bridge filters first; these lists must hold without it.
        val never = listOf(
            "socks5_pass", "taildrive_proxy_password", "taildrive_proxy_ip", "taildrive_shares", "extra_args_raw", "authkey",
            "login_server", "root_mode_enabled", "tun_mode_enabled", "force_bg", "autostart", "auto_reconnect_attempts", "remote_management",
            "TUN_MODE", "tun_mode ", "report", "ask_permission", "", null,
        )
        for (key in never) {
            assertTrue("$key", refused { d.getSetting(key) }.isNotEmpty())
            assertTrue("$key", refused { d.setSetting(key, "true") }.isNotEmpty())
        }
        for (value in listOf("1", "TRUE", "yes", "", null)) refused { d.setSetting("tun_mode", value) }
        // Full control is given up from afar and never taken, whatever the bridge let through.
        assertEquals("full control is switched on at the device", refused { d.setSetting("full_control", "true") })
        d.setSetting("full_control", "false")
        // Routes are prefixes and commas, nothing to splice into anything.
        for (bad in listOf("10.0.0.0/8 --advertise-exit-node", "10.0.0.0/8;x", "\"10.0.0.0/8\"", "10.0.0.0/8\n")) refused { d.setSetting("advertise_routes", bad) }
        for (action in listOf("stop", "disconnect", "reset", "reconnect_relays", "RECEIVE_FOLDER_DEFAULT", "", null)) refused { d.performAction(action) }
        for (name in listOf("grant", "log", "diag_log", "storage_read", "REPORT", "", null)) refused { d.request(name, "") }
        refused { d.request("storage_list", "not json") }
        assertEquals(listOf("set full_control=false"), host.calls)
    }

    @Test
    fun theReportIsTheDocumentTheBridgeReads() {
        val report = json(d.request("report", ""))
        val permission = report["permissions"]!!.jsonArray.single().jsonObject
        assertEquals("storage", permission["id"]!!.jsonPrimitive.content)
        assertEquals("missing", permission["state"]!!.jsonPrimitive.content)
        assertTrue(permission["ask"]!!.jsonPrimitive.boolean)
        val receive = report["receive"]!!.jsonObject
        assertEquals("ready", receive["state"]!!.jsonPrimitive.content)
        assertEquals("Download/Taildrop", receive["folder"]!!.jsonPrimitive.content)
        assertEquals("10", receive["free"]!!.jsonPrimitive.content)
        assertEquals("1", receive["waiting"]!!.jsonPrimitive.content)
        assertEquals("a.mp4", report["saved"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("/storage/emulated/0/Download", report["folders"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("proxy", report["device"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        // Nothing waits: the key is absent, not null.
        assertNull(report["asked"])
    }

    @Test
    fun requestsCarryTheirArgumentsAndAnswerInTheBridgesWords() {
        assertEquals("shown", d.request("ask_permission", """{"permission":"storage","from":"pixel-9-pro"}"""))
        host.route = RemoteAskRoute.NOTIFIED
        assertEquals("notified", d.request("ask_permission", """{"permission":"install","from":"pixel-9-pro"}"""))
        assertEquals("notified", d.request("identify", """{"from":"ann-iphone"}"""))
        host.route = RemoteAskRoute.WAITING
        assertEquals("waiting", d.request("ask_permission", """{"permission":"alarms","from":"pixel-9-pro"}"""))
        // Nothing waits for a person to see a name: it was seen or it was not.
        assertEquals("unseen", d.request("identify", """{"from":"ann-iphone"}"""))
        assertEquals("", d.request("notice", """{"from":"pixel-9-pro","op":"exit_node","detail":"exit-frankfurt"}"""))

        val listing = json(d.request("storage_list", """{"path":"/storage/emulated/0/Download","offset":500}"""))
        assertEquals("/storage/emulated/0/Download", listing["path"]!!.jsonPrimitive.content)
        assertEquals("500", listing["offset"]!!.jsonPrimitive.content)
        assertEquals("film.mkv", listing["entries"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("/storage/emulated/0/a b.txt", json(d.request("storage_file", """{"path":"/storage/emulated/0/a b.txt"}"""))["path"]!!.jsonPrimitive.content)
        assertEquals("", d.request("share_add", """{"name":"Movies","path":"/storage/emulated/0/Movies"}"""))
        assertEquals("", d.request("share_remove", """{"name":"Movies"}"""))
        assertEquals("--- TAILSOCKS DIAGNOSTICS ---", d.request("diag_report", ""))
        assertEquals(
            listOf(
                "ask storage from pixel-9-pro", "ask install from pixel-9-pro", "identify ann-iphone", "ask alarms from pixel-9-pro",
                "identify ann-iphone", "notice pixel-9-pro exit_node exit-frankfurt", "list '/storage/emulated/0/Download' from 500",
                "file '/storage/emulated/0/a b.txt'", "share Movies=/storage/emulated/0/Movies", "unshare Movies", "diag",
            ),
            host.calls
        )
    }

    @Test
    fun theVpnConsentAndWhatIsNoPermissionAreNeverAskedFor() {
        for (id in listOf("vpn", "camera", "", "STORAGE", "storage,install")) {
            assertEquals(id, "not a permission that can be asked for", refused { d.request("ask_permission", """{"permission":"$id","from":"x"}""") })
        }
        assertTrue(host.calls.isEmpty())
        // Every other permission of the app can be.
        for (p in AppPermission.entries) if (p != AppPermission.VPN) d.request("ask_permission", """{"permission":"${p.id}","from":"x"}""")
        assertEquals(AppPermission.entries.size - 1, host.calls.size)
    }

    @Test
    fun theAppsRefusalTravelsAndWhatItThrowsByAccidentDoesNot() {
        host.refuse = "the folder works; it is changed on the device"
        assertEquals("the folder works; it is changed on the device", refused { d.performAction("receive_folder_default") })
        assertEquals("the folder works; it is changed on the device", refused { d.setSetting("tun_mode", "true") })
        val broken = object : RemoteDeviceHost by FakeHost() {
            override fun switchOn(key: String): Boolean = throw IllegalStateException("/data/user/0/app/shared_prefs/appctr_default.xml")
            override fun report(): RemoteDeviceReport = throw SecurityException("token tskey-auth-secret")
            override fun storageList(path: String, offset: Int): RemoteStorageListing = throw java.io.IOException("/data/user/0/app/files/x")
            override fun diagReport(): String = throw RuntimeException("socks5 password hunter2")
        }
        val careless = RemoteDeviceDelegate(base, broken)
        for (said in listOf(
            refused { careless.getSetting("tun_mode") }, refused { careless.request("report", "") },
            refused { careless.request("storage_list", """{"path":""}""") }, refused { careless.request("diag_report", "") },
        )) assertEquals("the app could not do it", said)
    }

    // ─── Permissions as they are said ────────────────────────────────────────────────────────

    @Test
    fun aPermissionsStateIsSaidInFourWords() {
        assertEquals("granted", PermState.GRANTED.wire)
        assertEquals("missing", PermState.DENIED.wire)
        assertEquals("unknown", PermState.UNKNOWN.wire)
        assertEquals("na", PermState.NOT_APPLICABLE.wire)
        // Storage access of either kind missing is a permission that is missing.
        assertEquals(PermState.GRANTED, AppPermissions.storageState(StorageAccess.GRANTED))
        assertEquals(PermState.DENIED, AppPermissions.storageState(StorageAccess.NEEDS_ALL_FILES))
        assertEquals(PermState.DENIED, AppPermissions.storageState(StorageAccess.NEEDS_PERMISSION))
    }

    @Test
    fun theVpnConsentMattersOnlyWhereTheAppIsToOpenAVpn() {
        var asked = 0
        val prepared: (Boolean?) -> () -> Boolean? = { answer -> { asked++; answer } }
        // TUN mode off, or Root Mode: the system is not even asked.
        assertEquals(PermState.NOT_APPLICABLE, AppPermissions.vpnState(wanted = false, prepared = prepared(false)))
        assertEquals(0, asked)
        assertEquals(PermState.GRANTED, AppPermissions.vpnState(true, prepared(true)))
        // Another VPN app has taken the consent since, or it was never given.
        assertEquals(PermState.DENIED, AppPermissions.vpnState(true, prepared(false)))
        assertEquals(PermState.UNKNOWN, AppPermissions.vpnState(true, prepared(null)))
    }

    @Test
    fun aPermissionIsAskableOnlyWhileSomethingIsLeftToAskFor() {
        var asked = 0
        val page: () -> Boolean = { asked++; true }
        assertTrue(RemoteDeviceReports.permission(AppPermission.STORAGE, PermState.DENIED, page).ask)
        assertTrue(RemoteDeviceReports.permission(AppPermission.AUTOSTART, PermState.UNKNOWN, page).ask)
        assertEquals(2, asked)
        // Held, or absent on this device: the pages are not even looked for.
        assertFalse(RemoteDeviceReports.permission(AppPermission.NOTIFICATIONS, PermState.GRANTED, page).ask)
        assertFalse(RemoteDeviceReports.permission(AppPermission.BATTERY, PermState.NOT_APPLICABLE, page).ask)
        assertEquals(2, asked)
        // Missing, and the device has no page for it: a television without the all-files page.
        val none = RemoteDeviceReports.permission(AppPermission.STORAGE, PermState.DENIED) { false }
        assertEquals(RemoteDevicePermission("storage", "missing", false), none)
        assertTrue(AppPermissions.asksInDialog(AppPermission.STORAGE, sdk = 29))
        assertFalse(AppPermissions.asksInDialog(AppPermission.STORAGE, sdk = 30))
        assertFalse(AppPermissions.asksInDialog(AppPermission.NOTIFICATIONS, sdk = 32))
        assertTrue(AppPermissions.asksInDialog(AppPermission.NOTIFICATIONS, sdk = 33))
        assertFalse(AppPermissions.asksInDialog(AppPermission.ALARMS, sdk = 36))
        assertEquals(AppPermission.STORAGE, AppPermission.of("storage"))
        assertNull(AppPermission.of("Storage"))
        assertNull(AppPermission.of(null))
    }

    @Test
    fun whatAndroidLetsARequestDoDependsOnItsVersionAndOnWhatIsOnScreen() {
        // One of the app's screens is up: an activity may start another, on every Android.
        for (sdk in listOf(24, 28, 29, 34, 36)) assertEquals(RemoteAskRoute.SHOWN, RemoteAskRoute.of(sdk, onScreen = true, canNotify = false))
        // Before Android 10 a service may start one as well.
        for (sdk in listOf(24, 28)) assertEquals(RemoteAskRoute.SHOWN, RemoteAskRoute.of(sdk, onScreen = false, canNotify = false))
        // From Android 10 a start from the background is dropped: a notification, or nothing.
        for (sdk in listOf(29, 30, 33, 34, 36)) {
            assertEquals(RemoteAskRoute.NOTIFIED, RemoteAskRoute.of(sdk, onScreen = false, canNotify = true))
            assertEquals(RemoteAskRoute.WAITING, RemoteAskRoute.of(sdk, onScreen = false, canNotify = false))
        }
        assertEquals(listOf("shown", "notified", "waiting"), RemoteAskRoute.entries.map { it.word })
        assertEquals(listOf("shown", "notified", "unseen"), RemoteAskRoute.entries.map { it.identifyWord() })
    }

    // ─── Received files ──────────────────────────────────────────────────────────────────────

    @Test
    fun theReceiveFolderIsSaidWithWhetherItWorksAndWhetherTheDefaultWouldHelp() {
        fun of(label: String?, state: TaildropFolder.State?, default: Boolean) = RemoteDeviceReceive.of(label, state, default, free = 7, waiting = 2)
        // No folder: files wait inside the app, and there is nothing to reset.
        assertEquals(RemoteDeviceReceive("inbox", "", reset = false, free = 7, waiting = 2), of("ignored", null, true))
        assertEquals(RemoteDeviceReceive("ready", "Download/Taildrop", false, 7, 2), of("Download/Taildrop", TaildropFolder.State.READY, true))
        // A chosen folder that works is changed at the device, not from afar.
        assertFalse(of("Movies", TaildropFolder.State.READY, false).reset)
        assertEquals(RemoteDeviceReceive("needs_access", "Movies", true, 7, 2), of("Movies", TaildropFolder.State.NEEDS_ACCESS, false))
        assertEquals(RemoteDeviceReceive("unavailable", "USB/Films", true, 7, 2), of("USB/Films", TaildropFolder.State.UNAVAILABLE, false))
        // The default itself not working: resetting to it would change nothing.
        assertFalse(of("Download/Taildrop", TaildropFolder.State.NEEDS_ACCESS, true).reset)
        // A SAF folder only its provider can name.
        assertEquals("", of(null, TaildropFolder.State.READY, false).folder)
    }

    @Test
    fun theFilesTheFolderTookAreListedWithTheirFolder() {
        fun received(name: String, at: Long, savedTo: String?, uri: String? = "content://x", deleted: Long? = null) = TaildropHistoryEntry(
            name = name, peerName = "pixel", timestamp = at, direction = TaildropDirection.RECEIVED, size = 5,
            savedTo = savedTo, savedUri = uri, deletedAt = deleted, path = "/data/user/0/app/files/taildrop/1/$name",
        )
        val history = listOf(
            received("b.mp4", 3_000_000, "Download/Taildrop/b (1).mp4"),
            received("waiting.txt", 2_500_000, null, uri = null),
            received("gone.txt", 2_000_000, "Download/Taildrop/gone.txt", deleted = 2_100_000),
            received("a.pdf", 1_000_000, "USB drive/Docs/a.pdf"),
            TaildropHistoryEntry(name = "sent.txt", peerName = "pixel", timestamp = 500_000),
        )
        val waiting = listOf(TaildropFile("waiting.txt", 5, 2500, "/data/user/0/app/files/taildrop/1/waiting.txt"))
        assertEquals(
            listOf(
                // The copy's own name, when the folder had one of that name already.
                RemoteDeviceSaved("b (1).mp4", 5, 3000, "Download/Taildrop"),
                RemoteDeviceSaved("a.pdf", 5, 1000, "USB drive/Docs"),
            ),
            RemoteDeviceReports.saved(waiting, history)
        )
        assertTrue(RemoteDeviceReports.saved(emptyList(), emptyList()).isEmpty())
        assertEquals(listOf("root", "root", "tun", "proxy"), listOf(true to true, true to false, false to true, false to false).map { RemoteDeviceReports.mode(it.first, it.second) })
    }

    // ─── The storage by name ─────────────────────────────────────────────────────────────────

    /** A device's storage: the primary volume, a stick, the app's own directory, and a link that leads there. */
    private class FakeStorage : FileSource {
        val primary = File("/storage/emulated/0")
        val usb = File("/storage/1234-ABCD")
        override var access = StorageAccess.GRANTED
        val dirs = mutableMapOf(
            "/storage/emulated/0" to listOf("Download" to -1L, "Android" to -1L, ".thumbnails" to -1L, "notes.txt" to 12L, "private" to -1L),
            "/storage/emulated/0/Download" to listOf("film.mkv" to 734_003_200L, "Taildrop" to -1L),
            "/storage/emulated/0/Download/Taildrop" to emptyList(),
            "/storage/1234-ABCD" to listOf("a.txt" to 1L),
            "/data/user/0/app/files" to listOf("tailscaled.state" to 99L),
        )
        /** Links: a path and where it really leads. */
        val links = mapOf(
            "/sdcard" to "/storage/emulated/0",
            "/storage/self/primary" to "/storage/emulated/0",
            "/storage/emulated/0/private" to "/data/user/0/app/files",
        )
        override fun roots(start: File?) = listOf(
            BrowseRoot(RootKind.PRIMARY, primary),
            BrowseRoot(RootKind.DOWNLOADS, File(primary, "Download")),
            BrowseRoot(RootKind.VOLUME, usb, "USB drive", removable = true),
        )
        override fun canonical(file: File): File {
            var path = FileBrowse.clean(file).path
            for ((from, to) in links) if (path == from || path.startsWith("$from/")) path = to + path.removePrefix(from)
            return File(path)
        }
        override fun list(dir: File, showHidden: Boolean): Listing {
            val children = dirs[dir.path] ?: return Listing.Unreadable(dir, Listing.Reason.NOT_FOUND)
            return Listing.Entries(dir, FileBrowse.arrange(children.map { (name, size) -> FileEntry(File(dir, name), size < 0, maxOf(size, 0), 1_791_700_000_000) }, showHidden), limited = access != StorageAccess.GRANTED)
        }
        override fun describe(file: File): FileEntry? {
            if (dirs.containsKey(file.path)) return FileEntry(file, true)
            val (_, size) = dirs[file.parent.orEmpty()]?.firstOrNull { it.first == file.name } ?: return null
            return FileEntry(file, size < 0, maxOf(size, 0))
        }
    }

    private val storage = FakeStorage()

    @Test
    fun theVolumesAreTheRootsAndNothingElseIs() {
        val roots = RemoteStorage.roots(storage, name = { it.label ?: "Internal storage" }, space = { 10L to 100L })
        assertEquals("", roots.path)
        // The Downloads shortcut is inside the first volume, not a place of its own.
        assertEquals(
            listOf(
                RemoteStorageRoot("primary", "Internal storage", "/storage/emulated/0", 10, 100),
                RemoteStorageRoot("removable", "USB drive", "/storage/1234-ABCD", 10, 100),
            ),
            roots.roots
        )
        assertFalse(roots.limited)
        storage.access = StorageAccess.NEEDS_ALL_FILES
        assertTrue(RemoteStorage.roots(storage, { "x" }, { 0L to 0L }).limited)
    }

    @Test
    fun aFolderIsListedByNameWithNothingHidden() {
        val listing = RemoteStorage.list(storage, "/storage/emulated/0")
        assertEquals("/storage/emulated/0", listing.path)
        // The top of a volume has the list of volumes above it, not a folder.
        assertEquals("", listing.parent)
        assertEquals(listOf("Android", "Download", "private", "notes.txt"), listing.entries.map { it.name })
        assertEquals(RemoteStorageEntry("notes.txt", false, 12, 1_791_700_000), listing.entries.last())
        assertEquals(RemoteStorageEntry("Download", true, 0, 1_791_700_000), listing.entries[1])
        val downloads = RemoteStorage.list(storage, "/storage/emulated/0/Download")
        assertEquals("/storage/emulated/0", downloads.parent)
        assertEquals(listOf("Taildrop", "film.mkv"), downloads.entries.map { it.name })
        assertTrue(RemoteStorage.list(storage, "/storage/emulated/0/Download/Taildrop").entries.isEmpty())
        // A path with a link in it is answered with where it really is, and listed from there.
        assertEquals("/storage/emulated/0/Download", RemoteStorage.list(storage, "/sdcard/Download").path)
        assertEquals("not_found", refused { RemoteStorage.list(storage, "/storage/emulated/0/Nope") })
        // A long folder is cut, and says so.
        storage.dirs["/storage/emulated/0/DCIM"] = (1..RemoteStorage.MAX_ENTRIES + 20).map { "IMG_%04d.jpg".format(it) to 1L }
        val long = RemoteStorage.list(storage, "/storage/emulated/0/DCIM")
        assertEquals(RemoteStorage.MAX_ENTRIES, long.entries.size)
        assertTrue(long.truncated)
        assertEquals("IMG_0001.jpg", long.entries.first().name)
        // …and read on from where the first part ended.
        val rest = RemoteStorage.list(storage, "/storage/emulated/0/DCIM", offset = long.entries.size)
        assertEquals(20, rest.entries.size)
        assertEquals(RemoteStorage.MAX_ENTRIES, rest.offset)
        assertFalse(rest.truncated)
        assertEquals("IMG_0501.jpg", rest.entries.first().name)
        assertTrue(RemoteStorage.list(storage, "/storage/emulated/0/DCIM", offset = 9000).entries.isEmpty())
        assertEquals(0, RemoteStorage.list(storage, "/storage/emulated/0/DCIM", offset = -3).offset)
    }

    @Test
    fun nothingOutsideTheVolumesCanBeNamed() {
        val outside = listOf(
            "/", "/data", "/data/user/0/app/files", "/data/user/0/app/files/tailscaled.state", "/proc/self/environ", "/storage",
            "/storage/emulated", "/storage/emulated/10", "/storage/emulated/0-evil",
            // Leaving a volume by "..", by a path that is not spelled the one way, by a relative one…
            "/storage/emulated/0/../../../data", "/storage/emulated/0/Download/..", "/storage/emulated/0/./Download",
            "/storage/emulated/0//Download", "/storage/emulated/0/Download/", "storage/emulated/0", "Download", "",
            // …or through a link that leads off it.
            "/storage/emulated/0/private", "/storage/emulated/0/private/tailscaled.state",
        )
        for (path in outside) {
            assertEquals(path, "forbidden", refused { RemoteStorage.resolve(storage, path) })
            assertEquals(path, "forbidden", refused { RemoteStorage.list(storage, path) })
            assertEquals(path, "forbidden", refused { RemoteStorage.file(storage, path) })
        }
        // A sibling whose name merely starts like a volume's is not inside it.
        assertEquals("forbidden", refused { RemoteStorage.resolve(storage, "/storage/1234-ABCDE/a.txt") })
    }

    @Test
    fun aFileThatMayBeSentIsARegularFileOnAVolume() {
        assertEquals(RemoteStorageFile("/storage/emulated/0/Download/film.mkv", "film.mkv", 734_003_200), RemoteStorage.file(storage, "/storage/emulated/0/Download/film.mkv"))
        assertEquals("/storage/1234-ABCD/a.txt", RemoteStorage.file(storage, "/storage/1234-ABCD/a.txt").path)
        // Through a link that stays on the volume: the path that is sent is the real one.
        assertEquals("/storage/emulated/0/notes.txt", RemoteStorage.file(storage, "/sdcard/notes.txt").path)
        assertEquals("not_found", refused { RemoteStorage.file(storage, "/storage/emulated/0/Download/gone.mkv") })
        assertEquals("that is a folder, not a file", refused { RemoteStorage.file(storage, "/storage/emulated/0/Download") })
    }

    // ─── Shared folders ──────────────────────────────────────────────────────────────────────

    @Test
    fun sharesAreReadAsTheAppStoresThem() {
        val seeded = """[{"name":"Downloads","path":"/storage/emulated/0/Download"}]"""
        val shares = RemoteShares.parse(seeded)
        assertEquals(listOf(RemoteDeviceFolder("Downloads", "/storage/emulated/0/Download")), shares)
        assertEquals(seeded, RemoteShares.encode(shares))
        for (bad in listOf(null, "", "[]", "not json", """{"name":"x"}""", """[{"name":"","path":"/x"},{"name":"x","path":""}]""")) {
            assertTrue("$bad", RemoteShares.parse(bad).isEmpty())
        }
    }

    @Test
    fun aShareIsAddedUnderANameTaildriveTakesAndRemovedByIt() {
        val shares = RemoteShares.parse("""[{"name":"Downloads","path":"/storage/emulated/0/Download"}]""")
        val more = RemoteShares.add(shares, "Movies_2026", "/storage/1234-ABCD/Movies")
        assertEquals(listOf("Downloads", "Movies_2026"), more.map { it.name })
        for (name in listOf("", "my share", "a/b", "Фильмы", "x.y", "n".repeat(41), "../x")) {
            assertEquals(name, "not a name a share can have", refused { RemoteShares.add(shares, name, "/storage/1234-ABCD/Movies") })
        }
        // Taildrive takes Music and music for one; and a folder is shared once.
        assertEquals("a share of that name exists", refused { RemoteShares.add(shares, "downloads", "/storage/1234-ABCD/Movies") })
        assertEquals("that folder is shared already", refused { RemoteShares.add(shares, "Again", "/storage/emulated/0/Download/") })
        assertEquals(listOf("Downloads"), RemoteShares.remove(more, "movies_2026").map { it.name })
        assertTrue(RemoteShares.remove(shares, "Downloads").isEmpty())
        assertEquals("not_found", refused { RemoteShares.remove(shares, "Movies") })
    }

    // ─── What the page is told ───────────────────────────────────────────────────────────────

    @Test
    fun theAccessDocumentIsReadAndABrokenOneIsTheEmptyOne() {
        val access = RemoteAccess.of(
            """{"level":"full","guests":false,
                "guest":{"ops":["status","exit_nodes"]},
                "owner":{"ops":["status","storage.list"],"settings":[{"key":"tun_mode","guard":"always"},{"key":"auto_reconnect","only":"true"}],
                         "actions":["reconnect"],"permissions":["storage"]},
                "full":{"ops":[]},"never":["stop","sign_out"]}"""
        )
        assertEquals(RemoteAccess.LEVEL_FULL, access.level)
        assertFalse(access.guests)
        assertEquals(listOf("status", "exit_nodes"), access.guest.ops)
        assertEquals(RemoteAccessSetting("tun_mode", guard = "always"), access.owner.settings[0])
        assertEquals("true", access.owner.settings[1].only)
        assertEquals(listOf("stop", "sign_out"), access.never)
        assertEquals(RemoteAccess(), RemoteAccess.of(""))
        assertEquals(RemoteAccess(), RemoteAccess.of("not json"))
        assertEquals(RemoteAccess.LEVEL_BASIC, RemoteAccess.of("{}").level)
    }
}
