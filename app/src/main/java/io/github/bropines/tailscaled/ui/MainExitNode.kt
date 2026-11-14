package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The main screen's exit node: the one the profile has selected, the one the guard took off
 * and will select again, and the picker sheet with its list and its pings. Used by the main
 * screen (ui/MainActivity.kt), which draws the exit-node line and the picker tile off it.
 */

/**
 * What the main screen holds about the exit node. [exitNodeIp] and [exitNodePausedName] are
 * the active profile's, so a new holder comes with every account switch
 * ([rememberMainExitNode]); the sheet's flag and its list outlive the switch, as they did when
 * they were the screen's own.
 */
@Stable
internal class MainExitNodeState(
    exitNodeIp: MutableState<String>,
    exitNodePausedName: MutableState<String>,
    showExitNodeSheet: MutableState<Boolean>,
    exitNodes: MutableState<List<PeerData>>,
    isExitNodesLoading: MutableState<Boolean>,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /** The selected exit node's address, "" for none; mirrored from the profile's store. */
    var exitNodeIp by exitNodeIp
    // An exit node the guard took off because it is offline and will select again
    // by itself (ExitNodeGuard): the row must not read as a plain "no exit node".
    var exitNodePausedName by exitNodePausedName
    var showExitNodeSheet by showExitNodeSheet
    var exitNodes by exitNodes
    var isExitNodesLoading by isExitNodesLoading

    /** Keeps the two mirrors above in step with the profile's store (registered by MainDaemonEffects). */
    val profilePrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "exit_node_ip") {
            this.exitNodeIp = sharedPreferences.getString("exit_node_ip", "") ?: ""
        }
        if (key == ExitNodeGuard.KEY_PAUSED_NAME) {
            this.exitNodePausedName = sharedPreferences.getString(ExitNodeGuard.KEY_PAUSED_NAME, "").orEmpty()
        }
    }

    fun openExitNodeSheet() {
        showExitNodeSheet = true
        isExitNodesLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                // A failed read keeps the list the sheet already has.
                val status = DaemonStatus.readOrNull()
                if (status != null) {
                    val nodes = status.peers?.values?.filter { it.exitNodeOption == true }?.toList() ?: emptyList()
                    withContext(Dispatchers.Main) { exitNodes = nodes }
                }
            } catch (e: Exception) {}
            withContext(Dispatchers.Main) { isExitNodesLoading = false }
        }
    }

    fun applyExitNode(id: String, ip: String, haptics: HapticFeedback) {
        // A peer listed without its StableID: selecting it would clear the exit node.
        if (!ExitNodeApply.isSelectable(id, ip)) return
        // The confirm haptic: choosing where all traffic goes is the one
        // selection on this screen whose effect is not visible at once.
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        exitNodeIp = ip
        scope.launch(Dispatchers.IO) {
            val result = ExitNodeApply.select(context, id, ip)
            if (result is ExitNodeApply.Result.Refused) withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.settings_local_api_error_format, result.message), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * The holder for the profile [accountId], whose store is [prefs]. [scope] is the screen's: the
 * list is read and a choice applied there, so neither is cut short by the sheet closing.
 */
@Composable
internal fun rememberMainExitNode(accountId: String, prefs: SharedPreferences, demo: DemoData?, scope: CoroutineScope): MainExitNodeState {
    val context = LocalContext.current
    val exitNodeIp = remember(accountId) { mutableStateOf(demo?.exitNodeIp ?: prefs.getString("exit_node_ip", "") ?: "") }
    val exitNodePausedName = remember(accountId) {
        mutableStateOf(if (demo != null) "" else prefs.getString(ExitNodeGuard.KEY_PAUSED_NAME, "").orEmpty())
    }
    val showExitNodeSheet = rememberSaveable { mutableStateOf(false) }
    val exitNodes = remember { mutableStateOf<List<PeerData>>(emptyList()) }
    val isExitNodesLoading = remember { mutableStateOf(false) }
    return remember(exitNodeIp, exitNodePausedName) {
        MainExitNodeState(exitNodeIp, exitNodePausedName, showExitNodeSheet, exitNodes, isExitNodesLoading, context, scope)
    }
}

/** The picker, over the main screen while [MainExitNodeState.showExitNodeSheet] is set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainExitNodeSheet(state: MainExitNodeState, haptics: HapticFeedback): Unit = with(state) {
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.85f).dp

    // The picker's words, resolved out here in the parent composition — see
    // wrapContextWithLocale().
    val pickerStrings = exitNodePickerStrings()
    // The daemon's raw answer per node address, for as long as the sheet is open. A
    // reopened sheet starts clean: a figure measured minutes ago is not the figure now.
    // The pings run in the sheet's own scope, not the screen's, so closing the sheet
    // also drops the answers that were still on their way to this map.
    val exitNodePings = remember { mutableStateMapOf<String, String>() }
    val sheetScope = rememberCoroutineScope()
    fun pingExitNode(ip: String) {
        // A ping already in flight is not started again: the second answer would
        // overwrite the state the first one is about to write.
        if (exitNodePings[ip] == PING_IN_FLIGHT) return
        exitNodePings[ip] = PING_IN_FLIGHT
        sheetScope.launch { exitNodePings[ip] = pingPeer(ip) }
    }

    var pingingAllExitNodes by remember { mutableStateOf(false) }
    // The node Tailscale itself recommends, asked once the list is in: the row at the
    // top names it and the node's own row carries a mark. Held by the sheet, so a
    // reopened sheet asks again — the answer follows the network.
    var bestExitNode by remember { mutableStateOf<ExitNodeSuggestion.Outcome?>(null) }
    var bestAsked by remember { mutableIntStateOf(0) }
    LaunchedEffect(isExitNodesLoading, bestAsked) {
        if (isExitNodesLoading || exitNodes.isEmpty()) return@LaunchedEffect
        bestExitNode = null
        bestExitNode = fetchBestExitNode(exitNodes)
    }
    AppModalSheet(
        onDismissRequest = { showExitNodeSheet = false },
        // Without this the sheet opens half height and settles to its content a frame
        // later, which reads as a jump; the list also arrives after the spinner, so the
        // height it settles at is held from the start by the minimum below.
        sheetState = rememberFullSheetState()
    ) {
        ExitNodePicker(
            nodes = exitNodes,
            loading = isExitNodesLoading,
            selectedIp = exitNodeIp,
            pings = exitNodePings,
            pingingAll = pingingAllExitNodes,
            best = bestExitNode,
            strings = pickerStrings,
            onPingAll = {
                pingingAllExitNodes = true
                StatusAsides.bump(context, StatusAsides.PINGS)
                sheetScope.launch {
                    pingAll(exitNodes.map { it.getPrimaryIp() }, exitNodePings)
                    pingingAllExitNodes = false
                }
            },
            onPing = { pingExitNode(it) },
            onRetryBest = { bestAsked++ },
            onChoose = { id, ip -> applyExitNode(id, ip, haptics); showExitNodeSheet = false },
            onClose = { showExitNodeSheet = false },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = maxHeight)
                .navigationBarsPadding()
        )
    }
}
