package com.p25.apx1000.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * APX1000 radio skin. Drawn against a fixed 480x320 design surface and scaled
 * proportionally, so it renders correctly both on a 320x240 HT display and on
 * the top half of a large smartphone.
 */
class Apx1000View @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Light { IDLE, RX, TX, INHIBIT }

    var zone: String = "ZONE 1"
        set(v) { field = v; invalidate() }

    var channel: String = "APX-1000"
        set(v) { field = v; invalidate() }

    var unitId: String = "----"
        set(v) { field = v; invalidate() }

    var speakerId: String? = null
        set(v) { field = v; invalidate() }

    var light: Light = Light.IDLE
        set(v) { field = v; invalidate() }

    var signalLevel: Int = 4
        set(v) { field = v.coerceIn(0, 4); invalidate() }

    var batteryPct: Int = 82
        set(v) { field = v.coerceIn(0, 100); invalidate() }

    var softkeyHighlight: Int = -1
        set(v) { field = v; invalidate() }

    var statusText: String = "READY"
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val screenRect = RectF()

    private val backlightColor: Int
        get() = when (light) {
            Light.RX -> GREEN
            Light.TX -> YELLOW
            Light.INHIBIT -> RED
            Light.IDLE -> AMBER
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width / DESIGN_W, height / DESIGN_H)
        if (s <= 0f) return
        val ox = (width - DESIGN_W * s) / 2f
        val oy = (height - DESIGN_H * s) / 2f

        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        drawBevel(canvas)
        drawScreen(canvas)
        canvas.restore()
    }

    private fun drawBevel(canvas: Canvas) {
        val r = RectF(0f, 0f, DESIGN_W, DESIGN_H)
        paint.shader = LinearGradient(0f, 0f, 0f, DESIGN_H, BEVEL_TOP, BEVEL_BOTTOM, Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(r, 18f, 18f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.parseColor("#0A0A0A")
        canvas.drawRoundRect(r, 18f, 18f, paint)
    }

    private fun drawScreen(canvas: Canvas) {
        screenRect.set(SCREEN_INSET, SCREEN_INSET, DESIGN_W - SCREEN_INSET, DESIGN_H - SCREEN_INSET)

        // Backlight glow around the display.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.color = withAlpha(backlightColor, 0x55)
        canvas.drawRoundRect(screenRect, 10f, 10f, paint)

        // Screen background.
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            screenRect.left, screenRect.top, screenRect.left, screenRect.bottom,
            Color.parseColor("#04170A"), Color.parseColor("#020B05"), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(screenRect, 10f, 10f, paint)
        paint.shader = null

        // Backlight border.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = backlightColor
        canvas.drawRoundRect(screenRect, 10f, 10f, paint)
        paint.style = Paint.Style.FILL

        drawStatusBar(canvas)
        drawMainArea(canvas)
        drawSoftkeys(canvas)
    }

    private fun drawStatusBar(canvas: Canvas) {
        val top = screenRect.top + 12f
        val textColor = backlightColor

        // Signal bars.
        val barW = 5f
        for (i in 0 until 4) {
            val h = 6f + i * 5f
            val left = screenRect.left + 16f + i * (barW + 4f)
            paint.color = if (i < signalLevel) textColor else withAlpha(textColor, 0x33)
            canvas.drawRect(left, top + (20f - h), left + barW, top + 20f, paint)
        }

        // Network label.
        textPaint.color = withAlpha(textColor, 0xCC)
        textPaint.textSize = 14f
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText(statusText, screenRect.left + 54f, top + 19f, textPaint)

        // Battery.
        val batRight = screenRect.right - 16f
        val batW = 34f
        val batH = 16f
        val batTop = top + 3f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = textColor
        canvas.drawRect(batRight - batW, batTop, batRight, batTop + batH, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(batRight, batTop + batH / 2f - 3f, batRight + 4f, batTop + batH / 2f + 3f, paint)
        val fillW = (batW - 6f) * batteryPct / 100f
        paint.color = if (batteryPct <= 15) RED else textColor
        canvas.drawRect(batRight - batW + 3f, batTop + 3f, batRight - batW + 3f + fillW, batTop + batH - 3f, paint)

        textPaint.textSize = 12f
        textPaint.color = withAlpha(textColor, 0xCC)
        canvas.drawText("$batteryPct%", batRight - batW - 42f, batTop + 13f, textPaint)
    }

    private fun drawMainArea(canvas: Canvas) {
        val textColor = backlightColor

        // Zone (top left of main area).
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textSize = 16f
        textPaint.color = withAlpha(textColor, 0xAA)
        canvas.drawText(zone, screenRect.left + 18f, screenRect.top + 66f, textPaint)

        // TX/RX/status tag (top right).
        val tag = when (light) {
            Light.TX -> "TX"
            Light.RX -> "RX"
            Light.INHIBIT -> "BUSY"
            Light.IDLE -> "IDLE"
        }
        textPaint.textSize = 16f
        textPaint.color = textColor
        val tagW = textPaint.measureText(tag)
        canvas.drawText(tag, screenRect.right - 18f - tagW, screenRect.top + 66f, textPaint)

        // Channel / talkgroup (large, centered).
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textPaint.textSize = 42f
        textPaint.color = textColor
        val chW = textPaint.measureText(channel)
        canvas.drawText(channel, screenRect.centerX() - chW / 2f, screenRect.top + 132f, textPaint)

        // Speaker / operator ID line. RX mandates the exact "ID : XXXX" format.
        val idLine = when {
            light == Light.RX && speakerId != null -> "ID : $speakerId"
            speakerId != null -> "ID : $speakerId"
            else -> "ID : $unitId"
        }
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textSize = 26f
        textPaint.color = when {
            light == Light.RX -> GREEN
            light == Light.INHIBIT -> RED
            else -> withAlpha(textColor, 0xDD)
        }
        val idW = textPaint.measureText(idLine)
        canvas.drawText(idLine, screenRect.centerX() - idW / 2f, screenRect.top + 178f, textPaint)
    }

    private fun drawSoftkeys(canvas: Canvas) {
        val labels = SOFTKEYS
        val y = screenRect.bottom - 46f
        val gap = 10f
        val totalW = screenRect.width() - 32f
        val keyW = (totalW - gap * (labels.size - 1)) / labels.size
        val textColor = backlightColor

        labels.forEachIndexed { i, label ->
            val left = screenRect.left + 16f + i * (keyW + gap)
            val rect = RectF(left, y, left + keyW, y + 34f)
            val selected = i == softkeyHighlight
            paint.color = if (selected) withAlpha(textColor, 0x40) else withAlpha(Color.WHITE, 0x10)
            canvas.drawRoundRect(rect, 6f, 6f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = withAlpha(textColor, if (selected) 0xFF else 0x66)
            canvas.drawRoundRect(rect, 6f, 6f, paint)
            paint.style = Paint.Style.FILL

            textPaint.typeface = Typeface.MONOSPACE
            textPaint.textSize = 18f
            textPaint.color = textColor
            val w = textPaint.measureText(label)
            canvas.drawText(label, rect.centerX() - w / 2f, rect.centerY() + 6f, textPaint)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    companion object {
        const val DESIGN_W = 480f
        const val DESIGN_H = 320f
        private const val SCREEN_INSET = 16f

        private val SOFTKEYS = arrayOf("Chan", "Scan", "Cnts")

        val AMBER = Color.parseColor("#FFB000")
        val GREEN = Color.parseColor("#39FF6A")
        val YELLOW = Color.parseColor("#FFD400")
        val RED = Color.parseColor("#FF3B30")

        private val BEVEL_TOP = Color.parseColor("#3A3F44")
        private val BEVEL_BOTTOM = Color.parseColor("#1B1E21")
    }
}
