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
        setContent {
            MeraTheme { MeraApp(vm, onMic = ::onMic) }
        }
    }

    private fun onMic() {
        if (vm.isActive) { vm.stop(); return }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.start() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onStop() {
        super.onStop()
        vm.stop()
    }
}
