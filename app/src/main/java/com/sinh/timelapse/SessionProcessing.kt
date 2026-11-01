package com.sinh.timelapse

/** Sampling stage is intentionally NOT a processing stage. Legacy failure provenance stays unknown. */
enum class ProcessingStage(val label: String) {
    EXPORTING("Exporting"), PUBLISHING("Publishing video"), VERIFYING("Verifying video"), CLEANUP("Cleaning up sources"),
    DELETING("Deleting sources"), UNKNOWN("Stage unknown")
}
enum class ProcessingWait { NONE, QUEUED, RETRY_DELAY, FOREGROUND }
data class ActiveProcessing(val sessionId: String, val operation: String, val stage: ProcessingStage,
    val percent: Int? = null) {
    init { require(percent == null || (stage == ProcessingStage.EXPORTING && percent in 0..100)) }
}
data class SessionIssue(val stage: ProcessingStage, val reason: String)
data class SessionProcessing(val active: ActiveProcessing? = null, val wait: ProcessingWait = ProcessingWait.NONE,
    val issues: List<SessionIssue> = emptyList(), val hydrated: Boolean = false)

internal object SessionProcessingProjection {
    fun project(record: SessionRecord, active: ActiveProcessing?, foreground: Boolean): SessionProcessing {
        val own = active?.takeIf { it.sessionId == record.id && it.operation == record.operation }
        val issues = when {
            record.phase == SessionPhase.NEEDS_ATTENTION -> listOf(SessionIssue(
                ProcessingStage.UNKNOWN, record.error.ifBlank { "Processing needs attention; sources retained where available." }))
            record.phase == SessionPhase.DELETING && record.error.isNotEmpty() ->
                listOf(SessionIssue(ProcessingStage.DELETING, record.error))
            else -> emptyList()
        }
        val pending = record.phase in setOf(SessionPhase.QUEUED, SessionPhase.EXPORTING, SessionPhase.VERIFYING,
            SessionPhase.CLEANUP, SessionPhase.DELETING)
        return SessionProcessing(own, when {
            own != null || !pending || issues.isNotEmpty() -> ProcessingWait.NONE
            record.cycle.remainingMillis > 0 -> ProcessingWait.RETRY_DELAY
            !foreground -> ProcessingWait.FOREGROUND
            else -> ProcessingWait.QUEUED
        }, issues, true)
    }
}

/** Observed diagnostic only: never a queue entry, retry reservation or deletion receipt. */
data class ManualOutputFailure(val sessionId: String, val operation: String, val stage: ProcessingStage,
    val observedAt: Long, val reason: String) {
    init { require(sessionId.isNotBlank() && operation.isNotBlank() && observedAt >= 0 && reason.isNotBlank()) }
    val issue: SessionIssue get() = SessionIssue(stage,
        "Last manual export failure (${java.time.Instant.ofEpochMilli(observedAt)}): $reason")
}

enum class OutputAvailability(val label: String) {
    READABLE("Readable at last check"), MISSING("Video missing"), PERMISSION_BLOCKED("Video permission blocked"),
    UNREADABLE("Video unreadable"), UNKNOWN("Video availability unknown");
    val actionable: Boolean get() = this in setOf(MISSING, PERMISSION_BLOCKED, UNREADABLE)
}
data class SessionOutputIdentity(val sessionId: String, val uri: String, val operation: String?)
data class OutputObservation(val identity: SessionOutputIdentity, val availability: OutputAvailability,
    val checkedAt: Long, val diagnostic: String = "", val lastAttemptAt: Long = checkedAt,
    val lastAttemptDiagnostic: String = diagnostic) {
    init { require(checkedAt >= 0 && lastAttemptAt >= checkedAt) }
    /** An inconclusive read never erases prior conclusive evidence. */
    fun merge(next: OutputObservation): OutputObservation {
        require(identity == next.identity)
        if (next.lastAttemptAt < lastAttemptAt) return this
        return if (next.availability == OutputAvailability.UNKNOWN && availability != OutputAvailability.UNKNOWN)
            copy(lastAttemptAt = next.lastAttemptAt, lastAttemptDiagnostic = next.lastAttemptDiagnostic)
        else next
    }
}
