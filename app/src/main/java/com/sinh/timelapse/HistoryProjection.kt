package com.sinh.timelapse

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Resolve sorted history instants with one conversion per ordinary local day.
 * Any day containing an offset transition uses the direct path (including historic date-line shifts).
 */
internal class HistoryLocalDay(private val zone: ZoneId) {
    private var cached: LocalDate? = null
    private var lower = 0L
    private var upper = 0L
    internal var conversions = 0; private set
    fun date(epochMillis: Long): LocalDate {
        cached?.takeIf { epochMillis >= lower && epochMillis < upper }?.let { return it }
        conversions++
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        cached = null
        try {
            val begin = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            val transition = zone.rules.nextTransition(begin.minusNanos(1))
            if (transition == null || !transition.instant.isBefore(end)) {
                val low = begin.toEpochMilli(); val high = end.toEpochMilli()
                if (epochMillis >= low && epochMillis < high) { lower = low; upper = high; cached = date }
            }
        } catch (_: ArithmeticException) {
            // Extreme Long timestamps remain valid direct projections when day bounds overflow millis.
        } catch (_: java.time.DateTimeException) {
            // A date with unrepresentable boundary data is never memoized.
        }
        return date
    }
}

/** Compact, pure projections: never read media, start work or change stored order. */
data class HistoryDay<T>(val date: LocalDate, val items: List<T>)
data class SessionHistory(val today: HistoryDay<SessionReview>, val dates: List<HistoryDay<SessionReview>>) {
    companion object {
        fun project(values: List<SessionReview>, today: LocalDate, zone: ZoneId, attention: Boolean = false): SessionHistory {
            val unique = values.sortedWith(compareByDescending<SessionReview> { it.startedAt }.thenBy { it.id })
                .distinctBy { it.id }.filter { !attention || it.needsAttention }
            val resolver = HistoryLocalDay(zone)
            val groups = unique.groupBy { resolver.date(it.startedAt) }
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
            val resolver = HistoryLocalDay(zone)
            // Grouping order is independent of row sort; date-contiguous input avoids repeated zone conversion.
            val groups = matching.filter { it.start != null }.sortedByDescending { it.start!!.epoch }
                .groupBy { resolver.date(it.start!!.epoch) }
            return TimeboxHistory(ordered(matching.filter { it.start == null }, true), ordered(groups[today].orEmpty(), false),
                groups.keys.filter { it != today }.sortedDescending().map { HistoryDay(it, ordered(groups.getValue(it), false)) })
        }
    }
}
