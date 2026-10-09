package io.github.bropines.tailscaled.admin.attention

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.Loadable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Routes for the attention home: the list's own when it carries them, a read only when newer. */
class AttentionRoutesTest {

    private val listed = ApiDevice(nodeId = "n1", name = "router.tail1234.ts.net", advertisedRoutes = listOf("10.0.0.0/16"), enabledRoutes = listOf("10.0.0.0/16"))
    private val plain = ApiDevice(nodeId = "n2", name = "old.tail1234.ts.net")
    private val stale = DeviceRoutes(advertisedRoutes = listOf("10.0.0.0/16"), enabledRoutes = emptyList())

    private fun state(listAt: Long, sheet: Map<String, Loadable<DeviceRoutes>> = emptyMap()) =
        ConsoleState(devices = Loadable(listOf(listed, plain), loadedAt = listAt), routes = sheet)

    @Test
    fun aNewerListOutranksAnOlderScan() {
        val scan = AttentionUiState(routes = mapOf("n1" to stale, "n2" to stale), routesReadAt = 1_000)
        val input = AttentionSession.input(state(listAt = 2_000), scan)
        assertNull("the list's own routes are used for n1", input.routes["n1"])
        assertEquals("a device the list says nothing about keeps its read", stale, input.routes["n2"])
        assertEquals(emptyList<Any>(), Attention.compute(input, 0).filter { it.kind == AttentionKind.ROUTES_PENDING && it.targetId == "n1" })
    }

    @Test
    fun aSheetsReadNewerThanTheListWins() {
        val sheet = mapOf("n1" to Loadable(stale, loadedAt = 3_000))
        val input = AttentionSession.input(state(listAt = 2_000, sheet = sheet), AttentionUiState(routesReadAt = 2_500))
        assertEquals(stale, input.routes["n1"])
        val older = mapOf("n1" to Loadable(stale, loadedAt = 1_500))
        assertNull(AttentionSession.input(state(listAt = 2_000, sheet = older), AttentionUiState(routesReadAt = 1_000)).routes["n1"])
    }
}
