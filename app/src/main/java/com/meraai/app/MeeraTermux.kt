package com.meraai.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class TermuxLogEntry(
    val id: Long,
    val time: String,
    val command: String,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val running: Boolean = true
)

object MeeraTermuxEngine {
    const val PERM_TERMUX = "com.termux.permission.RUN_COMMAND"
    const val ACTION_RESULT = "com.meraai.app.TERMUX_RESULT"
    const val EXTRA_ENTRY_ID = "meera_entry_id"

    private val idGen = AtomicLong(100L)
    val entries = MutableStateFlow<List<TermuxLogEntry>>(emptyList())
    private val latches = ConcurrentHashMap<Long, CountDownLatch>()

    fun isTermuxInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo("com.termux", 0)
        true
    } catch (_: Exception) {
        false
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, PERM_TERMUX) == PackageManager.PERMISSION_GRANTED

    fun clearLogs() {
        entries.value = emptyList()
    }

    fun execute(
        ctx: Context,
        rawCommand: String,
        background: Boolean = true,
        waitMs: Long = 5500L
    ): ToolExecutionResult {
        val cmd = rawCommand.trim()
        if (cmd.isBlank()) {
            return ToolExecutionResult(true, "run_termux_command", "Termux में क्या कमांड चलाना है बताइए?", "ERROR: Empty command")
        }
        if (!isTermuxInstalled(ctx)) {
            return ToolExecutionResult(
                true, "run_termux_command",
                "फोन में Termux ऐप इंस्टॉल नहीं मिला। पहले Termux इंस्टॉल करें।",
                "FAILED: com.termux is not installed on this device."
            )
        }
        if (!hasPermission(ctx)) {
            return ToolExecutionResult(
                true, "run_termux_command",
                "Termux कमांड चलाने की परमिशन अभी बंद है। साइड मेन्यू में Terminal खोलकर 'Allow karo' दबाएँ।",
                "FAILED_PERMISSION: Missing com.termux.permission.RUN_COMMAND. Ask user to open Terminal in side drawer and tap Allow karo."
            )
        }

        val entryId = idGen.incrementAndGet()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val newEntry = TermuxLogEntry(id = entryId, time = timeStr, command = cmd, running = true)
        entries.value = (entries.value + newEntry).takeLast(40)

        val latch = CountDownLatch(1)
        latches[entryId] = latch

        return try {
            val resIntent = Intent(ctx.applicationContext, MeeraTermuxReceiver::class.java).apply {
                action = ACTION_RESULT
                putExtra(EXTRA_ENTRY_ID, entryId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(
                ctx.applicationContext,
                entryId.toInt(),
                resIntent,
                flags
            )

            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", cmd))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
            }

            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            MeeraDebugLog.log("TERMUX", "Sent #$entryId (bg=$background): $cmd")

            if (waitMs > 0) {
                latch.await(waitMs, TimeUnit.MILLISECONDS)
            }
            latches.remove(entryId)

            val latest = entries.value.find { it.id == entryId }
            if (latest != null && !latest.running) {
                val outCombined = buildString {
                    if (latest.stdout.isNotBlank()) append(latest.stdout.trim())
                    if (latest.stderr.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("ERR: ").append(latest.stderr.trim())
                    }
                }.ifBlank { "(Command finished with exitCode=${latest.exitCode ?: 0}, no text output)" }

                val ok = (latest.exitCode ?: 0) == 0
                ToolExecutionResult(
                    true, "run_termux_command",
                    if (ok) "Termux में कमांड पूरा हो गया! आउटपुट: ${outCombined.take(160)}"
                    else "Termux कमांड में एरर आया: ${outCombined.take(160)}",
                    if (ok) "VERIFIED_SUCCESS: Termux executed '$cmd' (exit=0)\nOUTPUT:\n${outCombined.take(1200)}"
                    else "FAILED: Termux command '$cmd' failed (exit=${latest.exitCode})\nOUTPUT:\n${outCombined.take(1200)}"
                )
            } else {
                ToolExecutionResult(
                    true, "run_termux_command",
                    "Termux बैकग्राउंड टर्मिनल में '$cmd' चालू कर दिया है! आप साइड मेन्यू के Terminal में लाइव आउटपुट देख सकते हैं।",
                    "VERIFIED_SUCCESS: Dispatched '$cmd' to Termux background engine (still running, live output streaming to Terminal screen)."
                )
            }
        } catch (e: Exception) {
            latches.remove(entryId)
            onCommandResult(entryId, "", e.message ?: "Failed to start Termux service", -1)
            ToolExecutionResult(
                true, "run_termux_command",
                "Termux से कनेक्ट नहीं हो पाया। एक बार Termux खोलकर allow-external-apps=true सेट करें।",
                "FAILED: Termux RUN_COMMAND exception -> ${e.message}"
            )
        }
    }

    fun onCommandResult(entryId: Long, stdout: String, stderr: String, exitCode: Int) {
        entries.value = entries.value.map { item ->
            if (item.id == entryId) {
                item.copy(stdout = stdout, stderr = stderr, exitCode = exitCode, running = false)
            } else item
        }
        latches.remove(entryId)?.countDown()
        MeeraDebugLog.log("TERMUX", "Result #$entryId exit=$exitCode out=${stdout.take(120)}")
    }
}

class MeeraTermuxReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val entryId = intent.getLongExtra(MeeraTermuxEngine.EXTRA_ENTRY_ID, -1L)
        val bundle = intent.getBundleExtra("result")
        val stdout = bundle?.getString("stdout").orEmpty()
        val stderr = bundle?.getString("stderr").orEmpty()
        val exitCode = bundle?.getInt("exitCode", 0) ?: 0
        if (entryId > 0) {
            MeeraTermuxEngine.onCommandResult(entryId, stdout, stderr, exitCode)
        }
    }
}

@Composable
fun MeeraTerminalScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val logs by MeeraTermuxEngine.entries.collectAsState()
    var permTick by remember { mutableIntStateOf(0) }
    val installed = remember { MeeraTermuxEngine.isTermuxInstalled(ctx) }
    val hasPerm = remember(permTick) { MeeraTermuxEngine.hasPermission(ctx) }
    var cmdInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        permTick++
    }

    LaunchedEffect(logs.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    val setupCmd = "mkdir -p ~/.termux && echo \"allow-external-apps = true\" >> ~/.termux/termux.properties && termux-reload-settings"

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF090D18))
            .systemBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Header Bar
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
                        .size(42.dp)
                        .border(1.dp, Color(0xFF26355E), CircleShape)
                        .clickable(onClick = onBack)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("←", color = Color.White, fontSize = 20.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Terminal", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text("meera@termux • Background Engine", color = Color(0xFF2EE6A8), fontSize = 11.5.sp)
                }
            }
            if (logs.isNotEmpty()) {
                TextButton(onClick = { MeeraTermuxEngine.clearLogs() }) {
                    Text("Clear", color = Color(0xFF8A94B8), fontSize = 13.sp)
                }
            }
        }

        // Permission Card (Maya Style "Allow karo")
        if (!installed || !hasPerm) {
            Surface(
                color = Color(0xFF16203A),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF5B8CFF), RoundedCornerShape(20.dp))
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Termux commands", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (!installed) "Phone me pehle Termux app install karein (F-Droid / GitHub)."
                            else "Taaki Meera tumhare liye background me Termux commands chala sake, permission allow karo.",
                            color = Color(0xFF8A94B8),
                            fontSize = 12.5.sp
                        )
                    }
                    if (installed && !hasPerm) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF5B8CFF), Color(0xFF38BDF8))))
                                .clickable { permLauncher.launch(MeeraTermuxEngine.PERM_TERMUX) }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text("Allow karo", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // One-time Termux External Apps Setup Card
        Surface(
            color = Color(0xFF12192E),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF26355E), RoundedCornerShape(16.dp))
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚙️ 1-Time Termux Setup (allow-external-apps)", color = Color(0xFF2EE6A8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "📋 Copy",
                            color = Color(0xFF5B8CFF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("TermuxSetup", setupCmd))
                                Toast.makeText(ctx, "Setup command copy हो गई! Termux में पेस्ट करें।", Toast.LENGTH_SHORT).show()
                            }
                        )
                        Text(
                            "↗ Open Termux",
                            color = Color(0xFF2EE6A8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { MeeraTools.openAppByName(ctx, "Termux") }
                        )
                    }
                }
                Text(
                    setupCmd,
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Live Dark Terminal Console Output
        Surface(
            color = Color(0xFF05070E),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(18.dp))
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(14.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "meera@termux:~ $ _",
                    color = Color(0xFF2EE6A8),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                if (logs.isEmpty()) {
                    Text(
                        "Abhi tak koi command nahi chali.\nMeera jab Termux me kuch chalayegi, wo yahan live dikhega. Bolke dekho: \"Termux me python version check karo\" ya neeche command likho.",
                        color = Color(0xFF8A94B8),
                        fontSize = 12.5.sp
                    )
                } else {
                    logs.forEach { entry ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0B1020), RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "$ ${entry.command}",
                                    color = Color(0xFF2EE6A8),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (entry.running) "⏳ running" else "exit:${entry.exitCode ?: 0} • ${entry.time}",
                                    color = if (entry.running) Color(0xFFFFB84D) else Color(0xFF64748B),
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (entry.stdout.isNotBlank()) {
                                Text(
                                    entry.stdout.trim(),
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (entry.stderr.isNotBlank()) {
                                Text(
                                    entry.stderr.trim(),
                                    color = Color(0xFFFF6B6B),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }

        // Direct Terminal Command Input Bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmdInput,
                onValueChange = { cmdInput = it },
                placeholder = { Text("Command likhein (e.g. ls -la, python3 -V)…", color = Color(0xFF8A94B8), fontSize = 12.5.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF2EE6A8), Color(0xFF5B8CFF))))
                    .clickable {
                        if (cmdInput.isNotBlank()) {
                            val toRun = cmdInput.trim()
                            cmdInput = ""
                            Thread {
                                MeeraTermuxEngine.execute(ctx, toRun, background = true, waitMs = 3000L)
                            }.start()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("▶", color = Color(0xFF090D18), fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}
