package io.github.bropines.tailscaled.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppItem(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunExcludedAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val initialExcluded = remember { 
        GlobalSettings.getTunExcludedApps(context)
            .filter { !it.startsWith("io.github.bropines.tailscaled") }
            .toSet() 
    }
    val excluded = remember { mutableStateOf(initialExcluded) }
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    
    // UI states
    var searchQuery by remember { mutableStateOf("") }
    var showOnlyExcluded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
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
    val hostActivity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
    }
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
    val filteredApps = remember(apps, searchQuery, showOnlyExcluded, excluded.value) {
        apps.filter { app ->
            val matchesSearch = app.label.contains(searchQuery, ignoreCase = true) || 
                                app.packageName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = !showOnlyExcluded || app.packageName in excluded.value
            matchesSearch && matchesFilter
        }
    }

    PredictiveBackContainer(
        onBack = { saveAndExit() },
        // Back here only closes the Activity, so the container installs no callback and
        // the platform animates across to the real screen underneath.
        popsInAppState = false
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    // A single-line bar has room for two short lines and no more:
                    // the settings row's full description wrapped the title onto a
                    // second line and pushed itself out of the bar entirely.
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.title_activity_tun_excluded_apps),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.tun_excluded_apps_count, excluded.value.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            // Also auto-save on back for safety
                            saveAndExit()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                // Search Bar
                CompactSearchBar(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholderText = stringResource(R.string.logs_search_placeholder),
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                val filterOptions = listOf(
                    stringResource(R.string.tun_apps_filter_all) + " (${apps.size})",
                    stringResource(R.string.tun_apps_filter_excluded) + " (${excluded.value.size})"
                )
                SlidingSegmentedChips(
                    options = filterOptions,
                    selectedIndex = if (showOnlyExcluded) 1 else 0,
                    onOptionSelected = { idx -> showOnlyExcluded = (idx == 1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    height = 38.dp
                )

            if (loading) {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredApps.isEmpty()) {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.settings_exit_node_empty),
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isExcluded = app.packageName in excluded.value
                        AppExclusionCard(
                            app = app,
                            isExcluded = isExcluded,
                            onToggle = {
                                excluded.value = if (isExcluded) {
                                    excluded.value - app.packageName
                                } else {
                                    excluded.value + app.packageName
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun AppExclusionCard(app: AppItem, isExcluded: Boolean, onToggle: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isExcluded) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
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
                    text = app.packageName,
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

private fun loadInstalledApps(context: Context): List<AppItem> {
    val pm = context.packageManager
    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { info ->
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val hasLaunchIntent = pm.getLaunchIntentForPackage(info.packageName) != null
            val isTailSocks = info.packageName.startsWith("io.github.bropines.tailscaled")
            (!isSystem || hasLaunchIntent) && !isTailSocks
        }
        .map { info ->
            val label = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { info.packageName }
            val icon = try {
                pm.getApplicationIcon(info.packageName).toBitmap(48, 48).asImageBitmap()
            } catch (_: Exception) { null }
            AppItem(info.packageName, label, icon)
        }
        .sortedBy { it.label.lowercase() }
}
