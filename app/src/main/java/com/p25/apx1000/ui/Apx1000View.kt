package com.p25.apx1000.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * APX1000 radio skin, styled after the reference LCD:
 * light grey screen, bold black text, green signal/battery, orange status dot,
 * dark soft-key bar (Chan / Scan / Cnts) in white.
 *
 * Display rules:
 *  - Zone is always shown ("ZONE 1").
 *  - The channel/talkgroup is shown large (default "P25").
 *  - Battery icon with a live percentage, and a live signal meter.
 *  - "ID : XXXX" is shown ONLY while a device is transmitting (RX from a peer,
 *    or our own TX), never as a permanent own-ID.
 *
 * Drawn against a fixed 480x280 design surface and scaled proportionally.
 */
class Apx1000View @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Light { IDLE, RX, TX, INHIBIT }

    var zone: String = "ZONE 1"
        set(v) { field = v; invalidate() }

    var channel: String = "P25"
        set(v) { field = v; invalidate() }

    var unitId: String = "----"
        set(v) { field = v; invalidate() }

    var speakerId: String? = null
        set(v) { field = v; invalidate() }

    var light: Light = Light.IDLE
        set(v) { field = v; invalidate() }

    var signalLevel: Int = 0
        set(v) { field = v.coerceIn(0, 4); invalidate() }

    var batteryPct: Int = -1
        set(v) { field = v.coerceIn(-1, 100); invalidate() }

    var softkeyHighlight: Int = 0
        set(v) { field = v; invalidate() }

    var statusText: String = "READY"
        set(v) { field = v; invalidate() }

    /** Small build/version marker drawn in the corner for on-device confirmation. */
    var buildTag: String = ""
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = BOLD
    }
    private val screenRect = RectF()
    private val path = Path()

    private val backlightColor: Int
        get() = when (light) {
            Light.RX -> GREEN
            Light.TX -> YELLOW
            Light.INHIBIT -> RED
            Light.IDLE -> BEZEL_EDGE
        }

    /** The unit currently transmitting, if any. Only valid while TX/RX is active. */
    private val activeSpeaker: String?
        get() = when (light) {
            Light.TX -> unitId
            Light.RX -> speakerId
            else -> null
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
        drawBezel(canvas)
        drawScreen(canvas)
        canvas.restore()
    }

    private fun drawBezel(canvas: Canvas) {
        val r = RectF(0f, 0f, DESIGN_W, DESIGN_H)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, DESIGN_H, BEZEL_TOP, BEZEL_BOTTOM, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, 16f, 16f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.parseColor("#0A0A0A")
        canvas.drawRoundRect(r, 16f, 16f, paint)
    }

    private fun drawScreen(canvas: Canvas) {
        screenRect.set(SCREEN_INSET, SCREEN_INSET, DESIGN_W - SCREEN_INSET, DESIGN_H - SCREEN_INSET)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 10f
        paint.color = withAlpha(backlightColor, 0x40)
        canvas.drawRoundRect(screenRect, 8f, 8f, paint)
        paint.strokeWidth = 4f
        paint.color = backlightColor
        canvas.drawRoundRect(screenRect, 8f, 8f, paint)

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            screenRect.left, screenRect.top, screenRect.left, screenRect.bottom,
            LCD_TOP, LCD_BOTTOM, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(screenRect, 8f, 8f, paint)
        paint.shader = null

        drawStatusBar(canvas)
        drawMainArea(canvas)
        drawSoftkeys(canvas)
    }

    private fun drawStatusBar(canvas: Canvas) {
        val left = screenRect.left + 14f
        val top = screenRect.top + 10f

        // Left tick.
        paint.style = Paint.Style.FILL
        paint.color = GREEN
        canvas.drawRect(left, top + 8f, left + 3f, top + 38f, paint)

        // Signal bars (live).
        val barW = 8f
        val gap = 5f
        val baseY = top + 38f
        val heights = floatArrayOf(14f, 22f, 30f, 38f)
        for (i in 0 until 4) {
            val bx = left + 9f + i * (barW + gap)
            paint.style = Paint.Style.FILL
            paint.color = if (i < signalLevel) GREEN else withAlpha(GREEN, 0x33)
            canvas.drawRect(bx, baseY - heights[i], bx + barW, baseY, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = withAlpha(Color.BLACK, 0x55)
            canvas.drawRect(bx, baseY - heights[i], bx + barW, baseY, paint)
        }

        // Transmit/grant triangle.
        val tx = left + 9f + 4f * (barW + gap) + 6f
        path.reset()
        path.moveTo(tx, baseY - 34f)
        path.lineTo(tx + 20f, baseY - 17f)
        path.lineTo(tx, baseY)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = if (activeSpeaker != null) backlightColor else TEXT
        canvas.drawPath(path, paint)

        // Center cluster: zone + orange dot + channel icon.
        textPaint.typeface = BOLD
        textPaint.textSize = 32f
        textPaint.color = TEXT
        val zoneText = "Z$zoneValue"
        val zw = textPaint.measureText(zoneText)
        val iconW = 40f
        var gx = screenRect.centerX() - (zw + 14f + iconW) / 2f
        canvas.drawText(zoneText, gx, top + 36f, textPaint)
        gx += zw + 6f
        paint.style = Paint.Style.FILL
        paint.color = ORANGE
        canvas.drawCircle(gx + 4f, top + 28f, 5.5f, paint)
        gx += 14f
        drawChannelIcon(canvas, gx, top + 10f, top + 40f)

        // Battery icon + live percentage.
        val batW = 40f
        val batH = 24f
        val batRight = screenRect.right - 14f
        val batTop = top + 10f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = TEXT
        canvas.drawRect(batRight - batW, batTop, batRight, batTop + batH, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(batRight, batTop + batH / 2f - 4f, batRight + 5f, batTop + batH / 2f + 4f, paint)
        if (batteryPct >= 0) {
            val fillW = (batW - 8f) * batteryPct / 100f
            paint.color = if (batteryPct <= 15) RED else GREEN
            canvas.drawRect(batRight - batW + 4f, batTop + 4f, batRight - batW + 4f + fillW, batTop + batH - 4f, paint)
        }
        textPaint.textSize = 22f
        textPaint.color = TEXT
        val pct = if (batteryPct < 0) "--%" else "$batteryPct%"
        canvas.drawText(pct, batRight - batW - 12f - textPaint.measureText(pct), batTop + batH - 3f, textPaint)
    }

    private fun drawChannelIcon(canvas: Canvas, x: Float, top: Float, bottom: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = TEXT
        canvas.drawLine(x, top, x, bottom, paint)
        canvas.drawLine(x + 30f, top, x + 30f, bottom, paint)
        val midY = (top + bottom) / 2f
        canvas.drawLine(x + 3f, midY, x + 27f, midY, paint)
        path.reset()
        path.moveTo(x + 3f, midY); path.lineTo(x + 10f, midY - 6f)
        path.moveTo(x + 3f, midY); path.lineTo(x + 10f, midY + 6f)
        path.moveTo(x + 27f, midY); path.lineTo(x + 20f, midY - 6f)
        path.moveTo(x + 27f, midY); path.lineTo(x + 20f, midY + 6f)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawMainArea(canvas: Canvas) {
        // Connection / network status, always visible.
        textPaint.typeface = BOLD
        textPaint.textSize = 22f
        while (textPaint.measureText(statusText) > screenRect.width() - 20f && textPaint.textSize > 11f) {
            textPaint.textSize -= 1f
        }
        textPaint.color = when {
            statusText.contains("ONLINE", ignoreCase = true) -> GREEN_DARK
            statusText.startsWith("OFF") || statusText.contains("ERR", ignoreCase = true) -> RED
            statusText.contains("BUSY", ignoreCase = true) -> ORANGE
            else -> TEXT
        }
        canvas.drawText(statusText, screenRect.centerX() - textPaint.measureText(statusText) / 2f, screenRect.top + 74f, textPaint)

        // Zone label (always visible).
        textPaint.typeface = BOLD
        textPaint.textSize = 30f
        textPaint.color = TEXT
        val z = "ZONE $zoneValue"
        canvas.drawText(z, screenRect.centerX() - textPaint.measureText(z) / 2f, screenRect.top + 100f, textPaint)

        // Channel / talkgroup (large).
        textPaint.textSize = 64f
        textPaint.color = TEXT
        val ch = channel
        var chW = textPaint.measureText(ch)
        while (chW > screenRect.width() - 24f && textPaint.textSize > 28f) {
            textPaint.textSize -= 2f
            chW = textPaint.measureText(ch)
        }
        canvas.drawText(ch, screenRect.centerX() - chW / 2f, screenRect.top + 156f, textPaint)

        // "ID : XXXX" only while a device is transmitting.
        val speaker = activeSpeaker
        if (speaker != null) {
            textPaint.textSize = 30f
            textPaint.color = if (light == Light.INHIBIT) RED else GREEN_DARK
            val idLine = "ID : $speaker"
            canvas.drawText(idLine, screenRect.centerX() - textPaint.measureText(idLine) / 2f, screenRect.top + 194f, textPaint)
        }

        if (buildTag.isNotEmpty()) {
            textPaint.textSize = 13f
            textPaint.color = withAlpha(TEXT, 0x88)
            canvas.drawText(buildTag, screenRect.left + 8f, screenRect.bottom - 54f, textPaint)
        }
    }

    private fun drawSoftkeys(canvas: Canvas) {
        val barTop = screenRect.bottom - 46f
        val bar = RectF(screenRect.left, barTop, screenRect.right, screenRect.bottom)

        paint.style = Paint.Style.FILL
        paint.color = SOFT_BG
        canvas.drawRoundRect(bar, 8f, 8f, paint)
        canvas.drawRect(screenRect.left, barTop, screenRect.right, barTop + 8f, paint)

        paint.color = SOFT_DIV
        paint.strokeWidth = 2f
        canvas.drawLine(screenRect.left, barTop, screenRect.right, barTop, paint)

        val cells = SOFTKEYS.size
        val cellW = bar.width() / cells
        textPaint.typeface = BOLD
        textPaint.textSize = 28f
        for (i in 0 until cells) {
            val cx0 = screenRect.left + i * cellW
            if (i == softkeyHighlight) {
                paint.style = Paint.Style.FILL
                paint.color = SOFT_HILITE
                canvas.drawRect(cx0 + 2f, barTop + 2f, cx0 + cellW - 2f, screenRect.bottom - 2f, paint)
            }
            if (i > 0) {
                paint.style = Paint.Style.STROKE
                paint.color = SOFT_DIV
                paint.strokeWidth = 2f
                canvas.drawLine(cx0, barTop, cx0, screenRect.bottom, paint)
            }
            textPaint.color = SOFT_TEXT
            val label = SOFTKEYS[i]
            val tw = textPaint.measureText(label)
            canvas.drawText(label, cx0 + cellW / 2f - tw / 2f, bar.centerY() + 10f, textPaint)
        }
    }

    /** Accepts "ZONE 1", "1" or "zone1" and renders "ZONE 1". */
    private val zoneValue: String
        get() {
            val digits = zone.filter { it.isDigit() }
            return digits.ifEmpty { zone.uppercase().removePrefix("ZONE").trim().ifEmpty { "1" } }
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    companion object {
        const val DESIGN_W = 480f
        const val DESIGN_H = 280f
        private const val SCREEN_INSET = 16f

        private val SOFTKEYS = arrayOf("Chan", "Scan", "Cnts")
        private val BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

        private val LCD_TOP = Color.parseColor("#EFEFEF")
        private val LCD_BOTTOM = Color.parseColor("#D8D8D8")
        private val TEXT = Color.parseColor("#111111")

        private val GREEN = Color.parseColor("#3E9E43")
        private val GREEN_DARK = Color.parseColor("#2E7D33")
        private val YELLOW = Color.parseColor("#E0A000")
        private val RED = Color.parseColor("#D0342C")
        private val ORANGE = Color.parseColor("#E0A030")

        private val SOFT_BG = Color.parseColor("#5B5B5B")
        private val SOFT_HILITE = Color.parseColor("#737373")
        private val SOFT_DIV = Color.parseColor("#2E2E2E")
        private val SOFT_TEXT = Color.parseColor("#F0F0F0")

        private val BEZEL_TOP = Color.parseColor("#3A3F44")
        private val BEZEL_BOTTOM = Color.parseColor("#15181A")
        private val BEZEL_EDGE = Color.parseColor("#7A7A7A")
    }
}
