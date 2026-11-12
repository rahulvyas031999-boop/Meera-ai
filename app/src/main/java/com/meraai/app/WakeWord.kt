package com.meraai.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import java.nio.FloatBuffer

/**
 * "Hey Meera" वेक-वर्ड (openWakeWord मॉडल).
 * असेट्स: app/src/main/assets/wakeword/{melspectrogram,embedding_model,meera}.onnx
 */
object MeeraWake {
    const val EXTRA_WAKE = "meera_wake"
    const val THRESHOLD = 0.5f      // ज़्यादा बार बिना बोले जागे तो 0.6-0.7 करें, सुने ही नहीं तो 0.4
    const val HITS_NEEDED = 2       // लगातार इतने 80ms-हिस्सों पर स्कोर ऊँचा रहना चाहिए
    const val COOLDOWN_MS = 3000L

    @Volatile var service: MeeraWakeService? = null
    @Volatile var enabled = true

    fun ensureRunning(private val ctx: Context) {
        if (!enabled || service != null) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            val i = Intent(ctx, MeeraWakeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            MeeraDebugLog.log("WAKE", "service start failed: ${e.javaClass.simpleName}")
        }
    }

    /** Meera का अपना माइक सेशन शुरू होने से पहले (माइक छोड़ देता है) */
    fun pause() { service?.pauseListening() }

    /** Meera सो जाए तो फिर से "Hey Meera" सुनना शुरू */
    fun resume() { service?.resumeListening() }
}

class WakeEngine(private val ctx: Context) {
    companion object {
        const val CHUNK = 1280        // 80 ms @16 kHz
        private const val CTX = 480   // mel के लिए पिछले 30 ms का संदर्भ
        private const val MEL_BINS = 32
        private const val EMB_WIN = 76
        private const val EMB_DIM = 96
        private const val WAKE_WIN = 16
    }

    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }

    private fun load(name: String): OrtSession {
        val bytes: ByteArray = ctx.assets.open("wakeword/$name").use { input -> input.readBytes() }
        return env.createSession(bytes, opts)
    }

    private val mel = load("melspectrogram.onnx")
    private val emb = load("embedding_model.onnx")
    private val wake = load("meera.onnx")

    private val raw = FloatArray(CHUNK + CTX)
    private val melFrames = ArrayDeque<FloatArray>()
    private val feats = ArrayDeque<FloatArray>()

    init { reset() }

    fun reset() {
        raw.fill(0f)
        melFrames.clear()
        repeat(EMB_WIN) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
        feats.clear()
    }

    /** ठीक 1280 सैंपल (int16) दें; 0..1 स्कोर लौटाता है */
    fun process(pcm: ShortArray): Float {
        System.arraycopy(raw, CHUNK, raw, 0, CTX)
        for (i in 0 until CHUNK) raw[CTX + i] = pcm[i].toFloat()

        val melOut = run1(mel, raw, longArrayOf(1, raw.size.toLong()))
        val frames = melOut.size / MEL_BINS
        for (f in 0 until frames) {
            melFrames.addLast(FloatArray(MEL_BINS) { melOut[f * MEL_BINS + it] / 10f + 2f })
        }
        while (melFrames.size > 200) melFrames.removeFirst()

        val embIn = FloatArray(EMB_WIN * MEL_BINS)
        val start = melFrames.size - EMB_WIN
        for (i in 0 until EMB_WIN) System.arraycopy(melFrames[start + i], 0, embIn, i * MEL_BINS, MEL_BINS)
        val e = run1(emb, embIn, longArrayOf(1, EMB_WIN.toLong(), MEL_BINS.toLong(), 1))
        feats.addLast(e.copyOf(EMB_DIM))
        while (feats.size > WAKE_WIN) feats.removeFirst()
        if (feats.size < WAKE_WIN) return 0f

        val wIn = FloatArray(WAKE_WIN * EMB_DIM)
        for (i in 0 until WAKE_WIN) System.arraycopy(feats[i], 0, wIn, i * EMB_DIM, EMB_DIM)
        return run1(wake, wIn, longArrayOf(1, WAKE_WIN.toLong(), EMB_DIM.toLong()))[0]
    }

    private fun run1(s: OrtSession, data: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                val fb = (r.get(0) as OnnxTensor).floatBuffer
                val out = FloatArray(fb.remaining())
                fb.get(out)
                return out
            }
        }
    }

    fun close() {
        try { mel.close() } catch (_: Throwable) {}
        try { emb.close() } catch (_: Throwable) {}
        try { wake.close() } catch (_: Throwable) {}
    }
}

class MeeraWakeService : Service() {
    companion object {
        private const val CH_ID = "meera_wake_channel"
        private const val RATE = 16000
    }

    private var engine: WakeEngine? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CH_ID, "Meera Wake Word", NotificationManager.IMPORTANCE_MIN))
            }
            val notif = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CH_ID)
                    .setContentTitle("Meera सुन रही है")
                    .setContentText("\"Hey Meera\" बोलकर जगाएँ")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true)
                    .build()
            } else Notification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(102, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(102, notif)
            }
        } catch (e: Exception) {
            MeeraDebugLog.log("WAKE", "startForeground failed: ${e.javaClass.simpleName}")
        }
        MeeraWake.service = this
        startListening()
        return START_STICKY
    }

    @Synchronized
    fun startListening() {
        if (running) return
        try { thread?.join(600) } catch (_: Exception) {}
        running = true
        thread = Thread({ loop() }, "meera-wake").also { it.isDaemon = true; it.start() }
    }

    fun pauseListening() {
        running = false
        val t = thread
        if (t != null && t != Thread.currentThread()) try { t.join(800) } catch (_: Exception) {}
    }

    fun resumeListening() {
        main.postDelayed({ if (MeeraWake.service === this) startListening() }, 500)
    }

    private fun loop() {
        var rec: AudioRecord? = null
        try {
            val eng = engine ?: WakeEngine(applicationContext).also { engine = it }
            eng.reset()
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("MissingPermission")
            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, WakeEngine.CHUNK * 2 * 8)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                MeeraDebugLog.log("WAKE", "mic init failed")
                running = false
                return
            }
            rec.startRecording()
            MeeraDebugLog.log("WAKE", "listening")
            val buf = ShortArray(WakeEngine.CHUNK)
            var hits = 0
            var lastWake = 0L
            var peak = 0f
            var frames = 0
            while (running) {
                var got = 0
                while (got < WakeEngine.CHUNK && running) {
                    val n = rec.read(buf, got, WakeEngine.CHUNK - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < WakeEngine.CHUNK) break
                if (MicMute.muted) { hits = 0; continue }
                val s = eng.process(buf)
                if (s > peak) peak = s
                if (++frames % 125 == 0) { // हर ~10 सेकंड में एक डिबग लाइन
                    MeeraDebugLog.log("WAKE", "peak score ${"%.2f".format(peak)}")
                    peak = 0f
                }
                hits = if (s >= MeeraWake.THRESHOLD) hits + 1 else 0
                val now = System.currentTimeMillis()
                if (hits >= MeeraWake.HITS_NEEDED && now - lastWake > MeeraWake.COOLDOWN_MS) {
                    lastWake = now
                    running = false
                    MeeraDebugLog.log("WAKE", "DETECTED score=${"%.2f".format(s)}")
                    main.post { launchApp() }
                    break
                }
            }
        } catch (t: Throwable) {
            MeeraDebugLog.log("WAKE", "loop error: ${t.javaClass.simpleName} ${t.message}")
            running = false
        } finally {
            try { rec?.stop() } catch (_: Throwable) {}
            try { rec?.release() } catch (_: Throwable) {}
        }
    }

    private fun launchApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MeeraWake.EXTRA_WAKE, true)
            )
        } catch (e: Exception) {
            MeeraDebugLog.log("WAKE", "launch failed: ${e.javaClass.simpleName}")
            resumeListening()
        }
    }

    override fun onDestroy() {
        running = false
        if (MeeraWake.service === this) MeeraWake.service = null
        try { thread?.join(500) } catch (_: Exception) {}
        engine?.close(); engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
