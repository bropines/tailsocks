package io.github.bropines.tailscaled.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.AccountManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Signing in without a browser on this device. The daemon hands out a login link, which the
 * main screen polls. Opening it here, in a browser tab, is one way in (LoginTab); any other
 * device opening it is the other, and the link as a QR code is how it gets there. A
 * television has only that way and gives it the whole screen (TvSignInScreen); a phone keeps
 * it behind "Sign in on another device" (SignInLinkSheet), with the link to send to whoever
 * owns the tailnet.
 *
 * The code is the link itself, as the daemon gave it, for Tailscale's server and for a
 * server of one's own alike: any camera opens it in a browser.
 */

/** A login link as it is shown: without the scheme, which nobody types. */
internal fun signInLinkText(url: String): String = url.substringAfter("://")

/** The server a login link signs in to, or null for a link that names none. */
internal fun signInServer(url: String?): String? =
    url?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.takeIf { it.isNotEmpty() }

/** What the device is called where the link is opened: the name it registers under. */
private fun signInDeviceName(context: Context): String {
    val account = AccountManager.getActiveAccount(context)
    val prefs = context.getSharedPreferences("appctr_${account.id}", Context.MODE_PRIVATE)
    return prefs.getString("hostname", "").orEmpty().ifBlank { android.os.Build.MODEL.orEmpty() }
}

/**
 * The login link for another device: its code, and Share with a line on what the link is, so
 * that the tailnet's owner can sign in a device that is somewhere else. Follows [url] while
 * it is open: a new link redraws the code.
 */
@Composable
internal fun SignInLinkSheet(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val device = remember(context) { signInDeviceName(context) }
    QrSheet(
        title = stringResource(R.string.signin_other_device),
        text = url,
        help = stringResource(R.string.signin_other_device_help),
        shareText = stringResource(R.string.signin_share_message, device, url),
        onDismiss = onDismiss,
    )
}

/** What asking the daemon for a new login link came to. */
internal sealed interface NewSignInLink {
    /** Asked: the link arrives the way the first one did. */
    data object Requested : NewSignInLink

    /** The daemon is not waiting for a login, so nothing was asked. */
    data object NotWaiting : NewSignInLink

    data class Failed(val reason: String) : NewSignInLink
}

/**
 * Asks the daemon for a new login link, for when the server no longer takes the one on
 * screen. Blocks for some seconds; call it off the main thread.
 *
 * A plain login request would not do: while the daemon holds a link younger than a week it
 * answers with the same one. The bridge's login starts the backend over, which drops that
 * link — and would restart a session as well, so it is asked only of a daemon that has none.
 * The state is read just before the request and can change in between (the phone finishing
 * at that moment): the cost then is one reconnect of a node that keeps its key.
 *
 * Read from the daemon's source (StartLoginInteractiveAs, resetControlClientLocked) and the
 * bridge's Login, and seen on the Android TV emulator against Tailscale's server: the link
 * on screen changed for another. Not tried against a server of one's own.
 */
internal fun requestNewSignInLink(): NewSignInLink {
    val state = runCatching { Appctr.getBackendState() }.getOrDefault("")
    if (state != "NeedsLogin") return NewSignInLink.NotWaiting
    val answer = runCatching { Appctr.login("") }.getOrElse { "Error: ${it.message}" }
    return if (answer.startsWith("Error")) NewSignInLink.Failed(answer.removePrefix("Error:").trim()) else NewSignInLink.Requested
}

/**
 * [TvSignInScreen] with what it takes to wait for a code and to ask for a new one. [url] is
 * the daemon's login link as the main screen polls it; [extraAction] is the screen's.
 */
@Composable
internal fun TvSignIn(
    account: String,
    url: String?,
    onClose: () -> Unit,
    extraAction: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val currentUrl by rememberUpdatedState(url)
    var renewing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A link is seconds away on an open network. Half a minute without one is a server that
    // does not answer; the daemon itself keeps trying for two.
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(url == null) {
        slow = false
        if (url == null) {
            delay(SIGN_IN_SLOW_MS)
            slow = true
        }
    }
    val failed = stringResource(R.string.tv_signin_new_code_failed)
    val notReady = stringResource(R.string.tv_signin_not_ready)
    TvSignInScreen(
        account = account,
        url = url,
        slow = slow,
        renewing = renewing,
        error = error,
        onNewCode = {
            // One request at a time; the button stays a stop for the remote meanwhile.
            if (!renewing) {
                renewing = true
                error = null
                scope.launch {
                    val before = currentUrl
                    when (val asked = withContext(Dispatchers.IO) { requestNewSignInLink() }) {
                        // The poll brings the new link in; the old code stays up until it does.
                        NewSignInLink.Requested ->
                            withTimeoutOrNull(SIGN_IN_NEW_CODE_MS) { snapshotFlow { currentUrl }.first { it != before } }
                        NewSignInLink.NotWaiting -> error = failed.format(notReady)
                        is NewSignInLink.Failed -> error = failed.format(asked.reason)
                    }
                    renewing = false
                }
            }
        },
        onClose = onClose,
        extraAction = extraAction,
    )
}

private const val SIGN_IN_SLOW_MS = 30_000L

/** How long a requested code may take to show before the wait for it is given up. */
private const val SIGN_IN_NEW_CODE_MS = 20_000L

/**
 * Sign-in on a television, the whole screen: the login link as a code for a phone's camera
 * and as text for any other device, and the wait for whoever opens it. The screen does not
 * finish by itself — its owner takes it away when the daemon is signed in.
 *
 * [url] null is the link still on its way, and [slow] that it has been for too long.
 * [renewing]: a new code was asked for and has not come yet. [error] is why the last request
 * for one came to nothing. Back and "Not now" both call [onClose].
 *
 * [extraAction] is the place for one more way in — a button of its own after "New code",
 * such as "Use a key from your phone". Nothing by default.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun TvSignInScreen(
    account: String,
    url: String?,
    slow: Boolean,
    renewing: Boolean,
    error: String?,
    onNewCode: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    extraAction: @Composable () -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val code: @Composable (Modifier) -> Unit = { size ->
        if (url != null) {
            QrCodeImage(text = url, modifier = size)
        } else {
            Box(
                size.background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) { LoadingIndicator(Modifier.size(48.dp)) }
        }
    }
    val words: @Composable ColumnScope.() -> Unit = {
        Text(
            stringResource(R.string.tv_signin_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        // Which account is being signed in, and where: a server of one's own shows here.
        Text(
            listOfNotNull(account, signInServer(url)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.tv_signin_step), style = MaterialTheme.typography.bodyLarge)
        if (url != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.tv_signin_or_link),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                signInLinkText(url),
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(if (url == null || renewing) R.string.tv_signin_preparing else R.string.tv_signin_waiting),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        when {
            error != null -> Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
            url == null && slow -> Text(
                stringResource(R.string.tv_signin_slow),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Where the remote starts: an OK pressed by accident costs nothing here, while a
            // new code would leave the phone holding one the daemon no longer waits for.
            OutlinedButton(onClick = onClose, modifier = Modifier.tvInitialFocus()) {
                Text(stringResource(R.string.tv_signin_later))
            }
            // Stays enabled while a code is on its way, so the remote keeps its place.
            FilledTonalButton(onClick = onNewCode) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tv_signin_new_code))
            }
            extraAction()
        }
        Text(
            stringResource(R.string.tv_signin_new_code_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    // A Surface, for the content colour as much as for its own: nothing above this sets one.
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = TvSignInEdge),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, content = words)
                // As large as the screen's height allows: it is read from across a room.
                code(Modifier.size(minOf(this@BoxWithConstraints.maxHeight - TvSignInEdge * 2, TvSignInCodeMax)))
            }
        } else {
            // The television interface turned on by hand on a narrow screen.
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                code(Modifier.fillMaxWidth().height(this@BoxWithConstraints.maxWidth - 32.dp))
                Column(Modifier.fillMaxWidth(), content = words)
            }
        }
    }
    }
}

/** Above and below the screen's content. */
private val TvSignInEdge = 16.dp

/** The widest the code is drawn: past this the words beside it run out of room. */
private val TvSignInCodeMax = 400.dp
