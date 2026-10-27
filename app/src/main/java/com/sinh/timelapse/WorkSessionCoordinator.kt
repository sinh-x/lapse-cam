package com.sinh.timelapse

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID
import java.util.concurrent.Executors

/** One application-process owner for Activity, alarms and capture transitions. All disk I/O is serial. */
class WorkSessionCoordinator internal constructor(private val app: Application, files: java.io.File = app.filesDir,
    private val remindersEnabled: Boolean = true, reminders: TimeboxReminders? = null,
    private val clock: (() -> WorkMoment)? = null) {
    data class State(val snapshot: WorkSnapshot = WorkSnapshot(), val loaded: Boolean = false,
        val message: String = "Loading work…", val error: String? = null, val reminder: String = "",
        val reminderAccess: TimeboxReminderAccess? = null)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val store = WorkSessionStore(files)
    private val scheduler = reminders ?: TimeboxAlarmScheduler(app)
    private val fixture = files != app.filesDir
    private val mutableState = MutableLiveData(State())
    val state: LiveData<State> = mutableState
    // Main-thread live edits survive recreation independently of acknowledged disk writes.
    var editorDraft: WorkDraft? = null; private set
    private var scheduled: String? = null
    @Volatile private var boot: String? = null
    private var captureSpan: String? = null // only accessed on worker
    private val completedSpans = mutableSetOf<String>()
    private var syncObserver: ((WorkSnapshot, WorkMoment) -> Unit)? = null

    /** Observation only enqueues memory on a different owner; no sync disk/network work here. */
    internal fun observeForSync(observer: (WorkSnapshot, WorkMoment) -> Unit) {
        worker.execute {
            syncObserver = observer
            runCatching { observer(store.load(), now()) }
        }
    }
    internal fun applySharedMetadata(records: List<org.json.JSONObject>) = submit("Shared descriptions saved") {
        var next = store.load()
        for (record in records) {
            val id = record.getString("id")
            val local = next.records.singleOrNull { it.id == id } ?: continue
            next = next.replace(SyncProjection.metadata(local, record.getJSONObject("fields")))
        }
        store.commit(next)
    }
    internal fun copyShared(draft: WorkDraft) = submit("Copied as new local planned timebox") {
        val next = store.load()
        // Do not overwrite an unrelated in-progress draft.
        store.commit(next.copy(records = next.records + draft.validated(now().epoch).copy(title = draft.title, outcome = draft.outcome.ifEmpty { null })))
    }

    init {
        submit("Saved") {
            boot = clock?.invoke()?.boot ?: Settings.Global.getString(app.contentResolver, Settings.Global.BOOT_COUNT)?.let { "boot:$it" }
            checkNotNull(boot) { "Boot identity unavailable; work writes blocked" }
            val old = store.load()
            val recovered = old.copy(spans = old.spans.map { span ->
                if (span.endElapsed == null) span.copy(endElapsed = span.confirmedThrough ?: (span.firstPhoto ?: span.admitted).elapsed, unknownTail = true) else span
            })
            store.commit(TimeboxPolicy.reconcile(recovered, now()))
        }
    }
    fun now(): WorkMoment = clock?.invoke() ?: WorkMoment(checkNotNull(boot) { "Work clock not loaded" }, SystemClock.elapsedRealtime(), System.currentTimeMillis())
    fun beginDraft(copy: WorkSession? = null): WorkDraft {
        check(Looper.myLooper() == Looper.getMainLooper())
        val draft = editorDraft ?: state.value?.snapshot?.draft ?: copy?.copyDraft() ?: WorkDraft()
        edit(draft)
        return draft
    }
    fun edit(draft: WorkDraft) {
        editorDraft = draft
        mutableState.value = (state.value ?: State()).copy(message = "Saving…")
        submit("Saved") { store.saveDraft(draft) }
    }
    fun save(draft: WorkDraft, done: (String) -> Unit) {
        editorDraft = draft
        submit("Saved", after = {
            if (editorDraft?.id == draft.id) editorDraft = null
            done(draft.id)
        }) { store.saveDraft(draft); store.save(draft.id, now().epoch) }
    }
    fun discard(id: String, done: () -> Unit) = submit("Draft discarded", after = {
        if (editorDraft?.id == id) editorDraft = null
        done()
    }) { store.discard(id) }
    fun archive(id: String, archived: Boolean, done: () -> Unit = {}) = submit(if (archived) "Timebox archived" else "Timebox restored", after = done) {
        store.archive(id, archived)
    }
    fun delete(id: String, done: () -> Unit = {}) = submit("Timebox deleted; photos and videos unchanged", after = done) {
        store.delete(id)
    }
    fun start(id: String, replace: String? = null) {
        val event = runCatching(::now).getOrElse { return }
        submit("Saved") { store.commit(TimeboxPolicy.start(store.load(), id, event, replace)) }
    }
    fun end(id: String) {
        val event = runCatching(::now).getOrElse { return }
        submit("Saved") { store.commit(TimeboxPolicy.end(store.load(), id, event)) }
    }
    fun reconcile() {
        val event = runCatching(::now).getOrElse { return }
        submit("Saved", clearError = false) { store.commit(TimeboxPolicy.reconcile(store.load(), event)) }
    }
    fun expire(id: String, done: () -> Unit) = submit("Saved", finished = done) {
        val old = store.load()
        if (old.running?.id == id) store.commit(TimeboxPolicy.reconcile(old, now())) else old
    }
    /** Called once at actual admission, never by preview/navigation. */
    fun captureStarted(recordingId: String, moment: WorkMoment): String {
        val id = UUID.randomUUID().toString()
        submit("Saved") {
            check(captureSpan == null) { "Overlapping capture spans" }
            val next = store.commit(store.load().copy(spans = store.load().spans + RecordingSpan(id, recordingId, moment)))
            captureSpan = id
            next
        }
        return id
    }
    /** Only the first committed frame writes work context, using its shutter anchor, not callback time. */
    fun photoCommitted(id: String, shutter: WorkMoment) = submit("Saved") {
        val old = store.load()
        val span = old.spans.single { it.id == id }
        if (span.firstPhoto != null || id in completedSpans) old else {
            val next = store.commit(old.copy(spans = old.spans.map {
                if (it.id == id) it.copy(firstPhoto = shutter, endElapsed = it.endElapsed?.coerceAtLeast(shutter.elapsed), confirmedThrough = shutter.elapsed) else it
            }))
            completedSpans.add(id)
            next
        }
    }
    fun captureCheckpoint(id: String, moment: WorkMoment) = submit("Saved") {
        val old = store.load()
        store.commit(old.copy(spans = old.spans.map { span ->
            if (span.id == id && span.firstPhoto != null && span.endElapsed == null && moment.boot == span.admitted.boot)
                span.copy(confirmedThrough = maxOf(span.confirmedThrough ?: span.firstPhoto.elapsed, moment.elapsed)) else span
        }))
    }
    internal fun closeFixture() {
        check(fixture) { "The application singleton must remain alive" }
        worker.shutdown()
        check(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
    }
    fun captureStopped(id: String, moment: WorkMoment) = submit("Saved") {
        val old = store.load()
        val span = old.spans.single { it.id == id }
        check(moment.boot == span.admitted.boot)
        val next = store.commit(old.copy(spans = old.spans.map { if (it.id == id && it.endElapsed == null)
            it.copy(endElapsed = moment.elapsed) else it }))
        if (captureSpan == id) captureSpan = null
        next
    }

    private fun submit(message: String, after: () -> Unit = {}, finished: () -> Unit = {}, clearError: Boolean = true, operation: () -> WorkSnapshot) {
        worker.execute {
            try {
                val before = store.load()
                val next = operation()
                if (next != before) runCatching { syncObserver?.invoke(next, now()) }
                val expired = next.records.filter { it.reason == WorkEnd.EXPIRY && before.records.any { old -> old.id == it.id && old.state == WorkState.RUNNING } }
                val running = next.running
                var reminder = ""
                var reminderAccess: TimeboxReminderAccess? = null
                try {
                    if (!remindersEnabled) {
                        reminder = "Injected fixture: OS reminders disabled"
                    } else {
                        if (before.running?.id != running?.id) before.running?.id?.let(scheduler::cancel)
                        if (scheduled != running?.id) { scheduled?.let(scheduler::cancel); scheduled = null }
                        reminderAccess = scheduler.access()
                        reminder = reminderAccess?.availability() ?: scheduler.availability()
                        if (running != null && reminder.isEmpty() && scheduled == null) {
                            scheduler.schedule(running); scheduled = running.id
                        } else if (reminder.isNotEmpty()) { scheduled?.let(scheduler::cancel); scheduled = null }
                        expired.forEach { scheduler.notifyEnded(it.id) }
                    }
                } catch (error: Exception) {
                    reminder = "Background reminder unavailable: ${error.message}. Work is saved; reopen to reconcile expiry."
                    scheduled = null
                }
                main.post {
                    val liveDraft = editorDraft
                    val acknowledged = liveDraft == null || next.draft == liveDraft || next.records.any { it.id == liveDraft.id }
                    val error = if (clearError && acknowledged) null else state.value?.error
                    mutableState.value = State(next, true, if (error != null) "Not saved" else if (message == "Saved" && !acknowledged) "Saving…" else message, error, reminder, reminderAccess)
                    after(); finished()
                }
            } catch (error: Exception) {
                main.post {
                    mutableState.value = (state.value ?: State()).copy(error = "Work error: ${error.message}. Retained data; camera remains independent.", message = "Not saved")
                    finished()
                }
            }
        }
    }
    companion object {
        @Volatile private var instance: WorkSessionCoordinator? = null
        fun get(app: Application): WorkSessionCoordinator = instance ?: synchronized(this) {
            instance ?: WorkSessionCoordinator(app).also { instance = it }
        }
    }
}
