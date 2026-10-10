package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyModelTest {

    private val model = PolicyModel.read(sample)!!

    @Test
    fun everySectionOfTheSampleIsRead() {
        assertEquals(
            listOf("derpMap", "groups", "tagOwners", "autoApprovers", "acls", "ssh", "nodeAttrs", "grants", "tests"),
            model.sections.map { it.key },
        )
        assertTrue(model.unknownSections.isEmpty())
        assertTrue(model.duplicateSections.isEmpty())
        assertEquals(listOf("group:prod-admin", "group:dev-admin", "group:guest-users"), model.groups.map { it.name })
        assertEquals(listOf("alice@example.com", "bob@example.com"), model.groups[0].values)
        assertEquals(11, model.tagOwners.size)
        assertEquals(listOf("group:prod-admin", "group:dev-admin"), model.tagOwners.first { it.name == "tag:dns" }.values)
        assertEquals(listOf("tag:exit-node"), model.autoApprovers!!.exitNode)
        assertTrue(model.autoApprovers!!.routes.isEmpty())
        assertEquals(14, model.acls.size)
        assertEquals(3, model.ssh.size)
        assertEquals(2, model.nodeAttrs.size)
        assertEquals(1, model.grants.size)
        assertEquals(1, model.tests.size)
        assertTrue(model.hosts.isEmpty())
    }

    @Test
    fun rulesKnowTheirPathLineAndComments() {
        val dns = model.acls[6]
        assertEquals(PolicyPath.of("acls", 6), dns.origin.path)
        assertEquals("--- 2. DNS ПРАВИЛА ---", dns.origin.header)
        assertNull(dns.origin.note)
        assertEquals(PolicyTestKit.lineOf(sample, "\"dst\":    [\"tag:dns:53\"]") - 3, dns.origin.line)
        assertEquals(listOf(HostPorts("tag:dns", "53")), dns.destinations)
        assertEquals(listOf("*"), dns.src)
        assertTrue(dns.origin.editable)

        assertEquals("--- 4.1 ACCESS FOR EXTERNAL USER ---", model.acls[12].origin.note)
        assertTrue(model.acls[0].origin.header!!.startsWith("--- 0. ПРАВИЛА ДЛЯ АДМИНИСТРАТОРОВ"))
        assertTrue(model.sections.first { it.key == "tests" }.origin.note!!.startsWith("Сервер не примет"))
        assertEquals("Funnel только своим: гости не публикуют устройства под доменом тейлнета.", model.nodeAttrs[1].origin.note)
        assertEquals(HostPorts("tag:homelab", "80,443,8096,4566,56569,8680,8443,7777"), model.acls[9].destinations.single())
    }

    @Test
    fun sshGrantsNodeAttrsAndTestsAreTyped() {
        val ssh = model.ssh[0]
        assertEquals("accept", ssh.action)
        assertEquals(listOf("root", "autogroup:nonroot", "administrator", "mini", "worker"), ssh.users)
        assertNull(ssh.checkPeriod)

        val grant = model.grants.single()
        assertEquals(listOf("tag:homelab", "tag:android", "tag:taildrop"), grant.dst)
        assertTrue(grant.ip.isEmpty())
        val drive = grant.app.single()
        assertEquals("tailscale.com/cap/drive", drive.name)
        assertTrue(drive.source.contains("\"access\": \"rw\""))

        assertEquals(listOf("cap:web-client", "cap:taildrop", "cap:advertise-services", "funnel", "drive:share", "drive:access"), model.nodeAttrs[0].attr)

        val test = model.tests.single()
        assertEquals("guest@example.com", test.src)
        assertEquals(6, test.deny.size)
        assertTrue(test.accept.isEmpty())

        val derp = model.derpMap!!
        assertEquals(DerpRegion("28", null, null, 0, disabled = true), derp.regions.single())
        assertFalse(derp.omitDefaultRegions)
    }

    @Test
    fun whatTheEditorDoesNotUnderstandIsFlaggedNotDropped() {
        val text = """
            {
              "acls": [
                {"action": "accept", "src": ["*"], "dst": ["*:*"], "users": ["legacy"]},
                {"Action": "accept", "src": "tag:a", "dst": [1]},
                {"action": "accept", "src": ["a"], "src": ["b"], "dst": ["x:1"]}
              ],
              "somethingNew": {"x": 1},
              "acls": []
            }
        """.trimIndent()
        val m = PolicyModel.read(text)!!
        assertEquals(setOf("acls"), m.duplicateSections)
        assertEquals(listOf("somethingNew"), m.unknownSections.map { it.key })
        // The last "acls" counts, as on the server: it is empty.
        assertTrue(m.acls.isEmpty())

        val first = PolicyModel.read(text.replace("\"acls\": []", "\"other\": []"))!!.acls
        assertEquals(listOf("users"), first[0].origin.extra)
        assertTrue(first[0].origin.editable)
        assertTrue(ShapeIssue.ODD_CASE in first[1].origin.issues)
        assertTrue(ShapeIssue.NOT_STRINGS in first[1].origin.issues)
        assertEquals(listOf("tag:a"), first[1].src)
        assertTrue(ShapeIssue.DUPLICATE_KEY in first[2].origin.issues)
        assertEquals(listOf("b"), first[2].src)
    }

    @Test
    fun selectorsAndPortsAreClassified() {
        fun k(s: String, hosts: Set<String> = emptySet()) = Selectors.parse(s, hosts).kind
        assertEquals(SelectorKind.ANY, k("*"))
        assertEquals(SelectorKind.AUTOGROUP, k("autogroup:internet"))
        assertEquals(SelectorKind.GROUP, k("group:dev-admin"))
        assertEquals(SelectorKind.TAG, k("tag:exit-node"))
        assertEquals(SelectorKind.USER, k("alice@example.com"))
        assertEquals(SelectorKind.USER, k("alice@"))
        assertEquals(SelectorKind.USER_DOMAIN, k("user:*@example.com"))
        assertEquals(SelectorKind.HOST, k("nas", setOf("nas")))
        assertEquals(SelectorKind.IP, k("100.64.0.1"))
        assertEquals(SelectorKind.IP, k("fd7a:115c:a1e0::1"))
        assertEquals(SelectorKind.CIDR, k("10.0.0.0/8"))
        assertEquals(SelectorKind.IP_RANGE, k("10.0.0.1-10.0.0.9"))
        assertEquals(SelectorKind.IPSET, k("ipset:prod"))
        assertEquals(SelectorKind.SERVICE, k("svc:web"))
        assertEquals(SelectorKind.EXTERNAL, k("group://partner/eng"))
        assertEquals(SelectorKind.LOCALPART, k("localpart:*@example.com"))
        assertEquals("exit-node", Selectors.parse("tag:exit-node").name)

        assertEquals(HostPorts("*", "*"), Selectors.hostPorts("*:*"))
        assertEquals(HostPorts("autogroup:internet", "*"), Selectors.hostPorts("autogroup:internet:*"))
        assertEquals(HostPorts("10.0.0.0/8", "22,80-90"), Selectors.hostPorts("10.0.0.0/8:22,80-90"))
        assertEquals(HostPorts("[fd7a::1]", "22"), Selectors.hostPorts("[fd7a::1]:22"))
        assertEquals(HostPorts("tag:web", null), Selectors.hostPorts("tag:web"))
        assertEquals(HostPorts("fd7a:115c:a1e0::1", null), Selectors.hostPorts("fd7a:115c:a1e0::1"))

        assertEquals(IpSpec("tcp", "443"), Selectors.ipSpec("tcp:443"))
        assertEquals(IpSpec(null, "*"), Selectors.ipSpec("*"))
        assertEquals(IpSpec("icmp", "*"), Selectors.ipSpec("icmp:*"))
        assertEquals(IpSpec(null, "80-443"), Selectors.ipSpec("80-443"))
        assertEquals(PortPreset.WEB, Selectors.preset("80, 443"))
        assertNull(Selectors.preset("8080"))
    }
}
