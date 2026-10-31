package com.cronos.camera.ui


import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview

import android.annotation.SuppressLint
import android.hardware.camera2.CaptureRequest
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.MediaStoreOutputOptions
import androidx.core.util.Consumer
import com.cronos.camera.enhance.VideoCaptureTuning
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.cronos.camera.enhance.AdaptiveBurst
import com.cronos.camera.enhance.BurstMerger
import com.cronos.camera.enhance.DaylightEnhancer
import com.cronos.camera.enhance.HdrBlend
import com.cronos.camera.enhance.MotionGate
import com.cronos.camera.enhance.HdrHighlightRecover
import com.cronos.camera.enhance.PortraitEffect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.keyCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CaptureMode { PHOTO, PORTRAIT, NIGHT, VIDEO }

private sealed class Screen {
    data object Camera : Screen()
    data object Gallery : Screen()
    data class Review(val original: Bitmap, val enhanced: Bitmap) : Screen()
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CronosApp() {
    val context = LocalContext.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val audioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
    val strength = 0.85f
    var processing by remember { mutableStateOf(false) }
    var burstStatus by remember { mutableStateOf<String?>(null) }
    var useFront by remember { mutableStateOf(false) }
    var captureMode by remember { mutableStateOf(CaptureMode.PHOTO) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
        if (!audioPermission.status.isGranted) audioPermission.launchPermissionRequest()
    }

    when (val s = screen) {
        is Screen.Gallery -> {
            GalleryScreen(onBack = { screen = Screen.Camera })
        }
        is Screen.Camera -> {
            if (cameraPermission.status.isGranted) {
                CameraBody(
                    useFront = useFront,
                    processing = processing,
                    burstStatus = burstStatus,
                    audioGranted = audioPermission.status.isGranted,
                    captureMode = captureMode,
                    onCaptureModeChange = { captureMode = it },
                    onFlip = { useFront = !useFront },
                    onOpenGallery = { screen = Screen.Gallery },
                    onBurstProgress = { burstStatus = it },
                    onCapturedFiles = { files ->
                        processing = true
                        burstStatus = "Merging…"
                        scope.launch {
                            try {
                                val frames = withContext(Dispatchers.IO) {
                                    files.map { decodeAndOrient(it, mirrorHorizontal = useFront) }
                                }
                                // Last file may be short-exposure (EC -1); keep aside for highlights
                                val shortFrame = if (frames.size >= 3) frames.last() else null
                                val mergeInputs = if (frames.size >= 3) frames.dropLast(1) else frames
                                val selected = withContext(Dispatchers.Default) {
                                    AdaptiveBurst.selectFrames(mergeInputs)
                                }
                                val merged: Bitmap = withContext(Dispatchers.Default) {
                                    BurstMerger.merge(selected)
                                }
                                val original: Bitmap = withContext(Dispatchers.Default) {
                                    HdrHighlightRecover.recover(merged, shortFrame)
                                }
                                frames.forEach { bmp ->
                                    if (bmp !== original && bmp !== merged) runCatching { bmp.recycle() }
                                }
                                val enhanced = withContext(Dispatchers.Default) {
                                    val str = when (captureMode) {
                                        CaptureMode.NIGHT -> (strength + 0.12f).coerceAtMost(1f)
                                        CaptureMode.PORTRAIT -> strength * 0.9f
                                        else -> strength
                                    }
                                    var e = DaylightEnhancer.enhance(
                                        original,
                                        str,
                                        singleFrame = selected.size <= 1
                                    )
                                    if (captureMode == CaptureMode.PORTRAIT) {
                                        e = PortraitEffect.apply(e, blurStrength = 0.88f)
                                    }
                                    e
                                }
                                screen = Screen.Review(original, enhanced)
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Capture failed", Toast.LENGTH_SHORT).show()
                            } finally {
                                processing = false
                                burstStatus = null
                                files.forEach { runCatching { it.delete() } }
                            }
                        }
                    }
                )
            } else {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Cronos", color = Color(0xFFE8B86D), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (cameraPermission.status.shouldShowRationale)
                            "Camera permission is required."
                        else "Allow camera access to start.",
                        color = Color(0xFFB0BEC5)
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { cameraPermission.launchPermissionRequest() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8B86D))
                    ) {
                        Text("Grant camera", color = Color(0xFF1A1208))
                    }
                }
            }
        }
        is Screen.Review -> {
            ReviewBody(
                original = s.original,
                enhanced = s.enhanced,
                processing = processing,
                onRetake = {
                    runCatching { s.original.recycle() }
                    runCatching { s.enhanced.recycle() }
                    screen = Screen.Camera
                },
                onSave = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { saveToGallery(context, s.enhanced) }
                        Toast.makeText(
                            context,
                            if (ok) "Saved to Pictures/Cronos" else "Save failed",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (ok) {
                            runCatching { s.original.recycle() }
                            runCatching { s.enhanced.recycle() }
                            screen = Screen.Camera
                        }
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
private object UltraWideLens {
    // Poco X3 Pro: 48MP main, 8MP UW 119°, 2MP macro, 2MP depth
    fun cameraSelector(): CameraSelector = buildSelector(preferUltra = true)
    fun mainSelector(): CameraSelector = buildSelector(preferUltra = false)

    private fun buildSelector(preferUltra: Boolean): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { cameraInfos ->
                data class Cand(
                    val info: androidx.camera.core.CameraInfo,
                    val focal: Float,
                    val sensorW: Int
                )
                val cands = cameraInfos.mapNotNull { info ->
                    try {
                        val c2 = Camera2CameraInfo.from(info)
                        val focals = c2.getCameraCharacteristic(
                            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                        ) ?: return@mapNotNull null
                        val minF = focals.minOrNull() ?: return@mapNotNull null
                        val size = c2.getCameraCharacteristic(
                            CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE
                        )
                        val sw = size?.width ?: 0
                        // Exclude macro/depth (~2MP)
                        if (sw in 1 until 2000) return@mapNotNull null
                        Cand(info, minF, sw)
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (cands.isEmpty()) return@addCameraFilter cameraInfos
                val picked = if (preferUltra) {
                    cands.minByOrNull { it.focal }!!
                } else {
                    cands.maxByOrNull { it.sensorW } ?: cands.maxByOrNull { it.focal }!!
                }
                listOf(picked.info)
            }
            .build()
    }
}



@OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun CameraBody(
    useFront: Boolean,
    processing: Boolean,
    burstStatus: String?,
    audioGranted: Boolean,
    captureMode: CaptureMode,
    onCaptureModeChange: (CaptureMode) -> Unit,
    onFlip: () -> Unit,
    onOpenGallery: () -> Unit = {},
    onCapturedFiles: (List<File>) -> Unit,
    onBurstProgress: (String?) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    // 0=off 1=auto 2=on
    var flashMode by remember { mutableStateOf(0) }
    // 0, 3, 5 seconds
    var timerSec by remember { mutableStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var isVideoMode by remember { mutableStateOf(captureMode == CaptureMode.VIDEO) }
    var zoomRatio by remember { mutableStateOf(1.0f) }
    var useUltraWide by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(true) }
    var minZoom by remember { mutableStateOf(0.6f) }
    var maxZoom by remember { mutableStateOf(8f) }
    var isRecording by remember { mutableStateOf(false) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var videoCaptureUseCase by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    LaunchedEffect(flashMode, imageCapture) {
        imageCapture?.flashMode = when (flashMode) {
            1 -> ImageCapture.FLASH_MODE_AUTO
            2 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }
    LaunchedEffect(boundCamera) {
        val cam = boundCamera ?: return@LaunchedEffect
        runCatching {
            val zs = cam.cameraInfo.zoomState.value
            if (zs != null) {
                minZoom = zs.minZoomRatio
                maxZoom = zs.maxZoomRatio
                // Prefer starting near 0.6 if hardware allows, else min
                // Start at 1× when possible; only use ultra if hardware supports < 1
                val start = when {
                    minZoom < 0.95f -> 1f.coerceIn(minZoom, maxZoom)
                    else -> 1f.coerceIn(minZoom, maxZoom)
                }
                zoomRatio = start
                cam.cameraControl.setZoomRatio(start)
            }
        }
    }
    LaunchedEffect(zoomRatio, boundCamera) {
        boundCamera?.cameraControl?.setZoomRatio(
            zoomRatio.coerceIn(minZoom, maxZoom)
        )
    }
        LaunchedEffect(captureMode) {
        isVideoMode = captureMode == CaptureMode.VIDEO
        if (captureMode == CaptureMode.PORTRAIT) {
            zoomRatio = 2f.coerceIn(minZoom, maxZoom)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(useUltraWide, useFront, captureMode) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                // Rebind when mode / camera / flash changes
                val modeVideo = captureMode == CaptureMode.VIDEO
                val front = useFront
                val flash = flashMode
                val ultraWide = useUltraWide
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val ultra = useUltraWide && !front
                    val selector = when {
                        front -> CameraSelector.DEFAULT_FRONT_CAMERA
                        ultra -> UltraWideLens.cameraSelector()
                        else -> UltraWideLens.mainSelector()
                    }
                    try {
                        provider.unbindAll()
                        @Suppress("UNUSED_EXPRESSION") ultraWide // rebind when ultra toggles
                        if (modeVideo) {
                            val qualitySelector = QualitySelector.fromOrderedList(
                                listOf(Quality.FHD, Quality.HD, Quality.SD),
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                            )
                            val recorder = Recorder.Builder()
                                .setQualitySelector(qualitySelector)
                                // ~12 Mbps FHD — cleaner than default on many devices
                                .setTargetVideoEncodingBitRate(12_000_000)
                                .build()
                            val videoCapture = VideoCapture.Builder(recorder)
                                .setVideoStabilizationEnabled(true)
                                .build()
                            videoCaptureUseCase = videoCapture
                            imageCapture = null
                            boundCamera = provider.bindToLifecycle(
                                lifecycleOwner, selector, preview, videoCapture
                            )
                            VideoCaptureTuning.applyStabilizationAndColor(boundCamera)
                        } else {
                            videoCaptureUseCase = null
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            imageCapture = capture
                            capture.flashMode = when (flash) {
                                1 -> ImageCapture.FLASH_MODE_AUTO
                                2 -> ImageCapture.FLASH_MODE_ON
                                else -> ImageCapture.FLASH_MODE_OFF
                            }
                            boundCamera = provider.bindToLifecycle(
                                lifecycleOwner, selector, preview, capture
                            )
                        }
                    } catch (_: Exception) {
                        boundCamera = null
                        videoCaptureUseCase = null
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )


        // Rule-of-thirds grid
        }
        if (showGrid) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val c = Color(0xFF00F2FE).copy(alpha = 0.18f)
                val stroke = 1.2f
                drawLine(c, androidx.compose.ui.geometry.Offset(size.width / 3f, 0f), androidx.compose.ui.geometry.Offset(size.width / 3f, size.height), stroke)
                drawLine(c, androidx.compose.ui.geometry.Offset(2f * size.width / 3f, 0f), androidx.compose.ui.geometry.Offset(2f * size.width / 3f, size.height), stroke)
                drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height / 3f), androidx.compose.ui.geometry.Offset(size.width, size.height / 3f), stroke)
                drawLine(c, androidx.compose.ui.geometry.Offset(0f, 2f * size.height / 3f), androidx.compose.ui.geometry.Offset(size.width, 2f * size.height / 3f), stroke)
            }
        }

        // Top brand + mode carousel (minimal futuristic)
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(Color(0xFF00F2FE).copy(alpha = 0.12f), CircleShape)
                            .border(1.dp, Color(0xFF00F2FE).copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(8.dp).background(Color(0xFF00F2FE), CircleShape))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "CRONOS",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            when (captureMode) {
                                CaptureMode.VIDEO -> "Video · FHD · stabilized"
                                CaptureMode.PORTRAIT -> "Portrait · subject mask · bokeh"
                                CaptureMode.NIGHT -> "Night · multi-frame denoise"
                                else -> "Photo · computational"
                            },
                            color = Color(0xFF94A3B8),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // Mode glass pill
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x990F1420), RoundedCornerShape(50))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf(
                    CaptureMode.PHOTO to "PHOTO",
                    CaptureMode.PORTRAIT to "PORTRAIT",
                    CaptureMode.NIGHT to "NIGHT",
                    CaptureMode.VIDEO to "VIDEO"
                ).forEach { (mode, label) ->
                    val selected = captureMode == mode
                    Text(
                        label,
                        color = if (selected) Color(0xFF07090E) else Color(0xFF94A3B8),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .background(
                                if (selected) Color(0xFF00F2FE) else Color.Transparent,
                                RoundedCornerShape(50)
                            )
                            .clickable(enabled = !processing && !isRecording) {
                                onCaptureModeChange(mode)
                                isVideoMode = mode == CaptureMode.VIDEO
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            if (burstStatus != null) {
                Text(
                    burstStatus!!,
                    color = Color(0xFF00F2FE),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (countdown != null) {
                Text(
                    "${countdown}",
                    color = Color(0xFF00F2FE),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp)
                )
            }
        }

        // Zoom glass pill
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 156.dp)
                .background(Color(0x990F1420), RoundedCornerShape(50))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            data class ZoomOpt(val label: String, val ultra: Boolean, val ratio: Float)
            val opts = listOf(
                ZoomOpt("0.6×", true, 1f),
                ZoomOpt("1×", false, 1f),
                ZoomOpt("2×", false, 2f),
                ZoomOpt("5×", false, 5f)
            )
            opts.forEach { opt ->
                val selected = if (opt.ultra) useUltraWide && !useFront
                else !useUltraWide && kotlin.math.abs(zoomRatio - opt.ratio.coerceIn(minZoom, maxZoom)) < 0.2f
                // Prefer exclusive: among non-ultra, closest ratio
                val nonUltraSelected = !useUltraWide && !opt.ultra &&
                    opts.filter { !it.ultra }.minByOrNull {
                        kotlin.math.abs(it.ratio.coerceIn(minZoom, maxZoom) - zoomRatio)
                    }?.ratio == opt.ratio
                val on = if (opt.ultra) (useUltraWide && !useFront) else nonUltraSelected
                Text(
                    opt.label,
                    color = if (on) Color(0xFF07090E) else Color(0xFF94A3B8),
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .background(
                            if (on) Color(0xFF00F2FE) else Color.Transparent,
                            RoundedCornerShape(50)
                        )
                        .clickable(enabled = !processing && !isRecording && !(opt.ultra && useFront)) {
                            if (opt.ultra) {
                                useUltraWide = true
                                zoomRatio = 1f
                            } else {
                                useUltraWide = false
                                zoomRatio = opt.ratio.coerceIn(minZoom, maxZoom)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 28.dp, start = 16.dp, end = 16.dp)
                .background(Color(0xA60A0D14), RoundedCornerShape(28.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    flashMode = (flashMode + 1) % 3
                }, enabled = !processing && !useFront) {
                    Icon(
                        when (flashMode) {
                            1 -> Icons.Default.FlashAuto
                            2 -> Icons.Default.FlashOn
                            else -> Icons.Default.FlashOff
                        },
                        contentDescription = "Flash",
                        tint = Color(0xFFE2E8F0),
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(onClick = {
                    timerSec = when (timerSec) {
                        0 -> 3
                        3 -> 5
                        else -> 0
                    }
                }, enabled = !processing) {
                    if (timerSec == 0) {
                        Icon(Icons.Default.Timer, contentDescription = "Timer", tint = Color.White, modifier = Modifier.size(28.dp))
                    } else {
                        Text("${timerSec}s", color = Color(0xFFE8B86D), fontWeight = FontWeight.Bold)
                    }
                }
                IconButton(onClick = onFlip, enabled = !processing && !isRecording) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Flip", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
            // Right side: open library
            IconButton(onClick = onOpenGallery, enabled = !processing && !isRecording) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.size(30.dp))
            }

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f))
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(when {
                        processing -> Color.Gray
                        isRecording -> Color(0xFFE53935)
                        else -> Color(0xFFE8B86D)
                    })
                    .clickable(enabled = !processing) {
                        if (isVideoMode) {
                            val vc = videoCaptureUseCase
                            if (vc == null) return@clickable
                            if (isRecording) {
                                activeRecording?.stop()
                                activeRecording = null
                                isRecording = false
                                return@clickable
                            }
                            // Start recording to MediaStore
                            val name = "CRONOS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
                                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Cronos")
                                }
                            }
                            val output = MediaStoreOutputOptions.Builder(
                                context.contentResolver,
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            ).setContentValues(contentValues).build()
                            val pending = vc.output.prepareRecording(context, output)
                            @SuppressLint("MissingPermission")
                            val prepared = if (audioGranted) {
                                pending.withAudioEnabled()
                            } else {
                                pending
                            }
                            activeRecording = prepared.start(ContextCompat.getMainExecutor(context)) { event ->
                                when (event) {
                                    is VideoRecordEvent.Start -> {
                                        isRecording = true
                                        VideoCaptureTuning.applyStabilizationAndColor(boundCamera)
                                        onBurstProgress("REC · stabilized")
                                    }
                                    is VideoRecordEvent.Finalize -> {
                                        isRecording = false
                                        activeRecording = null
                                        onBurstProgress(null)
                                        if (event.hasError()) {
                                            Toast.makeText(context, "Video error", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Video saved", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                            return@clickable
                        }
                        val capture = imageCapture ?: return@clickable
                        val camera = boundCamera
                        fun runBurst() {
                            val files = mutableListOf<File>()
                            val motion = MotionGate(context)
                            motion.start()
                            fun finish() {
                                motion.stop()
                                runCatching {
                                    camera?.cameraControl?.setExposureCompensationIndex(0)
                                }
                                ContextCompat.getMainExecutor(context).execute {
                                    onBurstProgress(null)
                                    onCapturedFiles(files.toList())
                                }
                            }
                            fun captureAt(index: Int, maxFrames: Int) {
                                if (index >= maxFrames) {
                                    finish()
                                    return
                                }
                                if (index > 0 && motion.isTooMuchMotion(1.6f)) {
                                    ContextCompat.getMainExecutor(context).execute {
                                        onBurstProgress("Hold steady…")
                                    }
                                    finish()
                                    return
                                }
                                ContextCompat.getMainExecutor(context).execute {
                                    onBurstProgress("Capturing ${index + 1}/$maxFrames…")
                                }
                                // Last frame slightly underexposed for highlight recovery
                                if (index == maxFrames - 1) {
                                    runCatching {
                                        val exp = camera?.cameraInfo?.exposureState
                                        if (exp != null && exp.isExposureCompensationSupported) {
                                            val range = exp.exposureCompensationRange
                                            val step = (-1).coerceIn(range.lower, range.upper)
                                            camera.cameraControl.setExposureCompensationIndex(step)
                                            Thread.sleep(90)
                                        }
                                    }
                                } else {
                                    runCatching {
                                        camera?.cameraControl?.setExposureCompensationIndex(0)
                                    }
                                }
                                val file = File(context.cacheDir, "cronos_${System.currentTimeMillis()}_$index.jpg")
                                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                                capture.takePicture(
                                    options,
                                    executor,
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                            files.add(file)
                                            captureAt(index + 1, maxFrames)
                                        }
                                        override fun onError(exception: ImageCaptureException) {
                                            motion.stop()
                                            if (files.isNotEmpty()) {
                                                ContextCompat.getMainExecutor(context).execute {
                                                    onBurstProgress(null)
                                                    onCapturedFiles(files.toList())
                                                }
                                            } else {
                                                ContextCompat.getMainExecutor(context).execute {
                                                    Toast.makeText(context, exception.message ?: "Capture error", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                            val framesWanted = when (captureMode) {
                                CaptureMode.NIGHT -> 5
                                CaptureMode.PORTRAIT -> 2
                                else -> 3
                            }
                            captureAt(0, framesWanted)
                        }
                        if (timerSec > 0) {
                            scope.launch {
                                for (t in timerSec downTo 1) {
                                    countdown = t
                                    kotlinx.coroutines.delay(1000)
                                }
                                countdown = null
                                runBurst()
                            }
                        } else {
                            runBurst()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (processing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(34.dp), strokeWidth = 3.dp)
                }
            }

            Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun ReviewBody(
    original: Bitmap,
    enhanced: Bitmap,
    processing: Boolean,
    onRetake: () -> Unit,
    onSave: () -> Unit
) {
    var showOriginal by remember { mutableStateOf(false) }
    val shown = if (showOriginal) original else enhanced

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Review", color = Color(0xFFE8B86D), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (showOriginal) "Original" else "Enhanced", color = Color(0xFF00F2FE))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (showOriginal) "Original · release for Enhanced"
            else "Enhanced · long-press for Original",
            color = Color(0xFF64748B),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            showOriginal = true
                            try {
                                awaitRelease()
                            } finally {
                                showOriginal = false
                            }
                        }
                    )
                },
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onRetake,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3441))
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Retake")
            }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8B86D))
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF1A1208))
                Spacer(Modifier.size(8.dp))
                Text("Save", color = Color(0xFF1A1208))
            }
        }
    }
}

private fun decodeAndOrient(file: File, mirrorHorizontal: Boolean = false): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val maxSide = 2048
    var sample = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    while (maxDim / sample > maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: error("Could not decode image")
    val exif = ExifInterface(file.absolutePath)
    val orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orient) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees != 0f) {
        val m = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        bmp = rotated
    }
    if (mirrorHorizontal) {
        val m = Matrix().apply {
            postScale(-1f, 1f, bmp.width / 2f, bmp.height / 2f)
        }
        val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (flipped != bmp) bmp.recycle()
        bmp = flipped
    }
    return bmp
}

private fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
    return try {
        val name = "CRONOS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cronos")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)
        } ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    } catch (_: Exception) {
        false
    }
}
