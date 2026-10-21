package com.sinh.timelapse

import android.net.Uri
import java.io.File
import java.util.concurrent.Executor

/** App-open serial media worker; all journals/cleanup effects remain on [owner]. No codec wait on owner. */
internal class SessionExportQueue(
    private val owner: Executor,
    private val media: Executor,
    private val repository: SessionRepository,
    private val exporter: VideoExporter,
    private val availableBytes: (File) -> Long,
    private val changed: (String?, Int) -> Unit,
    private val encode: (CaptureSession, List<File>, String, (Int) -> Unit) -> Uri = { session, files, operation, progress ->
        exporter.export(session, files, operation, progress)
    },
    private val verify: (CaptureSession, String, Uri?, Int) -> VideoExporter.PublishedVerification = exporter::verifyPublished,
    private val find: (String) -> Uri? = exporter::findPublished,
    private val published: (Uri) -> Unit = {},
) {
    @Volatile private var open = false
    private var clockOpen = false
    private var lastTick: Long? = null
    private var running: String? = null
    private var lastServed: String? = null
    private var manual = false
    val idle: Boolean get() = running == null && !manual
    fun stopAdmission() { open = false; clockOpen = false }
    private fun notifyChanged(id: String?, progress: Int) {
        runCatching { changed(id, progress) }.onFailure { android.util.Log.e("SessionExportQueue", "Review refresh failed", it) }
    }
    fun beginManual(): Boolean {
        if (running != null || manual) return false
        manual = true
        return true
    }
    fun endManual() { manual = false; pump() }
    private val interrupted = repository.records().filter { it.cycle.reserved }.map { it.id }.toMutableSet()

    fun setOpen(value: Boolean, now: Long) {
        open = value // Immediate admission gate, including when the owner is processing a bounded step.
        owner.execute {
            advance(now)
            clockOpen = value
            if (value) pump()
        }
    }

    /** Called on owner by the 1Hz model scheduler; no wall-time retry deadlines. */
    fun tick(now: Long) { advance(now); pump() }
    private fun advance(now: Long) {
        val elapsed = lastTick?.let { (now - it).coerceAtLeast(0) } ?: 0
        lastTick = now
        if (clockOpen && elapsed > 0) repository.records().filter { it.cycle.remainingMillis > 0 }.forEach {
            repository.put(it.withCycle(it.cycle.elapse(elapsed)))
        }
    }

    fun retry(id: String) {
        val record = repository.get(id)
        if (running == id || record.busy || record.phase != SessionPhase.NEEDS_ATTENTION || !record.cycle.exhausted) return
        repository.put(record.withCycle(record.cycle.manualRetry()).copy(
            phase = if (record.cleanupOnly) SessionPhase.CLEANUP else SessionPhase.QUEUED, error = ""))
        notifyChanged(running, 0)
        pump()
    }

    fun pump() {
        if (!open || running != null || manual) return
        val record = SessionQueuePolicy.nextDue(repository.records(), lastServed, { it.id }) {
            it.phase in setOf(SessionPhase.QUEUED, SessionPhase.EXPORTING, SessionPhase.VERIFYING, SessionPhase.CLEANUP) &&
                (it.cycle.due || it.cycle.reserved)
        } ?: return
        lastServed = record.id
        running = record.id
        val recovery = interrupted.remove(record.id)
        val reserved = if (record.cycle.reserved) record else record.withCycle(record.cycle.reserve())
        val work = reserved.copy(phase = if (record.cleanupOnly) SessionPhase.CLEANUP else SessionPhase.EXPORTING)
        try {
            repository.put(work) // Attempt reservation is durable BEFORE any external side effect.
            notifyChanged(running, 0)
            val session = repository.session(work)
            // Full list only for the one export in flight. Cleanup uses hashes/summary, not source JPEGs.
            val frames = if (work.cleanupOnly) emptyList() else repository.closedSelection(work).second
            media.execute {
                val started = System.nanoTime()
                try {
                    val verified = if (work.cleanupOnly) {
                        verify(session, work.operation, Uri.parse(work.output), work.selected)
                    } else {
                        val published = find(work.operation)
                        val uri = if (published != null) published else {
                            check(!recovery) { "Interrupted reserved attempt had no proven publication" }
                            check(ExportSpace.hasHeadroom(availableBytes(session.directory), frames.size.toLong(), session.fps)) {
                                "Insufficient export headroom; photos retained"
                            }
                            encode(session, frames, work.operation) { progress ->
                                owner.execute { if (running == work.id) notifyChanged(running, progress) }
                            }
                        }
                        owner.execute {
                            runCatching { repository.put(work.copy(phase = SessionPhase.VERIFYING)) }
                                .onFailure { android.util.Log.e("SessionExportQueue", "Verification checkpoint failed; receipt still required", it) }
                            notifyChanged(running, 100)
                        }
                        verify(session, work.operation, uri, work.selected)
                    }
                    android.util.Log.i("SessionExportQueue", "operation=${work.operation} cleanupOnly=${work.cleanupOnly} mediaStepMillis=${(System.nanoTime() - started) / 1_000_000}")
                    owner.execute {
                        try {
                            if (work.cleanupOnly) {
                                // A crash consumes a cleanup slot unless the complete deletion can be proven.
                                if (recovery && !repository.cleanupComplete(work)) error("Interrupted partial cleanup; reserved attempt consumed")
                                val done = repository.cleanup(work, verified)
                                if (done) {
                                    repository.put(work.copy(phase = SessionPhase.FINISHED, error = "",
                                        cleanupCycle = work.cleanupCycle.copy(reserved = false)))
                                    repository.compact(work.id)
                                }
                            } else {
                                repository.put(repository.receipt(work, verified))
                                runCatching { published(verified.uri) }.onFailure {
                                    android.util.Log.e("SessionExportQueue", "Last-video preference refresh failed; receipt retained", it)
                                }
                            }
                            running = null
                            notifyChanged(null, 0)
                            pump()
                        } catch (error: Exception) { failed(work, error) }
                    }
                } catch (error: Exception) { owner.execute { failed(work, error) } }
            }
        } catch (error: Exception) { failed(work, error) }
    }

    private fun failed(work: SessionRecord, error: Exception) {
        val failed = work.withCycle(work.cycle.failed())
        try {
            repository.put(failed.copy(phase = if (failed.cycle.exhausted) SessionPhase.NEEDS_ATTENTION
                else if (work.cleanupOnly) SessionPhase.CLEANUP else SessionPhase.QUEUED,
                error = error.message ?: error.javaClass.simpleName))
        } finally {
            running = null
            notifyChanged(null, 0)
        }
        // Defer even preflight failures; a large invalid backlog must not recurse on the owner stack.
        if (open) owner.execute { pump() }
    }
}
