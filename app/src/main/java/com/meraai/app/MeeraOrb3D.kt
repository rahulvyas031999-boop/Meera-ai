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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object MeeraOrbPrefs {
    private const val PREF_NAME = "meera_orb_settings"
    private const val KEY_STYLE = "orb_style"
    private const val KEY_SIZE = "orb_size_dp"

    val STYLES = listOf(
        "LIQUID_PLASMA" to "3D Liquid Plasma (लिक्विड ऊर्जा)",
        "CLASSIC" to "Classic 3D Glass Sphere (ग्लास स्फीयर)",
        "CYBER_CORE" to "Jarvis Cyber Core (होलोग्राफिक)",
        "SOLAR_NEBULA" to "Cosmic Nebula (गैलेक्सी भंवर)"
    )

    fun getStyle(ctx: Context): String =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STYLE, "LIQUID_PLASMA") ?: "LIQUID_PLASMA"

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
        STYLES.firstOrNull { it.first == code }?.second ?: "3D Liquid Plasma"
}

@Composable
fun MeeraDynamicOrb(
    state: VState,
    vm: VoiceViewModel,
    orbStyle: String = "LIQUID_PLASMA",
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
            else -> Color(0xFF8B5CF6)
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
            else -> Color(0xFF06B6D4)
        }
        else -> Color(0xFFA855F7)
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
        animationSpec = infiniteRepeatable(tween(6400, easing = LinearEasing), RepeatMode.Restart),
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
            "LIQUID_PLASMA" -> drawLiquidPlasmaOrb(primaryColor, secondaryColor, lvl, phase, spin, breathe, state, activeRipple, isDark)
            "CYBER_CORE" -> drawCyberCoreOrb(primaryColor, secondaryColor, lvl, spin, breathe, activeRipple, isDark)
            "SOLAR_NEBULA" -> drawNebulaOrb(primaryColor, secondaryColor, lvl, phase, breathe, isDark)
            else -> drawClassicEnhancedOrb(primaryColor, secondaryColor, lvl, spin, breathe, ring, state, activeRipple, isDark)
        }
    }
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
