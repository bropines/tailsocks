package io.github.bropines.tailscaled.admin.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.Loadable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The device list as the server filtered it, for [filters]. */
data class ServedList(val filters: List<Pair<String, String>>, val list: Loadable<List<ApiDevice>>)

/** Reading every device's routes, which the list does not carry, for the routers filter. */
data class RouteSweep(val done: Int, val total: Int, val running: Boolean = true, val error: Throwable? = null)

/** The bulk tag editor: what to do, with which tags; [offered] adds typed tags to the policy's. */
data class BulkDraft(
    val mode: BulkTagMode = BulkTagMode.ADD,
    val tags: List<String> = emptyList(),
    val offered: List<String> = emptyList(),
)

data class DevicesUi(
    val profileId: String? = null,
    val query: DeviceQuery = DeviceQuery(),
    val served: ServedList? = null,
    val sweep: RouteSweep? = null,
    val selecting: Boolean = false,
    /** By path id. */
    val selected: Set<String> = emptySet(),
    val bulk: BulkDraft? = null,
    /** The bulk run's results, in order, as they land; null before it runs. */
    val bulkResults: List<BulkOutcome>? = null,
    /** Devices read with every field (fields=all), by path id. */
    val detail: Map<String, Loadable<ApiDevice>> = emptyMap(),
)

/**
 * The Devices tab's own state, beside the console's: the query, the server-filtered list, the
 * route sweep, the selection and the bulk run, the devices read in full. Reads go through the
 * console's backend; writes only ever through [AdminConsoleViewModel.propose].
 */
class DevicesViewModel : ViewModel() {
    private val _ui = MutableStateFlow(DevicesUi())
    val ui: StateFlow<DevicesUi> = _ui.asStateFlow()

    private var servedJob: Job? = null
    private var sweepJob: Job? = null

    /** A profile switch starts over, keeping the search and the order. */
    fun bind(profileId: String?) {
        if (_ui.value.profileId == profileId) return
        servedJob?.cancel()
        sweepJob?.cancel()
        _ui.update { DevicesUi(profileId = profileId, query = it.query.copy(owner = null, tag = null)) }
    }

    fun setQuery(q: DeviceQuery) = _ui.update { it.copy(query = q) }

    /** The server-side part of the query, asked of the server; nothing when the query has none. */
    fun loadServed(vm: AdminConsoleViewModel, filters: List<Pair<String, String>>) {
        servedJob?.cancel()
        if (filters.isEmpty()) {
            _ui.update { it.copy(served = null) }
            return
        }
        val b = vm.backend ?: return
        _ui.update { ui ->
            val prev = ui.served?.takeIf { it.filters == filters }?.list ?: Loadable()
            ui.copy(served = ServedList(filters, prev.copy(loading = true, error = null)))
        }
        servedJob = viewModelScope.launch {
            val r = attempt { b.listDevices(filters) }
            if (vm.backend !== b) return@launch
            _ui.update { ui ->
                val cur = ui.served
                if (cur == null || cur.filters != filters) ui
                else ui.copy(
                    served = ServedList(
                        filters,
                        r.fold(
                            { Loadable(it.items, false, null, it.issues, System.currentTimeMillis()) },
                            { cur.list.copy(loading = false, error = it) },
                        ),
                    )
                )
            }
        }
    }

    /**
     * Reads the routes of every device whose routes are not known yet, four at a time, into the
     * console's routes map. Stops at the first refusal of the credential: the rest would fail
     * the same way.
     */
    fun sweepRoutes(vm: AdminConsoleViewModel, devices: List<ApiDevice>) {
        val b = vm.backend ?: return
        val known = vm.state.value.routes
        val todo = devices.filter { !it.isShared && known[it.pathId]?.value == null && DeviceQueries.knownRoutes(it, null) == null }
        if (todo.isEmpty() || _ui.value.sweep?.running == true) return
        _ui.update { it.copy(sweep = RouteSweep(0, todo.size)) }
        sweepJob = viewModelScope.launch {
            val gate = Semaphore(4)
            var refusal: Throwable? = null
            todo.map { d ->
                launch {
                    gate.withPermit {
                        if (refusal != null || vm.backend !== b) return@withPermit
                        val r = attempt { b.deviceRoutes(d.pathId) }
                        if (vm.backend !== b) return@withPermit
                        vm.update { s ->
                            s.copy(routes = s.routes + (d.pathId to Loadable(r.getOrNull(), false, r.exceptionOrNull(), loadedAt = System.currentTimeMillis())))
                        }
                        val e = r.exceptionOrNull()
                        if (e is AdminApiException.Forbidden || e is AdminApiException.Unauthorized || e is AdminApiException.Network) refusal = e
                        _ui.update { ui -> ui.copy(sweep = ui.sweep?.let { it.copy(done = it.done + 1, error = it.error ?: e) }) }
                    }
                }
            }.joinAll()
            _ui.update { ui -> ui.copy(sweep = ui.sweep?.copy(running = false)) }
        }
    }

    /** One device with every field, connectivity included; the backend falls back to the default fields. */
    fun loadDetail(vm: AdminConsoleViewModel, deviceId: String) {
        val b = vm.backend ?: return
        _ui.update { it.copy(detail = it.detail + (deviceId to (it.detail[deviceId] ?: Loadable()).copy(loading = true, error = null))) }
        viewModelScope.launch {
            val r = attempt { b.getDevice(deviceId) }
            if (vm.backend !== b) return@launch
            _ui.update { ui ->
                val prev = ui.detail[deviceId] ?: Loadable()
                ui.copy(
                    detail = ui.detail + (deviceId to r.fold(
                        { Loadable(it, false, null, loadedAt = System.currentTimeMillis()) },
                        { prev.copy(loading = false, error = it) },
                    ))
                )
            }
        }
    }

    // ------------------------------------------------------------------ selection and bulk tags

    fun startSelecting() = _ui.update { it.copy(selecting = true) }

    fun stopSelecting() = _ui.update { it.copy(selecting = false, selected = emptySet()) }

    fun toggle(deviceId: String) = _ui.update {
        it.copy(selected = if (deviceId in it.selected) it.selected - deviceId else it.selected + deviceId)
    }

    fun selectOnly(ids: Collection<String>) = _ui.update { it.copy(selected = ids.toSet()) }

    fun openBulk() = _ui.update { it.copy(bulk = BulkDraft(), bulkResults = null) }

    fun updateBulk(draft: BulkDraft) = _ui.update { if (it.bulkResults == null) it.copy(bulk = draft) else it }

    /** Closes the editor; after a run, the selection goes with it. */
    fun closeBulk() = _ui.update {
        if (it.bulkResults != null) it.copy(bulk = null, bulkResults = null, selecting = false, selected = emptySet())
        else it.copy(bulk = null)
    }

    /**
     * Hands the dry run to the gates as one HIGH change. The results stream into [DevicesUi.bulkResults]
     * while it applies, and each device's own record goes to the console's audit log.
     */
    fun applyBulk(vm: AdminConsoleViewModel, rows: List<BulkTagRow>, draft: BulkDraft) {
        val s = vm.state.value
        val planned = DeviceChanges.bulkTags(
            ctx = vm.text,
            rows = rows,
            mode = draft.mode,
            tags = draft.tags,
            selfNodeId = s.self.nodeId,
            profileId = s.active?.id.orEmpty(),
            profileName = s.active?.displayName.orEmpty(),
            onStart = {
                _ui.update { ui ->
                    ui.copy(bulkResults = rows.filter { it.changes }.map { BulkOutcome(it.device.pathId, it.device.shortName, BulkState.PENDING) })
                }
            },
            onOutcome = { out ->
                _ui.update { ui -> ui.copy(bulkResults = ui.bulkResults?.map { if (it.deviceId == out.deviceId) out else it }) }
            },
            record = { rec -> runCatching { vm.audit.append(rec) } },
        )
        vm.propose(planned)
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
