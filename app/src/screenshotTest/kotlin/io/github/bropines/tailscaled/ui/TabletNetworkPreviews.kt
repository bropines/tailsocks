package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

// Serve, TailCat, Netcheck and DNS in every window size (AdaptivePreviews.kt), fed made-up
// answers; the phone shapes of each, and of the states the large layouts change, to compare
// before and after; a foldable open like a book where a screen splits at the hinge.

/** DemoTailnet's node with the capabilities Serve reads: a certificate, Funnel on its three
 *  ports, a tag (so it may host services) and svc:grafana approved on it. */
private val serveStatusJson: String = DemoTailnet.statusJson
    .replaceFirst(
        "\"ID\": \"nSELF7\",",
        "\"ID\": \"nSELF7\", \"Tags\": [\"tag:phone\"], \"CapMap\": {\"https\": [], \"funnel\": [], " +
            "\"https://tailscale.com/cap/funnel-ports?ports=443,8443,10000\": [], " +
            "\"service-host\": [{\"svc:grafana\": [\"100.100.4.20\", \"fd7a:115c:a1e0::ab12:4814\"]}]},"
    )
    .replaceFirst("\"BackendState\": \"Running\",", "\"BackendState\": \"Running\", \"CertDomains\": [\"pixel-9-pro.tail4a2c9.ts.net\"],")

/** Five rules on the node (one public, one a TCP forward, one paused) and a service on two ports. */
private val demoServe = DemoServe(
    statusJson = serveStatusJson,
    configJson = """
    {
      "TCP": {"443": {"HTTPS": true}, "8443": {"HTTPS": true}, "5432": {"TCPForward": "127.0.0.1:5432"}},
      "Web": {
        "pixel-9-pro.tail4a2c9.ts.net:443": {"Handlers": {
          "/": {"Proxy": "http://127.0.0.1:8080"},
          "/docs": {"Path": "/sdcard/Documents/site"}
        }},
        "pixel-9-pro.tail4a2c9.ts.net:8443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:3000"}}}
      },
      "AllowFunnel": {"pixel-9-pro.tail4a2c9.ts.net:8443": true},
      "Services": {
        "svc:grafana": {
          "TCP": {"443": {"HTTPS": true}, "2550": {"HTTPS": true}},
          "Web": {
            "grafana.tail4a2c9.ts.net:443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:3001"}}},
            "grafana.tail4a2c9.ts.net:2550": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:3001"}}}
          }
        }
      }
    }
    """.trimIndent(),
    pausedJson = """[{"port": 9090, "kind": "PROXY", "target": "127.0.0.1:9090", "tls": true}]""",
    health = mapOf("127.0.0.1:8080" to true, "127.0.0.1:3000" to true, "127.0.0.1:3001" to true, "127.0.0.1:5432" to false),
)

private val demoServeEmpty = DemoServe(statusJson = serveStatusJson, configJson = "{}")

@Composable
private fun Serve(serve: DemoServe? = demoServe, data: DemoData? = DemoTailnet.data) = AdaptiveShowcase(data) {
    CompositionLocalProvider(LocalDemoServe provides serve) { ServeHost(startTab = 0, onBack = {}) }
}

/** MagicDNS on, three split routes (one tested fine, one timing out), a lookup made. */
private val demoDns = DemoDns(
    statusJson = """
    {
      "TailscaleDNS": true,
      "CurrentTailnet": {"MagicDNSEnabled": true, "MagicDNSSuffix": "tail4a2c9.ts.net", "SelfDNSName": "pixel-9-pro.tail4a2c9.ts.net"},
      "SplitDNSRoutes": {
        "tail4a2c9.ts.net.": [{"Addr": "100.100.100.100"}],
        "home.arpa.": [{"Addr": "192.168.1.1"}, {"Addr": "100.72.5.101"}],
        "corp.example.com.": [{"Addr": "10.20.0.53"}]
      }
    }
    """.trimIndent(),
    lookupDomain = "homelab-nas.tail4a2c9.ts.net",
    lookupResult = "100.72.5.101",
    localTest = "Success via Tailscale daemon (latency: 14 ms)\n100.72.5.101",
    routeTests = mapOf(
        "home.arpa._192.168.1.1" to "Success (reply: 61 bytes, latency: 3 ms)",
        "corp.example.com._10.20.0.53" to "Failed: timeout",
    ),
)

@Composable
private fun Dns(dns: DemoDns? = demoDns, data: DemoData? = DemoTailnet.data) = AdaptiveShowcase(data) {
    CompositionLocalProvider(LocalDemoDns provides dns) { DnsScreen(onBack = {}) }
}

private val tailcatData = DemoTailnet.data.copy(tailcat = demoTailcat)
private val tailcatEmptyData = DemoTailnet.data.copy(tailcat = DemoTailcat())
private val netcheckData = DemoTailnet.data.copy(netcheckJson = DemoNetcheck.healthy)

/** A foldable open like a book, the hinge down the middle. */
@Composable
private fun Book(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalPreviewFold provides Fold.Vertical(330.dp, 343.dp)) { content() }

private const val TABLET = "spec:width=1280dp,height=800dp,dpi=240"
private const val BOOK = "spec:width=673dp,height=841dp,dpi=420"

// Serve & Funnel

@PreviewTest @WindowSizes @Composable
fun TabletServe() = Serve()

@PreviewTest @PhoneSizes @Composable
fun PhoneServe() = Serve()

@PreviewTest @Preview(name = "5-tablet", device = TABLET) @Composable
fun TabletServeEmpty() = Serve(demoServeEmpty)

@PreviewTest @PhoneSizes @Composable
fun PhoneServeEmpty() = Serve(demoServeEmpty)

@PreviewTest @Preview(name = "5-tablet", device = TABLET) @Composable
fun TabletServeStopped() = Serve(null, DemoTailnet.data.copy(running = false))

@PreviewTest @PhoneSizes @Composable
fun PhoneServeStopped() = Serve(null, DemoTailnet.data.copy(running = false))

@PreviewTest @Preview(name = "fold-book", device = BOOK) @Composable
fun FoldServeBook() = Book { Serve() }

// TailCat

@PreviewTest @WindowSizes @Composable
fun TabletTailcat() = AdaptiveShowcase(tailcatData) { ServeHost(startTab = 1, onBack = {}) }

@PreviewTest @PhoneSizes @Composable
fun PhoneTailcat() = AdaptiveShowcase(tailcatData) { ServeHost(startTab = 1, onBack = {}) }

@PreviewTest @Preview(name = "5-tablet", device = TABLET) @Composable
fun TabletTailcatEmpty() = AdaptiveShowcase(tailcatEmptyData) { ServeHost(startTab = 1, onBack = {}) }

@PreviewTest @PhoneSizes @Composable
fun PhoneTailcatEmpty() = AdaptiveShowcase(tailcatEmptyData) { ServeHost(startTab = 1, onBack = {}) }

@PreviewTest @Preview(name = "fold-book", device = BOOK) @Composable
fun FoldTailcatBook() = AdaptiveShowcase(tailcatData) { Book { ServeHost(startTab = 1, onBack = {}) } }

// Netcheck

@PreviewTest @WindowSizes @Composable
fun TabletNetcheck() = AdaptiveShowcase(netcheckData) { NetcheckScreen(onBack = {}) }

@PreviewTest @PhoneSizes @Composable
fun PhoneNetcheck() = AdaptiveShowcase(netcheckData) { NetcheckScreen(onBack = {}) }

/** No home relay: the overview card carries a warning and its folded explanation. */
@PreviewTest
@Preview(name = "3-tablet-portrait", device = "spec:width=800dp,height=1280dp,dpi=240")
@Preview(name = "5-tablet", device = TABLET)
@Composable
fun TabletNetcheckTroubled() = AdaptiveShowcase(
    DemoTailnet.data.copy(statusJson = DemoNetcheck.statusWithoutHomeDerp, netcheckJson = DemoNetcheck.troubled)
) { NetcheckScreen(onBack = {}) }

/** Before the first answer: the waiting state takes the window, as on a phone. */
@PreviewTest @Preview(name = "5-tablet", device = TABLET) @Composable
fun TabletNetcheckWaiting() = AdaptiveShowcase(null) { NetcheckScreen(onBack = {}) }

@PreviewTest @PhoneSizes @Composable
fun PhoneNetcheckWaiting() = AdaptiveShowcase(null) { NetcheckScreen(onBack = {}) }

@PreviewTest @Preview(name = "fold-book", device = BOOK) @Composable
fun FoldNetcheckBook() = AdaptiveShowcase(netcheckData) { Book { NetcheckScreen(onBack = {}) } }

// DNS

@PreviewTest @WindowSizes @Composable
fun TabletDns() = Dns()

@PreviewTest @PhoneSizes @Composable
fun PhoneDns() = Dns()

/** Before the status has come: the tools alone. */
@PreviewTest @Preview(name = "5-tablet", device = TABLET) @Composable
fun TabletDnsLoading() = Dns(null)

@PreviewTest @PhoneSizes @Composable
fun PhoneDnsLoading() = Dns(null)

@PreviewTest @Preview(name = "5-tablet", device = TABLET) @Composable
fun TabletDnsStopped() = Dns(null, DemoTailnet.data.copy(running = false))

@PreviewTest @PhoneSizes @Composable
fun PhoneDnsStopped() = Dns(null, DemoTailnet.data.copy(running = false))

@PreviewTest @Preview(name = "fold-book", device = BOOK) @Composable
fun FoldDnsBook() = Book { Dns() }
