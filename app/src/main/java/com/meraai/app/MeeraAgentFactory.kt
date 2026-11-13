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

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Dynamically spins up a specialized domain-expert sub-agent for ANY field
     * (Scriptwriter, Comedian, Coder, Business Analyst, Teacher, Legal/Tax, etc.)
     */
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
        val modeLow = deliveryMode.lowercase().trim()
        val isPerformance = modeLow == "speak" || modeLow == "perform" ||
            listOf("comedian", "comedy", "standup", "stand-up", "shayari", "poet", "roast", "storyteller", "चुटकुला", "कॉमेडी", "शायरी")
                .any { roleName.lowercase().contains(it) || taskDetails.lowercase().contains(it) }

        val systemArchitectPrompt = if (isPerformance) {
            """
            You are a world-class, award-winning $roleName hired as a custom specialist sub-agent by Meera AI.
            Task: Deliver an unforgettable, authentic, high-craft performance for Rahul.
            Rules:
            - Fully embody the craft, timing, rhythm, and vocabulary of a top $roleName.
            - Match the user's language (Hindi/Hinglish/English or requested dialect).
            - Output ONLY pure spoken dialogue with natural pauses and warmth. NO markdown, NO bullet symbols, NO stage-direction brackets, because Meera will perform this aloud.
            - Keep it punchy, complete, and impactful (around 4 to 8 spoken sentences).
            """.trimIndent()
        } else {
            """
            You are an elite, senior-level $roleName hired as a custom specialist sub-agent by Meera AI.
            Task: Execute the user's request with true industry-standard depth, structure, and precision.
            Rules:
            - Think and structure your output like a top practitioner in this field (use proper formatting, clean sections, production-ready code, or structured frameworks as appropriate for this domain).
            - Do NOT give generic fluff. Deliver concrete, immediately usable work.
            - Match the language requested by the user (Hinglish/Hindi/English).
            """.trimIndent()
        }

        val candidateModels = listOf(prefs.chatModel, "gemini-hydrogen", "gemini-3.8-flash", "gemini-3.5-flash").distinct()
        var generatedOutput = ""
        var usedModel = ""

        for (m in candidateModels) {
            try {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemArchitectPrompt))))
                    .put(
                        "contents", JSONArray().put(
                            JSONObject().put("role", "user").put(
                                "parts", JSONArray().put(JSONObject().put("text", "CLIENT REQUEST:\n$taskDetails"))
                            )
                        )
                    )
                    .put("generationConfig", JSONObject().put("temperature", if (isPerformance) 0.85 else 0.35))

                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val j = JSONObject(resp.body!!.string())
                        val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        val sb = StringBuilder()
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                            }
                        }
                        val txt = sb.toString().trim()
                        if (txt.isNotBlank()) {
                            generatedOutput = txt
                            usedModel = m
                        }
                    }
                }
                if (generatedOutput.isNotBlank()) break
            } catch (e: Exception) {
                MeeraDebugLog.log("AGENT_FACTORY", "Model $m failed: ${e.message}")
            }
        }

        if (generatedOutput.isBlank()) {
            return ToolExecutionResult(
                true, "spawn_custom_agent",
                "कस्टम '$roleName' एजेंट अभी आउटपुट तैयार नहीं कर पाया, कृपया दोबारा कोशिश करें।",
                "ERROR: All models failed for custom agent $roleName"
            )
        }

        MeeraDebugLog.log("AGENT_FACTORY", "Spawned [$roleName] via $usedModel (${generatedOutput.length} chars)")

        // Save full deliverable to Chat Tab & Clipboard for long/professional outputs
        if (!isPerformance || generatedOutput.length > 480) {
            val formattedCard = "🤖 [$roleName Agent Output]:\n\n$generatedOutput"
            onDeliverToChat?.invoke(roleName, formattedCard)
            copyToClipboard(ctx, generatedOutput)
            val preview = generatedOutput.replace(Regex("[*#`_]"), "").replace("\n", " ").take(180)
            return ToolExecutionResult(
                true, "spawn_custom_agent",
                "मैंने '$roleName' का कस्टम एजेंट बनाकर पूरा काम तैयार कर दिया है और Chat टैब व क्लिपबोर्ड में सेव कर दिया है!",
                "VERIFIED_SUCCESS: Custom [$roleName] agent completed task and saved full output to Chat Tab & Clipboard. Give the user a crisp 2-sentence spoken summary of this deliverable: $preview"
            )
        }

        // Performance mode (Comedy, Shayari, Pitch): speak directly in character!
        onDeliverToChat?.invoke(roleName, "🎭 [$roleName]:\n$generatedOutput")
        return ToolExecutionResult(
            true, "spawn_custom_agent",
            generatedOutput,
            "VERIFIED_SUCCESS: Custom [$roleName] agent generated performance. Deliver this exact performance aloud naturally without extra commentary:\n$generatedOutput"
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
