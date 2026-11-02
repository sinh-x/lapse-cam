package com.sinh.timelapse

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Serialized diagnostic sidecar; never changes processing admission, index or cleanup receipts. */
internal class OutputObservationStore(files: File) {
    private val target = File(files.canonicalFile, "output-observations.json")
    private var loaded = false
    var revision: Long = 0; private set
    private var blocked: Exception? = null
    private var values = linkedMapOf<String, OutputObservation>()
    private var failures = linkedMapOf<String, ManualOutputFailure>()
    private fun text(json: JSONObject, key: String) = json.get(key) as? String ?: error("Invalid output evidence text: $key")
    fun load() {
        blocked?.let { throw IllegalStateException("Output evidence write requires reload; bytes retained", it) }
        if (loaded) return
        val next = linkedMapOf<String, OutputObservation>()
        val failed = linkedMapOf<String, ManualOutputFailure>()
        if (target.exists() || File(target.path + ".bak").exists()) {
            val json = SessionRepository.readJson(target)
            val version = json.get("version")
            check(version == 1 || version == 2) { "Output evidence version unavailable; retained" }
            val keys = setOf("version", "observations") + if (version == 2) setOf("manualFailures") else emptySet()
            check(json.keys().asSequence().toSet() == keys) { "Unknown output evidence fields; retained" }
            val array = json.getJSONArray("observations")
            for (i in 0 until array.length()) {
                val value = array.getJSONObject(i)
                val identity = SessionOutputIdentity(text(value, "session"), text(value, "uri"),
                    if (value.has("operation")) text(value, "operation") else null)
                check(identity.sessionId.isNotBlank() && identity.uri.isNotBlank())
                val observation = OutputObservation(identity, OutputAvailability.valueOf(text(value, "availability")),
                    SessionRepository.integer(value, "checkedAt"), text(value, "diagnostic"),
                    SessionRepository.integer(value, "lastAttemptAt"), text(value, "lastAttemptDiagnostic"))
                check(next.put(identity.sessionId, observation) == null) { "Duplicate output evidence; retained" }
            }
            if (version == 2) {
                val arrayFailures = json.getJSONArray("manualFailures")
                for (i in 0 until arrayFailures.length()) {
                    val value = arrayFailures.getJSONObject(i)
                    val failure = ManualOutputFailure(text(value, "session"), text(value, "operation"),
                        ProcessingStage.valueOf(text(value, "stage")), SessionRepository.integer(value, "observedAt"), text(value, "reason"))
                    check(failed.put(failure.sessionId, failure) == null) { "Duplicate manual output evidence; retained" }
                }
            }
        }
        values = next; failures = failed; loaded = true
    }
    fun observation(identity: SessionOutputIdentity?): OutputObservation? {
        load()
        return identity?.let { values[it.sessionId]?.takeIf { value -> value.identity == it } }
    }
    fun manualFailures(): List<ManualOutputFailure> { load(); return failures.values.toList() }
    /** Current operation comes from the existing manual producer, never an enabled UI button. */
    fun recordManualFailure(failure: ManualOutputFailure, currentOperation: String?): Boolean {
        load()
        if (failure.operation != currentOperation) return false
        commit(values, LinkedHashMap(failures).apply { put(failure.sessionId, failure) })
        return true
    }
    /** Only the actual current producer's proven publication success resolves its session diagnostic. */
    fun resolveManualFailure(sessionId: String, completedOperation: String, currentOperation: String?): Boolean {
        load()
        if (completedOperation != currentOperation) return false
        if (sessionId !in failures) return true
        commit(values, LinkedHashMap(failures).apply { remove(sessionId) })
        return true
    }
    /** Caller supplies the current owner-derived identity; lightweight reads never resolve export failures. */
    fun record(result: OutputObservation, current: SessionOutputIdentity?): Boolean {
        load()
        if (current != result.identity) return false
        val before = values[current.sessionId]?.takeIf { it.identity == current }
        val merged = before?.merge(result) ?: result
        commit(LinkedHashMap(values).apply { put(current.sessionId, merged) }, failures)
        return true
    }
    private fun commit(next: LinkedHashMap<String, OutputObservation>, failed: LinkedHashMap<String, ManualOutputFailure>) {
        val observations = JSONArray()
        next.values.forEach { value -> observations.put(JSONObject().put("session", value.identity.sessionId)
            .put("uri", value.identity.uri).put("operation", value.identity.operation)
            .put("availability", value.availability.name).put("checkedAt", value.checkedAt)
            .put("diagnostic", value.diagnostic).put("lastAttemptAt", value.lastAttemptAt)
            .put("lastAttemptDiagnostic", value.lastAttemptDiagnostic)) }
        val manual = JSONArray()
        failed.values.forEach { failure -> manual.put(JSONObject().put("session", failure.sessionId)
            .put("operation", failure.operation).put("stage", failure.stage.name).put("observedAt", failure.observedAt).put("reason", failure.reason)) }
        try { SessionRepository.atomicJson(target, JSONObject().put("version", 2).put("observations", observations).put("manualFailures", manual)) }
        catch (error: Exception) { blocked = error; throw error }
        values = next; failures = failed; revision++
    }
}
