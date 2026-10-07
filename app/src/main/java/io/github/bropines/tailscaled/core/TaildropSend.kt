package io.github.bropines.tailscaled.core

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import io.github.bropines.tailscaled.models.TaildropDirection
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import io.github.bropines.tailscaled.models.TaildropRoute
import io.github.bropines.tailscaled.models.TaildropSource
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What became of one file: [error] is null when it arrived, else the reason in words. */
data class TaildropSendOutcome(val name: String, val error: String?)

/**
 * Sends [uris] to [peer] one after another, through [sendTaildropFile]. [onProgress] gets a
 * line per file before it starts — its name, with "2/5" over it when there are several.
 */
fun sendTaildropFiles(
    context: Context,
    uris: List<Uri>,
    peer: PeerData,
    source: TaildropSource,
    onProgress: (String) -> Unit = {}
): List<TaildropSendOutcome> = uris.mapIndexed { i, uri ->
    val name = displayNameOf(context, uri)
    onProgress(if (uris.size > 1) "${i + 1}/${uris.size}\n$name" else name)
    sendTaildropFile(context, uri, peer, source, name)
}

/**
 * Sends one file to one peer and writes the attempt to [TaildropHistory], whether it
 * arrived or not — the one send routine of the three send sites (Files, the Share sheet, a
 * peer's sheet). Blocking: call it from a background dispatcher.
 *
 * The daemon takes a path, not a stream, so the file is copied into the cache first; the
 * SHA-256 and the size are taken during that copy, in the same pass. The duration is the
 * daemon's call alone. The device's address, OS and route are read from a fresh status
 * once the transfer is over: an idle peer often has no path at all until the first packet,
 * so the status from before the send would mostly say "none".
 */
fun sendTaildropFile(
    context: Context,
    uri: Uri,
    peer: PeerData,
    source: TaildropSource,
    name: String = displayNameOf(context, uri)
): TaildropSendOutcome {
    var size: Long? = null
    var sha256: String? = null
    var durationMs: Long? = null
    var error: String? = null
    val tmp = File(File(context.cacheDir, "taildrop_out/${source.name.lowercase()}").apply { mkdirs() }, name)
    try {
        val target = taildropTargetId(context, peer)
        val (copied, hash) = copyHashing(context, uri, tmp)
        size = copied
        sha256 = hash
        val started = SystemClock.elapsedRealtime()
        // "OK" is the bridge's word for a 2xx from the peer itself; anything else starts with
        // "Error" and carries the peer's HTTP status and body, or the local failure.
        val res = Appctr.sendFileFromAPI(target, tmp.absolutePath)
        durationMs = SystemClock.elapsedRealtime() - started
        if (res != "OK") error = res.removePrefix("Error: ").ifBlank { res }
    } catch (e: Exception) {
        error = e.message ?: e.javaClass.simpleName
    } finally {
        tmp.delete()
    }

    val now = currentPeer(peer) ?: peer
    val ok = error == null
    // A route for a send that failed against a device marked offline would only be its
    // home DERP region, which the daemon keeps on file whether the device is there or not.
    val route = if (ok || now.online == true) taildropRouteOf(now) else null
    TaildropHistory.record(
        context,
        TaildropHistoryEntry(
            name = name,
            peerName = now.getDisplayName(),
            timestamp = System.currentTimeMillis(),
            direction = TaildropDirection.SENT,
            ok = ok,
            size = size,
            mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: mimeTypeOf(name),
            sha256 = sha256,
            peerId = now.id?.takeIf { it.isNotEmpty() },
            peerIp = now.tailscaleIPs?.firstOrNull(),
            peerOs = now.os?.takeIf { it.isNotEmpty() },
            route = route?.first,
            routeAddress = route?.second,
            durationMs = durationMs,
            error = error,
            httpStatus = error?.let(::httpStatusOf),
            source = source
        )
    )
    return TaildropSendOutcome(name, error)
}

/**
 * The StableNodeID sendFileFromAPI needs, or an exception with the message to show. The
 * daemon looks the peer up by that ID alone (localapi file-put): a hostname or DNS name in
 * its place is a guaranteed 404, so a peer that came without an ID is an error to report,
 * not something to paper over.
 */
fun taildropTargetId(context: Context, peer: PeerData): String =
    peer.id?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException(context.getString(R.string.files_peer_no_id, peer.getDisplayName()))

/**
 * How the daemon reaches [peer] and through what, read the way the peers list reads it: a
 * direct endpoint wins over a relay (the daemon keeps Relay filled with the home DERP
 * region even while traffic goes direct), and PeerRelay is filled only while one carries it.
 */
fun taildropRouteOf(peer: PeerData): Pair<TaildropRoute, String>? = when {
    !peer.curAddr.isNullOrEmpty() -> TaildropRoute.DIRECT to peer.curAddr
    !peer.peerRelay.isNullOrEmpty() -> TaildropRoute.PEER_RELAY to peer.peerRelay
    !peer.relay.isNullOrEmpty() -> TaildropRoute.DERP to peer.relay
    else -> null
}

/** The peer's HTTP status from the bridge's "HTTP 404: node not found"; null for a local failure. */
internal fun httpStatusOf(error: String): Int? =
    HTTP_STATUS.find(error)?.groupValues?.get(1)?.toIntOrNull()

private val HTTP_STATUS = Regex("""^HTTP (\d{3})\b""")

/** The peer as the daemon sees it now, by StableNodeID; null when the status cannot say. */
internal fun currentPeer(peer: PeerData): PeerData? {
    val id = peer.id?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        val json = Appctr.getStatusFromAPI()
        if (json.isBlank() || json.startsWith("Error")) null
        else AppJson.decodeFromString<StatusResponse>(json).peers?.values?.firstOrNull { it.id == id }
    }.getOrNull()
}

private fun displayNameOf(context: Context, uri: Uri): String =
    getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"

/** Copies [uri] into [dest] and hashes it on the way: one read of the source. */
private fun copyHashing(context: Context, uri: Uri, dest: File): Pair<Long, String> {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IOException(context.getString(R.string.taildrop_error_unreadable))
    input.use { i ->
        dest.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                o.write(buf, 0, n)
                total += n
            }
        }
    }
    return total to digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
