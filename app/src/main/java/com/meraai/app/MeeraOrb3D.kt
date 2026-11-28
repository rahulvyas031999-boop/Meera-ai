package com.meraai.app

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private data class GlobeDot3D(val x: Float, val y: Float, val z: Float, val lat: Float)

private object GlobeMeshCache {
    val dots: List<GlobeDot3D> = buildList {
        val count = 190
        val goldenAngle = (PI * (3.0 - sqrt(5.0))).toFloat()
        for (i in 0 until count) {
            val y = 1f - (i.toFloat() / (count - 1).toFloat()) * 2f
            val r = sqrt((1f - y * y).coerceAtLeast(0f))
            val theta = goldenAngle * i
            val x = cos(theta) * r
            val z = sin(theta) * r
            add(GlobeDot3D(x, y, z, y))
        }
    }
}

object MeeraOrbPrefs {
    private const val PREF_NAME = "meera_orb_settings"
    private const val KEY_STYLE = "orb_style"
    private const val KEY_SIZE = "orb_size_dp"

    val STYLES = listOf(
        "DOT_GLOBE_HUD" to "3D Dot-Globe HUD (आर्चर / जार्विस)",
        "LIQUID_PLASMA" to "3D Liquid Plasma (लिक्विड ऊर्जा)",
        "CLASSIC" to "Classic 3D Glass Sphere (ग्लास स्फीयर)",
        "CYBER_CORE" to "Jarvis Cyber Core (होलोग्राफिक)",
        "SOLAR_NEBULA" to "Cosmic Nebula (गैलेक्सी भंवर)"
    )

    fun getStyle(ctx: Context): String =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STYLE, "DOT_GLOBE_HUD") ?: "DOT_GLOBE_HUD"

    fun setStyle(ctx: Context, style: String) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_STYLE, style).apply()
    }

    fun getSizeDp(ctx: Context): Float =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_SIZE, 220f).coerceIn(150f, 300f)

    fun setSizeDp(ctx: Context, sizeDp: Float) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SIZE, sizeDp.coerceIn(150f, 300f)).apply()
    }

    fun styleLabel(code: String): String =
        STYLES.firstOrNull { it.first == code }?.second ?: "3D Dot-Globe HUD"
}

@Composable
fun MeeraHudOrbStage(
    ui: UiState,
    vm: VoiceViewModel,
    orbStyle: String,
    orbSizeDp: Float,
    isDark: Boolean,
    onOpenVision: () -> Unit = {},
    onOpenYaadein: () -> Unit = {}
) {
    if (orbStyle != "DOT_GLOBE_HUD") {
        MeeraDynamicOrb(
            state = ui.state,
            vm = vm,
            orbStyle = orbStyle,
            persona = ui.persona,
            isDark = isDark,
            modifier = Modifier.size(orbSizeDp.dp)
        )
        return
    }

    val p = LocalMeeraColors.current
    val muted by MicMute.flow.collectAsState()
    val bgTasks by MeeraBackgroundMonitor.tasks.collectAsState()
    val runningTask = bgTasks.firstOrNull { it.status == "RUNNING" }

    val micActive = !muted && (ui.state == VState.LISTENING || ui.state == VState.USER_SPEAKING || ui.state == VState.ASSISTANT_SPEAKING)
    val toolActive = ui.state == VState.PROCESSING || runningTask != null
    val visionActive = MeeraVision.cameraOn
    val memActive = ui.memoryCount > 0

    val stageHeight = (orbSizeDp + 44f).coerceIn(210f, 310f)
    val globeSize = (orbSizeDp * 0.90f).coerceIn(145f, 250f)

    val lineBase = if (isDark) Color(0xFF38BDF8) else p.accentPrimary

    Box(
        Modifier
            .fillMaxWidth()
            .height(stageHeight.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val orbR = (globeSize.dp.toPx() / 2f) * 0.66f
            val strokeW = 1.4.dp.toPx()

            fun drawHudBranch(
                dirX: Float,
                dirY: Float,
                active: Boolean,
                activeCol: Color
            ) {
                val col = if (active) activeCol.copy(alpha = 0.85f) else lineBase.copy(alpha = if (isDark) 0.28f else 0.32f)
                val start = Offset(cx + dirX * orbR * 0.85f, cy + dirY * orbR * 0.65f)
                val elbow = Offset(cx + dirX * (size.width * 0.30f), cy + dirY * (size.height * 0.33f))
                val end = Offset(cx + dirX * (size.width * 0.41f), elbow.y)
                drawLine(col, start, elbow, strokeW, StrokeCap.Round)
                drawLine(col, elbow, end, strokeW, StrokeCap.Round)
                drawCircle(col, radius = if (active) 3.4.dp.toPx() else 2.2.dp.toPx(), center = start)
                drawCircle(col, radius = if (active) 3.4.dp.toPx() else 2.2.dp.toPx(), center = end)
            }

            drawHudBranch(-1f, -1f, micActive, Color(0xFF2EE6A8))
            drawHudBranch(1f, -1f, toolActive, Color(0xFFF59E0B))
            drawHudBranch(-1f, 1f, visionActive, Color(0xFF38BDF8))
            drawHudBranch(1f, 1f, memActive, Color(0xFFA855F7))
        }

        MeeraDynamicOrb(
            state = ui.state,
            vm = vm,
            orbStyle = "DOT_GLOBE_HUD",
            persona = ui.persona,
            isDark = isDark,
            modifier = Modifier.size(globeSize.dp)
        )

        HudNodePill(
            label = "MIC",
            sub = when {
                muted -> "MUTE"
                ui.state == VState.USER_SPEAKING -> "VOICE"
                micActive -> "LIVE"
                else -> "IDLE"
            },
            active = micActive,
            activeColor = Color(0xFF2EE6A8),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 6.dp),
            onClick = { if (ui.state != VState.IDLE) vm.toggleMute() }
        )

        HudNodePill(
            label = "TOOL",
            sub = if (runningTask != null) "${runningTask.percent}%" else if (toolActive) "RUN" else "READY",
            active = toolActive,
            activeColor = Color(0xFFF59E0B),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp),
            onClick = { MeeraBackgroundMonitor.openMonitor() }
        )

        HudNodePill(
            label = "VISION",
            sub = if (visionActive) "ON" else "SCAN",
            active = visionActive,
            activeColor = Color(0xFF38BDF8),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 6.dp),
            onClick = onOpenVision
        )

        HudNodePill(
            label = "MEMORY",
            sub = "${ui.memoryCount}",
            active = memActive,
            activeColor = Color(0xFFA855F7),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 6.dp),
            onClick = onOpenYaadein
        )
    }
}

@Composable
private fun HudNodePill(
    label: String,
    sub: String,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val p = LocalMeeraColors.current
    val borderCol = if (active) activeColor.copy(alpha = 0.85f) else p.cardBorder
    val bgCol = if (active) activeColor.copy(alpha = if (p.isDark) 0.16f else 0.12f) else p.card.copy(alpha = 0.90f)

    Surface(
        color = bgCol,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .border(1.dp, borderCol, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (active) activeColor else p.textSecondary.copy(alpha = 0.45f))
            )
            Column {
                Text(
                    label,
                    color = if (active) activeColor else p.textSecondary,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.6.sp
                )
                Text(
                    sub,
                    color = p.textPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun MeeraDynamicOrb(
    state: VState,
    vm: VoiceViewModel,
    orbStyle: String = "DOT_GLOBE_HUD",
    persona: String = "Meera",
    isDark: Boolean = true,
    modifier: Modifier = Modifier
) {
    val lvlRaw by vm.level.collectAsStateWithLifecycle()
    val lvl by animateFloatAsState(
        targetValue = lvlRaw.coerceIn(0f, 1f),
        animationSpec = tween(70),
        label = "orb_lvl"
    )

    val targetPrimary = when (state) {
        VState.USER_SPEAKING -> Color(0xFF10B981)
        VState.PROCESSING -> Color(0xFFF59E0B)
        VState.ASSISTANT_SPEAKING -> when (persona) {
            "Jarvis" -> Color(0xFF0EA5E9)
            "Rudra" -> Color(0xFFF97316)
            else -> Color(0xFF38BDF8)
        }
        VState.ERROR -> Color(0xFFEF4444)
        VState.LISTENING -> Color(0xFF06B6D4)
        else -> when (persona) {
            "Jarvis" -> Color(0xFF2563EB)
            "Rudra" -> Color(0xFFEA580C)
            else -> Color(0xFF4F46E5)
        }
    }

    val targetSecondary = when (state) {
        VState.USER_SPEAKING -> Color(0xFF38BDF8)
        VState.PROCESSING -> Color(0xFFEC4899)
        VState.ASSISTANT_SPEAKING -> when (persona) {
            "Jarvis" -> Color(0xFF6366F1)
            "Rudra" -> Color(0xFFEF4444)
            else -> Color(0xFF2EE6A8)
        }
        else -> Color(0xFF38BDF8)
    }

    val primaryColor by animateColorAsState(targetPrimary, tween(400), label = "p_col")
    val secondaryColor by animateColorAsState(targetSecondary, tween(400), label = "s_col")

    val inf = rememberInfiniteTransition(label = "orb_inf")
    val phase by inf.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    val spin by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7200, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val breathe by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    val ring by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearOutSlowInEasing)),
        label = "ring"
    )

    val activeRipple = state == VState.USER_SPEAKING || state == VState.ASSISTANT_SPEAKING

    Canvas(modifier = modifier) {
        when (orbStyle) {
            "DOT_GLOBE_HUD" -> drawDotGlobeHudOrb(primaryColor, secondaryColor, lvl, phase, spin, breathe, state, activeRipple, isDark)
            "LIQUID_PLASMA" -> drawLiquidPlasmaOrb(primaryColor, secondaryColor, lvl, phase, spin, breathe, state, activeRipple, isDark)
            "CYBER_CORE" -> drawCyberCoreOrb(primaryColor, secondaryColor, lvl, spin, breathe, activeRipple, isDark)
            "SOLAR_NEBULA" -> drawNebulaOrb(primaryColor, secondaryColor, lvl, phase, breathe, isDark)
            else -> drawClassicEnhancedOrb(primaryColor, secondaryColor, lvl, spin, breathe, ring, state, activeRipple, isDark)
        }
    }
}

private fun DrawScope.drawDotGlobeHudOrb(
    c1: Color,
    c2: Color,
    lvl: Float,
    phase: Float,
    spin: Float,
    breathe: Float,
    state: VState,
    rippling: Boolean,
    isDark: Boolean
) {
    val c = center
    val baseR = size.minDimension / 2f * 0.62f
    val r = baseR * (1f + 0.035f * breathe + 0.22f * lvl)

    // 1. Ambient Holographic Core Glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                c2.copy(alpha = if (isDark) 0.34f else 0.22f),
                c1.copy(alpha = if (isDark) 0.16f else 0.10f),
                Color.Transparent
            ),
            center = c,
            radius = r * 1.55f
        ),
        radius = r * 1.55f,
        center = c
    )

    // 2. Outer Calibration HUD Ring + Segmented Arcs
    val outerR = r * 1.18f
    drawCircle(
        color = c1.copy(alpha = if (isDark) 0.22f else 0.28f),
        radius = outerR,
        center = c,
        style = Stroke(width = 1.dp.toPx())
    )
    rotate(degrees = spin * (if (state == VState.PROCESSING) 1.8f else 0.85f), pivot = c) {
        for (i in 0 until 4) {
            drawArc(
                color = if (i % 2 == 0) c2.copy(alpha = 0.85f) else c1.copy(alpha = 0.65f),
                startAngle = i * 90f + 12f,
                sweepAngle = if (rippling) 44f else 30f,
                useCenter = false,
                topLeft = c - Offset(outerR, outerR),
                size = Size(outerR * 2f, outerR * 2f),
                style = Stroke(width = (2.2f + lvl * 1.8f).dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }

    val innerHudR = r * 1.07f
    rotate(degrees = -spin * 1.15f, pivot = c) {
        for (t in 0 until 24) {
            val ang = (t * 15.0 * PI / 180.0).toFloat()
            val tickLen = if (t % 3 == 0) 6.dp.toPx() else 3.dp.toPx()
            val p1 = c + Offset(cos(ang) * (innerHudR - tickLen), sin(ang) * (innerHudR - tickLen))
            val p2 = c + Offset(cos(ang) * innerHudR, sin(ang) * innerHudR)
            drawLine(
                color = c2.copy(alpha = if (t % 3 == 0) 0.65f else 0.28f),
                start = p1,
                end = p2,
                strokeWidth = 1.2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }

    // 3. 3D Rotating Fibonacci Dot-Globe (Audio Reactive)
    val rotY = (spin * PI / 180.0).toFloat()
    val rotX = 0.34f + 0.10f * sin(phase)
    val cosY = cos(rotY)
    val sinY = sin(rotY)
    val cosX = cos(rotX)
    val sinX = sin(rotX)

    val dotBasePx = (2.1f + lvl * 1.1f).dp.toPx()

    for (d in GlobeMeshCache.dots) {
        val wave = if (rippling) 1f + lvl * 0.18f * sin(d.lat * 5.5f + phase * 2.2f) else 1f
        val x0 = d.x * wave
        val y0 = d.y * wave
        val z0 = d.z * wave

        val x1 = x0 * cosY + z0 * sinY
        val z1 = -x0 * sinY + z0 * cosY
        val y2 = y0 * cosX - z1 * sinX
        val z2 = y0 * sinX + z1 * cosX

        val depth = ((z2 + 1.15f) / 2.3f).coerceIn(0.08f, 1f)
        val persp = 0.86f + 0.24f * depth
        val sx = c.x + x1 * r * persp
        val sy = c.y + y2 * r * persp

        val dotCol = if (d.lat > 0f) c2 else c1
        val alpha = (0.14f + 0.86f * depth * depth).coerceIn(0.12f, 1f)
        val dotR = dotBasePx * (0.45f + 0.78f * depth)

        if (depth > 0.72f) {
            drawCircle(
                color = dotCol.copy(alpha = alpha * 0.28f),
                radius = dotR * 2.1f,
                center = Offset(sx, sy)
            )
        }
        drawCircle(
            color = if (depth > 0.86f && rippling) Color.White.copy(alpha = alpha * 0.92f) else dotCol.copy(alpha = alpha),
            radius = dotR,
            center = Offset(sx, sy)
        )
    }

    // 4. Center Reactor Core Pulse
    val coreR = r * (0.19f + 0.14f * lvl + 0.03f * breathe)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.90f), c2.copy(alpha = 0.70f), Color.Transparent),
            center = c,
            radius = coreR * 2.1f
        ),
        radius = coreR * 2.1f,
        center = c
    )
}

private fun DrawScope.drawOrbParticles(c: Color, centerPt: Offset, radius: Float, phase: Float, isDark: Boolean) {
    val dotCount = 10
    for (i in 0 until dotCount) {
        val a = phase * (if (i % 2 == 0) 0.7f else -0.5f) + (i * (2f * PI.toFloat() / dotCount))
        val dist = radius * (1.16f + 0.14f * sin(phase * 1.5f + i))
        val pt = centerPt + Offset(cos(a) * dist, sin(a) * dist)
        drawCircle(
            color = c.copy(alpha = if (isDark) 0.65f else 0.50f),
            radius = (2.2f + (i % 3) * 1.1f).dp.toPx(),
            center = pt
        )
    }
}

private fun DrawScope.drawLiquidPlasmaOrb(
    c1: Color,
    c2: Color,
    lvl: Float,
    phase: Float,
    spin: Float,
    breathe: Float,
    state: VState,
    rippling: Boolean,
    isDark: Boolean
) {
    val c = center
    val baseR = size.minDimension / 2f * 0.54f
    val dynamicR = baseR * (1f + 0.055f * breathe + 0.36f * lvl)
    val rimEdge = if (isDark) Color(0xFF0A1024) else c1.copy(alpha = 0.85f)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(c1.copy(alpha = if (isDark) 0.44f else 0.30f), c2.copy(alpha = if (isDark) 0.18f else 0.12f), Color.Transparent),
            center = c,
            radius = dynamicR * 2.0f
        ),
        radius = dynamicR * 2.0f,
        center = c
    )

    drawOrbParticles(c2, c, dynamicR, phase, isDark)

    for (layer in 0..2) {
        val layerScale = 1f - layer * 0.10f
        val waveAmp = dynamicR * (0.035f + lvl * 0.13f) * (1f + layer * 0.25f)
        val freq = 3 + layer * 2
        val path = Path()
        val steps = 72
        for (i in 0..steps) {
            val angle = (i.toFloat() / steps) * (2f * PI).toFloat()
            val w1 = sin(angle * freq + phase * (1.2f + layer * 0.4f))
            val w2 = cos(angle * (freq + 2) - phase * 1.5f)
            val r = (dynamicR * layerScale) + waveAmp * (w1 * 0.6f + w2 * 0.4f)
            val px = c.x + r * cos(angle)
            val py = c.y + r * sin(angle)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()

        val layerBrush = Brush.radialGradient(
            colors = if (layer == 0) {
                listOf(Color.White.copy(alpha = 0.94f), c1, c2.copy(alpha = 0.88f), rimEdge)
            } else {
                listOf(Color.White.copy(alpha = if (isDark) 0.55f else 0.65f), c2.copy(alpha = 0.52f), c1.copy(alpha = 0.25f), Color.Transparent)
            },
            center = c + Offset(-dynamicR * 0.26f * cos(phase), -dynamicR * 0.26f * sin(phase)),
            radius = dynamicR * 1.45f
        )

        drawPath(
            path = path,
            brush = layerBrush,
            blendMode = if (layer == 0 || !isDark) BlendMode.SrcOver else BlendMode.Plus
        )
    }

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.65f), Color.Transparent),
            center = c + Offset(-dynamicR * 0.32f, -dynamicR * 0.34f),
            radius = dynamicR * 0.52f
        ),
        radius = dynamicR * 0.48f,
        center = c + Offset(-dynamicR * 0.30f, -dynamicR * 0.32f)
    )

    val ringCount = if (state == VState.PROCESSING) 3 else 2
    for (i in 0 until ringCount) {
        rotate(degrees = spin * (if (i % 2 == 0) 1.2f else -0.9f) + i * 110f, pivot = c) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, c2.copy(alpha = 0.80f), Color.White.copy(alpha = 0.92f), Color.Transparent),
                    center = c
                ),
                startAngle = 15f,
                sweepAngle = if (rippling) 145f else 90f,
                useCenter = false,
                topLeft = c - Offset(dynamicR * (0.90f + i * 0.08f), dynamicR * (0.90f + i * 0.08f)),
                size = Size(dynamicR * 2f * (0.90f + i * 0.08f), dynamicR * 2f * (0.90f + i * 0.08f)),
                style = Stroke(width = (3.0f + lvl * 3.8f).dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

private fun DrawScope.drawCyberCoreOrb(
    c1: Color,
    c2: Color,
    lvl: Float,
    spin: Float,
    breathe: Float,
    rippling: Boolean,
    isDark: Boolean
) {
    val c = center
    val baseR = size.minDimension / 2f * 0.54f
    val r = baseR * (1f + 0.04f * breathe + 0.34f * lvl)

    drawCircle(
        Brush.radialGradient(listOf(c1.copy(alpha = if (isDark) 0.42f else 0.28f), Color.Transparent), center = c, radius = r * 2.0f),
        radius = r * 2.0f,
        center = c
    )

    for (ring in 0..2) {
        val rr = r * (1.24f - ring * 0.14f)
        val dir = if (ring % 2 == 0) 1f else -1.4f
        rotate(spin * dir + ring * 60f, pivot = c) {
            for (seg in 0 until 6) {
                drawArc(
                    color = if (ring == 0) c1.copy(alpha = 0.90f) else c2.copy(alpha = 0.72f),
                    startAngle = seg * 60f + 8f,
                    sweepAngle = if (rippling) 40f else 32f,
                    useCenter = false,
                    topLeft = c - Offset(rr, rr),
                    size = Size(rr * 2f, rr * 2f),
                    style = Stroke(width = (3.4f - ring * 0.7f).dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }

    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White, c1, c2.copy(alpha = 0.85f), if (isDark) Color(0xFF0A1024) else c1.copy(alpha = 0.9f)),
            center = c + Offset(-r * 0.2f, -r * 0.2f),
            radius = r * 0.92f
        ),
        radius = r * 0.76f,
        center = c
    )
}

private fun DrawScope.drawNebulaOrb(
    c1: Color,
    c2: Color,
    lvl: Float,
    phase: Float,
    breathe: Float,
    isDark: Boolean
) {
    val c = center
    val baseR = size.minDimension / 2f * 0.54f
    val r = baseR * (1f + 0.06f * breathe + 0.36f * lvl)

    drawCircle(
        Brush.radialGradient(listOf(c2.copy(alpha = if (isDark) 0.40f else 0.26f), c1.copy(alpha = 0.16f), Color.Transparent), center = c, radius = r * 2.05f),
        radius = r * 2.05f,
        center = c
    )

    drawOrbParticles(c1, c, r, phase, isDark)

    for (i in 0 until 4) {
        val a = phase + (i * PI / 2.0).toFloat()
        val orbit = r * (0.17f + 0.14f * lvl)
        val subCenter = c + Offset(cos(a) * orbit, sin(a) * orbit)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.75f), if (i % 2 == 0) c1 else c2, Color.Transparent),
                center = subCenter,
                radius = r * 1.10f
            ),
            radius = r * 0.98f,
            center = subCenter,
            blendMode = if (isDark) BlendMode.Plus else BlendMode.SrcOver
        )
    }

    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.94f), c1.copy(alpha = 0.88f), Color.Transparent),
            center = c + Offset(-r * 0.2f, -r * 0.2f),
            radius = r * 0.95f
        ),
        radius = r * 0.84f,
        center = c
    )
}

private fun DrawScope.drawClassicEnhancedOrb(
    color: Color,
    secondary: Color,
    lvl: Float,
    spin: Float,
    breathe: Float,
    ring: Float,
    state: VState,
    rippling: Boolean,
    isDark: Boolean
) {
    val c = center
    val base = size.minDimension / 2f * 0.54f
    val r = base * (1f + 0.05f * breathe + 0.40f * lvl)
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = if (isDark) 0.40f else 0.28f), Color.Transparent), center = c, radius = r * 1.95f),
        radius = r * 1.95f,
        center = c
    )
    if (rippling) {
        for (i in 0..1) {
            val p = (ring + i * 0.5f) % 1f
            drawCircle(
                color.copy(alpha = (1f - p) * 0.45f),
                radius = r * (1f + p * 0.55f),
                center = c,
                style = Stroke(3.dp.toPx())
            )
        }
    }
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.92f), color, secondary.copy(alpha = 0.78f), if (isDark) Color(0xFF0A1024) else color),
            center = c + Offset(-r * 0.3f, -r * 0.3f),
            radius = r * 1.5f
        ),
        radius = r,
        center = c
    )
    val arcs = if (state == VState.PROCESSING) 3 else 1
    for (i in 0 until arcs) {
        rotate(spin * (if (state == VState.PROCESSING) 2f else 1f) + i * 120f, pivot = c) {
            drawArc(
                Color.White.copy(alpha = 0.35f),
                startAngle = 0f,
                sweepAngle = 65f,
                useCenter = false,
                topLeft = c - Offset(r * 0.8f, r * 0.8f),
                size = Size(r * 1.6f, r * 1.6f),
                style = Stroke(r * 0.1f, cap = StrokeCap.Round)
            )
        }
    }
}
