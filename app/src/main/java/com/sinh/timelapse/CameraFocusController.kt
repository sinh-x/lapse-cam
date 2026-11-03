package com.sinh.timelapse

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import java.util.concurrent.Executor

/** Acquire once, then explicitly disable AF at the measured distance for the whole LIVE segment.
 * No touch-to-focus listener and no automatic reacquisition. Still-capture results are monitored
 * as well as preview results; a non-held observation fails closed before another shutter.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
internal class CameraFocusController(private val executor: Executor) : CaptureFocus {
    private val main = Handler(Looper.getMainLooper())
    private val policy = FocusLockPolicy(SystemClock::elapsedRealtime)
    private var camera: Camera? = null
    private var callback: ((Boolean, String) -> Unit)? = null
    private var distance: Float? = null
    private var fixed = false
    private var manualRequested = false
    private var meteringConfirmed = false
    private var timeout: Runnable? = null
    private var watchdog: Runnable? = null
    private var lastObservation = 0L
    override val held get() = policy.state == FocusLockPolicy.State.HELD

    // Camera2 invokes callbacks on its own thread; serialize all policy/control work on main.
    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val token = policy.generation
            main.post { if (policy.current(token)) observe(token, result) }
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            val token = policy.generation
            main.post { fail(token, "Camera could not verify held focus") }
        }
    }
    fun attach(value: Camera) { camera = value }
    override fun acquire(result: (Boolean, String) -> Unit) {
        release()
        callback = result
        val token = policy.begin()
        timeout = Runnable { if (policy.timeout(token)) notifyFailure("Focus could not be held within five seconds") }
            .also { main.postDelayed(it, FocusLockPolicy.TIMEOUT_MILLIS) }
        try {
            val bound = checkNotNull(camera) { "Selected camera is unavailable" }
            val info = Camera2CameraInfo.from(bound.cameraInfo)
            val minimum = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            fixed = minimum != null && minimum == 0f
            val modes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            check(CaptureRequest.CONTROL_AF_MODE_OFF in modes) { "Selected camera cannot hold manual focus" }
            if (fixed) requestManual(token, null)
            else {
                check(CaptureRequest.CONTROL_AF_MODE_AUTO in modes) { "Selected camera cannot acquire stable focus" }
                val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(.5f, .5f)
                val clear = Camera2CameraControl.from(bound.cameraControl).clearCaptureRequestOptions()
                clear.addListener({
                    if (policy.current(token)) try {
                        clear.get()
                        val future = bound.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).disableAutoCancel().build())
                        future.addListener({
                            if (policy.current(token)) try {
                                check(future.get().isFocusSuccessful) { "Focus acquisition was unsuccessful" }
                                // Only a subsequent locked result can select the held distance.
                                meteringConfirmed = true
                            } catch (error: Exception) { fail(token, error.message ?: "Focus acquisition failed") }
                        }, executor)
                    } catch (error: Exception) { fail(token, "Camera could not begin focus acquisition") }
                }, executor)
            }
        } catch (error: Exception) { fail(token, error.message ?: "Focus is unsupported") }
    }
    private fun acquired(token: Long, result: TotalCaptureResult) {
        if (!meteringConfirmed || manualRequested || result.get(CaptureResult.CONTROL_AF_STATE) != CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) return
        val value = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
        if (value.isFinite() && value >= 0) requestManual(token, value)
    }
    private fun requestManual(token: Long, value: Float?) {
        if (!policy.current(token)) return
        distance = value; manualRequested = true
        val options = CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        if (value != null) options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, value)
        val future = Camera2CameraControl.from(checkNotNull(camera).cameraControl).setCaptureRequestOptions(options.build())
        future.addListener({ if (policy.current(token)) try { future.get() } catch (error: Exception) { fail(token, "Camera rejected held focus") } }, executor)
    }
    private fun observe(token: Long, result: TotalCaptureResult) {
        if (!manualRequested) { acquired(token, result); return }
        val before = policy.state
        val actual = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        val stable = FocusLockPolicy.stable(fixed,
            result.get(CaptureResult.CONTROL_AF_MODE) == CaptureResult.CONTROL_AF_MODE_OFF, actual, distance,
            result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_STATIONARY)
        if (stable) lastObservation = SystemClock.elapsedRealtime()
        when (policy.observe(token, stable)) {
            FocusLockPolicy.State.HELD -> if (before != FocusLockPolicy.State.HELD) {
                timeout?.let(main::removeCallbacks); timeout = null
                watchdog = object : Runnable {
                    override fun run() {
                        if (!policy.current(token) || !held) return
                        if (SystemClock.elapsedRealtime() - lastObservation >= 1500) fail(token, "Focus observations stopped")
                        else main.postDelayed(this, 500)
                    }
                }.also { main.postDelayed(it, 500) }
                callback?.invoke(true, "")
            }
            FocusLockPolicy.State.FAILED -> notifyFailure(if (before == FocusLockPolicy.State.HELD) "Focus hold was lost" else "Focus could not be verified within five seconds")
            else -> Unit
        }
    }
    private fun fail(token: Long, reason: String) { if (policy.fail(token)) notifyFailure(reason) }
    private fun notifyFailure(reason: String) {
        timeout?.let(main::removeCallbacks); timeout = null
        callback?.invoke(false, "$reason. Photos retained. Retry to acquire focus.")
    }
    override fun release() {
        policy.cancel(); timeout?.let(main::removeCallbacks); timeout = null
        watchdog?.let(main::removeCallbacks); watchdog = null
        callback = null; distance = null; manualRequested = false; meteringConfirmed = false
        // Leave the last OFF request in place until an explicit next acquire or camera unbind.
        // Clearing options here would silently restart continuous AF while a shutter drains.
    }
}
