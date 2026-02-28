package io.github.bropines.tailscaled

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.lang.Runtime

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkNotificationPermission()
        handleAppStartup()

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                MainScreen()
            }
        }
    }

    private fun handleAppStartup() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val appctrPrefs = getSharedPreferences("appctr", Context.MODE_PRIVATE)
        
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val currentUpdateTime = packageInfo.lastUpdateTime
            val savedUpdateTime = prefs.getLong("last_update_time", 0)

            if (savedUpdateTime != currentUpdateTime) {
                Runtime.getRuntime().exec("killall tailscaled")
                prefs.edit().putLong("last_update_time", currentUpdateTime).apply()
            }
        } catch (e: Exception) {}

        val forceBg = appctrPrefs.getBoolean("force_bg", false)

        if (ProxyState.isUserLetRunning(this) && !ProxyState.isActualRunning()) {
            if (forceBg) {
                val authKey = appctrPrefs.getString("authkey", "") ?: ""
                if (authKey.isNotBlank()) {
                    val intent = Intent(this, TailscaledService::class.java).apply { action = "START_ACTION" }
                    ContextCompat.startForegroundService(this, intent)
                } else {
                    ProxyState.setUserState(this, false)
                }
            } else {
                ProxyState.setUserState(this, false)
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("appctr", Context.MODE_PRIVATE)
    
    var proxyState by remember { mutableStateOf(if (ProxyState.isActualRunning()) "ACTIVE" else "STOPPED") }
    var showAboutDialog by remember { mutableStateOf(false) }
    var exitNodeIp by remember { mutableStateOf(prefs.getString("exit_node_ip", "") ?: "") }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    "STARTING" -> proxyState = "STARTING"
                    "START" -> {
                        proxyState = "ACTIVE"
                        exitNodeIp = prefs.getString("exit_node_ip", "") ?: ""
                    }
                    "STOP" -> proxyState = "STOPPED"
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("STARTING")
            addAction("START")
            addAction("STOP")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TailSocks") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            if (proxyState == "ACTIVE" && exitNodeIp.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Traffic is routed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text("Via exit node: $exitNodeIp", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }

            StatusCard(state = proxyState) {
                if (proxyState == "ACTIVE" || proxyState == "STARTING") {
                    val intent = Intent(context, TailscaledService::class.java).apply { action = "STOP_ACTION" }
                    context.startService(intent)
                } else {
                    val currentAuthKey = prefs.getString("authkey", "") ?: ""
                    if (currentAuthKey.isBlank()) {
                        Toast.makeText(context, "🚫 Error: Please enter your Auth Key in the settings!", Toast.LENGTH_LONG).show()
                        return@StatusCard
                    }
                    
                    val currentSocks = prefs.getString("socks5", "127.0.0.1:1055") ?: "127.0.0.1:1055"
                    if (currentSocks.isBlank()) {
                        Toast.makeText(context, "🚫 Error: SOCKS5 address cannot be empty!", Toast.LENGTH_LONG).show()
                        return@StatusCard
                    }

                    val intent = Intent(context, TailscaledService::class.java).apply { action = "START_ACTION" }
                    ContextCompat.startForegroundService(context, intent)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = "Console", icon = Icons.Default.PlayArrow, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, ConsoleActivity::class.java))
                }
                MenuCard(title = "Peers", icon = Icons.Default.Share, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, PeersActivity::class.java))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuCard(title = "Logs", icon = Icons.Default.Info, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    context.startActivity(Intent(context, LogsActivity::class.java))
                }
                MenuCard(title = "Settings", icon = Icons.Default.Settings, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            TextButton(onClick = { showAboutDialog = true }) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("About & Licenses")
            }
        }
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("TailSocks") },
            text = { 
                Column {
                    Text("Proxy is running via official Tailscale core.\nLicense: BSD-3-Clause\n")
                    
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bropines"))) },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("App Developer: Bropines") }
                    
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Asutorufa/tailscale"))) },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("Patch Developer: Asutorufa") }

                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tailscale/tailscale"))) },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("Core Developer: Tailscale") }
                } 
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bropines/tailscaled-socks5-android")))
                    showAboutDialog = false
                }) { Text("GitHub Repo") }
            },
            dismissButton = { TextButton(onClick = { showAboutDialog = false }) { Text("Close") } }
        )
    }
}

@Composable
fun StatusCard(state: String, onToggle: () -> Unit) {
    val backgroundColor = when (state) {
        "ACTIVE" -> Color(0xFFDCF8C6)
        "STARTING" -> Color(0xFFFFF59D)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when (state) {
        "ACTIVE" -> Color(0xFF205023)
        "STARTING" -> Color(0xFFF57F17)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = backgroundColor,
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clickable { onToggle() },
        tonalElevation = 4.dp
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = when(state) {
                    "ACTIVE" -> Icons.Default.CheckCircle
                    "STARTING" -> Icons.Default.Refresh
                    else -> Icons.Default.CheckCircle
                },
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(48.dp).padding(bottom = 16.dp)
            )
            Text(
                text = when(state) {
                    "ACTIVE" -> "Active"
                    "STARTING" -> "Starting..."
                    else -> "Stopped"
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = if (state != "STOPPED") Color.Black else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = when(state) {
                    "ACTIVE" -> "Service is running • Tap to stop"
                    "STARTING" -> "Waking up the daemon..."
                    else -> "Tap to connect"
                },
                modifier = Modifier.alpha(0.6f).padding(top = 4.dp),
                color = if (state != "STOPPED") Color.Black else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun MenuCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}