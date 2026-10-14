package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * Log lines as the log page colours them (highlightLogMessage), in a light and a dark theme: the
 * Logs page itself renders empty here, with no daemon to read. The lines are the kinds a real log
 * has — key=value pairs, addresses, words that mean good or bad — and every part of each must read
 * on both backgrounds.
 */

private val lines = listOf(
    Triple("08:23:29", "TAILSCALE", "control: NetInfo: NetInfo{varies=false ipv6=false ipv6os=true udp=true icmpv4=false derp=#4 portmap= link=\"\" firewallmode=\"\"}"),
    Triple("08:23:29", "TAILSCALE", "control: [v1] PollNetMap: stream=false ep=[95.165.172.169:59300 10.10.10.10:59300 192.168.1.75:59300]"),
    Triple("08:23:29", "TAILSCALE", "control: [v1] successful lite map update in 98ms"),
    Triple("08:37:29", "CORE", "Daemon startup state backend_state=Running has_auth_url=false"),
    Triple("08:37:29", "CORE", "Account has an active session, preserving it backend_state=Running"),
    Triple("08:37:31", "ERROR", "dial tcp 100.64.0.1:443: connection failed err=timeout retries=3"),
    Triple("08:37:32", "ROOT", "ip rule add fwmark 0x1000000/0x1000000 lookup 52 status=ok"),
    Triple("08:37:33", "LOGCAT", "TunVpnService: established fd=87 mtu=1280"),
    Triple("08:37:34", "TAILCAT", "relay offline, retrying in 5s"),
)

@Composable
private fun Lines(theme: String) = TailSocksTheme(appTheme = theme, dynamicColorEnabled = false) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        val text = MaterialTheme.colorScheme.onSurface
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { (time, category, message) ->
                Text(highlightLogMessage(time, category, message, text), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Preview(name = "log-lines", device = "spec:width=411dp,height=560dp,dpi=420")
internal annotation class LogLinesSize

@PreviewTest @LogLinesSize @Composable
fun LogLinesLight() = Lines("light")

@PreviewTest @LogLinesSize @Composable
fun LogLinesDark() = Lines("dark")
