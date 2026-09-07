package io.github.bropines.tailscaled.admin

import android.content.Context
import android.util.Log
import io.github.bropines.tailscaled.models.PeerData

/**
 * The Tailscale version a peer runs, from the only place that knows it.
 *
 * The daemon does not: ipnstate.PeerStatus has no Version field, and the Hostinfo the control
 * plane distributes for a peer carries its hostname, OS, services and SSH host keys — the
 * version is stripped before it leaves the coordination server. The Admin API's device list
 * does carry it (ApiDevice.clientVersion), so when the user has set the Admin Console up for
 * the current tailnet this asks that list, once, and answers every peer out of the answer.
 *
 * Rules:
 *  - No credentials for the current tailnet, or no tailnet known yet → null at once, with no
 *    network touched. The peer sheet then draws no version row at all.
 *  - The device list is fetched at most once per [TTL_OK_MS] and kept in memory; a failure is
 *    logged, remembered for [TTL_FAILED_MS] so a retry storm cannot follow a refresh storm, and
 *    is "no data" to the caller — never an error to show.
 *  - A peer is matched to a device by node key (PeerData.publicKey ↔ ApiDevice.nodeKey), and
 *    by FQDN only when one side has no key to offer: a key that is there and does not match
 *    is a different device, not a reason to guess by name.
 *
 * Blocking, so call it off the main thread. Safe to call from several coroutines at once:
 * they take turns on the cache and only one of them fetches.
 */
object PeerVersionSource {
    private const val TAG = "PeerVersionSource"
    private const val TTL_OK_MS = 5 * 60 * 1000L
    private const val TTL_FAILED_MS = 60 * 1000L

    /** One answer from the Admin API, or one failure to get it, and how long it stands for. */
    private class Snapshot(
        /** The settings the answer was fetched with: a changed token or tailnet invalidates it. */
        val settings: AdminApiSettings,
        val devices: List<ApiDevice>,
        val expiresAt: Long
    )

    private val lock = Any()
    private var snapshot: Snapshot? = null

    /**
     * The version of every peer in [peers] the Admin API reports one for, keyed by
     * PeerData.id. Peers without an id, without a match, or without a version are simply
     * absent. One settings read and one look at the cache for the whole list, however long it
     * is — this is what a list screen should call, once per load. Empty for "no data" of any
     * kind.
     */
    fun versionsFor(context: Context, peers: Collection<PeerData>): Map<String, String> = try {
        val devices = devices(context)
        if (devices.isNullOrEmpty()) emptyMap()
        else buildMap {
            for (peer in peers) {
                val id = peer.id ?: continue
                versionOf(peer, devices)?.let { put(id, it) }
            }
        }
    } catch (e: Exception) {
        // Nothing here is worth an error on screen; the sheet just has no version row.
        Log.w(TAG, "Version lookup failed: ${e.message}")
        emptyMap()
    }

    /** The version [peer] runs as the Admin API reports it, or null for "no data" of any kind. */
    fun versionFor(context: Context, peer: PeerData): String? = try {
        devices(context)?.let { versionOf(peer, it) }
    } catch (e: Exception) {
        Log.w(TAG, "Version lookup failed: ${e.message}")
        null
    }

    /** [peer]'s version out of an already fetched [devices] list — see the matching rules above. */
    private fun versionOf(peer: PeerData, devices: List<ApiDevice>): String? {
        val peerKey = normalizeKey(peer.publicKey)
        val peerName = normalizeName(peer.dnsName)
        val device = devices.firstOrNull { d ->
            val deviceKey = normalizeKey(d.nodeKey)
            if (peerKey != null && deviceKey != null) deviceKey == peerKey
            else peerName != null && normalizeName(d.name) == peerName
        }
        return device?.clientVersion?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Forget what was fetched, so the next [versionsFor] or [versionFor] asks again. */
    fun invalidate() = synchronized(lock) { snapshot = null }

    /**
     * The tailnet's device list, from memory while the snapshot stands and from the Admin API
     * otherwise. Null when the Admin Console is not set up for the current tailnet. An empty
     * list is the remembered shape of a failed fetch: every peer then misses, and the sheet
     * shows no version for a while, which is the right answer to an API that did not answer.
     */
    private fun devices(context: Context): List<ApiDevice>? {
        val tailnet = AdminApiSettings.lastKnownTailnet(context).trim()
        if (tailnet.isBlank()) return null
        val settings = AdminApiSettings.read(context, tailnet)
        if (!settings.hasCredentials) return null

        synchronized(lock) {
            val now = System.currentTimeMillis()
            snapshot?.let { if (it.settings == settings && now < it.expiresAt) return it.devices }
            val fetched = try {
                settings.newClient(context).listDevices()
            } catch (e: Exception) {
                Log.w(TAG, "Admin API device list for $tailnet failed: ${e.message}")
                null
            }
            snapshot = Snapshot(
                settings,
                fetched ?: emptyList(),
                now + if (fetched != null) TTL_OK_MS else TTL_FAILED_MS
            )
            return snapshot!!.devices
        }
    }

    /** "nodekey:ABC…" and "nodekey:abc…" are the same key. Blank is no key. */
    private fun normalizeKey(key: String?): String? =
        key?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** The daemon writes the FQDN with a trailing dot ("host.tail1234.ts.net."), the Admin
     *  API without; DNS names compare case-insensitively. Blank is no name. */
    private fun normalizeName(name: String?): String? =
        name?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
}
