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

    /** Maya UN.java style: Read screen elements with #id numbers */
    fun readScreenElements(maxItems: Int = 45): String {
        val root = rootInActiveWindow ?: return ""
        val pkg = root.packageName?.toString().orEmpty()
        val rawList = ArrayList<ScreenElement>()
        collectElements(root, rawList, maxItems)
        lastElements.clear()
        lastElements.addAll(rawList)
        if (rawList.isEmpty()) return ""
        return "Active App: $pkg\n" + rawList.joinToString("\n") { "#${it.id} [${it.kind}] ${it.label}" }
    }

    fun readScreenText(maxItems: Int = 45): String = readScreenElements(maxItems)

    private fun collectElements(node: AccessibilityNodeInfo?, out: MutableList<ScreenElement>, limit: Int) {
        if (node == null || out.size >= limit) return
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim().orEmpty() else ""
            val label = when {
                t.isNotBlank() && d.isNotBlank() && !t.equals(d, true) -> "$t ($d)"
                t.isNotBlank() -> t
                d.isNotBlank() -> d
                hint.isNotBlank() -> hint
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
                val duplicate = out.any { it.label.equals(label, ignoreCase = true) && it.kind == kind }
                if (!duplicate) {
                    out.add(ScreenElement(out.size + 1, label, kind, rect))
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (out.size >= limit) break
            collectElements(node.getChild(i), out, limit)
        }
    }

    /** Maya UN.java style: Tap an element by its #id */
    fun tapElementById(id: Int): Boolean {
        val el = lastElements.find { it.id == id } ?: return false
        if (!el.bounds.isEmpty) {
            return tapCoordinates(el.bounds.exactCenterX(), el.bounds.exactCenterY())
        }
        return clickByText(el.label)
    }

    fun tapCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 85)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Maya wait_for_screen_text: Wait for text to appear or disappear on screen */
    fun waitForScreenText(query: String, mode: String = "appear", timeoutSeconds: Int = 30): Boolean {
        val target = query.trim()
        if (target.isEmpty()) return true
        val deadline = System.currentTimeMillis() + (timeoutSeconds.coerceIn(1, 600) * 1000L)
        val wantAppear = !mode.equals("disappear", ignoreCase = true)
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            val found = root != null && findNodeByText(root, target) != null
            if (wantAppear && found) return true
            if (!wantAppear && !found) return true
            try { Thread.sleep(350) } catch (_: Exception) { break }
        }
        return false
    }

    fun hasScreenText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return findNodeByText(root, query.trim()) != null
    }

    fun clickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeByText(root, query.trim()) ?: return false
        return performClickOnNode(target)
    }

    /** YouTube Auto-Play: Click the first real video result (skipping Shorts, Ads, and Search bar) */
    fun clickFirstYoutubeVideo(): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectVideoCandidates(root, candidates)
        for (node in candidates) {
            if (performClickOnNode(node)) return true
        }
        // Fallback: tap center-upper area where first video thumbnail renders
        val dm = resources.displayMetrics
        return tapCoordinates(dm.widthPixels * 0.5f, dm.heightPixels * 0.36f)
    }

    private fun collectVideoCandidates(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null || out.size >= 8) return
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val combined = "$t $d".trim()
            val low = combined.lowercase()
            val isVideoRow = combined.length >= 15 &&
                (low.contains("minutes") || low.contains("seconds") || low.contains("views") ||
                 low.contains("ago") || low.contains("मिनट") || low.contains("व्यू") ||
                 combined.contains(":")) &&
                !low.contains("shorts") && !low.contains("sponsored") &&
                !low.startsWith("search") && !low.contains("filter")
            if (isVideoRow) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top > 180 && r.height() > 60) {
                    out.add(node)
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (out.size >= 8) break
            collectVideoCandidates(node.getChild(i), out)
        }
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?, q: String): AccessibilityNodeInfo? {
        if (node == null || q.isEmpty()) return null
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.equals(q, ignoreCase = true) || d.equals(q, ignoreCase = true)) return node
            if (t.contains(q, ignoreCase = true) || d.contains(q, ignoreCase = true)) return node
        }
        for (i in 0 until node.childCount) {
            val found = findNodeByText(node.getChild(i), q)
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
        val editNode = findEditableNode(root) ?: return false
        editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && autoSend) {
            try { Thread.sleep(300) } catch (_: Exception) {}
            clickByText("Send") || clickByText("भेजें") || clickByText("Send Message") || clickByText("Search")
        }
        return ok
    }

    fun isInputCleared(): Boolean {
        val root = rootInActiveWindow ?: return false
        val editNode = findEditableNode(root) ?: return true
        val t = editNode.text?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) editNode.hintText?.toString()?.trim().orEmpty() else ""
        return t.isEmpty() || t.equals(hint, ignoreCase = true) || t.equals("Message", ignoreCase = true)
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
