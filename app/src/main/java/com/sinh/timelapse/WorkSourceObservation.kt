package com.sinh.timelapse

/** Exact inputs to source projection, not durable sync state or a mutation/attempt queue. */
internal data class WorkSourceObservation private constructor(
    val records: List<WorkSession>, val deletedIds: Set<String>, val links: List<RecordingLink>,
) {
    companion object {
        fun of(snapshot: WorkSnapshot, now: WorkMoment) = WorkSourceObservation(
            snapshot.records.toList(), snapshot.deletedIds.toSet(), snapshot.links(now))
    }
}
