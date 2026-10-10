package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.core.TaildropHistory

// Files, Taildrop, Taildrive, Logs and the console in every window size (AdaptivePreviews.kt).

@PreviewTest @WindowSizes @Composable
fun TabletFiles() = AdaptiveShowcase(TaildropDemo.data) { FilesScreen(onBack = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletTaildrop() = AdaptiveShowcase(TaildropDemo.data) { FilesScreen(onBack = {}, openTaildrop = true) }

/** Nothing received, nothing sent: each section one quiet line. */
@PreviewTest @WindowSizes @Composable
fun TabletTaildropEmpty() = AdaptiveShowcase(TaildropDemo.empty) { FilesScreen(onBack = {}, openTaildrop = true) }

/** No default folder: the inbox's hint, and both files waiting. */
@PreviewTest
@Preview(name = "3-tablet-portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TabletTaildropNoFolder() = AdaptiveShowcase(TaildropDemo.noFolder) { FilesScreen(onBack = {}, openTaildrop = true) }

/** An entry of the history picked: its details in the pane, in place of sending and the
 *  history. Two-pane windows only — elsewhere they are a sheet, which the renderer does not draw. */
@PreviewTest
@Preview(name = "4-foldable", device = "spec:width=840dp,height=900dp,dpi=420")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TabletTaildropEntry() = AdaptiveShowcase(TaildropDemo.data) {
    FilesScreen(onBack = {}, openEntryAt = TaildropHistory.decode(TaildropDemo.historyJson).first { it.attempt == 2 }.timestamp)
}

/** A foldable open like a book: the inbox on one half, sending and the history on the other. */
@PreviewTest
@Preview(name = "fold-book", device = "spec:width=673dp,height=841dp,dpi=420")
@Composable
fun FoldTaildropBook() = AdaptiveShowcase(TaildropDemo.data) {
    CompositionLocalProvider(LocalPreviewFold provides Fold.Vertical(330.dp, 343.dp)) { FilesScreen(onBack = {}, openTaildrop = true) }
}

@PreviewTest @WindowSizes @Composable
fun TabletTaildrive() = AdaptiveShowcase { TaildriveScreen(onBack = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletLogs() = AdaptiveShowcase { LogsScreen(onBack = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletConsole() = AdaptiveShowcase { ConsoleScreen(initialCmd = "", onBack = {}) }

// The phone, upright and on its side: what the tablet layouts must leave exactly as it was.

@PreviewTest @PhoneSizes @Composable
fun PhoneFiles() = AdaptiveShowcase(TaildropDemo.data) { FilesScreen(onBack = {}) }

@PreviewTest @PhoneSizes @Composable
fun PhoneTaildrop() = AdaptiveShowcase(TaildropDemo.data) { FilesScreen(onBack = {}, openTaildrop = true) }

@PreviewTest @PhoneSizes @Composable
fun PhoneTaildropEmpty() = AdaptiveShowcase(TaildropDemo.empty) { FilesScreen(onBack = {}, openTaildrop = true) }

@PreviewTest @PhoneSizes @Composable
fun PhoneTaildropNoFolder() = AdaptiveShowcase(TaildropDemo.noFolder) { FilesScreen(onBack = {}, openTaildrop = true) }

@PreviewTest @PhoneSizes @Composable
fun PhoneTaildropIncoming() = AdaptiveShowcase(TaildropDemo.receiving) { FilesScreen(onBack = {}, openTaildrop = true) }

@PreviewTest @PhoneSizes @Composable
fun PhoneTaildrive() = AdaptiveShowcase { TaildriveScreen(onBack = {}) }

@PreviewTest @PhoneSizes @Composable
fun PhoneLogs() = AdaptiveShowcase { LogsScreen(onBack = {}) }

@PreviewTest @PhoneSizes @Composable
fun PhoneConsole() = AdaptiveShowcase { ConsoleScreen(initialCmd = "", onBack = {}) }

// With something to show: shared folders and the WebDAV proxy, a log, a console that has been used.

/** Four folders shared, the whole storage among them; access granted; the proxy on. */
private val demoTaildrive = TaildriveDemo(
    shares = listOf(
        LocalShare("sdcard", "/storage/emulated/0"),
        LocalShare("Camera", "/storage/emulated/0/DCIM/Camera"),
        LocalShare("Documents", "/storage/emulated/0/Documents"),
        LocalShare("Music", "/storage/emulated/0/Music"),
    ),
    storageAccess = true,
    proxy = true,
)

/** A start of the service and a few minutes after it, as the log store's text export has them. */
private val demoLogLines = listOf(
    "14:02:11 [INFO] [CORE] Service starting: proxy mode, SOCKS5 on 127.0.0.1:1055, HTTP on 127.0.0.1:1057",
    "14:02:11 [INFO] [CORE] Account Default: control https://controlplane.tailscale.com",
    "14:02:12 [INFO] [TAILSCALE] logtail started",
    "14:02:12 [INFO] [TAILSCALE] Program starting: v1.90.6-tailsocks, Go 1.25.3: []string{\"tailscaled\", \"--tun=userspace-networking\", \"--socks5-server=127.0.0.1:1055\"}",
    "14:02:12 [INFO] [TAILSCALE] wgengine.NewUserspaceEngine(tun \"userspace-networking\") ...",
    "14:02:12 [INFO] [TAILSCALE] magicsock: disco key = d:5d4f1e2a9b3c7e10",
    "14:02:13 [INFO] [TAILSCALE] control: client.Login(false, 0)",
    "14:02:13 [INFO] [TAILSCALE] health(warnable=login-state): ok",
    "14:02:14 [INFO] [TAILSCALE] netmap: self: [B06oh] auth=machine-authorized u=bropines@ [100.101.34.12/32 fd7a:115c:a1e0::9a01:220c/128]",
    "  [n1Kx2] desktop-home   100.88.12.4    windows  online",
    "  [n2Pq9] homelab-nas    100.72.5.101   linux    online",
    "  [n3Zt4] exit-frankfurt 100.94.210.8   linux    online  exit",
    "  [n4Hk1] exit-helsinki  100.66.18.77   linux    online  exit",
    "  [n5Mb7] macbook-air    100.110.9.33   macOS    online",
    "  [n6Rp3] raspberry-pi   100.71.3.9     linux    online",
    "  [n7Qz7] ipad           100.83.44.2    iOS      offline",
    "14:02:14 [INFO] [TAILSCALE] magicsock: derp-12 connected; connGen=1",
    "14:02:14 [INFO] [TAILSCALE] magicsock: endpoints changed: 203.0.113.24:41641 (stun), 192.168.1.33:41641 (local)",
    "14:02:15 [INFO] [CORE] DNS: MagicDNS on, 2 nameservers, search domain tail4a2c9.ts.net",
    "14:02:15 [INFO] [TAILSCALE] wgengine: Reconfig: configuring router",
    "14:02:16 [INFO] [TAILSCALE] magicsock: disco: node [n1Kx2] d:8a1b now using 192.168.1.20:41641 mtu=1360",
    "14:02:17 [WARN] [TAILSCALE] magicsock: disco: node [n7Qz7] d:31f0 ping timeout after 5s",
    "14:02:19 [ERROR] [CORE] Taildrop: send of holiday-video.mp4 to ipad failed: HTTP 502: no answer from the peer",
    "14:02:20 [INFO] [OTHER] DPI bypass: split the first TLS record for 3 hosts",
    "14:02:22 [INFO] [TAILSCALE] netcheck: report: udp=true v6=false mapvarydest=false v4a=203.0.113.24:41641 derp=12 derpdist=12v4:24ms,4v4:41ms,1v4:118ms",
    "14:02:25 [INFO] [CORE] SOCKS5 127.0.0.1:1055: connect 100.72.5.101:445 (homelab-nas) ok in 14ms",
    "14:02:28 [INFO] [TAILSCALE] magicsock: disco: node [n2Pq9] d:a0c3 now using 192.168.1.5:41641 mtu=1360",
    "14:02:31 [INFO] [TAILSCALE] wgengine: idle peer [n7Qz7] now inactive, removing from wireguard",
    "14:02:40 [WARN] [CORE] Exit node exit-frankfurt: 41ms over DERP fra, no direct path yet",
    "14:02:44 [INFO] [TAILSCALE] magicsock: disco: node [n3Zt4] d:77e2 now using 198.51.100.7:41641 mtu=1360",
    "14:02:44 [INFO] [CORE] Exit node exit-frankfurt: direct, 18ms",
    "14:03:02 [INFO] [CORE] SOCKS5 127.0.0.1:1055: connect example.com:443 via exit-frankfurt ok in 61ms",
    "14:03:10 [INFO] [TAILSCALE] taildrop: received IMG_20261007_143205.jpg (3.3 MB) from macbook-air",
    "14:03:10 [INFO] [CORE] Taildrop: saved IMG_20261007_143205.jpg to Download/Taildrop",
    "14:03:41 [INFO] [TAILSCALE] health(warnable=update-available): ok",
    "14:04:05 [INFO] [CORE] SOCKS5 127.0.0.1:1055: connect 100.88.12.4:3389 (desktop-home) ok in 9ms",
    "14:04:30 [ERROR] [TAILSCALE] magicsock: ReceiveIPv6: udp6 socket closed: network is unreachable",
    "14:05:00 [INFO] [TAILSCALE] magicsock: derp-4 connected; connGen=1",
    "14:05:12 [INFO] [OTHER] DPI bypass: 12 connections, 0 resets",
    "14:05:40 [INFO] [TAILSCALE] control: netmap: 9 peers, 6 online",
)

/** A console that has run a few commands, the history it keeps and one preset of its own. */
private val demoConsole = ConsoleDemo(
    scrollback = buildString {
        append("$ tailscale status\n")
        append("100.101.34.12  pixel-9-pro     bropines@  android  -\n")
        append("100.88.12.4    desktop-home    bropines@  windows  active; direct 192.168.1.20:41641, tx 182340 rx 941220\n")
        append("100.72.5.101   homelab-nas     bropines@  linux    active; direct 192.168.1.5:41641, tx 50211 rx 330912\n")
        append("100.94.210.8   exit-frankfurt  tagged-devices linux active; offers exit node; direct 198.51.100.7:41641\n")
        append("100.83.44.2    ipad            bropines@  iOS      offline\n")
        append("$ tailscale ping 100.88.12.4\n")
        append("pong from desktop-home (100.88.12.4) via 192.168.1.20:41641 in 8ms\n")
        append("$ LocalAPI /GET /localapi/v0/prefs\n")
        append(layOutJson("""{"ControlURL":"https://controlplane.tailscale.com","RouteAll":false,"ExitNodeID":"n3Zt4","CorpDNS":true,"RunSSH":false,"AdvertiseTags":["tag:android"],"NetfilterMode":2,"AutoUpdate":{"Check":true,"Apply":null}}"""))
        append("\n$ tailscale netcheck\n")
        append("Report:\n\t* UDP: true\n\t* IPv4: yes, 203.0.113.24:41641\n\t* IPv6: no\n\t* Nearest DERP: Frankfurt\n")
        append("# 2.4 s\n")
        append("$ ")
    },
    history = listOf("netcheck", "ip -4 homelab-nas", "whois 100.94.210.8", "ping 100.88.12.4", "/GET /localapi/v0/prefs", "exit-node list", "status"),
    presets = listOf("exit-node list"),
)

@PreviewTest @WindowSizes @Composable
fun TabletTaildriveShares() = AdaptiveShowcase {
    CompositionLocalProvider(LocalTaildriveDemo provides demoTaildrive) { TaildriveScreen(onBack = {}) }
}

/** The same in the Files hub's TailDrive tab. */
@PreviewTest
@Preview(name = "3-tablet-portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun TabletFilesShares() = AdaptiveShowcase(TaildropDemo.data) {
    CompositionLocalProvider(LocalTaildriveDemo provides demoTaildrive) { FilesScreen(onBack = {}) }
}

@PreviewTest @WindowSizes @Composable
fun TabletLogsLines() = AdaptiveShowcase(DemoTailnet.data.copy(logLines = demoLogLines)) { LogsScreen(onBack = {}) }

@PreviewTest @WindowSizes @Composable
fun TabletConsoleHistory() = AdaptiveShowcase {
    CompositionLocalProvider(LocalConsoleDemo provides demoConsole) { ConsoleScreen(initialCmd = "", onBack = {}) }
}

@PreviewTest @PhoneSizes @Composable
fun PhoneTaildriveShares() = AdaptiveShowcase {
    CompositionLocalProvider(LocalTaildriveDemo provides demoTaildrive) { TaildriveScreen(onBack = {}) }
}

@PreviewTest @PhoneSizes @Composable
fun PhoneLogsLines() = AdaptiveShowcase(DemoTailnet.data.copy(logLines = demoLogLines)) { LogsScreen(onBack = {}) }

@PreviewTest @PhoneSizes @Composable
fun PhoneConsoleHistory() = AdaptiveShowcase {
    CompositionLocalProvider(LocalConsoleDemo provides demoConsole) { ConsoleScreen(initialCmd = "", onBack = {}) }
}
