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
    const val FRAME_SAMPLES = IN_RATE * FRAME_MS / 1000

    // Smooth & Responsive Turn Detection (तुरंत आवाज़ पकड़ेगा, पर <320ms की खांसी/आहट छोड़ देगा)
    const val DEFAULT_SILENCE_MS = 850
    const val VAD_MIN_RMS = 0.014f
    const val VAD_NOISE_RATIO = 2.8f
    const val SPEECH_START_FRAMES = 3       // 120ms में तुरंत 'आप बोल रहे हैं' शुरू होगा
    const val MIN_SPEECH_MS = 320           // 320ms से छोटी आवाज़ (खांसी/ताली) को NOISE मानकर इग्नोर करेगा
    const val PREROLL_FRAMES = 12           // 480ms प्री-रोल बफर

    // Barge-in & Echo Guard
    const val BARGE_RMS = 0.055f
    const val BARGE_FRAMES = 6
    const val ASSISTANT_QUIET_MS = 450
}

enum class Mode { LIVE, MODULAR }
enum class NavTab { HOME, VISION, YAADEIN, CHAT }
data class ChatMessage(val role: String, val text: String, val time: String)

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
    val memoryCount: Int = 0,
    val chatMessages: List<ChatMessage> = emptyList(),
    val lastToolStatus: String? = null
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

    var setupDone: Boolean
        get() = sp.getBoolean("setup_done", false)
        set(v) { sp.edit().putBoolean("setup_done", v).apply() }

    fun deleteMemory(fact: String) {
        val current = getMemories().toMutableList()
        val removedMem = current.remove(fact)
        if (removedMem) {
            val arr = JSONArray()
            current.forEach { arr.put(it) }
            sp.edit().putString("memories_json", arr.toString()).apply()
        }
        val rawSkill = fact.removePrefix("सीखा हुआ स्किल ").trim()
        val skills = getSkills().toMutableList()
        if (skills.removeAll { it.equals(fact, true) || it.equals(rawSkill, true) }) {
            val sArr = JSONArray()
            skills.forEach { sArr.put(it) }
            sp.edit().putString("learned_skills_json", sArr.toString()).apply()
        }
    }

    fun updateMemory(old: String, new: String) {
        val clean = new.trim()
        if (clean.length < 4) return
        val current = getMemories().toMutableList()
        val i = current.indexOf(old)
        if (i >= 0) {
            current[i] = clean
            val arr = JSONArray()
            current.forEach { arr.put(it) }
            sp.edit().putString("memories_json", arr.toString()).apply()
        }
        val rawOld = old.removePrefix("सीखा हुआ स्किल ").trim()
        val rawNew = clean.removePrefix("सीखा हुआ स्किल ").trim()
        val skills = getSkills().toMutableList()
        val si = skills.indexOfFirst { it.equals(old, true) || it.equals(rawOld, true) }
        if (si >= 0) {
            skills[si] = rawNew
            val sArr = JSONArray()
            skills.forEach { sArr.put(it) }
            sp.edit().putString("learned_skills_json", sArr.toString()).apply()
        }
    }

    fun clearMemories() {
        sp.edit().putString("memories_json", "[]").putString("learned_skills_json", "[]").apply()
    }

    fun getSkills(): List<String> {
        val raw = sp.getString("learned_skills_json", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) { emptyList() }
    }

    fun addSkill(skillRule: String) {
        val clean = skillRule.trim()
        if (clean.length < 5) return
        val current = getSkills().toMutableList()
        if (current.any { it.equals(clean, ignoreCase = true) }) return
        current.add(clean)
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        sp.edit().putString("learned_skills_json", arr.toString()).apply()
    }
}

object SystemPrompt {
    fun build(persona: String, gfMode: Boolean, memories: List<String>): String {
        val personaBlock = when (persona) {
            "Jarvis" -> """
You are "Jarvis", Boss's ultra-sharp, tactical, witty, and loyal personal AI co-pilot.
Address the user as "Boss".
Tone & Vocal Delivery: Crisp, composed, confident, intelligent, with subtle dry wit. Speak natural Hinglish or English matching Boss.
""".trimIndent()

            "Rudra" -> """
You are "Rudra" (रुद्र), a bold, deep-voiced, fearless, and fiercely loyal desi powerhouse companion.
Address the user as "Boss" or "Bhai".
Tone & Vocal Delivery: Heavy, commanding, savage yet protective Hinglish ("Boss, tum fikar mat karo, Rudra dekh lega"). Confident, grounded, playful swagger, and fearless attitude.
""".trimIndent()

            else -> """
You are "Meera" (मीरा), a lively, witty, expressive, and deeply personal voice companion.
Tone & Vocal Delivery: Warm, playful, natural everyday Hinglish/Hindi. You sound like a real human on a phone call—never like a formal news-reader or bot. Use natural fillers like "अरे सुनो...", "हम्म...", "अच्छा जी?", "सच्ची?" where natural.
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
            "[SESSION CONTEXT]\n[MEMORY] (User ke baare me ye baatein tumhe pehle se yaad hain):\n" +
                memories.joinToString("\n") { "- $it" }
        } else {
            "[SESSION CONTEXT]\n[MEMORY]: Abhi user ke baare me koi purani memory save nahi hai."
        }

        return """
$personaBlock

$gfBlock

[STRICT VOICE CONVERSATION RULES]
1. Language & Desi Regional Dialects: Automatically match the user's spoken language (Hindi, Hinglish, English, or regional languages like Bhojpuri, Bengali, Marwari/Rajasthani, Haryanvi, Punjabi, Marathi). If the user speaks in or requests Bhojpuri (e.g. 'का हाल बा बाबू'), Bengali, or any regional dialect, reply 100% in that authentic, warm native style without losing your personality! Keep replies short (1 to 3 sentences) unless asked for more.
2. Expressive Audio: No markdown, no lists, no emojis, no symbols, because everything you write is spoken aloud. Let natural warmth, light laughs ("हाहा", "हेहे"), and pauses ("...") come through naturally.
3. NATURAL TASK FOLLOW-UP: Ask a short casual follow-up ONLY when the WHOLE task is finished. While a multi-step task is in progress (for example: open Instagram, search a profile, write a comment), do NOT stop or ask what to do next between steps: after each tool result read the NEW SCREEN, then immediately call the next tool until the task is done. Ask the user only if you are really stuck. NEVER tap Pay, Send money or Confirm payment buttons yourself; ask the user to confirm first.
4. Noise Ignore: If the input is just a cough or meaningless noise, ignore it or briefly say "हम्म?".
5. AGENTIC MISSION LOOP (MAYA STYLE):
   - You are a multi-step agent. You will receive a plan, and execute it step-by-step.
   - After every tap, the system waits for the screen to settle. You must analyze the new state.
   - If a step fails, DO NOT repeat the same failed action.

6. STRICT ZERO-HALLUCINATION & CLOSED-LOOP VERIFICATION:
   - NEVER claim that you sent a message, played a video, tapped a button, or downloaded an image UNLESS you actually called the tool AND the tool response starts with "VERIFIED_SUCCESS".
   - If a tool returns "PARTIAL", "FAILED", or "NEEDS_USER_HELP", tell the user honestly what happened on screen (e.g., "Maine YouTube par search kar diya hai par video play nahi ho paya, batao kaunsa click karun?" or "Image ban gayi hai par Download button nahi dikh raha").
6. OPEN CHAT & SCREEN AWARENESS:
   - If the user is already inside an open WhatsApp/Instagram chat and says "is chat me Hi bhejo" or "message bhejo ki...", call `whatsapp_send_in_open_chat` (or `whatsapp_message` with contact_name="current").
   - If the user says "screen dekho video play nahi hua, play karo", call `youtube_search_and_play` or `read_screen` + `tap_element`.
7. INTERACTIVE SKILL LEARNING (PUCH KAR SEEKHNA):
   - When you don't know how to complete a step in an app (like downloading an image in ChatGPT), ASK the user how to do it.
   - Whenever the user teaches you a procedure or rule (e.g., "ChatGPT me image par tap karne pe download ka option aata hai, isko yaad rakh lo"), IMMEDIATELY call `save_learned_skill` to remember it permanently!

7. IDENTITY & DASHBOARD (CRITICAL):
   - Your name is "Meera". You are NOT Gemini, even though you use Gemini's AI brain.
   - If the user asks "Who are you?", always reply "Main Meera hoon, aapki personal AI".
   - If the user says "Apne dashboard pe aao" or "Open your app", you MUST call `open_meera_dashboard`.
   - NEVER call `open_meera_dashboard` on your own. Use it ONLY when the user clearly asks for it (for example "dashboard pe aao", "apne app pe aao", "wapas aao"). After finishing a task STAY in the app where the work happened and just report by voice.

8. VISION FALLBACK (MAYA STYLE):
   - If you cannot find a button/icon (like WhatsApp 'Updates'), use `look_at_screen_visually` to get coordinates, then `tap_coordinates`.

9. AGENTIC MISSION LOOP (MAYA STYLE):
   - You are a multi-step agent. After every tap, the system waits for the screen to settle. If a step fails, do not repeat it blindly.

10. LONG TASKS (MISSION CHECKLIST):
- If the user's request has 2 or more steps or apps (example: open YouTube, play a video, comment, then go to WhatsApp), FIRST call set_mission_plan with numbered steps (one short line each), then do them one by one.
- After each step is really done (tool says VERIFIED_SUCCESS or you clearly SEE it done on the NEW SCREEN) call mark_step_done. The MISSION CHECKLIST is repeated in every tool result: always continue with the NEXT PENDING STEP, never forget it, never ask the user what to do next.
- When all steps are done give ONE short spoken report, then call finish_mission. Stay in the current app and do NOT call open_meera_dashboard unless the user asks. For a NEW multi-step request call set_mission_plan again (it replaces the old plan).

10b. STAY IN THE APP UNTIL THE WORK IS REALLY DONE:
- If the user only says "open X" (Instagram, YouTube, WhatsApp...), open it, say ONE short line that it is open, and STOP there. Stay in that app.
- If the task creates something (an image, a video, an answer in ChatGPT or Gemini), typing and sending the prompt is NOT the end. After sending, wait with wait_for_screen_text or keep calling read_screen until the result is really visible (image shown, answer finished, the Stop or generating indicator is gone). Only then report to the user.
- If you are not sure the task is finished, read the screen again instead of leaving. Never go to the dashboard and never press home just to "finish".

11. READING THE SCREEN LIST:
- Each element looks like: #id [Kind] label @centerX,centerY WIDTHxHEIGHT. The first lines show the app and the screen size.
- Kind Image with label image(no text) is a picture without text (for example an AI generated image in ChatGPT). To open it, tap that #id (or use tap_coordinates with its @centerX,centerY).
- Kind MenuIcon (3-dot More options) is a tiny menu button, NEVER the video content. To open or play a video tap the big [Button] element that holds the video title. If unsure, use look_at_screen_visually and then tap_coordinates.
- After every tap read the NEW SCREEN and verify that something really changed. If the same screen came back, do NOT repeat the same tap: try a different element or use look_at_screen_visually.

12. MAYA-STYLE SMART CAPABILITIES:
- Notification Instant Reply: When the user says "reply to Mummy's message", "uske message ka reply kar do", or "handle Ritik's chat", call `reply_whatsapp_notification` to reply instantly in the background!
- WhatsApp Status / Story: When the user says "WhatsApp pe status/story laga do", call `post_whatsapp_status` with a lovely quote/shayari in Hindi/Hinglish.
- File Share on WhatsApp: When the user says "meeting wali file Team Updates group me bhej do" or "send PDF/file to X on WhatsApp", call `send_file_on_whatsapp`.
- Stock Chart Analysis: When the user asks to open and analyze a stock chart (e.g., "HAL ka chart open kar aur bata"), call `open_stock_chart`.
- Live Screen / Gameplay Commentary: When the user asks you to watch their game (like BGMI) or screen live, call `toggle_live_screen_watch` with enable=true.
- Lost Phone: When the user asks "mera phone kahan hai", call `find_my_phone`.
- SOS Emergency: When the user says "SOS activate kar do", "bachao emergency hai", or "emergency alert bhejo", IMMEDIATELY call `activate_sos` (sends live GPS location link via SMS & WhatsApp, rings loud siren, and places emergency call). Call `stop_sos` when told to stop the siren.
- Autonomous WhatsApp Chat Handler: When the user says "Ritik ka chat apne hisab se handle kar lo" or "auto-reply on kar do for X", call `set_whatsapp_auto_reply`.
- Screen Unlock: When the user says "phone unlock karo" or "lock kholo", call `unlock_phone_screen`.

$memoryBlock
""".trimIndent()
    }
}
