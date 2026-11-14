package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.TailscaledService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Start the service" for the screens that offer it away from the main screen's switch
 * (the stopped state of a screen that needs the daemon, Netcheck, remote management).
 *
 * The refusal comes first, the same one the main screen's toggle makes: with the SOCKS5
 * address cleared the service would start with nowhere to listen, so the toast is shown and
 * nothing is started; the return value is false. Netcheck once had its own copy of the start
 * without this check and started a service the main screen would have refused.
 *
 * A system refusal of the foreground start itself (background start limits) is not caught
 * here: each caller already decides what that looks like — an error line in Netcheck, a
 * plain "could not" in remote management.
 */
internal fun startTailSocks(context: Context): Boolean {
    if (refuseEmptySocks5(context)) return false
    ContextCompat.startForegroundService(
        context,
        Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
    )
    return true
}

/**
 * True, with the toast already shown, when a start must be refused because the SOCKS5
 * address is empty. Separate from [startTailSocks] for a caller that refuses before it turns
 * busy and only later learns whether a start is needed at all (a daemon already up).
 */
internal fun refuseEmptySocks5(context: Context): Boolean {
    if (GlobalSettings.getString(context, "socks5", "127.0.0.1:48115").isNotBlank()) return false
    Toast.makeText(context, context.getString(R.string.main_error_socks5_empty), Toast.LENGTH_LONG).show()
    return true
}

/**
 * Returns once the daemon's backend has left its starting states, or after 15 s: past that
 * the screen loads anyway and shows what it finds. NeedsLogin ends the wait too — no waiting
 * changes it, and the screen has something to say about it. Netcheck's DERP map is held only
 * once the backend is Running, which is why it waits here as well.
 */
internal suspend fun awaitDaemonAnswering() = withContext(Dispatchers.IO) {
    withTimeoutOrNull(15_000) {
        while (runCatching { Appctr.getBackendState() }.getOrDefault("") in DAEMON_STARTING_STATES) delay(500)
    }
}

private val DAEMON_STARTING_STATES = setOf("", "NoState", "Starting", "Error")
