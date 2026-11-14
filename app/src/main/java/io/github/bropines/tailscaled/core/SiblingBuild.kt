package io.github.bropines.tailscaled.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.MessageDigest

/**
 * The other build of the app on the same device, and whether starting this one
 * beside it is going to hurt.
 *
 * TailSocks ships as two packages that install side by side: the release
 * `io.github.bropines.tailscaled` and the development build with the `.dev`
 * suffix (`applicationIdSuffix` in app/build.gradle.kts), signed with different
 * keys. Running both at once breaks in ways nobody can see from either app:
 *  - the same node key (a backup of one restored into the other): the two daemons
 *    are one node to control, and knock each other off the tailnet in turn;
 *  - Root Mode with `tailscale0` in both: one interface name, one table 52/53, the
 *    same policy rules and iptables chains — each start rewrites the other's;
 *  - TUN in both: Android has one VPN slot, and the second start takes it;
 *  - equal SOCKS5 / HTTP / DNS proxy ports: the second daemon cannot bind.
 *
 * Each build answers questions about itself through [SiblingStateProvider]
 * (`<applicationId>.sibling`), and the main screen asks the other build before a
 * start it was tapped into ([SiblingDialog]). The provider carries no permission
 * — a signature permission cannot work across two signing keys — so it answers
 * only the sibling's package and carries nothing sensitive in any case: running
 * or not, the mode, three port numbers and an 8-hex-character hash of the node's
 * stable ID. Never add an address, a name, a key or an account to it.
 *
 * The app never stops or changes the sibling: it only says what it found.
 */
object SiblingBuild {
    private const val TAG = "SiblingBuild"

    const val RELEASE_PACKAGE = "io.github.bropines.tailscaled"
    private const val DEV_SUFFIX = ".dev"
    const val AUTHORITY_SUFFIX = ".sibling"

    /** Bumped when a column changes meaning; a reader ignores a row it does not understand. */
    const val SCHEMA = 1

    // Cursor columns of the provider's single row.
    const val COL_SCHEMA = "schema"
    const val COL_RUNNING = "running"
    const val COL_MODE = "mode"
    const val COL_ROOT_TUN = "root_tun"
    const val COL_SOCKS = "socks_port"
    const val COL_HTTP = "http_port"
    const val COL_DNS = "dns_port"
    const val COL_NODE = "node"
    val COLUMNS = arrayOf(COL_SCHEMA, COL_RUNNING, COL_MODE, COL_ROOT_TUN, COL_SOCKS, COL_HTTP, COL_DNS, COL_NODE)

    /**
     * The provider's answer within this budget, or the start goes ahead as if
     * there were no sibling. A running sibling has its process up and answers in
     * a few milliseconds; one that is not running has to be cold-started by
     * Android to answer at all, which takes longer than this — and "not running"
     * is exactly the answer a timeout stands for.
     */
    const val PROVIDER_BUDGET_MS = 300L

    /**
     * Root Mode only: the extra time the root-shell check of the sibling's
     * daemon may take. A root daemon outlives its app, so a sibling whose app is
     * gone (or that says "stopped") may still hold `tailscale0`; asking `su`
     * costs a shell, a few hundred milliseconds, inside a Root Mode start that
     * spends seconds in root shells of its own.
     */
    const val ROOT_BUDGET_MS = 1_200L

    enum class Mode(val wire: String) {
        PROXY("proxy"), TUN("tun"), ROOT("root");

        companion object {
            fun fromWire(value: String?): Mode? = entries.firstOrNull { it.wire == value }
        }
    }

    /**
     * What one build says about itself. For "this" build, the one about to start,
     * [running] is not consulted: it describes what the start will be.
     * A port of 0 is a listener that is switched off.
     */
    data class State(
        val running: Boolean,
        val mode: Mode,
        /** Root Mode with the `tailscale0` interface (`root_tun_enabled`); false outside Root Mode. */
        val rootTun: Boolean,
        val socksPort: Int,
        val httpPort: Int,
        val dnsPort: Int,
        /** [fingerprint] of the active profile's node, or "" when it cannot be read cheaply. */
        val node: String,
    ) {
        /** The provider's row, in [COLUMNS] order. */
        fun toRow(): Array<Any> = arrayOf(
            SCHEMA, if (running) 1 else 0, mode.wire, if (rootTun) 1 else 0, socksPort, httpPort, dnsPort, node,
        )

        companion object {
            /**
             * A row back into a state, or null when it is not one this build
             * understands (another schema, an unknown mode). [get] reads a column
             * by name as text; a missing column is null.
             */
            fun fromRow(get: (String) -> String?): State? {
                if (get(COL_SCHEMA)?.toIntOrNull() != SCHEMA) return null
                val mode = Mode.fromWire(get(COL_MODE)) ?: return null
                fun int(col: String) = get(col)?.toIntOrNull()?.takeIf { it in 0..65535 } ?: 0
                return State(
                    running = get(COL_RUNNING) == "1",
                    mode = mode,
                    rootTun = mode == Mode.ROOT && get(COL_ROOT_TUN) == "1",
                    socksPort = int(COL_SOCKS),
                    httpPort = int(COL_HTTP),
                    dnsPort = int(COL_DNS),
                    node = get(COL_NODE).orEmpty().takeIf { FINGERPRINT.matches(it) }.orEmpty(),
                )
            }
        }
    }

    /** What to warn about, most severe first: [siblingConflict] returns the first that holds. */
    enum class Conflict { SAME_NODE, BOTH_ROOT_TUN, VPN_SLOT, PORTS }

    /** The sibling as found: its package, whether it is the dev build, and what it said. */
    data class Found(val packageName: String, val isDev: Boolean, val conflict: Conflict)

    private val FINGERPRINT = Regex("[0-9a-f]{8}")

    /** The other build's package for [own]: the release for the dev build, and the other way round. */
    fun siblingPackage(own: String): String =
        if (own.endsWith(DEV_SUFFIX)) own.removeSuffix(DEV_SUFFIX) else own + DEV_SUFFIX

    /**
     * The sibling's daemon socket, derived from our own data directory so that a
     * secondary user (`/data/user/10/…`) gets its own: the package is one path
     * segment of it. Null when [ownFilesDir] does not contain [own] as a segment.
     */
    fun siblingSocketPath(ownFilesDir: String, own: String, sibling: String): String? {
        val segment = "/$own/"
        val dir = "$ownFilesDir/"
        val at = dir.indexOf(segment)
        if (at < 0) return null
        return dir.substring(0, at) + "/$sibling/" + dir.substring(at + segment.length) + "tailscaled.sock"
    }

    /** A short, non-reversible tag of a node's stable ID: the first 8 hex characters of its SHA-256. */
    fun fingerprint(stableNodeId: String): String {
        if (stableNodeId.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(stableNodeId.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * The stable node ID of the current profile, from the daemon's state values
     * already base64-decoded: `_current-profile` holds the profile's key
     * (`profile-xxxx`), `_profiles` maps profile IDs to login profiles, each
     * with its `Key` and the `NodeID` control assigned. Null when either is
     * missing or the node was never registered.
     */
    fun currentNodeId(decoded: Map<String, String>): String? {
        val current = decoded["_current-profile"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val profiles = runCatching { AppJson.parseToJsonElement(decoded["_profiles"] ?: return null).jsonObject }
            .getOrNull() ?: return null
        val profile = profiles.values.mapNotNull { it as? JsonObject }
            .firstOrNull { (it["Key"] as? JsonPrimitive)?.content == current } ?: return null
        return (profile["NodeID"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    /**
     * Decides what to tell the user who is about to start [mine] while the
     * sibling is in [theirs]. Nothing unless the sibling runs.
     *  - SAME_NODE: equal non-empty fingerprints — one node, two daemons.
     *  - BOTH_ROOT_TUN: both in Root Mode with `tailscale0`.
     *  - VPN_SLOT: both in TUN mode — the start would take the sibling's VPN slot.
     *    Root Mode is below Android's VPN plumbing and holds no slot.
     *  - PORTS: an equal non-zero SOCKS5, HTTP or DNS proxy port. Every mode
     *    listens on them: the daemon takes `--socks5-server` in all three, and the
     *    bridge's DNS proxy runs whenever its address is set.
     */
    fun siblingConflict(mine: State, theirs: State): Conflict? {
        if (!theirs.running) return null
        if (mine.node.isNotEmpty() && mine.node == theirs.node) return Conflict.SAME_NODE
        if (mine.mode == Mode.ROOT && mine.rootTun && theirs.mode == Mode.ROOT && theirs.rootTun) return Conflict.BOTH_ROOT_TUN
        if (mine.mode == Mode.TUN && theirs.mode == Mode.TUN) return Conflict.VPN_SLOT
        val minePorts = listOf(mine.socksPort, mine.httpPort, mine.dnsPort).filter { it > 0 }.toSet()
        val theirPorts = setOf(theirs.socksPort, theirs.httpPort, theirs.dnsPort)
        if (minePorts.any { it in theirPorts }) return Conflict.PORTS
        return null
    }

    /**
     * This build's state as the provider tells it and as a start compares it.
     * [withLiveness] asks whether the service is meant to run and the daemon
     * answers ([ProxyState]: a preference and, in Root Mode, one socket connect —
     * no root shell); without it [State.running] is false.
     */
    fun localState(context: Context, withLiveness: Boolean): State {
        val root = GlobalSettings.isRootModeEnabled(context)
        val mode = when {
            root -> Mode.ROOT
            GlobalSettings.isTunModeEnabled(context) -> Mode.TUN
            else -> Mode.PROXY
        }
        val running = withLiveness && ProxyState.isUserLetRunning(context) && ProxyState.isActualRunning(context)
        return State(
            running = running,
            mode = mode,
            rootTun = root && GlobalSettings.isRootTunEnabled(context),
            socksPort = NetAddr.port(GlobalSettings.getSocks5BindAddr(context)) ?: 0,
            httpPort = NetAddr.port(GlobalSettings.getHttpProxyBindAddr(context)) ?: 0,
            dnsPort = NetAddr.port(GlobalSettings.getDnsProxyBindAddr(context)) ?: 0,
            node = localFingerprint(context),
        )
    }

    /**
     * [fingerprint] of the active profile's node, read from its state file — one
     * small file, no daemon call. Empty when the file is not there or not
     * readable: in Root Mode it belongs to root while the daemon runs.
     */
    private fun localFingerprint(context: Context): String = try {
        val file = File(context.filesDir, "states/${AccountManager.activeId(context)}/tailscaled.state")
        if (!file.canRead()) "" else {
            val decoded = AppJson.parseToJsonElement(file.readText()).jsonObject.mapNotNull { (k, v) ->
                val encoded = (v as? JsonPrimitive)?.content ?: return@mapNotNull null
                runCatching { k to String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
            }.toMap()
            currentNodeId(decoded)?.let(::fingerprint).orEmpty()
        }
    } catch (e: Exception) {
        ""
    }

    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Asks the sibling, if one is installed, whether starting this build now
     * conflicts with it. Null on no conflict and on every failure — not
     * installed, an older version without the provider, a timeout, an exception:
     * the caller then starts exactly as it would have.
     *
     * The read runs detached and is only awaited within the budget: a binder
     * call or a root shell cannot be interrupted, and a timeout must not wait
     * for it to finish.
     */
    suspend fun check(context: Context): Found? {
        val app = context.applicationContext
        val root = GlobalSettings.isRootModeEnabled(app)
        val budget = PROVIDER_BUDGET_MS + if (root) ROOT_BUDGET_MS else 0L
        val work = readScope.async {
            runCatching { checkBlocking(app) }
                .onFailure { Log.w(TAG, "sibling check failed: ${it.message}") }
                .getOrNull()
        }
        return withTimeoutOrNull(budget) { work.await() }
    }

    private fun checkBlocking(context: Context): Found? {
        val own = context.packageName
        val sibling = siblingPackage(own)
        if (!isInstalled(context, sibling)) return null
        val mine = localState(context, withLiveness = false)
        var theirs = queryProvider(context, sibling)
        // A root daemon outlives its app, and the provider of an older sibling is
        // not there at all: in Root Mode, look for the sibling's daemon process.
        if (mine.mode == Mode.ROOT && (theirs == null || !theirs.running)) {
            val socket = siblingSocketPath(context.filesDir.absolutePath, own, sibling)
            if (socket != null && RootUtils.isDaemonProcessAlive(socket)) {
                // What the provider said stands where it said anything; an unknown
                // sibling daemon is taken for the Root Mode default, with tailscale0.
                theirs = (theirs ?: State(true, Mode.ROOT, true, 0, 0, 0, "")).copy(running = true)
            }
        }
        val conflict = theirs?.let { siblingConflict(mine, it) } ?: return null
        return Found(sibling, sibling.endsWith(DEV_SUFFIX), conflict)
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** The sibling's row, or null when it has no provider, refuses, or answers something else. */
    private fun queryProvider(context: Context, sibling: String): State? {
        val uri = Uri.parse("content://$sibling$AUTHORITY_SUFFIX/state")
        // Unstable: a sibling that dies mid-call must not take this process with it.
        val client = context.contentResolver.acquireUnstableContentProviderClient(uri) ?: return null
        return try {
            client.query(uri, COLUMNS, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                State.fromRow { col -> c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) } }
            }
        } finally {
            client.close()
        }
    }

    /** The sibling's launcher activity, for "Open it"; null when it has none we can see. */
    fun launchIntent(context: Context, pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
