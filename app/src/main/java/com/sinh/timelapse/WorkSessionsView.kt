package com.sinh.timelapse

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.widget.*
import java.time.Instant

/** In-Activity Work surface; no lifecycle/camera binding or media ownership authority. */
internal class WorkSessionsView(
    private val activity: Activity, private val owner: WorkSessionCoordinator,
    private val recordings: () -> List<SessionReview>, private val open: (String) -> Unit,
    private val requestNotifications: () -> Unit, private val requestAlarms: () -> Unit,
    initialId: String? = null,
) : LinearLayout(activity) {
    private val content = LinearLayout(activity).apply { orientation = VERTICAL }
    private val message = text("")
    private var page = if (initialId == null) "list" else "detail"
    private var formGeneration = 0L
    private var disposed = false
    fun dispose() {
        disposed = true; formGeneration++
        discardPrompt?.dismiss(); discardPrompt = null; choicePrompt?.dismiss(); choicePrompt = null
    }
    private var selected = initialId
    private var setupReturn: String? = null
    private var showArchived = false
    private var archiveFilter: Button? = null
    private var activityFilter: String? = null
    private var sort = WorkSort.NEWEST
    private var typeFilterButton: Button? = null
    private var sortButton: Button? = null
    private var discardPrompt: AlertDialog? = null
    private var choicePrompt: AlertDialog? = null
    private var emptyList: TextView? = null
    private var managing = false
    var onPageChanged: (() -> Unit)? = null
    private var newButton: Button? = null
    private var currentActions: LinearLayout? = null
    private var reminderToggle: Button? = null
    private var reminderPanel: LinearLayout? = null
    private var reminderStatus: TextView? = null
    private var notificationButton: Button? = null
    private var timingButton: Button? = null
    private var reminderExpanded = false
    private var saving = false
    private var current = text("")
    private var detail = text("")
    private val rows = linkedMapOf<String, Button>()
    private var list: LinearLayout? = null
    private var draft: WorkDraft? = null
    private var titleField: EditText? = null
    private var durationField: EditText? = null
    private var durationShortcuts: LinearLayout? = null
    private var outcomeField: EditText? = null
    private var saveButton: Button? = null
    private var actionsKey = ""
    private var announcedState = ""
    private var announcedError: String? = null
    private var linksKey: String? = null
    private val linkTexts = linkedMapOf<String, TextView>()
    private val unavailableVideos = mutableSetOf<String>()
    fun markUnavailable(id: String) { unavailableVideos.add(id); owner.state.value?.let(::render) }
    private var actions: LinearLayout? = null
    private var linksPanel: LinearLayout? = null
    init {
        orientation = VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.rgb(16, 25, 22))
        addView(ScrollView(activity).apply { isFillViewport = true; addView(content) }, LayoutParams(-1, 0, 1f))
        buildPage()
    }
    fun back(): Boolean {
        if (page == "edit") { cancelEdit(); return true }
        if (page == "list") {
            if (!showArchived) return false
            showArchived = false; buildPage(); return true
        }
        if (page == "setup" && setupReturn != null) {
            page = "detail"; selected = setupReturn; setupReturn = null
        } else { page = "list"; selected = null }
        buildPage(); return true
    }
    fun savedPage(): String = page
    fun selectedId(): String? = selected
    fun setupReturnId(): String? = setupReturn
    fun isShowingArchived(): Boolean = showArchived
    fun activityTypeFilter(): String? = activityFilter
    fun listSort(): String = sort.name
    fun restore(page: String, id: String?, returnId: String? = null, archivedView: Boolean = false,
        typeFilter: String? = null, sortName: String? = null) {
        this.page = page.takeIf { it in setOf("list", "setup", "edit", "detail") } ?: "list"
        if (this.page == "edit" && owner.savedEditor == null) this.page = if (id == null) "list" else "detail"
        activityFilter = typeFilter?.takeIf { key -> WorkType.entries.any { it.key == key } }
        sort = WorkSort.entries.singleOrNull { it.name == sortName } ?: WorkSort.NEWEST
        selected = id; setupReturn = returnId; showArchived = archivedView; buildPage()
    }
    private fun cancelEdit() {
        if (owner.editSaving || discardPrompt != null) return
        fun discard() {
            owner.cancelSavedEdit(); hideKeyboard(); draft = null; page = "detail"; buildPage()
        }
        if (owner.savedEditor?.changed != true) { discard(); return }
        discardPrompt = AlertDialog.Builder(activity).setTitle("Discard unsaved changes?")
            .setMessage("This timebox has not been changed. Your create draft is also retained.")
            .setNegativeButton("Keep editing", null).setPositiveButton("Discard changes") { _, _ -> discard() }
            .create().also { dialog -> dialog.setOnDismissListener { discardPrompt = null }; dialog.show() }
    }
    private fun chooseTypeFilter() {
        if (disposed || choicePrompt != null) return
        val values = listOf<String?>(null) + WorkType.entries.map { it.key }
        choicePrompt = AlertDialog.Builder(activity).setTitle("Activity type")
            .setSingleChoiceItems((listOf("All") + WorkType.entries.map { it.label }).toTypedArray(), values.indexOf(activityFilter)) { dialog, index ->
                if (!disposed) { activityFilter = values[index]; owner.state.value?.let(::render) }
                choicePrompt = null; dialog.dismiss()
            }.setNegativeButton("Cancel", null).create().also { dialog ->
                dialog.setOnDismissListener { if (choicePrompt === dialog) choicePrompt = null }; dialog.show()
            }
    }
    private fun chooseSort() {
        if (disposed || choicePrompt != null) return
        choicePrompt = AlertDialog.Builder(activity).setTitle("Sort timeboxes")
            .setSingleChoiceItems(WorkSort.entries.map { it.label }.toTypedArray(), sort.ordinal) { dialog, index ->
                if (!disposed) { sort = WorkSort.entries[index]; owner.state.value?.let(::render) }
                choicePrompt = null; dialog.dismiss()
            }.setNegativeButton("Cancel", null).create().also { dialog ->
                dialog.setOnDismissListener { if (choicePrompt === dialog) choicePrompt = null }; dialog.show()
            }
    }
    private fun buildPage() {
        formGeneration++
        content.removeAllViews(); content.addView(message); rows.clear(); actionsKey = ""; linksKey = null; list = null
        newButton = null; currentActions = null; archiveFilter = null; emptyList = null; typeFilterButton = null; sortButton = null
        reminderToggle = null; reminderPanel = null; reminderStatus = null; notificationButton = null; timingButton = null
        titleField = null; outcomeField = null; durationField = null; durationShortcuts = null; saveButton = null
        when (page) {
            "setup", "edit" -> if (owner.state.value?.loaded == true) setup() else content.addView(text("Loading saved draft…"))
            "detail" -> {
                detail = text(""); content.addView(detail)
                actions = LinearLayout(activity).apply { orientation = VERTICAL }; content.addView(actions)
                linksPanel = LinearLayout(activity).apply { orientation = VERTICAL }; content.addView(linksPanel)
            }
            else -> {
                current = text(""); content.addView(current)
                currentActions = LinearLayout(activity).apply {
                    orientation = VERTICAL
                    addView(button("View running timebox") { owner.state.value?.snapshot?.running?.id?.let { selected = it; page = "detail"; buildPage() } })
                    addView(button("End timebox") { owner.state.value?.snapshot?.running?.id?.let(owner::end) })
                }
                content.addView(currentActions)
                newButton = button("New timebox") { newDraft() }; content.addView(newButton)
                reminderToggle = button("End reminder settings") {
                    reminderExpanded = !reminderExpanded
                    reminderPanel?.visibility = if (reminderExpanded) VISIBLE else GONE
                }
                content.addView(reminderToggle)
                reminderPanel = LinearLayout(activity).apply {
                    orientation = VERTICAL; visibility = if (reminderExpanded) VISIBLE else GONE
                    reminderStatus = text(""); addView(reminderStatus)
                    addView(text("1. Notifications let the app show an alert when a timebox ends."))
                    notificationButton = button("Allow end notification") { requestNotifications() }; addView(notificationButton)
                    addView(text("2. Background timing lets Android wake the app at the deadline while it is closed or the screen is off. This is separate from permission to show the alert."))
                    timingButton = button("Allow background timing") { requestAlarms() }; addView(timingButton)
                    addView(text("Both are optional and affect reminders only, not camera recording. Force-stop or OS restrictions can suppress delivery; reopen to reconcile expiry."))
                }
                content.addView(reminderPanel)
                archiveFilter = button("Show archived timeboxes") { showArchived = !showArchived; buildPage() }
                content.addView(archiveFilter)
                typeFilterButton = button("") { chooseTypeFilter() }; content.addView(typeFilterButton)
                sortButton = button("") { chooseSort() }; content.addView(sortButton)
                emptyList = text(""); content.addView(emptyList)
                list = LinearLayout(activity).apply { orientation = VERTICAL }; content.addView(list)
            }
        }
        onPageChanged?.invoke()
        owner.state.value?.let(::render)
    }
    private fun newDraft(copy: WorkSession? = null) {
        if (copy != null && (owner.editorDraft != null || owner.state.value?.snapshot?.draft != null)) {
            AlertDialog.Builder(activity).setMessage("An unsaved draft already exists. Open it or discard it explicitly before copying.")
                .setPositiveButton("Open draft") { _, _ -> newDraft() }.setNegativeButton("Cancel", null).show()
            return
        }
        if (owner.state.value?.loaded != true) return
        setupReturn = copy?.id
        draft = owner.beginDraft(copy); page = "setup"; buildPage()
    }
    private fun setup() {
        val generation = formGeneration
        val editing = page == "edit"
        val initial = if (editing) checkNotNull(owner.savedEditor).draft
            else owner.editorDraft ?: owner.state.value?.snapshot?.draft ?: owner.beginDraft()
        val durationLocked = editing && owner.savedEditor?.original?.state == WorkState.ENDED
        saving = if (editing) owner.editSaving else saving
        draft = initial
        titleField = edit("Activity title", initial.title).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        val type = Spinner(activity).apply {
            contentDescription = "Activity type, optional; unselected saves To-be-sorted"
            adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, listOf("Choose activity type (optional)") + WorkType.entries.map { it.label }) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply { isSingleLine = false; setTextColor(Color.WHITE); minHeight = dp(48) }
            }
            setSelection(initial.type?.let { key -> WorkType.entries.indexOfFirst { it.key == key } + 1 } ?: 0)
            minimumHeight = dp(48)
        }
        durationField = edit("Original planned duration (minutes, 1–1440)", initial.minutes).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        outcomeField = edit("Expected outcome (optional)", initial.outcome).apply {
            minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
        }
        titleField!!.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_NEXT) { focusInput(if (durationLocked) outcomeField!! else durationField!!); true } else false }
        durationField!!.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_NEXT) { focusInput(outcomeField!!); true } else false }
        content.addView(text(if (editing) "Edit timebox. Save changes updates this record only; original timing and recording links are retained. No camera action."
            else "Save creates an intention only. Start is a separate action; no camera or countdown starts on Save."))
        content.addView(text("Activity title (required)")); content.addView(titleField)
        content.addView(text("Activity type (optional; defaults to To-be-sorted)")); content.addView(type)
        content.addView(text("Original planned duration in minutes (required)")); content.addView(durationField)
        val shortcuts = LinearLayout(activity)
        durationShortcuts = shortcuts
        listOf(25, 45, 60, 90).forEach { minutes -> shortcuts.addView(button("$minutes") { if (!durationLocked) durationField!!.setText("$minutes") }, LayoutParams(0, -2, 1f)) }
        content.addView(shortcuts)
        if (durationLocked) content.addView(text("Ended timebox: original planned minutes cannot be changed."))
        content.addView(text("Expected outcome (optional)")); content.addView(outcomeField)
        fun edited() {
            if (disposed || saving || generation != formGeneration || page !in setOf("setup", "edit") || activity.isDestroyed) return
            val value = initial.copy(title = titleField!!.text.toString(), minutes = durationField!!.text.toString(),
                outcome = outcomeField!!.text.toString(), type = WorkType.entries.getOrNull(type.selectedItemPosition - 1)?.key)
            val errors = value.errors()
            if ("title" !in errors) titleField!!.error = null
            if ("minutes" !in errors) durationField!!.error = null
            if ("outcome" !in errors) outcomeField!!.error = null
            draft = value
            if (editing) owner.editSavedDraft(value) else owner.edit(value)
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = edited()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        listOf(titleField!!, durationField!!, outcomeField!!).forEach { it.addTextChangedListener(watcher) }
        type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = edited()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        saveButton = button(if (editing) "Save changes" else "Save intention") {
            if (saving || (editing && owner.editSaving)) return@button
            val value = checkNotNull(draft)
            val errors = value.errors()
            titleField!!.error = errors["title"]; durationField!!.error = errors["minutes"]; outcomeField!!.error = errors["outcome"]
            if (errors.isNotEmpty()) {
                focusInput(when { "title" in errors -> titleField!!; "minutes" in errors -> durationField!!; else -> outcomeField!! })
                return@button
            }
            saving = true; setFormEnabled(false)
            message.text = if (editing) "Saving changes…" else "Saving intention…"
            if (editing) { owner.editSavedDraft(value); owner.saveEdited(); return@button }
            owner.save(value) { id ->
                saving = false
                if (!disposed && !activity.isDestroyed) { hideKeyboard(); selected = id; setupReturn = null; showArchived = false; page = "detail"; buildPage() }
            }
        }
        content.addView(saveButton)
        content.addView(button(if (editing) "Cancel" else "Discard draft") {
            if (editing) cancelEdit()
            else if (!saving) owner.discard(initial.id) { if (!disposed && !activity.isDestroyed) { hideKeyboard(); page = "list"; setupReturn = null; draft = null; buildPage() } }
        })
    }
    fun render(state: WorkSessionCoordinator.State) {
        if (disposed) return
        if (page == "edit") {
            saving = owner.editSaving
            if (owner.savedEditor == null) { hideKeyboard(); page = "detail"; draft = null; buildPage(); return }
        } else if (state.error != null) saving = false
        if (state.error != null) managing = false
        val inForm = page == "setup" || page == "edit"
        if (inForm && titleField == null && state.loaded) { buildPage(); return }
        val status = listOfNotNull(state.error ?: if (saving) { if (page == "edit") "Saving changes…" else "Saving intention…" } else state.message,
            state.reminder.takeIf { it.isNotEmpty() && !inForm }).joinToString("\n")
        if (message.text.toString() != status) message.text = status
        if (state.error != null && state.error != announcedError) message.announceForAccessibility(state.error)
        announcedError = state.error
        if (!state.loaded) { newButton?.isEnabled = false; return }
        if (inForm) setFormEnabled(state.loaded && !saving)
        val now = runCatching(owner::now).getOrNull() ?: return
        when (page) {
            "list" -> {
                current.text = if (showArchived) "Archived timeboxes — recording links are retained" else state.snapshot.running?.let { "Running: ${it.title}\nRemaining ${remaining(it.remaining(now))}" } ?: "No timebox running"
                currentActions?.visibility = if (showArchived || state.snapshot.running == null) GONE else VISIBLE
                newButton?.visibility = if (showArchived) GONE else VISIBLE
                reminderToggle?.visibility = if (showArchived) GONE else VISIBLE
                reminderPanel?.visibility = if (!showArchived && reminderExpanded) VISIBLE else GONE
                archiveFilter?.text = if (showArchived) "Back to active timeboxes" else "Show archived timeboxes (${state.snapshot.records.count { it.archived }})"
                typeFilterButton?.text = "Activity type: ${activityFilter?.let { WorkType.fromKey(it).label } ?: "All"}"
                sortButton?.text = "Sort: ${sort.label}"
                val partition = state.snapshot.records.filter { it.archived == showArchived }
                val displayed = WorkListPolicy.project(state.snapshot.records, showArchived, activityFilter, sort)
                val ids = displayed.map { it.id }.toSet()
                rows.keys.filter { it !in ids }.toList().forEach { id -> list!!.removeView(rows.remove(id)) }
                emptyList?.apply {
                    text = if (partition.isNotEmpty()) "No timeboxes match this Activity type."
                        else if (showArchived) "No archived timeboxes." else "No saved timeboxes yet."
                    visibility = if (displayed.isEmpty()) VISIBLE else GONE
                }
                val access = state.reminderAccess
                reminderToggle?.text = "End reminder settings — ${if (access == null) "check access" else if (access.notificationsAllowed && access.backgroundTiming) "ready" else "needs setup"}"
                reminderStatus?.text = if (access == null) state.reminder else "Notifications: ${access.notificationStatus}\nBackground timing: ${if (access.backgroundTiming) "Allowed" else "Not allowed"}"
                notificationButton?.apply { text = access?.notificationAction ?: "Allow end notification"; isEnabled = access?.notificationsAllowed != true }
                timingButton?.apply { text = access?.timingAction ?: "Allow background timing"; isEnabled = access?.backgroundTiming != true }
                newButton?.apply { text = if (owner.editorDraft != null || state.snapshot.draft != null) "Resume draft" else "New timebox"; isEnabled = state.loaded }
                displayed.forEachIndexed { index, work ->
                    val row = rows.getOrPut(work.id) { button("") { selected = work.id; page = "detail"; buildPage() } }
                    val label = "${work.title}\n${WorkType.fromKey(work.type).label} · ${work.state.name.lowercase()} · ${dateTime(work.createdAt)}\n${work.id}"
                    if (row.text.toString() != label) row.text = label
                    if (list!!.indexOfChild(row) != index) {
                        val focused = row.hasFocus()
                        list!!.removeView(row); list!!.addView(row, index)
                        if (focused) row.requestFocus()
                    }
                }
            }
            "detail" -> {
                val work = state.snapshot.records.singleOrNull { it.id == selected } ?: run {
                    detail.text = "This timebox is unavailable or has been deleted."
                    actions?.removeAllViews(); linksPanel?.removeAllViews(); return
                }
                val start = work.start
                detail.text = "${work.title}\n${work.id}\nCreated ${dateTime(work.createdAt)}\n${WorkType.fromKey(work.type).label}\nOriginal plan: ${work.plannedDurationLabel}\nExpected outcome: ${work.outcome ?: "Not specified"}\n${work.state.name.lowercase()}${if (work.archived) " · archived" else ""}" +
                    (if (work.state == WorkState.RUNNING) "\nRemaining ${remaining(work.remaining(now))}" else "") +
                    (start?.let { "\nStart ${Instant.ofEpochMilli(it.epoch)} · boot ${it.boot}" } ?: "") +
                    (work.endElapsed?.let { "\n${work.reason} · confirmed elapsed ${work.confirmedElapsed}ms\nDerived end (start wall anchor): ${Instant.ofEpochMilli(Math.addExact(start!!.epoch, it - start.elapsed))}" } ?: "") +
                    (if (work.uncertain) "\nInterrupted by reboot; actual end uncertain. Confirmed elapsed is a lower bound." else "")
                val stateKey = "${work.id}:${work.state}:${work.archived}"
                if (announcedState != stateKey) { announcedState = stateKey; detail.announceForAccessibility("Timebox ${work.state.name.lowercase()}") }
                val key = "$stateKey:${state.error}:$managing"
                if (actionsKey != key) {
                    actionsKey = key; actions!!.removeAllViews()
                    if (work.archived) actions!!.addView(button("Restore timebox") { manageArchive(work, false) })
                    when (work.state) {
                        WorkState.PLANNED -> actions!!.addView(button("Start timebox") {
                            val running = owner.state.value?.snapshot?.running
                            if (running != null && running.id != work.id) AlertDialog.Builder(activity).setTitle("End current / Start new?")
                                .setMessage("End ${running.title} (${running.id}) and start ${work.title}?")
                                .setNegativeButton("Cancel", null).setPositiveButton("End current / Start new") { _, _ -> owner.start(work.id, running.id) }.show()
                            else owner.start(work.id)
                        }.apply { visibility = if (work.archived) GONE else VISIBLE; isEnabled = state.loaded })
                        WorkState.RUNNING -> actions!!.addView(button("End timebox") { owner.end(work.id) }.apply { isEnabled = state.loaded })
                        WorkState.ENDED -> actions!!.addView(button("Copy as new") { newDraft(work) }.apply { isEnabled = state.loaded })
                    }
                    if (work.state == WorkState.RUNNING) actions!!.addView(text("End this timebox before archiving or deleting it."))
                    else {
                        actions!!.addView(button("Edit timebox") {
                            val latest = owner.state.value?.snapshot?.records?.singleOrNull { it.id == selected }
                            if (latest != null && latest.state != WorkState.RUNNING) {
                                owner.beginSavedEdit(latest); page = "edit"; buildPage()
                            }
                        })
                        if (!work.archived) actions!!.addView(button("Archive timebox") { manageArchive(work, true) })
                        actions!!.addView(button("Delete timebox…") { confirmDelete(work) })
                    }
                }
                setActionButtonsEnabled(state.loaded && !managing)
                val links = state.snapshot.links(now).filter { it.workId == work.id }
                // Only rebuild when identities/output availability change; countdown refresh keeps focus.
                val linkKey = links.joinToString { it.id } + recordings().joinToString { "${it.id}:${it.output}:${it.status}" } + unavailableVideos.sorted().joinToString()
                if (linksKey != linkKey) {
                    linksKey = linkKey; linksPanel!!.removeAllViews(); linkTexts.clear()
                    linksPanel!!.addView(text("Linked recordings (capture time, not MP4 seek positions):${if (links.isEmpty()) "\nNo known recording overlaps." else ""}"))
                    links.forEach { link ->
                        val review = recordings().singleOrNull { it.id == link.recordingId }
                        val rangeText = text(""); linkTexts[link.id] = rangeText; linksPanel!!.addView(rangeText)
                        linksPanel!!.addView(button("Open recording ${link.recordingId}") { open(link.recordingId) }.apply { isEnabled = review?.output != null && link.recordingId !in unavailableVideos })
                    }
                }
                links.forEach { link ->
                    val review = recordings().singleOrNull { it.id == link.recordingId }
                    val availability = if (link.recordingId in unavailableVideos) "Video missing/unreadable; context retained" else if (review?.output == null) "Video unavailable (not published or missing); context retained" else review.status
                    linkTexts[link.id]?.text = linkText(link) + "\n$availability"
                }
            }
        }
    }
    private fun manageArchive(work: WorkSession, archived: Boolean) {
        if (managing) return
        managing = true; setActionButtonsEnabled(false)
        owner.archive(work.id, archived) {
            managing = false
            if (!disposed && !activity.isDestroyed) {
                showArchived = false
                if (archived) { page = "list"; selected = null } else { page = "detail"; selected = work.id }
                buildPage()
            }
        }
    }
    private fun confirmDelete(work: WorkSession) {
        if (managing) return
        AlertDialog.Builder(activity).setTitle("Delete timebox permanently?")
            .setMessage("${work.title}\n${work.id}\n\nRemove this timebox's title, outcome, timing and recording links? Photos, videos and other timeboxes are NOT deleted. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete timebox") { _, _ ->
                managing = true; setActionButtonsEnabled(false)
                owner.delete(work.id) {
                    managing = false
                    if (!disposed && !activity.isDestroyed) { page = "list"; selected = null; buildPage() }
                }
            }.show()
    }
    private fun setActionButtonsEnabled(enabled: Boolean) {
        actions?.let { panel -> for (i in 0 until panel.childCount) (panel.getChildAt(i) as? Button)?.isEnabled = enabled }
    }
    private fun setFormEnabled(enabled: Boolean) {
        fun visit(view: View) {
            if (view is EditText || view is Spinner || view is Button) view.isEnabled = enabled
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(content)
        if (page == "edit" && owner.savedEditor?.original?.state == WorkState.ENDED) {
            durationField?.isEnabled = false
            durationShortcuts?.let { shortcuts -> for (i in 0 until shortcuts.childCount) shortcuts.getChildAt(i).isEnabled = false }
        }
    }
    private fun focusInput(field: EditText) {
        field.requestFocus()
        field.post { if (!disposed && field.isAttachedToWindow) activity.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
    }
    private fun hideKeyboard() { activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0) }
    private fun dateTime(epoch: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epoch))
    private fun edit(name: String, value: String) = EditText(activity).apply {
        hint = name; contentDescription = name; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); setText(value); minHeight = dp(48)
    }
    private fun button(name: String, click: () -> Unit) = Button(activity).apply { text = name; isAllCaps = false; minHeight = dp(48); setOnClickListener { click() } }
    private fun text(value: String) = TextView(activity).apply { text = value; textSize = 15f; setTextColor(Color.WHITE) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        fun remaining(ms: Long): String { val s = (ms.coerceAtLeast(0) + 999) / 1000; return "%d:%02d".format(s / 60, s % 60) }
        fun linkText(link: RecordingLink) = "Recording ${link.recordingId}\n${Instant.ofEpochMilli(link.startEpoch)} – ${Instant.ofEpochMilli(link.endEpoch)} (capture wall anchors)\n${link.boot} · [${link.startElapsed}, ${link.endElapsed})ms${if (link.unknownTail) " · unknown tail excluded" else ""}${if (link.open) " · open span, provisional end" else ""}"
    }
}
