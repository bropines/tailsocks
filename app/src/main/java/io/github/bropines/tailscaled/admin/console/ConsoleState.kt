package io.github.bropines.tailscaled.admin.console

import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiAuditLogEntry
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiDeviceInvite
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.ApiService
import io.github.bropines.tailscaled.admin.api.ApiServiceHost
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.ApiUserInvite
import io.github.bropines.tailscaled.admin.api.ApiWebhook
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.BackendKind
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.DecodeIssue
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import io.github.bropines.tailscaled.admin.api.DnsConfiguration
import io.github.bropines.tailscaled.admin.api.TailnetSettings
import io.github.bropines.tailscaled.admin.api.TailscaleBackend
import io.github.bropines.tailscaled.admin.policy.PolicyState
import io.github.bropines.tailscaled.admin.logs.AuditLogQuery
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.profile.AdminProxySettings
import io.github.bropines.tailscaled.admin.profile.AuthType
import io.github.bropines.tailscaled.admin.safety.AuditRecord
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.SafetyStep
import io.github.bropines.tailscaled.admin.secure.LockState

/** Data on its way from the API: the last value, whether a load runs, how the last one ended. */
data class Loadable<T>(
    val value: T? = null,
    val loading: Boolean = false,
    val error: Throwable? = null,
    /** Items of the last list that could not be read. */
    val issues: List<DecodeIssue> = emptyList(),
    val loadedAt: Long = 0L,
) {
    fun fresh(now: Long, maxAgeMs: Long): Boolean = value != null && error == null && now - loadedAt < maxAgeMs
}

enum class ConsolePhase {
    LOADING,
    /** No profile yet: the editor, with nowhere to go back to but out. */
    SETUP,
    EDIT_PROFILE,
    /** Waiting for the unlock in front of the console. */
    LOCKED,
    READY,
}

/**
 * The console's tabs, each with the backend feature it needs: a backend without it (Headscale
 * has no webhooks or audit log) does not show the tab. WEB links to Tailscale's own console.
 */
enum class ConsoleTab(val feature: BackendFeature?) {
    /** The first screen: what waits for a person (admin/attention/). */
    ATTENTION(BackendFeature.DEVICES),
    DEVICES(BackendFeature.DEVICES),
    DNS(BackendFeature.DNS),
    POLICY(BackendFeature.POLICY),
    USERS(BackendFeature.USERS),
    KEYS(BackendFeature.KEYS),
    SERVICES(BackendFeature.SERVICES),
    WEBHOOKS(BackendFeature.WEBHOOKS),
    LOGS(BackendFeature.AUDIT_LOGS),
    WEB(null),
    SETTINGS(BackendFeature.SETTINGS),
}

/** Why writes are off for the whole profile, whatever the credential allows. */
enum class WriteBlock { NO_SCREEN_LOCK, READ_ONLY_PROFILE }

enum class CredentialProblem { MISSING, UNREADABLE }

/**
 * This phone as a node, from the daemon: used to mark "this phone" in the device list and to
 * keep the user it is signed in as from being suspended from here — but only when the device
 * list shows the phone is in the profile's tailnet at all.
 */
data class SelfIdentity(val nodeId: String? = null, val loginName: String? = null)

/** The profile editor's fields. A stored secret is never read back into it. */
data class ProfileDraft(
    /** Null for a new profile. */
    val id: String? = null,
    val name: String = "",
    val backend: BackendKind = BackendKind.TAILSCALE,
    val baseUrl: String = TailscaleBackend.DEFAULT_BASE_URL,
    val tailnet: String = "-",
    val authType: AuthType = AuthType.API_TOKEN,
    val oauthClientId: String = "",
    /** What was typed; empty with [hasStoredSecret] keeps the stored one. */
    val secret: String = "",
    val hasStoredSecret: Boolean = false,
    val readOnly: Boolean = false,
    /** The profile was read-only when editing started: allowing writes again needs an unlock. */
    val wasReadOnly: Boolean = false,
    val proxy: AdminProxySettings = AdminProxySettings(),
    val proxyPassword: String = "",
    val hasStoredProxyPassword: Boolean = false,
    val checking: Boolean = false,
    val error: String? = null,
    /** The check failed for a reason other than a refused credential: saving anyway is offered. */
    val offerUnchecked: Boolean = false,
    /** Saving waits for the write unlock (read-only switched off). */
    val awaitingUnlock: Boolean = false,
)

/** A one-line message for the snackbar; [undo] when the change can be put back. */
data class ConsoleMessage(val text: String, val id: Long, val undo: PlannedChange? = null)

/**
 * A secret the server returned once, shown until the person says it is saved. [qr] adds a QR
 * code of it; [once] false for what can be read again later (an invite link).
 */
data class RevealedSecret(val title: String, val text: String, val secret: String, val qr: Boolean = false, val once: Boolean = true)

data class ConsoleState(
    val phase: ConsolePhase = ConsolePhase.LOADING,
    val profiles: List<AdminProfile> = emptyList(),
    val active: AdminProfile? = null,
    val draft: ProfileDraft? = null,
    val credentialProblem: CredentialProblem? = null,
    val lockState: LockState = LockState.CRYPTO_PER_USE,
    val viewUnlockUnavailable: Boolean = false,
    val caps: Capabilities? = null,
    val self: SelfIdentity = SelfIdentity(),
    val devices: Loadable<List<ApiDevice>> = Loadable(),
    val policyTags: List<String> = emptyList(),
    val routes: Map<String, Loadable<DeviceRoutes>> = emptyMap(),
    val dns: Loadable<DnsConfiguration> = Loadable(),
    val users: Loadable<List<ApiUser>> = Loadable(),
    val keys: Loadable<List<ApiKey>> = Loadable(),
    val services: Loadable<List<ApiService>> = Loadable(),
    val serviceHosts: Map<String, Loadable<List<ApiServiceHost>>> = emptyMap(),
    val webhooks: Loadable<List<ApiWebhook>> = Loadable(),
    val tailnetLog: Loadable<List<ApiAuditLogEntry>> = Loadable(),
    val tailnetLogDays: Int = 7,
    /** The audit log's window and server-side filters. */
    val tailnetLogQuery: AuditLogQuery = AuditLogQuery(),
    val localLog: List<AuditRecord> = emptyList(),
    val settings: Loadable<TailnetSettings> = Loadable(),
    val safety: SafetyStep = SafetyStep.Idle,
    val revealed: RevealedSecret? = null,
    val message: ConsoleMessage? = null,
    /** The policy file, its editor and its previews; see PolicyConsole. */
    val policy: PolicyState = PolicyState(),
    /** The policy's tagOwners (tag → owners), for the new-key tag picker. */
    val tagOwners: Loadable<Map<String, List<String>>> = Loadable(),
    val userInvites: Loadable<List<ApiUserInvite>> = Loadable(),
    /** Share invites by device path id. */
    val deviceInvites: Map<String, Loadable<List<ApiDeviceInvite>>> = emptyMap(),
) {
    val writeBlock: WriteBlock?
        get() = when {
            active?.readOnly == true -> WriteBlock.READ_ONLY_PROFILE
            lockState == LockState.NO_SCREEN_LOCK -> WriteBlock.NO_SCREEN_LOCK
            else -> null
        }

    /** Whether to offer a change in [area] at all: the profile allows writes and the credential may. */
    fun canWrite(area: AdminArea): Boolean = writeBlock == null && caps?.canWrite(area) != false

    fun canRead(area: AdminArea): Boolean = caps?.canRead(area) != false

    /** The tabs this backend has: all of them until the capabilities say otherwise. */
    val tabs: List<ConsoleTab>
        get() = ConsoleTab.entries.filter { tab ->
            val c = caps ?: return@filter true
            if (tab == ConsoleTab.WEB) c.backend == BackendKind.TAILSCALE else tab.feature == null || c.has(tab.feature)
        }

    /** Whether this phone is a node of the profile's tailnet, as the device list says. */
    val phoneInTailnet: Boolean
        get() = self.nodeId != null && devices.value?.any { it.nodeId == self.nodeId } == true
}
