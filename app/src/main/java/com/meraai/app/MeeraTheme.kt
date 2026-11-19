package com.meraai.app

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow

data class MeeraPalette(
    val id: String,
    val label: String,
    val desc: String,
    val isDark: Boolean,
    val bg: Color,
    val panel: Color,
    val card: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentPrimary: Color,
    val accentSecondary: Color,
    val accentSuccess: Color,
    val danger: Color,
    val warning: Color,
    val dockBg: Color,
    val userBubble: Color,
    val userBubbleText: Color
)

object MeeraThemes {
    val CRISP_LIGHT = MeeraPalette(
        id = "CRISP_LIGHT",
        label = "Crisp Light (Pro)",
        desc = "साफ़ सफ़ेद Apple/Perplexity स्टाइल — दिन में बेहद शार्प",
        isDark = false,
        bg = Color(0xFFF6F8FC),
        panel = Color(0xFFFFFFFF),
        card = Color(0xFFFFFFFF),
        cardBorder = Color(0xFFE2E8F0),
        textPrimary = Color(0xFF0F172A),
        textSecondary = Color(0xFF64748B),
        accentPrimary = Color(0xFF4F46E5),
        accentSecondary = Color(0xFF0EA5E9),
        accentSuccess = Color(0xFF059669),
        danger = Color(0xFFDC2626),
        warning = Color(0xFFD97706),
        dockBg = Color(0xFFFFFFFF),
        userBubble = Color(0xFF4F46E5),
        userBubbleText = Color(0xFFFFFFFF)
    )

    val SOFT_PEARL = MeeraPalette(
        id = "SOFT_PEARL",
        label = "Soft Pearl (Warm Light)",
        desc = "सॉफ्ट क्रीम-लैवेंडर लाइट थीम — आँखों को सुकून देने वाली",
        isDark = false,
        bg = Color(0xFFFAF7F5),
        panel = Color(0xFFFFFFFF),
        card = Color(0xFFFFFDFC),
        cardBorder = Color(0xFFECE3DC),
        textPrimary = Color(0xFF1F1A1C),
        textSecondary = Color(0xFF786B70),
        accentPrimary = Color(0xFF7C3AED),
        accentSecondary = Color(0xFFEC4899),
        accentSuccess = Color(0xFF0D9488),
        danger = Color(0xFFE11D48),
        warning = Color(0xFFD97706),
        dockBg = Color(0xFFFFFFFF),
        userBubble = Color(0xFF7C3AED),
        userBubbleText = Color(0xFFFFFFFF)
    )

    val MIDNIGHT_AURORA = MeeraPalette(
        id = "MIDNIGHT_AURORA",
        label = "Midnight Aurora (Pro Dark)",
        desc = "डीप स्लेट डार्क + इलेक्ट्रिक इंडिगो और सियान ग्लो",
        isDark = true,
        bg = Color(0xFF080C16),
        panel = Color(0xFF10172A),
        card = Color(0xFF151F38),
        cardBorder = Color(0xFF243256),
        textPrimary = Color(0xFFF8FAFC),
        textSecondary = Color(0xFF94A3B8),
        accentPrimary = Color(0xFF6366F1),
        accentSecondary = Color(0xFF38BDF8),
        accentSuccess = Color(0xFF2EE6A8),
        danger = Color(0xFFF43F5E),
        warning = Color(0xFFF59E0B),
        dockBg = Color(0xFF11192E),
        userBubble = Color(0xFF4F46E5),
        userBubbleText = Color(0xFFFFFFFF)
    )

    val EMERALD_CYBER = MeeraPalette(
        id = "EMERALD_CYBER",
        label = "Emerald Cyber (Neon Dark)",
        desc = "कार्बन ब्लैक + एमरल्ड ग्रीन और टील प्रो लुक",
        isDark = true,
        bg = Color(0xFF060E0D),
        panel = Color(0xFF0C1A18),
        card = Color(0xFF112421),
        cardBorder = Color(0xFF1E3F3A),
        textPrimary = Color(0xFFECFDF5),
        textSecondary = Color(0xFF86A8A2),
        accentPrimary = Color(0xFF10B981),
        accentSecondary = Color(0xFF06B6D4),
        accentSuccess = Color(0xFF34D399),
        danger = Color(0xFFF43F5E),
        warning = Color(0xFFFBBF24),
        dockBg = Color(0xFF0D1D1B),
        userBubble = Color(0xFF059669),
        userBubbleText = Color(0xFFFFFFFF)
    )

    val ALL = listOf(CRISP_LIGHT, SOFT_PEARL, MIDNIGHT_AURORA, EMERALD_CYBER)

    fun byId(id: String): MeeraPalette = ALL.firstOrNull { it.id == id } ?: MIDNIGHT_AURORA
}

object MeeraThemePrefs {
    private const val PREF_NAME = "meera_theme_settings"
    private const val KEY_THEME_ID = "theme_id"

    val currentThemeId = MutableStateFlow("MIDNIGHT_AURORA")
    @Volatile private var loaded = false

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val saved = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_ID, "MIDNIGHT_AURORA") ?: "MIDNIGHT_AURORA"
        currentThemeId.value = saved
    }

    fun setTheme(ctx: Context, id: String) {
        loaded = true
        currentThemeId.value = id
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME_ID, id).apply()
    }
}

val LocalMeeraColors = compositionLocalOf { MeeraThemes.MIDNIGHT_AURORA }
