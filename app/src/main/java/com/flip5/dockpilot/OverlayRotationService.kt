package com.flip5.dockpilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Forces the display orientation by attaching a 1x1 transparent accessibility overlay whose
 * `LayoutParams.screenOrientation` asks for landscape.
 *
 * This is the same trick the Rotation app uses: the overlay sits above every application window,
 * so WindowManager follows its requested orientation instead of the app's own manifest value.
 * That is what makes portrait-only apps (for example UU远程) rotate to landscape.
 */
class OverlayRotationService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: OverlayRotationService? = null

        fun isConnected(): Boolean = instance != null

        /** Returns false when the service is not bound yet. */
        fun setForcedLandscape(enabled: Boolean): Boolean {
            val service = instance ?: return false
            return service.applyLandscape(enabled)
        }

        /**
         * Enables the accessibility service through Shizuku (no manual settings trip) and waits
         * for the binder connection. Safe to call from a background thread.
         */
        fun ensureEnabled(): Boolean {
            if (isConnected()) return true
            if (!Shell.hasPermission()) return false
            val component = "com.flip5.dockpilot/com.flip5.dockpilot.OverlayRotationService"
            val current = Shell.exec("settings get secure enabled_accessibility_services").stdout.trim()
            if (!current.contains("OverlayRotationService")) {
                val updated = if (current.isBlank() || current == "null") {
                    component
                } else {
                    "$current:$component"
                }
                Shell.exec("settings put secure enabled_accessibility_services '$updated'")
                Shell.exec("settings put secure accessibility_enabled 1")
            }
            repeat(25) {
                if (isConnected()) return true
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return false
        }
    }

    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val windowManager: WindowManager
        get() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo?.apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            eventTypes = 0
            notificationTimeout = 100
        }
        AppLog.info("无障碍服务已连接（强制横屏）")
        // Restore the state chosen in the UI when the service reconnects.
        if (Prefs(this).forceLandscape) {
            applyLandscape(true)
            DeviceCtrl.setSensorRotation(false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        removeOverlay()
        super.onDestroy()
    }

    private fun applyLandscape(enabled: Boolean): Boolean {
        return try {
            if (enabled) {
                attachOverlay(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            } else {
                removeOverlay()
            }
            true
        } catch (t: Throwable) {
            AppLog.error("强制横屏失败: ${t.message}")
            false
        }
    }

    private fun attachOverlay(orientation: Int) {
        val existing = overlayView
        if (existing != null) {
            val params = layoutParams ?: return
            params.screenOrientation = orientation
            windowManager.updateViewLayout(existing, params)
            return
        }

        val view = View(this)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            screenOrientation = orientation
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(view, params)
        overlayView = view
        layoutParams = params
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Throwable) {
            }
        }
        overlayView = null
        layoutParams = null
    }
}
