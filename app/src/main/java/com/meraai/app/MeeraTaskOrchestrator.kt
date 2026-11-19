package com.meraai.app

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class UiQueueJob(
    val jobId: Int,
    val goal: String,
    val returnToDashboard: Boolean
)

/**
 * Production-Grade 3-Lane Agentic Task Orchestrator (Talker + Doer Architecture):
 * - Lane 1 (Voice / Talker): Always free in VoiceViewModel for natural conversation.
 * - Lane 2 (Screen / UI Worker): Strict FIFO Serial Queue so Accessibility actions never collide.
 * - Lane 3 (Cognitive / Cloud Worker Army): Parallel Coroutine Pool (up to 4 concurrent workers)
 *   for stories, message drafts, research, coding, Hydrogen, Colab, and Termux.
 * All workers share 100% of Meera's SQLite Memory, Skills, Workflows, and Tools.
 */
object MeeraTaskOrchestrator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicInteger(1)
    private val uiQueue = Channel<UiQueueJob>(Channel.UNLIMITED)
    private val pendingUiGoals = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, String>>()
    private val activeParallelJobs = ConcurrentHashMap<Int, Pair<String, Job>>()
    private val parallelSemaphore = Semaphore(4)

    @Volatile private var uiLoopStarted = false
    @Volatile private var currentUiJob: Job? = null
    @Volatile private var currentUiGoal: String = ""
    @Volatile private var appCtxRef: Context? = null

    /** Callback into VoiceViewModel so Meera can proactively report completion without blocking */
    @Volatile var onTaskUpdate: ((title: String, ok: Boolean, summary: String) -> Unit)? = null

    private val workerHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Synchronized
    private fun ensureUiLoopStarted(ctx: Context) {
        appCtxRef = ctx.applicationContext
        if (uiLoopStarted) return
        uiLoopStarted = true
        scope.launch {
            for (job in uiQueue) {
                pendingUiGoals.removeAll { it.first == job.jobId }
                currentUiGoal = job.goal
                val runJob = launch {
                    runSingleUiJob(appCtxRef ?: ctx.applicationContext, job)
                }
                currentUiJob = runJob
                try {
                    runJob.join()
                } catch (_: Exception) {
                } finally {
                    currentUiJob = null
                    currentUiGoal = ""
                }
            }
        }
    }

    /** Enqueue a screen/Accessibility task into the Serial FIFO Queue and return immediately */
    fun enqueueUiTask(ctx: Context, goal: String, returnToDashboard: Boolean = false): ToolExecutionResult {
        val clean = goal.trim()
        if (clean.isBlank()) {
            return ToolExecutionResult(true, "dispatch_ui_task", "बताइए स्क्रीन पर क्या काम करना है?", "ERROR: empty goal")
        }
        ensureUiLoopStarted(ctx)
        val jid = idCounter.getAndIncrement()
        val position = pendingUiGoals.size + (if (currentUiGoal.isNotBlank()) 1 else 0) + 1
        pendingUiGoals.add(jid to clean)
        uiQueue.trySend(UiQueueJob(jid, clean, returnToDashboard))

        val reply = if (position == 1) {
            "ठीक है, मैंने '$clean' वाला काम बैकग्राउंड UI वर्कर को दे दिया है—वह अभी शुरू कर रहा है। तब तक आप मुझसे आराम से बात कर सकते हैं!"
        } else {
            "ठीक है, मैंने '$clean' को कतार (नंबर $position) में लगा दिया है—पहला स्क्रीन काम पूरा होते ही यह अपने-आप शुरू हो जाएगा!"
        }
        return ToolExecutionResult(
            true,
            "dispatch_ui_task",
            reply,
            "VERIFIED_SUCCESS: Dispatched UI task #$jid ('$clean') to Serial UI Queue (position=$position, returnToDashboard=$returnToDashboard). You are now FREE to continue natural conversation with the user."
        )
    }

    /** Launch a Parallel Cognitive/Cloud Worker with 100% shared Meera memory and return immediately */
    fun dispatchParallelTask(
        ctx: Context,
        taskKind: String,
        instruction: String,
        profession: String = ""
    ): ToolExecutionResult {
        val clean = instruction.trim()
        if (clean.isBlank()) {
            return ToolExecutionResult(true, "dispatch_background_task", "बताइए बैकग्राउंड में क्या तैयार करना है?", "ERROR: empty instruction")
        }
        val app = ctx.applicationContext
        appCtxRef = app
        val jid = idCounter.getAndIncrement()
        val kind = taskKind.lowercase().trim().ifBlank { "hydrogen" }
        val title = "${kind.replaceFirstChar { it.uppercase() }}: ${clean.take(38)}"

        val job = scope.launch {
            parallelSemaphore.withPermit {
                try {
                    val relevantMemories = MeeraMemoryManager.hybridSearch(app, clean, topK = 8)
                    val memContext = if (relevantMemories.isNotEmpty()) {
                        "\n[SHARED MEERA MEMORY CONTEXT]:\n" + relevantMemories.joinToString("\n") { "- $it" }
                    } else ""

                    val result = when (kind) {
                        "code", "coding" -> MeeraAgentFactory.generateProfessionalCode(app, clean + memContext)
                        "agent", "specialist" -> MeeraAgentFactory.spawnAndRun(app, profession.ifBlank { "Domain Specialist" }, clean + memContext)
                        "colab" -> MeeraColabBridge.runColabTask(app, "general", clean)
                        "termux" -> MeeraColabBridge.runTermuxCommand(app, clean)
                        else -> MeeraAgentFactory.askHydrogen(app, clean + memContext)
                    }
                    val ok = result.rawDataForAi.startsWith("VERIFIED_SUCCESS") || result.rawDataForAi.startsWith("SUCCESS")
                    val report = result.spokenReply.ifBlank { if (ok) "'$title' पूरा हो गया है और Chat टैब में सेव है।" else "'$title' में दिक्कत आई।" }
                    onTaskUpdate?.invoke(title, ok, report)
                } catch (e: CancellationException) {
                    MeeraDebugLog.log("ORCH", "Parallel job #$jid cancelled")
                    throw e
                } catch (e: Exception) {
                    onTaskUpdate?.invoke(title, false, "'$title' पूरा नहीं हो पाया: ${e.message}")
                } finally {
                    activeParallelJobs.remove(jid)
                }
            }
        }
        activeParallelJobs[jid] = title to job

        return ToolExecutionResult(
            true,
            "dispatch_background_task",
            "ठीक है, मैंने '$clean' का काम बैकग्राउंड वर्कर को सौंप दिया है—वह मेरी पूरी मेमोरी के साथ पैरेलल में तैयार कर रहा है। आप बातचीत जारी रखिए!",
            "VERIFIED_SUCCESS: Dispatched parallel worker #$jid ($kind) for '$clean'. Active parallel workers=${activeParallelJobs.size}. You are now FREE to continue talking."
        )
    }

    fun getQueueStatusReport(): String {
        val sb = StringBuilder()
        if (currentUiGoal.isNotBlank()) {
            sb.append("📱 अभी स्क्रीन पर चल रहा काम: $currentUiGoal. ")
        }
        if (pendingUiGoals.isNotEmpty()) {
            sb.append("📋 कतार में अगले स्क्रीन काम (${pendingUiGoals.size}): ${pendingUiGoals.joinToString(", ") { it.second }}. ")
        }
        if (activeParallelJobs.isNotEmpty()) {
            sb.append("🧠 पैरेलल बैकग्राउंड वर्कर्स (${activeParallelJobs.size}): ${activeParallelJobs.values.joinToString(", ") { it.first }}. ")
        }
        return if (sb.isEmpty()) {
            "अभी कोई बैकग्राउंड या कतार वाला काम नहीं चल रहा है, सारे वर्कर्स फ्री हैं।"
        } else {
            sb.toString().trim()
        }
    }

    fun cancelTasks(target: String = "all"): ToolExecutionResult {
        val q = target.lowercase().trim()
        var cancelledCount = 0
        if (q.isBlank() || q == "all" || q.contains("सब") || q.contains("ui") || q.contains("screen")) {
            if (currentUiJob?.isActive == true) {
                currentUiJob?.cancel()
                cancelledCount++
            }
            while (uiQueue.tryReceive().isSuccess) {
                cancelledCount++
            }
            pendingUiGoals.clear()
            MeeraMission.clear()
        }
        for ((jid, pair) in activeParallelJobs.entries.toList()) {
            if (q.isBlank() || q == "all" || q.contains("सब") || pair.first.lowercase().contains(q)) {
                pair.second.cancel()
                activeParallelJobs.remove(jid)
                cancelledCount++
            }
        }
        return ToolExecutionResult(
            true,
            "cancel_background_tasks",
            if (cancelledCount > 0) "मैंने $cancelledCount बैकग्राउंड/कतार वाले काम रोक दिए हैं।" else "अभी कोई चलता हुआ काम नहीं मिला जिसे रोका जाए।",
            "VERIFIED_SUCCESS: Cancelled $cancelledCount background/queued task(s)."
        )
    }

    /** Full-Capability Autonomous UI Worker (Shares 100% of Meera's Memory, Workflows & Tools) */
    private suspend fun runSingleUiJob(ctx: Context, job: UiQueueJob) {
        val prefs = Prefs(ctx)
        val rest = GeminiRest(workerHttp) { prefs.apiKey }
        val svc = MeeraAccessibilityService.instance
        if (svc == null) {
            onTaskUpdate?.invoke(job.goal.take(40), false, "स्क्रीन का काम करने के लिए Accessibility सर्विस ऑन करनी होगी।")
            return
        }

        val bgId = MeeraBackgroundMonitor.startTask(
            title = "UI Worker: ${job.goal.take(42)}",
            category = "Screen Worker Queue",
            modelUsed = "gemini-3.8-flash",
            initialStep = "मेमोरी और स्क्रीन देखकर प्लान बना रहा है...",
            initialPercent = 10
        )

        try {
            val isMultiStep = job.goal.contains(" और ") || job.goal.contains(" and ") ||
                job.goal.contains("फिर") || job.goal.contains("then") || job.goal.contains("comment") || job.goal.contains("कमेंट")
            if (!isMultiStep) {
                val direct = MeeraTools.tryDirectVoiceCommand(ctx, job.goal)
                if (direct.executed && (direct.rawDataForAi.startsWith("VERIFIED_SUCCESS") || direct.rawDataForAi.startsWith("SUCCESS"))) {
                    if (job.returnToDashboard) returnToMeeraDashboard(ctx)
                    MeeraBackgroundMonitor.completeTask(bgId, true, "100% पूरा: ${direct.spokenReply}")
                    onTaskUpdate?.invoke(job.goal.take(40), true, direct.spokenReply)
                    return
                }
            }

            val relevantMem = MeeraMemoryManager.hybridSearch(ctx, job.goal, topK = 6)
            val savedWfs = MeeraWorkflowStore.getAllWorkflows(ctx).keys.joinToString(", ")
            val goalWithContext = buildString {
                append(job.goal)
                if (relevantMem.isNotEmpty()) {
                    append("\n[SHARED MEMORY]: ").append(relevantMem.joinToString(" | "))
                }
                if (savedWfs.isNotBlank()) {
                    append("\n[SAVED WORKFLOWS]: ").append(savedWfs)
                }
            }

            val initialScreen = svc.readScreenElements(60)
            val plan = rest.generatePlan(goalWithContext, initialScreen)
            MeeraMission.set(plan.joinToString("\n"))

            var stepIdx = 0
            var consecutiveFailures = 0
            var lastReflection = "UI Worker started with shared Meera memory."
            var finalSummary = ""
            val maxTurns = 14

            while (stepIdx < maxTurns) {
                stepIdx++
                val pct = (15 + (stepIdx * 75 / maxTurns)).coerceAtMost(90)
                val liveScreen = svc.readScreenElements(120)
                val decision = rest.decideNextAgentAction(
                    overallGoal = goalWithContext,
                    missionStatus = MeeraMission.status(),
                    currentScreen = liveScreen,
                    lastReflection = lastReflection
                ) ?: rest.routeCommand(plan.getOrNull(stepIdx - 1) ?: job.goal)

                if (decision == null || decision.first == "none" || decision.first.isBlank()) break
                if (decision.first == "done") {
                    finalSummary = decision.second.optString("summary", "'${job.goal}' पूरा हो गया है!")
                    break
                }

                MeeraBackgroundMonitor.updateProgress(bgId, pct, "Step $stepIdx: ${decision.first}")
                val res = MeeraTools.executeFunctionCall(ctx, decision.first, decision.second)
                if (res.spokenReply.isNotBlank()) finalSummary = res.spokenReply

                val isHardFail = res.rawDataForAi.startsWith("FAILED") ||
                    res.rawDataForAi.startsWith("ERROR") ||
                    res.rawDataForAi.startsWith("BLOCKED_REPEAT_TAP") ||
                    res.rawDataForAi.contains("SCREEN_UNCHANGED") ||
                    res.rawDataForAi.contains("PING_PONG_LOOP_DETECTED")

                if (isHardFail) {
                    consecutiveFailures++
                    lastReflection = "Step ${decision.first} failed: ${res.rawDataForAi.take(220)}"
                    if (consecutiveFailures == 2) {
                        val hint = decision.second.optString("text", decision.second.optString("target_or_text", ""))
                        val rec = MeeraTools.executeFunctionCall(ctx, "recover_stuck_step", JSONObject().put("target_text", hint))
                        lastReflection = "Recovery: ${rec.rawDataForAi.take(180)}"
                        if (rec.rawDataForAi.startsWith("VERIFIED_SUCCESS")) consecutiveFailures = 0
                    }
                    if (consecutiveFailures >= 3) {
                        MeeraMission.clear(ctx)
                        MeeraBackgroundMonitor.completeTask(bgId, false, "स्टेप $stepIdx पर अटक गया")
                        onTaskUpdate?.invoke(job.goal.take(40), false, "'${job.goal}' स्टेप $stepIdx पर अटक गया, एक बार स्क्रीन देख लीजिए।")
                        return
                    }
                } else {
                    consecutiveFailures = 0
                    MeeraMission.markDone(stepIdx.coerceAtMost(plan.size))
                    lastReflection = "Step $stepIdx (${decision.first}) succeeded."
                }
                svc.waitForScreenSettle(1000L)
            }

            if (job.returnToDashboard) returnToMeeraDashboard(ctx)
            MeeraMission.clear(ctx)
            val doneMsg = finalSummary.ifBlank { "'${job.goal}' पूरा हो गया है!" }
            MeeraBackgroundMonitor.completeTask(bgId, true, "100% पूरा: $doneMsg")
            onTaskUpdate?.invoke(job.goal.take(40), true, doneMsg)
        } catch (e: CancellationException) {
            MeeraMission.clear()
            MeeraBackgroundMonitor.completeTask(bgId, false, "यूज़र द्वारा रोका गया")
            throw e
        } catch (e: Exception) {
            MeeraMission.clear()
            MeeraBackgroundMonitor.completeTask(bgId, false, "एरर: ${e.message}")
            onTaskUpdate?.invoke(job.goal.take(40), false, "'${job.goal}' करते समय दिक्कत आई: ${e.message}")
        }
    }

    private fun returnToMeeraDashboard(ctx: Context) {
        try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                ctx.startActivity(intent)
            }
        } catch (_: Exception) {}
    }
}
