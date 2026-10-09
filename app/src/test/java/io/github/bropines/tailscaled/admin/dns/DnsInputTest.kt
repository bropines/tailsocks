package io.github.bropines.tailscaled.admin.dns

import io.github.bropines.tailscaled.admin.api.DnsConfigPreferences
import io.github.bropines.tailscaled.admin.api.DnsConfiguration
import io.github.bropines.tailscaled.admin.api.DnsResolver
import io.github.bropines.tailscaled.admin.api.FakeTransport
import io.github.bropines.tailscaled.admin.api.recorded
import io.github.bropines.tailscaled.admin.api.testBackend
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsInputTest {

    @Test
    fun addresses() {
        listOf("1.1.1.1", "100.100.100.100", "0.0.0.0").forEach { assertTrue(it, DnsInput.isIpv4(it)) }
        listOf("1.1.1", "256.1.1.1", "01.1.1.1", "1.1.1.1.1", "a.b.c.d", "").forEach { assertFalse(it, DnsInput.isIpv4(it)) }
        listOf("::1", "2606:4700:4700::1111", "fd7a:115c:a1e0::53", "::ffff:1.2.3.4", "1:2:3:4:5:6:7:8", "1::").forEach { assertTrue(it, DnsInput.isIpv6(it)) }
        listOf("1:2:3:4:5:6:7:8:9", "1::2::3", ":1::", "fe80::1%wlan0", "12345::", "1.1.1.1", "g::1").forEach { assertFalse(it, DnsInput.isIpv6(it)) }
    }

    @Test
    fun resolvers() {
        assertEquals("1.1.1.1", DnsInput.resolver(" 1.1.1.1 ").value)
        assertEquals("2606:4700:4700::1111", DnsInput.resolver("[2606:4700:4700::1111]").value)
        assertEquals("https://dns.nextdns.io/abc123", DnsInput.resolver("https://dns.nextdns.io/abc123").value)
        assertEquals(DnsInputError.DOH_NOT_HTTPS, DnsInput.resolver("http://dns.example/q").error)
        assertEquals(DnsInputError.DOH_NOT_HTTPS, DnsInput.resolver("https://user:pw@dns.example/q").error)
        assertEquals(DnsInputError.NOT_RESOLVER, DnsInput.resolver("dns.google").error)
        assertEquals(DnsInputError.EMPTY, DnsInput.resolver("  ").error)
        assertEquals(DnsInputError.DUPLICATE, DnsInput.resolver("1.1.1.1", listOf("1.1.1.1")).error)
        val (list, err) = DnsInput.resolvers("10.0.0.53, 10.0.1.53 ;fd00::53")
        assertNull(err)
        assertEquals(listOf("10.0.0.53", "10.0.1.53", "fd00::53"), list)
        assertEquals(DnsInputError.DUPLICATE, DnsInput.resolvers("10.0.0.53, 10.0.0.53").second)
        assertEquals(DnsInputError.NOT_RESOLVER, DnsInput.resolvers("10.0.0.53, office").second)
    }

    @Test
    fun domains() {
        assertEquals("corp.example.com", DnsInput.domain(" Corp.Example.com. ").value)
        assertEquals("lan", DnsInput.domain("lan").value)
        assertEquals("xn--80ak6aa92e.com", DnsInput.domain("xn--80ak6aa92e.com").value)
        listOf("-bad.example", "bad-.example", "a..b", "with space.com", "under_score.com", "${"a".repeat(64)}.com").forEach {
            assertEquals(it, DnsInputError.BAD_DOMAIN, DnsInput.domain(it).error)
        }
        assertEquals(DnsInputError.DUPLICATE, DnsInput.domain("corp.example", listOf("corp.example")).error)
    }

    private val cfg = DnsConfiguration(
        nameservers = listOf(DnsResolver("1.1.1.1", true)),
        splitDns = mapOf("corp.example" to listOf(DnsResolver("10.0.0.53", false))),
        searchPaths = listOf("corp.example"),
        preferences = DnsConfigPreferences(overrideLocalDNS = false, magicDNS = true),
    )

    @Test
    fun editsTouchOnlyTheirPart() {
        val split = DnsEdit.Split("lab.example", listOf(DnsResolver("10.1.0.53", true)))
        val after = split.applyTo(cfg)
        assertEquals(cfg.nameservers, after.nameservers)
        assertEquals(setOf("corp.example", "lab.example"), after.splitDns.keys)
        assertTrue(split.holds(after))
        val removed = DnsEdit.Split("corp.example", null).applyTo(cfg)
        assertFalse("corp.example" in removed.splitDns)
        assertTrue(DnsEdit.MagicDns(false).holds(DnsEdit.MagicDns(false).applyTo(cfg)))
        assertTrue(DnsEdit.OverrideLocal(true).applyTo(cfg).preferences.overrideLocalDNS == true)
        // A flag the server leaves out is "off"; one never asked for is not compared.
        assertTrue(DnsEdit.sameResolvers(listOf(DnsResolver("1.1.1.1")), listOf(DnsResolver("1.1.1.1", false))))
        assertTrue(DnsEdit.sameResolvers(listOf(DnsResolver("1.1.1.1", true)), listOf(DnsResolver("1.1.1.1"))))
        assertFalse(DnsEdit.sameResolvers(listOf(DnsResolver("1.1.1.1", false)), listOf(DnsResolver("1.1.1.1", true))))
    }

    @Test
    fun aCombinedWriteStartsFromAFreshRead() = runBlocking {
        // The server already has a domain this tab never saw; adding another must not drop it.
        val t = FakeTransport()
            .ok("GET", "/dns/configuration", recorded("dns_configuration.json"))
            .ok("POST", "/dns/configuration", recorded("dns_configuration.json"))
        val b = testBackend(t)
        val fresh = b.dnsConfiguration()
        val edit = DnsEdit.Split("lab.example", listOf(DnsResolver("10.1.0.53")))
        b.setDnsConfiguration(edit.applyTo(fresh))
        val sent = AppJson.parseToJsonElement(t.requestsTo("POST", "/dns/configuration").single().body!!).jsonObject
        val domains = sent["splitDNS"]!!.jsonObject.keys
        assertTrue(domains.containsAll(fresh.splitDns.keys))
        assertTrue("lab.example" in domains)
        assertEquals(fresh.nameserverAddresses, sent["nameservers"]!!.jsonArray.map { it.jsonObject["address"]!!.jsonPrimitive.content })
    }
}
