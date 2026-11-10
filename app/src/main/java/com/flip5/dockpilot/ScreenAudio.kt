package com.flip5.dockpilot

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.os.Build

/**
 * Audio routing around HDMI.
 *
 * Third-party apps cannot move media audio between outputs on their own: MediaRouter2 only exposes
 * [MediaRouter2.getSystemController], and the audio strategy API needs MODIFY_AUDIO_ROUTING, which
 * is a privileged permission. The supported route is the system media output picker, which exists
 * publicly from Android 14 (`MediaRouter2.showSystemOutputSwitcher`).
 */
object ScreenAudio {

    fun hasHdmiOutput(context: Context): Boolean {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_HDMI }
    }

    /**
     * Which output media audio currently goes to.
     *
     * MediaRouter2 exposes the same route list the system output picker shows, and exactly one
     * route is marked selected. Falls back to "HDMI available?" when no route is reported.
     * (`AudioManager.getDevicesForAttributes` would be ideal but it is a system API.)
     */
    fun currentOutput(context: Context): String? {
        try {
            val selected = MediaRouter2.getInstance(context)
                .systemController
                .selectedRoutes
                .firstOrNull()
            if (selected != null) {
                return when (selected.type) {
                    MediaRoute2Info.TYPE_HDMI,
                    MediaRoute2Info.TYPE_HDMI_ARC,
                    MediaRoute2Info.TYPE_HDMI_EARC -> "外接屏"

                    MediaRoute2Info.TYPE_BUILTIN_SPEAKER -> "手机扬声器"

                    MediaRoute2Info.TYPE_BLUETOOTH_A2DP,
                    MediaRoute2Info.TYPE_BLE_HEADSET -> "蓝牙"

                    MediaRoute2Info.TYPE_USB_HEADSET,
                    MediaRoute2Info.TYPE_USB_DEVICE -> "USB 音频"

                    MediaRoute2Info.TYPE_WIRED_HEADPHONES,
                    MediaRoute2Info.TYPE_WIRED_HEADSET -> "有线耳机"

                    else -> selected.name?.toString()?.takeIf { it.isNotBlank() } ?: "类型 ${selected.type}"
                }
            }
        } catch (_: Throwable) {
        }
        return if (hasHdmiOutput(context)) "外接屏" else "手机扬声器"
    }

    fun supportsOutputSwitcher(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Opens the system media output picker. Returns false when the platform has no such API. */
    fun showOutputSwitcher(context: Context): Boolean {
        if (!supportsOutputSwitcher()) return false
        return try {
            MediaRouter2.getInstance(context).showSystemOutputSwitcher()
            AppLog.info("已打开系统媒体输出选择器")
            true
        } catch (t: Throwable) {
            AppLog.warn("打开媒体输出选择器失败: ${t.message}")
            false
        }
    }
}
