package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R

/*
 * The managing page (ui/RemoteManagePage.kt) and the managed device's files: its storage, its
 * shared folders, its log and its report. Two things stand in front of them. The device's own
 * switch, network access to files — while it is off there, the page says so where the files
 * would be, and where it is switched on. And this device's own lock (RemoteFilesLock), asked
 * before any of them is shown here.
 */

/** Asks this device's lock before [then], where the page has one to ask; a preview has none. */
internal fun RemoteManageHooks.filesGate(place: FilesPlace, then: () -> Unit) {
    val lock = files
    if (lock == null) then() else lock.run(place, then)
}

/** The files are open on this page: confirmed with this device's lock a moment ago, or there is no lock to ask. */
internal val RemoteManageHooks.filesShown: Boolean get() = files?.open() ?: true

/**
 * "Files are closed on <device>", where the files would be, and the one line that says where
 * they are opened: the device's own remote-management page — with Full control as well, where
 * what was asked for takes it and it is off there too. Full rights for a while that were given
 * while the files were closed never include them, whatever is switched there later: that is
 * said instead.
 */
@Composable
internal fun FilesClosed(ui: RemoteManageUi.Managing, needsFull: Boolean = false) {
    val hint = when {
        ui.elevated?.noFiles == true -> R.string.rmanage_files_closed_rights
        needsFull -> R.string.rmanage_files_closed_hint_full
        else -> R.string.rmanage_files_closed_hint
    }
    Column {
        ManageLine(stringResource(R.string.rmanage_files_closed, ui.name), icon = Icons.Default.Lock)
        Text(
            stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)
        )
    }
}

/** What this device's lock had to say at [place]: that it has none, said once, or why nothing was opened. */
@Composable
internal fun FilesLockLine(hooks: RemoteManageHooks, place: FilesPlace, name: String) {
    val (at, outcome) = hooks.files?.said ?: return
    if (at != place) return
    val text = when (outcome) {
        RemoteUnlockOutcome.NO_LOCK -> stringResource(R.string.rmanage_files_no_lock, name)
        RemoteUnlockOutcome.LOCKOUT -> stringResource(R.string.rmanage_files_unlock_lockout)
        RemoteUnlockOutcome.FAILED -> stringResource(R.string.rmanage_files_unlock_failed)
        RemoteUnlockOutcome.UNLOCKED, RemoteUnlockOutcome.CANCELLED -> return
    }
    ManageLine(text, Modifier.padding(top = 4.dp), tone = ManageTone.WARN)
}

/**
 * The shared folders, before this device's lock: their number is on the group's line already;
 * which they are, and where, waits for a press and the lock.
 */
@Composable
internal fun SharesBehindLock(ui: RemoteManageUi.Managing, hooks: RemoteManageHooks) {
    ManageOpenRow(
        icon = Icons.Default.LockOpen,
        title = stringResource(R.string.rmanage_shares_show),
        help = stringResource(R.string.rmanage_files_lock_help),
        enabled = ui.status != null,
        onClick = { hooks.filesGate(FilesPlace.SHARES) {} },
    )
}
