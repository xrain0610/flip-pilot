package com.flip5.dockpilot

import org.json.JSONObject

/**
 * Per-panel configuration. Persisted by [Prefs] and re-applied whenever that panel is plugged in.
 *
 * `width/height/refreshRate` of 0 means "leave the mode alone". `density` of 0 means the same.
 */
data class ScreenProfile(
    val key: String,
    var label: String = "",
    var width: Int = 0,
    var height: Int = 0,
    var refreshRate: Float = 0f,
    var density: Int = 0,
    var rotation: Int = 3,
    /** "screen" = panel speakers, "phone" = phone speaker. */
    var audioOutput: String = AUDIO_SCREEN,
    /** Unfold behaviour for this panel. */
    var lockLandscape: Boolean = true,
    var unlockKeyguard: Boolean = true,
    var immersive: Boolean = true,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("label", label)
        put("width", width)
        put("height", height)
        put("refreshRate", refreshRate.toDouble())
        put("density", density)
        put("rotation", rotation)
        put("audio", audioOutput)
        put("lockLandscape", lockLandscape)
        put("unlockKeyguard", unlockKeyguard)
        put("immersive", immersive)
    }

    companion object {
        const val AUDIO_SCREEN = "screen"
        const val AUDIO_PHONE = "phone"

        fun fromJson(json: JSONObject): ScreenProfile = ScreenProfile(
            key = json.optString("key"),
            label = json.optString("label"),
            width = json.optInt("width", 0),
            height = json.optInt("height", 0),
            refreshRate = json.optDouble("refreshRate", 0.0).toFloat(),
            density = json.optInt("density", 0),
            rotation = json.optInt("rotation", 3),
            audioOutput = json.optString("audio", AUDIO_SCREEN),
            lockLandscape = json.optBoolean("lockLandscape", true),
            unlockKeyguard = json.optBoolean("unlockKeyguard", true),
            immersive = json.optBoolean("immersive", true),
        )
    }
}
