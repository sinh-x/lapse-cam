package com.sinh.timelapse

import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.view.inspector.WindowInspector
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraSettingsSheetTest {
    @Test fun rawInvalidCapAndSelectionsSurviveDismissalAndRecreationWithoutStartingMedia() {
        WorkUiFixtures.emptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                WorkUiFixtures.field<Button>(activity, "timestampSettings").performClick()
                WorkUiFixtures.field<EditText>(activity, "maxSeconds").setText("invalid")
                WorkUiFixtures.field<Spinner>(activity, "fps").setSelection(1)
            }
            WorkUiFixtures.idle()
            scenario.onActivity {
                WindowInspector.getGlobalWindowViews().flatMap(WorkUiFixtures::descendants).filterIsInstance<Button>()
                    .single { it.text == "Done" }.performClick()
            }
            scenario.recreate(); WorkUiFixtures.idle()
            scenario.onActivity { activity ->
                assertEquals("invalid", WorkUiFixtures.field<EditText>(activity, "maxSeconds").text.toString())
                assertEquals(1, WorkUiFixtures.field<Spinner>(activity, "fps").selectedItemPosition)
                val state = ViewModelProvider(activity)[CaptureViewModel::class.java].state.value!!
                assertNull(state.session); assertFalse(state.capturing); assertFalse(state.exporting)
                // Inject handler entry (not a physical camera trial); validation must precede model admission.
                MainActivity::class.java.getDeclaredMethod("captureAction").apply { isAccessible = true }.invoke(activity)
                assertNotNull(WorkUiFixtures.field<EditText>(activity, "maxSeconds").error)
                assertNull(ViewModelProvider(activity)[CaptureViewModel::class.java].state.value!!.session)
            }
        }
    }
    @Test fun nativeBottomSheetDismissalDoesNotReplacePreviewOwner() {
        WorkUiFixtures.emptyActivity().use { scenario ->
            scenario.onActivity { activity ->
                val preview = WorkUiFixtures.field<androidx.camera.view.PreviewView>(activity, "preview")
                val owner = ViewModelProvider(activity)[CaptureViewModel::class.java]
                WorkUiFixtures.field<Button>(activity, "timestampSettings").performClick()
                val sheet = WorkUiFixtures.field<CameraSettingsSheet>(activity, "sheet")
                assertEquals(android.view.Gravity.BOTTOM, sheet.window!!.attributes.gravity)
                sheet.dismiss()
                assertSame(preview, WorkUiFixtures.field<androidx.camera.view.PreviewView>(activity, "preview"))
                assertSame(owner, ViewModelProvider(activity)[CaptureViewModel::class.java])
                assertFalse(owner.state.value!!.capturing)
            }
        }
    }
}
