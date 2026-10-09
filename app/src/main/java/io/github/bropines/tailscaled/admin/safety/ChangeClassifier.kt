package io.github.bropines.tailscaled.admin.safety

import io.github.bropines.tailscaled.admin.api.TailnetSettingKey

/**
 * The safety table, as code. Fixed kinds have a fixed class; a few depend on the values:
 * a nameserver list emptied under MagicDNS, search paths removed rather than added, and the
 * settings that open the tailnet up.
 */
object ChangeClassifier {

    fun classify(kind: ChangeKind): ChangeClass = when (kind) {
        ChangeKind.DEVICE_RENAME, ChangeKind.WEBHOOK_TEST -> ChangeClass.LOW

        ChangeKind.DEVICE_TAGS, ChangeKind.DEVICE_ROUTES, ChangeKind.DEVICE_AUTHORIZE, ChangeKind.DEVICE_KEY_EXPIRY,
        ChangeKind.KEY_CREATE, ChangeKind.USER_APPROVE, ChangeKind.USER_RESTORE,
        ChangeKind.DNS_MAGIC_DNS, ChangeKind.DNS_SPLIT, ChangeKind.DNS_NAMESERVERS, ChangeKind.DNS_SEARCH_PATHS,
        ChangeKind.SETTING, ChangeKind.WEBHOOK_CREATE, ChangeKind.WEBHOOK_UPDATE, ChangeKind.WEBHOOK_ROTATE,
        ChangeKind.SERVICE_PUBLISH, ChangeKind.SERVICE_HOST_APPROVAL -> ChangeClass.MEDIUM

        ChangeKind.DEVICE_DEAUTHORIZE, ChangeKind.DEVICE_EXPIRE, ChangeKind.DEVICE_DELETE, ChangeKind.DEVICE_IPV4,
        ChangeKind.KEY_REVOKE, ChangeKind.USER_ROLE, ChangeKind.USER_SUSPEND, ChangeKind.USER_DELETE,
        ChangeKind.WEBHOOK_DELETE, ChangeKind.SERVICE_DELETE -> ChangeClass.HIGH

        ChangeKind.POLICY_FILE -> ChangeClass.POLICY
    }

    /** Removing every nameserver turns MagicDNS off with it: HIGH while it is on. */
    fun nameservers(before: List<String>, after: List<String>, magicDnsOn: Boolean): ChangeClass =
        if (magicDnsOn && before.isNotEmpty() && after.isEmpty()) ChangeClass.HIGH else ChangeClass.MEDIUM

    /** Adding a search path is LOW; taking one away changes how existing names resolve. */
    fun searchPaths(before: List<String>, after: List<String>): ChangeClass =
        if (before.all { it in after }) ChangeClass.LOW else ChangeClass.MEDIUM

    /**
     * HIGH for what opens the tailnet: device or user approval switched off, a longer key
     * expiry, more roles allowed to join other tailnets, the policy handed to an external
     * manager. Everything else MEDIUM.
     */
    fun setting(key: TailnetSettingKey, before: Any?, after: Any?): ChangeClass = when (key) {
        TailnetSettingKey.DEVICES_APPROVAL, TailnetSettingKey.USERS_APPROVAL ->
            if (before == true && after == false) ChangeClass.HIGH else ChangeClass.MEDIUM
        TailnetSettingKey.DEVICES_KEY_DURATION -> {
            val was = (before as? Number)?.toInt() ?: Int.MAX_VALUE
            val now = (after as? Number)?.toInt() ?: 0
            if (now > was) ChangeClass.HIGH else ChangeClass.MEDIUM
        }
        TailnetSettingKey.USERS_EXTERNAL_ROLE ->
            if (externalRank(after) > externalRank(before)) ChangeClass.HIGH else ChangeClass.MEDIUM
        TailnetSettingKey.ACLS_EXTERNALLY_MANAGED ->
            if (before != after) ChangeClass.HIGH else ChangeClass.MEDIUM
        else -> ChangeClass.MEDIUM
    }

    /** none < admin < member: how many people may join other tailnets. */
    private fun externalRank(value: Any?): Int = when (value) {
        "none" -> 0
        "admin" -> 1
        "member" -> 2
        else -> 1
    }
}
