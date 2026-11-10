package com.flip5.dockpilot

import android.view.Display

/**
 * Identifies a panel across plug/unplug cycles.
 *
 * `displayId` and `DisplayInfo.uniqueId` are regenerated on every hotplug, so neither can key a
 * saved profile. What survives is the panel itself: its EDID name (`Display.getName()`), its
 * physical resolution and the set of modes it advertises. Same model panels share a profile —
 * telling two identical panels apart would need the EDID serial, which the public API hides.
 */
object ScreenIdentity {

    /** Stable key for saved configuration. */
    fun key(display: Display): String {
        val mode = display.mode
        val modes = display.supportedModes
            .map { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate.toInt()}" }
            .sorted()
            .joinToString(",")
        return "${display.name}|${mode.physicalWidth}x${mode.physicalHeight}|${modes.hashCode()}"
    }

    /** Short digest for display in the UI. */
    fun shortKey(display: Display): String {
        val digest = key(display).hashCode().toUInt().toString(16).padStart(8, '0')
        return "#$digest"
    }

    /** Human readable label, e.g. `TL070FVXS02 · 1920x1080`. */
    fun label(display: Display): String {
        val mode = display.mode
        return "${display.name} · ${mode.physicalWidth}x${mode.physicalHeight}"
    }
}
