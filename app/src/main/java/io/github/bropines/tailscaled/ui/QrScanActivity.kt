package io.github.bropines.tailscaled.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.Window
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.core.PredictiveBackContainer
import io.github.bropines.tailscaled.core.QrDecoder
import io.github.bropines.tailscaled.core.QrFrameAnalyzer
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import io.github.bropines.tailscaled.ui.theme.TailSocksTheme
import io.github.bropines.tailscaled.ui.theme.findActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The QR scanner: the camera, or a picture from the gallery, and the text of
 * the first code found handed back to the screen that opened it ([ScanQrCode],
 * [rememberQrScanner]). What the text means is that screen's business
 * (ScannedCode); the scanner itself opens nothing.
 *
 * The camera permission is asked for as the scanner opens, never earlier, with
 * the reason on screen behind the system's dialog. Refused, the screen says
 * how to grant it — again, or in the app's settings once Android stops asking
 * — and offers the gallery, which needs no permission at all: the system
 * photo picker hands over the one picture chosen. A device without a camera
 * gets the gallery alone. Frames and pictures are decoded on the device by
 * ZXing (QrDecoder); nothing is recorded, kept or sent.
 */
class QrScanActivity : ComponentActivity() {
    companion object {
        internal const val EXTRA_TEXT = "text"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The camera runs under the system bars; QrScanScreen sets their icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            TailSocksTheme {
                QrScanScreen(
                    onBack = { finish() },
                    onScanned = { text ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                        finish()
                    }
                )
            }
        }
    }
}

/** Opens [QrScanActivity]; the result is the code's text, or null when the user left without one. */
object ScanQrCode : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(context, QrScanActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        intent?.takeIf { resultCode == Activity.RESULT_OK }?.getStringExtra(QrScanActivity.EXTRA_TEXT)
}

/**
 * A function that opens the scanner. [onScanned] gets the text of the code it
 * read; leaving the scanner without one calls nothing.
 */
@Composable
fun rememberQrScanner(onScanned: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanQrCode) { text -> if (text != null) onScanned(text) }
    return remember(launcher) { { launcher.launch(Unit) } }
}

/** Where the camera stands; every state but [GRANTED] shows why there is no picture and what to do. */
internal enum class CameraAccess {
    /** Not granted yet: the system's dialog is up, or the user dismissed it. */
    ASKING,
    /** Refused; Android would ask again. */
    DENIED,
    /** Refused for good: only the app's settings can grant it now. */
    BLOCKED,
    /** The device has no camera. */
    NO_CAMERA,
    /** Granted, but the camera would not start (held by another app, or broken). */
    FAILED,
    GRANTED,
}

/** Over the camera: dark enough for white text, light enough to aim through. */
private val Scrim = Color.Black.copy(alpha = 0.6f)

@Composable
private fun QrScanScreen(onBack: () -> Unit, onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    fun cameraAllowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    var access by rememberSaveable {
        mutableStateOf(
            when {
                !hasCamera -> CameraAccess.NO_CAMERA
                cameraAllowed() -> CameraAccess.GRANTED
                else -> CameraAccess.ASKING
            }
        )
    }
    fun rationale() = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    // A refusal for good and a dialog dismissed on the first ask both leave
    // rationale() false. What tells them apart: the system's dialog pauses this
    // screen, and a request Android turns down by itself comes back without a
    // pause; a second "Deny" (or "Don't ask again") turns rationale() from true
    // to false. Saved, as a rotation under the dialog recreates the screen.
    var dialogShown by rememberSaveable { mutableStateOf(false) }
    var rationaleBefore by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { dialogShown = true }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access = when {
            granted -> CameraAccess.GRANTED
            rationale() -> CameraAccess.DENIED
            !dialogShown || rationaleBefore -> CameraAccess.BLOCKED
            else -> CameraAccess.DENIED
        }
    }
    fun ask() {
        dialogShown = false
        rationaleBefore = rationale()
        askCamera.launch(Manifest.permission.CAMERA)
    }
    // Asked as the scanner opens, and once: a recreated screen (a rotation)
    // does not ask again, and the answer reaches the new one all the same.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (access == CameraAccess.ASKING && !asked) {
            asked = true
            ask()
        }
    }
    // Back from the app's settings with the camera allowed there — or, restored
    // after the process was killed, with it taken away meanwhile.
    LifecycleResumeEffect(Unit) {
        val allowed = cameraAllowed()
        if ((access == CameraAccess.DENIED || access == CameraAccess.BLOCKED) && allowed) access = CameraAccess.GRANTED
        else if (access == CameraAccess.GRANTED && !allowed) access = CameraAccess.DENIED
        onPauseOrDispose { }
    }

    // One code, once: the camera may find it again in the next frame.
    var delivered by remember { mutableStateOf(false) }
    fun deliver(text: String) {
        if (delivered) return
        delivered = true
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onScanned(text)
    }

    var reading by remember { mutableStateOf(false) }
    val notFound = stringResource(R.string.qr_scan_not_found)
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            val text = withContext(Dispatchers.Default) { QrDecoder.decodeImage(context, uri) }
            reading = false
            if (text != null) deliver(text) else Toast.makeText(context, notFound, Toast.LENGTH_SHORT).show()
        }
    }
    val settingsFailed = stringResource(R.string.perm_open_failed)

    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    // Bumped by "Try again": the camera is bound afresh.
    var attempt by remember { mutableIntStateOf(0) }

    // Light icons over the camera; the theme's over the explanations.
    val lightBars = access != CameraAccess.GRANTED && MaterialTheme.colorScheme.background.luminance() > 0.5f
    if (!LocalInspectionMode.current) SideEffect {
        val window = activity?.window ?: return@SideEffect
        window.clearBarColors()
        WindowCompat.getInsetsController(window, view).run {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }

    PredictiveBackContainer(onBack = onBack, popsInAppState = false) {
        QrScanContent(
            access = access,
            torch = if (camera?.cameraInfo?.hasFlashUnit() == true) torchOn else null,
            reading = reading,
            onBack = onBack,
            onAllow = ::ask,
            onSettings = {
                val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                runCatching { context.startActivity(details) }
                    .onFailure { Toast.makeText(context, settingsFailed, Toast.LENGTH_LONG).show() }
            },
            onRetry = {
                attempt++
                access = CameraAccess.GRANTED
            },
            onGallery = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onTorch = { on -> camera?.cameraControl?.enableTorch(on) },
            camera = {
                key(attempt) {
                    CameraFeed(
                        onCode = ::deliver,
                        onCamera = { camera = it },
                        onTorch = { torchOn = it },
                        onFailed = { access = CameraAccess.FAILED }
                    )
                }
            }
        )
    }
}

/** Transparent bars: the theme paints them its background, which would cover the camera. */
@Suppress("DEPRECATION")
private fun Window.clearBarColors() {
    statusBarColor = android.graphics.Color.TRANSPARENT
    navigationBarColor = android.graphics.Color.TRANSPARENT
}

/**
 * The back camera (the front one on a device with no other), its preview, and
 * every frame offered to [QrFrameAnalyzer] at about 720p — enough for a code
 * across a room, light on the battery. Bound to this screen's lifecycle, so the
 * camera stops whenever the screen is not in front: the gallery, the settings.
 */
@Composable
private fun CameraFeed(onCode: (String) -> Unit, onCamera: (Camera?) -> Unit, onTorch: (Boolean) -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCode by rememberUpdatedState(onCode)
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    LaunchedEffect(lifecycleOwner) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context.applicationContext) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse { onFailed(); return@LaunchedEffect }
        val selector = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
            .firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) }
            ?: run { onFailed(); return@LaunchedEffect }
        val preview = Preview.Builder().build().apply { setSurfaceProvider { request = it } }
        val main = ContextCompat.getMainExecutor(context)
        val analysisThread = Executors.newSingleThreadExecutor()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(analysisThread, QrFrameAnalyzer { text -> main.execute { latestOnCode(text) } }) }
        val bound = runCatching { provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis) }
        val cam = bound.getOrNull()
        if (cam == null) {
            analysisThread.shutdown()
            onFailed()
            return@LaunchedEffect
        }
        onCamera(cam)
        cam.cameraInfo.torchState.observe(lifecycleOwner) { onTorch(it == TorchState.ON) }
        try {
            awaitCancellation()
        } finally {
            cam.cameraInfo.torchState.removeObservers(lifecycleOwner)
            provider.unbind(preview, analysis)
            analysis.clearAnalyzer()
            analysisThread.shutdown()
            onCamera(null)
        }
    }
    request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
}

/**
 * What the scanner shows, apart from the camera so a preview can stand a
 * picture in for it: with the camera, the picture under a viewfinder and the
 * gallery at the bottom; without, why not and what to do instead.
 */
@Composable
internal fun QrScanContent(
    access: CameraAccess,
    torch: Boolean?,
    reading: Boolean,
    onBack: () -> Unit,
    onAllow: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onGallery: () -> Unit,
    onTorch: (Boolean) -> Unit,
    camera: @Composable () -> Unit,
) {
    if (access == CameraAccess.GRANTED) CameraLayout(torch, reading, onBack, onGallery, onTorch, camera)
    else NoCameraLayout(access, reading, onBack, onAllow, onSettings, onRetry, onGallery)
}

@Composable
private fun CameraLayout(
    torch: Boolean?,
    reading: Boolean,
    onBack: () -> Unit,
    onGallery: () -> Unit,
    onTorch: (Boolean) -> Unit,
    camera: @Composable () -> Unit,
) {
    // How far the bar and the controls reach in, measured below and read by
    // the viewfinder as it draws, in the same frame: it keeps clear of both.
    val clearTop = remember { mutableIntStateOf(0) }
    val clearBottom = remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // A phone on its side has no height to spare for the second line.
        val roomy = maxHeight >= 520.dp
        camera()
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Viewfinder(Modifier.fillMaxSize(), clearTop = { clearTop.intValue }, clearBottom = { clearBottom.intValue })
                AppTopBar(
                    title = stringResource(R.string.qr_scan_title),
                    onBack = onBack,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    ),
                    actions = {
                        if (torch != null) {
                            IconButton(onClick = { onTorch(!torch) }) {
                                Icon(
                                    if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = stringResource(if (torch) R.string.qr_scan_torch_off else R.string.qr_scan_torch_on)
                                )
                            }
                        }
                    }
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.qr_scan_hint),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    if (roomy) {
                        HelpText(
                            stringResource(R.string.qr_scan_hint_more),
                            color = Color.White.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 480.dp)
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    FilledTonalButton(onClick = onGallery, enabled = !reading) { GalleryLabel(reading) }
                }
            }
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val row = Constraints(minWidth = width, maxWidth = width, maxHeight = height)
            val bar = measurables[1].measure(row)
            val controls = measurables[2].measure(row)
            clearTop.intValue = bar.height
            clearBottom.intValue = controls.height
            val finder = measurables[0].measure(Constraints.fixed(width, height))
            layout(width, height) {
                finder.place(0, 0)
                bar.place(0, 0)
                controls.place(0, height - controls.height)
            }
        }
    }
}

/**
 * The camera's picture dimmed but for a rounded square, its corners marked.
 * The square sits in the middle of the picture, where the lens points, and
 * moves up or shrinks only as far as it must to stay clear of the top
 * [clearTop] and the bottom [clearBottom] pixels.
 */
@Composable
private fun Viewfinder(modifier: Modifier, clearTop: () -> Int, clearBottom: () -> Int) {
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val margin = 12.dp.toPx()
        val roomTop = clearTop() + margin
        val roomBottom = size.height - clearBottom() - margin
        val side = minOf(size.width * 0.8f, roomBottom - roomTop, 280.dp.toPx()).coerceAtLeast(0f)
        val left = (size.width - side) / 2
        val top = ((size.height - side) / 2).coerceAtMost(roomBottom - side).coerceAtLeast(roomTop)
        val right = left + side
        val bottom = top + side
        val r = 24.dp.toPx()
        val arm = 40.dp.toPx()
        drawRect(Scrim)
        drawRoundRect(Color.Transparent, Offset(left, top), androidx.compose.ui.geometry.Size(side, side), CornerRadius(r), blendMode = BlendMode.Clear)
        val corners = Path().apply {
            moveTo(left, top + arm); lineTo(left, top + r)
            arcTo(Rect(left, top, left + 2 * r, top + 2 * r), 180f, 90f, false); lineTo(left + arm, top)
            moveTo(right - arm, top); lineTo(right - r, top)
            arcTo(Rect(right - 2 * r, top, right, top + 2 * r), 270f, 90f, false); lineTo(right, top + arm)
            moveTo(right, bottom - arm); lineTo(right, bottom - r)
            arcTo(Rect(right - 2 * r, bottom - 2 * r, right, bottom), 0f, 90f, false); lineTo(right - arm, bottom)
            moveTo(left + arm, bottom); lineTo(left + r, bottom)
            arcTo(Rect(left, bottom - 2 * r, left + 2 * r, bottom), 90f, 90f, false); lineTo(left, bottom - arm)
        }
        drawPath(corners, Color.White, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun GalleryLabel(reading: Boolean) {
    if (reading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    else Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(stringResource(R.string.qr_scan_gallery))
}

/** Without the camera: what stands in the way, the one thing that would clear it, and the gallery. */
@Composable
private fun NoCameraLayout(
    access: CameraAccess,
    reading: Boolean,
    onBack: () -> Unit,
    onAllow: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onGallery: () -> Unit,
) {
    val (icon: ImageVector, title: Int, text: Int) = when (access) {
        CameraAccess.DENIED -> Triple(Icons.Default.NoPhotography, R.string.qr_scan_denied_title, R.string.qr_scan_denied_text)
        CameraAccess.BLOCKED -> Triple(Icons.Default.NoPhotography, R.string.qr_scan_blocked_title, R.string.qr_scan_blocked_text)
        CameraAccess.NO_CAMERA -> Triple(Icons.Default.NoPhotography, R.string.qr_scan_no_camera_title, R.string.qr_scan_no_camera_text)
        CameraAccess.FAILED -> Triple(Icons.Default.NoPhotography, R.string.qr_scan_failed_title, R.string.qr_scan_failed_text)
        else -> Triple(Icons.Default.PhotoCamera, R.string.qr_scan_camera_title, R.string.qr_scan_camera_text)
    }
    // The one way back to the camera, where there is one.
    val primary: Pair<Int, () -> Unit>? = when (access) {
        CameraAccess.ASKING, CameraAccess.DENIED -> R.string.qr_scan_allow to onAllow
        CameraAccess.BLOCKED -> R.string.qr_scan_open_settings to onSettings
        CameraAccess.FAILED -> R.string.qr_scan_retry to onRetry
        else -> null
    }
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.qr_scan_title), onBack = onBack) }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            Column(
                // Centred while it fits, scrolled when it does not (a phone on its side).
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(56.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(28.dp))
                        }
                        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        HelpText(
                            stringResource(text),
                            lines = 3,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.size(4.dp))
                        if (primary != null) {
                            Button(onClick = primary.second, modifier = Modifier.fillMaxWidth()) { Text(stringResource(primary.first)) }
                            OutlinedButton(onClick = onGallery, enabled = !reading, modifier = Modifier.fillMaxWidth()) { GalleryLabel(reading) }
                        } else {
                            Button(onClick = onGallery, enabled = !reading, modifier = Modifier.fillMaxWidth()) { GalleryLabel(reading) }
                        }
                    }
                }
            }
        }
    }
}
