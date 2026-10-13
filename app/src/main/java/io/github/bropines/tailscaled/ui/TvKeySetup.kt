package io.github.bropines.tailscaled.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AccountManager
import io.github.bropines.tailscaled.core.HandoverPhase
import io.github.bropines.tailscaled.core.KeyHandover
import io.github.bropines.tailscaled.core.KeyKind
import io.github.bropines.tailscaled.core.KeyRoad
import io.github.bropines.tailscaled.core.SetupReceiveStatus
import io.github.bropines.tailscaled.core.TvKeyStep
import io.github.bropines.tailscaled.core.TvKeyStop
import io.github.bropines.tailscaled.core.formatCheckCode
import io.github.bropines.tailscaled.core.formatClock
import io.github.bropines.tailscaled.core.tvKeyStep
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * A television signs in with a key a phone sends it (appctr/setup.go, and
 * core/KeySetup.kt for what the bridge's answers mean here). The television
 * draws a code; TailSocks on a phone reads it (ui/KeySendSheet.kt) and sends an
 * auth key — over the local network, or through a relay when the two devices
 * share none; this screen shows who sent it and a check code the phone shows
 * too, and the key is used only after Confirm.
 *
 * Everything on it is a remote's: one button a step, two where the person
 * decides — and there the remote starts on Reject.
 */

/** How long "Signed in" stays up before the screen hands back. */
private const val SIGNED_IN_PAUSE_MS = 1500L

/** How often the session is asked where it stands: the time left is the bridge's to count. */
private const val LOOK_EVERY_MS = 1000L

/** The same while it has no code yet: the code is drawn as soon as the relay has answered. */
private const val LOOK_EVERY_PREPARING_MS = 300L

/**
 * The way into [TvKeySetupScreen], for the sign-in screen: an ordinary stop for
 * the remote, never where it starts — signing in by the account's own code
 * comes first.
 */
@Composable
fun TvKeySetupButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Default.Key, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.tvkey_entry), maxLines = 1)
    }
}

/**
 * The whole exchange on the television, by itself: opens a session and draws
 * its code, asks about the key a phone sent, signs in with it and calls
 * [onSignedIn]. [onClose] is Back and Cancel. Leaving the screen — closing it,
 * or the app going out of sight — ends the session, and a new code is drawn on
 * the way back; a key already confirmed is carried through regardless
 * (KeyHandover).
 */
@Composable
fun TvKeySetupScreen(onSignedIn: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<SetupReceiveStatus?>(null) }
    val handover by KeyHandover.phase.collectAsState()

    LaunchedEffect(lifecycleOwner) {
        // How an earlier key ended is not this visit's news.
        KeyHandover.forgetOutcome()
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    val now = KeyHandover.receive()
                    status = now
                    delay(if (now.state == "preparing") LOOK_EVERY_PREPARING_MS else LOOK_EVERY_MS)
                }
            } finally {
                KeyHandover.close()
                status = null
            }
        }
    }

    // Confirm shows at once; the bridge's answer follows within the second.
    // Kept by the check code: a later bundle has another and is asked about anew.
    var confirmedCode by remember { mutableStateOf<String?>(null) }
    val polled = tvKeyStep(status, handover)
    val step = if (polled is TvKeyStep.Offer && polled.checkCode == confirmedCode) TvKeyStep.SigningIn else polled

    val latestOnSignedIn by rememberUpdatedState(onSignedIn)
    if (step == TvKeyStep.SignedIn) {
        LaunchedEffect(Unit) {
            delay(SIGNED_IN_PAUSE_MS)
            latestOnSignedIn()
        }
    }

    // A code nobody can read under a screensaver is no code.
    if (step is TvKeyStep.Code || step is TvKeyStep.Offer) {
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }

    BackHandler(onBack = onClose)
    TvKeySetupContent(
        step = step,
        currentServer = remember { currentLoginServer(context) },
        onConfirm = {
            confirmedCode = (polled as? TvKeyStep.Offer)?.checkCode
            KeyHandover.confirm(context)
        },
        onReject = { scope.launch { status = KeyHandover.reject() } },
        onRenew = {
            status = null
            scope.launch { status = KeyHandover.renew() }
        },
        onClose = onClose,
    )
}

/** The control server of the active profile, "" for Tailscale's own. */
private fun currentLoginServer(context: Context): String {
    val account = AccountManager.getActiveAccount(context)
    return context.getSharedPreferences("appctr_${account.id}", Context.MODE_PRIVATE).getString("login_server", "").orEmpty().trim()
}

/** What [TvKeySetupScreen] shows for a [step]; apart from the session so a preview can draw every one. */
@Composable
internal fun TvKeySetupContent(
    step: TvKeyStep,
    currentServer: String,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onRenew: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.tvkey_title), onBack = onClose) }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            // A television, a tablet on its side: the code beside the words. A
            // phone showing the TV interface stacks them and scrolls.
            val wide = maxWidth >= 600.dp && maxWidth > maxHeight
            when (step) {
                // A few seconds, while the session asks for the relay: said, so the wait is not taken for a hang.
                TvKeyStep.Opening -> Centered {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.tvkey_opening), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                }
                is TvKeyStep.Code -> CodePane(step, wide, onClose)
                is TvKeyStep.Offer -> OfferPane(step, currentServer, wide, onConfirm, onReject)
                TvKeyStep.SigningIn -> Centered {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.tvkey_signing_in), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                }
                TvKeyStep.SignedIn -> Centered {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                    Text(stringResource(R.string.tvkey_signed_in), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                }
                is TvKeyStep.Stopped -> StoppedPane(step.why, onRenew)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) { content() }
}

/**
 * Side by side when [wide], one under the other and scrolling when not. [aside]
 * is the code or the check code: as tall as the screen has room for, and
 * [asideWidth] wide — or as wide as it makes itself, for a square.
 */
@Composable
private fun TwoPanes(wide: Boolean, asideWidth: Dp? = null, aside: @Composable (Modifier) -> Unit, main: @Composable () -> Unit) {
    if (wide) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The main pane scrolls by itself if a large font outgrows it; the remote's
            // buttons are in it, and focus brings them into view.
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { main() }
            aside(if (asideWidth != null) Modifier.fillMaxHeight().width(asideWidth) else Modifier.fillMaxHeight())
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            aside(Modifier.widthIn(max = 360.dp).fillMaxWidth())
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { main() }
        }
    }
}

@Composable
private fun CodePane(step: TvKeyStep.Code, wide: Boolean, onClose: () -> Unit) {
    TwoPanes(
        wide = wide,
        aside = { modifier ->
            // The plain code, black on white, whatever Settings chose for the others:
            // this one is read from across a room, by a camera that sees a television.
            QrCodeImage(text = step.text, modifier = modifier, style = QrStyle(QrShape.SQUARES, QrPalette.MONO))
        },
    ) {
        Text(stringResource(R.string.tvkey_scan_line), style = MaterialTheme.typography.headlineSmall)
        FoldedHelp(stringResource(if (step.relay) R.string.tvkey_scan_help else R.string.tvkey_scan_help_lan))
        // A television the relay alone leads to has no address to be named by.
        Fact(
            Icons.Default.Tv,
            if (step.addresses.isEmpty()) stringResource(R.string.tvkey_this_tv_named, step.session)
            else stringResource(R.string.tvkey_this_tv, step.session, step.addresses.joinToString(", ")),
        )
        // Through the relay the code leads here from any network; without it, from this device's own alone.
        if (step.relay) Fact(Icons.Default.Public, stringResource(R.string.tvkey_any_network))
        else Fact(Icons.Default.Wifi, stringResource(R.string.tvkey_same_network))
        Fact(Icons.Default.Schedule, stringResource(R.string.tvkey_waiting, formatClock(step.secondsLeft)))
        if (step.refused > 0) {
            Fact(
                Icons.Default.Warning,
                pluralStringResource(R.plurals.tvkey_refused, step.refused, step.refused),
                color = MaterialTheme.colorScheme.error,
            )
        }
        // The one stop there is while the code is up, and where the remote starts.
        OutlinedButton(onClick = onClose, modifier = Modifier.padding(top = 4.dp).tvInitialFocus()) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun OfferPane(step: TvKeyStep.Offer, currentServer: String, wide: Boolean, onConfirm: () -> Unit, onReject: () -> Unit) {
    TwoPanes(
        wide = wide,
        asideWidth = 340.dp,
        aside = { modifier ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        stringResource(R.string.tvkey_check_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatCheckCode(step.checkCode),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        // Six digits a sofa away; smaller only where a large font would cut them.
                        autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 60.sp),
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        stringResource(R.string.tvkey_check_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
    ) {
        Text(stringResource(R.string.tvkey_offer_title), style = MaterialTheme.typography.headlineSmall)
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // A sender that came through the relay has no address worth showing: the road is named in its place.
                val relayed = step.road == KeyRoad.RELAY
                Detail(
                    stringResource(R.string.tvkey_offer_from),
                    when {
                        step.sender.isEmpty() -> if (relayed) stringResource(R.string.tvkey_offer_relay_unnamed) else step.address
                        relayed -> stringResource(R.string.tvkey_offer_named, step.sender, stringResource(R.string.tvkey_offer_relay))
                        step.address.isEmpty() -> step.sender
                        else -> stringResource(R.string.tvkey_offer_named, step.sender, step.address)
                    },
                )
                // The prefix says what kind of key it is; the key itself never leaves the bridge for a screen.
                val kind = when (step.keyKind) {
                    KeyKind.TAILSCALE_AUTH -> R.string.tvkey_kind_tailscale_auth
                    KeyKind.TAILSCALE_CLIENT -> R.string.tvkey_kind_tailscale_client
                    KeyKind.HEADSCALE_AUTH -> R.string.tvkey_kind_headscale_auth
                    KeyKind.OTHER -> R.string.tvkey_kind_other
                }
                Detail(
                    stringResource(R.string.tvkey_offer_key),
                    if (step.keyKind == KeyKind.OTHER) stringResource(kind)
                    else stringResource(R.string.tvkey_offer_named, "${step.keyKind.prefix}-…", stringResource(kind)),
                )
                // A sender picks the server too, and a stranger's server is a stranger's network.
                val current = currentServer.ifEmpty { stringResource(R.string.tvkey_server_default) }
                when {
                    step.server.isEmpty() -> Detail(stringResource(R.string.tvkey_offer_server), stringResource(R.string.tvkey_server_same, current))
                    step.server == currentServer -> Detail(stringResource(R.string.tvkey_offer_server), step.server)
                    else -> Detail(
                        stringResource(R.string.tvkey_offer_server),
                        step.server,
                        note = stringResource(R.string.tvkey_server_new),
                    )
                }
            }
        }
        Fact(Icons.Default.Schedule, stringResource(R.string.tvkey_offer_time, formatClock(step.secondsLeft)))
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Where the remote starts: an OK pressed a moment too early says no.
            OutlinedButton(onClick = onReject, modifier = Modifier.tvInitialFocus()) { Text(stringResource(R.string.tvkey_reject)) }
            Button(onClick = onConfirm) { Text(stringResource(R.string.action_confirm)) }
        }
    }
}

@Composable
private fun StoppedPane(why: TvKeyStop, onRenew: () -> Unit) {
    val (icon: ImageVector, text: Int) = when (why) {
        TvKeyStop.NO_NETWORK -> Icons.Default.WifiOff to R.string.tvkey_stop_no_network
        TvKeyStop.CANNOT_LISTEN -> Icons.Default.WifiOff to R.string.tvkey_stop_cannot_listen
        TvKeyStop.TOO_MANY_ATTEMPTS -> Icons.Default.Warning to R.string.tvkey_stop_too_many
        TvKeyStop.NETWORK_CHANGED -> Icons.Default.WifiOff to R.string.tvkey_stop_network_changed
        TvKeyStop.SERVICE_STOPPED -> Icons.Default.ErrorOutline to R.string.tvkey_stop_service
        TvKeyStop.CODE_EXPIRED -> Icons.Default.Schedule to R.string.tvkey_stop_expired
        TvKeyStop.NOT_ANSWERED -> Icons.Default.Schedule to R.string.tvkey_stop_not_answered
        TvKeyStop.REJECTED -> Icons.Default.Block to R.string.tvkey_stop_rejected
        TvKeyStop.KEY_GONE -> Icons.Default.Schedule to R.string.tvkey_stop_key_gone
        TvKeyStop.NOT_SIGNED_IN -> Icons.Default.ErrorOutline to R.string.tvkey_stop_not_signed_in
        TvKeyStop.UNKNOWN -> Icons.Default.ErrorOutline to R.string.tvkey_stop_unknown
    }
    // Nothing was wrong with the code itself: the same thing again, not a new start.
    val retry = why == TvKeyStop.NO_NETWORK || why == TvKeyStop.CANNOT_LISTEN || why == TvKeyStop.UNKNOWN
    Centered {
        Box(
            Modifier.size(56.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(28.dp))
        }
        Text(
            stringResource(text),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Button(onClick = onRenew, modifier = Modifier.tvInitialFocus()) {
            Text(stringResource(if (retry) R.string.action_retry else R.string.tvkey_new_code))
        }
    }
}

/** A line of what is going on, under a small icon. */
@Composable
private fun Fact(icon: ImageVector, text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** One thing a bundle says, under its name; [note] is what the person should think twice about. */
@Composable
private fun Detail(label: String, value: String, note: String? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = if (note != null) FontWeight.Bold else null)
        if (note != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * [HelpText], reachable with a remote: on a television the fold is a row of its
 * own that OK opens and closes, since the ⓘ alone is nothing a remote can find.
 * Anywhere else it is the plain folded text.
 */
@Composable
private fun FoldedHelp(text: String) {
    if (!isTv) {
        HelpText(text, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val open = remember(text) { mutableStateOf(false) }
    Surface(
        onClick = { open.value = !open.value },
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        // The ring is drawn inside the row: the words keep clear of it, and still line up with the rest.
        modifier = Modifier.offset(x = -FoldedHelpInset),
    ) {
        HelpText(
            text,
            style = MaterialTheme.typography.bodyMedium,
            expanded = open,
            inClickableRow = true,
            modifier = Modifier.padding(FoldedHelpInset),
        )
    }
}

private val FoldedHelpInset = 8.dp
