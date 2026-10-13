package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.models.PeerData

/*
 * A television has no clipboard to copy a peer's address into and no keyboard to paste it with:
 * where a phone's button copies a value, the remote's shows it — large, from across the room,
 * and as a QR code where it is an address or a link, for a phone's camera to carry away.
 */

/** One value to read: [label] says what it is, [text] is the value, [qr] draws a code for it. */
internal class ShownValue(val label: String, val text: String, val qr: Boolean)

/** The words of the dialog, resolved by the screen: a window of its own answers in the system's language. */
internal class ShownValueStrings(val close: String, val scan: String, val qrImage: String, val qrTooLong: String)

@Composable
internal fun rememberShownValueStrings() = ShownValueStrings(
    close = stringResource(R.string.action_close),
    scan = stringResource(R.string.tv_value_scan),
    qrImage = stringResource(R.string.qr_image),
    qrTooLong = stringResource(R.string.qr_too_long),
)

/** The forms worth a code: an address, the name that resolves to one, a link. A command is not. */
internal fun PeerCopyKind.hasQr(): Boolean = when (this) {
    PeerCopyKind.FULL_NAME, PeerCopyKind.IPV4, PeerCopyKind.IPV6, PeerCopyKind.WEB -> true
    else -> false
}

/**
 * Every form of [peer] that "Copy as…" offers, to read: the list on the left, and for the form
 * the remote is on the value large on the right, with its code. Nothing is copied.
 */
@Composable
internal fun PeerShowAsDialog(peer: PeerData, isSelf: Boolean, names: MagicDnsNames, onDismiss: () -> Unit) {
    val forms = rememberPeerCopyForms(peer, isSelf, names)
    val nameOf = rememberPeerFormNames()
    val strings = rememberShownValueStrings()
    ValueDialog(
        title = peer.getDisplayName(),
        values = forms.map { ShownValue(nameOf(it).first, it.text, qr = it.kind.hasQr()) },
        strings = strings,
        onDismiss = onDismiss
    )
}

/**
 * [values] in a dialog the width of the screen: with more than one, a list whose focused row is
 * the one shown; the value itself in a pane the arrows can scroll; Close below. Back closes it.
 */
@Composable
internal fun ValueDialog(title: String, values: List<ShownValue>, strings: ShownValueStrings, onDismiss: () -> Unit) {
    if (values.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var selected by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ValueDialogContent(title, values, selected, onSelect = { selected = it }, strings, onDismiss)
    }
}

/** What [ValueDialog] holds, without its window: the renderer has none. [selected] is the value on the right. */
@Composable
internal fun ValueDialogContent(
    title: String,
    values: List<ShownValue>,
    selected: Int,
    onSelect: (Int) -> Unit,
    strings: ShownValueStrings,
    onDismiss: () -> Unit,
) {
    val value = values[selected.coerceIn(0, values.lastIndex)]
    val style = rememberQrStyle()
    val scroll = rememberScrollState()
    LaunchedEffect(selected) { scroll.scrollTo(0) }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(LocalTvSafeArea.current).widthIn(max = 880.dp).fillMaxWidth().heightIn(max = 460.dp)
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.weight(1f, fill = false).padding(top = 8.dp)) {
                if (values.size > 1) {
                    LazyColumn(Modifier.width(300.dp).fillMaxHeight().tvFocusGroup()) {
                        itemsIndexed(values) { i, v ->
                            ValueRow(
                                v, shown = i == selected,
                                onFocus = { onSelect(i) },
                                modifier = if (i == 0) Modifier.tvInitialFocus() else Modifier
                            )
                        }
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .tvReadingPane(scroll)
                        .verticalScroll(scroll)
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(value.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        value.text.withBreakOpportunities(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (value.text.length > 40) 20.sp else 28.sp,
                        textAlign = TextAlign.Center
                    )
                    if (value.qr) {
                        QrCodeImage(
                            text = value.text,
                            style = style,
                            contentDescription = strings.qrImage,
                            tooLong = strings.qrTooLong,
                            modifier = Modifier.size(190.dp)
                        )
                        Text(strings.scan, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDismiss,
                    modifier = if (values.size > 1) Modifier else Modifier.tvInitialFocus()
                ) { Text(strings.close) }
            }
        }
    }
}

/** A value in the dialog's list: the label small over the text; [onFocus] shows it on the right. */
@Composable
private fun ValueRow(value: ShownValue, shown: Boolean, onFocus: () -> Unit, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.medium
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .tvFocusRing(source, shape)
            .clip(shape)
            .background(if (shown) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onFocus)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(value.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
