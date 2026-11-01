package com.sinh.timelapse

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Disposable cache-root producer checks only; never use the original Work store or camera. */
class DisplayAttentionProducerTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun next(owner: WorkSessionCoordinator, operation: () -> Unit) {
        instrumentation.runOnMainSync(operation)
        val worker = WorkSessionCoordinator::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(owner) as ExecutorService
        worker.submit {}.get(10, TimeUnit.SECONDS) // Serial barrier after this operation's disk work.
        instrumentation.waitForIdleSync() // Then drain its posted publication, not the edit's Saving emission.
    }

    @Test fun repeatedRealWorkRecoveryFailuresKeepBytesDraftAndLocksAndNotifyOnlyOneStoreEpisode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "attention-producer-${UUID.randomUUID()}").apply { check(mkdir()) }
        val target = File(root, "work-context/state.json").apply { check(parentFile!!.mkdir()); writeText("deliberately invalid fixture") }
        val original = target.readBytes()
        val owner = WorkSessionCoordinator(context.applicationContext as Application, root, remindersEnabled = false,
            clock = { WorkMoment("fixture", 1, 1) })
        try {
            // Drain initialization on the same serial owner before inspecting its main-thread state.
            next(owner) { owner.reconcile() }
            instrumentation.runOnMainSync {
                assertNotNull(owner.state.value!!.error)
                assertFalse(owner.state.value!!.loaded)
                assertEquals(1, owner.attention.deliver(true))
            }
            repeat(5) {
                next(owner) { owner.reconcile() }
                instrumentation.runOnMainSync {
                    assertEquals(0, owner.attention.deliver(true))
                    assertEquals(1, owner.attention.snapshot.count)
                    assertFalse(owner.state.value!!.loaded)
                }
            }
            val draft = WorkDraft(title = "Unsaved fixture")
            next(owner) { owner.edit(draft) }
            instrumentation.runOnMainSync {
                assertEquals(draft, owner.editorDraft)
                assertNotNull(owner.state.value!!.error)
                assertFalse(owner.state.value!!.loaded)
                assertEquals(1, owner.attention.deliver(true)) // different affected item/operation
            }
            next(owner) { owner.edit(draft.copy(title = "Still unsaved")) }
            instrumentation.runOnMainSync {
                assertEquals("Still unsaved", owner.editorDraft!!.title)
                assertEquals(0, owner.attention.deliver(true))
                assertEquals(2, owner.attention.snapshot.count)
            }
            assertArrayEquals(original, target.readBytes())
            assertEquals(listOf("state.json"), target.parentFile!!.list()!!.toList())
        } finally { owner.closeFixture(); check(root.deleteRecursively()) }
    }
}
