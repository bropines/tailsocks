package io.github.bropines.tailscaled.admin

import android.content.Context
import android.util.Log
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.profile.AdminProfiles
import io.github.bropines.tailscaled.models.PeerData
import kotlinx.coroutines.runBlocking

/**
 * The Tailscale version a peer runs, from the only place that knows it.
 *
 * The daemon does not: ipnstate.PeerStatus has no Version field, and the Hostinfo the control
 * plane distributes for a peer carries its hostname, OS, services and SSH host keys — the
 * version is stripped before it leaves the coordination server. The Admin API's device list
 * does carry it (ApiDevice.clientVersion), so when an admin profile exists for the current
 * tailnet this asks that list, once, and answers every peer out of the answer.
 *
 * Rules:
 *  - No admin profile for the tailnet these peers are in, or none with a credential → null at
 *    once, with no network touched. The peer sheet then draws no version row at all. The
 *    tailnet is told by the peers' own MagicDNS names (AdminProfiles.forTailnet).
 *  - The device list is fetched at most once per [TTL_OK_MS] and kept in memory; a failure is
 *    logged, remembered for [TTL_FAILED_MS] so a retry storm cannot follow a refresh storm, and
 *    is "no data" to the caller — never an error to show.
 *  - A peer is matched to a device by node key (PeerData.publicKey ↔ ApiDevice.nodeKey), and
 *    by FQDN only when one side has no key to offer: a key that is there and does not match
 *    is a different device, not a reason to guess by name.
 *
 * Read-only: it lists devices and nothing else, so it needs no unlock. Blocking, so call it
 * off the main thread. Safe from several threads at once: callers that arrive during a fetch
 * wait for that same fetch, and no lock is held while the network answers.
 */
object PeerVersionSource {
    private const val TAG = "PeerVersionSource"
    private const val TTL_OK_MS = 5 * 60 * 1000L
    private const val TTL_FAILED_MS = 60 * 1000L

    /**
     * The version of every peer in [peers] the Admin API reports one for, keyed by
     * PeerData.id. Peers without an id, without a match, or without a version are simply
     * absent. One profile lookup and one look at the cache for the whole list — this is what
     * a list screen should call, once per load. Empty for "no data" of any kind.
     */
    fun versionsFor(context: Context, peers: Collection<PeerData>): Map<String, String> = try {
        val devices = devices(context, suffixOf(peers))
        if (devices.isNullOrEmpty()) emptyMap()
        else buildMap {
            for (peer in peers) {
                val id = peer.id ?: continue
                versionOf(peer, devices)?.let { put(id, it) }
            }
        }
    } catch (e: Exception) {
        // Nothing here is worth an error on screen; the sheet just has no version row.
        Log.w(TAG, "Version lookup failed: ${e.javaClass.simpleName}")
        emptyMap()
    }

    /**
     * The endpoints ("ip:port") every peer in [peers] last reported (ApiDevice.clientConnectivity,
     * only in a list fetched with fields=all), keyed by PeerData.id, from the same cached list as
     * the versions — what tells which node a peer relay's address belongs to. Peers without a
     * match or without endpoints are absent; empty for "no data" of any kind.
     */
    fun endpointsFor(context: Context, peers: Collection<PeerData>): Map<String, List<String>> = try {
        val devices = devices(context, suffixOf(peers))
        if (devices.isNullOrEmpty()) emptyMap()
        else buildMap {
            for (peer in peers) {
                val id = peer.id ?: continue
                deviceOf(peer, devices)?.clientConnectivity?.endpoints?.takeIf { it.isNotEmpty() }?.let { put(id, it) }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Endpoint lookup failed: ${e.javaClass.simpleName}")
        emptyMap()
    }

    /** [peer]'s version out of an already fetched [devices] list — see the matching rules above. */
    private fun versionOf(peer: PeerData, devices: List<ApiDevice>): String? =
        deviceOf(peer, devices)?.clientVersion?.trim()?.takeIf { it.isNotEmpty() }

    /** [peer]'s device in [devices], by the matching rules above. */
    private fun deviceOf(peer: PeerData, devices: List<ApiDevice>): ApiDevice? {
        val peerKey = normalizeKey(peer.publicKey)
        val peerName = normalizeName(peer.dnsName)
        return devices.firstOrNull { d ->
            val deviceKey = normalizeKey(d.nodeKey)
            if (peerKey != null && deviceKey != null) deviceKey == peerKey
            else peerName != null && normalizeName(d.name) == peerName
        }
    }

    /** "tail1234.ts.net" from the first peer whose MagicDNS name has a domain. */
    private fun suffixOf(peers: Collection<PeerData>): String? = peers.firstNotNullOfOrNull { p ->
        normalizeName(p.dnsName)?.substringAfter('.', "")?.takeIf { it.isNotEmpty() }
    }

    /**
     * The tailnet's device list, from memory while the snapshot stands and from the Admin API
     * otherwise. Null when no admin profile serves this tailnet. An empty list is the
     * remembered shape of a failed fetch: every peer then misses, and the sheet shows no
     * version for a while, which is the right answer to an API that did not answer.
     *
     * Callers that arrive while a fetch is under way wait for that one ([SharedFetch]); none of
     * them holds a lock while the network answers, so a dead API pins no thread behind another.
     */
    private fun devices(context: Context, suffix: String?): List<ApiDevice>? {
        val profile = AdminProfiles.forTailnet(context, suffix) ?: return null
        if (!AdminProfiles.hasCredential(context, profile)) return null
        val app = context.applicationContext
        // The callers are blocking (PeersActivity, PeerPath, both off the main thread).
        return runBlocking { lists(app).get(profile) }
    }

    /** Made on first use, with the application context; an edited profile is another key, fetched again. */
    private var shared: SharedFetch<AdminProfile, List<ApiDevice>>? = null

    @Synchronized
    private fun lists(app: Context): SharedFetch<AdminProfile, List<ApiDevice>> = shared ?: SharedFetch<AdminProfile, List<ApiDevice>>(
        okTtlMs = TTL_OK_MS, failedTtlMs = TTL_FAILED_MS, failed = emptyList(),
    ) { profile ->
        try {
            AdminProfiles.newBackend(app, profile).listDevices().items
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Admin API device list for ${profile.displayName} failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }.also { shared = it }

    /** "nodekey:ABC…" and "nodekey:abc…" are the same key. Blank is no key. */
    private fun normalizeKey(key: String?): String? =
        key?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** The daemon writes the FQDN with a trailing dot ("host.tail1234.ts.net."), the Admin
     *  API without; DNS names compare case-insensitively. Blank is no name. */
    private fun normalizeName(name: String?): String? =
        name?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
}
