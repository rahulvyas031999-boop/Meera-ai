package com.meraai.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import java.util.concurrent.CopyOnWriteArrayList

data class NotifItem(val pkg: String, val title: String, val text: String, val time: Long)
data class NotifReplyAction(
    val pkg: String,
    val title: String,
    val text: String,
    val actionIntent: android.app.PendingIntent,
    val remoteInputs: Array<android.app.RemoteInput>,
    val time: Long
)
data class ScreenElement(val id: Int, val label: String, val kind: String, val bounds: Rect)

class MeeraNotificationService : NotificationListenerService() {
    companion object {
        val recentNotifs = CopyOnWriteArrayList<NotifItem>()
        private val replyActions = java.util.concurrent.ConcurrentHashMap<String, NotifReplyAction>()
        @Volatile var lastAnnouncedTime = 0L
        @Volatile var lastNotifIsGroup = false
        @Volatile private var lastNotifKey = ""
        @Volatile private var lastSelfReplyText = ""
        @Volatile var onIncomingMessage: ((String, String, String) -> Unit)? = null

        fun getLatestSummary(): String {
            if (recentNotifs.isEmpty()) return "Abhi koi naya notification nahi hai."
            return recentNotifs.takeLast(8).reversed().joinToString("\n") { "• [${it.title}]: ${it.text}" }
        }

        fun getFreshUnreadAlert(): String {
            val latest = recentNotifs.lastOrNull() ?: return ""
            if (System.currentTimeMillis() - latest.time > 45_000L || latest.time <= lastAnnouncedTime) return ""
            lastAnnouncedTime = latest.time
            return "\n[NEW INCOMING NOTIFICATION just arrived from '${latest.title}': \"${latest.text}\". If appropriate, inform the user and ask if they want to reply using reply_whatsapp_notification.]"
        }

        fun replyToNotification(ctx: Context, senderQuery: String, replyText: String): Pair<Boolean, String> {
            val q = senderQuery.trim().lowercase()
            val target = if (q.isEmpty() || q == "latest" || q == "last" || q.contains("आखिरी") || q.contains("अभी")) {
                replyActions["latest"]
            } else {
                replyActions.entries.firstOrNull { (k, v) ->
                    k != "latest" && (k.contains(q) || q.contains(k) || v.title.lowercase().contains(q))
                }?.value
            } ?: return false to "Is naam ka koi active notification reply button nahi mila."

            return try {
                val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                val bundle = Bundle()
                for (ri in target.remoteInputs) {
                    bundle.putCharSequence(ri.resultKey, replyText)
                }
                lastSelfReplyText = replyText.trim().lowercase()
                android.app.RemoteInput.addResultsToIntent(target.remoteInputs, fillIn, bundle)
                target.actionIntent.send(ctx.applicationContext, 0, fillIn)
                true to target.title
            } catch (e: Exception) {
                false to (e.message ?: "Reply intent fail hua")
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence("android.title")?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence("android.text")?.toString()?.trim().orEmpty()
        val lowText = text.lowercase()
        if (lowText.contains("checking for new messages") || lowText.contains("messages from")) return

        if (title.isNotBlank() || text.isNotBlank()) {
            recentNotifs.add(NotifItem(pkg, title, text, System.currentTimeMillis()))
            while (recentNotifs.size > 25) recentNotifs.removeAt(0)

            val actions = n.actions
            if (actions != null) {
                for (act in actions) {
                    val ris = act.remoteInputs
                    val pi = act.actionIntent
                    if (!ris.isNullOrEmpty() && pi != null) {
                        val item = NotifReplyAction(pkg, title, text, pi, ris, System.currentTimeMillis())
                        replyActions["latest"] = item
                        if (title.isNotBlank()) replyActions[title.lowercase()] = item
                        val notifKey = "$pkg|$title|$text"
                        val isSelfEcho = lastSelfReplyText.isNotEmpty() && text.trim().lowercase() == lastSelfReplyText
                        if (pkg.contains("whatsapp") && notifKey != lastNotifKey && !isSelfEcho) {
                            lastNotifKey = notifKey
                            lastNotifIsGroup = ex.getBoolean("android.isGroupConversation", false)
                            onIncomingMessage?.invoke(pkg, title, text)
                        }
                        break
                    }
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}

class MeeraAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: MeeraAccessibilityService? = null
            private set
        @Volatile var currentPackage: String = ""
            private set
    }

    private val lastElements = CopyOnWriteArrayList<ScreenElement>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    fun waitForScreenSettle(timeoutMs: Long = 1200L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastFingerprint = ""
        var stableCount = 0
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            val currentFingerprint = root?.let { readScreenElements(15, updateCache = false) } ?: ""
            if (currentFingerprint == lastFingerprint) {
                stableCount++
                if (stableCount >= 2) return true
            } else {
                stableCount = 0
                lastFingerprint = currentFingerprint
            }
            try { Thread.sleep(250) } catch (_: Exception) { break }
        }
        return false
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    fun captureScreenshot(callback: (android.graphics.Bitmap?) -> Unit) {
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, java.util.concurrent.Executors.newSingleThreadExecutor(), object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                screenshot.hardwareBuffer.close()
                callback(bitmap)
            }
            override fun onFailure(errorCode: Int) { callback(null) }
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { if (it.isNotBlank()) currentPackage = it }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun getActivePackage(): String {
        val rootPkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        return if (rootPkg.isNotBlank()) rootPkg else currentPackage
    }

    /** Maya UN.java style: Read screen elements with #id numbers */
    fun readScreenElements(maxItems: Int = 200, updateCache: Boolean = true): String {
        val root = rootInActiveWindow ?: return ""
        val pkg = root.packageName?.toString().orEmpty()
        val rawList = ArrayList<ScreenElement>()
        collectElements(root, rawList, maxItems)
        val truncated = rawList.size >= maxItems
        val sorted = rawList
            .sortedWith(compareBy({ it.bounds.top / 40 }, { it.bounds.left }))
            .mapIndexed { i, e -> e.copy(id = i + 1) }
        if (updateCache) {
            lastElements.clear()
            lastElements.addAll(sorted)
        }
        if (sorted.isEmpty()) return ""
        val dm = resources.displayMetrics
        val note = if (truncated)
            "\n[NOTE: list cut at $maxItems items, more elements may exist. Scroll or use look_at_screen_visually if something is missing.]"
        else ""
        return "Active App: $pkg\nScreen: ${dm.widthPixels}x${dm.heightPixels} px\n" +
            sorted.joinToString("\n") {
                "#${it.id} [${it.kind}] ${it.label} @${it.bounds.centerX()},${it.bounds.centerY()} ${it.bounds.width()}x${it.bounds.height()}"
            } + note
    }

    fun readScreenText(maxItems: Int = 200): String = readScreenElements(maxItems)

    /** Bina text wali badi image (jaise ChatGPT ki banayi image, full-screen background ko chhodkar) */
    private fun isBigImage(node: AccessibilityNodeInfo): Boolean {
        if (node.childCount > 0) return false
        val r = Rect()
        node.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        val notFullScreen = r.height() < (dm.heightPixels * 0.85).toInt()
        return r.width() >= 280 && r.height() >= 280 && notFullScreen
    }

    private fun isMenuDots(label: String, resId: String): Boolean {
        val low = label.lowercase()
        return low == "more options" || low.contains("अधिक विकल्प") || resId.contains("overflow") || resId.contains("contextual_menu")
    }

    private fun collectElements(node: AccessibilityNodeInfo?, out: MutableList<ScreenElement>, limit: Int) {
        if (node == null || out.size >= limit) return
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim().orEmpty() else ""
            val resId = node.viewIdResourceName?.substringAfterLast("/")?.trim().orEmpty()
            val label = when {
                t.isNotBlank() && d.isNotBlank() && !t.equals(d, true) -> "$t ($d)"
                t.isNotBlank() -> t
                d.isNotBlank() -> d
                hint.isNotBlank() -> hint
                node.isClickable && resId.isNotBlank() -> "icon:$resId"
                node.isClickable -> "clickable_area"
                else -> if (isBigImage(node)) "image(no text)" else ""
            }
            if (label.length in 1..180) {
                val kind = when {
                    label == "image(no text)" -> "Image"
                    node.isEditable -> "Input"
                    isMenuDots(label, resId) -> "MenuIcon"
                    node.isClickable || node.parent?.isClickable == true -> "Button"
                    else -> "Text"
                }
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val duplicate = out.any { it.label.equals(label, ignoreCase = true) && it.kind == kind && (it.bounds.centerY() - rect.centerY()).let { dy -> dy in -20..20 } }
                if (!duplicate && rect.width() > 10 && rect.height() > 10) {
                    out.add(ScreenElement(out.size + 1, label, kind, rect))
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (out.size >= limit) break
            collectElements(node.getChild(i), out, limit)
        }
    }

    /** Tap an element by its #id */
    fun tapElementById(id: Int): Boolean {
        if (lastElements.isEmpty()) readScreenElements()
        val el = lastElements.find { it.id == id } ?: return false
        if (!el.bounds.isEmpty) {
            return tapCoordinates(el.bounds.exactCenterX(), el.bounds.exactCenterY())
        }
        return clickByText(el.label)
    }

    // SAFETY: पेमेंट ऐप्स में Meera के प्रोग्रामैटिक क्लिक/टैप बंद
    private val blockedPayPackages = setOf(
        "net.one97.paytm",
        "com.phonepe.app",
        "com.google.android.apps.nbu.paisa.user",
        "in.org.npci.upiapp",
        "com.dreamplug.androidapp"
    )

    private fun isPaymentAppActive(): Boolean {
        val pkg = getActivePackage()
        val blocked = pkg in blockedPayPackages
        if (blocked) MeeraDebugLog.log("SAFETY", "Click blocked in payment app: $pkg")
        return blocked
    }

    fun tapCoordinates(x: Float, y: Float): Boolean {
        if (isPaymentAppActive()) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 95)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Tap center of screen (used for tapping generated images in ChatGPT/Gemini to reveal Save/Download bar) */
    /** Wake & Swipe Up Lock Screen (Maya Voice Unlock Style) */
    fun swipeUpUnlockScreen(): Boolean {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels * 0.5f
        val startY = dm.heightPixels * 0.82f
        val endY = dm.heightPixels * 0.18f
        val path = Path().apply { moveTo(cx, startY); lineTo(cx, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 280)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun tapCenterImage(): Boolean {
        val dm = resources.displayMetrics
        return tapCoordinates(dm.widthPixels * 0.5f, dm.heightPixels * 0.44f)
    }

    /** Wait for text or viewId to appear or disappear on screen */
    fun waitForScreenText(query: String, mode: String = "appear", timeoutSeconds: Int = 30): Boolean {
        val target = query.trim()
        if (target.isEmpty()) return true
        val deadline = System.currentTimeMillis() + (timeoutSeconds.coerceIn(1, 600) * 1000L)
        val wantAppear = !mode.equals("disappear", ignoreCase = true)
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            val found = root != null && (findBestNodeByText(root, target) != null)
            if (wantAppear && found) return true
            if (!wantAppear && !found) return true
            try { Thread.sleep(350) } catch (_: Exception) { break }
        }
        return false
    }

    fun hasScreenText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return findBestNodeByText(root, query.trim()) != null
    }

    /** Self-Reflection के लिए स्क्रीन का तेज़ फ़िंगरप्रिंट ताकि पता चले कि एक्शन के बाद स्क्रीन बदली या नहीं */
    fun getScreenSignature(): String {
        val pkg = getActivePackage()
        val snap = readScreenElements(25, updateCache = false)
            .lineSequence()
            .filter { it.startsWith("#") }
            .joinToString("|") { it.substringAfter("]").trim().take(40) }
        return "$pkg::$snap"
    }

    /** Error Recovery Level 1: अचानक आए पॉपअप (Allow, Skip, Not now, Close, Later) को हटाना */
    fun dismissBlockingPopup(): String? {
        val root = rootInActiveWindow ?: return null
        val dismissLabels = listOf(
            "Not now", "Skip", "Close", "Dismiss", "No thanks", "Later", "Got it", "Cancel",
            "अभी नहीं", "छोड़ें", "बंद करें", "हटाएँ", "बाद में", "ठीक है"
        )
        for (label in dismissLabels) {
            val node = findExactNode(root, label, requireClickable = true)
                ?: findExactNode(root, label, requireClickable = false)
            if (node != null && performClickOnNode(node)) {
                try { Thread.sleep(450) } catch (_: Exception) {}
                return label
            }
        }
        return null
    }

    /** आम सेटिंग्स और ऐप ऑप्शन्स के पर्यायवाची (Synonyms) ताकि अलग-अलग मोबाइल में सही ऑप्शन मिले */
    private fun expandQuerySynonyms(query: String): List<String> {
        val q = query.trim()
        val low = q.lowercase()
        val extras = when {
            low.contains("app management") || low.contains("manage apps") || low == "apps" || low.contains("ऐप मैनेजमेंट") ->
                listOf(q, "App management", "Apps", "Manage apps", "App list", "All apps", "Installed apps", "ऐप्स")
            low.contains("search") || low.contains("खोज") || low.contains("सर्च") ->
                listOf(q, "Search", "Search & Explore", "Explore", "Find", "खोजें")
            low.contains("home") || low.contains("main") || low.contains("dashboard") || low.contains("होम") ->
                listOf(q, "Home", "Home tab", "Feed", "होम")
            low.contains("comment") || low.contains("कमेंट") ->
                listOf(q, "Comment", "Comments", "Add a comment", "Write a comment", "कमेंट")
            else -> listOf(q)
        }
        return extras.distinct()
    }

    /** ऊपर बाएँ कोने में दिखने वाला Back / Navigate Up / Close तीर ढूँढकर दबाना */
    fun clickTopBackOrCloseIcon(): Boolean {
        val root = rootInActiveWindow ?: return false
        val backLabels = listOf("Navigate up", "Back", "Close", "Dismiss", "पीछे जाएँ", "वापस जाएँ", "बंद करें")
        for (lbl in backLabels) {
            val n = findExactNode(root, lbl, requireClickable = true) ?: findContainsNode(root, lbl, requireClickable = true)
            if (n != null && performClickOnNode(n)) return true
        }
        // Top-left clickable icon fallback (x < 220, y in 60..280)
        if (lastElements.isEmpty()) readScreenElements(60)
        val topLeftBtn = lastElements.firstOrNull {
            it.kind == "Button" && it.bounds.centerX() < 220 && it.bounds.centerY() in 60..290 && it.bounds.width() < 180
        }
        if (topLeftBtn != null) {
            return tapCoordinates(topLeftBtn.bounds.exactCenterX(), topLeftBtn.bounds.exactCenterY())
        }
        return false
    }

    /**
     * किसी भी ऐप (Instagram, Hotstar, YouTube, WhatsApp, Settings) में पुरानी सब-स्क्रीन/रील/चैट से निकलकर
     * उसके मुख्य डैशबोर्ड (Main Home Screen) तक स्मार्ट तरीके से वापस आना (अधिकतम 4 स्टेप्स, ऐप से बाहर निकले बिना)
     */
    fun navigateToAppMainScreen(wantSearchReady: Boolean = false): String {
        val startPkg = getActivePackage()
        if (startPkg.isBlank()) return "NO_ACTIVE_APP"

        for (step in 1..4) {
            val root = rootInActiveWindow ?: break
            val curPkg = getActivePackage()
            if (curPkg.isNotBlank() && curPkg != startPkg) {
                // अगर गलती से ऐप के बाहर आ गए तो तुरंत उस ऐप को वापस खोलें
                break
            }

            // 1. अगर हम सिर्फ़ Search चाहते हैं और Search पहले से स्क्रीन पर मौजूद है, तो बैक जाने की ज़रूरत नहीं!
            if (wantSearchReady) {
                val hasSearch = findBestNodeByText(root, "Search") != null ||
                    findBestNodeByText(root, "Search & Explore") != null ||
                    findBestNodeByText(root, "खोजें") != null
                if (hasSearch) return "READY_FOR_SEARCH_ON_CURRENT_SCREEN"
            }

            // 2. कोई पॉपअप अटका हो तो पहले उसे बंद करें
            dismissBlockingPopup()

            // 3. अगर नीचे की बार में 'Home' या 'Chats' टैब दिख रहा है तो सीधे उसे टैप करें
            val homeTab = findExactNode(root, "Home", requireClickable = true)
                ?: findExactNode(root, "Home tab", requireClickable = true)
                ?: (if (startPkg.contains("whatsapp")) findExactNode(root, "Chats", requireClickable = true) else null)

            val beforeSig = getScreenSignature()
            if (homeTab != null && performClickOnNode(homeTab)) {
                waitForScreenSettle(900L)
                val afterHomeSig = getScreenSignature()
                if (afterHomeSig != beforeSig) return "REACHED_HOME_VIA_TAB (step $step)"
            }

            // 4. वरना ऊपर का Back/Close आइकॉन दबाएँ या सिस्टम Back करें
            if (!clickTopBackOrCloseIcon()) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            waitForScreenSettle(950L)
            val afterBackSig = getScreenSignature()
            if (afterBackSig == beforeSig) {
                // स्क्रीन अब और पीछे नहीं जा रही यानी हम मुख्य स्क्रीन पर हैं
                return "AT_ROOT_SCREEN"
            }
        }
        return "UNWOUND_TO_MAIN_SCREEN"
    }

    /** Error Recovery Level 2: पर्यायवाची (Synonyms) + 3 बार नीचे स्क्रॉल करके ऑप्शन ढूँढना और टैप करना */
    fun clickByTextWithScrollFallback(query: String, maxScrolls: Int = 3): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        val candidates = expandQuerySynonyms(q)
        for (c in candidates) {
            if (clickByText(c)) return true
        }
        for (i in 0 until maxScrolls) {
            val before = getScreenSignature()
            if (!performSystemAction("scroll_down")) break
            try { Thread.sleep(450) } catch (_: Exception) {}
            for (c in candidates) {
                if (clickByText(c)) return true
            }
            if (getScreenSignature() == before) break // लिस्ट के सबसे नीचे पहुँच गए
        }
        return false
    }

    fun clickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (candidate in expandQuerySynonyms(query)) {
            val target = findBestNodeByText(root, candidate)
            if (target != null && performClickOnNode(target)) return true
        }
        return false
    }

    /** WhatsApp Dedicated Send Button Clicker (by #id com.whatsapp:id/send or exact contentDescription) */
    fun clickWhatsAppSendButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val idCandidates = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/conversation_entry_action_button"
        )
        for (resId in idCandidates) {
            val nodes = root.findAccessibilityNodeInfosByViewId(resId)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    if (n.isVisibleToUser && performClickOnNode(n)) return true
                }
            }
        }
        // Fallback: exact contentDescription "Send" or "भेजें"
        val exactSend = findExactNode(root, "Send") ?: findExactNode(root, "भेजें") ?: findExactNode(root, "Send message")
        if (exactSend != null) return performClickOnNode(exactSend)
        return false
    }

    /** Wait until WhatsApp chat input box or Send button is actually open on screen */
    fun waitForWhatsAppChatOpen(timeoutSeconds: Int = 7): Boolean {
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L)
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg.contains("whatsapp")) {
                    val entry = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")
                        .takeIf { !it.isNullOrEmpty() }
                        ?: root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/entry")
                    val sendBtn = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
                    if (!entry.isNullOrEmpty() || !sendBtn.isNullOrEmpty() || findEditableNode(root) != null) {
                        return true
                    }
                }
            }
            try { Thread.sleep(300) } catch (_: Exception) { break }
        }
        return false
    }

    /** Verified YouTube First Video Clicker: Polls until search results load, skips Shorts/Ads, clicks real video */
    fun clickFirstYoutubeVideoVerified(timeoutMs: Long = 6500L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                val candidates = ArrayList<AccessibilityNodeInfo>()
                collectVideoCandidates(root, candidates)
                for (node in candidates) {
                    if (performClickOnNode(node)) {
                        return true
                    }
                }
            }
            try { Thread.sleep(450) } catch (_: Exception) { break }
        }
        // Smart fallback: find any large clickable row in the middle of YouTube results list
        val root = rootInActiveWindow ?: return false
        val dm = resources.displayMetrics
        val fallbackNode = findLargeClickableInRange(root, (dm.heightPixels * 0.22f).toInt(), (dm.heightPixels * 0.65f).toInt())
        if (fallbackNode != null && performClickOnNode(fallbackNode)) return true
        return false
    }

    fun clickFirstYoutubeVideo(): Boolean = clickFirstYoutubeVideoVerified(5500L)

    private fun collectVideoCandidates(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null || out.size >= 8) return
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            val combined = "$t $d".trim()
            val low = combined.lowercase()

            val isAdOrShort = low.contains("shorts") || low.contains("sponsored") ||
                low.startsWith("search") || low.contains("filter") || viewId.contains("reel") || viewId.contains("chip")

            val hasVideoIndicators = (combined.length >= 12 && (
                low.contains("minutes") || low.contains("seconds") || low.contains("views") ||
                low.contains("ago") || low.contains("watching") || low.contains("मिनट") ||
                low.contains("व्यू") || low.contains("पहले") || combined.contains(":") ||
                viewId.contains("video_info") || viewId.contains("title_wrapper")
            ))

            if (!isAdOrShort && hasVideoIndicators) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top > 210 && r.height() >= 55 && r.width() >= 200) {
                    out.add(node)
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (out.size >= 8) break
            collectVideoCandidates(node.getChild(i), out)
        }
    }

    private fun findLargeClickableInRange(node: AccessibilityNodeInfo?, minY: Int, maxY: Int): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.isClickable) {
            val r = Rect()
            node.getBoundsInScreen(r)
            val d = node.contentDescription?.toString()?.lowercase().orEmpty()
            if (r.top in minY..maxY && r.width() > 300 && r.height() > 120 && !d.contains("shorts") && !d.contains("sponsored")) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val found = findLargeClickableInRange(node.getChild(i), minY, maxY)
            if (found != null) return found
        }
        return null
    }

    /** Two-pass node finder: 1) Exact match (preferring clickable), 2) Contains match (preferring clickable) */
    private fun findBestNodeByText(root: AccessibilityNodeInfo?, q: String): AccessibilityNodeInfo? {
        if (root == null || q.isEmpty()) return null
        findExactNode(root, q, requireClickable = true)?.let { return it }
        findExactNode(root, q, requireClickable = false)?.let { return it }
        findContainsNode(root, q, requireClickable = true)?.let { return it }
        return findContainsNode(root, q, requireClickable = false)
    }

    private fun findExactNode(node: AccessibilityNodeInfo?, q: String, requireClickable: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val matches = t.equals(q, ignoreCase = true) || d.equals(q, ignoreCase = true)
            if (matches && (!requireClickable || node.isClickable || node.parent?.isClickable == true)) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val found = findExactNode(node.getChild(i), q, requireClickable)
            if (found != null) return found
        }
        return null
    }

    private fun findContainsNode(node: AccessibilityNodeInfo?, q: String, requireClickable: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val matches = t.contains(q, ignoreCase = true) || d.contains(q, ignoreCase = true)
            if (matches && (!requireClickable || node.isClickable || node.parent?.isClickable == true)) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val found = findContainsNode(node.getChild(i), q, requireClickable)
            if (found != null) return found
        }
        return null
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        if (isPaymentAppActive()) return false
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            return tapCoordinates(rect.exactCenterX(), rect.exactCenterY())
        }
        return false
    }

    fun inputText(text: String, autoSend: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val editNode = findWhatsAppOrGenericEditable(root) ?: return false
        editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && autoSend) {
            try { Thread.sleep(350) } catch (_: Exception) {}
            if (!clickWhatsAppSendButton()) {
                clickByText("Send") || clickByText("भेजें") || clickByText("Send Message") || clickByText("Search")
            }
        }
        return ok
    }

    /** Directly type and send a message inside the currently open chat screen and verify */
    fun sendInOpenChatVerified(message: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editNode = findWhatsAppOrGenericEditable(root) ?: return false
        editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        val typed = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!typed) return false
        try { Thread.sleep(400) } catch (_: Exception) {}
        val clicked = clickWhatsAppSendButton() || clickByText("Send") || clickByText("भेजें") || clickByText("Send Message")
        if (!clicked) return false
        try { Thread.sleep(550) } catch (_: Exception) {}
        return isInputCleared()
    }

    fun isInputCleared(): Boolean {
        val root = rootInActiveWindow ?: return false
        val editNode = findWhatsAppOrGenericEditable(root) ?: return true
        val t = editNode.text?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) editNode.hintText?.toString()?.trim().orEmpty() else ""
        return t.isEmpty() || t.equals(hint, ignoreCase = true) || t.equals("Message", ignoreCase = true) || t.equals("मैसेज", ignoreCase = true)
    }

    private fun findWhatsAppOrGenericEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val waEntry = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")
            ?.firstOrNull { it.isVisibleToUser }
            ?: root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/entry")
                ?.firstOrNull { it.isVisibleToUser }
        if (waEntry != null) return waEntry
        return findEditableNode(root)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val f = findEditableNode(node.getChild(i))
            if (f != null) return f
        }
        return null
    }

    fun performSystemAction(action: String): Boolean {
        return when (action.lowercase().trim()) {
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "scroll_down", "down" -> scrollScreen(forward = true)
            "scroll_up", "up" -> scrollScreen(forward = false)
            else -> false
        }
    }

    // Reliable Multi-Scroll: Synchronous swipe + Accessibility node scroll fallback
    private fun scrollScreen(forward: Boolean): Boolean {
        val before = readScreenElements(12, updateCache = false)
        val mainLatch = java.util.concurrent.CountDownLatch(1)
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val startY = if (forward) dm.heightPixels * 0.75f else dm.heightPixels * 0.25f
        val endY   = if (forward) dm.heightPixels * 0.22f else dm.heightPixels * 0.78f
        val path = Path().apply { moveTo(cx, startY); lineTo(cx, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 320)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { mainLatch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { mainLatch.countDown() }
            },
            null
        )
        try { mainLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        try { Thread.sleep(280) } catch (_: Exception) {}
        val after = readScreenElements(12, updateCache = false)
        if (after == before) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            findScrollable(rootInActiveWindow)?.performAction(action)
            try { Thread.sleep(250) } catch (_: Exception) {}
        }
        return true
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val f = findScrollable(node.getChild(i))
            if (f != null) return f
        }
        return null
    }
}

class MeeraKeepAliveService : Service() {
    companion object {
        private const val CH_ID = "meera_live_channel"
        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, MeeraKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {}
        }
        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, MeeraKeepAliveService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == MicMute.ACTION) MicMute.toggle()
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CH_ID, "Meera Active Voice", NotificationManager.IMPORTANCE_LOW))
            }
            val notif = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CH_ID)
                    .setContentTitle("Meera AI चालू है 🎙️")
                    .setContentText(if (MicMute.muted) "🔇 म्यूट है — बाहर की आवाज़ नहीं सुन रही" else "आप किसी भी ऐप में बोलकर कमांड दे सकते हैं")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true)
                    .addAction(
                        android.R.drawable.ic_lock_silent_mode,
                        if (MicMute.muted) "🔊 अनम्यूट" else "🔇 म्यूट",
                        android.app.PendingIntent.getService(
                            this, 7,
                            Intent(this, MeeraKeepAliveService::class.java).setAction(MicMute.ACTION),
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                    .build()
            } else Notification()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(101, notif)
            }
        } catch (_: Exception) {}
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}


object MicMute {
    const val ACTION = "com.meraai.app.TOGGLE_MUTE"
    val flow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val muted: Boolean get() = flow.value
    fun toggle() { flow.value = !flow.value }
    fun set(v: Boolean) { flow.value = v }
}

class MeeraUpgradeReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            MeeraDebugLog.log("UPGRADE", "New APK update installed seamless! Services refreshed.")
        }
    }
}

