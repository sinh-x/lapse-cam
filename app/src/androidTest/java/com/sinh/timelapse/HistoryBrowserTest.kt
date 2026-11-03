package com.sinh.timelapse

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HistoryBrowserTest {
    private fun review(id: String, days: Long = 0, failed: Boolean = false): SessionReview {
        val start = LocalDate.now().minusDays(days).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return SessionReview(id, start, "Adaptive", 30, 15, 0, false, 0, 0, 0, 0, 0,
            "Saved", 0, 0, 0, "", null, false, false,
            processing = SessionProcessing(issues = if (failed) listOf(SessionIssue(ProcessingStage.UNKNOWN, "Retained failure")) else emptyList()))
    }
    private fun fixture(body: (HistoryBenchmarkActivity) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<HistoryBenchmarkActivity>(Intent(context, HistoryBenchmarkActivity::class.java)).use { scenario ->
            scenario.onActivity(body)
        }
    }
    @Test fun collapsedHistoryAndAttentionAreLazyAndAllDate() = fixture { activity ->
        val values = (0 until 10000).map { review("id-$it", 1 + it % 30L, it % 10 == 0) }
        val browser = SessionsView(activity); activity.setContentView(browser); browser.render(values)
        val list = WorkUiFixtures.descendants(browser).filterIsInstance<ListView>().single()
        assertEquals(32, list.adapter.count) // Overview + Today empty state + date headers only.
        browser.showAttention()
        assertEquals(1004, list.adapter.count) // Overview + 1000 unique failures in three dates + date headers.
        browser.back(); assertEquals(32, list.adapter.count)
        assertTrue(WorkUiFixtures.descendants(browser).filterIsInstance<TextView>().any { it.text.toString().contains("1000") })
    }
    @Test fun detailRenderDoesNotRecheckAndRestoresEntryIdentity() = fixture { activity ->
        val requests = mutableListOf<Pair<String, String>>()
        val values = listOf(review("selected"))
        val browser = SessionsView(activity, {}, {}, {}, checkOutput = { id, token -> requests.add(id to token) })
        activity.setContentView(browser); browser.render(values); browser.showSession("selected")
        repeat(10) { browser.render(values) }
        assertEquals(1, requests.size)
        val saved = browser.saveState()
        val restored = SessionsView(activity, {}, {}, {}, checkOutput = { id, token -> requests.add(id to token) })
        restored.render(values); restored.restoreState(saved)
        assertEquals(requests[0], requests[1]) // Owner deduplicates the same admitted entry token.
        assertTrue(restored.back()); assertFalse(restored.back())
    }
    @Test fun reusedDateProjectionStillShowsLatestStatusActionsAndAttention() = fixture { activity ->
        val browser = SessionsView(activity); activity.setContentView(browser)
        val before = review("today", failed = true).copy(status = "Old status", canRetry = true)
        browser.render(listOf(before))
        val after = before.copy(status = "Current status", canRetry = false)
        browser.render(listOf(after))
        val list = WorkUiFixtures.descendants(browser).filterIsInstance<ListView>().single()
        assertTrue((list.adapter.getView(2, null, list) as TextView).text.contains("Current status"))
        browser.showSession("today")
        assertFalse(WorkUiFixtures.descendants(browser).filterIsInstance<android.widget.Button>().single { it.text == "Retry" }.isEnabled)
        browser.render(listOf(after.copy(processing = SessionProcessing(hydrated = true))))
        assertTrue(browser.back()) // The overview is a list header, not mounted on the detail page.
        assertTrue(WorkUiFixtures.descendants(browser).filterIsInstance<android.widget.Button>().any { it.text.toString().contains("Needs attention (0)") })
    }

    @Test fun staleSelectionHasSafeBackAndLoadingIsNotEmptyHistory() = fixture { activity ->
        val browser = SessionsView(activity); activity.setContentView(browser)
        browser.loading(false); browser.showSession("gone")
        assertTrue(WorkUiFixtures.descendants(browser).filterIsInstance<TextView>().any { it.text.toString().contains("Loading") })
        browser.loading(true); browser.render(emptyList())
        assertTrue(WorkUiFixtures.descendants(browser).filterIsInstance<TextView>().any { it.text.toString().contains("unavailable") })
        assertTrue(browser.back()); assertFalse(browser.back())
    }
}
