package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.RemoteElevateLengths
import io.github.bropines.tailscaled.core.RemoteElevateText

/*
 * Remote management, the question a person at the managed device answers before a guest gets
 * full rights for a while (appctr/remote_elevate.go): for whom, for how long, with full control
 * or not — and how it is confirmed: with the device's screen lock, or, on a device that has
 * none, by whoever is in front of it, which the dialog says plainly. RemoteElevateActivity
 * shows it, the same whether the rights are offered from the page or the guest asked for them.
 */

/** Why the rights were not given, said under the question; it stays open for another try. */
enum class RemoteElevateFailure {
    /** The device has a lock and cannot ask for it here. */
    LOCK_UNAVAILABLE,
    /** Too many tries: the lock is blocked for a while. */
    LOCKOUT,
    /** The prompt said yes and the Keystore did not believe it. */
    NOT_PROVEN,
    /** The bridge could not write the grant down, so it did not give it. */
    NOT_SAVED,
    /** The switch was turned off meanwhile. */
    OFF,
    FAILED,
}

/** What the dialog shows. */
@Immutable
data class RemoteElevateForm(
    val name: String,
    val login: String = "",
    /** The length the guest asked for; null when the rights are offered from this device's page. */
    val asked: Long? = null,
    /** Seconds the guest's pairing has left; -1 for until revoked. */
    val pairingLeft: Long = -1,
    val choice: Long = RemoteElevateLengths.DEFAULT,
    /** The full-control tier for this guest as well. */
    val full: Boolean = false,
    /** The device's own Full control is on: it comes with the rights anyway. */
    val fullOn: Boolean = false,
    /** The device's network access to files is on: the files may come with the rights. Off, they never do. */
    val files: Boolean = true,
    /** The device has a screen lock to confirm with; without one, the person in front of it confirms. */
    val locked: Boolean = true,
    /** The lock is being asked, or the rights given. */
    val working: Boolean = false,
    val failure: RemoteElevateFailure? = null,
) {
    /** The lengths worth offering this pairing. */
    val lengths: List<Long> get() = RemoteElevateLengths.offered(pairingLeft)

    companion object {
        /** The form for a guest with [pairingLeft] seconds left: what it asked for when it is on offer, else the default. */
        fun of(name: String, login: String, asked: Long?, pairingLeft: Long, fullOn: Boolean, locked: Boolean, files: Boolean = true): RemoteElevateForm {
            val offered = RemoteElevateLengths.offered(pairingLeft)
            val wanted = asked ?: RemoteElevateLengths.DEFAULT
            val choice = if (wanted in offered) wanted else RemoteElevateLengths.UNTIL_PAIRING_ENDS
            return RemoteElevateForm(name, login, asked, pairingLeft, choice, fullOn = fullOn, files = files, locked = locked)
        }
    }
}

/** A length as the dialog offers it; "until the pairing ends" with what that is. */
@Composable
internal fun remoteElevateLengthText(seconds: Long, pairingLeft: Long): String {
    val context = LocalContext.current
    return when (seconds) {
        15 * 60L -> stringResource(R.string.remote_elevate_len_15m)
        60 * 60L -> stringResource(R.string.remote_elevate_len_1h)
        8 * 60 * 60L -> stringResource(R.string.remote_elevate_len_8h)
        else -> {
            val real = RemoteElevateLengths.effective(RemoteElevateLengths.UNTIL_PAIRING_ENDS, pairingLeft)
            stringResource(R.string.remote_elevate_len_pairing_left, RemoteElevateText.length(context, real))
        }
    }
}

/**
 * The question. [onConfirm] asks the lock, or gives the rights at once where there is none;
 * [onDismiss] gives nothing. On a television the remote starts on the answer that gives
 * nothing, as every question here that hands more of a device out does.
 */
@Composable
internal fun RemoteElevateDialog(
    form: RemoteElevateForm,
    onChoose: (Long) -> Unit,
    onFull: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Resolved here: a dialog is a window of its own (see wrapContextWithLocale).
    val words = remoteElevateWords(form)
    AppAlertDialog(
        onDismissRequest = { if (!form.working) onDismiss() },
        title = { Text(words.title) },
        text = { RemoteElevateBody(form, words, onChoose, onFull, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { RemoteElevateConfirm(form, words, onConfirm) },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !form.working, modifier = Modifier.tvInitialFocus()) { Text(words.no) } },
    )
}

/** What [RemoteElevateDialog] holds, drawn without its window: the renderer has none. */
@Composable
internal fun RemoteElevateQuestion(form: RemoteElevateForm, modifier: Modifier = Modifier) {
    val words = remoteElevateWords(form)
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp, modifier = modifier) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(words.title, style = MaterialTheme.typography.headlineSmall)
            RemoteElevateBody(form, words, {}, {})
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {}) { Text(words.no) }
                RemoteElevateConfirm(form, words) {}
            }
        }
    }
}

/** The question's words, resolved where it is composed. */
@Immutable
internal class RemoteElevateWords(
    val title: String,
    val help: String,
    val howLong: String,
    val lengths: List<Pair<Long, String>>,
    val fullTitle: String,
    val fullHelp: String,
    val fullOn: String,
    /** Said where the device's files are closed: the rights do not include them. */
    val filesClosed: String?,
    val lock: String,
    val failure: String?,
    val yes: String,
    val no: String,
)

@Composable
private fun remoteElevateWords(form: RemoteElevateForm): RemoteElevateWords {
    val lengths = form.lengths.map { it to remoteElevateLengthText(it, form.pairingLeft) }
    // What the guest asked for, marked as such.
    val asked = form.asked?.let { a -> lengths.firstOrNull { it.first == a }?.let { a to stringResource(R.string.remote_elevate_asked_mark, it.second) } }
    return RemoteElevateWords(
        title = if (form.asked != null) stringResource(R.string.remote_elevate_title_asked, form.name)
        else stringResource(R.string.remote_elevate_title_give, form.name),
        help = stringResource(R.string.remote_elevate_help),
        howLong = stringResource(R.string.remote_elevate_for_how_long),
        lengths = lengths.map { if (it.first == asked?.first) asked else it },
        fullTitle = stringResource(R.string.remote_elevate_full_title),
        // Full control without the files is what is left of it: routes and the exit node.
        fullHelp = stringResource(if (form.files) R.string.remote_elevate_full_help else R.string.remote_elevate_full_help_no_files),
        fullOn = stringResource(R.string.remote_elevate_full_on),
        filesClosed = if (form.files) null else stringResource(R.string.remote_elevate_files_closed),
        lock = stringResource(if (form.locked) R.string.remote_elevate_lock else R.string.remote_elevate_no_lock),
        failure = form.failure?.let { remoteElevateFailureText(it, form.name) },
        yes = stringResource(R.string.remote_elevate_confirm),
        no = stringResource(if (form.asked != null) R.string.remote_elevate_decline else R.string.action_cancel),
    )
}

@Composable
private fun RemoteElevateBody(
    form: RemoteElevateForm,
    words: RemoteElevateWords,
    onChoose: (Long) -> Unit,
    onFull: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (form.login.isNotEmpty()) {
            Text(form.login, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // First: how it is confirmed, and where nothing checks who, that it does not. On a
        // television what is below the choices is reached only by scrolling past them.
        LockLine(words.lock, warn = !form.locked)
        // In full: a question may be long, and this is what is being given away.
        Text(words.help, style = MaterialTheme.typography.bodyMedium)
        Column {
            Text(words.howLong, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(4.dp))
            words.lengths.forEach { (seconds, text) ->
                RemoteChoiceRow(text = text, selected = seconds == form.choice, enabled = !form.working) { onChoose(seconds) }
            }
        }
        if (form.fullOn) {
            Text(words.fullOn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FullRow(words.fullTitle, words.fullHelp, form.full, enabled = !form.working, onChange = onFull)
        }
        // The files are not offered at all where they are closed: said plainly, with why.
        words.filesClosed?.let { FilesClosedLine(it) }
        words.failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun RemoteElevateConfirm(form: RemoteElevateForm, words: RemoteElevateWords, onConfirm: () -> Unit) {
    TextButton(onClick = onConfirm, enabled = !form.working) {
        if (form.working) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(words.yes)
    }
}

@Composable
internal fun remoteElevateFailureText(failure: RemoteElevateFailure, name: String): String = when (failure) {
    RemoteElevateFailure.LOCK_UNAVAILABLE -> stringResource(R.string.remote_elevate_fail_lock)
    RemoteElevateFailure.LOCKOUT -> stringResource(R.string.remote_elevate_fail_lockout)
    RemoteElevateFailure.NOT_PROVEN -> stringResource(R.string.remote_elevate_fail_not_proven)
    RemoteElevateFailure.NOT_SAVED -> stringResource(R.string.remote_elevate_fail_not_saved)
    RemoteElevateFailure.OFF -> stringResource(R.string.remote_elevate_fail_off)
    RemoteElevateFailure.FAILED -> stringResource(R.string.remote_elevate_fail, name)
}

/** One choice of several, a length here: the whole row chooses it, and is one stop for a remote. */
@Composable
internal fun RemoteChoiceRow(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .tvFocusRing(source, shape)
            .clip(shape)
            .selectable(selected = selected, enabled = enabled, interactionSource = source, indication = LocalIndication.current, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.tvNoFocus())
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Full control as part of the rights: the row turns it. */
@Composable
private fun FullRow(title: String, help: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocusRing(source, shape)
            .clip(shape)
            .toggleable(value = on, enabled = enabled, interactionSource = source, indication = LocalIndication.current, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = null, enabled = enabled, modifier = Modifier.tvNoFocus())
    }
}

/** The device's files are not part of the rights: a line with a lock, plain and in full. */
@Composable
private fun FilesClosedLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** How it is confirmed: the lock, or — on a device without one — a warning that nothing checks who presses. */
@Composable
private fun LockLine(text: String, warn: Boolean) {
    if (!warn) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
