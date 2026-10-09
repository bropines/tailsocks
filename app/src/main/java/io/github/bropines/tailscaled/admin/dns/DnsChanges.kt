package io.github.bropines.tailscaled.admin.dns

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminBackend
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.DnsConfiguration
import io.github.bropines.tailscaled.admin.api.DnsResolver
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClassifier
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.ChangeTarget
import io.github.bropines.tailscaled.admin.safety.DiffLine
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.TargetType

/**
 * One DNS change, as a delta: with /dns/configuration it is applied to a fresh read of the
 * whole document right before the write — the endpoint replaces everything, and a stale copy
 * would undo a change made elsewhere since the tab loaded — and without it, sent to the part's
 * own older endpoint.
 */
sealed class DnsEdit {
    abstract fun applyTo(c: DnsConfiguration): DnsConfiguration
    /** Whether [c] — a re-read — holds what this edit asked for. */
    abstract fun holds(c: DnsConfiguration): Boolean
    abstract suspend fun sendLegacy(b: AdminBackend)

    data class Nameservers(val list: List<DnsResolver>) : DnsEdit() {
        override fun applyTo(c: DnsConfiguration) = c.copy(nameservers = list)
        override fun holds(c: DnsConfiguration) = sameResolvers(c.nameservers, list)
        override suspend fun sendLegacy(b: AdminBackend) = b.setNameservers(list.map { it.address })
    }

    /** [resolvers] null removes the domain. */
    data class Split(val domain: String, val resolvers: List<DnsResolver>?) : DnsEdit() {
        override fun applyTo(c: DnsConfiguration) =
            c.copy(splitDns = if (resolvers == null) c.splitDns - domain else c.splitDns + (domain to resolvers))
        override fun holds(c: DnsConfiguration) =
            if (resolvers == null) c.splitDns[domain].isNullOrEmpty() else sameResolvers(c.splitDns[domain].orEmpty(), resolvers)
        override suspend fun sendLegacy(b: AdminBackend) = b.setSplitDnsDomain(domain, resolvers?.map { it.address })
    }

    data class SearchPaths(val list: List<String>) : DnsEdit() {
        override fun applyTo(c: DnsConfiguration) = c.copy(searchPaths = list)
        override fun holds(c: DnsConfiguration) = c.searchPaths == list
        override suspend fun sendLegacy(b: AdminBackend) = b.setSearchPaths(list)
    }

    data class MagicDns(val on: Boolean) : DnsEdit() {
        override fun applyTo(c: DnsConfiguration) = c.copy(preferences = c.preferences.copy(magicDNS = on))
        override fun holds(c: DnsConfiguration) = c.magicDns == on
        override suspend fun sendLegacy(b: AdminBackend) = b.setMagicDns(on)
    }

    /** Only /dns/configuration has it. */
    data class OverrideLocal(val on: Boolean) : DnsEdit() {
        override fun applyTo(c: DnsConfiguration) = c.copy(preferences = c.preferences.copy(overrideLocalDNS = on))
        override fun holds(c: DnsConfiguration) = (c.preferences.overrideLocalDNS == true) == on
        override suspend fun sendLegacy(b: AdminBackend) = throw AdminApiException.Unsupported(BackendFeature.DNS_CONFIGURATION)
    }

    companion object {
        /** Same addresses in the same order; a flag compared only where it was set. */
        fun sameResolvers(server: List<DnsResolver>, asked: List<DnsResolver>): Boolean =
            server.map { it.address } == asked.map { it.address } &&
                server.zip(asked).all { (s, a) -> a.useWithExitNode == null || (s.useWithExitNode == true) == a.useWithExitNode }
    }
}

/** The DNS tab's changes as [PlannedChange]s, classified by what they take away. */
object DnsChanges {

    private fun target(tailnetLabel: String) = ChangeTarget(TargetType.TAILNET, "-", tailnetLabel)

    /** A resolver as the diff shows it: its address, and the exit-node flag when it is set. */
    fun show(ctx: Context, r: DnsResolver): String =
        if (r.useWithExitNode == true) ctx.getString(R.string.admin_cfg_dns_with_exit, r.address) else r.address

    private fun list(ctx: Context, rs: List<DnsResolver>) = ConsoleText.list(rs.map { show(ctx, it) })

    fun plan(ctx: Context, edit: DnsEdit, before: DnsConfiguration, combined: Boolean, tailnetLabel: String, undo: Boolean = false): PlannedChange {
        val change = describe(ctx, edit, before, tailnetLabel)
        return PlannedChange(
            change = change,
            apply = { b ->
                if (combined) b.setDnsConfiguration(edit.applyTo(b.dnsConfiguration())) else edit.sendLegacy(b)
            },
            verify = { b -> edit.holds(b.dnsConfiguration()) },
            undo = if (undo) null else undoOf(edit, before)?.let { plan(ctx, it, edit.applyTo(before), combined, tailnetLabel, undo = true) },
            isUndo = undo,
        )
    }

    /** The edit that puts [before] back, where one does. */
    private fun undoOf(edit: DnsEdit, before: DnsConfiguration): DnsEdit? = when (edit) {
        is DnsEdit.Nameservers -> DnsEdit.Nameservers(before.nameservers)
        is DnsEdit.Split -> DnsEdit.Split(edit.domain, before.splitDns[edit.domain])
        is DnsEdit.SearchPaths -> DnsEdit.SearchPaths(before.searchPaths)
        is DnsEdit.MagicDns -> DnsEdit.MagicDns(!edit.on)
        is DnsEdit.OverrideLocal -> DnsEdit.OverrideLocal(!edit.on)
    }

    fun describe(ctx: Context, edit: DnsEdit, before: DnsConfiguration, tailnetLabel: String): AdminChange {
        val t = target(tailnetLabel)
        val override = before.preferences.overrideLocalDNS == true
        return when (edit) {
            is DnsEdit.Nameservers -> {
                val emptied = before.nameservers.isNotEmpty() && edit.list.isEmpty()
                val cls = ChangeClassifier.nameservers(before.nameserverAddresses, edit.list.map { it.address }, before.magicDns, override)
                val diff = buildList {
                    add(DiffLine(ctx.getString(R.string.admin2_diff_nameservers), list(ctx, before.nameservers), list(ctx, edit.list)))
                    if (emptied && before.magicDns) add(DiffLine(ctx.getString(R.string.admin_cfg_dns_magic), ConsoleText.onOff(ctx, true), ConsoleText.onOff(ctx, false)))
                }
                AdminChange(
                    ChangeKind.DNS_NAMESERVERS, cls, t,
                    title = ctx.getString(R.string.admin2_change_nameservers),
                    effect = ctx.getString(
                        when {
                            emptied && before.magicDns -> R.string.admin_cfg_dns_ns_none_magic_effect
                            emptied && override -> R.string.admin_cfg_dns_ns_none_override_effect
                            else -> R.string.admin2_change_nameservers_effect
                        }
                    ),
                    diff = diff,
                )
            }
            is DnsEdit.Split -> {
                val had = before.splitDns[edit.domain]
                val (title, effect) = when {
                    edit.resolvers == null -> ctx.getString(R.string.admin2_change_split_remove, edit.domain) to
                        ctx.getString(R.string.admin2_change_split_remove_effect, edit.domain)
                    had != null -> ctx.getString(R.string.admin_cfg_dns_split_change, edit.domain) to
                        ctx.getString(R.string.admin2_change_split_add_effect, edit.domain, list(ctx, edit.resolvers).orEmpty())
                    else -> ctx.getString(R.string.admin2_change_split_add, edit.domain) to
                        ctx.getString(R.string.admin2_change_split_add_effect, edit.domain, list(ctx, edit.resolvers).orEmpty())
                }
                AdminChange(
                    ChangeKind.DNS_SPLIT, ChangeClassifier.classify(ChangeKind.DNS_SPLIT), t, title, effect,
                    diff = listOf(DiffLine(edit.domain, had?.let { list(ctx, it) }, edit.resolvers?.let { list(ctx, it) })),
                )
            }
            is DnsEdit.SearchPaths -> AdminChange(
                ChangeKind.DNS_SEARCH_PATHS, ChangeClassifier.searchPaths(before.searchPaths, edit.list), t,
                title = ctx.getString(R.string.admin2_change_search),
                effect = ctx.getString(R.string.admin2_change_search_effect),
                diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_search), ConsoleText.list(before.searchPaths), ConsoleText.list(edit.list))),
            )
            is DnsEdit.MagicDns -> AdminChange(
                ChangeKind.DNS_MAGIC_DNS, ChangeClassifier.magicDns(edit.on), t,
                title = ctx.getString(if (edit.on) R.string.admin2_change_magicdns_on else R.string.admin2_change_magicdns_off),
                effect = ctx.getString(if (edit.on) R.string.admin2_change_magicdns_on_effect else R.string.admin_cfg_dns_magic_off_effect),
                diff = listOf(DiffLine(ctx.getString(R.string.admin_cfg_dns_magic), ConsoleText.onOff(ctx, before.magicDns), ConsoleText.onOff(ctx, edit.on))),
                warnings = if (edit.on && before.nameservers.isEmpty()) listOf(ctx.getString(R.string.admin_cfg_dns_magic_needs_ns)) else emptyList(),
            )
            is DnsEdit.OverrideLocal -> AdminChange(
                ChangeKind.DNS_OVERRIDE_LOCAL, ChangeClassifier.classify(ChangeKind.DNS_OVERRIDE_LOCAL), t,
                title = ctx.getString(if (edit.on) R.string.admin_cfg_dns_override_on else R.string.admin_cfg_dns_override_off),
                effect = ctx.getString(if (edit.on) R.string.admin_cfg_dns_override_on_effect else R.string.admin_cfg_dns_override_off_effect),
                diff = listOf(DiffLine(ctx.getString(R.string.admin_cfg_dns_override), ConsoleText.onOff(ctx, override), ConsoleText.onOff(ctx, edit.on))),
            )
        }
    }
}
