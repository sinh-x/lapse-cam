package com.sinh.timelapse

import android.app.Application
import android.content.Context
import android.widget.Button
import android.widget.EditText
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkSessionActivityTest {
    private fun buttons(view: android.view.View) = WorkUiFixtures.descendants(view).filterIsInstance<Button>()
    private fun field(view: android.view.View, prefix: String) = WorkUiFixtures.descendants(view).filterIsInstance<EditText>()
        .single { it.contentDescription.toString().startsWith(prefix) }
    private fun alertButton(label: String) = android.view.inspector.WindowInspector.getGlobalWindowViews()
        .flatMap(WorkUiFixtures::descendants).filterIsInstance<Button>().single { it.text.toString().equals(label, true) }.performClick()
    private fun choose(index: Int) {
        val list = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(WorkUiFixtures::descendants)
            .filterIsInstance<android.widget.ListView>().single()
        list.performItemClick(list.adapter.getView(index, null, list), index, list.adapter.getItemId(index))
    }

    @Test fun sharedEditorConfirmsDiscardRetainsCreateDraftAndRecreationThenSavesSameId() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "edit-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val original = WorkDraft(title = "Original", type = "study", minutes = "25").validated(123)
        val create = WorkDraft(title = "  Raw create  ", minutes = "invalid", outcome = "a\nb")
        WorkSessionStore(root).commit(WorkSnapshot(draft = create, records = listOf(original)))
        val owner = WorkSessionCoordinator(context.applicationContext as Application, root, false)
        try {
            WorkUiFixtures.emptyActivity().use { scenario ->
                WorkUiFixtures.await(scenario) { owner.state.value?.loaded == true }
                lateinit var view: WorkSessionsView
                lateinit var dialog: WorkDialog
                var returnPage = ""; var selected: String? = null
                scenario.onActivity { activity ->
                    owner.beginDraft()
                    view = WorkSessionsView(activity, owner, { emptyList() }, {}, {}, {}, original.id)
                    owner.state.observe(activity) { view.render(it) }
                    dialog = WorkDialog(activity, view) {}; dialog.show()
                    buttons(view).single { it.text == "Edit timebox" }.performClick()
                    assertEquals(3, WorkUiFixtures.descendants(view).filterIsInstance<EditText>().size)
                    assertEquals(1, WorkUiFixtures.descendants(view).filterIsInstance<android.widget.Spinner>().size)
                    assertEquals("Original", field(view, "Activity title").text.toString())
                    field(view, "Activity title").setText("Changed")
                    dialog.cancel() // Escape/legacy cancellation must not bypass the guard.
                    assertTrue(dialog.isShowing)
                }
                WorkUiFixtures.idle()
                scenario.onActivity {
                    alertButton("Keep editing")
                    assertEquals("Changed", field(view, "Activity title").text.toString())
                    assertTrue(view.back())
                }
                WorkUiFixtures.idle()
                scenario.onActivity {
                    alertButton("Discard changes")
                    assertEquals("detail", view.savedPage()); assertEquals(original.id, view.selectedId())
                    assertEquals(original, owner.state.value!!.snapshot.records.single()); assertEquals(create, owner.editorDraft)
                    buttons(view).single { it.text == "Edit timebox" }.performClick()
                    field(view, "Activity title").setText("Recreated edit")
                    field(view, "Original planned").setText("45")
                    field(view, "Expected outcome").setText("Retained outcome")
                    returnPage = view.savedPage(); selected = view.selectedId(); dialog.dismiss()
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    view = WorkSessionsView(activity, owner, { emptyList() }, {}, {}, {})
                    view.restore(returnPage, selected)
                    owner.state.observe(activity) { view.render(it) }
                    dialog = WorkDialog(activity, view) {}; dialog.show()
                    assertEquals("Recreated edit", field(view, "Activity title").text.toString())
                    assertEquals("45", field(view, "Original planned").text.toString())
                    assertEquals(create, owner.editorDraft)
                    field(view, "Activity title").setText(" ")
                    buttons(view).single { it.text == "Save changes" }.performClick()
                    assertNotNull(field(view, "Activity title").error)
                    assertEquals(original, owner.state.value!!.snapshot.records.single())
                    field(view, "Activity title").setText("Recreated edit")
                    buttons(view).single { it.text == "Save changes" }.performClick()
                }
                WorkUiFixtures.await(scenario) { view.savedPage() == "detail" && owner.state.value!!.snapshot.records.single().title == "Recreated edit" }
                scenario.onActivity { activity ->
                    assertEquals(original.copy(title = "Recreated edit", plannedMinutes = 45, outcome = "Retained outcome"), owner.state.value!!.snapshot.records.single())
                    assertEquals(create, owner.editorDraft); assertEquals(create, owner.state.value!!.snapshot.draft)
                    assertFalse(androidx.lifecycle.ViewModelProvider(activity)[CaptureViewModel::class.java].state.value!!.capturing)
                    assertTrue(view.back())
                    buttons(view).single { it.text == "Resume draft" }.performClick()
                    assertEquals(create.title, field(view, "Activity title").text.toString())
                    assertEquals(create.minutes, field(view, "Original planned").text.toString()); dialog.dismiss()
                }
            }
            assertEquals(create, WorkSessionStore(root).load().draft)
        } finally { owner.closeFixture(); check(root.deleteRecursively()) }
    }

    @Test fun archivedEndedEditorLocksMinutesAndShortcutsWhileRunningHasNoEdit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "ended-edit-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val work = WorkDraft(title = "Ended", minutes = "1").validated(123)
        fun moment(value: Long) = WorkMoment("fixture", value, 1000 + value)
        val ended = TimeboxPolicy.end(TimeboxPolicy.start(WorkSnapshot(records = listOf(work)), work.id, moment(0)), work.id, moment(10))
            .records.single().copy(archived = true)
        WorkSessionStore(root).commit(WorkSnapshot(records = listOf(ended)))
        val owner = WorkSessionCoordinator(context.applicationContext as Application, root, false, clock = { moment(20) })
        try {
            WorkUiFixtures.emptyActivity().use { scenario ->
                WorkUiFixtures.await(scenario) { owner.state.value?.loaded == true }
                lateinit var view: WorkSessionsView
                scenario.onActivity { activity ->
                    view = WorkSessionsView(activity, owner, { emptyList() }, {}, {}, {}, ended.id)
                    owner.state.observe(activity) { view.render(it) }
                    buttons(view).single { it.text == "Edit timebox" }.performClick()
                    assertFalse(field(view, "Original planned").isEnabled)
                    listOf("25", "45", "60", "90").forEach { shortcut -> assertFalse(buttons(view).single { it.text == shortcut }.isEnabled) }
                    field(view, "Activity title").setText("Edited ended")
                    view.render(owner.state.value!!)
                    assertFalse(field(view, "Original planned").isEnabled)
                    buttons(view).single { it.text == "Save changes" }.performClick()
                }
                WorkUiFixtures.await(scenario) { view.savedPage() == "detail" && owner.state.value!!.snapshot.records.single().title == "Edited ended" }
                scenario.onActivity {
                    assertEquals(ended.copy(title = "Edited ended"), owner.state.value!!.snapshot.records.single())
                    buttons(view).single { it.text == "Edit timebox" }.performClick()
                    field(view, "Activity title").setText("Stale edit")
                    owner.archive(ended.id, false)
                }
                WorkUiFixtures.await(scenario) { !owner.state.value!!.snapshot.records.single().archived }
                scenario.onActivity { buttons(view).single { it.text == "Save changes" }.performClick() }
                WorkUiFixtures.await(scenario) { !owner.editSaving && owner.state.value!!.error != null }
                scenario.onActivity {
                    assertEquals("edit", view.savedPage()); assertEquals("Stale edit", field(view, "Activity title").text.toString())
                    assertFalse(field(view, "Original planned").isEnabled)
                    assertTrue(WorkUiFixtures.descendants(view).filterIsInstance<android.widget.TextView>().any { it.text.toString().contains("timebox changed") })
                    assertEquals(ended.copy(title = "Edited ended", archived = false), owner.state.value!!.snapshot.records.single())
                    buttons(view).single { it.text == "Cancel" }.performClick()
                }
                WorkUiFixtures.idle()
                scenario.onActivity {
                    alertButton("Discard changes")
                    view.render(owner.state.value!!.copy(snapshot = WorkSnapshot(records = listOf(ended.copy(state = WorkState.RUNNING, archived = false, endElapsed = null, reason = null, confirmedElapsed = 0)))))
                    assertTrue(buttons(view).none { it.text == "Edit timebox" }); view.dispose()
                }
            }
        } finally { owner.closeFixture(); check(root.deleteRecursively()) }
    }

    @Test fun listControlsReorderVisibleRowsRetainStateAndKeepExcludedRunningAccessible() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "list-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val records = WorkType.entries.flatMap { type -> (0..2).map { index ->
            WorkDraft(title = "${type.label} $index", type = type.key).validated(if (index == 2) 100 else 200)
        } }
        val archived = records.map { it.copy(id = UUID.randomUUID().toString(), archived = true) }
        val runningSnapshot = TimeboxPolicy.start(WorkSnapshot(records = records + archived), records.first().id, WorkMoment("fixture", 0, 1000))
        WorkSessionStore(root).commit(runningSnapshot)
        val owner = WorkSessionCoordinator(context.applicationContext as Application, root, false, clock = { WorkMoment("fixture", 10, 1010) })
        fun ids(view: WorkSessionsView) = buttons(view).map { it.text.toString().substringAfterLast('\n') }.filter { id -> (records + archived).any { it.id == id } }
        try {
            WorkUiFixtures.emptyActivity().use { scenario ->
                WorkUiFixtures.await(scenario) { owner.state.value?.loaded == true }
                lateinit var view: WorkSessionsView
                lateinit var dialog: WorkDialog
                scenario.onActivity { activity ->
                    view = WorkSessionsView(activity, owner, { emptyList() }, {}, {}, {})
                    owner.state.observe(activity) { view.render(it) }
                    dialog = WorkDialog(activity, view) {}; dialog.show()
                    assertEquals("Sort: Newest created", buttons(view).single { it.text.toString().startsWith("Sort:") }.text.toString())
                    assertEquals(records.sortedWith(compareByDescending<WorkSession> { it.createdAt }.thenBy { it.id }).map { it.id }, ids(view))
                    buttons(view).single { it.text.toString().startsWith("Sort:") }.performClick(); choose(1)
                    assertEquals(records.sortedWith(compareBy<WorkSession> { it.createdAt }.thenBy { it.id }).map { it.id }, ids(view))
                    buttons(view).single { it.text.toString().startsWith("Sort:") }.performClick(); choose(2)
                    assertEquals(records.sortedWith(compareBy<WorkSession> { WorkType.fromKey(it.type).ordinal }.thenByDescending { it.createdAt }.thenBy { it.id }).map { it.id }, ids(view))
                    WorkType.entries.forEachIndexed { index, type ->
                        buttons(view).single { it.text.toString().startsWith("Activity type:") }.performClick(); choose(index + 1)
                        assertEquals(records.filter { it.type == type.key }.sortedWith(compareByDescending<WorkSession> { it.createdAt }.thenBy { it.id }).map { it.id }, ids(view))
                    }
                    buttons(view).single { it.text.toString().startsWith("Activity type:") }.performClick(); choose(4)
                    assertEquals(records.filter { it.type == "social" }.sortedWith(compareByDescending<WorkSession> { it.createdAt }.thenBy { it.id }).map { it.id }, ids(view))
                    assertTrue(buttons(view).single { it.text == "View running timebox" }.visibility == android.view.View.VISIBLE)
                    buttons(view).single { it.text == "View running timebox" }.performClick(); assertEquals(records.first().id, view.selectedId())
                    assertTrue(buttons(view).none { it.text == "Edit timebox" }); assertTrue(view.back())
                    buttons(view).single { it.text.toString().startsWith("Show archived") }.performClick()
                    assertEquals(archived.filter { it.type == "social" }.sortedWith(compareByDescending<WorkSession> { it.createdAt }.thenBy { it.id }).map { it.id }, ids(view))
                    assertTrue(buttons(view).single { it.text == "View running timebox" }.visibility != android.view.View.VISIBLE)
                    view.render(owner.state.value!!.copy(snapshot = WorkSnapshot(records = archived.filter { it.type != "social" })))
                    assertTrue(WorkUiFixtures.descendants(view).filterIsInstance<android.widget.TextView>().any { it.visibility == android.view.View.VISIBLE && it.text == "No timeboxes match this Activity type." })
                    view.render(owner.state.value!!.copy(snapshot = WorkSnapshot()))
                    assertTrue(WorkUiFixtures.descendants(view).filterIsInstance<android.widget.TextView>().any { it.visibility == android.view.View.VISIBLE && it.text == "No archived timeboxes." })
                    view.render(owner.state.value!!)
                    val savedSort = view.listSort(); val savedType = view.activityTypeFilter()
                    dialog.dismiss()
                    view = WorkSessionsView(activity, owner, { emptyList() }, {}, {}, {})
                    view.restore("list", null, archivedView = true, typeFilter = savedType, sortName = savedSort)
                    assertEquals(savedSort, view.listSort()); assertEquals(savedType, view.activityTypeFilter())
                    view.dispose()
                    view = WorkSessionsView(activity, owner, { emptyList() }, {}, {}, {})
                    assertNull(view.activityTypeFilter()); assertEquals("NEWEST", view.listSort())
                    assertEquals(runningSnapshot.records, owner.state.value!!.snapshot.records)
                    view.dispose()
                }
            }
        } finally { owner.closeFixture(); check(root.deleteRecursively()) }
    }

    @Test fun archiveRestoreAndDeleteConfirmationPreserveIdentityAndCancelIsNoOp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "manage-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val draft = WorkDraft(title = "Owned management fixture")
        WorkSessionStore(root).apply { saveDraft(draft); save(draft.id, 123) }
        val owner = WorkSessionCoordinator(context.applicationContext as Application, root, remindersEnabled = false)
        try {
            WorkUiFixtures.emptyActivity().use { scenario ->
                WorkUiFixtures.await(scenario) { owner.state.value?.loaded == true }
                lateinit var view: WorkSessionsView
                lateinit var dialog: WorkDialog
                lateinit var observer: Observer<WorkSessionCoordinator.State>
                scenario.onActivity { activity ->
                    view = WorkSessionsView(activity, owner, { emptyList() }, {}, {}, {}, draft.id)
                    observer = Observer { view.render(it) }; owner.state.observe(activity, observer)
                    dialog = WorkDialog(activity, view) {}; dialog.show()
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text == "Archive timebox" }.performClick()
                }
                WorkUiFixtures.await(scenario) { owner.state.value!!.snapshot.records.single().archived }
                scenario.onActivity {
                    assertTrue(WorkUiFixtures.descendants(view).filterIsInstance<Button>().none { it.text.toString().startsWith(draft.title) })
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text.toString().startsWith("Show archived timeboxes") }.performClick()
                    assertTrue(view.isShowingArchived())
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text.toString().startsWith(draft.title) }.performClick()
                    val buttons = WorkUiFixtures.descendants(view).filterIsInstance<Button>()
                    assertTrue(buttons.single { it.text == "Start timebox" }.visibility != android.view.View.VISIBLE)
                    buttons.single { it.text == "Restore timebox" }.performClick()
                }
                WorkUiFixtures.await(scenario) { !owner.state.value!!.snapshot.records.single().archived }
                scenario.onActivity {
                    assertEquals(draft.id, owner.state.value!!.snapshot.records.single().id)
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text == "Delete timebox…" }.performClick()
                }
                WorkUiFixtures.idle()
                scenario.onActivity {
                    android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(WorkUiFixtures::descendants)
                        .filterIsInstance<Button>().single { it.text.toString().equals("Cancel", true) }.performClick()
                    assertEquals(1, owner.state.value!!.snapshot.records.size)
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text == "Delete timebox…" }.performClick()
                }
                WorkUiFixtures.idle()
                scenario.onActivity {
                    android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(WorkUiFixtures::descendants)
                        .filterIsInstance<Button>().single { it.text.toString().equals("Delete timebox", true) }.performClick()
                }
                WorkUiFixtures.await(scenario) { owner.state.value!!.snapshot.records.isEmpty() }
                scenario.onActivity {
                    assertEquals(setOf(draft.id), owner.state.value!!.snapshot.deletedIds)
                    assertTrue(WorkUiFixtures.descendants(view).filterIsInstance<Button>().none { it.text.toString().startsWith(draft.title) })
                    owner.state.removeObserver(observer); dialog.dismiss()
                }
            }
        } finally { owner.closeFixture(); check(root.deleteRecursively()) }
    }

    @Test fun reminderSettingsShowAllowedStateAndOnlyOfferMissingAccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "reminder-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val owner = WorkSessionCoordinator(context.applicationContext as Application, root, remindersEnabled = false)
        try {
            WorkUiFixtures.emptyActivity().use { scenario ->
                WorkUiFixtures.await(scenario) { owner.state.value?.loaded == true }
                scenario.onActivity { activity ->
                    var notificationRequests = 0; var alarmRequests = 0
                    val view = WorkSessionsView(activity, owner, { emptyList() }, {}, { notificationRequests++ }, { alarmRequests++ })
                    val ready = owner.state.value!!.copy(reminderAccess = TimeboxReminderAccess(true, true, true, true))
                    view.render(ready)
                    val buttons = { WorkUiFixtures.descendants(view).filterIsInstance<Button>() }
                    assertFalse(buttons().single { it.text == "Notifications allowed" }.isEnabled)
                    assertFalse(buttons().single { it.text == "Background timing allowed" }.isEnabled)
                    assertEquals(0, notificationRequests); assertEquals(0, alarmRequests)
                    view.render(ready.copy(reminderAccess = TimeboxReminderAccess(true, true, false, true)))
                    buttons().single { it.text == "Open notification settings" }.performClick()
                    assertEquals(1, notificationRequests); assertEquals(0, alarmRequests)
                    view.render(ready.copy(reminderAccess = TimeboxReminderAccess(true, true, true, false)))
                    buttons().single { it.text == "Allow background timing" }.performClick()
                    assertEquals(1, notificationRequests); assertEquals(1, alarmRequests)
                    view.dispose()
                }
            }
        } finally { owner.closeFixture(); check(root.deleteRecursively()) }
    }

    @Test fun realWorkWindowHasCompactBackAndAllowsDynamicTextEditors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "work-window-${UUID.randomUUID()}").apply { check(mkdir()) }
        val owner = WorkSessionCoordinator(context.applicationContext as Application, root, remindersEnabled = false)
        try {
            WorkUiFixtures.emptyActivity().use { scenario ->
                WorkUiFixtures.await(scenario) { owner.state.value?.loaded == true }
                lateinit var dialog: WorkDialog
                lateinit var view: WorkSessionsView
                scenario.onActivity { activity ->
                    view = WorkSessionsView(activity, owner, { emptyList() }, {}, {}, {})
                    dialog = WorkDialog(activity, view) {}
                    dialog.show()
                    assertEquals(0, dialog.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text == "New timebox" }.performClick()
                }
                WorkUiFixtures.idle()
                scenario.onActivity { activity ->
                    val title = WorkUiFixtures.descendants(view).filterIsInstance<EditText>().single { it.contentDescription == "Activity title" }
                    assertTrue(title.onCheckIsTextEditor())
                    assertNotNull(title.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                    title.setText("Window draft")
                    val back = WorkUiFixtures.descendants(dialog.window!!.decorView).filterIsInstance<android.widget.ImageButton>().single { it.contentDescription == "Back" }
                    val expected = (48 * activity.resources.displayMetrics.density).toInt()
                    assertEquals(expected, back.width); assertEquals(expected, back.height)
                    back.performClick()
                    assertEquals("list", view.savedPage())
                    assertEquals("Window draft", owner.editorDraft!!.title)
                    view.render(owner.state.value!!)
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text == "Resume draft" }.performClick()
                    assertEquals("Window draft", WorkUiFixtures.descendants(view).filterIsInstance<EditText>().single { it.contentDescription == "Activity title" }.text.toString())
                    dialog.dismiss()
                }
            }
        } finally { owner.closeFixture(); check(root.deleteRecursively()) }
    }

    @Test fun restoreSetupWaitsForAcknowledgedDraftInsteadOfCreatingConflictingIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "work-load-${UUID.randomUUID()}").apply { check(mkdir()) }
        val original = WorkDraft(title = "Acknowledged", minutes = "invalid", outcome = "a\nb")
        WorkSessionStore(root).saveDraft(original)
        val release = java.util.concurrent.CountDownLatch(1)
        val owner = WorkSessionCoordinator(context.applicationContext as Application, root, remindersEnabled = false, clock = {
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); WorkMoment("fixture-boot", 100, 1000)
        })
        try {
            WorkUiFixtures.emptyActivity().use { scenario ->
                lateinit var view: WorkSessionsView
                lateinit var observer: Observer<WorkSessionCoordinator.State>
                scenario.onActivity { activity ->
                    view = WorkSessionsView(activity, owner, { emptyList() }, {}, {}, {})
                    view.restore("setup", null)
                    observer = Observer { view.render(it) }; owner.state.observe(activity, observer)
                    assertTrue(WorkUiFixtures.descendants(view).none { it is EditText })
                    assertNull(owner.editorDraft)
                }
                release.countDown()
                WorkUiFixtures.await(scenario) { owner.state.value?.loaded == true }
                scenario.onActivity {
                    assertEquals(original.id, owner.state.value!!.snapshot.draft!!.id)
                    assertEquals("invalid", WorkUiFixtures.descendants(view).filterIsInstance<EditText>().single { it.contentDescription.toString().startsWith("Original planned") }.text.toString())
                    owner.state.removeObserver(observer); view.dispose()
                }
            }
        } finally { release.countDown(); owner.closeFixture(); check(root.deleteRecursively()) }
    }

    @Test fun productionFormSavesOnceThenExplicitStartAndEndDoNotStartCamera() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "work-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val owner = WorkSessionCoordinator(context.applicationContext as Application, root, remindersEnabled = false)
        var observer: Observer<WorkSessionCoordinator.State>? = null
        try {
            WorkUiFixtures.emptyActivity().use { scenario ->
                WorkUiFixtures.await(scenario) { owner.state.value?.loaded == true }
                lateinit var view: WorkSessionsView
                scenario.onActivity { activity ->
                    view = WorkSessionsView(activity, owner, { emptyList() }, {}, {}, {})
                    observer = Observer { view.render(it) }; owner.state.observe(activity, observer!!)
                    view.render(owner.state.value!!)
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text == "New timebox" }.performClick()
                    val fields = WorkUiFixtures.descendants(view).filterIsInstance<EditText>()
                    fields.single { it.contentDescription == "Activity title" }.setText("Synthetic A")
                    fields.single { it.contentDescription.toString().startsWith("Expected outcome") }.setText("a\nb")
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text == "Save intention" }.performClick()
                }
                WorkUiFixtures.await(scenario) { owner.state.value?.snapshot?.records?.size == 1 }
                WorkUiFixtures.idle()
                scenario.onActivity { activity ->
                    val work = owner.state.value!!.snapshot.records.single()
                    assertEquals(WorkState.PLANNED, work.state); assertEquals("to-be-sorted", work.type)
                    assertNull(owner.state.value!!.snapshot.draft)
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text == "Start timebox" }.performClick()
                    assertFalse(androidx.lifecycle.ViewModelProvider(activity)[CaptureViewModel::class.java].state.value!!.capturing)
                }
                WorkUiFixtures.await(scenario) { owner.state.value!!.snapshot.running != null }
                scenario.onActivity {
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text == "End timebox" }.performClick()
                }
                WorkUiFixtures.await(scenario) { owner.state.value!!.snapshot.records.single().state == WorkState.ENDED }
                scenario.onActivity { observer?.let(owner.state::removeObserver) }
            }
            assertEquals(1, WorkSessionStore(root).load().records.size)
            assertFalse(File(root, "sessions").exists())
        } finally { owner.closeFixture(); check(root.deleteRecursively()) }
    }
    @Test fun rawLiveDraftSurvivesActivityRecreationAndBackRetainsUntilDiscard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "work-recreate-${UUID.randomUUID()}").apply { check(mkdir()) }
        val owner = WorkSessionCoordinator(context.applicationContext as Application, root, remindersEnabled = false)
        try {
            WorkUiFixtures.emptyActivity().use { scenario ->
                WorkUiFixtures.await(scenario) { owner.state.value?.loaded == true }
                var draftId = ""
                scenario.onActivity { activity ->
                    val view = WorkSessionsView(activity, owner, { emptyList() }, {}, {}, {})
                    view.render(owner.state.value!!)
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text == "New timebox" }.performClick()
                    WorkUiFixtures.descendants(view).filterIsInstance<EditText>().single { it.contentDescription == "Activity title" }.setText("  live draft  ")
                    WorkUiFixtures.descendants(view).filterIsInstance<EditText>().single { it.contentDescription.toString().startsWith("Original planned") }.setText("invalid")
                    draftId = owner.editorDraft!!.id
                    val oldType = WorkUiFixtures.descendants(view).filterIsInstance<android.widget.Spinner>().single()
                    val oldListener = oldType.onItemSelectedListener!!
                    assertTrue(view.back())
                    // A queued callback from a detached form must neither crash nor resurrect it.
                    oldListener.onItemSelected(oldType, null, 0, 0)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val view = WorkSessionsView(activity, owner, { emptyList() }, {}, {}, {})
                    view.restore("setup", null)
                    assertEquals("invalid", WorkUiFixtures.descendants(view).filterIsInstance<EditText>().single { it.contentDescription.toString().startsWith("Original planned") }.text.toString())
                    assertEquals(draftId, owner.editorDraft!!.id)
                    WorkUiFixtures.descendants(view).filterIsInstance<Button>().single { it.text == "Discard draft" }.performClick()
                    view.dispose()
                }
                WorkUiFixtures.await(scenario) { owner.state.value?.message == "Draft discarded" }
                assertNull(WorkSessionStore(root).load().draft)
            }
        } finally { owner.closeFixture(); check(root.deleteRecursively()) }
    }
}
