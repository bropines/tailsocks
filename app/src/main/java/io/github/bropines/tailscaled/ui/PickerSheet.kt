package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * One row of a [PickerSheet]. Its words arrive already resolved: the sheet is a
 * window of its own and would look them up in the system language — see
 * wrapContextWithLocale().
 */
data class PickerOption<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
    /** A second line, for when the label alone does not tell the rows apart. */
    val supporting: String? = null,
)

/**
 * A choice among a handful of options, as a bottom sheet — the shape of the
 * account switcher and the exit-node picker on the main screen, for every
 * smaller picker elsewhere. These used to be dropdown menus: they opened
 * wherever their anchor sat (under the thumb, or clipped at a tablet's edge),
 * gave each option one cramped line and did not mark the current value.
 *
 * With [selected] set the rows are a radio group and the current one carries a
 * check; left null (a command history, say) they are plain actions. A pick is
 * applied at once, so the caller's state has changed by the time the sheet has
 * slid away, and [onDismiss] follows when it is gone — the one place a caller
 * clears its "open" flag, for a pick and a swipe alike.
 *
 * No minimum height, unlike the exit-node picker: that one fills in after it
 * opens and was held at its final height so it would not jump, whereas every
 * list here is known before the sheet appears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PickerSheet(
    title: String,
    options: List<PickerOption<T>>,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    selected: T? = null,
    monospace: Boolean = false,
) {
    // Opened half height, a sheet settles to its content a frame later and
    // reads as a jump.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        PickerSheetContent(title, options, selected, monospace) { value ->
            onPick(value)
            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
        }
    }
}

/**
 * What a [PickerSheet] holds, without the sheet. Apart so that a preview can
 * draw it: the renderer has no windows, and a sheet is one.
 */
@Composable
internal fun <T> PickerSheetContent(
    title: String,
    options: List<PickerOption<T>>,
    selected: T?,
    monospace: Boolean,
    onRowClick: (T) -> Unit
) {
    // Past this the list scrolls under a title that stays put; a dozen rows
    // reach it in landscape.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    val choosesOne = selected != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .weight(1f, fill = false)
                .then(if (choosesOne) Modifier.selectableGroup() else Modifier),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(options) { option ->
                PickerRow(
                    option = option,
                    isSelected = choosesOne && option.value == selected,
                    choosesOne = choosesOne,
                    monospace = monospace
                ) { onRowClick(option.value) }
            }
        }
    }
}

@Composable
private fun <T> PickerRow(
    option: PickerOption<T>,
    isSelected: Boolean,
    choosesOne: Boolean,
    monospace: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    // A radio row says "selected" to TalkBack on its own, so the check needs
    // no words of its own.
    val action = if (choosesOne) {
        Modifier.selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Surface(
        // Clipped before the click, so the ripple keeps the row's corners.
        modifier = Modifier.fillMaxWidth().clip(shape).then(action),
        shape = shape,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 52.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            option.icon?.let { icon ->
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontFamily = if (monospace) FontFamily.Monospace else null,
                    // A command is one line, cut at its end; a label may take two.
                    maxLines = if (monospace) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                option.supporting?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isSelected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
