package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.ui.theme.findActivity

/**
 * The app's theme as the activity holds it ([SettingsActivity] keeps it, to redraw the whole
 * screen in it), with the way to change each part: what the theme rows show and change.
 */
@Immutable
internal class SettingsThemeChoice(
    val currentTheme: String,
    val onThemeChange: (String) -> Unit,
    val currentPreset: String,
    val onPresetChange: (String) -> Unit,
    val currentDynamicColor: Boolean,
    val onDynamicColorChange: (Boolean) -> Unit,
    val currentAmoledMode: Boolean,
    val onAmoledModeChange: (Boolean) -> Unit,
)

// H. Appearance & language
//
// The theme and the icon are a feature of their own (Feature.THEME_AND_ICON): four
// blocks that stand among the rest of Appearance where the device is offered them
// there, and on a page of their own — Advanced → Theme and icon — where it is not.

// Theme selector (Chips row)
@Composable
private fun ThemeChips(theme: SettingsThemeChoice) {
    with(theme) {
        val themeOptions = listOf(
            Triple("system", Icons.Default.Settings, stringResource(R.string.settings_theme_system)),
            Triple("light", Icons.Default.LightMode, stringResource(R.string.settings_theme_light)),
            Triple("dark", Icons.Default.DarkMode, stringResource(R.string.settings_theme_dark))
        )
        val selectedThemeIdx = themeOptions.indexOfFirst { it.first == currentTheme }.coerceAtLeast(0)
        Column(Modifier.padding(bottom = 8.dp).settingAnchor(stringResource(R.string.settings_theme_title), outset = 6.dp)) {
            Text(stringResource(R.string.settings_theme_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SlidingSegmentedChips(
                items = themeOptions.map { SegmentedChipItem(it.third, it.second) },
                selectedIndex = selectedThemeIdx,
                onOptionSelected = { idx -> onThemeChange(themeOptions[idx].first) },
                modifier = Modifier.fillMaxWidth(),
                height = 38.dp
            )
        }
    }
}

// Theme preset selector (Color Circles)
@Composable
private fun ThemePalette(theme: SettingsThemeChoice) {
    with(theme) {
        val tv = isTv
        if (!currentDynamicColor || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            Spacer(Modifier.height(12.dp))
            val presets = listOf(
                PresetItem("default", Color(0xFF6750A4), stringResource(R.string.settings_preset_default)),
                PresetItem("lavender", Color(0xFF704E9B), stringResource(R.string.settings_preset_lavender)),
                PresetItem("emerald", Color(0xFF006B54), stringResource(R.string.settings_preset_emerald)),
                PresetItem("sapphire", Color(0xFF005FAF), stringResource(R.string.settings_preset_sapphire)),
                PresetItem("amber", Color(0xFF825500), stringResource(R.string.settings_preset_amber)),
                PresetItem("monochrome", Color(0xFF1D2023), stringResource(R.string.settings_preset_monochrome)),
                PresetItem("tokionight", Color(0xFF7AA2F7), stringResource(R.string.settings_preset_tokionight))
            )
            Column(Modifier.settingAnchor(stringResource(R.string.settings_palette_title), outset = 6.dp)) {
                Text(stringResource(R.string.settings_palette_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    presets.forEach { item ->
                        val isSelected = currentPreset == item.id
                        val source = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                // A television rings the circle the remote is on, and clips Material's
                                // own square ring to it; a handheld's chain is the one it always was.
                                .tvFocusRing(source, CircleShape)
                                .then(if (tv) Modifier.clip(CircleShape) else Modifier)
                                .background(item.color, shape = CircleShape)
                                // A radio button to TalkBack, named: a swatch alone said nothing,
                                // and which one is chosen showed in colour only.
                                .selectable(
                                    selected = isSelected,
                                    interactionSource = source,
                                    indication = LocalIndication.current,
                                    role = Role.RadioButton
                                ) { onPresetChange(item.id) }
                                .semantics { contentDescription = item.name }
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Dynamic Colors switcher (Android 12+)
@Composable
private fun DynamicColorRow(theme: SettingsThemeChoice) {
    with(theme) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().settingAnchor(stringResource(R.string.settings_dynamic_color_title), outset = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_dynamic_color_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settings_dynamic_color_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                FocusSwitch(checked = currentDynamicColor, onCheckedChange = onDynamicColorChange)
            }
        }
    }
}

// AMOLED Black switcher
@Composable
private fun AmoledRow(theme: SettingsThemeChoice) {
    with(theme) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().settingAnchor(stringResource(R.string.settings_amoled_black_title), outset = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_amoled_black_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.settings_amoled_black_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            FocusSwitch(checked = currentAmoledMode, onCheckedChange = onAmoledModeChange)
        }
    }
}

/** [onOpenRemote]: opens remote management, a page of its own over Settings, from the row that puts its tile back. */
@Composable
internal fun SettingsAppearancePage(env: SettingsPageEnv, theme: SettingsThemeChoice, onOpenRemote: () -> Unit) {
    with(env) {
        val themeHere = place(Feature.THEME_AND_ICON) == Availability.OFFERED
        // One card in two halves, cut where they come out about as tall: one column on a
        // phone, side by side where the section stands in columns — the card is the whole
        // section, and alone in a column it would leave the pane's other half empty.
        val firstHalf: @Composable () -> Unit = {
            if (themeHere) ThemeChips(theme)

            // The language: a row that opens a sheet — see LanguagePicker.
            if (themeHere) Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(bottom = 8.dp).settingAnchor(stringResource(R.string.settings_lang_title), outset = 6.dp)) {
                Text(stringResource(R.string.settings_lang_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LanguagePicker(height = 38.dp)
            }

            // Interface scale (Chips row): a density of the app's own, applied by
            // wrapContextWithLocale(); the screen is recreated to take it.
            var currentScale by remember { mutableStateOf(GlobalSettings.getUiScale(context)) }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(bottom = 8.dp).settingAnchor(stringResource(R.string.settings_ui_scale_title), outset = 6.dp)) {
                Text(stringResource(R.string.settings_ui_scale_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HelpText(stringResource(R.string.settings_ui_scale_desc))
                Spacer(Modifier.height(8.dp))
                SlidingSegmentedChips(
                    options = GlobalSettings.UI_SCALES.map { "$it%" },
                    selectedIndex = GlobalSettings.UI_SCALES.indexOf(currentScale).coerceAtLeast(0),
                    onOptionSelected = { idx ->
                        val percent = GlobalSettings.UI_SCALES[idx]
                        if (percent != currentScale) {
                            currentScale = percent
                            GlobalSettings.setUiScale(context, percent)
                            context.findActivity()?.recreate()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    height = 38.dp,
                    recreates = true
                )
            }
            DeviceInterfaceSetting()

            if (themeHere) {
                ThemePalette(theme)

                Spacer(Modifier.height(12.dp))
                AppIconRow()
            }
        }
        val secondHalf: @Composable () -> Unit = {
            QrStyleSetting()

            if (themeHere) {
                DynamicColorRow(theme)
                AmoledRow(theme)
            }

            Spacer(Modifier.height(12.dp))
            var showChangelogAfterUpdate by remember { mutableStateOf(GlobalSettings.isShowChangelogAfterUpdate(context)) }
            SettingsSwitchItem(
                stringResource(R.string.settings_show_changelog_title),
                stringResource(R.string.settings_show_changelog_desc),
                Icons.Default.NewReleases,
                showChangelogAfterUpdate
            ) {
                GlobalSettings.setShowChangelogAfterUpdate(context, it)
                showChangelogAfterUpdate = it
            }
            // The summary is a block of the main screen's layout now; this switch is its older
            // handle and stays one, reading and changing the same arrangement the editor does.
            val remoteOnHome = !Feature.REMOTE_MANAGEMENT_ONLY_IN_SETTINGS.offered()
            // Bumped when the list of what is off the screen puts something back (MainHiddenRow).
            var layoutChanged by remember { mutableIntStateOf(0) }
            var connectionSummaryShown by remember(layoutChanged) { mutableStateOf(MainItem.SUMMARY in MainLayoutStore.load(context, remoteOnHome)) }
            SettingsSwitchItem(
                stringResource(R.string.settings_connection_summary_title),
                stringResource(R.string.settings_connection_summary_desc),
                Icons.Default.Summarize,
                connectionSummaryShown
            ) {
                MainLayoutStore.setSummaryShown(context, remoteOnHome, it)
                connectionSummaryShown = it
            }
            MainBlocksRow()
            MainHiddenRow(
                remoteTile = remoteOnHome,
                openHere = { item ->
                    when (item) {
                        MainItem.REMOTE -> { onOpenRemote(); true }
                        MainItem.DNS_SETTINGS -> { open("dns"); true }
                        MainItem.ROOT -> { open("root"); true }
                        else -> false
                    }
                },
                onRestored = { layoutChanged++ },
            )
            // Like the summary's: the block of the layout now, and this switch its older handle.
            var managedOnMainShown by remember(layoutChanged) { mutableStateOf(MainItem.MANAGED in MainLayoutStore.load(context, remoteOnHome)) }
            SettingsSwitchItem(
                stringResource(R.string.settings_managed_on_main_title),
                stringResource(R.string.settings_managed_on_main_desc),
                Icons.Default.SettingsRemote,
                managedOnMainShown
            ) {
                MainLayoutStore.setManagedShown(context, remoteOnHome, it)
                managedOnMainShown = it
            }
            if (UpdateChannel.selfUpdate(context)) {
                var updateCheckOnLaunch by remember { mutableStateOf(GlobalSettings.isUpdateCheckOnLaunch(context)) }
                SettingsSwitchItem(
                    stringResource(R.string.settings_update_check_title),
                    stringResource(R.string.settings_update_check_desc),
                    Icons.Default.SystemUpdate,
                    updateCheckOnLaunch
                ) {
                    GlobalSettings.setUpdateCheckOnLaunch(context, it)
                    updateCheckOnLaunch = it
                }
                // Under the check it widens: betas of the next version, for whoever asks (docs/RELEASING.md).
                var betaUpdates by remember { mutableStateOf(GlobalSettings.isBetaUpdates(context)) }
                SettingsSwitchItem(
                    stringResource(R.string.settings_update_beta_title),
                    stringResource(R.string.settings_update_beta_desc),
                    Icons.Default.Science,
                    betaUpdates
                ) {
                    GlobalSettings.setBetaUpdates(context, it)
                    betaUpdates = it
                }
            }
        }
        SettingsCard(title = stringResource(R.string.settings_sect_personalization)) {
            if (LocalSettingsColumns.current) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) { firstHalf() }
                    Column(Modifier.weight(1f)) { secondHalf() }
                }
            } else {
                firstHalf()
                Spacer(Modifier.height(12.dp))
                secondHalf()
            }
        }
        // Swipes and the back preview are touch gestures: a television has neither.
        if (place(Feature.TOUCH_GESTURES_HINTS) == Availability.OFFERED) {
            SettingsCardGap()
            SettingsCard(title = stringResource(R.string.gestures_section)) { GesturesSettings() }
        }
    }
}

/**
 * Where a swipe can be set up (core/Gestures.kt): its row, its two preferences, and what it
 * may do — each choice by its stored id and the label it is shown with.
 */
private class SwipeArea(
    val title: Int,
    val desc: Int,
    val icon: ImageVector,
    val keyToEnd: String,
    val keyToStart: String,
    val choices: List<Pair<String, Int>>,
    val toEnd: (android.content.Context) -> String,
    val toStart: (android.content.Context) -> String,
)

private fun peerLabel(s: PeerSwipe) = when (s) {
    PeerSwipe.NONE -> R.string.gesture_none
    PeerSwipe.SEND_FILE -> R.string.gesture_peer_send_file
    PeerSwipe.PING -> R.string.gesture_peer_ping
    PeerSwipe.COPY_IP -> R.string.gesture_peer_copy_ip
    PeerSwipe.EXIT_NODE -> R.string.gesture_peer_exit_node
    PeerSwipe.DETAILS -> R.string.gesture_peer_details
    PeerSwipe.COPY_NAME -> R.string.gesture_peer_copy_name
    PeerSwipe.COPY_AS -> R.string.gesture_peer_copy_as
    PeerSwipe.OPEN_WEB -> R.string.gesture_peer_open_web
}

private fun statusLabel(s: StatusSwipe) = when (s) {
    StatusSwipe.NONE -> R.string.gesture_none
    StatusSwipe.NEXT_ACCOUNT -> R.string.gesture_status_next_account
    StatusSwipe.EXIT_NODE_TOGGLE -> R.string.gesture_status_exit_toggle
    StatusSwipe.EXIT_NODE_PICKER -> R.string.gesture_status_exit_picker
    StatusSwipe.PREV_ACCOUNT -> R.string.gesture_status_prev_account
    StatusSwipe.RECONNECT_RELAYS -> R.string.gesture_status_reconnect_relays
    StatusSwipe.OPEN_PEERS -> R.string.gesture_status_open_peers
    StatusSwipe.OPEN_FILES -> R.string.gesture_status_open_files
    StatusSwipe.OPEN_MAP -> R.string.gesture_status_open_map
    StatusSwipe.OPEN_LOGS -> R.string.gesture_status_open_logs
    StatusSwipe.OPEN_SETTINGS -> R.string.gesture_status_open_settings
    StatusSwipe.SCAN_QR -> R.string.gesture_status_scan_qr
}

private fun fileLabel(s: FileSwipe) = when (s) {
    FileSwipe.NONE -> R.string.gesture_none
    FileSwipe.HIDE -> R.string.gesture_file_hide
    FileSwipe.OPEN -> R.string.gesture_file_open
    FileSwipe.SHARE -> R.string.gesture_file_share
    FileSwipe.SHOW_IN_FOLDER -> R.string.gesture_file_show_in_folder
    FileSwipe.COPY_FOLDER_PATH -> R.string.gesture_file_copy_folder_path
}

private val swipeAreas = listOf(
    SwipeArea(
        R.string.gestures_peer_title, R.string.gestures_peer_desc, Icons.Default.Devices,
        Gestures.KEY_PEER_TO_END, Gestures.KEY_PEER_TO_START,
        PeerSwipe.entries.map { it.id to peerLabel(it) },
        { Gestures.peerToEnd(it).id }, { Gestures.peerToStart(it).id },
    ),
    SwipeArea(
        R.string.gestures_status_title, R.string.gestures_status_desc, Icons.Default.Swipe,
        Gestures.KEY_STATUS_TO_END, Gestures.KEY_STATUS_TO_START,
        StatusSwipe.entries.map { it.id to statusLabel(it) },
        { Gestures.statusToEnd(it).id }, { Gestures.statusToStart(it).id },
    ),
    SwipeArea(
        R.string.gestures_file_title, R.string.gestures_file_desc, Icons.AutoMirrored.Filled.InsertDriveFile,
        Gestures.KEY_FILE_TO_END, Gestures.KEY_FILE_TO_START,
        FileSwipe.entries.map { it.id to fileLabel(it) },
        { Gestures.fileToEnd(it).id }, { Gestures.fileToStart(it).id },
    ),
)

/**
 * The Gestures card: the back preview's switch, and a row per swipe. A row's summary names
 * both directions; tapping it opens one sheet with the two directions side by side, each a
 * row of choices that applies at once. (Two lists one after the other read as the same list
 * opening twice.)
 */
@Composable
private fun GesturesSettings() {
    val context = LocalContext.current
    var predictiveBack by remember { mutableStateOf(Gestures.predictiveBack(context)) }
    SettingsSwitchItem(
        stringResource(R.string.gestures_back_title),
        stringResource(R.string.gestures_back_desc),
        Icons.Default.SwipeLeft,
        predictiveBack
    ) {
        Gestures.setPredictiveBack(context, it)
        predictiveBack = it
    }

    // The stored ids, by preference key: what the summaries show and the sheet marks.
    val chosen = remember {
        mutableStateMapOf<String, String>().apply {
            swipeAreas.forEach { put(it.keyToEnd, it.toEnd(context)); put(it.keyToStart, it.toStart(context)) }
        }
    }
    var picking by remember { mutableStateOf<Int?>(null) }
    val toEndWord = stringResource(R.string.gestures_to_end)
    val toStartWord = stringResource(R.string.gestures_to_start)

    swipeAreas.forEachIndexed { index, area ->
        val labels = area.choices.associate { (id, res) -> id to stringResource(res) }
        SwipeAreaRow(
            area.title,
            "${stringResource(area.desc)}. " + stringResource(
                R.string.gestures_summary,
                labels[chosen[area.keyToEnd]].orEmpty(),
                labels[chosen[area.keyToStart]].orEmpty(),
            ),
            area.icon
        ) { picking = index }
    }

    picking?.let { index ->
        val area = swipeAreas[index]
        // Resolved here, outside the sheet: a sheet is a window of its own (see PickerSheet).
        val title = stringResource(area.title)
        val options = area.choices.map { (id, res) -> id to stringResource(res) }
        SwipeChoiceSheet(
            title = title,
            directions = listOf(toEndWord to area.keyToEnd, toStartWord to area.keyToStart),
            options = options,
            chosen = { chosen[it] },
            onChoose = { key, id ->
                Gestures.set(context, key, id)
                chosen[key] = id
            },
            onDismiss = { picking = null },
        )
    }
}

/**
 * One swipe's two directions in one sheet: under each direction's name, its choices as chips,
 * the current one selected. A tap applies at once; the sheet stays until it is closed.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SwipeChoiceSheet(
    title: String,
    directions: List<Pair<String, String>>,
    options: List<Pair<String, String>>,
    chosen: (key: String) -> String?,
    onChoose: (key: String, id: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            directions.forEachIndexed { i, (word, key) ->
                Text(
                    word,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = if (i == 0) 16.dp else 20.dp, bottom = 8.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEachIndexed { j, (id, label) ->
                        FilterChip(
                            selected = chosen(key) == id,
                            onClick = { onChoose(key, id) },
                            label = { Text(label) },
                            shape = MaterialTheme.shapes.small,
                            modifier = if (i == 0 && j == 0) Modifier.tvInitialFocus() else Modifier,
                        )
                    }
                }
            }
        }
    }
}

// The theme and the icon alone, where Appearance does not carry them.
@Composable
internal fun SettingsThemePage(theme: SettingsThemeChoice) {
    SettingsCard(title = stringResource(R.string.tv_settings_theme)) {
        ThemeChips(theme)
        ThemePalette(theme)
        DynamicColorRow(theme)
        AmoledRow(theme)
        Spacer(Modifier.height(12.dp))
        AppIconRow()
    }
}

// A row of the Gestures card. Its title is a resource passed on, the way SettingsCatalogTest reads it.
@Composable
private fun SwipeAreaRow(title: Int, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    SettingsClickableItem(stringResource(title), subtitle, icon, onClick = onClick)
}
