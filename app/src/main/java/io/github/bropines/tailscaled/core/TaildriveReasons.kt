package io.github.bropines.tailscaled.core

import androidx.annotation.StringRes
import io.github.bropines.tailscaled.R

/**
 * The line a document picker shows when a Taildrive folder cannot be listed: one sentence in the
 * short form its list allows, chosen by what [DriveError] says went wrong. The picker is another
 * app's screen, so nothing here is an exception's text — that could name an address — and an error
 * the app has no words for becomes "unavailable", with the cause in the log.
 *
 * Pure, so the mapping has a test; the provider resolves the string in the app's language.
 */
@StringRes
fun documentReasonRes(error: DriveError): Int = when (error) {
    DriveError.NotConnected -> R.string.taildrive_unavailable_not_connected
    DriveError.NotPermitted -> R.string.taildrive_unavailable_not_permitted
    DriveError.NoAnswer -> R.string.taildrive_unavailable_no_answer
    DriveError.Unreachable -> R.string.taildrive_unavailable_no_fileserver
    DriveError.AccessDenied -> R.string.taildrive_unavailable_forbidden
    DriveError.NotFound -> R.string.taildrive_unavailable_not_found
    DriveError.Timeout -> R.string.taildrive_unavailable_timeout
    // The proxy is the share browser's business, not the picker's.
    DriveError.ProxyOff, DriveError.ProxyNotListening, is DriveError.Failed -> R.string.taildrive_unavailable_failed
}
