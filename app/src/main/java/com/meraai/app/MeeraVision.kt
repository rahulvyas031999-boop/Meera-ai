package com.meraai.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

object MeeraVision {
    @Volatile var latestJpeg: ByteArray? = null
    @Volatile var latestAt: Long = 0L
    @Volatile var cameraOn: Boolean = false
}

private val VBorder = Color(0xFF26355E)
private val VMint = Color(0xFF2EE6A8)
private val VMuted = Color(0xFF8A94B8)

private fun findActivity(c: Context): ComponentActivity? {
    var cur: Context? = c
    while (cur is ContextWrapper) {
        if (cur is ComponentActivity) return cur
        cur = cur.baseContext
    }
    return null
}

private class FrameGrabber(private val onFrame: (ByteArray) -> Unit) : ImageAnalysis.Analyzer {
    private var last = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - last >= 1000L) {
                last = now
                val rot = image.imageInfo.rotationDegrees
                var bmp: Bitmap = image.toBitmap()
                if (rot != 0) {
                    val m = Matrix()
                    m.postRotate(rot.toFloat())
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                val scale = 768f / maxOf(bmp.width, bmp.height).toFloat()
                if (scale < 1f) {
                    bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                }
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                onFrame(bos.toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }
}

@Composable
fun CameraCard(vm: VoiceViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { findActivity(ctx) }
    var hasCam by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var front by remember { mutableStateOf(false) }
    var on by remember { mutableStateOf(true) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> hasCam = ok }
    val previewView = remember { PreviewView(ctx) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(hasCam, front, on) {
        var provider: ProcessCameraProvider? = null
        var disposed = false
        if (hasCam && on && activity != null) {
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                try {
                    if (!disposed) {
                        val p = future.get()
                        provider = p
                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor, FrameGrabber { jpg ->
                            MeeraVision.latestJpeg = jpg
                            MeeraVision.latestAt = System.currentTimeMillis()
                            vm.sendCameraFrame(jpg)
                        })
                        val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                        p.unbindAll()
                        p.bindToLifecycle(activity, sel, preview, analysis)
                        MeeraVision.cameraOn = true
                    }
                } catch (_: Exception) {
                }
            }, ContextCompat.getMainExecutor(ctx))
        }
        onDispose {
            disposed = true
            MeeraVision.cameraOn = false
            try { provider?.unbindAll() } catch (_: Exception) {}
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black)
            .border(1.dp, VBorder, RoundedCornerShape(22.dp))
    ) {
        if (hasCam && on) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().padding(26.dp)) {
                val s = 34.dp.toPx()
                val w = 3.dp.toPx()
                val r = size.width
                val b = size.height
                drawLine(VMint, Offset(0f, 0f), Offset(s, 0f), w)
                drawLine(VMint, Offset(0f, 0f), Offset(0f, s), w)
                drawLine(VMint, Offset(r, 0f), Offset(r - s, 0f), w)
                drawLine(VMint, Offset(r, 0f), Offset(r, s), w)
                drawLine(VMint, Offset(0f, b), Offset(s, b), w)
                drawLine(VMint, Offset(0f, b), Offset(0f, b - s), w)
                drawLine(VMint, Offset(r, b), Offset(r - s, b), w)
                drawLine(VMint, Offset(r, b), Offset(r, b - s), w)
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (!hasCam) "कैमरा चालू करने के लिए अनुमति दें" else "कैमरा बंद है",
                    color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center
                )
                if (!hasCam) {
                    Box(
                        Modifier.padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(VMint)
                            .clickable { permLauncher.launch(Manifest.permission.CAMERA) }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) { Text("📷 अनुमति दें", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("● MEERA VISION", color = VMint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color(0x99000000))
                        .clickable { front = !front },
                    contentAlignment = Alignment.Center
                ) { Text("🔄", fontSize = 16.sp) }
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color(0x99000000))
                        .clickable { on = !on },
                    contentAlignment = Alignment.Center
                ) { Text(if (on) "⏸" else "▶", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
fun VisionCameraTab(vm: VoiceViewModel) {
    Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        CameraCard(vm, Modifier.fillMaxSize())
        VisionTabContent(vm)
    }
}
