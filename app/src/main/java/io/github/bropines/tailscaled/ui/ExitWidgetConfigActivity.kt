package io.github.bropines.tailscaled.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An exit node on the configuration screen; [name] is null for a favourite the tailnet no longer has. */
internal data class ConfigNode(val id: String, val name: String?, val ip: String, val online: Boolean?)

/**
 * The screen's two lists: the favourites in their order — one gone from the tailnet stays,
 * marked, so keeping or dropping it is the person's call — and every other node by name.
 */
internal fun configLists(candidates: List<ExitRow>, favourites: List<String>): Pair<List<ConfigNode>, List<ConfigNode>> {
    val byId = candidates.associateBy { it.id }
    val chosen = favourites.distinct().map { id ->
        byId[id]?.let { ConfigNode(it.id, it.name, it.ip, it.online) } ?: ConfigNode(id, null, "", null)
    }
    val others = candidates.filter { it.id !in favourites }.map { ConfigNode(it.id, it.name, it.ip, it.online) }
    return chosen to others
}

/** [list] with the item at [index] moved by [delta], or unchanged when it cannot move. */
internal fun <T> moved(list: List<T>, index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in list.indices || target !in list.indices) return list
    return list.toMutableList().apply { add(target, removeAt(index)) }
}

/**
 * The exit-node widget's configuration (`android:configure`, optional and reconfigurable):
 * which nodes this instance lists and in what order, and whether it offers "Best". Saved in
 * the instance's own Glance state, so each widget keeps its choice and loses it with itself.
 */
class ExitWidgetConfigActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        // Backing out changes nothing; the API reads it as a cancel.
        setResult(RESULT_CANCELED, result)
        // Only an exit-node widget of ours: the activity is reachable by launchers, and so by others.
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider
        if (provider != ComponentName(this, ExitNodeToggleWidgetReceiver::class.java)) {
            finish()
            return
        }
        setContent {
            TailSocksTheme {
                ExitWidgetConfigScreen(
                    appWidgetId = appWidgetId,
                    onSaved = { setResult(RESULT_OK, result); finish() },
                    onCancel = { finish() }
                )
            }
        }
    }
}

@Composable
private fun ExitWidgetConfigScreen(appWidgetId: Int, onSaved: () -> Unit, onCancel: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<List<ExitRow>?>(null) }
    var favourites by remember { mutableStateOf(emptyList<String>()) }
    var showBest by remember { mutableStateOf(true) }

    LaunchedEffect(appWidgetId) {
        val config = WidgetUpdates.loadConfig(context, appWidgetId)
        favourites = config.favourites
        showBest = config.showBest
        candidates = withContext(Dispatchers.IO) { WidgetUpdates.exitNodeChoices(context) }
    }

    fun save() = scope.launch {
        WidgetUpdates.saveConfig(context, appWidgetId, ExitWidgetConfig(favourites, showBest))
        onSaved()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.widget_config_title),
                onBack = onCancel,
                actions = {
                    TextButton(
                        onClick = { save() },
                        enabled = candidates != null,
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
        val loaded = candidates
        if (loaded == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) { CircularProgressIndicator() }
            return@Scaffold
        }
        val (chosen, others) = configLists(loaded, favourites)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { HelpText(stringResource(R.string.widget_config_help)) }
            item {
                // Not a SettingsSwitchItem: those are Settings rows, catalogued for its search.
                Surface(
                    onClick = { showBest = !showBest },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        supportingContent = { HelpText(stringResource(R.string.widget_config_show_best_desc), inClickableRow = true) },
                        trailingContent = { Switch(checked = showBest, onCheckedChange = { showBest = it }) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    ) { Text(stringResource(R.string.widget_config_show_best)) }
                }
            }
            if (chosen.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.widget_config_favourites)) }
                itemsIndexed(chosen, key = { _, n -> "fav-${n.id}" }) { index, node ->
                    NodeRow(node, checked = true, onToggle = { favourites = favourites - node.id }) {
                        IconButton(onClick = { favourites = moved(favourites, index, -1) }, enabled = index > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.widget_config_move_up))
                        }
                        IconButton(onClick = { favourites = moved(favourites, index, 1) }, enabled = index < chosen.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.widget_config_move_down))
                        }
                    }
                }
            }
            if (others.isNotEmpty()) {
                item { SectionTitle(stringResource(if (chosen.isEmpty()) R.string.widget_config_all else R.string.widget_config_others)) }
                items(others, key = { "node-${it.id}" }) { node ->
                    NodeRow(node, checked = false, onToggle = { favourites = favourites + node.id })
                }
            }
            if (loaded.isEmpty() && chosen.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.main_no_exit_nodes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun NodeRow(node: ConfigNode, checked: Boolean, onToggle: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val supporting = when {
        node.name == null -> stringResource(R.string.widget_config_gone)
        node.online == false -> "${node.ip} · ${stringResource(R.string.widget_offline)}"
        else -> node.ip
    }
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            leadingContent = { Checkbox(checked = checked, onCheckedChange = { onToggle() }) },
            supportingContent = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = { androidx.compose.foundation.layout.Row { trailing() } },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        ) { Text(node.name ?: node.id, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
