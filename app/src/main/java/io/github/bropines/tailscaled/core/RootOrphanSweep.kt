package io.github.bropines.tailscaled.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Device-wide rules nobody runs any more, removed when the app process starts.
 *
 * The root daemon outlives the app, and so do the rules. When the daemon dies
 * while the app process is dead too — killed by hand, crashed, an OEM task
 * killer that took both — `TAILSOCKS_DNS` stays hooked into `nat OUTPUT` and
 * sends every port-53 packet on the phone to 100.100.100.100, where nothing
 * answers: the phone has no DNS until the app is opened and someone presses
 * Stop. The health gate that would have unhooked it lives in the service, and
 * the service is gone with the process.
 *
 * So on process start, with Root routing recorded as installed: when the user
 * does not want the connection and no daemon process for our socket exists,
 * the routing cleanup runs once. When the user does want it, nothing is done
 * here — the start that follows unhooks the two DNS jumps before anything else
 * and the health gate re-arms them once MagicDNS answers
 * (`TailscaledService.startSequence`).
 *
 * It runs on the start gate's serial executor, so it can never interleave with
 * a start sequence: a START that arrives meanwhile runs after it, and the
 * desired state is read again inside, right before acting.
 */
object RootOrphanSweep {
    private const val TAG = "RootOrphanSweep"

    enum class Action { NOTHING, CLEANUP }

    /**
     * The decision, in the order it is made: nothing recorded means nothing to
     * do (and no root shell is spent asking); a wanted connection is the
     * start's business; a running daemon still owns its rules.
     */
    fun decide(installed: Boolean, desiredRunning: Boolean, daemonProcessAlive: () -> Boolean): Action = when {
        !installed -> Action.NOTHING
        desiredRunning -> Action.NOTHING
        daemonProcessAlive() -> Action.NOTHING
        else -> Action.CLEANUP
    }

    /** The body run on the start executor. */
    fun run(context: Context) {
        val app = context.applicationContext
        val socket = File(app.filesDir, "tailscaled.sock").absolutePath
        val action = decide(
            GlobalSettings.isRootRoutingInstalled(app),
            ProxyState.isUserLetRunning(app)
        ) { RootUtils.isDaemonProcessAlive(socket) }
        if (action != Action.CLEANUP) return
        Log.i(TAG, "Root routing is recorded as installed, no daemon runs and none is wanted; removing it")
        appctr.Appctr.logAndroid(
            "WARN", "ROOT",
            "The root daemon is gone but its rules were still on the device (the app was not running to " +
                "remove them); removing them now, so DNS and routing go back to the system"
        )
        if (RootUtils.cleanupTailscale0Routing()) {
            GlobalSettings.setRootRoutingInstalled(app, false)
            GlobalSettings.setRootRoutingYielded(app, false)
            GlobalSettings.setRootRoutingShared(app, false)
        }
    }
}
