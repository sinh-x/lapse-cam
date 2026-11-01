package com.sinh.timelapse

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.EditText
import android.widget.Spinner
import android.widget.Button
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Activity cases require the separately approved empty disposable primary-user app context.
 * Never start capture, clear app data, alter permissions, or overwrite pre-existing defaults.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceDefaultsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val intervals = listOf(1, 2, 5, 10, 30, 60)
    private val rates = listOf(15, 24, 30)

    private fun isolatedPreferences(test: (SharedPreferences) -> Unit) {
        val name = "ttl006-defaults-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, 0)
        try { test(preferences) } finally { check(context.deleteSharedPreferences(name)) }
    }

    @Test fun allValidCaptureDefaultsRoundTripWithStableKeysAndFixedPreservesAdaptiveCap() {
        isolatedPreferences { preferences ->
            for (cap in listOf(5, 30, 600)) for (rate in rates) for (split in SessionSplit.entries) {
                val config = SamplingConfiguration.Adaptive(cap, rate)
                CaptureDefaults(preferences).rememberCapture(config, 5, split)
                assertTrue(preferences.edit().commit()) // Drain apply() before reopening the reader.
                val reopened = CaptureDefaults(preferences)
                assertTrue(reopened.adaptive)
                assertEquals(cap, reopened.maxSeconds)
                assertEquals(rate, reopened.fps)
                assertEquals(split, reopened.split)
                assertEquals(split.storedValue, preferences.getString("split", null))
                for (interval in intervals) {
                    CaptureDefaults(preferences).rememberCapture(SamplingConfiguration.Fixed(interval, rate), interval, split)
                    val fixed = CaptureDefaults(preferences)
                    assertFalse(fixed.adaptive)
                    assertEquals(cap, fixed.maxSeconds)
                    assertEquals(interval, fixed.interval)
                    assertEquals(rate, fixed.fps)
                    assertEquals(split, fixed.split)
                }
            }
            assertEquals(setOf("adaptive", "maxSeconds", "fps", "interval", "split"), preferences.all.keys)
        }
    }

    @Test fun blackoutIsDefaultOnAndPersistsIndependentlyOfSessionDefaults() {
        isolatedPreferences { preferences ->
            assertTrue(CaptureDefaults(preferences).blackoutEnabled)
            for (enabled in listOf(false, true)) {
                CaptureDefaults(preferences).blackoutEnabled = enabled
                assertTrue(preferences.edit().commit())
                val reopened = CaptureDefaults(preferences)
                assertEquals(enabled, reopened.blackoutEnabled)
                reopened.rememberCapture(SamplingConfiguration.Adaptive(30, 30), 5, SessionSplit.DEFAULT)
                reopened.rememberAppearance(TimestampAppearance())
                assertEquals(enabled, CaptureDefaults(preferences).blackoutEnabled)
                assertEquals(enabled, preferences.getBoolean("blackoutEnabled", !enabled))
            }
        }
    }

    @Test fun everyAppearanceFieldRoundTripsUsingStableNames() {
        isolatedPreferences { preferences ->
            for (family in StampFamily.entries) for (bold in listOf(false, true))
                for (size in listOf(32, 44, 60)) for (position in StampPosition.entries) for (date in listOf(false, true)) {
                    val expected = TimestampAppearance(family, bold, size, position, date)
                    CaptureDefaults(preferences).rememberAppearance(expected)
                    assertTrue(preferences.edit().commit())
                    assertEquals(expected, CaptureDefaults(preferences).appearance())
                    assertEquals(family.name, preferences.getString("family", null))
                    assertEquals(position.name, preferences.getString("position", null))
                }
            assertEquals(setOf("family", "bold", "size", "position", "date"), preferences.all.keys)
        }
    }

    @Test fun missingAndInvalidValuesKeepOriginalFallbackAndTypeErrorPolicy() {
        isolatedPreferences { preferences ->
            val defaults = CaptureDefaults(preferences)
            assertTrue(defaults.adaptive)
            assertEquals(30, defaults.maxSeconds)
            assertEquals(5, defaults.interval)
            assertEquals(30, defaults.fps)
            assertEquals(SessionSplit.DEFAULT, defaults.split)
            assertEquals(TimestampAppearance(), defaults.appearance())
            for ((key, value) in listOf("family" to "unknown", "position" to "unknown", "size" to 45, "date" to "wrong-type")) {
                preferences.edit().clear().apply()
                val edit = preferences.edit()
                if (value is Int) edit.putInt(key, value) else edit.putString(key, value.toString())
                edit.apply()
                assertEquals(TimestampAppearance(), defaults.appearance())
            }
            preferences.edit().clear().putString("split", "unknown").apply()
            assertEquals(SessionSplit.DEFAULT, defaults.split)
            preferences.edit().putInt("split", 2).apply()
            assertEquals(SessionSplit.DEFAULT, defaults.split)
            preferences.edit().putInt("interval", 7).putInt("fps", 12).putInt("maxSeconds", -1).apply()
            assertEquals(7, defaults.interval) // Spinner index fallback remains in MainActivity.
            assertEquals(12, defaults.fps)
            assertEquals(-1, defaults.maxSeconds) // No new validation/migration at read time.
            preferences.edit().putString("adaptive", "wrong-type").apply()
            assertThrows(ClassCastException::class.java) { defaults.adaptive }
            preferences.edit().putString("fps", "wrong-type").apply()
            assertThrows(ClassCastException::class.java) { defaults.fps }
        }
    }

    private fun <T> field(activity: MainActivity, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun state(activity: MainActivity) = checkNotNull(ViewModelProvider(activity)[CaptureViewModel::class.java].state.value)
    private fun awaitReady(scenario: ActivityScenario<MainActivity>) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { ready = !state(it).loading }
            if (ready) {
                scenario.onActivity { assertFalse(state(it).recoveryBlocked); assertFalse(state(it).capturing); assertFalse(state(it).savingPhoto) }
                return
            }
            Thread.sleep(50)
        }
        fail("Activity recovery did not finish")
    }
    private fun assertControls(activity: MainActivity, config: SamplingConfiguration, split: SessionSplit, appearance: TimestampAppearance) {
        assertEquals(if (config is SamplingConfiguration.Adaptive) 0 else 1, field<Spinner>(activity, "mode").selectedItemPosition)
        assertEquals(rates.indexOf(config.fps), field<Spinner>(activity, "fps").selectedItemPosition)
        assertEquals(split.ordinal, field<Spinner>(activity, "split").selectedItemPosition)
        if (config is SamplingConfiguration.Adaptive) assertEquals(config.maxSeconds.toString(), field<EditText>(activity, "maxSeconds").text.toString())
        if (config is SamplingConfiguration.Fixed) assertEquals(intervals.indexOf(config.intervalSeconds), field<Spinner>(activity, "interval").selectedItemPosition)
        assertEquals(appearance, field<TimestampPreview>(activity, "clock").appearance)
        assertFalse(state(activity).capturing)
        assertFalse(state(activity).savingPhoto)
    }

    /** Fail BEFORE touching defaults/session state if the operator's empty-context premise is false. */
    private fun disposableActivity(test: (SharedPreferences) -> Unit) {
        val preferences = context.getSharedPreferences("capture-defaults", 0)
        check(preferences.all.isEmpty()) { "Requires empty disposable defaults; existing values retained" }
        val sessions = File(context.filesDir, "sessions")
        check(!sessions.exists() || sessions.listFiles()?.isEmpty() == true) { "Existing sessions retained; no Activity test admission" }
        check(context.filesDir.listFiles()!!.none { it.name.startsWith("session-index.json") }) { "Existing session index retained" }
        check(context.getSharedPreferences("exports", 0).all.isEmpty()) { "Existing export preferences retained" }
        val journal = File(context.filesDir, "export-attempts")
        check(!journal.exists() || journal.listFiles()?.isEmpty() == true) { "Existing export state retained" }
        try { test(preferences) } finally { assertTrue(preferences.edit().clear().commit()) }
    }

    @Test fun actualActivityRecreationWithoutSessionRestoresDefaultsAndUiAppearanceWriter() {
        disposableActivity { preferences ->
            val style = TimestampAppearance(StampFamily.SERIF, false, 60, StampPosition.TOP_RIGHT, true)
            CaptureDefaults(preferences).apply {
                rememberCapture(SamplingConfiguration.Fixed(60, 24), 60, SessionSplit.OFF)
                rememberAppearance(style)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitReady(scenario)
                scenario.onActivity { assertControls(it, SamplingConfiguration.Fixed(60, 24), SessionSplit.OFF, style); assertNull(state(it).session) }
                scenario.recreate()
                awaitReady(scenario)
                scenario.onActivity { activity ->
                    assertControls(activity, SamplingConfiguration.Fixed(60, 24), SessionSplit.OFF, style)
                    // Invoke the actual UI writer (no Start/Resume click or shutter).
                    field<Button>(activity, "timestampSettings").performClick()
                }
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<Button>()
                        .single { it.text.toString() == "Reset to defaults" }.performClick()
                    WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<Button>()
                        .single { it.text.toString() == "Done" }.performClick()
                }
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(TimestampAppearance(), CaptureDefaults(preferences).appearance())
                scenario.recreate()
                awaitReady(scenario)
                scenario.onActivity { assertControls(it, SamplingConfiguration.Fixed(60, 24), SessionSplit.OFF, TimestampAppearance()) }
            }
            // A new Activity/model as well as configuration recreation.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitReady(scenario)
                scenario.onActivity { assertControls(it, SamplingConfiguration.Fixed(60, 24), SessionSplit.OFF, TimestampAppearance()); assertNull(state(it).session) }
            }
            assertTrue(File(context.filesDir, "sessions").listFiles()!!.isEmpty())
        }
    }

    @Test fun actualActivityRecreationAndRelaunchKeepPausedFrozenSessionOverDifferentDefaults() {
        disposableActivity { preferences ->
            val style = TimestampAppearance(StampFamily.SANS_SERIF, false, 32, StampPosition.TOP_CENTER, true)
            val store = SessionStore(context.filesDir)
            val session = store.create(SamplingConfiguration.Adaptive(5, 15), SessionLens.FRONT, style, SessionSplit.TWO_HOURS)
            val repository = SessionRepository(context.filesDir, store)
            repository.register(session)
            val image = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            try {
                store.completeFrame(session, store.beginFrame(session, 123_000, 500), image)
                store.checkpoint(session, 750, false)
                repository.checkpointSession(session, false)
                val before = session.directory.listFiles()!!.associate { it.name to digest(it) }
                CaptureDefaults(preferences).apply {
                    rememberCapture(SamplingConfiguration.Fixed(60, 30), 60, SessionSplit.OFF)
                    rememberAppearance(TimestampAppearance())
                }
                val savedDefaults = preferences.all.toMap()
                repeat(2) {
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        awaitReady(scenario)
                        scenario.recreate()
                        awaitReady(scenario)
                        scenario.onActivity { activity ->
                            val recovered = checkNotNull(state(activity).session)
                            assertEquals(session, recovered) // Includes zone, lens, style, split and mode.
                            assertEquals(750L, state(activity).activeMillis)
                            assertEquals(1, state(activity).selectedFrames)
                            assertControls(activity, checkNotNull(session.configuration), session.split, style)
                            assertFalse(field<Button>(activity, "timestampSettings").isEnabled)
                            assertEquals(SessionLens.FRONT.ordinal, field<Spinner>(activity, "lens").selectedItemPosition)
                            assertEquals(session.zoneId, field<TimestampPreview>(activity, "clock").zone)
                        }
                    }
                }
                assertEquals(savedDefaults, preferences.all)
                assertEquals(before, session.directory.listFiles()!!.associate { it.name to digest(it) })
                assertEquals(1, store.exportFrames(session).size)
            } finally {
                image.recycle()
                // Only the exact newly allocated fixture session and its newly owned index.
                check(session.directory.deleteRecursively())
                for (name in listOf("session-index.json", "session-index.json.bak", "session-index.json.new")) {
                    File(context.filesDir, name).let { if (it.exists()) check(it.delete()) }
                }
            }
        }
    }
    private fun digest(file: File): List<Byte> = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
}
