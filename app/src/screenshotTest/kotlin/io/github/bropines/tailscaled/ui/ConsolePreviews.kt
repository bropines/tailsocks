package io.github.bropines.tailscaled.ui

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

/* The scrollback's colouring: a LocalAPI answer laid out and highlighted, plain CLI output, an error. */

private val SAMPLE = buildString {
    append("$ LocalAPI /GET /localapi/v0/prefs\n")
    append(layOutJson("""{"ControlURL":"https://controlplane.tailscale.com","RouteAll":false,"ExitNodeID":"nEXAMPLE","CorpDNS":true,"RunSSH":false,"AdvertiseTags":["tag:android","tag:homelab"],"Persist":null,"NoSNAT":false,"NetfilterMode":2,"AutoUpdate":{"Check":true,"Apply":null}}"""))
    append("\n$ tailscale ping 100.106.35.113\n")
    append("pong from personal-pinus-work (100.106.35.113) via 192.168.1.20:41641 in 14ms\n")
    append("$ tailscale up --bogus\n")
    append("Error: flag provided but not defined: -bogus\n")
    append("# 1.2 s\n")
    append("$ ")
}

@Composable
private fun ConsoleSample(dark: Boolean) {
    TailSocksTheme(appTheme = if (dark) "dark" else "light", dynamicColorEnabled = false) {
        val scheme = MaterialTheme.colorScheme
        val colors = ConsoleColors(
            prompt = scheme.primary, error = scheme.error, dim = scheme.outline, default = scheme.onSurface,
            jsonKey = scheme.primary, jsonString = scheme.tertiary, jsonNumber = scheme.secondary
        )
        Surface(color = scheme.surface, modifier = Modifier.fillMaxSize()) {
            Text(styleScrollback(SAMPLE, colors), fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        }
    }
}

@PreviewTest
@Preview(name = "console-json-light", device = "spec:width=393dp,height=700dp,dpi=420")
@Composable
fun ConsoleJsonLight() = ConsoleSample(dark = false)

@PreviewTest
@Preview(name = "console-json-dark", device = "spec:width=393dp,height=700dp,dpi=420")
@Composable
fun ConsoleJsonDark() = ConsoleSample(dark = true)
