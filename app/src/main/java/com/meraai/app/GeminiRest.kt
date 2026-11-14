package com.meraai.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GeminiRest(private val http: OkHttpClient, private val key: () -> String) {
    private val base = "https://generativelanguage.googleapis.com/v1beta/models"
    private val jsonType = "application/json".toMediaType()

    private fun request(model: String, method: String, body: JSONObject, sse: Boolean = false): Request {
        val q = if (sse) "?alt=sse" else ""
        return Request.Builder()
            .url("$base/$model:$method$q")
            .header("x-goog-api-key", key())
            .post(body.toString().toRequestBody(jsonType))
            .build()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { c ->
        val HTTP_T = System.currentTimeMillis(); c.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { MeeraDebugLog.log("HTTP", "${call.request().url.encodedPath.substringAfter("/models/")} FAIL ${System.currentTimeMillis() - HTTP_T}ms ${e.javaClass.simpleName}"); if (c.isActive) c.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { MeeraDebugLog.log("HTTP", "${call.request().url.encodedPath.substringAfter("/models/")} code=${response.code} ${System.currentTimeMillis() - HTTP_T}ms"); c.resume(response) }
        })
    }

    private fun Response.okOrThrow(): Response {
        if (!isSuccessful) {
            val t = try { body?.string()?.take(300) ?: "" } catch (_: Exception) { "" }
            val c = code
            close()
            throw IOException("HTTP $c $t")
        }
        return this
    }

    private fun textOf(j: JSONObject): String {
        val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
        return sb.toString()
    }

    // ---------- 1. STT (With Cough / Distant TV / Noise Reject Prompt) ----------
    private val sttPrompt = "Transcribe ONLY clear near-field human speech spoken directly to the assistant (Hindi in Devanagari, Hinglish, or English). " +
        "IMPORTANT: If the audio is a cough, throat clearing, sneeze, laugh, background TV/mobile sound, or indistinct noise, set hasSpeech to false and transcript to an empty string."

    suspend fun transcribe(wav: ByteArray, preferredStt: String = Cfg.STT_MODELS.first()): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        val sttList = (listOf(preferredStt) + Cfg.STT_MODELS).distinct()
        for ((idx, m) in sttList.withIndex()) {
            try {
                val structured = m != "gemini-3.5-transcribe"
                val promptText = if (structured) sttPrompt else "Transcribe clear spoken words only. Ignore coughs or background noise."
                val body = JSONObject().put(
                    "contents", JSONArray().put(
                        JSONObject().put("role", "user").put(
                            "parts", JSONArray()
                                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", b64(wav))))
                                .put(JSONObject().put("text", promptText))
                        )
                    )
                )
                if (structured) {
                    body.put(
                        "generationConfig", JSONObject()
                            .put("responseMimeType", "application/json")
                            .put(
                                "responseSchema", JSONObject()
                                    .put("type", "OBJECT")
                                    .put(
                                        "properties", JSONObject()
                                            .put("hasSpeech", JSONObject().put("type", "BOOLEAN"))
                                            .put("transcript", JSONObject().put("type", "STRING"))
                                    )
                                    .put("required", JSONArray().put("hasSpeech").put("transcript"))
                            )
                    )
                }
                val resp = http.newCall(request(m, "generateContent", body)).await().okOrThrow()
                val raw = resp.use { textOf(JSONObject(it.body!!.string())) }
                val text = if (structured) parseStt(raw) else raw.trim()
                if (text.isNotBlank()) return@withContext text
                if (idx == 0) return@withContext ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        last?.let { throw it }
        ""
    }

    private fun parseStt(raw: String): String = try {
        val j = JSONObject(raw)
        if (j.optBoolean("hasSpeech", true)) j.optString("transcript", "").trim() else ""
    } catch (_: Exception) {
        raw.trim().trim('"')
    }

    // ---------- 2. LLM (Streaming with Preferred Brain & Thinking Level) ----------
    private fun sentenceEnd(b: CharSequence): Int {
        for (i in b.indices) {
            val c = b[i]
            if (c == '\n' || c == '।') { if (i >= 12) return i + 1 }
            else if ((c == '.' || c == '!' || c == '?' || c == ';') && i >= 65 && i + 1 < b.length && b[i + 1].isWhitespace()) return i + 1
        }
        return -1
    }

    suspend fun streamChat(
        history: List<Pair<String, String>>,
        system: String,
        preferredModel: String = Cfg.CHAT_MODELS.first(),
        onSentence: suspend (String) -> Unit,
    ): Unit = withContext<Unit>(Dispatchers.IO) {
        var last: Exception? = null
        val isDeep = preferredModel == "gemini-3.8-flash-thinking"
        val firstModel = if (isDeep) "gemini-3.8-flash" else preferredModel
        val modelList = (listOf(firstModel) + Cfg.CHAT_MODELS).distinct()

        for (m in modelList) {
            val levels = when {
                isDeep && m == firstModel -> listOf("HIGH", "LOW", null)
                m.contains("3.8") || m.contains("3.7") || m.contains("3.6") -> listOf("LOW", null)
                m.contains("3.1") -> listOf("MINIMAL", null)
                else -> listOf(null)
            }
            for (lv in levels) {
                var emitted = false
                val contents = JSONArray()
                for ((role, text) in history) {
                    contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text))))
                }
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", contents)
                if (lv != null) body.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", lv)))
                val call = http.newCall(request(m, "streamGenerateContent", body, sse = true))
                val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
                try {
                    val resp = call.await().okOrThrow()
                    resp.use { r ->
                        val src = r.body!!.source()
                        val buf = StringBuilder()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val line = src.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.substring(5).trim()
                            if (payload.isEmpty() || payload == "[DONE]") continue
                            val piece = textOf(JSONObject(payload))
                            if (piece.isEmpty()) continue
                            buf.append(piece)
                            while (true) {
                                val cut = sentenceEnd(buf)
                                if (cut < 0) break
                                val s = buf.substring(0, cut).trim()
                                buf.delete(0, cut)
                                if (s.isNotEmpty()) { emitted = true; onSentence(s) }
                            }
                        }
                        val rest = buf.toString().trim()
                        if (rest.isNotEmpty()) { emitted = true; onSentence(rest) }
                    }
                    return@withContext
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (emitted) throw e
                    last = e
                } finally {
                    handle.dispose()
                }
            }
        }
        throw (last ?: IOException("Chat मॉडल जवाब नहीं दे पाया"))
    }

    // ---------- Modular मोड: बोले वाक्य से फोन-टूल पहचानना (Live के function-calling जैसा) ----------
    suspend fun routeCommand(userText: String): Pair<String, JSONObject>? {
        val sys = """You are a phone-command router for an Android voice assistant. The user text is a speech transcript (Hindi, Hinglish or English) and may contain punctuation or small recognition mistakes. Decide whether the user wants the phone to DO something. Reply with ONLY compact JSON like {"tool":"open_app","args":{"app_name":"WhatsApp"}}. Tools: open_app {app_name}; read_screen {}; screen_action {action_type: click|type|paste|home|back|scroll_down|scroll_up|notifications, target_or_text}; copy_debug_log {}; whatsapp_message {contact_name, message}; make_phone_call {contact_name}; play_or_search {query, platform: youtube|google}. If the text is normal conversation, a question, or not a phone action, reply {"tool":"none"}. Never explain."""
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0))
        return kotlinx.coroutines.withTimeoutOrNull(6000L) {
            var found: Pair<String, JSONObject>? = null
            for (m in Cfg.CHAT_MODELS.take(2)) {
                try {
                    val resp = http.newCall(request(m, "generateContent", body)).await().okOrThrow()
                    val j = resp.use { JSONObject(it.body!!.string()) }
                    val txt = textOf(j).trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    val o = JSONObject(txt)
                    val tool = o.optString("tool", "none")
                    found = if (tool.isBlank() || tool == "none") null else Pair(tool, o.optJSONObject("args") ?: JSONObject())
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            found
        }
    }

    // ---------- 2.75 Closed-Loop Agent Step Decider (Observe -> Reflect -> Act) ----------
    suspend fun decideNextAgentAction(
        overallGoal: String,
        missionStatus: String,
        currentScreen: String,
        lastReflection: String
    ): Pair<String, JSONObject>? = withContext(Dispatchers.IO) {
        val sys = """
You are Meera AI's autonomous Android agent controller with Self-Reflection and Error Recovery.
Given the user's OVERALL GOAL, CURRENT MISSION CHECKLIST, LAST ACTION REFLECTION, and CURRENT SCREEN ELEMENTS:
1. If the overall goal is completely achieved on screen, reply {"tool":"done","args":{"summary":"Short Hinglish confirmation of what was completed"}}.
2. PRE-FLIGHT APP RESET: If the target app is already open on screen, check if the required Search bar, Home tab, or target section is visible right now:
   - If Search or the target button IS visible on the current screen, tap it directly (do NOT go back unnecessarily).
   - If the app is stuck inside an old reel, comment box, video player, or irrelevant sub-page where Search/Home is NOT visible, choose `navigate_to_app_home` {"want_search":true}.
   - If the user asked to come to the app's main dashboard/home screen, choose `navigate_to_app_home` {"want_search":false}.
3. ANTI-PING-PONG & SCROLL RULE: Never pick any element listed in BLACKLISTED FAILED ACTIONS! If looking for a setting/option (like 'App management' or 'Apps') that is not visible in CURRENT SCREEN, choose `screen_action` {"action_type":"scroll_down","target_or_text":""} instead of tapping random items!
4. Otherwise pick the single best next tool from:
   - open_app {"app_name":"..."}
   - navigate_to_app_home {"want_search":true}
   - tap_element {"id":12, "text":"..."}
   - screen_action {"action_type":"click|type|paste|scroll_down|scroll_up|back", "target_or_text":"... (use target_or_text='send' with paste to paste & send immediately)"}
   - copy_debug_log {}
   - recover_stuck_step {"target_text":"..."}
   - wait_for_screen_text {"text":"...", "mode":"appear", "timeout_seconds":15}
   - whatsapp_message {"contact_name":"...", "message":"..."}
   - whatsapp_send_in_open_chat {"message":"..."}
   - youtube_search_and_play {"query":"..."}
Reply with ONLY valid compact JSON: {"tool":"...","args":{...},"mark_done_step":0}
""".trimIndent()
        val userPayload = "OVERALL GOAL: $overallGoal\n$missionStatus\nLAST REFLECTION: $lastReflection\nCURRENT SCREEN:\n${currentScreen.take(3500)}"
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userPayload)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1))
        kotlinx.coroutines.withTimeoutOrNull(8000L) {
            for (m in listOf("gemini-3.8-flash", Cfg.CHAT_MODELS.first()).distinct()) {
                try {
                    val resp = http.newCall(request(m, "generateContent", body)).await().okOrThrow()
                    val txt = resp.use { textOf(JSONObject(it.body!!.string())).trim() }
                        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    val o = JSONObject(txt)
                    val doneStep = o.optInt("mark_done_step", 0)
                    if (doneStep > 0) MeeraMission.markDone(doneStep)
                    val tool = o.optString("tool", "none")
                    if (tool.isNotBlank() && tool != "none") {
                        return@withTimeoutOrNull Pair(tool, o.optJSONObject("args") ?: JSONObject())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
            null
        }
    }

    // ---------- 2.8 Task Planner ----------
    suspend fun generatePlan(task: String, currentScreen: String): List<String> = withContext(Dispatchers.IO) {
        val prompt = """
            You are an expert Android phone automation planner.
            Break the user's task into 2 to 8 ordered, simple steps.
            Each step must be achievable by ONE simple action.
            Consider the CURRENT SCREEN: $currentScreen
            Output ONLY a valid JSON array of strings. No markdown, no explanation.
            Example: ["Open WhatsApp", "Tap on Search", "Type 'Rahul'", "Tap the contact"]
            Task: $task
        """.trimIndent()
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))).put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        try {
            val resp = http.newCall(request("gemini-3.8-flash", "generateContent", body)).await().okOrThrow()
            val raw = resp.use { textOf(JSONObject(it.body!!.string())).trim() }
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            listOf("Execute: $task")
        }
    }

    suspend fun generateQuickAutoReply(sender: String, incomingMsg: String, rule: String, persona: String): String = withContext(Dispatchers.IO) {
        val prompt = "You are handling WhatsApp chat on behalf of Rahul ($persona). Incoming message from '$sender': \"$incomingMsg\". Instruction: $rule. Write ONLY a short, natural 1-sentence Hinglish reply (no quotes, no markdown)."
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        try {
            val resp = http.newCall(request("gemini-flash-lite-latest", "generateContent", body)).await().okOrThrow()
            resp.use { textOf(JSONObject(it.body!!.string())).lineSequence().firstOrNull()?.trim().orEmpty() }
                .ifBlank { "Abhi Rahul thode busy hain, main Meera AI bol rahi hoon—unhe bata dungi!" }
        } catch (_: Exception) {
            "Abhi Rahul thode busy hain, main unhe aapka message bata dungi!"
        }
    }

    // ---------- 2.9 Vision Analysis ----------
    suspend fun analyzeScreenVisually(bitmap: android.graphics.Bitmap, prompt: String): String = withContext(Dispatchers.IO) {
        val bos = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos)
        val b64Img = b64(bos.toByteArray())
        val body = JSONObject().put("contents", JSONArray().put(
            JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64Img)))
                .put(JSONObject().put("text", prompt)))))
        val resp = http.newCall(request("gemini-3.8-flash", "generateContent", body)).await().okOrThrow()
        resp.use { textOf(JSONObject(it.body!!.string())).trim() }
    }

    // ---------- 3. Expressive TTS (Persona Style Directing + Glitch-Free WAV Parser) ----------
    suspend fun tts(
        text: String,
        voice: String,
        preferredTts: String = Cfg.TTS_MODELS.first(),
        persona: String = "Meera",
        gfMode: Boolean = true
    ): ByteArray = withContext(Dispatchers.IO) {
        var last: Exception? = null
        val stylePrompt = when (persona) {
            "Jarvis" -> "crisp, composed, intelligent, confident, and sharp"
            "Rudra" -> "deep, bold, commanding, fearless, and grounded"
            else -> if (gfMode) "warm, playful, affectionate, expressive, and natural like a close companion" else "warm, friendly, natural, and conversational"
        }
        val ttsList = (listOf(preferredTts) + Cfg.TTS_MODELS).distinct()
        for (ttsModel in ttsList) {
            val styles = if (ttsModel.startsWith("gemini-3.8")) listOf(true, false) else listOf(false)
            for (withStyle in styles) {
                try {
                    val part = JSONObject().put("text", text)
                    if (withStyle) part.put("speechMetadata", JSONObject().put("style", stylePrompt))
                    val body = JSONObject()
                        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(part))))
                        .put(
                            "generationConfig", JSONObject()
                                .put("responseModalities", JSONArray().put("AUDIO"))
                                .put(
                                    "speechConfig", JSONObject().put(
                                        "voiceConfig", JSONObject().put(
                                            "prebuiltVoiceConfig", JSONObject().put("voiceName", voice)
                                        )
                                    )
                                )
                        )
                    val resp = http.newCall(request(ttsModel, "generateContent", body)).await().okOrThrow()
                    val j = resp.use { JSONObject(it.body!!.string()) }
                    val inline = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                        ?.optJSONArray("parts")?.optJSONObject(0)?.optJSONObject("inlineData")
                        ?: throw IOException("TTS से ऑडियो नहीं मिला")
                    val rawBytes = unb64(inline.getString("data"))
                    val parsed = extractCleanPcm(rawBytes, inline.optString("mimeType", ""))
                    val pcm = if (parsed.second == Cfg.OUT_RATE) parsed.first else resample(parsed.first, parsed.second, Cfg.OUT_RATE)
                    return@withContext applyMicroFade(pcm)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
        }
        throw (last ?: IOException("TTS फेल"))
    }

    private fun extractCleanPcm(bytes: ByteArray, mime: String): Pair<ByteArray, Int> {
        var rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: Cfg.OUT_RATE
        if (bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            rate = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or
                ((bytes[26].toInt() and 0xFF) shl 16) or ((bytes[27].toInt() and 0xFF) shl 24)
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id0 = bytes[pos].toInt().toChar()
                val id1 = bytes[pos + 1].toInt().toChar()
                val id2 = bytes[pos + 2].toInt().toChar()
                val id3 = bytes[pos + 3].toInt().toChar()
                val chunkSize = (bytes[pos + 4].toInt() and 0xFF) or ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 16) or ((bytes[pos + 7].toInt() and 0xFF) shl 24)
                val dataStart = pos + 8
                if (id0 == 'd' && id1 == 'a' && id2 == 't' && id3 == 'a') {
                    val end = minOf(bytes.size, if (chunkSize > 0) dataStart + chunkSize else bytes.size)
                    val evenEnd = dataStart + ((end - dataStart) and -2)
                    return bytes.copyOfRange(dataStart, evenEnd) to rate
                }
                if (chunkSize < 0) break
                pos = dataStart + chunkSize + (chunkSize and 1)
            }
            val evenEnd = 44 + ((bytes.size - 44) and -2)
            return bytes.copyOfRange(44, evenEnd) to rate
        }
        val evenSize = bytes.size and -2
        return (if (evenSize == bytes.size) bytes else bytes.copyOfRange(0, evenSize)) to rate
    }

    private fun applyMicroFade(pcm: ByteArray): ByteArray {
        val samples = pcm.size / 2
        val fade = minOf(120, samples / 10)
        if (fade <= 2) return pcm
        for (i in 0 until fade) {
            val gain = i.toFloat() / fade
            val idx = i * 2
            val s = (((pcm[idx + 1].toInt() shl 8) or (pcm[idx].toInt() and 0xFF)).toShort() * gain).toInt()
            pcm[idx] = (s and 0xFF).toByte()
            pcm[idx + 1] = ((s shr 8) and 0xFF).toByte()

            val rIdx = (samples - 1 - i) * 2
            val rs = (((pcm[rIdx + 1].toInt() shl 8) or (pcm[rIdx].toInt() and 0xFF)).toShort() * gain).toInt()
            pcm[rIdx] = (rs and 0xFF).toByte()
            pcm[rIdx + 1] = ((rs shr 8) and 0xFF).toByte()
        }
        return pcm
    }

    private fun resample(pcm: ByteArray, from: Int, to: Int): ByteArray {
        val n = pcm.size / 2
        if (n < 2) return pcm
        val outN = (n.toLong() * to / from).toInt()
        val out = ByteArray(outN * 2)
        fun s(i: Int): Int {
            val k = i.coerceIn(0, n - 1)
            return ((pcm[k * 2 + 1].toInt() shl 8) or (pcm[k * 2].toInt() and 0xFF)).toShort().toInt()
        }
        for (o in 0 until outN) {
            val pos = o.toDouble() * from / to
            val i = pos.toInt()
            val f = pos - i
            val v = (s(i) * (1 - f) + s(i + 1) * f).toInt()
            out[o * 2] = v.toByte()
            out[o * 2 + 1] = (v shr 8).toByte()
        }
        return out
    }

    // ---------- 4. Background Memory Fact Extractor (Maya remember_fact style) ----------
    suspend fun extractMemoryFact(userText: String, existing: List<String>): String? = withContext(Dispatchers.IO) {
        if (userText.length < 6) return@withContext null
        try {
            val prompt = """
Analyze what the user just said: "$userText"
Already saved facts: ${existing.takeLast(15)}
Task: Did the user reveal a permanent personal fact worth remembering for months (their name, family/friends, job/project, city, favorite things, or a promise/plan)?
Do NOT save temporary mood, weather, greetings, or general questions.
If YES and not already saved, reply with ONLY 1 short clear sentence in Hinglish (e.g. "Boss ka naam Rahul hai", "Unka bhai Pune me rehta hai").
If NO, reply with ONLY: NONE
""".trimIndent()
            val body = JSONObject().put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            val resp = http.newCall(request("gemini-flash-lite-latest", "generateContent", body)).await().okOrThrow()
            val out = resp.use { textOf(JSONObject(it.body!!.string())).trim() }
            if (out.isBlank() || out.equals("NONE", ignoreCase = true) || out.length > 140) null else out
        } catch (_: Exception) {
            null
        }
    }
}
