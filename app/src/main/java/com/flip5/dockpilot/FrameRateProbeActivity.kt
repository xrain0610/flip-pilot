package com.flip5.dockpilot

import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity

/**
 * Diagnostic only: declares a 120 Hz frame-rate request on its surface so DisplayModeDirector
 * votes for the high-refresh mode. Launch it on a specific display with
 *
 *   adb shell am start --display <displayId> -n com.flip5.dockpilot/.FrameRateProbeActivity
 *
 * and then check whether that display's mode switches to 120 Hz.
 */
class FrameRateProbeActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            holder.surface.setFrameRate(
                120f,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            )
            AppLog.info("FrameRateProbe: 已在 display 上请求 120Hz")
        } catch (t: Throwable) {
            AppLog.warn("FrameRateProbe: setFrameRate 失败 ${t.message}")
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
}
