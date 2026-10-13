package io.github.bropines.tailscaled.core

import kotlinx.serialization.Serializable

/**
 * What the tailnet's policy lets this device do with Taildrive, as the running daemon reports it.
 *
 * Taildrive is off until the policy turns it on, device by device. The core (ipn/ipnlocal,
 * tailcfg/nodecap) wants two node attributes on the device and one grant between the devices:
 *
 * - `drive:share` on a device that shares folders. Without it the daemon refuses every share call
 *   with 403 and its PeerAPI answers 404, so the folders a person set up are kept but nobody sees them;
 * - `drive:access` on a device that opens other devices' folders. Without it the daemon closes
 *   the connection to `100.100.100.100:8080` without a word — a client sees an empty reply, the
 *   bridge's own proxy logs `EOF` and answers 502 — and it lists no device at all;
 * - a grant carrying `tailscale.com/cap/drive` from the devices that open to the devices that
 *   share. The sharer reads it as the peer's permission (403 without it), the opener's daemon
 *   derives `drive-sharer` from it and lists only the peers that have it.
 *
 * The two attributes are the device's own and are in its network map, which is what this reads
 * (appctr GetTaildriveAccessJSON). The grant is not visible from here: a missing one shows as an
 * empty list of devices, not as a refusal.
 */
object TaildriveAccess {

    /** What the daemon says: nothing yet, or what the server decided (appctr GetTaildriveAccessJSON). */
    @Serializable
    data class Report(
        /** False while there is no daemon or no network map: nothing to say yet. */
        val known: Boolean = false,
        /** The self node carries `drive:share`. */
        val canShare: Boolean = false,
        /** The self node carries `drive:access`. */
        val canAccess: Boolean = false,
    ) {
        /** May this device share folders. */
        val share: Verdict get() = if (!known) Verdict.UNKNOWN else if (canShare) Verdict.ALLOWED else Verdict.DENIED

        /** May this device open other devices' folders. */
        val access: Verdict get() = if (!known) Verdict.UNKNOWN else if (canAccess) Verdict.ALLOWED else Verdict.DENIED
    }

    /** The server's decision on one thing, or none yet: three answers, never a guess from "false". */
    enum class Verdict { UNKNOWN, ALLOWED, DENIED }

    /** The answer when the bridge cannot give one: a daemon that is off, a map that has not arrived, a bridge without the call. */
    val UNKNOWN = Report()

    /** [json] as the bridge sends it; [UNKNOWN] for nothing or something unreadable. */
    fun parse(json: String?): Report {
        if (json.isNullOrEmpty()) return UNKNOWN
        return runCatching { AppJson.decodeFromString(Report.serializer(), json) }.getOrDefault(UNKNOWN)
    }

    /**
     * Blocking (a LocalAPI round trip). Asks the bridge by name: the call came with the bridge
     * that has `GetTaildriveAccessJSON`, and an older one — a build whose Go core was not rebuilt
     * — answers [UNKNOWN] here instead of failing to load. R8 keeps `appctr.**` whole, so the
     * name survives a release build.
     */
    fun report(): Report = parse(fromBridge())

    private fun fromBridge(): String? = try {
        Class.forName("appctr.Appctr").getMethod("getTaildriveAccessJSON").invoke(null) as? String
    } catch (_: Throwable) {
        // No such method, no native library (a JVM test), no daemon: all of them mean "cannot tell".
        null
    }
}
