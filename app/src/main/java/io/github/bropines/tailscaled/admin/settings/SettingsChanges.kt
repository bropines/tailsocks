package io.github.bropines.tailscaled.admin.settings

import android.content.Context
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.TailnetSettingKey
import io.github.bropines.tailscaled.admin.console.ConsoleChanges
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.safety.AdminChange
import io.github.bropines.tailscaled.admin.safety.ChangeClass
import io.github.bropines.tailscaled.admin.safety.ChangeClassifier
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.ChangeTarget
import io.github.bropines.tailscaled.admin.safety.DiffLine
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.TargetType
import java.net.URI

/** Which way a setting moves, as its effect sentence tells it. */
enum class SettingDirection { OPENS, CLOSES, NEUTRAL }

/** The settings' words: names, explanations, values, and what each change does. */
object SettingsText {

    fun title(ctx: Context, key: TailnetSettingKey): String = ctx.getString(
        when (key) {
            TailnetSettingKey.DEVICES_APPROVAL -> R.string.admin_settings_device_approval_title
            TailnetSettingKey.USERS_APPROVAL -> R.string.admin_settings_user_approval_title
            TailnetSettingKey.DEVICES_KEY_DURATION -> R.string.admin_settings_key_expiry_title
            TailnetSettingKey.USERS_EXTERNAL_ROLE -> R.string.admin_settings_external_tailnets_title
            TailnetSettingKey.DEVICES_AUTO_UPDATES -> R.string.admin_settings_auto_updates_title
            TailnetSettingKey.HTTPS -> R.string.admin2_settings_https_title
            TailnetSettingKey.ROUTE_SELECTION -> R.string.admin2_settings_route_selection_title
            TailnetSettingKey.NETWORK_FLOW_LOGGING -> R.string.admin_settings_flow_logging_title
            TailnetSettingKey.POSTURE_IDENTITY -> R.string.admin_settings_posture_title
            TailnetSettingKey.ACLS_EXTERNALLY_MANAGED -> R.string.admin_cfg_set_acls_external
            TailnetSettingKey.ACLS_EXTERNAL_LINK -> R.string.admin_cfg_set_acls_link
        }
    )

    fun help(ctx: Context, key: TailnetSettingKey): String = ctx.getString(
        when (key) {
            TailnetSettingKey.DEVICES_APPROVAL -> R.string.admin_cfg_set_devices_approval_help
            TailnetSettingKey.USERS_APPROVAL -> R.string.admin_cfg_set_users_approval_help
            TailnetSettingKey.DEVICES_KEY_DURATION -> R.string.admin_cfg_set_key_duration_help
            TailnetSettingKey.USERS_EXTERNAL_ROLE -> R.string.admin_cfg_set_external_help
            TailnetSettingKey.DEVICES_AUTO_UPDATES -> R.string.admin_cfg_set_auto_updates_help
            TailnetSettingKey.HTTPS -> R.string.admin_cfg_set_https_help
            TailnetSettingKey.ROUTE_SELECTION -> R.string.admin_cfg_set_route_selection_help
            TailnetSettingKey.NETWORK_FLOW_LOGGING -> R.string.admin_cfg_set_flow_logs_help
            TailnetSettingKey.POSTURE_IDENTITY -> R.string.admin_cfg_set_posture_help
            TailnetSettingKey.ACLS_EXTERNALLY_MANAGED -> R.string.admin_cfg_set_acls_external_help
            TailnetSettingKey.ACLS_EXTERNAL_LINK -> R.string.admin_cfg_set_acls_link_help
        }
    )

    fun role(ctx: Context, role: String?): String = when (role) {
        "none" -> ctx.getString(R.string.pickers_role_none)
        "admin" -> ctx.getString(R.string.pickers_role_admin)
        "member" -> ctx.getString(R.string.pickers_role_member)
        null, "" -> ctx.getString(R.string.admin2_settings_unknown)
        else -> role
    }

    fun days(ctx: Context, days: Int): String = ctx.resources.getQuantityString(R.plurals.pickers_days, days, days)

    /** A value in words, as the row and the diff show it. */
    fun show(ctx: Context, key: TailnetSettingKey, value: Any?): String = when (key) {
        TailnetSettingKey.DEVICES_KEY_DURATION -> (value as? Number)?.let { days(ctx, it.toInt()) } ?: ctx.getString(R.string.admin2_settings_unknown)
        TailnetSettingKey.USERS_EXTERNAL_ROLE -> role(ctx, value as? String)
        TailnetSettingKey.ROUTE_SELECTION -> ConsoleText.routeSelection(ctx, value as? String)
        TailnetSettingKey.ACLS_EXTERNAL_LINK -> (value as? String)?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.admin2_settings_unknown)
        else -> ConsoleText.onOff(ctx, value as? Boolean)
    }

    fun direction(key: TailnetSettingKey, before: Any?, after: Any?): SettingDirection = when (key) {
        TailnetSettingKey.DEVICES_APPROVAL, TailnetSettingKey.USERS_APPROVAL ->
            if (after == false) SettingDirection.OPENS else SettingDirection.CLOSES
        TailnetSettingKey.DEVICES_KEY_DURATION ->
            if (((after as? Number)?.toInt() ?: 0) > ((before as? Number)?.toInt() ?: Int.MAX_VALUE)) SettingDirection.OPENS else SettingDirection.CLOSES
        TailnetSettingKey.USERS_EXTERNAL_ROLE ->
            if (ChangeClassifier.setting(key, before, after) == ChangeClass.HIGH) SettingDirection.OPENS else SettingDirection.CLOSES
        TailnetSettingKey.NETWORK_FLOW_LOGGING, TailnetSettingKey.POSTURE_IDENTITY, TailnetSettingKey.HTTPS,
        TailnetSettingKey.ACLS_EXTERNALLY_MANAGED, TailnetSettingKey.DEVICES_AUTO_UPDATES ->
            if (after == true) SettingDirection.OPENS else SettingDirection.CLOSES
        TailnetSettingKey.ROUTE_SELECTION, TailnetSettingKey.ACLS_EXTERNAL_LINK -> SettingDirection.NEUTRAL
    }

    /** What the change does, in a sentence: the consequence, not the field's name again. */
    fun effect(ctx: Context, key: TailnetSettingKey, before: Any?, after: Any?): String {
        val opens = direction(key, before, after) == SettingDirection.OPENS
        return ctx.getString(
            when (key) {
                TailnetSettingKey.DEVICES_APPROVAL -> if (opens) R.string.admin_cfg_set_devices_approval_off else R.string.admin_cfg_set_devices_approval_on
                TailnetSettingKey.USERS_APPROVAL -> if (opens) R.string.admin_cfg_set_users_approval_off else R.string.admin_cfg_set_users_approval_on
                TailnetSettingKey.DEVICES_KEY_DURATION -> if (opens) R.string.admin_cfg_set_key_duration_up else R.string.admin_cfg_set_key_duration_down
                TailnetSettingKey.USERS_EXTERNAL_ROLE -> if (opens) R.string.admin_cfg_set_external_wider else R.string.admin_cfg_set_external_narrower
                TailnetSettingKey.DEVICES_AUTO_UPDATES -> if (opens) R.string.admin_cfg_set_auto_updates_on else R.string.admin_cfg_set_auto_updates_off
                TailnetSettingKey.HTTPS -> if (opens) R.string.admin_cfg_set_https_on else R.string.admin_cfg_set_https_off
                TailnetSettingKey.ROUTE_SELECTION -> R.string.admin_cfg_set_route_selection_effect
                TailnetSettingKey.NETWORK_FLOW_LOGGING -> if (opens) R.string.admin_cfg_set_flow_logs_on else R.string.admin_cfg_set_flow_logs_off
                TailnetSettingKey.POSTURE_IDENTITY -> if (opens) R.string.admin_cfg_set_posture_on else R.string.admin_cfg_set_posture_off
                TailnetSettingKey.ACLS_EXTERNALLY_MANAGED -> if (opens) R.string.admin_cfg_set_acls_external_on else R.string.admin_cfg_set_acls_external_off
                TailnetSettingKey.ACLS_EXTERNAL_LINK -> R.string.admin_cfg_set_acls_link_effect
            }
        )
    }
}

object SettingsChanges {

    /** The days a key may last, as the picker offers them; the API takes 1–180. */
    val keyDurations = listOf(1, 7, 14, 30, 60, 90, 180)

    /** An external policy link: https with a host, or empty to clear it. */
    fun linkOk(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return true
        val uri = runCatching { URI(s) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && s.none { it.isWhitespace() }
    }

    /**
     * One setting, saved on its own: HIGH for what opens the tailnet or starts collecting
     * (see [ChangeClassifier.setting]), MEDIUM the way back. The undo sets the old value again.
     */
    fun set(ctx: Context, key: TailnetSettingKey, before: Any?, after: Any, tailnetLabel: String, undo: Boolean = false): PlannedChange {
        val label = SettingsText.title(ctx, key)
        return PlannedChange(
            change = AdminChange(
                kind = ChangeKind.SETTING,
                changeClass = ChangeClassifier.setting(key, before, after),
                target = ChangeTarget(TargetType.TAILNET, "-", tailnetLabel),
                title = ctx.getString(R.string.admin2_change_setting, label),
                effect = SettingsText.effect(ctx, key, before, after),
                diff = listOf(DiffLine(label, SettingsText.show(ctx, key, before), SettingsText.show(ctx, key, after))),
                area = key.scopeArea,
            ),
            apply = { it.updateTailnetSetting(key, after) },
            verify = { ConsoleChanges.settingValue(it.tailnetSettings(), key) == after },
            undo = if (undo || before == null) null else set(ctx, key, after, before, tailnetLabel, undo = true),
            isUndo = undo,
        )
    }
}
