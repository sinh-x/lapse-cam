package com.sinh.timelapse

import org.json.JSONArray
import org.json.JSONObject

/** Source snapshots never carry drafts or remote records. Descriptive edits use /patch only. */
internal object SyncProjection {
    fun fields(work: WorkSession) = json("title" to work.title, "type" to work.type,
        "description" to work.description, "expectedOutcome" to (work.outcome ?: ""))
    private fun moment(m: WorkMoment?) = m?.let { json("boot" to it.boot, "elapsed" to it.elapsed, "epoch" to it.epoch) }
    fun observe(state: JSONObject, snapshot: WorkSnapshot, now: WorkMoment) {
        val sources = state.getJSONObject("sources")
        val links = snapshot.links(now)
        for (work in snapshot.records) {
            val source = json("createdAt" to work.createdAt, "plannedMinutes" to work.plannedMinutes,
                "state" to work.state.name, "archived" to work.archived, "deleted" to false,
                "timing" to json("start" to moment(work.start), "deadline" to work.deadline,
                    "endElapsed" to work.endElapsed, "reason" to work.reason?.name, "confirmedElapsed" to work.confirmedElapsed),
                "links" to JSONArray(links.filter { it.workId == work.id }.map { l -> json(
                    "id" to l.id, "workId" to l.workId, "recordingId" to l.recordingId, "spanId" to l.spanId,
                    "boot" to l.boot, "startElapsed" to l.startElapsed, "endElapsed" to l.endElapsed,
                    "startEpoch" to l.startEpoch, "endEpoch" to l.endEpoch, "unknownTail" to l.unknownTail, "open" to l.open) }))
            append(state, sources, work.id, source, fields(work))
        }
        // Keep the last source-owned summary to mark deletion; never fabricate a deleted work record.
        for (id in snapshot.deletedIds) if (sources.has(id)) {
            val old = sources.getJSONObject(id)
            val source = JSONObject(old.getJSONObject("source").toString()).put("deleted", true)
            append(state, sources, id, source, old.getJSONObject("initial"))
        }
    }
    private fun append(state: JSONObject, sources: JSONObject, id: String, source: JSONObject, initial: JSONObject) {
        val previous = sources.optJSONObject(id)
        if (previous?.getJSONObject("source")?.toString() == source.toString()) return
        check(previous?.getJSONObject("source")?.getBoolean("deleted") != true) { "Deleted source cannot be reused" }
        val sequence = Math.addExact(previous?.getLong("sequence") ?: 0, 1)
        val item = json("id" to id, "sequence" to sequence, "source" to source,
            "initial" to (previous?.getJSONObject("initial") ?: initial))
        SyncStore.enqueue(state, "/v1/source", JSONArray().put(item).toString())
        sources.put(id, item)
    }
    fun patch(record: JSONObject, field: String, value: String, resolve: Boolean): String {
        SyncPolicy.validate(field, value)
        val base = record.getJSONObject("fields").getJSONObject(field).getLong("revision")
        return json("device" to record.getString("device"), "id" to record.getString("id"),
            "fields" to json(field to json("value" to value, "base" to base, "resolve" to resolve))).toString()
    }
    fun metadata(work: WorkSession, fields: JSONObject): WorkSession {
        fun value(key: String) = fields.getJSONObject(key).getString("value").also { SyncPolicy.validate(key, it) }
        return work.copy(title = value("title"), type = value("type"), description = value("description"), outcome = value("expectedOutcome").ifEmpty { null })
    }
}
