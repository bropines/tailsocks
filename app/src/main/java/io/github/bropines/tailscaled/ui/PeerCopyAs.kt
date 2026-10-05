package io.github.bropines.tailscaled.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.KotlinGoApiClient
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/*
 * A peer's "Copy as…": its names and addresses, and the commands they are typed into, each
 * exactly as it would be pasted. Opened from the details sheet's button or a long press on
 * the peer's row; nothing here runs for the list as a whole. The forms come out of the status
 * the screen already holds, and the one thing it does not carry — the ports the peer
 * advertises — is a single whois for that one peer, asked when its sheet opens.
 */

/**
 * What MagicDNS makes of a peer's name in this tailnet, kept from each status load so a sheet
 * opened later has it at hand. [suffix] is the tailnet's ("tail4a2c9.ts.net", no dots around
 * it), null when the daemon has not said; [enabled] is the tailnet's MagicDNS switch.
 */
internal data class MagicDnsNames(val suffix: String?, val enabled: Boolean) {
    companion object {
        /** Before a status has come back. Names count as resolvable: a tailnet without
         *  MagicDNS is the exception, and the next load says so. */
        val UNKNOWN = MagicDnsNames(suffix = null, enabled = true)

        fun of(status: StatusResponse) = MagicDnsNames(
            suffix = (status.currentTailnet?.magicDnsSuffix ?: status.magicDnsSuffix)
                ?.trim('.')?.takeIf { it.isNotEmpty() },
            enabled = status.currentTailnet?.magicDnsEnabled ?: true
        )
    }
}

internal enum class PeerCopyKind { SHORT_NAME, FULL_NAME, IPV4, IPV6, SSH, TAILSCALE_SSH, WEB, PING, TAILSCALE_PING }

/** One ready-to-paste form of a peer. [text] is what goes on the clipboard and what the row
 *  shows: one string, so the row never shows something other than what a tap copies. */
internal data class PeerCopyForm(
    val kind: PeerCopyKind,
    val text: String,
    /** For a web form, the program the peer reports on that port ("nginx"), when it says. */
    val service: String? = null
)

/** One entry of tailcfg.Hostinfo.Services. */
@Serializable
internal data class AdvertisedService(
    @SerialName("Proto") val proto: String = "",
    @SerialName("Port") val port: Int = 0,
    @SerialName("Description") val description: String? = null
)

/** apitype.WhoIsResponse, down to the one list this file reads. */
@Serializable
private data class WhoIsAnswer(@SerialName("Node") val node: WhoIsNode? = null)

@Serializable
private data class WhoIsNode(@SerialName("Hostinfo") val hostinfo: WhoIsHostinfo? = null)

@Serializable
private data class WhoIsHostinfo(@SerialName("Services") val services: List<AdvertisedService>? = null)

/** Ports a URL is opened over TLS on. Everything else gets http://. */
private val HTTPS_PORTS = setOf(443, 8443)

/**
 * Well-known TCP ports with no web page behind them. A Linux peer reports every TCP listener
 * it has (ipn/policy.IsInterestingService), its ssh daemon and file shares included, and
 * http://host:22 is not something anyone pastes.
 */
private val NOT_WEB_PORTS = setOf(
    21, 22, 23, 25, 53, 110, 111, 135, 139, 143, 445, 465, 587, 993, 995,
    2049, 3306, 3389, 5432, 5900, 6379
)

/**
 * The TCP ports in a whois answer worth a URL, lowest first. The peerapi entries go — they
 * are Taildrop's and the exit node's DNS, announced under protos of their own, and on some
 * systems the same port shows up again as a plain TCP listener — and so do [NOT_WEB_PORTS].
 * Empty for an answer that does not parse, or a peer that advertises nothing: the daemon
 * collects services only where the tailnet has service collection on, and whether the
 * control plane hands one peer's list to another is up to the control plane.
 */
internal fun advertisedWebServices(whoisJson: String): List<AdvertisedService> {
    val services = runCatching { AppJson.decodeFromString<WhoIsAnswer>(whoisJson) }.getOrNull()
        ?.node?.hostinfo?.services ?: return emptyList()
    val peerApiPorts = services.filter { it.proto.startsWith("peerapi") }.map { it.port }.toSet()
    return services
        .filter { it.proto == "tcp" && it.port in 1..65535 && it.port !in peerApiPorts && it.port !in NOT_WEB_PORTS }
        .distinctBy { it.port }
        .sortedBy { it.port }
}

/**
 * Every form [peer] can be pasted in, in the order the sheet lists them: names, addresses,
 * the ssh command, a URL per advertised web port, the two pings.
 *
 * Every command addresses the peer by its full MagicDNS name: that one resolves from any
 * device in the tailnet, with or without the search domain the short name needs, and it is
 * the only name an HTTPS certificate is issued for. With MagicDNS off in the tailnet no name
 * resolves anywhere, so the name rows go and the commands use the address.
 *
 * The ssh command names no user. The text is pasted on another machine, and there OpenSSH
 * fills in that machine's own account name — right whenever the same person has the same
 * account on both ends, which is the usual case, with or without Tailscale SSH. A guess
 * taken from here would be the tailnet login (alex@example.com → alex), which has nothing to
 * do with the accounts on the peer and breaks the command exactly where the default works.
 *
 * This device's own sheet has no pings: a node cannot measure a round trip to itself.
 */
internal fun peerCopyForms(
    peer: PeerData,
    isSelf: Boolean,
    names: MagicDnsNames,
    webServices: List<AdvertisedService>
): List<PeerCopyForm> {
    val fullName = peer.dnsName?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    // The name `tailscale status` prints: the full one less the tailnet's suffix. A name
    // outside that suffix — a node shared in from another tailnet — has no short form.
    val shortName = fullName?.let { full ->
        val suffix = names.suffix
        if (suffix == null) full.substringBefore('.')
        else full.removeSuffix(".$suffix").takeIf { it != full }
    }
    val ipv4 = peer.tailscaleIPs?.firstOrNull { ':' !in it }
    val ipv6 = peer.tailscaleIPs?.firstOrNull { ':' in it }
    val host = fullName?.takeIf { names.enabled } ?: ipv4 ?: ipv6 ?: return emptyList()
    val urlHost = if (':' in host) "[$host]" else host

    return buildList {
        if (names.enabled) {
            shortName?.let { add(PeerCopyForm(PeerCopyKind.SHORT_NAME, it)) }
            fullName?.let { add(PeerCopyForm(PeerCopyKind.FULL_NAME, it)) }
        }
        ipv4?.let { add(PeerCopyForm(PeerCopyKind.IPV4, it)) }
        ipv6?.let { add(PeerCopyForm(PeerCopyKind.IPV6, it)) }
        add(
            PeerCopyForm(
                if (peer.sshHostKeys.isNullOrEmpty()) PeerCopyKind.SSH else PeerCopyKind.TAILSCALE_SSH,
                "ssh $host"
            )
        )
        webServices.forEach { service ->
            val https = service.port in HTTPS_PORTS
            val scheme = if (https) "https" else "http"
            val port = if (service.port == (if (https) 443 else 80)) "" else ":${service.port}"
            add(PeerCopyForm(PeerCopyKind.WEB, "$scheme://$urlHost$port", service.description?.takeIf { it.isNotBlank() }))
        }
        if (!isSelf) {
            add(PeerCopyForm(PeerCopyKind.PING, "ping ${ipv4 ?: ipv6 ?: host}"))
            // The CLI finds a peer by its short name in its own peer list, so this one works
            // whether or not MagicDNS does.
            add(PeerCopyForm(PeerCopyKind.TAILSCALE_PING, "tailscale ping ${shortName ?: ipv4 ?: ipv6 ?: host}"))
        }
    }
}

/**
 * The picker for one peer. A pick puts the form on the clipboard with the details sheet's own
 * acknowledgement — the toast naming what was copied — and the sheet slides away.
 *
 * [extraOptions] is the hook for rows that are not a copy, a "Show QR" entry first among
 * them: handed the forms the sheet lists, it returns rows to go after them, each value run
 * on a pick before the sheet closes, e.g.
 * `{ forms -> listOf(PickerOption({ qrText = forms.first().text }, showQrLabel, Icons.Default.QrCode2)) }`.
 * Their labels are set in the text face; the forms' are monospace.
 */
@Composable
internal fun PeerCopyAsSheet(
    peer: PeerData,
    isSelf: Boolean,
    names: MagicDnsNames,
    onDismiss: () -> Unit,
    extraOptions: (forms: List<PeerCopyForm>) -> List<PickerOption<() -> Unit>> = { emptyList() }
) {
    // The parent's context, locale-wrapped: the sheet's own would put the toast in the
    // system language (see wrapContextWithLocale()).
    val context = LocalContext.current
    val forms = rememberPeerCopyForms(peer, isSelf, names)
    PickerSheet(
        title = stringResource(R.string.peer_copy_as_title, peer.getDisplayName()),
        options = peerCopyOptions(forms) { form, what -> copyPeerForm(context, form, what) } + extraOptions(forms),
        onPick = { it() },
        onDismiss = onDismiss,
        labelMaxLines = Int.MAX_VALUE
    )
}

/** What a [PeerCopyAsSheet] holds, without the sheet: the preview renderer has no windows. */
@Composable
internal fun PeerCopyAsContent(peer: PeerData, isSelf: Boolean, names: MagicDnsNames) {
    val forms = rememberPeerCopyForms(peer, isSelf, names)
    PickerSheetContent(
        title = stringResource(R.string.peer_copy_as_title, peer.getDisplayName()),
        options = peerCopyOptions(forms) { _, _ -> },
        selected = null,
        monospace = false,
        labelMaxLines = Int.MAX_VALUE
    ) { it() }
}

/**
 * [peer]'s forms, with the web ones filled in once the whois for it is back — a local call,
 * usually done before the sheet has finished sliding in. A demo's whois answer is read here
 * and now: the preview renderer takes its picture before anything launched could land.
 */
@Composable
private fun rememberPeerCopyForms(peer: PeerData, isSelf: Boolean, names: MagicDnsNames): List<PeerCopyForm> {
    val demo = LocalDemo.current
    val inPreview = LocalInspectionMode.current
    val address = peer.tailscaleIPs?.firstOrNull()
    var webServices by remember(address, demo) {
        mutableStateOf(demo?.whois?.get(address)?.let(::advertisedWebServices) ?: emptyList())
    }
    LaunchedEffect(address) {
        if (demo == null && !inPreview && address != null) {
            webServices = KotlinGoApiClient.whoIs(address).getOrNull()?.let(::advertisedWebServices) ?: emptyList()
        }
    }
    return remember(peer, isSelf, names, webServices) { peerCopyForms(peer, isSelf, names, webServices) }
}

/** The rows: each form's text as the label, what it is under it. Resolved in the parent
 *  composition, as every PickerSheet's words are. [onCopy] gets the form and those words. */
@Composable
private fun peerCopyOptions(
    forms: List<PeerCopyForm>,
    onCopy: (PeerCopyForm, String) -> Unit
): List<PickerOption<() -> Unit>> {
    val shortName = stringResource(R.string.peer_copy_short_name)
    val fullName = stringResource(R.string.peer_copy_full_name)
    val ipv4 = stringResource(R.string.peer_copy_ipv4)
    val ipv6 = stringResource(R.string.peer_copy_ipv6)
    val ssh = stringResource(R.string.peer_copy_ssh)
    val tailscaleSsh = stringResource(R.string.peer_copy_tailscale_ssh)
    val web = stringResource(R.string.peer_copy_web)
    // A format, filled in per row: several web rows share it.
    val webService = stringResource(R.string.peer_copy_web_service)
    val ping = stringResource(R.string.peer_copy_ping)
    val tailscalePing = stringResource(R.string.peer_copy_tailscale_ping)
    return forms.map { form ->
        val (what, icon) = when (form.kind) {
            PeerCopyKind.SHORT_NAME -> shortName to Icons.Default.Dns
            PeerCopyKind.FULL_NAME -> fullName to Icons.Default.Dns
            PeerCopyKind.IPV4 -> ipv4 to Icons.Default.Lan
            PeerCopyKind.IPV6 -> ipv6 to Icons.Default.Lan
            PeerCopyKind.SSH -> ssh to Icons.Default.Terminal
            PeerCopyKind.TAILSCALE_SSH -> tailscaleSsh to Icons.Default.Terminal
            PeerCopyKind.WEB -> (form.service?.let { webService.format(it) } ?: web) to Icons.Default.Language
            PeerCopyKind.PING -> ping to Icons.Default.NetworkPing
            PeerCopyKind.TAILSCALE_PING -> tailscalePing to Icons.Default.NetworkPing
        }
        PickerOption(
            value = { onCopy(form, what) },
            label = form.text,
            icon = icon,
            supporting = what,
            monospace = true
        )
    }
}

/** The details sheet's copy, for a form: the clipboard, labelled with what the form is, and
 *  the same toast, naming the text itself — a kind name ("Адрес IPv4") would not agree with
 *  the Russian sentence around it. */
private fun copyPeerForm(context: Context, form: PeerCopyForm, what: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText(what, form.text))
    Toast.makeText(context, context.getString(R.string.copied_to_clipboard, form.text), Toast.LENGTH_SHORT).show()
}
