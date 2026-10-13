package io.github.bropines.tailscaled.ui

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AccountManager
import io.github.bropines.tailscaled.core.KeyField
import io.github.bropines.tailscaled.core.KeySendEnd
import io.github.bropines.tailscaled.core.KeySendNext
import io.github.bropines.tailscaled.core.KeySendStep
import io.github.bropines.tailscaled.core.ServerField
import io.github.bropines.tailscaled.core.SetupSendStatus
import io.github.bropines.tailscaled.core.SetupTarget
import io.github.bropines.tailscaled.core.formatCheckCode
import io.github.bropines.tailscaled.core.keySendStep
import io.github.bropines.tailscaled.core.maskAuthKey
import io.github.bropines.tailscaled.core.normalizeLoginServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The phone's side of signing a television in with a key (appctr/setup_send.go,
 * core/KeySetup.kt; the television's is ui/TvKeySetup.kt). The scanner reads
 * the television's code and opens this sheet over itself: where the key would
 * go, the key, the server it belongs to if it is not Tailscale's — then the
 * check code the television shows too, and how it ended.
 *
 * The key is typed or pasted into a masked field and is never drawn in full,
 * here or anywhere: once entered, the sheet shows its kind and its length.
 */

/**
 * One send from this phone. A ViewModel so that a rotation keeps it — the
 * code that was scanned, the typed key, the television's answer — without any
 * of it going into the saved instance state, which leaves the process.
 */
internal class KeySendModel : ViewModel() {
    /** A scanned setup code and where it leads. */
    class Scanned(val code: String, val target: SetupTarget)

    /** What the sheet is open for; null while the scanner scans. */
    var scanned by mutableStateOf<Scanned?>(null)
        private set
    var step by mutableStateOf<KeySendStep>(KeySendStep.Form)
        private set
    var key by mutableStateOf("")
    var server by mutableStateOf("")

    private var job: Job? = null

    /** A setup code was read: the sheet opens on its form, or on why this code gets none. */
    fun open(code: String, defaultServer: String) {
        viewModelScope.launch {
            val target = withContext(Dispatchers.Default) { SetupTarget.parse(runCatching { Appctr.setupParse(code) }.getOrNull()) }
            key = ""
            server = defaultServer
            step = KeySendEnd.ofTarget(target)?.let { KeySendStep.Ended(it) } ?: KeySendStep.Form
            scanned = Scanned(code, target)
        }
    }

    /** Sends the key and follows the television's answer to its end. */
    fun send(senderName: String) {
        val to = scanned ?: return
        if (step == KeySendStep.Sending || step is KeySendStep.Compare) return
        step = KeySendStep.Sending
        job = viewModelScope.launch {
            // Blocks until the television answers or cannot be reached: twelve seconds at most.
            var status = withContext(Dispatchers.IO) {
                SetupSendStatus.parse(runCatching { Appctr.setupSend(to.code, key.trim(), normalizeLoginServer(server), senderName) }.getOrNull())
            }
            step = keySendStep(status)
            while (step is KeySendStep.Compare) {
                delay(1000)
                status = withContext(Dispatchers.IO) { SetupSendStatus.parse(runCatching { Appctr.setupSendStatus() }.getOrNull()) }
                step = keySendStep(status)
            }
            // Nothing is left to do with the key unless the same fields go out again.
            val next = (step as? KeySendStep.Ended)?.how?.next
            if (next != null && next != KeySendNext.RESEND && next != KeySendNext.EDIT) key = ""
        }
    }

    /** From a refused field back to the form. */
    fun edit() {
        if (step is KeySendStep.Ended) step = KeySendStep.Form
    }

    /**
     * Forgets the send and the code: the sheet closes. A bundle the television
     * already holds is not taken back by this — it is rejected there.
     */
    fun close() {
        job?.cancel()
        job = null
        // Not a call that waits: it drops the bridge's record of the send and closes its connection.
        runCatching { Appctr.setupSendCancel() }
        key = ""
        server = ""
        step = KeySendStep.Form
        scanned = null
    }

    override fun onCleared() {
        if (scanned != null) close()
    }
}

/** The control server of the phone's active profile, when it is not Tailscale's: what a key made here most likely belongs to. */
internal fun ownLoginServer(context: Context): String {
    val account = AccountManager.getActiveAccount(context)
    return context.getSharedPreferences("appctr_${account.id}", Context.MODE_PRIVATE).getString("login_server", "").orEmpty().trim()
}

/** What the television calls this phone when it asks about the key: the device's name in its tailnet, or its model. */
private fun senderName(context: Context): String {
    val account = AccountManager.getActiveAccount(context)
    val hostname = context.getSharedPreferences("appctr_${account.id}", Context.MODE_PRIVATE).getString("hostname", "").orEmpty().trim()
    return hostname.ifEmpty { Build.MODEL.orEmpty() }
}

/**
 * The sheet over the scanner. [onRescan] goes back to the camera for the
 * television's next code; [onClose] leaves the scanner. While a key is on its
 * way or in the television's hands the sheet does not go away by a swipe or
 * Back — the check code on it is the half of the comparison this phone holds —
 * only by its own button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeySendSheet(model: KeySendModel, onRescan: () -> Unit, onClose: () -> Unit) {
    val scanned = model.scanned ?: return
    // The sheet is a window of its own and would read strings in the system
    // language: the screen's context, configuration and resources go in with it
    // (see wrapContextWithLocale).
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val step = model.step
    val held = step == KeySendStep.Sending || step is KeySendStep.Compare
    val latestHeld by rememberUpdatedState(held)
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = { it != SheetValue.Hidden || !latestHeld },
    )
    val sender = remember(context) { senderName(context) }
    val nothingToPaste = stringResource(R.string.keysend_paste_empty)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !held, shouldDismissOnClickOutside = !held),
    ) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, LocalResources provides resources) {
            KeySendContent(
                target = scanned.target,
                step = step,
                key = model.key,
                onKey = { model.key = it },
                server = model.server,
                onServer = { model.server = it },
                onPaste = {
                    scope.launch {
                        val text = clipboard.readText(context)?.trim()
                        if (text.isNullOrEmpty()) Toast.makeText(context, nothingToPaste, Toast.LENGTH_SHORT).show()
                        else model.key = text
                    }
                },
                onSend = { model.send(sender) },
                onNext = { next ->
                    when (next) {
                        KeySendNext.RESEND -> model.send(sender)
                        KeySendNext.EDIT -> model.edit()
                        KeySendNext.RESCAN -> onRescan()
                        KeySendNext.DONE, KeySendNext.CLOSE -> onClose()
                    }
                },
                onStopWaiting = onClose,
            )
        }
    }
}

/** What [KeySendSheet] holds, without the sheet: a preview draws every step of it. */
@Composable
internal fun KeySendContent(
    target: SetupTarget,
    step: KeySendStep,
    key: String,
    onKey: (String) -> Unit,
    server: String,
    onServer: (String) -> Unit,
    onPaste: () -> Unit,
    onSend: () -> Unit,
    onNext: (KeySendNext) -> Unit,
    onStopWaiting: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.keysend_title), style = MaterialTheme.typography.titleLarge)
        when (step) {
            KeySendStep.Form -> KeySendForm(target, key, onKey, server, onServer, onPaste, onSend)
            KeySendStep.Sending -> Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.keysend_sending), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            is KeySendStep.Compare -> KeySendCompare(step, onStopWaiting)
            is KeySendStep.Ended -> KeySendEnded(step, onNext)
        }
    }
}

@Composable
private fun KeySendForm(
    target: SetupTarget,
    key: String,
    onKey: (String) -> Unit,
    server: String,
    onServer: (String) -> Unit,
    onPaste: () -> Unit,
    onSend: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Tv, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.keysend_target, target.id, target.addresses.joinToString(", ")),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HelpText(stringResource(R.string.keysend_target_help))
    }

    val keyField = KeyField.of(key)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = key,
            onValueChange = onKey,
            label = { Text(stringResource(R.string.keysend_key_label)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            // Masked, and with no way to unmask: what was entered is described below, not shown.
            visualTransformation = remember { PasswordVisualTransformation() },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
            trailingIcon = {
                IconButton(onClick = onPaste) {
                    Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.keysend_paste))
                }
            },
            isError = !keyField.sendable && keyField != KeyField.EMPTY,
            supportingText = when (keyField) {
                KeyField.EMPTY -> null
                KeyField.BAD_CHARACTERS -> ({ Text(stringResource(R.string.keysend_key_bad)) })
                KeyField.TOO_LONG -> ({ Text(stringResource(R.string.keysend_key_long)) })
                KeyField.API_TOKEN -> ({ Text(stringResource(R.string.keysend_key_api)) })
                KeyField.OK, KeyField.UNKNOWN_KIND -> ({
                    val length = key.trim().length
                    val summary = stringResource(
                        R.string.keysend_key_summary,
                        maskAuthKey(key),
                        pluralStringResource(R.plurals.keysend_key_chars, length, length),
                    )
                    Text(if (keyField == KeyField.UNKNOWN_KIND) summary + "\n" + stringResource(R.string.keysend_key_unknown) else summary)
                })
            },
            modifier = Modifier.fillMaxWidth(),
        )
        HelpText(stringResource(R.string.keysend_key_help))
    }

    val serverField = ServerField.of(server)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = server,
            onValueChange = onServer,
            label = { Text(stringResource(R.string.keysend_server_label)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
            isError = !serverField.sendable,
            supportingText = if (serverField.sendable) null else ({ Text(stringResource(R.string.keysend_server_bad)) }),
            modifier = Modifier.fillMaxWidth(),
        )
        HelpText(stringResource(R.string.keysend_server_help))
    }

    Button(onClick = onSend, enabled = keyField.sendable && serverField.sendable, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_send))
    }
}

@Composable
private fun KeySendCompare(step: KeySendStep.Compare, onStopWaiting: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            formatCheckCode(step.checkCode),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 64.sp),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(stringResource(R.string.keysend_compare), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        HelpText(stringResource(R.string.keysend_compare_help), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.keysend_sent_to, step.address),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onStopWaiting) { Text(stringResource(R.string.keysend_stop_waiting)) }
    }
}

@Composable
private fun KeySendEnded(step: KeySendStep.Ended, onNext: (KeySendNext) -> Unit) {
    val how = step.how
    val (title: Int, text: Int) = when (how) {
        KeySendEnd.CONFIRMED -> R.string.keysend_end_confirmed_title to R.string.keysend_end_confirmed_text
        KeySendEnd.REJECTED -> R.string.keysend_end_rejected_title to R.string.keysend_end_rejected_text
        KeySendEnd.EXPIRED -> R.string.keysend_end_expired_title to R.string.keysend_end_expired_text
        KeySendEnd.CANCELLED -> R.string.keysend_end_cancelled_title to R.string.keysend_end_cancelled_text
        KeySendEnd.LOST -> R.string.keysend_end_lost_title to R.string.keysend_end_lost_text
        KeySendEnd.TAKEN -> R.string.keysend_end_taken_title to R.string.keysend_end_taken_text
        KeySendEnd.UNSUPPORTED -> R.string.keysend_end_unsupported_title to R.string.keysend_end_unsupported_text
        KeySendEnd.CLOSED -> R.string.keysend_end_closed_title to R.string.keysend_end_closed_text
        KeySendEnd.UNREACHABLE -> R.string.keysend_end_unreachable_title to R.string.keysend_network_hint
        KeySendEnd.WRONG_NETWORK -> R.string.keysend_end_wrong_network_title to R.string.keysend_end_wrong_network_text
        KeySendEnd.NO_ANSWER -> R.string.keysend_end_no_answer_title to R.string.keysend_end_no_answer_text
        KeySendEnd.INVALID_CODE -> R.string.keysend_end_invalid_code_title to R.string.keysend_end_invalid_code_text
        KeySendEnd.NEWER_CODE -> R.string.keysend_end_newer_code_title to R.string.keysend_end_newer_code_text
        KeySendEnd.INVALID_KEY -> R.string.keysend_end_invalid_key_title to R.string.keysend_key_bad
        KeySendEnd.INVALID_SERVER -> R.string.keysend_end_invalid_server_title to R.string.keysend_server_bad
        KeySendEnd.BUSY -> R.string.keysend_end_busy_title to R.string.keysend_end_busy_text
        KeySendEnd.UNKNOWN -> R.string.keysend_end_unknown_title to R.string.keysend_end_unknown_text
    }
    val icon: ImageVector = when {
        how == KeySendEnd.CONFIRMED -> Icons.Default.CheckCircle
        how == KeySendEnd.REJECTED -> Icons.Default.Block
        how == KeySendEnd.EXPIRED || how == KeySendEnd.BUSY -> Icons.Default.Schedule
        how.network -> Icons.Default.WifiOff
        else -> Icons.Default.ErrorOutline
    }
    val good = how == KeySendEnd.CONFIRMED
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(56.dp).background(
                if (good) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null,
                tint = if (good) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // The phone is on another network: say what the two have to share.
        if (how == KeySendEnd.WRONG_NETWORK) {
            Text(
                stringResource(R.string.keysend_network_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (step.address.isNotEmpty() && how != KeySendEnd.CONFIRMED) {
            Text(
                stringResource(R.string.keysend_sent_to, step.address),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = { onNext(how.next) }, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    when (how.next) {
                        KeySendNext.DONE -> R.string.keysend_done
                        KeySendNext.RESCAN -> R.string.keysend_rescan
                        KeySendNext.RESEND -> R.string.action_retry
                        KeySendNext.EDIT -> R.string.keysend_edit
                        KeySendNext.CLOSE -> R.string.action_close
                    }
                )
            )
        }
    }
}
