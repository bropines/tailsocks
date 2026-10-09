package io.github.bropines.tailscaled.admin.safety

import io.github.bropines.tailscaled.admin.api.TailnetSettingKey
import io.github.bropines.tailscaled.admin.api.UserRole

/**
 * The safety table, as code. Fixed kinds have a fixed class; a few depend on the values:
 * a nameserver list emptied under MagicDNS, search paths removed rather than added, and the
 * settings that open the tailnet up.
 */
object ChangeClassifier {

    fun classify(kind: ChangeKind): ChangeClass = when (kind) {
        ChangeKind.DEVICE_RENAME, ChangeKind.WEBHOOK_TEST -> ChangeClass.LOW

        ChangeKind.DEVICE_TAGS, ChangeKind.DEVICE_ROUTES, ChangeKind.DEVICE_AUTHORIZE, ChangeKind.DEVICE_KEY_EXPIRY,
        ChangeKind.KEY_CREATE, ChangeKind.USER_APPROVE,
        ChangeKind.DNS_MAGIC_DNS, ChangeKind.DNS_SPLIT, ChangeKind.DNS_NAMESERVERS, ChangeKind.DNS_SEARCH_PATHS,
        ChangeKind.SETTING, ChangeKind.WEBHOOK_CREATE, ChangeKind.WEBHOOK_UPDATE,
        ChangeKind.SERVICE_PUBLISH, ChangeKind.SERVICE_HOST_APPROVAL, ChangeKind.DNS_OVERRIDE_LOCAL -> ChangeClass.MEDIUM

        ChangeKind.DEVICE_DEAUTHORIZE, ChangeKind.DEVICE_EXPIRE, ChangeKind.DEVICE_DELETE, ChangeKind.DEVICE_IPV4,
        ChangeKind.KEY_REVOKE, ChangeKind.USER_ROLE, ChangeKind.USER_SUSPEND, ChangeKind.USER_RESTORE, ChangeKind.USER_DELETE,
        ChangeKind.WEBHOOK_DELETE, ChangeKind.SERVICE_DELETE,
        // The endpoint rejects every event until it has the new secret.
        ChangeKind.WEBHOOK_ROTATE -> ChangeClass.HIGH

        ChangeKind.POLICY_FILE -> ChangeClass.POLICY

        ChangeKind.DEVICE_TAGS_BULK -> ChangeClass.HIGH
        ChangeKind.USER_INVITE_RESEND -> ChangeClass.LOW
        ChangeKind.OAUTH_CLIENT_CREATE, ChangeKind.USER_INVITE_CREATE, ChangeKind.DEVICE_INVITE_CREATE -> ChangeClass.MEDIUM
        ChangeKind.USER_INVITE_DELETE, ChangeKind.DEVICE_INVITE_DELETE -> ChangeClass.HIGH
        // The old name stops resolving for every client of the service.
        ChangeKind.SERVICE_RENAME -> ChangeClass.HIGH
    }

    /**
     * A new OAuth client is HIGH when a write scope lets it mint more credentials, rewrite the
     * policy or change people (all, oauth_keys, federated_keys, api_access_tokens, policy_file,
     * users); MEDIUM when it reads, or writes narrower areas.
     */
    fun oauthClient(scopes: List<String>): ChangeClass =
        if (scopes.any { it.trim() in POWERFUL_WRITE_SCOPES }) ChangeClass.HIGH else ChangeClass.MEDIUM

    private val POWERFUL_WRITE_SCOPES = setOf("all", "oauth_keys", "federated_keys", "api_access_tokens", "policy_file", "users")

    /** An invite to any role but member hands whoever accepts it admin-console rights: HIGH. */
    fun userInvite(role: UserRole): ChangeClass = if (role == UserRole.MEMBER) ChangeClass.MEDIUM else ChangeClass.HIGH

    /** A multi-use share link can be accepted up to a thousand times, by anyone holding it: HIGH. */
    fun deviceInvite(multiUse: Boolean): ChangeClass = if (multiUse) ChangeClass.HIGH else ChangeClass.MEDIUM

    /** Approving routes is MEDIUM; taking an approved exit node away cuts off everyone using it: HIGH. */
    fun routes(before: List<String>, after: List<String>): ChangeClass {
        val exit = setOf("0.0.0.0/0", "::/0")
        return if (before.any { it in exit } && after.none { it in exit }) ChangeClass.HIGH else ChangeClass.MEDIUM
    }

    /**
     * Removing every nameserver turns MagicDNS off with it: HIGH while it is on — and while
     * Override local DNS is, which leaves devices no resolver of their own to fall back on.
     */
    fun nameservers(before: List<String>, after: List<String>, magicDnsOn: Boolean, overrideLocalDns: Boolean = false): ChangeClass =
        if ((magicDnsOn || overrideLocalDns) && before.isNotEmpty() && after.isEmpty()) ChangeClass.HIGH else ChangeClass.MEDIUM

    /** Turning MagicDNS off stops every tailnet name from resolving: HIGH; on, MEDIUM. */
    fun magicDns(on: Boolean): ChangeClass = if (on) ChangeClass.MEDIUM else ChangeClass.HIGH

    /** Adding a search path is LOW; taking one away changes how existing names resolve. */
    fun searchPaths(before: List<String>, after: List<String>): ChangeClass =
        if (before.all { it in after }) ChangeClass.LOW else ChangeClass.MEDIUM

    /**
     * HIGH for what opens the tailnet: device or user approval switched off, a longer key
     * expiry, more roles allowed to join other tailnets, the policy handed to an external
     * manager — and for what starts collecting or publishing: flow logs, posture identity,
     * HTTPS certificates (whose names go to public Certificate Transparency logs). The way
     * back from each is MEDIUM, as is everything else.
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
        TailnetSettingKey.NETWORK_FLOW_LOGGING, TailnetSettingKey.POSTURE_IDENTITY, TailnetSettingKey.HTTPS ->
            if (before != true && after == true) ChangeClass.HIGH else ChangeClass.MEDIUM
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
