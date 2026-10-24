package com.sinh.timelapse

import java.util.UUID

/** Work identity and clocks are deliberately independent of CaptureSession and MP4 duration. */
enum class WorkType(val key: String, val label: String) {
    WORK("work", "Work"), STUDY("study", "Study"), PERSONAL("personal", "Personal"),
    SOCIAL("social", "Social"), UNSORTED("to-be-sorted", "To-be-sorted");
    companion object { fun fromKey(key: String) = entries.single { it.key == key } }
}

data class WorkDraft(
    val id: String = UUID.randomUUID().toString(), val title: String = "", val type: String? = null,
    val minutes: String = "25", val outcome: String = "",
) {
    fun validated(createdAt: Long): WorkSession {
        val errors = errors()
        require(errors.isEmpty()) { errors.values.joinToString("; ") }
        return WorkSession(id, createdAt, title.trim(), type ?: WorkType.UNSORTED.key, minutes.toInt(),
            outcome.takeUnless { it.isBlank() })
    }
    fun errors(): Map<String, String> = buildMap {
        if (title.trim().codePointCount(0, title.trim().length) !in 1..120) put("title", "Enter 1–120 characters")
        if (outcome.codePointCount(0, outcome.length) > 2000) put("outcome", "Use at most 2,000 characters")
        if (!minutes.matches(Regex("[0-9]+")) || minutes.toIntOrNull() !in 1..1440)
            put("minutes", "Enter whole minutes from 1 to 1,440")
        if (type != null && WorkType.entries.none { it.key == type }) put("type", "Choose a supported activity type")
    }
}

data class WorkMoment(val boot: String, val elapsed: Long, val epoch: Long) {
    init { require(boot.isNotBlank() && elapsed >= 0 && epoch >= 0) }
}

enum class WorkState { PLANNED, RUNNING, ENDED }
enum class WorkEnd { USER, EXPIRY, REBOOT }

data class WorkSession(
    val id: String, val createdAt: Long, val title: String, val type: String, val plannedMinutes: Int,
    val outcome: String? = null, val state: WorkState = WorkState.PLANNED,
    val start: WorkMoment? = null, val deadline: Long? = null, val endElapsed: Long? = null,
    val reason: WorkEnd? = null, val confirmedElapsed: Long = 0, val archived: Boolean = false,
) {
    val uncertain: Boolean get() = reason == WorkEnd.REBOOT
    val plannedDurationLabel: String get() = "$plannedMinutes ${if (plannedMinutes == 1) "minute" else "minutes"}"
    fun remaining(now: WorkMoment): Long = if (state == WorkState.RUNNING && start?.boot == now.boot)
        (checkNotNull(deadline) - now.elapsed).coerceAtLeast(0) else 0
    fun copyDraft() = WorkDraft(title = title, type = type, minutes = plannedMinutes.toString(), outcome = outcome ?: "")
}

/** One durable span ID per capture admission. No photo means no coverage. */
data class RecordingSpan(
    val id: String, val recordingId: String, val admitted: WorkMoment,
    val firstPhoto: WorkMoment? = null, val endElapsed: Long? = null, val unknownTail: Boolean = false,
    val confirmedThrough: Long? = null,
)
data class RecordingLink(val id: String, val workId: String, val recordingId: String, val spanId: String,
    val boot: String, val startElapsed: Long, val endElapsed: Long, val startEpoch: Long, val endEpoch: Long,
    val unknownTail: Boolean, val open: Boolean = false)

data class WorkSnapshot(val draft: WorkDraft? = null, val records: List<WorkSession> = emptyList(),
    val spans: List<RecordingSpan> = emptyList(), val deletedIds: Set<String> = emptySet()) {
    val running: WorkSession? get() = records.singleOrNull { it.state == WorkState.RUNNING }
    fun replace(record: WorkSession) = copy(records = records.map { if (it.id == record.id) record else it })

    /** Half-open, same-boot capture-time context, NOT an MP4 seek mapping. */
    fun links(now: WorkMoment): List<RecordingLink> = records.flatMap { work ->
        val start = work.start ?: return@flatMap emptyList()
        val workEnd = work.endElapsed ?: if (work.state == WorkState.RUNNING && start.boot == now.boot)
            minOf(now.elapsed, checkNotNull(work.deadline)) else return@flatMap emptyList()
        spans.mapNotNull { span ->
            val first = span.firstPhoto ?: return@mapNotNull null
            if (first.boot != start.boot) return@mapNotNull null
            val spanEnd = span.endElapsed ?: if (now.boot == first.boot) now.elapsed else first.elapsed
            val low = maxOf(start.elapsed, first.elapsed)
            val high = minOf(workEnd, spanEnd)
            if (high <= low) return@mapNotNull null
            RecordingLink("${work.id}:${span.id}", work.id, span.recordingId, span.id, first.boot, low, high,
                Math.addExact(first.epoch, low - first.elapsed), Math.addExact(first.epoch, high - first.elapsed), span.unknownTail, span.endElapsed == null)
        }
    }
}

/** Explicit user-managed metadata cleanup; never owns media or capture controls. */
object WorkManagementPolicy {
    fun archive(snapshot: WorkSnapshot, id: String, archived: Boolean): WorkSnapshot {
        val work = snapshot.records.single { it.id == id }
        require(work.state != WorkState.RUNNING) { "End the timebox before archiving or restoring it" }
        return snapshot.replace(work.copy(archived = archived))
    }
    fun delete(snapshot: WorkSnapshot, id: String): WorkSnapshot {
        if (id in snapshot.deletedIds) return snapshot
        val work = snapshot.records.single { it.id == id }
        require(work.state != WorkState.RUNNING) { "End the timebox before deleting it" }
        // Spans remain: other timeboxes may overlap the exact same recording interval.
        return snapshot.copy(records = snapshot.records.filterNot { it.id == id }, deletedIds = snapshot.deletedIds + id)
    }
}

object TimeboxPolicy {
    fun reconcile(snapshot: WorkSnapshot, now: WorkMoment): WorkSnapshot {
        val work = snapshot.running ?: return snapshot
        val start = checkNotNull(work.start)
        return when {
            start.boot != now.boot -> snapshot.replace(work.copy(state = WorkState.ENDED, reason = WorkEnd.REBOOT,
                endElapsed = Math.addExact(start.elapsed, work.confirmedElapsed)))
            now.elapsed >= checkNotNull(work.deadline) -> snapshot.replace(work.copy(state = WorkState.ENDED,
                reason = WorkEnd.EXPIRY, endElapsed = work.deadline, confirmedElapsed = work.plannedMinutes * 60_000L))
            else -> {
                require(now.elapsed >= start.elapsed)
                val elapsed = now.elapsed - start.elapsed
                // A bounded checkpoint, not a per-frame write; reboot uses only this lower bound.
                if (elapsed - work.confirmedElapsed >= 30_000) snapshot.replace(work.copy(confirmedElapsed = elapsed)) else snapshot
            }
        }
    }
    fun start(snapshot: WorkSnapshot, id: String, now: WorkMoment, replaceRunning: String? = null): WorkSnapshot {
        var next = reconcile(snapshot, now)
        val selected = next.records.single { it.id == id }
        if (selected.state == WorkState.RUNNING) return next
        require(!selected.archived) { "Restore this timebox before starting it" }
        require(selected.state == WorkState.PLANNED) { "Ended work cannot resume; copy as new" }
        next.running?.let { running ->
            require(replaceRunning == running.id) { "Confirm End current / Start new" }
            next = end(next, running.id, now)
        }
        return next.replace(selected.copy(state = WorkState.RUNNING, start = now,
            deadline = Math.addExact(now.elapsed, Math.multiplyExact(selected.plannedMinutes.toLong(), 60_000))))
    }
    fun end(snapshot: WorkSnapshot, id: String, now: WorkMoment): WorkSnapshot {
        val next = reconcile(snapshot, now)
        val work = next.records.single { it.id == id }
        if (work.state != WorkState.RUNNING) return next
        require(work.start!!.boot == now.boot && now.elapsed >= work.start.elapsed)
        return next.replace(work.copy(state = WorkState.ENDED, reason = WorkEnd.USER, endElapsed = now.elapsed,
            confirmedElapsed = now.elapsed - work.start.elapsed))
    }
}
