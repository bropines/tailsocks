package io.github.bropines.tailscaled.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.IncomingPhase
import io.github.bropines.tailscaled.core.IncomingTransfer
import io.github.bropines.tailscaled.core.TaildropHistory
import io.github.bropines.tailscaled.models.StatusResponse
import io.github.bropines.tailscaled.models.TaildropDirection
import io.github.bropines.tailscaled.models.TaildropHistoryEntry
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/*
 * The Taildrop page of the Files screen and its sheets, fed an invented tailnet with more
 * devices than the Send section shows, some the daemon refuses, an inbox and a history of
 * every kind of entry. Same look as Showcase.kt: dark, emerald, AMOLED. The sheets are
 * drawn without their window (the renderer has none), on the sheet's own surface.
 */

private object TaildropDemo {
    private fun peer(
        id: String, host: String, os: String, v4: String, online: Boolean, taildrop: Int,
        curAddr: String = "", relay: String = "fra", tags: List<String> = emptyList(), user: Int = 1
    ) = """
        "$id": {
          "ID": "$id", "UserID": $user, "HostName": "$host", "DNSName": "$host.tail4a2c9.ts.net.",
          "OS": "$os", "TailscaleIPs": ["$v4"], "CurAddr": "$curAddr", "Relay": "$relay",
          "Online": $online, "Active": $online, "LastSeen": "2026-09-30T08:12:00Z",
          "InNetworkMap": true, "TaildropTarget": $taildrop,
          "Tags": [${tags.joinToString { "\"$it\"" }}]
        }
    """.trimIndent()

    // 1 Available, 5 Offline, 4 missing capability, 7 unsupported OS, 9 another user's or a tag's.
    val statusJson = """
    {
      "BackendState": "Running",
      "MagicDNSSuffix": "tail4a2c9.ts.net",
      "Self": {
        "ID": "nSELF7", "UserID": 1, "HostName": "pixel-9-pro", "DNSName": "pixel-9-pro.tail4a2c9.ts.net.",
        "OS": "android", "TailscaleIPs": ["100.101.34.12"], "Online": true, "Active": true, "Relay": "fra"
      },
      "Peer": {
        ${peer("n1", "desktop-home", "windows", "100.88.12.4", true, 1, curAddr = "192.168.1.20:41641")},
        ${peer("n2", "homelab-nas", "linux", "100.72.5.101", true, 1, curAddr = "192.168.1.5:41641")},
        ${peer("n5", "macbook-air", "macOS", "100.110.9.33", true, 1, relay = "ams")},
        ${peer("n6", "raspberry-pi", "linux", "100.71.3.9", true, 1)},
        ${peer("n10", "galaxy-tab", "android", "100.90.1.17", true, 1)},
        ${peer("n11", "living-room-tv", "android", "100.90.1.44", true, 1)},
        ${peer("n7", "ipad", "iOS", "100.83.44.2", false, 5)},
        ${peer("n8", "steam-deck", "linux", "100.99.201.50", false, 5)},
        ${peer("n9", "work-laptop", "windows", "100.77.160.14", false, 5)},
        ${peer("n3", "exit-frankfurt", "linux", "100.94.210.8", true, 9, tags = listOf("tag:exit"))},
        ${peer("n4", "exit-helsinki", "linux", "100.66.18.77", true, 9, tags = listOf("tag:exit"))},
        ${peer("n12", "bobs-phone", "android", "100.80.2.3", true, 9, user = 2)},
        ${peer("n13", "printer", "linux", "100.70.0.9", true, 7)}
      }
    }
    """.trimIndent()

    // No default folder: both files wait in the app.
    val filesJson = """
    [
      {"Name": "IMG_20261007_143205.jpg", "Size": 3481233, "ModTime": ${now / 1000 - 1800}, "Path": "/data/taildrop/IMG_20261007_143205.jpg"},
      {"Name": "Quarterly report — final (2).pdf", "Size": 812345, "ModTime": ${now / 1000 - 86_400}, "Path": "/data/taildrop/Quarterly report — final (2).pdf"}
    ]
    """.trimIndent()

    private val now get() = System.currentTimeMillis()
    private const val MIN = 60_000L
    private const val DAY = 86_400_000L

    val historyJson: String get() = """
    [
      {"name": "IMG_20261007_143205.jpg", "target": "macbook-air", "timestamp": ${now - 30 * MIN}, "direction": "received",
       "size": 3481233, "mime": "image/jpeg", "peerId": "n5", "peerIp": "100.110.9.33", "peerOs": "macOS",
       "route": "derp", "routeAddress": "ams", "durationMs": 4100, "path": "/data/taildrop/IMG_20261007_143205.jpg"},
      {"name": "holiday-video.mp4", "target": "ipad", "timestamp": ${now - 45 * MIN}, "ok": false,
       "size": 104857600, "mime": "video/mp4", "peerId": "n7", "peerIp": "100.83.44.2", "peerOs": "iOS",
       "error": "HTTP 502: no answer from the peer", "httpStatus": 502, "durationMs": 30012, "attempt": 2, "source": "share",
       "sha256": "9f2c7b1e44a0d5c3e8b6f17a2d9e0c4b5a6f7e8d9c0b1a2f3e4d5c6b7a8f9e0d"},
      {"name": "holiday-video.mp4", "target": "ipad", "timestamp": ${now - 46 * MIN}, "ok": false,
       "size": 104857600, "mime": "video/mp4", "peerId": "n7", "peerIp": "100.83.44.2", "peerOs": "iOS",
       "error": "HTTP 502: no answer from the peer", "httpStatus": 502, "durationMs": 30008, "source": "share"},
      {"name": "boarding-pass.pdf", "target": "desktop-home", "timestamp": ${now - 3 * 60 * MIN}, "size": 245760,
       "mime": "application/pdf", "peerId": "n1", "peerIp": "100.88.12.4", "peerOs": "windows",
       "route": "direct", "routeAddress": "192.168.1.20:41641", "durationMs": 380, "source": "files",
       "sha256": "3b7d4c2a91e8f0567a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f7081"},
      {"name": "backup-2026-10.tar.zst", "target": "homelab-nas", "timestamp": ${now - DAY}, "size": 2147483648,
       "mime": "application/zstd", "peerId": "n2", "peerIp": "100.72.5.101", "peerOs": "linux",
       "route": "direct", "routeAddress": "192.168.1.5:41641", "durationMs": 212000, "source": "peers"},
      {"name": "Quarterly report — final (2).pdf", "target": "work-laptop", "timestamp": ${now - DAY - 5 * MIN}, "direction": "received",
       "size": 812345, "mime": "application/pdf", "peerId": "n9", "peerIp": "100.77.160.14", "peerOs": "windows",
       "route": "derp", "routeAddress": "fra", "durationMs": 2200, "path": "/data/taildrop/Quarterly report — final (2).pdf",
       "savedTo": "Download/TailSocks/Quarterly report — final (2).pdf", "savedAt": ${now - DAY + 10 * MIN}},
      {"name": "notes.txt", "target": "galaxy-tab", "timestamp": ${now - 3 * DAY}, "size": 1200, "peerId": "n10", "source": "files"},
      {"name": "scan.png", "target": "", "timestamp": ${now - 4 * DAY}, "direction": "received", "size": 90211,
       "deletedAt": ${now - 4 * DAY + 60 * MIN}},
      {"name": "old-entry.zip", "target": "steam-deck", "timestamp": ${now - 40 * DAY}}
    ]
    """.trimIndent()

    /** The default folder of the states that have one: Download/Taildrop on the device's storage. */
    private const val FOLDER = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTaildrop"
    private fun savedUri(name: String) = "$FOLDER/document/" + Uri.encode("primary:Download/Taildrop/$name")
    private const val IMG = "IMG_20261007_143205.jpg"

    // With a default folder, what the folder could not take waits: a file saved by hand
    // before it was chosen and one whose move failed. No ModTime, as the bridge listed files
    // up to 4.7.4: the dates come from the history.
    val folderFilesJson = """
    [
      {"Name": "Quarterly report — final (2).pdf", "Size": 812345, "Path": "/data/taildrop/Quarterly report — final (2).pdf"},
      {"Name": "contract-scan.pdf", "Size": 1532211, "Path": "/data/taildrop/contract-scan.pdf"}
    ]
    """.trimIndent()

    /**
     * [historyJson] as it reads with a default folder: the photo moved there, a voice memo
     * saved under a free name, a scan the folder could not take, and an older file whose
     * card was hidden.
     */
    val folderHistoryJson: String get() {
        val moved = TaildropHistory.decode(historyJson).map {
            if (it.name == IMG) it.copy(savedTo = "Download/Taildrop/$IMG", savedAt = it.timestamp + 1_000, savedUri = savedUri(IMG)) else it
        }
        fun received(name: String, from: String, id: String, at: Long, size: Long) = TaildropHistoryEntry(
            name = name, peerName = from, peerId = id, timestamp = at, direction = TaildropDirection.RECEIVED,
            size = size, path = "/data/taildrop/$name"
        )
        val more = listOf(
            received("voice-memo.m4a", "galaxy-tab", "n10", now - 2 * 60 * MIN, 734_003).let {
                it.copy(savedTo = "Download/Taildrop/voice-memo (1).m4a", savedAt = it.timestamp + 800, savedUri = savedUri("voice-memo (1).m4a"))
            },
            received("contract-scan.pdf", "desktop-home", "n1", now - 5 * 60 * MIN, 1_532_211)
                .copy(saveError = "the folder cannot be read"),
            received("flyer.png", "macbook-air", "n5", now - 2 * DAY, 402_113).let {
                it.copy(savedTo = "Download/Taildrop/flyer.png", savedAt = it.timestamp + 500, savedUri = savedUri("flyer.png"), dismissedAt = it.timestamp + DAY)
            }
        )
        return AppJson.encodeToString<List<TaildropHistoryEntry>>((moved + more).sortedByDescending { it.timestamp })
    }

    /** A default folder chosen. */
    val data get() = DemoData(statusJson = statusJson, taildropFilesJson = folderFilesJson, taildropHistoryJson = folderHistoryJson, taildropFolder = FOLDER)
    /** None chosen: received files wait in the app, and the inbox offers to choose one. */
    val noFolder get() = DemoData(statusJson = statusJson, taildropFilesJson = filesJson, taildropHistoryJson = historyJson)
    val empty get() = DemoData(statusJson = statusJson, taildropFilesJson = "[]", taildropHistoryJson = "[]")

    /** A video coming in from the MacBook, a bit under half-way, over the inbox of [data]. */
    val receiving get() = data.copy(
        taildropIncoming = listOf(
            IncomingTransfer(
                key = "holiday", name = "holiday-video.mp4", sender = "macbook-air",
                size = 104_857_600, received = 47_396_044, bytesPerSecond = 2_202_009
            )
        )
    )

    /** One card per state: no size declared, stalled, all bytes in, saving, interrupted. */
    val incomingStates = listOf(
        IncomingTransfer(key = "a", name = "scan-0042.tiff", sender = "homelab-nas", received = 18_874_368, bytesPerSecond = 1_048_576),
        IncomingTransfer(
            key = "b", name = "dataset-2026-10.parquet", sender = "desktop-home",
            size = 524_288_000, received = 131_072_000, phase = IncomingPhase.STALLED
        ),
        IncomingTransfer(
            key = "c", name = "IMG_20261008_101500.jpg", sender = "galaxy-tab",
            size = 4_194_304, received = 4_194_304, phase = IncomingPhase.FINISHING
        ),
        IncomingTransfer(
            key = "d", name = "voice-memo.m4a", sender = "galaxy-tab",
            size = 734_003, received = 734_003, phase = IncomingPhase.SAVING
        ),
        IncomingTransfer(
            key = "e", name = "backup-2026-10.tar.zst", sender = "raspberry-pi",
            size = 2_147_483_648, received = 805_306_368, phase = IncomingPhase.INTERRUPTED
        )
    )
}

@Composable
private fun TaildropShowcase(data: DemoData, content: @Composable () -> Unit) {
    TailSocksTheme(appTheme = "dark", themePreset = "emerald", dynamicColorEnabled = false, amoledModeEnabled = true) {
        CompositionLocalProvider(LocalDemo provides data) { content() }
    }
}

/** A sheet's content on the sheet's colour, the way it opens. */
@Composable
private fun SheetShowcase(content: @Composable () -> Unit) = TaildropShowcase(TaildropDemo.data) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(top = 24.dp)) { content() }
    }
}

private fun demoTargets(strings: TaildropReasonStrings) = taildropTargets(
    taildropPickerPeers(AppJson.decodeFromString<StatusResponse>(TaildropDemo.statusJson), strings),
    strings,
    TaildropHistory.decode(TaildropDemo.historyJson)
)

// A default folder chosen: the inbox holds the two files waiting (one saved by hand before,
// one the folder could not take) over the two it took, the second under a free name. Then
// the Send section capped at six (recent targets first) with the "All devices" row and the
// refused-devices line, and the last five history entries.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun TaildropPageShowcase() = TaildropShowcase(TaildropDemo.data) { FilesScreen(onBack = {}, openTaildrop = true) }

// The whole page at once, the History section included.
@PreviewTest
@Preview(name = "showcase-tall", device = "spec:width=411dp,height=1700dp,dpi=420")
@Composable
fun TaildropPageTallShowcase() = TaildropShowcase(TaildropDemo.data) { FilesScreen(onBack = {}, openTaildrop = true) }

// No default folder: both files wait in the app, and the inbox offers to choose a folder.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun TaildropPageNoFolderShowcase() = TaildropShowcase(TaildropDemo.noFolder) { FilesScreen(onBack = {}, openTaildrop = true) }

// A file arriving: its card heads the inbox — bar, size, speed, time left — over the files
// waiting and saved.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun TaildropPageIncomingShowcase() = TaildropShowcase(TaildropDemo.receiving) { FilesScreen(onBack = {}, openTaildrop = true) }

// The same in the light theme.
@PreviewTest @StatesPhone @Composable
fun TaildropPageIncomingLightShowcase() =
    TailSocksTheme(appTheme = "light", themePreset = "default", dynamicColorEnabled = false, amoledModeEnabled = false) {
        CompositionLocalProvider(LocalDemo provides TaildropDemo.receiving) { FilesScreen(onBack = {}, openTaildrop = true) }
    }

// The card in each of its other states.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun TaildropIncomingStatesShowcase() = TaildropShowcase(TaildropDemo.data) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TaildropDemo.incomingStates.forEach { TaildropIncomingCard(it) }
        }
    }
}

// Nothing received, nothing sent yet: each section says so in one line; the folder hint stays.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun TaildropPageEmptyShowcase() = TaildropShowcase(TaildropDemo.empty) { FilesScreen(onBack = {}, openTaildrop = true) }

// Settings → Sharing & access, no folder chosen: the folder row says what one would do.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun TaildropSettingsFolderShowcase() = TaildropShowcase(TaildropDemo.empty) {
    SettingsScreen(
        onBack = {},
        currentTheme = "dark", onThemeChange = {},
        currentPreset = "emerald", onPresetChange = {},
        currentDynamicColor = false, onDynamicColorChange = {},
        currentAmoledMode = true, onAmoledModeChange = {},
        initialSection = "sharing"
    )
}

// A failed resend: HTTP status, error, attempt, hash, Share sheet as the source.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun TaildropEntryFailedShowcase() = SheetShowcase {
    TaildropEntryDetails(TaildropHistory.decode(TaildropDemo.historyJson).first { it.attempt == 2 }) {}
}

// A received file that was later saved: sender, route, duration and speed, where it went.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun TaildropEntryReceivedShowcase() = SheetShowcase {
    TaildropEntryDetails(TaildropHistory.decode(TaildropDemo.historyJson).first { it.savedTo != null }) {}
}

// A received file the default folder could not take: why, under On this phone.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun TaildropEntrySaveFailedShowcase() = SheetShowcase {
    TaildropEntryDetails(TaildropHistory.decode(TaildropDemo.folderHistoryJson).first { it.saveError != null }) {}
}

// A send logged by 4.7.3 or older: a name, a device, a date — and no empty rows.
@PreviewTest @StatesPhoneBothLanguages @Composable
fun TaildropEntryOldShowcase() = SheetShowcase {
    TaildropEntryDetails(TaildropHistory.decode(TaildropDemo.historyJson).last()) {}
}

@PreviewTest @StatesPhoneBothLanguages @Composable
fun TaildropHistorySheetShowcase() = SheetShowcase {
    TaildropHistorySheetContent(TaildropHistory.decode(TaildropDemo.historyJson), onEntry = {}, onExport = {}, onClear = {})
}

@PreviewTest @StatesPhoneBothLanguages @Composable
fun TaildropAllDevicesSheetShowcase() = SheetShowcase {
    val targets = demoTargets(TaildropReasonStrings.from(LocalContext.current))
    TaildropDevicesSheetContent(stringResource(R.string.taildrop_section_send), targets) {}
}
