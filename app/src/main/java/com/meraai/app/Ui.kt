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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

@Composable
fun MeraTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { MeeraThemePrefs.ensureLoaded(ctx) }
    val themeId by MeeraThemePrefs.currentThemeId.collectAsState()
    val palette = MeeraThemes.byId(themeId)
    val scheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.accentPrimary,
            secondary = palette.accentSecondary,
            background = palette.bg,
            surface = palette.panel,
            onSurface = palette.textPrimary,
            onBackground = palette.textPrimary
        )
    } else {
        lightColorScheme(
            primary = palette.accentPrimary,
            secondary = palette.accentSecondary,
            background = palette.bg,
            surface = palette.panel,
            onSurface = palette.textPrimary,
            onBackground = palette.textPrimary
        )
    }
    CompositionLocalProvider(LocalMeeraColors provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun MeraApp(vm: VoiceViewModel, onMic: () -> Unit) {
    var splash by remember { mutableStateOf(true) }
    var showSetup by remember { mutableStateOf(!vm.isSetupDone()) }
    LaunchedEffect(Unit) { delay(1500); splash = false }
    Crossfade(targetState = Triple(splash, showSetup, Unit), animationSpec = tween(400), label = "root") { (isSplash, isSetup, _) ->
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
    val p = LocalMeeraColors.current
    val t = rememberInfiniteTransition(label = "splash")
    val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxSize().background(p.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(96.dp).alpha(a)
                    .background(Brush.radialGradient(listOf(Color.White, p.accentPrimary, p.accentSecondary)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Meera AI", color = p.textPrimary, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text("स्टार्ट हो रही है…", color = p.textSecondary, fontSize = 14.sp, modifier = Modifier.alpha(a))
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
private fun DrawerMenuItem(
    label: String,
    icon: ImageVector,
    badgeText: String? = null,
    badgeColor: Color? = null,
    onClick: () -> Unit
) {
    val p = LocalMeeraColors.current
    NavigationDrawerItem(
        label = { Text(label, color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
        icon = { Icon(icon, contentDescription = null, tint = p.accentPrimary, modifier = Modifier.size(20.dp)) },
        badge = badgeText?.let { bTxt ->
            { Text(bTxt, color = badgeColor ?: p.accentSuccess, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) }
        },
        selected = false,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 10.dp)
    )
}

@Composable
fun Dashboard(vm: VoiceViewModel, onMic: () -> Unit, onOpenSetup: () -> Unit = {}) {
    val p = LocalMeeraColors.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentTab by remember { mutableStateOf(NavTab.HOME) }
    var showKey by remember { mutableStateOf(false) }
    var showA11yHub by remember { mutableStateOf(false) }
    var showGitHubVault by remember { mutableStateOf(false) }
    var privateRepoPrompt by remember { mutableStateOf(false) }
    val showTerminal by MeeraTermuxEngine.terminalVisible.collectAsState()
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
            ModalDrawerSheet(drawerContainerColor = p.panel) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Meera AI", color = p.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                            val pkgInfo = remember { runCatching { view.context.packageManager.getPackageInfo(view.context.packageName, 0) }.getOrNull() }
                            Text("Pro Agentic Companion • v${pkgInfo?.versionName ?: "2.0"}", color = p.accentPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Surface(
                            color = p.card,
                            shape = CircleShape,
                            modifier = Modifier.size(34.dp).border(1.dp, p.cardBorder, CircleShape)
                                .clickable { scope.launch { drawer.close() } }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, contentDescription = "बंद करें", tint = p.textSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    HorizontalDivider(color = p.cardBorder)
                    Spacer(Modifier.height(6.dp))

                    val deepSleep by MeeraWake.deepSleepFlow.collectAsState()
                    LaunchedEffect(Unit) { MeeraWake.syncFromPrefs(orbCtx) }
                    Surface(
                        color = if (deepSleep) p.accentPrimary.copy(alpha = 0.14f) else p.card,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .border(1.dp, if (deepSleep) p.accentPrimary else p.cardBorder, RoundedCornerShape(16.dp))
                            .clickable {
                                val next = !deepSleep
                                vm.setDeepSleepMode(next)
                                Toast.makeText(
                                    orbCtx,
                                    if (next) "🌙 Deep Sleep ON — बैकग्राउंड माइक व वेक-वर्ड बंद" else "☀️ Deep Sleep OFF — 'Hey Meera' फिर से चालू",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Bedtime,
                                    contentDescription = null,
                                    tint = if (deepSleep) p.accentPrimary else p.textSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text("Deep Sleep Mode", color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (deepSleep) "बैकग्राउंड माइक व वेक-वर्ड बंद (बैटरी सेवर)" else "रात में बैकग्राउंड माइक पूरी तरह बंद करें",
                                        color = if (deepSleep) p.accentPrimary else p.textSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Switch(
                                checked = deepSleep,
                                onCheckedChange = { next ->
                                    vm.setDeepSleepMode(next)
                                    Toast.makeText(
                                        orbCtx,
                                        if (next) "🌙 Deep Sleep ON — बैकग्राउंड माइक व वेक-वर्ड बंद" else "☀️ Deep Sleep OFF — 'Hey Meera' फिर से चालू",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }

                    val logCtx = LocalContext.current
                    val curVerName = remember { runCatching { logCtx.packageManager.getPackageInfo(logCtx.packageName, 0).versionName ?: "2.0" }.getOrDefault("2.0") }
                    val exportUi by MeeraRepoExporter.state.collectAsState()
                    val hasGhToken = remember(showGitHubVault) { MeeraTokenVault.hasToken(logCtx) }
                    val bgTasks by MeeraBackgroundMonitor.tasks.collectAsState()
                    val bgRunning = bgTasks.firstOrNull { it.status == "RUNNING" }
                    val patcherUi by MeeraCodePatcher.state.collectAsState()

                    DrawerMenuItem("API Key", Icons.Rounded.Key, if (ui.hasKey) "Saved" else "Add", if (ui.hasKey) p.accentSuccess else p.warning) {
                        scope.launch { drawer.close() }; showKey = true
                    }
                    DrawerMenuItem(
                        "Accessibility Hub",
                        Icons.Rounded.AccessibilityNew,
                        if (MeeraAccessibilityService.instance != null) "Active" else "Enable",
                        if (MeeraAccessibilityService.instance != null) p.accentSuccess else p.warning
                    ) { scope.launch { drawer.close() }; showA11yHub = true }

                    DrawerMenuItem(
                        "Terminal (Termux Live)",
                        Icons.Rounded.Terminal,
                        if (MeeraTermuxEngine.hasPermission(orbCtx)) "Ready" else "Setup",
                        if (MeeraTermuxEngine.hasPermission(orbCtx)) p.accentSuccess else p.warning
                    ) { scope.launch { drawer.close() }; MeeraTermuxEngine.openTerminal() }

                    DrawerMenuItem("Permissions & Setup", Icons.Rounded.Security, "Hub", p.accentSecondary) {
                        scope.launch { drawer.close() }; onOpenSetup()
                    }
                    DrawerMenuItem("1-Tap App Update", Icons.Rounded.SystemUpdate, "v$curVerName", p.accentSuccess) {
                        scope.launch { drawer.close() }; MeeraUpdater.checkAndInstallUpdate(logCtx)
                    }
                    DrawerMenuItem(
                        "Code Dump डाउनलोड",
                        Icons.Rounded.Download,
                        if (exportUi.running) "Working..." else "Export",
                        if (exportUi.running) p.warning else p.accentSecondary
                    ) {
                        scope.launch { drawer.close() }
                        MeeraRepoExporter.exportFullCode(logCtx) {
                            privateRepoPrompt = true
                            showGitHubVault = true
                        }
                    }
                    DrawerMenuItem("GitHub Token Vault", Icons.Rounded.Lock, if (hasGhToken) "Encrypted" else "Optional", if (hasGhToken) p.accentSuccess else p.textSecondary) {
                        scope.launch { drawer.close() }; privateRepoPrompt = false; showGitHubVault = true
                    }
                    DrawerMenuItem(
                        "Background Activity",
                        Icons.Rounded.Insights,
                        when {
                            bgRunning != null -> "${bgRunning.percent}%"
                            bgTasks.firstOrNull()?.status == "COMPLETED" -> "100%"
                            else -> "Idle"
                        },
                        if (bgRunning != null) p.warning else p.accentSuccess
                    ) { scope.launch { drawer.close() }; MeeraBackgroundMonitor.openMonitor() }

                    DrawerMenuItem(
                        "Code & Build Studio",
                        Icons.Rounded.RocketLaunch,
                        when (patcherUi.buildStatus) {
                            "WATCHING" -> "CI..."
                            "SUCCESS" -> "APK Ready"
                            "FAILURE" -> "Error"
                            else -> "GitHub"
                        },
                        p.accentPrimary
                    ) { scope.launch { drawer.close() }; MeeraCodePatcher.openScreen() }

                    DrawerMenuItem("Debug Log कॉपी करें", Icons.Rounded.BugReport) {
                        scope.launch { drawer.close() }; MeeraDebugLog.copyNow(logCtx)
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = p.cardBorder)
                    var openSec by remember { mutableStateOf("") }

                    val curThemeId by MeeraThemePrefs.currentThemeId.collectAsState()
                    val curThemeObj = MeeraThemes.byId(curThemeId)
                    DropSection("ऐप थीम और कलर (Light / Dark)", curThemeObj.label, openSec == "theme", { openSec = if (openSec == "theme") "" else "theme" }) {
                        MeeraThemes.ALL.forEach { th ->
                            ThemeSwatchRow(th, curThemeId == th.id) {
                                MeeraThemePrefs.setTheme(orbCtx, th.id)
                                openSec = ""
                            }
                        }
                    }

                    DropSection("3D Orb डिज़ाइन (Visualizer)", MeeraOrbPrefs.styleLabel(orbStyle), openSec == "orb_style", { openSec = if (openSec == "orb_style") "" else "orb_style" }) {
                        MeeraOrbPrefs.STYLES.forEach { (sCode, sLabel) ->
                            ModeRow(sLabel, "60 FPS Audio Reactive 3D Shader", orbStyle == sCode) {
                                orbStyle = sCode
                                MeeraOrbPrefs.setStyle(orbCtx, sCode)
                                openSec = ""
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("3D Orb का साइज़", color = p.textSecondary, fontSize = 12.sp)
                            Text("${orbSizeDp.toInt()} dp", color = p.accentPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = orbSizeDp,
                            onValueChange = {
                                orbSizeDp = it
                                MeeraOrbPrefs.setSizeDp(orbCtx, it)
                            },
                            valueRange = 150f..300f,
                            colors = SliderDefaults.colors(thumbColor = p.accentPrimary, activeTrackColor = p.accentPrimary)
                        )
                    }
                    HorizontalDivider(color = p.cardBorder)

                    DropSection("पर्सनैलिटी चुनें", ui.persona, openSec == "persona", { openSec = if (openSec == "persona") "" else "persona" }) {
                        Cfg.PERSONAS.forEach { (pCode, pDesc, _) ->
                            ModeRow(pCode, pDesc, ui.persona == pCode) { vm.setPersona(pCode); openSec = "" }
                        }
                    }

                    if (ui.persona == "Meera") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Companion / Girlfriend Mode", color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("प्यार, नटखट नखरे, केयर और अपनापन", color = p.textSecondary, fontSize = 11.5.sp)
                            }
                            Switch(checked = ui.girlfriendMode, onCheckedChange = { vm.setGirlfriendMode(it) })
                        }
                    }

                    HorizontalDivider(color = p.cardBorder)
                    Text("बातचीत इंजन (Engine Mode)", color = p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
                    ModeRow("Live API (Real-Time)", "Gemini Live WebSocket Audio + Tools", ui.mode == Mode.LIVE) { vm.setMode(Mode.LIVE) }
                    ModeRow("Modular Pipeline", "Fast STT → Brain + Workers → HD TTS", ui.mode == Mode.MODULAR) { vm.setMode(Mode.MODULAR) }

                    if (ui.mode == Mode.LIVE) {
                        DropSection("Live मॉडल", ui.liveModel, openSec == "live", { openSec = if (openSec == "live") "" else "live" }) {
                            Cfg.LIVE_MODELS.forEach { m ->
                                ModeRow(m, "WebSocket Bidi Audio + Function Calling", ui.liveModel == m) { vm.setLiveModel(m); openSec = "" }
                            }
                        }
                    } else {
                        DropSection("चैट ब्रेन (LLM)", ui.chatModel, openSec == "chat", { openSec = if (openSec == "chat") "" else "chat" }) {
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
                    val vWarn = if (vTag.isNotEmpty() && !vTag.split("/").contains(ui.persona)) " ($vTag)" else ""
                    DropSection("आवाज़ चुनें (30 Google Voices)", "${ui.voice}$vWarn", openSec == "voice", { openSec = if (openSec == "voice") "" else "voice" }) {
                        listOf(
                            "Meera" to "Meera Voices (14 Feminine)",
                            "Jarvis" to "Jarvis Voices (6 Masculine)",
                            "Jarvis/Rudra" to "Jarvis / Rudra",
                            "Rudra" to "Rudra Voices (9 Deep Masculine)"
                        ).forEach { (tag, heading) ->
                            val group = Cfg.VOICES.filter { it.second.substringAfter("(").substringBefore(" —") == tag }
                            if (group.isNotEmpty()) {
                                Text(heading, color = p.accentPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
                                group.forEach { (vCode, vLabel) ->
                                    ModeRow(vCode, vLabel, ui.voice == vCode) { vm.setVoice(vCode); openSec = "" }
                                }
                            }
                        }
                    }

                    val voicePrefs = remember { Prefs(orbCtx) }
                    var curVoiceStyle by remember { mutableStateOf(voicePrefs.voiceStyle) }
                    val curStyleLabel = Cfg.VOICE_STYLE_PRESETS.firstOrNull { it.first == curVoiceStyle }?.second ?: "Auto"
                    DropSection("आवाज़ का अंदाज़ (HD Emotion)", curStyleLabel, openSec == "voice_style", { openSec = if (openSec == "voice_style") "" else "voice_style" }) {
                        Cfg.VOICE_STYLE_PRESETS.forEach { (stCode, stLabel) ->
                            ModeRow(stLabel, "Modular HD TTS Expression Style", curVoiceStyle == stCode) {
                                curVoiceStyle = stCode
                                voicePrefs.voiceStyle = stCode
                                openSec = ""
                            }
                        }
                    }

                    var curRetentionDays by remember { mutableStateOf(voicePrefs.chatRetentionDays) }
                    DropSection(
                        "चैट हिस्ट्री स्टोरेज (Auto-Delete)",
                        "$curRetentionDays दिन तक सेव रहेगी",
                        openSec == "chat_retention",
                        { openSec = if (openSec == "chat_retention") "" else "chat_retention" }
                    ) {
                        listOf(
                            7 to "7 दिन (हल्का और साफ़ — 1 सप्ताह पुरानी चैट हट जाएगी)",
                            15 to "15 दिन (संतुलित — 2 सप्ताह तक की चैट सेव रहेगी)",
                            30 to "30 दिन (प्रोफेशनल — पूरे 1 महीने की चैट सुरक्षित)"
                        ).forEach { (days, desc) ->
                            ModeRow("$days दिन", desc, curRetentionDays == days) {
                                curRetentionDays = days
                                voicePrefs.chatRetentionDays = days
                                MeeraMemoryManager.pruneOldChats(orbCtx, days)
                                openSec = ""
                            }
                        }
                    }
                }
            }
        }
    ) {
        val bgMonitorOpen by MeeraBackgroundMonitor.monitorVisible.collectAsState()
        val codePatcherOpen by MeeraCodePatcher.screenVisible.collectAsState()
        if (showTerminal) {
            MeeraTerminalScreen(onBack = { MeeraTermuxEngine.closeTerminal() })
        } else if (showA11yHub) {
            AccessibilityHubScreen(onBack = { showA11yHub = false })
        } else if (bgMonitorOpen) {
            MeeraBackgroundActivityScreen(onBack = { MeeraBackgroundMonitor.closeMonitor() })
        } else if (codePatcherOpen) {
            MeeraCodePatcherScreen(onBack = { MeeraCodePatcher.closeScreen() }, onOpenTokenVault = { MeeraCodePatcher.closeScreen(); showGitHubVault = true })
        } else {
            Column(
                Modifier.fillMaxSize().background(p.bg).systemBarsPadding()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        color = p.card,
                        shape = CircleShape,
                        shadowElevation = if (p.isDark) 0.dp else 2.dp,
                        modifier = Modifier.size(42.dp).border(1.dp, p.cardBorder, CircleShape)
                            .clickable { scope.launch { drawer.open() } }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Menu, contentDescription = "मेन्यू", tint = p.textPrimary, modifier = Modifier.size(21.dp))
                        }
                    }

                    val modeTag = if (ui.mode == Mode.LIVE) "Live" else "Modular"
                    Surface(
                        color = p.card,
                        shape = RoundedCornerShape(22.dp),
                        shadowElevation = if (p.isDark) 0.dp else 2.dp,
                        modifier = Modifier.border(1.dp, p.cardBorder, RoundedCornerShape(22.dp))
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier.size(8.dp).clip(CircleShape)
                                    .background(if (active) p.accentSuccess else p.accentPrimary)
                            )
                            Text(
                                "${ui.persona} • $modeTag • ${ui.voice}",
                                color = p.textPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    val a11yOn = MeeraAccessibilityService.instance != null
                    Surface(
                        color = p.card,
                        shape = CircleShape,
                        shadowElevation = if (p.isDark) 0.dp else 2.dp,
                        modifier = Modifier.size(42.dp).border(1.dp, if (a11yOn) p.accentSuccess.copy(alpha = 0.6f) else p.cardBorder, CircleShape)
                            .clickable { showA11yHub = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.AccessibilityNew,
                                contentDescription = "Accessibility",
                                tint = if (a11yOn) p.accentSuccess else p.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (currentTab) {
                        NavTab.HOME -> HomeTabContent(ui, vm, orbStyle, orbSizeDp)
                        NavTab.VISION -> VisionCameraTab(vm)
                        NavTab.YAADEIN -> YaadeinTabContent(vm)
                        NavTab.CHAT -> ChatTabContent(ui, vm)
                    }
                }

                if (active) {
                    val muted by MicMute.flow.collectAsState()
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
                        Surface(
                            color = if (muted) p.danger.copy(alpha = 0.16f) else p.card,
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier
                                .border(1.dp, if (muted) p.danger else p.cardBorder, RoundedCornerShape(22.dp))
                                .clickable { vm.toggleMute() }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                    contentDescription = null,
                                    tint = if (muted) p.danger else p.accentSuccess,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    if (muted) "म्यूट है — दबाकर अनम्यूट करें" else "सुन रही है — म्यूट करें",
                                    color = if (muted) p.danger else p.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

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

    val exportDialogState by MeeraRepoExporter.state.collectAsState()
    if (showGitHubVault) {
        GitHubTokenVaultDialog(
            hasSavedToken = MeeraTokenVault.hasToken(orbCtx),
            isForPrivateDownload = privateRepoPrompt,
            onSaveAndDownload = { tok, shouldSave ->
                if (shouldSave) {
                    MeeraTokenVault.saveToken(orbCtx, tok)
                    Toast.makeText(orbCtx, "Token encrypted save ho gaya!", Toast.LENGTH_SHORT).show()
                }
                showGitHubVault = false
                privateRepoPrompt = false
                MeeraRepoExporter.exportFullCode(orbCtx, explicitToken = tok) {
                    Toast.makeText(orbCtx, "Token invalid hai ya repo access nahi mila.", Toast.LENGTH_LONG).show()
                }
            },
            onSaveOnly = { tok ->
                MeeraTokenVault.saveToken(orbCtx, tok)
                showGitHubVault = false
                Toast.makeText(orbCtx, "GitHub Token encrypted save ho gaya!", Toast.LENGTH_SHORT).show()
            },
            onDeleteToken = {
                MeeraTokenVault.clearToken(orbCtx)
                showGitHubVault = false
                privateRepoPrompt = false
                Toast.makeText(orbCtx, "Saved GitHub Token hata diya gaya.", Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                showGitHubVault = false
                privateRepoPrompt = false
            }
        )
    }

    if (exportDialogState.completedFileName != null || exportDialogState.errorMsg != null) {
        AlertDialog(
            onDismissRequest = { MeeraRepoExporter.dismissCompletedDialog() },
            title = {
                Text(if (exportDialogState.errorMsg != null) "Code Export Error" else "Code Download Complete")
            },
            text = {
                Text(
                    exportDialogState.errorMsg
                        ?: "File: Downloads/${exportDialogState.completedFileName}\nTotal Code Files: ${exportDialogState.completedFileCount} | Size: ${exportDialogState.completedSizeKb} KB",
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                val sUri = exportDialogState.shareUri
                if (sUri != null && exportDialogState.errorMsg == null) {
                    TextButton(onClick = {
                        MeeraRepoExporter.launchShareSheet(orbCtx, sUri)
                        MeeraRepoExporter.dismissCompletedDialog()
                    }) { Text("Share Karein", fontWeight = FontWeight.Bold) }
                } else {
                    TextButton(onClick = { MeeraRepoExporter.dismissCompletedDialog() }) { Text("Theek Hai") }
                }
            },
            dismissButton = {
                if (exportDialogState.errorMsg == null) {
                    TextButton(onClick = { MeeraRepoExporter.dismissCompletedDialog() }) { Text("Band Karein") }
                }
            }
        )
    }
}

@Composable
private fun HomeTabContent(ui: UiState, vm: VoiceViewModel, orbStyle: String = "LIQUID_PLASMA", orbSizeDp: Float = 220f) {
    val p = LocalMeeraColors.current
    var quickInput by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(greetingText(), color = p.textSecondary, fontSize = 13.5.sp)
                Text("Rahul", color = p.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            Surface(
                color = p.card,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = if (p.isDark) 0.dp else 1.dp,
                modifier = Modifier.border(1.dp, p.cardBorder, RoundedCornerShape(16.dp))
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(Icons.Rounded.Psychology, contentDescription = null, tint = p.accentPrimary, modifier = Modifier.size(16.dp))
                    Text("${ui.memoryCount} यादें", color = p.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        val deepSleepHome by MeeraWake.deepSleepFlow.collectAsState()
        if (deepSleepHome) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = p.accentPrimary.copy(alpha = 0.14f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, p.accentPrimary.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                    .clickable {
                        vm.setDeepSleepMode(false)
                        Toast.makeText(ctx, "☀️ Deep Sleep OFF — 'Hey Meera' चालू हो गया", Toast.LENGTH_SHORT).show()
                    }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = p.accentPrimary, modifier = Modifier.size(17.dp))
                        Text(
                            "Deep Sleep ON • बैकग्राउंड माइक बंद",
                            color = p.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        "चालू करें",
                        color = p.accentPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(Modifier.weight(0.55f))

        MeeraDynamicOrb(
            state = ui.state,
            vm = vm,
            orbStyle = orbStyle,
            persona = ui.persona,
            isDark = p.isDark,
            modifier = Modifier.size(orbSizeDp.dp)
        )

        Spacer(Modifier.height(10.dp))
        Text(
            statusText(ui),
            color = if (ui.state == VState.ERROR) p.danger else p.textPrimary,
            fontSize = 16.5.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ui.latencyMs?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(13.dp))
                    Text("$it ms", color = p.textSecondary, fontSize = 11.5.sp)
                }
            }
            ui.lastToolStatus?.let {
                Text(it, color = p.accentPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (ui.user.isNotBlank() || ui.ai.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = p.card,
                shape = RoundedCornerShape(18.dp),
                shadowElevation = if (p.isDark) 0.dp else 2.dp,
                modifier = Modifier.fillMaxWidth().border(1.dp, p.cardBorder, RoundedCornerShape(18.dp))
            ) {
                Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (ui.user.isNotBlank()) {
                        Text(ui.user, color = p.textSecondary, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                    if (ui.ai.isNotBlank()) {
                        Text(ui.ai, color = p.textPrimary, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        Spacer(Modifier.weight(0.45f))

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickChip("WhatsApp", Icons.Rounded.ChatBubble) { MeeraTools.openAppByName(ctx, "WhatsApp") }
            QuickChip("स्क्रीन पढ़ें", Icons.Rounded.Visibility) { vm.sendTextQuery("स्क्रीन पर क्या लिखा है पढ़कर बताओ", speakAloud = true) }
            QuickChip("YouTube", Icons.Rounded.PlayArrow) { vm.sendTextQuery("YouTube पर हिंदी गाने चलाओ", speakAloud = false) }
            QuickChip("Instagram", Icons.Rounded.AutoAwesome) { MeeraTools.openAppByName(ctx, "Instagram") }
        }

        Spacer(Modifier.height(10.dp))

        val homeAttachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) vm.onSharedUrisReceived(uris)
        }
        ActiveSharedFileBanner(vm)
        Surface(
            color = p.card,
            shape = RoundedCornerShape(28.dp),
            shadowElevation = if (p.isDark) 0.dp else 3.dp,
            modifier = Modifier.fillMaxWidth().border(1.dp, p.cardBorder, RoundedCornerShape(28.dp))
        ) {
            Row(
                Modifier.padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape)
                        .background(p.accentPrimary.copy(alpha = 0.12f))
                        .clickable { homeAttachLauncher.launch(arrayOf("*/*")) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = "फ़ाइल अटैच करें", tint = p.accentPrimary, modifier = Modifier.size(20.dp))
                }
                OutlinedTextField(
                    value = quickInput,
                    onValueChange = { quickInput = it },
                    placeholder = { Text("Meera से कुछ भी पूछें या कमांड दें…", color = p.textSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = p.textPrimary,
                        unfocusedTextColor = p.textPrimary
                    ),
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(p.accentPrimary, p.accentSecondary)))
                        .clickable {
                            if (quickInput.isNotBlank()) {
                                vm.sendTextQuery(quickInput, speakAloud = true)
                                quickInput = ""
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(19.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun QuickChip(label: String, icon: ImageVector? = null, onClick: () -> Unit) {
    val p = LocalMeeraColors.current
    Surface(
        color = p.card,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = if (p.isDark) 0.dp else 1.dp,
        modifier = Modifier.border(1.dp, p.cardBorder, RoundedCornerShape(20.dp)).clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = p.accentPrimary, modifier = Modifier.size(15.dp))
            }
            Text(label, color = p.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun VisionTabContent(vm: VoiceViewModel) {
    val p = LocalMeeraColors.current
    var screenDump by remember { mutableStateOf("") }
    val a11yOn = MeeraAccessibilityService.instance != null
    var showInspector by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = p.panel.copy(alpha = 0.88f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.border(1.dp, p.accentSuccess.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            ) {
                Text(
                    "LIVE VISION",
                    color = p.accentSuccess,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }

            Surface(
                color = if (showInspector) p.accentPrimary else p.panel.copy(alpha = 0.88f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .border(1.dp, p.cardBorder, RoundedCornerShape(20.dp))
                    .clickable { showInspector = !showInspector }
            ) {
                Text(
                    if (showInspector) "कैमरा देखें" else "स्क्रीन टूल्स",
                    color = if (showInspector) Color.White else p.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        if (showInspector) {
            Surface(
                color = p.panel.copy(alpha = 0.96f),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 60.dp, start = 8.dp, end = 8.dp)
                    .border(1.dp, p.cardBorder, RoundedCornerShape(22.dp))
            ) {
                Column(
                    Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Vision & Screen Inspector", color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "यहाँ से Meera आपकी चालू स्क्रीन या किसी भी ऐप के टेक्स्ट को पढ़कर समझा सकती है।",
                        color = p.textSecondary, fontSize = 12.5.sp
                    )
                    Surface(
                        color = p.card,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, p.cardBorder, RoundedCornerShape(18.dp))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                if (a11yOn) "Screen Reader इंजन चालू है" else "Accessibility सर्विस बंद है",
                                color = if (a11yOn) p.accentSuccess else p.warning,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                QuickChip("स्क्रीन स्कैन", Icons.Rounded.Visibility) {
                                    screenDump = MeeraAccessibilityService.instance?.readScreenText()
                                        ?: "Accessibility बंद है। कृपया साइड मेन्यू से Accessibility ऑन करें।"
                                }
                                QuickChip("नोटिफिकेशन", Icons.Rounded.Notifications) {
                                    screenDump = MeeraNotificationService.getLatestSummary()
                                }
                            }
                        }
                    }

                    if (screenDump.isNotBlank()) {
                        Surface(
                            color = p.card,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, p.cardBorder, RoundedCornerShape(16.dp))
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text("स्कैन किया गया डेटा:", color = p.accentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text(screenDump, color = p.textPrimary, fontSize = 12.sp)
                                Spacer(Modifier.height(10.dp))
                                QuickChip("सारांश सुनें", Icons.Rounded.VolumeUp) {
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
    val p = LocalMeeraColors.current
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
        Text("यादें और स्मार्ट टास्क", color = p.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)

        Text("1-Tap स्मार्ट टास्क (Built-in Actions)", color = p.accentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        val builtInTasks = listOf(
            Triple("Gemini से पूछें", "Gemini ऐप में सवाल पूछकर जवाब सुनाए", "Gemini ऐप खोलकर आज की मुख्य टेक्नोलॉजी न्यूज़ पूछो"),
            Triple("ChatGPT से पूछें", "ChatGPT ऐप में सवाल पूछकर जवाब सुनाए", "ChatGPT में जाकर एक मोटिवेशनल कहानी पूछो"),
            Triple("Gemini Video", "Gemini में वीडियो बनाकर सेव करे", "Gemini में उगते सूरज का वीडियो बनाओ"),
            Triple("Gemini Image", "Gemini में इमेज बनाकर गैलरी में सेव करे", "Gemini में दहाड़ते हुए शेर की इमेज बनाओ"),
            Triple("ChatGPT Image", "ChatGPT में इमेज बनाकर डाउनलोड करे", "ChatGPT में दहाड़ते हुए शेर की इमेज बनाओ")
        )
        builtInTasks.forEach { (title, sub, cmd) ->
            Surface(
                color = p.card,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = if (p.isDark) 0.dp else 1.dp,
                modifier = Modifier.fillMaxWidth().border(1.dp, p.cardBorder, RoundedCornerShape(16.dp))
                    .clickable { vm.sendTextQuery(cmd, speakAloud = true) }
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(p.accentPrimary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = p.accentPrimary, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text(title, color = p.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            Text(sub, color = p.textSecondary, fontSize = 11.5.sp)
                        }
                    }
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "चलाएँ", tint = p.accentPrimary, modifier = Modifier.size(22.dp))
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("सेव की गई यादें (${memories.size})", color = p.accentPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            if (memories.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable {
                        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("MeeraMemories", memories.joinToString("\n")))
                        Toast.makeText(ctx, "सारी यादें कॉपी हो गईं!", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = p.accentSecondary, modifier = Modifier.size(14.dp))
                    Text("बैकअप कॉपी", color = p.accentSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newFact,
                onValueChange = { newFact = it },
                placeholder = { Text("नई याद जोड़ें (जैसे: मेरा नाम राहुल है)", color = p.textSecondary, fontSize = 12.5.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            QuickChip("जोड़ें", Icons.Rounded.Add) {
                if (newFact.isNotBlank()) {
                    vm.addManualMemory(newFact)
                    newFact = ""
                    tick++
                }
            }
        }

        if (memories.isEmpty()) {
            Text("अभी कोई याद सेव नहीं है। बात करते समय Meera खुद याद रख लेगी।", color = p.textSecondary, fontSize = 13.sp)
        } else {
            memories.reversed().forEach { m ->
                Surface(
                    color = p.card,
                    shape = RoundedCornerShape(14.dp),
                    shadowElevation = if (p.isDark) 0.dp else 1.dp,
                    modifier = Modifier.fillMaxWidth().border(1.dp, p.cardBorder, RoundedCornerShape(14.dp))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m, color = p.textPrimary, fontSize = 13.5.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.clickable { editText = m; editingOld = m }
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = null, tint = p.accentPrimary, modifier = Modifier.size(15.dp))
                                Text("बदलें", color = p.accentPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.clickable {
                                    vm.deleteMemory(m); tick++
                                    Toast.makeText(ctx, "हटा दिया", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = p.danger, modifier = Modifier.size(15.dp))
                                Text("हटाएँ", color = p.danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            editingOld?.let { old ->
                AlertDialog(
                    onDismissRequest = { editingOld = null },
                    title = { Text("याद / स्किल बदलें") },
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
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = p.danger, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("सारी यादें मिटाएँ", color = p.danger)
            }
        }
    }
}

@Composable
private fun ChatTabContent(ui: UiState, vm: VoiceViewModel) {
    val p = LocalMeeraColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    val totalMsgs = ui.chatMessages.size
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (totalMsgs - 1).coerceAtLeast(0)
    )
    var prevCount by remember { mutableIntStateOf(totalMsgs) }
    var isDraggingScrollbar by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(1f) }
    var trackHeightPx by remember { mutableIntStateOf(1) }

    val isAtBottom by remember(totalMsgs) {
        derivedStateOf {
            if (totalMsgs == 0) true
            else {
                val lastVis = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVis >= totalMsgs - 2
            }
        }
    }

    val scrollFraction by remember(totalMsgs) {
        derivedStateOf {
            if (totalMsgs <= 1) 1f
            else (listState.firstVisibleItemIndex.toFloat() / (totalMsgs - 1).toFloat()).coerceIn(0f, 1f)
        }
    }

    // चैट टैब खोलते ही सीधे सबसे आखिरी (ताज़ा) चैट पर आए
    LaunchedEffect(Unit) {
        if (ui.chatMessages.isNotEmpty()) {
            listState.scrollToItem(ui.chatMessages.lastIndex)
        }
    }

    // नया मैसेज आने पर अगर यूज़र नीचे है तो अपने-आप बॉटम पर स्क्रॉल हो
    LaunchedEffect(totalMsgs) {
        if (totalMsgs > prevCount && totalMsgs > 0) {
            if (isAtBottom || prevCount == 0) {
                listState.animateScrollToItem(totalMsgs - 1)
            }
        }
        prevCount = totalMsgs
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
        // Header + Top / End 1-Tap Jump Buttons + Clear
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "बातचीत ($totalMsgs)",
                color = p.textPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold
            )
            if (totalMsgs > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = p.card,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .border(1.dp, p.cardBorder, RoundedCornerShape(10.dp))
                            .clickable { scope.launch { listState.scrollToItem(0) } }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(Icons.Rounded.KeyboardDoubleArrowUp, contentDescription = "Top", tint = p.accentPrimary, modifier = Modifier.size(15.dp))
                            Text("Top", color = p.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Surface(
                        color = p.card,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .border(1.dp, p.accentSuccess.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .clickable { scope.launch { listState.scrollToItem((totalMsgs - 1).coerceAtLeast(0)) } }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(Icons.Rounded.KeyboardDoubleArrowDown, contentDescription = "End", tint = p.accentSuccess, modifier = Modifier.size(15.dp))
                            Text("End", color = p.accentSuccess, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Surface(
                        color = p.card,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .border(1.dp, p.cardBorder, RoundedCornerShape(10.dp))
                            .clickable { vm.clearChatLog() }
                    ) {
                        Box(Modifier.padding(horizontal = 7.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear", tint = p.textSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // Main Chat Area + Right-Edge Draggable Fast Scrollbar + Floating Jump-to-Latest Button
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ui.chatMessages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Meera से कुछ भी पूछें — आवाज़ से या नीचे लिखकर!",
                        color = p.textSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val showFastBar = totalMsgs > 6
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = if (showFastBar) 18.dp else 0.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(ui.chatMessages) { _, msg ->
                        val isUser = msg.role == "user"
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                color = if (isUser) p.userBubble else p.card,
                                shape = RoundedCornerShape(
                                    topStart = 18.dp, topEnd = 18.dp,
                                    bottomStart = if (isUser) 18.dp else 4.dp,
                                    bottomEnd = if (isUser) 4.dp else 18.dp
                                ),
                                shadowElevation = if (p.isDark) 0.dp else 1.dp,
                                modifier = Modifier.fillMaxWidth(0.86f)
                                    .border(1.dp, if (isUser) p.userBubble else p.cardBorder, RoundedCornerShape(18.dp))
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        msg.text,
                                        color = if (isUser) p.userBubbleText else p.textPrimary,
                                        fontSize = 14.sp
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            msg.time,
                                            color = if (isUser) p.userBubbleText.copy(alpha = 0.7f) else p.textSecondary,
                                            fontSize = 10.5.sp
                                        )
                                        if (!isUser) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                modifier = Modifier.clickable {
                                                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    cb.setPrimaryClip(ClipData.newPlainText("MeeraReply", msg.text))
                                                    Toast.makeText(ctx, "कॉपी हो गया", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = p.accentPrimary, modifier = Modifier.size(13.dp))
                                                Text("Copy", color = p.accentPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // दायीं तरफ ड्रैगेबल Fast Scrollbar (लंबी 30 दिन की चैट में 1 सेकंड में ऊपर-नीचे जाने के लिए)
                if (showFastBar) {
                    val thumbHeightPx = with(LocalDensity.current) { 52.dp.toPx() }
                    val activeFrac = if (isDraggingScrollbar) dragFraction else scrollFraction
                    val maxTravel = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                    val thumbOffsetY = (activeFrac * maxTravel).roundToInt()

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(22.dp)
                            .padding(vertical = 8.dp)
                            .onSizeChanged { trackHeightPx = it.height.coerceAtLeast(1) }
                            .pointerInput(totalMsgs, trackHeightPx) {
                                detectTapGestures { offset ->
                                    val frac = (offset.y / trackHeightPx.toFloat()).coerceIn(0f, 1f)
                                    val target = (frac * (totalMsgs - 1)).roundToInt().coerceIn(0, totalMsgs - 1)
                                    scope.launch { listState.scrollToItem(target) }
                                }
                            }
                            .pointerInput(totalMsgs, trackHeightPx) {
                                detectVerticalDragGestures(
                                    onDragStart = { offset ->
                                        isDraggingScrollbar = true
                                        val frac = (offset.y / trackHeightPx.toFloat()).coerceIn(0f, 1f)
                                        dragFraction = frac
                                        val target = (frac * (totalMsgs - 1)).roundToInt().coerceIn(0, totalMsgs - 1)
                                        scope.launch { listState.scrollToItem(target) }
                                    },
                                    onDragEnd = { isDraggingScrollbar = false },
                                    onDragCancel = { isDraggingScrollbar = false },
                                    onVerticalDrag = { change, _ ->
                                        change.consume()
                                        val frac = (change.position.y / trackHeightPx.toFloat()).coerceIn(0f, 1f)
                                        dragFraction = frac
                                        val target = (frac * (totalMsgs - 1)).roundToInt().coerceIn(0, totalMsgs - 1)
                                        scope.launch { listState.scrollToItem(target) }
                                    }
                                )
                            }
                    ) {
                        // हल्का बैकग्राउंड ट्रैक
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxHeight()
                                .width(3.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(p.cardBorder.copy(alpha = 0.65f))
                        )
                        // ड्रैगेबल थंब
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .offset { IntOffset(0, thumbOffsetY) }
                                .width(if (isDraggingScrollbar) 9.dp else 6.dp)
                                .height(52.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isDraggingScrollbar) p.accentSuccess else p.accentPrimary)
                        )
                    }

                    // फास्ट स्क्रॉल करते समय लाइव पोज़िशन और समय का बैज
                    if (isDraggingScrollbar) {
                        val previewIdx = (dragFraction * (totalMsgs - 1)).roundToInt().coerceIn(0, totalMsgs - 1)
                        val previewTime = ui.chatMessages[previewIdx].time
                        Surface(
                            color = p.panel,
                            shape = RoundedCornerShape(14.dp),
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 28.dp, top = 12.dp)
                                .border(1.dp, p.accentSuccess, RoundedCornerShape(14.dp))
                        ) {
                            Text(
                                "#${previewIdx + 1} / $totalMsgs • $previewTime",
                                color = p.textPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // जब यूज़र ऊपर स्क्रॉल कर ले तो तुरंत सबसे नीचे आने का फ्लोटिंग बटन
                if (!isAtBottom) {
                    Surface(
                        color = p.accentPrimary,
                        shape = CircleShape,
                        shadowElevation = 5.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = if (showFastBar) 28.dp else 8.dp, bottom = 10.dp)
                            .size(40.dp)
                            .clickable {
                                scope.launch { listState.animateScrollToItem((totalMsgs - 1).coerceAtLeast(0)) }
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.KeyboardDoubleArrowDown,
                                contentDescription = "सबसे नीचे जाएँ",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        val chatAttachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) vm.onSharedUrisReceived(uris)
        }
        Spacer(Modifier.height(6.dp))
        ActiveSharedFileBanner(vm)
        Surface(
            color = p.card,
            shape = RoundedCornerShape(26.dp),
            shadowElevation = if (p.isDark) 0.dp else 2.dp,
            modifier = Modifier.fillMaxWidth().border(1.dp, p.cardBorder, RoundedCornerShape(26.dp))
        ) {
            Row(Modifier.padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape)
                        .background(p.accentPrimary.copy(alpha = 0.12f))
                        .clickable { chatAttachLauncher.launch(arrayOf("*/*")) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = "फ़ाइल अटैच करें", tint = p.accentPrimary, modifier = Modifier.size(20.dp))
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("यहाँ मैसेज या कमांड लिखें…", color = p.textSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = p.textPrimary,
                        unfocusedTextColor = p.textPrimary
                    ),
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(p.accentPrimary, p.accentSecondary)))
                        .clickable {
                            if (input.isNotBlank()) {
                                vm.sendTextQuery(input, speakAloud = true)
                                input = ""
                                if (ui.chatMessages.isNotEmpty()) {
                                    scope.launch { listState.animateScrollToItem(ui.chatMessages.lastIndex) }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}

@Composable
private fun AccessibilityHubScreen(onBack: () -> Unit) {
    val p = LocalMeeraColors.current
    val ctx = LocalContext.current
    val a11yOn = MeeraAccessibilityService.instance != null
    val adbCmd = "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"

    Column(
        Modifier.fillMaxSize().background(p.bg).systemBarsPadding().padding(18.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = p.card,
                shape = CircleShape,
                modifier = Modifier.size(40.dp).border(1.dp, p.cardBorder, CircleShape).clickable(onClick = onBack)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = p.textPrimary, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("Accessibility Control Hub", color = p.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }

        Surface(
            color = p.card,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, if (a11yOn) p.accentSuccess else p.cardBorder, RoundedCornerShape(20.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (a11yOn) "Accessibility चालू है (Active)" else "Accessibility बंद है",
                    color = if (a11yOn) p.accentSuccess else p.warning,
                    fontSize = 16.sp, fontWeight = FontWeight.ExtraBold
                )
                Text(
                    "Meera स्क्रीन पढ़ और टैप कर सकती है — WhatsApp, Instagram, YouTube सब।",
                    color = p.textSecondary, fontSize = 13.sp
                )
            }
        }

        Box(
            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(p.accentPrimary, p.accentSecondary)))
                .clickable { PermHelper.openAccessibilitySettings(ctx) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (a11yOn) "Payment ऐप के लिए Accessibility खोलें" else "अभी Accessibility सेटिंग्स खोलकर ऑन करें",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
            )
        }

        Surface(
            color = p.card,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, p.cardBorder, RoundedCornerShape(18.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { PermHelper.openBatteryOptimization(ctx) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, tint = p.accentPrimary)
                        Column {
                            Text("Battery Unrestricted", color = p.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            Text("ताकि फोन बैकग्राउंड में Meera को बंद न करे", color = p.textSecondary, fontSize = 12.sp)
                        }
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = p.textSecondary)
                }
                HorizontalDivider(color = p.cardBorder)
                Row(
                    Modifier.fillMaxWidth().clickable { PermHelper.openNotificationListenerSettings(ctx) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Notifications, contentDescription = null, tint = p.accentPrimary)
                        Column {
                            Text("Notification Access", color = p.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            Text("WhatsApp मैसेज और कॉलर का नाम पढ़ने के लिए", color = p.textSecondary, fontSize = 12.sp)
                        }
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = p.textSecondary)
                }
            }
        }

        Surface(
            color = p.card,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, p.cardBorder, RoundedCornerShape(18.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ADB Command (Advanced)", color = p.accentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(adbCmd, color = p.textPrimary, fontSize = 12.sp)
                QuickChip("ADB Command कॉपी करें", Icons.Rounded.ContentCopy) {
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
    val p = LocalMeeraColors.current
    Surface(
        color = p.dockBg,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = if (p.isDark) 6.dp else 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .border(1.dp, p.cardBorder, RoundedCornerShape(28.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockTabItem(Icons.Rounded.Home, "Home", currentTab == NavTab.HOME) { onSelectTab(NavTab.HOME) }
            DockTabItem(Icons.Rounded.Visibility, "Vision", currentTab == NavTab.VISION) { onSelectTab(NavTab.VISION) }

            val micGrad = if (micActive) listOf(p.danger, Color(0xFFB91C1C)) else listOf(p.accentPrimary, p.accentSecondary)
            Box(
                Modifier
                    .size(58.dp)
                    .shadow(if (p.isDark) 4.dp else 6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(micGrad))
                    .clickable(onClick = onMicClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (micActive) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = "Mic",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            DockTabItem(Icons.Rounded.Psychology, "Yaadein", currentTab == NavTab.YAADEIN) { onSelectTab(NavTab.YAADEIN) }
            DockTabItem(Icons.Rounded.ChatBubble, "Chat", currentTab == NavTab.CHAT) { onSelectTab(NavTab.CHAT) }
        }
    }
}

@Composable
private fun DockTabItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalMeeraColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) p.accentPrimary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) p.accentPrimary else p.textSecondary,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = if (selected) p.accentPrimary else p.textSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ThemeSwatchRow(theme: MeeraPalette, selected: Boolean, onClick: () -> Unit) {
    val p = LocalMeeraColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = p.accentPrimary)
        )
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(theme.bg, theme.accentPrimary, theme.accentSecondary)))
                .border(1.dp, p.cardBorder, CircleShape)
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(theme.label, color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(theme.desc, color = p.textSecondary, fontSize = 11.5.sp)
        }
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
    val p = LocalMeeraColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = p.textSecondary, fontSize = 11.5.sp)
                Text(current, color = p.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = p.accentPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
        if (open) {
            Column(Modifier.fillMaxWidth().background(p.card.copy(alpha = 0.75f)).padding(vertical = 4.dp)) { content() }
        }
        HorizontalDivider(color = p.cardBorder)
    }
}

@Composable
private fun ModeRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalMeeraColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = p.accentPrimary)
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = p.textSecondary, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun KeyDialog(hasKey: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalMeeraColors.current
    var text by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemini API Key") },
        text = {
            Column {
                Text(
                    if (hasKey) "पहले से एक Key सेव है। नई डालने पर बदल जाएगी।" else "aistudio.google.com से Key कॉपी करके यहाँ पेस्ट करें।",
                    fontSize = 13.sp, color = p.textSecondary
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
    val p = LocalMeeraColors.current
    MeeraDynamicOrb(state = state, vm = vm, orbStyle = "CLASSIC", persona = "Meera", isDark = p.isDark, modifier = modifier)
}

@Composable
private fun ActiveSharedFileBanner(vm: VoiceViewModel) {
    val p = LocalMeeraColors.current
    val activeFile by MeeraFileVault.activeFileFlow.collectAsState()
    val visible by MeeraFileVault.chipVisibleFlow.collectAsState()
    val item = activeFile ?: return
    if (!visible) return

    val icon = when (item.kind) {
        "IMAGE" -> Icons.Rounded.Image
        "EXCEL" -> Icons.Rounded.TableChart
        else -> Icons.Rounded.Description
    }
    Surface(
        color = p.panel,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = if (p.isDark) 0.dp else 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .border(1.dp, p.accentPrimary.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(p.accentPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = p.accentPrimary, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.name, color = p.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${item.kind} • ${(item.sizeBytes / 1024).coerceAtLeast(1)} KB • तैयार है", color = p.accentSuccess, fontSize = 10.5.sp)
            }
            if (item.kind == "IMAGE") {
                Surface(
                    color = p.accentPrimary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.clickable {
                        vm.sendTextQuery("अभी जो फोटो शेयर की है उसे WhatsApp स्टेटस लगा दो", speakAloud = true)
                    }
                ) {
                    Text("Status", color = p.accentPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            if (item.kind != "EXCEL") {
                Surface(
                    color = p.accentPrimary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.clickable {
                        vm.sendTextQuery("इस फ़ाइल के डेटा से लाइव फ़ॉर्मूला वाली Excel शीट बना दो", speakAloud = true)
                    }
                ) {
                    Text("Excel", color = p.accentPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                }
            }
            Surface(
                color = p.accentSuccess.copy(alpha = 0.15f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.clickable {
                    vm.sendTextQuery("इस फ़ाइल को ध्यान से पढ़कर संक्षेप में समझाओ", speakAloud = true)
                }
            ) {
                Text("पढ़ो", color = p.accentSuccess, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Icon(
                Icons.Rounded.Close,
                contentDescription = "हटाएँ",
                tint = p.textSecondary,
                modifier = Modifier.size(18.dp).clickable { MeeraFileVault.dismissChip() }
            )
        }
    }
}

