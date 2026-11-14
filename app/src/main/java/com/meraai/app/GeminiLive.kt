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
        fun onReconnecting(attempt: Int) {}
        fun onResumed() {}
    }

    private var ws: WebSocket? = null
    @Volatile var ready = false
        private set
    @Volatile private var closedByUs = false

    // ---- लंबे सेशन: कंप्रेशन + रिज़म्प्शन + बिना माइक बंद किए ऑटो-रीकनेक्ट ----
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var socketGen = 0
    @Volatile private var resumeHandle: String? = null
    @Volatile private var reconnecting = false
    @Volatile private var setupDoneOnce = false
    @Volatile private var longSession = true
    @Volatile private var lastSetupAt = 0L
    @Volatile private var networkUp = true
    @Volatile private var dropStartAt = 0L
    private var dropAttempts = 0
    private var cKey = ""
    private var cModel = ""
    private var cPreferred = ""   // यूज़र की चुनी सेटिंग (फॉलबैक इसे नहीं बदलता)
    private var cVoice = ""
    private var cSystem = ""
    private var cSilenceMs = 0

    fun connect(key: String, model: String, voice: String, system: String, silenceMs: Int) {
        cKey = key
        cPreferred = model
        cModel = FallbackManager.liveStart(model)
        cVoice = voice
        cSystem = system
        cSilenceMs = silenceMs
        closedByUs = false
        ready = false
        resumeHandle = null
        reconnecting = false
        setupDoneOnce = false
        longSession = true
        dropStartAt = 0L
        dropAttempts = 0
        mainHandler.removeCallbacksAndMessages(null)
        openSocket()
    }

    /** बिना माइक बंद किए पर्सनैलिटी, आवाज़, मॉडल या सिस्टम प्रॉम्प्ट को तुरंत हॉट-स्विच करें */
    fun updateSessionConfig(model: String, voice: String, system: String, silenceMs: Int) {
        if (closedByUs) return
        cPreferred = model
        cModel = FallbackManager.liveStart(model)
        cVoice = voice
        cSystem = system
        cSilenceMs = silenceMs
        resumeHandle = null // नया वॉइस और नई पर्सनैलिटी लागू करने के लिए नया सेशन हैंडशेक
        ready = false
        reconnecting = true
        dropAttempts = 0
        socketGen++
        mainHandler.removeCallbacksAndMessages(null)
        try { ws?.close(1000, "config_switch") } catch (_: Exception) {}
        ws = null
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
        if (closedByUs) return
        val gen = ++socketGen
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$cKey"
        val setup = buildSetup()
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != socketGen || closedByUs) return
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(gen, text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(gen, bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try { webSocket.close(1000, null) } catch (_: Exception) {}
                if (gen != socketGen || closedByUs) return
                val ck = FallbackManager.classify("$code $reason").let { if (it == ErrKind.OTHER && (code == 1007 || code == 1008)) ErrKind.PERMANENT else it }
                handleDrop("Live सेशन बंद हुआ ($code) $reason".trim(), true, ck)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != socketGen || closedByUs) return
                val c = response?.code
                val msg = when (c) {
                    401, 403 -> "API Key सही नहीं है या Live की इजाज़त नहीं (HTTP $c)"
                    null -> "कनेक्शन फेल: ${t.message ?: t.javaClass.simpleName}"
                    else -> "Live कनेक्शन फेल (HTTP $c)"
                }
                handleDrop(msg, c != 401 && c != 403, FallbackManager.classify(t.message, c, t))
            }
        })
    }

    private fun handleDrop(msg: String, retryable: Boolean, kind: ErrKind = ErrKind.OTHER) {
        ready = false
        if (closedByUs) return
        if (!retryable) {
            ev.onError(msg)
            return
        }
        // मॉडल ही नहीं मिला / कोटा पूरा: उसी पर अटके रहने की जगह तुरंत अऔले Live मॉडल पर
        if ((kind == ErrKind.PERMANENT || kind == ErrKind.QUOTA) && switchModel(kind, msg)) return
        // यदि शुरुआती कनेक्शन में longSession फ़ील्ड से दिक्कत हो, तो एक बार बिना longSession के आज़माएँ
        if (!setupDoneOnce && longSession && dropAttempts == 0 && kind != ErrKind.PERMANENT) {
            longSession = false
            dropAttempts++
            MeeraDebugLog.log("LIVE", "लंबे-सेशन वाला सेटअप नहीं चला ($msg); बेसिक सेटअप से कोशिश")
            scheduleReconnect(400L)
            return
        }
        // जब तक यूज़र खुद माइक बंद नहीं करता, कभी हार न मानें (Flight mode में भी माइक चालू रहे)
        val now = System.currentTimeMillis()
        if (dropStartAt == 0L) dropStartAt = now
        reconnecting = true
        dropAttempts++
        // नेटवर्क ठीक है फिर भी इसी मॉडल पर 3 बार लगातार नहीं जुड़ा -> अगला मॉडल
        if (networkUp && kind != ErrKind.NETWORK && dropAttempts >= 3 && switchModel(kind, msg)) return
        if (dropAttempts > 2) resumeHandle = null
        ev.onReconnecting(dropAttempts)
        val waitMs = when {
            !networkUp -> 1500L
            dropAttempts <= 2 -> 800L
            dropAttempts <= 5 -> 1800L
            else -> 3000L
        }
        MeeraDebugLog.log("LIVE", "कनेक्शन टूटा ($msg); कोशिश $dropAttempts, $waitMs ms बाद")
        scheduleReconnect(waitMs)
    }

    /** अगले healthy Live मॉडल पर जाओ; जा पाए तो true */
    private fun switchModel(kind: ErrKind, msg: String): Boolean {
        val from = cModel
        FallbackManager.markFail(FallbackManager.Slot.LIVE, from, kind)
        val to = FallbackManager.nextLive(from, cPreferred)
        if (to == null) {
            // सारे Live मॉडल बंद: health साफ़ करके इसी पर धीरे-धीरे कोशिश जारी (हार नहीं मानते)
            FallbackManager.clearHealth(FallbackManager.Slot.LIVE)
            FallbackManager.onNotice?.invoke("⚠️ कोई भी Live मॉडल अभी नहीं जुड़ रहा — कोशिश जारी है")
            return false
        }
        cModel = to
        dropAttempts = 0
        resumeHandle = null
        setupDoneOnce = false
        longSession = true
        reconnecting = true
        MeeraDebugLog.log("LIVE", "मॉडल बदला: $from -> $to ($msg)")
        scheduleReconnect(300L)
        return true
    }

    private fun scheduleReconnect(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ reconnectTick() }, delayMs)
    }

    private fun reconnectTick() {
        if (closedByUs) return
        if (!networkUp) {
            // नेटवर्क आने का इंतज़ार करें, पर हर 2 सेकंड में चेक करते रहें
            scheduleReconnect(2000L)
            return
        }
        // फॉलबैक मॉडल पर हैं और ऊपर वाला ठीक हो चुका हो (cooldown ख़त्म) तो वापस उसी पर
        FallbackManager.betterLive(cModel, cPreferred)?.let { better ->
            cModel = better; resumeHandle = null; setupDoneOnce = false; longSession = true
        }
        openSocket()
    }

    // VoiceViewModel का नेटवर्क कॉलबैक इसे बुलाता है (Flight Mode ON/OFF होने पर)
    fun onNetworkChanged(up: Boolean) {
        val was = networkUp
        networkUp = up
        MeeraDebugLog.log("LIVE", if (up) "नेटवर्क आया (Flight Mode OFF / Net ON)" else "नेटवर्क गया (Flight Mode ON / Net OFF)")
        if (closedByUs) return
        if (up) {
            if (!ready || !was || reconnecting) {
                reconnecting = true
                dropAttempts = 0
                ev.onReconnecting(1)
                // Flight mode हटने के बाद DNS को तैयार होने के लिए 600ms दें, फेल होने पर handleDrop फिर से कोशिश करेगा
                scheduleReconnect(600L)
            }
        } else {
            if (ready) {
                ready = false
                reconnecting = true
                socketGen++
                try { ws?.cancel() } catch (_: Exception) {}
                ws = null
                ev.onReconnecting(1)
                scheduleReconnect(1500L)
            }
        }
    }

    private fun parseMs(s: String): Long {
        val v = s.trim().removeSuffix("s").toDoubleOrNull() ?: return -1L
        return (v * 1000).toLong()
    }

    private fun handle(gen: Int, s: String) {
        if (gen != socketGen || closedByUs) return
        try {
            val j = JSONObject(s)
            if (j.has("setupComplete")) {
                ready = true
                FallbackManager.markOk(FallbackManager.Slot.LIVE, cModel)
                FallbackManager.noteUse(FallbackManager.Slot.LIVE, cModel, cPreferred)
                val wasDone = setupDoneOnce
                setupDoneOnce = true
                longSession = true
                lastSetupAt = System.currentTimeMillis()
                dropStartAt = 0L
                dropAttempts = 0
                if (reconnecting && wasDone) {
                    reconnecting = false
                    MeeraDebugLog.log("LIVE", "सेशन दोबारा जुड़ गया")
                    ev.onResumed()
                } else {
                    reconnecting = false
                    ev.onReady()
                }
                return
            }
            j.optJSONObject("error")?.let { errObj ->
                val code = errObj.optInt("code", 0)
                val errMsg = errObj.optString("message", "Live API error")
                handleDrop(errMsg, code != 401 && code != 403, FallbackManager.classify(errMsg, code))
                return
            }

            j.optJSONObject("sessionResumptionUpdate")?.let { u ->
                val h = u.optString("newHandle", "")
                if (u.optBoolean("resumable", false) && h.isNotEmpty()) resumeHandle = h
            }
            j.optJSONObject("goAway")?.let { g ->
                val left = g.optString("timeLeft", "")
                MeeraDebugLog.log("LIVE", "GoAway: कनेक्शन जल्द बंद होगा, बचा समय=$left")
                ev.onGoAway(parseMs(left))
            }

            // Handle Live Tool Call
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
        reconnecting = false
        socketGen++
        mainHandler.removeCallbacksAndMessages(null)
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
    }
}
