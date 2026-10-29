package com.meraai.app

import android.annotation.SuppressLint
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

fun rmsOf(s: ShortArray): Float {
    if (s.isEmpty()) return 0f
    var sum = 0.0
    for (v in s) { val f = v / 32768.0; sum += f * f }
    return sqrt(sum / s.size).toFloat()
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

/** माइक: 16kHz mono, VOICE_COMMUNICATION source + हार्डवेयर Echo Canceller / Noise Suppressor / AGC (जहाँ फ़ोन सपोर्ट करे). */
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
            maxOf(min, Cfg.FRAME_SAMPLES * 2 * 6)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("माइक शुरू नहीं हो पाया")
        }
        val sid = r.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sid)?.let { it.setEnabled(true); fx += it }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sid)?.let { it.setEnabled(true); fx += it }
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sid)?.let { it.setEnabled(true); fx += it }
        rec = r
        running = true
        r.startRecording()
        thread = Thread {
            val buf = ShortArray(Cfg.FRAME_SAMPLES)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) onFrame(buf.copyOf(n)) else if (n < 0) break
            }
        }.also { it.isDaemon = true; it.start() }
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

/** 24kHz PCM16 स्ट्रीमिंग प्लेयर. flush() से तुरंत चुप (barge-in). */
class PcmPlayer {
    private class Chunk(val data: ByteArray, val gen: Int)

    private val track: AudioTrack
    private val queue = LinkedBlockingQueue<Chunk>()
    private val pending = AtomicInteger(0)
    @Volatile private var gen = 0
    @Volatile private var playUntil = 0L
    @Volatile private var alive = true
    @Volatile var level = 0f
        private set
    private val thread: Thread

    val busy: Boolean get() = pending.get() > 0 || System.nanoTime() < playUntil

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
            .setBufferSizeInBytes(maxOf(min, rate * 2 / 5))
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

/** Turn detection: adaptive noise-floor VAD + silence timeout (डिफ़ॉल्ट 900ms) + न्यूनतम बोलने की अवधि. */
class TurnDetector(private val silenceMs: () -> Int) {
    enum class Ev { NONE, SPEECH_START, SPEECH_END, NOISE }

    private var floor = 0.004f
    private var speaking = false
    private var onset = 0
    private var voicedMs = 0
    private var silentMs = 0

    @Synchronized fun reset() { speaking = false; onset = 0; voicedMs = 0; silentMs = 0 }

    @Synchronized fun forceSpeaking() { speaking = true; onset = 0; voicedMs = Cfg.MIN_SPEECH_MS; silentMs = 0 }

    @Synchronized
    fun process(rms: Float): Ev {
        val threshold = maxOf(Cfg.VAD_MIN_RMS, floor * Cfg.VAD_NOISE_RATIO)
        val voiced = rms > threshold
        if (!speaking) {
            if (voiced) {
                onset++
                if (onset >= Cfg.SPEECH_START_FRAMES) {
                    speaking = true; voicedMs = onset * Cfg.FRAME_MS; silentMs = 0
                    return Ev.SPEECH_START
                }
            } else {
                onset = 0
                floor = (floor * 0.95f + rms * 0.05f).coerceIn(0.001f, 0.03f)
            }
            return Ev.NONE
        }
        if (voiced) { voicedMs += Cfg.FRAME_MS; silentMs = 0 } else silentMs += Cfg.FRAME_MS
        if (silentMs >= silenceMs()) {
            val ok = voicedMs >= Cfg.MIN_SPEECH_MS
            speaking = false; onset = 0; voicedMs = 0; silentMs = 0
            return if (ok) Ev.SPEECH_END else Ev.NOISE
        }
        return Ev.NONE
    }
}
