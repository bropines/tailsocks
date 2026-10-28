package io.github.bropines.tailscaled.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The node's own switches in Settings → Account & connection that used to exist only as
 * extra arguments: shields up, LAN access through an exit node, posture reporting, this
 * phone as a peer relay, the app connector, and Tailscale SSH.
 *
 * They are stored per profile, in `appctr_<id>`. A key that is absent means the switch was
 * never touched in the app, and then the daemon's own value is left alone — a `tailscale
 * set` from the console survives until the switch is used — the rule applyTagsAndRoutes
 * already follows for `advertise_exit_node`. The values travel in syncSettings' PATCH
 * (StartOptions.clientPrefs), before the extra arguments, so a flag typed there still wins;
 * Settings reads the flags back (Appctr.extraArgsPrefsJSON) and says so on the switch.
 */
object ClientPrefs {
    const val SHIELDS_UP = "shields_up"
    const val EXIT_NODE_LAN = "exit_node_allow_lan"
    const val REPORT_POSTURE = "report_posture"
    const val RELAY_ENABLED = "relay_server_enabled"
    const val RELAY_PORT = "relay_server_port"
    const val APP_CONNECTOR = "advertise_connector"
    const val RUN_SSH = "run_ssh"

    /** The AdvertiseRoutes applyTagsAndRoutes wrote last, comma-separated. */
    const val ROUTES_APPLIED = "advertise_routes_applied"

    /** Each switch and the `tailscale up` flag that sets the same pref. */
    enum class Field(val flag: String) {
        SHIELDS_UP("--shields-up"),
        EXIT_NODE_LAN("--exit-node-allow-lan-access"),
        REPORT_POSTURE("--report-posture"),
        RELAY("--relay-server-port"),
        APP_CONNECTOR("--advertise-connector"),
        SSH("--ssh"),
    }

    /** The peer relay: off, or listening on [port] — 0 lets the system pick a free one. */
    data class Relay(val enabled: Boolean, val port: Int = 0)

    /**
     * One value per switch. Depending on where it came from, null means "never set in
     * the app" ([stored]), "not set by the extra arguments" ([overrides]) or "the daemon
     * did not say" ([live]).
     */
    data class Values(
        val shieldsUp: Boolean? = null,
        val exitNodeAllowLan: Boolean? = null,
        val reportPosture: Boolean? = null,
        val relay: Relay? = null,
        val appConnector: Boolean? = null,
        val runSsh: Boolean? = null,
    ) {
        fun isSet(field: Field): Boolean = when (field) {
            Field.SHIELDS_UP -> shieldsUp != null
            Field.EXIT_NODE_LAN -> exitNodeAllowLan != null
            Field.REPORT_POSTURE -> reportPosture != null
            Field.RELAY -> relay != null
            Field.APP_CONNECTOR -> appConnector != null
            Field.SSH -> runSsh != null
        }
    }

    /** What this profile's switches hold, from its preferences (`SharedPreferences.all`). */
    fun stored(all: Map<String, *>): Values = Values(
        shieldsUp = all[SHIELDS_UP] as? Boolean,
        exitNodeAllowLan = all[EXIT_NODE_LAN] as? Boolean,
        reportPosture = all[REPORT_POSTURE] as? Boolean,
        relay = (all[RELAY_ENABLED] as? Boolean)?.let { Relay(it, parsePort(all[RELAY_PORT] as? String ?: "") ?: 0) },
        appConnector = all[APP_CONNECTOR] as? Boolean,
        runSsh = all[RUN_SSH] as? Boolean,
    )

    /**
     * What a start sends the daemon: Tailscale SSH only to a Root Mode one. The others are
     * started with the SSH server disabled (appctr/daemon.go), refuse RunSSH, and would take
     * the other switches down with it; the profile keeps the value for the next Root Mode run.
     */
    fun forDaemon(v: Values, rootMode: Boolean): Values = if (rootMode) v else v.copy(runSsh = null)

    /**
     * The ipn.MaskedPrefs fields for the switches that are set, each with its …Set flag,
     * as StartOptions.clientPrefs carries them; "" when none is. The relay turned off is
     * an explicit null port, which is how the daemon is told to stop it.
     */
    fun maskedPrefsJson(v: Values): String {
        val obj = buildJsonObject {
            v.shieldsUp?.let { put("ShieldsUp", it); put("ShieldsUpSet", true) }
            v.exitNodeAllowLan?.let { put("ExitNodeAllowLANAccess", it); put("ExitNodeAllowLANAccessSet", true) }
            v.reportPosture?.let { put("PostureChecking", it); put("PostureCheckingSet", true) }
            v.relay?.let { r ->
                put("RelayServerPort", if (r.enabled) JsonPrimitive(r.port) else JsonNull)
                put("RelayServerPortSet", true)
            }
            v.appConnector?.let {
                putJsonObject("AppConnector") { put("Advertise", it) }
                put("AppConnectorSet", true)
            }
            v.runSsh?.let { put("RunSSH", it); put("RunSSHSet", true) }
        }
        return if (obj.isEmpty()) "" else obj.toString()
    }

    /**
     * What the extra arguments set, from the bridge's reading of them (ExtraArgsPrefsJSON):
     * the same MaskedPrefs fields, so a field counts only with its …Set flag.
     */
    fun overrides(json: String): Values {
        val o = runCatching { AppJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return Values()
        fun set(name: String) = (o["${name}Set"] as? JsonPrimitive)?.booleanOrNull == true
        fun bool(name: String) = if (set(name)) (o[name] as? JsonPrimitive)?.booleanOrNull else null
        return Values(
            shieldsUp = bool("ShieldsUp"),
            exitNodeAllowLan = bool("ExitNodeAllowLANAccess"),
            reportPosture = bool("PostureChecking"),
            relay = if (set("RelayServerPort")) {
                when (val port = o["RelayServerPort"]) {
                    null, JsonNull -> Relay(false)
                    else -> Relay(true, (port as? JsonPrimitive)?.intOrNull ?: 0)
                }
            } else null,
            appConnector = if (set("AppConnector")) {
                ((o["AppConnector"] as? JsonObject)?.get("Advertise") as? JsonPrimitive)?.booleanOrNull ?: false
            } else null,
            runSsh = bool("RunSSH"),
        )
    }

    /** The parts of GET /localapi/v0/prefs these switches and applyTagsAndRoutes read. */
    @Serializable
    private data class DaemonPrefs(
        @SerialName("ShieldsUp") val shieldsUp: Boolean = false,
        @SerialName("ExitNodeAllowLANAccess") val exitNodeAllowLan: Boolean = false,
        @SerialName("PostureChecking") val reportPosture: Boolean = false,
        @SerialName("RelayServerPort") val relayServerPort: Int? = null,
        @SerialName("AppConnector") val appConnector: DaemonAppConnector = DaemonAppConnector(),
        @SerialName("AdvertiseRoutes") val advertiseRoutes: List<String>? = null,
        @SerialName("RunSSH") val runSsh: Boolean = false,
    )

    @Serializable
    private data class DaemonAppConnector(@SerialName("Advertise") val advertise: Boolean = false)

    /** The daemon's current values, from GET /localapi/v0/prefs; null when unreadable. */
    fun live(json: String): Values? = daemonPrefs(json)?.let {
        Values(
            shieldsUp = it.shieldsUp,
            exitNodeAllowLan = it.exitNodeAllowLan,
            reportPosture = it.reportPosture,
            relay = it.relayServerPort?.let { p -> Relay(true, p) } ?: Relay(false),
            appConnector = it.appConnector.advertise,
            runSsh = it.runSsh,
        )
    }

    /** AdvertiseRoutes as the daemon holds them now; null when unreadable. */
    fun liveRoutes(json: String): List<String>? = daemonPrefs(json)?.let { it.advertiseRoutes.orEmpty() }

    private fun daemonPrefs(json: String): DaemonPrefs? =
        if (json.isBlank() || json.startsWith("Error")) null
        else runCatching { AppJson.decodeFromString<DaemonPrefs>(json) }.getOrNull()

    /**
     * Whether the coordination server has turned this node's relay server off: the
     * `disable-relay-server` node attribute in the self node's CapMap of a status document.
     * The daemon then keeps it stopped whatever RelayServerPort says.
     */
    fun relayDisabledByControl(statusJson: String): Boolean = runCatching {
        val self = AppJson.parseToJsonElement(statusJson).jsonObject["Self"] as? JsonObject
        (self?.get("CapMap") as? JsonObject)?.containsKey(DISABLE_RELAY_SERVER) == true
    }.getOrDefault(false)

    private const val DISABLE_RELAY_SERVER = "disable-relay-server"

    /**
     * A relay port as typed: blank is 0 (any free port), otherwise 0–65535; null when it
     * is not a port at all.
     */
    fun parsePort(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return 0
        if (t.length > 5 || !t.all { it.isDigit() }) return null
        return t.toInt().takeIf { it in 0..65535 }
    }

    /**
     * Whether applyTagsAndRoutes may leave AdvertiseRoutes as the daemon holds them. Every
     * PATCH that sets AdvertiseRoutes makes the daemon throw away what its app connector
     * learned (LocalBackend.MaybeClearAppConnector), and the app re-sends its routes on
     * every start and every settings change — so while the connector runs, the write is
     * skipped when nothing the user owns changed: every wanted route is there, and the
     * rest are host routes the connector added (/32, /128) that the app did not write
     * last time. Anything else and the routes are written as before.
     */
    fun connectorRoutesCurrent(wanted: List<String>, current: List<String>, lastApplied: List<String>): Boolean {
        val cur = current.toSet()
        if (!wanted.all { it in cur }) return false
        val extra = cur - wanted.toSet()
        return extra.all { isHostRoute(it) && it !in lastApplied }
    }

    private fun isHostRoute(prefix: String): Boolean =
        (prefix.endsWith("/32") && '.' in prefix) || (prefix.endsWith("/128") && ':' in prefix)
}
