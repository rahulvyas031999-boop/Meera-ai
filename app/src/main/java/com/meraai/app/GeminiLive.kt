package com.meraai.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

/** Gemini Live API (WebSocket, सीधा फ़ोन से). 16kHz PCM अंदर, 24kHz PCM बाहर. */
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

    fun connect(key: String, model: String, voice: String, system: String, silenceMs: Int) {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        val setup = JSONObject().put(
            "setup", JSONObject()
                .put("model", "models/$model")
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
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put(
                    "realtimeInputConfig", JSONObject().put(
                        "automaticActivityDetection", JSONObject()
                            .put("disabled", false)
                            .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                            .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                            .put("prefixPaddingMs", 100)
                            .put("silenceDurationMs", silenceMs)
                    )
                )
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
        )
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (!closedByUs) ev.onError("Live सेशन बंद हुआ ($code) $reason".trim())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closedByUs) return
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
