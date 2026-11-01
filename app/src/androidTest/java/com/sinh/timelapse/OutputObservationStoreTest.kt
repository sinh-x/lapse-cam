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
    @Test fun missingSidecarIsCompatibleAndObservationalLoadDoesNotWrite() = fixture { root ->
        assertNull(OutputObservationStore(root).observation(SessionOutputIdentity("session", "content://video/one", null)))
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
