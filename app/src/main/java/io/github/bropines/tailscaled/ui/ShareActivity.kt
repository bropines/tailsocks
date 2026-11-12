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
import androidx.core.content.pm.ShortcutManagerCompat
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
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareActivity : ComponentActivity() {
    companion object {
        /** A file picker first, then the same sheet: the launcher's "Send file" shortcut. */
        const val ACTION_PICK_AND_SEND = "io.github.bropines.tailscaled.action.PICK_AND_SEND"
        private const val STATE_PICKED = "picked"
        private const val STATE_BROWSING = "browsing"
    }

    /** What the picker returned, kept across a recreation of the sheet. */
    private var picked: ArrayList<Uri>? = null

    /** Whether the app's own browser is up in the system picker's place. */
    private var browsing = false

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
                savedInstanceState?.getBoolean(STATE_BROWSING) == true -> browse()
                // A recreation while the picker is open gets its answer through
                // pickFiles; launching again would open a second picker.
                savedInstanceState == null -> pick()
            }
            return
        }
        val fileUris = when (intent.action) {
            Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { listOf(it) }
            Intent.ACTION_SEND_MULTIPLE -> androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (fileUris.isNullOrEmpty()) { finish(); return }
        // These URIs are another app's choice and are read with this app's rights: one that names
        // this app's own data is not sent (core/ShareGuard.kt). The files picked above are the user's.
        val own = ShareGuard.ownPlaces(this)
        val allowed = fileUris.filter { ShareGuard.allows(it, own) }
        if (allowed.size < fileUris.size) Toast.makeText(this, R.string.share_guard_refused, Toast.LENGTH_LONG).show()
        if (allowed.isEmpty()) { finish(); return }
        setContent { TailSocksTheme { ShareOverlay(fileUris = allowed, directTo = directTo, onDismiss = { finish() }) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        picked?.let { outState.putParcelableArrayList(STATE_PICKED, it) }
        outState.putBoolean(STATE_BROWSING, browsing)
    }

    /** The system's picker, or the app's own browser on a device that has none (core/TaildropPick.kt). */
    private fun pick() {
        val offered = DeviceProfile.availability(this, Feature.SYSTEM_FILE_PICKER) == Availability.OFFERED
        if (choosePicker(offered) { pickFiles.tryLaunch("*/*") } == PickerKind.IN_APP) browse()
    }

    private fun browse() {
        browsing = true
        setContent {
            TailSocksTheme {
                // This activity's window is see-through, which the theme leaves alone: the
                // page brings its own background and a television's safe area.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(if (isTv) Modifier.padding(LocalTvSafeArea.current) else Modifier) {
                        PickFilesScreen(
                            source = remember { RealFileSource(this@ShareActivity) },
                            onPicked = { files -> show(ArrayList(files.map(Uri::fromFile))) },
                            onCancel = { finish() },
                            title = stringResource(R.string.taildrop_pick_files_title)
                        )
                    }
                }
            }
        }
    }

    private fun show(uris: ArrayList<Uri>) {
        picked = uris
        browsing = false
        setContent { TailSocksTheme { ShareOverlay(fileUris = uris, directTo = directTo, onDismiss = { finish() }) } }
    }

    /** The device the system share sheet's target names (core/ShareTargets.kt), if it was one. */
    private val directTo: String? get() = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
}

/**
 * [directTo] is a Direct Share target's shortcut id: the files go straight to that device
 * once the peers are in, when it belongs to the active account and the daemon takes a file
 * for it now. Otherwise — another account's device, gone, offline, no daemon — the list is
 * shown as for any share.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShareOverlay(fileUris: List<Uri>, onDismiss: () -> Unit, directTo: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberFullSheetState()
    
    var currentAccount by remember { mutableStateOf(AccountManager.getActiveAccount(context)) }
    val accounts = remember { AccountManager.getAccounts(context) }
    var peers by remember { mutableStateOf<List<PeerData>>(emptyList()) }
    var isLoadingPeers by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    // The daemon's verdict per peer, in the app's language: the sheet's own window follows
    // the system locale, so the words are resolved out here, once.
    val taildropStrings = remember { TaildropReasonStrings.from(context) }
    // Saveable: a recreation must not send the files a second time.
    var directTried by rememberSaveable { mutableStateOf(directTo == null) }
    var handedOver by rememberSaveable { mutableStateOf(false) }
    // A Direct Share target was already a pick: nothing is drawn while its device is looked
    // up, and the list opens only when that device cannot take the files.
    val showList = directTried && !handedOver

    // The files go in the background (TaildropOutbox) — a notification and the Files page
    // carry the progress — and this window gives the screen back to the app that shared them.
    fun send(p: PeerData) {
        if (handedOver) return
        handedOver = true
        TaildropOutbox.send(context, fileUris, p, TaildropSource.SHARE)
        Toast.makeText(context, context.getString(R.string.taildrop_out_started_format, p.getDisplayName()), Toast.LENGTH_SHORT).show()
        onDismiss()
    }

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
                withContext(Dispatchers.Main) {
                    isLoadingPeers = false; errorMsg = null
                    if (!directTried) {
                        directTried = true
                        val peerId = ShareTargets.peerIdFor(directTo, currentAccount.id)
                        peers.firstOrNull { it.id == peerId && taildropStatusOf(it, taildropStrings) == TaildropStatus.Available }
                            ?.let { send(it); ShortcutManagerCompat.reportShortcutUsed(context, directTo!!) }
                    }
                }
            } catch (e: Exception) {
                // No daemon, no direct send: a retry from the list is the user's own pick.
                withContext(Dispatchers.Main) { errorMsg = e.message; isLoadingPeers = false; directTried = true }
            }
        }
    }

    LaunchedEffect(currentAccount) { loadPeers() }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.85f).dp

    // Strings come from the parent context, not stringResource() — see wrapContextWithLocale().
    if (showList) AppModalSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                            PeerShareItem(p, true, taildropStatusOf(p, taildropStrings)) { send(p) }
                        }
                    }
                }
            }
        }
    }
}
