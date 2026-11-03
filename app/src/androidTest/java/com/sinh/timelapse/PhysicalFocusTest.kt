package com.sinh.timelapse

import android.app.Application
import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit physical-camera opt-in. Keeps test JPEGs in exact new private cache roots;
 * never exports, deletes sources, touches normal-package data or enrolls sync.
 * This certifies adapter/optics observations, not a human defocus/UAT decision.
 */
class PhysicalFocusTest {
    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun await(message: String, predicate: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            var ready = false; onMain { ready = predicate() }; if (ready) return
            Thread.sleep(25)
        }
        fail(message)
    }
    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    @Test fun bothLensesHoldThroughPrivateShuttersAndMountedBrowserWithoutReacquiring() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit camera/fixture permission required", args.getString("ttl019PhysicalFocus") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.packageName == "com.sinh.timelapse.ttl019verification") { "Dedicated disposable package required" }
        WorkUiFixtures.keepTestWindowsAwake(app)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            lateinit var actual: CaptureViewModel
            scenario.onActivity { activity = it; actual = ViewModelProvider(it)[CaptureViewModel::class.java] }
            await("Camera owner recovery did not complete") { actual.state.value?.loading == false }
            check(actual.state.value?.session == null) { "Existing recording retained; cannot run physical fixture" }
            for (lens in SessionLens.entries) {
                onMain { actual.selectLens(lens) }
                await("Selected $lens did not bind") { actual.state.value?.boundLens == lens }
                val root = File(app.cacheDir, "ttl019-physical-${lens.storedValue}-${UUID.randomUUID()}").apply { check(mkdirs()) }
                val store = ViewModelStore()
                lateinit var fixture: CaptureViewModel
                lateinit var focus: CaptureFocus
                var observer: androidx.lifecycle.DefaultLifecycleObserver? = null
                var fixtureCreated = false
                var heldDistance: Any? = null
                try {
                    onMain {
                        focus = field(actual, "focus") as CaptureFocus
                        fixture = ViewModelProvider(store, object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                                CaptureViewModel(app, root, File(root, "cache")) as T
                        })[CaptureViewModel::class.java]
                        fixtureCreated = true
                        observer = object : androidx.lifecycle.DefaultLifecycleObserver {
                            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { fixture.setForeground(false) }
                        }
                        activity.lifecycle.addObserver(checkNotNull(observer))
                    }
                    await("Physical fixture recovery failed") { fixture.state.value?.loading == false }
                    val started = android.os.SystemClock.elapsedRealtime()
                    onMain {
                        fixture.setForeground(true); fixture.selectLens(lens)
                        fixture.attachCamera(field(actual, "imageCapture") as ImageCapture, lens, focus = focus)
                        fixture.start(SamplingConfiguration.Fixed(1, 15), split = SessionSplit.OFF)
                    }
                    await("$lens gate failed: ${fixture.state.value}") { fixture.state.value?.capturing == true || fixture.state.value?.focusError?.isNotEmpty() == true }
                    var generation = 0L
                    onMain {
                        assertTrue("$lens: ${fixture.state.value?.focusError}", fixture.state.value!!.capturing)
                        assertTrue(focus.held)
                        generation = (field(focus, "policy") as FocusLockPolicy).generation
                        assertTrue("Focus gate exceeded five seconds", android.os.SystemClock.elapsedRealtime() - started < 5500)
                    }
                    await("$lens did not save two held-focus photos") { fixture.state.value!!.frames >= 2 }
                    onMain { MainActivity::class.java.getDeclaredMethod("showSessions").apply { isAccessible = true }.invoke(activity) }
                    await("$lens shutters stopped during in-app browsing") { fixture.state.value!!.frames >= 3 }
                    onMain {
                        assertTrue(focus.held); assertTrue(fixture.state.value!!.capturing)
                        assertEquals(generation, (field(focus, "policy") as FocusLockPolicy).generation)
                        heldDistance = field(focus, "distance")
                        (field(activity, "sessionsDialog") as android.app.Dialog).dismiss()
                        fixture.stop()
                    }
                    await("$lens admitted photo did not drain") { !fixture.state.value!!.busy }
                    onMain {
                        assertFalse(fixture.state.value!!.capturing)
                        assertTrue(fixture.state.value!!.frames >= 3)
                        val result = "TTL019_PHYSICAL lens=${lens.storedValue} held=true generation=$generation photos=${fixture.state.value!!.frames} retainedRoot=${root.name} fixed=${field(focus, "fixed")} distance=$heldDistance"
                        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", result + "\n") })
                    }
                } finally {
                    onMain {
                        (field(activity, "sessionsDialog") as? android.app.Dialog)?.dismiss()
                        observer?.let(activity.lifecycle::removeObserver)
                        if (fixtureCreated) fixture.setForeground(false)
                        store.clear()
                    }
                    // Deliberately retain this exact test root and its photos; no clearing to pass.
                }
            }
        }
    }
}
