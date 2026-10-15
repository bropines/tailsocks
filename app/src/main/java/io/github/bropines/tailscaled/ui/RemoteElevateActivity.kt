package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.secure.AdminWriteGate
import io.github.bropines.tailscaled.admin.secure.UnlockFailure
import io.github.bropines.tailscaled.admin.secure.UnlockResult
import io.github.bropines.tailscaled.core.REMOTE_CLASS_GUEST
import io.github.bropines.tailscaled.core.RemoteDevicePrompts
import io.github.bropines.tailscaled.core.RemoteElevate
import io.github.bropines.tailscaled.core.RemoteElevateAnswer
import io.github.bropines.tailscaled.core.RemoteElevateLengths
import io.github.bropines.tailscaled.core.RemoteElevateText
import io.github.bropines.tailscaled.core.RemoteJson
import io.github.bropines.tailscaled.core.RemoteLevel
import io.github.bropines.tailscaled.core.RemoteManage
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full rights for a guest for a while, given at the managed device (appctr/remote_elevate.go):
 * the one way they are given. It asks the question (RemoteElevateDialog) and then the device's
 * own lock — fingerprint, face, PIN or password, through the same gate as the admin console's
 * writes (AdminWriteGate), which fails closed — and only then calls the bridge. A device with no
 * lock at all has nothing to ask; there the question itself, answered on the device, is the
 * confirmation, and it says so.
 *
 * Opened from the remote-management page ("Give full rights for a while…"), and for a guest's
 * request: at once over the app's own screen, from its notification, or when TailSocks is next
 * opened (RemoteDevicePrompts.elevateAsk). Transparent over whatever is under it, in a task of
 * its own, never exported. A third way in takes the rights back, from the notification that
 * says who has them: that needs no lock.
 */
class RemoteElevateActivity : FragmentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nodeId = intent.getStringExtra(EXTRA_NODE)?.takeIf(RemoteJson::isNodeId)
        if (nodeId == null) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (intent.getBooleanExtra(EXTRA_TAKE_BACK, false)) {
            lifecycleScope.launch {
                val answer = withContext(Dispatchers.IO) { RemoteElevate.takeBack(applicationContext, nodeId) }
                if (answer.ok) toast(getString(R.string.remote_elevate_taken, name.ifEmpty { nodeId }))
                finish()
            }
            return
        }
        val asked = intent.getLongExtra(EXTRA_SECONDS, NOT_ASKED).takeIf { it != NOT_ASKED }?.let(RemoteElevateLengths::of)
        // The request is in front of a person now: it waits for nobody, and its notification goes.
        if (asked != null) RemoteDevicePrompts.elevateOpened(this, nodeId)
        setContent {
            TailSocksTheme {
                Box(Modifier.fillMaxSize()) {
                    ElevateHost(nodeId, asked) { message ->
                        message?.let(::toast)
                        finish()
                    }
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** The question for one guest, then the lock, then the bridge. [onDone] closes, with a line to say or none. */
    @Composable
    private fun ElevateHost(nodeId: String, asked: Long?, onDone: (String?) -> Unit) {
        val scope = rememberCoroutineScope()
        var form by remember { mutableStateOf<RemoteElevateForm?>(null) }
        LaunchedEffect(nodeId) {
            val read = withContext(Dispatchers.IO) { read(nodeId, asked) }
            if (read == null) onDone(getString(R.string.remote_elevate_gone)) else form = read
        }
        val current = form ?: return
        RemoteElevateDialog(
            form = current,
            onChoose = { form = current.copy(choice = it, failure = null) },
            onFull = { form = current.copy(full = it, failure = null) },
            onConfirm = {
                form = current.copy(working = true, failure = null)
                scope.launch {
                    when (val outcome = give(nodeId, current)) {
                        is Outcome.Given -> onDone(getString(R.string.remote_elevate_given, current.name, RemoteElevateText.clock(this@RemoteElevateActivity, outcome.until)))
                        is Outcome.Stay -> form = outcome.form
                        Outcome.Gone -> onDone(getString(R.string.remote_elevate_gone))
                    }
                }
            },
            onDismiss = { onDone(null) },
        )
    }

    /** The guest as the bridge has it now, or null when it is no guest of this device's any more, or the switch is off. Blocking. */
    private fun read(nodeId: String, asked: Long?): RemoteElevateForm? {
        if (!RemoteManage.isEnabled(this) || !RemoteElevate.isAllowed(this)) return null
        val device = RemoteJson.paired(Appctr.remotePairedJSON()).firstOrNull { it.nodeId == nodeId && it.role == REMOTE_CLASS_GUEST }
            ?: return null
        return RemoteElevateForm.of(
            name = device.name,
            login = device.login,
            asked = asked,
            pairingLeft = device.left,
            fullOn = RemoteLevel.isFullControl(this),
            locked = RemoteElevate.deviceSecure(this),
            files = RemoteLevel.isFilesOpen(this),
        )
    }

    private sealed interface Outcome {
        data class Given(val until: Long) : Outcome
        /** Not given: the question stays, saying why. */
        data class Stay(val form: RemoteElevateForm) : Outcome
        data object Gone : Outcome
    }

    /** The lock, where the device has one, and then the bridge. */
    private suspend fun give(nodeId: String, form: RemoteElevateForm): Outcome {
        // Asked again: a lock set or removed while the question was open decides, not the one it opened with.
        val locked = RemoteElevate.deviceSecure(this)
        if (locked) {
            val length = RemoteElevateText.length(this, RemoteElevateLengths.effective(form.choice, form.pairingLeft))
            val unlock = AdminWriteGate.unlock(this, getString(R.string.remote_elevate_prompt_title, form.name), length)
            val failure = when (unlock) {
                is UnlockResult.Granted -> null
                UnlockResult.Cancelled -> return Outcome.Stay(form.copy(working = false, locked = true))
                is UnlockResult.Failed -> when (unlock.reason) {
                    // Gone since the question opened: the question says so now, and is asked again.
                    UnlockFailure.NO_SCREEN_LOCK -> return Outcome.Stay(form.copy(working = false, locked = false))
                    UnlockFailure.PROMPT_UNAVAILABLE, UnlockFailure.ERROR -> RemoteElevateFailure.LOCK_UNAVAILABLE
                    UnlockFailure.LOCKOUT -> RemoteElevateFailure.LOCKOUT
                    UnlockFailure.NOT_PROVEN -> RemoteElevateFailure.NOT_PROVEN
                }
            }
            if (failure != null) return Outcome.Stay(form.copy(working = false, locked = true, failure = failure))
        } else if (form.locked) {
            // The lock went away while the question was open: it is asked again, saying what that means.
            return Outcome.Stay(form.copy(working = false, locked = false))
        }
        val answer = withContext(Dispatchers.IO) {
            RemoteElevate.grant(applicationContext, nodeId, form.choice, form.full && !form.fullOn)
        }
        return when {
            answer.ok -> Outcome.Given(answer.until)
            answer.error == "not_paired" || answer.error == "not_guest" || answer.error == "no_guests" -> Outcome.Gone
            else -> Outcome.Stay(
                form.copy(
                    working = false,
                    failure = when (answer.error) {
                        RemoteElevateAnswer.NOT_SAVED -> RemoteElevateFailure.NOT_SAVED
                        "off" -> RemoteElevateFailure.OFF
                        else -> RemoteElevateFailure.FAILED
                    }
                )
            )
        }
    }

    companion object {
        private const val EXTRA_NODE = "node"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_TAKE_BACK = "take_back"
        private const val NOT_ASKED = -1L

        /** "Give full rights for a while…" from the remote-management page. */
        fun giveIntent(context: Context, nodeId: String): Intent = base(context, nodeId, "give")

        /** A guest's request for [seconds] (0: until its pairing ends), from [name]. */
        fun askIntent(context: Context, nodeId: String, name: String, seconds: Long): Intent =
            base(context, nodeId, "ask").putExtra(EXTRA_NAME, name).putExtra(EXTRA_SECONDS, seconds)

        /** "Take back", from the notification that says who has full rights. */
        fun takeBackIntent(context: Context, nodeId: String, name: String): Intent =
            base(context, nodeId, "take-back").putExtra(EXTRA_NAME, name).putExtra(EXTRA_TAKE_BACK, true)

        private fun base(context: Context, nodeId: String, what: String): Intent =
            Intent(context, RemoteElevateActivity::class.java)
                // Distinct per guest and per way in, so two notifications' intents are never merged.
                .setData(Uri.parse("tailsocks-remote://elevate/${Uri.encode(nodeId)}/$what"))
                .putExtra(EXTRA_NODE, nodeId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
