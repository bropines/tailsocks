package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.models.PeerData
import io.github.bropines.tailscaled.models.StatusResponse
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.serialization.decodeFromString

/*
 * A peer's "Copy as…" picker, drawn without its sheet (the renderer has no windows) on the
 * sheet's own surface. Same look as Showcase.kt: dark, emerald, AMOLED.
 */

/** homelab-nas as a whois answers for it: Tailscale SSH, three web ports, and the listeners a
 *  URL is not made for — sshd, Samba and its own peerapi — which the picker leaves out. */
private val nasWhois = """
{
  "Node": {
    "ID": 2, "StableID": "n2", "Name": "homelab-nas.tail4a2c9.ts.net.",
    "Addresses": ["100.72.5.101/32", "fd7a:115c:a1e0::4801:565/128"],
    "Hostinfo": {
      "OS": "linux", "Hostname": "homelab-nas",
      "Services": [
        {"Proto": "tcp", "Port": 22, "Description": "sshd"},
        {"Proto": "tcp", "Port": 443, "Description": "caddy"},
        {"Proto": "tcp", "Port": 445, "Description": "smbd"},
        {"Proto": "tcp", "Port": 8096, "Description": "jellyfin"},
        {"Proto": "tcp", "Port": 9000},
        {"Proto": "tcp", "Port": 41237, "Description": "tailscaled"},
        {"Proto": "peerapi4", "Port": 41237},
        {"Proto": "peerapi6", "Port": 41237},
        {"Proto": "peerapi-dns-proxy", "Port": 1}
      ],
      "sshHostKeys": ["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDemoKeyNotARealOne"]
    }
  },
  "UserProfile": {"ID": 1, "LoginName": "alex@example.com", "DisplayName": "Alex"}
}
""".trimIndent()

/** DemoTailnet with homelab-nas running Tailscale SSH and answering a whois. */
private val copyTailnet = DemoTailnet.data.copy(
    statusJson = DemoTailnet.statusJson.replace(
        "\"HostName\": \"homelab-nas\",",
        "\"HostName\": \"homelab-nas\", \"sshHostKeys\": [\"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDemoKeyNotARealOne\"],"
    ),
    whois = mapOf("100.72.5.101" to nasWhois)
)

/** The same tailnet with MagicDNS switched off: no names, and commands by address. */
private val noMagicDnsTailnet = copyTailnet.copy(
    statusJson = copyTailnet.statusJson!!.replace(
        "\"BackendState\": \"Running\",",
        "\"BackendState\": \"Running\", \"CurrentTailnet\": {\"MagicDNSSuffix\": \"tail4a2c9.ts.net\", \"MagicDNSEnabled\": false},"
    )
)

@Composable
private fun CopyAs(data: DemoData, host: String) {
    TailSocksTheme(appTheme = "dark", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = true) {
        CompositionLocalProvider(LocalDemo provides data) {
            val status = AppJson.decodeFromString<StatusResponse>(data.statusJson!!)
            val peer: PeerData = status.peers!!.values.first { it.hostName == host }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(top = 24.dp)) {
                    PeerCopyAsContent(peer = peer, isSelf = false, names = MagicDnsNames.of(status))
                }
            }
        }
    }
}

// Names, addresses, Tailscale SSH, the web ports the NAS advertises, the two pings.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun PeerCopyAsShowcase() = CopyAs(copyTailnet, "homelab-nas")

// A peer that advertises nothing, in a tailnet without MagicDNS.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun PeerCopyAsNoMagicDnsShowcase() = CopyAs(noMagicDnsTailnet, "macbook-air")
