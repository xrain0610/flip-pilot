package com.flip5.dockpilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the monitor after a reboot, but only when the user left it enabled. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (!prefs.bootEnabled) {
            AppLog.info("开机：未开启自动启动，跳过")
            return
        }
        if (!prefs.monitorEnabled) {
            AppLog.info("开机：监控未启用，跳过自启")
            return
        }
        AppLog.info("开机：启动监控服务")
        DisplayMonitorService.start(context)
    }
}
