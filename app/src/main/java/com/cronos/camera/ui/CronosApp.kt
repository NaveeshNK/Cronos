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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.cronos.camera.enhance.BurstMerger
import com.cronos.camera.enhance.DaylightEnhancer
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

private sealed class Screen {
    data object Camera : Screen()
    data class Review(val original: Bitmap, val enhanced: Bitmap) : Screen()
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CronosApp() {
    val context = LocalContext.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
    val strength = 0.82f
    var processing by remember { mutableStateOf(false) }
    var burstStatus by remember { mutableStateOf<String?>(null) }
    var useFront by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    when (val s = screen) {
        is Screen.Camera -> {
            if (cameraPermission.status.isGranted) {
                CameraBody(
                    useFront = useFront,
                    processing = processing,
                    burstStatus = burstStatus,
                    onFlip = { useFront = !useFront },
                    onBurstProgress = { burstStatus = it },
                    onCapturedFiles = { files ->
                        processing = true
                        burstStatus = "Merging…"
                        scope.launch {
                            try {
                                val frames = withContext(Dispatchers.IO) {
                                    files.map { decodeAndOrient(it) }
                                }
                                val original = withContext(Dispatchers.Default) {
                                    BurstMerger.merge(frames)
                                }
                                // recycle intermediates except if same ref
                                frames.forEach { if (it != original) runCatching { it.recycle() } }
                                val enhanced = withContext(Dispatchers.Default) {
                                    DaylightEnhancer.enhance(original, strength)
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

@Composable
private fun CameraBody(
    useFront: Boolean,
    processing: Boolean,
    burstStatus: String?,
    onFlip: () -> Unit,
    onCapturedFiles: (List<File>) -> Unit,
    onBurstProgress: (String?) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    imageCapture = capture
                    val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                    } catch (_: Exception) { }
                }, ContextCompat.getMainExecutor(context))
            }
        )


        // Rule-of-thirds grid
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val c = Color.White.copy(alpha = 0.25f)
            val stroke = 1.5f
            drawLine(c, androidx.compose.ui.geometry.Offset(size.width / 3f, 0f), androidx.compose.ui.geometry.Offset(size.width / 3f, size.height), stroke)
            drawLine(c, androidx.compose.ui.geometry.Offset(2f * size.width / 3f, 0f), androidx.compose.ui.geometry.Offset(2f * size.width / 3f, size.height), stroke)
            drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height / 3f), androidx.compose.ui.geometry.Offset(size.width, size.height / 3f), stroke)
            drawLine(c, androidx.compose.ui.geometry.Offset(0f, 2f * size.height / 3f), androidx.compose.ui.geometry.Offset(size.width, 2f * size.height / 3f), stroke)
        }

        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(top = 48.dp, start = 20.dp)
        ) {
            Text("CRONOS", color = Color(0xFFE8B86D), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text("3-frame · motion-safe · Cronos color", color = Color(0xFF90A4AE), style = MaterialTheme.typography.bodySmall)
            if (burstStatus != null) {
                Text(burstStatus, color = Color(0xFFE8B86D), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 36.dp, start = 24.dp, end = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onFlip, enabled = !processing) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Flip", tint = Color.White, modifier = Modifier.size(30.dp))
            }

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f))
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(if (processing) Color.Gray else Color(0xFFE8B86D))
                    .clickable(enabled = !processing) {
                        val capture = imageCapture ?: return@clickable
                        val burstCount = 3
                        val files = mutableListOf<File>()
                        fun captureNext(index: Int) {
                            if (index >= burstCount) {
                                ContextCompat.getMainExecutor(context).execute {
                                    onBurstProgress(null)
                                    onCapturedFiles(files.toList())
                                }
                                return
                            }
                            ContextCompat.getMainExecutor(context).execute {
                                onBurstProgress("Capturing ${index + 1}/$burstCount…")
                            }
                            val file = File(context.cacheDir, "cronos_${System.currentTimeMillis()}_$index.jpg")
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()
                            capture.takePicture(
                                options,
                                executor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        files.add(file)
                                        captureNext(index + 1)
                                    }
                                    override fun onError(exception: ImageCaptureException) {
                                        if (files.isNotEmpty()) {
                                            ContextCompat.getMainExecutor(context).execute {
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
                        captureNext(0)
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
            Text(if (showOriginal) "Original" else "Enhanced", color = Color(0xFF90A4AE))
        }
        Spacer(Modifier.height(12.dp))
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showOriginal = !showOriginal }) {
                Text(if (showOriginal) "Show enhanced" else "Show original", color = Color(0xFF7EB8D4))
            }
        }
        Spacer(Modifier.height(8.dp))
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

private fun decodeAndOrient(file: File): Bitmap {
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
    val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: error("Could not decode image")
    val exif = ExifInterface(file.absolutePath)
    val orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orient) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bmp
    val m = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also {
        if (it != bmp) bmp.recycle()
    }
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
