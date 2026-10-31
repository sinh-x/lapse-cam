package com.sinh.timelapse

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*

/** Local configuration fixtures are not phone/UAT evidence. Never operate valuable app state. */
internal object WorkUiFixtures {
    fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    fun <T> field(activity: MainActivity, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    }
    fun emptyActivity(): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val syncFile = java.io.File(context.filesDir, "private-sync/state.json")
        if (syncFile.exists()) {
            val sync = org.json.JSONObject(syncFile.readText())
            check(sync.getInt("version") == 1 && !sync.getBoolean("enabled") && sync.getString("endpoint").isEmpty() && sync.getString("credential").isEmpty()) {
                "Enrolled/unknown sync context retained; fresh disposable fixture context required"
            }
        }
        check(context.getSharedPreferences("capture-defaults", 0).all.isEmpty()) { "Existing defaults retained; disposable context required" }
        check(context.getSharedPreferences("exports", 0).all.isEmpty()) { "Existing exports retained" }
        val sessions = java.io.File(context.filesDir, "sessions")
        check(!sessions.exists() || sessions.listFiles()!!.isEmpty()) { "Existing sources retained" }
        check(context.filesDir.listFiles()!!.none { it.name.startsWith("session-index.json") })
        val work = WorkSessionStore(context.filesDir).load()
        check(work.records.isEmpty() && work.draft == null && work.spans.isEmpty()) { "Existing work retained" }
        return ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            await(scenario) { activity -> !ViewModelProvider(activity)[CaptureViewModel::class.java].state.value!!.loading }
        }
    }
    fun await(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 15_000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            var ready = false
            scenario.onActivity { ready = predicate(it) }
            if (ready) return
            Thread.sleep(25)
        }
        fail("UI fixture did not become ready")
    }
    fun idle() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}
