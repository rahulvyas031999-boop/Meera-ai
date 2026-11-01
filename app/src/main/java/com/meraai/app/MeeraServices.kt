package com.meraai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CopyOnWriteArrayList

data class NotifItem(val pkg: String, val title: String, val text: String, val time: Long)

class MeeraNotificationService : NotificationListenerService() {
    companion object {
        val recentNotifs = CopyOnWriteArrayList<NotifItem>()
        fun getLatestSummary(): String {
            if (recentNotifs.isEmpty()) return "अभी कोई नया नोटिफिकेशन नहीं है।"
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

    /** चालू स्क्रीन के सारे पठनीय टेक्स्ट, बटन और लेबल्स निकालना */
    fun readScreenText(maxItems: Int = 45): String {
        val root = rootInActiveWindow ?: return ""
        val items = LinkedHashSet<String>()
        val pkg = root.packageName?.toString().orEmpty()
        collectNodes(root, items, maxItems)
        if (items.isEmpty()) return ""
        return "Active App: $pkg\n" + items.joinToString("\n") { "• $it" }
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, out: MutableSet<String>, limit: Int) {
        if (node == null || out.size >= limit) return
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val label = when {
                t.isNotBlank() && d.isNotBlank() && !t.equals(d, true) -> "$t ($d)"
                t.isNotBlank() -> t
                d.isNotBlank() -> d
                else -> ""
            }
            if (label.length in 2..180) {
                val tag = if (node.isClickable) "[Button] " else if (node.isEditable) "[Input] " else ""
                out.add("$tag$label")
            }
        }
        for (i in 0 until node.childCount) {
            if (out.size >= limit) break
            collectNodes(node.getChild(i), out, limit)
        }
    }

    /** स्क्रीन पर दिख रहे किसी टेक्स्ट या बटन को ढूँढकर उस पर क्लिक करना */
    fun clickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeByText(root, query.trim()) ?: return false
        return performClickOnNode(target)
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?, q: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.contains(q, ignoreCase = true) || d.contains(q, ignoreCase = true)) {
                return node
            }
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
            val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 80)
            return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
        return false
    }

    /** स्क्रीन के इनपुट बॉक्स (जैसे WhatsApp/Search बार) में टेक्स्ट टाइप करना */
    fun inputText(text: String, autoSend: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val editNode = findEditableNode(root) ?: return false
        editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && autoSend) {
            Thread.sleep(250)
            clickByText("Send") || clickByText("भेजें") || clickByText("Search")
        }
        return ok
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

    /** सिस्टम नेविगेशन और स्क्रॉल (home, back, notifications, scroll_down, scroll_up) */
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
                    .setContentTitle("Meera AI चालू है")
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
