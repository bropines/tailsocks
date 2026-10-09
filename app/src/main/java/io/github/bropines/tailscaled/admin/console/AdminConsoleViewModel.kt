package io.github.bropines.tailscaled.admin.console

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.Access
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.DecodeIssue
import io.github.bropines.tailscaled.admin.api.AdminBackend
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiKey
import io.github.bropines.tailscaled.admin.api.ApiService
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.ApiWebhook
import io.github.bropines.tailscaled.admin.api.AuthKeyRequest
import io.github.bropines.tailscaled.admin.api.CredentialKind
import io.github.bropines.tailscaled.admin.api.Listing
import io.github.bropines.tailscaled.admin.api.TailnetSettingKey
import io.github.bropines.tailscaled.admin.api.UserListType
import io.github.bropines.tailscaled.admin.api.UserRole
import io.github.bropines.tailscaled.admin.attention.AttentionSession
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.profile.AdminProfiles
import io.github.bropines.tailscaled.admin.profile.MissingCredentialException
import io.github.bropines.tailscaled.admin.safety.AdminAuditLog
import io.github.bropines.tailscaled.admin.safety.ChangeClass
import io.github.bropines.tailscaled.admin.safety.ChangeOutcome
import io.github.bropines.tailscaled.admin.safety.ChangeKind
import io.github.bropines.tailscaled.admin.safety.GateEvidence
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.SafeChangeRunner
import io.github.bropines.tailscaled.admin.safety.SafetyContext
import io.github.bropines.tailscaled.admin.safety.SafetyStep
import io.github.bropines.tailscaled.admin.secure.AdminWriteGate
import io.github.bropines.tailscaled.admin.secure.LockState
import io.github.bropines.tailscaled.admin.secure.UnlockResult
import io.github.bropines.tailscaled.admin.secure.ViewUnlock
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.ProxyState
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import io.github.bropines.tailscaled.models.StatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The admin console's state and every action it takes, outliving rotation (the old dashboard
 * was one composable holding thirty variables, and a turn of the phone dropped the unlock and
 * all data). Writes go through [propose] and the safety pipeline only.
 *
 * Profiles and their editor live in [ConsoleProfiles]; this class owns the session — the
 * backend of the active profile, what it loaded, the change in flight.
 */
class AdminConsoleViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ConsoleState())
    val state: StateFlow<ConsoleState> = _state.asStateFlow()

    internal var backend: AdminBackend? = null
        private set
    private var runner: SafeChangeRunner? = null
    private var capsJob: Job? = null
    private var viewUnlocked = false
    /** What to do once a change applied (show a returned secret, reload a sheet), by change. */
    private val afterApply = mutableMapOf<PlannedChange, () -> Unit>()
    private var messageSeq = 0L
    internal val audit = AdminAuditLog(AdminAuditLog.fileIn(app.filesDir))

    val profiles = ConsoleProfiles(this)

    /** The "Needs attention" home and the profile's background check. */
    val attention = AttentionSession(this)

    /**
     * Words in the app's chosen language: before Android 13 the Application's own resources
     * keep the system one (see wrapContextWithLocale), and the console's messages are made here.
     */
    internal val text: Context get() = wrapContextWithLocale(getApplication())

    internal val app: Application get() = getApplication()

    init {
        viewModelScope.launch { reload() }
    }

    internal fun update(change: (ConsoleState) -> ConsoleState) = _state.update(change)

    /** Profiles from storage (the first call migrates the old file), then the active one. */
    internal suspend fun reload(preferId: String? = null) {
        val (all, active, lock) = withContext(Dispatchers.IO) {
            val store = AdminProfiles.store(app)
            preferId?.let { store.setActive(it) }
            Triple(store.profiles(), store.active(), AdminWriteGate.lockState(app))
        }
        _state.update { it.copy(profiles = all, active = active, lockState = lock) }
        if (active == null) {
            closeSession()
            _state.update { it.copy(phase = ConsolePhase.SETUP, draft = ProfileDraft()) }
        } else activate(active)
    }

    private fun closeSession() {
        capsJob?.cancel()
        backend = null
        runner = null
    }

    private suspend fun activate(profile: AdminProfile) {
        closeSession()
        _state.update {
            ConsoleState(
                phase = ConsolePhase.LOADING,
                profiles = it.profiles,
                active = profile,
                lockState = it.lockState,
                tailnetLogDays = it.tailnetLogDays,
            )
        }
        val created = withContext(Dispatchers.IO) { runCatching { AdminProfiles.newBackend(app, profile) } }
        val b = created.getOrElse { e ->
            Log.w(TAG, "profile ${profile.id} has no usable credential: ${e.javaClass.simpleName}")
            val problem = if (e is MissingCredentialException && e.cause != null) CredentialProblem.UNREADABLE else CredentialProblem.MISSING
            _state.update { it.copy(phase = phaseAfterLoad(), credentialProblem = problem) }
            return
        }
        backend = b
        runner = SafeChangeRunner(b, audit, ::safetyContext)
        capsJob = viewModelScope.launch {
            b.capabilities.collect { caps ->
                _state.update { it.copy(caps = caps) }
                rememberRefusals(profile, caps)
            }
        }
        val log = withContext(Dispatchers.IO) { audit.records(profile.id) }
        _state.update { it.copy(phase = phaseAfterLoad(), localLog = log) }
        if (_state.value.phase == ConsolePhase.READY) start()
    }

    private fun phaseAfterLoad(): ConsolePhase =
        if (viewUnlocked || _state.value.lockState == LockState.NO_SCREEN_LOCK) ConsolePhase.READY else ConsolePhase.LOCKED

    /** A personal token's refusals, kept in the profile so the next session hides those parts at once. */
    private fun rememberRefusals(profile: AdminProfile, caps: Capabilities) {
        if (caps.credential != CredentialKind.API_TOKEN) return
        val reads = caps.access.filterValues { it == Access.NONE }.keys
        val writes = caps.access.filterValues { it == Access.READ }.keys
        if (reads == profile.deniedReads && writes == profile.deniedWrites) return
        viewModelScope.launch(Dispatchers.IO) {
            AdminProfiles.store(app).update(profile.id) { it.copy(deniedReads = reads, deniedWrites = writes) }
        }
    }

    private fun start() {
        val b = backend ?: return
        viewModelScope.launch {
            runCatching { b.refreshCapabilities() }.onFailure { Log.i(TAG, "capabilities: ${it.message}") }
        }
        loadSelf()
        refresh(ConsoleTab.DEVICES)
    }

    // ------------------------------------------------------------------ the unlock to view

    fun onViewUnlock(result: ViewUnlock) {
        when (result) {
            ViewUnlock.UNLOCKED -> {
                viewUnlocked = true
                _state.update { it.copy(phase = if (it.draft != null) it.phase else ConsolePhase.READY, viewUnlockUnavailable = false) }
                if (_state.value.phase == ConsolePhase.READY) start()
            }
            ViewUnlock.CANCELLED -> Unit
            ViewUnlock.UNAVAILABLE -> _state.update { it.copy(viewUnlockUnavailable = true) }
        }
    }

    internal fun leaveEditor() {
        val s = _state.value
        _state.update { it.copy(draft = null, phase = if (s.active == null) ConsolePhase.SETUP else phaseAfterLoad()) }
        if (_state.value.phase == ConsolePhase.READY && s.devices.value == null) start()
    }

    // ------------------------------------------------------------------ loading

    /** Loads what [tab] shows, unless it was loaded in the last minute and [force] is off. */
    fun refresh(tab: ConsoleTab, force: Boolean = false) {
        if (_state.value.phase != ConsolePhase.READY) return
        when (tab) {
            ConsoleTab.ATTENTION -> attention.refresh(force)
            ConsoleTab.DEVICES -> {
                loadList(force, { it.devices }, { s, v -> s.copy(devices = v) }) { it.listDevices() }
                    ?.invokeOnCompletion { learnTailnet() }
                loadTags(force)
            }
            ConsoleTab.DNS -> loadOne(force, { it.dns }, { s, v -> s.copy(dns = v) }) { it.dnsConfiguration() }
            ConsoleTab.USERS -> loadList(force, { it.users }, { s, v -> s.copy(users = v) }) { it.listUsers(UserListType.ALL) }
            ConsoleTab.SERVICES -> {
                loadList(force, { it.services }, { s, v -> s.copy(services = v) }) { it.listServices() }
                if (_state.value.devices.value == null) refresh(ConsoleTab.DEVICES)
            }
            ConsoleTab.WEBHOOKS -> loadList(force, { it.webhooks }, { s, v -> s.copy(webhooks = v) }) { it.listWebhooks() }
            ConsoleTab.LOGS -> {
                val days = _state.value.tailnetLogDays
                loadList(force, { it.tailnetLog }, { s, v -> s.copy(tailnetLog = v) }) {
                    val now = System.currentTimeMillis()
                    it.auditLog(rfc3339(now - days * DAY_MS), rfc3339(now))
                }
                reloadLocalLog()
            }
            ConsoleTab.WEB -> Unit
            ConsoleTab.SETTINGS -> loadOne(force, { it.settings }, { s, v -> s.copy(settings = v) }) { it.tailnetSettings() }
        }
    }

    fun refreshKeys(force: Boolean = true) =
        loadList(force, { it.keys }, { s, v -> s.copy(keys = v) }) { it.listKeys() }

    fun setTailnetLogDays(days: Int) {
        _state.update { it.copy(tailnetLogDays = days) }
        refresh(ConsoleTab.LOGS, force = true)
    }

    fun loadRoutes(device: ApiDevice) {
        val b = backend ?: return
        val id = device.pathId
        _state.update { it.copy(routes = it.routes + (id to (it.routes[id] ?: Loadable()).copy(loading = true, error = null))) }
        viewModelScope.launch {
            val r = runCatching { b.deviceRoutes(id) }
            _state.update {
                it.copy(routes = it.routes + (id to Loadable(r.getOrNull(), false, r.exceptionOrNull(), loadedAt = System.currentTimeMillis())))
            }
        }
    }

    fun loadServiceHosts(service: ApiService) {
        val b = backend ?: return
        val name = service.name
        _state.update { it.copy(serviceHosts = it.serviceHosts + (name to (it.serviceHosts[name] ?: Loadable()).copy(loading = true, error = null))) }
        viewModelScope.launch {
            val r = runCatching { b.serviceHosts(name) }
            _state.update {
                it.copy(
                    serviceHosts = it.serviceHosts + (name to Loadable(
                        r.getOrNull()?.items, false, r.exceptionOrNull(), r.getOrNull()?.issues.orEmpty(), System.currentTimeMillis()
                    ))
                )
            }
        }
    }

    fun reloadLocalLog() {
        val id = _state.value.active?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val records = audit.records(id)
            _state.update { it.copy(localLog = records) }
        }
    }

    fun clearLocalLog() {
        val id = _state.value.active?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            audit.clear(id)
            _state.update { it.copy(localLog = emptyList()) }
        }
    }

    private fun <T> loadList(
        force: Boolean,
        get: (ConsoleState) -> Loadable<List<T>>,
        set: (ConsoleState, Loadable<List<T>>) -> ConsoleState,
        fetch: suspend (AdminBackend) -> Listing<T>,
    ): Job? = load(force, get, set) { b -> fetch(b).let { it.items to it.issues } }

    private fun <T> loadOne(
        force: Boolean,
        get: (ConsoleState) -> Loadable<T>,
        set: (ConsoleState, Loadable<T>) -> ConsoleState,
        fetch: suspend (AdminBackend) -> T,
    ): Job? = load(force, get, set) { b -> fetch(b) to emptyList() }

    private fun <T> load(
        force: Boolean,
        get: (ConsoleState) -> Loadable<T>,
        set: (ConsoleState, Loadable<T>) -> ConsoleState,
        fetch: suspend (AdminBackend) -> Pair<T, List<DecodeIssue>>,
    ): Job? {
        val b = backend ?: return null
        val current = get(_state.value)
        val now = System.currentTimeMillis()
        if (current.loading || (!force && current.fresh(now, FRESH_MS))) return null
        _state.update { set(it, current.copy(loading = true)) }
        return viewModelScope.launch {
            val result = runCatching { fetch(b) }
            if (backend !== b) return@launch
            _state.update { s ->
                val prev = get(s)
                set(
                    s,
                    result.fold(
                        onSuccess = { (value, issues) -> Loadable(value, false, null, issues, System.currentTimeMillis()) },
                        // The last good value stays on screen under the error.
                        onFailure = { prev.copy(loading = false, error = it) },
                    )
                )
            }
        }
    }

    private fun loadTags(force: Boolean) {
        val b = backend ?: return
        if (!force && _state.value.policyTags.isNotEmpty()) return
        viewModelScope.launch {
            val tags = runCatching { b.policyTags() }.getOrDefault(emptyList())
            _state.update { it.copy(policyTags = tags) }
        }
    }

    /** The MagicDNS suffix most devices carry: how Serve and the peer list find this profile. */
    private fun learnTailnet() {
        val s = _state.value
        val profile = s.active ?: return
        if (profile.tailnetDnsName.isNotBlank()) return
        val suffix = s.devices.value.orEmpty().filter { !it.isShared }.mapNotNull { it.dnsSuffix?.lowercase() }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = AdminProfiles.store(app).update(profile.id) { it.copy(tailnetDnsName = suffix) } ?: return@launch
            _state.update { st -> st.copy(active = updated, profiles = st.profiles.map { if (it.id == updated.id) updated else it }) }
        }
    }

    /** This phone's node id and login, if the daemon runs; see [SelfIdentity]. */
    private fun loadSelf() {
        viewModelScope.launch(Dispatchers.IO) {
            val self = runCatching {
                if (!ProxyState.isActualRunning(app)) return@runCatching null
                val status = AppJson.decodeFromString(StatusResponse.serializer(), Appctr.getStatusFromAPI())
                val me = status.self ?: return@runCatching null
                SelfIdentity(me.id, status.users?.get(me.userID?.toString())?.loginName)
            }.getOrNull() ?: return@launch
            _state.update { it.copy(self = self) }
        }
    }

    // ------------------------------------------------------------------ changes

    private fun safetyContext(): SafetyContext {
        val s = _state.value
        val caps = s.caps
        return SafetyContext(
            profileId = s.active?.id.orEmpty(),
            profileName = s.active?.displayName.orEmpty(),
            readOnlyProfile = s.active?.readOnly == true,
            lockState = s.lockState,
            ownKeyId = caps?.ownKeyId,
            ownUserId = caps?.ownUserId,
            ownLoginName = s.self.loginName.takeIf { s.phoneInTailnet },
            canWrite = { area -> caps?.canWrite(area) != false },
        )
    }

    /** The label a tailnet-wide change is confirmed with: what the person sees in the top bar. */
    val tailnetLabel: String get() = _state.value.active?.let { it.tailnetDnsName.ifBlank { it.displayName } }.orEmpty()

    /** Whether the runner's guards would stop [planned] whatever happens at the gates. */
    fun blockedBy(planned: PlannedChange) = runner?.blockedBy(planned.change)

    /** Every write starts here: guards, then the gates its class needs, then apply and verify. */
    fun propose(planned: PlannedChange, after: (() -> Unit)? = null) {
        val r = runner ?: return
        val blocked = r.blockedBy(planned.change)
        if (blocked != null) {
            viewModelScope.launch {
                // Recorded too: the log shows what was stopped, not only what went out.
                withContext(Dispatchers.IO) { r.run(planned, GateEvidence(confirmed = false)) }
                say(ConsoleText.refusal(text, blocked))
            }
            return
        }
        if (after != null) afterApply[planned] = after
        if (planned.change.changeClass == ChangeClass.LOW) apply(planned, GateEvidence(confirmed = true))
        else _state.update { it.copy(safety = SafetyStep.Confirm(planned)) }
    }

    fun confirmChange(typedName: String?) {
        val step = _state.value.safety as? SafetyStep.Confirm ?: return
        _state.update { it.copy(safety = SafetyStep.Unlock(step.planned, typedName)) }
    }

    fun cancelChange() {
        when (val step = _state.value.safety) {
            is SafetyStep.Confirm -> afterApply.remove(step.planned)
            is SafetyStep.Unlock -> afterApply.remove(step.planned)
            else -> Unit
        }
        _state.update { it.copy(safety = SafetyStep.Idle) }
    }

    fun onUnlockResult(result: UnlockResult) {
        val step = _state.value.safety as? SafetyStep.Unlock ?: return
        when (result) {
            is UnlockResult.Granted -> apply(step.planned, GateEvidence(true, step.typedName, result.grant))
            UnlockResult.Cancelled -> {
                cancelChange()
                say(text.getString(R.string.admin2_unlock_cancelled))
            }
            is UnlockResult.Failed -> {
                cancelChange()
                say(ConsoleText.unlockFailure(text, result.reason))
            }
        }
    }

    private fun apply(planned: PlannedChange, gates: GateEvidence) {
        val r = runner ?: return
        _state.update { it.copy(safety = SafetyStep.Applying(planned.change)) }
        viewModelScope.launch {
            // Off the main thread: the runner appends to the audit log file.
            val out = withContext(Dispatchers.IO) { r.run(planned, gates) }
            val then = afterApply.remove(planned)
            _state.update { it.copy(safety = SafetyStep.Idle) }
            say(ConsoleText.outcome(text, planned.change.title, out), undo = planned.undo.takeIf { out is ChangeOutcome.Applied })
            if (out is ChangeOutcome.Applied) then?.invoke()
            refreshAfter(planned.change.kind)
            reloadLocalLog()
        }
    }

    private fun refreshAfter(kind: ChangeKind) {
        when (kind.area) {
            AdminArea.DEVICES, AdminArea.ROUTES ->
                refresh(ConsoleTab.DEVICES, force = true)
            AdminArea.AUTH_KEYS -> refreshKeys()
            AdminArea.USERS -> refresh(ConsoleTab.USERS, force = true)
            AdminArea.DNS -> refresh(ConsoleTab.DNS, force = true)
            AdminArea.WEBHOOKS -> refresh(ConsoleTab.WEBHOOKS, force = true)
            AdminArea.SERVICES -> refresh(ConsoleTab.SERVICES, force = true)
            else -> refresh(ConsoleTab.SETTINGS, force = true)
        }
    }

    fun say(message: String, undo: PlannedChange? = null) {
        _state.update { it.copy(message = ConsoleMessage(message, ++messageSeq, undo)) }
    }

    fun messageShown(id: Long) {
        _state.update { if (it.message?.id == id) it.copy(message = null) else it }
    }

    fun secretSaved() = _state.update { it.copy(revealed = null) }

    // ------------------------------------------------------------------ actions, by area

    private val selfNode: String? get() = _state.value.self.nodeId

    fun renameDevice(d: ApiDevice, name: String) = propose(ConsoleChanges.renameDevice(text, d, name, selfNode))
    fun setDeviceTags(d: ApiDevice, tags: List<String>) = propose(ConsoleChanges.setTags(text, d, tags, selfNode))
    fun setDeviceAuthorized(d: ApiDevice, authorized: Boolean) = propose(ConsoleChanges.setAuthorized(text, d, authorized, selfNode))
    fun setKeyExpiryDisabled(d: ApiDevice, disabled: Boolean) = propose(ConsoleChanges.setKeyExpiryDisabled(text, d, disabled, selfNode))
    fun expireDevice(d: ApiDevice) = propose(ConsoleChanges.expireDevice(text, d, selfNode))
    fun deleteDevice(d: ApiDevice) = propose(ConsoleChanges.deleteDevice(text, d, selfNode))
    fun setRoutes(d: ApiDevice, before: List<String>, after: List<String>) =
        propose(ConsoleChanges.setRoutes(text, d, before, after, selfNode)) { loadRoutes(d) }

    fun createAuthKey(request: AuthKeyRequest) {
        var created: ApiKey? = null
        val t = text
        propose(ConsoleChanges.createAuthKey(t, request) { created = it }) {
            created?.key?.let { secret ->
                _state.update {
                    it.copy(revealed = RevealedSecret(t.getString(R.string.admin_key_generated_title), t.getString(R.string.admin_key_generated_text), secret))
                }
            }
        }
    }

    fun revokeKey(k: ApiKey) = propose(ConsoleChanges.revokeKey(text, k))

    fun approveUser(u: ApiUser) = propose(ConsoleChanges.approveUser(text, u))
    fun setUserRole(u: ApiUser, role: UserRole) = propose(ConsoleChanges.setUserRole(text, u, role))
    fun suspendUser(u: ApiUser) = propose(ConsoleChanges.suspendUser(text, u))
    fun restoreUser(u: ApiUser) = propose(ConsoleChanges.restoreUser(text, u))
    fun deleteUser(u: ApiUser) = propose(ConsoleChanges.deleteUser(text, u))

    fun setMagicDns(on: Boolean) = propose(ConsoleChanges.setMagicDns(text, on, tailnetLabel))
    fun setNameservers(after: List<String>) {
        val cfg = _state.value.dns.value ?: return
        propose(ConsoleChanges.setNameservers(text, cfg.nameserverAddresses, after, cfg.magicDns, tailnetLabel))
    }
    fun setSplitDns(domain: String, nameservers: List<String>?) {
        val before = _state.value.dns.value?.splitDnsAddresses?.get(domain)
        propose(ConsoleChanges.setSplitDns(text, domain, before, nameservers, tailnetLabel))
    }
    fun setSearchPaths(after: List<String>) {
        val cfg = _state.value.dns.value ?: return
        propose(ConsoleChanges.setSearchPaths(text, cfg.searchPaths, after, tailnetLabel))
    }

    fun setSetting(key: TailnetSettingKey, label: String, after: Any, show: (Any?) -> String) {
        val before = _state.value.settings.value?.let { ConsoleChanges.settingValue(it, key) }
        if (before == after) return
        propose(ConsoleChanges.setSetting(text, key, label, before, after, show, tailnetLabel))
    }

    fun createWebhook(url: String, provider: String, events: List<String>) {
        var created: ApiWebhook? = null
        val t = text
        propose(ConsoleChanges.createWebhook(t, url, provider, events, tailnetLabel) { created = it }) {
            created?.secret?.takeIf { it.isNotBlank() }?.let { secret ->
                _state.update {
                    it.copy(revealed = RevealedSecret(t.getString(R.string.admin2_webhook_secret_title), t.getString(R.string.admin2_webhook_secret_text), secret))
                }
            }
        }
    }

    fun testWebhook(w: ApiWebhook) = propose(ConsoleChanges.testWebhook(text, w))
    fun deleteWebhook(w: ApiWebhook) = propose(ConsoleChanges.deleteWebhook(text, w))

    fun setServiceHost(service: ApiService, deviceId: String, deviceName: String, approved: Boolean) =
        propose(ConsoleChanges.setServiceHost(text, service, deviceId, deviceName, approved)) { loadServiceHosts(service) }

    // ------------------------------------------------------------------ profiles (see ConsoleProfiles)

    internal suspend fun activateById(id: String) = reload(preferId = id)

    internal fun markViewUnlocked() {
        viewUnlocked = true
    }

    internal fun lockStateNow(): LockState = AdminWriteGate.lockState(app)

    override fun onCleared() {
        closeSession()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AdminConsole"
        private const val FRESH_MS = 60_000L
        private const val DAY_MS = 24L * 3600 * 1000

        fun rfc3339(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    }
}
