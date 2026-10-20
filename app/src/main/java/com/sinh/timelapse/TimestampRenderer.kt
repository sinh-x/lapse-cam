package com.sinh.timelapse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

object TimestampRenderer {
    /** Normalize EXIF, crop, reflect the front scene once, then stamp unmirrored clock text. */
    fun render(photo: File, epochMillis: Long, zoneId: String, lens: SessionLens = SessionLens.REAR,
        appearance: TimestampAppearance = TimestampAppearance()): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Camera returned an unreadable photo" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > VIDEO_HEIGHT * 2) sample *= 2
        val decoded = checkNotNull(BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply {
            inSampleSize = sample
        })) { "Could not decode photo" }
        var oriented: Bitmap? = null
        try {
            val exif = ExifInterface(photo)
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            val image = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            oriented = image
            return Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888).also { result ->
                val canvas = Canvas(result)
                val scale = max(VIDEO_WIDTH.toFloat() / image.width, VIDEO_HEIGHT.toFloat() / image.height)
                val cropWidth = (VIDEO_WIDTH / scale).roundToInt().coerceAtMost(image.width)
                val cropHeight = (VIDEO_HEIGHT / scale).roundToInt().coerceAtMost(image.height)
                val left = (image.width - cropWidth) / 2
                val top = (image.height - cropHeight) / 2
                canvas.save()
                if (lens == SessionLens.FRONT) canvas.scale(-1f, 1f, VIDEO_WIDTH / 2f, VIDEO_HEIGHT / 2f)
                canvas.drawBitmap(image, Rect(left, top, left + cropWidth, top + cropHeight),
                    Rect(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT), Paint(Paint.FILTER_BITMAP_FLAG))
                canvas.restore()
                TimestampLayout.draw(canvas, epochMillis, zoneId, appearance)
            }
        } finally {
            if (oriented !== decoded) oriented?.recycle()
            decoded.recycle()
        }
    }

    internal fun drawClock(canvas: Canvas, text: String) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val margin = 28f
        val padding = 16f
        val width = textPaint.measureText(text)
        val metrics = textPaint.fontMetrics
        val bottom = canvas.height - margin
        val box = RectF(margin, bottom - (metrics.descent - metrics.ascent) - padding * 2,
            margin + width + padding * 2, bottom)
        canvas.drawRoundRect(box, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 0, 0)
        })
        canvas.drawText(text, box.left + padding, box.top + padding - metrics.ascent, textPaint)
    }
}
