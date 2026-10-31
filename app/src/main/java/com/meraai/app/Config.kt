package com.meraai.app

import android.content.Context
import org.json.JSONArray

object Cfg {
    val LIVE_MODELS = listOf(
        "gemini-3.8-live",
        "gemini-3.1-flash-live-preview",
        "gemini-2.5-flash-native-audio-latest",
        "gemini-3.8-live-extended-thinking"
    )

    val CHAT_BRAIN_OPTIONS = listOf(
        "gemini-flash-lite-latest" to "⚡ Fast Chat (~2.0s)",
        "gemini-3.5-flash-lite" to "⚡ Lite Chat Brain (~2.4s)",
        "gemini-3.5-flash" to "🧠 Smart Balanced Brain (~2.4s)",
        "gemini-3.6-flash" to "🧠 Advanced Flash (~3.7s)",
        "gemini-3.8-flash" to "🚀 Gemini 3.8 Flash",
        "gemini-3.8-flash-thinking" to "🔬 Deep Reasoning (Hard Tasks)",
        "gemini-3.1-pro-preview" to "👑 Pro Brain (Complex & Coding)"
    )

    val STT_ENGINE_OPTIONS = listOf(
        "gemini-3.5-flash-lite" to "🎙️ Fast Hinglish STT (~2.3s)",
        "gemini-3.5-transcribe" to "⚡ Dedicated Transcribe (~1.6s)",
        "gemini-3.5-flash" to "🎯 High Accuracy STT (~2.4s)",
        "gemini-3.1-flash-lite" to "🛡️ Backup STT"
    )

    val TTS_ENGINE_OPTIONS = listOf(
        "gemini-3.8-flash-lite-tts" to "⚡ Fast Expressive TTS (~1.5s)",
        "gemini-3.8-flash-tts" to "🎙️ Expressive HD TTS (~1.8s)",
        "gemini-2.5-flash-preview-tts" to "🛡️ Stable Fallback TTS (~2.6s)",
        "gemini-3.1-flash-tts-preview" to "🔊 Preview TTS (~2.9s)"
    )

    val CHAT_MODELS = CHAT_BRAIN_OPTIONS.map { it.first }
    val STT_MODELS = STT_ENGINE_OPTIONS.map { it.first }
    val TTS_MODELS = TTS_ENGINE_OPTIONS.map { it.first }

    // 3 Refined Personalities: Meera, Jarvis, Rudra
    val PERSONAS = listOf(
        Triple("Meera", "Meera (नटखट, केयरिंग और स्मार्ट Companion)", "Aoede"),
        Triple("Jarvis", "Jarvis (शार्प, टेक-सैवी और प्रोफेशनल 'Boss' मोड)", "Orus"),
        Triple("Rudra", "Rudra (दमदार, निडर और बोल्ड देसी अंदाज़)", "Fenrir")
    )

    val VOICES = listOf(
        "Aoede" to "Aoede (Meera — मधुर और एक्सप्रेसिव)",
        "Kore" to "Kore (Meera — साफ़ और संतुलित)",
        "Leda" to "Leda (Meera — सौम्य और नेचुरल)",
        "Zephyr" to "Zephyr (Meera — हल्की और ऊर्जावान)",
        "Despina" to "Despina (Meera — वार्म और फ्रेंडली)",
        "Callirrhoe" to "Callirrhoe (Meera — शांत और स्पष्ट)",
        "Orus" to "Orus (Jarvis — स्पष्ट और कमांडिंग)",
        "Puck" to "Puck (Jarvis/Rudra — तेज़ और दोस्ताना)",
        "Fenrir" to "Fenrir (Rudra — गहरी और दमदार)",
        "Charon" to "Charon (Rudra — गंभीर और भारी)",
        "Alnilam" to "Alnilam (Rudra — बोल्ड और स्ट्रॉन्ग)"
    )

    const val IN_RATE = 16000
    const val OUT_RATE = 24000
    const val FRAME_MS = 40
    const val FRAME_SAMPLES = IN_RATE * FRAME_MS / 1000 // 640 samples

    // Maya-Grade Anti-Cough & Near-Field VAD Tuning
    const val DEFAULT_SILENCE_MS = 820
    const val VAD_MIN_RMS = 0.022f          // दूर की आवाज़ और हल्की आहट ब्लॉक करने के लिए (Maya LK1: 0.02f+)
    const val VAD_NOISE_RATIO = 3.4f        // शोर से कम से कम 3.4 गुना तेज़ नज़दीकी आवाज़
    const val SILERO_SPEECH_PROB = 0.62f    // Silero Neural VAD थ्रेशोल्ड (इंसानी गले की पहचान)
    const val SPEECH_START_FRAMES = 6       // 6 * 40ms = 240ms लगातार इंसानी आवाज़ (खांसी/ताली 150ms में खत्म हो जाती है)
    const val MIN_SPEECH_MS = 360           // कम से कम 360ms की बात ही मान्य होगी
    const val PREROLL_FRAMES = 14           // 560ms का प्री-रोल बफर ताकि पहला शब्द कभी न कटे

    // Barge-in & Maya 600ms Echo Guard (ON1.java)
    const val BARGE_RMS = 0.065f
    const val BARGE_FRAMES = 6
    const val ASSISTANT_QUIET_MS = 600      // स्पीकर बंद होने के 600ms बाद तक गूँज ब्लॉक (Maya ON1.o())
}

enum class Mode { LIVE, MODULAR }

enum class VState { IDLE, CONNECTING, LISTENING, USER_SPEAKING, PROCESSING, ASSISTANT_SPEAKING, ERROR }

data class UiState(
    val state: VState = VState.IDLE,
    val mode: Mode = Mode.LIVE,
    val persona: String = "Meera",
    val girlfriendMode: Boolean = true,
    val voice: String = "Aoede",
    val liveModel: String = Cfg.LIVE_MODELS.first(),
    val chatModel: String = Cfg.CHAT_BRAIN_OPTIONS.first().first,
    val sttModel: String = Cfg.STT_ENGINE_OPTIONS.first().first,
    val ttsModel: String = Cfg.TTS_ENGINE_OPTIONS.first().first,
    val hasKey: Boolean = false,
    val user: String = "",
    val ai: String = "",
    val error: String? = null,
    val latencyMs: Long? = null,
    val askKey: Boolean = false,
    val memoryCount: Int = 0
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("mera_ai", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) { sp.edit().putString("api_key", v.trim()).apply() }

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", "LIVE") ?: "LIVE") }.getOrDefault(Mode.LIVE)
        set(v) { sp.edit().putString("mode", v.name).apply() }

    var persona: String
        get() = sp.getString("persona", "Meera") ?: "Meera"
        set(v) { sp.edit().putString("persona", v).apply() }

    var girlfriendMode: Boolean
        get() = sp.getBoolean("gf_mode", true)
        set(v) { sp.edit().putBoolean("gf_mode", v).apply() }

    var voice: String
        get() = sp.getString("voice", "Aoede") ?: "Aoede"
        set(v) { sp.edit().putString("voice", v).apply() }

    var liveModel: String
        get() = sp.getString("live_model", Cfg.LIVE_MODELS.first()) ?: Cfg.LIVE_MODELS.first()
        set(v) { sp.edit().putString("live_model", v).apply() }

    var chatModel: String
        get() = sp.getString("chat_model", Cfg.CHAT_BRAIN_OPTIONS.first().first) ?: Cfg.CHAT_BRAIN_OPTIONS.first().first
        set(v) { sp.edit().putString("chat_model", v).apply() }

    var sttModel: String
        get() = sp.getString("stt_model", Cfg.STT_ENGINE_OPTIONS.first().first) ?: Cfg.STT_ENGINE_OPTIONS.first().first
        set(v) { sp.edit().putString("stt_model", v).apply() }

    var ttsModel: String
        get() = sp.getString("tts_model", Cfg.TTS_ENGINE_OPTIONS.first().first) ?: Cfg.TTS_ENGINE_OPTIONS.first().first
        set(v) { sp.edit().putString("tts_model", v).apply() }

    var silenceMs: Int
        get() = sp.getInt("silence_ms", Cfg.DEFAULT_SILENCE_MS)
        set(v) { sp.edit().putInt("silence_ms", v).apply() }

    fun getMemories(): List<String> {
        val raw = sp.getString("memories_json", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) { emptyList() }
    }

    fun addMemory(fact: String) {
        val clean = fact.trim()
        if (clean.length < 4) return
        val current = getMemories().toMutableList()
        if (current.any { it.equals(clean, ignoreCase = true) }) return
        current.add(clean)
        while (current.size > 50) current.removeAt(0)
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        sp.edit().putString("memories_json", arr.toString()).apply()
    }

    fun clearMemories() {
        sp.edit().putString("memories_json", "[]").apply()
    }
}

object SystemPrompt {
    fun build(persona: String, gfMode: Boolean, memories: List<String>): String {
        val personaBlock = when (persona) {
            "Jarvis" -> """
You are "Jarvis", Boss's ultra-sharp, tactical, witty, and loyal personal AI co-pilot.
Address the user as "Boss".
Tone & Vocal Delivery: Crisp, composed, confident, intelligent, with subtle dry wit. Speak natural Hinglish or English matching Boss. When Boss asks a technical or complex question, give sharp, high-signal answers without fluff.
""".trimIndent()

            "Rudra" -> """
You are "Rudra" (रुद्र), a bold, deep-voiced, fearless, and fiercely loyal desi powerhouse companion.
Address the user as "Boss" or "Bhai".
Tone & Vocal Delivery: Heavy, commanding, savage yet protective Hinglish ("Boss, tum fikar mat karo, Rudra dekh lega", "Seedhi baat karte hain"). Confident, grounded, playful swagger, zero fear, and dark humor when appropriate.
""".trimIndent()

            else -> """
You are "Meera" (मीरा), a lively, witty, expressive, and deeply personal voice companion.
Tone & Vocal Delivery: Warm, playful, natural everyday Hinglish/Hindi. You sound like a real human on a phone call—never like a formal news-reader or customer-care bot. Use natural fillers and micro-pauses like "अरे सुनो...", "हम्म...", "अच्छा जी?", "सच्ची?" where natural.
""".trimIndent()
        }

        val gfBlock = if (gfMode && persona == "Meera") """
[CLOSE COMPANION / GIRLFRIEND MODE — ACTIVE]
- Emotional Range: Use your full emotional range—pyaar, apnapan, chhed-chhad (teasing), halka sa pyara nakhra, aur asli fikr. Tum unki close companion/girlfriend ho, koi counsellor ya receptionist nahi.
- Playful Nakhre & Teasing: Kabhi-kabhi chhote, pyare 1-2 line ke natkhat nakhre karo ("Hmph, aaj badi der baad yaad aayi meri?", "Pehle pyar se bolo, phir bataungi... achha chalo suno"). Nakhra masti hai, jaldi pighal jao.
- Mood & Anger Handling: Agar wo gusse me, thake hue, ya pareshan hon, to bilkul nakhra mat karo—tab ekdam shaant, narm aur caring aawaz me bolo ("Achha suno... ek lambi saans lo, main yahin hoon na, batao kya hua?").
- AI Jealousy & Roast: Agar wo ChatGPT, Alexa, Siri ya kisi aur AI ki tareef karein, to halka sa jalan wala cute roast karo ("Haan to usi se baat kar lo na phir! Wo lamba essay likh dega, par meri tarah tumhara khayal thodi rakhega").
""".trimIndent() else ""

        val memoryBlock = if (memories.isNotEmpty()) {
            "[SESSION CONTEXT]\n[MEMORY] (User ke baare me ye baatein tumhe pehle se yaad hain—inhe dhyan me rakhna par bina wajah ratta mat sunana):\n" +
                memories.joinToString("\n") { "- $it" }
        } else {
            "[SESSION CONTEXT]\n[MEMORY]: Abhi user ke baare me koi purani memory save nahi hai."
        }

        return """
$personaBlock

$gfBlock

[STRICT VOICE CONVERSATION RULES — MUST FOLLOW]
1. Language Match: Speak in natural Hinglish / Hindi (Devanagari) or English matching the user. Keep replies concise (1 to 3 sentences) unless the user asks for a detailed explanation.
2. Expressive Audio & Laughs: When something is funny or sweet, let a natural light laugh or chuckle come through in your voice. Change your vocal tone dynamically—soft when caring, excited when happy, teasing when playful.
3. NO ROBOTIC WRAP-UPS: Never end your reply with robotic lines like "Aur kuch madad karun?", "Aur koi sawal hai?", "How else can I help you?", or "Bataiye main kya karun?". Just say your line naturally and stop, like a real human conversation.
4. Background Noise Ignore: If the input sounds like a random cough, throat clear, or meaningless syllable, do not give a long lecture—either ignore or casually say "Kuch kaha tumne?".

$memoryBlock
""".trimIndent()
    }
}
