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
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class VoiceViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val wsHttp = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val rest = GeminiRest(http) { prefs.apiKey }
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _ui = MutableStateFlow(
        UiState(
            mode = prefs.mode,
            voice = prefs.voice,
            liveModel = prefs.liveModel,
            hasKey = prefs.apiKey.isNotBlank()
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var st = VState.IDLE
    @Volatile private var active = false
    val isActive: Boolean get() = active

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

    private val userBuf = StringBuilder()
    private val aiBuf = StringBuilder()
    private val history = ArrayList<Pair<String, String>>()

    // ---- सेटिंग्स ----
    fun setMode(m: Mode) {
        if (m == prefs.mode) return
        stop()
        prefs.mode = m
        _ui.update { it.copy(mode = m, error = null) }
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

    fun saveKey(k: String) {
        prefs.apiKey = k
        _ui.update { it.copy(hasKey = k.isNotBlank(), error = null, askKey = false) }
    }

    fun consumeAskKey() = _ui.update { it.copy(askKey = false) }

    fun showError(m: String) {
        st = VState.ERROR
        _ui.update { it.copy(state = VState.ERROR, error = m) }
    }

    // ---- सेशन ----
    @Suppress("DEPRECATION")
    fun start() {
        if (active) return
        if (prefs.apiKey.isBlank()) {
            _ui.update { it.copy(askKey = true, error = "पहले API Key डालें") }
            return
        }
        active = true
        history.clear(); userBuf.clear(); aiBuf.clear()
        newUserTurn = true; dropAudio = false; liveTurnOpen = false
        detector.reset(); preRoll.clear(); utter.clear(); collecting = false; bargeFrames = 0; quietMs = 0
        _ui.update { it.copy(user = "", ai = "", error = null, latencyMs = null) }
        setState(VState.CONNECTING)

        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true

        try {
            player = PcmPlayer()
            if (prefs.mode == Mode.LIVE) {
                live = GeminiLive(wsHttp, liveEvents).also {
                    it.connect(prefs.apiKey, prefs.liveModel, prefs.voice, SystemPrompt.build(), prefs.silenceMs)
                }
            } else {
                startMic()
            }
        } catch (e: Exception) {
            fail(e.message ?: "शुरू नहीं हो पाया")
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        if (!active && st == VState.IDLE) return
        active = false
        pipeline?.cancel(); pipeline = null
        live?.close(); live = null
        mic?.stop(); mic = null
        player?.release(); player = null
        try { am.isSpeakerphoneOn = false; am.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        collecting = false
        _level.value = 0f
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

    // ---- हर 40ms का माइक फ्रेम ----
    private fun onFrame(frame: ShortArray) {
        if (!active) return
        val rms = rmsOf(frame)
        val p = player
        val assistant = st == VState.ASSISTANT_SPEAKING || p?.busy == true
        _level.value = if (assistant) (p?.level ?: 0f) else (rms * 6f).coerceIn(0f, 1f)

        if (assistant) {
            if (rms > Cfg.BARGE_RMS) {
                bargeFrames++
                if (bargeFrames > Cfg.BARGE_FRAMES) { bargeFrames = 0; bargeIn() }
            } else bargeFrames = 0
        } else bargeFrames = 0

        if (st == VState.ASSISTANT_SPEAKING) {
            if (p?.busy != true && !turnOpen()) {
                quietMs += Cfg.FRAME_MS
                if (quietMs > Cfg.ASSISTANT_QUIET_MS) {
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
            utter.clear(); utter.addAll(preRoll); collecting = true
        } else {
            dropAudio = true
            liveTurnOpen = false
        }
        detector.forceSpeaking()
        quietMs = 0
        setState(VState.USER_SPEAKING)
    }

    // ---- LIVE ----
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
        override fun onInterrupted() {
            player?.flush()
            dropAudio = false; liveTurnOpen = false; newUserTurn = true
            if (st == VState.ASSISTANT_SPEAKING || st == VState.PROCESSING) {
                detector.forceSpeaking()
                setState(VState.USER_SPEAKING)
            }
        }
        override fun onTurnComplete() {
            liveTurnOpen = false; newUserTurn = true; dropAudio = false
        }
        override fun onError(msg: String) {
            if (active) fail(msg)
        }
    }

    // ---- MODULAR (Parallel Pre-Fetched TTS + 40ms Chunk Enqueue) ----
    private fun modularFrame(frame: ShortArray, rms: Float, busy: Boolean) {
        if (!collecting) {
            preRoll.addLast(frame)
            while (preRoll.size > Cfg.PREROLL_FRAMES) preRoll.removeFirst()
        }
        if (busy || st == VState.PROCESSING || st == VState.CONNECTING) return
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

    /** बड़े PCM ऑडियो को Live मोड की तरह 40ms (1920 bytes) के स्मूथ चंक्स में प्लेयर में भेजना */
    private fun enqueueSmoothly(pcm: ByteArray) {
        val chunkBytes = 1920 // 40ms at 24kHz 16-bit mono
        var off = 0
        while (off < pcm.size && active) {
            val end = minOf(off + chunkBytes, pcm.size)
            player?.enqueue(pcm.copyOfRange(off, end))
            off = end
        }
    }

    private fun runPipeline(wav: ByteArray, endAt: Long) {
        pipeline?.cancel()
        setState(VState.PROCESSING)
        pipeline = viewModelScope.launch(Dispatchers.IO) {
            val full = StringBuilder()
            var userAdded = false
            try {
                val text = rest.transcribe(wav)
                if (text.isBlank()) { setState(VState.LISTENING); return@launch }
                _ui.update { it.copy(user = text, ai = "", latencyMs = null) }
                history.add("user" to text); userAdded = true

                // Parallel TTS Jobs Channel: दूसरा वाक्य पहले वाक्य के बजते समय ही बैकग्राउंड में तैयार हो जाएगा
                val ttsQueue = Channel<Deferred<ByteArray>>(Channel.UNLIMITED)
                val voice = prefs.voice
                var first = true

                val speaker = launch {
                    for (deferredPcm in ttsQueue) {
                        val pcm = deferredPcm.await()
                        if (first) {
                            first = false
                            val ms = System.currentTimeMillis() - endAt
                            _ui.update { it.copy(latencyMs = ms) }
                            setState(VState.ASSISTANT_SPEAKING)
                        }
                        enqueueSmoothly(pcm)
                    }
                }

                try {
                    rest.streamChat(history.takeLast(10), SystemPrompt.build()) { s ->
                        full.append(s).append(' ')
                        _ui.update { it.copy(ai = full.toString().trim()) }
                        val cleanText = s.replace(Regex("[*#_~`]"), "").trim()
                        if (cleanText.isNotBlank()) {
                            val job = async(Dispatchers.IO) { rest.tts(cleanText, voice) }
                            ttsQueue.send(job)
                        }
                    }
                } finally {
                    ttsQueue.close()
                }
                speaker.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (active) fail(friendly(e))
            } finally {
                if (userAdded) {
                    if (full.isNotBlank()) history.add("model" to full.toString().trim())
                    else history.removeLastOrNull()
                }
            }
        }
    }

    private fun friendly(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            e is UnknownHostException -> "इंटरनेट नहीं है"
            m.contains("API key", true) || m.contains("HTTP 401") || m.contains("HTTP 403") -> "API Key सही नहीं है या इजाज़त नहीं: ${m.take(120)}"
            m.contains("HTTP 429") -> "कोटा/लिमिट पूरी हो गई (429)"
            else -> m.take(160)
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
