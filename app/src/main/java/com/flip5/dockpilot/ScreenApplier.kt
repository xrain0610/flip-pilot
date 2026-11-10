package com.flip5.dockpilot

import android.view.Display

/**
 * Applies a [ScreenProfile] to a display.
 *
 * Measured on this device:
 *  - `wm density <dpi> -d <id>` works (`Override density` shows up on read back).
 *  - `wm size <WxH> -d <id>` is ignored for the external display.
 *  - `cmd display set-user-preferred-display-mode` is accepted but the ROM keeps its own mode.
 *
 * So mode changes are attempted and reported honestly; density is the part that actually sticks.
 */
object ScreenApplier {

    fun modeAndDensityCommands(display: Display, profile: ScreenProfile): List<String> {
        val commands = mutableListOf<String>()
        if (profile.width > 0 && profile.height > 0 && profile.refreshRate > 0f) {
            commands += "cmd display set-user-preferred-display-mode " +
                "${profile.width} ${profile.height} ${profile.refreshRate} ${display.displayId}"
        }
        if (profile.density > 0) {
            commands += "wm density ${profile.density} -d ${display.displayId}"
        }
        return commands
    }

    fun resetCommands(display: Display): List<String> = listOf(
        "cmd display clear-user-preferred-display-mode ${display.displayId}",
        "wm density reset -d ${display.displayId}",
    )

    /** Runs the profile's commands. Returns true when every command reported success. */
    fun apply(display: Display, profile: ScreenProfile): Boolean {
        var allOk = true
        for (command in modeAndDensityCommands(display, profile)) {
            val result = Shell.exec(command)
            if (result.ok) {
                AppLog.info("屏幕配置: $command")
            } else {
                allOk = false
                AppLog.warn("屏幕配置失败: $command -> ${result.text}")
            }
        }
        return allOk
    }

    fun reset(display: Display): Boolean {
        var allOk = true
        for (command in resetCommands(display)) {
            val result = Shell.exec(command)
            if (!result.ok) {
                allOk = false
                AppLog.warn("屏幕配置重置失败: $command -> ${result.text}")
            }
        }
        if (allOk) AppLog.info("已重置屏幕 ${display.name} 的模式与密度")
        return allOk
    }
}
