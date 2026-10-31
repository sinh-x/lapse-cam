package com.sinh.timelapse

import java.util.UUID

/** Version-one wire field names. This is policy only; it does not enable sync or run I/O. */
object SyncPolicy {
    val fields: Set<String> = setOf("title", "type", "description", "expectedOutcome")
    enum class Network { OFFLINE, WIFI, MOBILE, OTHER }

    fun validText(value: String, min: Int, max: Int): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                index += 2
            } else {
                if (Character.isLowSurrogate(char)) return false
                index++
            }
        }
        return value.codePointCount(0, value.length) in min..max
    }

    // Unicode White_Space, matching Go strings.TrimSpace rather than JVM-specific blank rules.
    private fun hasTitleContent(value: String): Boolean = value.codePoints().anyMatch { code ->
        !(code in 9..13 || code == 0x20 || code == 0x85 || code == 0xa0 || code == 0x1680 ||
            code in 0x2000..0x200a || code in 0x2028..0x2029 || code == 0x202f || code == 0x205f || code == 0x3000)
    }

    fun validate(field: String, value: String) {
        require(field in fields) { "Execution, timing, archive and media fields are source-owned" }
        when (field) {
            "title" -> require(hasTitleContent(value) && validText(value, 1, 120)) { "Enter 1–120 Unicode characters" }
            "type" -> require(WorkType.entries.any { it.key == value }) { "Choose an existing activity type" }
            else -> require(validText(value, 0, 2000)) { "Use at most 2,000 Unicode characters" }
        }
    }

    /** Descriptive edits are also valid while running; no timer field is accepted here. */
    fun patch(work: WorkSession, values: Map<String, String>): WorkSession {
        require(values.isNotEmpty() && values.size <= fields.size)
        values.forEach(::validate)
        return work.copy(title = values["title"] ?: work.title, type = values["type"] ?: work.type,
            description = values["description"] ?: work.description,
            outcome = if ("expectedOutcome" in values) values.getValue("expectedOutcome").ifEmpty { null } else work.outcome)
    }

    fun mayTransfer(enabled: Boolean, enrolled: Boolean, network: Network, manual: Boolean,
        mobileConfirmed: Boolean = false): Boolean = enabled && enrolled && when (network) {
        Network.WIFI -> true
        Network.MOBILE -> manual && mobileConfirmed
        Network.OFFLINE, Network.OTHER -> false
    }

    /** Fresh planned identity only. Recording links belong to the separate source snapshot. */
    fun copyAsNew(work: WorkSession, createdAt: Long): WorkSession {
        require(createdAt >= 0)
        return WorkSession(UUID.randomUUID().toString(), createdAt, work.title, work.type,
            work.plannedMinutes, work.outcome, description = work.description)
    }

    fun key(source: String, localId: String): String {
        require(UUID.fromString(source).toString() == source && UUID.fromString(localId).toString() == localId)
        return "${source}_$localId"
    }
}
