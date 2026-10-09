package io.github.bropines.tailscaled.admin.attention

import android.util.Log
import androidx.lifecycle.viewModelScope
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.notify.AttentionChecks
import io.github.bropines.tailscaled.admin.notify.AttentionNotifier
import io.github.bropines.tailscaled.admin.notify.AttentionNotifyOnce
import io.github.bropines.tailscaled.admin.notify.AttentionPrefs
import io.github.bropines.tailscaled.admin.notify.AttentionScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What the attention home holds besides the console's own lists. */
data class AttentionUiState(
    val profileId: String? = null,
    /** Routes read device by device (the list carries none), by path id, all at [routesReadAt]. */
    val routes: Map<String, DeviceRoutes> = emptyMap(),
    val routesReadAt: Long = 0,
    val scanning: Boolean = false,
    /** Devices whose routes were read, of [scanTotal] that could have been: fewer when capped. */
    val scanned: Int = 0,
    val scanTotal: Int = 0,
    val checks: AttentionChecks = AttentionChecks(),
    val notificationsAllowed: Boolean = true,
)

/**
 * The "Needs attention" home's part of the console session: it loads what the list is
 * computed from (devices, users, keys — through the console's own loads — and each device's
 * routes, read here a few at a time), and keeps the profile's background check. Its actions
 * are the console's own changes, proposed through the ViewModel's safety pipeline.
 */
class AttentionSession internal constructor(private val vm: AdminConsoleViewModel) {

    private val _state = MutableStateFlow(AttentionUiState())
    val state: StateFlow<AttentionUiState> = _state.asStateFlow()
    private var scanJob: Job? = null

    init {
        // A restored backup or a cleared WorkManager database: put the enabled checks back.
        vm.viewModelScope.launch(Dispatchers.IO) { runCatching { AttentionScheduler.reconcile(vm.app) } }
    }

    /** What the home shows: the console's lists it needs, then the routes. */
    fun refresh(force: Boolean) {
        val s = vm.state.value
        val profile = s.active ?: return
        if (_state.value.profileId != profile.id) {
            scanJob?.cancel()
            _state.value = AttentionUiState(profileId = profile.id)
            loadChecks(profile.id)
        } else checkNotifications()
        vm.refresh(ConsoleTab.DEVICES, force)
        if (readable(s, BackendFeature.USERS, AdminArea.USERS)) vm.refresh(ConsoleTab.USERS, force)
        if (readable(s, BackendFeature.KEYS, AdminArea.AUTH_KEYS)) vm.refreshKeys(force)
        scanRoutes(force)
    }

    private fun scanRoutes(force: Boolean) {
        if (scanJob?.isActive == true) return
        val b = vm.backend ?: return
        val profileId = _state.value.profileId ?: return
        scanJob = vm.viewModelScope.launch {
            try {
                // The list this refresh asked for, or the one already there when it was fresh.
                val devices = vm.state.first { !it.devices.loading }.devices.value ?: return@launch
                val caps = vm.state.value.caps
                if (vm.backend !== b || !readable(vm.state.value, BackendFeature.DEVICE_ROUTES, AdminArea.ROUTES)) return@launch
                if (!force && System.currentTimeMillis() - _state.value.routesReadAt < ROUTES_FRESH_MS) return@launch
                val candidates = devices.filter { !it.isShared && it.authorized != false }
                val targets = candidates
                    .sortedWith(compareByDescending<ApiDevice> { it.isOnline }.thenBy { it.shortName.lowercase() })
                    .take(MAX_ROUTE_READS)
                _state.update { it.copy(scanning = true) }
                val gate = Semaphore(PARALLEL_READS)
                var refused = false
                val read = coroutineScope {
                    targets.map { d ->
                        async {
                            gate.withPermit {
                                if (refused) return@withPermit null
                                try {
                                    d.pathId to b.deviceRoutes(d.pathId)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // One refusal answers for every device: stop asking.
                                    if (e is AdminApiException.Forbidden || e is AdminApiException.Unauthorized) refused = true
                                    Log.i(TAG, "routes of ${d.pathId}: ${e.javaClass.simpleName}")
                                    null
                                }
                            }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }
                if (vm.backend !== b || _state.value.profileId != profileId) return@launch
                _state.update {
                    it.copy(
                        routes = if (caps?.canRead(AdminArea.ROUTES) == false) emptyMap() else read,
                        routesReadAt = System.currentTimeMillis(),
                        scanned = targets.size,
                        scanTotal = candidates.size,
                    )
                }
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private val selfNode: String? get() = vm.state.value.self.nodeId

    fun approveDevice(d: ApiDevice) = vm.setDeviceAuthorized(d, true)
    fun rejectDevice(d: ApiDevice) = vm.propose(AttentionChanges.rejectDevice(vm.text, d, selfNode))
    fun approveUser(u: ApiUser) = vm.approveUser(u)
    fun rejectUser(u: ApiUser) = vm.propose(AttentionChanges.rejectUser(vm.text, u))

    /** Every route the item lists as waiting, on top of what the device has approved now. */
    fun approveRoutes(item: AttentionItem) {
        val d = item.device ?: return
        val after = (item.enabledRoutes + item.pendingRoutes).distinct()
        vm.setRoutes(d, item.enabledRoutes, after)
    }

    // ------------------------------------------------------------------ background checks

    private fun loadChecks(profileId: String) {
        vm.viewModelScope.launch {
            val checks = withContext(Dispatchers.IO) { AttentionPrefs.of(vm.app).checks(profileId) }
            _state.update { if (it.profileId == profileId) it.copy(checks = checks) else it }
            checkNotifications()
        }
    }

    /** Whether a notification would show at all: the permission and the channel. */
    fun checkNotifications() {
        val allowed = AttentionNotifier.canPost(vm.app)
        _state.update { if (it.notificationsAllowed == allowed) it else it.copy(notificationsAllowed = allowed) }
    }

    /**
     * Turns the profile's background check on or off. Turning it on takes what is on screen
     * as already seen: the person is looking at it, so only what comes after is news.
     */
    fun setChecks(enabled: Boolean, onScreen: List<AttentionItem>) {
        val profileId = _state.value.profileId ?: return
        val next = _state.value.checks.copy(enabled = enabled)
        _state.update { it.copy(checks = next) }
        vm.viewModelScope.launch(Dispatchers.IO) {
            val prefs = AttentionPrefs.of(vm.app)
            if (enabled) prefs.setSeen(profileId, AttentionNotifyOnce.seed(onScreen))
            prefs.setChecks(profileId, next)
            AttentionScheduler.apply(vm.app, profileId, next)
        }
    }

    fun setInterval(minutes: Int) {
        val profileId = _state.value.profileId ?: return
        val next = _state.value.checks.copy(intervalMinutes = minutes)
        _state.update { it.copy(checks = next) }
        vm.viewModelScope.launch(Dispatchers.IO) {
            AttentionPrefs.of(vm.app).setChecks(profileId, next)
            AttentionScheduler.apply(vm.app, profileId, next)
        }
    }

    companion object {
        private const val TAG = "AdminAttention"
        private const val ROUTES_FRESH_MS = 5 * 60_000L
        /** A few at a time: a large tailnet is a hundred calls, not a burst of them. */
        private const val PARALLEL_READS = 4
        private const val MAX_ROUTE_READS = 100

        fun readable(s: ConsoleState, feature: BackendFeature, area: AdminArea): Boolean {
            val caps = s.caps ?: return true
            return caps.has(feature) && caps.canRead(area)
        }

        /**
         * What the list is computed from: the console's lists where they were read, and for
         * each device the newer of the routes read here and those its sheet loaded since.
         */
        fun input(s: ConsoleState, a: AttentionUiState): AttentionInput {
            val routes = a.routes.toMutableMap()
            s.routes.forEach { (id, l) -> if (l.value != null && l.loadedAt > a.routesReadAt) routes[id] = l.value }
            return AttentionInput(
                devices = s.devices.value,
                users = s.users.value.takeIf { readable(s, BackendFeature.USERS, AdminArea.USERS) },
                keys = s.keys.value.takeIf { readable(s, BackendFeature.KEYS, AdminArea.AUTH_KEYS) },
                routes = routes,
                ownKeyId = s.caps?.ownKeyId,
                credentialExpires = s.caps?.credentialExpires,
                credentialRefused = s.devices.error is AdminApiException.Unauthorized,
            )
        }
    }
}
