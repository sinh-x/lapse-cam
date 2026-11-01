package com.sinh.timelapse

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Full-screen browser body. ListView recycles summaries; only one detail/action panel is mounted. */
class SessionsView(
    context: Context,
    private val open: (SessionReview) -> Unit,
    private val retry: (SessionReview) -> Unit,
    private val delete: (SessionReview) -> Unit,
    private val linkedWork: (String) -> Unit = {},
    private val linkSummary: (String) -> String = { "Linked timeboxes: 0" },
    private val checkOutput: (String, String) -> Unit = { _, _ -> },
) : LinearLayout(context) {
    constructor(context: Context) : this(context, {}, {}, {})
    val diagnostics = text("")
    private val heading = text("Sessions")
    private val attentionButton = button("Needs attention (0)") { remember(); attention = true; selected = null; rebuild() }
    private val workStatus = text("")
    private val body = FrameLayout(context)
    private val list = ListView(context).apply { dividerHeight = 1; cacheColorHint = Color.TRANSPARENT }
    private var values = emptyList<SessionReview>()
    private val reviewIndex = mutableMapOf<String, SessionReview>()
    private var attentionCount = 0
    private var projection: SessionHistory? = null
    private var projectionKey = ""
    private var loaded = true
    private var evidenceMissing = false
    private var loadMessage = "Loading saved sessions…"
    private var attention = false
    private var selected: String? = null
    private var detailToken = ""
    private val expanded = linkedSetOf<String>()
    private val positions = mutableMapOf<Boolean, Pair<Int, Int>>()
    private var restoreListPosition = false
    private var dayKey = ""
    private var detailText: TextView? = null
    private var openButton: Button? = null
    private var retryButton: Button? = null
    private var deleteButton: Button? = null
    private var recheckButton: Button? = null
    private var linksButton: Button? = null
    private data class Item(val key: String, val label: String, val action: (() -> Unit)?)
    private var items = emptyList<Item>()
    private val rowIds = mutableMapOf<String, Long>()
    private var nextRowId = 0L
    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = rowIds.getOrPut(items[position].key) { nextRowId++ }
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? TextView) ?: text("").apply {
                minHeight = dp(56); setPadding(dp(12), dp(12), dp(12), dp(12)); isFocusable = true
            }
            val item = items[position]
            if (row.text.toString() != item.label) row.text = item.label
            row.contentDescription = item.label
            row.setOnClickListener { item.action?.invoke() }
            row.isEnabled = item.action != null
            return row
        }
    }
    init {
        orientation = VERTICAL; setBackgroundColor(Color.rgb(16, 25, 22))
        val header = LinearLayout(context)
        header.addView(button("Back") { if (!back()) onClose?.invoke() })
        header.addView(heading, LayoutParams(0, -2, 1f)); addView(header)
        addView(attentionButton); addView(workStatus)
        addView(ScrollView(context).apply { addView(diagnostics); visibility = GONE }, LayoutParams(-1, dp(80)))
        addView(body, LayoutParams(-1, 0, 1f)); list.adapter = adapter; rebuild()
    }
    var onClose: (() -> Unit)? = null
    fun loading(ready: Boolean, message: String = "Loading saved sessions…") {
        if (loaded != ready || loadMessage != message) { loaded = ready; loadMessage = message; rebuild() }
    }
    fun refreshDates() {
        val key = "${LocalDate.now()}:${ZoneId.systemDefault().id}"
        if (key != dayKey) { dayKey = key; projection = null; if (selected == null) rebuildList() else renderDetail() }
    }
    fun evidenceUnavailable(value: Boolean) {
        evidenceMissing = value
        attentionButton.text = "Needs attention ($attentionCount${if (evidenceMissing) " known; output review unavailable" else ""}) · all dates"
    }
    fun render(values: List<SessionReview>) {
        if (this.values === values) { refreshDates(); if (selected != null) renderDetail(); return }
        val sameIds = this.values.size == values.size && values.indices.all { this.values[it].id == values[it].id }
        if (!sameIds) { reviewIndex.clear(); attentionCount = 0; projection = null }
        values.forEach { value ->
            val old = reviewIndex[value.id]
            if (old == null || old.startedAt != value.startedAt || old.needsAttention != value.needsAttention) projection = null
            if (old?.needsAttention == true) attentionCount--
            if (value.needsAttention) attentionCount++
            if (old !== value) reviewIndex[value.id] = value
        }
        this.values = values
        evidenceUnavailable(evidenceMissing)
        val active = values.mapNotNull { it.processing.active }.distinct()
        workStatus.text = active.joinToString("\n") { "${it.stage.label} · ${it.sessionId}${it.percent?.let { p -> " · $p%" } ?: " · in progress"}" }
            .ifEmpty { if (!loaded || values.any { !it.processing.hydrated }) "Processing status loading / unavailable" else
                if (values.any { it.processing.wait == ProcessingWait.RETRY_DELAY }) "Waiting for automatic retry" else
                if (values.any { it.processing.wait != ProcessingWait.NONE }) "Processing queued / waiting" else "No active media processing" }
        refreshDates()
        if (selected == null) rebuildList() else renderDetail()
    }
    private fun remember() { if (selected == null) positions[attention] = list.firstVisiblePosition to (list.getChildAt(0)?.top ?: 0) }
    fun back(): Boolean {
        if (selected != null) { selected = null; detailToken = ""; restoreListPosition = true; rebuild(); return true }
        if (attention) { remember(); attention = false; restoreListPosition = true; rebuild(); return true }
        return false
    }
    fun saveState(): Bundle {
        remember()
        return Bundle().apply {
            putBoolean("attention", attention); putString("selected", selected); putString("entry", detailToken)
            putStringArrayList("expanded", ArrayList(expanded))
            positions.forEach { (key, value) -> putInt("position$key", value.first); putInt("offset$key", value.second) }
            selected?.let { putInt("detailScroll", (body.getChildAt(0) as? ScrollView)?.scrollY ?: 0) }
        }
    }
    fun restoreState(state: Bundle) {
        attention = state.getBoolean("attention"); selected = state.getString("selected"); detailToken = state.getString("entry").orEmpty()
        expanded.clear(); expanded.addAll(state.getStringArrayList("expanded").orEmpty())
        listOf(false, true).forEach { positions[it] = state.getInt("position$it") to state.getInt("offset$it") }
        restoreListPosition = true; rebuild()
        (body.getChildAt(0) as? ScrollView)?.post { (body.getChildAt(0) as? ScrollView)?.scrollTo(0, state.getInt("detailScroll")) }
        selected?.let { checkOutput(it, detailToken.ifEmpty { UUID.randomUUID().toString().also { token -> detailToken = token } }) }
    }
    fun showAttention() { remember(); attention = true; selected = null; rebuild() }
    fun showSession(id: String) {
        remember(); selected = id; detailToken = UUID.randomUUID().toString(); rebuild(); checkOutput(id, detailToken)
    }
    private fun rebuild() {
        body.removeAllViews(); detailText = null
        if (selected == null) { heading.text = if (attention) "Needs attention" else "Sessions"; body.addView(list); rebuildList() }
        else {
            heading.text = "Session details"
            val panel = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
            detailText = text(""); panel.addView(detailText)
            fun current(action: (SessionReview) -> Unit) { values.firstOrNull { it.id == selected }?.let(action) }
            openButton = button("Open video") { current(open) }; panel.addView(openButton)
            recheckButton = button("Recheck video") { selected?.let { checkOutput(it, UUID.randomUUID().toString()) } }; panel.addView(recheckButton)
            retryButton = button("Retry") { current(retry) }; panel.addView(retryButton)
            deleteButton = button("Delete source photos…") { current(delete) }; panel.addView(deleteButton)
            linksButton = button("Linked timeboxes") { selected?.let(linkedWork) }; panel.addView(linksButton)
            body.addView(ScrollView(context).apply { addView(panel) }); renderDetail()
        }
    }
    private fun rebuildList() {
        if (selected != null) return
        val result = mutableListOf<Item>()
        if (!loaded) result.add(Item("loading", loadMessage, null))
        else {
            val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
            val key = "$today:$zone:$attention"
            val history = if (projection == null || projectionKey != key) SessionHistory.project(values, today, zone, attention)
                .also { projection = it; projectionKey = key } else checkNotNull(projection)
            fun sessions(rows: List<SessionReview>) { rows.forEach { saved ->
                val value = reviewIndex[saved.id] ?: saved
                val issues = value.processing.issues.joinToString("; ") { "${it.stage.label}: ${it.reason}" }
                val output = value.currentObservation?.takeIf { it.availability.actionable }?.availability?.label
                result.add(Item("session:${value.id}", "${date(value.startedAt)} · ${value.id}\n${value.status}" +
                    listOfNotNull(issues.takeIf { it.isNotEmpty() }, output).joinToString("\n", prefix = if (issues.isNotEmpty() || output != null) "\n" else "")) { showSession(value.id) })
            } }
            if (!attention) {
                result.add(Item("today", "Today (${history.today.items.size})${if (history.today.items.isEmpty()) " · No sessions today" else ""}", null))
                sessions(history.today.items)
            } else if (history.today.items.isNotEmpty()) { result.add(Item("today", "Today (${history.today.items.size})", null)); sessions(history.today.items) }
            history.dates.forEach { day ->
                val key = day.date.toString(); val open = attention || key in expanded
                result.add(Item("date:$key", "$key (${day.items.size} sessions) · ${if (open) "expanded" else "collapsed"}") {
                    remember(); if (!expanded.remove(key)) expanded.add(key); rebuildList()
                })
                if (open) sessions(day.items)
            }
            if (values.isEmpty()) result.add(Item("empty", "No saved sessions.", null))
            else if (attention && result.isEmpty()) result.add(Item("resolved", "No sessions need attention. Viewing does not clear diagnostics.", null))
        }
        if (items.map { it.key to it.label } != result.map { it.key to it.label }) { items = result; adapter.notifyDataSetChanged() }
        if (restoreListPosition) {
            restoreListPosition = false; positions[attention]?.let { (position, offset) -> list.setSelectionFromTop(position, offset) }
        }
    }
    private fun renderDetail() {
        val value = reviewIndex[selected]
        if (value == null) {
            detailText?.text = if (!loaded) loadMessage else "This session is unavailable. Back returns to the browser."
            listOf(openButton, retryButton, deleteButton, recheckButton, linksButton).forEach { it?.isEnabled = false }; return
        }
        val evidence = value.currentObservation
        val message = "${date(value.startedAt)}\nSession ${value.id}\n${value.mode} · ${value.cap?.let { "cap ${it}s" } ?: "uncapped"} · ${value.fps} fps\n" +
            "Active ${value.activeMillis?.let { "${it / 1000}s" } ?: "unavailable (legacy)"}${if (value.unknownTail) " · last saved; tail unknown" else ""}\n" +
            "Sampling stage ${value.stage}\nSelected ${value.selected} / total ${value.total} · retained ${value.retained} · ${"%.1f".format(java.util.Locale.ROOT, value.bytes / 1048576.0)} MiB\n" +
            "${value.status}\nExport attempts ${value.attempts}/3 · cleanup attempts ${value.cleanupAttempts}/3\n" +
            value.processing.issues.joinToString("\n") { "${it.stage.label}: ${it.reason}" } +
            (if (value.error.isNotEmpty()) "\n${value.error}" else "") +
            "\n${evidence?.let { "${it.availability.label} · checked ${date(it.checkedAt)}\n${it.diagnostic}\n${it.lastAttemptDiagnostic}" } ?: if (value.output == null) "No published video" else "Video availability not yet checked"}" +
            "\nA lightweight read is not cleanup verification. Removed photos cannot be restored."
        if (detailText?.text.toString() != message) detailText?.text = message
        openButton?.isEnabled = value.output != null; retryButton?.isEnabled = value.canRetry
        deleteButton?.isEnabled = value.canDelete; recheckButton?.isEnabled = value.output != null
        linksButton?.apply { text = linkSummary(value.id); isEnabled = true }
    }
    private fun date(value: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String) = TextView(context).apply { text = value; textSize = 15f; setTextColor(Color.WHITE) }
    private fun button(value: String, action: () -> Unit) = Button(context).apply { text = value; minHeight = dp(48); setOnClickListener { action() } }
}
