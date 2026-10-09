package io.github.bropines.tailscaled.admin.safety

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.secure.AdminWriteGate
import io.github.bropines.tailscaled.admin.secure.SensitiveClipboard
import io.github.bropines.tailscaled.admin.secure.UnlockResult
import io.github.bropines.tailscaled.admin.secure.findFragmentActivity
import io.github.bropines.tailscaled.ui.HelpText
import io.github.bropines.tailscaled.ui.QrCodeImage

/**
 * Where a proposed change is in its gates. The console's ViewModel holds it; [SafetyHost]
 * draws it. LOW changes skip straight to applying: the sheet that asked was their confirm.
 */
sealed class SafetyStep {
    data object Idle : SafetyStep()
    /** The confirm dialog: target, effect, diff and — for HIGH — the typed name. */
    data class Confirm(val planned: PlannedChange) : SafetyStep()
    /** Confirmed; the unlock prompt is up. */
    data class Unlock(val planned: PlannedChange, val typedName: String?) : SafetyStep()
    data class Applying(val change: AdminChange) : SafetyStep()
}

/**
 * The gates on screen. Strings are resolved here, in the parent composition: a dialog is a
 * window of its own and would look them up in the system language (see wrapContextWithLocale).
 */
@Composable
fun SafetyHost(
    step: SafetyStep,
    onConfirm: (typedName: String?) -> Unit,
    onCancel: () -> Unit,
    onUnlockResult: (UnlockResult) -> Unit,
) {
    when (step) {
        SafetyStep.Idle -> Unit
        is SafetyStep.Confirm -> ChangeConfirmDialog(step.planned.change, onConfirm, onCancel)
        is SafetyStep.Unlock -> UnlockEffect(step, onUnlockResult)
        is SafetyStep.Applying -> ApplyingDialog(step.change)
    }
}

@Composable
private fun UnlockEffect(step: SafetyStep.Unlock, onResult: (UnlockResult) -> Unit) {
    val ctx = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val currentOnResult by rememberUpdatedState(onResult)
    val title = ctx.getString(R.string.admin2_write_unlock_title)
    LaunchedEffect(step) {
        if (inPreview) return@LaunchedEffect
        val activity = ctx.findFragmentActivity()
        if (activity == null) {
            currentOnResult(UnlockResult.Failed(io.github.bropines.tailscaled.admin.secure.UnlockFailure.PROMPT_UNAVAILABLE))
            return@LaunchedEffect
        }
        currentOnResult(AdminWriteGate.unlock(activity, title, step.planned.change.title))
    }
}

/** The badge that says how risky a change is, in words and an icon, not only a colour. */
@Composable
fun ChangeClassBadge(changeClass: ChangeClass, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val (label, icon) = when (changeClass) {
        ChangeClass.LOW -> ctx.getString(R.string.admin2_class_low) to Icons.Default.Shield
        ChangeClass.MEDIUM -> ctx.getString(R.string.admin2_class_medium) to Icons.Default.Lock
        ChangeClass.HIGH -> ctx.getString(R.string.admin2_class_high) to Icons.Default.ReportProblem
        ChangeClass.POLICY -> ctx.getString(R.string.admin2_class_policy) to Icons.Default.ReportProblem
    }
    val high = changeClass == ChangeClass.HIGH || changeClass == ChangeClass.POLICY
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (high) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (high) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Before → after, a row per field: the old value struck through, the new one plain. */
@Composable
fun DiffPreview(lines: List<DiffLine>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val nothing = ctx.getString(R.string.admin2_confirm_nothing)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ctx.getString(R.string.admin2_confirm_diff), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lines.forEach { line ->
                Column {
                    Text(line.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ctx.getString(R.string.admin2_confirm_before) + ": ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Text(
                            line.before ?: nothing,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error,
                            textDecoration = if (line.before != null) TextDecoration.LineThrough else null,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ctx.getString(R.string.admin2_confirm_after) + ": ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Text(
                            line.after ?: nothing,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The target's name, typed back — or, with [phrase], a fixed phrase ("apply policy"), quoted.
 * Matches exactly, apart from spaces around it.
 */
@Composable
fun TypedConfirmationField(expected: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, phrase: Boolean = false) {
    val ctx = LocalContext.current
    Column(modifier) {
        Text(ctx.getString(if (phrase) R.string.admin_cfg_confirm_phrase else R.string.admin2_confirm_type, expected), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(top = 6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            label = { Text(ctx.getString(if (phrase) R.string.admin_cfg_confirm_phrase_label else R.string.admin2_confirm_type_label)) },
            isError = value.isNotEmpty() && value.trim() != expected.trim(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The confirm gate: what the change is, what it acts on, what it does; the diff and the typed
 * name for HIGH changes. Confirm stays disabled until the name matches.
 */
@Composable
fun ChangeConfirmDialog(change: AdminChange, onConfirm: (typedName: String?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var typed by rememberSaveable(change.target.id, change.kind) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (change.isHighRisk) Icons.Default.Warning else Icons.Default.Lock, null) },
        title = { Text(change.title) },
        text = { ChangeConfirmContent(change, typed) { typed = it } },
        confirmButton = { ChangeConfirmButton(change, typed) { onConfirm(if (change.needsTypedConfirmation) typed else null) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.action_cancel)) } },
    )
}

/** The confirm button: "Unlock and apply" for what needs the unlock, red for HIGH, off until the name matches. */
@Composable
fun ChangeConfirmButton(change: AdminChange, typed: String, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val nameOk = !change.needsTypedConfirmation || typed.trim() == change.confirmText.trim()
    Button(
        onClick = onClick,
        enabled = nameOk,
        colors = if (change.isHighRisk) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        else ButtonDefaults.buttonColors(),
    ) {
        Text(ctx.getString(if (change.needsUnlock) R.string.admin2_confirm_apply else R.string.action_confirm))
    }
}

/** The confirm dialog's body, apart so a preview can draw it without a dialog window. */
@Composable
fun ChangeConfirmContent(change: AdminChange, typed: String, onTyped: (String) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChangeClassBadge(change.changeClass)
        Column {
            Text(ctx.getString(R.string.admin2_confirm_target), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(change.target.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            // The tailnet's id is "-": nothing worth showing under its name.
            if (change.target.id != change.target.name && change.target.type != TargetType.TAILNET) {
                Text(change.target.id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
            }
        }
        Text(change.effect, style = MaterialTheme.typography.bodyMedium)
        change.warnings.forEach { WarningLine(it) }
        if (change.target.isThisDevice) WarningLine(ctx.getString(R.string.admin2_confirm_this_device))
        if (change.diff.isNotEmpty()) DiffPreview(change.diff)
        if (change.needsTypedConfirmation) TypedConfirmationField(change.confirmText, typed, onTyped, phrase = change.confirmPhrase != null)
        if (change.needsUnlock) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LockOpen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(6.dp))
                Text(ctx.getString(R.string.admin2_confirm_unlock_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WarningLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ApplyingDialog(change: AdminChange) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(change.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ctx.getString(R.string.admin2_applying))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
    )
}

/**
 * Why changes are off, on the screen they are off on: no screen lock, a read-only profile, a
 * read-only credential. The first sentence stands alone; the rest folds.
 */
@Composable
fun ReadOnlyBanner(title: String, text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Default.Lock) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                HelpText(text, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/**
 * A secret the server hands out once — a new auth key, an OAuth client's secret, a webhook's
 * signing secret — or a link that admits whoever holds it ([once] false: it can be read again
 * later). Kept out of screenshots, masked until asked, copied as sensitive, shareable, with a
 * QR code when [qr] is set, and closed only by its own button: a stray tap outside used to
 * throw away the only copy. Its words come from the console's context: the dialog's own window
 * would answer in the system language.
 */
@Composable
fun SecretRevealDialog(title: String, text: String, secret: String, qr: Boolean = false, once: Boolean = true, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn),
        icon = { Icon(Icons.Default.Key, null) },
        title = { Text(title) },
        text = {
            CompositionLocalProvider(LocalContext provides ctx, LocalConfiguration provides configuration, LocalResources provides resources) {
                SecretRevealContent(title, text, secret, qr = qr, once = once)
            }
        },
        confirmButton = {
            Button(onClick = onDone) { Text(ctx.getString(if (once) R.string.admin2_secret_saved else R.string.action_close)) }
        },
    )
}

/**
 * The reveal dialog's body, apart so a preview can draw it without a dialog window. The secret
 * and its code stay hidden until shown: the screen may be in view of someone else.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecretRevealContent(
    label: String,
    text: String,
    secret: String,
    qr: Boolean = false,
    once: Boolean = true,
    startShown: Boolean = false,
) {
    val ctx = LocalContext.current
    var shown by rememberSaveable { mutableStateOf(startShown) }
    var qrShown by rememberSaveable { mutableStateOf(startShown && qr) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (once) Text(ctx.getString(R.string.admin2_secret_once), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (shown) secret else maskSecret(secret),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            IconButton(onClick = { shown = !shown }) {
                Icon(
                    if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = ctx.getString(if (shown) R.string.admin2_secret_hide else R.string.admin2_secret_show),
                )
            }
        }
        if (qr && qrShown) {
            QrCodeImage(
                text = secret,
                contentDescription = ctx.getString(R.string.admin_k_reveal_qr_description),
                tooLong = ctx.getString(R.string.qr_too_long),
                modifier = Modifier.fillMaxWidth().widthIn(max = 280.dp).align(Alignment.CenterHorizontally),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = {
                SensitiveClipboard.copy(ctx, label, secret)
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.admin2_secret_copied), android.widget.Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin2_secret_copy))
            }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, secret)
                ctx.startActivity(Intent.createChooser(send, label))
            }) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(R.string.admin_k_reveal_share))
            }
            if (qr) {
                OutlinedButton(onClick = { qrShown = !qrShown }) {
                    Icon(Icons.Default.QrCode2, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ctx.getString(if (qrShown) R.string.admin_k_reveal_qr_hide else R.string.admin_k_reveal_qr_show))
                }
            }
        }
    }
}

/**
 * [secret] with what makes it secret hidden: a Tailscale key keeps its "tskey-auth-<id>-" part,
 * which names the key and grants nothing, and a link its address up to the last slash.
 */
fun maskSecret(secret: String): String {
    val cut = maxOf(secret.lastIndexOf('-'), secret.lastIndexOf('/'))
    val keep = if (cut in 1 until secret.length - 4) secret.substring(0, cut + 1) else ""
    return keep + "\u2022".repeat(12)
}
