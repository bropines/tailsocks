package io.github.bropines.tailscaled.admin.policy

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text for an AI agent: the task and its links, every device, no secret, the policy as it is. */
class PolicyExportTest {

    /** Each word as its resource id and arguments, so that a check can name the word it expects. */
    private val words = ExportWords { id, args ->
        when (id) {
            R.string.admin_pex_users_cols -> "Login|Name|Role"
            R.string.admin_pex_devices_cols -> "Device|Owner|OS|Client|IPv4|IPv6|Online|Exit|Subnets|Expiry"
            else -> "<$id" + args.joinToString("") { "~$it" } + ">"
        }
    }

    private fun w(id: Int, vararg args: Any): String = words.get(id, *args)

    /** A policy with comments, aligned values, a trailing comma and a run of backticks in a comment. */
    private val policy = """// The family tailnet. ```not a fence```
{
	"groups": {
		"group:admins": ["alice@example.com"],
		"group:family": ["alice@example.com", "bob@example.com"],
	},
	"grants": [
		{"src": ["autogroup:member"], "dst": ["autogroup:self"], "ip": ["*"]},
	],
}
"""

    private val users = listOf(
        ApiUser(id = "u1", loginName = "alice@example.com", displayName = "Alice", role = "owner", type = "member", profilePicUrl = "https://pics.example/SECRET-PIC"),
        ApiUser(id = "u2", loginName = "bob@example.com", displayName = "Bob", role = "admin", type = "member"),
        ApiUser(id = "u3", loginName = "carol@other.example", displayName = "Carol", role = "member", type = "shared"),
    )

    private val devices = listOf(
        ApiDevice(
            id = "1", nodeId = "nSECRETID1", name = "alice-phone.tail1234.ts.net.", os = "android", clientVersion = "1.104.1",
            user = "alice@example.com", addresses = listOf("100.64.0.1", "fd7a:115c:a1e0::1"), connectedToControl = true,
            expires = "2027-01-01T00:00:00Z", machineKey = "mkey:SECRET-MACHINE", nodeKey = "nodekey:SECRET-NODE",
            tailnetLockKey = "tlpub:SECRET-LOCK", advertisedRoutes = emptyList(), enabledRoutes = emptyList(),
        ),
        ApiDevice(
            id = "2", nodeId = "n2", name = "nas.tail1234.ts.net", os = "linux", tags = listOf("tag:nas", "tag:server"),
            user = "alice@example.com", addresses = listOf("100.64.0.2"), lastSeen = "2026-10-14T08:00:00Z", keyExpiryDisabled = true,
            advertisedRoutes = listOf("192.168.1.0/24", "10.0.0.0/8"), enabledRoutes = listOf("192.168.1.0/24"),
            machineKey = "mkey:SECRET-MACHINE-2", nodeKey = "nodekey:SECRET-NODE-2",
        ),
        ApiDevice(
            id = "3", nodeId = "n3", name = "exit-fra.tail1234.ts.net", os = "linux", tags = listOf("tag:exit"),
            addresses = listOf("100.64.0.3", "fd7a:115c:a1e0::3"), connectedToControl = true,
            advertisedRoutes = listOf("0.0.0.0/0", "::/0"), enabledRoutes = listOf("0.0.0.0/0", "::/0"),
        ),
        // Routes left out of the list; the console read them for this device on their own.
        ApiDevice(id = "4", nodeId = "n4", name = "router.tail1234.ts.net", os = "linux", user = "bob@example.com", addresses = listOf("100.64.0.4")),
        // Nothing about routes at all, a shared device, a name with a pipe in it.
        ApiDevice(id = "5", nodeId = "n5", name = "weird|name.tail1234.ts.net", os = "windows", user = "carol@other.example", addresses = listOf("100.64.0.5"), isExternal = true, authorized = false),
    )

    private val map = TailnetMap(
        headscale = false,
        tailnet = "example.com",
        dnsSuffix = "tail1234.ts.net",
        users = users,
        devices = devices,
        routes = mapOf("n4" to DeviceRoutes(advertisedRoutes = listOf("172.16.0.0/16"), enabledRoutes = emptyList())),
        policy = policy,
        policyExternal = true,
    )

    private val now = 1_792_000_000_000L

    private fun text(m: TailnetMap = map, russian: Boolean = false) = PolicyExport.forAgent(m, words, russian, now)

    private fun rowOf(text: String, name: String): String = text.lines().first { it.startsWith("| $name |") }

    @Test
    fun theTaskComesFirstWithEveryLink() {
        val t = text()
        val lines = t.lines()
        assertEquals("# " + w(R.string.admin_pex_title), lines[0])
        assertEquals(w(R.string.admin_pex_task), lines[2])
        for (url in listOf(PolicyExport.SYNTAX, PolicyExport.ACLS, PolicyExport.GRANTS, PolicyExport.TAGS, PolicyExport.TESTS, PolicyExport.AUTOGROUPS)) {
            assertTrue(url, t.contains("](${url})"))
        }
        assertTrue(t.contains("- [${w(R.string.admin_pex_link_remote)}](https://bropines.github.io/tailsocks/remote-management/)"))
        assertFalse(t.contains(PolicyExport.HS_POLICY))
        // The task's last word comes before the map.
        assertTrue(t.indexOf(w(R.string.admin_pex_task_end)) < t.indexOf("## " + w(R.string.admin_pex_h_tailnet)))
        // Russian points at the Russian page.
        assertTrue(text(russian = true).contains("https://bropines.github.io/tailsocks/ru/remote-management/"))
    }

    @Test
    fun theTailnetAndWhoManagesThePolicy() {
        val t = text()
        assertTrue(t.contains("- " + w(R.string.admin_pex_tailnet_name, "example.com")))
        assertTrue(t.contains("- " + w(R.string.admin_pex_tailnet_dns, "tail1234.ts.net")))
        assertTrue(t.contains("- " + w(R.string.admin_pex_server_tailscale)))
        assertTrue(t.contains("- " + w(R.string.admin_pex_external)))
        assertTrue(t.contains("- " + w(R.string.admin_pex_made, "2026-10-14 17:46 UTC")))
    }

    @Test
    fun usersWithTheirRolesAndTheGroupsOfThePolicy() {
        val t = text()
        assertTrue(t.contains("## " + w(R.string.admin_pex_h_users, 3)))
        assertTrue(t.contains("| alice@example.com | Alice | owner |"))
        assertTrue(t.contains("| bob@example.com | Bob | admin |"))
        assertTrue(t.contains("| carol@other.example | Carol | ${w(R.string.admin_pex_shared_in)} |"))
        assertTrue(t.contains("- `group:admins`: alice@example.com\n"))
        assertTrue(t.contains("- `group:family`: alice@example.com, bob@example.com\n"))
    }

    @Test
    fun everyDeviceWithItsAddressesRoutesAndKeyExpiry() {
        val t = text()
        assertTrue(t.contains("## " + w(R.string.admin_pex_h_devices, 5)))
        for (d in devices) {
            assertTrue(d.shortName, t.contains("| " + d.shortName.replace("|", "\\|") + " |"))
            d.addresses.forEach { assertTrue(it, t.contains(" $it ")) }
        }
        val phone = rowOf(t, "alice-phone")
        assertTrue(phone.contains("| alice@example.com | android | 1.104.1 | 100.64.0.1 | fd7a:115c:a1e0::1 | ${w(R.string.admin_pex_online)} | — | — | 2027-01-01T00:00:00Z |"))
        val nas = rowOf(t, "nas")
        assertTrue(nas.contains("| tag:nas, tag:server |"))
        assertTrue(nas.contains(w(R.string.admin_pex_last_seen, "2026-10-14T08:00:00Z")))
        assertTrue(nas.contains("192.168.1.0/24 (${w(R.string.admin_pex_approved)}), 10.0.0.0/8 (${w(R.string.admin_pex_not_approved)})"))
        assertTrue(nas.endsWith("| ${w(R.string.admin_pex_expiry_off)} |"))
        assertTrue(rowOf(t, "exit-fra").contains("| ${w(R.string.admin_pex_advertised, w(R.string.admin_pex_approved))} | — |"))
        // Routes read on their own fill in what the list left out.
        assertTrue(rowOf(t, "router").contains("172.16.0.0/16 (${w(R.string.admin_pex_not_approved)})"))
        val weird = rowOf(t, "weird\\|name")
        assertTrue(weird.contains("carol@other.example; ${w(R.string.admin_pex_shared_in)}; ${w(R.string.admin_pex_not_authorized)}"))
        // Unknown routes are said to be unknown, not "none".
        assertTrue(weird.contains("| ${w(R.string.admin_pex_unknown)} | ${w(R.string.admin_pex_unknown)} |"))
        // Every row has as many cells as the header.
        val header = w(R.string.admin_pex_devices_cols).split('|').size
        t.lines().filter { it.startsWith("| ") && "100.64." in it }.forEach { row ->
            assertEquals(row, header, row.replace("\\|", "").trim('|').split('|').size)
        }
    }

    @Test
    fun noSecretGoesIn() {
        val t = text()
        assertFalse(t, t.contains("SECRET"))
        assertFalse(t.contains("nodekey:"))
        assertFalse(t.contains("mkey:"))
        assertFalse(t.contains("tlpub:"))
    }

    @Test
    fun thePolicyIsQuotedVerbatimInAFenceItCannotClose() {
        val t = text()
        // The policy has three backticks in a row: the fence takes four.
        assertTrue(t.contains("\n````jsonc\n" + policy + "````\n"))
        assertTrue(t.endsWith("````\n"))
        assertEquals("```", PolicyExport.fenceFor("{}"))
        assertEquals("``````", PolicyExport.fenceFor("a ````` b"))
        // A policy with no newline at its end still closes the fence on a line of its own.
        val bare = text(map.copy(policy = "{}")).lines()
        assertEquals(listOf("```jsonc", "{}", "```", ""), bare.takeLast(4))
    }

    @Test
    fun headscaleSaysSoAndLinksItsOwnDocs() {
        val hs = map.copy(headscale = true, tailnet = null, headscaleVersion = "0.29.5", headscalePolicyFile = false, policyExternal = false,
            users = users.take(2).map { it.copy(role = null) })
        val t = text(hs)
        assertTrue(t.startsWith("# " + w(R.string.admin_pex_title_hs) + "\n"))
        assertTrue(t.contains(w(R.string.admin_pex_task_hs)))
        assertTrue(t.contains("](${PolicyExport.HS_POLICY})"))
        assertTrue(t.contains("](${PolicyExport.HS_FEATURES})"))
        // Headscale's own page lists its autogroups; Tailscale's names role groups it lacks.
        assertFalse(t.contains(PolicyExport.AUTOGROUPS))
        assertTrue(t.contains("- " + w(R.string.admin_pex_server_hs, "0.29.5")))
        assertTrue(t.contains("- " + w(R.string.admin_pex_hs_policy_db)))
        assertTrue(t.contains("- " + w(R.string.admin_pex_hs_no_roles)))
        assertFalse(t.contains(w(R.string.admin_pex_server_tailscale)))
        assertTrue(t.contains("| alice@example.com | Alice | — |"))
    }

    @Test
    fun listsNotReadAreSaidToBeMissing() {
        val t = text(map.copy(users = null, devices = null))
        assertTrue(t.contains("## " + w(R.string.admin_pex_h_users, 0) + "\n\n" + w(R.string.admin_pex_not_loaded)))
        assertTrue(t.contains("## " + w(R.string.admin_pex_h_devices, 0) + "\n\n" + w(R.string.admin_pex_not_loaded)))
        assertTrue(text(map.copy(policy = "{ broken")).contains(w(R.string.admin_pex_policy_unparsed)))
    }
}
