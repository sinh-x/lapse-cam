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
    private var awakeRegistered = false
    /** Explicit isolated-test view flag only; no lock-screen bypass or global timeout change. */
    fun keepTestWindowsAwake(context: Context) {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("keepScreenAwake") != "true" || awakeRegistered) return
        check(context.packageName in setOf("com.sinh.timelapse.ttl018functional", "com.sinh.timelapse.ttl018baseline", "com.sinh.timelapse.ttl019verification"))
        (context.applicationContext as android.app.Application).registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) { activity.window.decorView.keepScreenOn = true }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        awakeRegistered = true
    }
    fun descendants(view: View): List<View> {
        val children = if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        // Configured headers exist before ListView mounts them. Do not inflate any lazy data rows.
        val headers = if (view is android.widget.ListView && view.adapter != null)
            (0 until view.headerViewsCount).map { view.adapter.getView(it, null, view) }
                .filter { it !in children }.flatMap(::descendants) else emptyList()
        return (listOf(view) + children + headers).distinct()
    }
    fun <T> field(activity: MainActivity, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    }
    fun emptyActivity(): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        keepTestWindowsAwake(context)
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
