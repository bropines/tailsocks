package io.github.bropines.tailscaled.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * A managed device that is not there: the contract's own example answers
 * (.claude/plans/tv/remote-api.md), and a device made of them that keeps the contract's rules —
 * who may do what, what a guarded change does until it is confirmed, what each refusal is.
 *
 * Only tests and previews construct it. It stands behind the same seam as the tailnet
 * (RemotePeerApi), so what runs against it is the real client, the real parsing and the real
 * page logic. The app never does; a release build drops this file.
 */

/** The contract's examples, as they are written there. */
object RemoteContract {
    /** §5.2 */
    const val APP = """{
  "permissions": [
    {"id": "storage",       "state": "missing", "ask": true},
    {"id": "notifications", "state": "granted", "ask": false},
    {"id": "battery",       "state": "na",      "ask": false},
    {"id": "vpn",           "state": "na",      "ask": false},
    {"id": "alarms",        "state": "granted", "ask": false},
    {"id": "install",       "state": "missing", "ask": true},
    {"id": "autostart",     "state": "unknown", "ask": false}
  ],
  "asked": {"id": "storage", "result": "notified", "age": 42},
  "receive": {"state": "needs_access", "folder": "Movies/From phone", "reset": true, "free": 51234567890, "waiting": 2},
  "saved": [{"name": "holiday.mp4", "size": 812647424, "time": 1791727380, "folder": "Download/Taildrop"}],
  "folders": [{"name": "Downloads", "path": "/storage/emulated/0/Download"}],
  "folderCount": 1
}"""

    /** §5, with §5.2 where it says "see 5.2". */
    const val STATUS = """{
  "running": true, "backendState": "Running",
  "version": "1.104.1", "appVersion": "4.11.0", "deviceClass": "tv",
  "name": "living-room-tv", "dnsName": "living-room-tv.tail1234.ts.net", "nodeId": "nKq7Xw2CNTRL",
  "exitNode": {"id": "nFRAexitCNTRL", "name": "exit-frankfurt", "online": true, "selected": true, "country": "Germany", "city": "Frankfurt"},
  "health": [], "since": 1791773780,
  "you": {"class": "owner", "left": -1, "can": ["status", "exit_nodes", "exit_node.set", "settings", "settings.set", "action", "files", "files.delete", "unpair", "permission.request", "folders", "confirm", "identify", "shares.remove"]},
  "caps": ["report", "permissions", "oneway", "suggest", "can", "device", "guard", "identify"],
  "level": "basic",
  "guard": {"id": "g7Jk2", "key": "exit_node", "left": 42, "after": 0},
  "device": {"model": "Xiaomi MiTV-MOOQ1", "android": "11", "sdk": 30, "build": "0e629420", "mode": "proxy", "engine": "", "keyExpiry": 1799539200},
  "advertise": {"exitNode": {"on": true, "approved": false}, "routes": [{"cidr": "192.168.5.0/24", "approved": true}]},
  "fetch": {"id": "t3", "name": "film.mkv", "size": 734003200, "state": "sending", "age": 0},
  "app": $APP
}"""

    /** What the first build answers: today's fields and nothing announced. */
    const val STATUS_FIRST_BUILD = """{
  "running": true, "backendState": "Running",
  "version": "1.104.1", "appVersion": "4.11.0-dev", "deviceClass": "tv",
  "name": "living-room-tv", "dnsName": "living-room-tv.tail1234.ts.net", "nodeId": "nKq7Xw2CNTRL",
  "exitNode": {"id": "nFRAexitCNTRL", "name": "exit-frankfurt", "online": true, "selected": true, "country": "Germany", "city": "Frankfurt"},
  "health": [], "since": 1791773780,
  "you": {"class": "owner", "left": -1}
}"""

    /** §6.1 */
    const val SETTINGS = """{"settings": [
  {"key": "accept_dns",              "kind": "bool", "writable": true,  "value": "true",  "guard": "optional"},
  {"key": "accept_routes",           "kind": "bool", "writable": true,  "value": "false", "guard": "optional"},
  {"key": "exit_node_lan_access",    "kind": "bool", "writable": true,  "value": "true",  "guard": "optional"},
  {"key": "tun_mode",                "kind": "bool", "writable": true,  "value": "false", "guard": "always"},
  {"key": "auto_reconnect",          "kind": "bool", "writable": true,  "value": "false", "only": "true"},
  {"key": "auto_start",              "kind": "bool", "writable": true,  "value": "false", "only": "true"},
  {"key": "keep_awake",              "kind": "bool", "writable": true,  "value": "true",  "only": "true"},
  {"key": "taildrive_enabled",       "kind": "bool", "writable": true,  "value": "true",  "only": "false", "needs": "full"},
  {"key": "taildrive_proxy_enabled", "kind": "bool", "writable": true,  "value": "false", "only": "false", "needs": "full"},
  {"key": "advertise_exit_node",     "kind": "bool", "writable": true,  "value": "false", "only": "false", "needs": "full"},
  {"key": "advertise_routes",        "kind": "cidrs", "writable": true, "value": "",      "needs": "full"},
  {"key": "full_control",            "kind": "bool", "writable": true,  "value": "false", "only": "false"},
  {"key": "device_class",            "kind": "text", "writable": false, "value": "tv"},
  {"key": "app_version",             "kind": "text", "writable": false, "value": "4.11.0"}
]}"""

    /** The same list as the first build sends it. */
    const val SETTINGS_FIRST_BUILD = """{"settings": [
  {"key": "accept_dns",           "kind": "bool", "writable": true,  "value": "true"},
  {"key": "accept_routes",        "kind": "bool", "writable": true,  "value": "false"},
  {"key": "exit_node_lan_access", "kind": "bool", "writable": true,  "value": "true"},
  {"key": "device_class",         "kind": "text", "writable": false, "value": "tv"},
  {"key": "app_version",          "kind": "text", "writable": false, "value": "4.11.0-dev"}
]}"""

    /** §6.4: the list of today, one node marked as the device's own pick. */
    const val EXIT_NODES = """{"nodes": [
  {"id": "nAMSexitCNTRL",  "name": "exit-amsterdam", "online": true,  "country": "Netherlands", "city": "Amsterdam", "suggested": true},
  {"id": "nFRAexitCNTRL",  "name": "exit-frankfurt", "online": true,  "selected": true, "country": "Germany", "city": "Frankfurt"},
  {"id": "nHOMEexitCNTRL", "name": "home-router",    "online": false},
  {"id": "nHELexitCNTRL",  "name": "exit-helsinki",  "online": true,  "country": "Finland", "city": "Helsinki"}
]}"""

    /** §4: unchanged since the first build. */
    const val FILES = """{"files": [
  {"name": "notes.txt", "size": 2048, "modTime": 1791800000},
  {"name": "Quarterly report Q3 2026 with a name that does not fit in one line.pdf", "size": 2516582, "modTime": 1791554580}
]}"""

    /** §7 */
    const val GUARDED = """{"guard": {"id": "g7Jk2", "key": "exit_node", "seconds": 60, "after": 5}}"""
    const val TOO_EARLY = """{"error":"too_early","after":3}"""

    /** §8 */
    const val PERMISSION_SHOWN = """{"permission": "storage", "result": "shown"}"""

    /** §10 */
    const val STORAGE_ROOTS = """{"path": "", "roots": [
  {"kind": "primary",   "name": "Internal storage", "path": "/storage/emulated/0", "free": 51234567890, "total": 118111600640},
  {"kind": "removable", "name": "USB-накопитель SanDisk", "path": "/storage/1234-ABCD", "free": 1234567, "total": 31914983424}
]}"""
    const val STORAGE_DIR = """{"path": "/storage/emulated/0/Download", "parent": "/storage/emulated/0",
 "entries": [
   {"name": "Taildrop", "dir": true,  "size": 0,         "time": 1791727380},
   {"name": "film.mkv", "dir": false, "size": 734003200, "time": 1791700000}
 ],
 "offset": 0, "truncated": false, "limited": false}"""
    const val STORAGE_SENT = """{"id": "t3", "name": "film.mkv", "size": 734003200}"""

    /** §13 */
    const val DIAG_REPORT = """{"text": "--- TAILSOCKS DIAGNOSTICS ---\nApp: 4.11.0 (0e629420)\nTailscale: 1.104.1\nDevice: Xiaomi MiTV-MOOQ1, Android 11 (API 30)\nMode: proxy\nPermissions: storage missing, notifications granted\nNetwork: Wi-Fi, IPv4 only\n"}"""
    const val DIAG_LOG = """{"lines": [
  "12:00:01 INFO CORE daemon started, version 1.104.1",
  "12:00:02 INFO CORE control: connected through […]",
  "12:00:04 INFO NET  magicsock: home relay is fra",
  "12:00:09 WARN NET  health: no UDP, falling back to relays",
  "12:01:30 INFO REMOTE pixel-9-pro changed the exit node"
], "scrubbed": 17}"""

    fun status(json: String = STATUS): ManagedStatus = RemoteJson.decode<ManagedStatus>(json) ?: ManagedStatus()
    fun settings(json: String = SETTINGS): List<ManagedSetting> = RemoteJson.decode<ManagedSettings>(json)?.settings.orEmpty()
    fun exitNodes(json: String = EXIT_NODES): List<ManagedExitNode> = RemoteJson.decode<ManagedExitNodes>(json)?.nodes.orEmpty()
    fun files(json: String = FILES): List<RemoteFile> = RemoteJson.decode<RemoteFiles>(json)?.files.orEmpty()
    fun report(json: String = DIAG_REPORT): ManagedReport = RemoteJson.decode<ManagedReport>(json) ?: ManagedReport()
    fun log(json: String = DIAG_LOG): ManagedLog = RemoteJson.decode<ManagedLog>(json) ?: ManagedLog()

    /** Every capability of the contract, for a device that has all of it built. */
    val ALL_CAPS = listOf(
        ManagedCaps.REPORT, ManagedCaps.PERMISSIONS, ManagedCaps.ONEWAY, ManagedCaps.SUGGEST, ManagedCaps.CAN,
        ManagedCaps.DEVICE, ManagedCaps.GUARD, ManagedCaps.MODE, ManagedCaps.IDENTIFY, ManagedCaps.STORAGE,
        ManagedCaps.SHARES, ManagedCaps.ADVERTISE, ManagedCaps.DIAG, ManagedCaps.RELAYS, ManagedCaps.SAVE,
    )

    /** The ops of §3 by the class and level that open them. */
    val GUEST_OPS = listOf("status", "exit_nodes", "exit_node.set", "settings", "files", "unpair", "confirm", "identify")
    val OWNER_OPS = GUEST_OPS + listOf("settings.set", "action", "files.delete", "permission.request", "folders", "shares.remove")
    val FULL_OPS = OWNER_OPS + listOf("shares.add", "storage.list", "storage.send", "diag.report", "diag.log")
}

/**
 * A device behind [RemotePeerApi] that answers by the contract. It starts as the contract's
 * example — a television, a Basic owner calling — and [become] makes it another caller's view
 * or another build. [clock] is its time in milliseconds: a guarded change is put back when
 * that passes the deadline, whoever is or is not asking.
 *
 * [reachable] off, nothing comes back, as from a device whose route was cut; [script] queues
 * an answer for a route ahead of the device's own, for the refusals it would not give itself.
 */
class FakeManagedDevice(
    private val clock: () -> Long = { 0L },
    val token: String = "tok-fake",
    statusJson: String = RemoteContract.STATUS,
    settingsJson: String = RemoteContract.SETTINGS,
    exitNodesJson: String = RemoteContract.EXIT_NODES,
    filesJson: String = RemoteContract.FILES,
) : RemotePeerApi {

    data class Request(val method: String, val path: String, val body: String = "") {
        override fun toString() = if (body.isEmpty()) "$method $path" else "$method $path $body"
    }

    /** Everything that was asked, in order — whether or not it got through. */
    val sent = ArrayList<Request>()

    var reachable = true
    /** What the device answers to a permission request and to identify: "shown", "notified", "waiting" / "unseen". */
    var permissionResult = "shown"
    var identifyResult = "shown"
    var guardSeconds = 60L
    var guardAfter = 5L
    /** The next guarded change is answered `504 pending`, with its guard beside the error (§7). */
    var answerPending = false

    private var status: JsonObject = parse(statusJson).without("guard", "fetch").let { s ->
        // The example's request for a permission is somebody's, a moment ago: a fresh device has none.
        s.with("app", (s["app"] as? JsonObject)?.without("asked"))
    }
    private var settings: List<JsonObject> = parse(settingsJson)["settings"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    private var exitNodes: List<JsonObject> = parse(exitNodesJson)["nodes"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    private var files: List<JsonObject> = parse(filesJson)["files"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    private val storage = HashMap<String, String>().apply {
        put("", RemoteContract.STORAGE_ROOTS)
        put("/storage/emulated/0", """{"path": "/storage/emulated/0", "entries": [
            {"name": "Download", "dir": true, "size": 0, "time": 1791727380},
            {"name": "Movies", "dir": true, "size": 0, "time": 1791700000}], "truncated": false, "limited": false}""")
        put("/storage/emulated/0/Download", RemoteContract.STORAGE_DIR)
        put("/storage/emulated/0/Movies", """{"path": "/storage/emulated/0/Movies", "parent": "/storage/emulated/0", "entries": [], "truncated": false, "limited": false}""")
    }
    private val scripted = HashMap<String, ArrayDeque<RemoteAnswer>>()

    private class Pending(val id: String, val key: String, val deadline: Long, val notBefore: Long, val original: String, val revert: () -> Unit)
    private var pending: Pending? = null
    private var guards = 0
    private var transfers = 0

    /** Why the last guarded change was put back, for a test to read: "not confirmed", or null. */
    var reverted: String? = null
        private set

    // ─── Shaping the device ──────────────────────────────────────────────────────────────

    /** Answers queued for "GET /v1/status"-style [route], taken one per request before the device's own. */
    fun script(route: String, vararg answers: RemoteAnswer) {
        scripted.getOrPut(route) { ArrayDeque() }.addAll(answers)
    }

    /**
     * Another caller, level or build. [caps] null makes it the first build: no `caps`, no
     * `level`, no `you.can`, none of the later fields, and the first build's settings.
     */
    fun become(owner: Boolean = true, full: Boolean = false, caps: List<String>? = RemoteContract.ALL_CAPS, left: Long = if (owner) -1 else 75_600) {
        val role = if (owner) REMOTE_CLASS_OWNER else REMOTE_CLASS_GUEST
        if (caps == null) {
            status = buildJsonObject {
                status.filterKeys { it !in LATER_FIELDS }.forEach { (k, v) -> put(k, v) }
                put("you", buildJsonObject { put("class", role); put("left", left) })
            }
            settings = settings.filter { it.text("key") in ManagedKeys.FIRST_BUILD || it.text("kind") == ManagedSetting.TEXT }
                .map { row -> row.without("guard", "only", "needs").with("writable", JsonPrimitive(owner && row.text("kind") == ManagedSetting.BOOL)) }
            return
        }
        val ops = when {
            !owner -> RemoteContract.GUEST_OPS
            full -> RemoteContract.FULL_OPS
            else -> RemoteContract.OWNER_OPS
        }
        status = status
            .with("caps", buildJsonArray { caps.forEach { add(JsonPrimitive(it)) } })
            .with("level", JsonPrimitive(if (full) ManagedLevel.FULL else ManagedLevel.BASIC))
            .with("you", buildJsonObject {
                put("class", role)
                put("left", left)
                if (ManagedCaps.CAN in caps) put("can", buildJsonArray { ops.forEach { add(JsonPrimitive(it)) } })
            })
        val app = status["app"] as? JsonObject
        if (app != null) {
            // A guest reads no folder names and can ask for nothing.
            val seen = if (owner) app else app.without("folders").with(
                "permissions",
                JsonArray(app["permissions"]?.jsonArray.orEmpty().map { it.jsonObject.with("ask", JsonPrimitive(false)) })
            )
            status = status.with("app", seen)
        }
        settings = settings.map { row ->
            val changeable = row.text("kind") != ManagedSetting.TEXT
            var next = row.with("writable", JsonPrimitive(owner && changeable))
            when {
                // It reads "true" while the device is at the full level, and can only be given up.
                row.text("key") == ManagedKeys.FULL_CONTROL -> next = next.with("value", JsonPrimitive(full.toString()))
                // With Full control on, what needed it arrives without its one-way mark.
                full && row.text("needs") == ManagedLevel.FULL -> next = next.without("only", "needs")
                !full && row.text("key") in NEEDS_FULL -> {
                    next = next.with("needs", JsonPrimitive(ManagedLevel.FULL))
                    if (row.text("kind") == ManagedSetting.BOOL) next = next.with("only", JsonPrimitive("false"))
                }
            }
            next
        }
    }

    /** Changes the status as the device holds it: [edit] adds and replaces top-level fields. */
    fun editStatus(edit: JsonObjectBuilder.() -> Unit) {
        val patch = buildJsonObject(edit)
        status = JsonObject(status + patch)
    }

    fun editApp(edit: JsonObjectBuilder.() -> Unit) {
        val app = (status["app"] as? JsonObject) ?: JsonObject(emptyMap())
        status = status.with("app", JsonObject(app + buildJsonObject(edit)))
    }

    /** Adds a row to the settings, or replaces the one of that key. */
    fun putSetting(json: String) {
        val row = parse(json)
        settings = settings.filter { it.text("key") != row.text("key") } + row
    }

    fun settingValue(key: String): String? = settings.firstOrNull { it.text("key") == key }?.text("value")
    val exitNodeId: String get() = (status["exitNode"] as? JsonObject)?.text("id").orEmpty()
    val shareNames: List<String> get() = folders().map { it.text("name") }
    val fileNames: List<String> get() = files.map { it.text("name") }
    val guardPending: Boolean get() { expire(); return pending != null }

    /** The change that waits is kept as if its confirmation had arrived: for an answer lost on its way back. */
    fun settle() {
        pending = null
    }

    /** The transfer the device is running ends: "sent", or "failed" with [error]. */
    fun finishFetch(error: String = "") {
        val fetch = status["fetch"] as? JsonObject ?: return
        status = status.with(
            "fetch",
            fetch.with("state", JsonPrimitive(if (error.isEmpty()) ManagedFetch.SENT else ManagedFetch.FAILED)).with("error", JsonPrimitive(error))
        )
    }

    // ─── RemotePeerApi ───────────────────────────────────────────────────────────────────

    override fun probe(peerId: String) = RemoteProbe(ok = reachable, api = 1, deviceClass = status.text("deviceClass"), name = status.text("name"), pairing = true)

    override fun pair(peerId: String, code: String): RemotePairAnswer =
        if (!reachable) RemotePairAnswer(error = "unreachable")
        else RemotePairAnswer(ok = true, status = 200, token = token, role = you().text("class"), left = -1, name = status.text("name"))

    override fun parseQr(text: String): RemoteQr? = null

    override fun get(peerId: String, token: String, path: String): RemoteAnswer = handle(Request("GET", path), token)

    override fun post(peerId: String, token: String, path: String, body: String): RemoteAnswer = handle(Request("POST", path, body), token)

    private fun handle(request: Request, token: String): RemoteAnswer {
        sent += request
        expire()
        if (!reachable) return RemoteAnswer(status = 0, error = "unreachable")
        scripted["${request.method} ${request.path}"]?.removeFirstOrNull()?.let { return it }
        if (token != this.token) return refuse(401, "unauthorized")
        val body = if (request.body.isBlank()) JsonObject(emptyMap()) else runCatching { parse(request.body) }.getOrNull()
            ?: return refuse(400, "bad_request")
        return when (request.method + " " + request.path) {
            "GET " + RemotePaths.STATUS -> ok(statusNow())
            "GET " + RemotePaths.EXIT_NODES -> ok(buildJsonObject { put("nodes", JsonArray(exitNodesNow())) })
            "GET " + RemotePaths.SETTINGS -> ok(buildJsonObject { put("settings", JsonArray(settings)) })
            "GET " + RemotePaths.FILES -> ok(buildJsonObject { put("files", JsonArray(files)) })
            "POST " + RemotePaths.EXIT_NODE -> setExitNode(body)
            "POST " + RemotePaths.SETTINGS -> setSetting(body)
            "POST " + RemotePaths.ACTIONS -> action(body)
            "POST " + RemotePaths.FILES_DELETE -> deleteFile(body)
            "POST " + RemotePaths.UNPAIR -> ok()
            "POST " + ManagedPaths.CONFIRM -> confirm(body)
            "POST " + ManagedPaths.PERMISSION_REQUEST -> askPermission(body)
            "POST " + ManagedPaths.IDENTIFY -> identify(body)
            "POST " + ManagedPaths.STORAGE_LIST -> storageList(body)
            "POST " + ManagedPaths.STORAGE_SEND -> storageSend(body)
            "POST " + ManagedPaths.SHARES_ADD -> addShare(body)
            "POST " + ManagedPaths.SHARES_REMOVE -> removeShare(body)
            "GET " + ManagedPaths.DIAG_REPORT -> diag(ManagedOp.DIAG_REPORT, RemoteContract.DIAG_REPORT)
            "GET " + ManagedPaths.DIAG_LOG -> diag(ManagedOp.DIAG_LOG, RemoteContract.DIAG_LOG)
            else -> refuse(404, "not_found")
        }
    }

    // ─── The routes ──────────────────────────────────────────────────────────────────────

    private fun statusNow(): JsonObject {
        // The count of what waits inside the app is the count of GET /v1/files.
        val app = status["app"] as? JsonObject
        val receive = app?.get("receive") as? JsonObject
        val status = if (app != null && receive != null) {
            status.with("app", app.with("receive", receive.with("waiting", JsonPrimitive(files.size))))
        } else status
        val p = pending ?: return status
        val now = clock()
        return status.with("guard", buildJsonObject {
            put("id", p.id)
            put("key", p.key)
            put("left", ((p.deadline - now + 999) / 1000).coerceAtLeast(0))
            put("after", ((p.notBefore - now + 999) / 1000).coerceAtLeast(0))
        })
    }

    private fun exitNodesNow(): List<JsonObject> {
        val current = exitNodeId
        val suggest = has(ManagedCaps.SUGGEST)
        return exitNodes.map { node ->
            var n = node.without("selected").let { if (suggest) it else it.without("suggested") }
            if (node.text("id") == current) n = n.with("selected", JsonPrimitive(true))
            n
        }
    }

    private fun setExitNode(body: JsonObject): RemoteAnswer {
        unknownField(body, "id", "guard".takeIf { has(ManagedCaps.GUARD) })?.let { return it }
        denied(ManagedOp.EXIT_NODE_SET)?.let { return it }
        val id = body.text("id")
        val node = exitNodes.firstOrNull { it.text("id") == id }
        if (id.isNotEmpty() && node == null) return refuse(404, "not_found")
        val before = status["exitNode"]
        val apply = {
            status = if (node == null) status.without("exitNode") else status.with("exitNode", node.without("suggested").with("selected", JsonPrimitive(true)))
        }
        val revert = { status = if (before == null) status.without("exitNode") else status.with("exitNode", before) }
        return changed(ManagedKeys.EXIT_NODE, guarded = body.flag("guard"), before = exitNodeId, value = id, apply = apply, revert = revert)
    }

    private fun setSetting(body: JsonObject): RemoteAnswer {
        unknownField(body, "key", "value", "guard".takeIf { has(ManagedCaps.GUARD) })?.let { return it }
        denied(ManagedOp.SETTINGS_SET)?.let { return it }
        val key = body.text("key")
        val value = body.text("value")
        val row = settings.firstOrNull { it.text("key") == key } ?: return refuse(403, "forbidden")
        if (row["writable"]?.jsonPrimitive?.booleanOrNull != true) return refuse(403, "forbidden")
        if (row.text("kind") == ManagedSetting.BOOL && value != "true" && value != "false") return refuse(400, "bad_request")
        val only = row["only"]?.jsonPrimitive?.content
        val needsFull = row.text("needs") == ManagedLevel.FULL
        if (only != null && value != only) return refuse(403, if (needsFull) "needs_full" else "forbidden")
        // A list may be cleared at the basic level; writing prefixes takes Full control.
        if (row.text("kind") == ManagedSetting.CIDRS) {
            if (needsFull && value.isNotEmpty()) return refuse(403, "needs_full")
            if (value.isNotEmpty() && cidrList(value) == null) return refuse(400, "bad_request")
        }
        val before = row.text("value")
        fun write(v: String) {
            settings = settings.map { if (it.text("key") == key) it.with("value", JsonPrimitive(v)) else it }
        }
        if (key == ManagedKeys.FULL_CONTROL) {
            // Given up at once, and with it everything it opened.
            become(owner = true, full = false, caps = caps())
            return ok()
        }
        val guarded = row.text("guard") == ManagedSetting.GUARD_ALWAYS || body.flag("guard")
        return changed(key, guarded, before = before, value = value, apply = { write(value) }, revert = { write(before) })
    }

    /** Applies a change; guarded, it is provisional and its answer says until when. */
    private fun changed(key: String, guarded: Boolean, before: String, value: String, apply: () -> Unit, revert: () -> Unit): RemoteAnswer {
        val held = pending
        // Put back by hand before it was confirmed: the wait is over, and nothing will be undone.
        if (held != null && held.key == key && value == held.original) {
            apply()
            pending = null
            return ok()
        }
        // What the setting already holds needs no guarding, and an unguarded change is held back by nothing.
        if (!guarded || value == before) {
            apply()
            return ok()
        }
        // Another key's change is still waiting; the same key's is replaced, and goes back to the original value.
        if (held != null && held.key != key) return refuse(409, "guard_pending")
        apply()
        val now = clock()
        val id = "g${++guards}"
        pending = Pending(id, key, now + guardSeconds * 1000, now + guardAfter * 1000, held?.original ?: before, held?.revert ?: revert)
        reverted = null
        val guard = buildJsonObject { put("id", id); put("key", key); put("seconds", guardSeconds); put("after", guardAfter) }
        if (answerPending) {
            // The app was asked and has not answered — and the change is under guard all the same.
            answerPending = false
            return RemoteAnswer(504, buildJsonObject { put("error", "pending"); put("guard", guard) }.toString())
        }
        return ok(buildJsonObject { put("guard", guard) })
    }

    private fun confirm(body: JsonObject): RemoteAnswer {
        if (!has(ManagedCaps.GUARD)) return refuse(404, "not_found")
        unknownField(body, "id")?.let { return it }
        val p = pending?.takeIf { it.id == body.text("id") } ?: return refuse(404, "not_found")
        val wait = p.notBefore - clock()
        if (wait > 0) return RemoteAnswer(409, buildJsonObject { put("error", "too_early"); put("after", (wait + 999) / 1000) }.toString())
        pending = null
        return ok()
    }

    /** The deadline passed with nobody confirming: the old value is back. */
    private fun expire() {
        val p = pending ?: return
        if (clock() < p.deadline) return
        p.revert()
        pending = null
        reverted = "not confirmed"
    }

    private fun action(body: JsonObject): RemoteAnswer {
        unknownField(body, "action")?.let { return it }
        denied(ManagedOp.ACTION)?.let { return it }
        return when (body.text("action")) {
            ManagedKeys.ACTION_RECONNECT -> ok()
            ManagedKeys.ACTION_RECEIVE_DEFAULT -> if (has(ManagedCaps.REPORT)) {
                editApp { put("receive", buildJsonObject { put("state", ManagedReceive.READY); put("folder", "Download/Taildrop"); put("reset", false); put("free", 51234567890) }) }
                ok()
            } else refuse(403, "forbidden")
            ManagedKeys.ACTION_RELAYS -> if (has(ManagedCaps.RELAYS)) ok() else refuse(403, "forbidden")
            ManagedKeys.ACTION_SAVE_RECEIVED -> {
                val app = status["app"] as? JsonObject
                val receive = app?.get("receive") as? JsonObject
                when {
                    !has(ManagedCaps.SAVE) -> refuse(403, "forbidden")
                    receive?.text("state") != ManagedReceive.READY -> refuse(409, "refused", "the receive folder cannot be written")
                    else -> {
                        // What waited inside the app is in the folder now, and in the list of what it took.
                        val moved = files.map { file ->
                            buildJsonObject {
                                put("name", file.text("name"))
                                put("size", file["size"] ?: JsonPrimitive(0))
                                put("time", file["modTime"] ?: JsonPrimitive(0))
                                put("folder", receive.text("folder"))
                            }
                        }
                        editApp { put("saved", JsonArray(moved + app["saved"]?.jsonArray.orEmpty())) }
                        files = emptyList()
                        ok()
                    }
                }
            }
            else -> refuse(403, "forbidden")
        }
    }

    private fun deleteFile(body: JsonObject): RemoteAnswer {
        unknownField(body, "name")?.let { return it }
        denied(ManagedOp.FILES_DELETE)?.let { return it }
        val name = body.text("name")
        if (files.none { it.text("name") == name }) return refuse(404, "not_found")
        files = files.filter { it.text("name") != name }
        return ok()
    }

    private fun askPermission(body: JsonObject): RemoteAnswer {
        if (!has(ManagedCaps.PERMISSIONS)) return refuse(404, "not_found")
        unknownField(body, "permission")?.let { return it }
        denied(ManagedOp.PERMISSION_REQUEST)?.let { return it }
        val id = body.text("permission")
        val row = (status["app"] as? JsonObject)?.get("permissions")?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.text("id") == id }
            ?: return refuse(403, "forbidden")
        if (row.text("state") == ManagedPermission.GRANTED) return refuse(409, "refused", "already granted")
        if (!row.flag("ask")) return refuse(403, "forbidden")
        if (permissionResult != "shown") editApp { put("asked", buildJsonObject { put("id", id); put("result", permissionResult); put("age", 0) }) }
        return ok(buildJsonObject { put("permission", id); put("result", permissionResult) })
    }

    /** Someone at the device allowed [id]: it reads as granted, and nothing waits any more. */
    fun grant(id: String) {
        val app = status["app"] as? JsonObject ?: return
        val rows = app["permissions"]?.jsonArray.orEmpty().map { row ->
            val r = row.jsonObject
            if (r.text("id") == id) r.with("state", JsonPrimitive(ManagedPermission.GRANTED)).with("ask", JsonPrimitive(false)) else r
        }
        status = status.with("app", app.without("asked").with("permissions", JsonArray(rows)))
    }

    private fun identify(body: JsonObject): RemoteAnswer {
        if (!has(ManagedCaps.IDENTIFY)) return refuse(404, "not_found")
        if (body.isNotEmpty()) return refuse(400, "bad_request")
        denied(ManagedOp.IDENTIFY)?.let { return it }
        return ok(buildJsonObject { put("result", identifyResult) })
    }

    private fun storageList(body: JsonObject): RemoteAnswer {
        if (!has(ManagedCaps.STORAGE)) return refuse(404, "not_found")
        unknownField(body, "path", "offset")?.let { return it }
        denied(ManagedOp.STORAGE_LIST)?.let { return it }
        val path = body.text("path")
        val offset = body["offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (".." in path.split('/')) return refuse(403, "forbidden")
        if (offset < 0 || (path.isEmpty() && offset != 0)) return refuse(400, "bad_request")
        val listing = storage[path]?.let(::parse) ?: return refuse(404, "not_found")
        if (path.isEmpty()) return ok(listing)
        // At most a page an answer; "truncated" says the folder holds more after these.
        val all = listing["entries"]?.jsonArray.orEmpty()
        val part = all.drop(offset).take(STORAGE_PAGE)
        return ok(
            listing.with("entries", JsonArray(part)).with("offset", JsonPrimitive(offset))
                .with("truncated", JsonPrimitive(offset + part.size < all.size))
        )
    }

    /** A folder of [count] files, for a listing longer than one answer. */
    fun putFolder(path: String, count: Int) {
        storage[path] = buildJsonObject {
            put("path", path)
            put("parent", path.substringBeforeLast('/'))
            put("entries", buildJsonArray {
                repeat(count) { i -> add(buildJsonObject { put("name", "IMG_${1000 + i}.jpg"); put("dir", false); put("size", 2_000_000 + i); put("time", 1791700000 + i) }) }
            })
            put("limited", false)
        }.toString()
    }

    private fun storageSend(body: JsonObject): RemoteAnswer {
        if (!has(ManagedCaps.STORAGE)) return refuse(404, "not_found")
        unknownField(body, "path")?.let { return it }
        denied(ManagedOp.STORAGE_SEND)?.let { return it }
        val path = body.text("path")
        val dir = storage[path.substringBeforeLast('/')] ?: return refuse(404, "not_found")
        val entry = parse(dir)["entries"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.text("name") == path.substringAfterLast('/') }
            ?: return refuse(404, "not_found")
        if (entry.flag("dir")) return refuse(409, "refused", "not a regular file")
        if ((status["fetch"] as? JsonObject)?.text("state") == ManagedFetch.SENDING) return refuse(409, "refused", "another file is still being sent")
        val started = buildJsonObject { put("id", "t${++transfers}"); put("name", entry.text("name")); put("size", entry["size"] ?: JsonPrimitive(0)) }
        status = status.with("fetch", started.with("state", JsonPrimitive(ManagedFetch.SENDING)).with("error", JsonPrimitive("")).with("age", JsonPrimitive(0)))
        return ok(started)
    }

    private fun folders(): List<JsonObject> = (status["app"] as? JsonObject)?.get("folders")?.jsonArray?.map { it.jsonObject }.orEmpty()

    private fun putFolders(list: List<JsonObject>) = editApp { put("folders", JsonArray(list)); put("folderCount", list.size) }

    private fun addShare(body: JsonObject): RemoteAnswer {
        if (!has(ManagedCaps.SHARES)) return refuse(404, "not_found")
        unknownField(body, "name", "path")?.let { return it }
        denied(ManagedOp.SHARES_ADD)?.let { return it }
        val name = body.text("name")
        if (!isShareName(name)) return refuse(400, "bad_request")
        if (folders().any { it.text("name").equals(name, ignoreCase = true) }) return refuse(409, "refused", "the name is taken")
        if (storage[body.text("path")] == null) return refuse(409, "refused", "not a folder")
        putFolders(folders() + buildJsonObject { put("name", name); put("path", body.text("path")) })
        return ok()
    }

    private fun removeShare(body: JsonObject): RemoteAnswer {
        if (!has(ManagedCaps.SHARES)) return refuse(404, "not_found")
        unknownField(body, "name")?.let { return it }
        denied(ManagedOp.SHARES_REMOVE)?.let { return it }
        val name = body.text("name")
        if (folders().none { it.text("name") == name }) return refuse(404, "not_found")
        putFolders(folders().filter { it.text("name") != name })
        return ok()
    }

    private fun diag(op: ManagedOp, json: String): RemoteAnswer {
        if (!has(ManagedCaps.DIAG)) return refuse(404, "not_found")
        denied(op)?.let { return it }
        return RemoteAnswer(200, json)
    }

    // ─── The rules ───────────────────────────────────────────────────────────────────────

    private fun you(): JsonObject = status["you"] as? JsonObject ?: JsonObject(emptyMap())
    private fun caps(): List<String>? = (status["caps"] as? JsonArray)?.map { it.jsonPrimitive.content }
    private fun has(cap: String): Boolean = caps()?.contains(cap) == true

    /** The refusal for an op this caller may not use, by §3's table: the class first, then the level. */
    private fun denied(op: ManagedOp): RemoteAnswer? {
        val owner = you().text("class") == REMOTE_CLASS_OWNER
        if (op.owner && !owner) return refuse(403, "forbidden")
        if (op.full && status.text("level") != ManagedLevel.FULL) return refuse(403, "needs_full")
        return null
    }

    /** A body may carry only what its route knows: anything else is a 400. */
    private fun unknownField(body: JsonObject, vararg known: String?): RemoteAnswer? =
        if (body.keys.any { it !in known }) refuse(400, "bad_request") else null

    private fun ok(body: JsonObject = JsonObject(emptyMap())) = RemoteAnswer(200, body.toString())

    private fun refuse(status: Int, error: String, message: String = "") =
        RemoteAnswer(status, buildJsonObject { put("error", error); if (message.isNotEmpty()) put("message", message) }.toString())

    companion object {
        /** The settings whose other value takes Full control (§6.1). */
        private val NEEDS_FULL = setOf(ManagedKeys.TAILDRIVE, ManagedKeys.TAILDRIVE_PROXY, ManagedKeys.ADVERTISE_EXIT_NODE, ManagedKeys.ADVERTISE_ROUTES)

        /** How many entries of a folder one answer carries (§10). */
        const val STORAGE_PAGE = 500

        /** What a status carries that the first build's does not. */
        private val LATER_FIELDS = setOf("caps", "level", "guard", "device", "advertise", "fetch", "app")

        private fun parse(json: String): JsonObject = AppJson.parseToJsonElement(json).jsonObject

        /** An answer for [script]: [status] with the refusal's word, and its sentence or `after`. */
        fun refusal(status: Int, error: String, message: String = "", after: Long? = null) = RemoteAnswer(
            status,
            buildJsonObject {
                put("error", error)
                if (message.isNotEmpty()) put("message", message)
                if (after != null) put("after", after)
            }.toString()
        )

        /** No answer at all, as from a device that is away. */
        val SILENCE = RemoteAnswer(status = 0, error = "unreachable")
    }
}

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()
private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.with(key: String, value: JsonElement?): JsonObject = if (value == null) without(key) else JsonObject(this + (key to value))
private fun JsonObject.without(vararg keys: String): JsonObject = JsonObject(filterKeys { it !in keys })
