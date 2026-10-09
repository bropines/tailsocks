package io.github.bropines.tailscaled.admin.devices

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.LoadProblems
import io.github.bropines.tailscaled.admin.LoadingIndicatorCompat
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.DeviceRoutes
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.ui.EmptyState
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet

/** What the list's controls do; the defaults do nothing, for previews. */
class DevicesListActions(
    val onQuery: (DeviceQuery) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDeviceClick: (ApiDevice) -> Unit = {},
    val onStartSelecting: () -> Unit = {},
    val onStopSelecting: () -> Unit = {},
    val onToggle: (ApiDevice) -> Unit = {},
    val onSelectOnly: (List<String>) -> Unit = {},
    val onBulkTags: () -> Unit = {},
)

/**
 * The Devices tab: search, filter chips, the list in its order, and the selection that leads to
 * bulk tags. Reads the console's [state] and the tab's own [DevicesViewModel]; every change goes
 * to [vm]. In previews [vm] is null and [preview] stands in for the tab's state.
 */
@Composable
fun DevicesTab(
    state: ConsoleState,
    vm: AdminConsoleViewModel?,
    selfNodeId: String?,
    onDeviceClick: (ApiDevice) -> Unit,
    preview: DevicesUi = DevicesUi(),
) {
    val dvm: DevicesViewModel? = if (vm != null) viewModel() else null
    val ui = dvm?.ui?.collectAsState()?.value ?: preview
    val now = if (LocalInspectionMode.current) PREVIEW_NOW else remember(state.devices.loadedAt) { System.currentTimeMillis() }
    val all = state.devices.value.orEmpty()
    val q = ui.query
    val serverFilters = remember(q) { DeviceQueries.serverFilters(q) }
    val routersOn = DeviceFilter.ROUTERS in q.filters

    LaunchedEffect(state.active?.id) { dvm?.bind(state.active?.id) }
    LaunchedEffect(serverFilters, state.devices.loadedAt) { if (vm != null) dvm?.loadServed(vm, serverFilters) }
    LaunchedEffect(routersOn, state.devices.loadedAt) { if (routersOn && vm != null) dvm?.sweepRoutes(vm, all) }
    BackHandler(enabled = ui.selecting && ui.bulk == null) { dvm?.stopSelecting() }

    DevicesListContent(
        state = state,
        ui = ui,
        now = now,
        selfNodeId = selfNodeId,
        canSelect = state.canWrite(AdminArea.DEVICES),
        actions = DevicesListActions(
            onQuery = { dvm?.setQuery(it) },
            onRetry = { vm?.refresh(ConsoleTab.DEVICES, force = true) },
            onDeviceClick = onDeviceClick,
            onStartSelecting = { dvm?.startSelecting() },
            onStopSelecting = { dvm?.stopSelecting() },
            onToggle = { dvm?.toggle(it.pathId) },
            onSelectOnly = { dvm?.selectOnly(it) },
            onBulkTags = { dvm?.openBulk() },
        ),
    )

    ui.bulk?.let { draft ->
        BulkTagsSheet(
            devices = all.filter { it.pathId in ui.selected },
            draft = draft,
            results = ui.bulkResults,
            policyTags = state.policyTags,
            onDraft = { dvm?.updateBulk(it) },
            onApply = { rows -> if (vm != null) dvm?.applyBulk(vm, rows, draft) },
            onClose = { dvm?.closeBulk() },
        )
    }
}

/** The tab without its ViewModel: what previews draw. */
@Composable
fun DevicesListContent(
    state: ConsoleState,
    ui: DevicesUi,
    now: Long,
    selfNodeId: String?,
    canSelect: Boolean,
    actions: DevicesListActions,
) {
    val ctx = LocalContext.current
    val q = ui.query
    val all = state.devices.value.orEmpty()
    val serverFilters = DeviceQueries.serverFilters(q)
    // The server's answer for the exact-match part, once it is there; the phone's filtering on top either way.
    val source = if (serverFilters.isEmpty()) all else ui.served?.takeIf { it.filters == serverFilters }?.list?.value ?: all
    val routesOf: (ApiDevice) -> DeviceRoutes? = { DeviceQueries.knownRoutes(it, state.routes[it.pathId]?.value) }
    val shown = remember(source, q, now, state.routes) { DeviceQueries.apply(source, q, now, routesOf) }
    val myLogin = state.self.loginName?.takeIf { state.phoneInTailnet }
        ?: state.caps?.ownUserId?.let { id -> state.users.value?.firstOrNull { it.id == id }?.loginName }

    Column(Modifier.fillMaxSize()) {
        SearchField(q.text) { actions.onQuery(q.copy(text = it)) }
        FilterChips(state, all, q, now, myLogin, routesOf, actions.onQuery)
        if (ui.selecting) SelectionBar(ui, shown, actions)
        else CountRow(shown.size, all.size, q, canSelect, actions)
        ui.sweep?.let { SweepLine(it, DeviceFilter.ROUTERS in q.filters) }
        LoadProblems(state.devices, actions.onRetry, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        when {
            all.isEmpty() && state.devices.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { LoadingIndicatorCompat() }
            all.isEmpty() -> EmptyState(Icons.Default.Devices, ctx.getString(R.string.admin_dev_none), Modifier.fillMaxWidth().weight(1f))
            shown.isEmpty() -> EmptyState(
                Icons.Default.FilterAltOff, ctx.getString(R.string.admin_dev_none_match), Modifier.fillMaxWidth().weight(1f),
                actionLabel = ctx.getString(R.string.admin_dev_clear_filters),
                onAction = { actions.onQuery(q.cleared()) },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.pathId }) { d ->
                    DeviceCard(
                        d = d,
                        badges = DeviceQueries.badges(d, now, routesOf(d), selfNodeId),
                        now = now,
                        selecting = ui.selecting,
                        selected = d.pathId in ui.selected,
                        onClick = { if (ui.selecting) actions.onToggle(d) else actions.onDeviceClick(d) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(text: String, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        placeholder = { Text(ctx.getString(R.string.admin_dev_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (text.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, ctx.getString(R.string.admin_dev_search_clear)) } }
        } else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * The filter chips, in one scrolling row. Whose devices, how long offline and which tag open a
 * picker; the rest switch on and off, with a check when on and a count where it tells something.
 */
@Composable
private fun FilterChips(
    state: ConsoleState,
    all: List<ApiDevice>,
    q: DeviceQuery,
    now: Long,
    myLogin: String?,
    routesOf: (ApiDevice) -> DeviceRoutes?,
    onQuery: (DeviceQuery) -> Unit,
) {
    val ctx = LocalContext.current
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val counts = remember(all, now, state.routes, q.offlineDays, q.tag) {
        DeviceFilter.entries.associateWith { f -> all.count { DeviceQueries.matches(it, f, q, now, routesOf(it)) } }
    }

    @Composable
    fun Toggle(f: DeviceFilter, label: Int, counted: Boolean = false) {
        val on = f in q.filters
        val n = counts[f] ?: 0
        val text = ctx.getString(label).let { if (counted && n > 0) ctx.getString(R.string.admin_dev_filter_count, it, n) else it }
        FilterChip(
            selected = on,
            onClick = { onQuery(q.with(f, !on)) },
            label = { Text(text) },
            leadingIcon = if (on) {
                { Icon(Icons.Default.Done, null, Modifier.size(18.dp)) }
            } else null,
        )
    }

    @Composable
    fun Picker(on: Boolean, text: String, id: String) {
        FilterChip(
            selected = on,
            onClick = { picker = id },
            label = { Text(text) },
            leadingIcon = if (on) {
                { Icon(Icons.Default.Done, null, Modifier.size(18.dp)) }
            } else null,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
    }

    val ownerText = when (val o = q.owner) {
        null -> ctx.getString(R.string.admin_dev_owner_all)
        myLogin -> ctx.getString(R.string.admin_dev_owner_mine)
        else -> o
    }
    val offlineOn = DeviceFilter.OFFLINE in q.filters
    val tagOn = DeviceFilter.TAGGED in q.filters
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Picker(q.owner != null, ownerText, "owner")
        Toggle(DeviceFilter.NEEDS_APPROVAL, R.string.admin_dev_filter_needs_approval, counted = true)
        Toggle(DeviceFilter.EXPIRING, R.string.admin_dev_filter_expiring, counted = true)
        Picker(
            offlineOn,
            if (offlineOn) ctx.resources.getQuantityString(R.plurals.admin_dev_filter_offline_days, q.offlineDays, q.offlineDays)
            else ctx.getString(R.string.admin_dev_filter_offline),
            "offline",
        )
        Toggle(DeviceFilter.ROUTERS, R.string.admin_dev_filter_routers)
        Toggle(DeviceFilter.UPDATE, R.string.admin_dev_filter_update, counted = true)
        Picker(tagOn, q.tag?.takeIf { tagOn } ?: ctx.getString(R.string.admin_dev_filter_tagged), "tag")
        Toggle(DeviceFilter.SHARED, R.string.admin_dev_filter_shared, counted = true)
    }

    when (picker) {
        "owner" -> {
            val others = all.mapNotNull { it.user?.takeIf { u -> u.isNotBlank() } }.distinct().sortedBy { it.lowercase() }.filter { it != myLogin }
            PickerSheet(
                title = ctx.getString(R.string.admin_dev_owner_title),
                options = listOf(PickerOption("", ctx.getString(R.string.admin_dev_owner_all))) +
                    listOfNotNull(myLogin?.let { PickerOption(it, ctx.getString(R.string.admin_dev_owner_mine_option, it), Icons.Default.Person) }) +
                    others.map { PickerOption(it, it) },
                selected = q.owner ?: "",
                onPick = { onQuery(q.copy(owner = it.ifEmpty { null })) },
                onDismiss = { picker = null },
            )
        }
        "offline" -> PickerSheet(
            title = ctx.getString(R.string.admin_dev_offline_title),
            options = listOf(PickerOption(0, ctx.getString(R.string.admin_dev_filter_off))) +
                DeviceQueries.offlineChoices.map { PickerOption(it, ctx.resources.getQuantityString(R.plurals.admin_dev_days, it, it)) },
            selected = if (offlineOn) q.offlineDays else 0,
            onPick = { days -> onQuery(if (days == 0) q.with(DeviceFilter.OFFLINE, false) else q.copy(offlineDays = days).with(DeviceFilter.OFFLINE, true)) },
            onDismiss = { picker = null },
        )
        "tag" -> {
            val tags = (state.policyTags + all.flatMap { it.tags }).distinct().sorted()
            PickerSheet(
                title = ctx.getString(R.string.admin_dev_tag_title),
                options = listOf(
                    PickerOption("", ctx.getString(R.string.admin_dev_filter_off)),
                    PickerOption(ANY_TAG, ctx.getString(R.string.admin_dev_tag_any)),
                ) + tags.map { PickerOption(it, it, monospace = true) },
                selected = if (!tagOn) "" else q.tag ?: ANY_TAG,
                onPick = { v ->
                    onQuery(
                        when (v) {
                            "" -> q.with(DeviceFilter.TAGGED, false)
                            ANY_TAG -> q.copy(tag = null).with(DeviceFilter.TAGGED, true)
                            else -> q.copy(tag = v).with(DeviceFilter.TAGGED, true)
                        }
                    )
                },
                onDismiss = { picker = null },
            )
        }
    }
}

private const val ANY_TAG = "\u0000any"

@Composable
private fun CountRow(shown: Int, total: Int, q: DeviceQuery, canSelect: Boolean, actions: DevicesListActions) {
    val ctx = LocalContext.current
    var sorting by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (q.narrowed) ctx.resources.getQuantityString(R.plurals.admin_dev_count_filtered, total, shown, total)
            else ctx.resources.getQuantityString(R.plurals.admin2_devices_count, total, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { sorting = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(sortLabel(ctx, q.sort), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // The chips scroll; an active one may be out of sight. This says filtering is on and undoes it.
        if (q.owner != null || q.filters.isNotEmpty()) {
            IconButton(onClick = { actions.onQuery(q.cleared().copy(text = q.text)) }) {
                Icon(Icons.Default.FilterAltOff, ctx.getString(R.string.admin_dev_clear_filters))
            }
        }
        if (canSelect) {
            IconButton(onClick = actions.onStartSelecting) { Icon(Icons.Default.Checklist, ctx.getString(R.string.admin_dev_select)) }
        }
    }
    if (sorting) {
        PickerSheet(
            title = ctx.getString(R.string.admin_dev_sort_title),
            options = listOf(
                PickerOption(DeviceSort.NAME, sortLabel(ctx, DeviceSort.NAME), Icons.Default.SortByAlpha),
                PickerOption(DeviceSort.LAST_SEEN, sortLabel(ctx, DeviceSort.LAST_SEEN), Icons.Default.Schedule),
                PickerOption(DeviceSort.CREATED, sortLabel(ctx, DeviceSort.CREATED), Icons.Default.Today),
            ),
            selected = q.sort,
            onPick = { actions.onQuery(q.copy(sort = it)) },
            onDismiss = { sorting = false },
        )
    }
}

private fun sortLabel(ctx: Context, sort: DeviceSort): String = ctx.getString(
    when (sort) {
        DeviceSort.NAME -> R.string.admin_dev_sort_name
        DeviceSort.LAST_SEEN -> R.string.admin_dev_sort_last_seen
        DeviceSort.CREATED -> R.string.admin_dev_sort_created
    }
)

@Composable
private fun SelectionBar(ui: DevicesUi, shown: List<ApiDevice>, actions: DevicesListActions) {
    val ctx = LocalContext.current
    val selectable = shown.filter { !it.isShared }.map { it.pathId }
    val allSelected = selectable.isNotEmpty() && ui.selected.containsAll(selectable)
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onStopSelecting) { Icon(Icons.Default.Close, ctx.getString(R.string.admin_dev_select_stop)) }
        Text(
            ctx.resources.getQuantityString(R.plurals.admin_dev_selected, ui.selected.size, ui.selected.size),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { actions.onSelectOnly(if (allSelected) emptyList() else selectable) }) {
            Icon(
                if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                ctx.getString(if (allSelected) R.string.admin_dev_select_none else R.string.admin_dev_select_all),
            )
        }
        Button(onClick = actions.onBulkTags, enabled = ui.selected.isNotEmpty(), shape = MaterialTheme.shapes.medium) {
            Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(ctx.getString(R.string.admin_dev_select_tags))
        }
    }
}

@Composable
private fun SweepLine(sweep: RouteSweep, routersOn: Boolean) {
    val ctx = LocalContext.current
    when {
        sweep.running -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(ctx.getString(R.string.admin_dev_routes_reading, sweep.done, sweep.total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { if (sweep.total == 0) 0f else sweep.done.toFloat() / sweep.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        routersOn && sweep.error != null -> HelpText(
            ctx.getString(R.string.admin_dev_routes_partial, ConsoleText.error(ctx, sweep.error)),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * One device: its OS, name, presence in words, address, badges and tags. In selection a checkbox
 * leads, and a shared-in device cannot be picked — it is not this tailnet's to change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceCard(d: ApiDevice, badges: List<DeviceBadge>, now: Long, selecting: Boolean, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val selectable = !d.isShared
    val interaction = if (selecting) Modifier.toggleable(value = selected, enabled = selectable, role = Role.Checkbox) { onClick() }
    else Modifier.clickable(onClick = onClick)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).then(interaction),
        shape = MaterialTheme.shapes.large,
        // An outline rather than a fill: the badges and tags keep their contrast.
        color = scheme.surfaceContainer,
        border = if (selected) BorderStroke(2.dp, scheme.primary) else null,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = null, enabled = selectable, modifier = Modifier.padding(top = 10.dp, end = 10.dp))
            }
            OsAvatar(d.os)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(d.shortName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PresenceLine(d, now, systemLine(d))
                (d.ipv4 ?: d.addresses.firstOrNull())?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                }
                if (badges.isNotEmpty() || d.tags.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        badges.forEach { BadgeChip(it) }
                        d.tags.forEach { TagChip(it) }
                    }
                }
            }
        }
    }
}
