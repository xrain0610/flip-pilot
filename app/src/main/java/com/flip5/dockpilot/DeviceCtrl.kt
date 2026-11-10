package com.flip5.dockpilot

/**
 * The exact shell commands that drive the fold state / orientation.
 *
 * All conclusions below were measured on the device and are recorded in ZFLIP5-掌机改造笔记.md:
 *  - `cmd device_state state 3` (unfolded) is persistent, no keep-alive loop is needed.
 *  - `wm user-rotation ...` reports success but does not apply; only `settings put system` works.
 *  - a showing keyguard forces portrait, so the keyguard must be dismissed after each switch.
 */
object DeviceCtrl {

    const val STATE_FOLDED = 0
    const val STATE_UNFOLDED = 3

    private val stateRegex = Regex("""(?i)(?:current[_\s]?state|state)\D{0,12}(\d+)""")

    /** Reads `cmd device_state print-state`; null when the output cannot be parsed. */
    fun queryState(): Int? {
        val r = Shell.exec("cmd device_state print-state")
        if (!r.ok) {
            AppLog.warn("读取折叠状态失败: ${r.text}")
            return null
        }
        val m = stateRegex.find(r.stdout)
        if (m != null) return m.groupValues[1].toIntOrNull()
        // Fallback: the last standalone integer in the output.
        return Regex("""\b(\d)\b""").findAll(r.stdout).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }

    fun expand(): Shell.Result = runState(STATE_UNFOLDED)

    fun fold(): Shell.Result = runState(STATE_FOLDED)

    /** Wakes the device; a display that stayed in OFF survives a state switch otherwise. */
    fun wakeUp(): Shell.Result = Shell.exec("input keyevent KEYCODE_WAKEUP")

    private fun runState(state: Int): Shell.Result {
        val r = Shell.exec("cmd device_state state $state")
        if (r.ok) {
            AppLog.info("device_state state $state 已下发")
        } else {
            AppLog.error("device_state state $state 失败: ${r.text}")
        }
        return r
    }

    /** Lock landscape via settings (the only reliable route on this device). */
    fun lockLandscape(rotation: Int): Shell.Result {
        val r = Shell.exec(
            "settings put system accelerometer_rotation 0; " +
                "settings put system user_rotation $rotation"
        )
        if (r.ok) {
            AppLog.info("已锁定横屏 rotation=$rotation")
        } else {
            AppLog.error("锁定横屏失败: ${r.text}")
        }
        return r
    }

    fun restoreAutoRotate(): Shell.Result =
        Shell.exec("settings put system accelerometer_rotation 1")

    /** Disable the keyguard; a showing keyguard silently forces portrait. */
    fun unlockKeyguard(): Shell.Result {
        val r = Shell.exec("locksettings set-disabled true; wm dismiss-keyguard")
        if (r.ok) {
            AppLog.info("已解除锁屏")
        } else {
            AppLog.warn("解除锁屏失败: ${r.text}")
        }
        return r
    }

    fun setImmersive(enabled: Boolean): Shell.Result {
        val value = if (enabled) "immersive.full=*" else "null"
        val r = Shell.exec("settings put global policy_control $value")
        if (r.ok) {
            AppLog.info("沉浸全屏: $value")
        } else {
            AppLog.warn("设置沉浸全屏失败: ${r.text}")
        }
        return r
    }
}
