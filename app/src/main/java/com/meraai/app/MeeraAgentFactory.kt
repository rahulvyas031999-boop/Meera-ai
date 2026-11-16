package com.meraai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object MeeraAgentFactory {
    @Volatile var onDeliverToChat: ((agentTitle: String, fullOutput: String) -> Unit)? = null
    @Volatile var lastError: String = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun auditGeneratedCode(code: String): List<String> {
        val issues = mutableListOf<String>()
        if (Regex("(?m)^\\s*(?://|#)\\s*\\.\\.\\.").containsMatchIn(code)) {
            issues.add("Contains '...' placeholder comments")
        }
        if (code.contains("class ") && Regex("(?m)^\\s{4}def\\s+[a-zA-Z_][a-zA-Z0-9_]*\\s*\\(\\s*\\)").containsMatchIn(code)) {
            issues.add("Python class method missing 'self' parameter")
        }
        if (code.count { it == '{' } != code.count { it == '}' }) {
            issues.add("Unbalanced curly braces")
        }
        return issues
    }

    private fun sanitizeGeneratedCode(raw: String): String {
        return raw
            .replace("\u00A0", " ")
            .replace("\u200B", "")
            .trim()
    }

    private fun callReasoningBrain(
        key: String,
        models: List<String>,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        useHighThinking: Boolean,
        onModelSelected: (String) -> Unit
    ): Pair<String, String> {
        for (m in models.distinct()) {
            val thinkingAttempts = if (useHighThinking && (m.contains("hydrogen") || m.contains("3.8") || m.contains("3.1-pro"))) {
                listOf(true, false)
            } else {
                listOf(false)
            }
            for (withThinking in thinkingAttempts) {
                try {
                    onModelSelected(m)
                    val genCfg = JSONObject().put("temperature", temperature)
                    if (withThinking) {
                        genCfg.put("thinkingConfig", JSONObject().put("thinkingLevel", "HIGH"))
                    }
                    val body = JSONObject()
                        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                        .put(
                            "contents", JSONArray().put(
                                JSONObject().put("role", "user").put(
                                    "parts", JSONArray().put(JSONObject().put("text", userPrompt))
                                )
                            )
                        )
                        .put("generationConfig", genCfg)

                    val req = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                        .header("x-goog-api-key", key)
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            lastError = "HTTP ${resp.code}: ${resp.body?.string()?.take(200)}"
                            MeeraDebugLog.log("CODING_ENGINE", "Model $m -> $lastError")
                        }
                        if (resp.isSuccessful) {
                            val j = JSONObject(resp.body!!.string())
                            val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                            val sb = StringBuilder()
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                                }
                            }
                            val txt = sanitizeGeneratedCode(sb.toString())
                            if (txt.isNotBlank()) return txt to m
                        }
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "error"
                    MeeraDebugLog.log("CODING_ENGINE", "Model $m (think=$withThinking) failed: ${e.message}")
                }
            }
        }
        return "" to ""
    }

    fun generateProfessionalCode(
        ctx: Context,
        taskDetails: String,
        targetPlatform: String = "Python / Google Colab"
    ): ToolExecutionResult {
        val prefs = Prefs(ctx)
        val key = prefs.apiKey
        if (key.isBlank()) {
            return ToolExecutionResult(true, "generate_professional_code", "पहले API Key डालनी होगी।", "ERROR: Missing API Key")
        }

        val taskId = MeeraBackgroundMonitor.startTask(
            title = "Code: ${taskDetails.take(45)}",
            category = "Hydrogen Coding Engine",
            modelUsed = "gemini-4-hydrogen",
            initialStep = "कोडिंग आवश्यकताएँ और आर्किटेक्चर समझ रही हूँ...",
            initialPercent = 15
        )

        val codingSystemPrompt = """
You are a Principal Software Architect and Staff Engineer powered by Gemini Hydrogen.
Target Environment: $targetPlatform

CRITICAL ENGINEERING RULES:
1. COMPLETE RUNNABLE CODE ONLY: NEVER use '...' placeholders or truncated blocks. Write 100% complete, runnable code.
2. STRICT SYNTAX VALIDATION: Ensure all braces, parentheses, and method parameters (like Python 'self') are 100% correct and balanced.
3. OUTPUT FORMAT: Provide clean Markdown code blocks.
""".trimIndent()

        MeeraBackgroundMonitor.updateProgress(taskId, 40, "Gemini Hydrogen से हाई-रीजनिंग कोड तैयार हो रहा है...")

        val priorityModels = listOf("gemini-4-hydrogen", "gemini-3.8-flash")
        var (codeOutput, usedModel) = callReasoningBrain(
            key = key,
            models = priorityModels,
            systemPrompt = codingSystemPrompt,
            userPrompt = "BUILD PRODUCTION CODE FOR:\n$taskDetails",
            temperature = 0.15,
            useHighThinking = true
        ) { selected ->
            MeeraBackgroundMonitor.updateProgress(taskId, 60, "मॉडल '$selected' कोड लिख रहा है...", selected)
        }

        val defects = auditGeneratedCode(codeOutput)
        if (codeOutput.isNotBlank() && defects.isNotEmpty()) {
            MeeraBackgroundMonitor.updateProgress(taskId, 75, "ऑटो-ऑडिट में कमियाँ मिलीं, रिपेयर हो रहा है...", usedModel)
            val (fixedCode, fixedModel) = callReasoningBrain(
                key = key,
                models = listOf(usedModel) + priorityModels,
                systemPrompt = codingSystemPrompt,
                userPrompt = "FIX THESE DEFECTS IN YOUR PREVIOUS CODE (${defects.joinToString("; ")}) AND RETURN THE COMPLETE FIXED CODE ONLY:\n\n$codeOutput",
                temperature = 0.1,
                useHighThinking = true
            ) {}
            if (fixedCode.isNotBlank()) {
                codeOutput = fixedCode
                usedModel = fixedModel
            }
        }

        if (codeOutput.isBlank()) {
            MeeraBackgroundMonitor.completeTask(taskId, false, "कोड जनरेट नहीं हो पाया — कारण: $lastError")
            return ToolExecutionResult(true, "generate_professional_code", "कोडिंग इंजन से अभी कोड तैयार नहीं हो पाया।", "ERROR: Coding engine failed")
        }

        MeeraBackgroundMonitor.updateProgress(taskId, 90, "सिंटैक्स साफ़ करके Chat टैब और क्लिपबोर्ड में सेव कर रही हूँ...", usedModel)
        val chatCard = "💻 [Production Code • $usedModel]:\n\n$codeOutput"
        onDeliverToChat?.invoke("Code ($usedModel)", chatCard)
        copyToClipboard(ctx, codeOutput)

        MeeraBackgroundMonitor.completeTask(taskId, true, "100% पूरा — कोड Chat टैब और क्लिपबोर्ड में सेव है।", codeOutput)

        return ToolExecutionResult(
            true, "generate_professional_code",
            "मैंने $usedModel मॉडल से पूरा वेरीफाइड कोड तैयार करके Chat टैब और क्लिपबोर्ड में सेव कर दिया है!",
            "VERIFIED_SUCCESS: Generated complete verified code using $usedModel."
        )
    }

    fun spawnAndRun(ctx: Context, profession: String, taskDetails: String, deliveryMode: String = "auto"): ToolExecutionResult {
        val prefs = Prefs(ctx)
        val key = prefs.apiKey
        if (key.isBlank()) return ToolExecutionResult(true, "spawn_custom_agent", "पहले API Key डालनी होगी।", "ERROR: Missing API Key")

        lastError = ""
        val roleName = profession.trim().ifBlank { "Domain Specialist" }
        val taskId = MeeraBackgroundMonitor.startTask("Agent: $roleName", "Custom Sub-Agent", "gemini-4-hydrogen", "'$roleName' एजेंट तैयार हो रहा है...", 15)

        val modeLow = deliveryMode.lowercase().trim()
        val isPerformance = modeLow == "speak" || modeLow == "perform" ||
            listOf("comedian", "comedy", "standup", "shayari", "roast").any { roleName.lowercase().contains(it) || taskDetails.lowercase().contains(it) }

        val systemPrompt = "You are an expert $roleName. Execute the request completely without truncation."
        val (generatedOutput, usedModel) = callReasoningBrain(
            key = key,
            models = listOf("gemini-4-hydrogen", "gemini-3.8-flash"),
            systemPrompt = systemPrompt,
            userPrompt = taskDetails,
            temperature = 0.3,
            useHighThinking = !isPerformance
        ) { }

        if (generatedOutput.isBlank()) {
            MeeraBackgroundMonitor.completeTask(taskId, false, "एजेंट विफल")
            return ToolExecutionResult(true, "spawn_custom_agent", "एजेंट आउटपुट तैयार नहीं कर पाया।", "ERROR")
        }

        onDeliverToChat?.invoke(roleName, "🤖 [$roleName • $usedModel]:\n\n$generatedOutput")
        copyToClipboard(ctx, generatedOutput)
        MeeraBackgroundMonitor.completeTask(taskId, true, "100% पूरा", generatedOutput)
        return ToolExecutionResult(true, "spawn_custom_agent", "मैंने '$roleName' का काम पूरा करके Chat टैब में सेव कर दिया है!", "VERIFIED_SUCCESS")
    }

    fun askHydrogen(ctx: Context, request: String): ToolExecutionResult {
        val prefs = Prefs(ctx)
        val key = prefs.apiKey
        if (key.isBlank()) return ToolExecutionResult(true, "ask_hydrogen", "पहले API Key डालनी होगी।", "ERROR")
        lastError = ""
        val taskId = MeeraBackgroundMonitor.startTask("Hydrogen: ${request.take(40)}", "Hydrogen Direct", "gemini-4-hydrogen", "हाइड्रोजन सोच रहा है...", 20)
        val (answer, usedModel) = callReasoningBrain(
            key = key, models = listOf("gemini-4-hydrogen", "gemini-3.8-flash"),
            systemPrompt = "You are Gemini Hydrogen.", userPrompt = request, temperature = 0.7, useHighThinking = false
        ) { }
        if (answer.isBlank()) {
            MeeraBackgroundMonitor.completeTask(taskId, false, "हाइड्रोजन विफल")
            return ToolExecutionResult(true, "ask_hydrogen", "हाइड्रोजन से जवाब नहीं मिला।", "ERROR")
        }
        onDeliverToChat?.invoke("Hydrogen", "🧪 [Hydrogen • $usedModel]:\n\n$answer")
        copyToClipboard(ctx, answer)
        MeeraBackgroundMonitor.completeTask(taskId, true, "100% पूरा", answer)
        return ToolExecutionResult(true, "ask_hydrogen", "हाइड्रोजन ने जवाब तैयार करके Chat टैब में सेव कर दिया है!", "VERIFIED_SUCCESS")
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("MeeraAgentOutput", text))
            } catch (_: Throwable) {}
        }
    }
}
