package com.sinh.timelapse

import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Stable row views preserve focus/scroll during status refresh. No disk/bitmap work in this view. */
class SessionsView(
    context: Context,
    private val open: (SessionReview) -> Unit,
    private val retry: (SessionReview) -> Unit,
    private val delete: (SessionReview) -> Unit,
) : ScrollView(context) {
    constructor(context: Context) : this(context, {}, {}, {})

    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        setBackgroundColor(Color.rgb(16, 25, 22))
    }
    private val empty = text("No saved sessions.")
    private val rows = linkedMapOf<String, Row>()
    private inner class Row(var review: SessionReview) {
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val details = text("")
        val openButton = button("Open video") { open(review) }
        val retryButton = button("Retry") { retry(review) }
        val deleteButton = button("Delete source photos…") { delete(review) }
        init {
            panel.addView(details)
            panel.addView(openButton)
            panel.addView(retryButton)
            panel.addView(deleteButton)
        }
        fun render(value: SessionReview) {
            review = value
            val start = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(value.startedAt))
            val seconds = (value.activeMillis ?: 0) / 1000
            val active = if (value.activeMillis == null) "unavailable (legacy)" else
                String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            val message = String.format(Locale.ROOT,
                "%s\nSession %s\n%s · %s · %d fps\nActive %s%s · stage %d\nSelected %d / total %d · retained %d · %.1f MiB\n%s%s\nExport attempts %d/3 · cleanup attempts %d/3%s",
                start, value.id, value.mode, value.cap?.let { "cap ${it}s" } ?: "uncapped", value.fps,
                active, if (value.unknownTail) " (last saved; tail unknown)" else "",
                value.stage, value.selected, value.total, value.retained, value.bytes / 1048576.0,
                value.status, if (value.progress > 0) " · ${value.progress}%" else "", value.attempts, value.cleanupAttempts,
                if (value.error.isEmpty()) "" else "\n${value.error}")
            if (details.text.toString() != message) details.text = message
            openButton.isEnabled = value.output != null
            retryButton.isEnabled = value.canRetry
            deleteButton.isEnabled = value.canDelete
            openButton.contentDescription = "Open video for session ${value.id}"
            retryButton.contentDescription = "Retry session ${value.id}"
            deleteButton.contentDescription = "Delete source photos for session ${value.id}"
        }
    }
    init { addView(list); list.addView(empty) }
    fun render(values: List<SessionReview>) {
        empty.visibility = if (values.isEmpty()) VISIBLE else GONE
        val identities = values.map { it.id }.toSet()
        rows.keys.filter { it !in identities }.toList().forEach { id -> list.removeView(rows.remove(id)!!.panel) }
        values.forEach { review ->
            val row = rows.getOrPut(review.id) { Row(review).also { list.addView(it.panel) } }
            row.render(review)
        }
    }
    private fun text(value: String) = TextView(context).apply {
        text = value; setTextColor(Color.WHITE); textSize = 15f
        // Deliberately not a live region: the 1Hz active countdown must not announce every tick.
    }
    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value; setOnClickListener { action() }
    }
}
