package com.sinh.timelapse

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Serialized session-owner sidecar; never changes the processing index or cleanup receipts. */
internal class OutputObservationStore(files: File) {
    private val target = File(files.canonicalFile, "output-observations.json")
    private var loaded = false
    private var values = linkedMapOf<String, OutputObservation>()
    fun load() {
        if (loaded) return
        val next = linkedMapOf<String, OutputObservation>()
        if (target.exists() || File(target.path + ".bak").exists()) {
            val json = SessionRepository.readJson(target)
            check(json.getInt("version") == 1) { "Output evidence version unavailable; retained" }
            val array = json.getJSONArray("observations")
            for (i in 0 until array.length()) {
                val value = array.getJSONObject(i)
                val identity = SessionOutputIdentity(value.getString("session"), value.getString("uri"),
                    if (value.has("operation")) value.getString("operation") else null)
                check(identity.sessionId.isNotBlank() && identity.uri.isNotBlank())
                val observation = OutputObservation(identity, OutputAvailability.valueOf(value.getString("availability")),
                    SessionRepository.integer(value, "checkedAt"), value.getString("diagnostic"),
                    SessionRepository.integer(value, "lastAttemptAt"), value.getString("lastAttemptDiagnostic"))
                check(next.put(identity.sessionId, observation) == null) { "Duplicate output evidence; retained" }
            }
        }
        values = next; loaded = true
    }
    fun observation(identity: SessionOutputIdentity?): OutputObservation? {
        load()
        return identity?.let { values[it.sessionId]?.takeIf { value -> value.identity == it } }
    }
    /** Caller must supply the current owner-derived identity, not a stale enabled UI button. */
    fun record(result: OutputObservation, current: SessionOutputIdentity?): Boolean {
        load()
        if (current != result.identity) return false
        val before = values[current.sessionId]?.takeIf { it.identity == current }
        val merged = before?.merge(result) ?: result
        val next = LinkedHashMap(values).apply { put(current.sessionId, merged) }
        val array = JSONArray()
        next.values.forEach { value -> array.put(JSONObject().put("session", value.identity.sessionId)
            .put("uri", value.identity.uri).put("operation", value.identity.operation)
            .put("availability", value.availability.name).put("checkedAt", value.checkedAt)
            .put("diagnostic", value.diagnostic).put("lastAttemptAt", value.lastAttemptAt)
            .put("lastAttemptDiagnostic", value.lastAttemptDiagnostic)) }
        SessionRepository.atomicJson(target, JSONObject().put("version", 1).put("observations", array))
        values = next
        return true
    }
}
