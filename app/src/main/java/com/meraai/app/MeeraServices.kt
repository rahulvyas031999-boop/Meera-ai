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
import java.util.concurrent.CopyOnWriteArrayList

data class NotifItem(val pkg: String, val title: String, val text: String, val time: Long)
data class ScreenElement(val id: Int, val label: String, val kind: String, val bounds: Rect)

class MeeraNotificationService : NotificationListenerService() {
    companion object {
        val recentNotifs = CopyOnWriteArrayList<NotifItem>()
        fun getLatestSummary(): String {
            if (recentNotifs.isEmpty()) return "Abhi koi naya notification nahi hai."
            return recentNotifs.takeLast(8).reversed().joinToString("\n") { "• [${it.title}]: ${it.text}" }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence("android.title")?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence("android.text")?.toString()?.trim().orEmpty()
        if (title.isNotBlank() || text.isNotBlank()) {
            recentNotifs.add(NotifItem(pkg, title, text, System.currentTimeMillis()))
            while (recentNotifs.size > 25) recentNotifs.removeAt(0)
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
    fun readScreenElements(maxItems: Int = 50): String {
        val root = rootInActiveWindow ?: return ""
        val pkg = root.packageName?.toString().orEmpty()
        val rawList = ArrayList<ScreenElement>()
        collectElements(root, rawList, maxItems)
        lastElements.clear()
        lastElements.addAll(rawList)
        if (rawList.isEmpty()) return ""
        return "Active App: $pkg\n" + rawList.joinToString("\n") { "#${it.id} [${it.kind}] ${it.label}" }
    }

    fun readScreenText(maxItems: Int = 50): String = readScreenElements(maxItems)

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
                else -> ""
            }
            if (label.length in 1..180) {
                val kind = when {
                    node.isEditable -> "Input"
                    node.isClickable -> "Button"
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

    fun tapCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 95)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Tap center of screen (used for tapping generated images in ChatGPT/Gemini to reveal Save/Download bar) */
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

    fun clickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findBestNodeByText(root, query.trim()) ?: return false
        return performClickOnNode(target)
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

    private fun scrollScreen(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollable(root)
        if (scrollable != null) {
            val act = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollable.performAction(act)) return true
        }
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val startY = if (forward) dm.heightPixels * 0.75f else dm.heightPixels * 0.25f
        val endY = if (forward) dm.heightPixels * 0.25f else dm.heightPixels * 0.75f
        val path = Path().apply { moveTo(cx, startY); lineTo(cx, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 260)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
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
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CH_ID, "Meera Active Voice", NotificationManager.IMPORTANCE_LOW))
            }
            val notif = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CH_ID)
                    .setContentTitle("Meera AI चालू है 🎙️")
                    .setContentText("आप किसी भी ऐप में बोलकर कमांड दे सकते हैं")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true)
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
