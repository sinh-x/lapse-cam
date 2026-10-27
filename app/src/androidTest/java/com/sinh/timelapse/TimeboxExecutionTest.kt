package com.sinh.timelapse

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Injected clock/restart fixtures, not real reboot, force-stop or alarm-delivery evidence. */
@RunWith(AndroidJUnit4::class)
class TimeboxExecutionTest {
    @Test fun coordinatorSchedulesExactIdentityRejectsStaleCallbacksAndDisclosesDenial() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "alarm-seam-${UUID.randomUUID()}").apply { check(mkdir()) }
        val a = WorkDraft(title = "A", minutes = "1")
        val b = WorkDraft(title = "B")
        val store = WorkSessionStore(root)
        store.saveDraft(a); store.save(a.id, 0); store.saveDraft(b); store.save(b.id, 0)
        val time = java.util.concurrent.atomic.AtomicReference(WorkMoment("fixture-boot", 1000, 10_000))
        val scheduled = java.util.Collections.synchronizedList(mutableListOf<WorkSession>())
        val cancelled = java.util.Collections.synchronizedList(mutableListOf<String>())
        val notified = java.util.Collections.synchronizedList(mutableListOf<String>())
        val denial = java.util.concurrent.atomic.AtomicReference("")
        val reminders = object : TimeboxReminders {
            override fun availability() = denial.get()
            override fun schedule(work: WorkSession) { scheduled.add(work) }
            override fun cancel(id: String) { cancelled.add(id) }
            override fun notifyEnded(id: String) { notified.add(id) }
        }
        val owner = WorkSessionCoordinator(context.applicationContext as android.app.Application, root, reminders = reminders, clock = { time.get() })
        fun await(predicate: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 10_000
            while (!predicate() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(20)
            assertTrue(predicate())
        }
        fun expire(id: String) {
            val done = java.util.concurrent.CountDownLatch(1)
            owner.expire(id) { done.countDown() }
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            await { owner.state.value?.loaded == true }
            owner.start(a.id); await { scheduled.size == 1 }
            assertEquals(a.id, scheduled.single().id); assertEquals(61_000L, scheduled.single().deadline)
            denial.set("Background reminder unavailable: injected denial")
            owner.reconcile(); await { owner.state.value?.reminder?.contains("injected denial") == true }
            assertTrue(cancelled.contains(a.id)); assertEquals(a.id, owner.state.value!!.snapshot.running!!.id)
            denial.set(""); owner.reconcile(); await { scheduled.size == 2 }
            time.set(time.get().copy(elapsed = 61_000))
            expire(UUID.randomUUID().toString())
            assertEquals(a.id, owner.state.value!!.snapshot.running!!.id) // stale callback cannot reconcile another ID
            expire(a.id); expire(a.id)
            assertEquals(listOf(a.id), notified.toList())
            assertEquals(61_000L, owner.state.value!!.snapshot.records.first().endElapsed)
            owner.start(b.id); await { owner.state.value?.snapshot?.running?.id == b.id }
            expire(a.id); assertEquals(b.id, owner.state.value!!.snapshot.running!!.id)
        } finally { owner.closeFixture(); check(root.deleteRecursively()) }
    }

    @Test fun savedEditorNeverAutosavesOrConsumesCreateDraftAndConflictRetainsInput() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "edit-seam-${UUID.randomUUID()}").apply { check(mkdir()) }
        val store = WorkSessionStore(root)
        val saved = WorkDraft(title = "Saved")
        val create = WorkDraft(title = "Create draft", minutes = "bad")
        store.saveDraft(saved); store.save(saved.id, 123); store.saveDraft(create)
        val original = store.load().records.single()
        val owner = WorkSessionCoordinator(context.applicationContext as android.app.Application, root, false,
            clock = { WorkMoment("fixture", 0, 1000) })
        fun main(block: () -> Unit) = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        fun await(predicate: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 10_000
            var ready = false
            while (!ready && android.os.SystemClock.elapsedRealtime() < end) { main { ready = predicate() }; if (!ready) Thread.sleep(20) }
            assertTrue(ready)
        }
        try {
            await { owner.state.value?.loaded == true }
            main {
                owner.beginDraft()
                owner.beginSavedEdit(original)
                owner.editSavedDraft(WorkEditBuffer(original).draft.copy(title = "Unsaved edit"))
                assertEquals(create, owner.editorDraft)
            }
            owner.reconcile(); await { owner.state.value?.snapshot?.draft == create }
            assertEquals(original, WorkSessionStore(root).load().records.single())
            main { owner.cancelSavedEdit(); owner.beginSavedEdit(original); owner.editSavedDraft(WorkEditBuffer(original).draft.copy(title = "Updated")); owner.saveEdited() }
            await { owner.savedEditor == null && owner.state.value?.snapshot?.records?.single()?.title == "Updated" }
            main { assertEquals(create, owner.editorDraft); owner.beginSavedEdit(owner.state.value!!.snapshot.records.single()) }
            owner.archive(saved.id, true)
            await { owner.state.value?.snapshot?.records?.single()?.archived == true }
            main { owner.editSavedDraft(owner.savedEditor!!.draft.copy(title = "Stale")); owner.saveEdited() }
            await { !owner.editSaving && owner.state.value?.error != null }
            main { assertEquals("Stale", owner.savedEditor!!.draft.title); assertEquals(create, owner.editorDraft) }
            assertEquals("Updated", WorkSessionStore(root).load().records.single().title)
            assertEquals(create, WorkSessionStore(root).load().draft)
        } finally { owner.closeFixture(); check(root.deleteRecursively()) }
    }

    @Test fun persistedDeadlineReplacementAndRebootLowerBound() {
        val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "execution-${UUID.randomUUID()}")
        check(root.mkdir())
        try {
            val store = WorkSessionStore(root)
            val a = WorkDraft(title = "A", minutes = "1")
            val b = WorkDraft(title = "B")
            store.saveDraft(a); store.save(a.id, 0); store.saveDraft(b); store.save(b.id, 0)
            val start = WorkMoment("boot:1", 1000, 10_000)
            store.commit(TimeboxPolicy.start(store.load(), a.id, start))
            val reopened = WorkSessionStore(root)
            assertEquals(61_000L, reopened.load().running!!.deadline)
            assertThrows(IllegalArgumentException::class.java) { TimeboxPolicy.start(reopened.load(), b.id, start) }
            assertEquals(a.id, reopened.load().running!!.id)
            reopened.commit(TimeboxPolicy.reconcile(reopened.load(), start.copy(elapsed = 100_000)))
            assertEquals(61_000L, WorkSessionStore(root).load().records.first().endElapsed)
            reopened.commit(TimeboxPolicy.start(reopened.load(), b.id, start.copy(elapsed = 100_000)))
            reopened.commit(TimeboxPolicy.reconcile(reopened.load(), WorkMoment("boot:2", 1, 999_999)))
            val interrupted = WorkSessionStore(root).load().records.last()
            assertEquals(WorkEnd.REBOOT, interrupted.reason); assertTrue(interrupted.uncertain)
            assertEquals(0L, interrupted.confirmedElapsed)
            assertFalse(File(root, "sessions").exists())
        } finally { check(root.deleteRecursively()) }
    }
}
