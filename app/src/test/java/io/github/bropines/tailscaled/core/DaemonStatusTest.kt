package io.github.bropines.tailscaled.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status reader: the bridge's `{"Error": …}` envelope must never pass for an empty tailnet. */
class DaemonStatusTest {

    private fun ok(json: String?) = DaemonStatus.parse(json) as DaemonStatus.Read.Ok
    private fun failed(json: String?) = DaemonStatus.parse(json) as DaemonStatus.Read.Failed

    @Test
    fun goodStatusIsOk() {
        val status = ok(
            """
            {"Version":"1.90.0","BackendState":"Running",
             "Self":{"ID":"self1","HostName":"phone","TailscaleIPs":["100.64.0.1"],"Online":true},
             "Peer":{"nodekey:a":{"ID":"p1","HostName":"laptop","Online":true}},
             "MagicDNSSuffix":"tail1234.ts.net"}
            """.trimIndent()
        ).status
        assertEquals("self1", status.self?.id)
        assertEquals("p1", status.peers?.values?.single()?.id)
        assertEquals("tail1234.ts.net", status.magicDnsSuffix)
    }

    @Test
    fun zeroPeersIsStillOk() {
        val status = ok("""{"Version":"1.90.0","Self":{"ID":"self1","TailscaleIPs":["100.64.0.1"]},"Peer":null}""").status
        assertEquals("self1", status.self?.id)
        assertTrue(status.peers.isNullOrEmpty())
        assertTrue(ok("""{"Version":"1.90.0","Self":{"ID":"self1"},"Peer":{}}""").status.peers!!.isEmpty())
    }

    @Test
    fun errorEnvelopeIsFailedWithItsText() {
        assertEquals("x", failed("""{"Error":"x"}""").error)
        // The exact shapes appctr/status.go writes.
        assertEquals("daemon not running", failed("""{"Error": "daemon not running"}""").error)
        assertEquals("a \"quoted\" reason", failed("""{"Error": "a \"quoted\" reason"}""").error)
        assertEquals("Status API returned empty response", failed("""  {"Error": "Status API returned empty response"}""").error)
    }

    @Test
    fun blankIsFailed() {
        failed("")
        failed("   \n")
        failed(null)
    }

    @Test
    fun garbageIsFailed() {
        failed("not json at all")
        failed("[1,2,3]")
        failed("{\"Self\":")
    }
}
