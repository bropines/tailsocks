package io.github.bropines.tailscaled.admin.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyLintTest {

    private val default = """
        {
          // The tailnet's default: everyone reaches everything.
          "acls": [{"action": "accept", "src": ["*"], "dst": ["*:*"]}],
          "ssh": [{"action": "check", "src": ["autogroup:member"], "dst": ["autogroup:self"], "users": ["autogroup:nonroot", "root"]}],
          "tests": [{"src": "a@example.com", "accept": ["tag:web:443"]}, {"src": "b@example.com", "deny": ["tag:db:5432"]}],
        }
    """.trimIndent()

    private fun introduced(before: String?, after: String) =
        PolicyLint.introduced(before?.let { HuJson.parse(it) }, HuJson.parse(after))

    @Test
    fun whatWasAlreadyThereIsNotNews() {
        assertTrue(introduced(default, default).isEmpty())
        val all = PolicyLint.findings(HuJson.parse(default)).map { it.kind }
        assertEquals(listOf(RiskKind.WILDCARD_SOURCE, RiskKind.WILDCARD_DESTINATION), all)
    }

    @Test
    fun aNewOrWidenedWildcardIsReportedWithItsLine() {
        val before = """{"acls": [{"action": "accept", "src": ["group:dev"], "dst": ["tag:web:443"]}]}"""
        val after = """
            {"acls": [
              {"action": "accept", "src": ["group:dev"], "dst": ["tag:web:443"]},
              {"action": "accept", "src": ["*"], "dst": ["tag:db:5432"]},
              {"action": "accept", "src": ["autogroup:danger-all"], "dst": ["tag:web:80"]},
            ]}
        """.trimIndent()
        val found = introduced(before, after)
        assertEquals(listOf(RiskKind.WILDCARD_SOURCE, RiskKind.DANGER_ALL), found.map { it.kind })
        assertEquals(3, found[0].line)
        assertEquals("* → tag:db:5432", found[0].detail)
        // The same rule reaching further is a new finding.
        val widened = after.replace("[\"tag:db:5432\"]", "[\"tag:db:5432\", \"tag:prod:*\"]")
        assertEquals(RiskKind.WILDCARD_SOURCE, introduced(after, widened).single().kind)
    }

    @Test
    fun aDenyRuleOpensNothing() {
        assertTrue(introduced("{}", """{"acls": [{"action": "deny", "src": ["*"], "dst": ["*:*"]}]}""").isEmpty())
    }

    @Test
    fun grantsAndSsh() {
        val after = """
            {
              "grants": [{"src": ["*"], "dst": ["*"], "ip": ["*"]}],
              "ssh": [
                {"action": "accept", "src": ["autogroup:member"], "dst": ["*"], "users": ["autogroup:nonroot"]},
                {"action": "accept", "src": ["group:ops"], "dst": ["tag:db"], "users": ["root"]},
                {"action": "check", "src": ["group:ops"], "dst": ["tag:web"], "users": ["root"]},
              ],
            }
        """.trimIndent()
        val kinds = introduced("{}", after).map { it.kind }
        assertEquals(
            listOf(RiskKind.WILDCARD_SOURCE, RiskKind.WILDCARD_DESTINATION, RiskKind.SSH_WILDCARD, RiskKind.SSH_ROOT),
            kinds
        )
    }

    @Test
    fun broadAutoApproversAndFunnel() {
        val after = """
            {
              "autoApprovers": {
                "routes": {"10.0.0.0/8": ["tag:router"], "192.168.1.0/24": ["autogroup:member"], "::/0": ["tag:exit"]},
                "exitNode": ["*"],
              },
              "nodeAttrs": [{"target": ["autogroup:member"], "attr": ["funnel"]}],
            }
        """.trimIndent()
        val found = introduced("{}", after)
        assertEquals(
            listOf(
                RiskKind.AUTOAPPROVER_WIDE_ROUTE, RiskKind.AUTOAPPROVER_BROAD, RiskKind.AUTOAPPROVER_WIDE_ROUTE,
                RiskKind.AUTOAPPROVER_BROAD, RiskKind.FUNNEL_BROAD,
            ),
            found.map { it.kind }
        )
        assertTrue(PolicyLint.isWideRoute("0.0.0.0/0"))
        assertTrue(PolicyLint.isWideRoute("2001:db8::/32"))
        assertTrue(!PolicyLint.isWideRoute("10.1.0.0/16"))
    }

    @Test
    fun removedTestsAreReported() {
        val fewer = default.replace(""", {"src": "b@example.com", "deny": ["tag:db:5432"]}""", "")
        val found = introduced(default, fewer).single()
        assertEquals(RiskKind.TESTS_REMOVED, found.kind)
        assertEquals("2 → 1", found.detail)
        val none = default.lines().filterNot { it.contains("\"tests\"") }.joinToString("\n")
        assertEquals(RiskKind.TESTS_REMOVED, introduced(default, none).single().kind)
    }
}
