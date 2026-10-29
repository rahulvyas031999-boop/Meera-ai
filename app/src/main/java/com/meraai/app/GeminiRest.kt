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

/** Modular पाइपलाइन: Fast STT, Streaming LLM, और Glitch-Free TTS */
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
        c.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (c.isActive) c.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { c.resume(response) }
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

    // ---- 1. Fast & Accurate STT (बिना भारी JSON Schema ओवरहेड के) ----
    private val sttPrompt = "Listen to the user's voice and transcribe the exact spoken words. " +
        "If spoken in Hindi, write in Hindi Devanagari script (e.g., हिंदी में बात करें). " +
        "If spoken in English, write in English. " +
        "Return ONLY the exact transcript without quotes or explanation. If there is only silence or noise, return EMPTY."

    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for ((idx, m) in Cfg.STT_MODELS.withIndex()) {
            try {
                val body = JSONObject().put(
                    "contents", JSONArray().put(
                        JSONObject().put("role", "user").put(
                            "parts", JSONArray()
                                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", b64(wav))))
                                .put(JSONObject().put("text", sttPrompt))
                        )
                    )
                )
                if (!m.contains("transcribe")) {
                    body.put("generationConfig", JSONObject()
                        .put("temperature", 0.0)
                        .put("thinkingConfig", JSONObject().put("thinkingLevel", "MINIMAL"))
                    )
                }

                val resp = http.newCall(request(m, "generateContent", body)).await().okOrThrow()
                val raw = resp.use { textOf(JSONObject(it.body!!.string())).trim() }
                val cleaned = raw.removeSurrounding("\"").trim()
                if (cleaned.equals("EMPTY", ignoreCase = true) || cleaned.equals("null", ignoreCase = true)) {
                    return@withContext ""
                }
                if (cleaned.isNotBlank()) return@withContext cleaned
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

    // ---- 2. Smart Sentence Grouping (छोटे वाक्यों को बीच में कटने से बचाता है) ----
    private fun sentenceEnd(b: CharSequence): Int {
        // कम से कम 85 अक्षर होने पर ही वाक्य तोड़ें ताकि 2-3 शब्दों पर ऑडियो बीच में न रुके
        for (i in b.indices) {
            val c = b[i]
            if (c == '\n' && i >= 40) return i + 1
            if ((c == '।' || c == '.' || c == '!' || c == '?' || c == '؟') && i >= 85) {
                if (i + 1 >= b.length || b[i + 1].isWhitespace()) return i + 1
            }
        }
        return -1
    }

    suspend fun streamChat(
        history: List<Pair<String, String>>,
        system: String,
        onSentence: suspend (String) -> Unit,
    ): Unit = withContext<Unit>(Dispatchers.IO) {
        var last: Exception? = null
        for (m in Cfg.CHAT_MODELS) {
            val levels = listOf("MINIMAL", null)
            for (lv in levels) {
                var emitted = false
                val contents = JSONArray()
                for ((role, text) in history) {
                    contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text))))
                }
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", contents)
                if (lv != null) {
                    body.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", lv)))
                }

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

    // ---- 3. Glitch-Free TTS (Proper WAV 'data' parser + Smooth Fade-In/Out) ----
    suspend fun tts(text: String, voice: String): ByteArray = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (ttsModel in Cfg.TTS_MODELS) {
            try {
                val part = JSONObject().put("text", text)
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
                val mime = inline.optString("mimeType", "")
                val (pcm, rate) = extractPcmAndRate(rawBytes, mime)
                val outPcm = if (rate == Cfg.OUT_RATE) pcm else resample(pcm, rate, Cfg.OUT_RATE)
                return@withContext smoothEdges(outPcm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw (last ?: IOException("TTS फेल"))
    }

    /** WAV हेडर में से असली 'data' चंक ढूँढकर प्योर 16-bit PCM निकालना (पॉप/ग्लिच रोकने के लिए) */
    private fun extractPcmAndRate(bytes: ByteArray, mime: String): Pair<ByteArray, Int> {
        var rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: Cfg.OUT_RATE
        if (bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) {
            rate = readIntLe(bytes, 24).coerceIn(8000, 96000)
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val c0 = bytes[offset].toInt().toChar()
                val c1 = bytes[offset + 1].toInt().toChar()
                val c2 = bytes[offset + 2].toInt().toChar()
                val c3 = bytes[offset + 3].toInt().toChar()
                val chunkSize = readIntLe(bytes, offset + 4)
                if (c0 == 'd' && c1 == 'a' && c2 == 't' && c3 == 'a') {
                    val start = offset + 8
                    val end = (start + chunkSize).coerceIn(start, bytes.size)
                    val evenEnd = start + ((end - start) and 1.inv())
                    return bytes.copyOfRange(start, evenEnd) to rate
                }
                if (chunkSize < 0) break
                offset += 8 + chunkSize + (chunkSize and 1)
            }
            val evenEnd = 44 + ((bytes.size - 44) and 1.inv())
            return bytes.copyOfRange(44, evenEnd) to rate
        }
        val evenSize = bytes.size and 1.inv()
        return (if (evenSize == bytes.size) bytes else bytes.copyOfRange(0, evenSize)) to rate
    }

    private fun readIntLe(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    /** ऑडियो के शुरू और अंत के 8ms को स्मूथ करना ताकि चंक बदलते समय कोई टिक/ग्लिच आवाज़ न आए */
    private fun smoothEdges(pcm: ByteArray): ByteArray {
        val totalSamples = pcm.size / 2
        val fadeSamples = minOf(192, totalSamples / 4) // ~8ms at 24kHz
        if (fadeSamples < 4) return pcm
        for (i in 0 until fadeSamples) {
            val gain = i.toFloat() / fadeSamples.toFloat()
            // Fade-in
            val sIn = ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort()
            val vIn = (sIn * gain).toInt().toShort()
            pcm[i * 2] = (vIn.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = (vIn.toInt() shr 8).toByte()
            // Fade-out
            val outIdx = totalSamples - 1 - i
            val sOut = ((pcm[outIdx * 2 + 1].toInt() shl 8) or (pcm[outIdx * 2].toInt() and 0xFF)).toShort()
            val vOut = (sOut * gain).toInt().toShort()
            pcm[outIdx * 2] = (vOut.toInt() and 0xFF).toByte()
            pcm[outIdx * 2 + 1] = (vOut.toInt() shr 8).toByte()
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
}
