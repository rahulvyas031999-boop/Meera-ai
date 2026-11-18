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
        "LIQUID_PLASMA" to "🌊 3D Liquid Plasma (लिक्विड ऊर्जा)",
        "CLASSIC" to "🔮 Classic 3D Sphere (क्लासिक)",
        "CYBER_CORE" to "⚡ Jarvis Cyber Core (होलोग्राफिक)",
        "SOLAR_NEBULA" to "🌌 Cosmic Nebula (गैलेक्सी भंवर)"
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
            .getFloat(KEY_SIZE, 225f).coerceIn(150f, 310f)

    fun setSizeDp(ctx: Context, sizeDp: Float) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SIZE, sizeDp.coerceIn(150f, 310f)).apply()
    }

    fun styleLabel(code: String): String =
        STYLES.firstOrNull { it.first == code }?.second ?: "🌊 3D Liquid Plasma"
}

@Composable
fun MeeraDynamicOrb(
    state: VState,
    vm: VoiceViewModel,
    orbStyle: String = "LIQUID_PLASMA",
    persona: String = "Meera",
    modifier: Modifier = Modifier
) {
    val lvlRaw by vm.level.collectAsStateWithLifecycle()
    val lvl by animateFloatAsState(
        targetValue = lvlRaw.coerceIn(0f, 1f),
        animationSpec = tween(75),
        label = "orb_lvl"
    )

    val targetPrimary = when (state) {
        VState.USER_SPEAKING -> Color(0xFF2EE6A8)
        VState.PROCESSING -> Color(0xFFFFB84D)
        VState.ASSISTANT_SPEAKING -> when (persona) {
            "Jarvis" -> Color(0xFF00E5FF)
            "Rudra" -> Color(0xFFFF6D00)
            else -> Color(0xFFB46BFF)
        }
        VState.ERROR -> Color(0xFFE5484D)
        VState.LISTENING -> Color(0xFF3CC8FF)
        else -> when (persona) {
            "Jarvis" -> Color(0xFF38BDF8)
            "Rudra" -> Color(0xFFFF9100)
            else -> Color(0xFF5B8CFF)
        }
    }

    val targetSecondary = when (state) {
        VState.USER_SPEAKING -> Color(0xFF38BDF8)
        VState.PROCESSING -> Color(0xFFEC4899)
        VState.ASSISTANT_SPEAKING -> when (persona) {
            "Jarvis" -> Color(0xFF3B82F6)
            "Rudra" -> Color(0xFFFF1744)
            else -> Color(0xFF00E5FF)
        }
        else -> Color(0xFF8B5CF6)
    }

    val primaryColor by animateColorAsState(targetPrimary, tween(420), label = "p_col")
    val secondaryColor by animateColorAsState(targetSecondary, tween(420), label = "s_col")

    val inf = rememberInfiniteTransition(label = "orb_inf")
    val phase by inf.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    val spin by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6800, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val breathe by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
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
            "LIQUID_PLASMA" -> drawLiquidPlasmaOrb(primaryColor, secondaryColor, lvl, phase, spin, breathe, state, activeRipple)
            "CYBER_CORE" -> drawCyberCoreOrb(primaryColor, secondaryColor, lvl, spin, breathe, activeRipple)
            "SOLAR_NEBULA" -> drawNebulaOrb(primaryColor, secondaryColor, lvl, phase, breathe)
            else -> drawClassicEnhancedOrb(primaryColor, lvl, spin, breathe, ring, state, activeRipple)
        }
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
    rippling: Boolean
) {
    val c = center
    val baseR = size.minDimension / 2f * 0.56f
    val dynamicR = baseR * (1f + 0.06f * breathe + 0.40f * lvl)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(c1.copy(alpha = 0.42f), c2.copy(alpha = 0.18f), Color.Transparent),
            center = c,
            radius = dynamicR * 2.1f
        ),
        radius = dynamicR * 2.1f,
        center = c
    )

    for (layer in 0..2) {
        val layerScale = 1f - layer * 0.11f
        val waveAmp = dynamicR * (0.04f + lvl * 0.14f) * (1f + layer * 0.25f)
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
                listOf(Color.White.copy(alpha = 0.90f), c1, c2.copy(alpha = 0.80f), Color(0xFF070A17))
            } else {
                listOf(Color.White.copy(alpha = 0.55f), c2.copy(alpha = 0.55f), c1.copy(alpha = 0.25f), Color.Transparent)
            },
            center = c + Offset(-dynamicR * 0.25f * cos(phase), -dynamicR * 0.25f * sin(phase)),
            radius = dynamicR * 1.45f
        )

        drawPath(
            path = path,
            brush = layerBrush,
            blendMode = if (layer == 0) BlendMode.SrcOver else BlendMode.Plus
        )
    }

    val ringCount = if (state == VState.PROCESSING) 3 else 2
    for (i in 0 until ringCount) {
        rotate(degrees = spin * (if (i % 2 == 0) 1.2f else -0.9f) + i * 110f, pivot = c) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, c2.copy(alpha = 0.75f), Color.White.copy(alpha = 0.9f), Color.Transparent),
                    center = c
                ),
                startAngle = 15f,
                sweepAngle = if (rippling) 150f else 95f,
                useCenter = false,
                topLeft = c - Offset(dynamicR * (0.88f + i * 0.08f), dynamicR * (0.88f + i * 0.08f)),
                size = Size(dynamicR * 2f * (0.88f + i * 0.08f), dynamicR * 2f * (0.88f + i * 0.08f)),
                style = Stroke(width = (3.2f + lvl * 4f).dp.toPx(), cap = StrokeCap.Round)
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
    rippling: Boolean
) {
    val c = center
    val baseR = size.minDimension / 2f * 0.55f
    val r = baseR * (1f + 0.04f * breathe + 0.36f * lvl)

    drawCircle(
        Brush.radialGradient(listOf(c1.copy(alpha = 0.45f), Color.Transparent), center = c, radius = r * 2.1f),
        radius = r * 2.1f,
        center = c
    )

    for (ring in 0..2) {
        val rr = r * (1.26f - ring * 0.15f)
        val dir = if (ring % 2 == 0) 1f else -1.4f
        rotate(spin * dir + ring * 60f, pivot = c) {
            for (seg in 0 until 6) {
                drawArc(
                    color = if (ring == 0) c1.copy(alpha = 0.88f) else c2.copy(alpha = 0.68f),
                    startAngle = seg * 60f + 8f,
                    sweepAngle = if (rippling) 40f else 32f,
                    useCenter = false,
                    topLeft = c - Offset(rr, rr),
                    size = Size(rr * 2f, rr * 2f),
                    style = Stroke(width = (3.5f - ring * 0.7f).dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }

    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White, c1, c2.copy(alpha = 0.75f), Color(0xFF070A17)),
            center = c,
            radius = r * 0.82f
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
    breathe: Float
) {
    val c = center
    val baseR = size.minDimension / 2f * 0.56f
    val r = baseR * (1f + 0.06f * breathe + 0.40f * lvl)

    drawCircle(
        Brush.radialGradient(listOf(c2.copy(alpha = 0.40f), c1.copy(alpha = 0.18f), Color.Transparent), center = c, radius = r * 2.15f),
        radius = r * 2.15f,
        center = c
    )

    for (i in 0 until 4) {
        val a = phase + (i * PI / 2.0).toFloat()
        val orbit = r * (0.18f + 0.15f * lvl)
        val subCenter = c + Offset(cos(a) * orbit, sin(a) * orbit)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.70f), if (i % 2 == 0) c1 else c2, Color.Transparent),
                center = subCenter,
                radius = r * 1.12f
            ),
            radius = r * 1.02f,
            center = subCenter,
            blendMode = BlendMode.Plus
        )
    }

    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.92f), c1.copy(alpha = 0.85f), Color.Transparent),
            center = c + Offset(-r * 0.2f, -r * 0.2f),
            radius = r * 0.95f
        ),
        radius = r * 0.85f,
        center = c
    )
}

private fun DrawScope.drawClassicEnhancedOrb(
    color: Color,
    lvl: Float,
    spin: Float,
    breathe: Float,
    ring: Float,
    state: VState,
    rippling: Boolean
) {
    val c = center
    val base = size.minDimension / 2f * 0.55f
    val r = base * (1f + 0.05f * breathe + 0.45f * lvl)
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = 0.40f), Color.Transparent), center = c, radius = r * 2f),
        radius = r * 2f,
        center = c
    )
    if (rippling) {
        for (i in 0..1) {
            val p = (ring + i * 0.5f) % 1f
            drawCircle(
                color.copy(alpha = (1f - p) * 0.4f),
                radius = r * (1f + p * 0.6f),
                center = c,
                style = Stroke(3.dp.toPx())
            )
        }
    }
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.85f), color, color.copy(alpha = 0.55f), Color(0xFF070A17)),
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
                Color.White.copy(alpha = 0.28f),
                startAngle = 0f,
                sweepAngle = 60f,
                useCenter = false,
                topLeft = c - Offset(r * 0.8f, r * 0.8f),
                size = Size(r * 1.6f, r * 1.6f),
                style = Stroke(r * 0.1f, cap = StrokeCap.Round)
            )
        }
    }
}
