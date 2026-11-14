package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import appctr.Appctr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * What a screen that needs the daemon shows while the service is stopped, with a Start button
 * that waits until the daemon can answer.
 */

/**
 * What a screen that needs the daemon shows while the service is stopped, in place of the
 * daemon's error text or a loader waiting for nothing: one line, and a Start button that
 * starts the service from here instead of sending the user back to the main screen.
 *
 * [onStarted] runs once the daemon can answer, not when the service says START: that
 * broadcast goes out as soon as the process is launched, while the backend is still
 * Starting with no netmap, and a screen reloaded then would draw an empty tailnet. It also
 * runs when the service is started from elsewhere — the tile, the notification — while this
 * is on screen. [footer] takes a second way out where a screen has one.
 */
@Composable
fun DaemonStoppedState(
    onStarted: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    footer: @Composable ColumnScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val inPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    val currentOnStarted by rememberUpdatedState(onStarted)
    var starting by remember { mutableStateOf(false) }
    val waiter = remember { mutableStateOf<Job?>(null) }

    fun awaitReady() {
        starting = true
        if (waiter.value?.isActive == true) return
        waiter.value = scope.launch {
            awaitDaemonAnswering()
            currentOnStarted()
        }
    }

    // The preview renderer has no service to hear from.
    if (!inPreview) DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    "STARTING" -> starting = true
                    "START" -> awaitReady()
                    // A start given up — su refused, a stop came in meanwhile.
                    "STOP" -> { waiter.value?.cancel(); starting = false }
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter().apply { addAction("STARTING"); addAction("START"); addAction("STOP") },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Column(
        modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.PowerSettingsNew,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.state_stopped_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(
            enabled = !starting,
            // Where the remote starts on a screen that shows this: it is the one thing to
            // press there, and the focus used to open on Refresh in the top bar.
            modifier = Modifier.tvInitialFocus(),
            onClick = {
                // The same refusal the main screen's toggle makes (ServiceStart.kt), before the
                // button turns busy: the service would start with nowhere to listen.
                if (refuseEmptySocks5(context)) return@Button
                starting = true
                scope.launch {
                    // Up already — started a moment before this screen looked, say — and a
                    // second start would announce nothing, so go straight to the waiting.
                    val up = withContext(Dispatchers.IO) { runCatching { Appctr.isRunning() }.getOrDefault(false) }
                    if (up) awaitReady()
                    else startTailSocks(context)
                }
            }
        ) {
            if (starting) {
                ButtonBusyIndicator(18.dp, color = LocalContentColor.current)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.main_status_starting))
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.main_status_action_start))
            }
        }
        footer()
    }
}
