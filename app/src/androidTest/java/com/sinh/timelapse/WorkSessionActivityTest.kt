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
