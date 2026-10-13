package io.github.bropines.tailscaled.core

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Remote management, the line a paired device makes this device say while one of the app's own
 * screens is in front (core/RemoteDevicePrompts.kt): kept here, one for the whole process, for
 * the bar every screen draws it in (ui/RemoteNoticeBar.kt). One at a time: a newer line takes
 * the place of the one showing.
 *
 * A line has two times. It waits a few seconds to be drawn, and a line nobody drew in that
 * time is gone — it is not shown when the app is opened an hour later. Once drawn it stays as
 * long as it was meant to be read, counted from that moment: a slow device takes a second to
 * put the bar up, and a line of two seconds would otherwise be half gone before it is seen.
 */

/**
 * One line: who ([from], never blank) and what ([text]) — the bar's two lines — and the same in
 * one sentence ([spoken]), which is what a screen reader says. It stays [staysMs] once it is
 * [drawn]; [until] is when it goes, on the board's clock. [id] tells it from the one before,
 * which may say the very same.
 */
data class RemoteDeviceNotice(
    val id: Long,
    val kind: Kind,
    val from: String,
    val text: String,
    val spoken: String,
    val staysMs: Long,
    val until: Long,
    val drawn: Boolean = false,
) {
    enum class Kind {
        /** "Managed from": a paired device asked this one to say whose hands it is in. */
        IDENTIFY,

        /** Something was changed from afar. */
        CHANGE,

        /** A permission is asked for, and its page could not be opened by itself. */
        ASK,
    }
}

/**
 * The line showing now, if any. [clock] counts milliseconds and keeps counting while the device
 * sleeps; a test hands in its own. Written from the bridge's threads, read by the screens.
 */
class RemoteDeviceNoticeBoard(private val clock: () -> Long) {
    private val state = MutableStateFlow<RemoteDeviceNotice?>(null)
    private var posted = 0L

    /** What was posted last and has not been taken down; its time may be over — see [showing]. */
    val current: StateFlow<RemoteDeviceNotice?> = state.asStateFlow()

    /** Puts a line up in place of whatever is there, to stay [staysMs] once a screen has drawn it. */
    @Synchronized
    fun post(kind: RemoteDeviceNotice.Kind, from: String, text: String, spoken: String, staysMs: Long): RemoteDeviceNotice {
        val notice = RemoteDeviceNotice(++posted, kind, from, text, spoken, staysMs.coerceAtLeast(0), until = clock() + DRAW_WAIT_MS)
        state.value = notice
        return notice
    }

    /**
     * The line with this [id] is in front of a person: its time to be read starts now. Once —
     * the window that draws it next (a dialog opening over the screen) does not start it again.
     */
    @Synchronized
    fun drawn(id: Long) {
        val notice = state.value ?: return
        if (notice.id == id && !notice.drawn) state.value = notice.copy(drawn = true, until = clock() + notice.staysMs)
    }

    /** The line to draw: the current one while its time lasts. */
    fun showing(): RemoteDeviceNotice? = state.value?.takeIf { remaining(it) > 0 }

    /** How long [notice] still has; nothing once it is over. */
    fun remaining(notice: RemoteDeviceNotice): Long = (notice.until - clock()).coerceAtLeast(0)

    /** Takes the current line down if its time is over. A timer set for a line that was replaced since finds a younger one and leaves it. */
    @Synchronized
    fun expire() {
        val notice = state.value ?: return
        if (remaining(notice) == 0L) state.value = null
    }

    /** Takes down the line with this [id] — a person waved it away — and no other: the one that replaced it stays. */
    @Synchronized
    fun dismiss(id: Long) {
        if (state.value?.id == id) state.value = null
    }

    companion object {
        /** How long a line waits for a screen to draw it. */
        const val DRAW_WAIT_MS = 3_000L
    }
}

/** The process's own board, on the clock [PendingAsk] is on. */
object RemoteDeviceNotices {
    val board = RemoteDeviceNoticeBoard(SystemClock::elapsedRealtime)
}
