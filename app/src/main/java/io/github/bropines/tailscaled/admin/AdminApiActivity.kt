package io.github.bropines.tailscaled.admin

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.console.AdminConsoleViewModel
import io.github.bropines.tailscaled.admin.console.ConsolePhase
import io.github.bropines.tailscaled.admin.console.ConsoleState
import io.github.bropines.tailscaled.admin.console.ConsoleTab
import io.github.bropines.tailscaled.admin.console.ProfileDraft
import io.github.bropines.tailscaled.admin.notify.AttentionLaunch
import io.github.bropines.tailscaled.admin.safety.SafetyHost
import io.github.bropines.tailscaled.admin.safety.SecretRevealDialog
import io.github.bropines.tailscaled.admin.secure.AdminWriteGate
import io.github.bropines.tailscaled.admin.secure.ViewUnlock
import io.github.bropines.tailscaled.admin.secure.findFragmentActivity
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import io.github.bropines.tailscaled.ui.AppTopBar
import io.github.bropines.tailscaled.ui.LocalDemo
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme

/**
 * The admin console. Its state lives in [AdminConsoleViewModel] and survives rotation; the
 * activity only hosts it. A FragmentActivity because the unlock prompts need one.
 */
class AdminApiActivity : FragmentActivity() {
    private val vm: AdminConsoleViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // From an attention notification: open on the profile it was about.
        if (savedInstanceState == null) AttentionLaunch.selectProfile(this, intent)
        setContent {
            TailSocksTheme {
                AdminConsoleRoot(vm, onBack = { finish() })
            }
        }
    }
}

/**
 * The console as a composable: the ViewModel's state in the app, the demo's in the preview
 * renderer, which has no Keystore, no bridge and no ViewModel store.
 */
@Composable
fun AdminApiMainScreen(onBack: () -> Unit) {
    if (LocalInspectionMode.current) {
        val demo = LocalDemo.current?.admin
        AdminConsoleContent(demo ?: ConsoleState(phase = ConsolePhase.SETUP, draft = ProfileDraft()), vm = null, onBack = onBack)
        return
    }
    AdminConsoleRoot(viewModel(), onBack)
}

@Composable
fun AdminConsoleRoot(vm: AdminConsoleViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    AdminConsoleContent(state, vm, onBack)
}

/** Every phase of the console, the safety gates over it, and the messages under it. */
@Composable
fun AdminConsoleContent(state: ConsoleState, vm: AdminConsoleViewModel?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    BackHandler(enabled = state.phase == ConsolePhase.EDIT_PROFILE) { vm?.profiles?.cancel() }

    Box(Modifier.fillMaxSize()) {
        when (state.phase) {
            ConsolePhase.LOADING -> AdminWaitScreen(onBack) { LoadingIndicatorCompat() }
            ConsolePhase.SETUP, ConsolePhase.EDIT_PROFILE -> state.draft?.let { draft ->
                AdminProfileEditorScreen(
                    draft = draft,
                    firstProfile = state.profiles.isEmpty(),
                    onChange = { vm?.profiles?.change(it) },
                    onSave = { vm?.profiles?.save(it) },
                    onCancel = { if (state.phase == ConsolePhase.SETUP) onBack() else vm?.profiles?.cancel() },
                    onDelete = { draft.id?.let { vm?.profiles?.delete(it) } },
                    onUnlockForWrites = { vm?.profiles?.onUnlockForWrites(it) },
                )
            } ?: AdminWaitScreen(onBack) { LoadingIndicatorCompat() }
            ConsolePhase.LOCKED -> AdminLockedScreen(state.viewUnlockUnavailable, onBack) { vm?.onViewUnlock(it) }
            ConsolePhase.READY -> AdminDashboard(state, vm, onBack, startTab = ConsoleTab.ATTENTION)
        }

        SafetyHost(
            step = state.safety,
            onConfirm = { vm?.confirmChange(it) },
            onCancel = { vm?.cancelChange() },
            onUnlockResult = { vm?.onUnlockResult(it) },
        )
        state.revealed?.let { SecretRevealDialog(it.title, it.text, it.secret, qr = it.qr, once = it.once) { vm?.secretSaved() } }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    val message = state.message
    LaunchedEffect(message?.id) {
        if (message == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = message.text,
            actionLabel = message.undo?.let { ctx.getString(R.string.admin2_undo) },
            withDismissAction = message.undo == null,
            duration = if (message.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        vm?.messageShown(message.id)
        if (result == SnackbarResult.ActionPerformed) message.undo?.let { vm?.propose(it) }
    }
}

/**
 * In front of the console: the screen lock or a biometric, asked once on arrival. Fails
 * closed — the console stays locked when no prompt can be shown. (A phone with no screen lock
 * never gets here: it opens read-only instead.)
 */
@Composable
private fun AdminLockedScreen(unavailable: Boolean, onBack: () -> Unit, onResult: (ViewUnlock) -> Unit) {
    val ctx = LocalContext.current
    val inPreview = LocalInspectionMode.current
    var asked by rememberSaveable { mutableStateOf(false) }
    fun ask() {
        if (inPreview) return
        val activity = ctx.findFragmentActivity() ?: return onResult(ViewUnlock.UNAVAILABLE)
        AdminWriteGate.unlockToView(activity, ctx.getString(R.string.admin2_view_unlock_title), ctx.getString(R.string.admin2_view_unlock_subtitle), onResult)
    }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            ask()
        }
    }
    AdminWaitScreen(onBack) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(Icons.Default.Lock, contentDescription = ctx.getString(R.string.admin_cd_locked), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Text(ctx.getString(R.string.admin_locked_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                ctx.getString(if (unavailable) R.string.admin2_view_unlock_unavailable else R.string.admin_locked_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = if (unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { ask() }, shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.Fingerprint, null)
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.action_unlock))
            }
        }
    }
}

/** The console's frame while there is nothing to show yet, with a way back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminWaitScreen(onBack: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    Scaffold(topBar = { AppTopBar(title = ctx.getString(R.string.admin_console_title), onBack = onBack) }) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
