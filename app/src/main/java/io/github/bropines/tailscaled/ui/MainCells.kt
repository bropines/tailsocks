package io.github.bropines.tailscaled.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The main screen's cells around the status card (ui/MainActivity.kt): the banners above it,
 * the exit-node line, and the card itself with what belongs to it. Each is one item of the
 * layout (core/MainLayout.kt), or goes where the card goes.
 */

/**
 * What the screen says without being asked, above the status card. Not the person's to move
 * or to take off, so it is not in the layout: it goes where the card goes.
 */
@Composable
internal fun MainBanners(
    isBatteryOptimizationsIgnored: Boolean,
    show410Warning: Boolean,
    proxyState: String,
    yieldedToForeignVpn: Boolean,
    sharedWithForeignVpn: Boolean,
) {
    val context = LocalContext.current
    Column {
        // Not where there is no battery to be optimised for.
androidx.compose.animation.AnimatedVisibility(visible = !isBatteryOptimizationsIgnored && Feature.BATTERY_OPTIMISATION_PROMPT.offered(), enter = MainAppear, exit = MainDisappear) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.large,
                // Clipped before the click, so the ripple keeps the banner's corners.
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(MaterialTheme.shapes.large).clickable {
                    if (!context.tryStartActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
                        Toast.makeText(context, context.getString(R.string.main_cannot_open_battery_settings), Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.main_battery_warn_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(stringResource(R.string.main_battery_warn_desc), 
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

androidx.compose.animation.AnimatedVisibility(visible = show410Warning, enter = MainAppear, exit = MainDisappear) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.main_network_sync_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(stringResource(R.string.main_network_sync_desc), 
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

androidx.compose.animation.AnimatedVisibility(visible = proxyState == "ACTIVE" && (yieldedToForeignVpn || sharedWithForeignVpn), enter = MainAppear, exit = MainDisappear) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.width(12.dp))
                    // The weight keeps the two-line Russian description off the
                    // icon instead of pushing the row wider than the screen.
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (sharedWithForeignVpn) stringResource(R.string.main_root_shared_title)
                            else stringResource(R.string.main_root_yielded_title),
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            if (sharedWithForeignVpn) stringResource(R.string.main_root_shared_desc)
                            else stringResource(R.string.main_root_yielded_desc),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

/**
 * The exit-node line: what carries the traffic, or that nothing does. The four flags are the
 * screen's reading of the exit node against a foreign VPN (see MainScreen); [summaryExitNodeName]
 * is the name the connection summary found for it.
 */
@Composable
internal fun MainExitNodeLine(
    modifier: Modifier,
    exitNodeIp: String,
    exitNodePausedName: String,
    summaryExitNodeName: String?,
    exitNodeActive: Boolean,
    exitNodeInert: Boolean,
    exitNodePartial: Boolean,
    exitNodePaused: Boolean,
    touchWording: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (exitNodeActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth()
            .shapedClickable(MaterialTheme.shapes.large) { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (exitNodeActive) Icons.Default.Lock else Icons.Default.Public,
                contentDescription = null,
                tint = if (exitNodeActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            // The weight belongs on the text, not on a trailing spacer:
            // the description carries an IP and used to squeeze the chevron out.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        exitNodeInert -> stringResource(R.string.main_exit_node_inert_label)
                        exitNodePartial -> stringResource(R.string.main_exit_node_partial_label)
                        exitNodeActive -> stringResource(R.string.main_traffic_routed)
                        exitNodePaused -> stringResource(R.string.main_exit_node_paused_label)
                        else -> stringResource(R.string.main_exit_node_none_label)
                    },
                    fontWeight = FontWeight.Bold,
                    color = if (exitNodeActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    when {
                        exitNodeInert -> stringResource(R.string.main_exit_node_inert_desc, exitNodeIp)
                        exitNodePartial -> stringResource(R.string.main_exit_node_partial_desc, exitNodeIp)
                        exitNodeActive -> stringResource(R.string.main_exit_node_routed_desc, summaryExitNodeName ?: exitNodeIp)
                        exitNodePaused -> stringResource(R.string.exitguard_paused_text, exitNodePausedName)
                        else -> stringResource(if (touchWording) R.string.main_exit_node_none_desc else R.string.tv_home_exit_node_none)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exitNodeActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = if (exitNodeActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The card and what belongs to it: it is one item of the layout. [bare] is the card alone, as
 * the canvas shows it while the screen is being arranged. The states passed in are the
 * screen's: the card writes the line it shows for a while, the relays' reconnect, the sign-in
 * code's flag and the sibling build found on a start.
 */
@Composable
internal fun ColumnScope.MainStatusCell(
    cardModifier: Modifier,
    bare: Boolean,
    daemon: MainDaemonState,
    flags: MainConnectionFlags,
    layoutState: MainLayoutState,
    cardState: String,
    serviceUp: Boolean,
    isFullTunnel: Boolean,
    yieldedToForeignVpn: Boolean,
    sharedWithForeignVpn: Boolean,
    exitNodeIp: String,
    exitNodePaused: Boolean,
    exitNodeLine: @Composable (Modifier) -> Unit,
    remoteTileOffered: Boolean,
    menuStart: FocusRequester,
    statusAsideState: MutableState<String?>,
    reconnectingRelaysState: MutableState<Boolean>,
    signInOpenState: MutableState<Boolean>,
    signInLeft: Boolean,
    browserSignIn: Boolean,
    accountId: String,
    siblingFoundState: MutableState<SiblingBuild.Found?>,
    haptics: HapticFeedback,
    scope: CoroutineScope,
    requestStop: () -> Unit,
    startNow: () -> Unit,
): Unit = with(daemon) { with(flags) { with(layoutState) {
    val context = LocalContext.current
    var statusAside by statusAsideState
    var reconnectingRelays by reconnectingRelaysState
    var signInOpen by signInOpenState
    var siblingFound by siblingFoundState
    // Taken off the screen, the line still shows while an exit node carries the
    // traffic or waits to come back: that is not something to find out later.
    if (!bare && proxyState == "ACTIVE" && MainItem.EXIT_NODE !in layout && MainItem.EXIT_PICKER !in layout && (exitNodeIp.isNotEmpty() || exitNodePaused)) {
        exitNodeLine(Modifier.padding(bottom = 16.dp))
    }
    StatusCard(
        state = cardState,
        // Where the remote starts on this screen.
        modifier = cardModifier.tvInitialFocus()
            .then(if (remoteTileOffered) Modifier.focusProperties { right = menuStart } else Modifier),
        issueTitle = shownWarnings.firstOrNull()?.let { healthTitle(it) },
        isProcessing = isProcessing,
        isTunEnabled = isTunEnabled,
        isTunDisplaced = isTunDisplaced,
        isFullTunnel = isFullTunnel,
        isRootEnabled = isRootEnabled,
        isYieldedToForeignVpn = yieldedToForeignVpn,
        isSharedWithForeignVpn = sharedWithForeignVpn,
        aside = statusAside,
        onLongPress = {
            if (editing) return@StatusCard
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            statusAside = StatusAsides.pick(context, statusAside)
        }
    ) {
        if (isProcessing || editing) return@StatusCard
        StatusAsides.bump(context, StatusAsides.TOGGLES)

        if (proxyState == "ACTIVE" || proxyState == "STARTING" || proxyState == "CONNECTION_ISSUE" || proxyState == "LOGGED_OUT") {
            requestStop()
        } else {
            // SOCKS5 lives in global settings, not in the profile store.
            val currentSocks = GlobalSettings.getString(context, "socks5", "127.0.0.1:48115")

            if (currentSocks.isBlank()) {
                Toast.makeText(context, context.getString(R.string.main_error_socks5_empty), Toast.LENGTH_LONG).show()
                return@StatusCard
            }

            isProcessing = true
            // The other build of the app, when it runs and would collide with
            // this start, is asked about first (core/SiblingBuild.kt); within a
            // short budget, and any failure starts as before.
            scope.launch {
                val found = SiblingBuild.check(context)
                if (found == null) startNow() else { isProcessing = false; siblingFound = found }
            }
        }
    }

    if (!bare) {
androidx.compose.animation.AnimatedVisibility(visible = serviceUp && shownWarnings.isNotEmpty() && cardState != "NEEDS_LOGIN", enter = MainAppear, exit = MainDisappear) { Column {
            Spacer(modifier = Modifier.height(12.dp))
            HealthBanner(
                warnings = shownWarnings,
                reconnecting = reconnectingRelays,
                dnsHasFallback = dnsHasFallback,
                onReconnectRelays = {
                    reconnectingRelays = true
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                    scope.launch(Dispatchers.IO) {
                        val err = runCatching { appctr.Appctr.reconnectRelays() }.getOrElse { it.message ?: "" }
                        withContext(Dispatchers.Main) {
                            reconnectingRelays = false
                            if (!err.isNullOrBlank()) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onOpenBypass = {
                    context.startActivity(
                        Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN_SECTION, "bypass")
                    )
                }
            )
        } }

androidx.compose.animation.AnimatedVisibility(visible = proxyState == "LOGGED_OUT", enter = MainAppear, exit = MainDisappear) { Column {
            Spacer(modifier = Modifier.height(16.dp))
            LoggedOutCard(
                loginUrl = loginUrl,
                onConfigureProxy = {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
                onStop = { requestStop() },
                // Where signing in is a code on this screen, the card's button shows it.
                onShowCode = if (browserSignIn) null else ({ signInOpen = true }),
                codeWasLeft = signInLeft,
                accountId = accountId,
            )
        } }

androidx.compose.animation.AnimatedVisibility(visible = proxyState == "CONNECTION_ISSUE", enter = MainAppear, exit = MainDisappear) { Column {
            Spacer(modifier = Modifier.height(16.dp))
            ConnectionIssueCard(
                onConfigureProxy = {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
                onStop = { requestStop() }
            )
        } }
    }
} } }

/**
 * How a banner or card of the main screen comes and goes: it opens out and fades in, so the
 * rest of the screen slides to make room instead of jumping in one frame.
 */
internal val MainAppear = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn()
internal val MainDisappear = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
