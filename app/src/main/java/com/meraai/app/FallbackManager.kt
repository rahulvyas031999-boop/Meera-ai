package com.meraai.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** error की किस्म: हर किस्म पर अलग बर्ताव (retry / अगला मॉडल / सीधे रुकना) */
enum class ErrKind { AUTH, QUOTA, PERMANENT, TEMPORARY, NETWORK, OTHER }

/** इसे फेंकने का मतलब: "आउटपुट/एक्शन हो चुका है, अब दूसरे मॉडल पर दजाओ” */
class FallbackAbort(cause: Throwable) : Exception(cause.message, cause)

/**
 * सारे फॉलबैक नियमए एकजगह:
 *  1. error classify  2. सीमित retry (backoff+jitter)  3. तय चेन  4. मॉडल की health/cooldown
 *  5. यूज़र को स्क्रीन पर सूचना + लॉग  6. फॉलबैक सिर्फ़ सेशन के लिए (यूज़र की चुनी सेटिंग नहीं बदलती)
 */
object FallbackManager {
    enum class Slot(val label: String) { LIVE("Live"), CHAT("Chat"), STT("STT"), TTS("TTS") }

    /** स्क्रीन के ऊपर दिखाने वाली सूचना (VoiceViewModel यहाँ सुनता है) */
    @Volatile var onNotice: ((String) -> Unit)? = null
    /** चालू मॉडल बदलने पर (UI की "अभी चल रहा है" लाइन अपडेट करने के लिए) */
    @Volatile var onActive: (() -> Unit)? = null

    private val downUntil = ConcurrentHashMap<String, Long>()
    private val active = ConcurrentHashMap<Slot, String>()
    private val lastReason = ConcurrentHashMap<Slot, String>()

    // चेन में सिर्फ़ तेज़/हल्के मॉडल: Pro, Hydrogen और thinking जैसे धीमे मॉडल बैकअप नहीं बनते (वॉइस असिस्टेंट के लिए)
    private val CHAT_FALLBACKS = listOf(
        "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-flash-lite-latest"
    )

    private fun key(s: Slot, m: String) = "${s.name}|$m"

    // ---------- 1. error की पहचान ----------
    fun classify(msg: String?, code: Int? = null, e: Throwable? = null): ErrKind {
        val m = (msg ?: e?.message ?: "").lowercase()
        val c = code ?: Regex("""http (\d{3})""").find(m)?.groupValues?.get(1)?.toIntOrNull()
        if (e is UnknownHostException || m.contains("unable to resolve host") || m.contains("network is unreachable") ||
            m.contains("no address associated")) return ErrKind.NETWORK
        if (c == 401 || c == 403 || m.contains("api key") || m.contains("api_key_invalid") || m.contains("permission_denied")) return ErrKind.AUTH
        if (c == 429 || m.contains("quota") || m.contains("resource_exhausted") || m.contains("rate limit")) return ErrKind.QUOTA
        if (c == 404 || c == 400 || c == 410 || m.contains("not found") || m.contains("not supported") ||
            m.contains("is not found for api version") || m.contains("unsupported") || m.contains("invalid argument")) return ErrKind.PERMANENT
        if (e is SocketTimeoutException || (c != null && c >= 500) || m.contains("timeout") || m.contains("timed out") ||
            m.contains("unavailable") || m.contains("overloaded") || m.contains("busy") || m.contains("internal") ||
            m.contains("connection reset") || m.contains("failed to connect") || m.contains("unexpected end of stream")) return ErrKind.TEMPORARY
        return ErrKind.OTHER
    }

    private fun cooldownMs(k: ErrKind) = when (k) {
        ErrKind.PERMANENT -> 20 * 60_000L
        ErrKind.QUOTA -> 2 * 60_000L
        ErrKind.TEMPORARY -> 45_000L
        else -> 30_000L
    }

    private fun describe(k: ErrKind) = when (k) {
        ErrKind.PERMANENT -> "मॉडल उपलब्ध नहीं"
        ErrKind.QUOTA -> "कोटा/लिमिट पूरी (429)"
        ErrKind.TEMPORARY -> "सर्वर व्यस्त/टाइमआउट"
        else -> "अनजान error"
    }

    // ---------- 2. मॉडल की health (circuit-breaker जैसा) ----------
    fun isDown(s: Slot, m: String): Boolean = (downUntil[key(s, m)] ?: 0L) > System.currentTimeMillis()

    fun markFail(s: Slot, m: String, k: ErrKind) {
        if (k == ErrKind.AUTH || k == ErrKind.NETWORK) return   // यह मॉडल की गलती नहीं
        downUntil[key(s, m)] = System.currentTimeMillis() + cooldownMs(k)
        lastReason[s] = describe(k)
        MeeraDebugLog.log("FALLBACK", "${s.label}: $m फेल (${describe(k)}) — ${cooldownMs(k) / 1000}s के लिए बंद")
    }

    fun markOk(s: Slot, m: String) { downUntil.remove(key(s, m)) }

    fun clearHealth(s: Slot) {
        downUntil.keys.filter { it.startsWith(s.name + "|") }.forEach { downUntil.remove(it) }
    }

    /** यूज़र ने खुद नया मॉडल चुना -> उस स्लॉट की याद साफ़ */
    fun resetSlot(s: Slot) { clearHealth(s); active.remove(s); lastReason.remove(s); onActive?.invoke() }

    // ---------- 3. चेन: पहले यूज़र का चुना, फिर तय बैकअप; चालू (healthy) पहले, बंद वाले आख़िर में ----------
    fun chain(s: Slot, preferred: String): List<String> {
        val base = when (s) {
            Slot.LIVE -> Cfg.LIVE_MODELS
            Slot.CHAT -> CHAT_FALLBACKS.filter { it in Cfg.CHAT_MODELS }
            Slot.STT -> Cfg.STT_MODELS
            Slot.TTS -> Cfg.TTS_MODELS
        }
        val all = (listOf(preferred) + base).distinct()
        val (up, down) = all.partition { !isDown(s, it) }
        return up + down
    }

    fun activeOr(s: Slot, fallback: String): String = active[s] ?: fallback

    /** सफल मॉडल दर्ज करो; बदला हो तो स्क्रीन पर बताओ */
    fun noteUse(s: Slot, model: String, preferred: String) {
        val prev = active.put(s, model)
        if (prev == model) return
        onActive?.invoke()
        if (prev == null && model == preferred) return
        val msg = when {
            model == preferred -> "⬆️ ${s.label}: वापस पहले मॉडल पर — $model"
            prev == null -> "🔄 ${s.label}: $preferred नहीं चला (${lastReason[s] ?: "error"}) → अब चल रहा है: $model"
            else -> "🔄 ${s.label}: $prev → $model (${lastReason[s] ?: "error"})"
        }
        MeeraDebugLog.log("FALLBACK", msg)
        onNotice?.invoke(msg)
    }

    // ---------- Live के लिए (non-suspend) ----------
    fun liveStart(preferred: String): String = chain(Slot.LIVE, preferred).first()

    fun nextLive(current: String, preferred: String): String? =
        chain(Slot.LIVE, preferred).firstOrNull { it != current && !isDown(Slot.LIVE, it) }

    /** चालू फॉलबैक मॉडल से ऊपर वाला (पहले के करीब) कोई मॉडल ठीक हो चुका हो तो वही */
    fun betterLive(current: String, preferred: String): String? {
        val ch = chain(Slot.LIVE, preferred)
        val cur = ch.indexOf(current)
        val best = ch.firstOrNull { !isDown(Slot.LIVE, it) } ?: return null
        return if (ch.indexOf(best) < cur) best else null
    }

    // ---------- 4. Modular (Chat/STT/TTS) के लिए साझा रनर ----------
    suspend fun <T> withFallback(slot: Slot, preferred: String, block: suspend (String) -> T): T {
        var last: Throwable? = null
        for (m in chain(slot, preferred)) {
            var attempt = 0
            while (true) {
                try {
                    val r = block(m)
                    markOk(slot, m)
                    noteUse(slot, m, preferred)
                    return r
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FallbackAbort) {
                    throw e.cause ?: e                         // आउटपुट/एक्शन हो चुका: दोबारा मत चलाओ
                } catch (e: Exception) {
                    val kind = classify(e.message, null, e)
                    MeeraDebugLog.log("FALLBACK", "${slot.label}/$m error=$kind ${e.message?.take(80)}")
                    if (kind == ErrKind.AUTH || kind == ErrKind.NETWORK) throw e   // सब मॉडल पर नतीजा एक जैसा
                    if (kind == ErrKind.TEMPORARY && attempt == 0) {               // एक छोटा retry, फिर अगला मॉडल
                        attempt++
                        delay(250L * (1 shl attempt) + Random.nextLong(0, 250))
                        continue
                    }
                    markFail(slot, m, kind)
                    last = e
                    break
                }
            }
        }
        throw last ?: IOException("${slot.label}: कोई मॉडल जवाब नहीं दे पाया")
    }
}
