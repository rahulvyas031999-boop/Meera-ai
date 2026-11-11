package com.meraai.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object MeeraUpdater {
    private const val OWNER = "rahulvyas031999-boop"
    private const val REPO = "Meera-ai"
    private const val DIRECT_APK_URL = "https://github.com/$OWNER/$REPO/releases/download/latest-v2-test/MeraAI-debug.apk"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/tags/latest-v2-test"

    fun checkAndInstallUpdate(ctx: Context) {
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(app, "पहले 'Install unknown apps' की अनुमति ऑन करें, फिर दोबारा अपडेट दबाएँ", Toast.LENGTH_LONG).show()
            try {
                val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(i)
            } catch (_: Exception) {}
            return
        }

        val curVer = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "2.0"
        }.getOrDefault("2.0")

        Toast.makeText(app, "🔄 GitHub पर नया अपडेट चेक हो रहा है (अभी: v$curVer)...", Toast.LENGTH_SHORT).show()

        Thread {
            var downloadUrl = DIRECT_APK_URL
            var remoteTitle = "Latest v2-test"
            try {
                val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (conn.responseCode == 200) {
                    val j = JSONObject(conn.inputStream.bufferedReader().readText())
                    remoteTitle = j.optString("name", remoteTitle)
                    val assets = j.optJSONArray("assets")
                    if (assets != null && assets.length() > 0) {
                        val url = assets.optJSONObject(0)?.optString("browser_download_url", "") ?: ""
                        if (url.isNotBlank()) downloadUrl = url
                    }
                }
            } catch (_: Exception) {}

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                startDownloadAndInstall(app, downloadUrl, remoteTitle)
            }
        }.start()
    }

    private fun startDownloadAndInstall(app: Context, apkUrl: String, title: String) {
        try {
            val outFile = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MeeraAI-update.apk")
            if (outFile.exists()) outFile.delete()

            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Meera AI Update ($title)")
                .setDescription("नया अपडेट डाउनलोड हो रहा है...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(outFile))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadId = dm.enqueue(req)
            Toast.makeText(app, "⬇️ अपडेट डाउनलोड शुरू हो गया! पूरा होते ही Install पॉपअप खुलेगा।", Toast.LENGTH_LONG).show()

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                    if (id == downloadId) {
                        try { app.unregisterReceiver(this) } catch (_: Exception) {}
                        launchInstaller(app, outFile, apkUrl)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
        } catch (e: Exception) {
            // Fallback: open direct link in browser if DownloadManager fails
            try {
                app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(app, "अपडेट डाउनलोड में दिक्कत: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchInstaller(app: Context, apkFile: File, fallbackUrl: String) {
        if (!apkFile.exists() || apkFile.length() < 100_000L) {
            Toast.makeText(app, "अभी GitHub पर नया Release APK तैयार हो रहा है। 2 मिनट बाद दोबारा दबाएँ!", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val apkUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apkFile)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(install)
        } catch (_: Exception) {
            try {
                app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }
}
