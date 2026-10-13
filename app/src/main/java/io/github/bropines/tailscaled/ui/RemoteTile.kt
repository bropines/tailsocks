package io.github.bropines.tailscaled.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.RemoteJson
import io.github.bropines.tailscaled.core.RemoteManage
import io.github.bropines.tailscaled.core.RemoteState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The home screen's tile for remote management (ui/RemoteManagedScreen.kt): where a device has
 * no better way to reach the page than Settings → Advanced → Experimental — a television, where
 * the page is the way to stop using the remote — its state is on the tile, so nobody opens the
 * page to find out whether it is on.
 *
 * It says what the switch and the bridge's state say, and nothing of its own about modes: a
 * mode that cannot receive connections is one the bridge reports (`inbound`), and a mode that
 * can is one it stops reporting.
 */

/**
 * What the tile says: off, waiting for a first pairing, how many devices are paired, or why
 * nobody can pair. [paired] counts for [Kind.PAIRED].
 */
data class RemoteTile(val kind: Kind, val paired: Int = 0) {
    enum class Kind {
        /** The switch is off. */
        OFF,
        /** On and reachable, nobody paired yet. */
        WAITING,
        PAIRED,
        /** On, and the bridge reports no way in: `inbound` is "none". */
        UNAVAILABLE_IN_MODE,
        /** On, and the daemon is not running or does not answer: it opens with the connection. */
        NOT_RUNNING,
        /** On, and another program holds the port. */
        PORT_BUSY,
    }

    /** Whether a code shown now could be used: the tile looks ready. */
    val ready: Boolean get() = kind == Kind.WAITING || kind == Kind.PAIRED

    /** Whether something stands in the way that no one waiting will clear: the tile is set apart. */
    val blocked: Boolean get() = kind == Kind.UNAVAILABLE_IN_MODE || kind == Kind.PORT_BUSY

    companion object {
        /** The tile for the switch [switchOn] and the bridge's [state]: the page's own reading of them, without its words. */
        fun of(switchOn: Boolean, state: RemoteState): RemoteTile = when {
            !switchOn -> RemoteTile(Kind.OFF)
            !state.available || state.inbound == RemoteState.INBOUND_UNKNOWN -> RemoteTile(Kind.NOT_RUNNING)
            state.inbound == RemoteState.INBOUND_NONE -> RemoteTile(Kind.UNAVAILABLE_IN_MODE)
            state.listening || state.error.isEmpty() ->
                if (state.paired > 0) RemoteTile(Kind.PAIRED, state.paired) else RemoteTile(Kind.WAITING)
            else -> RemoteTile(Kind.PORT_BUSY)
        }

        /**
         * The tile now: the switch and, when it is on, what the bridge answers — the same state
         * the page reads. Blocking, but cheap: a file and one call, none to the bridge while the
         * switch is off.
         */
        fun read(context: Context): RemoteTile {
            if (!RemoteManage.isEnabled(context)) return RemoteTile(Kind.OFF)
            return of(true, RemoteJson.state(Appctr.remoteStateJSON()))
        }
    }
}

/**
 * What the tile says, read off the main thread each time the screen it is on is shown again and
 * whenever one of [keys] changes (the connection coming up or going away, the page being left).
 * Null until the first answer; a preview draws what its demo says.
 */
@Composable
internal fun rememberRemoteTile(vararg keys: Any?): RemoteTile? {
    val demo = LocalDemo.current
    if (demo != null) return demo.remoteTile
    if (LocalInspectionMode.current) return null
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tile by remember { mutableStateOf<RemoteTile?>(null) }
    LaunchedEffect(lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            tile = withContext(Dispatchers.IO) { runCatching { RemoteTile.read(context) }.getOrNull() }
        }
    }
    return tile
}

/** The tile's second line. */
@Composable
internal fun remoteTileText(tile: RemoteTile): String = when (tile.kind) {
    RemoteTile.Kind.OFF -> stringResource(R.string.tile_remote_off)
    RemoteTile.Kind.WAITING -> stringResource(R.string.tile_remote_waiting)
    RemoteTile.Kind.PAIRED -> pluralStringResource(R.plurals.tile_remote_paired, tile.paired, tile.paired)
    RemoteTile.Kind.UNAVAILABLE_IN_MODE -> stringResource(R.string.tile_remote_mode)
    RemoteTile.Kind.NOT_RUNNING -> stringResource(R.string.tile_remote_stopped)
    RemoteTile.Kind.PORT_BUSY -> stringResource(R.string.tile_remote_busy)
}
