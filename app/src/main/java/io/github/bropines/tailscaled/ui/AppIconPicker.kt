package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Settings → Appearance: the launcher icon. A row showing the current one,
 * opening a sheet of all of them; a pick applies at once (core/AppIcons.kt).
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun AppIconRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(AppIcons.current(context)) }
    var open by rememberSaveable { mutableStateOf(false) }
    val help = remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(vertical = 4.dp).clip(MaterialTheme.shapes.medium).combinedClickable(
            onClick = { open = true },
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); help.value = !help.value }
        )
    ) {
        ListItem(
            supportingContent = { HelpText(stringResource(R.string.icon_note), expanded = help, inClickableRow = true) },
            leadingContent = { AppIconImage(current, 40.dp) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        ) { Text(stringResource(R.string.icon_title)) }
    }
    if (open) {
        AppIconSheet(
            selected = current,
            onPick = { v ->
                current = v
                val app = context.applicationContext
                scope.launch(Dispatchers.IO) { AppIcons.select(app, v) }
            },
            onDismiss = { open = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppIconSheet(selected: AppIcons.Variant, onPick: (AppIcons.Variant) -> Unit, onDismiss: () -> Unit) {
    // The parent's, all three: the sheet's own window answers in the system
    // language — see wrapContextWithLocale().
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val sheetState = rememberFullSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalResources provides resources
        ) {
            AppIconSheetContent(selected) { v ->
                onPick(v)
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
    }
}

/** What the sheet holds, without the sheet: a preview has no windows. */
@Composable
internal fun AppIconSheetContent(selected: AppIcons.Variant, onPick: (AppIcons.Variant) -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
    ) {
        Text(
            stringResource(R.string.icon_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(80.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .selectableGroup(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (group in AppIcons.Group.entries) {
                val icons = AppIcons.ALL.filter { it.group == group }
                if (icons.isEmpty()) continue
                item(key = group.name, span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(group.title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)
                    )
                }
                items(icons, key = { it.id }) { v ->
                    AppIconCell(v, v === selected) { onPick(v) }
                }
            }
        }
    }
}

@Composable
private fun AppIconCell(v: AppIcons.Variant, isSelected: Boolean, onClick: () -> Unit) {
    val name = stringResource(v.title)
    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            // The ring sits outside the icon, so a picked one keeps its size.
            Box(
                Modifier
                    .size(64.dp)
                    .border(3.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                    .padding(6.dp)
            ) {
                AppIconImage(v, 52.dp, contentDescription = name)
            }
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else null,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Said once, by the icon.
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

/**
 * A launcher icon as a round launcher draws it: its two layers, 108 dp in the
 * icon's own units, of which the mask shows the middle 72. Drawn from the
 * vector layers rather than the mipmap, which Compose cannot load as an
 * adaptive icon and which below Android 8 is a bitmap.
 */
@Composable
internal fun AppIconImage(v: AppIcons.Variant, size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Box(modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        val layer = Modifier.requiredSize(size * 1.5f)
        Image(painterResource(v.background), contentDescription, layer)
        Image(painterResource(v.foreground), null, layer)
        // A hairline, or a black icon is lost on the dark theme and a light one on the light.
        Box(Modifier.matchParentSize().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape))
    }
}
