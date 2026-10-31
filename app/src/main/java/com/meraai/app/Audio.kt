package com.meraai.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sqrt

fun rmsOf(s: ShortArray): Float {
    if (s.isEmpty()) return 0f
    var sum = 0.0
    for (v in s) { val f = v / 32768.0; sum += f * f }
    return sqrt(sum / s.size).toFloat()
}

/**
 * इंसानी गले की आवाज़ बनाम खांसी/शोर/दूसरे मोबाइल की आवाज़ की पहचान (Zero-Crossing + Vocal Formant Check).
 * खांसी, ताली या दूसरे मोबाइल के स्पीकर में हाई-फ्रीक्वेंसी शोर ज़्यादा होता है.
 */
fun isHumanVocalFrame(s: ShortArray, rms: Float): Boolean {
    if (s.size < 64 || rms < Cfg.VAD_MIN_RMS) return false
    var zc = 0
    var diffSum = 0.0
    var prev = s[0].toInt()
    for (i in 1 until s.size) {
        val cur = s[i].toInt()
        if ((prev >= 0 && cur < 0) || (prev < 0 && cur >= 0)) zc++
        val d = (cur - prev) / 32768.0
        diffSum += d * d
        prev = cur
    }
    val zcr = zc.toFloat() / s.size
    val hfRms = sqrt(diffSum / s.size).toFloat()
    // इंसानी आवाज़ का ZCR आमतौर पर 0.02 से 0.28 के बीच होता है; खांसी/हिस्स/मोबाइल स्पीकर टोन में HF/RMS अनुपात बहुत तीखा होता है
    val spectralRatio = hfRms / (rms + 1e-5f)
    return zcr in 0.015f..0.30f && spectralRatio in 0.12f..1.15f
}

/**
 * Maya APK (C0115Bl1.java) का ओरिजिनल Silero VAD v4 TFLite इंजन:
 * 512 samples (32ms @ 16kHz) लेता है और 0.0 से 1.0 के बीच Human Speech Probability देता है.
 */
class SileroVadEngine(ctx: Context) {
    private var interpreter: Interpreter? = null
    private val hState = FloatArray(128)
    private val cState = FloatArray(128)
    private val xInput = Array(1) { FloatArray(512) }
    private val hInput = Array(2) { Array(1) { FloatArray(64) } }
    private val cInput = Array(2) { Array(1) { FloatArray(64) } }
    private val probOut = Array(1) { FloatArray(1) }
    private val hOut = Array(1) { Array(2) { FloatArray(64) } }
    private val cOut = Array(1) { Array(2) { FloatArray(64) } }

    val isAvailable: Boolean get() = interpreter != null

    init {
        try {
            val bytes = ctx.assets.open("guardian/silero_vad.tflite").use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.rewind()
            val opts = Interpreter.Options().setNumThreads(1)
            val interp = Interpreter(buf, opts)
            if (interp.inputTensorCount == 3 && interp.outputTensorCount == 3) {
                interpreter = interp
            } else {
                interp.close()
            }
        } catch (_: Throwable) {
            interpreter = null
        }
    }

    @Synchronized
    fun resetState() {
        Arrays.fill(hState, 0f)
        Arrays.fill(cState, 0f)
    }

    @Synchronized
    fun speechProbability(frame: ShortArray): Float {
        val interp = interpreter ?: return -1f
        if (frame.size < 512) return -1f
        return try {
            for (i in 0 until 512) {
                xInput[0][i] = frame[i] / 32768.0f
            }
            System.arraycopy(hState, 0, hInput[0][0], 0, 64)
            System.arraycopy(hState, 64, hInput[1][0], 0, 64)
            System.arraycopy(cState, 0, cInput[0][0], 0, 64)
            System.arraycopy(cState, 64, cInput[1][0], 0, 64)

            val inputs = arrayOf<Any>(xInput, hInput, cInput)
            val outputs = mapOf<Int, Any>(0 to probOut, 1 to hOut, 2 to cOut)
            interp.runForMultipleInputsOutputs(inputs, outputs)

            System.arraycopy(hOut[0][0], 0, hState, 0, 64)
            System.arraycopy(hOut[0][1], 0, hState, 64, 64)
            System.arraycopy(cOut[0][0], 0, cState, 0, 64)
            System.arraycopy(cOut[0][1], 0, cState, 64, 64)
            probOut[0][0]
        } catch (_: Throwable) {
            -1f
        }
    }

    fun close() {
        try { interpreter?.close() } catch (_: Throwable) {}
        interpreter = null
    }
}

fun ShortArray.toLeBytes(): ByteArray {
    val out = ByteArray(size * 2)
    for (i in indices) {
        out[i * 2] = (this[i].toInt() and 0xFF).toByte()
        out[i * 2 + 1] = ((this[i].toInt() shr 8) and 0xFF).toByte()
    }
    return out
}

fun joinPcm(list: List<ShortArray>): ByteArray {
    val total = list.sumOf { it.size }
    val out = ByteArray(total * 2)
    var o = 0
    for (f in list) for (s in f) {
        out[o++] = (s.toInt() and 0xFF).toByte()
        out[o++] = ((s.toInt() shr 8) and 0xFF).toByte()
    }
    return out
}

fun pcmToWav(pcm: ByteArray, rate: Int): ByteArray {
    val h = ByteArray(44)
    fun i32(o: Int, v: Int) { h[o] = v.toByte(); h[o + 1] = (v shr 8).toByte(); h[o + 2] = (v shr 16).toByte(); h[o + 3] = (v shr 24).toByte() }
    fun i16(o: Int, v: Int) { h[o] = v.toByte(); h[o + 1] = (v shr 8).toByte() }
    "RIFF".toByteArray().copyInto(h, 0)
    i32(4, 36 + pcm.size)
    "WAVE".toByteArray().copyInto(h, 8)
    "fmt ".toByteArray().copyInto(h, 12)
    i32(16, 16); i16(20, 1); i16(22, 1); i32(24, rate); i32(28, rate * 2); i16(32, 2); i16(34, 16)
    "data".toByteArray().copyInto(h, 36)
    i32(40, pcm.size)
    return h + pcm
}

fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
fun unb64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

/** माइक: 16kHz mono, VOICE_COMMUNICATION source + हार्डवेयर Echo Canceller / Noise Suppressor / AGC (Maya NI0.java स्टाइल हाई-प्रायोरिटी थ्रेड). */
class MicRecorder(private val onFrame: (ShortArray) -> Unit) {
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val fx = mutableListOf<AudioEffect>()

    @SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(Cfg.IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, Cfg.IN_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, Cfg.FRAME_SAMPLES * 2 * 8)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("माइक शुरू नहीं हो पाया")
        }
        val sid = r.audioSessionId
        try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sid)?.let { it.enabled = true; fx += it }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sid)?.let { it.enabled = true; fx += it }
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sid)?.let { it.enabled = true; fx += it }
        } catch (_: Throwable) {}
        rec = r
        running = true
        r.startRecording()
        thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(Cfg.FRAME_SAMPLES)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) onFrame(buf.copyOf(n)) else if (n < 0) break
            }
        }, "meera-micbus").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { rec?.stop() } catch (_: Exception) {}
        if (Thread.currentThread() != thread) try { thread?.join(300) } catch (_: Exception) {}
        fx.forEach { try { it.release() } catch (_: Exception) {} }
        fx.clear()
        try { rec?.release() } catch (_: Exception) {}
        rec = null
    }
}

/** 24kHz PCM16 स्ट्रीमिंग प्लेयर + Maya 600ms Echo Guard Cooldown. */
class PcmPlayer {
    private class Chunk(val data: ByteArray, val gen: Int)

    private val track: AudioTrack
    private val queue = LinkedBlockingQueue<Chunk>()
    private val pending = AtomicInteger(0)
    @Volatile private var gen = 0
    @Volatile private var playUntil = 0L
    @Volatile var lastActiveAt = 0L
        private set
    @Volatile private var alive = true
    @Volatile var level = 0f
        private set
    private val thread: Thread

    val busy: Boolean get() = pending.get() > 0 || System.nanoTime() < playUntil
    val inEchoCooldown: Boolean get() = busy || (System.currentTimeMillis() - lastActiveAt < Cfg.ASSISTANT_QUIET_MS)

    init {
        val rate = Cfg.OUT_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, rate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    private fun loop() {
        val slice = Cfg.OUT_RATE * 2 / 50 // 20ms
        try {
            while (alive) {
                val c = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                var off = 0
                while (off < c.data.size && alive && c.gen == gen) {
                    val n = minOf(slice, c.data.size - off)
                    level = levelOf(c.data, off, n)
                    val w = track.write(c.data, off, n)
                    if (w < 0) break
                    off += w
                    lastActiveAt = System.currentTimeMillis()
                    playUntil = maxOf(playUntil, System.nanoTime()) + w * 1_000_000_000L / (Cfg.OUT_RATE * 2)
                }
                if (c.gen == gen) pending.addAndGet(-c.data.size)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun levelOf(b: ByteArray, off: Int, n: Int): Float {
        var s = 0.0
        var cnt = 0
        var i = off
        while (i + 1 < off + n) {
            val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt() / 32768.0
            s += v * v; cnt++; i += 2
        }
        return if (cnt == 0) 0f else (sqrt(s / cnt) * 5).toFloat().coerceIn(0f, 1f)
    }

    @Synchronized
    fun enqueue(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        lastActiveAt = System.currentTimeMillis()
        pending.addAndGet(pcm.size)
        queue.put(Chunk(pcm, gen))
    }

    @Synchronized
    fun flush() {
        gen++
        queue.clear()
        pending.set(0)
        playUntil = 0L
        level = 0f
        try { track.pause(); track.flush(); track.play() } catch (_: Exception) {}
    }

    fun release() {
        alive = false
        thread.interrupt()
        try { track.stop() } catch (_: Exception) {}
        try { track.release() } catch (_: Exception) {}
    }
}

/**
 * Maya-Grade TurnDetector:
 * 1) Near-field adaptive SNR gate (दूर की आवाज़ और हल्की खटपट रोके)
 * 2) Silero Neural VAD + Vocal Formant check (खांसी, ताली और दूसरे फोन की आवाज़ रोके)
 * 3) 240ms onset + 360ms minimum duration (छोटी खांसी या हिचकी पर कभी ट्रिगर न हो)
 */
class TurnDetector(
    private val silero: SileroVadEngine?,
    private val silenceMs: () -> Int
) {
    enum class Ev { NONE, SPEECH_START, SPEECH_END, NOISE }

    private var floor = 0.006f
    private var speaking = false
    private var onset = 0
    private var voicedMs = 0
    private var silentMs = 0

    val isUserSpeaking: Boolean @Synchronized get() = speaking

    @Synchronized
    fun reset() {
        speaking = false; onset = 0; voicedMs = 0; silentMs = 0
        silero?.resetState()
    }

    @Synchronized
    fun forceSpeaking() {
        speaking = true; onset = 0; voicedMs = Cfg.MIN_SPEECH_MS; silentMs = 0
    }

    @Synchronized
    fun process(frame: ShortArray, rms: Float): Ev {
        val threshold = maxOf(Cfg.VAD_MIN_RMS, floor * Cfg.VAD_NOISE_RATIO)
        val energyOk = rms > threshold
        val vocalFormantOk = isHumanVocalFrame(frame, rms)
        val sileroProb = if (energyOk && silero?.isAvailable == true) silero.speechProbability(frame) else -1f

        // यदि Silero TFLite उपलब्ध है तो उसकी पुष्टि अनिवार्य है; अन्यथा फॉर्मैंट + एनर्जी चेक
        val isRealHumanVoice = if (sileroProb >= 0f) {
            energyOk && sileroProb >= Cfg.SILERO_SPEECH_PROB && vocalFormantOk
        } else {
            energyOk && vocalFormantOk
        }

        if (!speaking) {
            if (isRealHumanVoice) {
                onset++
                if (onset >= Cfg.SPEECH_START_FRAMES) {
                    speaking = true
                    voicedMs = onset * Cfg.FRAME_MS
                    silentMs = 0
                    return Ev.SPEECH_START
                }
            } else {
                if (onset > 0) onset--
                // केवल शांत फ्रेम्स से नॉइज़ फ्लोर अपडेट करें
                if (rms < threshold) {
                    floor = (floor * 0.94f + rms * 0.06f).coerceIn(0.003f, 0.035f)
                }
            }
            return Ev.NONE
        }

        // एक बार बोलना शुरू होने के बाद वाक्य के बीच में नरम थ्रेशोल्ड रखें ताकि शब्द न टूटें
        val continuationVoiced = (rms > threshold * 0.72f) && (sileroProb < 0f || sileroProb >= 0.42f)
        if (continuationVoiced) {
            voicedMs += Cfg.FRAME_MS
            silentMs = 0
        } else {
            silentMs += Cfg.FRAME_MS
        }

        if (silentMs >= silenceMs()) {
            val ok = voicedMs >= Cfg.MIN_SPEECH_MS
            speaking = false; onset = 0; voicedMs = 0; silentMs = 0
            silero?.resetState()
            return if (ok) Ev.SPEECH_END else Ev.NOISE
        }
        return Ev.NONE
    }
}
