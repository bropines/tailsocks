package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.Feature
import io.github.bropines.tailscaled.core.ProfileHostinfo
import io.github.bropines.tailscaled.core.GlobalSettings
import io.github.bropines.tailscaled.core.tryLaunch
import io.github.bropines.tailscaled.core.tryStartActivity
import io.github.bropines.tailscaled.core.SlidingSegmentedChips
import io.github.bropines.tailscaled.core.SegmentedChipItem
import io.github.bropines.tailscaled.core.PredictiveBackContainer
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import kotlinx.coroutines.launch

class FirstStartActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(io.github.bropines.tailscaled.core.wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TailSocksTheme {
                FirstStartScreen(
                    onFinished = {
                        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("first_start_done", true)
                            .apply()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}

/** The onboarding slides; [initialPage] opens on a later one, for the previews. */
@Composable
fun FirstStartScreen(onFinished: () -> Unit, initialPage: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A step with nothing on it is not walked through. That is the permissions on a device
    // with no battery to be let off optimising for and no notifications left to allow — a
    // television; a phone is always shown the step, as it was.
    val inPreview = androidx.compose.ui.platform.LocalInspectionMode.current
    val notificationsToAllow = remember { !inPreview && !notificationsAllowed(context) }
    val askPermissions = Feature.BATTERY_OPTIMISATION_PROMPT.offered() || notificationsToAllow
    val steps = remember(askPermissions) { OnboardingStep.entries.filter { it != OnboardingStep.PERMISSIONS || askPermissions } }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { steps.size })

    val activeAccount = remember { io.github.bropines.tailscaled.core.AccountManager.getActiveAccount(context) }
    val profilePrefs = remember(activeAccount) { context.getSharedPreferences("appctr_${activeAccount.id}", Context.MODE_PRIVATE) }

    val window = rememberWindowLayout()
    val slideLayout = when {
        window.isPhone -> SlideLayout.PHONE
        window.widthClass >= WindowWidthClass.EXPANDED || window.fold is Fold.Vertical -> SlideLayout.SIDE_BY_SIDE
        else -> SlideLayout.TALL
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Back Button
                        if (pagerState.currentPage > 0) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.first_start_btn_back))
                            }
                        } else {
                            Spacer(modifier = Modifier.width(80.dp))
                        }

                        // Indicators
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(steps.size) { idx ->
                                val isSelected = pagerState.currentPage == idx
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 10.dp else 8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                        )
                                )
                            }
                        }

                        // Next / Finish Button. One button for both, so a remote that walks
                        // the steps with it stays on it at the last one; and it is where the
                        // remote starts — every step can be passed with OK alone.
                        val lastStep = pagerState.currentPage >= steps.lastIndex
                        Button(
                            onClick = {
                                if (lastStep) onFinished()
                                else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.tvInitialFocus()
                        ) {
                            Text(stringResource(if (lastStep) R.string.first_start_btn_finish else R.string.first_start_btn_next))
                        }
                    }
                }
            }
        ) { paddingValues ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                userScrollEnabled = false
            ) { page ->
                val slide: @Composable () -> Unit = {
                    when (steps[page]) {
                        OnboardingStep.WELCOME -> SlideWelcome()
                        OnboardingStep.HOW_IT_WORKS -> SlideHowItWorks()
                        OnboardingStep.CONTROL_PLANE -> SlideControlPlane(profilePrefs)
                        OnboardingStep.BYPASS -> SlideBypassSetup()
                        OnboardingStep.LOGIN -> SlideLogin(profilePrefs)
                        OnboardingStep.PERMISSIONS -> SlidePermissions()
                    }
                }
                CompositionLocalProvider(LocalSlideLayout provides slideLayout) {
                    if (slideLayout == SlideLayout.SIDE_BY_SIDE) {
                        // SlideContainer lays out the two halves and scrolls its own.
                        Box(Modifier.fillMaxSize()) { slide() }
                    } else {
                        // The whole page scrolls, so the slides fit a phone on its side;
                        // held to a readable width, so they do not sprawl on a tablet —
                        // a narrower one on a medium window, around a larger picture.
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .then(
                                    if (slideLayout == SlideLayout.TALL) {
                                        Modifier
                                            .fillMaxWidth()
                                            .wrapContentWidth(Alignment.CenterHorizontally)
                                            .widthIn(max = SlideColumnWidth + 48.dp)
                                    } else Modifier.readableWidth()
                                )
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            slide()
                        }
                    }
                }
            }
        }

        IconButton(
            onClick = onFinished,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.action_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The onboarding's steps, in the order they are walked. */
private enum class OnboardingStep { WELCOME, HOW_IT_WORKS, CONTROL_PLANE, BYPASS, LOGIN, PERMISSIONS }

/** Before Android 13 a notification needs no permission. */
private fun notificationsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** How a slide stands in its window; see [SlideContainer]. */
private enum class SlideLayout {
    /** A phone, either way up: one column, as the slides were written for. */
    PHONE,

    /** A medium window: the phone's column, narrower than the window and around a larger picture. */
    TALL,

    /** Expanded and wider, or a foldable open like a book: the picture on one side, the step on the other. */
    SIDE_BY_SIDE,
}

private val LocalSlideLayout = staticCompositionLocalOf { SlideLayout.PHONE }

/** The step's column on a tablet: its words and its controls, not the window's width. */
private val SlideColumnWidth = 560.dp

/** What the Close button in the corner takes of a step's height on a television: its margin and its 48dp. */
private val SlideCloseClearance = 60.dp

/**
 * One slide: its picture, its title, a line on what it is for, and [content].
 * On a phone a column, top to bottom. On a large window the picture and the
 * title take one half and the step the other, so a slide reads as a page of
 * the window rather than a phone's column in the middle of it; on a foldable
 * open like a book the hinge is between the two.
 */
@Composable
fun SlideContainer(
    icon: ImageVector,
    title: String,
    description: String,
    content: @Composable () -> Unit = {}
) {
    when (LocalSlideLayout.current) {
        SlideLayout.SIDE_BY_SIDE -> SlideSideBySide(icon, title, description, content)
        SlideLayout.TALL -> SlideColumn(icon, title, description, hero = 128.dp, content = content)
        SlideLayout.PHONE -> SlideColumn(icon, title, description, hero = 96.dp, content = content)
    }
}

@Composable
private fun SlideHero(icon: ImageVector, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size / 2)
        )
    }
}

@Composable
private fun SlideDescription(description: String) {
    HelpText(
        description,
        lines = 3,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        lineHeight = 22.sp,
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}

@Composable
private fun SlideSideBySide(icon: ImageVector, title: String, description: String, content: @Composable () -> Unit) {
    val fold = rememberWindowLayout().fold as? Fold.Vertical
    Row(Modifier.fillMaxSize()) {
        // The picture's half: a pane of its own colour, so the two halves read as one page.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = (if (fold != null) Modifier.width(fold.start) else Modifier.weight(1f))
                .fillMaxHeight()
                .padding(start = PaneDefaults.Gap, top = PaneDefaults.Gap, bottom = PaneDefaults.Gap)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                SlideHero(icon, if (fold != null) 128.dp else 160.dp)
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
        if (fold != null) Spacer(Modifier.width(fold.end - fold.start))
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = SlideColumnWidth + 48.dp)
                    // A television's step starts below the Close button: a step as tall as the
                    // screen ran its first lines under it, and scrolled the rest through it.
                    .then(if (isTv) Modifier.padding(top = SlideCloseClearance) else Modifier)
                    .verticalScroll(rememberScrollState())
                    .then(if (isTv) Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp) else Modifier.padding(24.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                SlideDescription(description)
                Spacer(modifier = Modifier.height(24.dp))
                content()
            }
        }
    }
}

@Composable
private fun SlideColumn(
    icon: ImageVector,
    title: String,
    description: String,
    hero: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit
) {
    SlideHero(icon, hero)
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))
    SlideDescription(description)
    Spacer(modifier = Modifier.height(24.dp))
    content()
}

@Composable
fun SlideWelcome() {
    SlideContainer(
        icon = Icons.Default.Speed,
        title = stringResource(R.string.first_start_welcome_title),
        description = stringResource(R.string.first_start_welcome_desc)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.first_start_select_lang),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            LanguagePicker()
        }
    }
}

@Composable
fun SlideHowItWorks() {
    val context = LocalContext.current
    var isTunMode by remember { mutableStateOf(GlobalSettings.isTunModeEnabled(context)) }
    var isRootMode by remember { mutableStateOf(GlobalSettings.isRootModeEnabled(context)) }
    var showRootWarningDialog by remember { mutableStateOf(false) }

    if (showRootWarningDialog) {
        // Strings resolved in the parent composition — see wrapContextWithLocale().
        val strSettingsRootWarningDialogTitle = stringResource(R.string.settings_root_warning_dialog_title)
        val strSettingsRootWarningDialogBody = stringResource(R.string.settings_root_warning_dialog_body)
        val strSettingsRootWarningDialogConfirm = stringResource(R.string.settings_root_warning_dialog_confirm)
        val strSettingsRootWarningDialogCancel = stringResource(R.string.settings_root_warning_dialog_cancel)
        AppAlertDialog(
            onDismissRequest = { showRootWarningDialog = false },
            title = { Text(strSettingsRootWarningDialogTitle) },
            text = { Text(strSettingsRootWarningDialogBody) },
            confirmButton = {
                Button(
                    onClick = {
                        showRootWarningDialog = false
                        if (io.github.bropines.tailscaled.core.RootUtils.isRootAvailable()) {
                            isRootMode = true
                            GlobalSettings.setRootModeEnabled(context, true)
                            Toast.makeText(context, context.getString(R.string.settings_root_mode_enabled_toast), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.settings_root_access_unavailable), Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(strSettingsRootWarningDialogConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRootWarningDialog = false }) {
                    Text(strSettingsRootWarningDialogCancel)
                }
            }
        )
    }

    SlideContainer(
        icon = Icons.Default.Language,
        title = stringResource(R.string.first_start_how_title),
        description = stringResource(R.string.first_start_how_desc)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SlidingSegmentedChips(
                options = listOf(stringResource(R.string.first_start_chip_proxy), stringResource(R.string.first_start_chip_vpn)),
                selectedIndex = if (isTunMode) 1 else 0,
                onOptionSelected = { idx ->
                    if (!isRootMode) {
                        isTunMode = (idx == 1)
                        GlobalSettings.setTunModeEnabled(context, isTunMode)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                height = 38.dp
            )

            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isRootMode) 0.2f else 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isRootMode) {
                            "Root Mode active"
                        } else if (!isTunMode) {
                            stringResource(R.string.first_start_mode_proxy_title)
                        } else {
                            stringResource(R.string.first_start_mode_vpn_title)
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isRootMode) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isRootMode) {
                            "Proxy and TUN modes are inactive because TailSocks operates natively as a system daemon in Root mode."
                        } else if (!isTunMode) {
                            stringResource(R.string.first_start_mode_proxy_desc)
                        } else {
                            stringResource(R.string.first_start_mode_vpn_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            // Root Mode is a first choice only where it is offered as one.
            if (Feature.ROOT_MODE.offered()) Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Security, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.settings_root_sect_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        HelpText(stringResource(R.string.settings_root_enable_desc))
                    }
                    Switch(
                        checked = isRootMode,
                        onCheckedChange = { checked ->
                            if (checked) {
                                showRootWarningDialog = true
                            } else {
                                isRootMode = false
                                GlobalSettings.setRootModeEnabled(context, false)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SlideControlPlane(profilePrefs: android.content.SharedPreferences) {
    var loginServer by remember { mutableStateOf(profilePrefs.getString("login_server", "") ?: "") }

    SlideContainer(
        icon = Icons.Default.Cloud,
        title = stringResource(R.string.first_start_cp_warning_title),
        description = stringResource(R.string.first_start_cp_warning_desc)
    ) {
        PageTextField(
            value = loginServer,
            onValueChange = {
                loginServer = it
                profilePrefs.edit().putString("login_server", it).apply()
            },
            label = stringResource(R.string.first_start_cp_field_label),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideBypassSetup() {
    val context = LocalContext.current
    var bypassMode by remember {
        mutableStateOf(
            if (GlobalSettings.isCPByeDpiEnabled(context)) "bbd"
            else if (GlobalSettings.isCPProxyEnabled(context)) "proxy"
            else "none"
        )
    }

    var proxyLink by remember { mutableStateOf("") }
    var proxyType by remember { mutableStateOf(GlobalSettings.getCPField(context, "type", "SOCKS5")) }
    var proxyHost by remember { mutableStateOf(GlobalSettings.getCPField(context, "host")) }
    var proxyPort by remember { mutableStateOf(GlobalSettings.getCPField(context, "port")) }
    var proxyUser by remember { mutableStateOf(GlobalSettings.getCPField(context, "user")) }
    var proxyPass by remember { mutableStateOf(GlobalSettings.getCPField(context, "pass")) }

    var presets by remember { mutableStateOf(GlobalSettings.getCPPresets(context)) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    fun applyBypassSettings(mode: String) {
        bypassMode = mode
        when (mode) {
            "none" -> {
                GlobalSettings.setCPByeDpiEnabled(context, false)
                GlobalSettings.setCPProxyEnabled(context, false)
            }
            "bbd" -> {
                GlobalSettings.setCPByeDpiEnabled(context, true)
                GlobalSettings.setCPProxyEnabled(context, false)
            }
            "proxy" -> {
                GlobalSettings.setCPByeDpiEnabled(context, false)
                GlobalSettings.setCPProxyEnabled(context, true)
            }
        }
    }

    SlideContainer(
        icon = Icons.Default.Shield,
        title = stringResource(R.string.first_start_bypass_title),
        description = stringResource(R.string.first_start_bypass_desc)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(
                "none" to stringResource(R.string.first_start_bypass_mode_none),
                "bbd" to stringResource(R.string.first_start_bypass_mode_bbd),
                "proxy" to stringResource(R.string.first_start_bypass_mode_proxy)
            ).forEach { (mode, label) ->
                val isSelected = bypassMode == mode
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .shapedClickable(MaterialTheme.shapes.medium) { applyBypassSettings(mode) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The card is the remote's stop; the button in it would be a second one.
                        RadioButton(
                            selected = isSelected,
                            onClick = { applyBypassSettings(mode) },
                            modifier = Modifier.tvNoFocus()
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            if (bypassMode == "proxy") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PageTextField(
                            value = proxyLink,
                            onValueChange = { proxyLink = it },
                            label = stringResource(R.string.first_start_proxy_link_label),
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = if (!Feature.CLIPBOARD_ACTIONS.offered()) null else ({
                                IconButton(
                                    onClick = {
                                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clipData = clipboardManager.primaryClip
                                        val clipboardText = if (clipData != null && clipData.itemCount > 0) {
                                            clipData.getItemAt(0).text?.toString()?.trim()
                                        } else null

                                        val linkToParse = if (!clipboardText.isNullOrEmpty()) {
                                            proxyLink = clipboardText
                                            clipboardText
                                        } else {
                                            proxyLink.trim()
                                        }

                                        if (linkToParse.isNotEmpty()) {
                                            val parsed = GlobalSettings.parseProxyUri(linkToParse)
                                            if (parsed != null) {
                                                proxyType = parsed["type"] ?: "SOCKS5"
                                                proxyHost = parsed["host"] ?: ""
                                                proxyPort = parsed["port"] ?: ""
                                                proxyUser = parsed["user"] ?: ""
                                                proxyPass = parsed["pass"] ?: ""
                                                
                                                GlobalSettings.setCPField(context, "type", proxyType)
                                                GlobalSettings.setCPField(context, "host", proxyHost)
                                                GlobalSettings.setCPField(context, "port", proxyPort)
                                                GlobalSettings.setCPField(context, "user", proxyUser)
                                                GlobalSettings.setCPField(context, "pass", proxyPass)
                                                Toast.makeText(context, context.getString(R.string.settings_proxy_parsed_success), Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, context.getString(R.string.settings_proxy_import_error), Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, context.getString(R.string.settings_proxy_clipboard_empty), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.settings_proxy_cd_paste_parse))
                                }
                            })
                        )
                        Spacer(Modifier.height(4.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            InputChip(
                                selected = false,
                                onClick = {
                                    if (proxyHost.isNotEmpty() && proxyPort.isNotEmpty()) {
                                        presetName = "$proxyHost:$proxyPort"
                                        showSavePresetDialog = true
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.settings_proxy_preset_fill_fields_error), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                label = { Text(stringResource(R.string.settings_proxy_preset_add_chip)) },
                                leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) }
                            )

                            presets.forEach { preset ->
                                InputChip(
                                    selected = false,
                                    onClick = {
                                        proxyType = preset.type
                                        proxyHost = preset.host
                                        proxyPort = preset.port
                                        proxyUser = preset.user
                                        proxyPass = preset.pass
                                        GlobalSettings.setCPField(context, "type", proxyType)
                                        GlobalSettings.setCPField(context, "host", proxyHost)
                                        GlobalSettings.setCPField(context, "port", proxyPort)
                                        GlobalSettings.setCPField(context, "user", proxyUser)
                                        GlobalSettings.setCPField(context, "pass", proxyPass)
                                    },
                                    label = { Text(preset.name) },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                val updated = presets.filter { it != preset }
                                                presets = updated
                                                GlobalSettings.saveCPPresets(context, updated)
                                            },
                                            modifier = Modifier.size(16.dp)
                                        ) {
                                            Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp))
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PageTextField(
                                value = proxyHost,
                                onValueChange = {
                                    proxyHost = it
                                    GlobalSettings.setCPField(context, "host", it)
                                },
                                label = stringResource(R.string.first_start_proxy_host_label),
                                modifier = Modifier.weight(2f)
                            )
                            PageTextField(
                                value = proxyPort,
                                onValueChange = { newValue ->
                                    val digits = newValue.filter { it.isDigit() }
                                    if (digits.length <= 5) {
                                        val num = digits.toIntOrNull()
                                        if (num == null || num <= 65535) {
                                            proxyPort = digits
                                            GlobalSettings.setCPField(context, "port", digits)
                                        }
                                    }
                                },
                                label = stringResource(R.string.first_start_proxy_port_label),
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PageTextField(
                                value = proxyUser,
                                onValueChange = {
                                    proxyUser = it
                                    GlobalSettings.setCPField(context, "user", it)
                                },
                                label = stringResource(R.string.settings_socks5_username_title),
                                modifier = Modifier.weight(1f)
                            )
                            PageTextField(
                                value = proxyPass,
                                onValueChange = {
                                    proxyPass = it
                                    GlobalSettings.setCPField(context, "pass", it)
                                },
                                label = stringResource(R.string.settings_socks5_password_title),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSavePresetDialog) {
        // Strings resolved in the parent composition — see wrapContextWithLocale().
        val strSettingsProxyPresetSaveTitle = stringResource(R.string.settings_proxy_preset_save_title)
        val strSettingsProxyPresetNameLabel = stringResource(R.string.settings_proxy_preset_name_label)
        val strSettingsProxyPresetSaveBtn = stringResource(R.string.settings_proxy_preset_save_btn)
        val strActionCancel = stringResource(R.string.action_cancel)
        AppAlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text(strSettingsProxyPresetSaveTitle) },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(strSettingsProxyPresetNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nameToSave = presetName.trim().ifEmpty { "$proxyHost:$proxyPort" }
                        val newPreset = GlobalSettings.ProxyPreset(
                            name = nameToSave,
                            type = proxyType,
                            host = proxyHost,
                            port = proxyPort,
                            user = proxyUser,
                            pass = proxyPass
                        )
                        val updated = presets + newPreset
                        presets = updated
                        GlobalSettings.saveCPPresets(context, updated)
                        showSavePresetDialog = false
                    }
                ) {
                    Text(strSettingsProxyPresetSaveBtn)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text(strActionCancel)
                }
            }
        )
    }
}

@Composable
fun SlideLogin(profilePrefs: android.content.SharedPreferences) {
    var authKey by remember { mutableStateOf(profilePrefs.getString("authkey", "") ?: "") }
    // Where signing in is a code for a phone, the step says so instead of naming a browser.
    val browserSignIn = Feature.BROWSER_SIGN_IN.offered()

    SlideContainer(
        icon = Icons.Default.VpnKey,
        title = stringResource(R.string.first_start_login_title),
        description = stringResource(if (browserSignIn) R.string.first_start_login_desc else R.string.tv_onboarding_login_desc)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PageTextField(
                value = authKey,
                onValueChange = {
                    authKey = it
                    profilePrefs.edit().putString("authkey", it).apply()
                },
                label = stringResource(R.string.first_start_authkey_label),
                modifier = Modifier.fillMaxWidth()
            )

            // Decided before the first login, because the coordination server
            // binds the node to the OS it registers with (ProfileHostinfo).
            var honestHostinfo by remember { mutableStateOf(profilePrefs.getBoolean(ProfileHostinfo.KEY, false)) }
            val setHonest: (Boolean) -> Unit = {
                honestHostinfo = it
                profilePrefs.edit().putBoolean(ProfileHostinfo.KEY, it).apply()
            }
            val honestHelp = remember { mutableStateOf(false) }
            val tv = isTv
            val honestRow: @Composable (Modifier) -> Unit = { row ->
                Row(
                    modifier = row,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // The row is the remote's stop; the box inside it would be a second one.
                    Checkbox(checked = honestHostinfo, onCheckedChange = setHonest, modifier = Modifier.tvNoFocus())
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_honest_hostinfo_title), style = MaterialTheme.typography.bodyMedium)
                        HelpText(stringResource(R.string.honest_hostinfo_checkbox_desc), expanded = honestHelp, inClickableRow = tv)
                    }
                }
            }
            if (tv) {
                // One stop, which unfolds its own explanation once the remote rests on it: the
                // explanation alone was a second stop inside the row, and showed no ring there.
                ExplainedRow(onClick = { setHonest(!honestHostinfo) }, help = honestHelp, color = Color.Transparent) {
                    honestRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp))
                }
            } else {
                honestRow(Modifier.fillMaxWidth().clickable { setHonest(!honestHostinfo) })
            }

            // The hint is about a login page that does not load: nothing to say without one.
            if (browserSignIn) Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.first_start_key_oauth_hint_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        HelpText(
                            stringResource(R.string.first_start_key_oauth_hint_desc),
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SlidePermissions() {
    val context = LocalContext.current
    var hasNotificationPermission by remember { mutableStateOf(notificationsAllowed(context)) }

    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var isBatteryIgnored by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else true
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    SlideContainer(
        icon = Icons.Default.Lock,
        title = stringResource(R.string.first_start_permissions_title),
        description = stringResource(R.string.first_start_permissions_desc)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_perm_notif_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                launcher.tryLaunch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(stringResource(R.string.onboarding_perm_grant))
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isBatteryIgnored && Feature.BATTERY_OPTIMISATION_PROMPT.offered()) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.onboarding_perm_battery_title),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.onboarding_perm_battery_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Button(
                            onClick = {
                                if (context.tryStartActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
                                    Toast.makeText(context, context.getString(R.string.onboarding_perm_battery_toast), Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, context.getString(R.string.onboarding_perm_battery_error), Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(stringResource(R.string.onboarding_perm_battery_configure))
                        }
                    }
                }
            }
        }
    }
}
