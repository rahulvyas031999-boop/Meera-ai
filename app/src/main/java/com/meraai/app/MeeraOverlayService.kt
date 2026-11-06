package com.meraai.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class MeeraOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var edgeView: EdgeLightingView? = null

    companion object {
        @Volatile var isRunning = false
            private set

        fun start(ctx: Context) {
            if (isRunning) return
            if (!Settings.canDrawOverlays(ctx)) return
            try {
                ctx.startService(Intent(ctx, MeeraOverlayService::class.java))
            } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            if (!isRunning) return
            try {
                ctx.stopService(Intent(ctx, MeeraOverlayService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        edgeView = EdgeLightingView(this)
        try {
            windowManager?.addView(edgeView, params)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        isRunning = false
        try {
            edgeView?.let { windowManager?.removeView(it) }
            edgeView = null
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class EdgeLightingView(context: Context) : View(context) {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 28f
        alpha = 90
    }
    private val rect = RectF()
    private var hue = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hue = (hue + 3.5f) % 360f

        val c1 = Color.HSVToColor(floatArrayOf(hue, 0.95f, 1f))
        val c2 = Color.HSVToColor(floatArrayOf((hue + 90f) % 360f, 0.95f, 1f))
        val c3 = Color.HSVToColor(floatArrayOf((hue + 180f) % 360f, 0.95f, 1f))
        val c4 = Color.HSVToColor(floatArrayOf((hue + 270f) % 360f, 0.95f, 1f))

        val shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(c1, c2, c3, c4, c1),
            null, Shader.TileMode.CLAMP
        )
        strokePaint.shader = shader
        glowPaint.shader = shader

        val pad = 8f
        rect.set(pad, pad, width.toFloat() - pad, height.toFloat() - pad)
        canvas.drawRoundRect(rect, 38f, 38f, glowPaint)
        canvas.drawRoundRect(rect, 38f, 38f, strokePaint)

        val badge = MeeraMission.overlayBadge()
        if (badge.isNotEmpty()) {
            val pillW = (width * 0.72f).coerceAtMost(720f)
            val pillH = 76f
            val left = (width - pillW) / 2f
            val top = 96f
            val pillRect = RectF(left, top, left + pillW, top + pillH)
            val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#DD090D18")
                style = Paint.Style.FILL
            }
            val brdP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#2EE6A8")
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 31f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            canvas.drawRoundRect(pillRect, 38f, 38f, bgP)
            canvas.drawRoundRect(pillRect, 38f, 38f, brdP)
            val shortBadge = if (badge.length > 36) badge.take(35) + "…" else badge
            canvas.drawText(shortBadge, width / 2f, top + 49f, txtP)
        }

        postInvalidateOnAnimation()
    }
}
