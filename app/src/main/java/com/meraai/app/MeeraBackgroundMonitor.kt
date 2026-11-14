package com.meraai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class BackgroundTaskRecord(
    val id: Long,
    val title: String,
    val category: String,
    val modelUsed: String,
    val percent: Int,
    val currentStep: String,
    val status: String,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val stepLogs: List<String>,
    val outputPreview: String = ""
)

object MeeraBackgroundMonitor {
    private val idGen = AtomicLong(1L)
    val monitorVisible = MutableStateFlow(false)
    val tasks = MutableStateFlow<List<BackgroundTaskRecord>>(emptyList())

    fun openMonitor() {
        monitorVisible.value = true
    }

    fun closeMonitor() {
        monitorVisible.value = false
    }

    fun clearHistory() {
        tasks.value = tasks.value.filter { it.status == "RUNNING" }
    }

    private fun nowTime(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    fun startTask(
        title: String,
        category: String,
        modelUsed: String = "gemini-hydrogen",
        initialStep: String = "प्रारंभ हो रहा है...",
        initialPercent: Int = 5
    ): Long {
        val id = idGen.incrementAndGet()
        val now = System.currentTimeMillis()
        val rec = BackgroundTaskRecord(
            id = id,
            title = title.take(80),
            category = category,
            modelUsed = modelUsed,
            percent = initialPercent.coerceIn(0, 100),
            currentStep = initialStep,
            status = "RUNNING",
            startedAtMs = now,
            updatedAtMs = now,
            stepLogs = listOf("[${nowTime()}] (${initialPercent}%) $initialStep")
        )
        tasks.value = (listOf(rec) + tasks.value).take(25)
        MeeraDebugLog.log("BG_TASK", "Started #$id [$category] $title ($initialPercent%)")
        return id
    }

    fun updateProgress(
        taskId: Long,
        percent: Int,
        stepDesc: String,
        modelUsed: String? = null
    ) {
        val now = System.currentTimeMillis()
        val pct = percent.coerceIn(0, 100)
        tasks.value = tasks.value.map { t ->
            if (t.id == taskId) {
                t.copy(
                    percent = pct,
                    currentStep = stepDesc,
                    modelUsed = modelUsed ?: t.modelUsed,
                    updatedAtMs = now,
                    stepLogs = (t.stepLogs + "[${nowTime()}] (${pct}%) $stepDesc").takeLast(30)
                )
            } else t
        }
        MeeraDebugLog.log("BG_TASK", "Progress #$taskId -> $pct% : $stepDesc")
    }

    fun completeTask(
        taskId: Long,
        success: Boolean,
        finalStep: String,
        outputPreview: String = ""
    ) {
        val now = System.currentTimeMillis()
        val finalPct = if (success) 100 else (tasks.value.find { it.id == taskId }?.percent ?: 0)
        val st = if (success) "COMPLETED" else "FAILED"
        tasks.value = tasks.value.map { t ->
            if (t.id == taskId) {
                t.copy(
                    percent = finalPct,
                    currentStep = finalStep,
                    status = st,
                    updatedAtMs = now,
                    stepLogs = (t.stepLogs + "[${nowTime()}] (${finalPct}%) $finalStep").takeLast(30),
                    outputPreview = outputPreview.ifBlank { t.outputPreview }
                )
            } else t
        }
        MeeraDebugLog.log("BG_TASK", "Finished #$taskId [$st] ($finalPct%): $finalStep")
    }

    fun getVoiceProgressReport(): String {
        val running = tasks.value.firstOrNull { it.status == "RUNNING" }
        if (running != null) {
            val sec = String.format(Locale.US, "%.1f", (System.currentTimeMillis() - running.startedAtMs) / 1000.0)
            return "अभी '${running.title}' (${running.category}) पर काम चल रहा है। कुल ${running.percent} प्रतिशत काम पूरा हो गया है — वर्तमान चरण: ${running.currentStep} (मॉडल: ${running.modelUsed}, समय: ${sec} सेकंड)।"
        }
        val latest = tasks.value.firstOrNull()
        if (latest != null) {
            val sec = String.format(Locale.US, "%.1f", (latest.updatedAtMs - latest.startedAtMs) / 1000.0)
            return if (latest.status == "COMPLETED") {
                "आखिरी काम '${latest.title}' 100 प्रतिशत पूरा हो चुका है (${sec} सेकंड में, मॉडल: ${latest.modelUsed})। अभी बैकग्राउंड में कोई नया काम पेंडिंग नहीं है।"
            } else {
                "आखिरी काम '${latest.title}' ${latest.percent} प्रतिशत पर रुका था: ${latest.currentStep}।"
            }
        }
        return "अभी बैकग्राउंड में कोई काम नहीं चल रहा है, सिस्टम 100 प्रतिशत तैयार है।"
    }
}

@Composable
fun MeeraBackgroundActivityScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val taskList by MeeraBackgroundMonitor.tasks.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF090D18))
            .systemBarsPadding()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Color(0xFF16203A),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(40.dp)
                        .border(1.dp, Color(0xFF26355E), CircleShape)
                        .clickable(onClick = onBack)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("←", color = Color.White, fontSize = 20.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Background Activity", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Live Progress % • Engine & Sub-Agent Monitor", color = Color(0xFF2EE6A8), fontSize = 11.sp)
                }
            }
            if (taskList.any { it.status != "RUNNING" }) {
                TextButton(onClick = { MeeraBackgroundMonitor.clearHistory() }) {
                    Text("Clear", color = Color(0xFF8A94B8), fontSize = 12.5.sp)
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (taskList.isEmpty()) {
                Surface(
                    color = Color(0xFF12192E),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF26355E), RoundedCornerShape(18.dp))
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("कोई सक्रिय बैकग्राउंड टास्क नहीं है", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "जब मीरा कोई कोडिंग स्क्रिप्ट लिखेगी, Colab/Termux कमांड चलाएगी या कोई कस्टम एजेंट बनाएगी, तो उसका लाइव प्रतिशत (0%–100%) और हर स्टेप यहाँ दिखेगा।",
                            color = Color(0xFF8A94B8),
                            fontSize = 12.5.sp
                        )
                    }
                }
            } else {
                taskList.forEach { task ->
                    val isRunning = task.status == "RUNNING"
                    val isOk = task.status == "COMPLETED"
                    val accentColor = when {
                        isRunning -> Color(0xFFFFB84D)
                        isOk -> Color(0xFF2EE6A8)
                        else -> Color(0xFFFF6B6B)
                    }
                    val elapsedSec = String.format(
                        Locale.US,
                        "%.1fs",
                        ((if (isRunning) System.currentTimeMillis() else task.updatedAtMs) - task.startedAtMs).coerceAtLeast(100L) / 1000.0
                    )

                    Surface(
                        color = Color(0xFF12192E),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${task.category} • ${task.modelUsed}",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${task.percent}% (${elapsedSec})",
                                    color = accentColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            Text(
                                task.title,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1E293B))
                            ) {
                                val frac = (task.percent / 100f).coerceIn(0.03f, 1f)
                                Box(
                                    Modifier
                                        .fillMaxWidth(frac)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(Color(0xFF5B8CFF), accentColor)
                                            )
                                        )
                                )
                            }

                            Text(
                                "वर्तमान चरण: ${task.currentStep}",
                                color = Color(0xFFE2E8F0),
                                fontSize = 12.5.sp
                            )

                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF070B14), RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                task.stepLogs.forEach { logLine ->
                                    Text(
                                        logLine,
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            if (task.outputPreview.isNotBlank()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Surface(
                                        color = Color(0xFF16203A),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .border(1.dp, Color(0xFF26355E), RoundedCornerShape(10.dp))
                                            .clickable {
                                                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cb.setPrimaryClip(ClipData.newPlainText("MeeraBgOutput", task.outputPreview))
                                                Toast.makeText(ctx, "पूरा आउटपुट क्लिपबोर्ड में कॉपी हो गया!", Toast.LENGTH_SHORT).show()
                                            }
                                    ) {
                                        Text(
                                            "📋 पूरा आउटपुट कॉपी करें",
                                            color = Color(0xFF2EE6A8),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
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
