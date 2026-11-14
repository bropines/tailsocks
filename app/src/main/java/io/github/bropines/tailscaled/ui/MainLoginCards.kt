package io.github.bropines.tailscaled.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.theme.findActivity
import kotlinx.serialization.json.*

/*
 * The cards the main screen shows in place of the dashboard when there is nothing to show:
 * signed out (LoggedOutCard, with its sign-in choices) and the daemon unable to reach the
 * coordination server (ConnectionIssueCard). MainScreen in MainActivity.kt decides which.
 */

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoggedOutCard(
    loginUrl: String?,
    onConfigureProxy: () -> Unit,
    onStop: () -> Unit,
    /**
     * Set where signing in is a code on this device's own screen (a television): the
     * card's button shows it instead of opening a browser.
     */
    onShowCode: (() -> Unit)? = null,
    /** The code was shown and left: its button is where the remote comes back to. */
    codeWasLeft: Boolean = false,
    /**
     * The profile the card signs in: the screen's active account. A switch does not recreate
     * the screen, so a card that looked it up once kept the first one's auth key. Null (a
     * preview) reads the stored active account.
     */
    accountId: String? = null,
) {
    val context = LocalContext.current
    val profileId = remember(accountId) { accountId ?: io.github.bropines.tailscaled.core.AccountManager.getActiveAccount(context).id }
    val profilePrefs = remember(profileId) { context.getSharedPreferences("appctr_$profileId", Context.MODE_PRIVATE) }
    var enteredKey by remember(profileId) { mutableStateOf(profilePrefs.getString("authkey", "") ?: "") }
    var showKeyInput by remember { mutableStateOf(false) }
    // The link's sheet goes with the link: a login that went through closes it.
    var showLink by remember { mutableStateOf(false) }
    LaunchedEffect(loginUrl == null) { if (loginUrl == null) showLink = false }
    if (showLink && loginUrl != null) SignInLinkSheet(url = loginUrl, onDismiss = { showLink = false })
    // The login tab wears the app's colours (LoginTab).
    val tabToolbar = MaterialTheme.colorScheme.surface.toArgb()
    val tabDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(R.string.main_logged_out_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            
            Text(
                text = stringResource(R.string.main_logged_out_desc),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onShowCode != null) {
                    // The code's own screen waits for the link, so this needs none.
                    Button(
                        onClick = onShowCode,
                        modifier = Modifier.weight(1f).height(40.dp)
                            .then(if (codeWasLeft) Modifier.tvInitialFocus() else Modifier),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Icon(Icons.Default.QrCode2, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.tv_signin_open),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                } else if (loginUrl != null) {
                    Button(
                        onClick = {
                            val activity = context.findActivity()
                            if (activity != null) {
                                LoginTab.open(activity, loginUrl, tabToolbar, tabDark)
                            } else if (!context.tryStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))) {
                                Toast.makeText(context, context.getString(R.string.cannot_open_browser), Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.main_logged_out_btn_login),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(modifier = Modifier.size(20.dp))
                    }
                }

                OutlinedButton(
                    onClick = { showKeyInput = !showKeyInput },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Key, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(if (showKeyInput) R.string.login_hide_key else R.string.login_use_key),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // The same link for another device: a friend's phone signed in by the tailnet's
            // owner, who is somewhere else. Not where the code has a screen of its own.
            // A button of the row above, and tonal: "Sign in" stays the one filled in the accent.
            if (onShowCode == null && loginUrl != null) {
                FilledTonalButton(
                    onClick = { showLink = true },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.QrCode2, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.signin_other_device),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            AnimatedVisibility(visible = showKeyInput) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PageTextField(
                        value = enteredKey,
                        onValueChange = { enteredKey = it },
                        label = stringResource(R.string.main_logged_out_authkey_label),
                        modifier = Modifier.fillMaxWidth(),
                        labelFontSize = 11.sp
                    )
                    Button(
                        onClick = {
                            profilePrefs.edit().putString("authkey", enteredKey).apply()
                            val stopIntent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
                            context.startService(stopIntent)
                            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                            mainHandler.postDelayed({
                                val startIntent = Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
                                ContextCompat.startForegroundService(context, startIntent)
                            }, 500)
                            Toast.makeText(context, context.getString(R.string.main_key_saved_restarting), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(stringResource(R.string.main_submit_key), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onConfigureProxy,
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.main_proxy_setup), fontSize = 11.sp)
                }

                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.main_logged_out_btn_stop_short), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun ConnectionIssueCard(
    onConfigureProxy: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(R.string.main_conn_issue_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            Text(
                text = stringResource(R.string.main_conn_issue_desc),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onConfigureProxy,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.main_conn_issue_btn_proxy),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.main_logged_out_btn_stop_short),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
