package com.meraai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class InfoSourceLink(val title: String, val url: String)
data class InfoCardItem(val headline: String, val detail: String)
data class InfoPanelData(
    val query: String = "",
    val title: String = "Information Panel",
    val summary: String = "",
    val cards: List<InfoCardItem> = emptyList(),
    val sources: List<InfoSourceLink> = emptyList(),
    val updatedAt: String = "",
    val grounded: Boolean = false
)

object MeeraInfoPanel {
    val visibleFlow = MutableStateFlow(false)
    val loadingFlow = MutableStateFlow(false)
    val dataFlow = MutableStateFlow<InfoPanelData?>(null)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    fun openPanel() { visibleFlow.value = true }
    fun closePanel() { visibleFlow.value = false }

    fun searchAndShow(ctx: Context, query: String, panelTitle: String = ""): ToolExecutionResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return ToolExecutionResult(true, "live_web_search", "क्या सर्च करना है बताइए?", "ERROR: empty search query")
        }
        val apiKey = Prefs(ctx).apiKey
        if (apiKey.isBlank()) {
            return ToolExecutionResult(true, "live_web_search", "पहले Gemini API Key डालें।", "ERROR: Missing API Key")
        }

        val bgId = MeeraBackgroundMonitor.startTask(
            title = "Web Search: ${cleanQuery.take(38)}",
            category = "Live Info Panel",
            modelUsed = "gemini-google-search",
            initialStep = "Google Search से ताज़ा जानकारी निकाली जा रही है...",
            initialPercent = 20
        )
        loadingFlow.value = true
        visibleFlow.value = true

        return try {
            val prompt = buildString {
                append("Search the live web for the latest, accurate information about: \"")
                append(cleanQuery)
                append("\".\n")
                append("Reply strictly in this clean plain-text format (no markdown stars or hashes):\n")
                append("TITLE: Short crisp title (max 6 words)\n")
                append("SUMMARY: 2 to 3 natural conversational Hinglish sentences summarizing the key answer to speak aloud.\n")
                append("ITEM: Headline 1 || Short 1-2 line crisp fact/update\n")
                append("ITEM: Headline 2 || Short 1-2 line crisp fact/update\n")
                append("ITEM: Headline 3 || Short 1-2 line crisp fact/update\n")
                append("Include 3 to 5 ITEM lines covering the most important latest points.")
            }

            val models = listOf(
                "gemini-flash-lite-latest" to true,
                "gemini-3.5-flash" to true,
                "gemini-2.5-flash" to true,
                "gemini-3.8-flash" to true,
                "gemini-flash-lite-latest" to false
            )

            var rawText = ""
            val foundSources = LinkedHashMap<String, InfoSourceLink>()
            var usedModel = ""
            var isGrounded = false
            var lastErr = ""

            for ((model, useSearchTool) in models) {
                try {
                    val body = JSONObject().put(
                        "contents", JSONArray().put(
                            JSONObject().put("role", "user").put(
                                "parts", JSONArray().put(JSONObject().put("text", prompt))
                            )
                        )
                    )
                    if (useSearchTool) {
                        body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                    }
                    val req = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                        .header("x-goog-api-key", apiKey)
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            lastErr = "HTTP ${resp.code}"
                            MeeraDebugLog.log("INFO_SEARCH", "$model (search=$useSearchTool) -> $lastErr")
                            return@use
                        }
                        val root = JSONObject(resp.body!!.string())
                        val cand = root.optJSONArray("candidates")?.optJSONObject(0) ?: return@use
                        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
                        val sb = StringBuilder()
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                            }
                        }
                        val txt = sb.toString().trim()
                        if (txt.isBlank()) return@use

                        rawText = txt
                        usedModel = model
                        isGrounded = useSearchTool

                        val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
                        if (chunks != null) {
                            for (i in 0 until chunks.length()) {
                                val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                                val uri = web.optString("uri", "").trim()
                                val title = web.optString("title", "").trim().ifBlank {
                                    uri.removePrefix("https://").removePrefix("http://").take(36)
                                }
                                if (uri.startsWith("http") && uri !in foundSources) {
                                    foundSources[uri] = InfoSourceLink(title, uri)
                                }
                            }
                        }
                    }
                    if (rawText.isNotBlank()) break
                } catch (e: Exception) {
                    lastErr = e.message ?: "error"
                    MeeraDebugLog.log("INFO_SEARCH", "$model failed: $lastErr")
                }
            }

            if (rawText.isBlank()) {
                loadingFlow.value = false
                MeeraBackgroundMonitor.completeTask(bgId, false, "वेब सर्च विफल ($lastErr)")
                return ToolExecutionResult(
                    true, "live_web_search",
                    "अभी लाइव सर्च से जानकारी नहीं मिल पाई, कुछ सेकंड बाद फिर कोशिश करें।",
                    "FAILED: Web search failed ($lastErr)."
                )
            }

            val parsed = parseResponse(cleanQuery, panelTitle, rawText, foundSources.values.toList(), isGrounded)
            dataFlow.value = parsed
            loadingFlow.value = false
            visibleFlow.value = true

            val chatSummary = buildString {
                append("🌐 [").append(parsed.title).append("]\n")
                append(parsed.summary).append("\n")
                parsed.cards.take(5).forEach { c ->
                    append("\n• ").append(c.headline).append(": ").append(c.detail)
                }
                if (parsed.sources.isNotEmpty()) {
                    append("\n\n🔗 Sources: ").append(parsed.sources.take(3).joinToString(", ") { it.title })
                }
            }
            MeeraAgentFactory.onDeliverToChat?.invoke("Live Info ($usedModel)", chatSummary)
            MeeraBackgroundMonitor.completeTask(bgId, true, "100% पूरा (${parsed.cards.size} कार्ड, ${parsed.sources.size} लिंक)", chatSummary)

            val spoken = parsed.summary.ifBlank {
                parsed.cards.firstOrNull()?.let { "${it.headline}: ${it.detail}" } ?: "जानकारी स्क्रीन के Information Panel में दिखा दी है।"
            }
            ToolExecutionResult(
                true, "live_web_search",
                spoken,
                "VERIFIED_SUCCESS: Live search completed and Information Panel is now open on screen. Spoken summary: $spoken"
            )
        } catch (e: Exception) {
            loadingFlow.value = false
            MeeraBackgroundMonitor.completeTask(bgId, false, "एरर: ${e.message}")
            ToolExecutionResult(true, "live_web_search", "सर्च करने में दिक्कत आई।", "ERROR: ${e.message}")
        }
    }

    private fun parseResponse(
        query: String,
        preferredTitle: String,
        raw: String,
        sources: List<InfoSourceLink>,
        grounded: Boolean
    ): InfoPanelData {
        var title = preferredTitle.trim()
        var summary = ""
        val cards = mutableListOf<InfoCardItem>()
        val fallbackLines = mutableListOf<String>()

        for (rawLine in raw.lines()) {
            val line = rawLine.trim().replace(Regex("^[*•-]+\\s*"), "").replace("**", "")
            if (line.isBlank()) continue
            when {
                line.startsWith("TITLE:", ignoreCase = true) -> {
                    if (title.isBlank()) title = line.substringAfter(":").trim()
                }
                line.startsWith("SUMMARY:", ignoreCase = true) -> {
                    summary = line.substringAfter(":").trim()
                }
                line.startsWith("ITEM:", ignoreCase = true) -> {
                    val body = line.substringAfter(":").trim()
                    if (body.contains("||")) {
                        val h = body.substringBefore("||").trim()
                        val d = body.substringAfter("||").trim()
                        if (h.isNotBlank() || d.isNotBlank()) {
                            cards.add(InfoCardItem(h.ifBlank { "अपडेट" }, d.ifBlank { h }))
                        }
                    } else if (body.isNotBlank()) {
                        cards.add(InfoCardItem("हाइलाइट", body))
                    }
                }
                else -> fallbackLines.add(line)
            }
        }

        if (summary.isBlank() && fallbackLines.isNotEmpty()) {
            summary = fallbackLines.first()
        }
        if (cards.isEmpty() && fallbackLines.size > 1) {
            fallbackLines.drop(1).take(5).forEachIndexed { idx, ln ->
                val parts = ln.split(":", limit = 2)
                if (parts.size == 2 && parts[0].length <= 60) {
                    cards.add(InfoCardItem(parts[0].trim(), parts[1].trim()))
                } else {
                    cards.add(InfoCardItem("अपडेट #${idx + 1}", ln))
                }
            }
        }
        if (cards.isEmpty() && summary.isNotBlank()) {
            cards.add(InfoCardItem(title.ifBlank { "लाइव अपडेट" }, summary))
        }

        val now = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date())
        return InfoPanelData(
            query = query,
            title = title.ifBlank { query.take(32) },
            summary = summary,
            cards = cards,
            sources = sources,
            updatedAt = now,
            grounded = grounded
        )
    }
}

@Composable
fun MeeraInfoPanelOverlay() {
    val visible by MeeraInfoPanel.visibleFlow.collectAsState()
    val loading by MeeraInfoPanel.loadingFlow.collectAsState()
    val data by MeeraInfoPanel.dataFlow.collectAsState()
    if (!visible) return

    val p = LocalMeeraColors.current
    val ctx = LocalContext.current
    val currentData = data

    Dialog(
        onDismissRequest = { MeeraInfoPanel.closePanel() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = p.panel,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth(0.93f)
                .fillMaxHeight(0.78f)
                .border(1.2.dp, p.accentPrimary.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(p.accentPrimary, p.accentSecondary))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Public, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                currentData?.title ?: "Information Panel",
                                color = p.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (loading) "Google Search से ताज़ा डेटा आ रहा है..."
                                else if (currentData?.grounded == true) "Live Google Search • ${currentData.updatedAt}"
                                else "AI Summary • ${currentData?.updatedAt ?: ""}",
                                color = if (loading) p.warning else p.accentSuccess,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (currentData != null && !loading) {
                            Surface(
                                color = p.card,
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(34.dp)
                                    .border(1.dp, p.cardBorder, CircleShape)
                                    .clickable {
                                        val fullTxt = buildString {
                                            append(currentData.title).append("\n\n").append(currentData.summary).append("\n")
                                            currentData.cards.forEach { append("\n• ${it.headline}: ${it.detail}") }
                                            if (currentData.sources.isNotEmpty()) {
                                                append("\n\nSources:\n")
                                                currentData.sources.forEach { append("- ${it.title}: ${it.url}\n") }
                                            }
                                        }
                                        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cb.setPrimaryClip(ClipData.newPlainText("MeeraInfoPanel", fullTxt))
                                        Toast.makeText(ctx, "पूरी जानकारी कॉपी हो गई!", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", tint = p.accentPrimary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Surface(
                            color = p.card,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(34.dp)
                                .border(1.dp, p.cardBorder, CircleShape)
                                .clickable { MeeraInfoPanel.closePanel() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, contentDescription = "बंद करें", tint = p.textPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = p.cardBorder)
                Spacer(Modifier.height(10.dp))

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (loading) {
                        Surface(
                            color = p.card,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, p.cardBorder, RoundedCornerShape(16.dp))
                        ) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = null, tint = p.accentPrimary, modifier = Modifier.size(22.dp))
                                Text("लाइव इंटरनेट सर्च और न्यूज़ कार्ड्स तैयार हो रहे हैं…", color = p.textPrimary, fontSize = 13.5.sp)
                            }
                        }
                    } else if (currentData == null) {
                        Surface(
                            color = p.card,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, p.cardBorder, RoundedCornerShape(16.dp))
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("कोई लाइव सर्च डेटा नहीं है", color = p.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "मीरा से बोलें — \"आज की AI न्यूज़ बताओ\", \"क्रिकेट स्कोर बताओ\" या \"मौसम कैसा है\" — ताज़ा कार्ड्स और लिंक यहाँ खुल जाएँगे।",
                                    color = p.textSecondary,
                                    fontSize = 12.5.sp
                                )
                            }
                        }
                    } else {
                        if (currentData.summary.isNotBlank()) {
                            Surface(
                                color = p.accentPrimary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, p.accentPrimary.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                            ) {
                                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = p.accentPrimary, modifier = Modifier.size(15.dp))
                                        Text("क्विक समरी", color = p.accentPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(currentData.summary, color = p.textPrimary, fontSize = 13.5.sp)
                                }
                            }
                        }

                        currentData.cards.forEachIndexed { idx, card ->
                            Surface(
                                color = p.card,
                                shape = RoundedCornerShape(16.dp),
                                shadowElevation = if (p.isDark) 0.dp else 1.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, p.cardBorder, RoundedCornerShape(16.dp))
                            ) {
                                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "${idx + 1}. ${card.headline}",
                                        color = p.textPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        card.detail,
                                        color = p.textSecondary,
                                        fontSize = 12.5.sp
                                    )
                                }
                            }
                        }

                        if (currentData.sources.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "वेरीफाइड सोर्स लिंक्स (${currentData.sources.size})",
                                color = p.accentSuccess,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            currentData.sources.take(8).forEach { src ->
                                Surface(
                                    color = p.card,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, p.accentSuccess.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                        .clickable {
                                            try {
                                                val i = Intent(Intent.ACTION_VIEW, Uri.parse(src.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                ctx.startActivity(i)
                                            } catch (_: Exception) {
                                                Toast.makeText(ctx, "लिंक नहीं खुल पाया", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(src.title, color = p.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(src.url, color = p.textSecondary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.Rounded.OpenInNew, contentDescription = "Open", tint = p.accentSuccess, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
