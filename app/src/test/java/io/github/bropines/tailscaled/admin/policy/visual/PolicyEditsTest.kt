package io.github.bropines.tailscaled.admin.policy.visual

import io.github.bropines.tailscaled.admin.policy.HuJson
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.assertMeaning
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.json
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.parsed
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.removeAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.sample
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.setAt
import io.github.bropines.tailscaled.admin.policy.visual.PolicyTestKit.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PolicyEditsTest {

    private val model = PolicyModel.read(sample)!!

    @Test
    fun savingAnUntouchedRuleChangesNothing() {
        // What the rule editor sends when the person opens a rule and taps Done.
        for (r in model.acls) {
            val same = PolicyEdits.updateRule(sample, r.origin.path, PolicyEdits.aclFields(r.src, r.dst, r.proto, r.srcPosture))
            assertEquals("acls ${r.origin.path}", sample, same)
        }
        for (r in model.ssh) {
            val same = PolicyEdits.updateRule(sample, r.origin.path, PolicyEdits.sshFields(r.action, r.src, r.dst, r.users, r.checkPeriod, r.acceptEnv))
            assertEquals(sample, same)
        }
        for (r in model.nodeAttrs) assertEquals(sample, PolicyEdits.updateRule(sample, r.origin.path, PolicyEdits.nodeAttrFields(r.target, r.attr)))
        for (g in model.groups) assertEquals(sample, PolicyEdits.putNamed(sample, Section.GROUPS, g.name, g.values))
        for (o in model.tagOwners) assertEquals(sample, PolicyEdits.putNamed(sample, Section.TAG_OWNERS, o.name, o.values))
        assertEquals(sample, PolicyEdits.setExitNodeApprovers(sample, listOf("tag:exit-node")))
    }

    @Test
    fun updateARuleFieldByField() {
        val r = model.acls[6]
        val after = PolicyEdits.updateRule(sample, r.origin.path, PolicyEdits.aclFields(listOf("autogroup:member"), listOf("tag:dns:53", "tag:dns:853"), proto = "udp"))
        assertTrue(after.contains("\t\t// --- 2. DNS ПРАВИЛА ---\n\t\t{\n\t\t\t\"action\": \"accept\",\n\t\t\t\"src\":    [\"autogroup:member\"],\n\t\t\t\"proto\":  \"udp\",\n\t\t\t\"dst\":    [\"tag:dns:53\", \"tag:dns:853\"],\n\t\t},"))
        assertMeaning(
            setAt(parsed(sample), listOf("acls", 6), json("{\"action\":\"accept\",\"src\":[\"autogroup:member\"],\"proto\":\"udp\",\"dst\":[\"tag:dns:53\",\"tag:dns:853\"]}")),
            after,
        )
        // And back: the field goes, the lists return, the text is the original byte for byte.
        val back = PolicyEdits.updateRule(after, r.origin.path, PolicyEdits.aclFields(r.src, r.dst, null))
        assertEquals(sample, back)
    }

    @Test
    fun sshCheckPeriodOnlyOnCheckRules() {
        val r = model.ssh[2]
        val check = PolicyEdits.updateRule(sample, r.origin.path, PolicyEdits.sshFields("check", r.src, r.dst, r.users, "12h"))
        assertTrue(check.contains("\"action\": \"check\""))
        assertTrue(check.contains("\"checkPeriod\": \"12h\""))
        val accept = PolicyEdits.updateRule(check, r.origin.path, PolicyEdits.sshFields("accept", r.src, r.dst, r.users, "12h"))
        assertEquals(sample, accept)
    }

    @Test
    fun addRulesToExistingAndMissingSections() {
        val acl = PolicyEdits.addRule(sample, Section.ACLS, PolicyEdits.aclFields(listOf("group:guest-users"), listOf("tag:dns:53")), note = "Guests resolve names too")
        assertTrue(acl.contains("\t\t// Guests resolve names too\n\t\t{\n\t\t\t\"action\": \"accept\",\n\t\t\t\"src\":    [\"group:guest-users\"],\n\t\t\t\"dst\":    [\"tag:dns:53\"],\n\t\t},\n\t],"))
        assertEquals("", window(sample, acl).removed)

        val sshTest = PolicyEdits.addRule(sample, Section.SSH_TESTS, PolicyEdits.sshTestFields("alice@example.com", listOf("tag:master"), listOf("root"), emptyList(), emptyList()))
        assertTrue(sshTest, sshTest.contains("\t\"sshTests\": [\n\t\t{\n\t\t\t\"src\":    \"alice@example.com\",\n\t\t\t\"dst\":    [\"tag:master\"],\n\t\t\t\"accept\": [\"root\"],\n\t\t},\n\t],\n}"))
        assertEquals("", window(sample, sshTest).removed)

        val host = PolicyEdits.putHost(sample, "nas", "100.64.0.10")
        assertTrue(host.contains("\n\t\"hosts\": {\n\t\t\"nas\": \"100.64.0.10\",\n\t},\n\n\t\"tagOwners\""))
        val second = PolicyEdits.putHost(host, "printer", "192.168.1.20")
        assertTrue(second.contains("\t\t\"nas\": \"100.64.0.10\",\n\t\t\"printer\": \"192.168.1.20\",\n"))

        val route = PolicyEdits.setRouteApprovers(sample, "192.168.0.0/24", listOf("tag:homelab"))
        assertTrue(route, route.contains("\t\"autoApprovers\": {\n\t\t\"routes\": {\n\t\t\t\"192.168.0.0/24\": [\"tag:homelab\"],\n\t\t},\n\t\t\"exitNode\": [\"tag:exit-node\"],\n\t},"))
        assertEquals(sample, PolicyEdits.setRouteApprovers(route, "192.168.0.0/24", emptyList()).replace("\t\t\"routes\": {},\n", ""))
        val svc = PolicyEdits.setServiceApprovers(sample, "svc:web", listOf("tag:homelab"))
        assertTrue(svc, svc.contains("\t\t\"exitNode\": [\"tag:exit-node\"],\n\t\t\"services\": {\n\t\t\t\"svc:web\": [\"tag:homelab\"],\n\t\t},\n\t},"))
        assertEquals(listOf("svc:web"), PolicyModel.read(svc)!!.autoApprovers!!.services.map { it.name })
    }

    @Test
    fun renameAGroupEverywhereItIsUsed() {
        val after = PolicyEdits.rename(sample, "group:dev-admin", "group:devs")
        // Every occurrence in this file is a reference, and none sits in an aligned column: a plain replace.
        assertEquals(sample.replace("group:dev-admin", "group:devs"), after)
    }

    @Test
    fun renameATagKeepsAlignmentAndLeavesLongerNamesAlone() {
        val after = PolicyEdits.rename(sample, "tag:lab", "tag:research")
        val expected = Regex("tag:lab(?=[\":])").replace(sample, "tag:research")
        assertTrue(HuJson.sameMeaning(expected, after))
        assertTrue(after.contains("\"tag:lab-proxy\":     [\"group:dev-admin\"],"))
        assertTrue(after.contains("\"tag:research\":      [\"group:dev-admin\"],"))
        assertTrue(after.contains("\"tag:research:*\","))
        assertTrue(after.contains("\"dst\":    [\"tag:research\"],"))
        // SSH login names are not references: the user "worker" is not a tag and stays.
        assertTrue(after.contains("\"users\":  [\"root\", \"worker\", \"autogroup:nonroot\"],"))
        try {
            PolicyEdits.rename(sample, "tag:lab", "tag:dns")
            fail("renaming onto an existing name must be refused")
        } catch (_: PolicyEditException) {
        }
    }

    @Test
    fun renameAHostDoesNotTouchSshUsersOfTheSameName() {
        val text = """
            {
              "hosts": {"worker": "100.64.0.9"},
              "acls": [{"action": "accept", "src": ["worker"], "dst": ["worker:22"]}],
              "ssh": [{"action": "accept", "src": ["tag:a"], "dst": ["tag:b"], "users": ["worker"]}],
              "ipsets": {"ipset:lab": ["add host:worker", "remove 100.64.0.9"]},
              "tests": [{"src": "worker", "accept": ["worker:22"]}]
            }
        """.trimIndent()
        val after = PolicyEdits.rename(text, "worker", "box")
        assertTrue(after.contains("\"hosts\": {\"box\": \"100.64.0.9\"}"))
        assertTrue(after.contains("\"src\": [\"box\"], \"dst\": [\"box:22\"]"))
        assertTrue(after.contains("\"users\": [\"worker\"]"))
        assertTrue(after.contains("\"add host:box\""))
        assertTrue(after.contains("{\"src\": \"box\", \"accept\": [\"box:22\"]}"))
    }

    @Test
    fun usesFindWhatADeleteWouldLeaveDangling() {
        val t = SourceTree.parse(sample)
        assertEquals(listOf(PolicyPath.of("acls", 11, "src", 0)), PolicyEdits.uses(t, "group:guest-users"))
        val prod = PolicyEdits.uses(t, "group:prod-admin")
        assertTrue(PolicyPath.of("tagOwners", "tag:master", 0) in prod)
        assertTrue(PolicyPath.of("acls", 0, "dst", 0) in prod)
        assertTrue(PolicyPath.of("nodeAttrs", 1, "target", 0) in prod)
        assertFalse(prod.any { it.steps.first() == PathStep.Key("groups") })
        assertTrue(PolicyEdits.uses(t, "group:nobody").isEmpty())
    }

    @Test
    fun deleteAGroupDefinition() {
        val after = PolicyEdits.removeNamed(sample, Section.GROUPS, "group:guest-users")
        assertMeaning(removeAt(parsed(sample), listOf("groups", "group:guest-users")), after)
        assertTrue(after.contains("\t\t\"group:dev-admin\": [\n\t\t\t\"alice@example.com\",\n\t\t\t\"bob@example.com\",\n\t\t],\n\t},"))
    }

    @Test
    fun optionsAreSetAndCleared() {
        val on = PolicyEdits.setOption(sample, Section.RANDOMIZE_CLIENT_PORT, true.pv())
        assertTrue(on.endsWith("\t],\n\n\t\"randomizeClientPort\": true,\n}\n") || on.endsWith("\t],\n\n\t\"randomizeClientPort\": true,\n}"))
        assertEquals(sample, PolicyEdits.setOption(on, Section.RANDOMIZE_CLIENT_PORT, null))
    }

    @Test
    fun aTestFromWhatARuleAllows() {
        val fields = PolicyEdits.testFrom(model.acls[4])!!
        assertEquals(listOf("src" to "tag:server".pv(), "proto" to null, "accept" to listOf("tag:master:80").pv(), "deny" to null), fields)
        // A rule from "*" has no single source a test can name.
        assertNull(PolicyEdits.testFrom(model.acls[6]))
        // Deny form, for "this rule is gone, prove it".
        val deny = PolicyEdits.testFrom(model.acls[12], accept = false)!!
        assertEquals("carol@example.com".pv(), deny.toMap()["src"])
        assertEquals(listOf("tag:lab:22").pv(), deny.toMap()["deny"])
        // The test goes into the file and parses.
        val text = PolicyEdits.addRule(sample, Section.TESTS, fields)
        assertEquals(2, PolicyModel.read(text)!!.tests.size)
        assertNull(PolicyEdits.testFrom(model.grants.single()))
    }

    @Test
    fun anAclBecomesGrants() {
        val g = PolicyEdits.grantsFrom(model.acls[4]).single().toMap()
        assertEquals(listOf("tag:server").pv(), g["src"])
        assertEquals(listOf("tag:master").pv(), g["dst"])
        assertEquals(listOf("80", "443").pv(), g["ip"])
        val mixed = PolicyEdits.grantsFrom(AclRule(model.acls[0].origin, "accept", listOf("a@b"), listOf("tag:x:22", "tag:y:22", "tag:z:*"), "tcp", emptyList()))
        assertEquals(2, mixed.size)
        assertEquals(listOf("tag:x", "tag:y").pv(), mixed[0].toMap()["dst"])
        assertEquals(listOf("tcp:22").pv(), mixed[0].toMap()["ip"])
        assertEquals(listOf("tcp:*").pv(), mixed[1].toMap()["ip"])
    }
}
