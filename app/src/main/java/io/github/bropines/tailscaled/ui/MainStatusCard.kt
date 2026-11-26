package io.github.bropines.tailscaled.ui

import androidx.compose.animation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/*
 * The status card at the top of the main screen (MainScreen in MainActivity.kt): its state,
 * colour and tap action, and the second line that shrinks a little to stay on one line before
 * it wraps. The decisions behind what it says live in MainStatusLogic.kt.
 */

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusCard(
    state: String,
    isProcessing: Boolean,
    isTunEnabled: Boolean,
    isFullTunnel: Boolean,
    /** TUN mode, with the slot taken by another VPN app: a proxy for now. */
    isTunDisplaced: Boolean = false,
    isRootEnabled: Boolean = false,
    isYieldedToForeignVpn: Boolean = false,
    isSharedWithForeignVpn: Boolean = false,
    /** For DEGRADED: the problem, in a few words, shown as the subtitle. */
    issueTitle: String? = null,
    /** Shown in place of the card's own subtitle while it is set. */
    aside: String? = null,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    onToggle: () -> Unit
) {
    // Six states, because the service being up says little about the tailnet:
    // STOPPED, STARTING (the service), CONNECTING (daemon up, no map yet),
    // ACTIVE, DEGRADED (up, but warnings say traffic is affected) and
    // NEEDS_LOGIN. The colours move between them instead of jumping.
    val scheme = MaterialTheme.colorScheme
    val on = state != "STOPPED"
    // Progress reads calm (secondary), a problem or a pending sign-in warm
    // (tertiary): the two must not look alike at a glance.
    val targetBackground = when (state) {
        "ACTIVE" -> scheme.primaryContainer
        "STARTING", "CONNECTING" -> scheme.secondaryContainer
        "DEGRADED", "NEEDS_LOGIN", "NEEDS_APPROVAL" -> scheme.tertiaryContainer
        else -> scheme.surfaceContainerHigh
    }
    val targetContent = when (state) {
        "ACTIVE" -> scheme.onPrimaryContainer
        "STARTING", "CONNECTING" -> scheme.onSecondaryContainer
        "DEGRADED", "NEEDS_LOGIN", "NEEDS_APPROVAL" -> scheme.onTertiaryContainer
        else -> scheme.onSurfaceVariant
    }
    val backgroundColor by animateColorAsState(targetBackground, label = "status_bg")
    val contentColor by animateColorAsState(targetContent, label = "status_fg")
    val haptics = LocalHapticFeedback.current
    val shape = MaterialTheme.shapes.extraLarge
    val stateWord = stringResource(if (on) R.string.main_status_state_on else R.string.main_status_state_off)
    val actionWord = stringResource(if (on) R.string.main_status_action_stop else R.string.main_status_action_start)
    val tv = isTv
    // A phone's "tap" is the remote's OK on a television.
    val touchWording = Feature.TOUCH_GESTURES_HINTS.offered()
    val interactions = remember { MutableInteractionSource() }

    Surface(
        shape = shape,
        color = backgroundColor,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 130.dp)
            .animateContentSize()
            // Ahead of the clip, which would cut the ring's corners off.
            .tvFocusRing(interactions, shape)
            // Clipped before the click, so the ripple follows the rounded card
            // instead of spilling out of it as a rectangle.
            .clip(shape)
            .semantics {
                role = Role.Switch
                stateDescription = stateWord
            }
            .combinedClickable(
                interactionSource = interactions,
                indication = androidx.compose.foundation.LocalIndication.current,
                // A television keeps the card a stop while it works — a target that goes
                // away takes the remote's place on the screen with it — and the press is
                // dropped by the caller instead.
                enabled = !isProcessing || tv,
                onClickLabel = actionWord,
                onClick = {
                    haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onToggle()
                },
                onLongClick = onLongPress
            ),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val busy = isProcessing || state == "STARTING" || state == "CONNECTING"
            AnimatedContent(targetState = if (busy) "BUSY" else state, label = "status_icon") { shown ->
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    when (shown) {
                        "BUSY" -> LoadingIndicator(color = contentColor, modifier = Modifier.size(40.dp))
                        "ACTIVE" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = contentColor, modifier = Modifier.size(32.dp))
                            if (isTunEnabled && !isRootEnabled) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.Default.VpnKey, null, tint = contentColor, modifier = Modifier.size(26.dp))
                            }
                        }
                        "DEGRADED" -> Icon(Icons.Default.WarningAmber, null, tint = contentColor, modifier = Modifier.size(32.dp))
                        "NEEDS_LOGIN" -> Icon(Icons.AutoMirrored.Filled.Login, null, tint = contentColor, modifier = Modifier.size(32.dp))
                        "NEEDS_APPROVAL" -> Icon(Icons.Default.HourglassTop, null, tint = contentColor, modifier = Modifier.size(32.dp))
                        else -> Icon(Icons.Default.PowerSettingsNew, null, tint = contentColor, modifier = Modifier.size(32.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val title = when (state) {
                "ACTIVE" -> when {
                    isRootEnabled -> "${stringResource(R.string.main_status_active)} + Root"
                    isTunEnabled && !isTunDisplaced -> "${stringResource(R.string.main_status_active)} + TUN"
                    else -> stringResource(R.string.main_status_active)
                }
                "STARTING" -> stringResource(R.string.main_status_starting)
                "CONNECTING" -> stringResource(R.string.main_status_connecting)
                "DEGRADED" -> stringResource(R.string.main_status_degraded)
                "NEEDS_LOGIN" -> stringResource(R.string.main_status_needs_login)
                "NEEDS_APPROVAL" -> stringResource(R.string.notif_awaiting_approval)
                else -> stringResource(R.string.status_stopped)
            }
            AnimatedContent(targetState = title, label = "status_title") { t ->
                Text(
                    text = t,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (aside != null) {
                // The line is typed out rather than scrolled. A copy of the whole text sits
                // underneath at zero alpha to claim the space up front, so the card makes
                // one smooth move to its full height instead of growing per character.
                var shown by remember(aside) { mutableIntStateOf(0) }
                LaunchedEffect(aside) {
                    for (i in 1..aside.length) {
                        shown = i
                        delay(16)
                    }
                }
                Box(contentAlignment = Alignment.Center) {
                    // The invisible copy only holds the height; TalkBack reads it once, whole,
                    // and not the part typed out so far.
                    Text(
                        text = aside,
                        textAlign = TextAlign.Center,
                        color = contentColor,
                        modifier = Modifier.alpha(0f)
                    )
                    Text(
                        text = aside.take(shown),
                        textAlign = TextAlign.Center,
                        color = contentColor,
                        modifier = Modifier.alpha(0.75f).clearAndSetSemantics { }
                    )
                }
                return@Column
            }
            StatusLine(
                line = when {
                    isProcessing -> stringResource(R.string.main_status_please_wait)
                    state == "ACTIVE" -> when {
                        // Root wins the label: with it on, the VpnService is not
                        // what carries the traffic even if the TUN switch is set.
                        // Yielded, it carries the tailnet and nothing else; shared,
                        // it carries the whole tunnel for a part of the phone.
                        isRootEnabled && isSharedWithForeignVpn ->
                            stringResource(if (touchWording) R.string.main_status_active_root_shared_desc else R.string.tv_home_active_root_shared)
                        isRootEnabled && isYieldedToForeignVpn ->
                            stringResource(if (touchWording) R.string.main_status_active_root_yielded_desc else R.string.tv_home_active_root_yielded)
                        isRootEnabled -> stringResource(if (touchWording) R.string.main_status_active_root_desc else R.string.tv_home_active_root)
                        isTunEnabled && isTunDisplaced -> stringResource(R.string.main_tun_displaced_desc)
                        isTunEnabled -> if (isFullTunnel) stringResource(R.string.main_tun_full_tunnel_desc)
                                        else stringResource(R.string.main_tun_split_tunnel_desc)
                        else -> stringResource(if (touchWording) R.string.main_status_active_desc else R.string.tv_home_active)
                    }
                    state == "STARTING" -> stringResource(R.string.main_status_starting_desc)
                    state == "CONNECTING" -> stringResource(R.string.main_status_connecting_desc)
                    state == "DEGRADED" -> stringResource(
                        if (touchWording) R.string.main_status_degraded_desc else R.string.tv_home_degraded,
                        issueTitle ?: stringResource(R.string.main_status_degraded)
                    )
                    state == "NEEDS_LOGIN" -> stringResource(if (touchWording) R.string.main_status_needs_login_desc else R.string.tv_home_needs_login)
                    state == "NEEDS_APPROVAL" -> stringResource(R.string.notif_awaiting_approval_text)
                    else -> stringResource(if (touchWording) R.string.tap_to_start else R.string.tv_home_start)
                },
                // 0.8, not 0.6: the subtitle carries the mode and the problem,
                // and at 0.6 it fell below readable contrast on the container.
                modifier = Modifier.alpha(0.8f),
                color = contentColor
            )
        }
    }
}

/** The smallest the status card's second line gets to stay on one line. */
private val STATUS_LINE_MIN = 13.sp

/**
 * The status card's second line: on one line when it fits there at the body size or a little
 * smaller, down to [STATUS_LINE_MIN] — the Russian "Служба запущена • Нажмите для остановки" broke
 * after its last word on a phone — and at the body size over two lines when not even that fits.
 *
 * A layout of its own that draws the text it measured: the size depends on the width it is
 * given, and the card stands in rows sized by their content's intrinsic height, which a
 * BoxWithConstraints cannot answer.
 */
@Composable
private fun StatusLine(line: String, modifier: Modifier, color: Color) {
    val style = LocalTextStyle.current.copy(color = color, textAlign = TextAlign.Center)
    val measurer = rememberTextMeasurer()
    val fit = remember(line, style, measurer) { StatusLineFit(measurer, line, style) }
    Layout(
        content = {},
        modifier = modifier
            .semantics { text = AnnotatedString(line) }
            .drawBehind { fit.last?.let { drawText(it) } },
        measurePolicy = object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                val laid = fit.at(constraints.maxWidth)
                return layout(constraints.constrainWidth(laid.size.width), constraints.constrainHeight(laid.size.height)) {}
            }
            override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = fit.at(width).size.height
            override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = fit.at(width).size.height
            override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = fit.oneLineWidth()
            override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = fit.oneLineWidth()
        }
    )
}

/** [text] laid out for a width, by StatusLine's rule; the last answer is what is drawn. */
private class StatusLineFit(private val measurer: TextMeasurer, private val text: String, private val style: TextStyle) {
    private val full = style.fontSize.takeIf { it.isSp } ?: 16.sp
    private var width = -1
    var last: TextLayoutResult? = null
        private set

    fun oneLineWidth(): Int = measurer.measure(text, style.copy(fontSize = full), maxLines = 1, softWrap = false).size.width

    fun at(maxWidth: Int): TextLayoutResult {
        last?.takeIf { width == maxWidth }?.let { return it }
        val bounded = if (maxWidth == Constraints.Infinity) Constraints() else Constraints(maxWidth = maxWidth)
        var size = full.value
        var chosen = full
        while (maxWidth != Constraints.Infinity && size >= STATUS_LINE_MIN.value) {
            val line = measurer.measure(text, style.copy(fontSize = size.sp), maxLines = 1, softWrap = false)
            if (line.size.width <= maxWidth) { chosen = size.sp; break }
            size -= 0.5f
        }
        return measurer.measure(text, style.copy(fontSize = chosen), constraints = bounded).also { width = maxWidth; last = it }
    }
}
