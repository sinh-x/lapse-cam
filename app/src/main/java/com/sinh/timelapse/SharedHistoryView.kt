package com.sinh.timelapse

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.text.InputType
import android.widget.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

/** Text-only offline replica UI. No remote execution buttons; playback/download are explicit actions. */
internal class SharedHistoryView(private val activity: Activity, private val owner: SyncCoordinator,
    private val download: (JSONObject, Boolean) -> Unit) : LinearLayout(activity) {
    private val body = LinearLayout(activity).apply { orientation = VERTICAL }
    private val status = TextView(activity)
    private val rows = LinearLayout(activity).apply { orientation = VERTICAL }
    private val device = edit("Source UUID (empty = both)")
    private val since = edit("From date UTC: YYYY-MM-DD")
    private val until = edit("Through date UTC: YYYY-MM-DD")
    private val category = spinner(listOf("All categories") + WorkType.entries.map { it.label })
    private val stateFilter = spinner(listOf("All states", "PLANNED", "RUNNING", "ENDED", "ARCHIVED", "DELETED"))
    private var latest = SyncCoordinator.State()
    private var offset = 0
    private var filter: (JSONObject, Boolean) -> Boolean = { _, _ -> true }
    init {
        orientation = VERTICAL
        body.setPadding(16, 8, 16, 8)
        addView(ScrollView(activity).apply { addView(body) }, LayoutParams(-1, -1))
        body.addView(text("Private archive — separate from local timers. Running summaries are stale until source sync; capture links are not MP4 seek offsets."))
        body.addView(status)
        body.addView(button("Enrollment / settings") { settings() })
        body.addView(button("Sync now / refresh") { consent { owner.sync(true, it) } })
        body.addView(button("Queue progress / preserved rejected edits") { queue() })
        body.addView(device); body.addView(since); body.addView(until); body.addView(category); body.addView(stateFilter)
        body.addView(button("Apply filters") {
            try {
                val d = device.text.toString().trim()
                val low = date(since.text.toString())
                val high = date(until.text.toString())?.let { Math.addExact(it, 86_400_000 - 1) }
                require(low == null || high == null || low <= high)
                val type = WorkType.entries.getOrNull(category.selectedItemPosition - 1)?.key
                val status = stateFilter.selectedItem.toString().takeUnless { stateFilter.selectedItemPosition == 0 }
                filter = { value, video ->
                    val source = value.optJSONObject("source")
                    val time = if (video) value.getLong("syncedAt") else source!!.getLong("createdAt")
                    val sourceState = if (source?.getBoolean("deleted") == true) "DELETED" else if (source?.getBoolean("archived") == true) "ARCHIVED" else source?.getString("state")
                    (d.isEmpty() || value.getString("device") == d) && (low == null || time >= low) && (high == null || time <= high) &&
                        (video || ((type == null || value.getJSONObject("fields").getJSONObject("type").getString("value") == type) &&
                            (status == null || status == sourceState)))
                }
                offset = 0; renderRows()
            } catch (_: Exception) { toast("Use valid UTC dates and an ordered range") }
        })
        body.addView(rows)
        body.addView(button("Previous page") { offset = (offset - 50).coerceAtLeast(0); renderRows() })
        body.addView(button("Next page") { offset += 50; renderRows() })
    }
    fun render(value: SyncCoordinator.State) {
        latest = value
        status.text = "${if (value.enabled) "Enabled" else "Disabled"} · ${if (value.busy) "transferring" else "idle"}\nPending: ${value.pending} · conflicts: ${value.conflicts}\nLast successful attempt: ${if (value.lastSuccess == 0L) "never" else java.time.Instant.ofEpochMilli(value.lastSuccess)}\n" +
            (if (value.lowStorage) "WARNING: server filesystem below 10% free. No automatic deletion or archive cap.\n" else "") + value.message
        renderRows()
    }
    private fun renderRows() {
        rows.removeAllViews()
        val records = latest.history.getJSONArray("records").objects().filter { filter(it, false) }
        val videos = latest.history.getJSONArray("videos").objects().filter { filter(it, true) }
        val all = records.map { it to false } + videos.map { it to true }
        if (offset >= all.size) offset = ((all.size - 1).coerceAtLeast(0) / 50) * 50
        rows.addView(text("${all.size} cached results · page ${offset / 50 + 1}. Refresh after reconnect; browsing downloads no videos."))
        all.drop(offset).take(50).forEach { (item, video) ->
            val title = if (video) "Video ${item.getString("id")}${if (item.getBoolean("missing")) " · source missing" else ""}"
                else item.getJSONObject("fields").getJSONObject("title").getString("value")
            rows.addView(button("$title\nSource ${item.getString("device")}") { if (video) this.video(item) else record(item) })
        }
    }
    private fun queue() {
        val panel = LinearLayout(activity).apply { orientation = VERTICAL }
        latest.uploads.strings().forEach { id ->
            val v = latest.uploads.getJSONObject(id)
            val label = if (v.getBoolean("complete")) "acknowledged archive" else if (v.getBoolean("missing")) "source missing; NOT archived" else "pending"
            panel.addView(text("Export $id — $label\n${v.getLong("offset")} / ${v.getJSONObject("description").getLong("size")} verified bytes\n${v.optString("error")}"))
        }
        latest.requests.objects().forEach { request ->
            panel.addView(text("${request.getString("mutation")} · ${request.getString("path")}\n" +
                if (request.optBoolean("rejected")) "REJECTED (not acknowledged): original proposal and base retained. Refresh shared history and queue a NEW explicit edit/resolution.\n${request.getString("body")}" else "Pending durable request"))
        }
        if (panel.childCount == 0) panel.addView(text("No queued transfers or rejected proposals."))
        AlertDialog.Builder(activity).setTitle("Durable sync queue").setView(ScrollView(activity).apply { addView(panel) }).setPositiveButton("Close", null).show()
    }
    private fun settings() {
        val panel = LinearLayout(activity).apply { orientation = VERTICAL; setPadding(24, 8, 24, 8) }
        panel.addView(text("Installation/source UUID (provision exactly this UUID in the server admin CLI):\n${latest.device}"))
        panel.addView(button("Copy source UUID") {
            activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Source UUID", latest.device))
        })
        val endpoint = edit("https://host.tailnet.ts.net:8443").apply { setText(latest.endpoint); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val token = edit("Private per-device token (never browser password)").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSaveEnabled = false }
        panel.addView(endpoint); panel.addView(token)
        panel.addView(text("Enable only with your private trusted HTTPS endpoint and independently provisioned credential. This opts in to upload ALL existing/future saved work, notes and proven app-owned published MP4s. Drafts/JPEGs/unrelated Gallery media are excluded. One host is not an independent backup. No automatic expiry; deleting locally retains acknowledged archive copies. Background Wi-Fi may be deferred; force-stop requires reopening. Mobile Sync now needs confirmation each attempt. Endpoint replacement is blocked to protect old receipts."))
        val dialog = AlertDialog.Builder(activity).setTitle("Private sync — explicit opt-in").setView(ScrollView(activity).apply { addView(panel) })
            .setNegativeButton("Cancel", null).setNeutralButton("Disable") { _, _ -> owner.disable() }
            .setPositiveButton("Enroll and enable") { _, _ ->
                val selectedEndpoint = endpoint.text.toString()
                val selectedToken = token.text.toString()
                consent { confirmed ->
                    // The confirmation covers enrollment request only; uploads still need Sync now/Wi-Fi job.
                    if (owner.network() == SyncPolicy.Network.MOBILE && !confirmed) return@consent
                    owner.enroll(selectedEndpoint, selectedToken); token.text.clear()
                }
            }.create()
        dialog.setOnDismissListener { token.text.clear() }; dialog.show()
    }
    private fun record(item: JSONObject) {
        val fields = item.getJSONObject("fields")
        val source = item.getJSONObject("source")
        val panel = LinearLayout(activity).apply { orientation = VERTICAL; setPadding(24, 8, 24, 8) }
        panel.addView(text("Source ${item.getString("device")}\nRecord ${item.getString("id")}\nLast source sync: ${java.time.Instant.ofEpochMilli(item.getLong("syncedAt"))}\nState ${source.getString("state")} (not a live remote countdown)\nPlanned ${source.getInt("plannedMinutes")} minutes · archived=${source.getBoolean("archived")} · source-deleted=${source.getBoolean("deleted")}\nOriginal timing: ${source.getJSONObject("timing")}\nNo remote Start, End, duration, archive, deletion or association controls."))
        SyncPolicy.fields.forEach { field ->
            val f = fields.getJSONObject(field)
            val variants = f.optJSONArray("variants")
            panel.addView(text("$field (revision ${f.getLong("revision")}):\n${f.getString("value")}" +
                if (variants != null && variants.length() > 0) "\nCONFLICT — preserved proposals:\n" + variants.objects().joinToString("\n") {
                    "${it.getString("value")}\n  source ${it.getString("device")}, base ${it.getLong("base")}: ${it.getString("baseValue")}" } else ""))
            panel.addView(button("Edit $field") { editor(item, field, false) })
            if (variants != null && variants.length() > 0) panel.addView(button("Resolve $field against this revision") { editor(item, field, true) })
        }
        val links = source.getJSONArray("links").objects()
        panel.addView(text(if (links.isEmpty()) "No captured-video associations; work without video is valid." else "Capture-time links (not seek offsets):\n" + links.joinToString("\n") { l ->
            val matching = latest.history.getJSONArray("videos").objects().filter { it.getString("device") == item.getString("device") && it.getString("recording") == l.getString("recordingId") }
            "${l.getString("recordingId")} · ${l.getLong("startEpoch")}–${l.getLong("endEpoch")} · unknown tail=${l.getBoolean("unknownTail")} · open=${l.getBoolean("open")}\n" +
                if (matching.isEmpty()) "No archived output / missing or not yet uploaded" else "${matching.size} archived output(s)"
        }))
        panel.addView(button("Copy as new local planned timebox") {
            val draft = WorkDraft(title = fields.getJSONObject("title").getString("value"), type = fields.getJSONObject("type").getString("value"),
                minutes = source.getInt("plannedMinutes").toString(), description = fields.getJSONObject("description").getString("value"), outcome = fields.getJSONObject("expectedOutcome").getString("value"))
            WorkSessionCoordinator.get(activity.application).copyShared(draft)
            toast("Copy requested with new local identity; see Work for save status. No timer or camera starts.")
        })
        AlertDialog.Builder(activity).setTitle("Shared work — descriptive edits only").setView(ScrollView(activity).apply { addView(panel) }).setPositiveButton("Close", null).show()
    }
    private fun editor(item: JSONObject, field: String, resolve: Boolean) {
        val value = edit("$field — ${if (resolve) "explicit resolution" else "pending descriptive edit"}").apply {
            setText(item.getJSONObject("fields").getJSONObject(field).getString("value")); minLines = 3
        }
        val dialog = AlertDialog.Builder(activity).setTitle(if (resolve) "Resolve preserved conflict" else "Edit $field")
            .setMessage(if (field == "type") "Existing keys only: ${WorkType.entries.joinToString { it.key }}" else "Title 1–120; description/outcome 0–2000 Unicode characters. No truncation. Offline proposals retain this exact base revision.")
            .setView(value).setNegativeButton("Cancel", null).setPositiveButton("Queue edit", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { owner.patch(item, field, value.text.toString(), resolve); dialog.dismiss() }
            catch (e: IllegalArgumentException) { value.error = e.message }
        } }; dialog.show()
    }
    private fun video(item: JSONObject) {
        val panel = LinearLayout(activity).apply { orientation = VERTICAL }
        val linked = latest.history.getJSONArray("records").objects().filter { r -> r.getString("device") == item.getString("device") &&
            r.getJSONObject("source").getJSONArray("links").objects().any { it.getString("recordingId") == item.getString("recording") } }
        panel.addView(text("Source ${item.getString("device")}\nExport ${item.getString("id")}\n${item.getLong("size")} bytes\nSHA-256 ${item.getString("sha256")}\nSource missing: ${item.getBoolean("missing")}\nRecording: ${item.getString("recording").ifEmpty { "unattributed legacy output (no guessed links)" }}"))
        panel.addView(text("Playback is authenticated on-demand range streaming, not automatic video mirroring. Capture links are not seek mappings."))
        panel.addView(button("Play / seek archived MP4") { consent { owner.play(activity, item, it) } })
        panel.addView(button("Download verified MP4…") { consent { download(item, it) } })
        linked.forEach { r -> panel.addView(button("Linked work: ${r.getJSONObject("fields").getJSONObject("title").getString("value")}") { record(r) }) }
        if (linked.isEmpty()) panel.addView(text("No linked work; no association is fabricated."))
        AlertDialog.Builder(activity).setTitle("Archived video").setView(panel).setPositiveButton("Close", null).show()
    }
    private fun consent(action: (Boolean) -> Unit) {
        when (owner.network()) {
            SyncPolicy.Network.WIFI -> action(false)
            SyncPolicy.Network.MOBILE -> AlertDialog.Builder(activity).setTitle("Use mobile data for this attempt?")
                .setMessage("This explicit attempt may transfer saved notes and large MP4s over mobile data. Background remains Wi-Fi-only. Cancel sends no transfer bytes.")
                .setNegativeButton("Cancel", null).setPositiveButton("Confirm mobile data") { _, _ -> action(true) }.show()
            else -> toast("No eligible Wi-Fi/mobile connection. Pending data remains available offline.")
        }
    }
    private fun text(value: String) = TextView(activity).apply { text = value; setTextIsSelectable(true); setPadding(8, 8, 8, 8) }
    private fun edit(h: String) = EditText(activity).apply { hint = h; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
    private fun button(label: String, action: () -> Unit) = Button(activity).apply { text = label; setOnClickListener { action() } }
    private fun spinner(values: List<String>) = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values) }
    private fun date(value: String): Long? = value.trim().takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
    private fun toast(value: String) = Toast.makeText(activity, value, Toast.LENGTH_LONG).show()
}
