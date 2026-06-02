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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
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

@Composable
fun AdminApiMainScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeAccount = remember { AccountManager.getActiveAccount(context) }
    val profilePrefs = remember(activeAccount.id) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }
    val globalPrefs = remember { context.getSharedPreferences("admin_api_keys", Context.MODE_PRIVATE) }

    var resolvedTailnet by remember { mutableStateOf(profilePrefs.getString("last_known_tailnet", "") ?: "") }
    var isLoadingSuffix by remember { mutableStateOf(true) }

    // Auth credentials
    var authType by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_auth_type", "TOKEN") ?: "TOKEN") }
    var token by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString(resolvedTailnet, "") ?: "") }
    var clientId by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_oauth_client_id", "") ?: "") }
    var clientSecret by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_oauth_client_secret", "") ?: "") }

    // Proxy settings
    var proxyMode by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_proxy_mode", "DIRECT") ?: "DIRECT") }
    var proxyHost by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_proxy_host", "") ?: "") }
    var proxyPort by remember(resolvedTailnet) { mutableIntStateOf(globalPrefs.getInt("${resolvedTailnet}_proxy_port", 0)) }
    var proxyUser by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_proxy_user", "") ?: "") }
    var proxyPass by remember(resolvedTailnet) { mutableStateOf(globalPrefs.getString("${resolvedTailnet}_proxy_pass", "") ?: "") }

    // Fetch magicDnsSuffix from LocalAPI on start
    LaunchedEffect(activeAccount.id) {
        scope.launch(Dispatchers.IO) {
            try {
                val pJson = appctr.Appctr.getStatusFromAPI()
                if (!pJson.startsWith("Error")) {
                    val status = com.google.gson.Gson().fromJson(pJson, StatusResponse::class.java)
                    val suffix = status.magicDnsSuffix?.trim()?.removeSuffix(".")
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

    val hasCredentials = if (authType == "TOKEN") {
        token.isNotBlank()
    } else {
        clientId.isNotBlank() && clientSecret.isNotBlank()
    }

    if (isLoadingSuffix) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
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
