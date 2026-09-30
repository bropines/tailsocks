package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/*
 * Network diagnostics fed invented netcheck answers through LocalDemo. The
 * region ids, codes and names are Tailscale's real public DERP map, so the rows
 * read as they would on a device; the latencies and addresses are made up (the
 * addresses from the documentation ranges).
 */

/** Netcheck answers in the shape Appctr.getNetcheckFromAPI returns: the report plus the regions' names. */
object DemoNetcheck {
    /** The public DERP map's regions: id to code and name. */
    private val regions = mapOf(
        1 to ("nyc" to "New York City"), 2 to ("sfo" to "San Francisco"), 3 to ("sin" to "Singapore"),
        4 to ("fra" to "Frankfurt"), 5 to ("syd" to "Sydney"), 6 to ("blr" to "Bengaluru"),
        7 to ("tok" to "Tokyo"), 8 to ("lhr" to "London"), 9 to ("dfw" to "Dallas"),
        10 to ("sea" to "Seattle"), 11 to ("sao" to "São Paulo"), 12 to ("ord" to "Chicago"),
        13 to ("den" to "Denver"), 14 to ("ams" to "Amsterdam"), 15 to ("jnb" to "Johannesburg"),
        16 to ("mia" to "Miami"), 17 to ("lax" to "Los Angeles"), 18 to ("par" to "Paris"),
        19 to ("mad" to "Madrid"), 20 to ("hkg" to "Hong Kong"), 21 to ("tor" to "Toronto"),
        22 to ("waw" to "Warsaw"), 23 to ("dbi" to "Dubai"), 24 to ("hnl" to "Honolulu"),
        25 to ("nai" to "Nairobi"), 26 to ("nue" to "Nuremberg"), 27 to ("iad" to "Ashburn"),
        28 to ("hel" to "Helsinki"),
    )

    /** A report as the netcheck package marshals it: latencies are time.Duration, so nanoseconds. */
    private fun answer(
        preferred: Int, udp: Boolean, ipv6: Boolean, mappingVaries: Boolean,
        globalV4: String, globalV6: String, latencyMs: Map<Int, Double>,
    ): String {
        fun durations(ms: Map<Int, Double>) =
            ms.entries.joinToString(", ") { (id, v) -> "\"$id\": ${(v * 1_000_000).toLong()}" }
        val meta = regions.entries.joinToString(",\n") { (id, r) -> "\"$id\": {\"Code\": \"${r.first}\", \"Name\": \"${r.second}\"}" }
        val v6 = if (ipv6) durations(latencyMs.mapValues { it.value + 1.4 }) else ""
        return """
        {
          "Report": {
            "Now": "2026-09-30T08:14:05.412Z",
            "UDP": $udp, "IPv4": true, "IPv6": $ipv6,
            "IPv4CanSend": true, "IPv6CanSend": $ipv6, "OSHasIPv6": true, "ICMPv4": false,
            "MappingVariesByDestIP": $mappingVaries, "UPnP": false, "PMP": false, "PCP": false,
            "PreferredDERP": $preferred,
            "RegionLatency": { ${durations(latencyMs)} },
            "RegionV4Latency": { ${durations(latencyMs)} },
            "RegionV6Latency": { $v6 },
            "GlobalV4": "$globalV4",
            "GlobalV6": "$globalV6",
            "CaptivePortal": false
          },
          "DERPMeta": { $meta }
        }
        """.trimIndent()
    }

    /** Home Wi-Fi in Germany: UDP through, a friendly NAT, IPv6, Frankfurt nearest. */
    val healthy: String = answer(
        preferred = 4, udp = true, ipv6 = true, mappingVaries = false,
        globalV4 = "198.51.100.23:41641", globalV6 = "[2001:db8:4f2a:1c00::7e3]:41641",
        latencyMs = mapOf(
            4 to 18.4, 26 to 21.7, 14 to 24.9, 18 to 27.3, 22 to 31.8, 8 to 33.5, 28 to 38.2,
            19 to 44.6, 27 to 94.1, 1 to 97.8, 21 to 108.5, 23 to 112.3, 12 to 112.9, 16 to 121.4,
            9 to 133.7, 13 to 139.2, 6 to 152.6, 2 to 156.1, 10 to 158.4, 25 to 161.3, 17 to 162.8,
            15 to 171.9, 3 to 176.5, 11 to 199.2, 20 to 205.7, 24 to 238.4, 7 to 244.1, 5 to 291.6,
        ),
    )

    /**
     * A mobile carrier that filters: symmetric NAT, no IPv6, a few regions that
     * never answered — and, with the status below, the TLS link to the home
     * relay cut, so the node has none although STUN still gets through.
     */
    val troubled: String = answer(
        preferred = 14, udp = true, ipv6 = false, mappingVaries = true,
        globalV4 = "203.0.113.184:3718", globalV6 = "",
        latencyMs = mapOf(
            14 to 46.2, 4 to 51.8, 26 to 55.3, 22 to 58.9, 28 to 63.4, 18 to 71.2, 8 to 74.6,
            19 to 88.1, 27 to 139.7, 1 to 146.2, 21 to 158.8, 12 to 163.5, 23 to 171.4, 16 to 184.9,
            6 to 212.0, 3 to 236.3, 7 to 301.8,
        ),
    )

    /**
     * DemoTailnet's status with no home relay. Only Self is written with
     * "Active" right before "Relay"; a peer's fields come in another order.
     */
    val statusWithoutHomeDerp: String = DemoTailnet.statusJson
        .replaceFirst("\"Active\": true, \"Relay\": \"fra\"", "\"Active\": true, \"Relay\": \"\"")
}

@PreviewTest
@Preview(name = "showcase-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "showcase-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun NetcheckShowcase() = Showcase {
    CompositionLocalProvider(LocalDemo provides DemoTailnet.data.copy(netcheckJson = DemoNetcheck.healthy)) {
        NetcheckScreen(onBack = {})
    }
}

/**
 * The light default theme, where the fixed green and amber this screen used to
 * draw were hardest to read, on the report that puts every state on screen at
 * once: good, middling and slow regions, warnings, and no home relay.
 */
@PreviewTest
@Preview(name = "light-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun NetcheckTroubledLightPreview() = TailSocksTheme(
    appTheme = "light", themePreset = "default", dynamicColorEnabled = false, amoledModeEnabled = false
) {
    CompositionLocalProvider(
        LocalDemo provides DemoTailnet.data.copy(
            statusJson = DemoNetcheck.statusWithoutHomeDerp,
            netcheckJson = DemoNetcheck.troubled
        )
    ) {
        NetcheckScreen(onBack = {})
    }
}
