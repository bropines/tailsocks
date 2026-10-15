package io.github.bropines.tailscaled.admin.policy

import androidx.annotation.StringRes
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import io.github.bropines.tailscaled.admin.policy.visual.PolicyModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * The policy tab's "Copy for an AI agent": one Markdown text that is a task for an assistant
 * and the map of the network under it — the tailnet, its users, the policy's groups, every
 * device, and the policy file verbatim. Built from what the console holds; nothing secret goes
 * in: a device's machine, node and Tailnet Lock keys, the console's credential, auth keys and
 * API keys are never read here, nor a device's public endpoints or a user's picture.
 */

/** The words of the text, in the app's language: the app's strings, or a stand-in in a test. */
fun interface ExportWords {
    fun get(@StringRes id: Int, vararg args: Any): String
}

/** What the map is made of. Lists that were not read are null and say so in the text. */
data class TailnetMap(
    val headscale: Boolean,
    /** The tailnet's name in the console ("example.com"); null for the token's default ("-"). */
    val tailnet: String?,
    /** The MagicDNS domain, "tail1234.ts.net". */
    val dnsSuffix: String?,
    val users: List<ApiUser>?,
    val devices: List<ApiDevice>?,
    /** Routes read per device (by [ApiDevice.pathId]), when the list itself left them out. */
    val routes: Map<String, DeviceRoutes> = emptyMap(),
    val policy: String,
    /** Tailscale: the policy is managed outside the console (GitOps). */
    val policyExternal: Boolean = false,
    /** Headscale: the server's version, when it told. */
    val headscaleVersion: String? = null,
    /** Headscale: true when the policy is a file on the server, false in its database, null unknown. */
    val headscalePolicyFile: Boolean? = null,
)

object PolicyExport {

    private const val TS_DOCS = "https://tailscale.com/docs"
    const val SYNTAX = "$TS_DOCS/reference/syntax/policy-file"
    const val ACLS = "$TS_DOCS/features/access-control/acls"
    const val GRANTS = "$TS_DOCS/features/access-control/grants"
    const val TAGS = "$TS_DOCS/features/tags"
    const val TESTS = "$SYNTAX#tests"
    const val AUTOGROUPS = "$TS_DOCS/reference/targets-and-selectors#autogroups"
    const val HS_POLICY = "https://headscale.net/stable/ref/policy/"
    const val HS_FEATURES = "https://headscale.net/stable/about/features/"
    private const val SITE = "https://bropines.github.io/tailsocks"

    /** The TailSocks site's page on remote management, in Russian for a Russian text. */
    fun remoteManagementUrl(russian: Boolean): String = if (russian) "$SITE/ru/remote-management/" else "$SITE/remote-management/"

    /** The documentation the agent is sent to first: Headscale's own where Tailscale's does not apply. */
    fun links(headscale: Boolean, russian: Boolean): List<Pair<Int, String>> = buildList {
        if (headscale) {
            add(R.string.admin_pex_link_hs_policy to HS_POLICY)
            add(R.string.admin_pex_link_hs_features to HS_FEATURES)
        }
        add(R.string.admin_pex_link_syntax to SYNTAX)
        add(R.string.admin_pex_link_acls to ACLS)
        add(R.string.admin_pex_link_grants to GRANTS)
        add(R.string.admin_pex_link_tags to TAGS)
        add(R.string.admin_pex_link_tests to TESTS)
        // Headscale's page above lists the autogroups it has; Tailscale's has role groups it lacks.
        if (!headscale) add(R.string.admin_pex_link_autogroups to AUTOGROUPS)
        add(R.string.admin_pex_link_remote to remoteManagementUrl(russian))
    }

    /** The text for an agent: the task, the links, then the map; [now] stamps it, in UTC. */
    fun forAgent(map: TailnetMap, words: ExportWords, russian: Boolean, now: Long): String = buildString {
        fun line(s: String = "") = append(s).append('\n')

        line("# " + words.get(if (map.headscale) R.string.admin_pex_title_hs else R.string.admin_pex_title))
        line()
        line(words.get(if (map.headscale) R.string.admin_pex_task_hs else R.string.admin_pex_task))
        line()
        links(map.headscale, russian).forEach { (label, url) -> line("- [${words.get(label)}]($url)") }
        line()
        line(words.get(R.string.admin_pex_task_end))
        line()

        line("## " + words.get(R.string.admin_pex_h_tailnet))
        line()
        map.tailnet?.takeIf { it.isNotBlank() && it != "-" }?.let { line("- " + words.get(R.string.admin_pex_tailnet_name, it)) }
        map.dnsSuffix?.takeIf { it.isNotBlank() }?.let { line("- " + words.get(R.string.admin_pex_tailnet_dns, it)) }
        if (map.headscale) {
            line("- " + words.get(R.string.admin_pex_server_hs, map.headscaleVersion ?: words.get(R.string.admin_pex_unknown)))
            when (map.headscalePolicyFile) {
                true -> line("- " + words.get(R.string.admin_pex_hs_policy_file))
                false -> line("- " + words.get(R.string.admin_pex_hs_policy_db))
                null -> Unit
            }
            line("- " + words.get(R.string.admin_pex_hs_no_roles))
        } else {
            line("- " + words.get(R.string.admin_pex_server_tailscale))
            if (map.policyExternal) line("- " + words.get(R.string.admin_pex_external))
        }
        line("- " + words.get(R.string.admin_pex_made, utc(now)))
        line()

        val users = map.users
        line("## " + words.get(R.string.admin_pex_h_users, users?.size ?: 0))
        line()
        when {
            users == null -> line(words.get(R.string.admin_pex_not_loaded))
            users.isEmpty() -> line(words.get(R.string.admin_pex_none))
            else -> {
                table(words.get(R.string.admin_pex_users_cols))
                users.sortedBy { it.loginName.lowercase() }.forEach { u ->
                    val role = when {
                        u.type == "shared" -> words.get(R.string.admin_pex_shared_in)
                        map.headscale -> "—"
                        else -> u.role?.takeIf { it.isNotBlank() } ?: "—"
                    }
                    row(listOf(u.loginName, u.displayName?.takeIf { it.isNotBlank() } ?: "—", role))
                }
            }
        }
        line()

        line("## " + words.get(R.string.admin_pex_h_groups))
        line()
        val model = PolicyModel.read(map.policy)
        when {
            model == null -> line(words.get(R.string.admin_pex_policy_unparsed))
            model.groups.isEmpty() -> line(words.get(R.string.admin_pex_none))
            else -> model.groups.forEach { g ->
                line("- `${g.name}`: " + (g.values.joinToString(", ").ifEmpty { words.get(R.string.admin_pex_none) }))
            }
        }
        line()

        val devices = map.devices
        line("## " + words.get(R.string.admin_pex_h_devices, devices?.size ?: 0))
        line()
        when {
            devices == null -> line(words.get(R.string.admin_pex_not_loaded))
            devices.isEmpty() -> line(words.get(R.string.admin_pex_none))
            else -> {
                table(words.get(R.string.admin_pex_devices_cols))
                devices.sortedBy { it.shortName.lowercase() }.forEach { d -> row(deviceCells(d, map.routes[d.pathId], words)) }
            }
        }
        line()

        line("## " + words.get(R.string.admin_pex_h_policy))
        line()
        val fence = fenceFor(map.policy)
        line(fence + "jsonc")
        append(map.policy)
        if (!map.policy.endsWith('\n')) line()
        line(fence)
    }

    /** One device as a row: only what a person would read in the console, never a key. */
    private fun deviceCells(d: ApiDevice, routes: DeviceRoutes?, words: ExportWords): List<String> {
        val owner = buildList {
            add(if (d.isTagged) d.tags.joinToString(", ") else d.user?.takeIf { it.isNotBlank() } ?: "—")
            if (d.isShared) add(words.get(R.string.admin_pex_shared_in))
            if (d.authorized == false) add(words.get(R.string.admin_pex_not_authorized))
        }.joinToString("; ")
        val online = when {
            d.isOnline -> words.get(R.string.admin_pex_online)
            !d.lastSeen.isNullOrBlank() -> words.get(R.string.admin_pex_last_seen, d.lastSeen)
            else -> words.get(R.string.admin_pex_offline)
        }
        val advertised = routes?.advertisedRoutes ?: d.advertisedRoutes
        val enabled = routes?.enabledRoutes ?: d.enabledRoutes
        val unknown = words.get(R.string.admin_pex_unknown)
        fun state(route: String) = words.get(if (enabled?.contains(route) == true) R.string.admin_pex_approved else R.string.admin_pex_not_approved)
        val exit = when {
            advertised == null -> unknown
            else -> advertised.firstOrNull { it in EXIT_ROUTES }?.let { words.get(R.string.admin_pex_advertised, state(it)) } ?: "—"
        }
        val subnets = when {
            advertised == null -> unknown
            else -> advertised.filter { it !in EXIT_ROUTES }.joinToString(", ") { "$it (${state(it)})" }.ifEmpty { "—" }
        }
        val expiry = when {
            d.keyExpiryDisabled == true -> words.get(R.string.admin_pex_expiry_off)
            !d.expires.isNullOrBlank() -> d.expires
            else -> "—"
        }
        return listOf(
            d.shortName,
            owner,
            d.os?.takeIf { it.isNotBlank() } ?: "—",
            d.clientVersion?.takeIf { it.isNotBlank() } ?: "—",
            d.ipv4 ?: "—",
            d.ipv6 ?: "—",
            online,
            exit,
            subnets,
            expiry,
        )
    }

    /** The routes that make a device an exit node. */
    private val EXIT_ROUTES = setOf("0.0.0.0/0", "::/0")

    private fun StringBuilder.table(header: String) {
        val cols = header.split('|').map { it.trim() }
        row(cols)
        append('|').append(cols.joinToString("|") { " --- " }).append("|\n")
    }

    private fun StringBuilder.row(cells: List<String>) {
        append("| ").append(cells.joinToString(" | ", transform = ::cell)).append(" |\n")
    }

    /** A table cell: a pipe would end it and a line break would end the row. */
    private fun cell(s: String): String = s.replace("|", "\\|").replace(Regex("\\s*\\R\\s*"), " ")

    /** A code fence longer than any run of backticks in [text], so the policy cannot close it. */
    internal fun fenceFor(text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        return "`".repeat(maxOf(3, longest + 1))
    }

    private fun utc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
