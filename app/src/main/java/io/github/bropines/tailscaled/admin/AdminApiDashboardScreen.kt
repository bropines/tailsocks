package io.github.bropines.tailscaled.admin

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiService
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.attention.AttentionTab
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.CredentialProblem
import io.github.bropines.tailscaled.admin.console.Loadable
import io.github.bropines.tailscaled.admin.console.WriteBlock
import io.github.bropines.tailscaled.admin.devices.DeviceDetailSheet
import io.github.bropines.tailscaled.admin.devices.DevicesTab
import io.github.bropines.tailscaled.admin.dns.DnsTab
import io.github.bropines.tailscaled.admin.policy.PolicyTab
import io.github.bropines.tailscaled.admin.headscale.HeadscaleTab
import io.github.bropines.tailscaled.admin.headscale.HeadscaleUiState
import io.github.bropines.tailscaled.admin.logs.LogsTab
import io.github.bropines.tailscaled.admin.safety.ReadOnlyBanner
import io.github.bropines.tailscaled.admin.settings.SettingsTab
import io.github.bropines.tailscaled.admin.webhooks.WebhooksTab
import io.github.bropines.tailscaled.admin.keys.KeysTab
import io.github.bropines.tailscaled.admin.safety.ReadOnlyBanner
import io.github.bropines.tailscaled.admin.users.UsersTab
import io.github.bropines.tailscaled.admin.services.ServiceEditorDialog
import io.github.bropines.tailscaled.admin.services.ServiceSheet
import io.github.bropines.tailscaled.admin.services.ServicesTab
import io.github.bropines.tailscaled.core.ScrollableSlidingSegmentedChips
import io.github.bropines.tailscaled.ui.AppTopBar
import io.github.bropines.tailscaled.ui.ListDetailLayout
import io.github.bropines.tailscaled.ui.PaneEmptyState
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet
import io.github.bropines.tailscaled.ui.rememberWindowLayout
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val PICK_ADD = "\u0000add"
private const val PICK_EDIT = "\u0000edit"

/**
 * The console's tabs over the active profile. Holds only what is on screen — which sheet is
 * open, which tab — and reads everything else from [state]; every action goes to [vm], which
 * is null in previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboard(
    state: ConsoleState,
    vm: AdminConsoleViewModel?,
    onBack: () -> Unit,
    startTab: ConsoleTab = ConsoleTab.DEVICES,
    /** The Headscale tab's state where there is no ViewModel (previews). */
    headscaleDemo: HeadscaleUiState? = null,
    /** A device to open on: its sheet on a phone, the pane beside the list on a large window (previews). */
    initialDeviceId: String? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tabs = state.tabs
    val tabLabels = tabs.map { ctx.getString(tabLabel(it)) }
    val pagerState = rememberPagerState(initialPage = tabs.indexOf(startTab).coerceAtLeast(0), pageCount = { tabs.size })

    var selectedDeviceId by rememberSaveable { mutableStateOf(initialDeviceId) }
    var selectedServiceName by rememberSaveable { mutableStateOf<String?>(null) }
    var showProfiles by rememberSaveable { mutableStateOf(false) }
    var showCreateService by rememberSaveable { mutableStateOf(false) }
    var editingServiceName by rememberSaveable { mutableStateOf<String?>(null) }
    val hsState = vm?.headscale?.state?.collectAsState()?.value ?: headscaleDemo ?: HeadscaleUiState()

    // The page the pager came to rest on: a swipe or a jump across several tabs loads none of those in between.
    LaunchedEffect(pagerState.settledPage, tabs) { tabs.getOrNull(pagerState.settledPage)?.let { vm?.refresh(it) } }

    val profile = state.active
    val selfNode = state.self.nodeId.takeIf { state.phoneInTailnet }

    // A large window: the tabs in a rail beside the content, and the list tabs' details in a
    // pane beside their lists instead of a sheet over them (see AdminAdaptive.kt). A phone
    // keeps the chip row, the swipe between tabs and the sheets.
    val window = rememberWindowLayout()
    val rail = window.adminRail
    val twoPane = window.listDetail
    // Straight there from a rail: the pages between are not slid through, and not loaded.
    fun goTo(tab: ConsoleTab) {
        val page = tabs.indexOf(tab).coerceAtLeast(0)
        scope.launch { if (rail) pagerState.scrollToPage(page) else pagerState.animateScrollToPage(page) }
    }

    // One device, as the sheet over the list on a phone and as the pane beside it on a large window.
    val deviceDetail: @Composable (ApiDevice) -> Unit = { device ->
        DeviceDetailSheet(
            device = device,
            state = state,
            vm = vm,
            selfNodeId = selfNode,
            onDismiss = { selectedDeviceId = null },
        )
    }
    val serviceDetail: @Composable (ApiService) -> Unit = { service ->
        ServiceSheet(
            service = service,
            hosts = state.serviceHosts[service.name],
            allDevices = state.devices.value.orEmpty(),
            canWrite = state.canWrite(AdminArea.SERVICES),
            onLoadHosts = { vm?.loadServiceHosts(service) },
            onSetHost = { deviceId, deviceName, approved -> vm?.setServiceHost(service, deviceId, deviceName, approved) },
            onEdit = { editingServiceName = service.name },
            onDelete = { vm?.deleteService(service) },
            onDismiss = { selectedServiceName = null },
        )
    }

    // What stands over the tabs' content: a credential that cannot be used, why nothing can be
    // changed, and that a tab shows the copy kept on the phone.
    val notices: @Composable () -> Unit = {
        state.credentialProblem?.let { problem ->
            CredentialProblemCard(problem) { vm?.profiles?.editActive() }
        }
        when (state.writeBlock) {
            WriteBlock.NO_SCREEN_LOCK -> ReadOnlyBanner(
                ctx.getString(R.string.admin2_readonly_no_lock_title), ctx.getString(R.string.admin2_readonly_no_lock_desc),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            WriteBlock.READ_ONLY_PROFILE -> ReadOnlyBanner(
                ctx.getString(R.string.admin2_readonly_profile_title), ctx.getString(R.string.admin2_readonly_profile_desc),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), Icons.Default.VisibilityOff,
            )
            null -> Unit
        }
        tabs.getOrNull(pagerState.currentPage)?.let { loadableFor(state, it) }?.takeIf { it.fromDisk }?.let { CachedCopyNote(it) }
    }

    val pages: @Composable (Modifier) -> Unit = { modifier ->
        // Beside a rail the rail turns the tabs; a swipe across a pane is the pane's own.
        HorizontalPager(state = pagerState, modifier = modifier, userScrollEnabled = !rail) { page ->
            val tab = tabs[page]
            PullToRefreshBox(
                isRefreshing = loadableFor(state, tab)?.loading == true,
                onRefresh = { vm?.refresh(tab, force = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (tab) {
                    ConsoleTab.ATTENTION -> AttentionTab(
                        state = state,
                        vm = vm,
                        onRetry = { vm?.refresh(tab, force = true) },
                        // Two-pane, a device opens beside the devices list, as a user opens on the Users tab.
                        onOpenDevice = {
                            selectedDeviceId = it.pathId
                            if (twoPane && ConsoleTab.DEVICES in tabs) goTo(ConsoleTab.DEVICES)
                        },
                        onOpenUser = { u -> vm?.usersTab?.openUser?.value = u.id; goTo(ConsoleTab.USERS) },
                        onOpenKeys = { goTo(ConsoleTab.KEYS) },
                        onReplaceCredential = { vm?.profiles?.editActive() },
                    )
                    ConsoleTab.DEVICES -> DevicesTab(
                        state = state,
                        vm = vm,
                        selfNodeId = selfNode,
                        onDeviceClick = { selectedDeviceId = it.pathId },
                        paneDeviceId = selectedDeviceId,
                        paneDetail = deviceDetail,
                    )
                    ConsoleTab.DNS -> DnsTab(state, vm)
                    ConsoleTab.POLICY -> PolicyTab(state, vm)
                    ConsoleTab.USERS -> UsersTab(state, vm)
                    ConsoleTab.KEYS -> KeysTab(state, vm)
                    ConsoleTab.SERVICES -> {
                        val list: @Composable (ApiService?) -> Unit = { shown ->
                            ServicesTab(
                                state = state.services,
                                canWrite = state.canWrite(AdminArea.SERVICES),
                                onRetry = { vm?.refresh(tab, force = true) },
                                onServiceClick = { selectedServiceName = it.name },
                                onCreate = { showCreateService = true },
                                shownName = shown?.name,
                            )
                        }
                        if (twoPane) {
                            // The service picked, or the first row: a pane is never empty while there is one.
                            val services = state.services.value.orEmpty()
                            val shown = services.firstOrNull { it.name == selectedServiceName } ?: services.minByOrNull { it.name }
                            ListDetailLayout(
                                window = window,
                                twoPane = true,
                                modifier = Modifier.fillMaxSize(),
                                list = { list(shown) },
                                detail = {
                                    if (shown != null) key(shown.name) { serviceDetail(shown) }
                                    else PaneEmptyState(Icons.Default.CloudQueue, ctx.getString(R.string.tablet_admin_service_pane_empty))
                                },
                            )
                        } else list(null)
                    }
                    ConsoleTab.WEBHOOKS -> WebhooksTab(state, vm)
                    ConsoleTab.LOGS -> LogsTab(
                        tailnetLog = state.tailnetLog,
                        query = state.tailnetLogQuery,
                        onQuery = { vm?.setTailnetLogQuery(it) },
                        onRetry = { vm?.refresh(tab, force = true) },
                        localLog = state.localLog,
                        onClearLocal = { vm?.clearLocalLog() },
                    )
                    ConsoleTab.WEB -> AdminApiWebTabContent()
                    ConsoleTab.SETTINGS -> SettingsTab(
                        state,
                        vm,
                        onManageKeys = { goTo(ConsoleTab.KEYS) },
                    )
                    ConsoleTab.SERVER -> HeadscaleTab(
                        state,
                        hsState,
                        vm,
                        onManageKeys = { goTo(ConsoleTab.KEYS) },
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = ctx.getString(R.string.admin_console_title),
                subtitle = profile?.let {
                    it.displayName + if (it.readOnly) " · " + ctx.getString(R.string.admin2_profile_read_only_badge) else ""
                },
                onBack = onBack,
                onTitleClick = { showProfiles = true },
                actions = {
                    IconButton(onClick = { tabs.getOrNull(pagerState.currentPage)?.let { vm?.refresh(it, force = true) } }) {
                        Icon(Icons.Default.Refresh, contentDescription = ctx.getString(R.string.admin_cd_refresh))
                    }
                    IconButton(onClick = { vm?.profiles?.editActive() }) {
                        Icon(Icons.Default.ManageAccounts, contentDescription = ctx.getString(R.string.admin2_profile_edit))
                    }
                },
            )
        }
    ) { padding ->
        if (rail) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                AdminTabRail(
                    tabs = tabs,
                    selected = pagerState.currentPage,
                    wide = window.adminRailWide,
                    onSelect = { scope.launch { pagerState.scrollToPage(it) } },
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    notices()
                    pages(Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                ScrollableSlidingSegmentedChips(
                    options = tabLabels,
                    selectedIndex = pagerState.currentPage,
                    onOptionSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    height = 40.dp,
                )
                notices()
                pages(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    if (showProfiles) {
        PickerSheet(
            title = ctx.getString(R.string.admin2_profile_switch_title),
            options = state.profiles.map { p ->
                PickerOption(p.id, p.displayName, supporting = p.tailnetDnsName.takeIf { it.isNotBlank() && it != p.displayName })
            } + PickerOption(PICK_EDIT, ctx.getString(R.string.admin2_profile_edit), Icons.Default.Edit) +
                PickerOption(PICK_ADD, ctx.getString(R.string.admin2_profile_add), Icons.Default.Add),
            selected = profile?.id,
            onPick = { id ->
                when (id) {
                    PICK_ADD -> vm?.profiles?.startNew()
                    PICK_EDIT -> vm?.profiles?.editActive()
                    else -> vm?.profiles?.switchTo(id)
                }
            },
            onDismiss = { showProfiles = false },
        )
    }

    // A sheet on a phone; two-pane, the Devices and Services tabs draw these in their panes.
    selectedDeviceId?.let { id ->
        val device = state.devices.value?.firstOrNull { it.pathId == id }
        // Gone after a refresh (deleted, say): the sheet closes with it.
        LaunchedEffect(device == null) { if (device == null) selectedDeviceId = null }
        if (device != null && !twoPane) deviceDetail(device)
    }

    selectedServiceName?.let { name ->
        val service = state.services.value?.firstOrNull { it.name == name }
        // Gone after a refresh (deleted, say): the sheet closes with it.
        LaunchedEffect(service == null) { if (service == null) selectedServiceName = null }
        if (service != null && !twoPane) serviceDetail(service)
    }

    if (showCreateService) {
        ServiceEditorDialog(null, state.policyTags, onDismiss = { showCreateService = false }) { vm?.createService(it) }
    }
    editingServiceName?.let { name ->
        val service = state.services.value?.firstOrNull { it.name == name }
        LaunchedEffect(service == null) { if (service == null) editingServiceName = null }
        if (service != null) ServiceEditorDialog(service, state.policyTags, onDismiss = { editingServiceName = null }) { vm?.updateService(service, it) }
    }

}

private fun loadableFor(state: ConsoleState, tab: ConsoleTab): Loadable<*>? = when (tab) {
    ConsoleTab.ATTENTION -> state.devices
    ConsoleTab.DEVICES -> state.devices
    ConsoleTab.DNS -> state.dns
    ConsoleTab.POLICY -> state.policy.file
    ConsoleTab.USERS -> state.users
    ConsoleTab.KEYS -> state.keys
    ConsoleTab.SERVICES -> state.services
    ConsoleTab.WEBHOOKS -> state.webhooks
    ConsoleTab.LOGS -> state.tailnetLog
    ConsoleTab.WEB -> null
    ConsoleTab.SETTINGS -> state.settings
    ConsoleTab.SERVER -> state.users
}

/** One line over a tab that still shows the copy kept on the phone: from when, and whether the server answered. */
@Composable
private fun CachedCopyNote(copy: Loadable<*>) {
    val ctx = LocalContext.current
    val failed = copy.error != null && !copy.loading
    val at = remember(copy.loadedAt) { savedAt(ctx, copy.loadedAt) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (failed) Icons.Default.CloudOff else Icons.Default.History, null,
            Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            ctx.getString(if (failed) R.string.admin_cache_note_failed else R.string.admin_cache_note_loading, at),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The time alone for today, the date with it otherwise; in the app's language. */
private fun savedAt(ctx: Context, at: Long): String {
    val locale = ctx.resources.configuration.locales[0]
    val today = DateUtils.isToday(at)
    val fmt = if (today) DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
    return fmt.format(Date(at))
}

@Composable
private fun CredentialProblemCard(problem: CredentialProblem, onFix: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    ctx.getString(if (problem == CredentialProblem.UNREADABLE) R.string.admin2_credential_unreadable else R.string.admin2_credential_missing),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = onFix, modifier = Modifier.align(Alignment.End).padding(top = 8.dp), shape = MaterialTheme.shapes.medium) {
                Text(ctx.getString(R.string.admin2_credential_fix))
            }
        }
    }
}
