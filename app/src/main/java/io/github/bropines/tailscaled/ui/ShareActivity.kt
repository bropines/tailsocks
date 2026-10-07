package io.github.bropines.tailscaled.ui
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import appctr.Appctr
import androidx.compose.ui.res.stringResource
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareActivity : ComponentActivity() {
    companion object {
        /** The system file picker first, then the same sheet: the launcher's "Send file" shortcut. */
        const val ACTION_PICK_AND_SEND = "io.github.bropines.tailscaled.action.PICK_AND_SEND"
        private const val STATE_PICKED = "picked"
    }

    /** What the picker returned, kept across a recreation of the sheet. */
    private var picked: ArrayList<Uri>? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) finish() else show(ArrayList(uris))
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_PICK_AND_SEND) {
            val restored = savedInstanceState?.let { BundleCompat.getParcelableArrayList(it, STATE_PICKED, Uri::class.java) }
            when {
                restored != null -> show(restored)
                // A recreation while the picker is open gets its answer through
                // pickFiles; launching again would open a second picker.
                savedInstanceState == null -> pickFiles.launch("*/*")
            }
            return
        }
        val fileUris = when (intent.action) {
            Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { listOf(it) }
            Intent.ACTION_SEND_MULTIPLE -> androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (fileUris.isNullOrEmpty()) { finish(); return }
        setContent { TailSocksTheme { ShareOverlay(fileUris = fileUris, onDismiss = { finish() }) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        picked?.let { outState.putParcelableArrayList(STATE_PICKED, it) }
    }

    private fun show(uris: ArrayList<Uri>) {
        picked = uris
        setContent { TailSocksTheme { ShareOverlay(fileUris = uris, onDismiss = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShareOverlay(fileUris: List<Uri>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberFullSheetState()
    
    var currentAccount by remember { mutableStateOf(AccountManager.getActiveAccount(context)) }
    val accounts = remember { AccountManager.getAccounts(context) }
    var peers by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var isLoadingPeers by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var sendProgressText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    // The daemon's verdict per peer, in the app's language: the sheet's own window follows
    // the system locale, so the words are resolved out here, once.
    val taildropStrings = remember { TaildropReasonStrings.from(context) }

    fun loadPeers() {
        isLoadingPeers = true
        scope.launch(Dispatchers.IO) {
            try {
                val json = Appctr.getStatusFromAPI()
                if (json.isBlank() || json.startsWith("Error")) throw Exception(if (json.isBlank()) "Empty status" else json)
                val status = AppJson.decodeFromString<StatusResponse>(json)
                // Same list and order as the Files hub picker; a peer the daemon refuses is
                // listed disabled with the reason, so what is offered is what will be accepted.
                peers = taildropPickerPeers(status, taildropStrings)
                withContext(Dispatchers.Main) { isLoadingPeers = false; errorMsg = null }
            } catch (e: Exception) { withContext(Dispatchers.Main) { errorMsg = e.message; isLoadingPeers = false } }
        }
    }

    LaunchedEffect(currentAccount) { loadPeers() }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.85f).dp

    // Strings come from the parent context, not stringResource() — see wrapContextWithLocale().
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(context.getString(R.string.share_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(context.getString(R.string.share_files_count_format, fileUris.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { loadPeers() }) {
                    Icon(Icons.Default.Refresh, contentDescription = context.getString(R.string.action_refresh))
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = { accountMenuExpanded = true },
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AccountCircle, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        // Free-text account name: capped, or the chip grows over
                        // the sheet title and past its own caret.
                        Text(
                            currentAccount.name,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (accountMenuExpanded) {
                // The same choice as the main screen's account switcher, so the same
                // shape: a sheet over this one rather than a menu hanging off the chip.
                // The server line tells apart two accounts that share a name. Read once
                // per opening: it comes from each account's preferences, and this
                // sheet recomposes with every line of send progress.
                val accountOptions = remember(accounts) {
                    accounts.map { acc ->
                        PickerOption(
                            value = acc,
                            label = acc.name,
                            icon = Icons.Default.AccountCircle,
                            supporting = AccountManager.facts(context, acc.id).loginServer
                        )
                    }
                }
                PickerSheet(
                    title = context.getString(R.string.accounts_sheet_title),
                    options = accountOptions,
                    selected = accounts.firstOrNull { it.id == currentAccount.id },
                    onPick = { acc ->
                        if (acc.id != currentAccount.id) {
                            AccountManager.setActiveAccount(context, acc.id)
                            currentAccount = acc
                            context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                        }
                    },
                    onDismiss = { accountMenuExpanded = false }
                )
            }

            if (isLoadingPeers) Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) { LoadingIndicator() }
            else if (errorMsg != null) Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(errorMsg!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center); Button(onClick = { loadPeers() }) { Text(context.getString(R.string.action_retry)) }
            } else Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(peers) { p -> 
                            PeerShareItem(p, !isSending, taildropStatusOf(p, taildropStrings)) {
                                isSending = true
                                scope.launch(Dispatchers.IO) {
                                    val failures = sendFilesWithProgress(context, fileUris, p) { sendProgressText = it }
                                    withContext(Dispatchers.Main) {
                                        isSending = false
                                        // The sheet closes right here, so the progress card
                                        // is gone before anyone reads it: a toast outlives it.
                                        if (failures.isNotEmpty()) {
                                            Toast.makeText(context, failures.joinToString("\n"), Toast.LENGTH_LONG).show()
                                        }
                                        onDismiss()
                                    }
                                }
                            } 
                        }
                    }
                }
            }
        }
    }

    if (isSending) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.5f)), contentAlignment = Alignment.Center) {
        Card(shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LoadingIndicator(); Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.share_sending), fontWeight = FontWeight.Bold)
                Text(sendProgressText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Sends every file in turn and returns one line per file that did not arrive, already in
 * the user's words, for the caller to show once the sheet is gone. Each attempt lands in
 * the Taildrop history (sendTaildropFile).
 */
private fun sendFilesWithProgress(context: Context, uris: List<Uri>, peer: PeerData, onProgress: (String) -> Unit): List<String> =
    sendTaildropFiles(context, uris, peer, TaildropSource.SHARE, onProgress)
        .mapNotNull { o -> o.error?.let { context.getString(R.string.share_failed_format, o.name, it) } }
