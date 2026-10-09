package io.github.bropines.tailscaled.admin.notify

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.UserStatus
import io.github.bropines.tailscaled.admin.attention.AttentionChanges
import io.github.bropines.tailscaled.admin.attention.AttentionKind
import io.github.bropines.tailscaled.admin.console.ConsoleChanges
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.profile.AdminProfiles
import io.github.bropines.tailscaled.admin.safety.AdminAuditLog
import io.github.bropines.tailscaled.admin.safety.ChangeOutcome
import io.github.bropines.tailscaled.admin.safety.GateEvidence
import io.github.bropines.tailscaled.admin.safety.PlannedChange
import io.github.bropines.tailscaled.admin.safety.SafeChangeRunner
import io.github.bropines.tailscaled.admin.safety.SafetyContext
import io.github.bropines.tailscaled.admin.safety.SafetyHost
import io.github.bropines.tailscaled.admin.safety.SafetyStep
import io.github.bropines.tailscaled.admin.secure.AdminWriteGate
import io.github.bropines.tailscaled.admin.secure.UnlockResult
import io.github.bropines.tailscaled.core.AppJson
import io.github.bropines.tailscaled.core.ProxyState
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import io.github.bropines.tailscaled.models.StatusResponse
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a notification's Approve or Reject asked for. */
data class ActionRequest(
    val profileId: String,
    val kind: AttentionKind,
    val targetId: String,
    val targetName: String,
    val notifyKey: String,
    val approve: Boolean,
) {
    companion object {
        fun from(intent: Intent?): ActionRequest? {
            intent ?: return null
            val kind = runCatching { AttentionKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }.getOrNull() ?: return null
            if (kind != AttentionKind.DEVICE_APPROVAL && kind != AttentionKind.USER_APPROVAL) return null
            return ActionRequest(
                profileId = intent.getStringExtra(EXTRA_PROFILE)?.takeIf { it.isNotBlank() } ?: return null,
                kind = kind,
                targetId = intent.getStringExtra(EXTRA_TARGET)?.takeIf { it.isNotBlank() } ?: return null,
                targetName = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                notifyKey = intent.getStringExtra(EXTRA_KEY).orEmpty(),
                approve = when (intent.getStringExtra(EXTRA_ACTION)) {
                    AttentionActionActivity.ACTION_APPROVE -> true
                    AttentionActionActivity.ACTION_REJECT -> false
                    else -> return null
                },
            )
        }

        internal const val EXTRA_PROFILE = "profile"
        internal const val EXTRA_KIND = "kind"
        internal const val EXTRA_TARGET = "target"
        internal const val EXTRA_NAME = "name"
        internal const val EXTRA_KEY = "key"
        internal const val EXTRA_ACTION = "action"
    }
}

/**
 * Approve or Reject from a notification, through the same gates as the console: the target is
 * read again (a device approved elsewhere in the meantime is not deleted by a stale Reject),
 * the change is confirmed with its target and effect — Reject deletes, so it takes the typed
 * name — the write unlock is asked, and the console's runner applies, verifies and records it
 * in the admin audit log. The result replaces the notification. Nothing is written from a
 * receiver or without the unlock; the activity is transparent and not exported.
 */
class AttentionActionActivity : FragmentActivity() {
    private val vm: AttentionActionViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = ActionRequest.from(intent)
        if (request == null) {
            finish()
            return
        }
        vm.start(request)
        setContent {
            TailSocksTheme {
                val state by vm.state.collectAsState()
                ActionScreen(state, vm)
                LaunchedEffect(state.finished) {
                    val end = state.finished ?: return@LaunchedEffect
                    end.message?.let { Toast.makeText(this@AttentionActionActivity, it, Toast.LENGTH_LONG).show() }
                    finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_APPROVE = "approve"
        const val ACTION_REJECT = "reject"

        fun intent(context: Context, profileId: String, kind: AttentionKind, targetId: String, targetName: String, notifyKey: String, action: String): Intent =
            Intent(context, AttentionActionActivity::class.java)
                // Distinct per target and action, so two notifications' intents are never merged.
                .setData(Uri.parse("tailsocks-admin://attention/${Uri.encode(profileId)}/${Uri.encode(notifyKey)}/$action"))
                .putExtra(ActionRequest.EXTRA_PROFILE, profileId)
                .putExtra(ActionRequest.EXTRA_KIND, kind.name)
                .putExtra(ActionRequest.EXTRA_TARGET, targetId)
                .putExtra(ActionRequest.EXTRA_NAME, targetName)
                .putExtra(ActionRequest.EXTRA_KEY, notifyKey)
                .putExtra(ActionRequest.EXTRA_ACTION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}

@Composable
private fun ActionScreen(state: ActionUiState, vm: AttentionActionViewModel) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        state.checking?.let { name ->
            AlertDialog(
                onDismissRequest = { vm.cancel() },
                title = { Text(name) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(ctx.getString(R.string.admin_attention_action_checking, name))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { vm.cancel() }) { Text(ctx.getString(R.string.action_cancel)) } },
            )
        }
        SafetyHost(
            step = state.safety,
            onConfirm = { vm.confirm(it) },
            onCancel = { vm.cancel() },
            onUnlockResult = { vm.onUnlockResult(it) },
        )
    }
}

data class ActionEnd(val message: String?)

data class ActionUiState(
    /** The target being read again before anything is offered. */
    val checking: String? = null,
    val safety: SafetyStep = SafetyStep.Idle,
    val finished: ActionEnd? = null,
)

class AttentionActionViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ActionUiState())
    val state: StateFlow<ActionUiState> = _state.asStateFlow()

    private var started = false
    private var request: ActionRequest? = null
    private var profile: AdminProfile? = null
    private var runner: SafeChangeRunner? = null

    private val text: Context get() = wrapContextWithLocale(getApplication())

    fun start(r: ActionRequest) {
        if (started) return
        started = true
        request = r
        _state.update { it.copy(checking = r.targetName.ifBlank { r.targetId }) }
        viewModelScope.launch {
            try {
                prepare(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "prepare: ${e.javaClass.simpleName}")
                finish(text.getString(R.string.admin_attention_action_read_failed, r.targetName, ConsoleText.error(text, e)))
            }
        }
    }

    private suspend fun prepare(r: ActionRequest) {
        val app = getApplication<Application>()
        val ctx = text
        val p = withContext(Dispatchers.IO) { AdminProfiles.store(app).get(r.profileId) }
            ?: return finish(ctx.getString(R.string.admin_attention_action_profile_gone))
        profile = p
        val backend = withContext(Dispatchers.IO) { AdminProfiles.newBackend(app, p) }
        runCatching { backend.refreshCapabilities() }.onFailure { if (it is CancellationException) throw it }
        val self = withContext(Dispatchers.IO) { selfNodeId(app) }

        val planned: PlannedChange = try {
            when (r.kind) {
                AttentionKind.DEVICE_APPROVAL -> {
                    val d = backend.getDevice(r.targetId)
                    if (d.authorized != false) return stale(r, p, R.string.admin_attention_action_not_pending)
                    if (r.approve) ConsoleChanges.setAuthorized(ctx, d, true, self) else AttentionChanges.rejectDevice(ctx, d, self)
                }
                AttentionKind.USER_APPROVAL -> {
                    val u = backend.getUser(r.targetId)
                    if (u.userStatus != UserStatus.NEEDS_APPROVAL) return stale(r, p, R.string.admin_attention_action_not_pending)
                    if (r.approve) ConsoleChanges.approveUser(ctx, u) else AttentionChanges.rejectUser(ctx, u)
                }
                else -> return finish(null)
            }
        } catch (e: AdminApiException.NotFound) {
            return stale(r, p, R.string.admin_attention_action_gone)
        }

        val caps = backend.capabilities.value
        val lock = AdminWriteGate.lockState(app)
        val run = SafeChangeRunner(backend, AdminAuditLog(AdminAuditLog.fileIn(app.filesDir)), {
            SafetyContext(
                profileId = p.id,
                profileName = p.displayName,
                readOnlyProfile = p.readOnly,
                lockState = lock,
                ownKeyId = caps.ownKeyId,
                ownUserId = caps.ownUserId,
                canWrite = { area -> caps.canWrite(area) },
            )
        })
        runner = run
        val blocked = run.blockedBy(planned.change)
        if (blocked != null) {
            // Recorded as the console records it: the log shows what was stopped too.
            withContext(Dispatchers.IO) { run.run(planned, GateEvidence(confirmed = false)) }
            val why = ConsoleText.refusal(ctx, blocked)
            AttentionNotifier.postResult(ctx, p, r.notifyKey, planned.change.title, why, keepOriginal = true)
            return finish(why)
        }
        _state.update { it.copy(checking = null, safety = SafetyStep.Confirm(planned)) }
    }

    /** The notification asked about something that changed since: say so and withdraw it. */
    private fun stale(r: ActionRequest, p: AdminProfile, message: Int) {
        val name = r.targetName.ifBlank { r.targetId }
        AttentionNotifier.cancelItem(text, p.id, r.notifyKey)
        finish(text.getString(message, name))
    }

    fun confirm(typedName: String?) {
        val step = _state.value.safety as? SafetyStep.Confirm ?: return
        _state.update { it.copy(safety = SafetyStep.Unlock(step.planned, typedName)) }
    }

    fun cancel() {
        if (_state.value.safety is SafetyStep.Applying) return
        finish(text.getString(R.string.admin2_unlock_cancelled))
    }

    fun onUnlockResult(result: UnlockResult) {
        val step = _state.value.safety as? SafetyStep.Unlock ?: return
        when (result) {
            is UnlockResult.Granted -> apply(step.planned, GateEvidence(true, step.typedName, result.grant))
            UnlockResult.Cancelled -> finish(text.getString(R.string.admin2_unlock_cancelled))
            is UnlockResult.Failed -> finish(ConsoleText.unlockFailure(text, result.reason))
        }
    }

    private fun apply(planned: PlannedChange, gates: GateEvidence) {
        val run = runner ?: return
        val r = request ?: return
        val p = profile ?: return
        _state.update { it.copy(safety = SafetyStep.Applying(planned.change)) }
        viewModelScope.launch {
            val out = withContext(Dispatchers.IO) { run.run(planned, gates) }
            val ctx = text
            val message = ConsoleText.outcome(ctx, planned.change.title, out)
            // A failure leaves the notification's actions for another try.
            AttentionNotifier.postResult(ctx, p, r.notifyKey, planned.change.title, message, keepOriginal = out !is ChangeOutcome.Applied)
            finish(message)
        }
    }

    private fun finish(message: String?) {
        _state.update { it.copy(checking = null, safety = SafetyStep.Idle, finished = ActionEnd(message)) }
    }

    companion object {
        private const val TAG = "AdminAttentionAction"

        /** This phone's node id when the daemon runs: a change on it carries the "this phone" warning. */
        fun selfNodeId(app: Application): String? = runCatching {
            if (!ProxyState.isActualRunning(app)) return@runCatching null
            AppJson.decodeFromString(StatusResponse.serializer(), Appctr.getStatusFromAPI()).self?.id
        }.getOrNull()
    }
}
