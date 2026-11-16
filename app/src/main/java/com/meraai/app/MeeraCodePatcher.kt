package com.meraai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.util.zip.ZipInputStream

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
    val commitMessage: String = "Update Meera AI via In-App Studio",
    val previews: List<FilePatchPreview> = emptyList(),
    val validationErrors: List<String> = emptyList(),
    val pushedCommitSha: String = "",
    val previousCommitSha: String = "",
    val buildStatus: String = "",
    val buildRunNumber: Int = 0,
    val buildSummaryText: String = "",
    val failureCompilerError: String = "",
    val failureReportText: String = "",
    val failureReportUri: Uri? = null,
    val pythonOutput: String = ""
)

object MeeraCodePatcher {
    private const val OWNER = "rahulvyas031999-boop"
    private const val REPO = "Meera-ai"
    private const val BRANCH = "v2-test"
    private const val PREF_NAME = "meera_code_patcher_prefs"
    private const val KEY_PREV_SHA = "prev_head_sha"

    val screenVisible = MutableStateFlow(false)
    val state = MutableStateFlow(PatcherUiState())

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun openScreen() { screenVisible.value = true }
    fun closeScreen() { screenVisible.value = false }

    fun clearPreview() {
        state.value = state.value.copy(previews = emptyList(), validationErrors = emptyList(), statusText = "")
    }

    private fun ghRequest(url: String, token: String): Request.Builder {
        val b = Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", "MeeraAI-Patcher")
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
        } catch (_: Exception) { emptyList() }
    }

    private fun validateSourceCode(filePath: String, code: String): Pair<Boolean, String> {
        if (code.isBlank()) return false to "फ़ाइल खाली है"
        if (Regex("(?m)^\\s*(?://|#|/\\*)\\s*\\.\\.\\.").containsMatchIn(code)) return false to "कोड में अधूरे '...' प्लेसहोल्डर मिले हैं"
        if (filePath.endsWith(".kt") && !code.contains("package com.meraai.app")) return false to "Kotlin फ़ाइल में 'package com.meraai.app' गायब है"
        if (code.count { it == '{' } != code.count { it == '}' }) return false to "Curly braces '{ }' असंतुलित हैं"
        if (code.count { it == '(' } != code.count { it == ')' }) return false to "Parentheses '( )' असंतुलित हैं"
        return true to "सिंटैक्स और ब्रैकेट्स बिल्कुल सही हैं"
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

    fun generateAiPatchFromInstruction(ctx: Context, instruction: String) {
        val cleanInst = instruction.trim()
        if (cleanInst.isBlank()) return
        val apiKey = Prefs(ctx).apiKey
        if (apiKey.isBlank()) {
            Toast.makeText(ctx, "पहले साइड मेन्यू से Gemini API Key डालें", Toast.LENGTH_LONG).show()
            return
        }

        val ghToken = MeeraTokenVault.getToken(ctx)
        val bgId = MeeraBackgroundMonitor.startTask("AI Patch: ${cleanInst.take(40)}", "In-App Code Studio", "gemini-4-hydrogen", "GitHub से फ़ाइलें पढ़ी जा रही हैं...", 15)
        state.value = state.value.copy(busy = true, statusText = "🔍 GitHub से ताज़ा फ़ाइलें पढ़ी जा रही हैं...", previews = emptyList(), validationErrors = emptyList())

        Thread {
            try {
                val allFiles = fetchRepoFileList(ghToken)
                val targetCandidates = chooseTargetFiles(apiKey, cleanInst, allFiles)
                if (targetCandidates.isEmpty()) throw RuntimeException("कौन सी फ़ाइल बदलनी है यह समझ नहीं आया। लिखें कि किस फ़ाइल (जैसे Ui.kt) में बदलाव चाहिए।")

                val contextBuilder = StringBuilder()
                for (path in targetCandidates) {
                    val liveCode = fetchLiveFileFromBranch(path, ghToken) ?: continue
                    contextBuilder.append("\n=== FILE: $path ===\n").append(liveCode).append("\n")
                }

                MeeraBackgroundMonitor.updateProgress(bgId, 45, "gemini-4-hydrogen सटीक टारगेटेड पैच तैयार कर रहा है...")
                state.value = state.value.copy(statusText = "🧠 gemini-4-hydrogen सटीक बदलाव तैयार कर रहा है...")

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
""".trimIndent()

                val userPrompt = "USER REQUEST:\n$cleanInst\n\nAVAILABLE REPOSITORY FILES:\n${allFiles.joinToString("\n")}\n\nLIVE FILE CONTENTS:\n$contextBuilder"

                val reqBody = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysPrompt))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))))
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1))

                val rawJson = callPatchModels(apiKey, reqBody)

                MeeraBackgroundMonitor.updateProgress(bgId, 80, "पैच का प्रीव्यू और सिंटैक्स जाँच हो रही है...")
                preparePreviewFromPatchJson(ctx, rawJson, bgId)
            } catch (e: Exception) {
                MeeraBackgroundMonitor.completeTask(bgId, false, "पैच जनरेट फेल: ${e.message}")
                state.value = state.value.copy(busy = false, statusText = "❌ एरर: ${e.message}")
            }
        }.start()
    }

    fun preparePreviewFromPatchJson(ctx: Context, rawInput: String, existingBgId: Long = -1L) {
        val cleanJson = rawInput.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").replace("\u00A0", " ").trim()
        if (cleanJson.isBlank()) return
        val ghToken = MeeraTokenVault.getToken(ctx)
        val bgId = if (existingBgId > 0) existingBgId else MeeraBackgroundMonitor.startTask("Validate Patch", "In-App Code Studio", "Syntax Guard", "GitHub से फ़ाइलें मिलाकर प्रीव्यू बन रहा है...", 30)
        state.value = state.value.copy(busy = true, statusText = "🔍 फ़ाइलों की जाँच हो रही है...", previews = emptyList(), validationErrors = emptyList())

        Thread {
            try {
                val rootObj = JSONObject(cleanJson)
                val commitMsg = rootObj.optString("commit_message", "Update Meera AI").trim()
                val changesArr = rootObj.optJSONArray("changes") ?: throw RuntimeException("JSON में 'changes' लिस्ट नहीं मिली।")
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
                MeeraBackgroundMonitor.completeTask(bgId, errorList.isEmpty() && previewList.isNotEmpty(), if (errorList.isEmpty()) "100% प्रीव्यू तैयार" else "जाँच में ${errorList.size} त्रुटि मिली")
                state.value = state.value.copy(busy = false, commitMessage = commitMsg, previews = previewList, validationErrors = errorList, previousCommitSha = prevSha,
                    statusText = if (errorList.isEmpty() && previewList.isNotEmpty()) "✅ प्रीव्यू तैयार है! नीचे बदलाव देखकर 'Confirm & Push' दबाएँ।" else "⚠️ पुश रोका गया: पहले नीचे दी गई त्रुटि ठीक करें।")
            } catch (e: Exception) {
                MeeraBackgroundMonitor.completeTask(bgId, false, "JSON पार्स एरर: ${e.message}")
                state.value = state.value.copy(busy = false, validationErrors = listOf("अमान्य Patch फ़ॉर्मेट: ${e.message}"), statusText = "❌ Patch पढ़ने में दिक्कत आई")
            }
        }.start()
    }

    private val longHttp = http.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()

    private fun callPatchModels(apiKey: String, reqBody: JSONObject): String {
        val models = listOf("gemini-4-hydrogen", "gemini-3.1-pro-preview", "gemini-2.5-pro")
        var lastErr = "कोई जवाब नहीं मिला"
        for (m in models) {
            try {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(reqBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val text = longHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastErr = "$m: HTTP ${resp.code} ${resp.body?.string()?.take(150).orEmpty()}"
                        return@use ""
                    }
                    val j = JSONObject(resp.body!!.string())
                    val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                    sb.toString().trim()
                }
                if (text.isNotBlank()) return text
                if (!lastErr.startsWith("$m:")) lastErr = "$m: खाली जवाब"
            } catch (e: Exception) {
                lastErr = "$m: ${e.message}"
            }
        }
        throw RuntimeException(lastErr)
    }

    private fun chooseTargetFiles(apiKey: String, instruction: String, allFiles: List<String>): List<String> {
        val chosen = LinkedHashSet<String>()
        Regex("""([A-Za-z0-9_]+\.(?:kt|xml|kts))""").findAll(instruction).forEach { m ->
            val name = m.groupValues[1]
            allFiles.filter { it.substringAfterLast("/") == name }.forEach { chosen.add(it) }
        }
        if (chosen.size < 3) {
            try {
                val prompt = "You help edit an Android Kotlin repository. From the file list below, choose the files (at most 3) that must be edited to fulfil the user request. " +
                    "Reply ONLY with JSON like {\"files\": [\"path1\", \"path2\"]} using exact paths from the list.\n\nFILES:\n" +
                    allFiles.joinToString("\n") + "\n\nUSER REQUEST:\n" + instruction.take(3000)
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.0))
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val j = JSONObject(resp.body!!.string())
                        val txt = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "").orEmpty()
                        val arr = JSONObject(txt.trim()).optJSONArray("files")
                        if (arr != null) for (i in 0 until arr.length()) {
                            val p = arr.optString(i)
                            if (p in allFiles) chosen.add(p)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return chosen.take(4)
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
            state.value = state.value.copy(statusText = "⚠️ फ़ाइल का पाथ और कोड दोनों भरें", validationErrors = emptyList(), previews = emptyList())
            return
        }
        val ghToken = MeeraTokenVault.getToken(ctx)
        val bgId = MeeraBackgroundMonitor.startTask("Direct File: ${filePath.substringAfterLast("/")}", "In-App Code Studio", "Raw File", "GitHub से मौजूदा फ़ाइल पढ़ी जा रही है...", 30)
        state.value = state.value.copy(busy = true, statusText = "🔍 फ़ाइल की जाँच हो रही है...", previews = emptyList(), validationErrors = emptyList())

        Thread {
            try {
                val errorList = mutableListOf<String>()
                if (filePath.contains("..") || filePath.contains("\\")) errorList.add("$filePath: पाथ सही नहीं है")
                val (httpCode, oldText) = fetchFileWithStatus(filePath, ghToken)
                var action = "OVERWRITE"
                var oldContent = ""
                when {
                    httpCode == 200 -> oldContent = oldText
                    httpCode == 404 -> {
                        action = "CREATE"
                        val sameName = fetchRepoFileList(ghToken).filter { it.substringAfterLast("/") == filePath.substringAfterLast("/") }
                        if (sameName.isNotEmpty()) errorList.add("इस नाम की फ़ाइल पहले से यहाँ है: ${sameName.joinToString()} — पूरा सही पाथ लिखें, वरना डुप्लीकेट फ़ाइल बन जाएगी")
                    }
                    else -> errorList.add("GitHub से मौजूदा फ़ाइल पढ़ नहीं पाया (HTTP $httpCode) — पुश रोका गया ताकि फ़ाइल गलती से न मिटे")
                }
                if (oldContent.isNotBlank()) {
                    val oldLines = oldContent.split("\n").size
                    val newLines = newCode.split("\n").size
                    if (newLines * 100 < oldLines * 60) errorList.add("नई फ़ाइल में $newLines लाइनें हैं, पुरानी में $oldLines थीं — शायद सिर्फ़ टुकड़ा पेस्ट हुआ है। पूरी फ़ाइल पेस्ट करें")
                }
                val (ok, note) = validateSourceCode(filePath, newCode)
                if (!ok) errorList.add("$filePath: $note")
                val (addCnt, remCnt, diffTxt) = buildCompactDiffSnippet(oldContent, newCode)
                val preview = FilePatchPreview(filePath, action, addCnt, remCnt, diffTxt, newCode, ok && errorList.isEmpty(), note)

                val prevSha = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_PREV_SHA, "").orEmpty()
                val good = errorList.isEmpty()
                MeeraBackgroundMonitor.completeTask(bgId, good, if (good) "100% प्रीव्यू तैयार" else "जाँच में ${errorList.size} त्रुटि मिली")
                state.value = state.value.copy(busy = false, commitMessage = "Update ${filePath.substringAfterLast("/")} via Direct Push", previews = listOf(preview), validationErrors = errorList, previousCommitSha = prevSha,
                    statusText = if (good) "✅ प्रीव्यू तैयार है! नीचे बदलाव देखकर 'Confirm & Push' दबाएँ।" else "⚠️ पुश रोका गया: पहले नीचे दी गई त्रुटि ठीक करें।")
            } catch (e: Exception) {
                MeeraBackgroundMonitor.completeTask(bgId, false, "एरर: ${e.message}")
                state.value = state.value.copy(busy = false, validationErrors = listOf("त्रुटि: ${e.message}"), statusText = "❌ फ़ाइल जाँच में दिक्कत आई")
            }
        }.start()
    }

    fun runPythonScript(ctx: Context, scriptCode: String) {
        val script = scriptCode.replace("\r\n", "\n")
        if (script.isBlank()) return
        if (script.contains("getpass") || script.contains("input(")) {
            state.value = state.value.copy(statusText = "⚠️ इस स्क्रिप्ट में getpass/input() है (कीबोर्ड से टोकन पूछती है)। इस टैब में कीबोर्ड नहीं है, इसलिए यह यहाँ नहीं चलेगी। इसे सीधे Termux ऐप में चलाएँ।", pythonOutput = "")
            return
        }
        val bgId = MeeraBackgroundMonitor.startTask("Python Runner", "Termux", "Python3", "स्क्रिप्ट चलाई जा रही है...", 20)
        state.value = state.value.copy(busy = true, statusText = "⏳ स्क्रिप्ट Termux में चल रही है (2 मिनट तक इंतज़ार)...", pythonOutput = "")

        Thread {
            try {
                val delim = "MEERA_PY_END_7Q2"
                val command = "cat << '$delim' > ~/meera_run.py\n$script\n$delim\ncd ~ && python3 -u meera_run.py 2>&1"
                val res = MeeraTermuxEngine.execute(ctx, command, background = true, waitMs = 120000L, autoOpenUi = false)
                val raw = res.rawDataForAi
                val finished = raw.startsWith("VERIFIED_SUCCESS: Termux executed")
                val stillRunning = raw.startsWith("VERIFIED_SUCCESS: Dispatched")
                val out = if (raw.contains("FULL_OUTPUT:")) raw.substringAfter("FULL_OUTPUT:").substringBefore("INSTRUCTIONS:").trim() else raw
                var hint = ""
                if (!finished && !stillRunning && (out.contains("not found") || out.contains("No such file"))) {
                    hint = "\n\n💡 अगर 'python3 not found' दिखे तो Termux में यह चलाएँ: pkg install python git"
                }
                val status = when {
                    finished -> "✅ स्क्रिप्ट पूरी हुई (exit=0)"
                    stillRunning -> "⏳ स्क्रिप्ट अभी भी चल रही है — नतीजा Background Activity में देखें"
                    else -> "❌ स्क्रिप्ट फेल हुई — नीचे आउटपुट देखें"
                }
                MeeraBackgroundMonitor.completeTask(bgId, finished, status, out.take(2000))
                state.value = state.value.copy(busy = false, statusText = status, pythonOutput = (out + hint).take(6000))
            } catch (e: Exception) {
                MeeraBackgroundMonitor.completeTask(bgId, false, "Python Runner एरर: ${e.message}")
                state.value = state.value.copy(busy = false, statusText = "❌ एरर: ${e.message}")
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

        val bgId = MeeraBackgroundMonitor.startTask("GitHub Push: ${current.commitMessage.take(35)}", "GitHub Push", "Git Trees API", "v2-test का वर्तमान कमिट पढ़ा जा रहा है...", 15)
        state.value = current.copy(busy = true, statusText = "🚀 GitHub पर एटॉमिक पुश हो रहा है...", buildStatus = "", failureReportText = "")

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
                    treeArr.put(JSONObject().put("path", p.filePath).put("mode", "100644").put("type", "blob").put("content", p.finalContent))
                }
                val treeBody = JSONObject().put("base_tree", baseTreeSha).put("tree", treeArr)

                val newTreeSha = http.newCall(ghRequest("https://api.github.com/repos/$OWNER/$REPO/git/trees", ghToken).post(treeBody.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("Create tree failed (HTTP ${resp.code})")
                    JSONObject(resp.body!!.string()).getString("sha")
                }

                MeeraBackgroundMonitor.updateProgress(bgId, 70, "नया कमिट तैयार हो रहा है...")

                val newCommitBody = JSONObject().put("message", current.commitMessage).put("tree", newTreeSha).put("parents", JSONArray().put(headSha))
                val newCommitSha = http.newCall(ghRequest("https://api.github.com/repos/$OWNER/$REPO/git/commits", ghToken).post(newCommitBody.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("Create commit failed (HTTP ${resp.code})")
                    JSONObject(resp.body!!.string()).getString("sha")
                }

                val patchRefBody = JSONObject().put("sha", newCommitSha).put("force", false)
                http.newCall(ghRequest(refUrl.replace("/git/ref/", "/git/refs/"), ghToken).patch(patchRefBody.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("Update branch ref failed (HTTP ${resp.code})")
                }

                ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_PREV_SHA, headSha).apply()
                MeeraBackgroundMonitor.completeTask(bgId, true, "100% पुश सफल (${newCommitSha.take(7)})")
                state.value = state.value.copy(busy = false, previews = emptyList(), pushedCommitSha = newCommitSha, previousCommitSha = headSha,
                    buildStatus = "WATCHING", buildSummaryText = "⏳ Commit ${newCommitSha.take(7)} पुश हो गया! APK बिल्ड की निगरानी हो रही है...", statusText = "✅ GitHub पर पुश सफल!")
                watchBuildForCommit(ctx.applicationContext, newCommitSha, ghToken)
            } catch (e: Exception) {
                MeeraBackgroundMonitor.completeTask(bgId, false, "GitHub Push विफल: ${e.message}")
                state.value = state.value.copy(busy = false, statusText = "❌ Push एरर: ${e.message}")
            }
        }.start()
    }

    private fun watchBuildForCommit(app: Context, targetSha: String, token: String) {
        val buildTaskId = MeeraBackgroundMonitor.startTask("APK Build (${targetSha.take(7)})", "GitHub CI", "Gradle", "GitHub Actions रनर शुरू होने का इंतज़ार...", 10)
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
                                    if (st == "completed") finalConclusion = conc
                                    else {
                                        val pct = (15 + attempt * 3).coerceAtMost(85)
                                        MeeraBackgroundMonitor.updateProgress(buildTaskId, pct, "Build #$matchedRunNumber कम्पाइल हो रहा है ($st)...")
                                        state.value = state.value.copy(buildRunNumber = matchedRunNumber, buildSummaryText = "⚙️ Build #$matchedRunNumber चल रहा है ($pct%)...")
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
                    state.value = state.value.copy(buildStatus = "SUCCESS", buildRunNumber = matchedRunNumber, buildSummaryText = "🎉 APK Build #$matchedRunNumber सफल (100%)! नीचे बटन दबाकर नया APK इंस्टॉल करें।")
                } else if (finalConclusion.isNotBlank()) {
                    MeeraBackgroundMonitor.updateProgress(buildTaskId, 90, "Build #$matchedRunNumber फेल हुआ — एरर लॉग निकल रही है...")
                    generateInAppFailureComparisonReport(app, matchedRunId, matchedRunNumber, targetSha, allRunsCache, token, buildTaskId)
                } else {
                    MeeraBackgroundMonitor.completeTask(buildTaskId, false, "Build निगरानी टाइमआउट हो गई।")
                    state.value = state.value.copy(buildStatus = "FAILURE", buildSummaryText = "⚠️ बिल्ड को 8 मिनट से ज़्यादा समय लगा।")
                }
            } catch (e: Exception) { MeeraBackgroundMonitor.completeTask(buildTaskId, false, "Build Watcher एरर: ${e.message}") }
        }.start()
    }

    private fun generateInAppFailureComparisonReport(app: Context, failedRunId: Long, failedRunNumber: Int, failSha: String, runsArr: JSONArray, token: String, buildTaskId: Long) {
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
                                    val lines = txt.lines().map { ln -> if (ln.length > 29 && ln[4] == '-' && ln[27] == 'Z') ln.substring(29) else ln }
                                    for (idx in lines.indices) {
                                        val cln = lines[idx]
                                        if (cln.startsWith("e: ") || cln.contains("FAILURE:") || cln.contains("What went wrong:")) {
                                            val snip = lines.subList(idx, minOf(idx + 4, lines.size)).joinToString("\n")
                                            if (snip !in errorLines) errorLines.add(snip)
                                        }
                                    }
                                    if (entry.name.contains("Build debug APK")) logTail.append(lines.takeLast(120).joinToString("\n"))
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
                            sb.append("FILE: ").append(f.optString("filename")).append(" (+").append(f.optInt("additions")).append("/-").append(f.optInt("deletions")).append(")\n")
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

            val shareUri = saveFailureReportFile(app, fullReport.toByteArray(Charsets.UTF_8))
            MeeraBackgroundMonitor.completeTask(buildTaskId, false, "Build #$failedRunNumber फेल — पूरी Report तैयार!", topErrorSummary)
            state.value = state.value.copy(buildStatus = "FAILURE", buildRunNumber = failedRunNumber, buildSummaryText = "❌ APK Build #$failedRunNumber फेल हो गया।", failureCompilerError = topErrorSummary, failureReportText = fullReport, failureReportUri = shareUri)
        } catch (e: Exception) { MeeraBackgroundMonitor.completeTask(buildTaskId, false, "Report error: ${e.message}") }
    }

    private fun saveFailureReportFile(app: Context, data: ByteArray): Uri? {
        val fileName = "meera_build_failure_report.txt"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) app.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            }
        } catch (_: Exception) {}
        return try {
            val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.cacheDir
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, fileName)
            f.writeBytes(data)
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
        } catch (_: Exception) { null }
    }

    fun rollbackLastPush(ctx: Context) {
        val prevSha = state.value.previousCommitSha.ifBlank { ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_PREV_SHA, "").orEmpty() }
        val ghToken = MeeraTokenVault.getToken(ctx)
        if (prevSha.isBlank() || ghToken.isBlank()) return

        state.value = state.value.copy(busy = true, statusText = "↩️ पिछला वर्किंग वर्ज़न (${prevSha.take(7)}) वापस लाया जा रहा है...")
        Thread {
            try {
                val refUrl = "https://api.github.com/repos/$OWNER/$REPO/git/ref/heads/$BRANCH"
                val body = JSONObject().put("sha", prevSha).put("force", true)
                http.newCall(ghRequest(refUrl.replace("/git/ref/", "/git/refs/"), ghToken).patch(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("Rollback HTTP ${resp.code}")
                }
                state.value = state.value.copy(busy = false, buildStatus = "", failureCompilerError = "", statusText = "✅ Rollback सफल! v2-test पिछले वर्किंग वर्ज़न पर आ गया है।")
            } catch (e: Exception) { state.value = state.value.copy(busy = false, statusText = "❌ Rollback फेल: ${e.message}") }
        }.start()
    }
}

@Composable
fun MeeraCodePatcherScreen(onBack: () -> Unit, onOpenTokenVault: () -> Unit) {
    val ctx = LocalContext.current
    val ui by MeeraCodePatcher.state.collectAsState()
    var currentTab by remember { mutableStateOf(0) }
    var inputBox by remember { mutableStateOf("") }
    var pyScript by remember { mutableStateOf("") }
    var rawPath by remember { mutableStateOf("") }
    var rawCode by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val hasToken = remember(ui.busy) { MeeraTokenVault.hasToken(ctx) }
    val readClip = { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty() }

    Column(Modifier.fillMaxSize().background(Color(0xFF090D18)).systemBarsPadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFF16203A), shape = CircleShape, modifier = Modifier.size(40.dp).border(1.dp, Color(0xFF26355E), CircleShape).clickable(onClick = onBack)) {
                    Box(contentAlignment = Alignment.Center) { Text("←", color = Color.White, fontSize = 20.sp) }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Code Push & Build Studio", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Target: v2-test • Live CI", color = Color(0xFF2EE6A8), fontSize = 11.sp)
                }
            }
            Surface(color = if (hasToken) Color(0xFF16203A) else Color(0xFF3B1219), shape = RoundedCornerShape(12.dp), modifier = Modifier.border(1.dp, if (hasToken) Color(0xFF2EE6A8) else Color(0xFFFF6B6B), RoundedCornerShape(12.dp)).clickable(onClick = onOpenTokenVault)) {
                Text(if (hasToken) "🔐 Token ✓" else "🔑 Token डालें", color = if (hasToken) Color(0xFF2EE6A8) else Color(0xFFFF9494), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }

        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF16203A))) {
            listOf("🧠 AI Patch", "🐍 Python", "📄 Raw Kotlin").forEachIndexed { idx, title ->
                val active = currentTab == idx
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (active) Color(0xFF5B8CFF) else Color.Transparent).clickable { if (currentTab != idx) { currentTab = idx; MeeraCodePatcher.clearPreview() } }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(title, color = if (active) Color.White else Color(0xFF8A94B8), fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (currentTab == 0) {
                Surface(color = Color(0xFF12192E), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF26355E), RoundedCornerShape(16.dp))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("1. क्या बदलाव करना है लिखें या Patch JSON पेस्ट करें:", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = inputBox, onValueChange = { inputBox = it }, placeholder = { Text("उदाहरण: Config.kt में चैट मॉडल का नाम बदल दो", color = Color(0xFF8A94B8), fontSize = 12.sp) }, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF5B8CFF), Color(0xFF38BDF8)))).clickable(enabled = !ui.busy) { MeeraCodePatcher.generateAiPatchFromInstruction(ctx, inputBox) }.padding(vertical = 10.dp, horizontal = 8.dp), contentAlignment = Alignment.Center) { Text("✨ AI से बदलाव बनाएँ", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF16203A)).border(1.dp, Color(0xFF2EE6A8), RoundedCornerShape(12.dp)).clickable(enabled = !ui.busy) { MeeraCodePatcher.preparePreviewFromPatchJson(ctx, inputBox) }.padding(vertical = 10.dp, horizontal = 8.dp), contentAlignment = Alignment.Center) { Text("🔍 प्रीव्यू व जाँच करें", color = Color(0xFF2EE6A8), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { val clip = readClip(); if (clip.isNotBlank()) inputBox = clip }) { Text("📋 क्लिपबोर्ड से पेस्ट करें", color = Color(0xFF38BDF8), fontSize = 12.sp) }
                            if (inputBox.isNotBlank() || ui.previews.isNotEmpty()) { TextButton(onClick = { inputBox = ""; MeeraCodePatcher.clearPreview() }) { Text("साफ़ करें", color = Color(0xFF8A94B8), fontSize = 12.sp) } }
                        }
                    }
                }
            }

            if (currentTab == 1) {
                Surface(color = Color(0xFF12192E), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF26355E), RoundedCornerShape(16.dp))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Python स्क्रिप्ट पेस्ट करें (Termux में चलेगी):", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        Text("ध्यान: इस टैब में कीबोर्ड नहीं है, इसलिए getpass / input() वाली स्क्रिप्ट यहाँ नहीं चलेगी। Termux में python और git लगे होने चाहिए।", color = Color(0xFF8A94B8), fontSize = 11.sp)
                        OutlinedTextField(value = pyScript, onValueChange = { pyScript = it }, placeholder = { Text("print('hello')", color = Color(0xFF8A94B8), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }, minLines = 6, maxLines = 14, modifier = Modifier.fillMaxWidth())
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669)))).clickable(enabled = !ui.busy) { MeeraCodePatcher.runPythonScript(ctx, pyScript) }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Text("▶️ Termux में स्क्रिप्ट चलाएँ", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { val clip = readClip(); if (clip.isNotBlank()) pyScript = clip }) { Text("📋 क्लिपबोर्ड से पेस्ट करें", color = Color(0xFF38BDF8), fontSize = 12.sp) }
                            if (pyScript.isNotBlank()) { TextButton(onClick = { pyScript = "" }) { Text("साफ़ करें", color = Color(0xFF8A94B8), fontSize = 12.sp) } }
                        }
                    }
                }
                if (ui.pythonOutput.isNotBlank()) {
                    Box(Modifier.fillMaxWidth().background(Color(0xFF070B14), RoundedCornerShape(10.dp)).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp)).padding(10.dp).horizontalScroll(rememberScrollState())) {
                        Text(ui.pythonOutput, color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            if (currentTab == 2) {
                Surface(color = Color(0xFF12192E), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF26355E), RoundedCornerShape(16.dp))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("फ़ाइल का पूरा पाथ और उसका पूरा नया कोड पेस्ट करें:", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        Text("सुरक्षा: नई फ़ाइल पुरानी से बहुत छोटी हो, या पुरानी फ़ाइल पढ़ी न जा सके, तो पुश रुक जाएगा।", color = Color(0xFF8A94B8), fontSize = 11.sp)
                        OutlinedTextField(value = rawPath, onValueChange = { rawPath = it }, placeholder = { Text("app/src/main/java/com/meraai/app/Config.kt", color = Color(0xFF8A94B8), fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = rawCode, onValueChange = { rawCode = it }, placeholder = { Text("package com.meraai.app", color = Color(0xFF8A94B8), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }, minLines = 6, maxLines = 14, modifier = Modifier.fillMaxWidth())
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF16203A)).border(1.dp, Color(0xFF2EE6A8), RoundedCornerShape(12.dp)).clickable(enabled = !ui.busy) { MeeraCodePatcher.prepareRawFilePreview(ctx, rawPath, rawCode) }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Text("🔍 प्रीव्यू व जाँच करें", color = Color(0xFF2EE6A8), fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { val clip = readClip(); if (clip.isNotBlank()) rawCode = clip }) { Text("📋 कोड क्लिपबोर्ड से पेस्ट करें", color = Color(0xFF38BDF8), fontSize = 12.sp) }
                            if (rawCode.isNotBlank() || rawPath.isNotBlank()) { TextButton(onClick = { rawCode = ""; rawPath = ""; MeeraCodePatcher.clearPreview() }) { Text("साफ़ करें", color = Color(0xFF8A94B8), fontSize = 12.sp) } }
                        }
                    }
                }
            }

            if (ui.statusText.isNotBlank()) {
                Surface(color = Color(0xFF16203A), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF26355E), RoundedCornerShape(14.dp))) { Text(ui.statusText, color = Color.White, fontSize = 12.5.sp, modifier = Modifier.padding(12.dp)) }
            }

            if (ui.validationErrors.isNotEmpty()) {
                Surface(color = Color(0xFF260D14), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFFF6B6B), RoundedCornerShape(14.dp))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("⚠️ जाँच में ये समस्याएँ मिलीं:", color = Color(0xFFFF6B6B), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        ui.validationErrors.forEach { err -> Text("• $err", color = Color(0xFFFFB4B4), fontSize = 12.sp) }
                    }
                }
            }

            if (ui.previews.isNotEmpty()) {
                Surface(color = Color(0xFF12192E), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2EE6A8).copy(alpha = 0.55f), RoundedCornerShape(16.dp))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("2. बदलावों का प्रीव्यू (${ui.previews.size} फ़ाइलें):", color = Color(0xFF2EE6A8), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Commit: ${ui.commitMessage}", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                        ui.previews.forEach { p ->
                            Column(Modifier.fillMaxWidth().background(Color(0xFF070B14), RoundedCornerShape(10.dp)).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${p.action}: ${p.filePath.substringAfterLast("/")}", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                    Text("+${p.linesAdded} / -${p.linesRemoved}", color = Color(0xFF2EE6A8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(p.validationNote, color = if (p.valid) Color(0xFF2EE6A8) else Color(0xFFFF6B6B), fontSize = 11.sp)
                                Box(Modifier.horizontalScroll(rememberScrollState())) { Text(p.diffSnippet, color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                            }
                        }
                        if (ui.validationErrors.isEmpty()) {
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669)))).clickable(enabled = !ui.busy) { MeeraCodePatcher.confirmAndPushAtomic(ctx, onOpenTokenVault) }.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                                Text("🚀 Confirm & Push to v2-test", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }

            if (ui.buildStatus.isNotBlank()) {
                val borderCol = when (ui.buildStatus) { "SUCCESS" -> Color(0xFF2EE6A8); "FAILURE" -> Color(0xFFFF6B6B); else -> Color(0xFFFFB84D) }
                Surface(color = Color(0xFF12192E), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().border(1.dp, borderCol, RoundedCornerShape(16.dp))) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("3. GitHub Actions APK Build Status:", color = borderCol, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        Text(ui.buildSummaryText, color = Color.White, fontSize = 12.5.sp)
                        if (ui.buildStatus == "SUCCESS") {
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF2EE6A8)).clickable { MeeraUpdater.checkAndInstallUpdate(ctx) }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) { Text("⬇️ नया APK इंस्टॉल करें (1-Tap Update)", color = Color(0xFF090D18), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold) }
                        }
                        if (ui.buildStatus == "FAILURE") {
                            if (ui.failureCompilerError.isNotBlank()) {
                                Text(ui.failureCompilerError, color = Color(0xFFFF9494), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().background(Color(0xFF1F0A10), RoundedCornerShape(8.dp)).padding(8.dp))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF16203A)).border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp)).clickable { val uri = ui.failureReportUri; if (uri != null) MeeraRepoExporter.launchShareSheet(ctx, uri) else { val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; cb.setPrimaryClip(ClipData.newPlainText("BuildFailureReport", ui.failureReportText)); Toast.makeText(ctx, "Report कॉपी हो गई!", Toast.LENGTH_SHORT).show() } }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) { Text("📤 Error Report शेयर करें", color = Color(0xFF38BDF8), fontSize = 11.5.sp, fontWeight = FontWeight.Bold) }
                                Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF5B8CFF)).clickable(enabled = !ui.busy) { currentTab = 0; MeeraCodePatcher.generateAiPatchFromInstruction(ctx, "Fix this exact Kotlin compilation error:\n${ui.failureCompilerError}") }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) { Text("🤖 Hydrogen Auto-Fix", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }

            if (ui.previousCommitSha.isNotBlank()) {
                TextButton(enabled = !ui.busy, onClick = { MeeraCodePatcher.rollbackLastPush(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("↩️ Undo Last Push (पिछला वर्किंग वर्ज़न ${ui.previousCommitSha.take(7)} वापस लाएँ)", color = Color(0xFFFFB84D), fontSize = 12.sp) }
            }
        }
    }
}
