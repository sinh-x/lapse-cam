package com.sinh.timelapse

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

class SessionExportQueueTest {
    private val direct = Executor { it.run() }
    private class Pending : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() = tasks.removeFirst().run()
    }
    private fun fixture(body: (Context, File, SessionStore, SessionRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "queue-${UUID.randomUUID()}").canonicalFile.apply { check(mkdirs()) }
        try {
            val store = SessionStore(root)
            val repo = SessionRepository(root, store)
            repo.recover()
            body(context, root, store, repo)
        } finally { check(root.deleteRecursively()) }
    }
    private fun closed(store: SessionStore, repo: SessionRepository): CaptureSession {
        val session = store.create(SamplingConfiguration.Adaptive(5, 15), SessionLens.REAR, TimestampAppearance(), SessionSplit.DEFAULT)
        repo.register(session)
        add(store, session)
        repo.close(session, false)
        return session
    }
    private fun add(store: SessionStore, session: CaptureSession) {
        val image = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
        try { store.completeFrame(session, store.beginFrame(session, 1, store.snapshot(session).count * 500L), image) }
        finally { image.recycle() }
    }
    private fun photos(store: SessionStore, session: CaptureSession): Map<String, String> =
        store.frames(session).associate { it.name to SessionRepository.sha256(it) }

    private fun proof(session: CaptureSession, operation: String, uri: Uri?, count: Int) =
        VideoExporter.PublishedVerification(uri ?: Uri.parse("content://fixture/$operation"), "0".repeat(64), count, session.fps,
            (session.configuration as SamplingConfiguration.Adaptive).maxSeconds)

    @Test fun encodingNeverBlocksSuccessorSavesAndOnlyOneExportRuns() = fixture { context, root, store, repo ->
        val old = closed(store, repo)
        val media = Pending()
        var encodes = 0
        val queue = SessionExportQueue(direct, media, repo, VideoExporter(context, root, root), { Long.MAX_VALUE }, { _, _ -> },
            encode = { _, _, operation, _ -> encodes++; Uri.parse("content://fixture/$operation") }, verify = ::proof, find = { null })
        queue.setOpen(true, 0)
        assertEquals(1, media.tasks.size)
        val next = store.create(SamplingConfiguration.Adaptive(), SessionLens.FRONT, TimestampAppearance(), SessionSplit.DEFAULT)
        repo.register(next)
        add(store, next)
        assertEquals(1, store.snapshot(next).count)
        repeat(5) { queue.tick(0) }
        assertEquals(1, media.tasks.size)
        media.runNext() // Export verifies and journals receipt; only now is cleanup eligible.
        assertEquals(1, encodes)
        assertTrue(File(old.directory, "receipt.json").isFile)
        assertEquals(1, store.frames(old).size)
        while (media.tasks.isNotEmpty()) media.runNext()
        assertEquals(SessionPhase.FINISHED, repo.get(old.directory.name).phase)
        assertEquals(0, store.frames(old).size)
        assertEquals(1, store.frames(next).size)
    }

    @Test fun failureIsFairBoundedDurableAndManualRetryStartsOneCycle() = fixture { context, root, store, repo ->
        val first = closed(store, repo)
        val second = closed(store, repo)
        var encodes = 0
        fun queue(repository: SessionRepository) = SessionExportQueue(direct, direct, repository, VideoExporter(context, root, root),
            { Long.MAX_VALUE }, { _, _ -> }, encode = { _, _, _, _ -> encodes++; error("injected encoder failure") }, verify = ::proof, find = { null })
        var queue = queue(repo)
        queue.setOpen(true, 0)
        assertEquals(2, encodes) // Both due sessions run, not only the failing head.
        queue.tick(9_999)
        assertEquals(2, encodes)
        queue.setOpen(false, 9_999)
        queue.tick(1_000_000)
        assertEquals(2, encodes)
        val reopenedStore = SessionStore(root)
        val reopened = SessionRepository(root, reopenedStore)
        reopened.recover()
        queue = queue(reopened)
        queue.setOpen(true, 0)
        queue.tick(1)
        assertEquals(4, encodes)
        queue.tick(30_000)
        assertEquals(4, encodes)
        queue.tick(30_001)
        assertEquals(6, encodes)
        assertEquals(SessionPhase.NEEDS_ATTENTION, reopened.get(first.directory.name).phase)
        assertEquals(SessionPhase.NEEDS_ATTENTION, reopened.get(second.directory.name).phase)
        queue.tick(Long.MAX_VALUE / 2)
        assertEquals(6, encodes)
        queue.retry(first.directory.name)
        queue.retry(first.directory.name)
        assertEquals(7, encodes)
        assertEquals(1, reopened.get(first.directory.name).exportCycle.attempts)
        assertEquals(1, store.frames(first).size)
    }

    @Test fun cleanupFailureNeverReencodesAndUnknownFilesBlockDeletion() = fixture { context, root, store, repo ->
        val session = closed(store, repo)
        val sentinel = File(session.directory, "unrelated.txt").apply { writeText("keep") }
        var encodes = 0
        val queue = SessionExportQueue(direct, direct, repo, VideoExporter(context, root, root), { Long.MAX_VALUE }, { _, _ -> },
            encode = { _, _, operation, _ -> encodes++; Uri.parse("content://fixture/$operation") }, verify = ::proof, find = { null })
        queue.setOpen(true, 0)
        queue.tick(10_000)
        queue.tick(40_000)
        val failed = repo.get(session.directory.name)
        assertEquals(SessionPhase.NEEDS_ATTENTION, failed.phase)
        assertTrue(failed.cleanupOnly)
        assertEquals(3, failed.cleanupCycle.attempts)
        assertEquals(1, encodes)
        assertEquals("keep", sentinel.readText())
        assertEquals(1, store.frames(session).size)
        check(sentinel.delete()) // Exact fixture allocation only.
        queue.retry(failed.id)
        assertEquals(SessionPhase.FINISHED, repo.get(failed.id).phase)
        assertEquals(1, encodes)
    }

    @Test fun reservedInterruptedAttemptReconcilesPublicationWithoutEncoding() = fixture { context, root, store, repo ->
        val session = closed(store, repo)
        val record = repo.get(session.directory.name)
        repo.put(record.copy(phase = SessionPhase.EXPORTING, exportCycle = record.exportCycle.reserve()))
        val reopened = SessionRepository(root, SessionStore(root)).also { it.recover() }
        var encodes = 0
        val queue = SessionExportQueue(direct, direct, reopened, VideoExporter(context, root, root), { Long.MAX_VALUE }, { _, _ -> },
            encode = { _, _, _, _ -> encodes++; error("must reconcile") }, verify = ::proof,
            find = { Uri.parse("content://fixture/$it") })
        queue.setOpen(true, 0)
        assertEquals(0, encodes)
        assertEquals(SessionPhase.FINISHED, reopened.get(record.id).phase)
        assertEquals(1, reopened.get(record.id).exportCycle.attempts)
    }

    @Test fun everyCleanupCheckpointResumesExactInventoryAndRetainsOtherSessions() {
        listOf("receipt", "before-source-delete", "source-deleted", "cleanup-checkpoint").forEach { failure -> fixture { _, root, store, _ ->
            var armed = false
            var fired = false
            val repo = SessionRepository(root, store, checkpoint = {
                if (armed && !fired && it == failure) { fired = true; throw IllegalStateException("injected") }
            }).also { it.recover() }
            val session = closed(store, repo)
            val other = closed(store, repo)
            val record = repo.get(session.directory.name)
            val verified = proof(session, record.operation, null, record.selected)
            armed = true
            runCatching {
                val withReceipt = repo.receipt(record, verified)
                repo.put(withReceipt.copy(cleanupCycle = withReceipt.cleanupCycle.reserve()))
                repo.cleanup(repo.get(record.id), verified)
            }
            val recovered = SessionRepository(root, SessionStore(root)).also { it.recover() }
            var work = recovered.get(record.id)
            if (!work.cleanupOnly) work = recovered.receipt(work, verified)
            if (!work.cleanupCycle.reserved) work = work.copy(cleanupCycle = work.cleanupCycle.reserve())
            recovered.put(work)
            while (!recovered.cleanup(work, verified)) { /* bounded chunks */ }
            assertEquals(0, store.frames(session).size)
            assertEquals(1, store.frames(other).size)
        } }
    }

    @Test fun changedOutputOrReceiptNeverAuthorizesCleanup() = fixture { _, _, store, repo ->
        val session = closed(store, repo)
        val record = repo.get(session.directory.name)
        val proof = proof(session, record.operation, null, record.selected)
        val withReceipt = repo.receipt(record, proof).let { it.copy(cleanupCycle = it.cleanupCycle.reserve()) }
        repo.put(withReceipt)
        assertThrows(IllegalStateException::class.java) { repo.cleanup(withReceipt, proof.copy(sha256 = "1".repeat(64))) }
        assertEquals(1, store.frames(session).size)
        File(session.directory, "receipt.json").appendText(" ")
        assertThrows(IllegalStateException::class.java) { repo.cleanup(withReceipt, proof) }
        assertEquals(1, store.frames(session).size)
    }

    @Test fun lookalikeSidecarNeverBecomesDeletionAuthority() = fixture { _, root, store, repo ->
        val session = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance(), SessionSplit.DEFAULT)
        repo.register(session)
        add(store, session)
        val before = photos(store, session)
        val knownIntent = File(session.directory, "intent_0.json").readBytes()
        val unknown = File(session.directory, "intent_999999.json").apply { writeText("unattributed") }
        assertNull(repo.close(session, false))
        assertEquals(SessionPhase.QUEUED, repo.get(session.directory.name).phase)
        assertFalse(File(session.directory, "sources.tsv").readText().contains(unknown.name))
        val reopened = SessionRepository(root, SessionStore(root)).also { it.recover() }
        assertEquals(repo.get(session.directory.name).operation, reopened.get(session.directory.name).operation)
        assertThrows(IllegalStateException::class.java) { reopened.deleteSources(session.directory.name) }
        assertEquals("unattributed", unknown.readText())
        assertEquals(1, store.frames(session).size)
        assertEquals(before, photos(store, session))
        assertArrayEquals(knownIntent, File(session.directory, "intent_0.json").readBytes())
    }

    @Test fun closingRecoveryQuarantinesUnreservedSidecarsWithoutAuthorizingCleanup() {
        for (name in listOf("intent_1.json", "intent_999999.json", "intent_999999.json.bak", "intent_00.json",
            "intent_00.json.bak", "intent_9223372036854775808.json", "intent_1.json.new")) fixture { _, root, store, _ ->
            var armed = false
            val repo = SessionRepository(root, store, checkpoint = { if (armed && it == "closing") error("injected closing interruption") })
            repo.recover()
            val session = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance(), SessionSplit.DEFAULT)
            repo.register(session)
            add(store, session)
            val before = photos(store, session)
            val unknown = File(session.directory, name).apply { writeText("unattributed") }
            armed = true
            assertThrows(IllegalStateException::class.java) { repo.close(session, true) }
            val nextId = repo.get(session.directory.name).successor!!
            val reopenedStore = SessionStore(root)
            val reopened = SessionRepository(root, reopenedStore).also { it.recover() }
            val record = reopened.get(session.directory.name)
            assertEquals(SessionPhase.QUEUED, record.phase)
            assertEquals(nextId, reopened.current()!!.directory.name)
            assertEquals(2, reopened.records().size)
            reopened.recover()
            assertEquals(2, reopened.records().size)
            assertEquals(nextId, reopened.current()!!.directory.name)
            assertFalse(File(session.directory, "sources.tsv").readText().contains(unknown.name))
            val verified = proof(session, record.operation, null, record.selected)
            val cleanup = reopened.receipt(record, verified).let { it.copy(cleanupCycle = it.cleanupCycle.reserve()) }
            reopened.put(cleanup)
            assertThrows(IllegalStateException::class.java) { reopened.cleanup(cleanup, verified) }
            assertEquals("unattributed", unknown.readText())
            assertEquals(before, photos(store, session))
            // The affected old cleanup does not prevent the independently owned successor from saving.
            val successor = reopened.current()!!
            add(reopenedStore, successor)
            assertEquals(1, reopenedStore.snapshot(successor).count)
        }
    }

    @Test fun corruptReservedIntentsAndUnattributedCompletedPhotosStillBlockRecovery() {
        for (fault in listOf("reserved-json", "reserved-id", "missing-intent", "unreserved-jpeg")) fixture { _, root, store, repo ->
            val session = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance(), SessionSplit.DEFAULT)
            repo.register(session)
            add(store, session)
            val intent = File(session.directory, "intent_0.json")
            when (fault) {
                "reserved-json" -> intent.writeText("unattributed")
                "reserved-id" -> SessionRepository.atomicJson(intent, SessionRepository.readJson(intent).put("id", 1))
                "missing-intent" -> check(intent.delete())
                "unreserved-jpeg" -> {
                    val frame = "frame_${999999L.toString().padStart(19, '0')}_1.jpg"
                    store.frames(session).single().copyTo(File(session.directory, frame))
                    SessionRepository.atomicJson(File(session.directory, "intent_999999.json"), org.json.JSONObject()
                        .put("version", 1).put("id", 999999).put("epoch", 1).put("active", 0).put("name", frame))
                }
            }
            val before = photos(store, session)
            assertThrows(IllegalStateException::class.java) { repo.close(session, false) }
            assertThrows(IllegalStateException::class.java) { SessionRepository(root, SessionStore(root)).recover() }
            assertEquals(before, photos(store, session))
            assertFalse(File(session.directory, "sources.tsv").exists())
            assertFalse(File(session.directory, "receipt.json").exists())
        }
    }

    @Test fun manualSessionsKeepStrictUnreservedSidecarRecovery() = fixture { _, root, store, _ ->
        val session = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance())
        add(store, session)
        val before = photos(store, session)
        val unknown = File(session.directory, "intent_999999.json").apply { writeText("unattributed") }
        assertThrows(IllegalStateException::class.java) { SessionStore(root).latest() }
        assertEquals("unattributed", unknown.readText())
        assertEquals(before, photos(store, session))
    }

    @Test fun unknownSidecarsCannotBeOverwrittenByFutureReservations() {
        for (suffix in listOf("", ".bak", ".new", "dangling-link")) fixture { _, root, store, repo ->
            val session = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance(), SessionSplit.DEFAULT)
            repo.register(session)
            add(store, session)
            val before = photos(store, session)
            val file = File(session.directory, "intent_1.json" + if (suffix == "dangling-link") "" else suffix)
            val target = File(root, "absent-fixture-target")
            if (suffix == "dangling-link") android.system.Os.symlink(target.path, file.path) else file.writeText("unattributed")
            assertThrows(IllegalStateException::class.java) { store.beginFrame(session, 2, 500) }
            assertEquals(1L, SessionRepository.readJson(File(session.directory, "progress.json")).getLong("nextId"))
            assertEquals(before, photos(store, session))
            if (suffix == "dangling-link") {
                assertTrue(java.nio.file.Files.isSymbolicLink(file.toPath()))
                assertFalse(target.exists())
            } else assertEquals("unattributed", file.readText())
            check(file.delete()) // Exact fixture allocation, after proving production did not overwrite it.
            assertEquals(1L, store.beginFrame(session, 2, 500).id)
        }
    }

    @Test fun losingIndexNeverSilentlyReopensAClosedSessionWithANewOperation() = fixture { _, root, store, repo ->
        val session = closed(store, repo)
        val operation = repo.get(session.directory.name).operation
        check(File(root, "session-index.json").delete()) // Exact fixture's fault injection, not app data.
        val recovered = SessionRepository(root, SessionStore(root))
        assertThrows(IllegalStateException::class.java) { recovered.recover() }
        assertEquals(operation, SessionRepository.readJson(File(session.directory, "closed.json")).getString("operation"))
        assertEquals(1, store.frames(session).size)
    }

    @Test fun lowSpaceConsumesAttemptsWithoutEncodingOrDeleting() = fixture { context, root, store, repo ->
        val session = closed(store, repo)
        var encodes = 0
        val queue = SessionExportQueue(direct, direct, repo, VideoExporter(context, root, root), { CapturePolicy.MIN_FREE_BYTES }, { _, _ -> },
            encode = { _, _, _, _ -> encodes++; error("headroom must block") }, verify = ::proof, find = { null })
        queue.setOpen(true, 0)
        queue.tick(10_000)
        queue.tick(40_000)
        assertEquals(0, encodes)
        assertEquals(SessionPhase.NEEDS_ATTENTION, repo.get(session.directory.name).phase)
        assertEquals(3, repo.get(session.directory.name).exportCycle.attempts)
        assertEquals(1, store.frames(session).size)
    }

    @Test fun aliasesAndBusySessionsCannotBeDeletedAndConfirmedManualDeleteStaysScoped() = fixture { _, root, store, repo ->
        val current = store.create(SamplingConfiguration.Adaptive(), SessionLens.REAR, TimestampAppearance(), SessionSplit.DEFAULT)
        repo.register(current)
        add(store, current)
        assertThrows(IllegalStateException::class.java) { repo.deleteSources(current.directory.name) }
        repo.close(current, false)
        val sentinel = File(root, "outside.txt").apply { writeText("keep") }
        val alias = File(current.directory, "foreign.txt")
        android.system.Os.symlink(sentinel.path, alias.path)
        assertThrows(IllegalStateException::class.java) { repo.deleteSources(current.directory.name) }
        assertEquals("keep", sentinel.readText())
        assertEquals(1, store.frames(current).size)
        check(alias.delete()) // The fixture's exact symlink, never its target.
        while (!repo.continueDelete(current.directory.name)) { }
        assertEquals(SessionPhase.DELETED, repo.get(current.directory.name).phase)
        assertEquals("keep", sentinel.readText())
        val manual = store.create(SamplingConfiguration.Fixed(1), SessionLens.FRONT, TimestampAppearance())
        add(store, manual)
        val header = File(manual.directory, "session.json").readBytes()
        repo.deleteManual(manual)
        while (repo.get(manual.directory.name).phase == SessionPhase.DELETING) repo.continueDelete(manual.directory.name)
        assertArrayEquals(header, File(manual.directory, "session.json").readBytes())
        assertEquals(0, store.frames(manual).size)
        assertEquals(SessionSplit.OFF, repo.session(repo.get(manual.directory.name)).split)
    }

    @Test fun publishedCopyIsReopenedDecodedAndCorruptionRetainsSources() = fixture { context, root, store, repo ->
        val session = closed(store, repo)
        val record = repo.get(session.directory.name)
        val exporter = VideoExporter(context, root, root)
        var uri: Uri? = null
        try {
            uri = exporter.export(session, repo.closedSelection(record).second, record.operation) {}
            val verified = exporter.verifyPublished(session, record.operation, uri, 1)
            assertEquals(uri, verified.uri)
            assertEquals(64, verified.sha256.length)
            assertEquals(uri, exporter.export(session, repo.closedSelection(record).second, record.operation) {})
            checkNotNull(context.contentResolver.openOutputStream(uri, "w")).use { it.write(byteArrayOf(1, 2, 3)) }
            assertThrows(Exception::class.java) { exporter.verifyPublished(session, record.operation, uri, 1) }
            assertEquals(1, store.frames(session).size)
            assertFalse(File(session.directory, "receipt.json").exists())
        } finally {
            // URI belongs to this UUID fixture; production never deletes a published output.
            uri?.let { context.contentResolver.delete(it, null, null) }
        }
    }
}
