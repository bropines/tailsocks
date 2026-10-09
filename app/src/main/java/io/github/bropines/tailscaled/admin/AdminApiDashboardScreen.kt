package io.github.bropines.tailscaled.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminArea
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
import io.github.bropines.tailscaled.admin.safety.ReadOnlyBanner
import io.github.bropines.tailscaled.admin.settings.SettingsTab
import io.github.bropines.tailscaled.admin.webhooks.WebhooksTab
import io.github.bropines.tailscaled.core.ScrollableSlidingSegmentedChips
import io.github.bropines.tailscaled.ui.AppTopBar
import io.github.bropines.tailscaled.ui.PickerOption
import io.github.bropines.tailscaled.ui.PickerSheet
import io.github.bropines.tailscaled.ui.rememberFullSheetState
import kotlinx.coroutines.launch

private const val PICK_ADD = "\u0000add"
private const val PICK_EDIT = "\u0000edit"

/**
 * The console's tabs over the active profile. Holds only what is on screen — which sheet is
 * open, which tab — and reads everything else from [state]; every action goes to [vm], which
 * is null in previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboard(state: ConsoleState, vm: AdminConsoleViewModel?, onBack: () -> Unit, startTab: ConsoleTab = ConsoleTab.DEVICES) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tabs = state.tabs
    val tabLabels = tabs.map {
        ctx.getString(
            when (it) {
                ConsoleTab.ATTENTION -> R.string.admin_attention_tab
                ConsoleTab.DEVICES -> R.string.admin_tab_devices
                ConsoleTab.DNS -> R.string.admin_tab_dns
                ConsoleTab.POLICY -> R.string.admin_cfg_tab_policy
                ConsoleTab.USERS -> R.string.admin_tab_users
                ConsoleTab.SERVICES -> R.string.admin_tab_services
                ConsoleTab.WEBHOOKS -> R.string.admin_tab_webhooks
                ConsoleTab.LOGS -> R.string.admin_tab_logs
                ConsoleTab.WEB -> R.string.admin_tab_web_links
                ConsoleTab.SETTINGS -> R.string.admin_tab_settings
            }
        )
    }
    val pagerState = rememberPagerState(initialPage = tabs.indexOf(startTab).coerceAtLeast(0), pageCount = { tabs.size })

    var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedUserId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedServiceName by rememberSaveable { mutableStateOf<String?>(null) }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var showCreateKey by rememberSaveable { mutableStateOf(false) }
    var showProfiles by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage, tabs) { tabs.getOrNull(pagerState.currentPage)?.let { vm?.refresh(it) } }
    LaunchedEffect(showKeys) { if (showKeys) vm?.refreshKeys(force = false) }

    val profile = state.active
    val selfNode = state.self.nodeId.takeIf { state.phoneInTailnet }
    fun isOwnUser(u: ApiUser) = (state.caps?.ownUserId != null && u.id == state.caps.ownUserId) ||
        (state.phoneInTailnet && state.self.loginName != null && u.loginName.equals(state.self.loginName, ignoreCase = true))

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
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableSlidingSegmentedChips(
                options = tabLabels,
                selectedIndex = pagerState.currentPage,
                onOptionSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                height = 40.dp,
            )
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
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
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
                            onOpenDevice = { selectedDeviceId = it.pathId },
                            onOpenUser = { selectedUserId = it.id },
                            onOpenKeys = { showKeys = true },
                            onReplaceCredential = { vm?.profiles?.editActive() },
                        )
                        ConsoleTab.DEVICES -> DevicesTab(
                            state = state,
                            vm = vm,
                            selfNodeId = selfNode,
                            onDeviceClick = { selectedDeviceId = it.pathId },
                        )
                        ConsoleTab.DNS -> DnsTab(state, vm)
                        ConsoleTab.POLICY -> PolicyTab(state, vm)
                        ConsoleTab.USERS -> UsersTabContent(
                            state = state.users,
                            isOwn = ::isOwnUser,
                            onRetry = { vm?.refresh(tab, force = true) },
                            onUserClick = { selectedUserId = it.id },
                        )
                        ConsoleTab.SERVICES -> ServicesTabContent(
                            state = state.services,
                            onRetry = { vm?.refresh(tab, force = true) },
                            onServiceClick = { selectedServiceName = it.name },
                        )
                        ConsoleTab.WEBHOOKS -> WebhooksTab(state, vm)
                        ConsoleTab.LOGS -> AdminApiLogsTabContent(
                            tailnetLog = state.tailnetLog,
                            daysRange = state.tailnetLogDays,
                            onDaysRangeChange = { vm?.setTailnetLogDays(it) },
                            onRetry = { vm?.refresh(tab, force = true) },
                            localLog = state.localLog,
                            onClearLocal = { vm?.clearLocalLog() },
                        )
                        ConsoleTab.WEB -> AdminApiWebTabContent()
                        ConsoleTab.SETTINGS -> SettingsTab(state, vm, onManageKeys = { showKeys = true })
                    }
                }
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

    selectedDeviceId?.let { id ->
        val device = state.devices.value?.firstOrNull { it.pathId == id }
        // Gone after a refresh (deleted, say): the sheet closes with it.
        LaunchedEffect(device == null) { if (device == null) selectedDeviceId = null }
        if (device != null) {
            DeviceDetailSheet(
                device = device,
                state = state,
                vm = vm,
                selfNodeId = selfNode,
                onDismiss = { selectedDeviceId = null },
            )
        }
    }

    selectedUserId?.let { id ->
        val user = state.users.value?.firstOrNull { it.id == id }
        // Gone after a refresh (deleted, say): the sheet closes with it.
        LaunchedEffect(user == null) { if (user == null) selectedUserId = null }
        if (user != null) {
            UserDetailBottomSheet(
                user = user,
                own = isOwnUser(user),
                canWrite = state.canWrite(AdminArea.USERS),
                onDismiss = { selectedUserId = null },
                onRoleChange = { vm?.setUserRole(user, it) },
                onApprove = { vm?.approveUser(user) },
                onSuspend = { vm?.suspendUser(user) },
                onRestore = { vm?.restoreUser(user) },
                onDelete = { vm?.deleteUser(user) },
            )
        }
    }

    selectedServiceName?.let { name ->
        val service = state.services.value?.firstOrNull { it.name == name }
        // Gone after a refresh (deleted, say): the sheet closes with it.
        LaunchedEffect(service == null) { if (service == null) selectedServiceName = null }
        if (service != null) {
            ServiceDetailBottomSheet(
                service = service,
                hosts = state.serviceHosts[service.name],
                allDevices = state.devices.value.orEmpty(),
                canWrite = state.canWrite(AdminArea.SERVICES),
                onLoadHosts = { vm?.loadServiceHosts(service) },
                onSetHost = { deviceId, deviceName, approved -> vm?.setServiceHost(service, deviceId, deviceName, approved) },
                onDismiss = { selectedServiceName = null },
            )
        }
    }

    if (showCreateKey) {
        CreateKeyDialog(
            policyTags = state.policyTags,
            onDismiss = { showCreateKey = false },
            onGenerate = { request ->
                showCreateKey = false
                vm?.createAuthKey(request)
            },
        )
    }

    if (showKeys) {
        val sheetState = rememberFullSheetState()
        ModalBottomSheet(onDismissRequest = { showKeys = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ctx.getString(R.string.admin_settings_auth_keys_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showKeys = false }) { Icon(Icons.Default.Close, contentDescription = ctx.getString(R.string.action_close)) }
                }
                HorizontalDivider()
                KeysTabContent(
                    state = state.keys,
                    ownKeyId = state.caps?.ownKeyId,
                    canWrite = state.canWrite(AdminArea.AUTH_KEYS),
                    onRetry = { vm?.refreshKeys() },
                    onRevokeClick = { vm?.revokeKey(it) },
                    onCreateKeyClick = { showCreateKey = true },
                )
            }
        }
    }
}

private fun loadableFor(state: ConsoleState, tab: ConsoleTab): Loadable<*>? = when (tab) {
    ConsoleTab.ATTENTION -> state.devices
    ConsoleTab.DEVICES -> state.devices
    ConsoleTab.DNS -> state.dns
    ConsoleTab.POLICY -> state.policy.file
    ConsoleTab.USERS -> state.users
    ConsoleTab.SERVICES -> state.services
    ConsoleTab.WEBHOOKS -> state.webhooks
    ConsoleTab.LOGS -> state.tailnetLog
    ConsoleTab.WEB -> null
    ConsoleTab.SETTINGS -> state.settings
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
