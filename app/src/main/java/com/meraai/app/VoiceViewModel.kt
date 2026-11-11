package com.meraai.app

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VoiceViewModel(private val appCtx: Application) : AndroidViewModel(appCtx) {
    private val prefs = Prefs(appCtx)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val wsHttp = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val rest = GeminiRest(http) { prefs.apiKey }
    private val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _ui = MutableStateFlow(
        UiState(
            mode = prefs.mode,
            persona = prefs.persona,
            girlfriendMode = prefs.girlfriendMode,
            voice = prefs.voice,
            liveModel = prefs.liveModel,
            chatModel = prefs.chatModel,
            sttModel = prefs.sttModel,
            ttsModel = prefs.ttsModel,
            hasKey = prefs.apiKey.isNotBlank(),
            memoryCount = prefs.getMemories().size
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var st = VState.IDLE
    @Volatile private var active = false
    val isActive: Boolean get() = active

    private val requestInFlight = AtomicBoolean(false)

    private var mic: MicRecorder? = null
    @Volatile private var player: PcmPlayer? = null
    @Volatile private var live: GeminiLive? = null
    private var pipeline: Job? = null

    private val detector = TurnDetector { prefs.silenceMs }
    private val preRoll = ArrayDeque<ShortArray>()
    private val utter = ArrayList<ShortArray>()
    @Volatile private var collecting = false
    private var bargeFrames = 0
    private var quietMs = 0
    @Volatile private var speechEndAt = 0L
    @Volatile private var firstAudioPending = false
    @Volatile private var dropAudio = false
    @Volatile private var liveTurnOpen = false
    @Volatile private var newUserTurn = true
    @Volatile private var toolCalledThisTurn = false
    private val userBuf = StringBuilder()
    private val aiBuf = StringBuilder()
    private val history = ArrayList<Pair<String, String>>()

    private fun nowTime(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun appendChatLog(role: String, text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        _ui.update { cur ->
            val updated = (cur.chatMessages + ChatMessage(role, clean, nowTime())).takeLast(60)
            cur.copy(chatMessages = updated)
        }
    }

    fun clearChatLog() {
        history.clear()
        _ui.update { it.copy(chatMessages = emptyList(), user = "", ai = "") }
    }

    fun toggleMute() {
        MicMute.toggle()
        if (active) MeeraKeepAliveService.start(getApplication())   // नोटिफ़िकेशन का बटन/टेक्स्ट अपडेट
    }

    fun isSetupDone(): Boolean = prefs.setupDone
    fun markSetupDone() { prefs.setupDone = true }
    fun getMemoriesList(): List<String> = prefs.getMemories()

    fun addManualMemory(fact: String) {
        prefs.addMemory(fact)
        _ui.update { it.copy(memoryCount = prefs.getMemories().size) }
    }

    fun clearAllMemories() {
        prefs.clearMemories()
        _ui.update { it.copy(memoryCount = 0) }
    }

    fun deleteMemory(fact: String) {
        prefs.deleteMemory(fact)
        _ui.update { it.copy(memoryCount = prefs.getMemories().size) }
    }

    fun editMemory(old: String, new: String) {
        prefs.updateMemory(old, new)
        _ui.update { it.copy(memoryCount = prefs.getMemories().size) }
    }


    private fun autoLearnFromUser(text: String) {
        if (text.length < 6) return
        viewModelScope.launch(Dispatchers.IO) {
            val fact = rest.extractMemoryFact(text, prefs.getMemories())
            if (!fact.isNullOrBlank()) {
                prefs.addMemory(fact)
                _ui.update { it.copy(memoryCount = prefs.getMemories().size) }
            }
        }
    }

    /** टेक्स्ट चैट या Quick Action बटन से सीधे कमांड/मैसेज भेजना */
    fun sendTextQuery(query: String, speakAloud: Boolean = false) {
        val clean = query.trim()
        if (clean.isBlank()) return
        if (prefs.apiKey.isBlank()) {
            _ui.update { it.copy(askKey = true, error = "पहले API Key डालें") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val startAt = System.currentTimeMillis()
            _ui.update { it.copy(user = clean, ai = "सोच रही हूँ…", error = null) }
            appendChatLog("user", clean)
            autoLearnFromUser(clean)

            // 1. पहले चेक करें कि क्या यह सीधा फोन टूल कमांड है (जैसे WhatsApp खोलो, स्क्रीन पढ़ो)
            val toolRes = MeeraTools.tryDirectVoiceCommand(appCtx, clean).let { if (it.executed) it else routeViaLlm(clean) }
            if (toolRes.executed && toolRes.spokenReply.isNotBlank() && (toolRes.rawDataForAi.startsWith("VERIFIED_SUCCESS:") || toolRes.rawDataForAi.startsWith("SUCCESS:"))) {
                _ui.update {
                    it.copy(
                        ai = toolRes.spokenReply,
                        lastToolStatus = "⚡ ${toolRes.toolName}",
                        latencyMs = System.currentTimeMillis() - startAt
                    )
                }
                appendChatLog("model", toolRes.spokenReply)
                return@launch
            }

            val effectivePrompt = if (toolRes.executed && toolRes.rawDataForAi.isNotBlank()) {
                "$clean\n\n[TOOL OUTPUT]:\n${toolRes.rawDataForAi}"
            } else clean

            history.add("user" to effectivePrompt)
            val sysPrompt = SystemPrompt.build(prefs.persona, prefs.girlfriendMode, prefs.getMemories() + prefs.getSkills())
            val full = StringBuilder()
            try {
                rest.streamChat(history.takeLast(12), sysPrompt, prefs.chatModel) { s ->
                    full.append(s).append(' ')
                    _ui.update {
                        it.copy(
                            ai = full.toString().trim(),
                            latencyMs = System.currentTimeMillis() - startAt
                        )
                    }
                }
                val reply = full.toString().trim()
                if (reply.isNotBlank()) {
                    history.add("model" to reply)
                    appendChatLog("model", reply)
                    if (speakAloud) {
                        val pcm = rest.tts(reply.replace(Regex("[*#_`~]"), ""), prefs.voice, prefs.ttsModel, prefs.persona, prefs.girlfriendMode)
                        val tempPlayer = player ?: PcmPlayer().also { player = it }
                        tempPlayer.enqueue(pcm)
                    }
                }
            } catch (e: Exception) {
                val err = friendly(e)
                _ui.update { it.copy(ai = err, error = err) }
            }
        }
    }

    fun setMode(m: Mode) {
        if (m == prefs.mode) return
        stop()
        prefs.mode = m
        _ui.update { it.copy(mode = m, error = null) }
    }

    fun setPersona(p: String) {
        if (p == prefs.persona) return
        stop()
        prefs.persona = p
        val defaultVoice = Cfg.PERSONAS.find { it.first == p }?.third ?: prefs.voice
        prefs.voice = defaultVoice
        _ui.update { it.copy(persona = p, voice = defaultVoice, error = null) }
    }

    fun setGirlfriendMode(enabled: Boolean) {
        stop()
        prefs.girlfriendMode = enabled
        _ui.update { it.copy(girlfriendMode = enabled, error = null) }
    }

    fun setVoice(v: String) {
        if (v == prefs.voice) return
        stop()
        prefs.voice = v
        _ui.update { it.copy(voice = v, error = null) }
    }

    fun setLiveModel(m: String) {
        if (m == prefs.liveModel) return
        stop()
        prefs.liveModel = m
        _ui.update { it.copy(liveModel = m, error = null) }
    }

    fun setChatModel(m: String) {
        if (m == prefs.chatModel) return
        stop()
        prefs.chatModel = m
        _ui.update { it.copy(chatModel = m, error = null) }
    }

    fun setSttModel(m: String) {
        if (m == prefs.sttModel) return
        stop()
        prefs.sttModel = m
        _ui.update { it.copy(sttModel = m, error = null) }
    }

    fun setTtsModel(m: String) {
        if (m == prefs.ttsModel) return
        stop()
        prefs.ttsModel = m
        _ui.update { it.copy(ttsModel = m, error = null) }
    }

    fun saveKey(k: String) {
        prefs.apiKey = k
        _ui.update { it.copy(hasKey = k.isNotBlank(), error = null, askKey = false) }
    }

    fun consumeAskKey() = _ui.update { it.copy(askKey = false) }

    // Modular मोड में लोकल कमांड न मिले तो LLM-राउटर से फोन-टूल पहचानना
    private suspend fun routeViaLlm(text: String): ToolExecutionResult {
        if (!MeeraTools.looksLikeCommand(text)) return ToolExecutionResult(false)
        val svc = MeeraAccessibilityService.instance
        val currentScreen = svc?.readScreenText(10) ?: "Unknown"
        val plan = rest.generatePlan(text, currentScreen)
        if (plan.isEmpty()) return ToolExecutionResult(false)
        
        var stepCount = 0
        var finalSpokenReply = ""
        var executedCount = 0
        var failed = false
        val maxSteps = 8
        
        while (stepCount < plan.size && stepCount < maxSteps) {
            val step = plan[stepCount]
            stepCount++
            val r = rest.routeCommand(step) ?: continue
            if (r.first == "none" || r.first.isBlank()) continue
            
            val res = MeeraTools.executeFunctionCall(appCtx, r.first, r.second)
            executedCount++
            finalSpokenReply += res.spokenReply + " "
            
            if (res.rawDataForAi.startsWith("FAILED") || res.rawDataForAi.startsWith("NEEDS_USER_HELP") || res.rawDataForAi.startsWith("PARTIAL") || res.rawDataForAi.startsWith("ERROR") || res.rawDataForAi.startsWith("TIMEOUT")) {
                finalSpokenReply = "Boss, step ${stepCount} par dikkat aayi: ${res.spokenReply}"
                failed = true
                break
            }
            if (r.first == "tap_element" || r.first == "screen_action" || r.first == "tap_text") {
                svc?.waitForScreenSettle(1200L)
            } else {
                kotlinx.coroutines.delay(1000)
            }
        }
        if (executedCount == 0) return ToolExecutionResult(true, "AgentLoop", "मैं यह काम नहीं कर पाई, एक बार फिर से बोलिए।", "FAILED: no step could be executed")
        if (failed) return ToolExecutionResult(true, "AgentLoop", finalSpokenReply.trim(), "FAILED: step $stepCount failed")
        return ToolExecutionResult(true, "AgentLoop", finalSpokenReply.trim(), "SUCCESS: Completed $executedCount of ${plan.size} steps.")
    }

    fun sendCameraFrame(jpeg: ByteArray) {
        if (!active || prefs.mode != Mode.LIVE) return
        val l = live ?: return
        if (l.ready) l.sendVideoFrame(jpeg)
    }

    fun showError(m: String) {
        st = VState.ERROR
        _ui.update { it.copy(state = VState.ERROR, error = m) }
    }

    @Suppress("DEPRECATION")
    fun start() {
        if (active) return
        if (prefs.apiKey.isBlank()) {
            _ui.update { it.copy(askKey = true, error = "पहले API Key डालें") }
            return
        }
        active = true
        requestInFlight.set(false)
        userBuf.clear(); aiBuf.clear()
        registerNetCallback()
        newUserTurn = true; dropAudio = false; liveTurnOpen = false
        detector.reset(); preRoll.clear(); utter.clear(); collecting = false; bargeFrames = 0; quietMs = 0
        _ui.update { it.copy(user = "", ai = "", error = null, latencyMs = null, memoryCount = prefs.getMemories().size) }
        MeeraKeepAliveService.start(getApplication())
        MeeraNotificationService.onIncomingMessage = { _, sender, msgText ->
            if (active && sender.isNotBlank() && msgText.isNotBlank()) {
                val grpFlag = MeeraNotificationService.lastNotifIsGroup
                viewModelScope.launch(Dispatchers.IO) {
                    val rule0 = MeeraAutoReply.getRuleFor(sender)
                    val rule = if (rule0 != null && !grpFlag && MeeraAutoReply.allowReply(sender)) rule0 else null
                    if (rule != null) {
                        try {
                            val autoReply = rest.generateQuickAutoReply(sender, msgText, rule, prefs.persona)
                            val (ok, _) = MeeraNotificationService.replyToNotification(appCtx, sender, autoReply)
                            if (ok) {
                                val info = "सुनो, WhatsApp पर $sender का मैसेज आया था: '$msgText' — मैंने हैंडल करके रिप्लाई भेज दिया है!"
                                _ui.update { it.copy(ai = info, lastToolStatus = "🤖 Auto-Replied $sender") }
                                appendChatLog("model", info)
                                if (st == VState.LISTENING && player?.busy != true) {
                                    val pcm = rest.tts(info, prefs.voice, prefs.ttsModel, prefs.persona, prefs.girlfriendMode)
                                    player?.enqueue(pcm)
                                }
                            }
                        } catch (_: Exception) {}
                    } else if (st == VState.LISTENING && player?.busy != true && !turnOpen()) {
                        try {
                            MeeraNotificationService.lastAnnouncedTime = System.currentTimeMillis()
                            val alertLine = "सुनो, WhatsApp पर $sender का मैसेज आया है: ${msgText.take(80)}। क्या मैं तुम्हारी तरफ से रिप्लाई कर दूँ?"
                            _ui.update { it.copy(ai = alertLine, lastToolStatus = "🔔 WhatsApp: $sender") }
                            appendChatLog("model", alertLine)
                            val pcm = rest.tts(alertLine, prefs.voice, prefs.ttsModel, prefs.persona, prefs.girlfriendMode)
                            setState(VState.ASSISTANT_SPEAKING)
                            player?.enqueue(pcm)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        MeeraOverlayService.start(getApplication())
        setState(VState.CONNECTING)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
        val sysPrompt = SystemPrompt.build(prefs.persona, prefs.girlfriendMode, prefs.getMemories() + prefs.getSkills())
        try {
            player = PcmPlayer()
            if (prefs.mode == Mode.LIVE) {
                live = GeminiLive(wsHttp, liveEvents).also {
                    it.connect(prefs.apiKey, prefs.liveModel, prefs.voice, sysPrompt, prefs.silenceMs)
                    MeeraLiveBridge.liveSession = it
                }
            } else {
                startMic()
            }
        } catch (e: Exception) {
            fail(e.message ?: "शुरू नहीं हो पाया")
        }
    }

    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null
    private var curNet: android.net.Network? = null

    private fun registerNetCallback() {
        try {
            val cm = getApplication<android.app.Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    curNet = network
                    live?.onNetworkChanged(true)
                }

                override fun onLost(network: android.net.Network) {
                    if (network == curNet) {
                        curNet = null
                        live?.onNetworkChanged(false)
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (_: Exception) {}
    }

    private fun unregisterNetCallback() {
        try {
            val cb = netCb ?: return
            val cm = getApplication<android.app.Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        netCb = null
        curNet = null
    }

    @Suppress("DEPRECATION")
    fun stop() {
        if (!active && st == VState.IDLE) return
        active = false
        requestInFlight.set(false)
        pipeline?.cancel(); pipeline = null
        MeeraLiveBridge.stopScreenWatch()
        MeeraLiveBridge.liveSession = null
        live?.close(); live = null
        mic?.stop(); mic = null
        unregisterNetCallback()
        MicMute.set(false)
        player?.release(); player = null
        try { am.isSpeakerphoneOn = false; am.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        collecting = false
        _level.value = 0f
        MeeraNotificationService.onIncomingMessage = null
        MeeraSiren.stop()
        MeeraKeepAliveService.stop(getApplication())
        MeeraOverlayService.stop(getApplication())
        setState(VState.IDLE)
    }

    private fun fail(msg: String) {
        stop()
        showError(msg)
    }

    private fun setState(s: VState) {
        st = s
        _ui.update { it.copy(state = s) }
    }

    private fun startMic() {
        mic = MicRecorder(::onFrame).also { it.start() }
        setState(VState.LISTENING)
    }

    private fun turnOpen(): Boolean =
        (prefs.mode == Mode.MODULAR && pipeline?.isActive == true) || liveTurnOpen

    private fun onFrame(frameIn: ShortArray) {
        if (!active) return
        val frame = if (MicMute.muted) ShortArray(frameIn.size) else frameIn   // म्यूट में सिर्फ़ ख़ामोशी जाती है
        val rms = rmsOf(frame)
        val p = player
        val assistant = st == VState.ASSISTANT_SPEAKING || p?.busy == true
        _level.value = if (assistant) (p?.level ?: 0f) else (rms * 6f).coerceIn(0f, 1f)

        if (assistant) {
            if (rms > Cfg.BARGE_RMS) {
                bargeFrames++
                if (bargeFrames >= Cfg.BARGE_FRAMES) { bargeFrames = 0; bargeIn() }
            } else bargeFrames = 0
        } else bargeFrames = 0

        if (st == VState.ASSISTANT_SPEAKING) {
            if (p?.busy != true && !turnOpen()) {
                quietMs += Cfg.FRAME_MS
                if (quietMs >= Cfg.ASSISTANT_QUIET_MS) {
                    quietMs = 0
                    detector.reset()
                    setState(VState.LISTENING)
                }
            } else quietMs = 0
        }

        val busy = st == VState.ASSISTANT_SPEAKING || player?.busy == true
        if (prefs.mode == Mode.LIVE) liveFrame(frame, rms, busy) else modularFrame(frame, rms, busy)
    }

    private fun bargeIn() {
        player?.flush()
        if (prefs.mode == Mode.MODULAR) {
            pipeline?.cancel()
            requestInFlight.set(false)
            utter.clear(); utter.addAll(preRoll); collecting = true
        } else {
            dropAudio = true
            liveTurnOpen = false
        }
        detector.forceSpeaking()
        quietMs = 0
        setState(VState.USER_SPEAKING)
    }

    private fun liveFrame(frame: ShortArray, rms: Float, busy: Boolean) {
        val l = live ?: return
        if (!l.ready) return
        val send = if (busy && rms < Cfg.BARGE_RMS) ShortArray(frame.size) else frame
        l.sendAudio(send.toLeBytes())
        if (busy) return
        when (detector.process(rms)) {
            TurnDetector.Ev.SPEECH_START -> if (st == VState.LISTENING || st == VState.PROCESSING) setState(VState.USER_SPEAKING)
            TurnDetector.Ev.SPEECH_END -> {
                speechEndAt = System.currentTimeMillis(); firstAudioPending = true
                if (st == VState.USER_SPEAKING) setState(VState.PROCESSING)
            }
            TurnDetector.Ev.NOISE -> if (st == VState.USER_SPEAKING) setState(VState.LISTENING)
            TurnDetector.Ev.NONE -> {}
        }
        if (st == VState.PROCESSING && System.currentTimeMillis() - speechEndAt > 8000) setState(VState.LISTENING)
    }

    private val liveEvents = object : GeminiLive.Events {
        override fun onReady() {
            try { startMic() } catch (e: Exception) { fail(e.message ?: "माइक शुरू नहीं हुआ") }
        }

        override fun onAudio(pcm: ByteArray) {
            if (dropAudio || !active) return
            liveTurnOpen = true
            if (st != VState.ASSISTANT_SPEAKING) {
                if (firstAudioPending) {
                    firstAudioPending = false
                    val ms = System.currentTimeMillis() - speechEndAt
                    _ui.update { it.copy(latencyMs = ms) }
                }
                setState(VState.ASSISTANT_SPEAKING)
            }
            player?.enqueue(pcm)
        }

        override fun onInputText(t: String) {
            dropAudio = false
            if (newUserTurn) { userBuf.clear(); aiBuf.clear(); newUserTurn = false }
            userBuf.append(t)
            _ui.update { it.copy(user = userBuf.toString().trim(), ai = "") }
        }

        override fun onOutputText(t: String) {
            if (dropAudio) return
            aiBuf.append(t)
            _ui.update { it.copy(ai = aiBuf.toString().trim()) }
        }

        override fun onToolCall(id: String, name: String, args: JSONObject) {
            toolCalledThisTurn = true
            viewModelScope.launch(Dispatchers.IO) {
                val res = MeeraTools.executeFunctionCall(appCtx, name, args).also { MeeraDebugLog.log("LIVE_TOOL", "$name args=$args -> ${it.rawDataForAi.take(100)}") }
                _ui.update { it.copy(lastToolStatus = "⚡ $name") }
                live?.sendToolResponse(id, name, res.rawDataForAi.ifBlank { res.spokenReply })
            }
        }

        override fun onInterrupted() {
            player?.flush()
            dropAudio = false; liveTurnOpen = false; newUserTurn = true
            if (st == VState.ASSISTANT_SPEAKING || st == VState.PROCESSING) {
                detector.forceSpeaking()
                setState(VState.USER_SPEAKING)
            }
        }

        override fun onTurnComplete() {
            val finishedUserText = userBuf.toString().trim()
            val finishedAiText = aiBuf.toString().trim()
            if (finishedUserText.isNotBlank()) {
                appendChatLog("user", finishedUserText)
                autoLearnFromUser(finishedUserText)
                // अगर मॉडल ने टूल कॉल किए बिना बोला हो लेकिन यूज़र ने ऐप खोलने कहा हो, तो बैकअप के तौर पर चलाएँ
                if (!toolCalledThisTurn && finishedUserText.length < 45 && !finishedUserText.lowercase().contains("chatgpt")) {
                    val cmdText = finishedUserText
                    viewModelScope.launch(Dispatchers.IO) { MeeraTools.tryDirectVoiceCommand(appCtx, cmdText) }
                }
            }
            if (finishedAiText.isNotBlank()) appendChatLog("model", finishedAiText)
            toolCalledThisTurn = false
            liveTurnOpen = false; newUserTurn = true; dropAudio = false
        }

        override fun onError(msg: String) {
            if (active) fail(msg)
        }

        override fun onReconnecting(attempt: Int) {
            if (!active) return
            player?.flush()
            liveTurnOpen = false; dropAudio = false; toolCalledThisTurn = false
            if (st != VState.CONNECTING) setState(VState.CONNECTING)
            _ui.update { it.copy(error = null, lastToolStatus = "📶 कनेक्शन टूटा, दोबारा जुड़ रही हूँ...") }
        }

        override fun onResumed() {
            if (!active) return
            detector.reset()
            setState(VState.LISTENING)
            _ui.update { it.copy(lastToolStatus = "✅ दोबारा जुड़ गई") }
        }
    }

    private fun modularFrame(frame: ShortArray, rms: Float, busy: Boolean) {
        if (!collecting) {
            preRoll.addLast(frame)
            while (preRoll.size > Cfg.PREROLL_FRAMES) preRoll.removeFirst()
        }
        if (busy || st == VState.PROCESSING || st == VState.CONNECTING || requestInFlight.get()) return
        if (collecting) utter.add(frame)
        when (detector.process(rms)) {
            TurnDetector.Ev.SPEECH_START -> {
                utter.clear(); utter.addAll(preRoll); collecting = true
                setState(VState.USER_SPEAKING)
            }
            TurnDetector.Ev.SPEECH_END -> {
                collecting = false
                val endAt = System.currentTimeMillis()
                val wav = pcmToWav(joinPcm(utter), Cfg.IN_RATE)
                utter.clear(); preRoll.clear()
                runPipeline(wav, endAt)
            }
            TurnDetector.Ev.NOISE -> {
                collecting = false; utter.clear()
                if (st == VState.USER_SPEAKING) setState(VState.LISTENING)
            }
            TurnDetector.Ev.NONE -> {}
        }
    }

    private fun runPipeline(wav: ByteArray, endAt: Long) {
        if (!requestInFlight.compareAndSet(false, true)) return
        pipeline?.cancel()
        setState(VState.PROCESSING)
        pipeline = viewModelScope.launch(Dispatchers.IO) {
            val full = StringBuilder()
            var userAdded = false
            try {
                val text = rest.transcribe(wav, prefs.sttModel)
                if (text.isBlank()) {
                    requestInFlight.set(false)
                    setState(VState.LISTENING)
                    return@launch
                }
                _ui.update { it.copy(user = text, ai = "", latencyMs = null) }
                appendChatLog("user", text)
                autoLearnFromUser(text)

                // Modular डायरेक्ट कमांड चेक (जैसे "WhatsApp खोलो", "स्क्रीन पढ़ो")
                MeeraDebugLog.log("MODULAR", "heard='$text'")
                val directRes = MeeraTools.tryDirectVoiceCommand(appCtx, text)
                MeeraDebugLog.log("DIRECT", "executed=${directRes.executed} tool=${directRes.toolName} raw=${directRes.rawDataForAi.take(90)}")
                val toolRes = if (directRes.executed) directRes else routeViaLlm(text)
                if (!directRes.executed) MeeraDebugLog.log("LLM_ROUTE", "executed=${toolRes.executed} tool=${toolRes.toolName} raw=${toolRes.rawDataForAi.take(90)}")
                if (toolRes.executed && toolRes.spokenReply.isNotBlank() && (toolRes.rawDataForAi.startsWith("VERIFIED_SUCCESS:") || toolRes.rawDataForAi.startsWith("SUCCESS:"))) {
                    _ui.update { it.copy(ai = toolRes.spokenReply, lastToolStatus = "⚡ ${toolRes.toolName}") }
                    appendChatLog("model", toolRes.spokenReply)
                    val pcm = rest.tts(toolRes.spokenReply, prefs.voice, prefs.ttsModel, prefs.persona, prefs.girlfriendMode)
                    _ui.update { it.copy(latencyMs = System.currentTimeMillis() - endAt) }
                    setState(VState.ASSISTANT_SPEAKING)
                    player?.enqueue(pcm)
                    return@launch
                }

                val effectiveUserMsg = if (toolRes.executed && toolRes.rawDataForAi.isNotBlank()) {
                    "$text\n\n[SCREEN / TOOL DATA]:\n${toolRes.rawDataForAi}"
                } else text

                history.add("user" to effectiveUserMsg); userAdded = true

                val sysPrompt = SystemPrompt.build(prefs.persona, prefs.girlfriendMode, prefs.getMemories() + prefs.getSkills())
                val ttsJobs = Channel<Deferred<ByteArray>>(capacity = 3)
                val voice = prefs.voice
                val selectedTts = prefs.ttsModel
                val curPersona = prefs.persona
                val curGf = prefs.girlfriendMode
                var first = true

                val speaker = launch {
                    for (job in ttsJobs) {
                        val pcm = job.await()
                        if (first) {
                            first = false
                            val ms = System.currentTimeMillis() - endAt
                            _ui.update { it.copy(latencyMs = ms) }
                            setState(VState.ASSISTANT_SPEAKING)
                        }
                        player?.enqueue(pcm)
                    }
                }
                try {
                    rest.streamChat(history.takeLast(12), sysPrompt, prefs.chatModel) { s ->
                        full.append(s).append(' ')
                        _ui.update { it.copy(ai = full.toString().trim()) }
                        val cleanText = s.replace(Regex("[*#_`~]"), "").trim()
                        if (cleanText.isNotEmpty()) {
                            val job = async(Dispatchers.IO) {
                                rest.tts(cleanText, voice, selectedTts, curPersona, curGf)
                            }
                            ttsJobs.send(job)
                        }
                    }
                } finally {
                    ttsJobs.close()
                }
                speaker.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (active) fail(friendly(e))
            } finally {
                requestInFlight.set(false)
                if (userAdded) {
                    val reply = full.toString().trim()
                    if (reply.isNotBlank()) {
                        history.add("model" to reply)
                        appendChatLog("model", reply)
                    } else history.removeLastOrNull()
                }
            }
        }
    }

    private fun friendly(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        val low = m.lowercase()
        return when {
            e is UnknownHostException || low.contains("unable to resolve host") || low.contains("network is unreachable") ->
                "इंटरनेट कनेक्शन कमज़ोर या बंद है। एक बार Wi-Fi या मोबाइल डेटा चेक कर लो 🌐"
            low.contains("429") || low.contains("quota") || low.contains("resource_exhausted") || low.contains("rate limit") ->
                "अरे सुनो, अभी आपकी Gemini API Key की फ्री मिनट्स/कोटा लिमिट पूरी हो गई है 🥺 एक-दो मिनट रुककर फिर से बात करते हैं, या साइड मेन्यू में जाकर दूसरी Key डाल लो!"
            low.contains("503") || low.contains("busy") || low.contains("overloaded") ->
                "अभी Google का AI सर्वर थोड़ा व्यस्त (Busy) है, कुछ सेकंड बाद दोबारा बोलो!"
            low.contains("api key") || low.contains("401") || low.contains("403") ->
                "Google ने यह API Key स्वीकार नहीं की। कृपया Google AI Studio से पूरी Key कॉपी करके दोबारा पेस्ट करें।"
            else -> m.take(160)
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
