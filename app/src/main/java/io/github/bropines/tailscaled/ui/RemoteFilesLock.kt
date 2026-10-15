package io.github.bropines.tailscaled.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Where on the managing page another device's files were asked for: what this device's lock had to say is said there. */
enum class FilesPlace { STORAGE, SHARES, DIAG }

/**
 * This device's own lock in front of another device's files on the managing page: its storage,
 * its shared folders, its log and its report are shown here only after this device's
 * fingerprint, face or screen lock (RemoteUnlockActivity). One confirmation holds for
 * [WINDOW_MS] on this page; the page keeps this object, so it ends when the page closes.
 *
 * A device with no lock has nothing to ask: the files open, and the page says once that
 * anyone using this device could open them as well. A prompt that fails or is cancelled opens
 * nothing.
 *
 * [secure] is whether this device has a lock; [ask] puts the prompt up and hands its outcome
 * back on the main thread, later; [now] is the clock.
 */
@Stable
class RemoteFilesLock(
    private val secure: () -> Boolean,
    private val ask: (answer: (RemoteUnlockOutcome) -> Unit) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** This device's clock, milliseconds, of the last confirmation; 0 for none on this page. */
    var unlockedAt by mutableLongStateOf(0L)
        private set

    /**
     * What the page has to say about this device's lock, and where: that it has none — said
     * once, where the files were first asked for — or why the last request opened nothing.
     */
    var said by mutableStateOf<Pair<FilesPlace, RemoteUnlockOutcome>?>(null)
        private set

    /** What waits for the prompt that is up; null while none is. */
    private var waiting: Pair<FilesPlace, () -> Unit>? = null

    /** The files are open on this page now: confirmed within [WINDOW_MS]. */
    fun open(at: Long = now()): Boolean = unlockedAt > 0 && at - unlockedAt in 0 until WINDOW_MS

    /** Runs [then] at once while the files are open here; otherwise after this device's lock, or at once where it has none. */
    fun run(place: FilesPlace, then: () -> Unit) {
        if (open()) return then()
        if (!secure()) {
            unlocked()
            if (said?.second != RemoteUnlockOutcome.NO_LOCK) said = place to RemoteUnlockOutcome.NO_LOCK
            return then()
        }
        // One prompt at a time: a second press while it is up is the same request.
        if (waiting != null) return
        waiting = place to then
        ask(::answered)
    }

    /** How the prompt ended. */
    fun answered(outcome: RemoteUnlockOutcome) {
        val (place, then) = waiting ?: return
        waiting = null
        when (outcome) {
            RemoteUnlockOutcome.UNLOCKED -> {
                unlocked()
                if (said?.second != RemoteUnlockOutcome.NO_LOCK) said = null
                then()
            }
            // The lock went away while the prompt was on its way: nothing to ask, said as such.
            RemoteUnlockOutcome.NO_LOCK -> {
                unlocked()
                said = place to RemoteUnlockOutcome.NO_LOCK
                then()
            }
            RemoteUnlockOutcome.CANCELLED -> Unit
            RemoteUnlockOutcome.LOCKOUT, RemoteUnlockOutcome.FAILED -> said = place to outcome
        }
    }

    private fun unlocked() {
        unlockedAt = now()
    }

    companion object {
        /** How long one confirmation holds on a page. */
        const val WINDOW_MS = 5 * 60_000L
    }
}
