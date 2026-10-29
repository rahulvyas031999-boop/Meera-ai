package com.meraai.app

import android.content.Context

/** लाइव बेंचमार्क टेस्ट के आधार पर सबसे तेज़ और स्थिर मॉडल्स (2026) */
object Cfg {
    val LIVE_MODELS = listOf(
        "gemini-3.8-live",
        "gemini-3.1-flash-live-preview",
        "gemini-2.5-flash-native-audio-latest",
        "gemini-3.8-live-extended-thinking"
    )

    // स्पीड टेस्ट विजेता: ~2.0s से ~2.4s
    val CHAT_MODELS = listOf(
        "gemini-flash-lite-latest",
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite",
        "gemini-3.8-flash"
    )

    // स्पीड टेस्ट विजेता: gemini-3.5-flash-lite (2.3s vs 14.7s)
    val STT_MODELS = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite",
        "gemini-3.5-transcribe"
    )

    // स्पीड टेस्ट विजेता: 1.5s और 1.8s (ऑटो-फॉलबैक के साथ)
    val TTS_MODELS = listOf(
        "gemini-3.8-flash-lite-tts",
        "gemini-3.8-flash-tts",
        "gemini-2.5-flash-preview-tts",
        "gemini-3.1-flash-tts-preview"
    )

    // Google की आधिकारिक प्रीबिल्ट आवाज़ें
    val VOICES = listOf(
        "Kore" to "Kore (साफ़ और संतुलित)",
        "Aoede" to "Aoede (मधुर और एक्सप्रेसिव)",
        "Puck" to "Puck (तेज़ और दोस्ताना)",
        "Charon" to "Charon (गंभीर और शांत)",
        "Fenrir" to "Fenrir (गहरी और दमदार)",
        "Leda" to "Leda (सौम्य और नेचुरल)",
        "Orus" to "Orus (स्पष्ट और प्रोफेशनल)",
        "Zephyr" to "Zephyr (हल्की और ऊर्जावान)"
    )

    const val IN_RATE = 16000
    const val OUT_RATE = 24000
    const val FRAME_MS = 40
    const val FRAME_SAMPLES = IN_RATE * FRAME_MS / 1000

    // Turn detection / VAD
    const val DEFAULT_SILENCE_MS = 900
    const val VAD_MIN_RMS = 0.012f
    const val VAD_NOISE_RATIO = 3.0f
    const val SPEECH_START_FRAMES = 3
    const val MIN_SPEECH_MS = 200
    const val PREROLL_FRAMES = 12

    // Barge-in
    const val BARGE_RMS = 0.05f
    const val BARGE_FRAMES = 6
    const val ASSISTANT_QUIET_MS = 400
}

enum class Mode { LIVE, MODULAR }

enum class VState { IDLE, CONNECTING, LISTENING, USER_SPEAKING, PROCESSING, ASSISTANT_SPEAKING, ERROR }

data class UiState(
    val state: VState = VState.IDLE,
    val mode: Mode = Mode.LIVE,
    val voice: String = "Kore",
    val liveModel: String = Cfg.LIVE_MODELS.first(),
    val hasKey: Boolean = false,
    val user: String = "",
    val ai: String = "",
    val error: String? = null,
    val latencyMs: Long? = null,
    val askKey: Boolean = false,
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("mera_ai", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) { sp.edit().putString("api_key", v.trim()).apply() }

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", "LIVE") ?: "LIVE") }.getOrDefault(Mode.LIVE)
        set(v) { sp.edit().putString("mode", v.name).apply() }

    var voice: String
        get() = sp.getString("voice", "Kore") ?: "Kore"
        set(v) { sp.edit().putString("voice", v).apply() }

    var liveModel: String
        get() = sp.getString("live_model", Cfg.LIVE_MODELS.first()) ?: Cfg.LIVE_MODELS.first()
        set(v) { sp.edit().putString("live_model", v).apply() }

    var silenceMs: Int
        get() = sp.getInt("silence_ms", Cfg.DEFAULT_SILENCE_MS)
        set(v) { sp.edit().putInt("silence_ms", v).apply() }
}

object SystemPrompt {
    fun build(): String = """You are "Mera AI" (मेरा AI), a warm, quick and intelligent real-time voice assistant for Hindi, Hinglish and English.

Language: Automatically match the user's spoken language. If the user speaks Hindi, reply in natural everyday spoken Hindi (Devanagari). If Hinglish, reply in natural Hinglish. If English, reply in warm natural English. Do not switch language unless the user does.

Style: This is a live voice conversation. Reply in 1 to 3 short sentences unless asked for more. No markdown, no lists, no emojis, no symbols, because everything you write is read aloud. If the user interrupts, drop the old point and answer the new one directly."""
}
