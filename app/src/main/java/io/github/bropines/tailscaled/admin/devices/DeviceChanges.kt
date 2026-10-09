package io.github.bropines.tailscaled.admin.devices

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.console.ConsoleChanges
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.AuditRecord
import io.github.bropines.tailscaled.admin.safety.AuditResult
import io.github.bropines.tailscaled.admin.safety.ChangeClass
import io.github.bropines.tailscaled.admin.safety.ChangeClassifier
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.ChangeTarget
import io.github.bropines.tailscaled.admin.safety.DiffLine
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.TargetType

/**
 * The device changes the console's own builders do not cover — a new IPv4 address, a name
 * reset to the hostname, tags over several devices — and the tag change with the stronger
 * ownership warning. Same contract as [ConsoleChanges]: words in the language of `ctx`, an
 * apply, a re-read, an undo where the API has one.
 */
object DeviceChanges {

    fun setIpv4(ctx: Context, d: ApiDevice, ipv4: String, selfNodeId: String?, undo: Boolean = false): PlannedChange {
        val old = d.ipv4
        return PlannedChange(
            change = AdminChange(
                kind = ChangeKind.DEVICE_IPV4,
                changeClass = ChangeClassifier.classify(ChangeKind.DEVICE_IPV4),
                target = ConsoleChanges.deviceTarget(d, selfNodeId),
                title = ctx.getString(R.string.admin_dev_change_ip, d.shortName),
                effect = ctx.getString(R.string.admin_dev_change_ip_effect, ipv4),
                diff = listOf(DiffLine(ctx.getString(R.string.admin_dev_ipv4), old, ipv4)),
            ),
            apply = { it.setDeviceIpv4(d.pathId, ipv4) },
            verify = { it.getDevice(d.pathId).ipv4 == ipv4 },
            // The old address is free again once this one is set: putting it back is the same call.
            undo = if (undo || old == null) null
            else setIpv4(ctx, d.copy(addresses = d.addresses.map { if (it == old) ipv4 else it }), old, selfNodeId, undo = true),
            isUndo = undo,
        )
    }

    /** The API's empty name: the server derives one from the hostname again. No undo — the old name can be typed back. */
    fun resetName(ctx: Context, d: ApiDevice, selfNodeId: String?): PlannedChange {
        val host = d.hostname?.takeIf { it.isNotBlank() } ?: d.shortName
        val guess = DeviceNames.fromHostname(d.hostname)
        return PlannedChange(
            change = AdminChange(
                kind = ChangeKind.DEVICE_RENAME,
                changeClass = ChangeClassifier.classify(ChangeKind.DEVICE_RENAME),
                target = ConsoleChanges.deviceTarget(d, selfNodeId),
                title = ctx.getString(R.string.admin_dev_change_reset_name, d.shortName),
                effect = ctx.getString(R.string.admin_dev_change_reset_name_effect, host),
                diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_name), d.shortName, guess ?: host)),
            ),
            apply = { it.resetDeviceName(d.pathId) },
            verify = { b ->
                val now = b.getDevice(d.pathId).shortName
                // The server may add a suffix when the plain name is taken.
                (guess != null && (now.equals(guess, ignoreCase = true) || now.startsWith("$guess-", ignoreCase = true))) ||
                    !now.equals(d.shortName, ignoreCase = true)
            },
        )
    }

    /**
     * The console's tag change, with the warnings this screen owes: tagging a person's device
     * takes it away from them for good (as far as this console goes), and taking every tag off
     * leaves the server to decide who owns it.
     */
    fun setTags(ctx: Context, d: ApiDevice, tags: List<String>, selfNodeId: String?): PlannedChange {
        val base = ConsoleChanges.setTags(ctx, d, tags, selfNodeId)
        val warnings = buildList {
            if (d.tags.isEmpty() && tags.isNotEmpty()) {
                add(ctx.getString(R.string.admin_dev_tags_owner_warning, d.user?.takeIf { it.isNotBlank() } ?: d.shortName))
            }
            if (d.tags.isNotEmpty() && tags.isEmpty()) add(ctx.getString(R.string.admin_dev_tags_clear_warning))
        }
        return PlannedChange(base.change.copy(warnings = warnings), base.apply, base.verify, base.undo, base.isUndo)
    }

    /**
     * The console's route change (HIGH when it takes an approved exit node away, see
     * [ChangeClassifier.routes]), saying what that does to the devices that use it.
     */
    fun setRoutes(ctx: Context, d: ApiDevice, before: List<String>, after: List<String>, selfNodeId: String?): PlannedChange {
        val base = ConsoleChanges.setRoutes(ctx, d, before, after, selfNodeId)
        if (ChangeClassifier.routes(before, after) != ChangeClass.HIGH) return base
        val warnings = base.change.warnings + ctx.getString(R.string.admin_dev_exit_off_warning)
        return PlannedChange(base.change.copy(warnings = warnings), base.apply, base.verify, base.undo, base.isUndo)
    }

    /**
     * One tag change over several devices, HIGH: one confirmation with the whole diff, the count
     * of devices typed back, one unlock, then each device in turn ([BulkTags.apply]). Every device
     * gets its own record in the audit log through [record], besides the batch's own; [onOutcome]
     * hears each result as it lands. Fails as a whole only when no device took the change.
     */
    fun bulkTags(
        ctx: Context,
        rows: List<BulkTagRow>,
        mode: BulkTagMode,
        tags: List<String>,
        selfNodeId: String?,
        profileId: String,
        profileName: String,
        onStart: () -> Unit,
        onOutcome: (BulkOutcome) -> Unit,
        record: (AuditRecord) -> Unit,
    ): PlannedChange {
        val changing = rows.filter { it.changes }
        val n = changing.size
        val label = ctx.resources.getQuantityString(R.plurals.admin_dev_bulk_target, n, n)
        val chosen = tags.mapNotNull(BulkTags::normalize).distinct()
        val tagList = chosen.joinToString(", ")
        val effect = ctx.getString(
            when (mode) {
                BulkTagMode.ADD -> R.string.admin_dev_bulk_effect_add
                BulkTagMode.REMOVE -> R.string.admin_dev_bulk_effect_remove
                BulkTagMode.REPLACE -> R.string.admin_dev_bulk_effect_replace
            },
            tagList,
        )
        val owned = changing.filter { it.takesOwnership }
        val warnings = buildList {
            if (owned.isNotEmpty()) {
                add(
                    ctx.resources.getQuantityString(
                        R.plurals.admin_dev_bulk_owner_warning_named, owned.size, owned.size, owned.joinToString(", ") { it.device.shortName },
                    )
                )
            }
        }
        // Worded now, in the language the change was made in, for each device's own record.
        val rowEffect = changing.associate { row ->
            row.device.pathId to ctx.getString(
                R.string.admin_dev_bulk_record_effect,
                ConsoleText.list(row.before) ?: ctx.getString(R.string.admin2_confirm_nothing),
                ConsoleText.list(row.after) ?: ctx.getString(R.string.admin2_confirm_nothing),
            )
        }
        var outcomes: List<BulkOutcome> = emptyList()
        return PlannedChange(
            change = AdminChange(
                kind = ChangeKind.DEVICE_TAGS_BULK,
                changeClass = ChangeClassifier.classify(ChangeKind.DEVICE_TAGS_BULK),
                target = ChangeTarget(
                    type = TargetType.DEVICE,
                    id = label,
                    name = label,
                    isThisDevice = selfNodeId != null && changing.any { it.device.nodeId == selfNodeId },
                ),
                title = ctx.resources.getQuantityString(R.plurals.admin_dev_bulk_change_title, n, n),
                effect = effect,
                diff = changing.map { DiffLine(it.device.shortName, ConsoleText.list(it.before), ConsoleText.list(it.after)) },
                warnings = warnings,
            ),
            apply = { b ->
                onStart()
                outcomes = BulkTags.apply(b, changing) { row, out ->
                    onOutcome(out)
                    record(
                        AuditRecord(
                            time = System.currentTimeMillis(),
                            profileId = profileId,
                            profileName = profileName,
                            kind = ChangeKind.DEVICE_TAGS,
                            changeClass = ChangeClass.HIGH,
                            targetType = TargetType.DEVICE,
                            targetId = row.device.pathId,
                            targetName = row.device.shortName,
                            effect = rowEffect[row.device.pathId].orEmpty(),
                            result = when (out.state) {
                                BulkState.VERIFIED -> AuditResult.VERIFIED
                                BulkState.APPLIED, BulkState.PENDING -> AuditResult.APPLIED
                                BulkState.MISMATCH -> AuditResult.MISMATCH
                                BulkState.FAILED -> AuditResult.FAILED
                                BulkState.NOT_SENT -> AuditResult.REFUSED
                            },
                            detail = out.error?.let { e -> listOfNotNull(e.message, (e as? AdminApiException)?.apiMessage).joinToString(": ") },
                            requestIds = out.requestIds,
                        )
                    )
                }
                val sent = outcomes.filter { it.state != BulkState.NOT_SENT }
                if (sent.isNotEmpty() && sent.all { it.state == BulkState.FAILED }) {
                    throw sent.first().error ?: IllegalStateException("no device took the change")
                }
            },
            verify = {
                // Each device was re-read as it went; "unknown" ends as a plain "done".
                BulkTags.verified(outcomes) ?: throw IllegalStateException("not every device could be re-read")
            },
        )
    }
}
