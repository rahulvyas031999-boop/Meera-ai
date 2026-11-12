package com.meraai.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val vm: VoiceViewModel by viewModels()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.start() else vm.showError("माइक की अनुमति के बिना बात नहीं हो सकती")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeeraCrashLog.install(this)
        handleWakeIntent(intent)
        MeeraWake.ensureRunning(this)
        setContent {
            MeraTheme { MeraApp(vm, onMic = ::onMic) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleWakeIntent(i: android.content.Intent?) {
        if (i == null || !i.getBooleanExtra(MeeraWake.EXTRA_WAKE, false)) return
        i.removeExtra(MeeraWake.EXTRA_WAKE)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true); setTurnScreenOn(true)
            } else {
                window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        } catch (_: Exception) {}
        if (!vm.isActive) onMic()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (!vm.isActive) MeeraWake.resume() }, 2500)
    }

    private fun onMic() {
        if (vm.isActive) { vm.stop(); return }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.start() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroy() {
        if (isFinishing) vm.stop()
        super.onDestroy()
    }
}
