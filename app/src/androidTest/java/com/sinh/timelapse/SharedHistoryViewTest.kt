package com.sinh.timelapse

import android.widget.Button
import android.widget.TextView
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SharedHistoryViewTest {
    @Test fun offlineHistoryPaginatesTextOnlyWithoutDownloadOrExecutionControls() {
        WorkUiFixtures.emptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                var downloads = 0
                val owner = SyncCoordinator.get(activity.application)
                val view = SharedHistoryView(activity, owner) { _, _ -> downloads++ }
                val records = JSONArray()
                repeat(51) { index -> records.put(json("id" to UUID.randomUUID().toString(), "device" to "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    "fields" to json("title" to json("value" to "Literal <script>$index</script>")))) }
                view.render(SyncCoordinator.State(loaded = true, history = json("records" to records, "videos" to JSONArray())))
                fun buttons() = WorkUiFixtures.descendants(view).filterIsInstance<Button>()
                assertEquals(50, buttons().count { it.text.startsWith("Literal <script>") })
                assertFalse(buttons().any { it.text.toString() in listOf("Start", "End", "Delete", "Archive") })
                buttons().single { it.text == "Next page" }.performClick()
                assertEquals(1, buttons().count { it.text.startsWith("Literal <script>") })
                assertEquals(0, downloads)
            }
        }
    }
    @Test fun statusDistinguishesPendingConflictLowStorageAndLastSyncedState() {
        WorkUiFixtures.emptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                val view = SharedHistoryView(activity, SyncCoordinator.get(activity.application)) { _, _ -> fail("No automatic download") }
                view.render(SyncCoordinator.State(loaded = true, pending = 100, conflicts = 2, lowStorage = true,
                    message = "Fixture unavailable; work remains offline"))
                val text = WorkUiFixtures.descendants(view).filterIsInstance<TextView>().joinToString("\n") { it.text }
                assertTrue(text.contains("Pending: 100")); assertTrue(text.contains("conflicts: 2"))
                assertTrue(text.contains("below 10%")); assertTrue(text.contains("stale until source sync"))
                assertTrue(text.contains("never")); assertTrue(text.contains("No automatic deletion"))
            }
        }
    }
}
