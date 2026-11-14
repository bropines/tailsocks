package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.PredictiveBackContainer
import io.github.bropines.tailscaled.core.SlidingSegmentedChips
import io.github.bropines.tailscaled.core.CompactSearchBar
import io.github.bropines.tailscaled.core.TunVpnService
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.ui.theme.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppItem(
    val packageName: String,
    val label: String,
    /** An icon given up front (a preview's); the installed apps come without and load theirs per row. */
    val icon: ImageBitmap?,
    /** False for a package that is on the exclusion list but no longer on the phone. */
    val installed: Boolean = true,
)

class TunExcludedAppsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                TunExcludedAppsScreen(onBack = { finish() })
            }
        }
    }
}

/**
 * The apps the tunnel leaves alone. [initialApps] stands in for the installed
 * apps and is shown from the first frame: the preview renderer has no package
 * manager to list and draws only that frame.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TunExcludedAppsScreen(onBack: () -> Unit, initialApps: List<AppItem>? = null) {
    val context = LocalContext.current
    val initialExcluded = remember { 
        GlobalSettings.getTunExcludedApps(context)
            .filter { !it.startsWith("io.github.bropines.tailscaled") }
            .toSet() 
    }
    val excluded = remember { mutableStateOf(initialExcluded) }
    var apps by remember { mutableStateOf(initialApps ?: emptyList()) }
    var loading by remember { mutableStateOf(initialApps == null) }
    
    // UI states
    var searchQuery by remember { mutableStateOf("") }
    var showOnlyExcluded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (initialApps != null) return@LaunchedEffect
        val installed = withContext(Dispatchers.IO) { loadInstalledApps(context) }
        // Packages excluded earlier but gone from the phone still count against
        // the list; show them greyed so they can be seen and removed.
        val present = installed.map { it.packageName }.toSet()
        val missing = initialExcluded.filter { it !in present }.sorted().map { AppItem(it, it, null, installed = false) }
        apps = installed + missing
        loading = false
    }

    // Persisting is no longer tied to onBack(): the back gesture is handled by the platform now
    // (see PredictiveBackContainer), which finishes the Activity without going through us.
    val persistedExcluded = remember { mutableStateOf(initialExcluded) }
    fun persistExclusions() {
        val value = excluded.value
        if (value == persistedExcluded.value) return
        persistedExcluded.value = value
        GlobalSettings.setTunExcludedApps(context, value)
        // If TUN is running, restart it to apply changes
        if (GlobalSettings.isTunModeEnabled(context)) {
            context.startService(Intent(context, TunVpnService::class.java).apply {
                action = TunVpnService.ACTION_START
            })
        }
    }

    fun saveAndExit() {
        persistExclusions()
        onBack()
    }

    // Save on the way out, whichever way the user leaves: back gesture, back key or the arrow.
    // Only when the Activity is really finishing, so a trip to Home does not restart the tunnel
    // behind the user's back.
    // findActivity follows TailSocksTheme's language wrapper, whose baseContext chain never
    // reaches the activity: with a language chosen in the app the plain walk found none, and
    // every trip to Home counted as leaving.
    val hostActivity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, hostActivity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && hostActivity?.isFinishing != false) {
                persistExclusions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Filter apps based on search query and tab/chip selection
    val filteredApps = remember(apps, searchQuery, showOnlyExcluded, excluded.value, persistedExcluded.value) {
        // In the "bypassed" view an app switched off stays on screen, switch off,
        // until the screen is left — a slip can be undone where it happened.
        val bypassedView = excluded.value + persistedExcluded.value
        apps.filter { app ->
            val matchesSearch = app.label.contains(searchQuery, ignoreCase = true) || 
                                app.packageName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = !showOnlyExcluded || app.packageName in bypassedView
            matchesSearch && matchesFilter
        }
    }

    val window = rememberWindowLayout()
    val icons = rememberAppIcons()

    PredictiveBackContainer(
        onBack = { saveAndExit() },
        // Back here only closes the Activity, so the container installs no callback and
        // the platform animates across to the real screen underneath.
        popsInAppState = false
    ) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.title_activity_tun_excluded_apps),
                    subtitle = stringResource(R.string.tun_excluded_apps_count, excluded.value.size),
                    onBack = { saveAndExit() },
                    actions = {
                        TextButton(
                            onClick = { saveAndExit() },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        ) { padding ->
            val searchBar: @Composable (Modifier) -> Unit = { modifier ->
                CompactSearchBar(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholderText = stringResource(R.string.logs_search_placeholder),
                    modifier = modifier
                )
            }
            val filterChips: @Composable (Modifier) -> Unit = { modifier ->
                val filterOptions = listOf(
                    stringResource(R.string.tun_apps_filter_all) + " (${apps.size})",
                    stringResource(R.string.tun_apps_filter_excluded) + " (${excluded.value.size})"
                )
                SlidingSegmentedChips(
                    options = filterOptions,
                    selectedIndex = if (showOnlyExcluded) 1 else 0,
                    onOptionSelected = { idx -> showOnlyExcluded = (idx == 1) },
                    modifier = modifier,
                    height = 38.dp
                )
            }
            val toggle: (AppItem) -> Unit = { app ->
                val isExcluded = app.packageName in excluded.value
                excluded.value = if (isExcluded) {
                    excluded.value - app.packageName
                } else {
                    excluded.value + app.packageName
                }
            }
            // Loading, or nothing to list: in place of the list, whatever its shape.
            val noList: @Composable ColumnScope.() -> Unit = {
                if (loading) {
                    Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                } else {
                    Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.settings_exit_node_empty),
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            // A phone's list, and a tablet's where two columns do not fit.
            val oneColumn: @Composable () -> Unit = {
                // Held to a readable width on a tablet; see ReadableWidth.
                ReadableWidth {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp)
                    ) {
                        searchBar(Modifier.padding(vertical = 8.dp))
                        filterChips(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                        if (loading || filteredApps.isEmpty()) {
                            noList()
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                items(filteredApps, key = { it.packageName }) { app ->
                                    AppExclusionCard(icons = icons,
                                        app = app,
                                        isExcluded = app.packageName in excluded.value,
                                        onToggle = { toggle(app) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!window.multiColumn) {
                oneColumn()
                return@Scaffold
            }
            // From a medium window up the apps stand in a grid, the search and the filter on
            // one line over it, at its width.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (columnsFor(maxWidth - window.margin * 2, AppColumnWidth) < 2) {
                    oneColumn()
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = window.margin)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            searchBar(Modifier.weight(1f))
                            filterChips(Modifier.width(AppColumnWidth))
                        }
                        if (loading || filteredApps.isEmpty()) {
                            noList()
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(AppColumnWidth),
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
                            ) {
                                gridItems(filteredApps, key = { it.packageName }) { app ->
                                    AppExclusionCard(icons = icons,
                                        app = app,
                                        isExcluded = app.packageName in excluded.value,
                                        onToggle = { toggle(app) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The narrowest column of app rows: an icon, a name and a switch. */
private val AppColumnWidth = 320.dp

/** The size an app's icon is drawn at in its row. */
private val AppIconSize = 40.dp

/**
 * App icons for the rows, loaded one row at a time as rows are composed, off the main thread,
 * at the pixel size of [AppIconSize] — they used to be rasterised at 48 px for all apps before
 * the list could show, and then drawn upscaled at 40 dp. A screen-scoped LRU, bounded in bytes,
 * keeps the icons scrolled past so scrolling back does not decode them again; a package whose
 * icon could not be loaded is remembered too and not asked again.
 */
private class AppIcons(private val pm: PackageManager, val px: Int) {
    private val cache = object : LruCache<String, ImageBitmap>(ICON_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val failed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun cached(packageName: String): ImageBitmap? = cache.get(packageName)

    /** Decodes the icon; null when the package has none to give. Blocks: call it on IO. */
    fun load(packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        if (packageName in failed) return null
        return try {
            pm.getApplicationIcon(packageName).toBitmap(px, px).asImageBitmap().also { cache.put(packageName, it) }
        } catch (_: Exception) {
            failed += packageName
            null
        }
    }

    private companion object {
        /** About a hundred icons at 140 px (40 dp on a 3.5x screen); more than a screen or two. */
        const val ICON_CACHE_BYTES = 8 * 1024 * 1024
    }
}

@Composable
private fun rememberAppIcons(): AppIcons {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { AppIconSize.roundToPx() }
    return remember(context, px) { AppIcons(context.packageManager, px) }
}

/**
 * The row's icon: the one [app] came with (a preview's), else the cached or lazily loaded one,
 * null while it loads or when there is none (the row shows the label's first letter then).
 */
@Composable
private fun rowIcon(app: AppItem, icons: AppIcons): ImageBitmap? {
    app.icon?.let { return it }
    if (!app.installed || LocalInspectionMode.current) return null
    val icon by produceState(icons.cached(app.packageName), app.packageName, icons) {
        if (value == null) value = withContext(Dispatchers.IO) { icons.load(app.packageName) }
    }
    return icon
}

@Composable
private fun AppExclusionCard(icons: AppIcons, app: AppItem, isExcluded: Boolean, onToggle: () -> Unit) {
    val icon = rowIcon(app, icons)
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isExcluded) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .alpha(if (app.installed) 1f else 0.55f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(AppIconSize)
                        .clip(MaterialTheme.shapes.small)
                )
            } else {
                Box(
                    Modifier
                        .size(AppIconSize)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.label.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (app.installed) app.packageName
                           else androidx.compose.ui.res.stringResource(R.string.tun_excluded_apps_not_installed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = isExcluded, 
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

/**
 * The apps to choose from, by label only: the icons are loaded per row as the list scrolls
 * ([AppIcons]), so the list shows once the labels are read. A system package is listed only
 * when it can be launched; the launch-intent lookup is asked of system packages alone.
 */
private fun loadInstalledApps(context: Context): List<AppItem> {
    val pm = context.packageManager
    return pm.getInstalledApplications(0)
        .filter { info ->
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isTailSocks = info.packageName.startsWith("io.github.bropines.tailscaled")
            !isTailSocks && (!isSystem || pm.getLaunchIntentForPackage(info.packageName) != null)
        }
        .map { info ->
            val label = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { info.packageName }
            AppItem(info.packageName, label, icon = null)
        }
        .sortedBy { it.label.lowercase() }
}
