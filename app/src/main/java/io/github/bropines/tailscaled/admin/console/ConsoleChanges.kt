package io.github.bropines.tailscaled.admin.console

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.ApiService
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.ApiWebhook
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.api.TailnetSettingKey
import io.github.bropines.tailscaled.admin.api.TailnetSettings
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.api.UserStatus
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClass
import io.github.bropines.tailscaled.admin.safety.ChangeClassifier
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.ChangeTarget
import io.github.bropines.tailscaled.admin.safety.DiffLine
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.TargetType
import java.net.URI

/**
 * Every write the console offers, as a [PlannedChange]: what it is (title, effect, diff, in the
 * language of [ctx]), how to apply it, how to check it took, and its undo where the API has
 * one. The screens never call the backend's write methods themselves.
 */
object ConsoleChanges {

    private fun change(
        kind: ChangeKind,
        target: ChangeTarget,
        title: String,
        effect: String,
        diff: List<DiffLine> = emptyList(),
        warnings: List<String> = emptyList(),
        cls: ChangeClass = ChangeClassifier.classify(kind),
        area: AdminArea = kind.area,
    ) = AdminChange(kind, cls, target, title, effect, diff, warnings, area)

    private fun yesNo(ctx: Context, v: Boolean?) = ctx.getString(if (v == true) R.string.admin2_yes else R.string.admin2_no)

    // ------------------------------------------------------------------ devices

    fun deviceTarget(d: ApiDevice, selfNodeId: String?) = ChangeTarget(
        type = TargetType.DEVICE,
        id = d.pathId,
        name = d.shortName,
        shared = d.isShared,
        isThisDevice = selfNodeId != null && d.nodeId == selfNodeId,
    )

    fun renameDevice(ctx: Context, d: ApiDevice, newName: String, selfNodeId: String?, undo: Boolean = false): PlannedChange {
        val name = newName.trim()
        val target = deviceTarget(d, selfNodeId)
        return PlannedChange(
            change = change(
                ChangeKind.DEVICE_RENAME, target,
                title = ctx.getString(R.string.admin2_change_rename, d.shortName),
                effect = ctx.getString(R.string.admin2_change_rename_effect, d.dnsSuffix?.let { "$name.$it" } ?: name),
                diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_name), d.shortName, name)),
            ),
            apply = { it.renameDevice(d.pathId, name) },
            verify = { it.getDevice(d.pathId).shortName.equals(name, ignoreCase = true) },
            undo = if (undo) null else renameDevice(ctx, d.copy(name = d.dnsSuffix?.let { "$name.$it" } ?: name), d.shortName, selfNodeId, undo = true),
            isUndo = undo,
        )
    }

    fun setTags(ctx: Context, d: ApiDevice, tags: List<String>, selfNodeId: String?, undo: Boolean = false): PlannedChange {
        val next = tags.distinct()
        val warnings = buildList {
            if (d.tags.isEmpty() && next.isNotEmpty() && !d.user.isNullOrBlank()) add(ctx.getString(R.string.admin2_device_tags_owner_warning, d.user))
        }
        return PlannedChange(
            change = change(
                ChangeKind.DEVICE_TAGS, deviceTarget(d, selfNodeId),
                title = ctx.getString(R.string.admin2_change_tags, d.shortName),
                effect = ctx.getString(R.string.admin2_change_tags_effect),
                diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_tags), ConsoleText.list(d.tags), ConsoleText.list(next))),
                warnings = warnings,
            ),
            apply = { it.setDeviceTags(d.pathId, next) },
            verify = { it.getDevice(d.pathId).tags.toSet() == next.toSet() },
            // Tags cannot be taken off a device back to its user; an undo exists only between tag sets.
            undo = if (undo || d.tags.isEmpty()) null else setTags(ctx, d.copy(tags = next), d.tags, selfNodeId, undo = true),
            isUndo = undo,
        )
    }

    /** [title] tells an exit-node switch from a subnet route; the change is the same call. */
    fun setRoutes(ctx: Context, d: ApiDevice, before: List<String>, after: List<String>, selfNodeId: String?, undo: Boolean = false): PlannedChange {
        val exitBefore = "0.0.0.0/0" in before || "::/0" in before
        val exitAfter = "0.0.0.0/0" in after || "::/0" in after
        val exitOnly = before.filterNot { it == "0.0.0.0/0" || it == "::/0" }.toSet() == after.filterNot { it == "0.0.0.0/0" || it == "::/0" }.toSet()
        val (title, effect) = when {
            exitOnly && exitAfter && !exitBefore -> ctx.getString(R.string.admin2_change_exit_on, d.shortName) to ctx.getString(R.string.admin2_change_exit_effect)
            exitOnly && !exitAfter && exitBefore -> ctx.getString(R.string.admin2_change_exit_off, d.shortName) to ctx.getString(R.string.admin2_change_exit_effect)
            else -> ctx.getString(R.string.admin2_change_routes, d.shortName) to ctx.getString(R.string.admin2_change_routes_effect)
        }
        return PlannedChange(
            change = change(
                ChangeKind.DEVICE_ROUTES, deviceTarget(d, selfNodeId), title, effect,
                diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_routes), ConsoleText.list(before), ConsoleText.list(after))),
                cls = ChangeClassifier.routes(before, after),
            ),
            apply = { it.setDeviceRoutes(d.pathId, after) },
            verify = { it.deviceRoutes(d.pathId).enabledRoutes.toSet() == after.toSet() },
            undo = if (undo) null else setRoutes(ctx, d, after, before, selfNodeId, undo = true),
            isUndo = undo,
        )
    }

    fun setAuthorized(ctx: Context, d: ApiDevice, authorized: Boolean, selfNodeId: String?, undo: Boolean = false): PlannedChange =
        PlannedChange(
            change = change(
                if (authorized) ChangeKind.DEVICE_AUTHORIZE else ChangeKind.DEVICE_DEAUTHORIZE,
                deviceTarget(d, selfNodeId),
                title = ctx.getString(if (authorized) R.string.admin2_change_authorize else R.string.admin2_change_deauthorize, d.shortName),
                effect = ctx.getString(if (authorized) R.string.admin2_change_authorize_effect else R.string.admin2_change_deauthorize_effect),
                diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_authorized), yesNo(ctx, d.authorized), yesNo(ctx, authorized))),
            ),
            apply = { it.setDeviceAuthorized(d.pathId, authorized) },
            verify = { it.getDevice(d.pathId).authorized == authorized },
            undo = if (undo) null else setAuthorized(ctx, d.copy(authorized = authorized), !authorized, selfNodeId, undo = true),
            isUndo = undo,
        )

    fun setKeyExpiryDisabled(ctx: Context, d: ApiDevice, disabled: Boolean, selfNodeId: String?, undo: Boolean = false): PlannedChange =
        PlannedChange(
            change = change(
                ChangeKind.DEVICE_KEY_EXPIRY, deviceTarget(d, selfNodeId),
                title = ctx.getString(if (disabled) R.string.admin2_change_expiry_off else R.string.admin2_change_expiry_on, d.shortName),
                effect = ctx.getString(if (disabled) R.string.admin2_change_expiry_off_effect else R.string.admin2_change_expiry_on_effect),
            ),
            apply = { it.setDeviceKeyExpiryDisabled(d.pathId, disabled) },
            verify = { it.getDevice(d.pathId).keyExpiryDisabled == disabled },
            undo = if (undo) null else setKeyExpiryDisabled(ctx, d.copy(keyExpiryDisabled = disabled), !disabled, selfNodeId, undo = true),
            isUndo = undo,
        )

    fun expireDevice(ctx: Context, d: ApiDevice, selfNodeId: String?): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.DEVICE_EXPIRE, deviceTarget(d, selfNodeId),
            title = ctx.getString(R.string.admin2_change_expire, d.shortName),
            effect = ctx.getString(R.string.admin2_change_expire_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_device), d.name.ifBlank { d.shortName }, null)),
        ),
        apply = { it.expireDevice(d.pathId) },
    )

    fun deleteDevice(ctx: Context, d: ApiDevice, selfNodeId: String?): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.DEVICE_DELETE, deviceTarget(d, selfNodeId),
            title = ctx.getString(R.string.admin2_change_delete_device, d.shortName),
            effect = ctx.getString(R.string.admin2_change_delete_device_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_device), listOfNotNull(d.name.ifBlank { null }, d.ipv4).joinToString(" · "), null)),
        ),
        apply = { it.deleteDevice(d.pathId) },
        verify = { b -> runCatching { b.getDevice(d.pathId) }.exceptionOrNull() is AdminApiException.NotFound },
    )

    // ------------------------------------------------------------------ keys

    fun createAuthKey(ctx: Context, request: AuthKeyRequest, onCreated: (ApiKey) -> Unit): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.KEY_CREATE,
            ChangeTarget(TargetType.KEY, "new", request.description.ifBlank { ctx.getString(R.string.admin2_key_type_auth) }),
            title = ctx.getString(R.string.admin2_change_key_create),
            effect = ctx.getString(R.string.admin2_change_key_create_effect),
        ),
        apply = { onCreated(it.createAuthKey(request)) },
    )

    fun keyTarget(k: ApiKey) = ChangeTarget(TargetType.KEY, k.id, k.description?.takeIf { it.isNotBlank() } ?: k.id)

    fun revokeKey(ctx: Context, k: ApiKey): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.KEY_REVOKE, keyTarget(k),
            title = ctx.getString(R.string.admin2_change_key_revoke, k.description?.takeIf { it.isNotBlank() } ?: k.id),
            effect = ctx.getString(R.string.admin2_change_key_revoke_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_key), "${ConsoleText.keyType(ctx, k.type)} ${k.id}", null)),
        ),
        apply = { it.deleteKey(k.id) },
        verify = { b ->
            val after = runCatching { b.getKey(k.id) }
            after.exceptionOrNull() is AdminApiException.NotFound || after.getOrNull()?.let { it.isRevoked || it.invalid == true } == true
        },
    )

    // ------------------------------------------------------------------ users

    fun userTarget(u: ApiUser) = ChangeTarget(TargetType.USER, u.id, u.loginName.ifBlank { u.name }, loginName = u.loginName)

    fun approveUser(ctx: Context, u: ApiUser): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.USER_APPROVE, userTarget(u),
            title = ctx.getString(R.string.admin2_change_user_approve, u.name),
            effect = ctx.getString(R.string.admin2_change_user_approve_effect),
        ),
        apply = { it.approveUser(u.id) },
        verify = { it.getUser(u.id).userStatus != UserStatus.NEEDS_APPROVAL },
    )

    fun setUserRole(ctx: Context, u: ApiUser, role: UserRole, undo: Boolean = false): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.USER_ROLE, userTarget(u),
            title = ctx.getString(R.string.admin2_change_user_role, u.name),
            effect = ctx.getString(R.string.admin2_change_user_role_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_role), ConsoleText.role(ctx, u.userRole, u.role), ConsoleText.role(ctx, role))),
        ),
        apply = { it.setUserRole(u.id, role) },
        verify = { it.getUser(u.id).role == role.wire },
        undo = if (undo || u.userRole == UserRole.UNKNOWN || u.userRole == UserRole.OWNER) null
        else setUserRole(ctx, u.copy(role = role.wire), u.userRole, undo = true),
        isUndo = undo,
    )

    fun suspendUser(ctx: Context, u: ApiUser, undo: Boolean = false): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.USER_SUSPEND, userTarget(u),
            title = ctx.getString(R.string.admin2_change_user_suspend, u.name),
            effect = ctx.getString(R.string.admin2_change_user_suspend_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_status), ConsoleText.status(ctx, u.userStatus, u.status), ConsoleText.status(ctx, UserStatus.SUSPENDED))),
        ),
        apply = { it.suspendUser(u.id) },
        verify = { it.getUser(u.id).userStatus == UserStatus.SUSPENDED },
        undo = if (undo) null else restoreUser(ctx, u.copy(status = UserStatus.SUSPENDED.wire), undo = true),
        isUndo = undo,
    )

    fun restoreUser(ctx: Context, u: ApiUser, undo: Boolean = false): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.USER_RESTORE, userTarget(u),
            title = ctx.getString(R.string.admin2_change_user_restore, u.name),
            effect = ctx.getString(R.string.admin2_change_user_restore_effect),
        ),
        apply = { it.restoreUser(u.id) },
        verify = { it.getUser(u.id).userStatus != UserStatus.SUSPENDED },
        undo = if (undo) null else suspendUser(ctx, u.copy(status = UserStatus.ACTIVE.wire), undo = true),
        isUndo = undo,
    )

    fun deleteUser(ctx: Context, u: ApiUser): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.USER_DELETE, userTarget(u),
            title = ctx.getString(R.string.admin2_change_user_delete, u.name),
            effect = ctx.getString(R.string.admin2_change_user_delete_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_status), ConsoleText.status(ctx, u.userStatus, u.status), null)),
        ),
        apply = { it.deleteUser(u.id) },
        verify = { b -> runCatching { b.getUser(u.id) }.exceptionOrNull() is AdminApiException.NotFound },
    )

    // ------------------------------------------------------------------ DNS

    fun tailnetTarget(tailnetLabel: String) = ChangeTarget(TargetType.TAILNET, "-", tailnetLabel)

    fun setMagicDns(ctx: Context, on: Boolean, tailnetLabel: String, undo: Boolean = false): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.DNS_MAGIC_DNS, tailnetTarget(tailnetLabel),
            title = ctx.getString(if (on) R.string.admin2_change_magicdns_on else R.string.admin2_change_magicdns_off),
            effect = ctx.getString(if (on) R.string.admin2_change_magicdns_on_effect else R.string.admin2_change_magicdns_off_effect),
        ),
        apply = { it.setMagicDns(on) },
        verify = { it.dnsConfiguration().magicDns == on },
        undo = if (undo) null else setMagicDns(ctx, !on, tailnetLabel, undo = true),
        isUndo = undo,
    )

    fun setNameservers(ctx: Context, before: List<String>, after: List<String>, magicDnsOn: Boolean, tailnetLabel: String, undo: Boolean = false): PlannedChange =
        PlannedChange(
            change = change(
                ChangeKind.DNS_NAMESERVERS, tailnetTarget(tailnetLabel),
                title = ctx.getString(R.string.admin2_change_nameservers),
                effect = ctx.getString(
                    if (after.isEmpty() && magicDnsOn) R.string.admin2_change_nameservers_none_effect else R.string.admin2_change_nameservers_effect
                ),
                diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_nameservers), ConsoleText.list(before), ConsoleText.list(after))),
                cls = ChangeClassifier.nameservers(before, after, magicDnsOn),
            ),
            apply = { it.setNameservers(after) },
            verify = { it.dnsConfiguration().nameserverAddresses == after },
            undo = if (undo) null else setNameservers(ctx, after, before, magicDnsOn && after.isNotEmpty(), tailnetLabel, undo = true),
            isUndo = undo,
        )

    fun setSplitDns(ctx: Context, domain: String, before: List<String>?, after: List<String>?, tailnetLabel: String, undo: Boolean = false): PlannedChange {
        val adding = after != null
        return PlannedChange(
            change = change(
                ChangeKind.DNS_SPLIT, tailnetTarget(tailnetLabel),
                title = ctx.getString(if (adding) R.string.admin2_change_split_add else R.string.admin2_change_split_remove, domain),
                effect = if (adding) ctx.getString(R.string.admin2_change_split_add_effect, domain, after!!.joinToString(", "))
                else ctx.getString(R.string.admin2_change_split_remove_effect, domain),
                diff = listOf(DiffLine(domain, before?.let { ConsoleText.list(it) }, after?.let { ConsoleText.list(it) })),
            ),
            apply = { it.setSplitDnsDomain(domain, after) },
            verify = { it.dnsConfiguration().splitDnsAddresses[domain].orEmpty() == after.orEmpty() },
            undo = if (undo) null else setSplitDns(ctx, domain, after, before, tailnetLabel, undo = true),
            isUndo = undo,
        )
    }

    fun setSearchPaths(ctx: Context, before: List<String>, after: List<String>, tailnetLabel: String, undo: Boolean = false): PlannedChange =
        PlannedChange(
            change = change(
                ChangeKind.DNS_SEARCH_PATHS, tailnetTarget(tailnetLabel),
                title = ctx.getString(R.string.admin2_change_search),
                effect = ctx.getString(R.string.admin2_change_search_effect),
                diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_search), ConsoleText.list(before), ConsoleText.list(after))),
                cls = ChangeClassifier.searchPaths(before, after),
            ),
            apply = { it.setSearchPaths(after) },
            verify = { it.dnsConfiguration().searchPaths == after },
            undo = if (undo) null else setSearchPaths(ctx, after, before, tailnetLabel, undo = true),
            isUndo = undo,
        )

    // ------------------------------------------------------------------ settings

    fun settingValue(s: TailnetSettings, key: TailnetSettingKey): Any? = when (key) {
        TailnetSettingKey.DEVICES_APPROVAL -> s.devicesApprovalOn
        TailnetSettingKey.DEVICES_AUTO_UPDATES -> s.devicesAutoUpdatesOn
        TailnetSettingKey.DEVICES_KEY_DURATION -> s.devicesKeyDurationDays
        TailnetSettingKey.USERS_APPROVAL -> s.usersApprovalOn
        TailnetSettingKey.USERS_EXTERNAL_ROLE -> s.usersRoleAllowedToJoinExternalTailnets
        TailnetSettingKey.NETWORK_FLOW_LOGGING -> s.networkFlowLoggingOn
        TailnetSettingKey.ROUTE_SELECTION -> s.routeSelection
        TailnetSettingKey.POSTURE_IDENTITY -> s.postureIdentityCollectionOn
        TailnetSettingKey.HTTPS -> s.httpsEnabled
        TailnetSettingKey.ACLS_EXTERNALLY_MANAGED -> s.aclsExternallyManagedOn
        TailnetSettingKey.ACLS_EXTERNAL_LINK -> s.aclsExternalLink
    }

    /**
     * One tailnet setting. [label] is the setting's name on screen; [show] turns a value into
     * its words for the diff. HIGH for what opens the tailnet (see [ChangeClassifier.setting]).
     */
    fun setSetting(
        ctx: Context,
        key: TailnetSettingKey,
        label: String,
        before: Any?,
        after: Any,
        show: (Any?) -> String,
        tailnetLabel: String,
        undo: Boolean = false,
    ): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.SETTING, tailnetTarget(tailnetLabel),
            title = ctx.getString(R.string.admin2_change_setting, label),
            effect = ctx.getString(R.string.admin2_change_setting_effect),
            diff = listOf(DiffLine(label, show(before), show(after))),
            cls = ChangeClassifier.setting(key, before, after),
            area = key.scopeArea,
        ),
        apply = { it.updateTailnetSetting(key, after) },
        verify = { settingValue(it.tailnetSettings(), key) == after },
        undo = if (undo || before == null) null else setSetting(ctx, key, label, after, before, show, tailnetLabel, undo = true),
        isUndo = undo,
    )

    // ------------------------------------------------------------------ webhooks

    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

    fun createWebhook(ctx: Context, url: String, provider: String, events: List<String>, tailnetLabel: String, onCreated: (ApiWebhook) -> Unit): PlannedChange =
        PlannedChange(
            change = change(
                ChangeKind.WEBHOOK_CREATE, tailnetTarget(tailnetLabel),
                title = ctx.getString(R.string.admin2_change_webhook_create),
                effect = ctx.getString(R.string.admin2_change_webhook_create_effect, url),
            ),
            apply = { onCreated(it.createWebhook(url, provider, events)) },
        )

    fun testWebhook(ctx: Context, w: ApiWebhook): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.WEBHOOK_TEST, ChangeTarget(TargetType.WEBHOOK, w.endpointId, hostOf(w.endpointUrl)),
            title = ctx.getString(R.string.admin2_change_webhook_test, hostOf(w.endpointUrl)),
            effect = w.endpointUrl,
        ),
        apply = { it.testWebhook(w.endpointId) },
    )

    fun deleteWebhook(ctx: Context, w: ApiWebhook): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.WEBHOOK_DELETE, ChangeTarget(TargetType.WEBHOOK, w.endpointId, hostOf(w.endpointUrl)),
            title = ctx.getString(R.string.admin2_change_webhook_delete, hostOf(w.endpointUrl)),
            effect = ctx.getString(R.string.admin2_change_webhook_delete_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin2_diff_url), w.endpointUrl, null)),
        ),
        apply = { it.deleteWebhook(w.endpointId) },
        verify = { b -> b.listWebhooks().items.none { it.endpointId == w.endpointId } },
    )

    // ------------------------------------------------------------------ services

    fun setServiceHost(ctx: Context, service: ApiService, deviceId: String, deviceName: String, approved: Boolean, undo: Boolean = false): PlannedChange =
        PlannedChange(
            change = change(
                ChangeKind.SERVICE_HOST_APPROVAL, ChangeTarget(TargetType.SERVICE, service.name, service.name),
                title = ctx.getString(if (approved) R.string.admin2_change_service_host_on else R.string.admin2_change_service_host_off, deviceName, service.name),
                effect = ctx.getString(R.string.admin2_change_service_host_effect),
            ),
            apply = { it.setServiceHostApproved(service.name, deviceId, approved) },
            verify = { b -> b.serviceHosts(service.name).items.firstOrNull { it.stableNodeID == deviceId }?.isApproved == approved },
            undo = if (undo) null else setServiceHost(ctx, service, deviceId, deviceName, !approved, undo = true),
            isUndo = undo,
        )
}
