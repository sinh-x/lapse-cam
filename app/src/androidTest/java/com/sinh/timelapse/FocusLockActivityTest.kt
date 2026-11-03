package com.sinh.timelapse

import android.app.Application
import androidx.camera.core.ImageCapture
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic port integration only. Does not certify a lens or physical focus behavior. */
class FocusLockActivityTest {
    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun waitFor(model: CaptureViewModel, condition: (CaptureViewModel.State) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var ready = false; onMain { ready = model.state.value?.let(condition) == true }
            if (ready) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out: ${model.state.value}")
    }
    @Test fun admissionCancellationLossAndRetryCannotInventShutters() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File(app.cacheDir, "focus-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val requests = AtomicInteger()
        val complete = java.util.concurrent.atomic.AtomicReference<() -> Unit>()
        val focus = FocusFixtures(immediate = false)
        lateinit var model: CaptureViewModel
        val owner = androidx.lifecycle.ViewModelStore()
        onMain {
            model = androidx.lifecycle.ViewModelProvider(owner, object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    CaptureViewModel(app, root, File(root, "cache"), { Long.MAX_VALUE }, { _, file, executor, callback ->
                        requests.incrementAndGet()
                        complete.set { executor.execute {
                            val bitmap = android.graphics.Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, android.graphics.Bitmap.Config.ARGB_8888)
                            try { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) } }
                            finally { bitmap.recycle() }
                            callback.onImageSaved(ImageCapture.OutputFileResults(null))
                        } }
                    }) as T
            })[CaptureViewModel::class.java]
        }
        try {
            waitFor(model) { !it.loading }
            onMain {
                model.setForeground(true)
                model.attachCamera(ImageCapture.Builder().build(), SessionLens.REAR, focus = focus)
                model.start(1000, 30)
                assertTrue(model.state.value!!.focusing); assertFalse(model.state.value!!.capturing)
                assertNull(model.state.value!!.session); assertEquals(0, requests.get())
                val stale = focus.callback!!
                model.setForeground(false)
                focus.held = true; stale(true, "")
                assertFalse(model.state.value!!.capturing); assertNull(model.state.value!!.session)
                model.setForeground(true); model.start(1000, 30)
                focus.lose()
                assertFalse(model.state.value!!.focusing); assertTrue(model.state.value!!.focusError.isNotEmpty())
                assertEquals(0, requests.get())
                model.start(1000, 30); focus.success()
            }
            waitFor(model) { it.capturing && it.savingPhoto && complete.get() != null }
            assertEquals(1, requests.get())
            onMain {
                val session = model.state.value!!.session
                focus.lose()
                assertEquals(session, model.state.value!!.session)
                assertFalse(model.state.value!!.capturing)
                assertTrue(model.state.value!!.focusError.isNotEmpty())
                assertEquals(1, requests.get())
            }
            complete.get().invoke()
            waitFor(model) { !it.busy && it.frames == 1 }
            onMain {
                assertEquals(1, model.state.value!!.frames)
                model.start(1000, 30)
                assertTrue(model.state.value!!.focusing)
                focus.lose()
                assertFalse(model.state.value!!.capturing)
                assertEquals(1, requests.get())
            }
        } finally {
            onMain { model.setForeground(false); owner.clear() }
            root.deleteRecursively()
        }
    }
}
