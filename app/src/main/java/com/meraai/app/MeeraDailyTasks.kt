package com.meraai.app

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DailyTaskItem(
    val id: String,
    val title: String,
    val done: Boolean = false,
    val timeHint: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

object MeeraDailyTasks {
    private const val PREF_NAME = "meera_daily_tasks_prefs"
    private const val KEY_TASKS = "tasks_json_v1"

    val tasksFlow = MutableStateFlow<List<DailyTaskItem>>(emptyList())
    val dialogVisibleFlow = MutableStateFlow(false)
    @Volatile private var loaded = false

    fun openDialog() { dialogVisibleFlow.value = true }
    fun closeDialog() { dialogVisibleFlow.value = false }

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val raw = ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TASKS, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            val list = mutableListOf<DailyTaskItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title", "").trim()
                if (title.isBlank()) continue
                list.add(
                    DailyTaskItem(
                        id = o.optString("id", System.currentTimeMillis().toString() + i),
                        title = title,
                        done = o.optBoolean("done", false),
                        timeHint = o.optString("timeHint", ""),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            tasksFlow.value = list
        } catch (e: Exception) {
            MeeraDebugLog.log("TASKS", "load error: ${e.message}")
        }
    }

    @Synchronized
    private fun persist(ctx: Context, list: List<DailyTaskItem>) {
        tasksFlow.value = list
        try {
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("title", t.title)
                        .put("done", t.done)
                        .put("timeHint", t.timeHint)
                        .put("createdAt", t.createdAt)
                )
            }
            ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_TASKS, arr.toString()).apply()
        } catch (e: Exception) {
            MeeraDebugLog.log("TASKS", "save error: ${e.message}")
        }
    }

    fun addTask(ctx: Context, title: String, timeHint: String = ""): DailyTaskItem? {
        ensureLoaded(ctx)
        val clean = title.trim()
        if (clean.length < 2) return null
        val item = DailyTaskItem(
            id = "t_${System.currentTimeMillis()}_${(100..999).random()}",
            title = clean,
            done = false,
            timeHint = timeHint.trim()
        )
        persist(ctx, listOf(item) + tasksFlow.value)
        return item
    }

    fun toggleTask(ctx: Context, id: String) {
        ensureLoaded(ctx)
        val updated = tasksFlow.value.map { if (it.id == id) it.copy(done = !it.done) else it }
        persist(ctx, updated)
    }

    fun markDoneByQuery(ctx: Context, query: String): DailyTaskItem? {
        ensureLoaded(ctx)
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return null
        val list = tasksFlow.value
        val target = list.firstOrNull { !it.done && it.title.lowercase(Locale.ROOT).contains(q) }
            ?: list.firstOrNull { it.title.lowercase(Locale.ROOT).contains(q) }
            ?: return null
        val updated = list.map { if (it.id == target.id) it.copy(done = true) else it }
        persist(ctx, updated)
        return target.copy(done = true)
    }

    fun deleteTask(ctx: Context, id: String) {
        ensureLoaded(ctx)
        persist(ctx, tasksFlow.value.filterNot { it.id == id })
    }

    fun deleteByQuery(ctx: Context, query: String): DailyTaskItem? {
        ensureLoaded(ctx)
        val q = query.trim().lowercase(Locale.ROOT)
        val target = tasksFlow.value.firstOrNull { it.title.lowercase(Locale.ROOT).contains(q) } ?: return null
        persist(ctx, tasksFlow.value.filterNot { it.id == target.id })
        return target
    }

    fun clearCompleted(ctx: Context) {
        ensureLoaded(ctx)
        persist(ctx, tasksFlow.value.filterNot { it.done })
    }

    fun buildDailyBriefingTool(ctx: Context, period: String = "auto"): ToolExecutionResult {
        ensureLoaded(ctx)
        MeeraFileVault.ensureLoaded(ctx)

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dateStr = SimpleDateFormat("EEEE, dd MMM", Locale.getDefault()).format(Date())
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        val slot = when (period.trim().lowercase(Locale.ROOT)) {
            "morning", "सुबह" -> "Morning Briefing"
            "afternoon", "दोपहर" -> "Afternoon Briefing"
            "evening", "शाम" -> "Evening Briefing"
            "night", "रात" -> "Night Wrap-Up"
            else -> when (hour) {
                in 5..11 -> "Morning Briefing"
                in 12..16 -> "Afternoon Briefing"
                in 17..20 -> "Evening Briefing"
                else -> "Night Wrap-Up"
            }
        }

        val allTasks = tasksFlow.value
        val pending = allTasks.filterNot { it.done }
        val completed = allTasks.filter { it.done }

        val notifRaw = try { MeeraNotificationService.getLatestSummary().trim() } catch (_: Exception) { "" }
        val hasRealNotif = notifRaw.isNotBlank() &&
            !notifRaw.contains("कोई नया नोटिफिकेशन नहीं") &&
            !notifRaw.contains("कोई नोटिफिकेशन नहीं") &&
            !notifRaw.contains("बंद है")

        val vaultFiles = MeeraFileVault.filesFlow.value
        val bgRunning = MeeraBackgroundMonitor.tasks.value.firstOrNull { it.status == "RUNNING" }

        val spoken = buildString {
            append("$slot ($dateStr, $timeStr): ")
            if (allTasks.isEmpty()) {
                append("आज की टास्क लिस्ट में अभी कोई टास्क नहीं है। ")
            } else if (pending.isEmpty()) {
                append("शाबाश! आपके आज के सारे ${allTasks.size} टास्क पूरे हो चुके हैं। ")
            } else {
                append("आज के कुल ${allTasks.size} में से ${completed.size} टास्क पूरे हैं और ${pending.size} बाकी हैं — ")
                append(pending.take(3).joinToString(", ") { t ->
                    if (t.timeHint.isNotBlank()) "${t.title} (${t.timeHint})" else t.title
                })
                append("। ")
            }
            if (hasRealNotif) {
                append("ताज़ा नोटिफिकेशन में: ${notifRaw.replace("\n", " ").take(120)}। ")
            } else {
                append("अभी कोई ज़रूरी अनपढ़ा नोटिफिकेशन नहीं है। ")
            }
            if (bgRunning != null) {
                append("बैकग्राउंड में '${bgRunning.title}' ${bgRunning.percent} प्रतिशत पूरा हो चुका है।")
            }
        }.trim()

        val cards = mutableListOf<InfoCardItem>()
        if (pending.isNotEmpty()) {
            val pendingText = pending.mapIndexed { i, t ->
                val hint = if (t.timeHint.isNotBlank()) " [${t.timeHint}]" else ""
                "${i + 1}. ${t.title}$hint"
            }.joinToString("\n")
            cards.add(InfoCardItem("बाकी टास्क (${pending.size}/${allTasks.size})", pendingText))
        } else {
            cards.add(InfoCardItem("Today's Tasks", if (allTasks.isEmpty()) "अभी कोई टास्क जोड़ा नहीं गया है।" else "सभी ${allTasks.size} टास्क पूरे हो गए हैं!"))
        }
        if (completed.isNotEmpty()) {
            cards.add(InfoCardItem("पूरे हुए टास्क (${completed.size})", completed.take(5).joinToString(", ") { it.title }))
        }
        cards.add(
            InfoCardItem(
                "नोटिफिकेशन अपडेट",
                if (hasRealNotif) notifRaw.take(300) else "कोई नया अनपढ़ा नोटिफिकेशन नहीं है।"
            )
        )
        if (vaultFiles.isNotEmpty()) {
            cards.add(InfoCardItem("File Vault (${vaultFiles.size} फ़ाइलें)", "सबसे ताज़ा: ${vaultFiles.first().name} (${vaultFiles.first().kind})"))
        }

        MeeraInfoPanel.dataFlow.value = InfoPanelData(
            query = slot,
            title = "$slot • $dateStr",
            summary = spoken,
            cards = cards,
            sources = emptyList(),
            updatedAt = timeStr,
            grounded = false
        )
        MeeraInfoPanel.loadingFlow.value = false
        MeeraInfoPanel.visibleFlow.value = true

        MeeraAgentFactory.onDeliverToChat?.invoke(slot, "📋 [$slot • $dateStr]\n$spoken")
        return ToolExecutionResult(
            true,
            "get_daily_briefing",
            spoken,
            "VERIFIED_SUCCESS: Delivered $slot and opened briefing cards on screen. Summary: $spoken"
        )
    }

    fun getTasksSummaryForPrompt(ctx: Context): String {
        ensureLoaded(ctx)
        val all = tasksFlow.value
        if (all.isEmpty()) return ""
        val pending = all.filterNot { it.done }
        val doneCnt = all.size - pending.size
        val sb = StringBuilder("\n[TODAY'S TASKS ($doneCnt/${all.size} DONE)]:\n")
        pending.take(6).forEach { t ->
            val h = if (t.timeHint.isNotBlank()) " (${t.timeHint})" else ""
            sb.append("- [ ] ${t.title}$h\n")
        }
        if (pending.isEmpty()) sb.append("- All tasks completed!\n")
        return sb.toString().trimEnd()
    }
}

@Composable
fun HomeDailyTasksCard(vm: VoiceViewModel) {
    val p = LocalMeeraColors.current
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { MeeraDailyTasks.ensureLoaded(ctx) }
    val tasks by MeeraDailyTasks.tasksFlow.collectAsState()
    val total = tasks.size
    val done = tasks.count { it.done }
    val pending = tasks.firstOrNull { !it.done }
    val progress = if (total == 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    Surface(
        color = p.card,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = if (p.isDark) 0.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, p.cardBorder, RoundedCornerShape(16.dp))
            .clickable { MeeraDailyTasks.openDialog() }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(Icons.Rounded.TaskAlt, contentDescription = null, tint = p.accentPrimary, modifier = Modifier.size(17.dp))
                    Text("Today's Tasks", color = p.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (total == 0) "• खाली" else "• $done/$total",
                        color = if (total > 0 && done == total) p.accentSuccess else p.textSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = p.accentPrimary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.clickable {
                            vm.sendTextQuery("मेरा डेली ब्रीफिंग बताओ", speakAloud = true)
                        }
                    ) {
                        Text(
                            "Briefing",
                            color = p.accentPrimary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(p.accentSuccess.copy(alpha = 0.16f))
                            .clickable { MeeraDailyTasks.openDialog() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add task", tint = p.accentSuccess, modifier = Modifier.size(15.dp))
                    }
                }
            }

            if (total > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(p.cardBorder)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceAtLeast(0.02f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Brush.horizontalGradient(listOf(p.accentPrimary, p.accentSuccess)))
                    )
                }
            }

            Text(
                when {
                    pending != null -> "अगला: ${pending.title}" + if (pending.timeHint.isNotBlank()) " (${pending.timeHint})" else ""
                    total > 0 -> "सभी $total टास्क पूरे हो गए! टैप करके देखें।"
                    else -> "टैप करें या बोलें: \"आज के टास्क में जोड़ो...\""
                },
                color = p.textSecondary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun MeeraDailyTasksDialog(vm: VoiceViewModel) {
    val visible by MeeraDailyTasks.dialogVisibleFlow.collectAsState()
    if (!visible) return

    val p = LocalMeeraColors.current
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { MeeraDailyTasks.ensureLoaded(ctx) }
    val tasks by MeeraDailyTasks.tasksFlow.collectAsState()
    var newTitle by remember { mutableStateOf("") }
    var newTime by remember { mutableStateOf("") }

    val total = tasks.size
    val done = tasks.count { it.done }
    val pct = if (total == 0) 0 else (done * 100 / total)

    Dialog(
        onDismissRequest = { MeeraDailyTasks.closeDialog() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = p.panel,
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth(0.93f)
                .fillMaxHeight(0.76f)
                .border(1.2.dp, p.accentPrimary.copy(alpha = 0.65f), RoundedCornerShape(24.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(p.accentPrimary, p.accentSuccess))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.TaskAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                        }
                        Column {
                            Text("Today's Tasks", color = p.textPrimary, fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold)
                            Text("$done / $total पूरे ($pct%)", color = p.accentSuccess, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Surface(
                        color = p.card,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(34.dp)
                            .border(1.dp, p.cardBorder, CircleShape)
                            .clickable { MeeraDailyTasks.closeDialog() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Close, contentDescription = "बंद करें", tint = p.textPrimary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(p.cardBorder)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((pct / 100f).coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Brush.horizontalGradient(listOf(p.accentPrimary, p.accentSuccess)))
                    )
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(p.accentPrimary, p.accentSecondary)))
                        .clickable {
                            MeeraDailyTasks.closeDialog()
                            vm.sendTextQuery("मेरा डेली ब्रीफिंग बताओ", speakAloud = true)
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text("मेरा Daily Briefing सुनाओ", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        placeholder = { Text("नया टास्क लिखें…", color = p.textSecondary, fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary,
                            focusedBorderColor = p.accentPrimary,
                            unfocusedBorderColor = p.cardBorder
                        ),
                        modifier = Modifier.weight(1.6f)
                    )
                    OutlinedTextField(
                        value = newTime,
                        onValueChange = { newTime = it },
                        placeholder = { Text("समय", color = p.textSecondary, fontSize = 11.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary,
                            focusedBorderColor = p.accentPrimary,
                            unfocusedBorderColor = p.cardBorder
                        ),
                        modifier = Modifier.weight(0.8f)
                    )
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(p.accentSuccess)
                            .clickable {
                                if (newTitle.isNotBlank()) {
                                    MeeraDailyTasks.addTask(ctx, newTitle, newTime)
                                    newTitle = ""
                                    newTime = ""
                                    Toast.makeText(ctx, "टास्क जुड़ गया!", Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "जोड़ें", tint = Color(0xFF090D18), modifier = Modifier.size(22.dp))
                    }
                }

                HorizontalDivider(color = p.cardBorder)

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (tasks.isEmpty()) {
                        Text(
                            "अभी कोई टास्क नहीं है। ऊपर लिखकर जोड़ें या मीरा से बोलें: \"आज के टास्क में 6 बजे जिम जाना जोड़ दो\"।",
                            color = p.textSecondary,
                            fontSize = 12.5.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    } else {
                        tasks.forEach { item ->
                            Surface(
                                color = p.card,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, if (item.done) p.accentSuccess.copy(alpha = 0.45f) else p.cardBorder, RoundedCornerShape(14.dp))
                                    .clickable { MeeraDailyTasks.toggleTask(ctx, item.id) }
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        if (item.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (item.done) p.accentSuccess else p.textSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            item.title,
                                            color = if (item.done) p.textSecondary else p.textPrimary,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None
                                        )
                                        if (item.timeHint.isNotBlank()) {
                                            Text(item.timeHint, color = p.accentPrimary, fontSize = 11.sp)
                                        }
                                    }
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "हटाएँ",
                                        tint = p.danger.copy(alpha = 0.85f),
                                        modifier = Modifier.size(19.dp).clickable { MeeraDailyTasks.deleteTask(ctx, item.id) }
                                    )
                                }
                            }
                        }
                    }
                }

                if (done > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { MeeraDailyTasks.clearCompleted(ctx) }) {
                            Text("पूरे हुए टास्क हटाएँ ($done)", color = p.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
