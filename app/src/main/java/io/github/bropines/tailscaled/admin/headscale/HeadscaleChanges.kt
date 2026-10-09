package io.github.bropines.tailscaled.admin.headscale

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminBackend
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.headscale.HeadscaleAdmin
import io.github.bropines.tailscaled.admin.api.headscale.HsApiKey
import io.github.bropines.tailscaled.admin.api.headscale.RegistrationLink
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClassifier
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.ChangeTarget
import io.github.bropines.tailscaled.admin.safety.DiffLine
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.TargetType

/**
 * A Headscale server's own changes as planned changes: registering a waiting device (MEDIUM),
 * refusing it (MEDIUM), adding a user (MEDIUM), renaming one (HIGH — the policy names users,
 * and every `name@` in it stops matching), a new API key (HIGH — all access) and expiring one
 * (HIGH; never the console's own, which the runner refuses).
 */
object HeadscaleChanges {

    private fun change(kind: ChangeKind, target: ChangeTarget, title: String, effect: String, diff: List<DiffLine> = emptyList(), warnings: List<String> = emptyList()) =
        AdminChange(kind, ChangeClassifier.classify(kind), target, title, effect, diff, warnings)

    private fun AdminBackend.hs(): HeadscaleAdmin = this as? HeadscaleAdmin
        ?: throw AdminApiException.Unsupported(BackendFeature.HEADSCALE_ADMIN)

    /** The waiting device's link, shortened for a title: the id's last characters. */
    fun shortId(link: RegistrationLink): String = "…" + link.authId.takeLast(8)

    fun register(ctx: Context, link: RegistrationLink, user: ApiUser, serverHost: String?, onRegistered: (ApiDevice) -> Unit): PlannedChange {
        var registered: ApiDevice? = null
        val warnings = buildList {
            if (serverHost != null && link.host != null && !link.host.equals(serverHost, ignoreCase = true)) {
                add(ctx.getString(R.string.admin_hs_register_other_server, link.host, serverHost))
            }
        }
        return PlannedChange(
            change = change(
                ChangeKind.NODE_REGISTER,
                ChangeTarget(TargetType.DEVICE, link.authId, shortId(link)),
                title = ctx.getString(R.string.admin_hs_change_register, user.loginName),
                effect = ctx.getString(R.string.admin_hs_change_register_effect, user.loginName),
                diff = listOf(DiffLine(ctx.getString(R.string.admin_hs_diff_owner), null, user.loginName)),
                warnings = warnings,
            ),
            apply = { b -> b.hs().registerNode(link.authId, user.loginName).also { registered = it; onRegistered(it) } },
            verify = { b -> registered?.let { d -> b.listDevices().items.any { it.pathId == d.pathId } } == true },
        )
    }

    fun reject(ctx: Context, link: RegistrationLink): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.NODE_REGISTRATION_REJECT,
            ChangeTarget(TargetType.DEVICE, link.authId, shortId(link)),
            title = ctx.getString(R.string.admin_hs_change_reject),
            effect = ctx.getString(R.string.admin_hs_change_reject_effect),
        ),
        apply = { it.hs().rejectRegistration(link.authId) },
    )

    fun createUser(ctx: Context, name: String, displayName: String?, email: String?): PlannedChange {
        val n = name.trim()
        return PlannedChange(
            change = change(
                ChangeKind.USER_CREATE,
                ChangeTarget(TargetType.USER, "new", n),
                title = ctx.getString(R.string.admin_hs_change_user_create, n),
                effect = ctx.getString(R.string.admin_hs_change_user_create_effect),
                diff = listOfNotNull(
                    DiffLine(ctx.getString(R.string.admin_hs_diff_user), null, n),
                    displayName?.takeIf { it.isNotBlank() }?.let { DiffLine(ctx.getString(R.string.admin_hs_diff_display_name), null, it.trim()) },
                    email?.takeIf { it.isNotBlank() }?.let { DiffLine(ctx.getString(R.string.admin_hs_diff_email), null, it.trim()) },
                ),
            ),
            apply = { it.hs().createUser(n, displayName?.trim(), email?.trim()) },
            verify = { b -> b.listUsers().items.any { it.loginName == n } },
        )
    }

    fun renameUser(ctx: Context, user: ApiUser, newName: String, undo: Boolean = false): PlannedChange {
        val n = newName.trim()
        return PlannedChange(
            change = change(
                ChangeKind.USER_RENAME,
                ChangeTarget(TargetType.USER, user.id, user.loginName, loginName = user.loginName),
                title = ctx.getString(R.string.admin_hs_change_user_rename, user.loginName),
                effect = ctx.getString(R.string.admin_hs_change_user_rename_effect, user.loginName, n),
                diff = listOf(DiffLine(ctx.getString(R.string.admin_hs_diff_user), user.loginName, n)),
            ),
            apply = { it.hs().renameUser(user.id, n) },
            verify = { b -> b.listUsers().items.firstOrNull { it.id == user.id }?.loginName == n },
            undo = if (undo) null else renameUser(ctx, user.copy(loginName = n), user.loginName, undo = true),
            isUndo = undo,
        )
    }

    /**
     * A new API key valid for [days]. Typed confirmation names the server: that is where the key
     * reaches, all of it. [onCreated] gets the secret, which the server shows only this once.
     */
    fun createApiKey(ctx: Context, serverLabel: String, days: Int, now: Long, onCreated: (String) -> Unit): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.API_KEY_CREATE,
            ChangeTarget(TargetType.KEY, "new", serverLabel),
            title = ctx.getString(R.string.admin_hs_change_apikey_create),
            effect = ctx.resources.getQuantityString(R.plurals.admin_hs_change_apikey_create_effect, days, days),
        ),
        apply = { it.hs().createApiKey(now + days * DAY_MS).also(onCreated) },
    )

    fun expireApiKey(ctx: Context, key: HsApiKey): PlannedChange = PlannedChange(
        change = change(
            ChangeKind.API_KEY_EXPIRE,
            // The id is the listed prefix: what the runner compares with the console's own key.
            ChangeTarget(TargetType.KEY, key.prefix, key.bareprefix),
            title = ctx.getString(R.string.admin_hs_change_apikey_expire, key.bareprefix),
            effect = ctx.getString(R.string.admin_hs_change_apikey_expire_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin_hs_diff_api_key), key.prefix, null)),
        ),
        apply = { it.hs().expireApiKey(key.prefix) },
        verify = { b -> b.hs().listApiKeys().items.firstOrNull { it.prefix == key.prefix }?.isExpired(System.currentTimeMillis()) != false },
    )

    private const val DAY_MS = 24L * 3600 * 1000
}
