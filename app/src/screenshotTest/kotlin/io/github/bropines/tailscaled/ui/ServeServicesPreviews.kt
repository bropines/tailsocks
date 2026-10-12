package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

/*
 * Serve rules published as Tailscale Services, in every standing a service can be in: svc:grafana
 * answering (approved automatically), svc:wiki defined and waiting for this node's approval,
 * svc:ci not defined in the tailnet; with and without an admin profile that may publish, and
 * without one at all. And the rule editor, drawn in place (the renderer draws no sheet): a new
 * rule as a service with the known names to pick from, a node rule, a service rule.
 */

/** DemoTailnet's node, tagged, with a certificate, Funnel and svc:grafana's address. */
private val servicesStatusJson: String = DemoTailnet.statusJson
    .replaceFirst(
        "\"ID\": \"nSELF7\",",
        "\"ID\": \"nSELF7\", \"Tags\": [\"tag:phone\"], \"CapMap\": {\"https\": [], \"funnel\": [], " +
            "\"https://tailscale.com/cap/funnel-ports?ports=443,8443,10000\": [], " +
            "\"service-host\": [{\"svc:grafana\": [\"100.100.4.20\", \"fd7a:115c:a1e0::ab12:4814\"]}]},"
    )
    .replaceFirst("\"BackendState\": \"Running\",", "\"BackendState\": \"Running\", \"CertDomains\": [\"pixel-9-pro.tail4a2c9.ts.net\"],")

private val servicesConfigJson = """
{
  "TCP": {"443": {"HTTPS": true}},
  "Web": {"pixel-9-pro.tail4a2c9.ts.net:443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:8080"}}}},
  "Services": {
    "svc:grafana": {
      "TCP": {"443": {"HTTPS": true}},
      "Web": {"grafana.tail4a2c9.ts.net:443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:3001"}}}}
    },
    "svc:wiki": {
      "TCP": {"443": {"HTTPS": true}},
      "Web": {"wiki.tail4a2c9.ts.net:443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:8081"}}}}
    },
    "svc:ci": {
      "TCP": {"2222": {"TCPForward": "127.0.0.1:22"}}
    }
  }
}
""".trimIndent()

/** The Admin API's view: grafana and wiki defined (and metrics, served elsewhere), this node
 *  approved for grafana by a rule, not yet for wiki; ci unknown to the tailnet. */
private val tailnet = TailnetServices(
    defined = setOf("grafana", "wiki", "metrics"),
    approval = mapOf("grafana" to "approved:auto", "wiki" to "not-approved"),
)

private val health = mapOf("127.0.0.1:8080" to true, "127.0.0.1:3001" to true, "127.0.0.1:8081" to true, "127.0.0.1:22" to false)

private val readOnly = DemoServe(servicesStatusJson, servicesConfigJson, health = health, tailnetServices = tailnet)
private val writable = DemoServe(servicesStatusJson, servicesConfigJson, health = health, tailnetServices = tailnet, canPublish = true)
private val noProfile = DemoServe(servicesStatusJson, servicesConfigJson, health = health)

@Composable
private fun Services(serve: DemoServe, content: @Composable () -> Unit) = AdaptiveShowcase(DemoTailnet.data) {
    CompositionLocalProvider(LocalDemoServe provides serve) { content() }
}

@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Preview(name = "1-phone-ru", device = "spec:width=411dp,height=891dp,dpi=420", locale = "ru")
annotation class ServicePhones

// The Serve screen: each service's heading says where it stands and offers the next step.
@PreviewTest @ServicePhones @Composable
fun ServeServicesReadOnly() = Services(readOnly) { ServeHost(startTab = 0, onBack = {}) }

@PreviewTest @ServicePhones @Composable
fun ServeServicesWritable() = Services(writable) { ServeHost(startTab = 0, onBack = {}) }

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun ServeServicesNoProfile() = Services(noProfile) { ServeHost(startTab = 0, onBack = {}) }

@PreviewTest
@Preview(name = "5-tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "5-tablet-ru", device = "spec:width=1280dp,height=800dp,dpi=240", locale = "ru")
@Composable
fun ServeServicesTablet() = Services(writable) { ServeHost(startTab = 0, onBack = {}) }

// The editor.
@PreviewTest @ServicePhones @Composable
fun ServeEditorNewService() = Services(writable) {
    Surface(Modifier.fillMaxSize()) { ServeEditorDemo(writable, service = "new", newRule = true) }
}

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun ServeEditorNodeRule() = Services(readOnly) {
    Surface(Modifier.fillMaxSize()) { ServeEditorDemo(readOnly, service = null) }
}

@PreviewTest
@Preview(name = "1-phone", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun ServeEditorServiceRule() = Services(writable) {
    Surface(Modifier.fillMaxSize()) { ServeEditorDemo(writable, service = "wiki") }
}
