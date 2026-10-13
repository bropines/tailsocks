package io.github.bropines.tailscaled.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import appctr.Appctr
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.console.ConsoleText
import io.github.bropines.tailscaled.admin.secure.AdminWriteGate
import io.github.bropines.tailscaled.admin.secure.LockState
import io.github.bropines.tailscaled.admin.secure.UnlockResult
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.TailnetLock
import io.github.bropines.tailscaled.core.TailnetLockPeer
import io.github.bropines.tailscaled.core.TailnetLockStatus
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tailnet Lock as this device sees it, from Settings → Account & connection. Read-only but for
 * one write: on a device whose key is trusted, signing a node that is waiting — behind a dialog
 * that names the node and then the phone's unlock (AdminWriteGate, the console's write gate).
 * A FragmentActivity because BiometricPrompt wants one.
 *
 * A device that is not offered signing (Feature.TAILNET_LOCK_SIGN — a television, which has no
 * screen lock to confirm with) shows the same status with no Sign buttons and says why the
 * nodes are signed from elsewhere. One without a clipboard to speak of (Feature.CLIPBOARD_ACTIONS)
 * shows a value as a QR code for a phone to read, where another copies it.
 */
class TailnetLockActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TailSocksTheme { TailnetLockScreen(activity = this, onBack = { finish() }) } }
    }
}

/** What the screen has to show. */
sealed interface TailnetLockLoad {
    data object Loading : TailnetLockLoad
    data object Offline : TailnetLockLoad
    data class Failed(val message: String) : TailnetLockLoad
    data class Loaded(val status: TailnetLockStatus) : TailnetLockLoad
}

@Composable
fun TailnetLockScreen(activity: FragmentActivity?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inPreview = LocalInspectionMode.current
    var load by remember { mutableStateOf<TailnetLockLoad>(TailnetLockLoad.Loading) }
    var tick by remember { mutableIntStateOf(0) }
    var signing by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<TailnetLockPeer?>(null) }
    val canUnlock = remember { !inPreview && AdminWriteGate.lockState(context) != LockState.NO_SCREEN_LOCK }
    val copies = Feature.CLIPBOARD_ACTIONS.offered()
    // A value on show as a QR code: its title and the text.
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(tick) {
        if (inPreview) return@LaunchedEffect
        load = withContext(Dispatchers.IO) {
            if (!Appctr.isRunning()) return@withContext TailnetLockLoad.Offline
            val body = Appctr.doLocalAPIRequest("GET", TailnetLock.STATUS_PATH, "")
            TailnetLock.parse(body)?.let { TailnetLockLoad.Loaded(it) }
                ?: TailnetLockLoad.Failed(body.removePrefix("Error: ").trim().ifEmpty { "?" })
        }
    }

    fun sign(peer: TailnetLockPeer) {
        val host = activity ?: return
        scope.launch {
            val unlock = AdminWriteGate.unlock(
                host,
                context.getString(R.string.tka_sign_unlock_title, peer.shortName),
                context.getString(R.string.tka_title),
            )
            when (unlock) {
                is UnlockResult.Granted -> if (!unlock.grant.consume()) return@launch
                UnlockResult.Cancelled -> return@launch
                is UnlockResult.Failed -> {
                    Toast.makeText(context, ConsoleText.unlockFailure(context, unlock.reason), Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            signing = peer.nodeKey
            val answer = withContext(Dispatchers.IO) {
                Appctr.doLocalAPIRequest("POST", TailnetLock.SIGN_PATH, TailnetLock.signRequest(peer.nodeKey))
            }
            signing = null
            val text = if (answer.startsWith("Error")) {
                context.getString(R.string.tka_sign_failed, answer.removePrefix("Error: ").trim())
            } else {
                context.getString(R.string.tka_signed, peer.shortName)
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            tick++
        }
    }

    TailnetLockContent(
        load = load,
        canUnlock = canUnlock,
        signing = signing,
        onBack = onBack,
        onRefresh = { tick++ },
        onCopy = { title, text ->
            if (copies) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.tka_title), text))
                Toast.makeText(context, context.getString(R.string.tka_copied), Toast.LENGTH_SHORT).show()
            } else {
                shown = title to text
            }
        },
        onSign = { confirm = it },
        signsHere = Feature.TAILNET_LOCK_SIGN.offered(),
        copies = copies,
    )

    shown?.let { (title, text) -> QrSheet(title = title, text = text, onDismiss = { shown = null }) }

    confirm?.let { peer ->
        // Resolved here, in the activity's locale: a dialog's window does not carry it.
        val title = stringResource(R.string.tka_sign_dialog_title, peer.shortName)
        val body = stringResource(
            R.string.tka_sign_dialog_body,
            peer.name.trimEnd('.'),
            peer.tailscaleIps.orEmpty().joinToString(", "),
            TailnetLock.shortKey(peer.nodeKey),
        )
        val strSign = stringResource(R.string.tka_sign)
        val strCancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Default.Verified, contentDescription = null) },
            title = { Text(title) },
            text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { Button(onClick = { confirm = null; sign(peer) }) { Text(strSign) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(strCancel) } },
        )
    }
}

/** The screen without its loading and its writes, so a preview can hand it any state. */
@Composable
internal fun TailnetLockContent(
    load: TailnetLockLoad,
    canUnlock: Boolean,
    signing: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    /** A row's title and its value: copied, or shown as a code where the device does not copy. */
    onCopy: (String, String) -> Unit,
    onSign: (TailnetLockPeer) -> Unit,
    /** Whether this device signs nodes at all (Feature.TAILNET_LOCK_SIGN). */
    signsHere: Boolean = true,
    /** Whether a value is copied; false shows it as a QR code instead (Feature.CLIPBOARD_ACTIONS). */
    copies: Boolean = true,
) {
    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.tka_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
                },
            )
        },
    ) { padding ->
        ReadableWidth {
            LazyColumn(
                state = listState,
                // The state card and the waiting nodes are no stops for a remote: it scrolls to them.
                modifier = Modifier.padding(padding).fillMaxSize().tvScrollPastStops(listState),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "help") {
                    HelpText(stringResource(R.string.tka_screen_help), modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                }
                when (load) {
                    TailnetLockLoad.Loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    TailnetLockLoad.Offline -> item(key = "offline") {
                        StateLine(Icons.Default.CloudOff, stringResource(R.string.tka_offline))
                    }
                    is TailnetLockLoad.Failed -> item(key = "failed") {
                        StateLine(Icons.Default.CloudOff, stringResource(R.string.tka_load_failed, load.message), error = true)
                    }
                    is TailnetLockLoad.Loaded -> {
                        val st = load.status
                        item(key = "state") { LockStateCard(st) }
                        item(key = "device") { DeviceCard(st, copies, onCopy) }
                        // Nodes waiting matter to a signer; anyone else sees them only if there are any.
                        if (st.enabled && (st.isTrustedSigner || st.lockedOutPeers.isNotEmpty())) {
                            item(key = "waiting-head") { WaitingHeader(st, canUnlock, signsHere) }
                            items(st.lockedOutPeers, key = { "peer-" + it.nodeKey }) { peer ->
                                WaitingPeerRow(
                                    peer = peer,
                                    signsHere = signsHere,
                                    canSign = st.isTrustedSigner && canUnlock && signing == null,
                                    signing = signing == peer.nodeKey,
                                    onSign = { onSign(peer) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StateLine(icon: ImageVector, text: String, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun LockStateCard(st: TailnetLockStatus) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (st.enabled) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (st.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        stringResource(if (st.enabled) R.string.tka_state_on else R.string.tka_state_off),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val keys = st.trustedKeys.orEmpty().size
                    if (st.enabled && keys > 0) {
                        Text(
                            pluralStringResource(R.plurals.tka_trusted_keys, keys, keys),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!st.enabled) {
                Spacer(Modifier.size(8.dp))
                HelpText(stringResource(if (isTv) R.string.tka_state_off_help_tv else R.string.tka_state_off_help))
            }
        }
    }
}

@Composable
private fun DeviceCard(st: TailnetLockStatus, copies: Boolean, onCopy: (String, String) -> Unit) {
    SettingsCard(title = stringResource(R.string.tka_sect_device)) {
        when {
            !st.hasKey -> StateLine(Icons.Default.HourglassEmpty, stringResource(R.string.tka_device_no_key))
            !st.enabled -> Unit
            st.isTrustedSigner -> StateLine(Icons.Default.Verified, stringResource(R.string.tka_device_signer))
            st.isLockedOut -> StateLine(Icons.Default.Block, stringResource(R.string.tka_device_locked_out), error = true)
            else -> StateLine(Icons.Default.CheckCircle, stringResource(R.string.tka_device_signed))
        }
        if (st.hasKey) {
            val key = TailnetLock.cliKey(st.publicKey)
            val title = stringResource(R.string.tka_key_title)
            if (copies) CopyRow(Icons.Default.Key, title, key, stringResource(R.string.tka_key_help)) { onCopy(title, key) }
            else ShowRow(Icons.Default.Key, title, key, stringResource(R.string.tka_key_help_show), Modifier.tvInitialFocus()) { onCopy(title, key) }
        }
        if (st.isLockedOut) {
            TailnetLock.signCommand(st)?.let { cmd ->
                val title = stringResource(R.string.tka_sign_me_title)
                if (copies) CopyRow(Icons.Default.Terminal, title, cmd, stringResource(R.string.tka_sign_me_help)) { onCopy(title, cmd) }
                else ShowRow(Icons.Default.Terminal, title, cmd, stringResource(R.string.tka_sign_me_help_show)) { onCopy(title, cmd) }
            }
        }
    }
}

/** A value to copy: monospace, the whole row the button, an explanation folded under it. */
@Composable
private fun CopyRow(icon: ImageVector, title: String, value: String, help: String, onCopy: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.clickable(onClick = onCopy).padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                HelpText(help)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * [CopyRow] on a device that does not copy: the same value, and the row shows it as a QR code
 * for a phone to read. One stop for a remote, its explanation unfolding as the remote rests on it.
 */
@Composable
private fun ShowRow(icon: ImageVector, title: String, value: String, help: String, modifier: Modifier = Modifier, onShow: () -> Unit) {
    val folded = remember(help) { mutableStateOf(false) }
    ExplainedRow(onClick = onShow, help = folded, modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                HelpText(help, expanded = folded, inClickableRow = true)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.QrCode2, contentDescription = stringResource(R.string.qr_show), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun WaitingHeader(st: TailnetLockStatus, canUnlock: Boolean, signsHere: Boolean) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(
            stringResource(R.string.tka_sect_waiting),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        val note = when {
            st.lockedOutPeers.isEmpty() -> R.string.tka_waiting_none
            !st.isTrustedSigner -> R.string.tka_waiting_not_signer
            !signsHere -> R.string.tka_waiting_other_device
            !canUnlock -> R.string.tka_waiting_no_lock
            else -> null
        }
        if (note != null) Text(stringResource(note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WaitingPeerRow(peer: TailnetLockPeer, signsHere: Boolean, canSign: Boolean, signing: Boolean, onSign: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(peer.shortName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val ips = peer.tailscaleIps.orEmpty().joinToString(", ")
                if (ips.isNotEmpty()) {
                    Text(ips, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    TailnetLock.shortKey(peer.nodeKey),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Where the device does not sign there is no button to press, and no dead one either.
            if (signsHere) {
                Spacer(Modifier.width(12.dp))
                if (signing) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    FilledTonalButton(onClick = onSign, enabled = canSign) { Text(stringResource(R.string.tka_sign)) }
                }
            }
        }
    }
}
