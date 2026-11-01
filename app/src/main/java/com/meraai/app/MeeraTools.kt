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

data class ToolExecutionResult(
    val executed: Boolean,
    val toolName: String = "",
    val spokenReply: String = "",
    val rawDataForAi: String = ""
)

object MeeraTools {

    // आम बोलचाल के हिंदी/इंग्लिश ऐप नामों की स्मार्ट मैपिंग
    private val KNOWN_APPS = mapOf(
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "व्हाट्सएप" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "व्हाट्सऐप" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "youtube" to listOf("com.google.android.youtube", "app.revanced.android.youtube"),
        "यूट्यूब" to listOf("com.google.android.youtube"),
        "instagram" to listOf("com.instagram.android"),
        "इंस्टाग्राम" to listOf("com.instagram.android"),
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
        "paytm" to listOf("net.one97.paytm"),
        "phonepe" to listOf("com.phonepe.app"),
        "gpay" to listOf("com.google.android.apps.nbu.paisa.user"),
        "google pay" to listOf("com.google.android.apps.nbu.paisa.user")
    )

    /** Gemini Live API और REST API के लिए ऑफिशियल Function Declarations */
    fun getGeminiToolsJson(): JSONArray {
        val funcs = JSONArray()

        funcs.put(
            JSONObject()
                .put("name", "open_app")
                .put("description", "Open any installed mobile application on the user's Android phone such as WhatsApp, YouTube, Instagram, Chrome, Camera, Settings, Gallery, Calculator, etc.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("app_name", JSONObject().put("type", "STRING").put("description", "Name of the app to open, e.g., WhatsApp, YouTube, Instagram, Settings"))
                        )
                        .put("required", JSONArray().put("app_name"))
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "read_screen")
                .put("description", "Read all text, messages, buttons, and content currently visible on the user's phone screen using Accessibility service.")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        )

        funcs.put(
            JSONObject()
                .put("name", "screen_action")
                .put("description", "Perform an action on the screen: click a button/text, type text into an input field, scroll up/down, or navigate home/back.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("action_type", JSONObject().put("type", "STRING").put("description", "One of: click, type, home, back, scroll_down, scroll_up, notifications"))
                                .put("target_or_text", JSONObject().put("type", "STRING").put("description", "Button/contact text to click, or message text to type"))
                        )
                        .put("required", JSONArray().put("action_type"))
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "whatsapp_message")
                .put("description", "Send a WhatsApp message or open a WhatsApp chat with a contact name or phone number.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("contact_name", JSONObject().put("type", "STRING").put("description", "Name or phone number of the person"))
                                .put("message", JSONObject().put("type", "STRING").put("description", "Message text to send"))
                        )
                        .put("required", JSONArray().put("contact_name"))
                )
        )

        funcs.put(
            JSONObject()
                .put("name", "make_phone_call")
                .put("description", "Place a phone call to a contact name or phone number.")
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
                .put("name", "play_or_search")
                .put("description", "Play a song/video on YouTube or search something on Google.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties", JSONObject()
                                .put("query", JSONObject().put("type", "STRING").put("description", "Song name, video topic, or search query"))
                                .put("platform", JSONObject().put("type", "STRING").put("description", "youtube or google"))
                        )
                        .put("required", JSONArray().put("query"))
                )
        )

        return JSONArray().put(JSONObject().put("functionDeclarations", funcs))
    }

    /** Gemini से आए किसी भी Function Call को एग्जीक्यूट करना */
    fun executeFunctionCall(ctx: Context, name: String, args: JSONObject): ToolExecutionResult {
        return when (name) {
            "open_app" -> {
                val appName = args.optString("app_name", "").trim()
                val ok = openAppByName(ctx, appName)
                if (ok) ToolExecutionResult(true, name, "$appName खोल दिया है! बताओ इसमें आगे क्या करना है?", "SUCCESS: Opened app $appName")
                else ToolExecutionResult(true, name, "फोन में $appName नाम का ऐप नहीं मिला।", "ERROR: App $appName not found on device")
            }
            "read_screen" -> {
                val svc = MeeraAccessibilityService.instance
                if (svc == null) {
                    ToolExecutionResult(
                        true, name,
                        "स्क्रीन पढ़ने के लिए पहले Accessibility सर्विस ऑन करनी होगी।",
                        "ERROR: Accessibility service is OFF. Ask user to enable Meera AI in Accessibility Settings."
                    )
                } else {
                    val content = svc.readScreenText()
                    val summary = if (content.isBlank()) "स्क्रीन पर अभी कोई टेक्स्ट नहीं दिख रहा है।" else content
                    ToolExecutionResult(true, name, "", "CURRENT SCREEN CONTENT:\n$summary")
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
            "whatsapp_message" -> {
                val person = args.optString("contact_name", "").trim()
                val msg = args.optString("message", "").trim()
                sendWhatsAppToPerson(ctx, person, msg)
            }
            "make_phone_call" -> {
                val person = args.optString("contact_name", "").trim()
                callPerson(ctx, person)
            }
            "play_or_search" -> {
                val q = args.optString("query", "").trim()
                val platform = args.optString("platform", "youtube").lowercase()
                playOrSearchMedia(ctx, q, platform)
            }
            else -> ToolExecutionResult(false)
        }
    }

    /**
     * लोकल फास्ट कमांड राउटर (Modular/Chat मोड में बिना रुके तुरंत ऐप खोलने या स्क्रीन पढ़ने के लिए)
     */
    fun tryDirectVoiceCommand(ctx: Context, userText: String): ToolExecutionResult {
        val raw = userText.trim()
        val low = raw.lowercase()

        // 1. स्क्रीन पढ़ने या नोटिफिकेशन पढ़ने की कमांड
        if (low.contains("स्क्रॉल डाउन") || low.contains("नीचे स्क्रॉल") || low.contains("नीचे करो") || low.contains("scroll down")) {
            val ok = MeeraAccessibilityService.instance?.performSystemAction("scroll_down") == true
            return ToolExecutionResult(true, "screen_action", if (ok) "नीचे स्क्रॉल कर दिया है। और नीचे करूँ या कुछ खोलना है?" else "स्क्रॉल करने के लिए Accessibility ऑन करें।", "SUCCESS: Scrolled Down")
        }
        if (low.contains("स्क्रॉल अप") || low.contains("ऊपर स्क्रॉल") || low.contains("ऊपर करो") || low.contains("scroll up")) {
            val ok = MeeraAccessibilityService.instance?.performSystemAction("scroll_up") == true
            return ToolExecutionResult(true, "screen_action", if (ok) "ऊपर स्क्रॉल कर दिया है। आगे बताओ क्या करना है?" else "Accessibility ऑन करें।", "SUCCESS: Scrolled Up")
        }
        if (low == "बैक करो" || low == "पीछे जाओ" || low == "वापस आओ" || low == "go back" || low == "back") {
            val ok = MeeraAccessibilityService.instance?.performSystemAction("back") == true
            return ToolExecutionResult(true, "screen_action", if (ok) "पीछे आ गई। अब बताओ क्या करें?" else "Accessibility ऑन करें।", "SUCCESS: Back")
        }
        if (low.contains("स्क्रीन पढ़") || low.contains("screen padh") || low.contains("स्क्रीन पर क्या") ||
            low.contains("screen par kya") || low.contains("read screen")
        ) {
            return executeFunctionCall(ctx, "read_screen", JSONObject())
        }

        if (low.contains("नोटिफिकेशन") || low.contains("notification") || low.contains("किसका मैसेज आया")) {
            val notifSummary = MeeraNotificationService.getLatestSummary()
            return ToolExecutionResult(true, "read_notifications", "", "RECENT NOTIFICATIONS:\n$notifSummary")
        }

        // 2. होम स्क्रीन या बैक जाने की कमांड
        if (low == "होम स्क्रीन पे आओ" || low == "होम स्क्रीन पर आओ" || low == "go home" || low == "home screen") {
            val svc = MeeraAccessibilityService.instance
            if (svc != null && svc.performSystemAction("home")) {
                return ToolExecutionResult(true, "screen_action", "आ गई होम स्क्रीन पर, बताइए अब क्या करना है?", "SUCCESS: Navigated Home")
            }
        }

        // 3. YouTube पर गाना/वीडियो चलाने की कमांड
        val ytMatch = Regex("(?:youtube\\s*(?:पर|पे|par|pe|me)|यूट्यूब\\s*(?:पर|पे))\\s+(.+?)(?:\\s+(?:चलाओ|चला दो|प्ले करो|खोलो|लगाओ|play|search))?$", RegexOption.IGNORE_CASE).find(raw)
        if (ytMatch != null) {
            val song = ytMatch.groupValues[1].trim()
            if (song.isNotBlank()) return playOrSearchMedia(ctx, song, "youtube")
        }

        // 4. WhatsApp मैसेज कमांड (जैसे: "लक्ष्मण को व्हाट्सएप पर मैसेज भेजो कि...")
        val waMatch = Regex("(.+?)\\s+को\\s+(?:whatsapp|व्हाट्सएप|व्हाट्सऐप).*?(?:मैसेज|message)\\s+(?:भेजो|करो|लिखो)(?:\\s+कि|\\s+ki)?\\s*(.*)", RegexOption.IGNORE_CASE).find(raw)
        if (waMatch != null) {
            val person = waMatch.groupValues[1].replace(Regex("^(?:मीरा|अरे|सुनो|एक काम करो)\\s*"), "").trim()
            val msg = waMatch.groupValues[2].trim()
            return sendWhatsAppToPerson(ctx, person, msg)
        }

        // 5. कॉल लगाने की कमांड (जैसे: "लक्ष्मण को कॉल लगाओ / फोन करो")
        val callMatch = Regex("(.+?)\\s+को\\s+(?:कॉल|फोन|call|phone)\\s+(?:लगाओ|करो|मिलाओ|लगा दो)", RegexOption.IGNORE_CASE).find(raw)
        if (callMatch != null) {
            val person = callMatch.groupValues[1].replace(Regex("^(?:मीरा|अरे|सुनो)\\s*"), "").trim()
            return callPerson(ctx, person)
        }

        // 6. कोई भी ऐप खोलने की कमांड (जैसे: "WhatsApp ओपन करो", "यूट्यूब खोलो", "open instagram")
        val openPatterns = listOf(
            Regex("^(?:मीरा|अरे|सुनो|प्लीज)?\\s*(.+?)\\s+(?:ओपन करो|ओपन कर दो|खोलो|खोल दो|चालू करो|open karo|open kar do|kholo|khol do)$", RegexOption.IGNORE_CASE),
            Regex("^(?:open|launch)\\s+(.+)$", RegexOption.IGNORE_CASE)
        )
        for (pat in openPatterns) {
            val m = pat.find(raw)
            if (m != null) {
                val appCandidate = m.groupValues[1]
                    .replace(Regex("(?:ऐप|app|एप्लीकेशन|application)$", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (appCandidate.length in 2..30 && openAppByName(ctx, appCandidate)) {
                    return ToolExecutionResult(true, "open_app", "$appCandidate खोल दिया है।", "SUCCESS: Opened $appCandidate")
                }
            }
        }

        return ToolExecutionResult(false)
    }

    /** फोन में इंस्टॉल किसी भी ऐप को उसके नाम (हिंदी या इंग्लिश) से खोलना */
    fun openAppByName(ctx: Context, rawName: String): Boolean {
        val clean = rawName.lowercase().replace("ऐप", "").replace("app", "").trim()
        if (clean.isBlank()) return false

        // स्पेशल सिस्टम स्क्रीन
        if (clean == "settings" || clean == "सेटिंग" || clean == "सेटिंग्स") {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }

        val pm = ctx.packageManager

        // 1. पहले Known Apps मैप में चेक करें
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

        // 2. फोन के सारे Launcher Apps के नामों में मैच ढूँढें
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(mainIntent, 0)
            for (info in resolved) {
                val label = info.loadLabel(pm).toString().lowercase()
                val pkg = info.activityInfo.packageName.lowercase()
                if (label == clean || label.contains(clean) || clean.contains(label) || pkg.contains(clean)) {
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

    /** कॉन्टैक्ट्स में से नाम के आधार पर मोबाइल नंबर ढूँढना */
    fun findContactNumber(ctx: Context, nameQuery: String): Pair<String, String>? {
        val hasPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm || nameQuery.isBlank()) return null
        val q = nameQuery.trim().lowercase()
        var cursor: Cursor? = null
        try {
            cursor = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )
            if (cursor != null) {
                var bestMatch: Pair<String, String>? = null
                while (cursor.moveToNext()) {
                    val dName = cursor.getString(0) ?: continue
                    val num = cursor.getString(1) ?: continue
                    if (dName.equals(q, ignoreCase = true)) return dName to num
                    if (dName.lowercase().contains(q) || q.contains(dName.lowercase())) {
                        bestMatch = dName to num
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

    private fun sendWhatsAppToPerson(ctx: Context, person: String, message: String): ToolExecutionResult {
        val contact = findContactNumber(ctx, person)
        val rawDigits = (contact?.second ?: person).replace(Regex("[^0-9+]"), "")
        val phone = when {
            rawDigits.length == 10 -> "91$rawDigits"
            rawDigits.startsWith("+") -> rawDigits.substring(1)
            else -> rawDigits
        }

        return try {
            if (phone.length >= 10) {
                val encoded = URLEncoder.encode(message, "UTF-8")
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone" + if (message.isNotBlank()) "&text=$encoded" else "")
                val i = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(i)
                // अगर Accessibility चालू है और मैसेज दिया गया है, तो 1.2s बाद Send बटन भी दबाने की कोशिश करें
                if (message.isNotBlank()) {
                    Thread {
                        try {
                            Thread.sleep(1400)
                            MeeraAccessibilityService.instance?.let { svc ->
                                svc.clickByText("Send") || svc.clickByText("भेजें")
                            }
                        } catch (_: Exception) {}
                    }.start()
                }
                val targetName = contact?.first ?: person
                ToolExecutionResult(true, "whatsapp_message", "$targetName की WhatsApp चैट खोल दी है।", "SUCCESS: Opened WhatsApp chat for $targetName")
            } else {
                // अगर कॉन्टैक्ट नंबर नहीं मिला, तो सीधे WhatsApp खोलें
                openAppByName(ctx, "WhatsApp")
                ToolExecutionResult(
                    true, "whatsapp_message",
                    "कॉन्टैक्ट्स में '$person' का नंबर नहीं मिला, इसलिए मैंने WhatsApp खोल दिया है।",
                    "Opened WhatsApp (contact '$person' not found in phonebook)"
                )
            }
        } catch (_: Exception) {
            openAppByName(ctx, "WhatsApp")
            ToolExecutionResult(true, "whatsapp_message", "WhatsApp खोल दिया है।", "Opened WhatsApp")
        }
    }

    private fun callPerson(ctx: Context, person: String): ToolExecutionResult {
        val contact = findContactNumber(ctx, person)
        val number = contact?.second ?: person.filter { it.isDigit() || it == '+' }
        if (number.length < 3) {
            return ToolExecutionResult(true, "make_phone_call", "कॉन्टैक्ट्स में '$person' का नंबर नहीं मिला।", "ERROR: Contact $person not found")
        }
        val canDirectCall = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (canDirectCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return try {
            val intent = Intent(action, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            val label = contact?.first ?: person
            ToolExecutionResult(true, "make_phone_call", "$label को कॉल लगा रही हूँ।", "SUCCESS: Calling $label ($number)")
        } catch (e: Exception) {
            ToolExecutionResult(true, "make_phone_call", "कॉल लगाने में दिक्कत आई।", "ERROR: ${e.message}")
        }
    }

    private fun playOrSearchMedia(ctx: Context, query: String, platform: String): ToolExecutionResult {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val uri = if (platform.contains("google")) {
                Uri.parse("https://www.google.com/search?q=$encoded")
            } else {
                Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            }
            val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            ToolExecutionResult(true, "play_or_search", "YouTube पर '$query' लगा दिया है! सुनिए, और कुछ काम हो तो बताइए?", "SUCCESS: Opened $platform for $query")
        } catch (_: Exception) {
            ToolExecutionResult(false)
        }
    }
}
