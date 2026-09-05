package io.github.bropines.tailscaled.admin
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.Locale

object AdminApiLogsCache {
    var auditLogs: List<ApiAuditLogEntry> = emptyList()
    var daysRange: Int = 7
    var lastFetchTime: Long = 0L
    var lastFetchedRange: Int = -1

    fun clear() {
        auditLogs = emptyList()
        daysRange = 7
        lastFetchTime = 0L
        lastFetchedRange = -1
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminApiDashboardScreen(
    token: String,
    tailnet: String,
    clientId: String,
    clientSecret: String,
    proxyMode: String,
    proxyHost: String,
    proxyPort: Int,
    proxyUser: String,
    proxyPass: String,
    onUpdateProxy: (String, String, Int, String, String) -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tabDevices = stringResource(R.string.admin_tab_devices)
    val tabDns = stringResource(R.string.admin_tab_dns)
    val tabUsers = stringResource(R.string.admin_tab_users)
    val tabServices = stringResource(R.string.admin_tab_services)
    val tabWebhooks = stringResource(R.string.admin_tab_webhooks)
    val tabLogs = stringResource(R.string.admin_tab_logs)
    val tabWebLinks = stringResource(R.string.admin_tab_web_links)
    val tabSettings = stringResource(R.string.admin_tab_settings)
    val tabs = listOf(tabDevices, tabDns, tabUsers, tabServices, tabWebhooks, tabLogs, tabWebLinks, tabSettings)
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    var showKeysManagement by remember { mutableStateOf(false) }

    // Fetch SOCKS5 and Control Plane Proxy settings from global configurations
    val localSocksAddr = remember { GlobalSettings.getString(context, "socks5", "127.0.0.1:48115") }
    val localSocksUser = remember { GlobalSettings.getString(context, "socks5_user", "") }
    val localSocksPass = remember { GlobalSettings.getString(context, "socks5_pass", "") }
    val controlProxyUrl = remember { GlobalSettings.getControlProxyUrl(context) }

    val client = remember(token, tailnet, proxyMode, proxyHost, proxyPort, proxyUser, proxyPass, localSocksAddr, localSocksUser, localSocksPass, clientId, clientSecret, controlProxyUrl) {
        TailscaleApiClient(
            token = token,
            tailnetName = tailnet,
            proxyMode = proxyMode,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUser = proxyUser,
            proxyPass = proxyPass,
            localSocksAddr = localSocksAddr,
            localSocksUser = localSocksUser,
            localSocksPass = localSocksPass,
            clientId = clientId,
            clientSecret = clientSecret,
            controlProxyUrl = controlProxyUrl
        )
    }

    // State holders
    var isRefreshing by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<ApiDevice>>(emptyList()) }
    var keys by remember { mutableStateOf<List<ApiKeyInfo>>(emptyList()) }
    var magicDnsEnabled by remember { mutableStateOf(false) }
    var dnsNameservers by remember { mutableStateOf<List<String>>(emptyList()) }
    var splitDns by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var dnsSearchPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var users by remember { mutableStateOf<List<ApiUser>>(emptyList()) }
    var tailnetSettings by remember { mutableStateOf<TailnetSettings?>(null) }
    var selectedUser by remember { mutableStateOf<ApiUser?>(null) }
    var allTailnetTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var vipServices by remember { mutableStateOf<List<VIPServiceInfo>>(emptyList()) }
    var webhooks by remember { mutableStateOf<List<WebhookEndpoint>>(emptyList()) }
    var selectedServiceInfo by remember { mutableStateOf<VIPServiceInfo?>(null) }
    var showCreateWebhookDialog by remember { mutableStateOf(false) }

    var auditLogs by remember { mutableStateOf<List<ApiAuditLogEntry>>(AdminApiLogsCache.auditLogs) }
    var auditLogsDaysRange by remember { mutableIntStateOf(AdminApiLogsCache.daysRange) }

    // Cache Timestamps
    var lastDevicesFetch by remember { mutableLongStateOf(0L) }
    var lastKeysFetch by remember { mutableLongStateOf(0L) }
    var lastDnsFetch by remember { mutableLongStateOf(0L) }
    var lastUsersFetch by remember { mutableLongStateOf(0L) }
    var lastServicesFetch by remember { mutableLongStateOf(0L) }
    var lastWebhooksFetch by remember { mutableLongStateOf(0L) }
    var lastSettingsFetch by remember { mutableLongStateOf(0L) }
    var lastAuditLogsFetch by remember { mutableLongStateOf(AdminApiLogsCache.lastFetchTime) }
    var lastFetchedAuditLogsRange by remember { mutableIntStateOf(AdminApiLogsCache.lastFetchedRange) }

    var selectedDevice by remember { mutableStateOf<ApiDevice?>(null) }
    var showCreateKeyDialog by remember { mutableStateOf(false) }
    var generatedKeyToShow by remember { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showProxySettingsDialog by remember { mutableStateOf(false) }

    fun getRfc3339Time(timeMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timeMs))
    }

    fun refreshTab(tabIndex: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val cacheDuration = 60 * 1000L // 60 seconds throttle
        
        isRefreshing = true
        scope.launch(Dispatchers.IO) {
            try {
                when (tabIndex) {
                    0 -> {
                        if (force || now - lastDevicesFetch >= cacheDuration || devices.isEmpty()) {
                            val list = client.listDevices()
                            val tagsList = client.getTailnetTags()
                            withContext(Dispatchers.Main) {
                                devices = list
                                allTailnetTags = tagsList
                                lastDevicesFetch = now
                            }
                        }
                    }
                    1 -> {
                        if (force || now - lastDnsFetch >= cacheDuration || dnsNameservers.isEmpty()) {
                            val pref = client.getDnsPreferences()
                            val ns = client.getDnsNameservers()
                            val sdns = client.getSplitDns()
                            val sp = client.listDnsSearchPaths()
                            withContext(Dispatchers.Main) {
                                magicDnsEnabled = pref.magicDNS
                                dnsNameservers = ns
                                splitDns = sdns
                                dnsSearchPaths = sp
                                lastDnsFetch = now
                            }
                        }
                    }
                    2 -> {
                        if (force || now - lastUsersFetch >= cacheDuration || users.isEmpty()) {
                            val list = client.listUsers()
                            withContext(Dispatchers.Main) {
                                users = list
                                lastUsersFetch = now
                            }
                        }
                    }
                    3 -> {
                        if (force || now - lastServicesFetch >= cacheDuration || vipServices.isEmpty()) {
                            val list = client.listTailnetServices()
                            withContext(Dispatchers.Main) {
                                vipServices = list
                                lastServicesFetch = now
                            }
                        }
                    }
                    4 -> {
                        if (force || now - lastWebhooksFetch >= cacheDuration || webhooks.isEmpty()) {
                            val list = client.listWebhooks()
                            withContext(Dispatchers.Main) {
                                webhooks = list
                                lastWebhooksFetch = now
                            }
                        }
                    }
                    5 -> {
                        if (force || now - lastAuditLogsFetch >= cacheDuration || auditLogs.isEmpty() || lastFetchedAuditLogsRange != auditLogsDaysRange) {
                            val nowMs = System.currentTimeMillis()
                            val end = getRfc3339Time(nowMs)
                            val start = getRfc3339Time(nowMs - auditLogsDaysRange * 24 * 60 * 60 * 1000L)
                            val logsList = client.getAuditLogs(start, end)
                            withContext(Dispatchers.Main) {
                                auditLogs = logsList
                                lastAuditLogsFetch = now
                                lastFetchedAuditLogsRange = auditLogsDaysRange
                                AdminApiLogsCache.auditLogs = logsList
                                AdminApiLogsCache.lastFetchTime = now
                                AdminApiLogsCache.lastFetchedRange = auditLogsDaysRange
                            }
                        }
                    }
                    6 -> {
                        // Web Links tab (static links)
                    }
                    7 -> {
                        if (force || now - lastSettingsFetch >= cacheDuration || tailnetSettings == null) {
                            val s = client.getTailnetSettings()
                            withContext(Dispatchers.Main) {
                                tailnetSettings = s
                                lastSettingsFetch = now
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { isRefreshing = false }
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        refreshTab(pagerState.currentPage, force = false)
    }

    LaunchedEffect(showKeysManagement) {
        if (showKeysManagement) {
            scope.launch(Dispatchers.IO) {
                try {
                    val list = client.listKeys()
                    withContext(Dispatchers.Main) {
                        keys = list.sortedBy { it.revoked == true }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.admin_error_loading_keys_format, e.message), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(stringResource(R.string.admin_console_title)) 
                        Text(tailnet, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTab(pagerState.currentPage, force = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.admin_cd_refresh))
                    }
                    IconButton(onClick = { showProxySettingsDialog = true }) {
                        Icon(Icons.Default.Router, contentDescription = stringResource(R.string.admin_cd_proxy_settings))
                    }
                    IconButton(
                        onClick = { showDisconnectConfirm = true }
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = stringResource(R.string.admin_cd_disconnect_api), tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableSlidingSegmentedChips(
                options = tabs,
                selectedIndex = pagerState.currentPage,
                onOptionSelected = { index ->
                    scope.launch {
                        pagerState.animateScrollToPage(index)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                height = 40.dp
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { refreshTab(page, force = true) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (page) {
                        0 -> DevicesTabContent(
                            devices = devices,
                            onDeviceClick = { selectedDevice = it }
                        )
                        1 -> DnsTabContent(
                            magicDns = magicDnsEnabled,
                            nameservers = dnsNameservers,
                            splitDns = splitDns,
                            searchPaths = dnsSearchPaths,
                            onMagicDnsChanged = { enabled ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.updateDnsPreferences(enabled)
                                        withContext(Dispatchers.Main) {
                                            magicDnsEnabled = enabled
                                            Toast.makeText(context, context.getString(R.string.admin_settings_magic_dns_updated), Toast.LENGTH_SHORT).show()
                                            refreshTab(1, force = true)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onApplyNameservers = { updatedList ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.setDnsNameservers(updatedList)
                                        withContext(Dispatchers.Main) {
                                            dnsNameservers = updatedList
                                            Toast.makeText(context, context.getString(R.string.admin_settings_ns_applied), Toast.LENGTH_SHORT).show()
                                            refreshTab(1, force = true)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onUpdateSplitDns = { domain, nsList ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.updateSplitDns(domain, nsList)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.admin_settings_search_applied), Toast.LENGTH_SHORT).show()
                                            refreshTab(1, force = true)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onApplySearchPaths = { updatedPaths ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.setDnsSearchPaths(updatedPaths)
                                        withContext(Dispatchers.Main) {
                                            dnsSearchPaths = updatedPaths
                                            Toast.makeText(context, context.getString(R.string.admin_settings_search_applied), Toast.LENGTH_SHORT).show()
                                            refreshTab(1, force = true)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        )
                        2 -> UsersTabContent(
                            users = users,
                            onUserClick = { selectedUser = it }
                        )
                        3 -> ServicesTabContent(
                            services = vipServices,
                            onServiceClick = { selectedServiceInfo = it }
                        )
                        4 -> WebhooksTabContent(
                            webhooks = webhooks,
                            onCreateClick = { showCreateWebhookDialog = true },
                            onTestClick = { wh ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.testWebhook(wh.endpointId)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.admin_webhooks_test_sent), Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onDeleteClick = { wh ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        client.deleteWebhook(wh.endpointId)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.admin_webhooks_deleted), Toast.LENGTH_SHORT).show()
                                            refreshTab(4, force = true)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        )
                        5 -> AdminApiLogsTabContent(
                            auditLogs = auditLogs,
                            daysRange = auditLogsDaysRange,
                            onDaysRangeChange = { newRange ->
                                auditLogsDaysRange = newRange
                                AdminApiLogsCache.daysRange = newRange
                                refreshTab(5, force = true)
                            },
                            isLoading = auditLogs.isEmpty() && isRefreshing
                        )
                        6 -> AdminApiWebTabContent()
                        7 -> TailnetSettingsTabContent(
                            settings = tailnetSettings,
                            onApplySettings = { updatedSettings ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val res = client.updateTailnetSettings(updatedSettings)
                                        withContext(Dispatchers.Main) {
                                            tailnetSettings = res
                                            Toast.makeText(context, context.getString(R.string.admin_settings_updated), Toast.LENGTH_SHORT).show()
                                            refreshTab(7, force = true)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            onManageKeysClick = {
                                showKeysManagement = true
                            },
                            onBillingClick = {}
                        )
                    }
                }
            }
        }
    }

    if (showDisconnectConfirm) {
        // Strings come from the parent context, not stringResource() — see wrapContextWithLocale().
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(context.getString(R.string.admin_disconnect_title)) },
            text = { Text(context.getString(R.string.admin_disconnect_text, tailnet)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectConfirm = false
                        AdminApiLogsCache.clear()
                        onDisconnect()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(context.getString(R.string.action_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text(context.getString(R.string.action_cancel)) }
            }
        )
    }

    if (showProxySettingsDialog) {
        ProxySettingsDialog(
            initialProxyMode = proxyMode,
            initialProxyHost = proxyHost,
            initialProxyPort = proxyPort,
            initialProxyUser = proxyUser,
            initialProxyPass = proxyPass,
            onDismiss = { showProxySettingsDialog = false },
            onSave = { pmode, phost, pport, puser, ppass ->
                showProxySettingsDialog = false
                onUpdateProxy(pmode, phost, pport, puser, ppass)
                Toast.makeText(context, context.getString(R.string.admin_settings_updated), Toast.LENGTH_SHORT).show()
                refreshTab(pagerState.currentPage, force = true)
            }
        )
    }

    if (generatedKeyToShow != null) {
        // Strings resolved in the parent composition — see wrapContextWithLocale().
        val strAdminKeyGeneratedTitle = stringResource(R.string.admin_key_generated_title)
        val strAdminKeyGeneratedText = stringResource(R.string.admin_key_generated_text)
        val strAdminKeyCopyClose = stringResource(R.string.admin_key_copy_close)
        val strActionClose = stringResource(R.string.action_close)
        AlertDialog(
            onDismissRequest = { generatedKeyToShow = null },
            title = { Text(strAdminKeyGeneratedTitle) },
            text = {
                Column {
                    Text(strAdminKeyGeneratedText)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = generatedKeyToShow!!,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Tailscale Auth Key", generatedKeyToShow))
                        Toast.makeText(context, context.getString(R.string.copied_to_clipboard, "Key"), Toast.LENGTH_SHORT).show()
                        generatedKeyToShow = null
                        scope.launch(Dispatchers.IO) {
                            try {
                                val list = client.listKeys()
                                withContext(Dispatchers.Main) {
                                    keys = list.sortedBy { it.revoked == true }
                                }
                            } catch (e: Exception) {}
                        }
                    }
                ) {
                    Text(strAdminKeyCopyClose)
                }
            },
            dismissButton = {
                TextButton(onClick = { generatedKeyToShow = null }) { Text(strActionClose) }
            }
        )
    }

    // Modal detailed sheets
    selectedDevice?.let { device ->
        DeviceDetailBottomSheet(
            device = device,
            client = client,
            allTailnetTags = allTailnetTags,
            onDismiss = { selectedDevice = null },
            onRename = { newName ->
                scope.launch(Dispatchers.IO) {
                    try {
                        client.renameDevice(device.id, newName)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.admin_device_renamed), Toast.LENGTH_SHORT).show()
                            selectedDevice = null
                            refreshTab(0, force = true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onAuthorize = { authorized ->
                scope.launch(Dispatchers.IO) {
                    try {
                        client.setDeviceAuthorized(device.id, authorized)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(if (authorized) R.string.admin_device_authorized else R.string.admin_device_deauthorized), Toast.LENGTH_SHORT).show()
                            selectedDevice = null
                            refreshTab(0, force = true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onExpire = {
                scope.launch(Dispatchers.IO) {
                    try {
                        client.expireDevice(device.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.admin_device_key_expired), Toast.LENGTH_SHORT).show()
                            selectedDevice = null
                            refreshTab(0, force = true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onDelete = {
                scope.launch(Dispatchers.IO) {
                    try {
                        client.deleteDevice(device.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.admin_device_deleted), Toast.LENGTH_SHORT).show()
                            selectedDevice = null
                            refreshTab(0, force = true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onUpdateTags = { tagsList ->
                scope.launch(Dispatchers.IO) {
                    try {
                        client.setDeviceTags(device.id, tagsList)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.admin_device_tags_updated), Toast.LENGTH_SHORT).show()
                            selectedDevice = null
                            refreshTab(0, force = true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onToggleKeyExpiryDisabled = { disabled ->
                scope.launch(Dispatchers.IO) {
                    try {
                        client.setDeviceKeyExpiryDisabled(device.id, disabled)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.admin_device_key_expiry_updated), Toast.LENGTH_SHORT).show()
                            selectedDevice = null
                            refreshTab(0, force = true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    selectedUser?.let { user ->
        UserDetailBottomSheet(
            user = user,
            onDismiss = { selectedUser = null },
            onRoleChange = { newRole ->
                scope.launch(Dispatchers.IO) {
                    try {
                        client.changeUserRole(user.id, newRole)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.admin_users_role_updated, newRole.uppercase()), Toast.LENGTH_SHORT).show()
                            selectedUser = null
                            refreshTab(3, force = true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onApprove = {
                scope.launch(Dispatchers.IO) {
                    try {
                        client.approveUser(user.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.admin_users_approved), Toast.LENGTH_SHORT).show()
                            selectedUser = null
                            refreshTab(3, force = true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onSuspend = {
                scope.launch(Dispatchers.IO) {
                    try {
                        client.suspendUser(user.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.admin_users_suspended), Toast.LENGTH_SHORT).show()
                            selectedUser = null
                            refreshTab(3, force = true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onRestore = {
                scope.launch(Dispatchers.IO) {
                    try {
                        client.restoreUser(user.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.admin_users_restored), Toast.LENGTH_SHORT).show()
                            selectedUser = null
                            refreshTab(3, force = true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onDelete = {
                scope.launch(Dispatchers.IO) {
                    try {
                        client.deleteUser(user.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.admin_users_deleted), Toast.LENGTH_SHORT).show()
                            selectedUser = null
                            refreshTab(3, force = true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    if (showCreateKeyDialog) {
        CreateKeyDialog(
            onDismiss = { showCreateKeyDialog = false },
            onGenerate = { desc, expiry, ephemeral, preauth, tagsList ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val newKey = client.createKey(desc, expiry, ephemeral, preauth, tagsList)
                        withContext(Dispatchers.Main) {
                            showCreateKeyDialog = false
                            generatedKeyToShow = newKey.key
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    selectedServiceInfo?.let { service ->
        ServiceDetailBottomSheet(
            service = service,
            client = client,
            allDevices = devices,
            onDismiss = { selectedServiceInfo = null }
        )
    }

    if (showCreateWebhookDialog) {
        CreateWebhookDialog(
            onDismiss = { showCreateWebhookDialog = false },
            onSave = { url, events ->
                scope.launch(Dispatchers.IO) {
                    try {
                        client.createWebhook(url, events)
                        withContext(Dispatchers.Main) {
                            showCreateWebhookDialog = false
                            Toast.makeText(context, context.getString(R.string.admin_webhooks_added), Toast.LENGTH_SHORT).show()
                            refreshTab(4, force = true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    if (showKeysManagement) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // Strings resolved in the parent composition — see wrapContextWithLocale().
        val strAdminSettingsAuthKeysTitle = stringResource(R.string.admin_settings_auth_keys_title)
        val strActionClose = stringResource(R.string.action_close)
        ModalBottomSheet(
            onDismissRequest = { showKeysManagement = false },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strAdminSettingsAuthKeysTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { showKeysManagement = false }) {
                        Icon(Icons.Default.Close, contentDescription = strActionClose)
                    }
                }
                HorizontalDivider()
                Box(modifier = Modifier.weight(1f)) {
                    KeysTabContent(
                        keys = keys,
                        onRevokeClick = { key ->
                            scope.launch(Dispatchers.IO) {
                                try {
                                    client.revokeKey(key.id)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, context.getString(R.string.admin_keys_status_revoked), Toast.LENGTH_SHORT).show()
                                        val list = client.listKeys()
                                        keys = list.sortedBy { it.revoked == true }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, context.getString(R.string.error_generic, e.message), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        onCreateKeyClick = { showCreateKeyDialog = true }
                    )
                }
            }
        }
    }
}
