package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.bropines.tailscaled.admin.secure.AdminWriteGate
import io.github.bropines.tailscaled.admin.secure.UnlockFailure
import io.github.bropines.tailscaled.admin.secure.UnlockResult
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import kotlinx.coroutines.launch

/** How asking this device's own lock ended. */
enum class RemoteUnlockOutcome {
    UNLOCKED,
    /** The device has no PIN, pattern or password: there was nothing to ask. */
    NO_LOCK,
    CANCELLED,
    /** Too many tries: the lock is blocked for a while. */
    LOCKOUT,
    /** The prompt could not be shown, or the Keystore did not believe what it said. */
    FAILED,
}

/**
 * This device's own lock, asked over whatever is on screen — for remote management's two pages,
 * which live in windows of activities that cannot show the prompt themselves: the managed
 * device's page before its files are opened to the network, the managing page before another
 * device's files are shown here. The gate is the admin console's (AdminWriteGate): a strong
 * biometric or the screen lock, failing closed. Transparent, in the task that asked, never
 * exported; it answers through [RemoteUnlock] and goes.
 */
class RemoteUnlockActivity : FragmentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
        lifecycleScope.launch {
            val outcome = when (val result = AdminWriteGate.unlock(this@RemoteUnlockActivity, title, subtitle)) {
                // Proof of one unlock a moment ago, taken here and now: nothing else spends it.
                is UnlockResult.Granted -> if (result.grant.consume()) RemoteUnlockOutcome.UNLOCKED else RemoteUnlockOutcome.FAILED
                UnlockResult.Cancelled -> RemoteUnlockOutcome.CANCELLED
                is UnlockResult.Failed -> when (result.reason) {
                    UnlockFailure.NO_SCREEN_LOCK -> RemoteUnlockOutcome.NO_LOCK
                    UnlockFailure.LOCKOUT -> RemoteUnlockOutcome.LOCKOUT
                    UnlockFailure.PROMPT_UNAVAILABLE, UnlockFailure.NOT_PROVEN, UnlockFailure.ERROR -> RemoteUnlockOutcome.FAILED
                }
            }
            setResult(RESULT_OK, Intent().putExtra(EXTRA_OUTCOME, outcome.ordinal))
            finish()
        }
    }

    internal companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_OUTCOME = "outcome"
    }
}

/** [RemoteUnlockActivity] as an activity result: what the prompt says in, how it ended out. */
object RemoteUnlock : ActivityResultContract<RemoteUnlock.Ask, RemoteUnlockOutcome>() {
    /** The prompt's two lines, in the language of the screen that asks. */
    data class Ask(val title: String, val subtitle: String? = null)

    override fun createIntent(context: Context, input: Ask): Intent =
        Intent(context, RemoteUnlockActivity::class.java)
            .putExtra(RemoteUnlockActivity.EXTRA_TITLE, input.title)
            .putExtra(RemoteUnlockActivity.EXTRA_SUBTITLE, input.subtitle)

    /** Gone without a word — Back, or the activity torn down — is a cancel: nothing was unlocked. */
    override fun parseResult(resultCode: Int, intent: Intent?): RemoteUnlockOutcome =
        intent?.getIntExtra(RemoteUnlockActivity.EXTRA_OUTCOME, -1)?.let { RemoteUnlockOutcome.entries.getOrNull(it) }
            ?: RemoteUnlockOutcome.CANCELLED
}
