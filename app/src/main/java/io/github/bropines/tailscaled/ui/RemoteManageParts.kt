package io.github.bropines.tailscaled.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.ManagedDenial
import io.github.bropines.tailscaled.core.ManagedGate
import io.github.bropines.tailscaled.core.ManagedLock
import io.github.bropines.tailscaled.core.ManagedSetting
import io.github.bropines.tailscaled.core.lock

/*
 * What the managing page (ui/RemoteManagePage.kt) is built of: the card a group stands in, the
 * rows inside it, and the one line that says why a thing does not answer. The switch and the
 * row that opens something are Settings' own (SettingsSwitchItem, SettingsClickableItem), so a
 * device managed from afar reads like the settings of the one in hand.
 */

/** The groups of the page, in the order they are drawn. */
enum class ManageGroup { CONNECTION, FILES, SHARES, PERMISSIONS, DEVICE, DIAGNOSTICS, PAIRING }

/** How a line of status is meant. */
internal enum class ManageTone { PLAIN, GOOD, WARN }

/**
 * A group of the page: an icon, a title and one line that says where things stand, over rows
 * that fold away. The header is one stop for a remote and OK folds the group in and out.
 * [attention] tints the line: something in the group wants looking at.
 */
@Composable
internal fun ManageCard(
    icon: ImageVector,
    title: String,
    summary: String?,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** For the header, which is the group's stop for a remote: where the remote starts, say. */
    headerModifier: Modifier = Modifier,
    attention: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val source = remember { MutableInteractionSource() }
    val state = stringResource(if (open) R.string.rmanage_fold_close else R.string.rmanage_fold_open)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                headerModifier
                    .fillMaxWidth()
                    .tvFocusRing(source, shape)
                    .clip(shape)
                    .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClickLabel = state, onClick = onToggle)
                    .semantics { stateDescription = state }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GroupIcon(icon)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (!summary.isNullOrEmpty()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (attention) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = open) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
            }
        }
    }
}

/** A group's icon on a round ground of its own colour. */
@Composable
internal fun GroupIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(modifier.size(36.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** A small heading inside a group, over the rows it names. */
@Composable
internal fun ManageSubtitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

/** One line of status with its mark: a fact, a good end, or something to look at. */
@Composable
internal fun ManageLine(text: String, modifier: Modifier = Modifier, tone: ManageTone = ManageTone.PLAIN, icon: ImageVector? = null) {
    val tint = when (tone) {
        ManageTone.PLAIN -> MaterialTheme.colorScheme.onSurfaceVariant
        ManageTone.GOOD -> MaterialTheme.colorScheme.primary
        ManageTone.WARN -> MaterialTheme.colorScheme.tertiary
    }
    val mark = icon ?: when (tone) {
        ManageTone.PLAIN -> Icons.Default.Info
        ManageTone.GOOD -> Icons.Default.CheckCircle
        ManageTone.WARN -> Icons.Default.Warning
    }
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(mark, null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (tone == ManageTone.PLAIN) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** The one sentence for a thing that is there and is not this caller's to use. */
@Composable
internal fun denialText(denial: ManagedDenial, name: String): String = when (denial) {
    ManagedDenial.NEEDS_FULL -> stringResource(R.string.rmanage_deny_full, name)
    ManagedDenial.OWNER_ONLY -> stringResource(R.string.rmanage_deny_owner)
}

/** The same in two words, for the line of a folded group. */
@Composable
internal fun denialShort(denial: ManagedDenial): String = stringResource(
    when (denial) {
        ManagedDenial.NEEDS_FULL -> R.string.rmanage_deny_full_short
        ManagedDenial.OWNER_ONLY -> R.string.rmanage_deny_owner_short
    }
)

/**
 * The same for a row that does not answer. "Only an owner can do this" is short as it is;
 * for Full control the row says what it waits for, and its card says once where that is
 * switched on.
 */
@Composable
internal fun denialRow(denial: ManagedDenial): String = stringResource(
    when (denial) {
        ManagedDenial.NEEDS_FULL -> R.string.rmanage_deny_full_short
        ManagedDenial.OWNER_ONLY -> R.string.rmanage_deny_owner
    }
)

/** Why the rows under it do not answer, said once for all of them. Nothing for a gate that is open. */
@Composable
internal fun DeniedNote(gate: ManagedGate, name: String, modifier: Modifier = Modifier) {
    val denial = gate.denial ?: return
    ManageLine(denialText(denial, name), modifier, icon = Icons.Default.Lock)
}

/**
 * Why a switch cannot be moved from here, short enough for its row. Null when it can — and
 * for a guest, whose every switch stands still for one reason that the card says once.
 */
@Composable
internal fun lockText(lock: ManagedLock): String? = when (lock) {
    ManagedLock.NONE, ManagedLock.OWNER_ONLY -> null
    ManagedLock.UNKNOWN -> stringResource(R.string.remote_setting_unknown)
    ManagedLock.READ_ONLY -> stringResource(R.string.rmanage_lock_read_only)
    ManagedLock.NEEDS_FULL -> stringResource(R.string.rmanage_lock_needs_full)
    ManagedLock.OFF_AT_DEVICE -> stringResource(R.string.rmanage_lock_off_at_device)
    ManagedLock.ON_AT_DEVICE -> stringResource(R.string.rmanage_lock_on_at_device)
}

/**
 * A row of a group in the shape of a Settings row (ExplainedRow): a tinted surface that is
 * one button and one stop for a remote, an icon, a title and a line under it. The line is
 * either [help] — folded to two lines like every explanation in the app — or [reason]: why
 * the row does not answer, short, and shown whole, since a reason nobody can read is none.
 */
@Composable
internal fun ManageRow(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    reason: String? = null,
    /** What the row holds now, when that is the thing to read; it stands in the explanation's place. */
    value: String? = null,
    trailing: @Composable () -> Unit,
) {
    val folded = remember(help) { mutableStateOf(false) }
    val live = enabled && reason == null
    val scheme = MaterialTheme.colorScheme
    ExplainedRow(
        onClick = { if (live) onClick() },
        help = folded,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = scheme.surfaceVariant.copy(alpha = if (live) 0.3f else 0.12f),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (live) scheme.primary else scheme.outline, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = if (live) scheme.onSurface else scheme.onSurfaceVariant)
                when {
                    reason != null -> Text(reason, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    value != null -> Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (live) scheme.onSurfaceVariant else scheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    help != null -> HelpText(help, color = if (live) scheme.onSurfaceVariant else scheme.outline, expanded = folded, inClickableRow = true)
                }
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * A switch of the managed device. It moves when the device lets this caller move it and the
 * page can ask; otherwise it stands disabled, and the line under its title is the reason
 * instead of the explanation — one reason, the device's own.
 */
@Composable
internal fun ManageSwitch(
    setting: ManagedSetting,
    title: String,
    help: String,
    icon: ImageVector,
    owner: Boolean,
    usable: Boolean,
    onChange: (Boolean) -> Unit,
    /** Said under the title while the switch moves: what the tailnet made of it, say. */
    note: String? = null,
) {
    val lock = setting.lock(owner)
    val moves = usable && lock == ManagedLock.NONE
    ManageRow(
        icon = icon,
        title = title,
        enabled = moves,
        onClick = { onChange(!setting.on) },
        help = note ?: help,
        reason = lockText(lock),
    ) {
        // The row toggles it: for a remote the switch is no stop of its own.
        Switch(
            checked = setting.on,
            onCheckedChange = if (moves) onChange else null,
            modifier = Modifier.tvNoFocus(),
            enabled = moves,
            // A switch that cannot be moved must still say which way it stands: Material's
            // disabled "on" is nearly the colour of "off" on a dark ground.
            colors = SwitchDefaults.colors(
                disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                disabledCheckedThumbColor = MaterialTheme.colorScheme.surface,
            ),
        )
    }
}

/** A row that opens something: a picker, a browser, a page of text. [reason] disables it and says why. */
@Composable
internal fun ManageOpenRow(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** What it holds now, when that is the thing to read: the exit node in use. */
    value: String? = null,
    help: String? = null,
    reason: String? = null,
) {
    ManageRow(icon = icon, title = title, enabled = enabled, onClick = onClick, modifier = modifier, help = help, reason = reason, value = value) {
        Icon(Icons.Default.ChevronRight, null, tint = if (enabled && reason == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline)
    }
}

/** A label and its value on one line; [note] under the value, quieter. */
@Composable
internal fun ManageFact(label: String, value: String, modifier: Modifier = Modifier, note: String? = null, tone: ManageTone = ManageTone.PLAIN) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp)
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (tone == ManageTone.WARN) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End
            )
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

/** A button of the page: [primary] for the one thing a group is opened for, tonal for the rest. */
@Composable
internal fun ManageButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
    working: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        if (working) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    val shape = MaterialTheme.shapes.medium
    val size = modifier.heightIn(min = 46.dp)
    if (primary) Button(onClick = onClick, enabled = enabled, shape = shape, modifier = size) { content() }
    else FilledTonalButton(onClick = onClick, enabled = enabled, shape = shape, modifier = size) { content() }
}

/** Space between two blocks of one group. */
@Composable
internal fun ManageGap() = Spacer(Modifier.size(8.dp))

/** A small tag: the level, "Suggested", "Paired". */
@Composable
internal fun ManageTag(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (strong) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (strong) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** Blocks of a group one under another, with the group's own spacing. */
@Composable
internal fun ManageBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
