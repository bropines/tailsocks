package io.github.bropines.tailscaled.core

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import io.github.bropines.tailscaled.core.RootRouting.CHAIN_DNS
import io.github.bropines.tailscaled.core.RootRouting.DNS_CHAIN_ABSENT
import io.github.bropines.tailscaled.core.RootRouting.DNS_HOOK_FAILED
import io.github.bropines.tailscaled.core.RootRoutingScript.dnsHookInstall
import io.github.bropines.tailscaled.core.RootRoutingScript.dnsHookRemove
import io.github.bropines.tailscaled.core.RootShell.rootLog
import io.github.bropines.tailscaled.core.RootShell.runSu
import io.github.bropines.tailscaled.core.RootUtils.TAG
import io.github.bropines.tailscaled.core.RootUtils.UPSTREAM_PROBE_ZONE
import io.github.bropines.tailscaled.core.RootUtils.upstreamProbeHealthy
import io.github.bropines.tailscaled.core.RootUtils.upstreamProbeName
import io.github.bropines.tailscaled.models.StatusResponse
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The daemon's answer to `/localapi/v0/dns-query`: the raw DNS response
 * message, which Go encodes as base64 because the field is a `[]byte`.
 */
@Serializable
private class DnsQueryResponse(@SerialName("Bytes") val bytes: String? = null)

/**
 * The health gate of the device-wide DNS redirect: whether the nat OUTPUT
 * jumps into the DNS chain are armed ([setDnsRedirectHooked]), the MagicDNS
 * probe that decides it ([magicDnsAnswers]: this node's own name, then an
 * upstream name, through the daemon's resolver) and the small hand-written
 * LocalAPI client the probe and the adoption check use ([localApiGet]).
 */
internal object RootDnsGate {
    /** What the last hook/unhook put on the device, so only real transitions are logged. */
    @Volatile private var dnsRedirectHooked: Boolean? = null

    /** The verdict [magicDnsAnswers] last reached, named in the hook log as the reason. */
    @Volatile internal var lastMagicDnsReason: String = "MagicDNS has not been probed yet"

    /**
     * Hooks or unhooks the device-wide DNS redirect, leaving [CHAIN_DNS] built
     * either way. Returns true when the device is in the requested state
     * afterwards; safe to call when it already was.
     *
     * This is the health gate's actuator: the redirect points every port-53
     * packet on the phone at 100.100.100.100, so it may only be armed while
     * something answers there ([magicDnsAnswers]), and must come off again the
     * moment nothing does — without tearing down a chain that would then have
     * to be rebuilt from inputs only an apply holds.
     */
    fun setDnsRedirectHooked(hooked: Boolean): Boolean = setDnsRedirectHooked(hooked, lastMagicDnsReason)

    private fun setDnsRedirectHooked(hooked: Boolean, reason: String): Boolean {
        val script = buildString {
            if (hooked) {
                // A jump to a chain that does not exist is refused by iptables,
                // and the caller would be left believing DNS is redirected.
                append("iptables -w -t nat -S $CHAIN_DNS >/dev/null 2>&1 || { echo '$DNS_CHAIN_ABSENT'; exit 1; }\n")
                append(dnsHookInstall())
                // Both, read back: a UDP jump alone would leave every TCP query
                // — a large answer, a DoT-less resolver retrying — outside
                // MagicDNS while the caller was told the redirect is on.
                for (proto in listOf("udp", "tcp")) {
                    append(
                        "iptables -w -t nat -C OUTPUT -p $proto --dport 53 -j $CHAIN_DNS 2>/dev/null || " +
                            "{ echo '$DNS_HOOK_FAILED'; exit 1; }\n"
                    )
                }
            } else {
                append(dnsHookRemove())
            }
        }
        val res = runSu(if (hooked) "dns-redirect-arm" else "dns-redirect-disarm", script, timeoutMs = 10_000L)
        if (!res.ok) {
            rootLog(
                "WARN",
                if (hooked) {
                    "device-wide DNS redirect could not be armed (" +
                        (if (res.output.contains(DNS_CHAIN_ABSENT)) "the $CHAIN_DNS chain is not on this device; a full routing apply has to build it"
                        else "iptables refused the nat OUTPUT jump") + "); port 53 stays with the system resolver"
                } else {
                    "device-wide DNS redirect could not be removed; port 53 still goes to MagicDNS"
                }
            )
            return false
        }
        noteDnsHookState(hooked, reason)
        return true
    }

    /**
     * Logs a change of the redirect's state, naming what caused it. Repeats say
     * nothing, and neither does the first apply of a run: until then there is no
     * previous state to have changed from, and the apply's own summary already
     * reports what it installed.
     */
    internal fun noteDnsHookState(hooked: Boolean, reason: String) {
        val previous = dnsRedirectHooked
        dnsRedirectHooked = hooked
        if (previous == null || previous == hooked) return
        rootLog(
            "INFO",
            if (hooked) {
                "device-wide DNS redirect armed: port 53 on this device now goes to MagicDNS ($reason)"
            } else {
                "device-wide DNS redirect disarmed: port 53 goes back to the system resolver ($reason). " +
                    "The $CHAIN_DNS chain stays built, so re-arming it is two rules"
            }
        )
    }

    /**
     * Whether the daemon's own resolver answers a query right now.
     *
     * The redirect above sends every port-53 packet on the device to
     * 100.100.100.100, so it must only be armed while there is a working
     * resolver behind that address. The state that breaks this is not a crash
     * and does not show up on the health bus: turning "Accept DNS" off leaves
     * the daemon running with an empty dns.Config, and upstream's forwarder
     * deliberately does not raise dnsForwarderFailing for it — see
     * net/dns/resolver/forwarder.go, "No upstream resolver for this name isn't
     * a forwarder failure", which then logs "no upstream resolvers set,
     * returning SERVFAIL". Every query on the device would get SERVFAIL while
     * the bus stays quiet, which is why this asks the resolver instead of
     * waiting to be told.
     *
     * Two questions. The node's own MagicDNS name first: a configured resolver
     * answers it from its in-memory host map without touching the network, so
     * a failure there is a broken configuration, not a flaky link. Then a name
     * outside the tailnet ([upstreamProbeName]), which the resolver has to
     * forward. That is the query every app on the phone makes, and the own name
     * alone could not see its path fail: when the tailnet's DNS server is a peer
     * and the tunnel is down, the own name kept passing while every lookup on
     * the device timed out — and the daemon's "DNS unavailable" warning is
     * hidden behind its network-down one, so the health bus said nothing either.
     *
     * The outside name is a fresh random label under [UPSTREAM_PROBE_ZONE] on
     * every probe. A fixed name (`connectivitycheck.gstatic.com` until now) can
     * be answered from a cache on the way — the daemon's, the tailnet DNS
     * server's — for as long as its TTL, while the upstream behind it is dead;
     * a name nobody has asked before has to travel to the zone's own servers.
     * Their answer is NXDOMAIN, which counts as healthy, as NOERROR does
     * ([upstreamProbeHealthy]); SERVFAIL, REFUSED, any other code and silence
     * mean nothing upstream is reachable.
     *
     * What this validates is the resolver and what it forwards to, not the
     * path to it. localapi is answered in-process, inside the daemon, and never
     * traverses the nat OUTPUT DNAT — this is not an end-to-end test of the
     * redirect, and a device whose iptables rules are wrong will still pass it.
     *
     * No root shell: two unix-socket reads, the second waiting at most
     * [UPSTREAM_PROBE_TIMEOUT_MS] for the forwarder to give up.
     */
    fun magicDnsAnswers(context: Context): Boolean {
        val name = selfDnsName(context)
        if (name.isNullOrEmpty()) {
            lastMagicDnsReason = "the daemon reports no MagicDNS name for this node"
            return false
        }
        val own = dnsQuery(context, name, 3_000)
        if (own.rcode != 0) {
            lastMagicDnsReason = own.failure ?: "the daemon's resolver returned ${rcodeName(own.rcode)} for this node's own name $name"
            return false
        }
        val probeName = upstreamProbeName()
        val upstream = dnsQuery(context, probeName, UPSTREAM_PROBE_TIMEOUT_MS)
        if (upstream.failure != null || !upstreamProbeHealthy(upstream.rcode)) {
            lastMagicDnsReason = upstream.failure?.let { "own name answered, but $it" }
                ?: "own name answered, but the daemon's resolver returned ${rcodeName(upstream.rcode)} for " +
                    "$probeName: it cannot reach its upstream DNS servers"
            return false
        }
        lastMagicDnsReason = "the daemon's resolver answered $name (${own.answers} record(s)) and " +
            "a fresh name under $UPSTREAM_PROBE_ZONE (${rcodeName(upstream.rcode)})"
        return true
    }

    private class DnsProbe(val rcode: Int, val answers: Int, val failure: String?)

    /** One query through localapi; [DnsProbe.failure] says why there was no DNS message. */
    private fun dnsQuery(context: Context, name: String, timeoutMs: Int): DnsProbe {
        val encoded = java.net.URLEncoder.encode(name, "UTF-8")
        val body = localApiGet(context, "/localapi/v0/dns-query?name=$encoded&type=A", timeoutMs)
            ?: return DnsProbe(-1, 0, "the daemon's localapi did not answer a DNS query for $name")
        val packed = runCatching {
            AppJson.decodeFromString(DnsQueryResponse.serializer(), body).bytes
                ?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
        }.getOrNull()
        // A DNS message shorter than its own header is not one; so is the plain
        // text an error status carries, which is quoted here because "the
        // resolver said no" and "localapi said no" call for different fixes.
        if (packed == null || packed.size < 12) {
            return DnsProbe(-1, 0, "the daemon's localapi returned no DNS message for $name (${body.trim().take(80)})")
        }
        val rcode = packed[3].toInt() and 0x0f
        val answers = ((packed[6].toInt() and 0xff) shl 8) or (packed[7].toInt() and 0xff)
        return DnsProbe(rcode, answers, null)
    }

    /** Longer than the forwarder's own retries take to end in SERVFAIL. */
    private const val UPSTREAM_PROBE_TIMEOUT_MS = 6_000

    private fun rcodeName(rcode: Int): String = when (rcode) {
        0 -> "NOERROR"
        1 -> "FORMERR"
        2 -> "SERVFAIL"
        3 -> "NXDOMAIN"
        4 -> "NOTIMP"
        5 -> "REFUSED"
        else -> "rcode $rcode"
    }

    /**
     * This node's MagicDNS name.
     *
     * From the bridge when it is attached, which costs nothing — it is served
     * from the state the daemon already pushed over the bus. Root Mode outlives
     * the app process, though, so the bridge may be attached to nothing while
     * the daemon runs perfectly well; localapi answers then.
     */
    private fun selfDnsName(context: Context): String? {
        val fromBridge = runCatching { appctr.Appctr.getSelfDNSName() }.getOrNull()
        if (!fromBridge.isNullOrBlank()) return fromBridge.trimEnd('.')
        // Only Self is read: without peers=false the daemon serialises every peer of the tailnet for it.
        val status = localApiGet(context, "/localapi/v0/status?peers=false") ?: return null
        val self = runCatching { AppJson.decodeFromString(StatusResponse.serializer(), status).self }.getOrNull()
        return self?.dnsName?.trimEnd('.')?.ifBlank { null }
    }

    /**
     * One blocking GET against the daemon's localapi, bounded in time.
     *
     * [LocalApiClient] speaks the same protocol, but it is suspend-only and sets
     * no read timeout; this runs on the routing thread, where a daemon that
     * accepts the connection and then says nothing must not park it forever.
     * Returns the response body, or null when anything at all went wrong — the
     * callers treat "no answer" and "a bad answer" alike.
     */
    internal fun localApiGet(context: Context, path: String, timeoutMs: Int = 3_000): String? =
        localApiGet(File(context.filesDir, "tailscaled.sock").absolutePath, path, timeoutMs)

    internal fun localApiGet(socketPath: String, path: String, timeoutMs: Int): String? {
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.soTimeout = timeoutMs
                val request = "GET $path HTTP/1.1\r\nHost: local-tailscaled.sock\r\nConnection: close\r\n\r\n"
                socket.outputStream.apply {
                    write(request.toByteArray(Charsets.UTF_8))
                    flush()
                }
                // ISO-8859-1 decodes bytes 1:1, so string offsets are byte
                // offsets — which is what the chunk lengths below are counted in.
                httpBody(socket.inputStream.readBytes().toString(Charsets.ISO_8859_1))
            }
        } catch (e: Exception) {
            Log.d(TAG, "localApiGet $path failed: ${e.message}")
            null
        }
    }

    /**
     * The body of an HTTP/1.1 response, de-chunked if it has to be.
     *
     * Go's server picks chunked transfer encoding whenever it cannot size the
     * body up front, which it can for a small handler like dns-query and cannot
     * for the status dump, so both forms really do arrive here.
     */
    private fun httpBody(response: String): String? {
        val sep = response.indexOf("\r\n\r\n")
        if (sep < 0) return null
        val head = response.substring(0, sep)
        val body = response.substring(sep + 4)
        if (!head.contains("Transfer-Encoding: chunked", ignoreCase = true)) {
            return body.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
        }
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val eol = body.indexOf("\r\n", i)
            if (eol < 0) break
            // The size line may carry chunk extensions after a semicolon.
            val size = body.substring(i, eol).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = eol + 2
            val end = minOf(start + size, body.length)
            out.append(body, start, end)
            i = end + 2
        }
        return out.toString().toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
    }
}
