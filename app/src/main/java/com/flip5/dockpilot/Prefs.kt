package com.flip5.dockpilot

import android.content.Context
import org.json.JSONObject

/** Small settings store: monitor switch, last applied fold state and tuning knobs. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("dockpilot", Context.MODE_PRIVATE)

    var monitorEnabled: Boolean
        get() = sp.getBoolean(KEY_MONITOR, false)
        set(value) = sp.edit().putBoolean(KEY_MONITOR, value).apply()

    /** Last fold state we actually pushed, -1 when unknown. Used to avoid duplicate commands. */
    var lastAppliedState: Int
        get() = sp.getInt(KEY_LAST_STATE, -1)
        set(value) = sp.edit().putInt(KEY_LAST_STATE, value).apply()

    /** State wanted while Shizuku was unavailable; replayed when the binder comes back. */
    var pendingState: Int
        get() = sp.getInt(KEY_PENDING_STATE, -1)
        set(value) = sp.edit().putInt(KEY_PENDING_STATE, value).apply()

    /** Start the monitor after a reboot, when monitoring itself is enabled. */
    var bootEnabled: Boolean
        get() = sp.getBoolean(KEY_BOOT, true)
        set(value) = sp.edit().putBoolean(KEY_BOOT, value).apply()

    /** Lock landscape right after unfolding. */
    var lockLandscape: Boolean
        get() = sp.getBoolean(KEY_LOCK_LANDSCAPE, true)
        set(value) = sp.edit().putBoolean(KEY_LOCK_LANDSCAPE, value).apply()

    /** Landscape rotation: 3 = 270°, 1 = 90°. */
    var landscapeRotation: Int
        get() = sp.getInt(KEY_ROTATION, 3)
        set(value) = sp.edit().putInt(KEY_ROTATION, value).apply()

    var lastDisplayId: Int
        get() = sp.getInt(KEY_DISPLAY_ID, -1)
        set(value) = sp.edit().putInt(KEY_DISPLAY_ID, value).apply()

    var disableKeyguard: Boolean
        get() = sp.getBoolean(KEY_DISABLE_KEYGUARD, true)
        set(value) = sp.edit().putBoolean(KEY_DISABLE_KEYGUARD, value).apply()

    var immersiveMode: Boolean
        get() = sp.getBoolean(KEY_IMMERSIVE, true)
        set(value) = sp.edit().putBoolean(KEY_IMMERSIVE, value).apply()

    /** Per-panel configuration, keyed by [ScreenIdentity.key]. */
    fun loadScreenProfile(key: String): ScreenProfile? {
        val raw = sp.getString(profileKey(key), null) ?: return null
        return try {
            ScreenProfile.fromJson(JSONObject(raw))
        } catch (t: Throwable) {
            null
        }
    }

    fun saveScreenProfile(profile: ScreenProfile) {
        sp.edit().putString(profileKey(profile.key), profile.toJson().toString()).apply()
    }

    fun deleteScreenProfile(key: String) {
        sp.edit().remove(profileKey(key)).apply()
    }

    private fun profileKey(key: String): String =
        "screen_profile_" + key.hashCode().toUInt().toString(16)

    private companion object {
        const val KEY_MONITOR = "monitor_enabled"
        const val KEY_BOOT = "boot_enabled"
        const val KEY_LOCK_LANDSCAPE = "lock_landscape"
        const val KEY_LAST_STATE = "last_applied_state"
        const val KEY_PENDING_STATE = "pending_state"
        const val KEY_ROTATION = "landscape_rotation"
        const val KEY_DISPLAY_ID = "last_display_id"
        const val KEY_DISABLE_KEYGUARD = "disable_keyguard"
        const val KEY_IMMERSIVE = "immersive"
    }
}
