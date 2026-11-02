package com.sinh.timelapse

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OutputObservationStoreTest {
    private fun fixture(body: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile,
            "output-evidence-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { body(root) } finally { check(root.deleteRecursively()) }
    }
    @Test fun persistsAndResolvesOnlyTheRelevantOutputIssue() = fixture { root ->
        val identity = SessionOutputIdentity("session", "content://video/one", "operation")
        val store = OutputObservationStore(root)
        assertTrue(store.record(OutputObservation(identity, OutputAvailability.PERMISSION_BLOCKED, 10, "permission"), identity))
        val reopened = OutputObservationStore(root)
        assertEquals(OutputAvailability.PERMISSION_BLOCKED, reopened.observation(identity)!!.availability)
        assertTrue(reopened.record(OutputObservation(identity, OutputAvailability.UNKNOWN, 20, "unavailable"), identity))
        assertEquals(10L, reopened.observation(identity)!!.checkedAt)
        assertTrue(reopened.record(OutputObservation(identity, OutputAvailability.READABLE, 30), identity))
        assertEquals(OutputAvailability.READABLE, OutputObservationStore(root).observation(identity)!!.availability)
    }
    @Test fun staleCheckCannotResolveOrReplaceNewOutput() = fixture { root ->
        val first = SessionOutputIdentity("session", "content://video/one", "operation")
        val second = first.copy(uri = "content://video/two", operation = "new")
        val store = OutputObservationStore(root)
        assertTrue(store.record(OutputObservation(second, OutputAvailability.MISSING, 20), second))
        assertFalse(store.record(OutputObservation(first, OutputAvailability.READABLE, 30), second))
        assertEquals(OutputAvailability.MISSING, OutputObservationStore(root).observation(second)!!.availability)
        assertNull(store.observation(first))
    }
    @Test fun corruptAndUnknownVersionEvidenceIsRetainedNotReset() = fixture { root ->
        val target = File(root, "output-observations.json")
        listOf("{", "{\"version\":9,\"observations\":[]}").forEach { raw ->
            target.writeText(raw)
            assertThrows(Exception::class.java) { OutputObservationStore(root).load() }
            assertEquals(raw, target.readText())
        }
        assertFalse(File(root, "session-index.json").exists())
    }
    @Test fun manualFailurePersistsAndReadableOutputCannotClearIt() = fixture { root ->
        val identity = SessionOutputIdentity("session", "content://video/one", null)
        val failure = ManualOutputFailure("session", "attempt", ProcessingStage.VERIFYING, 10, "verification failed")
        val store = OutputObservationStore(root)
        assertTrue(store.recordManualFailure(failure, "attempt"))
        val reopened = OutputObservationStore(root)
        assertEquals(failure, reopened.manualFailures().single())
        assertTrue(reopened.record(OutputObservation(identity, OutputAvailability.READABLE, 20), identity))
        assertEquals(failure, OutputObservationStore(root).manualFailures().single())
    }
    @Test fun staleManualResultCannotReplaceOrResolveAndSuccessIsSessionScoped() = fixture { root ->
        val store = OutputObservationStore(root)
        val first = ManualOutputFailure("one", "current", ProcessingStage.EXPORTING, 10, "export failed")
        val other = first.copy(sessionId = "other", operation = "other-attempt")
        assertTrue(store.recordManualFailure(first, "current"))
        assertTrue(store.recordManualFailure(other, "other-attempt"))
        assertFalse(store.recordManualFailure(first.copy(operation = "stale"), "current"))
        assertFalse(store.resolveManualFailure("one", "stale", "current"))
        assertEquals(2, store.manualFailures().size)
        assertTrue(store.resolveManualFailure("one", "new-success", "new-success"))
        assertEquals(listOf(other), OutputObservationStore(root).manualFailures())
    }
    @Test fun v1EvidenceLoadIsBytePreservingAndFailureWriteMigratesWithoutLosingAvailability() = fixture { root ->
        val identity = SessionOutputIdentity("session", "content://video/one", "operation")
        val store = OutputObservationStore(root)
        store.record(OutputObservation(identity, OutputAvailability.MISSING, 10, "missing"), identity)
        val target = File(root, "output-observations.json")
        val legacy = org.json.JSONObject(target.readText()).put("version", 1).apply { remove("manualFailures") }
        target.writeText(legacy.toString()); val bytes = target.readBytes()
        val reopened = OutputObservationStore(root)
        assertEquals(OutputAvailability.MISSING, reopened.observation(identity)!!.availability)
        assertTrue(reopened.manualFailures().isEmpty()); assertArrayEquals(bytes, target.readBytes())
        val failure = ManualOutputFailure("session", "attempt", ProcessingStage.UNKNOWN, 20, "legacy stage unknown")
        reopened.recordManualFailure(failure, "attempt")
        assertEquals(2, org.json.JSONObject(target.readText()).getInt("version"))
        assertEquals(OutputAvailability.MISSING, OutputObservationStore(root).observation(identity)!!.availability)
        assertEquals(failure, OutputObservationStore(root).manualFailures().single())
    }
    @Test fun duplicateManualEvidenceIsRetainedAndBlocksOverwrite() = fixture { root ->
        val store = OutputObservationStore(root)
        val failure = ManualOutputFailure("session", "attempt", ProcessingStage.UNKNOWN, 10, "failure")
        store.recordManualFailure(failure, "attempt")
        val target = File(root, "output-observations.json")
        val json = org.json.JSONObject(target.readText())
        json.getJSONArray("manualFailures").put(json.getJSONArray("manualFailures").getJSONObject(0))
        target.writeText(json.toString()); val bytes = target.readBytes()
        assertThrows(Exception::class.java) { OutputObservationStore(root).recordManualFailure(failure, "attempt") }
        assertArrayEquals(bytes, target.readBytes())
    }
    @Test fun recreatedModelHydratesManualFailureWithoutRetryOrEvidenceMutation() = fixture { root ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = SessionStore(root).create(SamplingConfiguration.Adaptive(30, 15), SessionLens.REAR,
            TimestampAppearance(), SessionSplit.OFF,
            "${System.currentTimeMillis() - 3 * 86_400_000L}-${UUID.randomUUID()}")
        val failure = ManualOutputFailure(session.directory.name, UUID.randomUUID().toString(), ProcessingStage.VERIFYING, 10, "failed verification")
        OutputObservationStore(root).recordManualFailure(failure, failure.operation)
        val target = File(root, "output-observations.json"); val bytes = target.readBytes()
        val ready = java.util.concurrent.CountDownLatch(1)
        val review = java.util.concurrent.atomic.AtomicReference<SessionReview>()
        lateinit var model: CaptureViewModel
        val owner = androidx.lifecycle.ViewModelStore()
        val observer = androidx.lifecycle.Observer<List<SessionReview>> { values ->
            values.singleOrNull { it.id == session.directory.name && it.needsAttention }?.let { review.set(it); ready.countDown() }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            model = CaptureViewModel(context.applicationContext as android.app.Application, root, root, workContext = null)
            owner.put("fixture", model); model.sessions.observeForever(observer)
        }
        try {
            assertTrue(ready.await(15, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(failure.issue, review.get().processing.issues.single())
            assertEquals(session.startedAt, review.get().startedAt)
            assertArrayEquals(bytes, target.readBytes())
            assertFalse(File(root, "session-index.json").exists())
        } finally {
            instrumentation.runOnMainSync { model.sessions.removeObserver(observer); owner.clear() }
            val worker = CaptureViewModel::class.java.getDeclaredField("worker").apply { isAccessible = true }
                .get(model) as java.util.concurrent.ExecutorService
            assertTrue(worker.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test fun missingSidecarIsCompatibleAndObservationalLoadDoesNotWrite() = fixture { root ->
        assertNull(OutputObservationStore(root).observation(SessionOutputIdentity("session", "content://video/one", null)))
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
