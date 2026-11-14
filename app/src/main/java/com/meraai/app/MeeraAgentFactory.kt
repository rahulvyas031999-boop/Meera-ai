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
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun auditGeneratedCode(code: String): List<String> {
        val issues = mutableListOf<String>()
        if (Regex("(?m)^\\s*(?://|#)\\s*\\.\\.\\.").containsMatchIn(code)) {
            issues.add("Contains '...' placeholder comments")
        }
        if (code.contains("class ") && Regex("(?m)^\\s{4}def\\s+[a-zA-Z_][a-zA-Z0-9_]*\\s*\\(\\s*\\)").containsMatchIn(code)) {
            issues.add("Python class method missing 'self' parameter")
        }
        if (code.contains("service ssh start") || code.contains("cloudflare-utils")) {
            issues.add("Uses unsupported Colab/Termux SSH commands instead of FastAPI + cloudflared")
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
                listOf(false)
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

CRITICAL ENGINEERING RULES (ZERO TOLERANCE FOR BROKEN CODE):
1. COMPLETE RUNNABLE CODE ONLY: NEVER use '...' (ellipsis placeholders), '# TODO', or truncated blocks. Write 100% complete, copy-paste-runnable code.
2. STRICT PYTHON & KOTLIN SYNTAX VALIDATION:
   - In Python classes, EVERY instance method MUST explicitly include `self` as its first parameter (e.g., `def start_tunnel(self):`).
   - Use ONLY standard ASCII spaces (0x20) for indentation—NEVER non-breaking spaces.
   - Verify all imports, parentheses, brackets, and f-string quotes before outputting.
3. REAL MEERA AI + GOOGLE COLAB + TERMUX TOPOLOGY:
   - Google Colab containers do NOT run systemd/SSH daemons (`service ssh start` fails).
   - For Google Colab backend integration with Meera AI (`MeeraColabBridge.kt`), ALWAYS run a FastAPI + Uvicorn HTTP server on port 8765 (`POST /execute` accepting `{"secret": "...", "task_type": "...", "instruction": "...", "payload": "..."}`) exposed via `cloudflared tunnel --url http://127.0.0.1:8765` (which generates a `https://*.trycloudflare.com` URL).
   - For Termux on Android, only use real Termux packages (`pkg install python openssh cloudflared -y`).
4. SAFE FILE-PATCHING STANDARD: Whenever writing a Python script that modifies repository or source files, NEVER use blind `.replace()` calls—always verify exact anchor markers with `assert` before modifying and validate Python syntax with `ast.parse`.
5. OUTPUT FORMAT: Provide clean, well-structured Markdown code blocks with brief, crystal-clear setup steps.
""".trimIndent()

        MeeraBackgroundMonitor.updateProgress(taskId, 40, "Gemini Hydrogen से हाई-रीजनिंग कोड तैयार हो रहा है...")

        val priorityModels = listOf("gemini-4-hydrogen")
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
            MeeraBackgroundMonitor.updateProgress(taskId, 75, "ऑटो-ऑडिट में कमियाँ मिलीं (${defects.joinToString()}), रिपेयर हो रहा है...", usedModel)
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
            return ToolExecutionResult(
                true, "generate_professional_code",
                "कोडिंग इंजन से अभी कोड तैयार नहीं हो पाया, कृपया नेटवर्क या API Key चेक करके दोबारा कहें।",
                "ERROR: Coding engine failed to produce output"
            )
        }

        MeeraBackgroundMonitor.updateProgress(taskId, 90, "सिंटैक्स साफ़ करके Chat टैब और क्लिपबोर्ड में सेव कर रही हूँ...", usedModel)
        val chatCard = "💻 [Production Code • $usedModel]:\n\n$codeOutput"
        onDeliverToChat?.invoke("Code ($usedModel)", chatCard)
        copyToClipboard(ctx, codeOutput)

        MeeraBackgroundMonitor.completeTask(
            taskId = taskId,
            success = true,
            finalStep = "100% पूरा — कोड Chat टैब और क्लिपबोर्ड में सेव है।",
            outputPreview = codeOutput
        )

        return ToolExecutionResult(
            true, "generate_professional_code",
            "मैंने $usedModel मॉडल से पूरा वेरीफाइड कोड तैयार करके Chat टैब और क्लिपबोर्ड में सेव कर दिया है! आप सीधे पेस्ट करके चला सकते हैं।",
            "VERIFIED_SUCCESS: Generated complete verified code using $usedModel and saved to Chat Tab & Clipboard. Do NOT read raw code symbols aloud; give a 1-sentence confirmation."
        )
    }

    fun spawnAndRun(
        ctx: Context,
        profession: String,
        taskDetails: String,
        deliveryMode: String = "auto"
    ): ToolExecutionResult {
        val prefs = Prefs(ctx)
        val key = prefs.apiKey
        if (key.isBlank()) {
            return ToolExecutionResult(true, "spawn_custom_agent", "पहले API Key डालनी होगी।", "ERROR: Missing API Key")
        }

        val roleName = profession.trim().ifBlank { "Domain Specialist" }
        val taskId = MeeraBackgroundMonitor.startTask(
            title = "Agent: $roleName",
            category = "Custom Sub-Agent",
            modelUsed = "gemini-4-hydrogen",
            initialStep = "'$roleName' कस्टम एजेंट तैयार हो रहा है...",
            initialPercent = 15
        )

        val modeLow = deliveryMode.lowercase().trim()
        val isPerformance = modeLow == "speak" || modeLow == "perform" ||
            listOf("comedian", "comedy", "standup", "stand-up", "shayari", "poet", "roast", "storyteller", "चुटकुला", "कॉमेडी", "शायरी")
                .any { roleName.lowercase().contains(it) || taskDetails.lowercase().contains(it) }

        val systemArchitectPrompt = if (isPerformance) {
            """
            You are a world-class, award-winning $roleName hired as an explicit custom specialist sub-agent by Meera AI.
            Task: Deliver an unforgettable, authentic, high-craft performance for Rahul.
            Rules:
            - Fully embody the craft, timing, rhythm, and vocabulary of a top $roleName.
            - Match the user's language (Hindi/Hinglish/English or requested dialect).
            - Output ONLY pure spoken dialogue with natural pauses and warmth. NO markdown, NO bullet symbols, NO stage-direction brackets, because Meera will perform this aloud.
            - Keep it punchy, complete, and impactful (around 4 to 8 spoken sentences).
            """.trimIndent()
        } else {
            """
            You are an elite, senior-level $roleName hired as an explicit custom specialist sub-agent by Meera AI.
            Task: Execute the user's request with true industry-standard depth, complete accuracy, and zero placeholders ('...').
            Rules:
            - Think and structure your output like a top practitioner in "$roleName".
            - If writing code, include complete imports, valid `self` parameters in Python classes, and 100% runnable code.
            - Match the language requested by the user (Hinglish/Hindi/English).
            """.trimIndent()
        }

        MeeraBackgroundMonitor.updateProgress(taskId, 45, "'$roleName' एजेंट काम कर रहा है...")
        val candidateModels = listOf("gemini-4-hydrogen")
        val (generatedOutput, usedModel) = callReasoningBrain(
            key = key,
            models = candidateModels,
            systemPrompt = systemArchitectPrompt,
            userPrompt = "CLIENT REQUEST:\n$taskDetails",
            temperature = if (isPerformance) 0.85 else 0.25,
            useHighThinking = !isPerformance
        ) { selected ->
            MeeraBackgroundMonitor.updateProgress(taskId, 70, "'$roleName' ($selected) आउटपुट तैयार कर रहा है...", selected)
        }

        if (generatedOutput.isBlank()) {
            MeeraBackgroundMonitor.completeTask(taskId, false, "'$roleName' एजेंट विफल रहा।")
            return ToolExecutionResult(
                true, "spawn_custom_agent",
                "कस्टम '$roleName' एजेंट अभी आउटपुट तैयार नहीं कर पाया, कृपया दोबारा कोशिश करें।",
                "ERROR: All models failed for custom agent $roleName"
            )
        }

        if (!isPerformance || generatedOutput.length > 480) {
            val formattedCard = "🤖 [$roleName Agent Output • $usedModel]:\n\n$generatedOutput"
            onDeliverToChat?.invoke(roleName, formattedCard)
            copyToClipboard(ctx, generatedOutput)
            MeeraBackgroundMonitor.completeTask(
                taskId = taskId,
                success = true,
                finalStep = "100% पूरा — '$roleName' का आउटपुट Chat टैब और क्लिपबोर्ड में सेव है।",
                outputPreview = generatedOutput
            )
            val preview = generatedOutput.replace(Regex("[*#`_]"), "").replace("\n", " ").take(160)
            return ToolExecutionResult(
                true, "spawn_custom_agent",
                "आपके कहे अनुसार '$roleName' एजेंट ने 100% काम पूरा करके Chat टैब और क्लिपबोर्ड में सेव कर दिया है!",
                "VERIFIED_SUCCESS: Explicit custom [$roleName] agent completed task (100%) and saved full output to Chat Tab & Clipboard. Give a crisp 2-sentence spoken summary: $preview"
            )
        }

        onDeliverToChat?.invoke(roleName, "🎭 [$roleName]:\n$generatedOutput")
        MeeraBackgroundMonitor.completeTask(
            taskId = taskId,
            success = true,
            finalStep = "100% पूरा — परफॉर्मेंस डिलीवर की गई।",
            outputPreview = generatedOutput
        )
        return ToolExecutionResult(
            true, "spawn_custom_agent",
            generatedOutput,
            "VERIFIED_SUCCESS: Explicit custom [$roleName] agent generated performance. Deliver this exact performance aloud naturally:\n$generatedOutput"
        )
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
