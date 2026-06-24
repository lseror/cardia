package com.serortech.cardia.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.serortech.cardia.net.CaptureClient
import com.serortech.cardia.net.DebugClient
import com.serortech.cardia.settings.SettingsStore
import com.serortech.cardia.vision.CardDetector
import com.serortech.cardia.vision.CardOutlineDetector
import com.serortech.cardia.vision.CardQuad
import com.serortech.cardia.vision.OutlineResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val detector = remember { CardDetector(ctx) }
    val outlineDetector = remember { CardOutlineDetector() }
    val store = remember { SettingsStore(ctx) }
    val lastDebugPost = remember { AtomicLong(0L) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

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
    var diag by remember { mutableStateOf<OutlineResult?>(null) }
    var frames by remember { mutableStateOf(0) }

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
                            val small = downscaleJpeg(jpeg, 768)
                            // 1) Sauvegarde S3 AVANT identification.
                            try {
                                CaptureClient.upload(store.serverUrl, store.licenseKey, small)
                            } catch (e: Exception) {
                                snackbar.showSnackbar("Sauvegarde S3 : ${e.message}")
                            }
                            // 2) Identification (reste affichée jusqu'à la prochaine).
                            result = detector.detect(small)
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
                        val previewView = PreviewView(c).apply {
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                        }
                        val future = ProcessCameraProvider.getInstance(c)
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder()
                                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                .build()
                                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                            val analysis = ImageAnalysis.Builder()
                                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .build()
                                .also { ia ->
                                    ia.setAnalyzer(analysisExecutor) { image ->
                                        try {
                                            val now = System.currentTimeMillis()
                                            val post = now - lastDebugPost.get() >= 1000L
                                            if (post) lastDebugPost.set(now)
                                            val r = outlineDetector.detect(image, encodeDebug = post)
                                            diag = r
                                            frames++
                                            if (post) DebugClient.post(store.serverUrl, store.licenseKey, frames, r)
                                        } finally {
                                            image.close()
                                        }
                                    }
                                }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture,
                                analysis,
                            )
                        }, ContextCompat.getMainExecutor(c))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize().clickable { analyze() },
                )

                CardOutlineOverlay(quads = diag?.quads ?: emptyList(), modifier = Modifier.fillMaxSize())

                DebugHud(
                    diag = diag,
                    frames = frames,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )

                diag?.frameColor?.let { c ->
                    FrameColorChip(c, modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 8.dp))
                }

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

/** Trace chaque parallélogramme (bord carte + cadre interne) : vert + milieux rouges + médianes + valeurs. */
@Composable
private fun CardOutlineOverlay(quads: List<CardQuad>, modifier: Modifier) {
    if (quads.isEmpty()) return
    Canvas(modifier = modifier) {
        val srcW = quads[0].srcWidth
        val srcH = quads[0].srcHeight
        val s = min(size.width / srcW, size.height / srcH)
        val dx = (size.width - srcW * s) / 2f
        val dy = (size.height - srcH * s) / 2f
        fun map(p: android.graphics.PointF) = Offset(p.x * s + dx, p.y * s + dy)

        quads.forEachIndexed { idx, quad ->
            if (quad.corners.size != 4 || quad.midpoints.size != 4) return@forEachIndexed
            val pts = quad.corners.map { map(it) }
            val mids = quad.midpoints.map { map(it) }

            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                close()
            }
            drawPath(path, color = Color(0xFF00E676), style = Stroke(width = 6f))
            drawLine(Color(0xCCFFEB3B), mids[0], mids[2], strokeWidth = 3f)
            drawLine(Color(0xCCFFEB3B), mids[1], mids[3], strokeWidth = 3f)
            mids.forEach { drawCircle(Color(0xFFFF1744), radius = 10f, center = it) }

            val cx = pts.map { it.x }.average().toFloat()
            val cy = pts.map { it.y }.average().toFloat() + idx * 44f
            val txt = "A=${quad.segA.toInt()}  B=${quad.segB.toInt()}  r=${"%.2f".format(quad.ratio)}"
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 38f
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                }
                canvas.nativeCanvas.drawText(txt, cx, cy, paint)
            }
        }
    }
}

/** Pastille couleur du cadre de la carte : carré de la couleur + code hexa. */
@Composable
private fun FrameColorChip(color: Int, modifier: Modifier) {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    Row(
        modifier = modifier.background(Color(0xAA000000)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(28.dp).background(Color(r, g, b)))
        Text("#%02X%02X%02X".format(r, g, b), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** HUD de debug : état OpenCV / frames analysées / contours / meilleur candidat. */
@Composable
private fun DebugHud(diag: OutlineResult?, frames: Int, modifier: Modifier) {
    val text = if (diag == null) {
        "init…  f$frames"
    } else buildString {
        append(if (diag.ocvLoaded) "OCV✓" else "OCV✗")
        append("  f").append(frames)
        append("  cnt").append(diag.contourCount)
        append("  q").append(diag.quads.size)
        append("  ").append(diag.bestAreaPct).append("%")
        append("  r").append(String.format("%.2f", diag.bestRatio))
        diag.error?.takeIf { it.isNotBlank() }?.let { append("  ⚠ ").append(it.take(40)) }
    }
    Box(modifier = modifier.background(Color(0xAA000000)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, color = Color(0xFFFFEB3B), style = MaterialTheme.typography.labelSmall)
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
