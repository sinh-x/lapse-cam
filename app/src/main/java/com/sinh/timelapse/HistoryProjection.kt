package com.sinh.timelapse

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Compact, pure projections: never read media, start work or change stored order. */
data class HistoryDay<T>(val date: LocalDate, val items: List<T>)
data class SessionHistory(val today: HistoryDay<SessionReview>, val dates: List<HistoryDay<SessionReview>>) {
    companion object {
        fun project(values: List<SessionReview>, today: LocalDate, zone: ZoneId, attention: Boolean = false): SessionHistory {
            val unique = values.sortedWith(compareByDescending<SessionReview> { it.startedAt }.thenBy { it.id })
                .distinctBy { it.id }.filter { !attention || it.needsAttention }
            val groups = unique.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            return SessionHistory(HistoryDay(today, groups[today].orEmpty()), groups.keys.filter { it != today }
                .sortedDescending().map { HistoryDay(it, groups.getValue(it)) })
        }
    }
}
data class TimeboxHistory(val unstarted: List<WorkSession>, val today: List<WorkSession>, val dates: List<HistoryDay<WorkSession>>) {
    companion object {
        fun project(records: List<WorkSession>, archived: Boolean, type: String?, sort: WorkSort,
            today: LocalDate, zone: ZoneId): TimeboxHistory {
            require(type == null || WorkType.entries.any { it.key == type })
            val matching = records.distinctBy { it.id }.filter { it.archived == archived && (type == null || it.type == type) }
            fun ordered(values: List<WorkSession>, unstarted: Boolean): List<WorkSession> {
                fun time(value: WorkSession) = if (unstarted) value.createdAt else checkNotNull(value.start).epoch
                val newest = compareByDescending<WorkSession> { time(it) }.thenBy { it.id }
                return values.sortedWith(when (sort) {
                    WorkSort.NEWEST -> newest
                    WorkSort.OLDEST -> compareBy<WorkSession> { time(it) }.thenBy { it.id }
                    WorkSort.TYPE -> compareBy<WorkSession> { WorkType.fromKey(it.type).ordinal }.then(newest)
                })
            }
            val groups = matching.filter { it.start != null }.groupBy { Instant.ofEpochMilli(it.start!!.epoch).atZone(zone).toLocalDate() }
            return TimeboxHistory(ordered(matching.filter { it.start == null }, true), ordered(groups[today].orEmpty(), false),
                groups.keys.filter { it != today }.sortedDescending().map { HistoryDay(it, ordered(groups.getValue(it), false)) })
        }
    }
}
