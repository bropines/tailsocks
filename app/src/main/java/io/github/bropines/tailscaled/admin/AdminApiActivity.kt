package io.github.bropines.tailscaled.admin
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString

class AdminApiActivity : FragmentActivity() {
    private val isAuthenticated = mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authenticateBiometric()

        setContent {
            TailSocksTheme {
                val authed by isAuthenticated
                if (authed) {
                    AdminApiMainScreen(onBack = { finish() })
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = stringResource(R.string.admin_cd_locked),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp)
                            )
                            Text(
                                stringResource(R.string.admin_locked_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.admin_locked_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { authenticateBiometric() }) {
                                Icon(Icons.Default.Fingerprint, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.action_unlock))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun authenticateBiometric() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isAuthenticated.value = true
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.admin_biometric_title))
            .setSubtitle(getString(R.string.admin_biometric_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            isAuthenticated.value = true
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AdminApiMainScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val profilePrefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    val globalPrefs = remember { context.getSharedPreferences(AdminApiSettings.PREFS_NAME, Context.MODE_PRIVATE) }

    var resolvedTailnet by remember { mutableStateOf(profilePrefs.getString("last_known_tailnet", "") ?: "") }
    // The tailnet's name is the one thing this console reads off the daemon; the Admin API
    // itself is reached over the internet. So a stopped daemon holds nothing up once the
    // name is known, and when it is not, the screen offers to start the daemon instead of
    // spinning. The preview renderer has no daemon: there the demo, or its absence, decides.
    val inPreview = LocalInspectionMode.current
    val demo = LocalDemo.current
    var daemonRunning by remember {
        mutableStateOf(if (inPreview) demo?.running == true else ProxyState.isActualRunning(context))
    }
    var isLoadingSuffix by remember { mutableStateOf(daemonRunning && !inPreview) }
    var suffixRound by remember { mutableIntStateOf(0) }
    var enterTailnetByHand by remember { mutableStateOf(false) }

    // What is stored for this tailnet, read through the same seam the peer sheet's version
    // lookup reads it through (AdminApiSettings), then split into per-field state because the
    // setup and settings screens hand the fields back one at a time.
    val stored = remember(resolvedTailnet) { AdminApiSettings.read(context, resolvedTailnet) }

    // Auth credentials
    var authType by remember(resolvedTailnet) { mutableStateOf(stored.authType) }
    var token by remember(resolvedTailnet) { mutableStateOf(stored.token) }
    var clientId by remember(resolvedTailnet) { mutableStateOf(stored.clientId) }
    var clientSecret by remember(resolvedTailnet) { mutableStateOf(stored.clientSecret) }

    // Proxy settings
    var proxyMode by remember(resolvedTailnet) { mutableStateOf(stored.proxyMode) }
    var proxyHost by remember(resolvedTailnet) { mutableStateOf(stored.proxyHost) }
    var proxyPort by remember(resolvedTailnet) { mutableIntStateOf(stored.proxyPort) }
    var proxyUser by remember(resolvedTailnet) { mutableStateOf(stored.proxyUser) }
    var proxyPass by remember(resolvedTailnet) { mutableStateOf(stored.proxyPass) }

    // Fetch magicDnsSuffix from LocalAPI on start, and again once the Start button below has
    // brought the daemon up.
    LaunchedEffect(activeAccount.id, suffixRound) {
        if (!isLoadingSuffix) return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            try {
                val pJson = appctr.Appctr.getStatusFromAPI()
                if (!pJson.startsWith("Error") && pJson.isNotBlank()) {
                    val status = runCatching { AppJson.decodeFromString<StatusResponse>(pJson) }.getOrNull()
                    val suffix = status?.magicDnsSuffix?.trim()?.removeSuffix(".")
                    if (!suffix.isNullOrBlank()) {
                        profilePrefs.edit().putString("last_known_tailnet", suffix).apply()
                        withContext(Dispatchers.Main) {
                            resolvedTailnet = suffix
                        }
                    }
                }
            } catch (e: Exception) {}
            finally {
                withContext(Dispatchers.Main) {
                    isLoadingSuffix = false
                }
            }
        }
    }

    val hasCredentials = if (authType == AdminApiSettings.AUTH_TYPE_TOKEN) {
        token.isNotBlank()
    } else {
        clientId.isNotBlank() && clientSecret.isNotBlank()
    }

    if (isLoadingSuffix) {
        AdminApiWaitScreen(onBack) { LoadingIndicator() }
    } else if (resolvedTailnet.isBlank() && !daemonRunning && !enterTailnetByHand) {
        AdminApiWaitScreen(onBack) {
            DaemonStoppedState(
                onStarted = {
                    daemonRunning = true
                    isLoadingSuffix = true
                    suffixRound++
                }
            ) {
                // The console itself needs no daemon; only this one name does.
                TextButton(onClick = { enterTailnetByHand = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.state_admin_enter_tailnet))
                }
            }
        }
    } else if (resolvedTailnet.isBlank()) {
        AdminApiNoTailnetScreen(
            onBack = onBack,
            onSaveTailnet = { enteredTailnet ->
                profilePrefs.edit().putString("last_known_tailnet", enteredTailnet).apply()
                resolvedTailnet = enteredTailnet
            }
        )
    } else if (!hasCredentials) {
        AdminApiSetupScreen(
            tailnet = resolvedTailnet,
            initialAuthType = authType,
            initialToken = token,
            initialClientId = clientId,
            initialClientSecret = clientSecret,
            initialProxyMode = proxyMode,
            initialProxyHost = proxyHost,
            initialProxyPort = proxyPort,
            initialProxyUser = proxyUser,
            initialProxyPass = proxyPass,
            onBack = onBack,
            onSave = { type, tok, cid, csec, pmode, phost, pport, puser, ppass ->
                globalPrefs.edit().apply {
                    putString("${resolvedTailnet}_auth_type", type)
                    putString(resolvedTailnet, tok)
                    putString("${resolvedTailnet}_oauth_client_id", cid)
                    putString("${resolvedTailnet}_oauth_client_secret", csec)
                    putString("${resolvedTailnet}_proxy_mode", pmode)
                    putString("${resolvedTailnet}_proxy_host", phost)
                    putInt("${resolvedTailnet}_proxy_port", pport)
                    putString("${resolvedTailnet}_proxy_user", puser)
                    putString("${resolvedTailnet}_proxy_pass", ppass)
                }.apply()
                authType = type
                token = tok
                clientId = cid
                clientSecret = csec
                proxyMode = pmode
                proxyHost = phost
                proxyPort = pport
                proxyUser = puser
                proxyPass = ppass
            },
            onResetTailnet = {
                profilePrefs.edit().remove("last_known_tailnet").apply()
                resolvedTailnet = ""
            }
        )
    } else {
        AdminApiDashboardScreen(
            token = token,
            tailnet = resolvedTailnet,
            clientId = clientId,
            clientSecret = clientSecret,
            proxyMode = proxyMode,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUser = proxyUser,
            proxyPass = proxyPass,
            onUpdateProxy = { pmode, phost, pport, puser, ppass ->
                globalPrefs.edit().apply {
                    putString("${resolvedTailnet}_proxy_mode", pmode)
                    putString("${resolvedTailnet}_proxy_host", phost)
                    putInt("${resolvedTailnet}_proxy_port", pport)
                    putString("${resolvedTailnet}_proxy_user", puser)
                    putString("${resolvedTailnet}_proxy_pass", ppass)
                }.apply()
                proxyMode = pmode
                proxyHost = phost
                proxyPort = pport
                proxyUser = puser
                proxyPass = ppass
            },
            onBack = onBack,
            onDisconnect = {
                globalPrefs.edit().apply {
                    remove(resolvedTailnet)
                    remove("${resolvedTailnet}_auth_type")
                    remove("${resolvedTailnet}_oauth_client_id")
                    remove("${resolvedTailnet}_oauth_client_secret")
                    remove("${resolvedTailnet}_proxy_mode")
                    remove("${resolvedTailnet}_proxy_host")
                    remove("${resolvedTailnet}_proxy_port")
                    remove("${resolvedTailnet}_proxy_user")
                    remove("${resolvedTailnet}_proxy_pass")
                }.apply()
                token = ""
                clientId = ""
                clientSecret = ""
            }
        )
    }
}

/**
 * The console's frame while it cannot show a console yet — the tailnet name still coming
 * from the daemon, or the daemon stopped. It used to be a bare loader with no top bar, and
 * with no way back but the system gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminApiWaitScreen(onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_console_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.admin_cd_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}
