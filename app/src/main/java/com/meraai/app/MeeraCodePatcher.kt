package com.meraai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

val DEFAULT_STUDIO_MODELS = listOf(
    "gemini-4-hydrogen",
    "gemini-3.1-pro-preview",
    "gemini-3.8-flash",
    "gemini-3.5-flash"
)

data class CodingModelHealth(
    val modelId: String,
    val label: String,
    val working: Boolean,
    val validOnKey: Boolean = true,
    val latencyMs: Long = 0L,
    val note: String = ""
)

data class PendingHydrogenJob(
    val instruction: String,
    val extraContext: String = "",
    val attempts: Int = 1,
    val lastReason: String = ""
)

data class FilePatchPreview(
    val filePath: String,
    val action: String,
    val linesAdded: Int,
    val linesRemoved: Int,
    val diffSnippet: String,
    val finalContent: String,
    val valid: Boolean,
    val validationNote: String
)

data class PatcherUiState(
    val busy: Boolean = false,
    val statusText: String = "",
    val activeModelText: String = "",
    val commitMessage: String = "Update Meera AI via In-App Studio",
    val previews: List<FilePatchPreview> = emptyList(),
    val validationErrors: List<String> = emptyList(),
    val pushedCommitSha: String = "",
    val previousCommitSha: String = "",
    val lastPushedFiles: List<String> = emptyList(),
    val buildStatus: String = "",
    val buildRunNumber: Int = 0,
    val buildSummaryText: String = "",
    val failureCompilerError: String = "",
    val failureReportText: String = "",
    val failureReportUri: Uri? = null,
    val studioErrorTitle: String = "",
    val studioErrorReportText: String = "",
    val studioErrorReportUri: Uri? = null,
    val studioErrorSavedName: String = "",
    val pythonOutput: String = "",
    val curatedModels: List<String> = DEFAULT_STUDIO_MODELS,
    val verifiedHealthMap: Map<String, CodingModelHealth> = emptyMap(),
    val verifyingModels: Boolean = false,
    val verifySummary: String = "",
    val hydrogenBusyPrompt: Boolean = false,
    val hydrogenWaitingAuto: Boolean = false,
    val hydrogenWaitStatus: String = "",
    val pendingHydrogenJob: PendingHydrogenJob? = null
)

object MeeraCodePatcher {
    private const val OWNER = "rahulvyas031999-boop"
    private const val REPO = "Meera-ai"
    private const val BRANCH = "v2-test"
    private const val PREF_NAME = "meera_code_patcher_prefs"
    private const val KEY_PREV_SHA = "prev_head_sha"
    private const val KEY_CURATED_MODELS = "curated_coding_models_json"
    private const val KEY_VERIFIED_HEALTH = "verified_coding_health_json"

    val screenVisible = MutableStateFlow(false)
    val state = MutableStateFlow(PatcherUiState())
    private val cancelWaitFlag = AtomicBoolean(false)
    private var prefsLoaded = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val hydrogenHttp = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .build()

    private val probeHttp = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .build()

    fun openScreen() {
        screenVisible.value = true
    }

    fun closeScreen() {
        screenVisible.value = false
    }

    fun clearPreview() {
        state.value = state.value.copy(
            previews = emptyList(),
            validationErrors = emptyList(),
            statusText = "",
            activeModelText = "",
            hydrogenBusyPrompt = false,
            studioErrorTitle = "",
            studioErrorReportText = "",
            studioErrorReportUri = null,
            studioErrorSavedName = ""
        )
    }

    fun clearStudioError() {
        state.value = state.value.copy(
            studioErrorTitle = "",
            studioErrorReportText = "",
            studioErrorReportUri = null,
            studioErrorSavedName = ""
        )
    }

    fun friendlyModelLabel(modelId: String): String {
        return when (modelId.trim()) {
            "gemini-4-hydrogen" -> "🧪 Hydrogen"
            "gemini-3.1-pro-preview" -> "👑 3.1-Pro"
            "gemini-3.8-flash" -> "🚀 3.8-Flash"
            "gemini-3.6-flash" -> "⚡ 3.6-Flash"
            "gemini-3.5-flash" -> "🧠 3.5-Flash"
            "gemini-3.5-flash-lite" -> "⚡ 3.5-Lite"
            "gemini-flash-lite-latest" -> "⚡ Flash-Lite"
            else -> modelId.removePrefix("models/").take(22)
        }
    }

    fun ensurePrefsLoaded(ctx: Context) {
        if (prefsLoaded) return
        prefsLoaded = true
        val sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val rawModels = sp.getString(KEY_CURATED_MODELS, null)
        val loadedModels = if (!rawModels.isNullOrBlank()) {
            try {
                val arr = JSONArray(rawModels)
                (0 until arr.length())
                    .mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() && s != "gemini-3.8-flash-thinking" } }
                    .distinct()
                    .take(4)
            } catch (_: Exception) {
                DEFAULT_STUDIO_MODELS
            }
        } else {
            DEFAULT_STUDIO_MODELS
        }.ifEmpty { DEFAULT_STUDIO_MODELS }

        val rawHealth = sp.getString(KEY_VERIFIED_HEALTH, null)
        val loadedHealth = LinkedHashMap<String, CodingModelHealth>()
        if (!rawHealth.isNullOrBlank()) {
            try {
                val arr = JSONArray(rawHealth)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("modelId").trim()
                    if (id.isNotBlank() && id != "gemini-3.8-flash-thinking") {
                        loadedHealth[id] = CodingModelHealth(
                            modelId = id,
                            label = o.optString("label", friendlyModelLabel(id)),
                            working = o.optBoolean("working", false),
                            validOnKey = o.optBoolean("validOnKey", true),
                            latencyMs = o.optLong("latencyMs", 0L),
                            note = o.optString("note", "")
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        state.value = state.value.copy(
            curatedModels = loadedModels,
            verifiedHealthMap = loadedHealth
        )
    }

    private fun saveCuratedModels(ctx: Context, list: List<String>) {
        val clean = list.map { it.trim().removePrefix("models/") }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)
            .ifEmpty { listOf("gemini-4-hydrogen") }
        val arr = JSONArray()
        clean.forEach { arr.put(it) }
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CURATED_MODELS, arr.toString())
            .apply()
        state.value = state.value.copy(curatedModels = clean)
    }

    private fun saveVerifiedHealth(ctx: Context, map: Map<String, CodingModelHealth>) {
        val arr = JSONArray()
        map.values.forEach { h ->
            arr.put(
                JSONObject()
                    .put("modelId", h.modelId)
                    .put("label", h.label)
                    .put("working", h.working)
                    .put("validOnKey", h.validOnKey)
                    .put("latencyMs", h.latencyMs)
                    .put("note", h.note)
            )
        }
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_VERIFIED_HEALTH, arr.toString())
            .apply()
        state.value = state.value.copy(verifiedHealthMap = map)
    }

    fun toggleCuratedModel(ctx: Context, modelId: String) {
        ensurePrefsLoaded(ctx)
        val id = modelId.trim().removePrefix("models/")
        if (id.isBlank()) return
        val cur = state.value.curatedModels.toMutableList()
        if (id in cur) {
            if (cur.size <= 1) {
                Toast.makeText(ctx, "कम से कम 1 कोडिंग मॉडल रखना ज़रूरी है", Toast.LENGTH_SHORT).show()
                return
            }
            cur.remove(id)
        } else {
            if (cur.size >= 4) {
                Toast.makeText(ctx, "अधिकतम 4 कोडिंग मॉडल चुन सकते हैं — पहले किसी एक को हटाएँ", Toast.LENGTH_SHORT).show()
                return
            }
            cur.add(id)
        }
        saveCuratedModels(ctx, cur)
    }

    fun moveModelPriorityUp(ctx: Context, modelId: String) {
        ensurePrefsLoaded(ctx)
        val cur = state.value.curatedModels.toMutableList()
        val idx = cur.indexOf(modelId)
        if (idx > 0) {
            cur.removeAt(idx)
            cur.add(idx - 1, modelId)
            saveCuratedModels(ctx, cur)
        }
    }

    fun verifyModelsOnApiKey(ctx: Context, customModelToCheck: String = "") {
        ensurePrefsLoaded(ctx)
        val apiKey = Prefs(ctx).apiKey
        if (apiKey.isBlank()) {
            Toast.makeText(ctx, "पहले साइड मेन्यू से Gemini API Key डालें", Toast.LENGTH_LONG).show()
            return
        }
        if (state.value.verifyingModels) return

        val bgId = MeeraBackgroundMonitor.startTask(
            "Verify Coding Models",
            "Code Studio Health",
            "API Key Probe",
            "आपकी API Key पर सक्रिय कोडिंग मॉडल्स की जाँच हो रही है...",
            15
        )
        state.value = state.value.copy(
            verifyingModels = true,
            verifySummary = "🔍 API Key पर सभी उपलब्ध कोडिंग मॉडल्स की लाइव जाँच चल रही है..."
        )

        Thread {
            try {
                val discovered = LinkedHashSet<String>()
                val customClean = customModelToCheck.trim().removePrefix("models/")
                if (customClean.isNotBlank()) discovered.add(customClean)
                discovered.addAll(state.value.curatedModels)
                discovered.addAll(DEFAULT_STUDIO_MODELS)

                try {
                    val listReq = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
                        .header("x-goog-api-key", apiKey)
                        .get()
                        .build()
                    probeHttp.newCall(listReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val arr = JSONObject(resp.body!!.string()).optJSONArray("models") ?: JSONArray()
                            val blockedKeywords = listOf(
                                "tts", "audio", "transcribe", "image", "imagen",
                                "veo", "embedding", "aqa", "1.0", "1.5", "2.0", "vision"
                            )
                            for (i in 0 until arr.length()) {
                                val mObj = arr.optJSONObject(i) ?: continue
                                val rawName = mObj.optString("name", "").removePrefix("models/").trim()
                                val methods = mObj.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                                if (rawName.isNotBlank() && methods.contains("generateContent")) {
                                    val low = rawName.lowercase()
                                    if (blockedKeywords.none { low.contains(it) }) {
                                        discovered.add(rawName)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                val candidates = discovered.take(16)
                val resultMap = LinkedHashMap<String, CodingModelHealth>()
                var activeCount = 0

                for ((idx, modelId) in candidates.withIndex()) {
                    val pct = 15 + ((idx + 1) * 80 / candidates.size.coerceAtLeast(1))
                    MeeraBackgroundMonitor.updateProgress(bgId, pct, "Checking $modelId...")
                    val health = probeSingleCodingModel(apiKey, modelId)
                    resultMap[modelId] = health
                    if (health.working) activeCount++
                }

                val sortedMap = LinkedHashMap<String, CodingModelHealth>()
                resultMap.values
                    .sortedWith(compareBy({ !it.working }, { !it.validOnKey }, { it.latencyMs }))
                    .forEach { sortedMap[it.modelId] = it }

                saveVerifiedHealth(ctx, sortedMap)
                if (customClean.isNotBlank() && sortedMap[customClean]?.validOnKey == true && customClean !in state.value.curatedModels) {
                    val updated = (state.value.curatedModels + customClean).take(4)
                    saveCuratedModels(ctx, updated)
                }

                val summary = "✅ जाँच पूरी: ${candidates.size} मॉडल्स में से $activeCount अभी तुरंत सक्रिय (OK) हैं।"
                MeeraBackgroundMonitor.completeTask(bgId, true, summary)
                state.value = state.value.copy(verifyingModels = false, verifySummary = summary)
            } catch (e: Exception) {
                MeeraBackgroundMonitor.completeTask(bgId, false, "Model check failed: ${e.message}")
                state.value = state.value.copy(verifyingModels = false, verifySummary = "❌ जाँच विफल: ${e.message}")
            }
        }.start()
    }

    private fun probeSingleCodingModel(apiKey: String, modelId: String): CodingModelHealth {
        val start = System.currentTimeMillis()
        return try {
            val body = JSONObject()
                .put(
                    "contents", JSONArray().put(
                        JSONObject().put("role", "user").put(
                            "parts", JSONArray().put(JSONObject().put("text", "Reply ONLY with JSON: {\"ok\":true}"))
                        )
                    )
                )
                .put(
                    "generationConfig", JSONObject()
                        .put("responseMimeType", "application/json")
                        .put("temperature", 0.0)
                        .put("maxOutputTokens", 32)
                )
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            probeHttp.newCall(req).execute().use { resp ->
                val ms = System.currentTimeMillis() - start
                if (resp.isSuccessful) {
                    val txt = resp.body?.string().orEmpty()
                    if (txt.contains("ok")) {
                        CodingModelHealth(modelId, friendlyModelLabel(modelId), working = true, validOnKey = true, latencyMs = ms, note = "OK (${ms}ms)")
                    } else {
                        CodingModelHealth(modelId, friendlyModelLabel(modelId), working = false, validOnKey = true, latencyMs = ms, note = "Unexpected JSON")
                    }
                } else {
                    val code = resp.code
                    when (code) {
                        404 -> CodingModelHealth(modelId, friendlyModelLabel(modelId), working = false, validOnKey = false, latencyMs = ms, note = "Not on API (404)")
                        429 -> CodingModelHealth(modelId, friendlyModelLabel(modelId), working = false, validOnKey = true, latencyMs = ms, note = "Quota / Busy (429)")
                        503, 504 -> CodingModelHealth(modelId, friendlyModelLabel(modelId), working = false, validOnKey = true, latencyMs = ms, note = "High Load ($code)")
                        else -> CodingModelHealth(modelId, friendlyModelLabel(modelId), working = false, validOnKey = false, latencyMs = ms, note = "HTTP $code")
                    }
                }
            }
        } catch (_: Exception) {
            val ms = System.currentTimeMillis() - start
            CodingModelHealth(modelId, friendlyModelLabel(modelId), working = false, validOnKey = true, latencyMs = ms, note = "Slow / Timeout")
        }
    }

    private fun recordStudioError(
        app: Context,
        sourceTab: String,
        title: String,
        errorSummary: String,
        rawDetails: String = ""
    ) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            append("========================================================================\n")
            append("MEERA-AI CODE STUDIO ERROR REPORT\n")
            append("========================================================================\n")
            append("Timestamp  : $ts\n")
            append("Source Tab : $sourceTab\n")
            append("Error Title: $title\n\n")
            append("--- 1. ERROR SUMMARY ---\n")
            append(errorSummary.trim()).append("\n\n")
            if (rawDetails.isNotBlank()) {
                append("--- 2. FULL DIAGNOSTIC / OUTPUT DETAILS ---\n")
                append(rawDetails.trim()).append("\n\n")
            }
            append("--- 3. RECENT APP DEBUG LOGS ---\n")
            append(MeeraDebugLog.getFullLog())
        }

        val shareUri = saveReportToCacheForShare(app, "meera_studio_error_report.txt", report.toByteArray(Charsets.UTF_8))
        state.value = state.value.copy(
            studioErrorTitle = title,
            studioErrorReportText = report,
            studioErrorReportUri = shareUri,
            studioErrorSavedName = ""
        )
    }

    fun downloadStudioErrorReportNow(ctx: Context, customContent: String? = null, prefix: String = "meera_error_report") {
        val textToSave = (customContent ?: state.value.studioErrorReportText).ifBlank { state.value.failureReportText }
        if (textToSave.isBlank()) {
            Toast.makeText(ctx, "डाउनलोड करने के लिए कोई एरर रिपोर्ट नहीं है", Toast.LENGTH_SHORT).show()
            return
        }
        val app = ctx.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${prefix}_$stamp.txt"
        val data = textToSave.toByteArray(Charsets.UTF_8)

        Thread {
            var savedOk = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        app.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                        savedOk = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!pubDir.exists()) pubDir.mkdirs()
                    File(pubDir, fileName).writeBytes(data)
                    savedOk = true
                }
            } catch (_: Exception) {}

            val shareUri = saveReportToCacheForShare(app, fileName, data)
            Handler(Looper.getMainLooper()).post {
                if (savedOk) {
                    state.value = state.value.copy(
                        studioErrorSavedName = "Downloads/$fileName",
                        studioErrorReportUri = shareUri ?: state.value.studioErrorReportUri
                    )
                    Toast.makeText(app, "✅ Downloads/$fileName सेव हो गई!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(app, "⚠️ Downloads में सेव नहीं हो पाया, Share बटन का उपयोग करें", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun saveReportToCacheForShare(app: Context, fileName: String, data: ByteArray): Uri? {
        return try {
            val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.cacheDir
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, fileName)
            f.writeBytes(data)
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
        } catch (_: Exception) {
            null
        }
    }

    private fun ghRequest(url: String, token: String): Request.Builder {
        val b = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MeeraAI-Patcher")
        if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
        return b
    }

    private fun fetchLiveFileFromBranch(path: String, token: String): String? {
        val cleanPath = path.trim().trimStart('/')
        val url = "https://api.github.com/repos/$OWNER/$REPO/contents/$cleanPath?ref=$BRANCH"
        http.newCall(ghRequest(url, token).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val j = JSONObject(resp.body!!.string())
            val b64Content = j.optString("content", "").replace("\n", "").replace("\r", "")
            if (b64Content.isBlank()) return ""
            return String(Base64.decode(b64Content, Base64.DEFAULT), Charsets.UTF_8).replace("\r\n", "\n")
        }
    }

    private fun fetchRepoFileList(token: String): List<String> {
        return try {
            val url = "https://api.github.com/repos/$OWNER/$REPO/git/trees/$BRANCH?recursive=1"
            http.newCall(ghRequest(url, token).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONObject(resp.body!!.string()).optJSONArray("tree") ?: return emptyList()
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    if (item.optString("type") == "blob") {
                        val p = item.optString("path")
                        if (p.endsWith(".kt") || p.endsWith(".xml") || p.endsWith(".kts")) out.add(p)
                    }
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun validateSourceCode(filePath: String, code: String): Pair<Boolean, String> {
        if (code.isBlank()) return false to "फ़ाइल खाली है"
        if (Regex("(?m)^\\s*(?://|#|/\\*)\\s*\\.\\.\\.").containsMatchIn(code)) {
            return false to "कोड में अधूरे '...' प्लेसहोल्डर मिले हैं"
        }
        if (filePath.endsWith(".kt") && !code.contains("package com.meraai.app")) {
            return false to "Kotlin फ़ाइल में 'package com.meraai.app' गायब है"
        }
        if (code.count { it == '{' } != code.count { it == '}' }) {
            return false to "Curly braces '{ }' असंतुलित हैं (${code.count { it == '{' }} vs ${code.count { it == '}' }})"
        }
        if (code.count { it == '(' } != code.count { it == ')' }) {
            return false to "Parentheses '( )' असंतुलित हैं (${code.count { it == '(' }} vs ${code.count { it == ')' }})"
        }
        return true to "सिंटैक्स और ब्रैकेट्स 100% सही हैं"
    }

    private fun buildCompactDiffSnippet(oldText: String, newText: String): Triple<Int, Int, String> {
        val oldLines = if (oldText.isEmpty()) emptyList() else oldText.split("\n")
        val newLines = newText.split("\n")
        val oldSet = oldLines.toHashSet()
        val newSet = newLines.toHashSet()
        val removed = oldLines.filter { it.isNotBlank() && it !in newSet }
        val added = newLines.filter { it.isNotBlank() && it !in oldSet }
        val sb = StringBuilder()
        removed.take(8).forEach { sb.append("- ").append(it.trim()).append("\n") }
        if (removed.size > 8) sb.append("... (+${removed.size - 8} more removed lines)\n")
        added.take(12).forEach { sb.append("+ ").append(it.trim()).append("\n") }
        if (added.size > 12) sb.append("... (+${added.size - 12} more added lines)\n")
        if (sb.isEmpty()) sb.append("(Formatting / whitespace updated)")
        return Triple(added.size, removed.size, sb.toString().trim())
    }

    fun generateAiPatchFromInstruction(
        ctx: Context,
        instruction: String,
        preferredModel: String? = null,
        allowAutoFallback: Boolean = false,
        extraContext: String = ""
    ) {
        ensurePrefsLoaded(ctx)
        val cleanInst = instruction.trim()
        if (cleanInst.isBlank()) return
        val apiKey = Prefs(ctx).apiKey
        if (apiKey.isBlank()) {
            Toast.makeText(ctx, "पहले साइड मेन्यू से Gemini API Key डालें", Toast.LENGTH_LONG).show()
            return
        }

        val app = ctx.applicationContext
        cancelWaitFlag.set(true)
        val curated = state.value.curatedModels
        val chosenModels = when {
            !preferredModel.isNullOrBlank() && allowAutoFallback ->
                (listOf(preferredModel.trim()) + curated).distinct()
            !preferredModel.isNullOrBlank() ->
                listOf(preferredModel.trim())
            allowAutoFallback ->
                curated
            else ->
                listOf(curated.firstOrNull() ?: "gemini-4-hydrogen")
        }

        val primaryLabel = friendlyModelLabel(chosenModels.first())
        val ghToken = MeeraTokenVault.getToken(ctx)
        val bgId = MeeraBackgroundMonitor.startTask(
            "AI Patch ($primaryLabel): ${cleanInst.take(35)}",
            "In-App Code Studio",
            chosenModels.first(),
            "GitHub से ताज़ा फ़ाइलें पढ़ी जा रही हैं...",
            15
        )
        state.value = state.value.copy(
            busy = true,
            statusText = "🔍 GitHub से ताज़ा फ़ाइलें पढ़ी जा रही हैं...",
            activeModelText = "$primaryLabel से तैयारी...",
            previews = emptyList(),
            validationErrors = emptyList(),
            hydrogenBusyPrompt = false,
            hydrogenWaitingAuto = false,
            studioErrorTitle = "",
            studioErrorReportText = ""
        )

        Thread {
            try {
                val allFiles = fetchRepoFileList(ghToken)
                val targetCandidates = chooseTargetFiles(apiKey, cleanInst + "\n" + extraContext, allFiles, chosenModels)
                if (targetCandidates.isEmpty()) {
                    throw RuntimeException("कौन सी फ़ाइल बदलनी है यह समझ नहीं आया। निर्देश में फ़ाइल का नाम (जैसे Ui.kt) लिखें।")
                }

                val contextBuilder = StringBuilder()
                for (path in targetCandidates) {
                    val liveCode = fetchLiveFileFromBranch(path, ghToken) ?: continue
                    contextBuilder.append("\n=== FILE: $path ===\n").append(liveCode).append("\n")
                }

                val sysPrompt = """
You are a Principal Android Kotlin Engineer modifying the Meera AI repository (package com.meraai.app).
Return ONLY valid JSON matching this exact schema (no markdown fences, no commentary):
{
  "commit_message": "Short clear commit title",
  "changes": [
    {
      "file": "app/src/main/java/com/meraai/app/TargetFile.kt",
      "action": "modify",
      "anchor": "EXACT verbatim substring from the existing file that uniquely identifies where to edit",
      "replacement": "Exact replacement text that replaces the anchor cleanly"
    }
  ]
}
RULES:
1. For "modify", `anchor` MUST appear verbatim ONCE in the provided file content.
2. For "create" (new file), set `action`: "create", `anchor`: "", and `replacement`: full complete Kotlin file with `package com.meraai.app`.
3. NEVER use '...' placeholders. All `{}` and `()` must be 100% balanced.
4. Escape all regex backslashes and newlines properly inside JSON strings.
""".trimIndent()

                val userPrompt = buildString {
                    append("USER REQUEST:\n").append(cleanInst).append("\n\n")
                    if (extraContext.isNotBlank()) {
                        append("BUILD FAILURE & CONTEXT DETAILS:\n").append(extraContext.take(4000)).append("\n\n")
                    }
                    append("AVAILABLE REPOSITORY FILES:\n").append(allFiles.joinToString("\n")).append("\n\n")
                    append("LIVE FILE CONTENTS:\n").append(contextBuilder)
                }

                val reqBody = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysPrompt))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))))
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1))

                var rawJson = ""
                var usedModel = ""
                var lastErr = ""
                var hydrogenOverloaded = false

                for (m in chosenModels) {
                    val mLabel = friendlyModelLabel(m)
                    MeeraBackgroundMonitor.updateProgress(bgId, 45, "$mLabel से कनेक्ट होकर पैच तैयार किया जा रहा है...", m)
                    state.value = state.value.copy(
                        statusText = "🧠 $mLabel सटीक बदलाव तैयार कर रहा है (कृपया प्रतीक्षा करें)...",
                        activeModelText = "$mLabel सक्रिय..."
                    )

                    val (ok, textOrErr, code) = executeSingleModelCall(apiKey, m, reqBody)
                    if (ok && textOrErr.isNotBlank()) {
                        rawJson = textOrErr
                        usedModel = m
                        break
                    } else {
                        lastErr = "$mLabel: $textOrErr"
                        if (m == "gemini-4-hydrogen" && !allowAutoFallback && (code == 429 || code == 503 || code == 504 || code == 0)) {
                            hydrogenOverloaded = true
                            break
                        }
                    }
                }

                if (rawJson.isBlank()) {
                    if (hydrogenOverloaded) {
                        val pending = PendingHydrogenJob(cleanInst, extraContext, attempts = 1, lastReason = lastErr)
                        MeeraBackgroundMonitor.completeTask(bgId, false, "Hydrogen पर ज़्यादा लोड है — यूज़र के निर्णय का इंतज़ार")
                        recordStudioError(
                            app,
                            "AI Patch (Hydrogen High Load)",
                            "Hydrogen High Load / Busy",
                            lastErr,
                            "Instruction:\n$cleanInst"
                        )
                        state.value = state.value.copy(
                            busy = false,
                            activeModelText = "",
                            hydrogenBusyPrompt = true,
                            pendingHydrogenJob = pending,
                            statusText = "⏳ Hydrogen पर अभी ज़्यादा लोड है ($lastErr)। नीचे चुनें कि Hydrogen का इंतज़ार करना है या अर्जेंसी में दूसरे मॉडल से अभी करवाना है:"
                        )
                        return@Thread
                    }
                    throw RuntimeException(lastErr.ifBlank { "चुने गए मॉडल से कोई जवाब नहीं मिला" })
                }

                MeeraBackgroundMonitor.updateProgress(bgId, 80, "${friendlyModelLabel(usedModel)} के पैच का प्रीव्यू और ब्रैकेट्स जाँच हो रही है...", usedModel)
                preparePreviewFromPatchJson(ctx, rawJson, bgId, friendlyModelLabel(usedModel))
            } catch (e: Exception) {
                val errMsg = e.message ?: "Unknown AI Patch error"
                MeeraBackgroundMonitor.completeTask(bgId, false, "पैच जनरेट फेल: $errMsg")
                recordStudioError(
                    app,
                    "AI Patch ($primaryLabel)",
                    "AI Patch Generation Failed",
                    errMsg,
                    "Instruction:\n$cleanInst\n\nExtra Context:\n$extraContext"
                )
                state.value = state.value.copy(
                    busy = false,
                    activeModelText = "",
                    statusText = "❌ एरर: $errMsg"
                )
            }
        }.start()
    }

    fun startHydrogenBackgroundWaiter(ctx: Context) {
        val job = state.value.pendingHydrogenJob ?: return
        val apiKey = Prefs(ctx).apiKey
        if (apiKey.isBlank()) return

        cancelWaitFlag.set(false)
        val waitTaskId = MeeraBackgroundMonitor.startTask(
            "Wait for Hydrogen: ${job.instruction.take(30)}",
            "Hydrogen Auto-Queue",
            "gemini-4-hydrogen",
            "Hydrogen स्लॉट खाली होने का इंतज़ार (Attempt 1/30)...",
            10
        )
        state.value = state.value.copy(
            hydrogenBusyPrompt = false,
            hydrogenWaitingAuto = true,
            hydrogenWaitStatus = "⏳ बैकग्राउंड में Hydrogen का इंतज़ार चालू है (Attempt 1/30)..."
        )

        Thread {
            try {
                for (attempt in 1..30) {
                    if (cancelWaitFlag.get()) {
                        MeeraBackgroundMonitor.completeTask(waitTaskId, false, "यूज़र द्वारा Hydrogen इंतज़ार रोका गया")
                        state.value = state.value.copy(hydrogenWaitingAuto = false, hydrogenWaitStatus = "")
                        return@Thread
                    }
                    val pct = (10 + attempt * 2).coerceAtMost(85)
                    val msg = "⏳ Hydrogen का इंतज़ार (कोशिश $attempt/30) — स्लॉट मिलते ही अपने-आप शुरू होगा..."
                    MeeraBackgroundMonitor.updateProgress(waitTaskId, pct, msg)
                    state.value = state.value.copy(hydrogenWaitStatus = msg)

                    val probe = probeSingleCodingModel(apiKey, "gemini-4-hydrogen")
                    if (probe.working) {
                        MeeraBackgroundMonitor.completeTask(waitTaskId, true, "100% Hydrogen उपलब्ध! अब पैच बन रहा है...")
                        state.value = state.value.copy(
                            hydrogenWaitingAuto = false,
                            hydrogenWaitStatus = "",
                            pendingHydrogenJob = null
                        )
                        generateAiPatchFromInstruction(
                            ctx = ctx,
                            instruction = job.instruction,
                            preferredModel = "gemini-4-hydrogen",
                            allowAutoFallback = false,
                            extraContext = job.extraContext
                        )
                        return@Thread
                    }
                    Thread.sleep(18000L)
                }
                MeeraBackgroundMonitor.completeTask(waitTaskId, false, "10 मिनट तक Hydrogen व्यस्त रहा")
                state.value = state.value.copy(
                    hydrogenWaitingAuto = false,
                    hydrogenBusyPrompt = true,
                    statusText = "⚠️ 10 मिनट बाद भी Hydrogen पर भारी लोड है। आप चाहें तो नीचे से किसी दूसरे मॉडल से अभी काम पूरा करवा सकते हैं।"
                )
            } catch (e: Exception) {
                MeeraBackgroundMonitor.completeTask(waitTaskId, false, "Waiter error: ${e.message}")
                state.value = state.value.copy(hydrogenWaitingAuto = false)
            }
        }.start()
    }

    fun stopHydrogenWaiting() {
        cancelWaitFlag.set(true)
        state.value = state.value.copy(
            hydrogenWaitingAuto = false,
            hydrogenBusyPrompt = state.value.pendingHydrogenJob != null,
            hydrogenWaitStatus = ""
        )
    }

    fun triggerBuildAutoFix(ctx: Context, modelId: String, allowFallback: Boolean = false) {
        val err = state.value.failureCompilerError.ifBlank { "Gradle compilation failed" }
        val filesNote = if (state.value.lastPushedFiles.isNotEmpty()) {
            "Recently modified files in failed build:\n" + state.value.lastPushedFiles.joinToString("\n")
        } else {
            ""
        }
        val prompt = buildString {
            append("Fix this exact Kotlin compilation error in Meera AI:\n")
            append(err)
            if (filesNote.isNotBlank()) {
                append("\n\n").append(filesNote)
            }
        }
        generateAiPatchFromInstruction(
            ctx = ctx,
            instruction = prompt,
            preferredModel = modelId,
            allowAutoFallback = allowFallback,
            extraContext = state.value.failureReportText.take(3500)
        )
    }

    private fun executeSingleModelCall(apiKey: String, modelId: String, reqBody: JSONObject): Triple<Boolean, String, Int> {
        val client = if (modelId.contains("hydrogen") || modelId.contains("pro")) {
            hydrogenHttp
        } else {
            http
        }
        return try {
            val bodyCopy = JSONObject(reqBody.toString())
            if (modelId.contains("hydrogen")) {
                bodyCopy.optJSONObject("generationConfig")
                    ?.put("thinkingConfig", JSONObject().put("thinkingLevel", "HIGH"))
            }
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(bodyCopy.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val code = resp.code
                if (!resp.isSuccessful) {
                    val errSnippet = resp.body?.string()?.take(200).orEmpty()
                    return Triple(false, "HTTP $code $errSnippet", code)
                }
                val j = JSONObject(resp.body!!.string())
                val parts = j.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                    }
                }
                val txt = sb.toString().trim()
                if (txt.isBlank()) Triple(false, "Empty response", 200)
                else Triple(true, txt, 200)
            }
        } catch (e: Exception) {
            Triple(false, e.message ?: "Network timeout", 0)
        }
    }

    private fun chooseTargetFiles(
        apiKey: String,
        instruction: String,
        allFiles: List<String>,
        preferredModels: List<String>
    ): List<String> {
        val chosen = LinkedHashSet<String>()
        Regex("""([A-Za-z0-9_]+\.(?:kt|xml|kts))""").findAll(instruction).forEach { m ->
            val name = m.groupValues[1]
            allFiles.filter { it.substringAfterLast("/") == name }.forEach { chosen.add(it) }
        }
        if (chosen.isNotEmpty()) return chosen.take(4)

        val pickerModels = (preferredModels.reversed() + state.value.curatedModels).distinct()
        val prompt = "You help edit an Android Kotlin repository. From the file list below, choose the files (at most 3) that must be edited to fulfil the user request. " +
            "Reply ONLY with JSON like {\"files\": [\"path1\", \"path2\"]} using exact paths from the list.\n\nFILES:\n" +
            allFiles.joinToString("\n") + "\n\nUSER REQUEST:\n" + instruction.take(3000)

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.0))

        for (m in pickerModels) {
            try {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                probeHttp.newCall(req).execute().use { resp ->
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
                        val arr = JSONObject(txt).optJSONArray("files")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val p = arr.optString(i)
                                if (p in allFiles) chosen.add(p)
                            }
                        }
                    }
                }
                if (chosen.isNotEmpty()) break
            } catch (_: Exception) {}
        }
        return chosen.take(4)
    }

    fun preparePreviewFromPatchJson(
        ctx: Context,
        rawInput: String,
        existingBgId: Long = -1L,
        modelBadge: String = ""
    ) {
        val cleanJson = rawInput.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .replace("\u00A0", " ")
            .trim()
        if (cleanJson.isBlank()) return
        val app = ctx.applicationContext
        val ghToken = MeeraTokenVault.getToken(ctx)
        val bgId = if (existingBgId > 0) existingBgId else MeeraBackgroundMonitor.startTask(
            "Validate Patch",
            "In-App Code Studio",
            "Syntax Guard",
            "GitHub से फ़ाइलें मिलाकर प्रीव्यू बन रहा है...",
            30
        )
        state.value = state.value.copy(
            busy = true,
            statusText = "🔍 फ़ाइलों और ब्रैकेट्स की जाँच हो रही है...",
            previews = emptyList(),
            validationErrors = emptyList()
        )

        Thread {
            try {
                val rootObj = JSONObject(cleanJson)
                val commitMsg = rootObj.optString("commit_message", "Update Meera AI").trim()
                val changesArr = rootObj.optJSONArray("changes")
                    ?: throw RuntimeException("JSON में 'changes' लिस्ट नहीं मिली।")
                val previewList = mutableListOf<FilePatchPreview>()
                val errorList = mutableListOf<String>()

                for (i in 0 until changesArr.length()) {
                    val item = changesArr.optJSONObject(i) ?: continue
                    val filePath = item.optString("file").trim().trimStart('/')
                    val action = item.optString("action", "modify").lowercase().trim()
                    val anchor = item.optString("anchor", "").replace("\r\n", "\n")
                    val replacement = item.optString("replacement", "").replace("\r\n", "\n")

                    if (filePath.isBlank()) {
                        errorList.add("Item #${i + 1}: फ़ाइल का पाथ खाली है")
                        continue
                    }

                    if (action == "create") {
                        val (ok, note) = validateSourceCode(filePath, replacement)
                        if (!ok) errorList.add("$filePath: $note")
                        val (addCnt, remCnt, diffTxt) = buildCompactDiffSnippet("", replacement)
                        previewList.add(FilePatchPreview(filePath, "CREATE", addCnt, remCnt, diffTxt, replacement, ok, note))
                    } else {
                        val currentContent = fetchLiveFileFromBranch(filePath, ghToken)
                        if (currentContent == null) {
                            errorList.add("$filePath: GitHub पर यह फ़ाइल नहीं मिली")
                            continue
                        }
                        if (anchor.isBlank()) {
                            errorList.add("$filePath: 'anchor' खाली है")
                            continue
                        }
                        val occurrences = currentContent.split(anchor).size - 1
                        if (occurrences == 0) {
                            errorList.add("$filePath: टारगेट एंकर मौजूदा फ़ाइल से मैच नहीं हुआ")
                            continue
                        }
                        if (occurrences > 1) {
                            errorList.add("$filePath: यह एंकर फ़ाइल में $occurrences बार मिला है")
                            continue
                        }
                        val updatedContent = currentContent.replace(anchor, replacement)
                        val (ok, note) = validateSourceCode(filePath, updatedContent)
                        if (!ok) errorList.add("$filePath: $note")
                        val (addCnt, remCnt, diffTxt) = buildCompactDiffSnippet(currentContent, updatedContent)
                        previewList.add(FilePatchPreview(filePath, "MODIFY", addCnt, remCnt, diffTxt, updatedContent, ok, note))
                    }
                }

                val prevSha = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_PREV_SHA, "").orEmpty()
                val good = errorList.isEmpty() && previewList.isNotEmpty()
                val byTag = if (modelBadge.isNotBlank()) " ($modelBadge)" else ""
                MeeraBackgroundMonitor.completeTask(
                    bgId,
                    good,
                    if (good) "100% प्रीव्यू तैयार$byTag" else "जाँच में ${errorList.size} त्रुटि मिली"
                )
                if (!good && errorList.isNotEmpty()) {
                    recordStudioError(
                        app,
                        "AI Patch / JSON Validation$byTag",
                        "Patch Validation Failed (${errorList.size} errors)",
                        errorList.joinToString("\n"),
                        "Raw Patch JSON:\n${cleanJson.take(4000)}"
                    )
                }
                state.value = state.value.copy(
                    busy = false,
                    activeModelText = "",
                    commitMessage = commitMsg,
                    previews = previewList,
                    validationErrors = errorList,
                    previousCommitSha = prevSha,
                    statusText = if (good) "✅ प्रीव्यू तैयार है$byTag! नीचे बदलाव देखकर 'Confirm & Push' दबाएँ।"
                    else "⚠️ पुश रोका गया: नीचे पूरी एरर रिपोर्ट और डाउनलोड बटन उपलब्ध है।"
                )
            } catch (e: Exception) {
                val errMsg = e.message ?: "JSON parse error"
                MeeraBackgroundMonitor.completeTask(bgId, false, "JSON पार्स एरर: $errMsg")
                recordStudioError(
                    app,
                    "JSON Preview / AI Patch",
                    "Invalid Patch JSON Format",
                    errMsg,
                    "Input JSON:\n${cleanJson.take(4000)}"
                )
                state.value = state.value.copy(
                    busy = false,
                    activeModelText = "",
                    validationErrors = listOf("अमान्य Patch फ़ॉर्मेट: $errMsg"),
                    statusText = "❌ Patch पढ़ने में दिक्कत आई"
                )
            }
        }.start()
    }

    private fun fetchFileWithStatus(path: String, token: String): Pair<Int, String> {
        val cleanPath = path.trim().trimStart('/')
        val url = "https://api.github.com/repos/$OWNER/$REPO/contents/$cleanPath?ref=$BRANCH"
        http.newCall(ghRequest(url, token).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return resp.code to ""
            val j = JSONObject(resp.body!!.string())
            val b64 = j.optString("content", "").replace("\n", "").replace("\r", "")
            val text = if (b64.isBlank()) "" else String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).replace("\r\n", "\n")
            return 200 to text
        }
    }

    fun prepareRawFilePreview(ctx: Context, rawPath: String, code: String) {
        val filePath = rawPath.trim().trimStart('/')
        val newCode = code.replace("\r\n", "\n")
        if (filePath.isBlank() || newCode.isBlank()) {
            state.value = state.value.copy(
                statusText = "⚠️ फ़ाइल का पाथ और कोड दोनों भरें",
                validationErrors = emptyList(),
                previews = emptyList()
            )
            return
        }
        val app = ctx.applicationContext
        val ghToken = MeeraTokenVault.getToken(ctx)
        val bgId = MeeraBackgroundMonitor.startTask(
            "Direct File: ${filePath.substringAfterLast("/")}",
            "In-App Code Studio",
            "Raw File",
            "GitHub से मौजूदा फ़ाइल पढ़ी जा रही है...",
            30
        )
        state.value = state.value.copy(
            busy = true,
            statusText = "🔍 फ़ाइल की जाँच हो रही है...",
            previews = emptyList(),
            validationErrors = emptyList()
        )

        Thread {
            try {
                val errorList = mutableListOf<String>()
                if (filePath.contains("..") || filePath.contains("\\")) {
                    errorList.add("$filePath: पाथ सही नहीं है")
                }
                val (httpCode, oldText) = fetchFileWithStatus(filePath, ghToken)
                var action = "OVERWRITE"
                var oldContent = ""
                when {
                    httpCode == 200 -> oldContent = oldText
                    httpCode == 404 -> {
                        action = "CREATE"
                        val sameName = fetchRepoFileList(ghToken).filter { it.substringAfterLast("/") == filePath.substringAfterLast("/") }
                        if (sameName.isNotEmpty()) {
                            errorList.add("इस नाम की फ़ाइल पहले से यहाँ है: ${sameName.joinToString()} — पूरा सही पाथ लिखें")
                        }
                    }
                    else -> errorList.add("GitHub से मौजूदा फ़ाइल पढ़ नहीं पाया (HTTP $httpCode) — पुश रोका गया")
                }
                if (oldContent.isNotBlank()) {
                    val oldLines = oldContent.split("\n").size
                    val newLines = newCode.split("\n").size
                    if (newLines * 100 < oldLines * 60) {
                        errorList.add("नई फ़ाइल में $newLines लाइनें हैं, पुरानी में $oldLines थीं — पूरी फ़ाइल पेस्ट करें")
                    }
                }
                val (ok, note) = validateSourceCode(filePath, newCode)
                if (!ok) errorList.add("$filePath: $note")
                val (addCnt, remCnt, diffTxt) = buildCompactDiffSnippet(oldContent, newCode)
                val preview = FilePatchPreview(filePath, action, addCnt, remCnt, diffTxt, newCode, ok && errorList.isEmpty(), note)

                val prevSha = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_PREV_SHA, "").orEmpty()
                val good = errorList.isEmpty()
                MeeraBackgroundMonitor.completeTask(bgId, good, if (good) "100% प्रीव्यू तैयार" else "जाँच में ${errorList.size} त्रुटि मिली")
                if (!good) {
                    recordStudioError(
                        app,
                        "Raw Kotlin Tab ($filePath)",
                        "Raw File Validation Failed",
                        errorList.joinToString("\n"),
                        "Submitted Code Snippet:\n${newCode.take(3000)}"
                    )
                }
                state.value = state.value.copy(
                    busy = false,
                    commitMessage = "Update ${filePath.substringAfterLast("/")} via Direct Push",
                    previews = listOf(preview),
                    validationErrors = errorList,
                    previousCommitSha = prevSha,
                    statusText = if (good) "✅ प्रीव्यू तैयार है! नीचे बदलाव देखकर 'Confirm & Push' दबाएँ।"
                    else "⚠️ पुश रोका गया: नीचे पूरी एरर रिपोर्ट डाउनलोड करें।"
                )
            } catch (e: Exception) {
                val errMsg = e.message ?: "Raw preview error"
                MeeraBackgroundMonitor.completeTask(bgId, false, "एरर: $errMsg")
                recordStudioError(app, "Raw Kotlin Tab", "Raw File Check Exception", errMsg)
                state.value = state.value.copy(
                    busy = false,
                    validationErrors = listOf("त्रुटि: $errMsg"),
                    statusText = "❌ फ़ाइल जाँच में दिक्कत आई"
                )
            }
        }.start()
    }

    fun runPythonScript(ctx: Context, scriptCode: String) {
        val script = scriptCode.replace("\r\n", "\n")
        if (script.isBlank()) return
        val app = ctx.applicationContext
        val vaultToken = MeeraTokenVault.getToken(ctx)
        val runnableScript = if (script.contains("getpass") || script.contains("input(")) {
            if (vaultToken.isBlank()) {
                val msg = "इस स्क्रिप्ट में getpass/input() है और Vault में GitHub Token सेव नहीं है। पहले ऊपर '🔑 Token' से टोकन सेव करें।"
                recordStudioError(app, "Python / Termux Tab", "Missing GitHub Token in Vault", msg, script.take(2500))
                state.value = state.value.copy(
                    statusText = "⚠️ $msg",
                    pythonOutput = ""
                )
                return
            }
            "import getpass\ngetpass.getpass = lambda *a, **k: '$vaultToken'\ninput = lambda *a, **k: '$vaultToken'\n" + script
        } else {
            script
        }

        val bgId = MeeraBackgroundMonitor.startTask("Python Runner", "Termux", "Python3", "स्क्रिप्ट चलाई जा रही है...", 20)
        state.value = state.value.copy(
            busy = true,
            statusText = "⏳ स्क्रिप्ट Termux में चल रही है (2 मिनट तक इंतज़ार)...",
            pythonOutput = "",
            studioErrorTitle = "",
            studioErrorReportText = ""
        )

        Thread {
            try {
                val delim = "MEERA_PY_END_7Q2"
                val command = "cat << '$delim' > ~/meera_run.py\n$runnableScript\n$delim\ncd ~ && python3 -u meera_run.py 2>&1"
                val res = MeeraTermuxEngine.execute(ctx, command, background = true, waitMs = 120000L, autoOpenUi = false)
                val raw = res.rawDataForAi
                val finished = raw.startsWith("VERIFIED_SUCCESS: Termux executed")
                val stillRunning = raw.startsWith("VERIFIED_SUCCESS: Dispatched")
                val out = if (raw.contains("FULL_OUTPUT:")) {
                    raw.substringAfter("FULL_OUTPUT:").substringBefore("INSTRUCTIONS:").trim()
                } else {
                    raw
                }
                var hint = ""
                if (!finished && !stillRunning && (out.contains("not found") || out.contains("No such file"))) {
                    hint = "\n\n💡 अगर 'python3 not found' दिखे तो Termux में यह चलाएँ: pkg install python git"
                }
                val status = when {
                    finished -> "✅ स्क्रिप्ट पूरी हुई (exit=0)"
                    stillRunning -> "⏳ स्क्रिप्ट अभी भी चल रही है — नतीजा Background Activity में देखें"
                    else -> "❌ स्क्रिप्ट फेल हुई — नीचे पूरी एरर रिपोर्ट डाउनलोड या शेयर करें"
                }
                MeeraBackgroundMonitor.completeTask(bgId, finished, status, out.take(2000))
                if (!finished && !stillRunning) {
                    recordStudioError(
                        app,
                        "Python / Termux Runner",
                        "Python / Termux Execution Error",
                        out.lines().takeLast(12).joinToString("\n").ifBlank { "Script exited with error" },
                        "=== FULL TERMUX OUTPUT ===\n$out$hint\n\n=== SCRIPT EXECUTED ===\n${script.take(3000)}"
                    )
                }
                state.value = state.value.copy(busy = false, statusText = status, pythonOutput = (out + hint).take(6000))
            } catch (e: Exception) {
                val errMsg = e.message ?: "Python runner exception"
                MeeraBackgroundMonitor.completeTask(bgId, false, "Python Runner एरर: $errMsg")
                recordStudioError(app, "Python / Termux Runner", "Termux Bridge Exception", errMsg, script.take(3000))
                state.value = state.value.copy(busy = false, statusText = "❌ एरर: $errMsg")
            }
        }.start()
    }

    fun confirmAndPushAtomic(ctx: Context, onNeedToken: () -> Unit) {
        val current = state.value
        if (current.busy || current.previews.isEmpty() || current.validationErrors.isNotEmpty()) return
        val ghToken = MeeraTokenVault.getToken(ctx)
        if (ghToken.isBlank()) {
            onNeedToken()
            return
        }

        val app = ctx.applicationContext
        val pushedPaths = current.previews.map { it.filePath }
        val bgId = MeeraBackgroundMonitor.startTask(
            "GitHub Push: ${current.commitMessage.take(35)}",
            "GitHub Push",
            "Git Trees API",
            "v2-test का वर्तमान कमिट पढ़ा जा रहा है...",
            15
        )
        state.value = current.copy(
            busy = true,
            statusText = "🚀 GitHub पर एटॉमिक पुश हो रहा है...",
            buildStatus = "",
            failureReportText = "",
            lastPushedFiles = pushedPaths
        )

        Thread {
            try {
                val refUrl = "https://api.github.com/repos/$OWNER/$REPO/git/ref/heads/$BRANCH"
                val headSha = http.newCall(ghRequest(refUrl, ghToken).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("Branch ref fetch failed (HTTP ${resp.code})")
                    JSONObject(resp.body!!.string()).getJSONObject("object").getString("sha")
                }

                val commitUrl = "https://api.github.com/repos/$OWNER/$REPO/git/commits/$headSha"
                val baseTreeSha = http.newCall(ghRequest(commitUrl, ghToken).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("Base commit fetch failed (HTTP ${resp.code})")
                    JSONObject(resp.body!!.string()).getJSONObject("tree").getString("sha")
                }

                MeeraBackgroundMonitor.updateProgress(bgId, 45, "${current.previews.size} फ़ाइलों का नया ट्री बन रहा है...")

                val treeArr = JSONArray()
                for (p in current.previews) {
                    treeArr.put(
                        JSONObject()
                            .put("path", p.filePath)
                            .put("mode", "100644")
                            .put("type", "blob")
                            .put("content", p.finalContent)
                    )
                }
                val treeBody = JSONObject().put("base_tree", baseTreeSha).put("tree", treeArr)

                val newTreeSha = http.newCall(
                    ghRequest("https://api.github.com/repos/$OWNER/$REPO/git/trees", ghToken)
                        .post(treeBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("Create tree failed (HTTP ${resp.code})")
                    JSONObject(resp.body!!.string()).getString("sha")
                }

                MeeraBackgroundMonitor.updateProgress(bgId, 70, "नया कमिट तैयार हो रहा है...")

                val newCommitBody = JSONObject()
                    .put("message", current.commitMessage)
                    .put("tree", newTreeSha)
                    .put("parents", JSONArray().put(headSha))
                val newCommitSha = http.newCall(
                    ghRequest("https://api.github.com/repos/$OWNER/$REPO/git/commits", ghToken)
                        .post(newCommitBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("Create commit failed (HTTP ${resp.code})")
                    JSONObject(resp.body!!.string()).getString("sha")
                }

                val patchRefBody = JSONObject().put("sha", newCommitSha).put("force", false)
                http.newCall(
                    ghRequest(refUrl.replace("/git/ref/", "/git/refs/"), ghToken)
                        .patch(patchRefBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("Update branch ref failed (HTTP ${resp.code})")
                }

                ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_PREV_SHA, headSha).apply()
                MeeraBackgroundMonitor.completeTask(bgId, true, "100% पुश सफल (${newCommitSha.take(7)})")
                state.value = state.value.copy(
                    busy = false,
                    previews = emptyList(),
                    pushedCommitSha = newCommitSha,
                    previousCommitSha = headSha,
                    buildStatus = "WATCHING",
                    buildSummaryText = "⏳ Commit ${newCommitSha.take(7)} पुश हो गया! APK बिल्ड की निगरानी हो रही है...",
                    statusText = "✅ GitHub पर पुश सफल!"
                )
                watchBuildForCommit(app, newCommitSha, ghToken)
            } catch (e: Exception) {
                val errMsg = e.message ?: "GitHub push failed"
                MeeraBackgroundMonitor.completeTask(bgId, false, "GitHub Push विफल: $errMsg")
                recordStudioError(app, "GitHub Atomic Push", "GitHub Push Failed", errMsg, "Files:\n${pushedPaths.joinToString("\n")}")
                state.value = state.value.copy(busy = false, statusText = "❌ Push एरर: $errMsg")
            }
        }.start()
    }

    private fun watchBuildForCommit(app: Context, targetSha: String, token: String) {
        val buildTaskId = MeeraBackgroundMonitor.startTask(
            "APK Build (${targetSha.take(7)})",
            "GitHub CI",
            "Gradle",
            "GitHub Actions रनर शुरू होने का इंतज़ार...",
            10
        )
        Thread {
            try {
                var matchedRunId = -1L
                var matchedRunNumber = 0
                var finalConclusion = ""
                var allRunsCache = JSONArray()

                for (attempt in 1..40) {
                    Thread.sleep(12000L)
                    val runsUrl = "https://api.github.com/repos/$OWNER/$REPO/actions/runs?branch=$BRANCH&per_page=15"
                    http.newCall(ghRequest(runsUrl, token).get().build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val j = JSONObject(resp.body!!.string())
                            val runs = j.optJSONArray("workflow_runs") ?: JSONArray()
                            allRunsCache = runs
                            for (i in 0 until runs.length()) {
                                val r = runs.optJSONObject(i) ?: continue
                                if (r.optString("head_sha") == targetSha) {
                                    matchedRunId = r.optLong("id", -1L)
                                    matchedRunNumber = r.optInt("run_number", 0)
                                    val st = r.optString("status")
                                    val conc = r.optString("conclusion")
                                    if (st == "completed") {
                                        finalConclusion = conc
                                    } else {
                                        val pct = (15 + attempt * 3).coerceAtMost(85)
                                        MeeraBackgroundMonitor.updateProgress(buildTaskId, pct, "Build #$matchedRunNumber कम्पाइल हो रहा है ($st)...")
                                        state.value = state.value.copy(
                                            buildRunNumber = matchedRunNumber,
                                            buildSummaryText = "⚙️ Build #$matchedRunNumber चल रहा है ($pct%)..."
                                        )
                                    }
                                    break
                                }
                            }
                        }
                    }
                    if (finalConclusion.isNotBlank()) break
                }

                if (finalConclusion == "success") {
                    MeeraBackgroundMonitor.completeTask(buildTaskId, true, "100% पूरा — APK Build #$matchedRunNumber तैयार है!")
                    state.value = state.value.copy(
                        buildStatus = "SUCCESS",
                        buildRunNumber = matchedRunNumber,
                        buildSummaryText = "🎉 APK Build #$matchedRunNumber सफल (100%)! नीचे बटन दबाकर नया APK इंस्टॉल करें।"
                    )
                } else if (finalConclusion.isNotBlank()) {
                    MeeraBackgroundMonitor.updateProgress(buildTaskId, 90, "Build #$matchedRunNumber फेल हुआ — एरर लॉग निकल रही है...")
                    generateInAppFailureComparisonReport(app, matchedRunId, matchedRunNumber, targetSha, allRunsCache, token, buildTaskId)
                } else {
                    MeeraBackgroundMonitor.completeTask(buildTaskId, false, "Build निगरानी टाइमआउट हो गई।")
                    state.value = state.value.copy(buildStatus = "FAILURE", buildSummaryText = "⚠️ बिल्ड को 8 मिनट से ज़्यादा समय लगा।")
                }
            } catch (e: Exception) {
                MeeraBackgroundMonitor.completeTask(buildTaskId, false, "Build Watcher एरर: ${e.message}")
            }
        }.start()
    }

    private fun generateInAppFailureComparisonReport(
        app: Context,
        failedRunId: Long,
        failedRunNumber: Int,
        failSha: String,
        runsArr: JSONArray,
        token: String,
        buildTaskId: Long
    ) {
        try {
            var lastSuccessSha = ""
            var lastSuccessNum = 0
            for (i in 0 until runsArr.length()) {
                val r = runsArr.optJSONObject(i) ?: continue
                if (r.optString("conclusion") == "success") {
                    lastSuccessSha = r.optString("head_sha")
                    lastSuccessNum = r.optInt("run_number", 0)
                    break
                }
            }

            val errorLines = mutableListOf<String>()
            val logTail = StringBuilder()
            if (failedRunId > 0 && token.isNotBlank()) {
                val logUrl = "https://api.github.com/repos/$OWNER/$REPO/actions/runs/$failedRunId/logs"
                http.newCall(ghRequest(logUrl, token).get().build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body!!.bytes()
                        ZipInputStream(BufferedInputStream(ByteArrayInputStream(bytes))).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && entry.name.endsWith(".txt") && (entry.name.contains("Build debug APK") || entry.name.contains("0_build"))) {
                                    val txt = zis.readBytes().toString(Charsets.UTF_8)
                                    val lines = txt.lines().map { ln ->
                                        if (ln.length > 29 && ln[4] == '-' && ln[27] == 'Z') ln.substring(29) else ln
                                    }
                                    for (idx in lines.indices) {
                                        val cln = lines[idx]
                                        if (cln.startsWith("e: ") || cln.contains("FAILURE:") || cln.contains("What went wrong:")) {
                                            val snip = lines.subList(idx, minOf(idx + 4, lines.size)).joinToString("\n")
                                            if (snip !in errorLines) errorLines.add(snip)
                                        }
                                    }
                                    if (entry.name.contains("Build debug APK")) {
                                        logTail.append(lines.takeLast(120).joinToString("\n"))
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    }
                }
            }

            var compareDiff = ""
            if (lastSuccessSha.isNotBlank()) {
                val cmpUrl = "https://api.github.com/repos/$OWNER/$REPO/compare/$lastSuccessSha...$failSha"
                http.newCall(ghRequest(cmpUrl, token).get().build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val j = JSONObject(resp.body!!.string())
                        val filesArr = j.optJSONArray("files") ?: JSONArray()
                        val sb = StringBuilder()
                        for (i in 0 until filesArr.length()) {
                            val f = filesArr.optJSONObject(i) ?: continue
                            sb.append("FILE: ").append(f.optString("filename"))
                                .append(" (+").append(f.optInt("additions"))
                                .append("/-").append(f.optInt("deletions")).append(")\n")
                            sb.append(f.optString("patch")).append("\n\n")
                        }
                        compareDiff = sb.toString()
                    }
                }
            }

            val topErrorSummary = if (errorLines.isNotEmpty()) errorLines.joinToString("\n---\n") else "Gradle compilation failed"
            val fullReport = buildString {
                append("MEERA-AI IN-APP BUILD FAILURE REPORT\n\n")
                append("--- 1. EXACT COMPILER ERRORS (Build #$failedRunNumber) ---\n").append(topErrorSummary).append("\n\n")
                append("--- 2. BUILD COMPARISON ---\n")
                append("Last Successful Build : #$lastSuccessNum ($lastSuccessSha)\n")
                append("Current Failed Build  : #$failedRunNumber ($failSha)\n\n")
                append("--- 3. EXACT GIT DIFF ---\n").append(compareDiff.ifBlank { "(No diff available)" }).append("\n\n")
                append("--- 4. FAILED STEP LOG TAIL ---\n").append(logTail.toString())
            }

            val shareUri = saveReportToCacheForShare(app, "meera_build_failure_report.txt", fullReport.toByteArray(Charsets.UTF_8))
            MeeraBackgroundMonitor.completeTask(buildTaskId, false, "Build #$failedRunNumber फेल — पूरी Report तैयार!", topErrorSummary)
            state.value = state.value.copy(
                buildStatus = "FAILURE",
                buildRunNumber = failedRunNumber,
                buildSummaryText = "❌ APK Build #$failedRunNumber फेल हो गया। नीचे से रिपोर्ट डाउनलोड करें या मॉडल चुनकर फिक्स करवाएँ:",
                failureCompilerError = topErrorSummary,
                failureReportText = fullReport,
                failureReportUri = shareUri
            )
        } catch (e: Exception) {
            MeeraBackgroundMonitor.completeTask(buildTaskId, false, "Report error: ${e.message}")
        }
    }

    fun rollbackLastPush(ctx: Context) {
        val prevSha = state.value.previousCommitSha.ifBlank {
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_PREV_SHA, "").orEmpty()
        }
        val ghToken = MeeraTokenVault.getToken(ctx)
        if (prevSha.isBlank() || ghToken.isBlank()) return

        state.value = state.value.copy(busy = true, statusText = "↩️ पिछला वर्किंग वर्ज़न (${prevSha.take(7)}) वापस लाया जा रहा है...")
        Thread {
            try {
                val refUrl = "https://api.github.com/repos/$OWNER/$REPO/git/ref/heads/$BRANCH"
                val body = JSONObject().put("sha", prevSha).put("force", true)
                http.newCall(
                    ghRequest(refUrl.replace("/git/ref/", "/git/refs/"), ghToken)
                        .patch(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("Rollback HTTP ${resp.code}")
                }
                state.value = state.value.copy(
                    busy = false,
                    buildStatus = "",
                    failureCompilerError = "",
                    statusText = "✅ Rollback सफल! v2-test पिछले वर्किंग वर्ज़न पर आ गया है।"
                )
            } catch (e: Exception) {
                state.value = state.value.copy(busy = false, statusText = "❌ Rollback फेल: ${e.message}")
            }
        }.start()
    }
}

@Composable
fun MeeraCodePatcherScreen(onBack: () -> Unit, onOpenTokenVault: () -> Unit) {
    val ctx = LocalContext.current
    val palette = LocalMeeraColors.current
    val studioFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = palette.textPrimary,
        unfocusedTextColor = palette.textPrimary,
        cursorColor = palette.accentPrimary,
        focusedContainerColor = palette.bg,
        unfocusedContainerColor = palette.bg,
        focusedBorderColor = palette.accentPrimary,
        unfocusedBorderColor = palette.cardBorder,
        focusedPlaceholderColor = palette.textSecondary,
        unfocusedPlaceholderColor = palette.textSecondary
    )
    LaunchedEffect(Unit) { MeeraCodePatcher.ensurePrefsLoaded(ctx) }
    val ui by MeeraCodePatcher.state.collectAsState()
    var currentTab by remember { mutableStateOf(0) }
    var showModelManager by remember { mutableStateOf(false) }
    var customModelId by remember { mutableStateOf("") }
    var inputBox by remember { mutableStateOf("") }
    var pyScript by remember { mutableStateOf("") }
    var rawPath by remember { mutableStateOf("") }
    var rawCode by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val errorScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val hasToken = remember(ui.busy) { MeeraTokenVault.hasToken(ctx) }
    val readClip = {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .systemBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Fixed Top Header Bar (proper weight so Models and Token pills never wrap vertically)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = palette.panel,
                shape = CircleShape,
                modifier = Modifier
                    .size(38.dp)
                    .border(1.dp, palette.cardBorder, CircleShape)
                    .clickable(onClick = onBack)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("←", color = palette.textPrimary, fontSize = 19.sp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Code & Build Studio",
                    color = palette.textPrimary,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "v2-test • ${ui.curatedModels.size} Models",
                    color = Color(0xFF2EE6A8),
                    fontSize = 10.5.sp,
                    maxLines = 1
                )
            }
            Surface(
                color = if (showModelManager) Color(0xFF5B8CFF) else Color(0xFF16203A),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                    .clickable {
                        showModelManager = !showModelManager
                        if (showModelManager) scope.launch { scrollState.animateScrollTo(0) }
                    }
            ) {
                Text(
                    if (showModelManager) "✕ Models" else "⚙️ Models",
                    color = palette.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
            Surface(
                color = if (hasToken) Color(0xFF16203A) else Color(0xFF3B1219),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .border(1.dp, if (hasToken) Color(0xFF2EE6A8) else Color(0xFFFF6B6B), RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenTokenVault)
            ) {
                Text(
                    if (hasToken) "🔐 Token" else "🔑 Token",
                    color = if (hasToken) Color(0xFF2EE6A8) else Color(0xFFFF9494),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }

        // 3 Studio Tabs + Quick Jump Top / End Controls
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF16203A))
            ) {
                listOf("🧠 Patch", "🐍 Python", "📄 Raw").forEachIndexed { idx, title ->
                    val active = currentTab == idx
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) Color(0xFF5B8CFF) else Color.Transparent)
                            .clickable {
                                if (currentTab != idx) {
                                    currentTab = idx
                                    MeeraCodePatcher.clearPreview()
                                }
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            title,
                            color = if (active) Color.White else Color(0xFF8A94B8),
                            fontSize = 11.5.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
            Surface(
                color = palette.panel,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .border(1.dp, palette.cardBorder, RoundedCornerShape(10.dp))
                    .clickable { scope.launch { scrollState.animateScrollTo(0) } }
            ) {
                Text("⬆️ Top", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
            }
            Surface(
                color = palette.panel,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .border(1.dp, Color(0xFF2EE6A8), RoundedCornerShape(10.dp))
                    .clickable { scope.launch { scrollState.animateScrollTo(scrollState.maxValue) } }
            ) {
                Text("⬇️ End", color = Color(0xFF2EE6A8), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
            }
        }

        // Main Scrollable Workspace (Models panel + tabs + error reports all scroll together!)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showModelManager) {
                Surface(
                    color = palette.card,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🧠 Coding Models (Max 4)", color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFF5B8CFF), Color(0xFF2EE6A8))))
                                    .clickable(enabled = !ui.verifyingModels) { MeeraCodePatcher.verifyModelsOnApiKey(ctx) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    if (ui.verifyingModels) "⏳ जाँच जारी..." else "🔍 Verify API Models",
                                    color = Color(0xFF090D18),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        if (ui.verifySummary.isNotBlank()) {
                            Text(ui.verifySummary, color = Color(0xFF2EE6A8), fontSize = 11.5.sp)
                        }

                        Text("आपकी चुनी हुई प्राथमिकता (Priority Order):", color = palette.textSecondary, fontSize = 11.sp)
                        ui.curatedModels.forEachIndexed { idx, mId ->
                            val h = ui.verifiedHealthMap[mId]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0B1020), RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "#${idx + 1} ${MeeraCodePatcher.friendlyModelLabel(mId)} ($mId)",
                                        color = palette.textPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        h?.note ?: "Tap 'Verify API Models' to check status",
                                        color = when {
                                            h == null -> Color(0xFF8A94B8)
                                            h.working -> Color(0xFF2EE6A8)
                                            h.validOnKey -> Color(0xFFFFB84D)
                                            else -> Color(0xFFFF6B6B)
                                        },
                                        fontSize = 10.5.sp
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (idx > 0) {
                                        Text(
                                            "⬆️",
                                            fontSize = 14.sp,
                                            modifier = Modifier
                                                .clickable { MeeraCodePatcher.moveModelPriorityUp(ctx, mId) }
                                                .padding(4.dp)
                                        )
                                    }
                                    if (ui.curatedModels.size > 1) {
                                        Text(
                                            "✕",
                                            color = Color(0xFFFF6B6B),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clickable { MeeraCodePatcher.toggleCuratedModel(ctx, mId) }
                                                .padding(4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        val otherModels = ui.verifiedHealthMap.values.filter { it.modelId !in ui.curatedModels }
                        if (otherModels.isNotEmpty()) {
                            Text("API Key पर मिले अन्य मॉडल (लिस्ट में जोड़ने के लिए टैप करें):", color = palette.textSecondary, fontSize = 11.sp)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                otherModels.forEach { item ->
                                    val borderC = when {
                                        item.working -> Color(0xFF2EE6A8)
                                        item.validOnKey -> Color(0xFFFFB84D)
                                        else -> Color(0xFF475569)
                                    }
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF0B1020), RoundedCornerShape(10.dp))
                                            .border(1.dp, borderC.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                                            .clickable { MeeraCodePatcher.toggleCuratedModel(ctx, item.modelId) }
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "＋ ${item.label} (${item.modelId})",
                                                color = if (item.validOnKey) Color.White else Color(0xFF94A3B8),
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(item.note, color = borderC, fontSize = 10.5.sp)
                                        }
                                        Text("Add", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = customModelId,
                                onValueChange = { customModelId = it },
                                placeholder = { Text("नया मॉडल ID डालें (जैसे gemini-3.6-flash)", color = palette.textSecondary, fontSize = 11.sp) },
                                singleLine = true,
                                colors = studioFieldColors,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF16203A))
                                    .border(1.dp, Color(0xFF2EE6A8), RoundedCornerShape(10.dp))
                                    .clickable(enabled = customModelId.isNotBlank() && !ui.verifyingModels) {
                                        val toTest = customModelId.trim()
                                        customModelId = ""
                                        MeeraCodePatcher.verifyModelsOnApiKey(ctx, toTest)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 12.dp)
                            ) {
                                Text("Verify & Add", color = Color(0xFF2EE6A8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (currentTab == 0) {
                Surface(
                    color = palette.card,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.cardBorder, RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("1. बदलाव लिखें और नीचे अपना पसंदीदा मॉडल चुनें:", color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = inputBox,
                            onValueChange = { inputBox = it },
                            placeholder = { Text("उदाहरण: Ui.kt में साइड मेन्यू बटन का रंग बदलो", color = palette.textSecondary, fontSize = 12.sp) },
                            minLines = 3,
                            maxLines = 7,
                            colors = studioFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("किस मॉडल से पैच बनवाना है (Direct Pick):", color = palette.textSecondary, fontSize = 11.sp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ui.curatedModels.forEachIndexed { idx, mId ->
                                val isPrimary = idx == 0
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isPrimary) Brush.linearGradient(listOf(Color(0xFF5B8CFF), Color(0xFF38BDF8)))
                                            else Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF16203A)))
                                        )
                                        .border(1.dp, if (isPrimary) Color(0xFF2EE6A8) else Color(0xFF334155), RoundedCornerShape(12.dp))
                                        .clickable(enabled = !ui.busy && inputBox.isNotBlank()) {
                                            MeeraCodePatcher.generateAiPatchFromInstruction(
                                                ctx = ctx,
                                                instruction = inputBox,
                                                preferredModel = mId,
                                                allowAutoFallback = false
                                            )
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        MeeraCodePatcher.friendlyModelLabel(mId),
                                        color = palette.textPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF16203A))
                                    .border(1.dp, Color(0xFFFFB84D), RoundedCornerShape(12.dp))
                                    .clickable(enabled = !ui.busy && inputBox.isNotBlank()) {
                                        MeeraCodePatcher.generateAiPatchFromInstruction(
                                            ctx = ctx,
                                            instruction = inputBox,
                                            preferredModel = ui.curatedModels.firstOrNull(),
                                            allowAutoFallback = true
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text("⚡ Auto Fallback", color = Color(0xFFFFB84D), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { val clip = readClip(); if (clip.isNotBlank()) inputBox = clip }) {
                                Text("📋 पेस्ट करें", color = Color(0xFF38BDF8), fontSize = 12.sp)
                            }
                            TextButton(
                                enabled = !ui.busy && inputBox.trim().startsWith("{"),
                                onClick = { MeeraCodePatcher.preparePreviewFromPatchJson(ctx, inputBox) }
                            ) {
                                Text("🔍 JSON प्रीव्यू", color = Color(0xFF2EE6A8), fontSize = 12.sp)
                            }
                            if (inputBox.isNotBlank() || ui.previews.isNotEmpty()) {
                                TextButton(onClick = { inputBox = ""; MeeraCodePatcher.clearPreview() }) {
                                    Text("साफ़ करें", color = palette.textSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (currentTab == 1) {
                Surface(
                    color = palette.card,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.cardBorder, RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Python स्क्रिप्ट पेस्ट करें (Termux में चलेगी):", color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        Text("Vault में सेव GitHub Token अपने-आप getpass/input() में जुड़ जाएगा।", color = Color(0xFF2EE6A8), fontSize = 11.sp)
                        OutlinedTextField(
                            value = pyScript,
                            onValueChange = { pyScript = it },
                            placeholder = { Text("print('hello')", color = palette.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                            minLines = 5,
                            maxLines = 10,
                            colors = studioFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669))))
                                .clickable(enabled = !ui.busy) { MeeraCodePatcher.runPythonScript(ctx, pyScript) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("▶️ Termux में स्क्रिप्ट चलाएँ", color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { val clip = readClip(); if (clip.isNotBlank()) pyScript = clip }) {
                                Text("📋 क्लिपबोर्ड से पेस्ट करें", color = Color(0xFF38BDF8), fontSize = 12.sp)
                            }
                            if (pyScript.isNotBlank()) {
                                TextButton(onClick = { pyScript = "" }) {
                                    Text("साफ़ करें", color = palette.textSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                if (ui.pythonOutput.isNotBlank()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF070B14), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📟 Termux Output:", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "⬇️ Download",
                                    color = Color(0xFF2EE6A8),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        MeeraCodePatcher.downloadStudioErrorReportNow(ctx, ui.pythonOutput, "meera_termux_output")
                                    }
                                )
                                Text(
                                    "📋 Copy",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cb.setPrimaryClip(ClipData.newPlainText("TermuxOutput", ui.pythonOutput))
                                        Toast.makeText(ctx, "आउटपुट कॉपी हो गया!", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            Text(ui.pythonOutput, color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            if (currentTab == 2) {
                Surface(
                    color = palette.card,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.cardBorder, RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("फ़ाइल का पूरा पाथ और उसका पूरा नया कोड पेस्ट करें:", color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = rawPath,
                            onValueChange = { rawPath = it },
                            placeholder = { Text("app/src/main/java/com/meraai/app/Config.kt", color = palette.textSecondary, fontSize = 12.sp) },
                            singleLine = true,
                            colors = studioFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = rawCode,
                            onValueChange = { rawCode = it },
                            placeholder = { Text("package com.meraai.app", color = palette.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                            minLines = 5,
                            maxLines = 10,
                            colors = studioFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF16203A))
                                .border(1.dp, Color(0xFF2EE6A8), RoundedCornerShape(12.dp))
                                .clickable(enabled = !ui.busy) { MeeraCodePatcher.prepareRawFilePreview(ctx, rawPath, rawCode) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🔍 प्रीव्यू व ब्रैकेट जाँच करें", color = Color(0xFF2EE6A8), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { val clip = readClip(); if (clip.isNotBlank()) rawCode = clip }) {
                                Text("📋 कोड पेस्ट करें", color = Color(0xFF38BDF8), fontSize = 12.sp)
                            }
                            if (rawCode.isNotBlank() || rawPath.isNotBlank()) {
                                TextButton(onClick = { rawCode = ""; rawPath = ""; MeeraCodePatcher.clearPreview() }) {
                                    Text("साफ़ करें", color = palette.textSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (ui.hydrogenBusyPrompt || ui.hydrogenWaitingAuto) {
                Surface(
                    color = Color(0xFF241B0B),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFFFB84D), RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🧪 Hydrogen High-Load Control", color = Color(0xFFFFB84D), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                        if (ui.hydrogenWaitingAuto) {
                            Text(ui.hydrogenWaitStatus, color = palette.textPrimary, fontSize = 12.5.sp)
                            TextButton(onClick = { MeeraCodePatcher.stopHydrogenWaiting() }) {
                                Text("⏹️ इंतज़ार रोकें (दूसरा मॉडल चुनने के लिए)", color = Color(0xFFFF9494), fontSize = 12.sp)
                            }
                        } else {
                            Text(
                                "Hydrogen ने अभी जनरेशन शुरू नहीं किया है (ज़्यादा लोड)। आप Hydrogen के फ्री होने का इंतज़ार कर सकते हैं या अर्जेंसी में नीचे से दूसरा मॉडल चुन सकते हैं:",
                                color = Color(0xFFFDE68A),
                                fontSize = 12.sp
                            )
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF3B2B0E))
                                    .border(1.dp, Color(0xFFFFB84D), RoundedCornerShape(12.dp))
                                    .clickable { MeeraCodePatcher.startHydrogenBackgroundWaiter(ctx) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("⏳ Wait for Hydrogen (Auto-Resume)", color = Color(0xFFFFB84D), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }

                            val urgentAlternatives = ui.curatedModels.filter { it != "gemini-4-hydrogen" }
                            val pendingJob = ui.pendingHydrogenJob
                            if (urgentAlternatives.isNotEmpty() && pendingJob != null) {
                                Text("⚡ Urgent — अभी दूसरे मॉडल से करवाएँ:", color = palette.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    urgentAlternatives.forEach { altId ->
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF5B8CFF))
                                                .clickable {
                                                    MeeraCodePatcher.generateAiPatchFromInstruction(
                                                        ctx = ctx,
                                                        instruction = pendingJob.instruction,
                                                        preferredModel = altId,
                                                        allowAutoFallback = false,
                                                        extraContext = pendingJob.extraContext
                                                    )
                                                }
                                                .padding(horizontal = 12.dp, vertical = 9.dp)
                                        ) {
                                            Text(
                                                "⚡ ${MeeraCodePatcher.friendlyModelLabel(altId)}",
                                                color = palette.textPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (ui.statusText.isNotBlank()) {
                Surface(
                    color = palette.panel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.cardBorder, RoundedCornerShape(14.dp))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (ui.activeModelText.isNotBlank()) {
                            Text(ui.activeModelText, color = Color(0xFF2EE6A8), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(ui.statusText, color = palette.textPrimary, fontSize = 12.5.sp)
                    }
                }
            }

            // Universal Studio Error Report Card (with Top Download Button + Top/End Quick Jump!)
            if (ui.studioErrorReportText.isNotBlank()) {
                Surface(
                    color = Color(0xFF260D14),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFFF6B6B), RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "🚨 ${ui.studioErrorTitle.ifBlank { "Studio Error Report" }}",
                                color = Color(0xFFFF6B6B),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "✕",
                                color = Color(0xFFFF9494),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { MeeraCodePatcher.clearStudioError() }
                                    .padding(4.dp)
                            )
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF2EE6A8))
                                    .clickable {
                                        MeeraCodePatcher.downloadStudioErrorReportNow(ctx, ui.studioErrorReportText, "meera_studio_error")
                                    }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("⬇️ Download .txt", color = Color(0xFF090D18), fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF16203A))
                                    .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                                    .clickable {
                                        val uri = ui.studioErrorReportUri
                                        if (uri != null) {
                                            MeeraRepoExporter.launchShareSheet(ctx, uri)
                                        } else {
                                            val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cb.setPrimaryClip(ClipData.newPlainText("StudioErrorReport", ui.studioErrorReportText))
                                            Toast.makeText(ctx, "Report कॉपी हो गई!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📤 Share", color = Color(0xFF38BDF8), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF16203A))
                                    .border(1.dp, Color(0xFF94A3B8), RoundedCornerShape(10.dp))
                                    .clickable {
                                        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cb.setPrimaryClip(ClipData.newPlainText("StudioErrorReport", ui.studioErrorReportText))
                                        Toast.makeText(ctx, "पूरी रिपोर्ट कॉपी हो गई!", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📋 Copy", color = palette.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (ui.studioErrorSavedName.isNotBlank()) {
                            Text("✅ सेव हो गई: ${ui.studioErrorSavedName}", color = Color(0xFF2EE6A8), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("पूरा एरर लॉग:", color = Color(0xFFFFB4B4), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "⬆️ ऊपर (Top)",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        scope.launch { errorScrollState.animateScrollTo(0) }
                                    }
                                )
                                Text(
                                    "⬇️ नीचे (End)",
                                    color = Color(0xFF2EE6A8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        scope.launch { errorScrollState.animateScrollTo(errorScrollState.maxValue) }
                                    }
                                )
                            }
                        }

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 90.dp, max = 260.dp)
                                .background(Color(0xFF14070B), RoundedCornerShape(10.dp))
                                .border(1.dp, Color(0xFF4C1D29), RoundedCornerShape(10.dp))
                                .padding(8.dp)
                                .verticalScroll(errorScrollState)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                ui.studioErrorReportText,
                                color = Color(0xFFFFB4B4),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            if (ui.previews.isNotEmpty()) {
                Surface(
                    color = palette.card,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF2EE6A8).copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("2. बदलावों का प्रीव्यू (${ui.previews.size} फ़ाइलें):", color = Color(0xFF2EE6A8), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Commit: ${ui.commitMessage}", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                        ui.previews.forEach { p ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF070B14), RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${p.action}: ${p.filePath.substringAfterLast("/")}", color = palette.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                    Text("+${p.linesAdded} / -${p.linesRemoved}", color = Color(0xFF2EE6A8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(p.validationNote, color = if (p.valid) Color(0xFF2EE6A8) else Color(0xFFFF6B6B), fontSize = 11.sp)
                                Box(Modifier.horizontalScroll(rememberScrollState())) {
                                    Text(p.diffSnippet, color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        if (ui.validationErrors.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669))))
                                    .clickable(enabled = !ui.busy) { MeeraCodePatcher.confirmAndPushAtomic(ctx, onOpenTokenVault) }
                                    .padding(vertical = 13.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🚀 Confirm & Push to v2-test", color = palette.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }

            if (ui.buildStatus.isNotBlank()) {
                val borderCol = when (ui.buildStatus) {
                    "SUCCESS" -> Color(0xFF2EE6A8)
                    "FAILURE" -> Color(0xFFFF6B6B)
                    else -> Color(0xFFFFB84D)
                }
                Surface(
                    color = palette.card,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderCol, RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("3. GitHub Actions APK Build Status:", color = borderCol, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        Text(ui.buildSummaryText, color = palette.textPrimary, fontSize = 12.5.sp)
                        if (ui.buildStatus == "SUCCESS") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF2EE6A8))
                                    .clickable { MeeraUpdater.checkAndInstallUpdate(ctx) }
                                    .padding(vertical = 11.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("⬇️ नया APK इंस्टॉल करें (1-Tap Update)", color = Color(0xFF090D18), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        if (ui.buildStatus == "FAILURE") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF2EE6A8))
                                        .clickable {
                                            MeeraCodePatcher.downloadStudioErrorReportNow(
                                                ctx,
                                                ui.failureReportText,
                                                "meera_build_${ui.buildRunNumber}_failure"
                                            )
                                        }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("⬇️ Download Report", color = Color(0xFF090D18), fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF16203A))
                                        .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                                        .clickable {
                                            val uri = ui.failureReportUri
                                            if (uri != null) {
                                                MeeraRepoExporter.launchShareSheet(ctx, uri)
                                            } else {
                                                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cb.setPrimaryClip(ClipData.newPlainText("BuildFailureReport", ui.failureReportText))
                                                Toast.makeText(ctx, "Report कॉपी हो गई!", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("📤 Share Report", color = Color(0xFF38BDF8), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (ui.failureCompilerError.isNotBlank()) {
                                Text(
                                    ui.failureCompilerError,
                                    color = Color(0xFFFF9494),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF1F0A10), RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                )
                            }

                            Text("🤖 किस मॉडल से Build Error फिक्स करवाना है चुनें:", color = Color(0xFF2EE6A8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ui.curatedModels.forEachIndexed { idx, mId ->
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (idx == 0) Color(0xFF5B8CFF) else Color(0xFF16203A))
                                            .border(1.dp, if (idx == 0) Color(0xFF2EE6A8) else Color(0xFF38BDF8), RoundedCornerShape(10.dp))
                                            .clickable(enabled = !ui.busy) {
                                                currentTab = 0
                                                MeeraCodePatcher.triggerBuildAutoFix(ctx, mId, allowFallback = false)
                                            }
                                            .padding(horizontal = 11.dp, vertical = 9.dp)
                                    ) {
                                        Text(
                                            "${MeeraCodePatcher.friendlyModelLabel(mId)} Fix",
                                            color = palette.textPrimary,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (ui.previousCommitSha.isNotBlank()) {
                TextButton(
                    enabled = !ui.busy,
                    onClick = { MeeraCodePatcher.rollbackLastPush(ctx) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "↩️ Undo Last Push (पिछला वर्किंग वर्ज़न ${ui.previousCommitSha.take(7)} वापस लाएँ)",
                        color = Color(0xFFFFB84D),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

