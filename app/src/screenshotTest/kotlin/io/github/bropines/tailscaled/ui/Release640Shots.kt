package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.ShareTargetPicks
import io.github.bropines.tailscaled.models.StatusResponse
import kotlinx.serialization.decodeFromString

/*
 * Screens for the 6.4.0 release notes, on the made-up tailnet of the README shots (Showcase):
 * the swipe on a device, the gestures' choices, the share menu's devices and the sleep mode.
 * Each in English and Russian, for the two halves of the notes.
 */

private const val SHORT = "spec:width=411dp,height=440dp,dpi=420"
private const val TALL = "spec:width=411dp,height=860dp,dpi=420"

private val demoStatus: StatusResponse by lazy { AppJson.decodeFromString(DemoTailnet.statusJson) }
private fun demoPeer(host: String) = demoStatus.peers!!.values.first { it.hostName == host }

/** Three devices of the list, the middle one dragged most of the way to the left: "Copy IP" under it. */
@PreviewTest
@Preview(name = "en", device = SHORT, locale = "en")
@Preview(name = "ru", device = SHORT, locale = "ru")
@Composable
fun ReleaseGestureSwipe() = Showcase {
    val copy = SwipeAction(stringResource(R.string.gesture_peer_copy_ip), Icons.Default.ContentCopy) {}
    val send = SwipeAction(stringResource(R.string.gesture_peer_send_file), Icons.Default.UploadFile) {}
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            PeerItem(demoPeer("desktop-home"), isSelf = false, swipeToEnd = send, swipeToStart = copy) {}
            CompositionLocalProvider(LocalSwipePreview provides -0.42f) {
                PeerItem(demoPeer("homelab-nas"), isSelf = false, swipeToEnd = send, swipeToStart = copy) {}
            }
            PeerItem(demoPeer("macbook-air"), isSelf = false, swipeToEnd = send, swipeToStart = copy) {}
        }
    }
}

/** Settings → Gestures → a device: both directions, each a row of choices. */
@PreviewTest
@Preview(name = "en", device = TALL, locale = "en")
@Preview(name = "ru", device = TALL, locale = "ru")
@Composable
fun ReleaseGestureChoices() = Showcase {
    val chosen = mapOf("end" to "send_file", "start" to "copy_ip")
    val options = listOf(
        "none" to R.string.gesture_none, "send_file" to R.string.gesture_peer_send_file,
        "ping" to R.string.gesture_peer_ping, "copy_ip" to R.string.gesture_peer_copy_ip,
        "exit_node" to R.string.gesture_peer_exit_node, "details" to R.string.gesture_peer_details,
        "copy_name" to R.string.gesture_peer_copy_name, "copy_as" to R.string.gesture_peer_copy_as,
        "open_web" to R.string.gesture_peer_open_web,
    ).map { (id, res) -> id to stringResource(res) }
    SheetLook {
        SwipeChoiceContent(
            title = stringResource(R.string.gestures_peer_title),
            directions = listOf(stringResource(R.string.gestures_to_end) to "end", stringResource(R.string.gestures_to_start) to "start"),
            options = options,
            chosen = { chosen[it] },
            onChoose = { _, _ -> },
        )
    }
}

/** Settings → Sharing → Choose devices, with one pinned and one hidden. */
@PreviewTest
@Preview(name = "en", device = TALL, locale = "en")
@Preview(name = "ru", device = TALL, locale = "ru")
@Composable
fun ReleaseShareTargets() = Showcase {
    val devices = listOf("desktop-home", "homelab-nas", "macbook-air", "pixel-8", "ipad").map {
        val p = demoPeer(it)
        ShareTargetDevice(p, p.id!!, online = p.online == true)
    }
    val picks = ShareTargetPicks(pinned = setOf(demoPeer("homelab-nas").id!!), hidden = setOf(demoPeer("ipad").id!!))
    SheetLook {
        ShareTargetsContent(ShareDevicesLoad.Ready(devices), picks, capacity = 8, words = shareTargetsWords(8), update = {})
    }
}

/** Settings → Background → Keep the connection awake: the three modes. */
@PreviewTest
@Preview(name = "en", device = SHORT, locale = "en")
@Preview(name = "ru", device = SHORT, locale = "ru")
@Composable
fun ReleaseSleepModes() = Showcase {
    val modes = listOf(
        R.string.settings_sleep_off to R.string.settings_sleep_off_desc,
        R.string.settings_sleep_periodic to R.string.settings_sleep_periodic_desc,
        R.string.settings_sleep_always to R.string.settings_force_bg_desc,
    ).mapIndexed { i, (label, desc) -> PickerOption(i, stringResource(label), supporting = stringResource(desc)) }
    SheetLook {
        PickerSheetContent(stringResource(R.string.settings_keep_awake_title), modes, selected = 1, monospace = false) {}
    }
}

/** A bottom sheet's surface, without the window a real sheet would need. */
@Composable
private fun SheetLook(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) { Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.Top) { content() } }
        }
    }
}
