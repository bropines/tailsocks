package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.EMPTY
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.acls
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.grants
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.items
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.load
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.plan
import io.github.bropines.tailscaled.admin.policy.templates.TemplateTestKit.sample
import io.github.bropines.tailscaled.admin.policy.visual.PolicyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The branches of the network templates (policy-templates/): what each writes for the choices
 * its form offers, the tags it defines, the rules it leaves alone, and what it says when the
 * policy has the path already.
 */
class NetworkTemplatesTest {

    private fun write(id: String, text: String, values: Map<String, ParamValue> = emptyMap(), headscale: Boolean = false): PolicyModel =
        PolicyModel.read(TemplateTestKit.apply(load(id), text, values, headscale))!!

    @Test
    fun exitNodesGoViaTheTagWhichIsDefinedAndApproves() {
        val m = write("exit-nodes-group", grants, mapOf("who" to items("group:family")))
        val g = m.grants.last()
        assertEquals(listOf("autogroup:internet"), g.dst)
        assertEquals(listOf("tag:exit-node"), g.via)
        assertEquals(emptyList<String>(), m.tagOwners.single { it.name == "tag:exit-node" }.values)
        assertEquals(listOf("tag:exit-node"), m.autoApprovers!!.exitNode)
        // An existing tag keeps its owners.
        val owned = write("exit-nodes-group", grants, mapOf("who" to items("group:family"), "exit" to items("tag:web")))
        assertEquals(listOf("alice@example.com"), owned.tagOwners.single { it.name == "tag:web" }.values)
    }

    @Test
    fun noTagMeansAnyExitNodeInTheFilesOwnSyntax() {
        val m = write("exit-nodes-group", acls, mapOf("who" to items("group:family"), "exit" to items()))
        assertEquals(listOf("autogroup:internet:*"), m.acls.last().dst)
        assertNull(m.autoApprovers)
        // Open already and no tag: nothing to add.
        val open = TemplateTestKit.apply(load("exit-nodes-group"), acls, mapOf("who" to items("group:family"), "exit" to items()))
        assertEquals(listOf(TemplateIssue.NothingToAdd), plan(load("exit-nodes-group"), open, mapOf("who" to items("group:family"), "exit" to items())).issues)
    }

    @Test
    fun theSubnetRouteIsApprovedForTheRouterAndTestedOnlyWithBothFields() {
        val base = mapOf("who" to items("group:family"), "subnet" to items("192.168.1.0/24"))
        val m = write("subnet-router", grants, base)
        assertEquals(listOf("tag:subnet-router"), m.autoApprovers!!.routes.single { it.name == "192.168.1.0/24" }.values)
        assertEquals(listOf("tag:subnet-router"), m.grants.last().via)
        assertTrue(m.tests.isEmpty())
        assertTrue(write("subnet-router", grants, base + ("tester" to items("mom@example.com"))).tests.isEmpty())
        val t = write("subnet-router", grants, base + ("tester" to items("mom@example.com")) + ("probe" to items("192.168.1.10"))).tests.single()
        assertEquals("mom@example.com", t.src)
        assertEquals(listOf("192.168.1.10:443"), t.accept)
    }

    @Test
    fun sshTestPinsRootByTheUsersAndTheMode() {
        val base = mapOf("admins" to items("group:admins"), "servers" to items("tag:web"))
        val check = write("ssh-admins", grants, base)
        assertEquals("check", check.ssh.single().action)
        assertEquals(listOf("root"), check.sshTests.single().check)
        assertEquals(listOf("tag:web:22"), check.tests.single().accept)
        assertEquals(listOf("tcp:22"), check.grants.last().ip)
        assertEquals(listOf("root"), write("ssh-admins", grants, base + ("mode" to items("accept"))).sshTests.single().accept)
        val nonroot = write("ssh-admins", grants, base + ("users" to items("autogroup:nonroot")))
        assertEquals(listOf("root"), nonroot.sshTests.single().deny)
        assertTrue(write("ssh-admins", grants, base + ("test" to ParamValue.Flag(false))).sshTests.isEmpty())
    }

    @Test
    fun sshWritesNoSecondAccessRuleWhenPort22IsOpen() {
        val p = plan(load("ssh-admins"), TemplateTestKit.json, mapOf("admins" to items("group:admins"), "servers" to items("tag:web")))
        assertTrue(p.checks.single { it.check.id == "open" }.holds)
        val m = PolicyModel.read(p.apply(TemplateTestKit.json))!!
        assertEquals(1, m.acls.size)
        assertEquals(1, m.ssh.size)
    }

    @Test
    fun dnsIsUdpAndTcpInEitherSyntaxAndNothingOnTheAuthorsPolicy() {
        assertEquals(listOf("udp:53", "tcp:53"), write("dns-server", grants).grants.last().ip)
        val a = write("dns-server", acls).acls.takeLast(2)
        assertEquals(listOf("udp", "tcp"), a.map { it.proto })
        assertTrue(a.all { it.src == listOf("*") && it.dst == listOf("tag:dns:53") })
        // The author's file opens tag:dns:53 to everyone and owns the tag: nothing to add.
        val p = plan(load("dns-server"), sample)
        assertTrue(p.checks.single().holds)
        assertEquals(listOf(TemplateIssue.NothingToAdd), p.issues)
    }

    @Test
    fun k3sOpensTheFlannelBackendItIsGivenAndKubectlOnlyWhenNamed() {
        val vx = write("k3s-cluster", grants)
        val ips = vx.grants.drop(1).flatMap { it.ip }
        assertTrue("udp:8472" in ips)
        assertFalse("udp:51820" in ips)
        assertEquals(listOf("tcp:2379-2380"), vx.grants.single { it.dst == listOf("tag:k3s-server") && it.src == listOf("tag:k3s-server") }.ip)
        assertEquals(2, vx.tagOwners.count { it.name.startsWith("tag:k3s-") })
        assertEquals(listOf("tag:k3s-server:6443"), vx.tests.single().accept)
        val wg = write("k3s-cluster", grants, mapOf("flannel" to items("wireguard-native"), "etcd" to ParamValue.Flag(false), "kubectl" to items("group:admins")))
        val wgIps = wg.grants.drop(1).flatMap { it.ip }
        assertTrue("udp:51820" in wgIps && "udp:51821" in wgIps)
        assertFalse("udp:8472" in wgIps || "tcp:2379-2380" in wgIps)
        assertEquals(listOf("tag:k3s-agent", "group:admins"), wg.tests.map { it.src })
    }

    @Test
    fun lockDownOnlyAddsAndPointsAtTheAllowAllRule() {
        val allowAll = """{
	"grants": [
		{"src": ["*"], "dst": ["*"], "ip": ["*"]},
	],
}
"""
        val p = plan(load("lock-down"), allowAll)
        assertTrue(p.checks.single().holds)
        val m = PolicyModel.read(p.apply(allowAll))!!
        assertEquals(listOf("*"), m.grants.first().src)
        assertEquals(listOf(listOf("autogroup:self"), listOf("*"), listOf("autogroup:internet")), m.grants.drop(1).map { it.dst })
        assertFalse(plan(load("lock-down"), EMPTY).checks.single().holds)
        // Headscale has no admin role: only the chosen admins reach everything.
        val hs = write("lock-down", EMPTY, mapOf("admins" to items("group:admins")), headscale = true)
        assertEquals(listOf(listOf("autogroup:member"), listOf("group:admins"), listOf("autogroup:member")), hs.acls.map { it.src })
    }

    @Test
    fun guestsGetADenyTestOnlyWithBothFields() {
        val base = mapOf("guests" to items("group:family"))
        assertTrue(write("guests-exit-nodes", grants, base).tests.isEmpty())
        val t = write("guests-exit-nodes", grants, base + ("tester" to items("mom@example.com")) + ("closed" to items("alice@example.com", "tag:web"))).tests.single()
        assertEquals(listOf("alice@example.com:22", "tag:web:22", "alice@example.com:443", "tag:web:443"), t.deny)
    }

    @Test
    fun taildriveBetweenGroupsSplitsTheAttributesAndScopesTheGrant() {
        val m = write("taildrive-groups", grants, mapOf("sharers" to items("tag:web"), "readers" to items("group:family"), "shares" to items("docs", "photos")))
        assertEquals(listOf(listOf("drive:share"), listOf("drive:access")), m.nodeAttrs.map { it.attr })
        val g = m.grants.last()
        assertEquals(listOf("group:family"), g.src)
        assertEquals(listOf("tag:web"), g.dst)
        val cap = g.app.single().source
        assertTrue(cap, "\"ro\"" in cap && "\"photos\"" in cap)
    }

    @Test
    fun theAppConnectorNamesItsDomainsAndOpensDnsDiscovery() {
        val m = write("app-connector", grants, mapOf("name" to items("GitHub"), "domains" to items("github.com", "*.github.com")))
        assertTrue("*.github.com" in m.nodeAttrs.single().app.single().source)
        assertEquals(listOf("tag:app-connector"), m.grants.single { it.dst == listOf("autogroup:internet") }.via)
        assertEquals(listOf("tcp:53", "udp:53"), m.grants.single { it.dst == listOf("tag:app-connector") }.ip)
        assertEquals(setOf("0.0.0.0/0", "::/0"), m.autoApprovers!!.routes.map { it.name }.toSet())
    }

    @Test
    fun thePostureIsDefinedAndRequired() {
        val m = write("posture-tailscale-version", grants, mapOf("who" to items("group:family"), "dst" to items("tag:web"), "version" to items("1.98")))
        assertEquals(listOf("node:tsVersion >= '1.98'"), m.postures.single { it.name == "posture:recentTailscale" }.assertions)
        assertEquals(listOf("posture:recentTailscale"), m.grants.last().srcPosture)
        // A version that is not one stays out of the policy.
        assertTrue(plan(load("posture-tailscale-version"), grants, mapOf("who" to items("group:family"), "dst" to items("tag:web"), "version" to items("1.9'"))).issues.any { it is TemplateIssue.BadValue })
    }

    @Test
    fun tailscaleOnlyTemplatesAreRefusedOnHeadscale() {
        for (id in listOf("funnel-tag", "mullvad-exit-nodes", "app-connector", "posture-tailscale-version")) {
            assertTrue(id, TemplateIssue.NotOnHeadscale in plan(load(id), EMPTY, headscale = true).issues)
        }
    }
}
