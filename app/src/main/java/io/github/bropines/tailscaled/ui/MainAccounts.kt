package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The main screen's accounts: the account in use, the list, and what opens over the screen to
 * change them — the accounts sheet (the top bar's title, the Accounts tile, or Settings when
 * that tile is off the screen), "Add account", and the rename, delete and switch
 * confirmations. Used by the main screen (ui/MainActivity.kt); the rows are in
 * MainAccountRows.kt.
 */

/**
 * The accounts and the flags of what is open over the screen. The flags that hold nothing but
 * a flag or a draft that is not a secret are saveable, like the screen's other dialogs and
 * sheets: neither activity handles configuration changes, so a rotation recreates the screen
 * and used to close whatever was open, with what had been typed in it.
 */
@Stable
internal class MainAccountsState(
    activeAccount: MutableState<TailscaleAccount>,
    /** The profiles, replaced whole (never edited in place) after every change to them. */
    val accounts: MutableState<List<TailscaleAccount>>,
    showAddAccountDialog: MutableState<Boolean>,
    showRenameAccountDialog: MutableState<Boolean>,
    showSwitchConfirmDialog: MutableState<TailscaleAccount?>,
    accountToDeleteConfirm: MutableState<TailscaleAccount?>,
    accountToRename: MutableState<TailscaleAccount?>,
    accountsEditMode: MutableState<Boolean>,
    newAccountName: MutableState<String>,
    newAccountHonest: MutableState<Boolean>,
    accountMenuExpanded: MutableState<Boolean>,
) {
    var activeAccount by activeAccount
    var showAddAccountDialog by showAddAccountDialog
    var showRenameAccountDialog by showRenameAccountDialog
    var showSwitchConfirmDialog by showSwitchConfirmDialog
    var accountToDeleteConfirm by accountToDeleteConfirm
    var accountToRename by accountToRename
    var accountsEditMode by accountsEditMode
    var newAccountName by newAccountName
    // "Report the real OS" for the profile about to be created: decided here,
    // before its first login, because the coordinator binds a node to the OS
    // it registered with (ProfileHostinfo).
    // A new profile reports the real OS unless the box is cleared (ProfileHostinfo).
    var newAccountHonest by newAccountHonest
    /** The accounts sheet is open. */
    var accountMenuExpanded by accountMenuExpanded
}

@Composable
internal fun rememberMainAccounts(context: Context): MainAccountsState {
    val activeAccount = remember { mutableStateOf(AccountManager.getActiveAccount(context)) }
    val accounts = remember { mutableStateOf(AccountManager.getAccounts(context)) }
    val showAddAccountDialog = rememberSaveable { mutableStateOf(false) }
    val showRenameAccountDialog = remember { mutableStateOf(false) }
    val showSwitchConfirmDialog = remember { mutableStateOf<TailscaleAccount?>(null) }
    val accountToDeleteConfirm = remember { mutableStateOf<TailscaleAccount?>(null) }
    val accountToRename = remember { mutableStateOf<TailscaleAccount?>(null) }
    val accountsEditMode = remember { mutableStateOf(false) }
    val newAccountName = rememberSaveable { mutableStateOf("") }
    val newAccountHonest = rememberSaveable { mutableStateOf(true) }
    val accountMenuExpanded = rememberSaveable { mutableStateOf(false) }
    return remember {
        MainAccountsState(
            activeAccount, accounts, showAddAccountDialog, showRenameAccountDialog, showSwitchConfirmDialog,
            accountToDeleteConfirm, accountToRename, accountsEditMode, newAccountName, newAccountHonest, accountMenuExpanded,
        )
    }
}

/**
 * Everything of the accounts' that opens over the main screen, each while its flag is set, in
 * the order they always stood in. [proxyState] is the screen's (MainDaemonState): the sheet
 * reads the active node's line only while it is connected. [onSwitching] is told when a
 * running service is restarted into another account, before the restart is sent.
 */
@Composable
internal fun MainAccountsOverlays(state: MainAccountsState, proxyState: String, scope: CoroutineScope, onSwitching: () -> Unit) {
    if (state.showAddAccountDialog) MainAccountAddDialog(state)
    if (state.showRenameAccountDialog) MainAccountRenameDialog(state)
    if (state.accountToDeleteConfirm != null) MainAccountDeleteDialog(state, scope)
    if (state.showSwitchConfirmDialog != null) MainAccountSwitchDialog(state, onSwitching)
    if (state.accountMenuExpanded) MainAccountsMenuSheet(state, proxyState)
}

@Composable
private fun MainAccountAddDialog(state: MainAccountsState): Unit = with(state) {
    val context = LocalContext.current
    var newAccountServer by rememberSaveable { mutableStateOf("") }
    // A secret: never in the saved state, which the system writes out with the task.
    var newAccountAuthKey by remember { mutableStateOf("") }
    // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
    val dlgTitle = stringResource(R.string.main_add_account_title)
    val dlgNameLabel = stringResource(R.string.main_account_name_label)
    val dlgServerLabel = stringResource(R.string.settings_login_server_title)
    val dlgServerPlaceholder = stringResource(R.string.settings_login_server_placeholder)
    val dlgAuthKeyLabel = stringResource(R.string.main_add_account_authkey_label)
    val dlgAuthKeyPlaceholder = stringResource(R.string.main_add_account_authkey_placeholder)
    // Without a key a television signs in by a code for a phone, not in a browser.
    val dlgAuthKeyHint = stringResource(
        if (Feature.BROWSER_SIGN_IN.offered()) R.string.main_add_account_authkey_hint else R.string.tv_add_account_authkey_hint
    )
    val dlgAdd = stringResource(R.string.action_add)
    val dlgCancel = stringResource(R.string.action_cancel)
    val dlgHonestTitle = stringResource(R.string.settings_honest_hostinfo_title)
    val dlgHonestDesc = stringResource(R.string.honest_hostinfo_checkbox_desc)
    AppAlertDialog(
        onDismissRequest = { showAddAccountDialog = false; newAccountHonest = true },
        title = { Text(dlgTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // PageTextField: three fields in a row are a page of them, which a remote
                // has to cross on its way to the buttons.
                PageTextField(
                    value = newAccountName,
                    onValueChange = { newAccountName = it },
                    label = dlgNameLabel,
                    modifier = Modifier.fillMaxWidth()
                )
                PageTextField(
                    value = newAccountServer,
                    onValueChange = { newAccountServer = it },
                    label = dlgServerLabel,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = dlgServerPlaceholder
                )
                // Optional: with a key the profile registers on its first start,
                // without one it goes through the browser like before.
                PageTextField(
                    value = newAccountAuthKey,
                    onValueChange = { newAccountAuthKey = it },
                    label = dlgAuthKeyLabel,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = dlgAuthKeyPlaceholder
                )
                Text(
                    dlgAuthKeyHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { newAccountHonest = !newAccountHonest },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // The row is the remote's stop; the box inside it would be a second one.
                    Checkbox(checked = newAccountHonest, onCheckedChange = { newAccountHonest = it }, modifier = Modifier.tvNoFocus())
                    Column(modifier = Modifier.weight(1f)) {
                        Text(dlgHonestTitle, style = MaterialTheme.typography.bodyMedium)
                        Text(dlgHonestDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (newAccountName.isNotBlank()) {
                    val acc = AccountManager.addAccount(context, newAccountName)
                    val accPrefs = context.getSharedPreferences("appctr_${acc.id}", Context.MODE_PRIVATE)
                    accPrefs.edit().putBoolean("do_reset", true).apply()
                    // Written before the first start, so the node registers with it.
                    ProfileHostinfo.setHonest(context, acc.id, newAccountHonest)
                    newAccountHonest = true
                    if (newAccountServer.isNotBlank()) {
                        accPrefs.edit().putString("login_server", newAccountServer.trim()).apply()
                    }
                    // Written before the first start, like the hostinfo choice: the
                    // service reads it out of the profile when it builds StartOptions.
                    if (newAccountAuthKey.isNotBlank()) {
                        accPrefs.edit().putString("authkey", newAccountAuthKey.trim()).apply()
                    }
                    accounts.value = AccountManager.getAccounts(context)
                    AccountManager.setActiveAccount(context, acc.id)
                    activeAccount = acc
                    newAccountName = ""
                    showAddAccountDialog = false

                    if (ProxyState.isActualRunning()) {
                        context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
                    }
                }
            }) { Text(dlgAdd) }
        },
        dismissButton = { TextButton(onClick = { showAddAccountDialog = false; newAccountHonest = true }) { Text(dlgCancel) } }
    )
}

@Composable
private fun MainAccountRenameDialog(state: MainAccountsState): Unit = with(state) {
    val context = LocalContext.current
    val targetAcc = accountToRename ?: activeAccount
    var renameText by remember(targetAcc.id) { mutableStateOf(targetAcc.name) }
    // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
    val dlgTitle = stringResource(R.string.main_rename_account_title)
    val dlgNameLabel = stringResource(R.string.main_new_name_label)
    val dlgRename = stringResource(R.string.action_rename)
    val dlgCancel = stringResource(R.string.action_cancel)
    AppAlertDialog(
        onDismissRequest = { 
            showRenameAccountDialog = false
            accountToRename = null
        },
        title = { Text(dlgTitle) },
        text = {
            OutlinedTextField(
                value = renameText,
                onValueChange = { renameText = it },
                label = { Text(dlgNameLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = {
                if (renameText.isNotBlank()) {
                    AccountManager.renameAccount(context, targetAcc.id, renameText)
                    accounts.value = AccountManager.getAccounts(context)
                    activeAccount = AccountManager.getActiveAccount(context)
                    showRenameAccountDialog = false
                    accountToRename = null
                }
            }) { Text(dlgRename) }
        },
        dismissButton = { 
            TextButton(onClick = { 
                showRenameAccountDialog = false
                accountToRename = null
            }) { Text(dlgCancel) } 
        }
    )
}

@Composable
private fun MainAccountDeleteDialog(state: MainAccountsState, scope: CoroutineScope): Unit = with(state) {
    val context = LocalContext.current
    val targetAcc = accountToDeleteConfirm!!
    // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
    val dlgTitle = stringResource(R.string.main_delete_account_confirm_title, targetAcc.name)
    val dlgText = stringResource(R.string.main_delete_account_confirm_text)
    val dlgDelete = stringResource(R.string.action_delete)
    val dlgCancel = stringResource(R.string.action_cancel)
    AppAlertDialog(
        onDismissRequest = { accountToDeleteConfirm = null },
        icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(dlgTitle) },
        text = { Text(dlgText) },
        confirmButton = {
            Button(
                onClick = {
                    accountToDeleteConfirm = null
                    accountMenuExpanded = false
                    // Deleting the active, running account would pull its
                    // state dir out from under the live daemon; stop first.
                    val wasActiveRunning = targetAcc.id == activeAccount.id && ProxyState.isActualRunning(context)
                    if (wasActiveRunning) {
                        context.startService(Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" })
                    }
                    scope.launch(Dispatchers.IO) {
                        if (wasActiveRunning) { try { Thread.sleep(800) } catch (_: Exception) {} }
                        AccountManager.deleteAccount(context, targetAcc.id)
                        withContext(Dispatchers.Main) {
                            activeAccount = AccountManager.getActiveAccount(context)
                            accounts.value = AccountManager.getAccounts(context)
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(dlgDelete)
            }
        },
        dismissButton = {
            TextButton(onClick = { accountToDeleteConfirm = null }) {
                Text(dlgCancel)
            }
        }
    )
}

@Composable
private fun MainAccountSwitchDialog(state: MainAccountsState, onSwitching: () -> Unit): Unit = with(state) {
    val context = LocalContext.current
    // Dialog strings are resolved out here, in the parent composition — see wrapContextWithLocale().
    val dlgTitle = stringResource(R.string.main_switch_account_title)
    val dlgText = stringResource(R.string.main_switch_account_text, showSwitchConfirmDialog!!.name)
    val dlgRestart = stringResource(R.string.main_restart_switch)
    val dlgCancel = stringResource(R.string.action_cancel)
    AppAlertDialog(
        onDismissRequest = { showSwitchConfirmDialog = null },
        title = { Text(dlgTitle) },
        text = { Text(dlgText) },
        confirmButton = {
            Button(onClick = {
                val target = showSwitchConfirmDialog!!
                showSwitchConfirmDialog = null
                onSwitching()

                // Move core logic to Service via RESTART_ACTION
                AccountManager.setActiveAccount(context, target.id)
                activeAccount = target
                context.startService(Intent(context, TailscaledService::class.java).apply { action = "RESTART_ACTION" })
            }) { Text(dlgRestart) }
        },
        dismissButton = { TextButton(onClick = { showSwitchConfirmDialog = null }) { Text(dlgCancel) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainAccountsMenuSheet(state: MainAccountsState, proxyState: String): Unit = with(state) {
    val context = LocalContext.current
    val tv = isTv
    // Which account cannot be deleted; read again whenever the list changes.
    var primaryId by remember(accounts.value) { mutableStateOf(AccountManager.getPrimaryId(context)) }
    val sheetState = rememberFullSheetState()
    // Strings come from the parent context, not stringResource() — see wrapContextWithLocale().
    val sheetTitle = context.getString(R.string.accounts_sheet_title)
    val sheetHelp = context.getString(R.string.accounts_sheet_help)
    val primaryHelp = context.getString(R.string.accounts_primary_help)
    val sheetEdit = context.getString(R.string.action_edit)
    val sheetDone = context.getString(R.string.accounts_done)
    val sheetAdd = context.getString(R.string.accounts_add)
    val strSignedIn = context.getString(R.string.accounts_signed_in)
    val strNeverSignedIn = context.getString(R.string.accounts_never_signed_in)
    val strNotConnected = context.getString(R.string.accounts_not_connected)

    // What the active account's node is right now. Read once per opening,
    // off the main thread; the other accounts have no daemon to ask and
    // describe themselves from their own preferences instead.
    var activeLiveLine by remember(activeAccount.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(activeAccount.id, proxyState) {
        if (proxyState != "ACTIVE") { activeLiveLine = null; return@LaunchedEffect }
        val line = withContext(Dispatchers.IO) {
            runCatching {
                val st = DaemonStatus.readOrNull() ?: return@runCatching null
                val self = st.self ?: return@runCatching null
                // The tailnet, not the node's full name: which tailnet this
                // account is in is what tells it apart from the others, and
                // the fqdn is long enough to push the address off the row.
                val tailnet = st.magicDnsSuffix?.trim('.')?.takeIf { it.isNotEmpty() }
                    ?: self.dnsName?.trim('.')?.substringAfter('.')?.takeIf { it.isNotEmpty() }
                val ip = self.tailscaleIPs?.firstOrNull { !it.contains(':') }
                listOfNotNull(tailnet, ip).joinToString(" · ").takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
        activeLiveLine = line
    }

    // What each profile says of itself, read once per opening and for every row together,
    // off the main thread: the first look at another profile's store loads its file, and
    // whether it ever registered can read its state. Done in the frame that opened the sheet,
    // that was file I/O per row; the sheet is still sliding in when this lands.
    var accountFacts by remember { mutableStateOf<Map<String, AccountManager.AccountFacts>>(emptyMap()) }
    LaunchedEffect(accounts.value) {
        val ids = accounts.value.map { it.id }
        accountFacts = withContext(Dispatchers.IO) { ids.associateWith { AccountManager.facts(context, it) } }
    }

    // With a remote the sheet opens on the account in use — OK there closes it and
    // changes nothing — wherever that account is in the list.
    val activeIndex = accounts.value.indexOfFirst { it.id == activeAccount.id }.coerceAtLeast(0)
    val accountsListState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = if (tv) activeIndex else 0
    )
    AppModalSheet(
        onDismissRequest = { accountMenuExpanded = false; accountsEditMode = false },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    sheetTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // The actions used to hide behind a long press on a row.
                TextButton(onClick = { accountsEditMode = !accountsEditMode }) {
                    Text(if (accountsEditMode) sheetDone else sheetEdit)
                }
            }

            HelpText(
                if (accountsEditMode) primaryHelp else sheetHelp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
            )

            Spacer(Modifier.height(12.dp))

            androidx.compose.foundation.lazy.LazyColumn(
                state = accountsListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(accounts.value.size) { i ->
                    val account = accounts.value[i]
                    val isActive = account.id == activeAccount.id
                    val isPrimary = account.id == primaryId
                    val facts = accountFacts[account.id]
                    AccountRow(
                        account = account,
                        facts = facts,
                        active = isActive,
                        modifier = if (isActive) Modifier.tvInitialFocus() else Modifier,
                        liveLine = if (isActive) activeLiveLine else null,
                        editing = accountsEditMode,
                        signedInLabel = strSignedIn,
                        neverSignedInLabel = strNeverSignedIn,
                        notConnectedLabel = strNotConnected,
                        onClick = {
                            if (accountsEditMode) return@AccountRow
                            accountMenuExpanded = false
                            if (!isActive) {
                                StatusAsides.bump(context, StatusAsides.SWITCHES)
                                if (ProxyState.isActualRunning()) showSwitchConfirmDialog = account
                                else {
                                    AccountManager.setActiveAccount(context, account.id)
                                    activeAccount = account
                                }
                            }
                        },
                        onRename = {
                            accountToRename = account
                            showRenameAccountDialog = true
                        },
                        primary = isPrimary,
                        onMakePrimary = {
                            AccountManager.setPrimary(context, account.id)
                            primaryId = AccountManager.getPrimaryId(context)
                        },
                        onDelete = if (isPrimary) null else {
                            { accountToDeleteConfirm = account }
                        }
                    )
                }

                item {
                    // A permanent row rather than a button: adding an account is
                    // part of the same list everywhere this pattern is used.
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shapedClickable(MaterialTheme.shapes.medium) {
                                accountsEditMode = false
                                accountMenuExpanded = false
                                showAddAccountDialog = true
                            },
                        shape = MaterialTheme.shapes.medium,
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                sheetAdd,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
