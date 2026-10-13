package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.tryStartActivity

/*
 * A link on a device with nowhere to open it. A row of the credits, the licences or the issue
 * report opens a page in the browser; a television has none as often as not, and OK on such a
 * row did nothing. Where no browser answers, the row shows its address instead — large, and as a
 * QR code for the phone in the viewer's hand, the way a peer's address is shown (PeerShow.kt).
 */

/** An address to read instead of open: [title] says whose it is. */
internal class ShownLink(val title: String, val url: String)

/** What a row that links to a web page does: [open] it, or [show] where it is. */
internal class LinkOpener(
    private val context: Context,
    private val browser: Boolean,
    private val onShow: (ShownLink) -> Unit,
) {
    /**
     * Opens [url] in a browser where one answers, and shows it, named [title], where none does.
     * True when it was opened, which is when the screen it was opened from may be left.
     */
    fun open(url: String, title: String): Boolean {
        if (browser && context.tryStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) return true
        show(url, title)
        return false
    }

    /** The address, whether or not a browser could open it. */
    fun show(url: String, title: String) = onShow(ShownLink(title, url))
}

/**
 * A [LinkOpener] and the dialog it shows an address in. Called where the strings of a window of
 * its own are resolved — the parent composition, not a dialog's slot (see wrapContextWithLocale).
 * The browser is asked of the system, so a television with one installed opens links as a phone
 * does; a launch that finds no handler shows the address too.
 */
@Composable
internal fun rememberLinkOpener(): LinkOpener {
    val context = LocalContext.current
    val browser = Feature.BROWSER.offered()
    val strings = rememberShownValueStrings()
    val label = stringResource(R.string.link_address)
    var shown by remember { mutableStateOf<ShownLink?>(null) }
    shown?.let { link ->
        ValueDialog(
            title = link.title,
            values = listOf(ShownValue(label, link.url, qr = true)),
            strings = strings,
            onDismiss = { shown = null },
        )
    }
    return remember(context, browser) { LinkOpener(context, browser) { shown = it } }
}
