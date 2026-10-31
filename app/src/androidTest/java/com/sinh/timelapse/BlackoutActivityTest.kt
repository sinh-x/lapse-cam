package com.sinh.timelapse

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI/clock/input fixtures only. No shutter, network enrollment, ADB or physical UAT. */
@RunWith(AndroidJUnit4::class)
class BlackoutActivityTest {
    private fun set(activity: MainActivity, name: String, value: Any) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
    private fun call(activity: MainActivity, name: String) {
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }
    private fun render(activity: MainActivity, state: CaptureViewModel.State) {
        MainActivity::class.java.getDeclaredMethod("renderCapture", CaptureViewModel.State::class.java)
            .apply { isAccessible = true }.invoke(activity, state)
    }
    private val liveState = CaptureViewModel.State(loading = false, capturing = true, boundLens = SessionLens.REAR)
    private class Fixture(val activity: MainActivity) {
        var now = 0L
        val policy = BlackoutPolicy { now }
        val cover: FrameLayout get() = WorkUiFixtures.field(activity, "blackoutCover")
        val ui: ViewGroup get() = WorkUiFixtures.field(activity, "cameraUi")
        fun show() {
            now += 30_000
            policy.timeout(checkNotNull(policy.timer))
            MainActivity::class.java.getDeclaredMethod("renderBlackout").apply { isAccessible = true }.invoke(activity)
            assertEquals(View.VISIBLE, cover.visibility)
        }
        fun motion(action: Int, x: Float = 20f, y: Float = 20f) {
            val t = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(t, t, action, x, y, 0)
            val mustConsume = cover.visibility == View.VISIBLE || WorkUiFixtures.field<Boolean>(activity, "consumingTouch")
            try {
                val handled = activity.dispatchTouchEvent(event)
                if (mustConsume) assertTrue(handled)
            } finally { event.recycle() }
        }
        fun key(action: Int, key: Int) { assertTrue(activity.window.callback.dispatchKeyEvent(KeyEvent(action, key))) }
    }
    private fun fixture(activity: MainActivity): Fixture {
        val f = Fixture(activity)
        set(activity, "blackout", f.policy)
        set(activity, "activityForeground", true); set(activity, "activityFocused", true)
        render(activity, liveState)
        assertNotNull(f.policy.timer)
        return f
    }

    @Test fun opaqueCoverAndExactBannerFitBothViewportsAtDoubleTextSizeWithoutRemovingPreview() {
        WorkUiFixtures.emptyActivity().use { scenario -> scenario.onActivity { activity ->
            val f = fixture(activity)
            val preview = WorkUiFixtures.field<androidx.camera.view.PreviewView>(activity, "preview")
            val previewParent = preview.parent
            val owner = ViewModelProvider(activity)[CaptureViewModel::class.java]
            val brightness = activity.window.attributes.screenBrightness
            val flags = activity.window.attributes.flags
            f.show()
            assertEquals(Color.BLACK, (f.cover.background as ColorDrawable).color)
            val banner = WorkUiFixtures.field<TextView>(activity, "blackoutBanner")
            assertEquals("LIVE — recording", banner.text.toString())
            assertEquals(Color.rgb(170, 0, 0), (banner.background as ColorDrawable).color)
            assertEquals(Color.WHITE, banner.currentTextColor)
            assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16f,
                activity.resources.displayMetrics), banner.textSize, 0.1f)
            val density = activity.resources.displayMetrics.density
            assertEquals((12 * density).toInt(), banner.paddingLeft)
            assertEquals((6 * density).toInt(), banner.paddingTop)
            val px = banner.textSize
            for (factor in listOf(1f, 2f)) for ((w, h) in listOf(360 to 640, 412 to 915)) {
                banner.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px * factor)
                val width = (w * density).toInt(); val height = (h * density).toInt()
                f.cover.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                f.cover.layout(0, 0, width, height)
                assertTrue(banner.left >= f.cover.paddingLeft && banner.right <= width - f.cover.paddingRight)
                assertTrue(banner.top >= f.cover.paddingTop && banner.bottom <= height - f.cover.paddingBottom)
                assertTrue(banner.height >= banner.layout.height + banner.paddingTop + banner.paddingBottom)
                assertEquals(1, f.cover.childCount)
            }
            assertSame(previewParent, preview.parent); assertEquals(View.VISIBLE, preview.visibility)
            assertSame(owner, ViewModelProvider(activity)[CaptureViewModel::class.java])
            assertFalse(WorkUiFixtures.descendants(preview.parent as View).contains(f.cover))
            f.cover.performClick()
            assertEquals(brightness, activity.window.attributes.screenBrightness, 0f)
            assertEquals(flags, activity.window.attributes.flags)
            assertSame(previewParent, preview.parent)
        } }
    }

    @Test fun immersiveCoverHidesBothSystemBarsAndRestoresThemOnReturnAndFocusLoss() {
        WorkUiFixtures.emptyActivity().use { scenario ->
            lateinit var f: Fixture
            var originalBehavior = 0
            scenario.onActivity { activity ->
                f = fixture(activity)
                originalBehavior = activity.window.insetsController!!.systemBarsBehavior
                f.show()
                assertEquals(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    activity.window.insetsController!!.systemBarsBehavior)
            }
            fun barsVisible(activity: MainActivity, visible: Boolean): Boolean {
                val insets = activity.window.decorView.rootWindowInsets ?: return false
                return insets.isVisible(android.view.WindowInsets.Type.statusBars()) == visible &&
                    insets.isVisible(android.view.WindowInsets.Type.navigationBars()) == visible
            }
            WorkUiFixtures.await(scenario) { barsVisible(it, false) }
            scenario.onActivity { activity ->
                f.cover.performClick()
                assertEquals(View.GONE, f.cover.visibility)
                assertEquals(originalBehavior, activity.window.insetsController!!.systemBarsBehavior)
            }
            WorkUiFixtures.await(scenario) { barsVisible(it, true) }
            scenario.onActivity { f.show() }
            WorkUiFixtures.await(scenario) { barsVisible(it, false) }
            scenario.onActivity { activity ->
                activity.onWindowFocusChanged(false)
                assertEquals(View.GONE, f.cover.visibility)
                assertNull(f.policy.timer)
                assertEquals(originalBehavior, activity.window.insetsController!!.systemBarsBehavior)
            }
            WorkUiFixtures.await(scenario) { barsVisible(it, true) }
        }
    }

    @Test fun touchReturnConsumesDownMoveUpCancelAndHiddenActionThenNextTapWorks() {
        WorkUiFixtures.emptyActivity().use { scenario ->
            var actions = 0
            lateinit var f: Fixture
            scenario.onActivity { activity ->
            f = fixture(activity)
            val buttons = WorkUiFixtures.descendants(f.ui).filterIsInstance<Button>().filter { it.visibility == View.VISIBLE && it.width > 0 }
            assertTrue(buttons.isNotEmpty())
            buttons.forEach { it.setOnClickListener { actions++ } }
            for (button in buttons) {
                val position = IntArray(2); button.getLocationInWindow(position)
                val x = position[0] + button.width / 2f; val y = position[1] + button.height / 2f
                for (terminal in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                    f.show()
                    f.motion(MotionEvent.ACTION_DOWN, x, y)
                    assertEquals(View.VISIBLE, f.cover.visibility)
                    f.motion(MotionEvent.ACTION_MOVE, x, y)
                    assertEquals(View.VISIBLE, f.cover.visibility)
                    f.motion(terminal, x, y)
                    assertEquals(View.GONE, f.cover.visibility); assertEquals(0, actions)
                }
            }
            val button = buttons.first(); val p = IntArray(2); button.getLocationInWindow(p)
            f.motion(MotionEvent.ACTION_DOWN, p[0] + button.width / 2f, p[1] + button.height / 2f)
            f.motion(MotionEvent.ACTION_UP, p[0] + button.width / 2f, p[1] + button.height / 2f)
            }
            // View posts performClick after UP. Let that real dispatch complete before
            // checking the next deliberate action, rather than asserting in the same loop turn.
            WorkUiFixtures.idle()
            scenario.onActivity {
                assertEquals(1, actions)
                assertEquals(f.now + 30_000, f.policy.timer!!.deadline)
            }
        }
    }

    @Test fun multipointerAndErrorDuringReturnNeverLeakRemainder() {
        WorkUiFixtures.emptyActivity().use { scenario -> scenario.onActivity { activity ->
            val f = fixture(activity); f.show()
            var actions = 0
            WorkUiFixtures.descendants(f.ui).filterIsInstance<Button>().forEach { it.setOnClickListener { actions++ } }
            f.motion(MotionEvent.ACTION_DOWN)
            render(activity, liveState.copy(savingPhoto = true))
            assertEquals(View.VISIBLE, f.cover.visibility)
            val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(2) { MotionEvent.PointerCoords().apply { x = 20f; y = 20f; pressure = 1f; size = 1f } }
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP)) {
                val event = MotionEvent.obtain(time, time, action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { assertTrue(activity.dispatchTouchEvent(event)) } finally { event.recycle() }
                assertEquals(View.VISIBLE, f.cover.visibility)
            }
            render(activity, liveState.copy(cameraMessage = "Camera error"))
            assertEquals(View.GONE, f.cover.visibility)
            f.motion(MotionEvent.ACTION_MOVE); f.motion(MotionEvent.ACTION_UP)
            assertEquals(0, actions); assertNull(f.policy.timer)
        } }
    }

    @Test fun accessibilityKeysAndFirstBackOnlyReturnWithNeutralFocus() {
        WorkUiFixtures.emptyActivity().use { scenario -> scenario.onActivity { activity ->
            val f = fixture(activity)
            var actions = 0
            WorkUiFixtures.descendants(f.ui).filterIsInstance<Button>().forEach { it.setOnClickListener { actions++ } }
            for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER)) {
                f.show()
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, f.ui.importantForAccessibility)
                assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, f.ui.descendantFocusability)
                assertTrue(f.cover.hasFocus())
                f.key(KeyEvent.ACTION_DOWN, key); f.key(KeyEvent.ACTION_DOWN, key)
                render(activity, liveState.copy(exporting = true, progress = 12))
                assertEquals(View.VISIBLE, f.cover.visibility)
                f.key(KeyEvent.ACTION_UP, key)
                assertEquals(View.GONE, f.cover.visibility); assertEquals(0, actions)
                assertSame(f.ui, activity.currentFocus)
            }
            f.show()
            assertTrue(f.cover.contentDescription.contains("Recording timelapse"))
            val node = f.cover.createAccessibilityNodeInfo()
            assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label.toString().contains("Return to Camera") })
            assertTrue(f.cover.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(View.GONE, f.cover.visibility); assertEquals(0, actions)
            f.show(); activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(View.GONE, f.cover.visibility); assertEquals(0, actions)
        } }
    }

    @Test fun heldInputDefersTimeoutAndStaleCallbackCannotCoverAfterFocusLoss() {
        WorkUiFixtures.emptyActivity().use { scenario -> scenario.onActivity { activity ->
            val f = fixture(activity); val stale = f.policy.timer!!
            f.motion(MotionEvent.ACTION_DOWN)
            f.now = 60_000; f.policy.timeout(stale); call(activity, "renderBlackout")
            assertEquals(View.GONE, f.cover.visibility); assertNull(f.policy.timer)
            f.motion(MotionEvent.ACTION_CANCEL)
            assertEquals(90_000L, f.policy.timer!!.deadline)
            val callback = WorkUiFixtures.field<Runnable>(activity, "blackoutCallback")
            activity.onWindowFocusChanged(false)
            f.now = 120_000; callback.run()
            assertEquals(View.GONE, f.cover.visibility); assertNull(f.policy.timer)
            activity.onWindowFocusChanged(true)
            assertEquals(150_000L, f.policy.timer!!.deadline)
            f.show(); f.key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
            activity.onWindowFocusChanged(false); activity.onWindowFocusChanged(true)
            // An interrupted key stream is no longer held input, but its orphan UP is swallowed.
            assertEquals(f.now + 30_000, f.policy.timer!!.deadline)
            f.key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
            assertEquals(View.GONE, f.cover.visibility)
        } }
    }

    @Test fun nonLiveReadinessErrorAndPickerTransitionsClearAndRestart() {
        WorkUiFixtures.emptyActivity().use { scenario -> scenario.onActivity { activity ->
            val f = fixture(activity)
            for (state in listOf(liveState.copy(capturing = false), liveState.copy(loading = true),
                liveState.copy(switching = true), liveState.copy(working = true), liveState.copy(boundLens = null),
                liveState.copy(recoveryBlocked = true), liveState.copy(message = "Session queue needs attention"))) {
                f.show(); render(activity, state)
                assertEquals(View.GONE, f.cover.visibility); assertNull(f.policy.timer)
                render(activity, liveState); assertEquals(f.now + 30_000, f.policy.timer!!.deadline)
            }
            val deadline = f.policy.timer
            render(activity, liveState.copy(savingPhoto = true, exporting = true, progress = 23))
            assertEquals(deadline, f.policy.timer)
            f.show(); set(activity, "downloadPickerPending", true); call(activity, "updateBlackout")
            assertEquals(View.GONE, f.cover.visibility); assertNull(f.policy.timer)
            set(activity, "downloadPickerPending", false); call(activity, "updateBlackout")
            assertEquals(f.now + 30_000, f.policy.timer!!.deadline)
        } }
    }

    @Test fun settingsAndSharedHistoryRemainUncoveredUntilFinalDismissAndFocus() {
        WorkUiFixtures.emptyActivity().use { scenario ->
            lateinit var f: Fixture
            scenario.onActivity { activity ->
            f = fixture(activity); f.show()
            call(activity, "showSharedHistory")
            assertEquals(View.GONE, f.cover.visibility); assertNull(f.policy.timer)
            // A nested TTL-014 window may hold focus even after the parent closes.
            activity.onWindowFocusChanged(false)
            val windows = android.view.inspector.WindowInspector.getGlobalWindowViews()
            val close = windows.flatMap(WorkUiFixtures::descendants).filterIsInstance<Button>().last { it.text == "Close" }
            close.performClick()
            assertNull(f.policy.timer)
            }
            // Dialog dismissal delivers its listener through a queued main-thread message.
            WorkUiFixtures.idle()
            scenario.onActivity { activity ->
            activity.onWindowFocusChanged(true)
            assertEquals(f.now + 30_000, f.policy.timer!!.deadline)
            f.show()
            WorkUiFixtures.descendants(f.ui).filterIsInstance<Button>()
                .single { it.contentDescription == "Camera settings" }.performClick()
            assertEquals(View.GONE, f.cover.visibility); assertNull(f.policy.timer)
            val toggle = WorkUiFixtures.field<CheckBox>(activity, "blackoutToggle")
            assertTrue(toggle.isEnabled)
            val sheet = WorkUiFixtures.field<CameraSettingsSheet>(activity, "sheet")
            sheet.setLocked(true); assertTrue(toggle.isEnabled)
            sheet.dismiss()
            }
            WorkUiFixtures.idle()
            scenario.onActivity { activity ->
                activity.onWindowFocusChanged(true)
                assertEquals(f.now + 30_000, f.policy.timer!!.deadline)
            }
        }
    }

    @Test fun accessibilityFocusResetsIdleButContentUpdatesDoNot() {
        WorkUiFixtures.emptyActivity().use { scenario -> scenario.onActivity { activity ->
            val f = fixture(activity)
            val child = WorkUiFixtures.field<Button>(activity, "capture")
            val original = f.policy.timer
            f.now = 10_000
            // Exercise the installed delegate directly and terminate propagation at the
            // fixture parent. No real accessibility event may be sent to a disabled service.
            // This remains a policy seam, not TalkBack execution or a settings change.
            val parent = f.ui.parent as ViewGroup
            parent.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View,
                    event: android.view.accessibility.AccessibilityEvent): Boolean = false
            }
            f.ui.requestSendAccessibilityEvent(child, android.view.accessibility.AccessibilityEvent(
                android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
            assertEquals(original, f.policy.timer)
            f.ui.requestSendAccessibilityEvent(child, android.view.accessibility.AccessibilityEvent(
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
            assertEquals(40_000L, f.policy.timer!!.deadline)
        } }
    }

    @Test fun preferenceSurvivesRecreationButCoverAndOldCallbackDoNot() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("capture-defaults", 0)
        WorkUiFixtures.emptyActivity().use { scenario ->
            lateinit var oldCallback: Runnable
            lateinit var oldPolicy: BlackoutPolicy
            try {
                scenario.onActivity { activity ->
                    val f = fixture(activity)
                    oldCallback = WorkUiFixtures.field(activity, "blackoutCallback"); oldPolicy = f.policy
                    f.show()
                    WorkUiFixtures.field<CheckBox>(activity, "blackoutToggle").isChecked = false
                    assertEquals(View.GONE, f.cover.visibility); assertNull(f.policy.timer)
                }
                scenario.recreate(); WorkUiFixtures.idle()
                scenario.onActivity { activity ->
                    oldCallback.run()
                    assertFalse(oldPolicy.covered); assertNull(oldPolicy.timer)
                    assertFalse(WorkUiFixtures.field<CheckBox>(activity, "blackoutToggle").isChecked)
                    assertEquals(View.GONE, WorkUiFixtures.field<View>(activity, "blackoutCover").visibility)
                    assertFalse(ViewModelProvider(activity)[CaptureViewModel::class.java].state.value!!.capturing)
                }
            } finally {
                // Fixture admitted only with empty defaults; remove only its own new display key.
                assertTrue(preferences.edit().remove("blackoutEnabled").commit())
            }
        }
    }
}
