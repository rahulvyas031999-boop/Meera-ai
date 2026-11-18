package com.meraai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

data class ReelVideoMetadata(
    val uri: Uri,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sampleFramesJpeg: List<ByteArray> = emptyList()
)

data class ReelCaptionResult(
    val caption: String,
    val hashtags: List<String>
) {
    val fullPostText: String
        get() = buildString {
            append(caption.trim())
            if (hashtags.isNotEmpty()) {
                append("\n\n")
                append(hashtags.joinToString(" ") { tag ->
                    val clean = tag.trim()
                    if (clean.startsWith("#")) clean else "#$clean"
                })
            }
        }
}

data class ReelDraft(
    val videoUri: Uri,
    val metadata: ReelVideoMetadata,
    val captionResult: ReelCaptionResult? = null
)

sealed interface ReelPostStatus {
    data object Idle : ReelPostStatus
    data object PickingVideo : ReelPostStatus
    data class AnalyzingVideo(val videoUri: Uri) : ReelPostStatus
    data class GeneratingCaption(val videoUri: Uri) : ReelPostStatus
    data class ReadyToShare(val draft: ReelDraft) : ReelPostStatus
    data class SharedToInstagram(val draft: ReelDraft, val copiedToClipboard: Boolean) : ReelPostStatus
    data class Error(val message: String, val throwable: Throwable? = null) : ReelPostStatus
}

object MeeraReelBridge {
    @Volatile var onLaunchPicker: ((userHint: String) -> Unit)? = null
}

object MeeraReelManager {
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val INSTAGRAM_REEL_ACTION = "com.instagram.share.ADD_TO_REEL"
    private const val MAX_SAMPLE_FRAMES = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow<ReelPostStatus>(ReelPostStatus.Idle)
    val status: StateFlow<ReelPostStatus> = _status.asStateFlow()

    private var videoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    @Volatile private var pendingUserHint: String = ""

    fun initInActivity(activity: ComponentActivity) {
        val appCtx = activity.applicationContext
        videoPickerLauncher = activity.registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    prepareAndShareReel(appCtx, uri, pendingUserHint, autoTriggerInstagram = true)
                }
            } else {
                _status.value = ReelPostStatus.Error("कोई वीडियो सेलेक्ट नहीं किया गया।")
                MeeraDebugLog.log("REEL", "User cancelled video picker")
            }
        }

        MeeraReelBridge.onLaunchPicker = { hint ->
            launchGalleryVideoPicker(hint)
        }
    }

    fun startReelWorkflow(ctx: Context, userHint: String = ""): ToolExecutionResult {
        val launcher = MeeraReelBridge.onLaunchPicker
        if (launcher == null) {
            return ToolExecutionResult(
                true, "post_instagram_reel",
                "गैलरी पिकर अभी तैयार नहीं है। कृपया Meera ऐप के होम स्क्रीन से दोबारा कोशिश करें।",
                "FAILED: MeeraReelManager.initInActivity not initialized"
            )
        }
        if (!isPackageInstalled(ctx, INSTAGRAM_PACKAGE)) {
            return ToolExecutionResult(
                true, "post_instagram_reel",
                "फोन में Instagram ऐप इंस्टॉल नहीं मिला।",
                "FAILED: com.instagram.android not installed"
            )
        }
        Handler(Looper.getMainLooper()).post {
            launcher.invoke(userHint)
        }
        return ToolExecutionResult(
            true, "post_instagram_reel",
            "मैंने गैलरी खोल दी है! अपना Reel वीडियो चुन लें — मैं खुद शानदार कैप्शन और हैशटैग बनाकर Instagram पर खोल दूँगी।",
            "VERIFIED_SUCCESS: Launched gallery video picker for Instagram Reel workflow."
        )
    }

    fun launchGalleryVideoPicker(userHint: String = "") {
        pendingUserHint = userHint.trim()
        val launcher = videoPickerLauncher
        if (launcher == null) {
            _status.value = ReelPostStatus.Error("VideoPicker रजिस्टर नहीं है।")
            return
        }
        _status.value = ReelPostStatus.PickingVideo
        launcher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }

    suspend fun prepareAndShareReel(
        ctx: Context,
        videoUri: Uri,
        userHint: String = "",
        autoTriggerInstagram: Boolean = true
    ): Result<ReelDraft> = runCatching {
        val taskId = MeeraBackgroundMonitor.startTask(
            title = "Instagram Reel Auto-Post",
            category = "Reel Workflow",
            modelUsed = "gemini-3.8-flash",
            initialStep = "वीडियो के फ्रेम्स और मेटाडेटा पढ़ रही हूँ...",
            initialPercent = 20
        )

        _status.value = ReelPostStatus.AnalyzingVideo(videoUri)
        val metadata = extractVideoMetadata(ctx, videoUri)

        MeeraBackgroundMonitor.updateProgress(taskId, 55, "वीडियो देखकर आकर्षक कैप्शन और हैशटैग लिख रही हूँ...")
        _status.value = ReelPostStatus.GeneratingCaption(videoUri)
        val captionResult = generateCaptionWithGemini(ctx, metadata, userHint)

        val draft = ReelDraft(
            videoUri = videoUri,
            metadata = metadata,
            captionResult = captionResult
        )
        _status.value = ReelPostStatus.ReadyToShare(draft)

        MeeraAgentFactory.onDeliverToChat?.invoke(
            "Instagram Reel",
            "🎬 [Reel Caption & Hashtags]:\n\n${captionResult.fullPostText}"
        )

        if (autoTriggerInstagram) {
            MeeraBackgroundMonitor.updateProgress(taskId, 85, "Instagram Reel शेयर स्क्रीन खोल रही हूँ...")
            val shared = shareToInstagramReel(ctx, draft)
            MeeraBackgroundMonitor.completeTask(
                taskId,
                shared,
                if (shared) "100% पूरा — Instagram Reel खुली है और कैप्शन क्लिपबोर्ड में कॉपी है!" else "Instagram खोलने में दिक्कत आई",
                captionResult.fullPostText
            )
        } else {
            MeeraBackgroundMonitor.completeTask(taskId, true, "100% पूरा — कैप्शन तैयार है!", captionResult.fullPostText)
        }
        draft
    }.onFailure { error ->
        MeeraDebugLog.log("REEL", "prepareAndShareReel error: ${error.message}")
        _status.value = ReelPostStatus.Error(
            message = error.localizedMessage ?: "Reel तैयार करने में समस्या आई।",
            throwable = error
        )
    }

    fun shareToInstagramReel(ctx: Context, draft: ReelDraft): Boolean {
        val fullCaption = draft.captionResult?.fullPostText.orEmpty()
        val copied = if (fullCaption.isNotBlank()) {
            copyToClipboard(ctx, "Meera Reel Caption", fullCaption)
        } else {
            false
        }

        val shareUri = ensureShareableUri(ctx, draft.videoUri)
        if (!isPackageInstalled(ctx, INSTAGRAM_PACKAGE)) {
            _status.value = ReelPostStatus.Error("फोन में Instagram ऐप इंस्टॉल नहीं है।")
            return false
        }

        val reelIntent = Intent(INSTAGRAM_REEL_ACTION).apply {
            setDataAndType(shareUri, "video/*")
            setPackage(INSTAGRAM_PACKAGE)
            putExtra(Intent.EXTRA_TEXT, fullCaption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            ctx.grantUriPermission(INSTAGRAM_PACKAGE, shareUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}

        val pm = ctx.packageManager
        val targetIntent = if (reelIntent.resolveActivity(pm) != null) {
            reelIntent
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_TEXT, fullCaption)
                setPackage(INSTAGRAM_PACKAGE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return try {
            ctx.startActivity(targetIntent)
            _status.value = ReelPostStatus.SharedToInstagram(draft = draft, copiedToClipboard = copied)
            true
        } catch (e: Exception) {
            _status.value = ReelPostStatus.Error("Instagram Reel खोलने में त्रुटि: ${e.localizedMessage}", e)
            false
        }
    }

    private suspend fun generateCaptionWithGemini(
        ctx: Context,
        metadata: ReelVideoMetadata,
        userHint: String
    ): ReelCaptionResult = withContext(Dispatchers.IO) {
        val apiKey = Prefs(ctx).apiKey
        if (apiKey.isBlank()) {
            return@withContext ReelCaptionResult(
                caption = "New Reel vibes ✨",
                hashtags = listOf("#Reels", "#Trending", "#InstagramReels", "#Explore")
            )
        }

        val durSec = (metadata.durationMs / 1000L).coerceAtLeast(1L)
        val prompt = buildString {
            append("You are an expert Instagram Reels content creator. Analyze the attached video frames ")
            append("(video duration: ${durSec}s, resolution: ${metadata.width}x${metadata.height}). ")
            if (userHint.isNotBlank()) append("User topic/hint: \"$userHint\". ")
            append("Write an engaging, catchy Instagram Reel caption (natural Hinglish or matching the mood) ")
            append("and 8 to 12 relevant trending hashtags. ")
            append("Reply ONLY with valid JSON: {\"caption\": \"...\", \"hashtags\": [\"#tag1\", \"#tag2\"]}")
        }

        val parts = JSONArray()
        for (jpeg in metadata.sampleFramesJpeg) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                )
            )
        }
        parts.put(JSONObject().put("text", prompt))

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("temperature", 0.7)
            )

        val models = listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-2.5-flash")
        for (m in models) {
            try {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val j = JSONObject(resp.body!!.string())
                        val txt = j.optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text", "")
                            .orEmpty()
                            .trim()
                            .removePrefix("```json")
                            .removePrefix("```")
                            .removeSuffix("```")
                            .trim()
                        if (txt.isNotBlank()) {
                            val parsed = JSONObject(txt)
                            val cap = parsed.optString("caption", "").trim()
                            val arr = parsed.optJSONArray("hashtags")
                            val tags = mutableListOf<String>()
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val t = arr.optString(i, "").trim()
                                    if (t.isNotBlank()) tags.add(t)
                                }
                            }
                            if (cap.isNotBlank()) {
                                return@withContext ReelCaptionResult(cap, tags)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                MeeraDebugLog.log("REEL", "Caption model $m failed: ${e.message}")
            }
        }

        ReelCaptionResult(
            caption = if (userHint.isNotBlank()) "$userHint ✨🔥" else "Moments worth sharing ✨",
            hashtags = listOf("#Reels", "#InstagramReels", "#ViralReels", "#ExplorePage", "#Trending")
        )
    }

    private suspend fun extractVideoMetadata(ctx: Context, videoUri: Uri): ReelVideoMetadata =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ctx, videoUri)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0

                val frames = mutableListOf<ByteArray>()
                if (durationMs > 0L) {
                    val stepUs = (durationMs * 1000L) / (MAX_SAMPLE_FRAMES + 1)
                    for (i in 1..MAX_SAMPLE_FRAMES) {
                        val bmp = retriever.getFrameAtTime(stepUs * i, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (bmp != null) {
                            val scaled = scaleBitmap(bmp, 640)
                            val bos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                            frames.add(bos.toByteArray())
                            if (scaled !== bmp) scaled.recycle()
                            bmp.recycle()
                        }
                    }
                }
                ReelVideoMetadata(videoUri, durationMs, width, height, frames)
            } finally {
                runCatching { retriever.release() }
            }
        }

    private fun scaleBitmap(bmp: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(bmp.width, bmp.height)
        if (maxSide <= maxDim) return bmp
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun ensureShareableUri(ctx: Context, uri: Uri): Uri {
        return if (uri.scheme == "file" && uri.path != null) {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(uri.path!!))
        } else {
            uri
        }
    }

    private fun copyToClipboard(ctx: Context, label: String, text: String): Boolean {
        return runCatching {
            val app = ctx.applicationContext
            Handler(Looper.getMainLooper()).post {
                val cb = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText(label, text))
            }
            true
        }.getOrDefault(false)
    }

    private fun isPackageInstalled(ctx: Context, packageName: String): Boolean {
        return runCatching {
            ctx.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            true
        }.recoverCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }
}