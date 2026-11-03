package com.sinh.timelapse

import android.view.View
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Test

/** Native rendering/navigation fixtures; not screenshot comparison or human accessibility UAT. */
class AppDesignActivityTest {
    @Test fun sharedCaptureReturnOnlyNavigatesAndNeverInventsLiveState() {
        WorkUiFixtures.emptyActivity().use { scenario -> scenario.onActivity { activity ->
            val view = CaptureReturnView(activity)
            var returns = 0; view.setOnClickListener { returns++ }
            val owner = ViewModelProvider(activity)[CaptureViewModel::class.java]
            val before = owner.state.value
            view.render(CaptureViewModel.State(loading = false, capturing = true, activeMillis = 1_122_000))
            assertTrue(view.text.contains("LIVE")); assertTrue(view.text.contains("00:18:42"))
            view.render(CaptureViewModel.State(loading = false))
            assertFalse(view.text.contains("LIVE")); assertEquals(0, returns)
            view.performClick(); assertEquals(1, returns); assertEquals(before, owner.state.value)
        } }
    }
    @Test fun cameraHomeKeepsExplicitDestinationsAndNoGlobalTabs() {
        WorkUiFixtures.emptyActivity().use { scenario -> scenario.onActivity { activity ->
            val root = activity.findViewById<View>(android.R.id.content)
            val children = WorkUiFixtures.descendants(root)
            val labels = children.filterIsInstance<Button>().map { it.text.toString() }
            assertTrue(labels.containsAll(listOf("Details", "Private sync", "Sessions")))
            assertTrue(children.none { it is android.widget.TabHost })
            assertNotNull(WorkUiFixtures.field<Button>(activity, "lensSwitch"))
        } }
    }
}
