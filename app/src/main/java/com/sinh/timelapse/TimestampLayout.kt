package com.sinh.timelapse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** All geometry is in 720x1280 output pixels, independent of dp/sp or accessibility font scale. */
object TimestampLayout {
    const val MARGIN = 28f
    const val PADDING = 16f
    data class Layout(val box: RectF, val lines: List<String>, val baselines: List<Float>, val textPaint: Paint)
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    fun lines(epochMillis: Long, zone: String, appearance: TimestampAppearance): List<String> = buildList {
        if (appearance.date) add(dateFormat.withZone(ZoneId.of(zone)).format(Instant.ofEpochMilli(epochMillis)))
        add(CaptureTiming.timestamp(epochMillis, zone))
    }

    fun layout(epochMillis: Long, zone: String, appearance: TimestampAppearance): Layout {
        val lines = lines(epochMillis, zone, appearance)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = appearance.size.toFloat()
            val family = when (appearance.family) {
                StampFamily.MONOSPACE -> Typeface.MONOSPACE
                StampFamily.SANS_SERIF -> Typeface.SANS_SERIF
                StampFamily.SERIF -> Typeface.SERIF
            }
            typeface = Typeface.create(family, if (appearance.bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val metrics = paint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val width = lines.maxOf(paint::measureText) + 2 * PADDING
        val height = lineHeight * lines.size + 2 * PADDING
        check(width <= VIDEO_WIDTH - 2 * MARGIN && height <= VIDEO_HEIGHT - 2 * MARGIN) { "Timestamp exceeds safe margins" }
        val left = when (appearance.position) {
            StampPosition.TOP_LEFT, StampPosition.BOTTOM_LEFT -> MARGIN
            StampPosition.TOP_CENTER, StampPosition.BOTTOM_CENTER -> (VIDEO_WIDTH - width) / 2
            StampPosition.TOP_RIGHT, StampPosition.BOTTOM_RIGHT -> VIDEO_WIDTH - MARGIN - width
        }
        val top = when (appearance.position) {
            StampPosition.TOP_LEFT, StampPosition.TOP_CENTER, StampPosition.TOP_RIGHT -> MARGIN
            else -> VIDEO_HEIGHT - MARGIN - height
        }
        return Layout(RectF(left, top, left + width, top + height), lines,
            lines.indices.map { top + PADDING - metrics.ascent + it * lineHeight }, paint)
    }

    fun draw(canvas: Canvas, epochMillis: Long, zone: String, appearance: TimestampAppearance): Layout {
        val layout = layout(epochMillis, zone, appearance)
        canvas.drawRoundRect(layout.box, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 0, 0, 0) })
        layout.lines.forEachIndexed { index, text ->
            canvas.drawText(text, layout.box.left + PADDING, layout.baselines[index], layout.textPaint)
        }
        return layout
    }
}

internal class TimestampPreview(context: Context) : View(context) {
    var appearance = TimestampAppearance()
    var zone = ZoneId.systemDefault().id
    var epochMillis = System.currentTimeMillis()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        canvas.save()
        canvas.scale(width.toFloat() / VIDEO_WIDTH, height.toFloat() / VIDEO_HEIGHT)
        TimestampLayout.draw(canvas, epochMillis, zone, appearance)
        canvas.restore()
    }
}
