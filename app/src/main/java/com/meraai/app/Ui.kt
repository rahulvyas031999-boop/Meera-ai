package com.meraai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

val Bg = Color(0xFF090D18)
private val Panel = Color(0xFF12192E)
private val CardGlass = Color(0xFF16203A)
private val CardBorder = Color(0xFF26355E)
private val AccentCyan = Color(0xFF2EE6A8)
private val AccentBlue = Color(0xFF5B8CFF)
private val AccentPurple = Color(0xFFA855F7)
private val Muted = Color(0xFF8A94B8)

@Composable
fun MeraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AccentBlue, background = Bg, surface = Panel, onSurface = Color.White, onBackground = Color.White
        ),
        content = content
    )
}

@Composable
fun MeraApp(vm: VoiceViewModel, onMic: () -> Unit) {
    var splash by remember { mutableStateOf(true) }
    var showSetup by remember { mutableStateOf(!vm.isSetupDone()) }
    LaunchedEffect(Unit) { delay(1800); splash = false }
    Crossfade(targetState = Triple(splash, showSetup, Unit), animationSpec = tween(450), label = "root") { (isSplash, isSetup, _) ->
        when {
            isSplash -> SplashScreen()
            isSetup -> PermissionsSetupScreen(onFinish = {
                vm.markSetupDone()
                showSetup = false
            })
            else -> Dashboard(vm, onMic, onOpenSetup = { showSetup = true })
        }
    }
}

@Composable
fun SplashScreen() {
    val t = rememberInfiniteTransition(label = "splash")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(90.dp).alpha(a)
                    .background(Brush.radialGradient(listOf(Color(0xFF9DB7FF), AccentBlue, Color(0xFF1B2A6B))), CircleShape)
            )
            Spacer(Modifier.height(28.dp))
            Text("Meera AI", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("स्टार्ट हो रही है…", color = Muted, fontSize = 15.sp, modifier = Modifier.alpha(a))
        }
    }
}

private fun statusText(s: UiState): String = when (s.state) {
    VState.IDLE -> "माइक दबाएँ या नीचे लिखकर पूछें"
    VState.CONNECTING -> "कनेक्ट हो रहा है…"
    VState.LISTENING -> "सुन रही हूँ…"
    VState.USER_SPEAKING -> "आप बोल रहे हैं"
    VState.PROCESSING -> "सोच रही हूँ…"
    VState.ASSISTANT_SPEAKING -> "बोल रही हूँ…"
    VState.ERROR -> s.error ?: "कुछ गड़बड़ हुई"
}

private fun greetingText(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..11 -> "सुप्रभात,"
        in 12..16 -> "नमस्ते,"
        in 17..21 -> "शुभ संध्या,"
        else -> "नमस्ते,"
    }
}

@Composable
fun Dashboard(vm: VoiceViewModel, onMic: () -> Unit, onOpenSetup: () -> Unit = {}) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentTab by remember { mutableStateOf(NavTab.HOME) }
    var showKey by remember { mutableStateOf(false) }
    var showA11yHub by remember { mutableStateOf(false) }
    val orbCtx = LocalContext.current
    var orbStyle by remember { mutableStateOf(MeeraOrbPrefs.getStyle(orbCtx)) }
    var orbSizeDp by remember { mutableFloatStateOf(MeeraOrbPrefs.getSizeDp(orbCtx)) }
    LaunchedEffect(ui.askKey) { if (ui.askKey) { showKey = true; vm.consumeAskKey() } }

    val active = ui.state != VState.IDLE && ui.state != VState.ERROR
    val view = LocalView.current
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Panel) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Meera AI", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                            val pkgInfo = remember { runCatching { view.context.packageManager.getPackageInfo(view.context.packageName, 0) }.getOrNull() }
                            Text("Smart Agentic Voice Companion • v${pkgInfo?.versionName ?: "2.0"}", color = AccentCyan, fontSize = 11.sp)
                        }
                        TextButton(onClick = { scope.launch { drawer.close() } }) {
                            Text("✕ बंद करें", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    NavigationDrawerItem(
                        label = { Text("API Key") },
                        icon = { Text("🔑") },
                        badge = { Text(if (ui.hasKey) "सेव ✓" else "डालें", color = Muted) },
                        selected = false,
                        onClick = { scope.launch { drawer.close() }; showKey = true },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    NavigationDrawerItem(
                        label = { Text("Accessibility & Control Hub") },
                        icon = { Text("🚹") },
                        badge = {
                            val on = MeeraAccessibilityService.instance != null
                            Text(if (on) "चालू ✓" else "ऑन करें", color = if (on) AccentCyan else Color(0xFFFFB84D))
                        },
                        selected = false,
                        onClick = { scope.launch { drawer.close() }; showA11yHub = true },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    NavigationDrawerItem(
                        label = { Text("Permissions & Setup (परमिशंस)") },
                        icon = { Text("🛡️") },
                        badge = { Text("Mint UI", color = AccentCyan) },
                        selected = false,
                        onClick = { scope.launch { drawer.close() }; onOpenSetup() },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    val logCtx = LocalContext.current
                    val curVerName = remember { runCatching { logCtx.packageManager.getPackageInfo(logCtx.packageName, 0).versionName ?: "2.0" }.getOrDefault("2.0") }
                    NavigationDrawerItem(
                        label = { Text("ऐप अपडेट करें (1-Tap Update)") },
                        icon = { Text("🔄") },
                        badge = { Text("v$curVerName", color = AccentCyan) },
                        selected = false,
                        onClick = { scope.launch { drawer.close() }; MeeraUpdater.checkAndInstallUpdate(logCtx) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    NavigationDrawerItem(
                        label = { Text("Debug log कॉपी करें") },
                        icon = { Text("📋") },
                        selected = false,
                        onClick = { scope.launch { drawer.close() }; MeeraDebugLog.copyNow(logCtx) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF232C52))
                    var openSec by remember { mutableStateOf("") }
                    DropSection("3D Orb डिज़ाइन (Visualizer)", MeeraOrbPrefs.styleLabel(orbStyle), openSec == "orb_style", { openSec = if (openSec == "orb_style") "" else "orb_style" }) {
                        MeeraOrbPrefs.STYLES.forEach { (sCode, sLabel) ->
                            ModeRow(sLabel, "60 FPS Native 3D Audio Reactive Shader", orbStyle == sCode) {
                                orbStyle = sCode
                                MeeraOrbPrefs.setStyle(orbCtx, sCode)
                                openSec = ""
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("📏 3D Orb का साइज़", color = Muted, fontSize = 12.sp)
                            Text("${orbSizeDp.toInt()} dp", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = orbSizeDp,
                            onValueChange = {
                                orbSizeDp = it
                                MeeraOrbPrefs.setSizeDp(orbCtx, it)
                            },
                            valueRange = 150f..310f
                        )
                    }
                    HorizontalDivider(color = Color(0xFF232C52))
                    DropSection("पर्सनैलिटी चुनें", "🎭 ${ui.persona}", openSec == "persona", { openSec = if (openSec == "persona") "" else "persona" }) {
                        Cfg.PERSONAS.forEach { (pCode, pDesc, _) ->
                            ModeRow("🎭 $pCode", pDesc, ui.persona == pCode) { vm.setPersona(pCode); openSec = "" }
                        }
                    }

                    if (ui.persona == "Meera") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("💖 Girlfriend / Companion Mode", color = Color.White, fontSize = 15.sp)
                                Text("प्यार, नटखट नखरे, केयर और AI रोस्ट ऑन", color = Muted, fontSize = 12.sp)
                            }
                            Switch(checked = ui.girlfriendMode, onCheckedChange = { vm.setGirlfriendMode(it) })
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFF232C52))
                    Text("बातचीत का तरीका", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    ModeRow("⚡ Live API", "Gemini Live — आवाज़ से आवाज़ + Live Tools", ui.mode == Mode.LIVE) { vm.setMode(Mode.LIVE) }
                    ModeRow("🧩 Modular", "Fast STT → Brain + Tools → Expressive TTS", ui.mode == Mode.MODULAR) { vm.setMode(Mode.MODULAR) }

                    if (ui.mode == Mode.LIVE) {
                        DropSection("Live मॉडल", ui.liveModel, openSec == "live", { openSec = if (openSec == "live") "" else "live" }) {
                            Cfg.LIVE_MODELS.forEach { m ->
                                ModeRow(m, "WebSocket Bidi Audio + Function Calling", ui.liveModel == m) { vm.setLiveModel(m); openSec = "" }
                            }
                        }
                    } else {
                        DropSection("चैट ब्रेन (LLM / Reasoning)", ui.chatModel, openSec == "chat", { openSec = if (openSec == "chat") "" else "chat" }) {
                            Cfg.CHAT_BRAIN_OPTIONS.forEach { (mCode, mDesc) ->
                                ModeRow(mCode, mDesc, ui.chatModel == mCode) { vm.setChatModel(mCode); openSec = "" }
                            }
                        }
                        DropSection("सुनने का इंजन (STT)", ui.sttModel, openSec == "stt", { openSec = if (openSec == "stt") "" else "stt" }) {
                            Cfg.STT_ENGINE_OPTIONS.forEach { (sCode, sDesc) ->
                                ModeRow(sCode, sDesc, ui.sttModel == sCode) { vm.setSttModel(sCode); openSec = "" }
                            }
                        }
                        DropSection("आवाज़ इंजन (TTS)", ui.ttsModel, openSec == "tts", { openSec = if (openSec == "tts") "" else "tts" }) {
                            Cfg.TTS_ENGINE_OPTIONS.forEach { (tCode, tDesc) ->
                                ModeRow(tCode, tDesc, ui.ttsModel == tCode) { vm.setTtsModel(tCode); openSec = "" }
                            }
                        }
                    }

                    val vTag = Cfg.VOICES.firstOrNull { it.first == ui.voice }?.second?.substringAfter("(")?.substringBefore(" —") ?: ""
                    val vWarn = if (vTag.isNotEmpty() && !vTag.split("/").contains(ui.persona)) "  ⚠️ $vTag की आवाज़" else ""
                    DropSection("आवाज़ चुनें (Google Voices)", "🔊 ${ui.voice}$vWarn", openSec == "voice", { openSec = if (openSec == "voice") "" else "voice" }) {
                        listOf("Meera" to "Meera की आवाज़ें", "Jarvis" to "Jarvis की आवाज़ें", "Jarvis/Rudra" to "Jarvis / Rudra दोनों की आवाज़", "Rudra" to "Rudra की आवाज़ें").forEach { (tag, heading) ->
                            val group = Cfg.VOICES.filter { it.second.substringAfter("(").substringBefore(" —") == tag }
                            if (group.isNotEmpty()) {
                                Text("— $heading —", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                                group.forEach { (vCode, vLabel) ->
                                    ModeRow("🔊 $vCode", vLabel, ui.voice == vCode) { vm.setVoice(vCode); openSec = "" }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        if (showA11yHub) {
            AccessibilityHubScreen(onBack = { showA11yHub = false })
        } else {
            Column(
                Modifier.fillMaxSize().background(Bg).systemBarsPadding()
            ) {
                // ---------- Top Header Bar ----------
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        color = CardGlass,
                        shape = CircleShape,
                        modifier = Modifier.size(42.dp).border(1.dp, CardBorder, CircleShape)
                            .clickable { scope.launch { drawer.open() } }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Menu, contentDescription = "मेन्यू", tint = Color.White)
                        }
                    }

                    val gfTag = if (ui.persona == "Meera" && ui.girlfriendMode) " 💖" else ""
                    val modeTag = if (ui.mode == Mode.LIVE) "Live" else "Modular"
                    Surface(
                        color = CardGlass,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.border(1.dp, CardBorder, RoundedCornerShape(20.dp))
                    ) {
                        Text(
                            "${ui.persona}$gfTag • $modeTag • ${ui.voice}",
                            color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }

                    Surface(
                        color = CardGlass,
                        shape = CircleShape,
                        modifier = Modifier.size(42.dp).border(1.dp, CardBorder, CircleShape)
                            .clickable { showA11yHub = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val a11yOn = MeeraAccessibilityService.instance != null
                            Text(if (a11yOn) "🟢" else "🚹", fontSize = 16.sp)
                        }
                    }
                }

                // ---------- Active Tab Content ----------
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (currentTab) {
                        NavTab.HOME -> HomeTabContent(ui, vm, active, orbStyle, orbSizeDp)
                        NavTab.VISION -> VisionCameraTab(vm)
                        NavTab.YAADEIN -> YaadeinTabContent(vm)
                        NavTab.CHAT -> ChatTabContent(ui, vm)
                    }
                }

                if (active) {
                    val muted by MicMute.flow.collectAsState()
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center) {
                        Surface(
                            color = if (muted) Color(0xFFE5484D).copy(alpha = 0.25f) else CardGlass,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .border(1.dp, if (muted) Color(0xFFE5484D) else CardBorder, RoundedCornerShape(20.dp))
                                .clickable { vm.toggleMute() }
                        ) {
                            Text(
                                if (muted) "🔇 म्यूट है — दबाकर चालू करें" else "🎙️ सुन रही है — म्यूट करें",
                                color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // ---------- Bottom Floating Aurora Glass Dock ----------
                BottomAuroraDock(
                    currentTab = currentTab,
                    micActive = active,
                    onSelectTab = { currentTab = it },
                    onMicClick = onMic
                )
            }
        }
    }

    if (showKey) {
        KeyDialog(
            hasKey = ui.hasKey,
            onSave = { vm.saveKey(it); showKey = false },
            onDismiss = { showKey = false }
        )
    }
}

@Composable
private fun HomeTabContent(ui: UiState, vm: VoiceViewModel, active: Boolean, orbStyle: String = "LIQUID_PLASMA", orbSizeDp: Float = 225f) {
    var quickInput by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Greeting + Status Row
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(greetingText(), color = Muted, fontSize = 14.sp)
                Text("Rahul", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            Surface(
                color = CardGlass,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🧠 ${ui.memoryCount} यादें", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.weight(0.6f))

        // Center Stage: Clean 3D Dynamic Orb (Controlled via Side Drawer Menu)
        MeeraDynamicOrb(
            state = ui.state,
            vm = vm,
            orbStyle = orbStyle,
            persona = ui.persona,
            modifier = Modifier.size(orbSizeDp.dp)
        )

        Spacer(Modifier.height(10.dp))
        Text(
            statusText(ui),
            color = if (ui.state == VState.ERROR) Color(0xFFFF6B6B) else Color.White,
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.latencyMs?.let { Text("⏱ $it ms", color = Muted, fontSize = 12.sp) }
            ui.lastToolStatus?.let { Text(it, color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }

        // Live Transcript Preview Card
        if (ui.user.isNotBlank() || ui.ai.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = CardGlass.copy(alpha = 0.85f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(18.dp))
            ) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (ui.user.isNotBlank()) {
                        Text(ui.user, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                    if (ui.ai.isNotBlank()) {
                        Text(ui.ai, color = Color.White, fontSize = 14.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        Spacer(Modifier.weight(0.5f))

        // Quick Action Pills (WhatsApp, Screen Read, YouTube, Instagram)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickChip("📱 WhatsApp खोलो") {
                MeeraTools.openAppByName(ctx, "WhatsApp")
            }
            QuickChip("👀 स्क्रीन पढ़ो") {
                vm.sendTextQuery("स्क्रीन पर क्या लिखा है पढ़कर बताओ", speakAloud = true)
            }
            QuickChip("▶️ YouTube गाने") {
                vm.sendTextQuery("YouTube पर हिंदी गाने चलाओ", speakAloud = false)
            }
            QuickChip("📸 Instagram") {
                MeeraTools.openAppByName(ctx, "Instagram")
            }
        }

        Spacer(Modifier.height(10.dp))

        // Ask Meera Input Bar
        Surface(
            color = CardGlass,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(26.dp))
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = quickInput,
                    onValueChange = { quickInput = it },
                    placeholder = { Text("Meera से कुछ भी पूछो या ऐप खुलवाओ…", color = Muted, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(AccentBlue, AccentPurple)))
                        .clickable {
                            if (quickInput.isNotBlank()) {
                                vm.sendTextQuery(quickInput, speakAloud = true)
                                quickInput = ""
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("➤", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    Surface(
        color = CardGlass,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, CardBorder, RoundedCornerShape(20.dp)).clickable(onClick = onClick)
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun VisionTabContent(vm: VoiceViewModel) {
    var screenDump by remember { mutableStateOf("") }
    val a11yOn = MeeraAccessibilityService.instance != null

    var showInspector by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
        
        // Bottom-left Floating Pill to toggle Screen Inspector without blocking full-screen camera
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color(0xCC090D18),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.border(1.dp, AccentCyan.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            ) {
                Text(
                    "● LIVE FULL-SCREEN VISION",
                    color = AccentCyan,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }

            Surface(
                color = if (showInspector) AccentBlue else Color(0xCC16203A),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
                    .clickable { showInspector = !showInspector }
            ) {
                Text(
                    if (showInspector) "✕ कैमरा देखें" else "🔍 स्क्रीन टूल्स",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        if (showInspector) {
            Surface(
                color = Color(0xEE090D18),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 60.dp, start = 8.dp, end = 8.dp)
                    .border(1.dp, CardBorder, RoundedCornerShape(22.dp))
            ) {
                Column(
                    Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("👁️ Meera Vision & Screen Inspector", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "यहाँ से Meera आपकी चालू स्क्रीन या किसी भी ऐप के टेक्स्ट को पढ़कर समझा सकती है और उस पर एक्शन ले सकती है।",
            color = Muted, fontSize = 13.sp
        )

        Surface(
            color = CardGlass,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(20.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (a11yOn) "🟢 Screen Reader इंजन चालू है" else "⚠️ Accessibility सर्विस बंद है",
                    color = if (a11yOn) AccentCyan else Color(0xFFFFB84D),
                    fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
                Text(
                    "जब आप किसी भी ऐप (जैसे WhatsApp या Chrome) में हों और माइक पर बोलें 'मीरा, स्क्रीन पढ़ो', तो वह तुरंत स्क्रीन का सारा टेक्स्ट पढ़ लेगी।",
                    color = Muted, fontSize = 12.5.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickChip("🔍 अभी की स्क्रीन स्कैन करें") {
                        screenDump = MeeraAccessibilityService.instance?.readScreenText()
                            ?: "Accessibility बंद है। कृपया साइड मेन्यू से Accessibility ऑन करें।"
                    }
                    QuickChip("🔔 नोटिफिकेशन पढ़ें") {
                        screenDump = MeeraNotificationService.getLatestSummary()
                    }
                }
            }
        }

        if (screenDump.isNotBlank()) {
            Surface(
                color = CardGlass,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(18.dp))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("📋 स्कैन किया गया डेटा:", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(screenDump, color = Color.White, fontSize = 12.5.sp)
                    Spacer(Modifier.height(10.dp))
                    QuickChip("🗣️ Meera से इसका सारांश सुनें") {
                        vm.sendTextQuery("इस स्क्रीन डेटा को पढ़कर संक्षेप में बताओ:\n$screenDump", speakAloud = true)
                    }
                }
            }
        }
    }

                }
            }
        }
    }
@Composable
private fun YaadeinTabContent(vm: VoiceViewModel) {
    val ctx = LocalContext.current
    var newFact by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var editingOld by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    val memories = tick.let { vm.getMemoriesList() }

    Column(
        Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🧠 यादें और सीखे हुए टास्क", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)

        // Built-in Smart Automation Tasks (जैसे Maya के 'Sikhe hue tasks')
        Text("⚡ 1-Tap स्मार्ट टास्क (Built-in Actions)", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        val builtInTasks = listOf(
            Triple("gemini se poochho · 8 steps", "sawaal · built-in (Gemini ऐप में सवाल पूछकर जवाब सुनाए)", "Gemini ऐप खोलकर आज की मुख्य टेक्नोलॉजी न्यूज़ पूछो"),
            Triple("chatgpt se poochho · 8 steps", "sawaal · built-in (ChatGPT ऐप में सवाल पूछकर जवाब सुनाए)", "ChatGPT में जाकर एक मोटिवेशनल कहानी पूछो"),
            Triple("gemini video · 16 steps", "video prompt · built-in (Gemini में वीडियो बनाकर सेव करे)", "Gemini में उगते सूरज का वीडियो बनाओ"),
            Triple("gemini image · 17 steps", "image prompt · built-in (Gemini में इमेज बनाकर गैलरी में सेव करे)", "Gemini में दहाड़ते हुए शेर की इमेज बनाओ"),
            Triple("chatgpt image · 14 steps", "image prompt · built-in (ChatGPT में इमेज बनाकर टैप करके डाउनलोड करे)", "ChatGPT में दहाड़ते हुए शेर की इमेज बनाओ")
        )
        builtInTasks.forEach { (title, sub, cmd) ->
            Surface(
                color = CardGlass,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                    .clickable { vm.sendTextQuery(cmd, speakAloud = true) }
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(sub, color = Muted, fontSize = 12.sp)
                    }
                    Text("▶ चलाओ", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📌 सेव की गई यादें (${memories.size})", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (memories.isNotEmpty()) {
                Text(
                    "☁️ कॉपी/बैकअप",
                    color = AccentBlue,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("MeeraMemories", memories.joinToString("\n")))
                        Toast.makeText(ctx, "सारी यादें क्लिपबोर्ड पर कॉपी हो गईं!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newFact,
                onValueChange = { newFact = it },
                placeholder = { Text("नई याद जोड़ें (जैसे: मेरा नाम राहुल है)", color = Muted, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            QuickChip("＋ जोड़ें") {
                if (newFact.isNotBlank()) {
                    vm.addManualMemory(newFact)
                    newFact = ""
                }
            }
        }

        if (memories.isEmpty()) {
            Text("अभी कोई याद सेव नहीं है। बात करते समय Meera खुद याद रख लेगी।", color = Muted, fontSize = 13.sp)
        } else {
            memories.reversed().forEach { m ->
                Surface(
                    color = CardGlass,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("• $m", color = Color.White, fontSize = 13.5.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.End)
                        ) {
                            Text(
                                "✏️ बदलें", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { editText = m; editingOld = m }
                            )
                            Text(
                                "🗑️ हटाएँ", color = Color(0xFFFF6B6B), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    vm.deleteMemory(m); tick++
                                    Toast.makeText(ctx, "हटा दिया", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
            editingOld?.let { old ->
                AlertDialog(
                    onDismissRequest = { editingOld = null },
                    title = { Text("✏️ याद / स्किल बदलें") },
                    text = {
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { vm.editMemory(old, editText); tick++; editingOld = null }) { Text("सेव") }
                    },
                    dismissButton = {
                        TextButton(onClick = { editingOld = null }) { Text("रद्द") }
                    }
                )
            }
            TextButton(onClick = { vm.clearAllMemories(); tick++ }) {
                Text("🗑️ सारी यादें मिटाएँ", color = Color(0xFFFF6B6B))
            }
        }
    }
}

@Composable
private fun ChatTabContent(ui: UiState, vm: VoiceViewModel) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("💬 बातचीत इतिहास (${ui.chatMessages.size})", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            if (ui.chatMessages.isNotEmpty()) {
                TextButton(onClick = { vm.clearChatLog() }) {
                    Text("🗑️ Clear", color = Muted, fontSize = 13.sp)
                }
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (ui.chatMessages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                    Text("Meera से कुछ भी पूछो — आवाज़ से या नीचे लिखकर!", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            } else {
                ui.chatMessages.forEach { msg ->
                    val isUser = msg.role == "user"
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = if (isUser) AccentBlue else CardGlass,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth(0.84f).border(1.dp, if (isUser) AccentBlue else CardBorder, RoundedCornerShape(18.dp))
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(msg.text, color = Color.White, fontSize = 14.5.sp)
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(msg.time, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                                    if (!isUser) {
                                        Text(
                                            "📋 Copy",
                                            color = AccentCyan,
                                            fontSize = 11.sp,
                                            modifier = Modifier.clickable {
                                                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cb.setPrimaryClip(ClipData.newPlainText("MeeraReply", msg.text))
                                                Toast.makeText(ctx, "कॉपी हो गया", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("यहाँ मैसेज या कमांड लिखें…", color = Muted, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(46.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(AccentBlue, AccentPurple)))
                    .clickable {
                        if (input.isNotBlank()) {
                            vm.sendTextQuery(input, speakAloud = true)
                            input = ""
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("➤", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AccessibilityHubScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val a11yOn = MeeraAccessibilityService.instance != null
    val adbCmd = "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"

    Column(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(18.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = CardGlass,
                shape = CircleShape,
                modifier = Modifier.size(42.dp).border(1.dp, CardBorder, CircleShape).clickable(onClick = onBack)
            ) {
                Box(contentAlignment = Alignment.Center) { Text("←", color = Color.White, fontSize = 20.sp) }
            }
            Spacer(Modifier.width(12.dp))
            Text("Accessibility Control Hub", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }

        // Status Card
        Surface(
            color = CardGlass,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, if (a11yOn) AccentCyan else CardBorder, RoundedCornerShape(22.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (a11yOn) "🟢 Accessibility चालू है" else "⚠️ Accessibility बंद है",
                    color = if (a11yOn) AccentCyan else Color(0xFFFFB84D),
                    fontSize = 17.sp, fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "Meera स्क्रीन पढ़ और टैप कर सकती है — WhatsApp, Instagram, YouTube सब।",
                    color = Muted, fontSize = 13.sp
                )
            }
        }

        // Payment App Pause Button
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(AccentBlue, AccentPurple)))
                .clickable { PermHelper.openAccessibilitySettings(ctx) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (a11yOn) "🏦 Payment ऐप (GPay/PhonePe) के लिए बंद/चालू करें" else "🚹 अभी Accessibility सेटिंग्स खोलकर ऑन करें",
                color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold
            )
        }

        Surface(
            color = CardGlass,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(18.dp))
        ) {
            Text(
                "💡 Paytm, PhonePe, Google Pay और ज़्यादातर बैंक ऐप्स Accessibility ON होने पर सिक्योरिटी की वजह से चेतावनी देते हैं। ऊपर वाले बटन से आप एक टैप में सेटिंग खोलकर पेमेंट के समय इसे बंद और बाद में वापस चालू कर सकते हैं।",
                color = Muted, fontSize = 12.5.sp, modifier = Modifier.padding(14.dp)
            )
        }

        // Battery & Autostart Card
        Text("बार-बार बंद हो रही है?", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Surface(
            color = CardGlass,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(20.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { PermHelper.openBatteryOptimization(ctx) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("🔋 Battery: Meera को optimise मत करो", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("ताकि फोन बैकग्राउंड में Meera को बंद न करे", color = Muted, fontSize = 12.sp)
                    }
                    Text("›", color = Color.White, fontSize = 22.sp)
                }
                HorizontalDivider(color = CardBorder)
                Row(
                    Modifier.fillMaxWidth().clickable { PermHelper.openNotificationListenerSettings(ctx) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("🔔 Notification Access", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("WhatsApp मैसेज और कॉलर का नाम पढ़ने के लिए", color = Muted, fontSize = 12.sp)
                    }
                    Text("›", color = Color.White, fontSize = 22.sp)
                }
            }
        }

        // ADB One-Tap Fix
        Text("ONE-TAP FIX (ADVANCED)", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Surface(
            color = CardGlass,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(20.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Android किसी ऐप को अपनी रुकी हुई Accessibility खुद ठीक करने की इजाज़त इस ADB कमांड से देता है:",
                    color = Muted, fontSize = 12.5.sp
                )
                Text(adbCmd, color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                QuickChip("📋 ADB Command कॉपी करें") {
                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("adb", adbCmd))
                    Toast.makeText(ctx, "ADB Command कॉपी हो गई!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
private fun BottomAuroraDock(
    currentTab: NavTab,
    micActive: Boolean,
    onSelectTab: (NavTab) -> Unit,
    onMicClick: () -> Unit
) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockTabItem("🏠", "Home", currentTab == NavTab.HOME) { onSelectTab(NavTab.HOME) }
            DockTabItem("👁️", "Vision", currentTab == NavTab.VISION) { onSelectTab(NavTab.VISION) }

            // Center Floating Mic Button
            val micColor = if (micActive) Color(0xFFE5484D) else AccentBlue
            Box(
                Modifier.size(64.dp).border(2.dp, micColor.copy(alpha = 0.45f), CircleShape).padding(4.dp)
                    .clip(CircleShape).background(Brush.linearGradient(listOf(micColor, if (micActive) Color(0xFFB91C1C) else AccentPurple)))
                    .clickable(onClick = onMicClick),
                contentAlignment = Alignment.Center
            ) {
                Text(if (micActive) "■" else "🎙️", fontSize = 24.sp, color = Color.White)
            }

            DockTabItem("🧠", "Yaadein", currentTab == NavTab.YAADEIN) { onSelectTab(NavTab.YAADEIN) }
            DockTabItem("💬", "Chat", currentTab == NavTab.CHAT) { onSelectTab(NavTab.CHAT) }
        }
    }
}

@Composable
private fun DockTabItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) AccentBlue.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(icon, fontSize = 18.sp)
        Text(
            label,
            color = if (selected) Color.White else Muted,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun DropSection(
    title: String,
    current: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Muted, fontSize = 12.sp)
                Text(current, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(if (open) "▲" else "▼", color = AccentCyan, fontSize = 14.sp)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().background(Color(0xFF10172F)).padding(vertical = 4.dp)) { content() }
        }
        HorizontalDivider(color = Color(0xFF232C52))
    }
}

@Composable
private fun ModeRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text(sub, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun KeyDialog(hasKey: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemini API Key") },
        text = {
            Column {
                Text(
                    if (hasKey) "पहले से एक Key सेव है. नई डालने पर बदल जाएगी." else "aistudio.google.com से Key कॉपी करके यहाँ पेस्ट करें.",
                    fontSize = 13.sp, color = Muted
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, label = { Text("API Key") },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "छुपाएँ" else "दिखाएँ") } }
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text("सेव") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("रद्द") } }
    )
}

@Composable
fun Orb(state: VState, vm: VoiceViewModel, modifier: Modifier = Modifier) {
    val lvlRaw by vm.level.collectAsStateWithLifecycle()
    val lvl by animateFloatAsState(lvlRaw.coerceIn(0f, 1f), tween(90), label = "lvl")
    val target = when (state) {
        VState.IDLE -> AccentBlue
        VState.CONNECTING -> Color(0xFF7C8CB8)
        VState.LISTENING -> Color(0xFF3CC8FF)
        VState.USER_SPEAKING -> AccentCyan
        VState.PROCESSING -> Color(0xFFFFB84D)
        VState.ASSISTANT_SPEAKING -> Color(0xFFB46BFF)
        VState.ERROR -> Color(0xFFE5484D)
    }
    val color by animateColorAsState(target, tween(500), label = "color")
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "s")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearOutSlowInEasing)), label = "r")
    val rippling = state == VState.USER_SPEAKING || state == VState.ASSISTANT_SPEAKING

    Canvas(modifier) {
        val c = center
        val base = size.minDimension / 2f * 0.55f
        val r = base * (1f + 0.05f * breathe + 0.45f * lvl)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.40f), Color.Transparent), center = c, radius = r * 2f), radius = r * 2f, center = c)
        if (rippling) {
            for (i in 0..1) {
                val p = (ring + i * 0.5f) % 1f
                drawCircle(color.copy(alpha = (1f - p) * 0.4f), radius = r * (1f + p * 0.6f), center = c, style = Stroke(3.dp.toPx()))
            }
        }
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.85f), color, color.copy(alpha = 0.55f), Bg),
                center = c + Offset(-r * 0.3f, -r * 0.3f), radius = r * 1.5f
            ),
            radius = r, center = c
        )
        val arcs = if (state == VState.PROCESSING) 3 else 1
        for (i in 0 until arcs) {
            rotate(spin * (if (state == VState.PROCESSING) 2f else 1f) + i * 120f, pivot = c) {
                drawArc(
                    Color.White.copy(alpha = 0.28f), startAngle = 0f, sweepAngle = 60f, useCenter = false,
                    topLeft = c - Offset(r * 0.8f, r * 0.8f), size = Size(r * 1.6f, r * 1.6f),
                    style = Stroke(r * 0.1f, cap = StrokeCap.Round)
                )
            }
        }
    }
}
