package io.github.bropines.tailscaled.admin.policy

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.PolicyPreview
import io.github.bropines.tailscaled.admin.api.PolicyPreviewType
import io.github.bropines.tailscaled.admin.api.PolicyRuleMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The access check's words turned into devices: a reworded rule is not a loss, a real one names what it costs. */
class ReachResolverTest {

    private val phone = ApiDevice(nodeId = "n1", name = "phone.t.ts.net", user = "alice@example.com", addresses = listOf("100.64.0.1"))
    private val laptop = ApiDevice(nodeId = "n2", name = "laptop.t.ts.net", user = "bob@example.com", addresses = listOf("100.64.0.2"))
    private val tablet = ApiDevice(nodeId = "n3", name = "tablet.t.ts.net", user = "guest@example.com", addresses = listOf("100.64.0.3"))
    private val dns = ApiDevice(nodeId = "n4", name = "dns.t.ts.net", user = "alice@example.com", tags = listOf("tag:dns"), addresses = listOf("100.64.0.4"))
    private val db = ApiDevice(nodeId = "n5", name = "db.t.ts.net", user = "bob@example.com", tags = listOf("tag:db"), addresses = listOf("100.64.1.5"))
    private val view = TailnetView(
        listOf(phone, laptop, tablet, dns, db),
        listOf(
            ApiUser(id = "u1", loginName = "alice@example.com", role = "owner", type = "member"),
            ApiUser(id = "u2", loginName = "bob@example.com", role = "admin", type = "member"),
            ApiUser(id = "u3", loginName = "guest@example.com", role = "member", type = "member"),
        ),
    )
    private val policy = HuJson.parse(
        """{ "groups": { "group:admins": ["alice@example.com", "bob@example.com"] }, "hosts": { "lab": "100.64.1.0/24" } }"""
    )

    private fun reach(vararg ports: String) = PolicyPreview(listOf(PolicyRuleMatch(users = listOf("x"), ports = ports.toList())))
    private fun from(vararg users: String) = PolicyPreview(listOf(PolicyRuleMatch(users = users.toList(), ports = listOf("x"))))
    private fun user(before: PolicyPreview, after: PolicyPreview) =
        ReachResolver.resolve(PolicyPreviewType.USER, "alice@example.com", before, after, policy, policy, view, "alice@example.com")

    @Test
    fun aWildcardSpelledOutCostsOnlyWhatTheListLeavesOut() {
        val loss = user(
            reach("*:*"),
            reach("group:admins:*", "tag:dns:*", "tag:db:*", "autogroup:internet:*"),
        )
        assertEquals(listOf(tablet), loss.devices.map { it.device })
        assertTrue(loss.devices.single().ports.isAll)
        assertTrue(loss.unresolved.isEmpty())
    }

    @Test
    fun theSameReachInOtherWordsIsNoLoss() {
        val loss = user(reach("*:*", "tag:dns:53"), reach("autogroup:member:*", "autogroup:tagged:*"))
        assertTrue(loss.isEmpty)
        val probe = AccessProbe(PolicyPreviewType.USER, "alice@example.com", reach("*:*"), reach("autogroup:member:*", "autogroup:tagged:*"), resolved = loss)
        assertFalse(probe.warns)
        assertTrue(probe.rewritten)
    }

    @Test
    fun narrowedPortsAreNamed() {
        val loss = user(reach("tag:dns:*", "tag:db:5432,6432"), reach("tag:dns:53", "tag:db:5432"))
        val byName = loss.devices.associate { it.device.shortName to it }
        assertEquals("53", byName.getValue("dns").kept.text())
        assertTrue((byName.getValue("dns").ports + byName.getValue("dns").kept).isAll)
        assertEquals("6432", byName.getValue("db").ports.text())
    }

    @Test
    fun whatCannotBeResolvedStaysAsWritten() {
        val loss = user(reach("autogroup:internet:*", "tag:dns:53"), reach("tag:dns:53"))
        assertTrue(loss.devices.isEmpty())
        assertEquals(listOf("autogroup:internet:*"), loss.unresolved)
    }

    @Test
    fun hostsAndAddressesResolveByRange() {
        assertEquals(setOf(db), ReachResolver.devicesFor("lab", policy, view, null))
        assertEquals(setOf(laptop), ReachResolver.devicesFor("100.64.0.2", policy, view, null))
        assertEquals(setOf(phone, laptop, tablet, dns), ReachResolver.devicesFor("100.64.0.0/24", policy, view, null))
        assertNull(ReachResolver.devicesFor("autogroup:shared", policy, view, null))
        assertEquals(setOf(laptop), ReachResolver.devicesFor("autogroup:admin", policy, view, null))
        assertEquals(setOf(phone), ReachResolver.devicesFor("autogroup:self", policy, view, "alice@example.com"))
        // Without the users list, what depends on it is not guessed.
        assertNull(ReachResolver.devicesFor("autogroup:member", policy, view.copy(users = null), null))
    }

    @Test
    fun sourcesThatStopReachingThisPhone() {
        val loss = ReachResolver.resolve(
            PolicyPreviewType.IP_PORT, "100.64.0.1:22",
            from("group:admins", "guest@example.com"), from("group:admins"), policy, policy, view, "alice@example.com",
        )
        assertEquals(listOf(tablet), loss.devices.map { it.device })
        assertEquals("22", loss.devices.single().ports.text())
    }

    @Test
    fun groupsComeFromTheirOwnVersion() {
        val after = HuJson.parse("""{ "groups": { "group:admins": ["alice@example.com"] } }""")
        val loss = ReachResolver.resolve(
            PolicyPreviewType.USER, "alice@example.com", reach("group:admins:*"), reach("group:admins:*"), policy, after, view, "alice@example.com",
        )
        assertEquals(listOf(laptop), loss.devices.map { it.device })
    }

    @Test
    fun portSets() {
        assertEquals("22, 80–90", PortSet.parse("90,80-89,22")!!.text())
        assertTrue(PortSet.parse("*")!!.isAll)
        assertNull(PortSet.parse("22-x"))
        assertNull(PortSet.parse("70000"))
        assertEquals("0–52, 54–65535", (PortSet.ALL - PortSet.single(53)).text())
        assertTrue((PortSet.parse("1-10")!! - PortSet.parse("1-10")!!).isEmpty)
    }
}
