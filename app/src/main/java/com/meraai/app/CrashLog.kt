package com.meraai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File

object MeeraCrashLog {
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val f = File(app.filesDir, "last_crash.txt")
        try {
            if (f.exists()) {
                val txt = f.readText()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("meera_crash", txt))
                        Toast.makeText(app, "Pichhla crash log copy ho gaya. Paste karke bhejo.", Toast.LENGTH_LONG).show()
                        f.delete()
                    } catch (_: Throwable) {}
                }, 1500)
            }
        } catch (_: Throwable) {}
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { f.writeText("Thread: " + t.name + "\n" + android.util.Log.getStackTraceString(e)) } catch (_: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }
}
