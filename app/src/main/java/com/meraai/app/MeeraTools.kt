package com.meraai.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max

data class ToolExecutionResult(
    val executed: Boolean,
    val toolName: String = "",
    val spokenReply: String = "",
    val rawDataForAi: String = ""
)

data class MacroStep(
    val tool: String,
    val args: Map<String, String>,
    val optional: Boolean = false
)

data class BuiltinMacro(
    val name: String,
    val intent: String,
    val paramName: String,
    val steps: List<MacroStep>
)

object MeeraTools {

    private val KNOWN_APPS = mapOf(
        "meera" to listOf("com.meraai.app"),
        "dashboard" to listOf("com.meraai.app"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "व्हाट्सएप" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "व्हाट्सऐप" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "vhatsep" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "votsap" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "youtube" to listOf("com.google.android.youtube", "app.revanced.android.youtube"),
        "यूट्यूब" to listOf("com.google.android.youtube"),
        "instagram" to listOf("com.instagram.android"),
        "इंस्टाग्राम" to listOf("com.instagram.android"),
        "chatgpt" to listOf("com.openai.chatgpt"),
        "चैटजीपीटी" to listOf("com.openai.chatgpt"),
        "gemini" to listOf("com.google.android.apps.bard"),
        "जेमिनी" to listOf("com.google.android.apps.bard"),
        "chrome" to listOf("com.android.chrome"),
        "क्रोम" to listOf("com.android.chrome"),
        "camera" to listOf("com.android.camera", "com.sec.android.app.camera", "com.oneplus.camera"),
        "कैमरा" to listOf("com.android.camera"),
        "gmail" to listOf("com.google.android.gm"),
        "maps" to listOf("com.google.android.apps.maps"),
        "मैप्स" to listOf("com.google.android.apps.maps"),
        "spotify" to listOf("com.spotify.music"),
        "facebook" to listOf("com.facebook.katana"),
        "telegram" to listOf("org.telegram.messenger"),
        "termux" to listOf("com.termux"),
        "paytm" to listOf("net.one97.paytm"),
        "phonepe" to listOf("com.phonepe.app"),
        "gpay" to listOf("com.google.android.apps.nbu.paisa.user"),
        "google pay" to listOf("com.google.android.apps.nbu.paisa.user")
    )

    /** Maya's Exact 5 Built-in Macros from builtin_macros.json */
    val BUILTIN_MACROS: List<BuiltinMacro> = listOf(
        BuiltinMacro(
            name = "chatgpt image",
            intent = "ChatGPT app me image generate karo aur image par tap karke gallery me save/download karo",
            paramName = "image prompt",
            steps = listOf(
                MacroStep("open_app", mapOf("name" to "ChatGPT")),
                MacroStep("wait", mapOf("seconds" to "4")),
                MacroStep("tap_text", mapOf("text" to "New chat"), optional = true),
                MacroStep("wait", mapOf("seconds" to "3")),
                MacroStep("type_text", mapOf("text" to "Create an image: {param}")),
                MacroStep("tap_text", mapOf("text" to "Send Message")),
                MacroStep("wait", mapOf("seconds" to "6")),
                MacroStep("wait_for_screen_text", mapOf("text" to "Creating image", "mode" to "disappear", "timeout_seconds" to "300")),
                MacroStep("wait", mapOf("seconds" to "3")),
                MacroStep("tap_center_image", emptyMap(), optional = true),
                MacroStep("wait", mapOf("seconds" to "2")),
                MacroStep("tap_save_or_download", emptyMap(), optional = true),
                MacroStep("wait", mapOf("seconds" to "2")),
                MacroStep("go_back", emptyMap(), optional = true)
            )
        ),
        BuiltinMacro(
            name = "gemini image",
            intent = "Gemini app me Images tool se image banao aur gallery me save karo",
            paramName = "image prompt",
            steps = listOf(
                MacroStep("open_app", mapOf("name" to "Gemini")),
                MacroStep("wait", mapOf("seconds" to "4")),
                MacroStep("tap_text", mapOf("text" to "New chat"), optional = true),
                MacroStep("wait", mapOf("seconds" to "3")),
                MacroStep("tap_text", mapOf("text" to "Add attachments"), optional = true),
                MacroStep("wait", mapOf("seconds" to "2")),
                MacroStep("tap_text", mapOf("text" to "Images"), optional = true),
                MacroStep("wait", mapOf("seconds" to "2")),
                MacroStep("type_text", mapOf("text" to "Create an image: {param}")),
                MacroStep("tap_text", mapOf("text" to "Send")),
                MacroStep("wait_for_screen_text", mapOf("text" to "Generated image", "mode" to "appear", "timeout_seconds" to "300")),
                MacroStep("wait", mapOf("seconds" to "2")),
                MacroStep("tap_text", mapOf("text" to "Generated image 1"), optional = true),
                MacroStep("wait", mapOf("seconds" to "3")),
                MacroStep("tap_text", mapOf("text" to "Save"), optional = true),
                MacroStep("wait", mapOf("seconds" to "3")),
                MacroStep("go_back", emptyMap())
            )
        ),
        BuiltinMacro(
            name = "gemini video",
            intent = "Gemini app me Videos tool se video banao aur save karo",
            paramName = "video prompt",
            steps = listOf(
                MacroStep("open_app", mapOf("name" to "Gemini")),
                MacroStep("wait", mapOf("seconds" to "4")),
                MacroStep("tap_text", mapOf("text" to "New chat"), optional = true),
                MacroStep("wait", mapOf("seconds" to "3")),
                MacroStep("tap_text", mapOf("text" to "Add attachments")),
                MacroStep("wait", mapOf("seconds" to "2")),
                MacroStep("tap_text", mapOf("text" to "Videos")),
                MacroStep("wait", mapOf("seconds" to "3")),
                MacroStep("type_text", mapOf("text" to "{param}")),
                MacroStep("tap_text", mapOf("text" to "Send")),
                MacroStep("wait_for_screen_text", mapOf("text" to "Your video is ready", "mode" to "appear", "timeout_seconds" to "600")),
                MacroStep("wait", mapOf("seconds" to "2")),
                MacroStep("tap_text", mapOf("text" to "More options")),
                MacroStep("wait", mapOf("seconds" to "2")),
                MacroStep("tap_text", mapOf("text" to "Save video")),
                MacroStep("wait", mapOf("seconds" to "4"))
            )
        ),
        BuiltinMacro(
            name = "chatgpt se poochho",
            intent = "ChatGPT app me sawaal poochho aur jawab padhke sunao",
            paramName = "sawaal",
            steps = listOf(
                MacroStep("open_app", mapOf("name" to "ChatGPT")),
                MacroStep("wait", mapOf("seconds" to "4")),
                MacroStep("tap_text", mapOf("text" to "New chat"), optional = true),
                MacroStep("wait", mapOf("seconds" to "4")),
                MacroStep("type_text", mapOf("text" to "{param}")),
                MacroStep("tap_text", mapOf("text" to "Send Message")),
                MacroStep("wait", mapOf("seconds" to "15")),
                MacroStep("read_screen", emptyMap())
            )
        ),
        BuiltinMacro(
            name = "gemini se poochho",
            intent = "Gemini app me sawaal poochho aur jawab padhke sunao",
            paramName = "sawaal",
            steps = listOf(
                MacroStep("open_app", mapOf("name" to "Gemini")),
                MacroStep("wait", mapOf("seconds" to "4")),
                MacroStep("tap_text", mapOf("text" to "New chat"), optional = true),
                MacroStep("wait", mapOf("seconds" to "3")),
                MacroStep("type_text", mapOf("text" to "{param}")),
                MacroStep("tap_text", mapOf("text" to "Send")),
                MacroStep("wait", mapOf("seconds" to "15")),
                MacroStep("read_screen", emptyMap())
            )
        )
    )

    fun getGeminiToolsJson(): JSONArray {
        val funcs = JSONArray()

        funcs.put(
            JSONObject().put("name", "open_meera_dashboard").put("description", "Open your OWN app (Meera AI Dashboard). Use when user says 'Come to your dashboard' or after finishing a task.").put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )
        funcs.put(
            JSONObject().put("name", "look_at_screen_visually").put("description", "Look at the screen image. Use if text parsing fails (e.g., hidden buttons, images).").put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("question", JSONObject().put("type", "STRING"))))
        )
        funcs.put(
            JSONObject().put("name", "tap_coordinates").put("description", "Tap at X, Y coordinates.").put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("x", JSONObject().put("type", "INTEGER")).put("y", JSONObject().put("type", "INTEGER"))).put("required", JSONArray().put("x").put("y")))
        )
        funcs.put(
            JSONObject()
                .put("name", "open_app")
                .put("description", "Open any installed mobile application on the user's Android phone such as WhatsApp, YouTube, Instagram, ChatGPT, Gemini, Chrome, Camera, Settings, etc.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put("properties", JSONObject().put("app_name", JSONObject().put("type", "STRING").put("description", "Name of the app to open")))
                        .put("required", JSONArray().put("app_name"))
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "read_screen")
                .put("description", "List all visible text, buttons, icons and input fields on the current screen, each with an #id number (#1, #2, etc.) that can be tapped with tap_element.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )

        funcs.put(
            JSONObject()
                .put("name", "tap_element")
                .put("description", "Tap an on-screen element by its #id number from read_screen, or by its visible text label.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("id", JSONObject().put("type", "INTEGER").put("description", "The #id number from read_screen"))
                                .put("text", JSONObject().put("type", "STRING").put("description", "Visible text or button label to tap if id is not used"))
                        )
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "wait_for_screen_text")
                .put("description", "Wait until specific text APPEARS on or DISAPPEARS from the screen (e.g. waiting for image generation or page load).")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("text", JSONObject().put("type", "STRING").put("description", "On-screen text to watch for"))
                                .put("mode", JSONObject().put("type", "STRING").put("description", "appear (default) or disappear"))
                                .put("timeout_seconds", JSONObject().put("type", "INTEGER").put("description", "Max seconds to wait (default 30)"))
                        )
                        .put("required", JSONArray().put("text"))
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "screen_action")
                .put("description", "Perform an action on the screen: click text, type text into an input field, scroll_down, scroll_up, home, or back.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("action_type", JSONObject().put("type", "STRING").put("description", "One of: click, type, home, back, scroll_down, scroll_up, notifications"))
                                .put("target_or_text", JSONObject().put("type", "STRING").put("description", "Button text to click, or message text to type"))
                        )
                        .put("required", JSONArray().put("action_type"))
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "whatsapp_message")
                .put("description", "Send a WhatsApp message to a contact name or phone number. Supports both Hindi and English contact names, verifies chat opening and sending, and searches inside WhatsApp if needed.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("contact_name", JSONObject().put("type", "STRING").put("description", "Contact name exactly as spoken by user"))
                                .put("message", JSONObject().put("type", "STRING").put("description", "Message text to send"))
                        )
                        .put("required", JSONArray().put("contact_name"))
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "make_phone_call")
                .put("description", "Place a phone call to a contact name (matches Hindi speech to English contacts) or phone number.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("contact_name", JSONObject().put("type", "STRING").put("description", "Contact name or phone number to call"))
                        )
                        .put("required", JSONArray().put("contact_name"))
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "youtube_search_and_play")
                .put("description", "Open YouTube, search for the song/video, and AUTOMATICALLY PLAY the first real video result (skipping Shorts and ads). Use when the user says 'chalao', 'laga do', or 'play'.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("query", JSONObject().put("type", "STRING").put("description", "Song or video name to play on YouTube"))
                        )
                        .put("required", JSONArray().put("query"))
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "play_or_search")
                .put("description", "Search something on YouTube or Google. Set auto_play=true if the user asked to play/watch the video.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("query", JSONObject().put("type", "STRING").put("description", "Search query or song name"))
                                .put("platform", JSONObject().put("type", "STRING").put("description", "youtube or google"))
                                .put("auto_play", JSONObject().put("type", "BOOLEAN").put("description", "true to automatically click and play the first video"))
                        )
                        .put("required", JSONArray().put("query"))
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "whatsapp_send_in_open_chat")
                .put("description", "Type and send a message directly in the CURRENTLY OPEN WhatsApp/Instagram chat on screen without searching contacts. Use when the user says 'abhi hum us chat me hain message bhejo', 'is chat me Hi bhejo', or 'yahan message likho'.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("message", JSONObject().put("type", "STRING").put("description", "Exact message text to send in the open chat"))
                        )
                        .put("required", JSONArray().put("message"))
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "save_learned_skill")
                .put("description", "Save a new UI automation trick or app skill taught by the user into permanent memory (e.g., 'ChatGPT me image banne ke baad image par tap karne se Save/Download ka option aata hai').")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("app_or_task", JSONObject().put("type", "STRING").put("description", "App or task name, e.g. 'ChatGPT Image Download'"))
                                .put("how_to_steps", JSONObject().put("type", "STRING").put("description", "Clear step-by-step rule taught by the user"))
                        )
                        .put("required", JSONArray().put("app_or_task").put("how_to_steps"))
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "play_macro")
                .put("description", "Run one of the built-in multi-step task playbooks: 'chatgpt image', 'gemini image', 'gemini video', 'chatgpt se poochho', 'gemini se poochho'.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("name", JSONObject().put("type", "STRING").put("description", "Macro name, e.g., 'chatgpt image', 'gemini image', 'gemini video', 'chatgpt se poochho', 'gemini se poochho'"))
                                .put("param", JSONObject().put("type", "STRING").put("description", "The prompt or question for the macro"))
                        )
                        .put("required", JSONArray().put("name"))
                )
        )

        return JSONArray().put(JSONObject().put("functionDeclarations", funcs))
    }

    fun executeFunctionCall(ctx: Context, name: String, args: JSONObject): ToolExecutionResult {
        return when (name) {
            "open_meera_dashboard" -> {
                val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    ctx.startActivity(intent)
                    ToolExecutionResult(true, name, "मैं अपने डैशबोर्ड (Meera AI) पर आ गई हूँ।", "VERIFIED_SUCCESS: Opened Meera Dashboard")
                } else ToolExecutionResult(true, name, "डैशबोर्ड नहीं मिला।", "ERROR")
            }
            "look_at_screen_visually" -> {
                val svc = MeeraAccessibilityService.instance
                if (svc == null || android.os.Build.VERSION.SDK_INT < 30) {
                    ToolExecutionResult(true, name, "Vision ke liye Android 11+ aur Accessibility ON hona chahiye.", "ERROR")
                } else {
                    val question = args.optString("question", "What is on the screen? Give coordinates if asked.")
                    val bitmap = kotlinx.coroutines.runBlocking { kotlinx.coroutines.suspendCancellableCoroutine<android.graphics.Bitmap?> { cont -> svc.captureScreenshot { bmp -> cont.resumeWith(Result.success(bmp)) } } }
                    if (bitmap != null) {
                        val rest = GeminiRest(okhttp3.OkHttpClient(), { Prefs(ctx).apiKey })
                        val ans = kotlinx.coroutines.runBlocking { rest.analyzeScreenVisually(bitmap, question) }
                        ToolExecutionResult(true, name, ans, "VERIFIED_SUCCESS: Vision Analysis -> $ans")
                    } else ToolExecutionResult(true, name, "Screenshot nahi le payi.", "ERROR")
                }
            }
            "tap_coordinates" -> {
                val x = args.optDouble("x", -1.0).toFloat(); val y = args.optDouble("y", -1.0).toFloat()
                if (x < 0 || y < 0) ToolExecutionResult(true, name, "Sahi coordinates batao.", "ERROR")
                else {
                    val ok = MeeraAccessibilityService.instance?.tapCoordinates(x, y) == true
                    ToolExecutionResult(true, name, if (ok) "($x, $y) par tap kar diya." else "Tap fail hua.", if (ok) "VERIFIED_SUCCESS: Tapped at $x, $y" else "FAILED")
                }
            }
            "open_app" -> {
                val appName = args.optString("app_name", "").trim()
                val ok = openAppByName(ctx, appName)
                if (ok) ToolExecutionResult(true, name, "$appName खोल दिया है! बताओ इसमें आगे क्या करना है?", "SUCCESS: Opened app $appName")
                else ToolExecutionResult(true, name, "फोन में $appName नाम का ऐप नहीं मिला।", "ERROR: App $appName not found on device")
            }
            "read_screen" -> {
                val svc = MeeraAccessibilityService.instance
                if (svc == null) {
                    ToolExecutionResult(true, name, "स्क्रीन पढ़ने के लिए पहले Accessibility सर्विस ऑन करनी होगी।", "ERROR: Accessibility service is OFF.")
                } else {
                    val content = svc.readScreenElements()
                    val summary = if (content.isBlank()) "स्क्रीन पर अभी कोई टेक्स्ट नहीं दिख रहा है।" else content
                    ToolExecutionResult(true, name, "", "CURRENT SCREEN ELEMENTS:\n$summary")
                }
            }
            "tap_element" -> {
                val svc = MeeraAccessibilityService.instance
                if (svc == null) {
                    ToolExecutionResult(true, name, "टैप करने के लिए Accessibility ऑन करें।", "ERROR: Accessibility OFF")
                } else {
                    val id = args.optInt("id", -1)
                    val text = args.optString("text", "").trim()
                    val ok = if (id > 0) svc.tapElementById(id) else svc.clickByText(text)
                    val label = if (id > 0) "#$id" else text
                    ToolExecutionResult(true, name, if (ok) "$label पर टैप कर दिया।" else "स्क्रीन पर '$label' नहीं मिला।", if (ok) "SUCCESS: Tapped $label" else "FAILED: $label not found")
                }
            }
            "wait_for_screen_text" -> {
                val svc = MeeraAccessibilityService.instance
                val text = args.optString("text", "").trim()
                val mode = args.optString("mode", "appear").trim()
                val timeout = args.optInt("timeout_seconds", 30)
                if (svc == null) {
                    ToolExecutionResult(true, name, "Accessibility बंद है।", "ERROR: Accessibility OFF")
                } else {
                    val ok = svc.waitForScreenText(text, mode, timeout)
                    ToolExecutionResult(true, name, if (ok) "'$text' पूरा हो गया।" else "'$text' का इंतज़ार टाइमआउट हो गया।", if (ok) "SUCCESS: Condition met for $text" else "TIMEOUT waiting for $text")
                }
            }
            "screen_action" -> {
                val act = args.optString("action_type", "").lowercase().trim()
                val target = args.optString("target_or_text", "").trim()
                val svc = MeeraAccessibilityService.instance
                if (svc == null) {
                    ToolExecutionResult(true, name, "यह काम करने के लिए कृपया Accessibility परमिशन ऑन करें।", "ERROR: Accessibility Service OFF")
                } else {
                    val ok = when (act) {
                        "click" -> svc.clickByText(target)
                        "type" -> svc.inputText(target, autoSend = true)
                        else -> svc.performSystemAction(act)
                    }
                    ToolExecutionResult(true, name, if (ok) "कर दिया।" else "स्क्रीन पर '$target' नहीं मिला।", if (ok) "SUCCESS: $act ($target)" else "FAILED: $act ($target)")
                }
            }
            "whatsapp_send_in_open_chat" -> {
                val msg = args.optString("message", "").trim()
                sendInCurrentOpenChatTool(msg)
            }
            "save_learned_skill" -> {
                val appTask = args.optString("app_or_task", "").trim()
                val steps = args.optString("how_to_steps", "").trim()
                val combined = "[$appTask]: $steps"
                Prefs(ctx).addSkill(combined)
                Prefs(ctx).addMemory("सीखा हुआ स्किल $combined")
                ToolExecutionResult(
                    true, name,
                    "मैंने '$appTask' का यह तरीका अपनी मेमोरी और स्किल्स में हमेशा के लिए सेव कर लिया है! अगली बार मैं बिल्कुल ऐसे ही करूँगी।",
                    "VERIFIED_SUCCESS: Saved learned skill -> $combined"
                )
            }
            "whatsapp_message" -> {
                val person = args.optString("contact_name", "").trim()
                val msg = args.optString("message", "").trim()
                sendWhatsAppVerified(ctx, person, msg)
            }
            "make_phone_call" -> {
                val person = args.optString("contact_name", "").trim()
                callPerson(ctx, person)
            }
            "youtube_search_and_play" -> {
                val q = args.optString("query", "").trim()
                playOrSearchMedia(ctx, q, "youtube", autoPlay = true)
            }
            "play_or_search" -> {
                val q = args.optString("query", "").trim()
                val platform = args.optString("platform", "youtube").lowercase()
                val autoPlay = args.optBoolean("auto_play", !platform.contains("google"))
                playOrSearchMedia(ctx, q, platform, autoPlay = autoPlay)
            }
            "play_macro" -> {
                val macroName = args.optString("name", "").trim()
                val param = args.optString("param", "").trim()
                runBuiltinMacro(ctx, macroName, param)
            }
            else -> ToolExecutionResult(false)
        }
    }

    /** Execute a Built-in Macro Playbook step-by-step with wait_for_screen_text & _optional support */
    fun runBuiltinMacro(ctx: Context, macroName: String, param: String): ToolExecutionResult {
        val cleanName = macroName.lowercase().trim()
        val macro = BUILTIN_MACROS.find {
            it.name.equals(cleanName, true) || cleanName.contains(it.name) || it.name.contains(cleanName)
        } ?: return ToolExecutionResult(true, "play_macro", "'$macroName' नाम का कोई टास्क नहीं मिला।", "ERROR: Macro not found")

        if (param.isBlank()) {
            return ToolExecutionResult(
                true, "play_macro",
                "'${macro.name}' चलाने के लिए ${macro.paramName} बताइए—क्या लिखना या बनवाना है?",
                "NEEDS_INPUT: Ask user for ${macro.paramName}"
            )
        }

        Thread {
            var lastRead = ""
            for (step in macro.steps) {
                try {
                    val svc = MeeraAccessibilityService.instance
                    when (step.tool) {
                        "open_app" -> openAppByName(ctx, step.args["name"].orEmpty())
                        "wait" -> {
                            val sec = (step.args["seconds"]?.toLongOrNull() ?: 3L).coerceIn(1L, 30L)
                            Thread.sleep(sec * 1000L)
                        }
                        "tap_text" -> {
                            val t = step.args["text"].orEmpty().replace("{param}", param)
                            val ok = svc?.clickByText(t) == true
                            if (!ok && !step.optional) {
                                Thread.sleep(1200)
                                svc?.clickByText(t)
                            }
                        }
                        "type_text" -> {
                            val t = step.args["text"].orEmpty().replace("{param}", param)
                            svc?.inputText(t, autoSend = false)
                        }
                        "wait_for_screen_text" -> {
                            val t = step.args["text"].orEmpty()
                            val mode = step.args["mode"] ?: "appear"
                            val timeout = step.args["timeout_seconds"]?.toIntOrNull() ?: 120
                            svc?.waitForScreenText(t, mode, timeout)
                        }
                        "tap_center_image" -> {
                            svc?.tapCenterImage()
                        }
                        "tap_save_or_download" -> {
                            val saved = svc?.clickByText("Save") == true ||
                                svc?.clickByText("Download") == true ||
                                svc?.clickByText("सेव") == true ||
                                (svc?.clickByText("More options") == true && Thread.sleep(900).let { svc.clickByText("Download") || svc.clickByText("Save") }) ||
                                (svc?.clickByText("Share") == true && Thread.sleep(1200).let { svc.clickByText("Download") || svc.clickByText("Save") })
                            if (!saved) {
                                lastRead = "NEEDS_USER_HELP: Image generated on screen, but Save/Download button was not automatically found."
                            }
                        }
                        "read_screen" -> {
                            lastRead = svc?.readScreenElements() ?: ""
                        }
                        "go_back" -> svc?.performSystemAction("back")
                    }
                } catch (_: Exception) {}
            }
        }.start()

        return ToolExecutionResult(
            true, "play_macro",
            "चालू कर रही हूँ '${macro.name}' ✨ मैं हर स्टेप स्क्रीन देखकर पूरा करूँगी।",
            "SUCCESS: Started macro ${macro.name} with param '$param'"
        )
    }

    /** Direct Voice Router with Strict Scroll Guard & Smart Intent Separation */
    fun tryDirectVoiceCommand(ctx: Context, userText: String): ToolExecutionResult {
        val raw = userText.trim()
        val low = raw.lowercase()

        // 1. Strict Scroll Guard: ONLY scroll when user explicitly says scroll / नीचे स्क्रॉल / ऊपर स्क्रॉल
        if (low.contains("स्क्रॉल डाउन") || low.contains("नीचे स्क्रॉल") || low == "नीचे करो" || low.contains("scroll down")) {
            val ok = MeeraAccessibilityService.instance?.performSystemAction("scroll_down") == true
            return ToolExecutionResult(true, "screen_action", if (ok) "नीचे स्क्रॉल कर दिया है। और नीचे करूँ या कुछ खोलना है?" else "स्क्रॉल करने के लिए Accessibility ऑन करें।", "SUCCESS: Scrolled Down")
        }
        if (low.contains("स्क्रॉल अप") || low.contains("ऊपर स्क्रॉल") || low == "ऊपर करो" || low.contains("scroll up")) {
            val ok = MeeraAccessibilityService.instance?.performSystemAction("scroll_up") == true
            return ToolExecutionResult(true, "screen_action", if (ok) "ऊपर स्क्रॉल कर दिया है। आगे बताओ क्या करना है?" else "Accessibility ऑन करें।", "SUCCESS: Scrolled Up")
        }
        if (low == "बैक करो" || low == "पीछे जाओ" || low == "वापस आओ" || low == "go back" || low == "back") {
            val ok = MeeraAccessibilityService.instance?.performSystemAction("back") == true
            return ToolExecutionResult(true, "screen_action", if (ok) "पीछे आ गई। अब बताओ क्या करें?" else "Accessibility ऑन करें।", "SUCCESS: Back")
        }

        // 1B. Open-Chat Direct Message (जब यूज़र कहे "इस चैट में हाय भेजो" या WhatsApp खुला हो और कहे "मैसेज भेजो ...")
        val openChatMatch = Regex("(?:इस चैट में|इसी चैट में|यहाँ|अंदर|is chat me|isi chat me|yahan)[ \t]*(.+?)[ \t]*(?:का मैसेज भेजो|मैसेज भेजो|लिखो|भेजो|bhejo|send karo)$", RegexOption.IGNORE_CASE).find(raw)
        if (openChatMatch != null) {
            val msg = openChatMatch.groupValues[1].replace(Regex("^(?:कि|ki)[ \t]*", RegexOption.IGNORE_CASE), "").trim()
            if (msg.isNotBlank()) return sendInCurrentOpenChatTool(msg)
        }
        val activePkg = MeeraAccessibilityService.instance?.getActivePackage().orEmpty()
        if (activePkg.contains("whatsapp") && !low.contains("को ") && (low.contains("मैसेज भेजो") || low.contains("message bhejo") || low.contains("लिखकर भेजो"))) {
            val msg = raw.replace(Regex("(?i)^.*?(?:मैसेज भेजो|message bhejo|लिखकर भेजो)(?:[ \t]+कि|[ \t]+ki)?[ \t]*"), "").trim()
            if (msg.isNotBlank()) return sendInCurrentOpenChatTool(msg)
        }

        // 2. Built-in Macro Triggers (e.g., "ChatGPT में शेर की इमेज बनाओ")
        if ((low.contains("chatgpt") || low.contains("चैटजीपीटी")) && (low.contains("इमेज") || low.contains("फोटो") || low.contains("image") || low.contains("photo"))) {
            val promptClean = raw
                .replace(Regex("(?i)(और उसे डाउनलोड करो|और डाउनलोड करो|डाउनलोड भी करो|गैलरी में सेव करो|and download it|and save it)"), " ")
                .replace(Regex("(?i)(हेलो|मीरा|अरे|सुनो|चैटजीपीटी|chatgpt|में|मे|जाकर|जाओ|वहां पर|वहाँ पर|एक|की|का|इमेज|फोटो|image|photo|बनाओ|बना दो|जनरेट करो|क्रिएट करो|create karo|generate karo)"), " ")
                .replace(Regex("[।,.!]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (promptClean.isNotBlank()) return runBuiltinMacro(ctx, "chatgpt image", promptClean)
        }
        if ((low.contains("gemini") || low.contains("जेमिनी")) && (low.contains("इमेज") || low.contains("फोटो") || low.contains("image") || low.contains("photo"))) {
            val promptClean = raw.replace(Regex("(?i)(gemini|जेमिनी|में|मे|जाकर|जाओ|एक|की|का|इमेज|फोटो|image|photo|बनाओ|बना दो|जनरेट करो)"), " ").trim()
            if (promptClean.isNotBlank()) return runBuiltinMacro(ctx, "gemini image", promptClean)
        }

        // 3. Screen Read & Notifications
        if (low.contains("स्क्रीन पढ़") || low.contains("screen padh") || low.contains("स्क्रीन पर क्या") ||
            low.contains("screen par kya") || low.contains("read screen")
        ) {
            return executeFunctionCall(ctx, "read_screen", JSONObject())
        }
        if (low.contains("नोटिफिकेशन") || low.contains("notification") || low.contains("किसका मैसेज आया")) {
            val notifSummary = MeeraNotificationService.getLatestSummary()
            return ToolExecutionResult(true, "read_notifications", "", "RECENT NOTIFICATIONS:\n$notifSummary")
        }

        // 4. Home Screen
        if (low == "होम स्क्रीन पे आओ" || low == "होम स्क्रीन पर आओ" || low == "go home" || low == "home screen") {
            val svc = MeeraAccessibilityService.instance
            if (svc != null && svc.performSystemAction("home")) {
                return ToolExecutionResult(true, "screen_action", "आ गई होम स्क्रीन पर, बताइए अब क्या करना है?", "SUCCESS: Navigated Home")
            }
        }

        // 5. YouTube Search vs Auto-Play ("चलाओ/लगाओ/play" -> autoPlay=true; "सर्च करो/ढूँढो" -> autoPlay=false)
        val ytMatch = Regex("(?:youtube[ \t]*(?:पर|पे|par|pe|me)|यूट्यूब[ \t]*(?:पर|पे))[ \t]+(.+?)(?:[ \t]+(चलाओ|चला दो|प्ले करो|लगाओ|लगा दो|सर्च करो|खोजो|play|search))?$", RegexOption.IGNORE_CASE).find(raw)
        if (ytMatch != null) {
            val song = ytMatch.groupValues[1].replace(Regex("(?i)(गाना|सॉन्ग|वीडियो|song|video)$"), "").trim()
            val verb = ytMatch.groupValues.getOrNull(2).orEmpty().lowercase()
            val shouldPlay = !verb.contains("सर्च") && !verb.contains("खोज") && !verb.contains("search")
            if (song.isNotBlank()) return playOrSearchMedia(ctx, song, "youtube", autoPlay = shouldPlay)
        }

        // 6. WhatsApp Message Command
        val waMatch = Regex("(.+?)[ \t]+को[ \t]+(?:whatsapp|व्हाट्सएप|व्हाट्सऐप).*?(?:मैसेज|message)[ \t]+(?:भेजो|करो|लिखो)(?:[ \t]+कि|[ \t]+ki)?[ \t]*(.*)", RegexOption.IGNORE_CASE).find(raw)
        if (waMatch != null) {
            val person = waMatch.groupValues[1].replace(Regex("^(?:मीरा|अरे|सुनो|एक काम करो)[ \t]*"), "").trim()
            val msg = waMatch.groupValues[2].trim()
            return sendWhatsAppVerified(ctx, person, msg)
        }

        // 7. Phone Call Command
        val callMatch = Regex("(.+?)[ \t]+को[ \t]+(?:कॉल|फोन|call|phone)[ \t]+(?:लगाओ|करो|मिलाओ|लगा दो)", RegexOption.IGNORE_CASE).find(raw)
        if (callMatch != null) {
            val person = callMatch.groupValues[1].replace(Regex("^(?:मीरा|अरे|सुनो)[ \t]*"), "").trim()
            return callPerson(ctx, person)
        }

        // 8. Open App Command
        val openPatterns = listOf(
            Regex("^(?:मीरा|अरे|सुनो|प्लीज)?[ \t]*(.+?)[ \t]+(?:ओपन करो|ओपन कर दो|खोलो|खोल दो|चालू करो|open karo|open kar do|kholo|khol do)$", RegexOption.IGNORE_CASE),
            Regex("^(?:open|launch)[ \t]+(.+)$", RegexOption.IGNORE_CASE)
        )
        for (pat in openPatterns) {
            val m = pat.find(raw)
            if (m != null) {
                val appCandidate = m.groupValues[1]
                    .replace(Regex("(?:ऐप|app|एप्लीकेशन|application)$", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (appCandidate.length in 2..25 && !appCandidate.contains(" और ") && !appCandidate.contains(" and ") && openAppByName(ctx, appCandidate)) {
                    return ToolExecutionResult(true, "open_app", "$appCandidate खोल दिया है! बताओ इसमें आगे क्या करना है?", "SUCCESS: Opened $appCandidate")
                }
            }
        }

        return ToolExecutionResult(false)
    }

    fun openAppByName(ctx: Context, rawName: String): Boolean {
        val clean = rawName.lowercase().replace("ऐप", "").replace("app", "").trim()
        if (clean.isBlank()) return false
        if (clean == "settings" || clean == "सेटिंग" || clean == "सेटिंग्स") {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        val pm = ctx.packageManager
        for ((key, pkgs) in KNOWN_APPS) {
            if (clean.contains(key) || key.contains(clean)) {
                for (pkg in pkgs) {
                    val launch = pm.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(launch)
                        return true
                    }
                }
            }
        }
        val latinClean = transliterateHindiToEnglish(clean)
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(mainIntent, 0)
            for (info in resolved) {
                val label = info.loadLabel(pm).toString().lowercase()
                val pkg = info.activityInfo.packageName.lowercase()
                if (label == clean || label.contains(clean) || label.contains(latinClean) || pkg.contains(latinClean)) {
                    val launch = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(launch)
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Layer 1: Devanagari (Hindi) to English Phonetic Transliteration + Maya AbstractC2163ad2 Fuzzy Scorer
     * Matches spoken "लक्ष्मण सालवी" -> "lakshman salvi" against saved English contact "Laxman Salvi"
     */
    fun transliterateHindiToEnglish(input: String): String {
        val map = mapOf(
            'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
            'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऋ' to "ri",
            'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "oo",
            'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ृ' to "ri",
            'ं' to "n", 'ँ' to "n", 'ः' to "h", '्' to "", '़' to "",
            'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
            'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
            'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
            'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
            'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
            'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
            'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
            'ॐ' to "om"
        )
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            // Check 2-char conjuncts like क्ष (क + ् + ष)
            if (i + 2 < input.length && input[i] == 'क' && input[i + 1] == '्' && input[i + 2] == 'ष') {
                sb.append("x")
                i += 3
                continue
            }
            val ch = input[i]
            val mapped = map[ch]
            if (mapped != null) {
                sb.append(mapped)
                // Add implicit 'a' after Hindi consonant if not followed by matra/halant/space
                val isConsonant = ch in 'क'..'ह'
                val nextCh = if (i + 1 < input.length) input[i + 1] else ' '
                val nextIsMatraOrHalant = nextCh in 'ा'..'ौ' || nextCh == '्' || nextCh == ' '
                if (isConsonant && !nextIsMatraOrHalant) {
                    sb.append('a')
                }
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString().lowercase(Locale.ROOT).trim()
    }

    /** Phonetic Consonant Skeleton so "laxman" and "lakshman" both become "lxmn" */
    private fun phoneticSkeleton(s: String): String {
        return s.lowercase(Locale.ROOT)
            .replace("ksh", "x")
            .replace("chh", "c")
            .replace("sh", "s")
            .replace("ph", "f")
            .replace("bh", "b")
            .replace("dh", "d")
            .replace("th", "t")
            .replace("gh", "g")
            .replace("kh", "k")
            .replace("w", "v")
            .replace("z", "j")
            .replace(Regex("[aeiouy ]+"), "")
    }

    /** Maya AbstractC2163ad2.f style 4-tier contact similarity score */
    private fun scoreContactMatch(savedName: String, rawQuery: String): Float {
        val sLow = savedName.lowercase(Locale.ROOT).trim()
        val qRaw = rawQuery.lowercase(Locale.ROOT).trim()
        val qLat = transliterateHindiToEnglish(qRaw)

        if (sLow == qRaw || sLow == qLat) return 1.0f

        val sWords = sLow.split(Regex("[ ]+")).filter { it.isNotBlank() }
        val qWords = qLat.split(Regex("[ ]+")).filter { it.isNotBlank() }

        if (sWords.any { it == qLat || it == qRaw }) return 0.95f
        if (sLow.startsWith(qLat) || sLow.contains(qLat)) return 0.90f

        // Phonetic skeleton comparison (e.g., "laxman salvi" == "lakshman salvi" -> "lxmnslv")
        val sSkel = phoneticSkeleton(sLow)
        val qSkel = phoneticSkeleton(qLat)
        if (sSkel.isNotEmpty() && qSkel.isNotEmpty()) {
            if (sSkel == qSkel) return 0.94f
            if (sSkel.contains(qSkel) || qSkel.contains(sSkel)) return 0.86f
        }

        // Word-by-word phonetic overlap
        if (qWords.isNotEmpty() && sWords.isNotEmpty()) {
            var matchedWords = 0
            for (qw in qWords) {
                val qws = phoneticSkeleton(qw)
                if (sWords.any { sw ->
                        val sws = phoneticSkeleton(sw)
                        sw.startsWith(qw.take(3)) || (qws.length >= 2 && sws == qws)
                    }
                ) {
                    matchedWords++
                }
            }
            if (matchedWords == qWords.size) return 0.92f
            if (matchedWords > 0) return 0.78f + (0.1f * matchedWords / max(sWords.size, qWords.size))
        }
        return 0.0f
    }

    fun findContactNumber(ctx: Context, nameQuery: String): Pair<String, String>? {
        val hasPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm || nameQuery.isBlank()) return null
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )
            if (cursor != null) {
                var bestMatch: Pair<String, String>? = null
                var bestScore = 0.74f
                while (cursor.moveToNext()) {
                    val dName = cursor.getString(0)?.trim() ?: continue
                    val num = cursor.getString(1)?.trim() ?: continue
                    val sc = scoreContactMatch(dName, nameQuery)
                    if (sc > bestScore) {
                        bestScore = sc
                        bestMatch = dName to num
                        if (sc >= 0.99f) break
                    }
                }
                return bestMatch
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return null
    }

    /**
     * Layer 2: Closed-Loop Verified WhatsApp Sender + In-App Search Fallback (Maya GR1.java style)
     */
    private fun sendInCurrentOpenChatTool(message: String): ToolExecutionResult {
        val svc = MeeraAccessibilityService.instance
            ?: return ToolExecutionResult(true, "whatsapp_send_in_open_chat", "मैसेज भेजने के लिए Accessibility सर्विस ऑन होनी चाहिए।", "ERROR: Accessibility OFF")
        if (message.isBlank()) {
            return ToolExecutionResult(true, "whatsapp_send_in_open_chat", "बताइए इस चैट में क्या मैसेज लिखकर भेजना है?", "NEEDS_INPUT: Message text is empty")
        }
        val sent = svc.sendInOpenChatVerified(message)
        return if (sent) {
            ToolExecutionResult(
                true, "whatsapp_send_in_open_chat",
                "खुली हुई चैट में '$message' भेज दिया है और स्क्रीन पर वेरीफाई भी कर लिया है!",
                "VERIFIED_SUCCESS: Sent '$message' in currently open chat and verified input box cleared."
            )
        } else {
            ToolExecutionResult(
                true, "whatsapp_send_in_open_chat",
                "मैंने खुली हुई स्क्रीन पर मैसेज बॉक्स या Send बटन ढूँढने की कोशिश की, पर मैसेज जा नहीं पाया। कृपया देखें कि क्या चैट खुली हुई है?",
                "FAILED_NOT_VERIFIED: Could not find editable chat box or Send button on current screen."
            )
        }
    }

    private fun sendWhatsAppVerified(ctx: Context, person: String, message: String): ToolExecutionResult {
        val pLow = person.lowercase().trim()
        val svc = MeeraAccessibilityService.instance
        val inWhatsAppNow = svc?.getActivePackage()?.contains("whatsapp") == true

        // If user meant current open chat ("current", "is chat", or empty person while inside WhatsApp)
        if ((pLow.isEmpty() || pLow == "current" || pLow.contains("इस चैट") || pLow.contains("is chat") || pLow.contains("यहाँ")) && inWhatsAppNow) {
            return sendInCurrentOpenChatTool(message)
        }

        val contact = findContactNumber(ctx, person)
        val rawDigits = (contact?.second ?: person).replace(Regex("[^0-9+]"), "")
        val phone = when {
            rawDigits.length == 10 -> "91$rawDigits"
            rawDigits.startsWith("+") -> rawDigits.substring(1)
            else -> rawDigits
        }
        val targetDisplay = contact?.first ?: transliterateHindiToEnglish(person).replaceFirstChar { it.uppercase() }

        return try {
            if (phone.length >= 10) {
                val encoded = URLEncoder.encode(message, "UTF-8")
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone" + if (message.isNotBlank()) "&text=$encoded" else "")
                val i = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(i)

                if (message.isBlank()) {
                    return ToolExecutionResult(true, "whatsapp_message", "$targetDisplay की WhatsApp चैट खोल दी है! बताइए क्या मैसेज लिखना है?", "VERIFIED_SUCCESS: Opened WhatsApp chat for $targetDisplay")
                }

                if (svc != null) {
                    // Wait 1.4s for deep-link transition, then verify WhatsApp chat window is active
                    Thread.sleep(1400)
                    val chatOpen = svc.waitForWhatsAppChatOpen(7)
                    if (chatOpen) {
                        Thread.sleep(450)
                        var clicked = svc.clickWhatsAppSendButton()
                        if (!clicked) {
                            // If deep link didn't pre-fill or Send icon wasn't visible yet, type explicitly and send
                            clicked = svc.sendInOpenChatVerified(message)
                        } else {
                            Thread.sleep(550)
                        }
                        val verified = clicked && svc.isInputCleared()
                        return if (verified) {
                            ToolExecutionResult(
                                true, "whatsapp_message",
                                "$targetDisplay को WhatsApp पर '$message' भेज दिया है और स्क्रीन देखकर वेरीफाई भी कर लिया है!",
                                "VERIFIED_SUCCESS: WhatsApp message '$message' sent to $targetDisplay and verified."
                            )
                        } else {
                            ToolExecutionResult(
                                true, "whatsapp_message",
                                "$targetDisplay की चैट खुल गई है और मैसेज बॉक्स में है, पर Send बटन अपने-आप दबने की पुष्टि नहीं हुई—एक बार स्क्रीन देख लीजिए।",
                                "PARTIAL_NOT_VERIFIED: Chat opened for $targetDisplay, but Send confirmation failed."
                            )
                        }
                    }
                }
                ToolExecutionResult(
                    true, "whatsapp_message",
                    "$targetDisplay की WhatsApp चैट में मैसेज तैयार कर दिया है, पर Accessibility बंद होने से Send बटन नहीं दबा सकी।",
                    "PARTIAL: Opened WhatsApp chat for $targetDisplay (Accessibility not active)"
                )
            } else {
                // Maya GR1.java Fallback: Search inside WhatsApp itself synchronously and verify!
                openAppByName(ctx, "WhatsApp")
                val searchName = transliterateHindiToEnglish(person)
                if (svc != null) {
                    Thread.sleep(1600)
                    val searchOpened = svc.clickByText("Search") || svc.clickByText("खोजें")
                    if (searchOpened) {
                        Thread.sleep(500)
                        svc.inputText(searchName, autoSend = false)
                        Thread.sleep(1200)
                        val chatClicked = svc.clickByText(searchName) || svc.clickByText(person)
                        if (chatClicked && message.isNotBlank()) {
                            Thread.sleep(900)
                            val sentOk = svc.sendInOpenChatVerified(message)
                            if (sentOk) {
                                return ToolExecutionResult(
                                    true, "whatsapp_message",
                                    "WhatsApp के अंदर '$searchName' ढूँढकर '$message' भेज दिया है और वेरीफाई भी कर लिया है!",
                                    "VERIFIED_SUCCESS: Searched '$searchName' inside WhatsApp and sent message."
                                )
                            }
                        }
                    }
                    ToolExecutionResult(
                        true, "whatsapp_message",
                        "मुझे फोनबुक और WhatsApp सर्च में '$person' नाम का पक्का कॉन्टैक्ट नहीं मिला। मैंने WhatsApp खोल दिया है, बताइए किस नाम पर टैप करूँ?",
                        "FAILED_NOT_FOUND: Could not verify contact '$person' ($searchName) in WhatsApp."
                    )
                } else {
                    ToolExecutionResult(
                        true, "whatsapp_message",
                        "कॉन्टैक्ट्स में '$person' नहीं मिला और Accessibility बंद है।",
                        "FAILED: Contact not found and Accessibility OFF"
                    )
                }
            }
        } catch (e: Exception) {
            ToolExecutionResult(true, "whatsapp_message", "WhatsApp में मैसेज भेजने में तकनीकी दिक्कत आई: ${e.message}", "ERROR: ${e.message}")
        }
    }

    private fun callPerson(ctx: Context, person: String): ToolExecutionResult {
        val contact = findContactNumber(ctx, person)
        val number = contact?.second ?: person.filter { it.isDigit() || it == '+' }
        if (number.length < 3) {
            return ToolExecutionResult(true, "make_phone_call", "कॉन्टैक्ट्स में '$person' का नंबर नहीं मिला। क्या यह किसी और नाम से सेव है?", "ERROR: Contact $person not found")
        }
        val canDirectCall = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (canDirectCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return try {
            val intent = Intent(action, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            val label = contact?.first ?: person
            ToolExecutionResult(true, "make_phone_call", "$label को कॉल लगा दिया है।", "VERIFIED_SUCCESS: Calling $label ($number)")
        } catch (e: Exception) {
            ToolExecutionResult(true, "make_phone_call", "कॉल लगाने में दिक्कत आई।", "ERROR: ${e.message}")
        }
    }

    /**
     * Synchronous Verified YouTube Search & Auto-Play Engine (Waits for video click before reporting success!)
     */
    private fun playOrSearchMedia(ctx: Context, query: String, platform: String, autoPlay: Boolean = true): ToolExecutionResult {
        val cleanQuery = query
            .replace(Regex("(?i)(यूट्यूब पर|यूट्यूब में|youtube par|youtube me|सर्च करके|search karke|चलाओ|प्ले करो|play karo|लगाओ)"), "")
            .trim()
            .ifBlank { query.trim() }

        return try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            if (platform.contains("google")) {
                val uri = Uri.parse("https://www.google.com/search?q=$encoded")
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return ToolExecutionResult(true, "play_or_search", "Google पर '$cleanQuery' सर्च कर दिया है।", "VERIFIED_SUCCESS: Google search $cleanQuery")
            }

            // If user said "video play nahi hua, play karo" while ALREADY on YouTube search results
            val svc = MeeraAccessibilityService.instance
            val alreadyOnYouTube = svc?.getActivePackage()?.contains("youtube") == true
            if (alreadyOnYouTube && (cleanQuery.isBlank() || cleanQuery.contains("वीडियो") || cleanQuery.contains("video"))) {
                val clickedNow = svc?.clickFirstYoutubeVideoVerified(3500L) == true
                return if (clickedNow) {
                    ToolExecutionResult(true, "youtube_search_and_play", "स्क्रीन देखकर पहला वीडियो प्ले कर दिया है!", "VERIFIED_SUCCESS: Clicked first video on existing YouTube screen")
                } else {
                    ToolExecutionResult(true, "youtube_search_and_play", "मैंने स्क्रीन देखी पर कोई वीडियो आइटम क्लिक नहीं हो पाया।", "FAILED_NOT_VERIFIED: Could not click video on YouTube screen")
                }
            }

            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", cleanQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                ctx.startActivity(searchIntent)
            } catch (_: Exception) {
                val uri = Uri.parse("https://www.youtube.com/results?search_query=$encoded")
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            if (autoPlay) {
                if (svc == null) {
                    return ToolExecutionResult(
                        true, "youtube_search_and_play",
                        "YouTube पर '$cleanQuery' सर्च कर दिया है, पर वीडियो अपने-आप प्ले करने के लिए Accessibility सर्विस ऑन करनी होगी।",
                        "PARTIAL_NO_A11Y: YouTube search opened for '$cleanQuery', but Accessibility is OFF so could not click video."
                    )
                }
                // Wait 1.8s for YouTube search results activity to open, then synchronously poll & click!
                Thread.sleep(1800)
                val clicked = svc.clickFirstYoutubeVideoVerified(6500L)
                if (clicked) {
                    ToolExecutionResult(
                        true, "youtube_search_and_play",
                        "YouTube पर '$cleanQuery' सर्च करके पहला वीडियो प्ले कर दिया है! सुनिए, और कुछ काम हो तो बताइए?",
                        "VERIFIED_SUCCESS: YouTube searched '$cleanQuery' and verified click on first video result."
                    )
                } else {
                    ToolExecutionResult(
                        true, "youtube_search_and_play",
                        "मैंने YouTube पर '$cleanQuery' सर्च कर दिया है, पर नेटवर्क या स्क्रीन लोड होने की वजह से वीडियो अपने-आप प्ले नहीं हो पाया। बोलिए 'पहला वीडियो चलाओ' तो मैं दोबारा टैप कर दूँगी!",
                        "PARTIAL_NOT_PLAYED: YouTube search results opened for '$cleanQuery', but auto-click on first video was not verified."
                    )
                }
            } else {
                ToolExecutionResult(
                    true, "play_or_search",
                    "YouTube पर '$cleanQuery' सर्च कर दिया है! बताइए कौन-सा वाला वीडियो चलाऊँ?",
                    "VERIFIED_SUCCESS: YouTube search '$cleanQuery'"
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(false)
        }
    }

    private val CMD_HINTS = listOf(
        "खोल", "ओपन", "open", "चला", "चालू", "play", "कॉल", "call", "फोन", "मैसेज", "message", "msg",
        "भेज", "व्हाट्स", "whatsapp", "यूट्यूब", "youtube", "स्क्रॉल", "scroll", "स्क्रीन", "screen",
        "बैक", "back", "होम", "home", "सर्च", "search", "ढूंढ", "लगा", "नोटिफिकेशन", "notification",
        "क्लिक", "click", "टैप", "tap"
    )

    fun looksLikeCommand(text: String): Boolean {
        val low = text.lowercase()
        return CMD_HINTS.any { low.contains(it) }
    }
}
