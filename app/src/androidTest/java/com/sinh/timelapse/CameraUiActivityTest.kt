package com.sinh.timelapse

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Render/configuration injection only, not real camera, TalkBack or OS lifecycle evidence. */
@RunWith(AndroidJUnit4::class)
class CameraUiActivityTest {
    @Test fun actualRendererShowsLiveIffCapturingAndPauseRemainsAvailableDuringExport() {
        WorkUiFixtures.emptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                val render = MainActivity::class.java.getDeclaredMethod("renderCapture", CaptureViewModel.State::class.java).apply { isAccessible = true }
                val live = WorkUiFixtures.field<TextView>(activity, "live")
                val primary = WorkUiFixtures.field<Button>(activity, "capture")
                val states = listOf(
                    CaptureViewModel.State(loading = true),
                    CaptureViewModel.State(loading = false, capturing = true),
                    CaptureViewModel.State(loading = false, capturing = true, savingPhoto = true, exporting = true),
                    CaptureViewModel.State(loading = false, switching = true),
                    CaptureViewModel.State(loading = false, recoveryBlocked = true),
                    CaptureViewModel.State(loading = false, exporting = true),
                    CaptureViewModel.State(loading = false, cameraMessage = "Camera error"),
                )
                for (state in states) {
                    render.invoke(activity, state)
                    assertEquals(state.capturing, live.text.toString().contains("LIVE"))
                    if (state.capturing) { assertEquals("Pause capture", primary.text.toString()); assertTrue(primary.isEnabled) }
                }
                val preview = WorkUiFixtures.field<androidx.camera.view.PreviewView>(activity, "preview")
                assertFalse(WorkUiFixtures.descendants(preview.parent as View).contains(live))
                assertTrue(WorkUiFixtures.descendants(activity.findViewById(android.R.id.content)).contains(live))
            }
        }
    }
    @Test fun primaryLayoutFitsTwoViewportsAtBaseAndDoubleTextSize() {
        WorkUiFixtures.emptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
                assertFalse(root is android.widget.ScrollView)
                val density = activity.resources.displayMetrics.density
                val texts = WorkUiFixtures.descendants(root).filterIsInstance<TextView>()
                val sizes = texts.associateWith { it.textSize }
                for (factor in listOf(1f, 2f)) {
                    // Labelled linear text-scale fixture; real nonlinear system-font UAT is separate.
                    sizes.forEach { (text, px) -> text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px * factor) }
                    for ((widthDp, heightDp) in listOf(360 to 640, 412 to 915)) {
                        val width = (widthDp * density).toInt(); val height = (heightDp * density).toInt()
                        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, width, height)
                        val controls = WorkUiFixtures.descendants(root).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE }
                        controls.forEach { button ->
                            val rectangle = android.graphics.Rect(); button.getDrawingRect(rectangle)
                            (root as ViewGroup).offsetDescendantRectToMyCoords(button, rectangle)
                            assertTrue("${button.text} bounds $rectangle at $widthDp/$heightDp/$factor", rectangle.top >= 0 && rectangle.bottom <= height && rectangle.left >= 0 && rectangle.right <= width)
                            assertTrue(button.height >= 48 * density); assertTrue(button.width >= 48 * density)
                        }
                        val primary = WorkUiFixtures.field<Button>(activity, "capture")
                        assertTrue(primary.width >= 72 * density && primary.height >= 72 * density)
                    }
                }
                sizes.forEach { (text, px) -> text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px) }
            }
        }
    }
}
