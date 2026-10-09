package io.github.bropines.tailscaled.admin.safety

import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.AdminBackend

/**
 * How much a change can hurt, which decides the checks it passes before it applies:
 *  - LOW: the confirm in the sheet that asked for it (rename a device, add a search path, test a webhook).
 *  - MEDIUM: a dialog naming the target and the effect, then an unlock.
 *  - HIGH: the before → after diff, the target's name typed back, an unlock, then a re-read
 *    that verifies the result; undo where the API allows one.
 *  - POLICY: the policy file's own pipeline (validate, preview, lint, If-Match), on top of HIGH.
 */
enum class ChangeClass { LOW, MEDIUM, HIGH, POLICY }

enum class ChangeKind(val area: AdminArea) {
    DEVICE_RENAME(AdminArea.DEVICES),
    DEVICE_TAGS(AdminArea.DEVICES),
    DEVICE_ROUTES(AdminArea.ROUTES),
    DEVICE_AUTHORIZE(AdminArea.DEVICES),
    DEVICE_DEAUTHORIZE(AdminArea.DEVICES),
    DEVICE_KEY_EXPIRY(AdminArea.DEVICES),
    DEVICE_EXPIRE(AdminArea.DEVICES),
    DEVICE_DELETE(AdminArea.DEVICES),
    DEVICE_IPV4(AdminArea.DEVICES),
    KEY_CREATE(AdminArea.AUTH_KEYS),
    KEY_REVOKE(AdminArea.AUTH_KEYS),
    USER_APPROVE(AdminArea.USERS),
    USER_ROLE(AdminArea.USERS),
    USER_SUSPEND(AdminArea.USERS),
    USER_RESTORE(AdminArea.USERS),
    USER_DELETE(AdminArea.USERS),
    DNS_MAGIC_DNS(AdminArea.DNS),
    DNS_NAMESERVERS(AdminArea.DNS),
    DNS_SEARCH_PATHS(AdminArea.DNS),
    DNS_SPLIT(AdminArea.DNS),
    SETTING(AdminArea.SETTINGS),
    WEBHOOK_CREATE(AdminArea.WEBHOOKS),
    WEBHOOK_UPDATE(AdminArea.WEBHOOKS),
    WEBHOOK_TEST(AdminArea.WEBHOOKS),
    WEBHOOK_ROTATE(AdminArea.WEBHOOKS),
    WEBHOOK_DELETE(AdminArea.WEBHOOKS),
    SERVICE_PUBLISH(AdminArea.SERVICES),
    SERVICE_HOST_APPROVAL(AdminArea.SERVICES),
    SERVICE_DELETE(AdminArea.SERVICES),
    POLICY_FILE(AdminArea.POLICY),
}

enum class TargetType { DEVICE, KEY, USER, TAILNET, WEBHOOK, SERVICE }

/**
 * What a change acts on. [name] is what the person types back for a HIGH change, so it is the
 * name they see on screen. [loginName] (users) and [shared] (devices shared in from another
 * tailnet) feed the guards.
 */
data class ChangeTarget(
    val type: TargetType,
    val id: String,
    val name: String,
    val loginName: String? = null,
    val shared: Boolean = false,
    /** The device this phone runs as: allowed, with a warning. */
    val isThisDevice: Boolean = false,
)

/** One row of a before → after preview; null on one side for something added or removed. */
data class DiffLine(val label: String, val before: String?, val after: String?)

/**
 * A described change, ready for the gates: what it is, what it acts on, what it does (one
 * sentence, already in the user's language) and, for HIGH changes, the before → after lines.
 */
data class AdminChange(
    val kind: ChangeKind,
    val changeClass: ChangeClass,
    val target: ChangeTarget,
    val title: String,
    val effect: String,
    val diff: List<DiffLine> = emptyList(),
    /** Extra warnings shown above the confirm: "this is the phone you are using", and so on. */
    val warnings: List<String> = emptyList(),
    /** The area whose write access the change needs; the kind's own unless a setting says otherwise. */
    val area: AdminArea = kind.area,
) {
    val needsUnlock: Boolean get() = changeClass != ChangeClass.LOW
    val needsTypedConfirmation: Boolean get() = changeClass == ChangeClass.HIGH || changeClass == ChangeClass.POLICY
}

/**
 * A change with the calls that make it: [apply] does it, [verify] re-reads and says whether the
 * server now holds what was asked, [undo] is the change that puts the old state back — itself
 * gated like any other — where the API allows one.
 */
class PlannedChange(
    val change: AdminChange,
    val apply: suspend (AdminBackend) -> Unit,
    val verify: (suspend (AdminBackend) -> Boolean)? = null,
    val undo: PlannedChange? = null,
)
