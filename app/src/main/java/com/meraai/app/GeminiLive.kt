package com.meraai.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maya C4871o40.java + C5275q40.java का एडवांस Live WebSocket इंजन:
 * - enableAffectiveDialog: true (आवाज़ में असली भावना, हँसी और नखरे)
 * - contextWindowCompression + sessionResumption (लंबी बातचीत में कॉल न कटे)
 * - Safe Handshake Auto-Fallback (अगर कोई मॉडल नई सेटिंग रिजेक्ट करे तो तुरंत स्टैंडर्ड सेटअप से ऑटो-रीकनेक्ट)
 */
class GeminiLive(private val http: OkHttpClient, private val ev: Events) {
    interface Events {
        fun onReady()
        fun onAudio(pcm: ByteArray)
        fun onInputText(t: String)
        fun onOutputText(t: String)
        fun onInterrupted()
        fun onTurnComplete()
        fun onError(msg: String)
    }

    private var ws: WebSocket? = null
    @Volatile var ready = false
        private set
    @Volatile private var closedByUs = false
    @Volatile private var fallbackTried = false

    fun connect(key: String, model: String, voice: String, system: String, silenceMs: Int, safeMode: Boolean = false) {
        closedByUs = false
        ready = false
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"

        val genCfg = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "speechConfig", JSONObject().put(
                    "voiceConfig", JSONObject().put(
                        "prebuiltVoiceConfig", JSONObject().put("voiceName", voice)
                    )
                )
            )
        if (!safeMode) {
            genCfg.put("enableAffectiveDialog", true)
        }

        val setupInner = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", genCfg)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())

        if (!safeMode) {
            // Maya C4871o40.java: START_SENSITIVITY_HIGH हटाया गया ताकि खांसी/आहट पर सर्वर झूठा ट्रिगर न करे
            setupInner.put(
                "realtimeInputConfig", JSONObject().put(
                    "automaticActivityDetection", JSONObject()
                        .put("disabled", false)
                        .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                        .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                        .put("prefixPaddingMs", 120)
                        .put("silenceDurationMs", silenceMs)
                )
            )
            setupInner.put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        }

        val setup = JSONObject().put("setup", setupInner)

        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (closedByUs) return
                // Maya C5275q40.java Auto-Healing: यदि हैंडशेक पर 1007/1011 आए तो तुरंत बिना एडवांस फ्लैग के रीकनेक्ट करें
                if (!ready && !safeMode && !fallbackTried && (code == 1007 || code == 1011 || code == 1002)) {
                    fallbackTried = true
                    connect(key, model, voice, system, silenceMs, safeMode = true)
                    return
                }
                ev.onError("Live सेशन बंद हुआ ($code) $reason".trim())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closedByUs) return
                if (!ready && !safeMode && !fallbackTried) {
                    fallbackTried = true
                    connect(key, model, voice, system, silenceMs, safeMode = true)
                    return
                }
                val c = response?.code
                ev.onError(
                    when (c) {
                        401, 403 -> "API Key सही नहीं है या Live की इजाज़त नहीं (HTTP $c)"
                        null -> "कनेक्शन फेल: ${t.message ?: t.javaClass.simpleName}"
                        else -> "Live कनेक्शन फेल (HTTP $c)"
                    }
                )
            }
        })
    }

    private fun handle(s: String) {
        try {
            val j = JSONObject(s)
            if (j.has("setupComplete")) { ready = true; ev.onReady(); return }
            j.optJSONObject("error")?.let { ev.onError(it.optString("message", "Live API error")); return }
            val sc = j.optJSONObject("serverContent") ?: return
            if (sc.optBoolean("interrupted")) ev.onInterrupted()
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { ev.onInputText(it) }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { ev.onOutputText(it) }
            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val d = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                    if (!d.isNullOrEmpty()) ev.onAudio(unb64(d))
                }
            }
            if (sc.optBoolean("turnComplete")) ev.onTurnComplete()
        } catch (_: Exception) {
        }
    }

    fun sendAudio(pcm: ByteArray) {
        if (!ready) return
        val m = JSONObject().put(
            "realtimeInput", JSONObject().put(
                "audio", JSONObject().put("data", b64(pcm)).put("mimeType", "audio/pcm;rate=16000")
            )
        )
        ws?.send(m.toString())
    }

    fun close() {
        closedByUs = true
        ready = false
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
    }
}
