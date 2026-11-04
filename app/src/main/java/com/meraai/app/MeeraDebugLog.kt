package com.meraai.app

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object MeeraDebugLog {
    private val lines = CopyOnWriteArrayList<String>()
    @Volatile private var dirty = false
    @Volatile private var hooked = false

    fun log(tag: String, msg: String) {
        val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        lines.add("$t [$tag] ${msg.take(300)}")
        while (lines.size > 200) lines.removeAt(0)
        dirty = true
    }

    fun copyNow(ctx: Context) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("meera_debug", lines.joinToString("\n")))
                dirty = false
                Toast.makeText(app, "Debug log copy ho gaya. Paste karke bhejo.", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
        }
    }

    fun hook(app: Application) {
        if (hooked) return
        hooked = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { if (dirty) copyNow(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
