package com.meraai.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class GeminiLive(private val http: OkHttpClient, private val ev: Events) {
    interface Events {
        fun onReady()
        fun onAudio(pcm: ByteArray)
        fun onInputText(t: String)
        fun onOutputText(t: String)
        fun onToolCall(id: String, name: String, args: JSONObject)
        fun onInterrupted()
        fun onTurnComplete()
        fun onError(msg: String)
        fun onGoAway(timeLeftMs: Long) {}
    }

    private var ws: WebSocket? = null
    @Volatile var ready = false
        private set
    @Volatile private var closedByUs = false

    // ---- लंबे सेशन: कंप्रेशन + रिज़म्प्शन + अपने-आप दोबारा जुड़ना ----
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var socketGen = 0
    @Volatile private var resumeHandle: String? = null
    @Volatile private var reconnecting = false
    @Volatile private var setupDoneOnce = false
    @Volatile private var longSession = true
    @Volatile private var lastSetupAt = 0L
    private var reconnectAttempts = 0
    private var cKey = ""
    private var cModel = ""
    private var cVoice = ""
    private var cSystem = ""
    private var cSilenceMs = 0

    fun connect(key: String, model: String, voice: String, system: String, silenceMs: Int) {
        cKey = key
        cModel = model
        cVoice = voice
        cSystem = system
        cSilenceMs = silenceMs
        closedByUs = false
        ready = false
        resumeHandle = null
        reconnecting = false
        setupDoneOnce = false
        longSession = true
        reconnectAttempts = 0
        openSocket()
    }

    private fun buildSetup(): JSONObject {
        val s = JSONObject()
            .put("model", "models/$cModel")
            .put(
                "generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig", JSONObject().put(
                            "voiceConfig", JSONObject().put(
                                "prebuiltVoiceConfig", JSONObject().put("voiceName", cVoice)
                            )
                        )
                    )
            )
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", cSystem))))
            .put("tools", MeeraTools.getGeminiToolsJson())
            .put(
                "realtimeInputConfig", JSONObject().put(
                    "automaticActivityDetection", JSONObject()
                        .put("disabled", false)
                        .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                        .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        .put("prefixPaddingMs", 100)
                        .put("silenceDurationMs", cSilenceMs)
                )
            )
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        if (longSession) {
            s.put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            val sr = JSONObject()
            resumeHandle?.let { sr.put("handle", it) }
            s.put("sessionResumption", sr)
        }
        return JSONObject().put("setup", s)
    }

    private fun openSocket() {
        val gen = ++socketGen
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$cKey"
        val setup = buildSetup()
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != socketGen) return
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(gen, text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(gen, bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (gen != socketGen || closedByUs) return
                handleDrop("Live सेशन बंद हुआ ($code) $reason".trim(), true)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != socketGen || closedByUs) return
                val c = response?.code
                val msg = when (c) {
                    401, 403 -> "API Key सही नहीं है या Live की इजाज़त नहीं (HTTP $c)"
                    null -> "कनेक्शन फेल: ${t.message ?: t.javaClass.simpleName}"
                    else -> "Live कनेक्शन फेल (HTTP $c)"
                }
                handleDrop(msg, c != 401 && c != 403)
            }
        })
    }

    private fun handleDrop(msg: String, retryable: Boolean) {
        ready = false
        val now = System.currentTimeMillis()
        if (setupDoneOnce && lastSetupAt > 0 && now - lastSetupAt > 60_000) reconnectAttempts = 0
        // चरण 1: सेटअप पूरा होने से पहले ही गिरा. हो सकता है मॉडल नए फ़ील्ड न समझे, तो पुराने सेटअप से एक बार कोशिश
        if (retryable && !setupDoneOnce && longSession) {
            longSession = false
            MeeraDebugLog.log("LIVE", "लंबे-सेशन वाला सेटअप नहीं चला ($msg); पुराने सेटअप से दोबारा कोशिश")
            scheduleReopen(300L)
            return
        }
        // चरण 2: चलता सेशन टूटा और टोकन है, तो रिज़म्प्शन से अधिकतम 3 बार जुड़ो
        if (retryable && setupDoneOnce && resumeHandle != null && reconnectAttempts < 3) {
            reconnectAttempts++
            reconnecting = true
            MeeraDebugLog.log("LIVE", "कनेक्शन टूटा ($msg); रिज़म्प्शन से दोबारा जुड़ रहा है, कोशिश $reconnectAttempts")
            scheduleReopen(500L * reconnectAttempts)
            return
        }
        ev.onError(msg)
    }

    private fun scheduleReopen(delayMs: Long) {
        mainHandler.postDelayed({ if (!closedByUs) openSocket() }, delayMs)
    }

    private fun parseMs(s: String): Long {
        val v = s.trim().removeSuffix("s").toDoubleOrNull() ?: return -1L
        return (v * 1000).toLong()
    }

    private fun handle(gen: Int, s: String) {
        if (gen != socketGen) return
        try {
            val j = JSONObject(s)
            if (j.has("setupComplete")) {
                ready = true
                setupDoneOnce = true
                lastSetupAt = System.currentTimeMillis()
                if (reconnecting) {
                    reconnecting = false
                    MeeraDebugLog.log("LIVE", "सेशन रिज़म्प्शन से दोबारा जुड़ गया")
                } else {
                    ev.onReady()
                }
                return
            }
            j.optJSONObject("error")?.let { ev.onError(it.optString("message", "Live API error")); return }

            j.optJSONObject("sessionResumptionUpdate")?.let { u ->
                val h = u.optString("newHandle", "")
                if (u.optBoolean("resumable", false) && h.isNotEmpty()) resumeHandle = h
            }
            j.optJSONObject("goAway")?.let { g ->
                val left = g.optString("timeLeft", "")
                MeeraDebugLog.log("LIVE", "GoAway: कनेक्शन जल्द बंद होगा, बचा समय=$left")
                ev.onGoAway(parseMs(left))
            }

            // Handle Live Tool Call (e.g., open_app, read_screen, whatsapp_message)
            j.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
                for (i in 0 until calls.length()) {
                    val call = calls.optJSONObject(i) ?: continue
                    val id = call.optString("id", "")
                    val name = call.optString("name", "")
                    val args = call.optJSONObject("args") ?: JSONObject()
                    if (name.isNotBlank()) ev.onToolCall(id, name, args)
                }
            }

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

    fun sendToolResponse(id: String, name: String, resultText: String) {
        if (!ready) return
        val respObj = JSONObject().put(
            "toolResponse", JSONObject().put(
                "functionResponses", JSONArray().put(
                    JSONObject()
                        .put("id", id)
                        .put("name", name)
                        .put("response", JSONObject().put("result", resultText))
                )
            )
        )
        ws?.send(respObj.toString())
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

    fun sendVideoFrame(jpeg: ByteArray) {
        if (!ready) return
        val m = JSONObject().put(
            "realtimeInput", JSONObject().put(
                "video", JSONObject().put("data", b64(jpeg)).put("mimeType", "image/jpeg")
            )
        )
        ws?.send(m.toString())
    }

    fun close() {
        closedByUs = true
        ready = false
        socketGen++
        mainHandler.removeCallbacksAndMessages(null)
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
    }
}
