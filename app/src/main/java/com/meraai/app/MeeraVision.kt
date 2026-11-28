package com.meraai.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object MeeraVision {
    @Volatile var latestJpeg: ByteArray? = null
    @Volatile var latestAt: Long = 0L
    @Volatile var cameraOn: Boolean = false
    @Volatile var tabCameraMounted: Boolean = false

    val frontCameraFlow = MutableStateFlow(false)
    val cameraEnabledFlow = MutableStateFlow(true)
    val autoOverlayVisibleFlow = MutableStateFlow(false)
    val autoStatusFlow = MutableStateFlow("LIVE VISION")

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(28, TimeUnit.SECONDS)
        .build()

    fun openAutoCamera(front: Boolean, statusLabel: String = "LIVE VISION") {
        frontCameraFlow.value = front
        cameraEnabledFlow.value = true
        autoStatusFlow.value = statusLabel
        if (!tabCameraMounted) {
            autoOverlayVisibleFlow.value = true
        }
    }

    fun closeAutoCamera() {
        autoOverlayVisibleFlow.value = false
        if (!tabCameraMounted) {
            cameraEnabledFlow.value = false
            cameraOn = false
        }
    }

    fun toggleCameraVisionTool(ctx: Context, enable: Boolean, cameraSide: String = "auto"): ToolExecutionResult {
        val side = cameraSide.trim().lowercase(Locale.ROOT)
        if (!enable) {
            closeAutoCamera()
            return ToolExecutionResult(
                true, "toggle_camera_vision",
                "ठीक है, मैंने कैमरा विज़न बंद कर दिया है।",
                "VERIFIED_SUCCESS: Camera vision turned OFF."
            )
        }
        val useFront = when {
            side.contains("front") || side.contains("selfie") || side.contains("फ्रंट") -> true
            side.contains("back") || side.contains("rear") || side.contains("बैक") -> false
            else -> frontCameraFlow.value
        }
        bringMeeraToForegroundIfNeeded(ctx)
        openAutoCamera(useFront, if (useFront) "FRONT VISION" else "BACK VISION")
        val camLabel = if (useFront) "फ्रंट कैमरा" else "बैक कैमरा"
        val hasPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return if (!hasPerm) {
            ToolExecutionResult(
                true, "toggle_camera_vision",
                "मैंने कैमरा पैनल खोल दिया है, कृपया स्क्रीन पर कैमरा अनुमति (Allow) दे दीजिए।",
                "PARTIAL: Opened camera panel, waiting for Android CAMERA permission."
            )
        } else {
            ToolExecutionResult(
                true, "toggle_camera_vision",
                "मैंने $camLabel विज़न चालू कर दिया है! अब मैं लाइव देख रही हूँ।",
                "VERIFIED_SUCCESS: Live camera vision ON (camera=${if (useFront) "front" else "back"})."
            )
        }
    }

    fun lookThroughCameraTool(
        ctx: Context,
        cameraSide: String = "front",
        question: String = "Describe clearly what you see right now.",
        keepOpen: Boolean = true
    ): ToolExecutionResult {
        val sideLow = cameraSide.trim().lowercase(Locale.ROOT)
        val qLow = question.trim().lowercase(Locale.ROOT)
        val useFront = when {
            sideLow.contains("front") || sideLow.contains("selfie") || sideLow.contains("फ्रंट") -> true
            sideLow.contains("back") || sideLow.contains("rear") || sideLow.contains("बैक") -> false
            listOf("mujhe", "mera", "main", "face", "chehra", "मुझे", "मेरा", "मैं", "चेहरा", "देख पा रही", "dekh pa rahi", "see me").any { qLow.contains(it) } -> true
            else -> false
        }

        bringMeeraToForegroundIfNeeded(ctx)
        val hasPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        openAutoCamera(useFront, if (useFront) "SCANNING (FRONT)..." else "SCANNING (BACK)...")
        if (!hasPerm) {
            return ToolExecutionResult(
                true, "look_through_camera",
                "कैमरा देखने के लिए पहले स्क्रीन पर 'अनुमति दें' बटन दबाकर कैमरा परमिशन दे दीजिए!",
                "FAILED: Android CAMERA permission not granted yet. Camera card opened for user permission."
            )
        }

        val requestedAt = System.currentTimeMillis()
        var frameBytes: ByteArray? = null
        val maxWaitAt = requestedAt + 3800L
        while (System.currentTimeMillis() < maxWaitAt) {
            val ts = latestAt
            val jpg = latestJpeg
            if (jpg != null && jpg.isNotEmpty() && ts >= requestedAt) {
                frameBytes = jpg
                break
            }
            try { Thread.sleep(150) } catch (_: Exception) { break }
        }
        if (frameBytes == null) {
            val recent = latestJpeg
            if (recent != null && recent.isNotEmpty() && (System.currentTimeMillis() - latestAt) <= 6000L) {
                frameBytes = recent
            }
        }

        if (frameBytes == null) {
            autoStatusFlow.value = if (useFront) "FRONT VISION" else "BACK VISION"
            return ToolExecutionResult(
                true, "look_through_camera",
                "मैंने कैमरा चालू कर दिया है, फ्रेम लोड होने में एक सेकंड लगा — अब बताइए क्या देखना है?",
                "PARTIAL: Camera opened but fresh frame timed out; live stream is now active."
            )
        }

        autoStatusFlow.value = "ANALYZING..."
        val analysis = analyzeFrameWithGemini(ctx, frameBytes, question, useFront)
        autoStatusFlow.value = if (useFront) "FRONT VISION • LIVE" else "BACK VISION • LIVE"
        if (!keepOpen) {
            closeAutoCamera()
        }

        val camTag = if (useFront) "front" else "back"
        return if (analysis.isNotBlank()) {
            MeeraDebugLog.log("AUTO_VISION", "($camTag) -> ${analysis.take(180)}")
            ToolExecutionResult(
                true, "look_through_camera",
                analysis,
                "VERIFIED_SUCCESS: Live $camTag camera captured and analyzed. What you see right now: $analysis"
            )
        } else {
            ToolExecutionResult(
                true, "look_through_camera",
                "मैंने कैमरा खोल दिया है, लाइव फ्रेम आ रहे हैं—एक बार फिर पूछिए।",
                "PARTIAL: Camera active ($camTag), vision text fallback empty."
            )
        }
    }

    private fun bringMeeraToForegroundIfNeeded(ctx: Context) {
        try {
            val activePkg = MeeraAccessibilityService.instance?.getActivePackage().orEmpty()
            if (activePkg.isNotBlank() && activePkg != ctx.packageName) {
                val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    ctx.startActivity(launch)
                    try { Thread.sleep(450) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun analyzeFrameWithGemini(ctx: Context, jpeg: ByteArray, question: String, isFront: Boolean): String {
        val apiKey = Prefs(ctx).apiKey
        if (apiKey.isBlank()) return ""
        val sideDesc = if (isFront) "front selfie camera facing the user" else "rear camera showing what is in front of the user"
        val prompt = "You are Meera AI looking live through the phone's $sideDesc. " +
            "User asked: \"$question\". " +
            "Answer accurately, honestly, and naturally in 1 to 2 short conversational Hinglish sentences based ONLY on what is visibly clear in this camera image. No markdown or emojis."

        val body = JSONObject().put(
            "contents", JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts", JSONArray()
                        .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64(jpeg))))
                        .put(JSONObject().put("text", prompt))
                )
            )
        )

        for (model in listOf("gemini-flash-lite-latest", "gemini-3.5-flash", "gemini-3.8-flash")) {
            try {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val j = JSONObject(resp.body!!.string())
                    val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (parts != null) {
                        for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                    }
                    val out = sb.toString().trim()
                    if (out.isNotBlank()) return out
                }
            } catch (e: Exception) {
                MeeraDebugLog.log("AUTO_VISION", "Model $model failed: ${e.message}")
            }
        }
        return ""
    }
}

private val VBorder = Color(0xFF26355E)
private val VMint = Color(0xFF2EE6A8)

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
            if (now - last >= 900L) {
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
                bmp.compress(Bitmap.CompressFormat.JPEG, 72, bos)
                onFrame(bos.toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }
}

@Composable
fun CameraCard(
    vm: VoiceViewModel,
    modifier: Modifier = Modifier,
    compactOverlay: Boolean = false,
    onCloseOverlay: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { findActivity(ctx) }
    var hasCam by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val front by MeeraVision.frontCameraFlow.collectAsState()
    val on by MeeraVision.cameraEnabledFlow.collectAsState()
    val statusBadge by MeeraVision.autoStatusFlow.collectAsState()
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
        } else {
            MeeraVision.cameraOn = false
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
            .border(1.2.dp, if (compactOverlay) VMint.copy(alpha = 0.75f) else VBorder, RoundedCornerShape(22.dp))
    ) {
        if (hasCam && on) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().padding(if (compactOverlay) 14.dp else 26.dp)) {
                val s = (if (compactOverlay) 22.dp else 34.dp).toPx()
                val w = 2.6.dp.toPx()
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
                Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (!hasCam) "कैमरा चालू करने के लिए अनुमति दें" else "कैमरा बंद है",
                    color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center
                )
                if (!hasCam) {
                    Box(
                        Modifier.padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(VMint)
                            .clickable { permLauncher.launch(Manifest.permission.CAMERA) }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) { Text("अनुमति दें", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color(0xAA0B1020),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "● ${if (compactOverlay) statusBadge else "MEERA VISION"}",
                    color = VMint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(0xAA000000))
                        .clickable { MeeraVision.frontCameraFlow.value = !front },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Cameraswitch, contentDescription = "Switch Camera", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(0xAA000000))
                        .clickable { MeeraVision.cameraEnabledFlow.value = !on },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (on) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Toggle", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                if (onCloseOverlay != null) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Color(0xCCDC2626))
                            .clickable(onClick = onCloseOverlay),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close Vision", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun MeeraAutoVisionOverlay(vm: VoiceViewModel) {
    val visible by MeeraVision.autoOverlayVisibleFlow.collectAsState()
    if (!visible || MeeraVision.tabCameraMounted) return

    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(focusable = false)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 56.dp, start = 14.dp, end = 14.dp)
        ) {
            CameraCard(
                vm = vm,
                modifier = Modifier.fillMaxWidth().height(275.dp),
                compactOverlay = true,
                onCloseOverlay = { MeeraVision.closeAutoCamera() }
            )
        }
    }
}

@Composable
fun VisionCameraTab(vm: VoiceViewModel) {
    DisposableEffect(Unit) {
        MeeraVision.tabCameraMounted = true
        MeeraVision.autoOverlayVisibleFlow.value = false
        MeeraVision.cameraEnabledFlow.value = true
        onDispose {
            MeeraVision.tabCameraMounted = false
        }
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        CameraCard(vm, Modifier.fillMaxSize())
        VisionTabContent(vm)
    }
}
