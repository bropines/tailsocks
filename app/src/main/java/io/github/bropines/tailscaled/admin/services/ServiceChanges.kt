package io.github.bropines.tailscaled.admin.services

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.ApiService
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClassifier
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.ChangeTarget
import io.github.bropines.tailscaled.admin.safety.DiffLine
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.TargetType

/**
 * Service definitions as planned changes: create (MEDIUM), edit (MEDIUM), rename (HIGH — the
 * old name stops resolving for every client) and delete (HIGH). A rename is the same PUT as an
 * edit, sent to the old name with the new one in the body. Each is verified by reading the
 * service back; edits and renames can be undone.
 */
object ServiceChanges {

    fun target(name: String) = ChangeTarget(TargetType.SERVICE, name, name)

    fun create(ctx: Context, service: ApiService): PlannedChange {
        val s = ServiceForm.normalized(service)
        return PlannedChange(
            change = AdminChange(
                ChangeKind.SERVICE_PUBLISH, ChangeClassifier.classify(ChangeKind.SERVICE_PUBLISH), target(s.name),
                title = ctx.getString(R.string.admin_svc_change_create, s.name),
                effect = ctx.getString(R.string.admin_svc_change_create_effect),
                diff = diff(ctx, null, s),
            ),
            apply = { it.putService(s) },
            verify = { b -> b.getService(s.name)?.let { same(it, s) } == true },
        )
    }

    /** An edit of [before] into [after]; a different name makes it a rename. */
    fun update(ctx: Context, before: ApiService, after: ApiService, undo: Boolean = false): PlannedChange {
        val a = ServiceForm.normalized(after)
        val rename = a.name != before.name
        val kind = if (rename) ChangeKind.SERVICE_RENAME else ChangeKind.SERVICE_PUBLISH
        // The addresses go with it: an update that leaves them out is one that asks for new ones.
        // A comment taken away is sent empty; left out, the old one would stay.
        val sent = a.copy(addrs = a.addrs.ifEmpty { before.addrs }, comment = a.comment ?: "".takeIf { !before.comment.isNullOrBlank() })
        return PlannedChange(
            change = AdminChange(
                kind, ChangeClassifier.classify(kind), target(before.name),
                title = ctx.getString(if (rename) R.string.admin_svc_change_rename else R.string.admin_svc_change_edit, before.name),
                effect = if (rename) ctx.getString(R.string.admin_svc_change_rename_effect, a.name) else ctx.getString(R.string.admin_svc_change_edit_effect),
                diff = diff(ctx, before, sent),
            ),
            apply = { it.putService(sent, pathName = before.name) },
            verify = { b ->
                val now = b.getService(sent.name)
                now != null && same(now, sent) && (!rename || b.getService(before.name) == null)
            },
            undo = if (undo) null else update(ctx, sent, before, undo = true),
            isUndo = undo,
        )
    }

    fun delete(ctx: Context, service: ApiService): PlannedChange = PlannedChange(
        change = AdminChange(
            ChangeKind.SERVICE_DELETE, ChangeClassifier.classify(ChangeKind.SERVICE_DELETE), target(service.name),
            title = ctx.getString(R.string.admin_svc_change_delete, service.name),
            effect = ctx.getString(R.string.admin_svc_change_delete_effect),
            diff = listOf(
                DiffLine(
                    ctx.getString(R.string.admin_svc_diff_service),
                    listOfNotNull(service.name, ConsoleText.list(service.addrs)).joinToString(" · "),
                    null,
                )
            ),
        ),
        apply = { it.deleteService(service.name) },
        verify = { b -> b.getService(service.name) == null },
    )

    /** What the server holds matches what was sent: the parts a definition sets. */
    internal fun same(a: ApiService, b: ApiService): Boolean =
        a.name == b.name && a.ports.toSet() == b.ports.toSet() && a.tags.toSet() == b.tags.toSet() &&
            a.comment.orEmpty() == b.comment.orEmpty() && a.displayName.orEmpty() == b.displayName.orEmpty()

    /** Before → after rows for the fields that differ; every set field for a new service. */
    internal fun diff(ctx: Context, before: ApiService?, after: ApiService): List<DiffLine> = buildList {
        fun row(label: Int, b: String?, a: String?) {
            if (before == null && a == null) return
            if (before != null && b == a) return
            add(DiffLine(ctx.getString(label), b, a))
        }
        row(R.string.admin_svc_diff_name, before?.name, after.name)
        row(R.string.admin_svc_diff_display, before?.displayName?.ifBlank { null }, after.displayName?.ifBlank { null })
        row(R.string.admin_svc_diff_ports, before?.let { ConsoleText.list(it.ports.sorted()) }, ConsoleText.list(after.ports.sorted()))
        row(R.string.admin_svc_diff_tags, before?.let { ConsoleText.list(it.tags.sorted()) }, ConsoleText.list(after.tags.sorted()))
        row(R.string.admin_svc_diff_comment, before?.comment?.ifBlank { null }, after.comment?.ifBlank { null })
        if (before == null && after.addrs.isNotEmpty()) row(R.string.admin_svc_diff_addrs, null, ConsoleText.list(after.addrs))
    }
}

/** What a service definition must look like before it is sent. */
object ServiceForm {
    const val PREFIX = "svc:"
    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val PORT = Regex("^tcp:(\\d{1,5})(?:-(\\d{1,5}))?$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    enum class Problem { NAME, PORTS, ADDRESS, DISPLAY_NAME }

    /** "web" and "SVC:Web " both become "svc:web". */
    fun name(text: String): String {
        val t = text.trim().lowercase()
        return if (t.startsWith(PREFIX)) t else PREFIX + t
    }

    /** "443, tcp:80" → ["tcp:443", "tcp:80"]; a bare number means TCP, the only protocol there is. */
    fun ports(text: String): List<String> = text.split(',', ' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        .map { if (it.first().isDigit()) "tcp:$it" else it }.distinct()

    fun validate(name: String, ports: List<String>, ipv4: String = "", displayName: String = ""): Problem? {
        if (!LABEL.matches(name.removePrefix(PREFIX))) return Problem.NAME
        if (ports.isEmpty() || ports.any { !portOk(it) }) return Problem.PORTS
        if (ipv4.isNotBlank() && !ipv4Ok(ipv4.trim())) return Problem.ADDRESS
        if (displayName.length > 64) return Problem.DISPLAY_NAME
        return null
    }

    private fun portOk(p: String): Boolean {
        if (p == "do-not-validate") return true
        val m = PORT.matchEntire(p) ?: return false
        val from = m.groupValues[1].toInt()
        val to = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: from
        return from in 1..65535 && to in from..65535
    }

    private fun ipv4Ok(a: String): Boolean = IPV4.matchEntire(a)?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true

    /** Trimmed, blank optional fields dropped, ports and tags without duplicates. */
    fun normalized(s: ApiService): ApiService = s.copy(
        name = name(s.name),
        displayName = s.displayName?.trim()?.ifBlank { null },
        comment = s.comment?.trim()?.ifBlank { null },
        ports = s.ports.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        tags = s.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        addrs = s.addrs.map { it.trim() }.filter { it.isNotEmpty() },
    )
}
