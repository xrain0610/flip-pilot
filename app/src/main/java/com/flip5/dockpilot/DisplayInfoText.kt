package com.flip5.dockpilot

import android.view.Display

/**
 * Formats a display's modes for the UI.
 *
 * `Display.mode` is what the system is driving *right now*; the native rate is the highest mode
 * the panel advertises for the same resolution. On this device the external panel offers 120 Hz
 * while DisplayModeDirector pins the whole display group to the internal screen's 60 Hz, so the
 * two numbers differ and both are worth showing.
 */
object DisplayInfoText {

    private class Rates(val width: Int, val height: Int, val nativeHz: Float, val currentHz: Float)

    private fun rates(display: Display): Rates {
        val mode = display.mode
        val native = display.supportedModes
            .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .maxOfOrNull { it.refreshRate }
            ?: mode.refreshRate
        return Rates(mode.physicalWidth, mode.physicalHeight, native, mode.refreshRate)
    }

    private fun hz(value: Float): String = "${value.toInt()}Hz"

    /** Single line for logs and the notification. */
    fun compact(display: Display): String {
        val r = rates(display)
        return "${r.width}x${r.height}@${hz(r.currentHz)}（原生 ${hz(r.nativeHz)}）"
    }

    /** Multi-line block for the status card. */
    fun detailed(display: Display): String {
        val r = rates(display)
        return buildString {
            append("外接屏：ID ${display.displayId}\n")
            append("原生 ${r.width}x${r.height} @ ${hz(r.nativeHz)}\n")
            append("实际 ${r.width}x${r.height} @ ${hz(r.currentHz)}")
        }
    }
}
