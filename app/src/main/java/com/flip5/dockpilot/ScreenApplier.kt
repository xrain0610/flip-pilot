package com.flip5.dockpilot

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.view.Display

/**
 * Applies a [ScreenProfile] using the same strategy as SecondScreen:
 *
 *  - Desktop mode (DeX): `wm size WxH -d <displayId>` — the external display has its own WM state.
 *  - Mirror mode: `wm size WxH` — global override on display 0, which the external panel mirrors.
 *
 * Setting a mode by display id (`set-user-preferred-display-mode`, `wm size -d` on the HDMI
 * display) is ignored by this ROM, so it is not used here.
 */
object ScreenApplier {

    fun isDesktopMode(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            ?: return false
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_DESK
    }

    /** `-d <id>` only in desktop mode; mirror mode uses the global override. */
    private fun displaySuffix(context: Context, display: Display): String =
        if (isDesktopMode(context)) " -d ${display.displayId}" else ""

    fun commands(context: Context, display: Display, profile: ScreenProfile): List<String> {
        val suffix = displaySuffix(context, display)
        val commands = mutableListOf<String>()
        if (profile.width > 0 && profile.height > 0) {
            commands += "wm size ${profile.width}x${profile.height}$suffix"
        }
        if (profile.density > 0) {
            commands += "wm density ${profile.density}$suffix"
        }
        return commands
    }

    fun resetCommands(context: Context, display: Display): List<String> {
        val suffix = displaySuffix(context, display)
        return listOf("wm size reset$suffix", "wm density reset$suffix")
    }

    /** Runs the profile's commands. Returns true when every command reported success. */
    fun apply(context: Context, display: Display, profile: ScreenProfile): Boolean {
        var allOk = true
        for (command in commands(context, display, profile)) {
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

    fun reset(context: Context, display: Display): Boolean {
        var allOk = true
        for (command in resetCommands(context, display)) {
            val result = Shell.exec(command)
            if (!result.ok) {
                allOk = false
                AppLog.warn("屏幕配置重置失败: $command -> ${result.text}")
            }
        }
        if (allOk) AppLog.info("已重置分辨率与密度（${display.name}）")
        return allOk
    }
}
