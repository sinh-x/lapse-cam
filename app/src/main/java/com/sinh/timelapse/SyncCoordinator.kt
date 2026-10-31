package com.sinh.timelapse

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/** Independent serial network/storage owner. Work/capture owners only post immutable observations. */
internal class SyncCoordinator private constructor(private val app: Application) {
    data class State(val loaded: Boolean = false, val device: String = "", val endpoint: String = "",
        val enabled: Boolean = false, val busy: Boolean = false, val pending: Int = 0, val conflicts: Int = 0,
        val lastSuccess: Long = 0, val lowStorage: Boolean = false, val message: String = "Loading private sync…",
        val history: JSONObject = json("records" to JSONArray(), "videos" to JSONArray()),
        val uploads: JSONObject = JSONObject(), val requests: JSONArray = JSONArray())
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableLiveData(State())
    val state: LiveData<State> = mutable
    private val store by lazy { SyncStore(app.filesDir, mayInitialize = { !SyncCredential.exists() }) }
    @Volatile private var allowed = true
    @Volatile private var stopped = false
    private var busy = false
    private var mediaOwnerReady = false
    private var associationHints: String? = null
    private var message = "Disabled; local camera and timers work offline"
    init {
        worker.execute { safely {
            val s = store.load(); allowed = s.getBoolean("enabled"); publish()
            SyncJobService.schedule(app, allowed)
        } }
        WorkSessionCoordinator.get(app).observeForSync { snapshot, now ->
            worker.execute { safely { store.update { SyncProjection.observe(it, snapshot, now) }; publish() } }
        }
    }
    private fun safely(operation: () -> Unit) {
        try { operation() } catch (_: Exception) {
            // Never expose endpoint response bodies, secrets, file paths or note text in logs/errors.
            message = "Sync storage/credentials unavailable; retained data. Camera and timers remain independent."
            main.post { mutable.value = (mutable.value ?: State()).copy(message = message, busy = false) }
        }
    }
    private fun publish() {
        val s = store.load()
        val history = s.getJSONObject("history")
        val conflicts = history.getJSONArray("records").objects().sumOf { record ->
            record.getJSONObject("fields").strings().count { field ->
                record.getJSONObject("fields").getJSONObject(field).optJSONArray("variants")?.length()?.let { it > 0 } == true
            }
        }
        val exports = s.getJSONObject("exports")
        val pendingVideos = exports.strings().count { !exports.getJSONObject(it).getBoolean("complete") }
        val value = State(true, s.getString("device"), s.getString("endpoint"), s.getBoolean("enabled"), busy,
            s.getJSONArray("pending").objects().count { !it.optBoolean("rejected") } + pendingVideos, conflicts, s.getLong("lastSuccess"), s.getBoolean("lowStorage"), message,
            JSONObject(history.toString()), JSONObject(exports.toString()), JSONArray(s.getJSONArray("pending").toString()))
        main.post { mutable.value = value }
    }
    fun backfillAssociations(reviews: List<SessionReview>) {
        val hints = reviews.filter { it.output != null }.map { Triple(it.id, it.output!!, it.verifiedOperation) }
        worker.execute { safely {
            val signature = hints.toString()
            if (associationHints == signature) return@safely
            val queued = store.load().getJSONObject("exports")
            val outputs = SyncMediaInventory.published(app).associateBy { it.uri.toString() }
            hints.forEach { (recording, uri, operation) ->
                outputs[uri]?.let { output ->
                    check(operation == null || output.id == operation)
                    if (!queued.has(output.id)) SyncMediaInventory.associate(app.filesDir, output.id, recording)
                }
            }
            associationHints = signature; mediaOwnerReady = true
        } }
    }
    fun disable() {
        allowed = false // Immediate gate; queued owner command cannot wait behind a slow network call.
        worker.execute { safely {
            store.update { it.put("enabled", false) }; SyncJobService.schedule(app, false)
            message = "Disabled; pending work retained"; publish()
        } }
    }
    fun enroll(endpoint: String, token: String, finished: (Boolean, String) -> Unit = { _, _ -> }) {
        worker.execute {
            var enrolled = false
            try {
                busy = true
                message = "Checking enrollment with the private server… Please wait for the result."
                publish()
                val base = SyncEnrollment.endpoint(endpoint)
                val privateToken = SyncEnrollment.token(token)
                val s = store.load()
                SyncEnrollment.checkArchive(s.getString("endpoint"), base)
                // Explicit enrollment authorizes this credential check, not an unverified upload.
                SyncEnrollment.checkNetwork(network())
                SyncEnrollment.checkIdentity(s.getString("device")) {
                    SyncTransport(base, privateToken).get("/v1/identity").getString("device")
                }
                val encrypted = SyncCredential.encrypt(privateToken)
                store.update { it.put("endpoint", base).put("credential", encrypted).put("enabled", true) }
                allowed = true; SyncJobService.schedule(app, true)
                enrolled = true
                message = "Enrollment succeeded. Sync is enabled; background uploads use Wi-Fi. Tap Sync now / refresh to check progress."
            } catch (error: Exception) {
                message = SyncEnrollment.error(error)
            } finally {
                busy = false
                val result = message
                try { publish() } catch (_: Exception) {
                    // Even an unreadable store must not hide the enrollment result.
                    main.post { mutable.value = (mutable.value ?: State()).copy(message = result, busy = false) }
                }
                val success = enrolled
                main.post { finished(success, result) }
            }
        }
    }
    fun patch(record: JSONObject, field: String, value: String, resolve: Boolean) {
        val request = SyncProjection.patch(record, field, value, resolve)
        worker.execute { safely {
            store.enqueue("/v1/patch", request)
            message = "Descriptive edit pending; original base and proposed value retained"
            publish()
        } }
    }
    fun network(): SyncPolicy.Network {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return SyncPolicy.Network.OFFLINE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> SyncPolicy.Network.MOBILE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> SyncPolicy.Network.WIFI
            else -> SyncPolicy.Network.OTHER
        }
    }
    fun cancelAttempt() { stopped = true }
    fun sync(manual: Boolean, mobileConfirmed: Boolean = false, shouldStop: () -> Boolean = { false }, done: (Boolean) -> Unit = {}) {
        worker.execute {
            var retry = false
            try {
                stopped = false
                val s = store.load()
                val endpoint = s.getString("endpoint")
                fun eligibility() {
                    check(!stopped && !shouldStop() && allowed && SyncPolicy.mayTransfer(s.getBoolean("enabled"), endpoint.isNotEmpty(), network(), manual, mobileConfirmed))
                }
                eligibility(); busy = true; message = "Syncing metadata and verified video chunks…"; publish()
                val client = SyncTransport(endpoint, SyncCredential.decrypt(s.getString("credential")), ::eligibility)
                var metadataError = false
                // Failed metadata never prevents independent video progress or a fresh read-only history fetch.
                try { drainMetadata(client) } catch (_: Exception) { metadataError = true; retry = true }
                try { refresh(client) } catch (_: Exception) { retry = true }
                try {
                    discover()
                    val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
                    val verifiedOffsets = mutableMapOf<String, Long>()
                    do {
                        val progressed = transferRound(client, ::eligibility, verifiedOffsets)
                        if (!progressed) break
                        // Metadata posted during this attempt can run at the next serialized attempt.
                    } while (android.os.SystemClock.elapsedRealtime() < deadline)
                } catch (_: Exception) { retry = true }
                try { refresh(client) } catch (_: Exception) { retry = true }
                message = if (metadataError) "Metadata pending/error; proposals retained. Refresh before resolving a stale conflict."
                    else if (retry) "Transfer/fetch pending; verified progress retained. Check authorization, server, storage and network."
                    else "Attempt finished. Android may defer background work; shared running states are last-synced summaries."
                if (!retry) store.update { it.put("lastSuccess", System.currentTimeMillis()) }
            } catch (_: Exception) {
                retry = allowed
                message = "No eligible connection, enrollment or storage; pending data retained. Background requires Wi-Fi."
            } finally {
                busy = false
                safely { publish() }
                main.post { done(retry) }
            }
        }
    }
    private fun drainMetadata(client: SyncTransport) {
        // Bound one attempt; leave remaining durable work for the next attempt.
        repeat(100) {
            val queue = store.load().getJSONArray("pending").objects()
            val item = queue.firstOrNull { !it.optBoolean("rejected") } ?: return
            try {
                client.request("POST", item.getString("path"), item.getString("body").toByteArray(), item.getString("mutation"))
                // Only a 2xx durable receipt removes this exact request. Response is historical, not a replica.
                store.update { it.put("pending", JSONArray(queue.filterNot { q -> q.getString("mutation") == item.getString("mutation") })) }
            } catch (e: SyncTransport.HttpError) {
                if (e.status == 400 && item.getString("path") == "/v1/patch") {
                    // A stale resolution is not retryable. Preserve its exact proposal/base, then allow
                    // unrelated edits and a NEW explicit resolution against refreshed revisions.
                    item.put("rejected", true).put("status", e.status)
                    store.update { it.put("pending", JSONArray(queue)) }
                } else throw e
            }
        }
    }
    private fun refresh(client: SyncTransport) {
        val records = JSONArray(); val videos = JSONArray(); var cursor = ""
        val seen = mutableSetOf<String>()
        do {
            val page = client.get("/v1/history?limit=50" + if (cursor.isEmpty()) "" else "&after=${java.net.URLEncoder.encode(cursor, "UTF-8")}")
            page.getJSONArray("records").objects().forEach { records.put(it) }
            page.getJSONArray("videos").objects().forEach { videos.put(it) }
            cursor = page.getString("next")
            check(cursor.isEmpty() || seen.add(cursor)) { "Pagination loop" }
        } while (cursor.isNotEmpty())
        val status = client.get("/v1/status")
        store.update { it.put("history", json("records" to records, "videos" to videos)).put("lowStorage", status.getBoolean("lowStorage")) }
        val s = store.load()
        // Do not project a stale acknowledged value over a pending local source proposal.
        val pendingSourceIds = s.getJSONArray("pending").objects().filter { it.getString("path") == "/v1/patch" }
            .map { JSONObject(it.getString("body")) }.filter { it.getString("device") == s.getString("device") }.map { it.getString("id") }.toSet()
        WorkSessionCoordinator.get(app).applySharedMetadata(records.objects().filter {
            it.getString("device") == s.getString("device") && it.getString("id") !in pendingSourceIds
        })
        publish()
    }
    private fun discover() {
        val outputs = SyncMediaInventory.published(app)
        for (output in outputs) {
            if (output.recording.isEmpty() && !mediaOwnerReady) continue // wait for exact legacy owner hints after app-open recovery
            if (store.load().getJSONObject("exports").has(output.id)) continue
            val description = SyncMediaInventory.describe(app, output)
            store.update { it.getJSONObject("exports").put(output.id, json("description" to description,
                "create" to UUID.randomUUID().toString(), "finish" to UUID.randomUUID().toString(),
                "missingMutation" to UUID.randomUUID().toString(), "complete" to false, "missing" to false, "offset" to 0, "error" to "")) }
        }
    }
    private fun transferRound(client: SyncTransport, eligible: () -> Unit, verifiedOffsets: MutableMap<String, Long>): Boolean {
        val outputs = SyncMediaInventory.published(app).associateBy { it.id }
        val exports = store.load().getJSONObject("exports")
        var progressed = false
        var failure: Exception? = null
        for (id in exports.strings()) {
            val item = exports.getJSONObject(id)
            if (item.getBoolean("missing")) continue
            if (item.getBoolean("complete") && outputs.containsKey(id)) continue
            try {
                eligible()
                val output = outputs[id]
                val desc = item.getJSONObject("description")
                if (output != null && id !in verifiedOffsets) {
                    // Revalidate bytes before trusting a prior manifest's progress.
                    val current = SyncMediaInventory.describe(app, output)
                    check(current.toString() == desc.toString()) { "Source bytes changed; no new identity allocated" }
                }
                val result = SyncUpload.step(client, id, item, store.load().getString("device"),
                    output?.let { source -> { checkNotNull(app.contentResolver.openInputStream(source.uri)) } },
                    verifiedOffsets[id]) { change -> markExport(id, change) }
                result.verifiedOffset?.let { verifiedOffsets[id] = it }
                progressed = progressed || result.progressed
            } catch (e: Exception) {
                failure = e
                markExport(id) { it.put("error", "Pending transfer failed; retained offset/identity. Check source, authorization, network and server storage.") }
            }
        }
        if (!progressed && failure != null) throw failure
        publish()
        return progressed
    }
    private fun markExport(id: String, change: (JSONObject) -> Unit) = store.update { change(it.getJSONObject("exports").getJSONObject(id)) }
    fun play(activity: android.app.Activity, video: JSONObject, mobileConfirmed: Boolean) {
        worker.execute { safely {
            val s = store.load()
            val device = video.getString("device"); val id = video.getString("id")
            SyncPolicy.key(device, id)
            fun eligible() { check(allowed && SyncPolicy.mayTransfer(s.getBoolean("enabled"), true, network(), true, mobileConfirmed)) }
            eligible()
            val client = SyncTransport(s.getString("endpoint"), SyncCredential.decrypt(s.getString("credential")), ::eligible)
            main.post { if (!activity.isDestroyed) SyncPlayback.show(activity, client, "/v1/media/$device/$id", video.getLong("size")) }
        } }
    }
    /** Explicit user-chosen destination; never populate a media mirror during history fetch. */
    fun download(video: JSONObject, destination: android.net.Uri, mobileConfirmed: Boolean) {
        worker.execute { safely {
            val s = store.load()
            val expected = video.getString("sha256"); val size = video.getLong("size")
            val id = video.getString("id"); val device = video.getString("device")
            SyncPolicy.key(device, id)
            fun eligible() { check(allowed && SyncPolicy.mayTransfer(true, true, network(), true, mobileConfirmed)) }
            val client = SyncTransport(s.getString("endpoint"), SyncCredential.decrypt(s.getString("credential")), ::eligible)
            // Stage privately before publishing to the explicit SAF create-document URI.
            val temp = java.io.File.createTempFile("sync-download-", ".part", app.cacheDir)
            try {
                val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                temp.outputStream().use { out ->
                    client.exchange("GET", "/v1/media/$device/$id", null, null, emptyMap(), {}) { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { eligible(); val n = input.read(buffer); if (n < 0) break
                            count += n; check(count <= size); digest.update(buffer, 0, n); out.write(buffer, 0, n) }
                    }
                }
                check(count == size && digest.digest().joinToString("") { "%02x".format(it) } == expected)
                checkNotNull(app.contentResolver.openOutputStream(destination, "w")).use { target -> temp.inputStream().use { it.copyTo(target) } }
                message = "Verified download saved to the document you selected"
            } catch (_: Exception) { message = "Download not completed; private staging removed. Inspect the selected document before retrying." }
            finally { temp.delete(); publish() }
        } }
    }
    companion object {
        @Volatile private var instance: SyncCoordinator? = null
        fun get(app: Application): SyncCoordinator = instance ?: synchronized(this) { instance ?: SyncCoordinator(app).also { instance = it } }
    }
}
