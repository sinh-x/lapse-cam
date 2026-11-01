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
        assertEquals(31, list.adapter.count) // Today empty state + date headers only.
        browser.showAttention()
        assertEquals(1003, list.adapter.count) // 1000 unique failures in three dates + date headers.
        browser.back(); assertEquals(31, list.adapter.count)
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
    @Test fun staleSelectionHasSafeBackAndLoadingIsNotEmptyHistory() = fixture { activity ->
        val browser = SessionsView(activity); activity.setContentView(browser)
        browser.loading(false); browser.showSession("gone")
        assertTrue(WorkUiFixtures.descendants(browser).filterIsInstance<TextView>().any { it.text.toString().contains("Loading") })
        browser.loading(true); browser.render(emptyList())
        assertTrue(WorkUiFixtures.descendants(browser).filterIsInstance<TextView>().any { it.text.toString().contains("unavailable") })
        assertTrue(browser.back()); assertFalse(browser.back())
    }
}
