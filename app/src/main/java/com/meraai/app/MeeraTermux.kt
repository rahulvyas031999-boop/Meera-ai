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
    val running: Boolean = true,
    val startedAtMs: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L
)

object MeeraTermuxEngine {
    const val PERM_TERMUX = "com.termux.permission.RUN_COMMAND"
    const val ACTION_RESULT = "com.meraai.app.TERMUX_RESULT"
    const val EXTRA_ENTRY_ID = "meera_entry_id"

    private val idGen = AtomicLong(100L)
    val entries = MutableStateFlow<List<TermuxLogEntry>>(emptyList())
    val terminalVisible = MutableStateFlow(false)
    private val latches = ConcurrentHashMap<Long, CountDownLatch>()

    private val FOLLOW_UP_PROMPTS = listOf(
        "बताइए, टर्मक्स में और कोई कमांड चलानी है या बस इतना ही?",
        "यह काम तो पूरा हो गया, अब आगे क्या करना है?",
        "क्या टर्मक्स में कुछ और रन करना है, या यहीं खत्म करें?",
        "सब तैयार है! बताइए अगला क्या काम है या डैशबोर्ड पर चलें?",
        "और कुछ करवाना है टर्मक्स में, या अब वापस चलें?"
    )
    @Volatile private var lastFollowUpIdx = -1

    fun nextFollowUpPrompt(): String {
        val candidates = FOLLOW_UP_PROMPTS.indices.filter { it != lastFollowUpIdx }
        val chosen = candidates.random()
        lastFollowUpIdx = chosen
        return FOLLOW_UP_PROMPTS[chosen]
    }

    fun openTerminal(ctx: Context? = null) {
        terminalVisible.value = true
        if (ctx != null) {
            try {
                val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    ctx.startActivity(launch)
                }
            } catch (_: Exception) {}
        }
    }

    fun closeTerminal() {
        terminalVisible.value = false
    }

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
        waitMs: Long = 15000L,
        autoOpenUi: Boolean = true
    ): ToolExecutionResult {
        val cmd = rawCommand.trim()
        if (cmd.isBlank()) {
            return ToolExecutionResult(true, "run_termux_command", "Termux में क्या कमांड चलाना है बताइए?", "ERROR: Empty command")
        }

        if (autoOpenUi) {
            openTerminal(ctx)
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
                "मैंने Terminal स्क्रीन खोल दी है — ऊपर दिख रहे 'Allow karo' बटन को दबा दें ताकि मैं कमांड चला सकूँ।",
                "FAILED_PERMISSION: Opened Meera Terminal screen, but com.termux.permission.RUN_COMMAND is not granted yet. Ask user to tap 'Allow karo' on screen."
            )
        }

        val entryId = idGen.incrementAndGet()
        val nowMs = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(nowMs))
        val newEntry = TermuxLogEntry(
            id = entryId,
            time = timeStr,
            command = cmd,
            running = true,
            startedAtMs = nowMs
        )
        entries.value = (entries.value + newEntry).takeLast(40)
        val bgId = MeeraBackgroundMonitor.startTask("Termux: ${cmd.take(40)}", "Termux Terminal", "Android Bash", "कमांड चल रही है: $cmd", 35)

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
                val cmdOk = (latest.exitCode ?: 0) == 0
                MeeraBackgroundMonitor.completeTask(bgId, cmdOk, if (cmdOk) "100% पूरा (exit=0)" else "एरर (exit=${latest.exitCode})", latest.stdout.ifBlank { latest.stderr })
                val outCombined = buildString {
                    if (latest.stdout.isNotBlank()) append(latest.stdout.trim())
                    if (latest.stderr.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("ERR: ").append(latest.stderr.trim())
                    }
                }.ifBlank { "(Command finished with exitCode=${latest.exitCode ?: 0}, no text output)" }

                val secStr = String.format(Locale.US, "%.1fs", latest.durationMs / 1000.0)
                val ok = (latest.exitCode ?: 0) == 0
                val followUp = nextFollowUpPrompt()
                ToolExecutionResult(
                    true, "run_termux_command",
                    if (ok) "कमांड पूरा हो गया ($secStr)! आउटपुट स्क्रीन पर है: ${outCombined.take(160)} — $followUp"
                    else "कमांड में एरर आया: ${outCombined.take(160)} — बताओ क्या करूँ?",
                    if (ok) "VERIFIED_SUCCESS: Termux executed '$cmd' in $secStr (exit=0) and displayed output on Meera Terminal screen.\nFULL_OUTPUT:\n${outCombined.take(2500)}\nINSTRUCTIONS: Report the actual output clearly to Rahul. If more commands are pending in a multi-step task, run the next command immediately. Once all commands are done, ask a natural varied follow-up such as: '$followUp' (never repeat the exact same closing phrase every time)."
                    else "FAILED: Termux command '$cmd' failed (exit=${latest.exitCode}).\nFULL_OUTPUT:\n${outCombined.take(2500)}"
                )
            } else {
                ToolExecutionResult(
                    true, "run_termux_command",
                    "मैंने Terminal स्क्रीन खोलकर '$cmd' चालू कर दिया है, आउटपुट लाइव आ रहा है।",
                    "VERIFIED_SUCCESS: Dispatched '$cmd' to Termux and opened Meera Terminal UI (still running, output streaming live)."
                )
            }
        } catch (e: Exception) {
            latches.remove(entryId)
            onCommandResult(entryId, "", e.message ?: "Failed to start Termux service", -1)
            ToolExecutionResult(
                true, "run_termux_command",
                "Termux से कनेक्ट नहीं हो पाया। Terminal स्क्रीन पर ऊपर 'Setup' दबाकर allow-external-apps=true सेट करें।",
                "FAILED: Termux RUN_COMMAND exception -> ${e.message}"
            )
        }
    }

    fun onCommandResult(entryId: Long, stdout: String, stderr: String, exitCode: Int) {
        val now = System.currentTimeMillis()
        entries.value = entries.value.map { item ->
            if (item.id == entryId) {
                val dur = (now - item.startedAtMs).coerceAtLeast(50L)
                item.copy(
                    stdout = stdout,
                    stderr = stderr,
                    exitCode = exitCode,
                    running = false,
                    durationMs = dur
                )
            } else item
        }
        latches.remove(entryId)?.countDown()
        MeeraDebugLog.log("TERMUX", "Result #$entryId exit=$exitCode out=${stdout.take(150)}")
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
    var showSetupBox by remember { mutableStateOf(logs.isEmpty()) }
    var cmdInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        permTick++
    }

    val scrollTrigger = logs.hashCode()
    LaunchedEffect(scrollTrigger) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    val setupCmd = "mkdir -p ~/.termux && echo \"allow-external-apps = true\" >> ~/.termux/termux.properties && termux-reload-settings"

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF090D18))
            .systemBarsPadding()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    Text("Terminal", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text("meera@termux • Live Output", color = Color(0xFF2EE6A8), fontSize = 11.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    color = if (showSetupBox) Color(0xFF5B8CFF).copy(alpha = 0.25f) else Color(0xFF16203A),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .border(1.dp, Color(0xFF26355E), RoundedCornerShape(12.dp))
                        .clickable { showSetupBox = !showSetupBox }
                ) {
                    Text(
                        "⚙️ Setup",
                        color = Color(0xFF2EE6A8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                if (logs.isNotEmpty()) {
                    TextButton(onClick = { MeeraTermuxEngine.clearLogs() }) {
                        Text("Clear", color = Color(0xFF8A94B8), fontSize = 12.5.sp)
                    }
                }
            }
        }

        // Permission Card (Maya Style "Allow karo")
        if (!installed || !hasPerm) {
            Surface(
                color = Color(0xFF16203A),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF5B8CFF), RoundedCornerShape(18.dp))
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Termux commands", color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (!installed) "Phone me pehle Termux app install karein (F-Droid / GitHub)."
                            else "Taaki Meera tumhare liye Termux me commands chala sake, permission allow karo.",
                            color = Color(0xFF8A94B8),
                            fontSize = 12.sp
                        )
                    }
                    if (installed && !hasPerm) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF5B8CFF), Color(0xFF38BDF8))))
                                .clickable { permLauncher.launch(MeeraTermuxEngine.PERM_TERMUX) }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                        ) {
                            Text("Allow karo", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Collapsible 1-Time Termux Setup Card
        if (showSetupBox) {
            Surface(
                color = Color(0xFF12192E),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF26355E), RoundedCornerShape(14.dp))
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("1-Time Termux Setup", color = Color(0xFF2EE6A8), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "📋 Copy",
                                color = Color(0xFF5B8CFF),
                                fontSize = 11.5.sp,
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
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { MeeraTools.openAppByName(ctx, "Termux") }
                            )
                        }
                    }
                    Text(
                        setupCmd,
                        color = Color(0xFFCBD5E1),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Full-Screen Live Dark Terminal Console & Complete Output View
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        val ok = (entry.exitCode ?: 0) == 0
                        val durSec = String.format(Locale.US, "%.1fs", entry.durationMs / 1000.0)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0B1020), RoundedCornerShape(12.dp))
                                .border(
                                    1.dp,
                                    if (entry.running) Color(0xFFFFB84D).copy(alpha = 0.45f)
                                    else if (ok) Color(0xFF2EE6A8).copy(alpha = 0.35f)
                                    else Color(0xFFFF6B6B).copy(alpha = 0.45f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "$ ${entry.command}",
                                    color = Color(0xFF2EE6A8),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (entry.running) "⏳ running..."
                                    else if (ok) "✓ done in $durSec"
                                    else "✗ exit:${entry.exitCode} ($durSec)",
                                    color = if (entry.running) Color(0xFFFFB84D)
                                    else if (ok) Color(0xFF2EE6A8)
                                    else Color(0xFFFF6B6B),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            if (!entry.running && entry.stdout.isBlank() && entry.stderr.isBlank()) {
                                Text(
                                    "(Command completed with exit code ${entry.exitCode ?: 0}, no console text printed)",
                                    color = Color(0xFF64748B),
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            if (entry.stdout.isNotBlank()) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF060913), RoundedCornerShape(8.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("OUTPUT:", color = Color(0xFF38BDF8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            "📋 Copy",
                                            color = Color(0xFF8A94B8),
                                            fontSize = 10.5.sp,
                                            modifier = Modifier.clickable {
                                                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cb.setPrimaryClip(ClipData.newPlainText("TermuxOut", entry.stdout.trim()))
                                                Toast.makeText(ctx, "Output copy हो गया", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                    Text(
                                        entry.stdout.trim(),
                                        color = Color(0xFFF1F5F9),
                                        fontSize = 12.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            if (entry.stderr.isNotBlank()) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF1F0A10), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Text("ERROR:", color = Color(0xFFFF6B6B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        entry.stderr.trim(),
                                        color = Color(0xFFFF9494),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
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
                                MeeraTermuxEngine.execute(ctx, toRun, background = true, waitMs = 15000L, autoOpenUi = false)
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
