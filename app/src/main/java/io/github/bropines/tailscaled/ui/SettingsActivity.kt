package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R

import io.github.bropines.tailscaled.core.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** What the remote is sent to after Settings changes what it shows; see `focus` in [SettingsScreen]. */
private enum class SettingsFocus { HUB, PAGE, ROW }

/**
 * Work Settings starts that has to finish once started: a Root Mode switch and the
 * restart after a full restore. The screen's own scope is cancelled by Back, a rotation
 * or a language change; the blocking `su` calls of a switch ran to the end regardless,
 * and only the service restart at its end — the one suspending step — was dropped,
 * leaving the toggle on the new mode and no daemon behind it. Lives as long as the
 * process. A failure is logged: with no screen to report to, it must not end the process.
 */
internal val settingsWork = CoroutineScope(
    SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, e -> android.util.Log.e("SettingsActivity", "Settings background work failed", e) }
)

class SettingsActivity : ComponentActivity() {
    companion object {
        /** Open on this section instead of the list, e.g. "bypass" from a health banner. */
        const val EXTRA_OPEN_SECTION = "open_section"

        /** With it or alone: open on this row of a page, an entry of ui/SettingsCatalog.kt by its id. */
        const val EXTRA_OPEN_ROW = "open_row"

        /** Start with this typed into the search of the settings. */
        const val EXTRA_SEARCH = "search"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var appTheme by remember { mutableStateOf(GlobalSettings.getAppTheme(context)) }
            var themePreset by remember { mutableStateOf(GlobalSettings.getThemePreset(context)) }
            var dynamicColor by remember { mutableStateOf(GlobalSettings.isDynamicColorEnabled(context)) }
            var amoledMode by remember { mutableStateOf(GlobalSettings.getBoolean(context, "amoled_mode", false)) }

            TailSocksTheme(
                appTheme = appTheme,
                themePreset = themePreset,
                dynamicColorEnabled = dynamicColor,
                amoledModeEnabled = amoledMode
            ) {
                SettingsScreen(
                    onBack = { finish() },
                    initialSection = intent?.getStringExtra(EXTRA_OPEN_SECTION),
                    initialRow = intent?.getStringExtra(EXTRA_OPEN_ROW),
                    initialSearch = intent?.getStringExtra(EXTRA_SEARCH),
                    currentTheme = appTheme,
                    onThemeChange = { 
                        appTheme = it
                        GlobalSettings.setAppTheme(context, it)
                    },
                    currentPreset = themePreset,
                    onPresetChange = {
                        themePreset = it
                        GlobalSettings.setThemePreset(context, it)
                    },
                    currentDynamicColor = dynamicColor,
                    onDynamicColorChange = {
                        dynamicColor = it
                        GlobalSettings.setDynamicColorEnabled(context, it)
                    },
                    currentAmoledMode = amoledMode,
                    onAmoledModeChange = {
                        amoledMode = it
                        GlobalSettings.setBoolean(context, "amoled_mode", it)
                    }
                )
            }
        }
    }
}

/**
 * Where a settings list was left. Deliberately not snapshot state: it is read once, when a
 * list is composed, and written on every scrolled frame — as state it would recompose the
 * very list that is scrolling. The settings surface is composed more than once at a time
 * (the open section, the hub the back gesture uncovers underneath it, and for an instant
 * both hubs as the pop lands), so each instance restores the position from here rather than
 * sharing one LazyListState between two live LazyColumns.
 */
private class ScrollAnchor(var index: Int = 0, var offset: Int = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    currentTheme: String,
    onThemeChange: (String) -> Unit,
    currentPreset: String,
    onPresetChange: (String) -> Unit,
    currentDynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    currentAmoledMode: Boolean,
    onAmoledModeChange: (Boolean) -> Unit,
    initialSection: String? = null,
    initialRow: String? = null,
    initialSearch: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val profilePrefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    
    // Scroll positions of the two levels, kept outside the surfaces that draw them —
    // see ScrollAnchor. Without this the hub the finger uncovers during a back gesture is
    // a fresh LazyColumn at the top, and so is the one the pop lands on: a category low in
    // the list came back to a screen scrolled somewhere the user had never been.
    val hubAnchor = remember { ScrollAnchor() }
    val sectionAnchors = remember { mutableMapOf<String, Int>() }

    // What this device is offered, and where (core/DeviceProfile.kt). A handheld is offered
    // everything in its usual place, so there every list below is the one it always was. A
    // television keeps some of it for the Advanced page and hides what it cannot do.
    val device = LocalDeviceClass.current
    val capabilities = if (device == DeviceClass.HANDHELD) Capabilities.ALL else rememberCapabilities()
    fun place(feature: Feature?): Availability =
        if (feature == null) Availability.OFFERED else DeviceProfile.availability(feature, device, capabilities)
    val advancedPages = remember(device, capabilities) { advancedPageCandidates.filter { place(it.feature) == Availability.ADVANCED } }
    val advancedLinks = remember(device, capabilities) { advancedTools.filter { place(it.feature) == Availability.ADVANCED } }
    // The last row: what the device keeps aside and, on every device, what is an experiment.
    val advanced = if (advancedPages.isEmpty() && advancedLinks.isEmpty()) experimentalCategory else advancedCategory
    val hubCategories = remember(device, capabilities) {
        settingsCategories.filter { place(it.feature) == Availability.OFFERED } + advanced
    }
    // The page a page was opened from, when that is not the hub: Back returns there.
    fun parentOf(section: String?): String? = advancedCategory.id.takeIf { advancedPages.any { it.id == section } }

    // Two-level navigation: null == the category hub, otherwise the id of the
    // open section (see settingsCategories). rememberSaveable, because the App
    // Language row calls recreate() and a plain remember would drop the user
    // back to the hub mid-edit. It starts on the page that was asked for, as this
    // device has it (settingsLanding).
    val landing = remember(initialSection, hubCategories, advancedPages) {
        settingsLanding(initialSection, hubCategories.map { it.id }, advancedPages.map { it.id })
    }
    var openSection by rememberSaveable { mutableStateOf(landing) }
    // The remote management page (RemoteManagedScreen), which takes the whole screen while open.
    var remoteOpen by rememberSaveable { mutableStateOf(false) }
    // What this device exposes (ExposureScreen): a page of the same kind.
    var exposureOpen by rememberSaveable { mutableStateOf(false) }

    // A television's remote. It needs something in focus at every moment, and Settings swaps
    // what is on screen under it — one page for another, a page for the file browser. So
    // each swap says where the remote goes next: into the page that opened, or back onto the
    // row it was opened from; and the effect below sends it there once that is on screen.
    // Off a television nothing asks and nothing is sent.
    val tv = isTv
    val hubFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    var pageHasFocus by remember { mutableStateOf(false) }
    // Whether the list still has to be given the remote for the first time. A press that comes
    // before it is lost — it only puts the remote on the first row, which a request that
    // follows would put there again — and a request that waits for an effect and a frame, as
    // `focus` does, leaves room for the first few presses of a held key. So the row that is
    // to have it asks as it is placed, the first moment a request can succeed.
    val hubStart = remember { booleanArrayOf(openSection == null) }
    val hubPlaced: () -> Unit = {
        if (hubStart[0] && runCatching { hubFocus.requestFocus() }.getOrDefault(false)) hubStart[0] = false
    }
    // The same for a page that was asked for by name, which the remote starts on.
    val pageStart = remember { booleanArrayOf(openSection != null) }
    val pagePlaced: () -> Unit = {
        if (pageStart[0] && runCatching { pageFocus.requestFocus() }.getOrDefault(false)) pageStart[0] = false
    }
    // The rows to come back to: the hub's, and the Advanced page's.
    var hubRow by rememberSaveable { mutableStateOf(landing?.let { parentOf(it) ?: it }) }
    var advancedRow by rememberSaveable { mutableStateOf(landing?.takeIf { parentOf(it) != null }) }
    var focusAsked by remember { mutableStateOf<Pair<SettingsFocus, Int>?>(null) }
    // The list of sections on screen, for bringing a row of it back into view.
    val hubList = remember { arrayOfNulls<androidx.compose.foundation.lazy.LazyListState>(1) }
    fun focus(target: SettingsFocus) {
        if (tv) focusAsked = target to (focusAsked?.second ?: 0) + 1
    }
    // Before a page with the remote on it is swapped for another, the remote steps back
    // onto the list of sections, to the row the page belongs to. A stop that is removed
    // from under the remote leaves it nowhere, and Compose then puts it on the first stop
    // of the screen — the top of that list, which scrolls there to show it.
    fun stepOutOfPage() {
        if (tv && pageHasFocus) runCatching { hubFocus.requestFocus() }
    }
    if (tv) {
        LaunchedEffect(focusAsked) {
            val target = focusAsked?.first ?: return@LaunchedEffect
            // What was asked for is composed by now, and placed a frame later.
            withFrameNanos { }
            if (target == SettingsFocus.HUB) {
                // A row scrolled out of a lazy list is not there to be asked: bring it back first.
                val list = hubList[0]
                val index = hubCategories.indexOfFirst { it.id == (hubRow ?: hubCategories.first().id) }
                if (list != null && index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                    list.scrollToItem(index)
                    withFrameNanos { }
                }
            }
            val requester = when (target) {
                SettingsFocus.HUB -> hubFocus
                SettingsFocus.PAGE -> pageFocus
                SettingsFocus.ROW -> rowFocus
            }
            // False is no failure: a group that puts the remote back on the row it left
            // (tvFocusGroup) answers the request itself and cancels this one. Only a row
            // that is not on screen at all — the requester has nothing to ask — falls
            // back to the page.
            val asked = runCatching { requester.requestFocus() }
            if (asked.isFailure && target != SettingsFocus.PAGE) runCatching { pageFocus.requestFocus() }
        }
        // The list, or the page that was asked for, takes the remote as it is first placed
        // (hubPlaced, pagePlaced), not a few frames later. This is the second try, for a page
        // that could not be asked yet.
        LaunchedEffect(Unit) {
            if (openSection == null) return@LaunchedEffect
            withFrameNanos { }
            if (pageStart[0]) focus(SettingsFocus.PAGE)
        }
    }
    fun open(section: String) {
        if (parentOf(section) == null) hubRow = section else advancedRow = section
        if (section != openSection) stepOutOfPage()
        openSection = section
        focus(SettingsFocus.PAGE)
    }
    // The search of the settings (SettingsSearch.kt): its field stands over the list of sections, and
    // a result opens a page through open(), on its row.
    val search = rememberSettingsSearch(initialRow, initialSearch) { open(it) }
    // What every page is handed (SettingsPageEnv). Made on each composition, as the page
    // closures were, so that `place` and `open` read the screen as it is now.
    val env = SettingsPageEnv(context, scope, activeAccount, profilePrefs, ::place, ::open)
    // The theme rows of Appearance and of Advanced → Theme and icon (SettingsAppearance.kt).
    val theme = SettingsThemeChoice(
        currentTheme, onThemeChange, currentPreset, onPresetChange,
        currentDynamicColor, onDynamicColorChange, currentAmoledMode, onAmoledModeChange
    )

    // The state of the pages, one holder each (ui/Settings<Page>.kt), remembered here for as long
    // as the screen lives: a page is composed only while it is open, and what it shows is read
    // once, when Settings opens, as it always was.
    // The Sharing page's values, and the chooser of its Taildrop folder: registered here,
    // where it is composed whichever page is open, for the system's picker to answer.
    val sharing = remember { SettingsSharingState(context, profilePrefs) }
    val taildropFolderChooser = rememberReceiveFolderChooser { sharing.taildropFolder = it }
    val background = remember { SettingsBackgroundState(context) }
    // The control proxy: its row on the Censorship bypass page, its dialog over the screen.
    val bypass = remember { SettingsBypassState(context) }
    val proxies = remember { SettingsProxiesState(context, activeAccount.name) }
    val dns = remember { SettingsDnsState(context) }
    val diagnostics = remember { SettingsDiagnosticsState(context) }

    // TUN Mode State (the Tunnel page, SettingsTunnel.kt)
    val tunnel = remember { SettingsTunnelState(context) }

    val excludedAppsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tunnel.tunExcludedApps = GlobalSettings.getTunExcludedApps(context)
    }

    // The Account page (SettingsAccount.kt). The Android/Linux choice is keyed on the account,
    // as it always was; the account itself is the one open when Settings opened.
    val honestHostinfo = remember(activeAccount.id) { mutableStateOf(ProfileHostinfo.isHonest(context, activeAccount.id)) }
    val account = remember { SettingsAccountState(context, profilePrefs, honestHostinfo) }

    // Backup and restore (SettingsBackup.kt): its saved and remembered state, its four
    // pickers — registered here, composed whichever page is open — and its work.
    val backup = rememberSettingsBackup(context, scope, activeAccount, profilePrefs)

    SettingsLogoutDialog(env, account)

    // The section detail pages are files of their own, in the A..K order of the target
    // structure: SettingsAccount.kt, SettingsTunnel.kt (Root mode as well), SettingsProxies.kt,
    // SettingsDns.kt, SettingsBypass.kt, SettingsSharing.kt, SettingsBackground.kt,
    // SettingsAppearance.kt (Theme and icon as well), SettingsBackup.kt, SettingsAutomation.kt,
    // SettingsDiagnostics.kt and SettingsAdvanced.kt. Below is what is around them: the list
    // of sections, the way between the two levels, and Back.

    // Back means "up one level": out of an open section to the hub — from a page the
    // Advanced page opened, to that page first — and only from the hub out of the
    // Activity. The toolbar arrow keeps the crossfade below;
    // the gesture draws its own transition and sets [poppedByGesture] so the
    // crossfade stands down instead of replaying a pop the finger already showed.
    var poppedByGesture by remember { mutableStateOf(false) }
    val popSection: () -> Unit = {
        val parent = parentOf(openSection)
        openSection = parent
        focus(if (parent == null) SettingsFocus.HUB else SettingsFocus.ROW)
    }
    val popSectionByGesture: () -> Unit = {
        poppedByGesture = true
        popSection()
    }
    // Armed for exactly one transition. The reset runs after the composition that
    // consumed it, and transitionSpec is only consulted when the target changes,
    // so it cannot cancel the transition it just configured.
    LaunchedEffect(openSection) { poppedByGesture = false }

    // One whole rendering of the screen at a given level: the category hub when
    // [section] is null, that category's page otherwise. It takes the level as a
    // parameter instead of reading `openSection` because the back gesture needs
    // two levels on screen at once — the section being dragged away, and the hub
    // coming back underneath it.
    // backable: whether a section shows the arrow that returns to the hub. Beside
    // the hub, as the right pane of the wide layout, there is nothing to return
    // to — the hub is already on screen — so the arrow would be a lie.
    // live: whether this rendering is the one in use, not one fading out or lying
    // under a back gesture. Only that one is where a television's remote is sent.
    val window = rememberWindowLayout()
    val settingsSurface: @Composable (String?, Boolean, Boolean) -> Unit = { section, backable, live ->
        val openCategory = if (section == advanced.id) advanced else settingsPages.firstOrNull { it.id == section }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                SettingsSearchBar(search, shown = section == null, live = live) {
                AppTopBar(
                    title = if (openCategory != null) stringResource(openCategory.titleRes) else stringResource(R.string.settings_title),
                    onBack = if (section == null || backable) {
                        { if (section != null) popSection() else onBack() }
                    } else null
                )
                }
            }
        ) { padding ->
            // While the search is in use its results stand where the list of sections does.
            if (section == null && search.active) SettingsSearchResults(search, Modifier.padding(padding)) else
            if (section == null) {
                val hubState = rememberLazyListState(hubAnchor.index, hubAnchor.offset)
                // Every hub instance starts where the last one was left and writes back
                // where it is now, so the copy under the finger, the copy on top of it and
                // the copy that lands are all at the same place in the list.
                LaunchedEffect(hubState) {
                    snapshotFlow { hubState.firstVisibleItemIndex to hubState.firstVisibleItemScrollOffset }
                        .collect { (index, offset) ->
                            hubAnchor.index = index
                            hubAnchor.offset = offset
                        }
                }
                ReadableWidth {
                // The row the remote starts on and comes back to: the one last opened.
                val returnRow = hubRow ?: hubCategories.first().id
                if (tv && live) SideEffect { hubList[0] = hubState }
                LazyColumn(
                    state = hubState,
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .tvFocusGroup(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(hubCategories, key = { it.id }) { category ->
                        SettingsClickableItem(
                            title = stringResource(category.titleRes),
                            subtitle = stringResource(category.descRes),
                            icon = category.icon,
                            modifier = if (tv && live && category.id == returnRow) {
                                Modifier.focusRequester(hubFocus).onGloballyPositioned { if (hubStart[0]) hubPlaced() }
                            } else Modifier,
                            onClick = { open(category.id) }
                        )
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
                }
            } else {
                // Hoisted for the same reason as the hub's: the back container swaps its own
                // structure the moment a pop lands, and a section that jumps back to the top
                // while it is still fading out is worse than the pop it is showing.
                // Beside the list one pane serves every section in turn, and the state it had for the
                // last one is not the next one's: each section is its own, and one opened from the list
                // starts at its top. A search result or a link that names a row scrolls to it from there.
                val sectionScroll = if (backable) rememberScrollState(sectionAnchors[section] ?: 0)
                else key(section) { rememberScrollState() }
                LaunchedEffect(sectionScroll, section) {
                    snapshotFlow { sectionScroll.value }.collect { sectionAnchors[section] = it }
                }
                val sectionBody: @Composable () -> Unit = {
                    // The row a search result was opened on is found by the rows of this page (settingAnchor).
                    SettingsRowTarget(search, section, live) {
                    when (section) {
                        "account" -> SettingsAccountPage(env, account, diagnostics.extraArgs)
                        "tunnel" -> SettingsTunnelPage(env, tunnel, proxies.socks5, excludedAppsLauncher, place(Feature.ROOT_MODE) == Availability.OFFERED)
                        rootPage.id -> SettingsTunnelPage(env, tunnel, proxies.socks5, excludedAppsLauncher, true)
                        "proxies" -> SettingsProxiesPage(env, proxies)
                        "dns" -> SettingsDnsPage(env, dns)
                        "bypass" -> SettingsBypassPage(context, bypass)
                        "sharing" -> SettingsSharingPage(env, sharing, taildropFolderChooser)
                        "background" -> SettingsBackgroundPage(env, background)
                        "appearance" -> SettingsAppearancePage(env, theme) { remoteOpen = true }
                        themePage.id -> SettingsThemePage(theme)
                        "backup" -> SettingsBackupPage(backup)
                        "automation" -> SettingsAutomationPage(env)
                        "diagnostics" -> SettingsDiagnosticsPage(env, diagnostics)
                        advancedCategory.id -> SettingsAdvancedPage(
                            env, advancedPages, advancedLinks, advancedRow, rowFocus,
                            onExposure = {
                                advancedRow = EXPOSURE_ROW
                                stepOutOfPage()
                                exposureOpen = true
                            },
                            onRemote = {
                                advancedRow = REMOTE_ROW
                                stepOutOfPage()
                                remoteOpen = true
                            }
                        )
                    }
                    }
                }
                // For a television's remote the page is one group: somewhere it can be
                // sent into as a whole, that remembers the row it left, and that scrolls
                // to its head and its tail, which have no row of their own to stop on.
                val remote = if (tv && live) {
                    Modifier
                        .focusRequester(pageFocus)
                        .onGloballyPositioned { if (pageStart[0]) pagePlaced() }
                        .onFocusChanged { pageHasFocus = it.hasFocus }
                        .tvScrollPastStops(sectionScroll)
                        .tvFocusGroup()
                } else Modifier
                // A phone's page, and any page a tablet has no columns for.
                val oneColumn: @Composable () -> Unit = {
                    ReadableWidth {
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .then(remote)
                            .verticalScroll(sectionScroll)
                            .padding(16.dp)
                    ) {
                        sectionBody()
                        Spacer(Modifier.height(32.dp))
                    }
                    }
                }
                // On a tablet a page of several cards stands them in two columns instead of
                // one 720dp strip down the middle of the pane: beside the categories from two
                // cards, alone on a medium window from three — two cards there would be two
                // half-empty columns. Never on a phone, upright or turned: its two-pane keeps
                // the single column it always had.
                val cards = openCategory?.cards ?: 1
                if (!window.isPhone && cards >= (if (backable) 3 else 2)) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        if (columnsFor(maxWidth - window.margin * 2, SettingsColumnWidth, maxColumns = 2) < 2) {
                            oneColumn()
                        } else {
                            Column(
                                modifier = Modifier
                                    .padding(padding)
                                    .fillMaxSize()
                                    .then(remote)
                                    .verticalScroll(sectionScroll)
                                    .padding(horizontal = window.margin, vertical = 16.dp)
                            ) {
                                CompositionLocalProvider(LocalSettingsColumns provides true) {
                                    if (openCategory?.splits == true) sectionBody()
                                    else CardColumns(minColumnWidth = SettingsColumnWidth, maxColumns = 2) { sectionBody() }
                                }
                                Spacer(Modifier.height(32.dp))
                            }
                        }
                    }
                } else {
                    oneColumn()
                }
            }
        }
    }

    // The crossfade between the hub and a section: Material 3's own *default effects*
    // spring. Read here rather than inside `transitionSpec`, which is not a composable
    // lambda and so cannot reach `MaterialTheme` itself.
    val sectionFadeSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()

    // The crossfade sits outside the back container, so that opening a section
    // still fades and each level keeps its own container: the one drawn for a
    // section installs the gesture, the one drawn for the hub does not.
    // Wide windows — a tablet, a phone on its side — show the hub and a section
    // at once, the way a settings screen reads on a large display: the list stays
    // put and the right side follows it. Nothing to push or pop there, so none of
    // the transition or predictive-back machinery below is involved; back simply
    // leaves, as it does from the hub.
    // A foldable held open like a book gets the two panes on its two halves
    // whatever its width, with the hinge itself as the divider. The width is the
    // content's, not the window's size class: a phone on its side (some 890dp) is
    // two panes here, and always has been.
    //
    // While a backup's file is being chosen in the app's own browser, the browser is the
    // screen: it takes the Back key for itself, and gives the settings back as it closes.
    val browsing = backup.browsingFor
    if (remoteOpen) {
        // A page of its own, with its own Back: it hands the settings back as it closes.
        RemoteManagedScreen(onBack = { remoteOpen = false; focus(SettingsFocus.ROW) })
    } else if (exposureOpen) {
        ExposureScreen(onBack = { exposureOpen = false; focus(SettingsFocus.ROW) })
    } else if (browsing != null) {
        val close = { backup.browsingFor = null; focus(SettingsFocus.PAGE) }
        SettingsBackupBrowser(backup, browsing, close)
    } else
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    if (maxWidth >= 840.dp || window.fold is Fold.Vertical) {
        val shown = openSection ?: hubCategories.first().id
        // On a television Back leaves a page before it leaves Settings: from a page of
        // the Advanced page to that page, from any other to its row in the list, the
        // page staying where it is — and from the list, as everywhere, out.
        BackHandler(enabled = tv && pageHasFocus) {
            val parent = parentOf(openSection)
            if (parent != null) {
                stepOutOfPage()
                openSection = parent
                focus(SettingsFocus.ROW)
            } else {
                focus(SettingsFocus.HUB)
            }
        }
        ListDetailLayout(
            list = { settingsSurface(null, true, true) },
            detail = { settingsSurface(shown, false, true) },
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            window = window,
            twoPane = true,
            listWidth = 340.dp,
            style = PaneStyle.FLAT
        )
    } else
    AnimatedContent(
        targetState = openSection,
        transitionSpec = {
            if (poppedByGesture) {
                // The finger already played this one: the section shrank away and
                // the hub came back up underneath it. Fading them into each other
                // on top of that would show the section again, full size.
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(sectionFadeSpec) togetherWith fadeOut(sectionFadeSpec)
            }
        },
        label = "settings_section",
        // The two levels overlap while they crossfade, and half-transparent over
        // half-transparent would let the window background show through between
        // them; this is the ground they fade over.
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) { section ->
        PredictiveBackContainer(
            // Only the section pop is ours to draw. On the hub back leaves the
            // Activity, so nothing is intercepted there and the platform keeps the
            // gesture and its real cross-activity animation.
            // openSection as well as this slot's own section: AnimatedContent keeps the
            // outgoing slot alive for the whole exit fade, and that slot was invoked with a
            // section, so a handler keyed on it alone stayed armed on a screen that is
            // already leaving — a back gesture in that window was swallowed instead of
            // closing the Activity, and it re-ran the pop with nothing left to pop, which
            // latched poppedByGesture and cost the next section its fade.
            onBack = if (section != null && openSection != null) popSectionByGesture else null,
            modifier = Modifier.fillMaxSize(),
            // What the finger uncovers is the real hub, not a hint of it — for the eye
            // only: it is a second live copy of every category row, and a screen reader
            // reaches the hub through the one that lands.
            previousContent = {
                Box(Modifier.fillMaxSize().clearAndSetSemantics { }) { settingsSurface(parentOf(section), true, false) }
            }
        ) {
            settingsSurface(section, true, section == openSection)
        }
    }
    }

    SettingsControlProxyDialog(context, bypass)

    SettingsBackupDialogs(backup)
}

