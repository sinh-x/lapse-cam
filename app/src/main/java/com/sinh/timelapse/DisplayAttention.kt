package com.sinh.timelapse

/** Display provenance, never inferred from diagnostic wording. Unknown impact fails closed. */
enum class CaptureMessageImpact { ROUTINE, INDEPENDENT, CAPTURE_SAFETY, UNKNOWN }

/**
 * Process-memory incident owner. Keys name an affected item + operation, NOT text/attempt/tick.
 * Only explicit success or a deliberate new user cycle resolves an episode. Missing/partial
 * snapshots do not. Pending delivery is coalesced to one bounded counter, including resolved
 * incidents; delivering it never acknowledges, retries or changes the underlying operation.
 * Confined to the owner's main thread. Keep this owner outside the Activity.
 */
class DisplayAttention {
    data class Snapshot(val details: Map<String, String> = emptyMap(), val pending: Int = 0) {
        val count: Int get() = details.size
    }
    private val unresolved = linkedMapOf<String, String>()
    private var pending = 0
    private var cachedSnapshot: Snapshot? = null
    val snapshot: Snapshot get() = cachedSnapshot ?: Snapshot(
        java.util.Collections.unmodifiableMap(LinkedHashMap(unresolved)), pending).also { cachedSnapshot = it }

    fun failure(key: String, detail: String, baseline: Boolean = false) {
        if (unresolved[key] == detail) return
        if (key !in unresolved && !baseline && pending < Int.MAX_VALUE) pending++
        unresolved[key] = detail; cachedSnapshot = null
    }
    fun resolve(key: String) { if (unresolved.remove(key) != null) cachedSnapshot = null }
    fun resolveAll() { if (unresolved.isNotEmpty()) { unresolved.clear(); cachedSnapshot = null } }
    fun deliver(available: Boolean): Int {
        if (!available) return 0
        return pending.also { if (pending != 0) { pending = 0; cachedSnapshot = null } }
    }
}

/** Source classification at the repository boundary, independent of the selected recording ID. */
internal object SessionAttentionPolicy {
    enum class Category { EXPORT, CLEANUP, DELETE, UNSAFE }
    fun category(record: SessionRecord): Category = when (record.phase) {
        SessionPhase.DELETING, SessionPhase.DELETED -> Category.DELETE
        SessionPhase.ACTIVE, SessionPhase.PAUSED, SessionPhase.CLOSING -> Category.UNSAFE
        else -> if (record.cleanupOnly) Category.CLEANUP else Category.EXPORT
    }
    fun key(record: SessionRecord, category: Category = category(record)) = "${record.id}:${record.operation}:$category"
    fun actionable(record: SessionRecord) = record.error.isNotEmpty() || record.phase == SessionPhase.NEEDS_ATTENTION
    fun observe(owner: DisplayAttention, records: List<SessionRecord>, baseline: Boolean = false): Boolean {
        var unsafe = false
        for (record in records) {
            if (record.phase in setOf(SessionPhase.FINISHED, SessionPhase.DELETED, SessionPhase.EMPTY)) {
                Category.entries.forEach { owner.resolve(key(record, it)) }
            } else {
                // A verified receipt proves export success, not cleanup success.
                if (record.cleanupOnly) owner.resolve(key(record, Category.EXPORT))
                if (actionable(record)) {
                    if (category(record) == Category.UNSAFE) unsafe = true
                    else owner.failure(key(record), record.error.ifEmpty { "Session ${record.id} needs attention" }, baseline)
                }
            }
        }
        return unsafe
    }
}
