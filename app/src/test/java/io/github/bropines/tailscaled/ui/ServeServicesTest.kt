package io.github.bropines.tailscaled.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServeServicesTest {
    private val hosted = mapOf("grafana" to listOf("fd7a:115c:a1e0::ab12:4814", "100.100.4.20"))
    private val tailnet = TailnetServices(
        defined = setOf("grafana", "wiki", "docs", "ops"),
        approval = mapOf("grafana" to "approved:auto", "wiki" to "not-approved", "docs" to "approved:manual", "ops" to null),
    )

    @Test
    fun theNetmapIsTheFact() {
        // Answering, with the IPv4 address however the netmap orders them.
        assertEquals(ServiceState(ServiceStanding.SERVING, "100.100.4.20", auto = true), serviceStateOf("grafana", hosted, tailnet))
        // Without an admin profile the netmap alone still says it answers.
        assertEquals(ServiceState(ServiceStanding.SERVING, "100.100.4.20"), serviceStateOf("grafana", hosted, null))
    }

    @Test
    fun theAdminApiExplainsWhyNot() {
        assertEquals(ServiceStanding.APPROVED, serviceStateOf("docs", hosted, tailnet).standing)
        assertFalse(serviceStateOf("docs", hosted, tailnet).auto)
        assertEquals(ServiceStanding.AWAITING_APPROVAL, serviceStateOf("wiki", hosted, tailnet).standing)
        // Not listed as a host yet is waiting too.
        assertEquals(ServiceStanding.AWAITING_APPROVAL, serviceStateOf("ops", hosted, tailnet).standing)
        assertEquals(ServiceStanding.NOT_DEFINED, serviceStateOf("ci", hosted, tailnet).standing)
        // Defined, but its hosts could not be read.
        assertEquals(ServiceStanding.UNKNOWN, serviceStateOf("ci", hosted, tailnet.copy(defined = tailnet.defined + "ci")).standing)
        assertEquals(ServiceStanding.UNKNOWN, serviceStateOf("wiki", hosted, null).standing)
    }

    @Test
    fun namesAreOneDnsLabel() {
        assertEquals("webapp", normalizeServiceName("  svc:WebApp "))
        assertTrue(isServiceName("web-app2"))
        for (bad in listOf("", "-web", "web-", "web_app", "web.app", "a".repeat(64))) assertFalse(bad, isServiceName(bad))
    }

    @Test
    fun suggestionsNarrowAsYouType() {
        val known = listOf("grafana", "wiki", "svc:grafana", "metrics", "Bad_Name", "graphite")
        assertEquals(listOf("grafana", "wiki", "metrics", "graphite"), serviceNameSuggestions("", known))
        assertEquals(listOf("grafana", "graphite"), serviceNameSuggestions("gra", known))
        // The name typed exactly is not offered back.
        assertEquals(emptyList<String>(), serviceNameSuggestions("svc:grafana", known))
        assertEquals(2, serviceNameSuggestions("", known, limit = 2).size)
    }
}
