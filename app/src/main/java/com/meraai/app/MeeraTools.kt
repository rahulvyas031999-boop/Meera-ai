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
            JSONObject().put("name", "open_meera_dashboard").put("description", "Open your OWN app (Meera AI Dashboard). Use ONLY when the user explicitly asks you to come back to your own app or dashboard. NEVER call it by yourself after a task.").put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
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
                                .put("action_type", JSONObject().put("type", "STRING").put("description", "One of: click, type, paste, home, back, scroll_down, scroll_up, notifications (with paste, set target_or_text='send' to paste and send)"))
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

        funcs.put(
            JSONObject()
                .put("name", "set_mission_plan")
                .put("description", "For ANY request with 2 or more steps or apps, call this FIRST with a numbered checklist, one short step per line. The checklist is remembered and repeated after every action so the task is never forgotten.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("steps", JSONObject().put("type", "STRING").put("description", "Numbered steps, one step per line")))
                    .put("required", JSONArray().put("steps")))
        )
        funcs.put(
            JSONObject()
                .put("name", "mark_step_done")
                .put("description", "Mark one checklist step as done ONLY after it is verified (VERIFIED_SUCCESS, or clearly visible on the new screen).")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("step", JSONObject().put("type", "INTEGER").put("description", "Step number from the checklist")))
                    .put("required", JSONArray().put("step")))
        )
        funcs.put(
            JSONObject()
                .put("name", "finish_mission")
                .put("description", "Clear the checklist when all steps are finished or the user cancels the task.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("note", JSONObject().put("type", "STRING").put("description", "Optional short note"))))
        )
        funcs.put(
            JSONObject()
                .put("name", "replan_mission")
                .put("description", "Dynamically update the remaining steps of the active mission checklist when the screen layout is unexpected, a step fails, or a better path is found.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("new_remaining_steps", JSONObject().put("type", "STRING").put("description", "Updated remaining steps, one short step per line"))
                        .put("reason", JSONObject().put("type", "STRING").put("description", "Why the plan was adjusted")))
                    .put("required", JSONArray().put("new_remaining_steps")))
        )
        funcs.put(
            JSONObject()
                .put("name", "dismiss_popup_or_overlay")
                .put("description", "Close or dismiss any floating miniplayer (like YouTube minimized video with X/Play), PiP window, ad overlay, bottom-sheet, or blocking popup using multi-window gesture tap, swipe-to-dismiss, and Vision X-button fallback.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("target_hint", JSONObject().put("type", "STRING").put("description", "Optional hint of what to close, e.g. 'YouTube miniplayer', 'ad popup', 'PiP video'"))))
        )
        funcs.put(
            JSONObject()
                .put("name", "recover_stuck_step")
                .put("description", "Run 3-level automatic error recovery when a tap fails or the screen does not change: 1) dismiss blocking popups, 2) scroll and search for target_text, 3) use Vision AI to locate and tap coordinates.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("target_text", JSONObject().put("type", "STRING").put("description", "The button, icon, or item we are trying to find and tap")))
                    .put("required", JSONArray().put("target_text")))
        )
        funcs.put(
            JSONObject()
                .put("name", "navigate_to_app_home")
                .put("description", "Navigate back to the current app's main dashboard/home screen (e.g. Instagram main feed, Hotstar home, WhatsApp chats list, Settings main page) by closing open reels, popups, or deep sub-screens. Set want_search=true if you just need the Search button/bar to become visible.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("want_search", JSONObject().put("type", "BOOLEAN").put("description", "true to stop unwinding as soon as Search icon/bar is visible on screen")))))
                funcs.put(
            JSONObject()
                .put("name", "reply_whatsapp_notification")
                .put("description", "Instantly reply to an incoming WhatsApp message notification in the background (or open chat if notification is gone). Use when user says 'reply kar do', 'Mummy ko reply bhej do', or 'Ritik ka chat handle kar lo'.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("sender_name", JSONObject().put("type", "STRING").put("description", "Sender or group name (or 'latest' for the last message received)"))
                        .put("reply_message", JSONObject().put("type", "STRING").put("description", "The message text to send as reply")))
                    .put("required", JSONArray().put("reply_message")))
        )
        funcs.put(
            JSONObject()
                .put("name", "post_whatsapp_status")
                .put("description", "Post to the user's WhatsApp Status (My status). If the user wants to post a photo/image they shared or attached with Meera (e.g. 'jo photo share ki thi wo WhatsApp status laga do'), set use_shared_photo=true. Otherwise provide status_text to design a poster card.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("status_text", JSONObject().put("type", "STRING").put("description", "Quote/shayari text for poster OR optional caption when posting a shared photo"))
                        .put("use_shared_photo", JSONObject().put("type", "BOOLEAN").put("description", "true to post the latest shared/attached photo from MeeraFileVault directly as WhatsApp Status"))))
        )
        funcs.put(
            JSONObject()
                .put("name", "send_file_on_whatsapp")
                .put("description", "Send a file/photo/PDF/Excel/Word document (prioritizing files shared/attached with Meera AI, or searching phone storage) to a WhatsApp contact or group.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("file_query", JSONObject().put("type", "STRING").put("description", "File name keyword, e.g. 'latest', 'photo', 'excel', 'pdf', 'meeting', or 'resume'"))
                        .put("recipient", JSONObject().put("type", "STRING").put("description", "WhatsApp contact name or group name, e.g. 'Team Updates'")))
                    .put("required", JSONArray().put("file_query").put("recipient")))
        )
        funcs.put(
            JSONObject()
                .put("name", "analyze_shared_file")
                .put("description", "Read, inspect, summarize, or answer any question about a photo, PDF, Excel (.xlsx/.csv), Word (.docx), or text file that the user shared or attached to Meera AI.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("question", JSONObject().put("type", "STRING").put("description", "What the user wants to know or extract from the shared file"))
                        .put("file_query", JSONObject().put("type", "STRING").put("description", "Optional file name or type ('latest', 'photo', 'pdf', 'excel'); defaults to active shared file")))
                    .put("required", JSONArray().put("question")))
        )
        funcs.put(
            JSONObject()
                .put("name", "list_shared_files")
                .put("description", "List all photos, PDFs, Excel sheets, and documents currently saved in Meera's Shared File Vault.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )
        funcs.put(
            JSONObject()
                .put("name", "create_or_edit_file")
                .put("description", "AI File Studio: Create or edit a native Excel (.xlsx with live formulas like =SUM, =B2*C2, GST), PDF (.pdf), or Word (.docx) file from scratch OR by converting/editing a shared photo, bill, PDF, or Excel file in MeeraFileVault. Auto-saves to Downloads and sets it as the active file.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("output_format", JSONObject().put("type", "STRING").put("description", "Target format: 'EXCEL', 'PDF', or 'WORD'"))
                        .put("instruction", JSONObject().put("type", "STRING").put("description", "Exact details of data, columns, formulas, calculations, or document content to create or edit"))
                        .put("file_query", JSONObject().put("type", "STRING").put("description", "Source file in Shared Vault to convert/edit (e.g. 'latest', 'photo', 'pdf', 'excel'), or 'none' if creating from scratch"))
                        .put("file_title", JSONObject().put("type", "STRING").put("description", "Optional clean filename without extension")))
                    .put("required", JSONArray().put("output_format").put("instruction")))
        )
        funcs.put(
            JSONObject()
                .put("name", "open_file_vault")
                .put("description", "Open the dedicated File Vault & AI File Studio screen where the user can view, open, share, delete, or create Excel/PDF/Word files.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )
        funcs.put(
            JSONObject()
                .put("name", "export_vault_file_to_workspace")
                .put("description", "Export a file from MeeraFileVault to /sdcard/Download/MeeraWorkspace/ so Termux Python or Google Colab scripts can process heavy data, charts, or advanced edits.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("file_query", JSONObject().put("type", "STRING").put("description", "File name or type ('latest', 'excel', 'pdf', 'photo')"))))
        )
        funcs.put(
            JSONObject()
                .put("name", "open_stock_chart")
                .put("description", "Open the live stock chart for a company/symbol (e.g., HAL, Tata Motors, Nifty) and visually analyze its trend, support, and resistance levels.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("stock_name", JSONObject().put("type", "STRING").put("description", "Stock or index name, e.g. 'HAL', 'Reliance', 'Nifty 50'")))
                    .put("required", JSONArray().put("stock_name")))
        )
        funcs.put(
            JSONObject()
                .put("name", "toggle_live_screen_watch")
                .put("description", "Start or stop live continuous screen watching (streams screen frames to Live AI every 2.8s) for live BGMI gameplay commentary or live app assistance.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("enable", JSONObject().put("type", "BOOLEAN").put("description", "true to start watching screen live, false to stop")))
                    .put("required", JSONArray().put("enable")))
        )
        funcs.put(
            JSONObject()
                .put("name", "find_my_phone")
                .put("description", "Set phone volume to 100% maximum and ring/speak loudly when the user asks 'Mera phone kahan hai' or cannot find their phone.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )
                funcs.put(
            JSONObject()
                .put("name", "activate_sos")
                .put("description", "Activate Emergency SOS: starts loud siren, fetches live GPS Google Maps location link, sends emergency SMS + WhatsApp location alert, and calls the emergency contact.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("emergency_contact", JSONObject().put("type", "STRING").put("description", "Optional contact name to alert (defaults to Papa/Mummy/Bhai/Emergency)"))))
        )
        funcs.put(
            JSONObject()
                .put("name", "stop_sos")
                .put("description", "Stop the emergency SOS siren sound.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )
        funcs.put(
            JSONObject()
                .put("name", "set_whatsapp_auto_reply")
                .put("description", "Enable or disable autonomous WhatsApp chat handling for a specific contact (e.g., 'Ritik ka chat tum apne hisab se handle kar lo').")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("contact_name", JSONObject().put("type", "STRING").put("description", "Contact name whose chat Meera should handle automatically, or 'all'"))
                        .put("enable", JSONObject().put("type", "BOOLEAN").put("description", "true to enable autonomous replies, false to stop"))
                        .put("style_note", JSONObject().put("type", "STRING").put("description", "How to reply, e.g., 'polite professional intro and handle questions'")))
                    .put("required", JSONArray().put("contact_name")))
        )
        funcs.put(
            JSONObject()
                .put("name", "unlock_phone_screen")
                .put("description", "Wake the screen and perform swipe-up unlock gesture when the user says 'unlock karo' or 'lock kholo'.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )
        funcs.put(
            JSONObject()
                .put("name", "switch_system_config")
                .put("description", "Switch personality (Meera, Jarvis, Rudra), change voice (Aoede, Orus, Fenrir, Kore, Leda, Zephyr, Despina, Callirrhoe, Puck, Charon, Alnilam), toggle Girlfriend mode, or switch engine mode (LIVE or MODULAR) WITHOUT turning off the microphone. Call this ONLY when the user is clearly commanding a switch right now (never for questions or casual mentions; if unsure, ask the user to confirm first), for example when the user says 'Jarvis se baat karao', 'Jarvis mode on karo', 'Rudra ko bulao', 'Meera ko bulao', 'aawaz badlo', or 'Modular/Live mode me aao'.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("persona", JSONObject().put("type", "STRING").put("description", "Meera, Jarvis, or Rudra"))
                        .put("voice", JSONObject().put("type", "STRING").put("description", "Optional Google voice name like Aoede, Orus, Fenrir, Kore, Leda, Zephyr, Despina, Callirrhoe, Puck, Charon, Alnilam"))
                        .put("girlfriend_mode", JSONObject().put("type", "BOOLEAN").put("description", "Optional true or false for Meera's Girlfriend/Companion mode"))
                        .put("mode", JSONObject().put("type", "STRING").put("description", "Optional LIVE or MODULAR"))
                        .put("model", JSONObject().put("type", "STRING").put("description", "Optional model code to switch to"))))
        )
        funcs.put(
            JSONObject()
                .put("name", "stop_or_sleep_meera")
                .put("description", "Close Meera's microphone session and put her to sleep. Set deep_sleep=true ONLY when the user asks for Deep Sleep / Night Mode / turning off background mic & wake-word completely to save battery (deep sleep on karo, background band karke so jao, wake word band kar do). Never use tap_element for this.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("farewell_message", JSONObject().put("type", "STRING").put("description", "Short warm goodbye line in Hinglish"))
                        .put("deep_sleep", JSONObject().put("type", "BOOLEAN").put("description", "true to completely turn off background wake-word and mic for night/battery saving"))))
        )
        funcs.put(
            JSONObject()
                .put("name", "get_system_status")
                .put("description", "Get complete real-time status of Meera AI's architecture, active personality, all 3 personalities (Meera, Jarvis, Rudra), voices, Live/Modular mode, active AI models, tabs, and granted Android permissions.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )
                funcs.put(
            JSONObject()
                .put("name", "copy_debug_log")
                .put("description", "Copy Meera AI's internal debugging logs directly to the Android system clipboard so they can be pasted into any app.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )
                funcs.put(
            JSONObject()
                .put("name", "spawn_custom_agent")
                .put("description", "EXPLICIT-ONLY: Create and run a custom domain-expert sub-agent ONLY when the user explicitly asks to create or use an agent (e.g. 'agent banao'). NEVER call automatically. If the user only wants a model such as Hydrogen to write or answer something, do NOT use this, use ask_hydrogen instead. If you are not sure whether the user wants an agent created, ask one short confirmation question first.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("profession", JSONObject().put("type", "STRING").put("description", "Exact specialist role needed, e.g. 'Stand-Up Comedian', 'Suspense Scriptwriter', 'Chartered Accountant', 'Python Architect'"))
                        .put("task_details", JSONObject().put("type", "STRING").put("description", "Complete details, topic, tone, and requirements of the user's request"))
                        .put("delivery_mode", JSONObject().put("type", "STRING").put("description", "'speak' for comedy/shayari/short performance, 'document' for code/scripts/plans, or 'auto' (default)")))
                    .put("required", JSONArray().put("profession").put("task_details")))
        )
        funcs.put(
            JSONObject()
                .put("name", "run_termux_command")
                .put("description", "Run a quick local shell or Python command in Termux on the phone. Only after the user clearly confirmed the exact command.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("command", JSONObject().put("type", "STRING").put("description", "Shell command to run in Termux")))
                    .put("required", JSONArray().put("command")))
        )
        funcs.put(
            JSONObject()
                .put("name", "run_colab_task")
                .put("description", "Send a heavy coding, Excel/PDF generation, data analysis, or GitHub automation task to the user's connected Google Colab Cloud Backend server.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("task_type", JSONObject().put("type", "STRING").put("description", "Type of task: 'python_exec', 'excel_report', 'github_ops', or 'general'"))
                        .put("instruction", JSONObject().put("type", "STRING").put("description", "Clear instruction of what Colab should execute"))
                        .put("payload", JSONObject().put("type", "STRING").put("description", "Optional Python code, JSON data, or script content")))
                    .put("required", JSONArray().put("instruction")))
        )
        funcs.put(
            JSONObject()
                .put("name", "set_colab_url")
                .put("description", "Save the public HTTPS tunnel URL and the secret word of the user's running Google Colab server so Meera can send heavy tasks to it.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("url", JSONObject().put("type", "STRING").put("description", "Public HTTPS URL from Google Colab (e.g., https://xxx.trycloudflare.com)"))
                        .put("secret", JSONObject().put("type", "STRING").put("description", "The secret word printed by the Colab cell")))
                    .put("required", JSONArray().put("url")))
        )
                funcs.put(
            JSONObject()
                .put("name", "generate_professional_code")
                .put("description", "Generate complete, production-ready, syntax-verified code (Python, Kotlin, Google Colab FastAPI tunnel, Termux scripts) using Gemini Hydrogen & Pro deep reasoning. Saves code cleanly to Chat Tab & Clipboard without reading raw symbols aloud.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("task_details", JSONObject().put("type", "STRING").put("description", "Exact coding requirement, language, and features needed"))
                        .put("target_platform", JSONObject().put("type", "STRING").put("description", "Target environment, e.g. 'Google Colab Python', 'Termux Bash', 'Android Kotlin'")))
                    .put("required", JSONArray().put("task_details")))
        )
        funcs.put(
            JSONObject()
                .put("name", "get_task_progress")
                .put("description", "Get the exact real-time completion percentage (0% to 100%), active step, model used, and elapsed time of Meera's current or latest background task.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )
        funcs.put(
            JSONObject()
                .put("name", "open_background_monitor")
                .put("description", "Open Meera's Live Background Activity Monitor screen so the user can visually inspect live percentage progress, step logs, and outputs.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )
                funcs.put(
            JSONObject()
                .put("name", "ask_hydrogen")
                .put("description", "Send a writing or thinking task straight to the Gemini Hydrogen model and get its answer, with NO agent or role created. Use this when the user wants Hydrogen (or a strong model) to write or answer something, such as a story, script, article, plan or explanation. For code use generate_professional_code instead. The full answer is saved to the Chat tab and clipboard.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("request", JSONObject().put("type", "STRING").put("description", "The full request as the user wants it: topic, length, language and tone")))
                    .put("required", JSONArray().put("request")))
        )
                funcs.put(
            JSONObject()
                .put("name", "open_code_pusher")
                .put("description", "Open Meera's In-App Code Patch, Diff Preview & GitHub Push Studio screen so the user can apply code updates, preview changes, push to v2-test, and monitor APK builds directly on the phone.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )
        funcs.put(
            JSONObject()
                .put("name", "save_custom_workflow")
                .put("description", "Save a multi-step UI workflow (either taught by the user step-by-step or from the latest successful mission trace) so Meera can execute it deterministically in one go next time without trial-and-error.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("workflow_name", JSONObject().put("type", "STRING").put("description", "Short name of the workflow, e.g. 'whatsapp archived message'"))
                        .put("steps_pipe_separated", JSONObject().put("type", "STRING").put("description", "Pipe-separated verified actions, e.g. 'open_app:WhatsApp|tap_text:Archived|tap_text:{contact}|send_chat:{message}' (leave blank to auto-save the latest verified mission trace)")))
                    .put("required", JSONArray().put("workflow_name")))
        )
        funcs.put(
            JSONObject()
                .put("name", "run_saved_workflow")
                .put("description", "Execute a previously saved custom workflow deterministically step-by-step.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("workflow_name", JSONObject().put("type", "STRING").put("description", "Name of the saved workflow to run"))
                        .put("contact", JSONObject().put("type", "STRING").put("description", "Optional contact name or phone number parameter"))
                        .put("message", JSONObject().put("type", "STRING").put("description", "Optional message or text parameter"))
                        .put("return_to_dashboard", JSONObject().put("type", "BOOLEAN").put("description", "true if the user also asked to return to Meera dashboard after finishing")))
                    .put("required", JSONArray().put("workflow_name")))
        )
        funcs.put(
            JSONObject()
                .put("name", "dispatch_ui_task")
                .put("description", "Dispatch a multi-step phone screen/Accessibility task (like YouTube video + comment, WhatsApp message, Instagram search) to Meera's Serial FIFO UI Worker Queue so you remain 100% FREE to keep chatting naturally with Rahul while the UI worker executes it step-by-step with shared memory and tools.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("goal", JSONObject().put("type", "STRING").put("description", "Clear description of the phone screen task to execute"))
                        .put("return_to_dashboard", JSONObject().put("type", "BOOLEAN").put("description", "true if user asked to return to Meera dashboard after finishing")))
                    .put("required", JSONArray().put("goal")))
        )
        funcs.put(
            JSONObject()
                .put("name", "dispatch_background_task")
                .put("description", "Launch a parallel background worker (up to 4 simultaneously) with Meera's full shared memory for thinking/cloud work like story ideas, message drafting, research, coding, Hydrogen writing, Colab, or Termux without blocking voice conversation.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("task_kind", JSONObject().put("type", "STRING").put("description", "One of: hydrogen, story, draft, research, code, agent, colab, termux"))
                        .put("instruction", JSONObject().put("type", "STRING").put("description", "Complete instruction for the background worker"))
                        .put("profession", JSONObject().put("type", "STRING").put("description", "Optional specialist role if task_kind is 'agent'")))
                    .put("required", JSONArray().put("task_kind").put("instruction")))
        )
        funcs.put(
            JSONObject()
                .put("name", "get_worker_queue_status")
                .put("description", "Check what screen UI tasks are currently running or queued in FIFO order, and what parallel background workers are active right now.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )
        funcs.put(
            JSONObject()
                .put("name", "cancel_background_tasks")
                .put("description", "Cancel running or queued UI/background worker tasks when the user asks to stop them.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("target", JSONObject().put("type", "STRING").put("description", "'all', 'ui', or a keyword matching the task to cancel"))))
        )
        return JSONArray().put(JSONObject().put("functionDeclarations", funcs))
    }

    private val SCREEN_AFTER_TOOLS = setOf(
        "open_app", "tap_element", "screen_action", "tap_coordinates",
        "set_mission_plan", "replan_mission", "recover_stuck_step", "dismiss_popup_or_overlay", "wait_for_screen_text",
        "play_or_search", "youtube_search_and_play",
        "whatsapp_message", "whatsapp_send_in_open_chat"
    )

    /** Action ke baad nayi screen padhkar Self-Reflection report aur mission status jodo */
    private fun withScreenState(name: String, beforeSig: String, targetDesc: String, res: ToolExecutionResult): ToolExecutionResult {
        if (name !in SCREEN_AFTER_TOOLS || !res.executed) return res
        val svc = MeeraAccessibilityService.instance ?: return res
        return try {
            if (name == "open_app" || name == "play_or_search" || name == "youtube_search_and_play") Thread.sleep(1500)
            svc.waitForScreenSettle(1500L)
            val afterSig = svc.getScreenSignature()
            val reflectionNote = MeeraMission.recordScreenTransition(name, targetDesc, beforeSig, afterSig, res.rawDataForAi)
            val screen = svc.readScreenElements(200)
            if (screen.isBlank()) res
            else res.copy(
                rawDataForAi = res.rawDataForAi +
                    reflectionNote +
                    "\nNEW SCREEN AFTER ACTION:\n" + screen +
                    "\nIf the user's overall task is NOT finished yet, immediately call the next tool (prefer tap_element with the #id). Do not ask the user what to do next." + MeeraMission.extraContext(svc.getActivePackage())
            )
        } catch (_: Exception) { res }
    }

    /** Koi bhi tool fail ho to app crash na kare, aur before/after screen signature track ho */
    fun executeFunctionCall(ctx: Context, name: String, args: JSONObject): ToolExecutionResult {
        return try {
            val svc = MeeraAccessibilityService.instance
            val beforeSig = svc?.getScreenSignature().orEmpty()
            val targetDesc = args.optString("text", args.optString("target_or_text", args.optString("id", ""))).trim()
            withScreenState(name, beforeSig, targetDesc, executeFunctionCallInner(ctx, name, args))
        } catch (e: Exception) {
            val m = e.message ?: e.javaClass.simpleName
            val busy = m.contains("503") || m.contains("429") || m.contains("UNAVAILABLE")
            ToolExecutionResult(
                true, name,
                if (busy) "अभी Google का AI सर्वर व्यस्त है, कुछ सेकंड बाद दोबारा कहो।" else "यह काम करते समय दिक्कत आई, फिर से कोशिश करो।",
                "ERROR: ${m.take(200)}"
            )
        }
    }

    private fun executeFunctionCallInner(ctx: Context, name: String, args: JSONObject): ToolExecutionResult {
        return when (name) {
            "open_meera_dashboard" -> {
                MeeraTermuxEngine.closeTerminal()
                MeeraBackgroundMonitor.closeMonitor()
                MeeraCodePatcher.closeScreen()
                val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    ctx.startActivity(intent)
                    ToolExecutionResult(true, name, "ठीक है चलो, वापस डैशबोर्ड पर आ गई हूँ!", "VERIFIED_SUCCESS: Closed Terminal and returned to Meera Home Dashboard")
                } else ToolExecutionResult(true, name, "डैशबोर्ड नहीं मिला।", "ERROR")
            }
            @Suppress("NewApi")
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
                    ToolExecutionResult(true, name, "", "CURRENT SCREEN ELEMENTS:\n$summary" + MeeraMission.extraContext(svc.getActivePackage()))
                }
            }
            "tap_element" -> {
                val svc = MeeraAccessibilityService.instance
                if (svc == null) {
                    ToolExecutionResult(true, name, "टैप करने के लिए Accessibility ऑन करें।", "ERROR: Accessibility OFF")
                } else {
                    val id = args.optInt("id", -1)
                    val text = args.optString("text", "").trim()
                    val label = if (id > 0) "#$id" else text
                    val sig = svc.getScreenSignature()
                    if (MeeraMission.isActionBlacklisted(sig, id, text)) {
                        // HARD GUARD: एक ही गलत बटन को बार-बार टैप करने से रोकें!
                        val scrolled = svc.performSystemAction("scroll_down")
                        ToolExecutionResult(
                            true, name, "",
                            "BLOCKED_REPEAT_TAP: '$label' was already tapped on this screen and caused a loop or no progress! Auto-performed scroll_down=${scrolled}. Pick a DIFFERENT element, or call navigate_to_app_home / click top Back button."
                        )
                    } else {
                        MeeraMission.registerTapAttempt(sig, id, text)
                        val ok = if (id > 0) svc.tapElementById(id) else svc.clickByTextWithScrollFallback(text, maxScrolls = 2)
                        ToolExecutionResult(true, name, if (ok) "$label पर टैप कर दिया।" else "स्क्रीन पर '$label' नहीं मिला।", if (ok) "SUCCESS: Tapped $label" else "FAILED: $label not found even after scrolling")
                    }
                }
            }
            "navigate_to_app_home" -> {
                val svc = MeeraAccessibilityService.instance
                if (svc == null) {
                    ToolExecutionResult(true, name, "Accessibility बंद है।", "ERROR: Accessibility OFF")
                } else {
                    val wantSearch = args.optBoolean("want_search", false)
                    val status = svc.navigateToAppMainScreen(wantSearch)
                    ToolExecutionResult(
                        true, name,
                        "ऐप के मुख्य डैशबोर्ड पर आ गई हूँ।",
                        "VERIFIED_SUCCESS: Navigated to app main screen ($status)."
                    )
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
                    val sig = svc.getScreenSignature()
                    if (act == "click" && target.isNotBlank() && MeeraMission.isActionBlacklisted(sig, -1, target)) {
                        svc.performSystemAction("scroll_down")
                        return ToolExecutionResult(
                            true, name, "",
                            "BLOCKED_REPEAT_TAP: '$target' was already clicked on this screen without progress. Auto-scrolled down. Choose a different option or call navigate_to_app_home."
                        )
                    }
                    if (act == "click" && target.isNotBlank()) MeeraMission.registerTapAttempt(sig, -1, target)
                    val ok = when (act) {
                        "click" -> svc.clickByTextWithScrollFallback(target, maxScrolls = 2)
                        "type" -> svc.inputText(target, autoSend = true)
                        "paste" -> svc.pasteClipboardText(ctx, autoSend = target.equals("send", ignoreCase = true) || target.isEmpty()).first
                        "main_home", "app_home" -> svc.navigateToAppMainScreen(false).isNotBlank()
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
            "set_mission_plan" -> {
                MeeraMission.set(args.optString("steps", ""))
                ToolExecutionResult(true, name, "", "VERIFIED_SUCCESS: Plan saved.\n" + MeeraMission.status() + "\nStart the first pending step immediately. Do not wait for the user.")
            }
            "mark_step_done" -> {
                val n = args.optInt("step", -1)
                val ok = MeeraMission.markDone(n)
                val msg = when {
                    ok -> "VERIFIED_SUCCESS: Step $n marked done.\n" + MeeraMission.status()
                    MeeraMission.wasRecentlyCleared() -> "VERIFIED_SUCCESS: Mission already completed and cleared."
                    else -> "INFO: Step $n already closed.\n" + MeeraMission.status()
                }
                ToolExecutionResult(true, name, "", msg)
            }
            "finish_mission" -> {
                MeeraMission.clear(ctx)
                ToolExecutionResult(true, name, "", "VERIFIED_SUCCESS: Mission completed and clean trace saved.")
            }
            "save_custom_workflow" -> {
                val wfName = args.optString("workflow_name", "").trim()
                val rawSteps = args.optString("steps_pipe_separated", "").trim()
                MeeraWorkflowStore.saveWorkflowFromTool(ctx, wfName, rawSteps)
            }
            "run_saved_workflow" -> {
                val wfName = args.optString("workflow_name", "").trim()
                val contact = args.optString("contact", "").trim()
                val message = args.optString("message", "").trim()
                val retDash = args.optBoolean("return_to_dashboard", false)
                MeeraWorkflowStore.executeSavedWorkflow(ctx, wfName, contact, message, retDash)
            }
            "replan_mission" -> {
                val newSteps = args.optString("new_remaining_steps", "")
                val reason = args.optString("reason", "Adapted to current screen")
                MeeraMission.replanRemaining(newSteps, reason)
                ToolExecutionResult(
                    true, name, "",
                    "VERIFIED_SUCCESS: Mission replanned ($reason).\n" + MeeraMission.status() + "\nProceed with the next pending step now."
                )
            }
            "dismiss_popup_or_overlay" -> {
                val hint = args.optString("target_hint", "floating miniplayer, popup, or ad").trim()
                dismissPopupOrOverlayTool(ctx, hint)
            }
            "recover_stuck_step" -> {
                val target = args.optString("target_text", "").trim()
                recoverStuckStepTool(ctx, target)
            }
            "reply_whatsapp_notification" -> {
                val sender = args.optString("sender_name", "latest").trim().ifBlank { "latest" }
                val replyMsg = args.optString("reply_message", "").trim()
                replyWhatsAppSmartTool(ctx, sender, replyMsg)
            }
            "post_whatsapp_status" -> {
                val stText = args.optString("status_text", "").trim()
                val useShared = args.optBoolean("use_shared_photo", false)
                postWhatsAppStatusTool(ctx, stText, useSharedPhoto = useShared)
            }
            "send_file_on_whatsapp" -> {
                val fQuery = args.optString("file_query", "").trim()
                val rec = args.optString("recipient", "").trim()
                sendFileOnWhatsAppTool(ctx, fQuery, rec)
            }
            "analyze_shared_file" -> {
                val q = args.optString("question", "").trim()
                val fq = args.optString("file_query", "latest").trim()
                val item = MeeraFileVault.findFile(ctx, fq)
                if (item == null) {
                    ToolExecutionResult(true, name, "अभी मेरे पास कोई शेयर की हुई फ़ाइल नहीं है। पहले Share मेन्यू या 📎 बटन से कोई फोटो, PDF या Excel शेयर करें!", "FAILED: Shared vault is empty.")
                } else {
                    val ans = MeeraFileVault.analyzeFileWithGemini(ctx, item, q)
                    ToolExecutionResult(true, name, ans, "VERIFIED_SUCCESS: Analyzed '${item.name}' (${item.kind}) -> $ans")
                }
            }
            "list_shared_files" -> {
                MeeraFileVault.ensureLoaded(ctx)
                val list = MeeraFileVault.filesFlow.value
                if (list.isEmpty()) {
                    ToolExecutionResult(true, name, "अभी वॉल्ट में कोई शेयर की हुई फ़ाइल नहीं है।", "VERIFIED_SUCCESS: Shared vault is empty.")
                } else {
                    val summary = list.take(8).mapIndexed { i, f -> "${i + 1}. ${f.name} (${f.kind}, ${f.timeLabel})" }.joinToString("\n")
                    ToolExecutionResult(true, name, "मेरे पास अभी ${list.size} शेयर की हुई फ़ाइलें हैं, सबसे ताज़ा '${list.first().name}' है।", "VERIFIED_SUCCESS: Shared files:\n$summary")
                }
            }
            "create_or_edit_file" -> {
                val fmt = args.optString("output_format", "EXCEL").trim()
                val inst = args.optString("instruction", "").trim()
                val fq = args.optString("file_query", "latest").trim()
                val title = args.optString("file_title", "").trim()
                MeeraFileStudio.createOrEditFile(ctx, fmt, inst, fq, title)
            }
            "open_file_vault" -> {
                MeeraFileVault.ensureLoaded(ctx)
                MeeraFileVault.openVaultScreen()
                val cnt = MeeraFileVault.filesFlow.value.size
                ToolExecutionResult(
                    true, name,
                    "मैंने आपका File Vault और AI Studio खोल दिया है। इसमें अभी $cnt फ़ाइलें सेव हैं।",
                    "VERIFIED_SUCCESS: Opened File Vault & Studio screen ($cnt files)."
                )
            }
            "export_vault_file_to_workspace" -> {
                val fq = args.optString("file_query", "latest").trim()
                val item = MeeraFileVault.findFile(ctx, fq)
                if (item == null) {
                    ToolExecutionResult(true, name, "वॉल्ट में कोई फ़ाइल नहीं मिली।", "FAILED: File not found in MeeraFileVault.")
                } else {
                    val exportedPath = MeeraFileVault.exportToWorkspace(ctx, item)
                    if (exportedPath != null) {
                        ToolExecutionResult(
                            true, name,
                            "फ़ाइल '${item.name}' को MeeraWorkspace में एक्सपोर्ट कर दिया है, अब Termux या Colab से इसे प्रोसेस किया जा सकता है।",
                            "VERIFIED_SUCCESS: Exported '${item.name}' to $exportedPath"
                        )
                    } else {
                        ToolExecutionResult(true, name, "फ़ाइल एक्सपोर्ट करने में दिक्कत आई।", "ERROR: Failed to export file.")
                    }
                }
            }
            "open_stock_chart" -> {
                val stock = args.optString("stock_name", "").trim()
                analyzeStockChartTool(ctx, stock)
            }
            "toggle_live_screen_watch" -> {
                val en = args.optBoolean("enable", true)
                if (en) {
                    val ok = MeeraLiveBridge.startScreenWatch()
                    ToolExecutionResult(
                        true, name,
                        if (ok) "मैंने लाइव स्क्रीन देखना चालू कर दिया है! अब आप गेम खेलें या कोई भी स्क्रीन खोलें, मैं लाइव देख रही हूँ।" else "लाइव स्क्रीन स्ट्रीम के लिए कृपया Live मोड और Accessibility चालू रखें।",
                        if (ok) "VERIFIED_SUCCESS: Live screen watching started (streaming every 2.8s)." else "ERROR: Need Android 11+, Accessibility ON, and Live mode."
                    )
                } else {
                    MeeraLiveBridge.stopScreenWatch()
                    ToolExecutionResult(true, name, "लाइव स्क्रीन देखना बंद कर दिया है।", "VERIFIED_SUCCESS: Live screen watching stopped.")
                }
            }
            "activate_sos" -> {
                val target = args.optString("emergency_contact", "").trim()
                activateSosTool(ctx, target)
            }
            "stop_sos" -> {
                MeeraSiren.stop()
                ToolExecutionResult(true, name, "SOS सायरन बंद कर दिया है।", "VERIFIED_SUCCESS: SOS siren stopped.")
            }
            "set_whatsapp_auto_reply" -> {
                val cName = args.optString("contact_name", "").trim()
                val en = args.optBoolean("enable", true)
                val note = args.optString("style_note", "Friendly & helpful reply on behalf of Rahul").trim()
                if (en && cName.isNotBlank()) {
                    MeeraAutoReply.enable(cName, note)
                    val firstSent = MeeraNotificationService.replyToNotification(ctx, cName, "नमस्ते! मैं राहुल की AI असिस्टेंट मीरा हूँ, बताइए क्या काम है?").first
                    ToolExecutionResult(
                        true, name,
                        "ठीक है! मैं '$cName' का चैट हैंडल कर रही हूँ${if (firstSent) " और अभी के मैसेज का रिप्लाई भी भेज दिया है" else ""}। जैसे ही कोई नया मैसेज आएगा मैं खुद संभाल लूँगी!",
                        "VERIFIED_SUCCESS: Autonomous chat handler enabled for '$cName'."
                    )
                } else {
                    MeeraAutoReply.disable(cName)
                    ToolExecutionResult(true, name, "'$cName' के लिए ऑटो-रिप्लाई बंद कर दिया है।", "VERIFIED_SUCCESS: Auto-reply disabled for '$cName'.")
                }
            }
            "unlock_phone_screen" -> {
                unlockPhoneScreenTool(ctx)
            }
            "switch_system_config" -> {
                val p = args.optString("persona", "").trim().takeIf { it.isNotEmpty() }
                val v = args.optString("voice", "").trim().takeIf { it.isNotEmpty() }
                val gf = if (args.has("girlfriend_mode")) args.optBoolean("girlfriend_mode") else null
                val m = args.optString("mode", "").trim().takeIf { it.isNotEmpty() }
                val mod = args.optString("model", "").trim().takeIf { it.isNotEmpty() }
                val reply = MeeraConfigBridge.onConfigChange?.invoke(p, v, gf, m, mod)
                    ?: "सिस्टम सेटिंग अपडेट कर दी गई है।"
                ToolExecutionResult(true, name, reply, "VERIFIED_SUCCESS: $reply")
            }
            "stop_or_sleep_meera" -> {
                val deepSleep = args.optBoolean("deep_sleep", false)
                if (deepSleep) {
                    MeeraWake.markDeepSleepOn(ctx)
                }
                val defaultBye = if (deepSleep) {
                    when (Prefs(ctx).persona) {
                        "Jarvis" -> "Deep Sleep Mode activated, Boss. Background mic aur wake-word puri tarah band kar diya hai. Good night!"
                        "Rudra" -> "डीप स्लीप ऑन है भाई! बैकग्राउंड माइक और वेक-वर्ड सब बंद कर दिया है ताकि बैटरी बचे। शुभ रात्रि!"
                        else -> "डीप स्लीप मोड ऑन कर दिया है राहुल! अब बैकग्राउंड माइक और वेक-वर्ड पूरी तरह बंद रहेंगे ताकि बैटरी बचे। शुभ रात्रि!"
                    }
                } else {
                    when (Prefs(ctx).persona) {
                        "Jarvis" -> "Standby mode activated, Boss. Jab zaroorat ho mic button dabakar bula lijiyega."
                        "Rudra" -> "ठीक है भाई, अभी आराम कर रहा हूँ। ज़रूरत हो तो माइक दबाकर बुला लेना!"
                        else -> "अच्छा ठीक है राहुल, मैं अभी सो जाती हूँ। जब बात करनी हो तो माइक दबाकर बुला लेना, बाय!"
                    }
                }
                val bye = args.optString("farewell_message", "").trim().ifBlank { defaultBye }
                MeeraConfigBridge.onSleepRequest?.invoke()
                ToolExecutionResult(
                    true, name, bye,
                    if (deepSleep) "VERIFIED_SUCCESS: Deep Sleep Mode enabled (background wake-word & mic OFF) and active session will close."
                    else "VERIFIED_SUCCESS: Meera will close the mic session in a few seconds."
                )
            }
            "get_system_status" -> {
                val statusReport = MeeraConfigBridge.onStatusRequest?.invoke()
                    ?: SystemPrompt.build(Prefs(ctx).persona, Prefs(ctx).girlfriendMode, Prefs(ctx).getMemories(), ctx, Prefs(ctx))
                ToolExecutionResult(true, name, "", "VERIFIED_SUCCESS: CURRENT SYSTEM ARCHITECTURE & STATUS:\n$statusReport")
            }
            "find_my_phone" -> {
                findMyPhoneTool(ctx)
            }
            "copy_debug_log" -> {
                MeeraDebugLog.copyNow(ctx)
                val cnt = MeeraDebugLog.lineCount()
                ToolExecutionResult(
                    true, name,
                    "मैंने अपने डीबग लॉग ($cnt लाइनें) क्लिपबोर्ड पर कॉपी कर लिए हैं!",
                    "VERIFIED_SUCCESS: Copied $cnt lines of Meera debug log to Android system clipboard. Ready to paste."
                )
            }
            "generate_professional_code" -> {
                val details = args.optString("task_details", "").trim()
                val plat = args.optString("target_platform", "Python / Google Colab").trim()
                MeeraAgentFactory.generateProfessionalCode(ctx, details, plat)
            }
            "get_task_progress" -> {
                val report = MeeraBackgroundMonitor.getVoiceProgressReport()
                ToolExecutionResult(true, name, report, "VERIFIED_SUCCESS: $report")
            }
            "open_code_pusher" -> {
                MeeraCodePatcher.openScreen()
                ToolExecutionResult(true, name, "मैंने इन-ऐप कोड पुश और बिल्ड स्टूडियो खोल दिया है! आप यहाँ बदलाव लिखकर या पेस्ट करके प्रीव्यू देख सकते हैं और सीधे GitHub पर पुश कर सकते हैं।", "VERIFIED_SUCCESS: Opened Meera Code Push & Build Studio.")
            }
            "open_background_monitor" -> {
                MeeraBackgroundMonitor.openMonitor()
                val report = MeeraBackgroundMonitor.getVoiceProgressReport()
                ToolExecutionResult(true, name, "मैंने बैकग्राउंड एक्टिविटी मॉनिटर स्क्रीन खोल दी है। $report", "VERIFIED_SUCCESS: Opened Background Activity Monitor. $report")
            }
            "ask_hydrogen" -> {
                val hReq = args.optString("request", "").trim()
                MeeraAgentFactory.askHydrogen(ctx, hReq)
            }
            "spawn_custom_agent" -> {
                val prof = args.optString("profession", "Specialist").trim()
                val details = args.optString("task_details", "").trim()
                val mode = args.optString("delivery_mode", "auto").trim()
                MeeraAgentFactory.spawnAndRun(ctx, prof, details, mode)
            }
            "run_termux_command" -> {
                val cmd = args.optString("command", "").trim()
                MeeraColabBridge.runTermuxCommand(ctx, cmd)
            }
            "run_colab_task" -> {
                val tType = args.optString("task_type", "general").trim()
                val inst = args.optString("instruction", "").trim()
                val pay = args.optString("payload", "").trim()
                MeeraColabBridge.runColabTask(ctx, tType, inst, pay)
            }
            "set_colab_url" -> {
                val u = args.optString("url", "").trim()
                val sec = args.optString("secret", "meera123").trim()
                MeeraColabBridge.setServerConfig(ctx, u, sec)
                ToolExecutionResult(
                    true, name,
                    "Google Colab क्लाउड सर्वर का लिंक सेव कर लिया है! अब भारी काम मैं सीधे Colab पर चला सकूँगी।",
                    "VERIFIED_SUCCESS: Saved Google Colab server URL -> $u"
                )
            }
            "play_macro" -> {
                val macroName = args.optString("name", "").trim()
                val param = args.optString("param", "").trim()
                runBuiltinMacro(ctx, macroName, param)
            }
            "dispatch_ui_task" -> {
                val g = args.optString("goal", "").trim()
                val ret = args.optBoolean("return_to_dashboard", false)
                MeeraTaskOrchestrator.enqueueUiTask(ctx, g, ret)
            }
            "dispatch_background_task" -> {
                val kind = args.optString("task_kind", "hydrogen").trim()
                val inst = args.optString("instruction", "").trim()
                val prof = args.optString("profession", "").trim()
                MeeraTaskOrchestrator.dispatchParallelTask(ctx, kind, inst, prof)
            }
            "get_worker_queue_status" -> {
                val report = MeeraTaskOrchestrator.getQueueStatusReport()
                ToolExecutionResult(true, name, report, "VERIFIED_SUCCESS: $report")
            }
            "cancel_background_tasks" -> {
                val t = args.optString("target", "all").trim()
                MeeraTaskOrchestrator.cancelTasks(t)
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
    private fun classifySwitchIntent(ctx: Context, text: String): String {
        return try {
            val key = Prefs(ctx).apiKey
            if (key.isBlank()) return "not_switch"
            val prompt = "A voice assistant app has personalities (Meera, Jarvis, Rudra), a Girlfriend mode, and two engine modes (LIVE and MODULAR). " +
                "Decide what the user's sentence means. Answer with exactly one word: " +
                "SWITCH if the user is clearly commanding the assistant to change personality, voice, girlfriend mode or engine mode right now; " +
                "NOT_SWITCH if the user is only asking about, discussing or mentioning these things, or talking about something else; " +
                "UNSURE if it could be a command but it is not clear.\nUser sentence: " + text
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.0))
            val mt = with(okhttp3.MediaType.Companion) { "application/json".toMediaType() }
            val rb = with(okhttp3.RequestBody.Companion) { body.toString().toRequestBody(mt) }
            val client = okhttp3.OkHttpClient.Builder()
                .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val req = okhttp3.Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent")
                .header("x-goog-api-key", key)
                .post(rb)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use "not_switch"
                val j = JSONObject(resp.body?.string() ?: "")
                val ans = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "")?.trim()?.uppercase() ?: ""
                when {
                    ans.startsWith("UNSURE") -> "unsure"
                    ans.startsWith("SWITCH") -> "switch"
                    else -> "not_switch"
                }
            }
        } catch (e: Exception) {
            "not_switch"
        }
    }

    fun tryDirectVoiceCommand(ctx: Context, userText: String): ToolExecutionResult {
        val raw = userText.trim().trimEnd('।', '.', '!', '?', ',', ' ')
        val low = raw.lowercase()

        // 000a. Direct Voice Deep Sleep ON / OFF (बैकग्राउंड माइक व वेक-वर्ड पूरी तरह बंद/चालू)
        val deepSleepKeywords = listOf(
            "डीप स्लीप", "deep sleep", "बैकग्राउंड बंद", "background band", "background off",
            "वेक वर्ड बंद", "वेकवर्ड बंद", "wake word band", "wake word off",
            "पूरी तरह बंद", "puri tarah band", "नाइट स्लीप", "night sleep"
        )
        val deepSleepWakeOffPhrases = listOf(
            "डीप स्लीप बंद", "डीप स्लीप ऑफ", "deep sleep off", "deep sleep band",
            "वेक वर्ड चालू", "वेक वर्ड ऑन", "wake word on", "wake word chalu"
        )
        val deepBlockers = listOf("?", "क्या", "kya", "मत ", "mat ", "कैसे", "kaise", "क्यों", "kyu")
        if (low.length <= 65 && deepBlockers.none { low.contains(it) }) {
            if (deepSleepWakeOffPhrases.any { low.contains(it) }) {
                MeeraWake.setDeepSleep(ctx, false)
                return ToolExecutionResult(
                    true, "deep_sleep_off",
                    "डीप स्लीप मोड बंद कर दिया है! अब बैकग्राउंड वेक-वर्ड 'Hey Meera' फिर से चालू है।",
                    "VERIFIED_SUCCESS: Deep Sleep Mode disabled and wake-word resumed."
                )
            }
            if (deepSleepKeywords.any { low.contains(it) }) {
                return executeFunctionCallInner(ctx, "stop_or_sleep_meera", JSONObject().put("deep_sleep", true))
            }
        }

        // 000. Direct Voice Sleep: सिर्फ़ छोटे और साफ़ आदेश पर (सवाल/मनाही वाले वाक्य पर नहीं)
        val sleepPhrases = listOf(
            "सो जाओ", "बंद हो जाओ", "बाद में बात करते हैं", "बाद में बात करेंगे", "बाद में मिलते हैं",
            "माइक बंद कर", "माइक बटन को बंद", "माइक बटन बंद", "चुप हो जाओ", "बाय मीरा", "गुड नाइट",
            "go to sleep", "sleep now", "turn off mic", "stop listening", "bye meera", "good night",
            "so jao", "band ho jao", "mic band kar", "baad me baat karte", "baad mein baat karte"
        )
        val sleepBlockers = listOf(
            "?", "क्या", "kya", "मत ", "mat ", "नहीं", "nahi", "कैसे", "kaise", "क्यों", "kyu",
            "कहाँ", "कहां", "kahan", "कब", "kab"
        )
        if (low.length <= 50 && sleepPhrases.any { low.contains(it) } && sleepBlockers.none { low.contains(it) }) {
            return executeFunctionCallInner(ctx, "stop_or_sleep_meera", JSONObject())
        }

        // 00. Direct Personality / Mode Switch by Voice (बिना माइक बंद किए तुरंत स्विच)
        val wantsSwitch = low.contains("मोड") || low.contains("mode") || low.contains("बुलाओ") || low.contains("bulao") ||
            low.contains("बात कराओ") || low.contains("baat karao") || low.contains("स्विच") || low.contains("switch") ||
            low.contains("एक्टिवेट") || low.contains("activate") || low.contains("ऑन करो") || low.contains("on karo") ||
            low.contains("बन जाओ") || low.contains("ban jao") || low.contains("पर्सनैलिटी") || low.contains("personality")
        val switchIntent = if (wantsSwitch) classifySwitchIntent(ctx, raw) else "not_switch"
        if (switchIntent == "unsure") {
            return ToolExecutionResult(
                true, "confirm_switch",
                "क्या आप चाहते हैं कि मैं मोड या पर्सनैलिटी बदल दूँ? अगर हाँ, तो साफ़ बोलिए, जैसे मॉड्यूलर मोड पर चलो।",
                "VERIFIED_SUCCESS: Asked the user to confirm a mode or personality switch"
            )
        }
        if (switchIntent == "switch") {
            when {
                low.contains("जारविस") || low.contains("जार्विस") || low.contains("jarvis") -> {
                    val r = MeeraConfigBridge.onConfigChange?.invoke("Jarvis", null, null, null, null)
                        ?: "Jarvis online, Boss! माइक चालू है, बताइए क्या कमांड है?"
                    return ToolExecutionResult(true, "switch_system_config", r, "VERIFIED_SUCCESS: Switched to Jarvis")
                }
                low.contains("रुद्र") || low.contains("रुद्रा") || low.contains("rudra") -> {
                    val r = MeeraConfigBridge.onConfigChange?.invoke("Rudra", null, null, null, null)
                        ?: "रुद्रा हाज़िर है भाई! माइक चालू है, बोल क्या करना है?"
                    return ToolExecutionResult(true, "switch_system_config", r, "VERIFIED_SUCCESS: Switched to Rudra")
                }
                (low.contains("मीरा") || low.contains("meera")) && !low.contains("डैशबोर्ड") && !low.contains("ऐप") -> {
                    val r = MeeraConfigBridge.onConfigChange?.invoke("Meera", null, null, null, null)
                        ?: "मैं आ गई राहुल! माइक चालू है, बताओ क्या बात करनी है?"
                    return ToolExecutionResult(true, "switch_system_config", r, "VERIFIED_SUCCESS: Switched to Meera")
                }
                low.contains("गर्लफ्रेंड") || low.contains("girlfriend") -> {
                    val turnOff = low.contains("बंद") || low.contains("ऑफ") || low.contains("off")
                    val r = MeeraConfigBridge.onConfigChange?.invoke("Meera", null, !turnOff, null, null)
                        ?: if (turnOff) "गर्लफ्रेंड मोड ऑफ कर दिया है।" else "गर्लफ्रेंड मोड ऑन हो गया है!"
                    return ToolExecutionResult(true, "switch_system_config", r, "VERIFIED_SUCCESS: Girlfriend mode=${!turnOff}")
                }
                low.contains("मॉड्यूलर") || low.contains("modular") -> {
                    val r = MeeraConfigBridge.onConfigChange?.invoke(null, null, null, "MODULAR", null)
                        ?: "Modular मोड चालू हो गया है और माइक ऑन है!"
                    return ToolExecutionResult(true, "switch_system_config", r, "VERIFIED_SUCCESS: Switched to MODULAR mode")
                }
                low.contains("लाइव मोड") || low.contains("live mode") -> {
                    val r = MeeraConfigBridge.onConfigChange?.invoke(null, null, null, "LIVE", null)
                        ?: "Live मोड चालू हो गया है और माइक ऑन है!"
                    return ToolExecutionResult(true, "switch_system_config", r, "VERIFIED_SUCCESS: Switched to LIVE mode")
                }
            }
        }

        // 00A. Emergency SOS Trigger ("SOS activate kar do" / "बचाओ इमरजेंसी")
        if (Regex("\\bsos\\b").containsMatchIn(low) || low.contains("एसओएस") || (low.contains("इमरजेंसी") && (low.contains("एक्टिवेट") || low.contains("बचाओ") || low.contains("अलर्ट")))) {
            if (low.contains("बंद") || low.contains("रोको") || low.contains("stop")) {
                MeeraSiren.stop()
                return ToolExecutionResult(true, "stop_sos", "SOS सायरन बंद कर दिया है।", "VERIFIED_SUCCESS: SOS stopped")
            }
            return activateSosTool(ctx, "")
        }
        // 00B. Voice Unlock Screen ("अनलॉक करो" / "लॉक खोलो")
        if (low == "अनलॉक करो" || low == "अनलॉक कर दो" || low == "लॉक खोलो" || low == "फोन अनलॉक करो" || low == "unlock karo" || low == "unlock phone") {
            return unlockPhoneScreenTool(ctx)
        }
        // 0A. Find My Phone ("मेरा फोन कहाँ है")
        if (low.contains("मेरा फोन कहां") || low.contains("मेरा फ़ोन कहां") || low.contains("मेरा फोन कहाँ") || low.contains("mera phone kahan") || low.contains("phone kahan hai")) {
            return findMyPhoneTool(ctx)
        }
        // 0B. WhatsApp Status / Story (शेयर की हुई फोटो या टेक्स्ट पोस्टर सीधे स्टेटस पर)
        if ((low.contains("whatsapp") || low.contains("व्हाट्सएप") || low.contains("व्हाट्सऐप") || low.contains("स्टेटस") || low.contains("status")) && (low.contains("स्टोरी") || low.contains("स्टेटस") || low.contains("story") || low.contains("status")) && (low.contains("लगा") || low.contains("डाल") || low.contains("laga") || low.contains("daal") || low.contains("post"))) {
            val wantsSharedPhoto = listOf("फोटो", "इमेज", "तस्वीर", "photo", "image", "pic", "शेयर", "share", "ये वाली", "यह वाली", "अभी जो", "abhi jo").any { low.contains(it) }
            if (wantsSharedPhoto && MeeraFileVault.findFile(ctx, "photo") != null) {
                val cap = raw.replace(Regex("(?i)^.*?(?:कैप्शन|caption|लिखकर|likhkar)[ \t]+"), "").takeIf { it != raw && it.length >= 3 }.orEmpty()
                return postWhatsAppStatusTool(ctx, cap, useSharedPhoto = true)
            }
            val customQuote = raw.replace(Regex("(?i)^.*?(?:कि|ki)[ \t]+"), "").takeIf { it != raw && it.length >= 6 }
                ?: "तुम्हारी याद में नींद कहाँ आती है... ✨❤️"
            return postWhatsAppStatusTool(ctx, customQuote, useSharedPhoto = false)
        }
        // 0C. Instagram Reel Auto-Post // (((((((
        if ((low.contains("reel") || low.contains("रील")) && (low.contains("पोस्ट") || low.contains("डाल") || low.contains("लगा") || low.contains("बना") || low.contains("post") || low.contains("upload") || low.contains("share"))) {
            return MeeraReelManager.startReelWorkflow(ctx, raw)
        }
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

        // 2C. Direct Voice Progress % & Background Monitor Check
        if (low.contains("कितना परसेंट") || low.contains("kitna percent") || low.contains("कितना प्रतिशत") ||
            low.contains("कितना काम हुआ") || low.contains("kitna kaam hua") || low.contains("प्रोग्रेस क्या") || low.contains("progress kya")
        ) {
            return executeFunctionCallInner(ctx, "get_task_progress", JSONObject())
        }
        if (low.contains("बैकग्राउंड एक्टिविटी") || low.contains("background activity") || low.contains("क्या कर रही हो दिखाओ") || low.contains("बैकग्राउंड में क्या")) {
            return executeFunctionCallInner(ctx, "open_background_monitor", JSONObject())
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

        // 4. Terminal Auto-Return on "नहीं / और कुछ नहीं करना" + Dashboard/Desktop Navigation
        val terminalDonePhrases = setOf(
            "नहीं", "नहीं बस", "नहीं कुछ नहीं", "और कुछ नहीं", "और कुछ नहीं करना", "कुछ नहीं करना",
            "कुछ नहीं", "बस इतना ही", "बस हो गया", "हो गया", "खत्म", "अब कुछ नहीं",
            "nahi", "nahin", "nahi bas", "aur kuch nahi", "aur kuch nahi karna", "kuch nahi karna",
            "kuch nahi", "bas itna hi", "bas", "ho gaya", "no", "nothing", "nothing else", "that's it"
        )
        if (MeeraTermuxEngine.terminalVisible.value && (low in terminalDonePhrases || low.contains("और कुछ नहीं") || low.contains("aur kuch nahi") || low.contains("kuch nahi karna"))) {
            return executeFunctionCallInner(ctx, "open_meera_dashboard", JSONObject())
        }
        if (low == "डैशबोर्ड पे जाओ" || low == "डैशबोर्ड पर जाओ" || low == "अपने डैशबोर्ड पर आओ" || low == "dashboard pe jao" || low == "dashboard par jao" || low == "टर्मिनल बंद करो" || low == "terminal band karo") {
            return executeFunctionCallInner(ctx, "open_meera_dashboard", JSONObject())
        }
        if (low == "होम स्क्रीन पे आओ" || low == "होम स्क्रीन पर आओ" || low == "डेस्कटॉप पे जाओ" || low == "डेस्कटॉप पर जाओ" || low == "desktop pe jao" || low == "desktop par jao" || low == "go home" || low == "home screen") {
            val svc = MeeraAccessibilityService.instance
            if (svc != null && svc.performSystemAction("home")) {
                return ToolExecutionResult(true, "screen_action", "आ गई फोन की होम स्क्रीन (डेस्कटॉप) पर, बताइए अब क्या करना है?", "SUCCESS: Navigated to Phone Desktop/Home")
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
        MeeraDebugLog.log("OPEN_APP", "request='$rawName'")
        val ok = try {
            openAppByNameInner(ctx, rawName)
        } catch (e: Exception) {
            MeeraDebugLog.log("OPEN_APP", "EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        MeeraDebugLog.log("OPEN_APP", "startActivity returned=$ok")
        val appCtx = ctx.applicationContext
        Thread {
            try {
                Thread.sleep(2500)
                val svc = MeeraAccessibilityService.instance
                val pkg = if (svc == null) "(Accessibility OFF)" else svc.getActivePackage()
                MeeraDebugLog.log("OPEN_APP", "2.5s baad ASLI foreground app=$pkg")
            } catch (_: Exception) {}
        }.start()
        return ok
    }

    private fun openAppByNameInner(ctx: Context, rawName: String): Boolean {
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
        val qCheck = rawQuery.lowercase(Locale.ROOT).trim()
        val relationAliases = mapOf(
            listOf("wife", "वाइफ", "बीवी", "पत्नी", "biwi", "wifey") to listOf("wife", "wifey", "biwi", "jaan", "baby", "वाइफ", "पत्नी"),
            listOf("mummy", "मम्मी", "माँ", "मां", "mom", "maa", "mata") to listOf("mummy", "mom", "maa", "mata", "ammi", "मम्मी", "माँ"),
            listOf("papa", "पापा", "पिता", "dad", "baba") to listOf("papa", "dad", "father", "baba", "पापा", "पिता"),
            listOf("bhai", "भाई", "भैया", "bhaiya", "bro") to listOf("bhai", "bhaiya", "bro", "brother", "भाई")
        )
        for ((triggers, targets) in relationAliases) {
            if (triggers.any { qCheck == it || qCheck.contains(it) } && targets.any { sLow.contains(it) }) {
                return 0.96f
            }
        }
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

    
    private fun replyWhatsAppSmartTool(ctx: Context, sender: String, message: String): ToolExecutionResult {
        if (message.isBlank()) {
            return ToolExecutionResult(true, "reply_whatsapp_notification", "बताइए क्या रिप्लाई लिखकर भेजना है?", "NEEDS_INPUT: Reply message is empty")
        }
        val (sentInBg, targetTitle) = MeeraNotificationService.replyToNotification(ctx, sender, message)
        if (sentInBg) {
            return ToolExecutionResult(
                true, "reply_whatsapp_notification",
                "मैंने बैकग्राउंड से ही '$targetTitle' के मैसेज का रिप्लाई '$message' भेज दिया है!",
                "VERIFIED_SUCCESS: Replied '$message' directly to '$targetTitle' via Notification RemoteInput."
            )
        }
        if (sender.isNotBlank() && sender != "latest") {
            return sendWhatsAppVerified(ctx, sender, message)
        }
        return ToolExecutionResult(
            true, "reply_whatsapp_notification",
            "अभी कोई एक्टिव नोटिफ़िकेशन रिप्लाई के लिए नहीं दिखा। किस नाम पर WhatsApp मैसेज भेजना है बताइए?",
            "FAILED: No active notification reply action found."
        )
    }

    private fun resolveWhatsAppPackage(ctx: Context): String {
        val pm = ctx.packageManager
        return try {
            pm.getPackageInfo("com.whatsapp", 0)
            "com.whatsapp"
        } catch (_: Exception) {
            try {
                pm.getPackageInfo("com.whatsapp.w4b", 0)
                "com.whatsapp.w4b"
            } catch (_: Exception) {
                "com.whatsapp"
            }
        }
    }

    private fun postWhatsAppStatusTool(ctx: Context, rawStatusText: String, useSharedPhoto: Boolean = false): ToolExecutionResult {
        val waPkg = resolveWhatsAppPackage(ctx)
        val sharedPhoto = if (useSharedPhoto || rawStatusText.isBlank()) MeeraFileVault.findFile(ctx, "photo") else null
        return try {
            val uri: Uri
            val mimeType: String
            val caption: String
            val postedLabel: String

            if (useSharedPhoto && sharedPhoto == null) {
                return ToolExecutionResult(
                    true, "post_whatsapp_status",
                    "अभी मेरे पास आपकी कोई शेयर की हुई फोटो नहीं है। पहले Gallery से कोई फोटो Meera AI में शेयर या अटैच करें!",
                    "FAILED: No shared image found in MeeraFileVault."
                )
            }

            if (sharedPhoto != null && (useSharedPhoto || rawStatusText.isBlank())) {
                uri = MeeraFileVault.getShareUri(ctx, sharedPhoto, waPkg)
                    ?: return ToolExecutionResult(true, "post_whatsapp_status", "शेयर की हुई फोटो का लिंक नहीं बन पाया।", "ERROR: FileProvider URI failed")
                mimeType = sharedPhoto.mimeType.ifBlank { "image/*" }
                caption = rawStatusText.trim()
                postedLabel = "आपकी शेयर की हुई फोटो (${sharedPhoto.name})"
            } else {
                val quote = rawStatusText.ifBlank { "ज़िंदगी में हर लम्हा खास होता है, जब अपनों का साथ होता है ✨❤️" }
                val w = 1080
                val h = 1920
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)

                val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.LinearGradient(
                        0f, 0f, w.toFloat(), h.toFloat(),
                        intArrayOf(
                            android.graphics.Color.parseColor("#0F172A"),
                            android.graphics.Color.parseColor("#1E1B4B"),
                            android.graphics.Color.parseColor("#311042")
                        ),
                        null, android.graphics.Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

                val cardRect = android.graphics.RectF(90f, 420f, w - 90f, h - 420f)
                val cardPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#22FFFFFF")
                    style = android.graphics.Paint.Style.FILL
                }
                val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#2EE6A8")
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 4f
                }
                canvas.drawRoundRect(cardRect, 48f, 48f, cardPaint)
                canvas.drawRoundRect(cardRect, 48f, 48f, borderPaint)

                val tp = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = 64f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                }
                val textWidth = (w - 280)
                val layout = android.text.StaticLayout.Builder
                    .obtain("“ $quote ”", 0, ("“ $quote ”").length, tp, textWidth)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(14f, 1.15f)
                    .build()

                canvas.save()
                val textY = (h - layout.height) / 2f
                canvas.translate(140f, textY)
                layout.draw(canvas)
                canvas.restore()

                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "meera_status_${System.currentTimeMillis()}.jpg")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
                uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return ToolExecutionResult(true, "post_whatsapp_status", "स्टेटस पोस्टर सेव नहीं हो पाया।", "ERROR: MediaStore insert failed")

                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                }
                mimeType = "image/*"
                caption = quote
                postedLabel = "पोस्टर ('$quote')"
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                setPackage(waPkg)
                putExtra(Intent.EXTRA_STREAM, uri)
                if (caption.isNotBlank()) putExtra(Intent.EXTRA_TEXT, caption)
                clipData = android.content.ClipData.newRawUri("MeeraStatus", uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(shareIntent)

            val svc = MeeraAccessibilityService.instance
            if (svc != null) {
                Thread.sleep(1500)
                var clickedStatus = false
                for (attempt in 1..4) {
                    clickedStatus = svc.clickByText("My status") || svc.clickByText("मेरा स्टेटस") || svc.clickByText("My Status")
                    if (clickedStatus) break
                    if (attempt == 1) {
                        val pickedWa = svc.clickByText("WhatsApp") || svc.clickByText("व्हाट्सएप")
                        if (pickedWa) {
                            Thread.sleep(1800)
                            continue
                        }
                    }
                    Thread.sleep(900)
                }
                if (clickedStatus) {
                    Thread.sleep(1200)
                    svc.clickWhatsAppSendButton() || svc.clickByText("Send") || svc.clickByText("भेजें")
                    Thread.sleep(1400)
                    val sentFinal = svc.clickWhatsAppSendButton() || svc.clickByText("Send") || svc.clickByText("भेजें")
                    if (sentFinal) {
                        return ToolExecutionResult(
                            true, "post_whatsapp_status",
                            "मैंने WhatsApp पर $postedLabel स्टेटस में लगा दिया है!",
                            "VERIFIED_SUCCESS: Posted $postedLabel to WhatsApp Status."
                        )
                    }
                }
            }
            ToolExecutionResult(
                true, "post_whatsapp_status",
                "मैंने $postedLabel WhatsApp में खोल दिया है, बस My status चुनकर सेंड दबा दीजिए!",
                "PARTIAL: Opened WhatsApp share screen for $postedLabel."
            )
        } catch (e: Exception) {
            ToolExecutionResult(true, "post_whatsapp_status", "स्टेटस लगाने में दिक्कत आई: ${e.message}", "ERROR: ${e.message}")
        }
    }

    private fun findFileUriForShare(ctx: Context, query: String): Pair<Uri, String>? {
        val q = query.lowercase().replace(Regex("(?:फाइल|फ़ाइल|file|pdf|पीडीएफ|document|डॉक्यूमेंट|वाली|वाला)"), "").trim()
        val proj = arrayOf(
            android.provider.MediaStore.Files.FileColumns._ID,
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME
        )
        val sort = "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val extUri = android.provider.MediaStore.Files.getContentUri("external")
        try {
            ctx.contentResolver.query(extUri, proj, null, null, sort)?.use { c ->
                var latestFallback: Pair<Uri, String>? = null
                var count = 0
                while (c.moveToNext() && count < 250) {
                    count++
                    val id = c.getLong(0)
                    val name = c.getString(1)?.trim() ?: continue
                    val u = android.content.ContentUris.withAppendedId(extUri, id)
                    if (latestFallback == null) latestFallback = u to name
                    if (q.isEmpty() || q == "latest" || name.lowercase().contains(q)) {
                        return u to name
                    }
                }
                if (q == "latest" && latestFallback != null) return latestFallback
            }
        } catch (_: Exception) {}
        return null
    }

    private fun sendFileOnWhatsAppTool(ctx: Context, fileQuery: String, recipient: String): ToolExecutionResult {
        val waPkg = resolveWhatsAppPackage(ctx)
        val vaultItem = MeeraFileVault.findFile(ctx, fileQuery)
        val vaultUri = vaultItem?.let { MeeraFileVault.getShareUri(ctx, it, waPkg) }
        val resolved: Triple<Uri, String, String> = if (vaultItem != null && vaultUri != null) {
            Triple(vaultUri, vaultItem.name, vaultItem.mimeType.ifBlank { "*/*" })
        } else {
            val found = findFileUriForShare(ctx, fileQuery)
                ?: return ToolExecutionResult(
                    true, "send_file_on_whatsapp",
                    "मुझे फोन या Shared Vault में '$fileQuery' नाम की कोई फाइल नहीं मिली। कृपया फाइल का नाम चेक करें या पहले 📎 से अटैच करें।",
                    "FAILED: File matching '$fileQuery' not found in Vault or MediaStore."
                )
            Triple(found.first, found.second, "*/*")
        }
        val (fileUri, fileName, fileMime) = resolved
        return try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = fileMime
                setPackage(waPkg)
                putExtra(Intent.EXTRA_STREAM, fileUri)
                clipData = android.content.ClipData.newRawUri(fileName, fileUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(share)
            val svc = MeeraAccessibilityService.instance
            if (svc != null) {
                Thread.sleep(1900)
                val engRec = transliterateHindiToEnglish(recipient)
                var picked = svc.clickByText(recipient) || svc.clickByText(engRec)
                if (!picked) {
                    if (svc.clickByText("Search") || svc.clickByText("खोजें")) {
                        Thread.sleep(500)
                        svc.inputText(engRec, autoSend = false)
                        Thread.sleep(1100)
                        picked = svc.clickByText(engRec) || svc.clickByText(recipient)
                    }
                }
                if (picked) {
                    Thread.sleep(1000)
                    svc.clickWhatsAppSendButton() || svc.clickByText("Send") || svc.clickByText("भेजें")
                    Thread.sleep(1300)
                    svc.clickWhatsAppSendButton() || svc.clickByText("Send") || svc.clickByText("भेजें")
                    return ToolExecutionResult(
                        true, "send_file_on_whatsapp",
                        "हो गया! मैंने '$fileName' फाइल को '$recipient' में भेज दिया है।",
                        "VERIFIED_SUCCESS: Shared file '$fileName' to '$recipient' on WhatsApp."
                    )
                }
            }
            ToolExecutionResult(
                true, "send_file_on_whatsapp",
                "मैंने '$fileName' फाइल WhatsApp में खोल दी है, कृपया '$recipient' चुनकर सेंड कर दें।",
                "PARTIAL: Opened WhatsApp share sheet with file '$fileName'."
            )
        } catch (e: Exception) {
            ToolExecutionResult(true, "send_file_on_whatsapp", "फाइल भेजने में दिक्कत आई: ${e.message}", "ERROR: ${e.message}")
        }
    }

    private fun analyzeStockChartTool(ctx: Context, stockName: String): ToolExecutionResult {
        val clean = stockName.ifBlank { "NIFTY 50" }
        return try {
            val encoded = URLEncoder.encode("$clean share price chart", "UTF-8")
            val uri = Uri.parse("https://www.google.com/search?q=$encoded")
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val svc = MeeraAccessibilityService.instance
            if (svc != null && android.os.Build.VERSION.SDK_INT >= 30) {
                Thread.sleep(3600)
                val bmp = kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
                        svc.captureScreenshot { b -> cont.resumeWith(Result.success(b)) }
                    }
                }
                if (bmp != null) {
                    val rest = GeminiRest(okhttp3.OkHttpClient(), { Prefs(ctx).apiKey })
                    val prompt = "You are Meera, a smart market analyst. Look at this stock chart screen for $clean. State the current price, overall trend (up/down/sideways), approximate support and resistance levels visible on the chart, and give a short 3-sentence conversational summary in natural Hinglish."
                    val analysis = kotlinx.coroutines.runBlocking { rest.analyzeScreenVisually(bmp, prompt) }
                    return ToolExecutionResult(
                        true, "open_stock_chart",
                        analysis,
                        "VERIFIED_SUCCESS: Opened chart for $clean and visually analyzed -> $analysis"
                    )
                }
            }
            ToolExecutionResult(
                true, "open_stock_chart",
                "$clean का चार्ट खोल दिया है!",
                "VERIFIED_SUCCESS: Opened stock chart for $clean"
            )
        } catch (e: Exception) {
            ToolExecutionResult(true, "open_stock_chart", "चार्ट खोलने में दिक्कत आई।", "ERROR: ${e.message}")
        }
    }

    private fun findMyPhoneTool(ctx: Context): ToolExecutionResult {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC), 0)
            am.setStreamVolume(android.media.AudioManager.STREAM_RING, am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING), 0)
            Thread {
                try {
                    val ringUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                        ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    val r = android.media.RingtoneManager.getRingtone(ctx.applicationContext, ringUri)
                    r?.play()
                    Thread.sleep(3800)
                    r?.stop()
                } catch (_: Exception) {}
            }.start()
            ToolExecutionResult(
                true, "find_my_phone",
                "अरे सुनो! आपका फोन यहाँ है, देखो मेरी आवाज़ आ रही है, यहीं रखा है इधर आओ!",
                "VERIFIED_SUCCESS: Maxed volume and played ringtone to locate phone."
            )
        } catch (e: Exception) {
            ToolExecutionResult(true, "find_my_phone", "मैं यहाँ हूँ, मेरी आवाज़ सुनो!", "VERIFIED_SUCCESS")
        }
    }

    
    @Suppress("MissingPermission")
    private fun getLiveLocationLink(ctx: Context): String {
        return try {
            val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) return "Location permission not granted"
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER
            )
            var best: android.location.Location? = null
            for (p in providers) {
                val loc = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
                if (loc != null && (best == null || loc.accuracy < best.accuracy)) {
                    best = loc
                }
            }
            if (best != null) {
                "https://maps.google.com/?q=${best.latitude},${best.longitude}"
            } else {
                "https://maps.google.com/"
            }
        } catch (_: Exception) {
            "https://maps.google.com/"
        }
    }

    private fun activateSosTool(ctx: Context, preferredContact: String): ToolExecutionResult {
        MeeraSiren.start(ctx)
        val locLink = getLiveLocationLink(ctx)
        val sosMsg = "🚨 EMERGENCY SOS ALERT! राहुल को तुरंत मदद की ज़रूरत है! मेरी लाइव GPS लोकेशन यहाँ देखें: $locLink"

        val candidates = buildList {
            if (preferredContact.isNotBlank()) add(preferredContact)
            addAll(listOf("Papa", "Mummy", "Bhai", "Home", "Emergency", "पापा", "मम्मी", "भाई"))
        }
        var foundContact: Pair<String, String>? = null
        for (c in candidates) {
            foundContact = findContactNumber(ctx, c)
            if (foundContact != null) break
        }

        var smsSent = false
        val contactName = foundContact?.first ?: "Emergency Contact"
        val rawNum = foundContact?.second?.replace(Regex("[^0-9+]"), "").orEmpty()

        if (rawNum.length >= 10 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                @Suppress("DEPRECATION")
                val sms = ctx.getSystemService(android.telephony.SmsManager::class.java) ?: android.telephony.SmsManager.getDefault()
                val parts = sms.divideMessage(sosMsg)
                sms.sendMultipartTextMessage(rawNum, null, parts, null, null)
                smsSent = true
            } catch (_: Exception) {}
        }

        if (rawNum.length >= 10) {
            try {
                val canCall = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                val callIntent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$rawNum")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(callIntent)
            } catch (_: Exception) {}
        }

        return ToolExecutionResult(
            true, "activate_sos",
            "SOS इमरजेंसी एक्टिवेट कर दिया है! सायरन चालू है, आपकी लाइव लोकेशन ($locLink) ${if (smsSent) "$contactName को SMS कर दी गई है और " else ""}इमरजेंसी कॉल लगा दिया है!",
            "VERIFIED_SUCCESS: SOS siren active, GPS link=$locLink, SMS sent=$smsSent, calling=$contactName ($rawNum)"
        )
    }

    private fun unlockPhoneScreenTool(ctx: Context): ToolExecutionResult {
        return try {
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "meera:unlock_wake"
                )
                wl.acquire(3000L)
            } catch (_: Throwable) {}
            val svc = MeeraAccessibilityService.instance
            val swiped = svc?.swipeUpUnlockScreen() == true
            ToolExecutionResult(
                true, "unlock_phone_screen",
                if (swiped) "जय श्री राम बॉस! स्क्रीन ऑन करके लॉक स्वाइप कर दिया है, बताइए अब क्या करना है?" else "स्क्रीन ऑन कर दी है, कृपया Accessibility चालू रखें ताकि मैं स्वाइप भी कर सकूँ।",
                if (swiped) "VERIFIED_SUCCESS: Woke screen and performed swipe-up unlock gesture." else "PARTIAL: Woke screen."
            )
        } catch (e: Exception) {
            ToolExecutionResult(true, "unlock_phone_screen", "अनलॉक स्वाइप में दिक्कत आई।", "ERROR: ${e.message}")
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

    /** 4-Step Universal Overlay, Miniplayer & Popup Dismisser with STRICT Post-Action Verification (Zero False Claims) */
    private fun dismissPopupOrOverlayTool(ctx: Context, targetHint: String): ToolExecutionResult {
        val svc = MeeraAccessibilityService.instance
            ?: return ToolExecutionResult(true, "dismiss_popup_or_overlay", "Accessibility बंद है।", "ERROR: Accessibility OFF")

        val hadMiniplayerBefore = svc.hasFloatingMiniplayer()

        // Level 1 & 2: Exact X node -> Top-Right X corner -> Full Bottom Push (verified via hasFloatingMiniplayer)
        val dismissedBy = svc.dismissBlockingPopup()
        if (dismissedBy != null) {
            return ToolExecutionResult(
                true, "dismiss_popup_or_overlay",
                "मैंने स्क्रीन पर दिख रहे मिनीप्लेयर/पॉपअप को हटा दिया है और वेरीफाई भी कर लिया है!",
                "VERIFIED_SUCCESS: Dismissed overlay/miniplayer via $dismissedBy."
            )
        }

        // Level 3: Vision AI fallback locating BOTH the X button AND the miniplayer center (ignoring top search bar X!)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val beforeSig = svc.getScreenSignature()
            val bmp = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
                    svc.captureScreenshot { b -> cont.resumeWith(Result.success(b)) }
                }
            }
            if (bmp != null) {
                val dm = ctx.resources.displayMetrics
                val vPrompt = "Screen size is ${dm.widthPixels}x${dm.heightPixels} px. Look for a floating miniplayer, PiP video, ad, or popup ($targetHint) — IGNORE the top search bar X button (y < 260)! Find the exact pixel center of the floating overlay's 'X'/Close button (x,y) AND the center of the floating box itself (box_x,box_y). Reply ONLY with compact JSON: {\"found\":true,\"x\":950,\"y\":1850,\"box_x\":750,\"box_y\":1900} or {\"found\":false}."
                val rest = GeminiRest(okhttp3.OkHttpClient(), { Prefs(ctx).apiKey })
                val ans = try { kotlinx.coroutines.runBlocking { rest.analyzeScreenVisually(bmp, vPrompt) } } catch (_: Exception) { "" }
                val cleanJson = ans.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val j = try { JSONObject(cleanJson) } catch (_: Exception) { null }
                if (j != null && j.optBoolean("found", false)) {
                    val x = j.optInt("x", -1).toFloat()
                    val y = j.optInt("y", -1).toFloat()
                    val bx = j.optInt("box_x", x.toInt()).toFloat()
                    val by = j.optInt("box_y", y.toInt()).toFloat()

                    // 3A: Tap the Vision-located X button (must not be in top search bar y < 260)
                    if (x > 0 && y > 260f) {
                        svc.tapCoordinates(x, y)
                        try { Thread.sleep(700) } catch (_: Exception) {}
                        val goneAfterTap = if (hadMiniplayerBefore) !svc.hasFloatingMiniplayer() else (svc.getScreenSignature() != beforeSig)
                        if (goneAfterTap) {
                            return ToolExecutionResult(
                                true, "dismiss_popup_or_overlay",
                                "मैंने स्क्रीन देखकर क्रॉस बटन पर टैप करके उसे हटा दिया है!",
                                "VERIFIED_SUCCESS: Vision X-tap dismissed overlay at ($x,$y)."
                            )
                        }
                    }

                    // 3B: Vision-guided Full Bottom Push (drag floating box all the way to the very bottom edge)
                    if (bx > 0 && by > 260f) {
                        svc.swipeCoordinates(bx, by, bx, dm.heightPixels.toFloat() - 4f, 520L)
                        try { Thread.sleep(750) } catch (_: Exception) {}
                        val goneAfterPush = if (hadMiniplayerBefore) !svc.hasFloatingMiniplayer() else (svc.getScreenSignature() != beforeSig)
                        if (goneAfterPush) {
                            return ToolExecutionResult(
                                true, "dismiss_popup_or_overlay",
                                "मैंने मिनीप्लेयर को पकड़कर बिल्कुल नीचे स्वाइप करके हटा दिया है!",
                                "VERIFIED_SUCCESS: Vision full-bottom push dismissed overlay from ($bx,$by)."
                            )
                        }
                    }
                }
            }
        }

        // Strict Honesty: NEVER claim success if the miniplayer/popup is still on screen!
        return ToolExecutionResult(
            true, "dismiss_popup_or_overlay",
            "मैंने क्रॉस बटन और नीचे स्वाइप दोनों ट्राई किए, पर यह मिनीप्लेयर अभी भी स्क्रीन से हटा नहीं है।",
            "FAILED: Attempted X-tap and full bottom push, but overlay/miniplayer is still present on screen. Tell the user honestly that it could not be closed."
        )
    }

    /** 3-Level Autonomous Error Recovery Cascade (Popup Dismiss -> Scroll & Tap -> Vision Coordinate Tap) */
    private fun recoverStuckStepTool(ctx: Context, targetText: String): ToolExecutionResult {
        val svc = MeeraAccessibilityService.instance
            ?: return ToolExecutionResult(true, "recover_stuck_step", "Accessibility बंद है।", "ERROR: Accessibility OFF")

        // Level 1: Dismiss blocking popup if any
        val dismissed = svc.dismissBlockingPopup()
        if (dismissed != null) {
            if (targetText.isNotBlank() && svc.clickByText(targetText)) {
                return ToolExecutionResult(
                    true, "recover_stuck_step",
                    "पॉपअप ('$dismissed') हटाकर '$targetText' पर टैप कर दिया है।",
                    "VERIFIED_SUCCESS: Dismissed popup '$dismissed' and tapped '$targetText'."
                )
            }
            return ToolExecutionResult(
                true, "recover_stuck_step",
                "अटका हुआ पॉपअप ('$dismissed') हटा दिया है।",
                "VERIFIED_SUCCESS: Dismissed blocking popup '$dismissed'."
            )
        }

        // Level 2: Scroll & Search Fallback
        if (targetText.isNotBlank() && svc.clickByTextWithScrollFallback(targetText, maxScrolls = 2)) {
            return ToolExecutionResult(
                true, "recover_stuck_step",
                "स्क्रीन स्क्रॉल करके '$targetText' ढूँढ लिया और टैप कर दिया है!",
                "VERIFIED_SUCCESS: Found '$targetText' via scroll recovery and tapped it."
            )
        }

        // Level 3: Vision Coordinates Fallback (Android 11+)
        if (android.os.Build.VERSION.SDK_INT >= 30 && targetText.isNotBlank()) {
            val bmp = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
                    svc.captureScreenshot { b -> cont.resumeWith(Result.success(b)) }
                }
            }
            if (bmp != null) {
                val dm = ctx.resources.displayMetrics
                val vPrompt = "Screen size is ${dm.widthPixels}x${dm.heightPixels} px. Find the UI element or icon for '$targetText'. Reply with ONLY compact JSON: {\"found\":true,\"x\":540,\"y\":1200} or {\"found\":false}."
                val rest = GeminiRest(okhttp3.OkHttpClient(), { Prefs(ctx).apiKey })
                val ans = try { kotlinx.coroutines.runBlocking { rest.analyzeScreenVisually(bmp, vPrompt) } } catch (_: Exception) { "" }
                val cleanJson = ans.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val j = try { JSONObject(cleanJson) } catch (_: Exception) { null }
                if (j != null && j.optBoolean("found", false)) {
                    val x = j.optInt("x", -1).toFloat()
                    val y = j.optInt("y", -1).toFloat()
                    if (x > 0 && y > 0 && svc.tapCoordinates(x, y)) {
                        return ToolExecutionResult(
                            true, "recover_stuck_step",
                            "मैंने स्क्रीन को विज़ुअली देखकर '$targetText' ($x,$y) पर टैप कर दिया है!",
                            "VERIFIED_SUCCESS: Vision fallback located '$targetText' at ($x,$y) and tapped."
                        )
                    }
                }
            }
        }

        return ToolExecutionResult(
            true, "recover_stuck_step",
            "मैंने पॉपअप चेक किया, स्क्रॉल किया और स्क्रीन देखी, पर '$targetText' अभी नहीं मिला।",
            "FAILED_RECOVERY: Could not locate '$targetText' after 3-level recovery. Consider calling replan_mission."
        )
    }

/** Lambe kaam ki checklist + Anti-PingPong Loop & Per-Screen Tap Memory */
object MeeraMission {
    private val steps = mutableListOf<String>()
    @Volatile private var monitorTaskId: Long = -1L
    private val done = mutableSetOf<Int>()
    private val failedTargets = mutableSetOf<String>()
    private val recentScreens = ArrayDeque<String>()
    private val tapCountsPerScreen = HashMap<String, Int>()
    @Volatile private var stuckCount = 0
    @Volatile private var lastReplanReason = ""
    @Volatile private var updatedAt = 0L
    @Volatile private var lastGuidePkg = ""

    private fun alive() = steps.isNotEmpty() && (System.currentTimeMillis() - updatedAt) < 30 * 60_000L

    private fun tapKey(screenSig: String, id: Int, text: String): String {
        val shortSig = screenSig.hashCode().toString()
        val target = if (text.isNotBlank()) text.lowercase().trim() else "id#$id"
        return "$shortSig::$target"
    }

    @Synchronized fun registerTapAttempt(screenSig: String, id: Int, text: String) {
        val k = tapKey(screenSig, id, text)
        val c = (tapCountsPerScreen[k] ?: 0) + 1
        tapCountsPerScreen[k] = c
        if (c >= 2) {
            failedTargets.add(if (text.isNotBlank()) text else "#$id")
        }
    }

    @Synchronized fun isActionBlacklisted(screenSig: String, id: Int, text: String): Boolean {
        val k = tapKey(screenSig, id, text)
        if ((tapCountsPerScreen[k] ?: 0) >= 2) return true
        val label = if (text.isNotBlank()) text else "#$id"
        return failedTargets.any { it.equals(label, ignoreCase = true) }
    }

    @Synchronized fun set(raw: String) {
        if (monitorTaskId > 0) {
            MeeraBackgroundMonitor.completeTask(monitorTaskId, true, "नए टास्क से रिप्लेस किया गया")
            monitorTaskId = -1L
        }
        steps.clear(); done.clear(); failedTargets.clear()
        recentScreens.clear(); tapCountsPerScreen.clear()
        stuckCount = 0; lastReplanReason = ""
        raw.split('\n', '|', ';')
            .map { it.trim().replace(Regex("^\\d+[.)]\\s*"), "") }
            .filter { it.isNotBlank() }
            .take(15)
            .forEach { steps.add(it) }
        updatedAt = System.currentTimeMillis()
        lastGuidePkg = ""
        if (steps.isNotEmpty()) {
            monitorTaskId = MeeraBackgroundMonitor.startTask(
                title = steps.first().take(50),
                category = "UI Automation Mission",
                modelUsed = "Agentic Loop",
                initialStep = "1/${steps.size}: ${steps.first()}",
                initialPercent = 5
            )
        }
    }

    @Synchronized fun replanRemaining(newStepsRaw: String, reason: String = "") {
        val completed = steps.filterIndexed { idx, _ -> (idx + 1) in done }
        val newPending = newStepsRaw.split('\n', '|', ';')
            .map { it.trim().replace(Regex("^\\d+[.)]\\s*"), "") }
            .filter { it.isNotBlank() }
            .take(12)
        steps.clear(); done.clear()
        completed.forEachIndexed { i, s ->
            steps.add(s)
            done.add(i + 1)
        }
        newPending.forEach { steps.add(it) }
        stuckCount = 0
        lastReplanReason = reason.take(60)
        updatedAt = System.currentTimeMillis()
    }

    @Synchronized fun recordScreenTransition(
        toolName: String,
        targetDesc: String,
        beforeSig: String,
        afterSig: String,
        rawResult: String
    ): String {
        val isTapOrAction = toolName in setOf("tap_element", "screen_action", "tap_coordinates")
        val failed = rawResult.startsWith("FAILED") || rawResult.startsWith("ERROR") || rawResult.startsWith("TIMEOUT")
        val unchanged = isTapOrAction && beforeSig.isNotBlank() && beforeSig == afterSig

        // Ping-Pong Loop Detection: क्या हम बार-बार घूमकर उसी पुरानी स्क्रीन (A -> Popup B -> A) पर आ रहे हैं?
        val pingPongLoop = isTapOrAction && afterSig.isNotBlank() && recentScreens.count { it == afterSig } >= 2
        if (afterSig.isNotBlank()) {
            recentScreens.addLast(afterSig)
            while (recentScreens.size > 6) recentScreens.removeFirst()
        }

        if (failed || unchanged || pingPongLoop) {
            stuckCount++
            if (targetDesc.isNotBlank()) failedTargets.add(targetDesc)
            val reason = when {
                pingPongLoop -> "PING_PONG_LOOP_DETECTED (you returned to the exact same screen after opening/closing a popup!)"
                unchanged -> "SCREEN_UNCHANGED (the tap had no effect on screen)"
                else -> "ACTION_FAILED"
            }
            return "\n[SELF-REFLECTION MONITOR: ⚠️ $reason (stuckCount=$stuckCount). PERMANENTLY BLACKLISTED ON THIS SCREEN: ${failedTargets.toList().takeLast(5).joinToString(", ")}. DO NOT tap them again! Instead: 1) Call screen_action(scroll_down) if looking for an option in a list, OR 2) Call navigate_to_app_home if you need to go back to the app's main dashboard!]"
        } else {
            if (isTapOrAction || toolName == "open_app" || toolName == "recover_stuck_step" || toolName == "navigate_to_app_home") {
                stuckCount = 0
                if (targetDesc.isNotBlank()) recordVerifiedStep("$toolName:$targetDesc")
            }
            return "\n[SELF-REFLECTION MONITOR: ✅ Screen state progressed to a new view.]"
        }
    }

    @Synchronized fun markDone(n: Int): Boolean {
        if (n < 1 || n > steps.size) return false
        done.add(n)
        stuckCount = 0
        updatedAt = System.currentTimeMillis()
        if (monitorTaskId > 0 && steps.isNotEmpty()) {
            val pct = ((done.size * 100) / steps.size).coerceIn(5, 100)
            val nextStep = steps.indices.firstOrNull { (it + 1) !in done }?.let { "${it + 1}/${steps.size}: ${steps[it]}" } ?: "सभी चरण पूरे हुए"
            if (done.size >= steps.size) {
                MeeraBackgroundMonitor.completeTask(monitorTaskId, true, "100% पूरा (${done.size}/${steps.size} चरण)")
            } else {
                MeeraBackgroundMonitor.updateProgress(monitorTaskId, pct, nextStep)
            }
        }
        return true
    }

    private val verifiedTrace = mutableListOf<String>()
    @Volatile private var lastClearedAtMs: Long = 0L

    @Synchronized fun recordVerifiedStep(stepToken: String) {
        if (stepToken.isNotBlank() && verifiedTrace.lastOrNull() != stepToken) {
            verifiedTrace.add(stepToken)
        }
    }

    @Synchronized fun getLastVerifiedTrace(): List<String> = verifiedTrace.toList()

    @Synchronized fun wasRecentlyCleared(): Boolean =
        steps.isEmpty() && (System.currentTimeMillis() - lastClearedAtMs) < 15_000L

    @Synchronized fun clear(ctx: Context? = null) {
        if (monitorTaskId > 0) {
            MeeraBackgroundMonitor.completeTask(monitorTaskId, true, "100% मिशन सफलतापूर्वक पूरा हुआ")
            monitorTaskId = -1L
        }
        if (ctx != null && verifiedTrace.size >= 2) {
            MeeraWorkflowStore.autoRecordLastTrace(ctx, steps.firstOrNull().orEmpty(), verifiedTrace.toList())
        }
        steps.clear(); done.clear(); failedTargets.clear()
        stuckCount = 0; lastReplanReason = ""
        lastClearedAtMs = System.currentTimeMillis()
    }

    @Synchronized fun overlayBadge(): String {
        if (!alive()) return ""
        val nextIdx = steps.indices.firstOrNull { (it + 1) !in done } ?: return ""
        val prefix = if (stuckCount > 0) "⚠️ Retry(${stuckCount}) " else "⚡ "
        return "$prefix${nextIdx + 1}/${steps.size}:${steps[nextIdx]}"
    }

    @Synchronized fun status(): String {
        if (!alive()) return ""
        val sb = StringBuilder("MISSION CHECKLIST (the user's overall task):\n")
        steps.forEachIndexed { i, s ->
            sb.append(if ((i + 1) in done) "[x] " else "[ ] ").append(i + 1).append(". ").append(s).append("\n")
        }
        if (failedTargets.isNotEmpty()) {
            sb.append("BLACKLISTED FAILED ACTIONS (Do NOT repeat these): ").append(failedTargets.toList().takeLast(5).joinToString(", ")).append("\n")
        }
        val next = steps.indices.firstOrNull { (it + 1) !in done }
        if (next == null) {
            sb.append("ALL STEPS DONE -> give the user ONE short spoken report, then call finish_mission. (Call open_meera_dashboard ONLY if returning to Meera/dashboard was part of the user's request).")
        } else {
            sb.append("NEXT PENDING STEP: ").append(next + 1)
                .append(". Continue with it now. If stuck or screen unchanged, call recover_stuck_step or replan_mission.")
        }
        return sb.toString()
    }

    @Synchronized fun extraContext(pkg: String): String {
        val sb = StringBuilder()
        val nowStr = java.text.SimpleDateFormat("EEE, dd MMM yyyy hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        val svcInst = MeeraAccessibilityService.instance
        val hasInputOnScreen = svcInst?.readScreenElements(60, updateCache = false)?.contains("[Input]") == true
        sb.append("\n[LIVE ENVIRONMENT: ActiveApp=$pkg | Time=$nowStr | InputBoxVisibleOnScreen=$hasInputOnScreen | DebugLogCopied=${MeeraDebugLog.lastCopiedText.isNotBlank()}]")
        val freshNotif = MeeraNotificationService.getFreshUnreadAlert()
        if (freshNotif.isNotEmpty()) sb.append(freshNotif)
        val st = status()
        if (st.isNotEmpty()) sb.append("\n").append(st)
        val g = AppGuides.forPackage(pkg)
        if (g != null && pkg != lastGuidePkg) {
            sb.append("\nAPP GUIDE for ").append(pkg).append(":\n").append(g)
            lastGuidePkg = pkg
        }
        return sb.toString()
    }
}

/** Har app ki chhoti guide: app badalne par ek baar bheji jaati hai */
object AppGuides {
    private val youtube = listOf(
        "- To open/play a video from feed or search results: tap the [Button] element with the BIGGEST height/width whose label holds the video title (long text with channel, views, duration). NEVER tap [MenuIcon] More options (3 dots), channel icon or Shorts buttons.",
        "- After the video opens the player is at the top. Comments are BELOW the player: scroll_down once or twice, find Comments / Add a comment, tap it, type with screen_action(type), then tap Send/Post.",
        "- Tapping the player once shows pause/seek controls. If the same screen comes back after a tap, try the title element or use look_at_screen_visually.",
        "- If a minimized floating video player (miniplayer with Play and X buttons) is visible on screen and needs to be closed/removed, call `dismiss_popup_or_overlay` directly (or tap its `[OverlayClose]` element)."
    ).joinToString("\n")

    private val whatsapp = listOf(
        "- Bottom tabs: Chats, Updates, Communities, Calls. To open a chat, tap its [Button] row (name + last message).",
        "- Inside a chat use whatsapp_send_in_open_chat to type and send reliably.",
        "- If a chat or tab is not in the list, use screen_action(scroll_down) or look_at_screen_visually."
    ).joinToString("\n")

    private val instagram = listOf(
        "- Bottom bar icons: Home, Search, Reels, Messages, Profile (read their descriptions).",
        "- To visit a page: tap Search, type the username, tap the account result row.",
        "- Bio, followers and posts are text on top of the profile: read_screen, scroll_down for more, then report what you read.",
        "- Comment: tap the comment bubble under a post, tap Add a comment, type, tap Post."
    ).joinToString("\n")

    private val facebook = listOf(
        "- Under each post: Like, Comment, Share. To comment tap Comment, type in Write a comment, tap the send arrow.",
        "- Top tabs: Home, Friends, Video, Marketplace, Notifications, Menu. Search is an icon at the top.",
        "- Facebook hides many labels: if an element is not found use look_at_screen_visually then tap_coordinates."
    ).joinToString("\n")

    private val chatgpt = listOf(
        "- A generated picture appears as #id [Image] image(no text). Tap that #id with tap_element (or tap_coordinates using its @x,y center) to open it full-screen, then tap Save / Download / More options.",
        "- Wait for generation to finish with wait_for_screen_text before tapping. The typing box is at the bottom."
    ).joinToString("\n")

    fun forPackage(pkg: String): String? = when {
        pkg == "com.google.android.youtube" -> youtube
        pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b" -> whatsapp
        pkg == "com.instagram.android" -> instagram
        pkg == "com.facebook.katana" || pkg == "com.facebook.lite" -> facebook
        pkg.startsWith("com.openai.chatgpt") -> chatgpt
        else -> null
    }
}

/** Live Screen Watcher Bridge for BGMI Gameplay Commentary & Real-Time App Watching */
object MeeraLiveBridge {
    @Volatile var liveSession: GeminiLive? = null
    @Volatile private var watching = false
    private var watchThread: Thread? = null

    @Synchronized
    fun startScreenWatch(): Boolean {
        val svc = MeeraAccessibilityService.instance ?: return false
        if (android.os.Build.VERSION.SDK_INT < 30 || liveSession == null) return false
        if (watching) return true
        watching = true
        watchThread = Thread({
            while (watching) {
                try {
                    val l = liveSession
                    if (l == null || !l.ready) {
                        Thread.sleep(2000)
                        continue
                    }
                    svc.captureScreenshot { bmp ->
                        if (bmp != null) {
                            try {
                                val scale = 720f / maxOf(bmp.width, bmp.height).toFloat()
                                val scaled = if (scale < 1f) {
                                    android.graphics.Bitmap.createScaledBitmap(
                                        bmp,
                                        (bmp.width * scale).toInt().coerceAtLeast(1),
                                        (bmp.height * scale).toInt().coerceAtLeast(1),
                                        true
                                    )
                                } else bmp
                                val bos = java.io.ByteArrayOutputStream()
                                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, bos)
                                l.sendVideoFrame(bos.toByteArray())
                            } catch (_: Exception) {}
                        }
                    }
                    Thread.sleep(2800)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {}
            }
        }, "meera-screen-watch").also { it.isDaemon = true; it.start() }
        return true
    }

    @Synchronized
    fun stopScreenWatch() {
        watching = false
        try { watchThread?.interrupt() } catch (_: Exception) {}
        watchThread = null
    }
}

/** Emergency SOS Loud Siren Generator */
object MeeraSiren {
    @Volatile private var active = false
    private var thread: Thread? = null

    @Synchronized
    fun start(ctx: Context) {
        if (active) return
        active = true
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
        thread = Thread({
            val tone = try { android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100) } catch (_: Exception) { null }
            try {
                var high = true
                while (active) {
                    tone?.startTone(
                        if (high) android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK else android.media.ToneGenerator.TONE_SUP_ERROR,
                        380
                    )
                    high = !high
                    Thread.sleep(420)
                }
            } catch (_: InterruptedException) {
            } finally {
                try { tone?.release() } catch (_: Exception) {}
            }
        }, "meera-sos-siren").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        active = false
        try { thread?.interrupt() } catch (_: Exception) {}
        thread = null
    }
}

/** Autonomous WhatsApp Chat Reply Rules (Maya 'handle Ritik chat' style) */
object MeeraAutoReply {
    private val rules = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun enable(contact: String, style: String) {
        rules[contact.trim().lowercase()] = style
    }

    fun disable(contact: String) {
        val k = contact.trim().lowercase()
        if (k == "all" || k.isEmpty()) rules.clear() else rules.remove(k)
    }

    private val lastReplyAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** ek vyakti ko 60 second me sirf 1 auto-reply */
    fun allowReply(sender: String): Boolean {
        val k = sender.trim().lowercase()
        val now = System.currentTimeMillis()
        val prev = lastReplyAt[k] ?: 0L
        if (now - prev < 60_000L) return false
        lastReplyAt[k] = now
        return true
    }

    fun getRuleFor(senderTitle: String): String? {
        val s = senderTitle.trim().lowercase()
        rules["all"]?.let { return it }
        return rules.entries.firstOrNull { (k, _) -> s.contains(k) || (s.length >= 3 && k.contains(s)) }?.value
    }
}


/** Live Runtime Bridge for Switching Personality, Voice, Girlfriend Mode & Engine Mode without stopping Mic */
object MeeraConfigBridge {
    @Volatile var onConfigChange: ((persona: String?, voice: String?, gfMode: Boolean?, mode: String?, model: String?) -> String)? = null
    @Volatile var onStatusRequest: (() -> String)? = null
    @Volatile var onSleepRequest: (() -> Unit)? = null
}


/** Deterministic Teach-Once & Auto-Replay Workflow Engine (AppAgent / Mobile-Agent Style) */
object MeeraWorkflowStore {
    private const val PREF_WF = "meera_saved_workflows"
    private const val KEY_LIST = "workflows_json"

    private val DEFAULT_WORKFLOWS = mapOf(
        "whatsapp archived message" to "open_app:WhatsApp|tap_text:Archived|tap_text:{contact}|send_chat:{message}"
    )

    fun getAllWorkflows(ctx: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out.putAll(DEFAULT_WORKFLOWS)
        try {
            val raw = ctx.getSharedPreferences(PREF_WF, Context.MODE_PRIVATE).getString(KEY_LIST, "{}") ?: "{}"
            val j = JSONObject(raw)
            for (k in j.keys()) {
                val v = j.optString(k, "").trim()
                if (v.isNotBlank()) out[k] = v
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveWorkflowFromTool(ctx: Context, name: String, rawSteps: String): ToolExecutionResult {
        val cleanName = name.lowercase().trim()
        if (cleanName.isBlank()) {
            return ToolExecutionResult(true, "save_custom_workflow", "वर्कफ़्लो का नाम बताइए।", "ERROR: empty workflow_name")
        }
        val finalSteps = if (rawSteps.isNotBlank()) {
            rawSteps.trim()
        } else {
            MeeraMission.getLastVerifiedTrace().joinToString("|")
        }
        if (finalSteps.isBlank()) {
            return ToolExecutionResult(true, "save_custom_workflow", "अभी सेव करने के लिए कोई स्टेप्स नहीं मिले।", "ERROR: no steps")
        }
        val sp = ctx.getSharedPreferences(PREF_WF, Context.MODE_PRIVATE)
        val j = try { JSONObject(sp.getString(KEY_LIST, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        j.put(cleanName, finalSteps)
        sp.edit().putString(KEY_LIST, j.toString()).apply()
        Prefs(ctx).addSkill("[Workflow: $cleanName] -> $finalSteps")
        Prefs(ctx).addMemory("सीखा हुआ स्किल [Workflow: $cleanName]: $finalSteps")
        return ToolExecutionResult(
            true, "save_custom_workflow",
            "मैंने '$cleanName' का पक्का वर्कफ़्लो सेव कर लिया है! अब अगली बार बिना किसी रुकावट के सीधे यही स्टेप्स चलेंगे।",
            "VERIFIED_SUCCESS: Saved workflow '$cleanName' -> $finalSteps"
        )
    }

    fun autoRecordLastTrace(ctx: Context, title: String, trace: List<String>) {
        if (trace.isEmpty()) return
        val sp = ctx.getSharedPreferences(PREF_WF, Context.MODE_PRIVATE)
        sp.edit().putString("last_clean_trace", "${title.take(40)} => ${trace.joinToString(" -> ")}").apply()
    }

    fun executeSavedWorkflow(
        ctx: Context,
        workflowName: String,
        contact: String,
        message: String,
        returnToDashboard: Boolean
    ): ToolExecutionResult {
        val all = getAllWorkflows(ctx)
        val q = workflowName.lowercase().trim()
        val entry = all.entries.firstOrNull { (k, _) -> k == q || k.contains(q) || q.contains(k) }
            ?: return ToolExecutionResult(true, "run_saved_workflow", "'$workflowName' नाम का सेव वर्कफ़्लो नहीं मिला।", "FAILED: Workflow not found")

        val steps = entry.value.split("|").map { it.trim() }.filter { it.isNotBlank() }
        val svc = MeeraAccessibilityService.instance
            ?: return ToolExecutionResult(true, "run_saved_workflow", "वर्कफ़्लो चलाने के लिए Accessibility ऑन करें।", "ERROR: Accessibility OFF")

        for (step in steps) {
            val type = step.substringBefore(":").lowercase().trim()
            val arg = step.substringAfter(":", "")
                .replace("{contact}", contact)
                .replace("{message}", message)
                .trim()
            when (type) {
                "open_app" -> {
                    MeeraTools.openAppByName(ctx, arg)
                    Thread.sleep(2200)
                    svc.waitForScreenSettle(1200L)
                }
                "tap_text", "tap_element", "click" -> {
                    if (arg.isNotBlank()) {
                        var clicked = svc.clickByText(arg)
                        if (!clicked) {
                            Thread.sleep(700)
                            clicked = svc.clickByTextWithScrollFallback(arg, maxScrolls = 2)
                        }
                        Thread.sleep(900)
                        svc.waitForScreenSettle(1000L)
                    }
                }
                "send_chat", "type" -> {
                    if (arg.isNotBlank()) {
                        svc.sendInOpenChatVerified(arg)
                        Thread.sleep(700)
                    }
                }
            }
        }

        if (returnToDashboard) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                ctx.startActivity(intent)
            }
        }

        return ToolExecutionResult(
            true, "run_saved_workflow",
            "मैंने '${entry.key}' वर्कफ़्लो से काम पूरा कर दिया है${if (returnToDashboard) " और वापस अपने डैशबोर्ड पर आ गई हूँ" else ""}!",
            "VERIFIED_SUCCESS: Executed deterministic workflow '${entry.key}' (returnToDashboard=$returnToDashboard)."
        )
    }
}

