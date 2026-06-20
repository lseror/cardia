package com.serortech.cardia.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.serortech.cardia.vision.CardDetector
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val detector = remember { CardDetector(ctx) }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) { if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA) }

    var analyzing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Boolean?>(null) }

    fun analyze() {
        if (analyzing) return
        analyzing = true
        result = null
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val jpeg = image.toJpegBytes()
                    image.close()
                    scope.launch {
                        try {
                            result = detector.detect(downscaleJpeg(jpeg, 768))
                        } catch (e: Exception) {
                            result = null
                            snackbar.showSnackbar(e.message ?: "Échec de la détection")
                        } finally {
                            analyzing = false
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    analyzing = false
                    scope.launch { snackbar.showSnackbar("Capture impossible.") }
                }
            },
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (!hasPermission) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("CardIA a besoin de la caméra.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Autoriser la caméra")
                    }
                }
            } else {
                AndroidView(
                    factory = { c ->
                        val previewView = PreviewView(c)
                        val future = ProcessCameraProvider.getInstance(c)
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture,
                            )
                        }, ContextCompat.getMainExecutor(c))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize().clickable { analyze() },
                )

                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Réglages", tint = Color.White)
                }

                IndicatorBanner(
                    analyzing = analyzing,
                    result = result,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun IndicatorBanner(analyzing: Boolean, result: Boolean?, modifier: Modifier) {
    val (bg, label) = when {
        analyzing -> MaterialTheme.colorScheme.surfaceVariant to "Analyse…"
        result == true -> Color(0xFF2E7D32) to "Carte détectée"
        result == false -> Color(0xFF616161) to "Aucune carte"
        else -> Color(0x99000000) to "Touchez l'écran pour analyser"
    }
    Box(modifier = modifier.background(bg).padding(20.dp), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (analyzing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            }
            Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun ImageProxy.toJpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

private fun downscaleJpeg(jpeg: ByteArray, maxEdge: Int): ByteArray {
    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
    val largest = maxOf(bmp.width, bmp.height)
    if (largest <= maxEdge) return jpeg
    val scale = maxEdge.toFloat() / largest
    val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
    if (scaled !== bmp) bmp.recycle()
    scaled.recycle()
    return out.toByteArray()
}
