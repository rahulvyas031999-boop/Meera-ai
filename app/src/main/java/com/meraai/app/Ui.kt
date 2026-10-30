package com.meraai.app

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

val Bg = Color(0xFF0B0F1A)
private val Panel = Color(0xFF121830)
private val Muted = Color(0xFF8A94B8)

@Composable
fun MeraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF5B8CFF), background = Bg, surface = Panel, onSurface = Color.White, onBackground = Color.White
        ),
        content = content
    )
}

@Composable
fun MeraApp(vm: VoiceViewModel, onMic: () -> Unit) {
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1800); splash = false }
    Crossfade(targetState = splash, animationSpec = tween(500), label = "splash") { s ->
        if (s) SplashScreen() else Dashboard(vm, onMic)
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
                    .background(Brush.radialGradient(listOf(Color(0xFF9DB7FF), Color(0xFF5B8CFF), Color(0xFF1B2A6B))), CircleShape)
            )
            Spacer(Modifier.height(28.dp))
            Text("मीरा AI", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("स्टार्ट हो रही है…", color = Muted, fontSize = 15.sp, modifier = Modifier.alpha(a))
        }
    }
}

private fun statusText(s: UiState): String = when (s.state) {
    VState.IDLE -> "माइक दबाकर बात शुरू करें"
    VState.CONNECTING -> "कनेक्ट हो रहा है…"
    VState.LISTENING -> "सुन रहा हूँ…"
    VState.USER_SPEAKING -> "आप बोल रहे हैं"
    VState.PROCESSING -> "सोच रहा हूँ…"
    VState.ASSISTANT_SPEAKING -> "बोल रहा हूँ…"
    VState.ERROR -> s.error ?: "कुछ गड़बड़ हुई"
}

@Composable
fun Dashboard(vm: VoiceViewModel, onMic: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showKey by remember { mutableStateOf(false) }
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
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("मीरा AI", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF232C52))
                    Text("बातचीत का तरीका", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    ModeRow("⚡ Live API", "Gemini Live — आवाज़ से आवाज़, सबसे तेज़", ui.mode == Mode.LIVE) { vm.setMode(Mode.LIVE) }
                    ModeRow("🧩 Modular", "Fast STT (3.5) → Flash Lite → TTS (3.8)", ui.mode == Mode.MODULAR) { vm.setMode(Mode.MODULAR) }

                    if (ui.mode == Mode.LIVE) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF232C52))
                        Text("Live मॉडल चुनें", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                        Cfg.LIVE_MODELS.forEach { m ->
                            ModeRow(m, "WebSocket Bidi Audio", ui.liveModel == m) { vm.setLiveModel(m) }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF232C52))
                    Text("आवाज़ चुनें (Google Voices)", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    Cfg.VOICES.forEach { (vCode, vLabel) ->
                        ModeRow("🔊 $vCode", vLabel, ui.voice == vCode) { vm.setVoice(vCode) }
                    }
                }
            }
        }
    ) {
        Column(
            Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Default.Menu, contentDescription = "मेन्यू", tint = Color.White)
                }
                Text(
                    if (ui.mode == Mode.LIVE) "Live (${ui.liveModel}) • ${ui.voice}" else "Modular • ${ui.voice}",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center)
                )
            }
            Spacer(Modifier.weight(1f))
            Orb(ui.state, vm, Modifier.size(280.dp))
            Spacer(Modifier.height(20.dp))
            Text(statusText(ui), color = if (ui.state == VState.ERROR) Color(0xFFFF6B6B) else Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
            ui.latencyMs?.let { Text("⏱ $it ms", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().heightIn(min = 110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (ui.user.isNotBlank()) Text(ui.user, color = Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (ui.ai.isNotBlank()) Text(ui.ai, color = Color.White, fontSize = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            MicButton(active, onMic)
            Spacer(Modifier.height(32.dp))
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
private fun MicButton(active: Boolean, onClick: () -> Unit) {
    val c = if (active) Color(0xFFE5484D) else Color(0xFF5B8CFF)
    Box(
        Modifier.size(88.dp).border(2.dp, c.copy(alpha = 0.35f), CircleShape).padding(6.dp)
            .clip(CircleShape).background(c).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(if (active) "■" else "🎙", fontSize = 30.sp, color = Color.White)
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
        VState.IDLE -> Color(0xFF5B8CFF)
        VState.CONNECTING -> Color(0xFF7C8CB8)
        VState.LISTENING -> Color(0xFF3CC8FF)
        VState.USER_SPEAKING -> Color(0xFF2EE6A8)
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
                listOf(Color.White.copy(alpha = 0.85f), color, color.copy(alpha = 0.55f), Color(0xFF0B0F1A)),
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
