package com.flip5.dockpilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

/**
 * Watches external displays and drives the fold state.
 *
 * Debounce matters: a plug/unplug can fire several display events within a few hundred ms, and
 * every duplicate `cmd device_state` call fights the previous one. 500 ms was enough on device.
 */
class DisplayMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.flip5.dockpilot.action.START"
        const val ACTION_STOP = "com.flip5.dockpilot.action.STOP"

        private const val CHANNEL_ID = "display_monitor"
        private const val NOTIFICATION_ID = 1
        private const val DEBOUNCE_MS = 500L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private val _status = MutableStateFlow("未运行")
        val status: StateFlow<String> = _status

        fun start(context: Context) {
            val intent = Intent(context, DisplayMonitorService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DisplayMonitorService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var displayManager: DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val applyLock = Mutex()

    private var pendingDisplayEvent: Runnable? = null
    private var externalDisplayId = -1
    private var initialSyncDone = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val display = displayManager.getDisplay(displayId) ?: return
            if (display.flags and Display.FLAG_PRESENTATION == 0) return
            scheduleDisplayEvent(displayId, connected = true)
        }

        override fun onDisplayRemoved(displayId: Int) {
            // A removed display is already gone from DisplayManager, so trust the event itself.
            if (displayId != externalDisplayId) return
            scheduleDisplayEvent(displayId, connected = false)
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == externalDisplayId) {
                updateStatus("外接屏 ${describe(displayId)}")
            }
        }
    }

    private val shizukuBinderReceived = Shizuku.OnBinderReceivedListener {
        AppLog.info("Shizuku 已连接")
        replayPendingState()
        updateStatus(if (externalDisplayId >= 0) "外接屏 ${describe(externalDisplayId)}" else "待机 · 无外接屏")
    }

    private val shizukuBinderDead = Shizuku.OnBinderDeadListener {
        AppLog.warn("Shizuku 已断开")
        updateStatus("Shizuku 已断开，等待重连")
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        displayManager = getSystemService(DisplayManager::class.java)
        createChannel()
        displayManager.registerDisplayListener(displayListener, mainHandler)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived)
        Shizuku.addBinderDeadListener(shizukuBinderDead)
        _running.value = true
        AppLog.info("监控服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.info("收到停止指令")
            prefs.monitorEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground(buildNotification("监控中 · 待机"))
        syncFromCurrentDisplays()
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.info("监控服务已停止")
        pendingDisplayEvent?.let { mainHandler.removeCallbacks(it) }
        displayManager.unregisterDisplayListener(displayListener)
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceived)
            Shizuku.removeBinderDeadListener(shizukuBinderDead)
        } catch (_: Throwable) {
        }
        _running.value = false
        _status.value = "未运行"
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- display events ---------------------------------------------------------------

    private fun scheduleDisplayEvent(displayId: Int, connected: Boolean) {
        pendingDisplayEvent?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingDisplayEvent = null
            if (connected) {
                externalDisplayId = displayId
                prefs.lastDisplayId = displayId
                AppLog.info("外接屏接入 displayId=$displayId ${describe(displayId)}")
                applyTarget(DeviceCtrl.STATE_UNFOLDED, "外接屏接入", force = true)
            } else {
                AppLog.info("外接屏断开 displayId=$displayId")
                externalDisplayId = -1
                prefs.lastDisplayId = -1
                applyTarget(DeviceCtrl.STATE_FOLDED, "外接屏断开")
            }
        }
        pendingDisplayEvent = runnable
        mainHandler.postDelayed(runnable, DEBOUNCE_MS)
    }

    /** Boot / service start: adopt whatever is plugged in right now. Only runs once per process. */
    private fun syncFromCurrentDisplays() {
        if (initialSyncDone) return
        initialSyncDone = true
        val presentation = displayManager.displays.firstOrNull {
            it.flags and Display.FLAG_PRESENTATION != 0 && it.displayId != Display.DEFAULT_DISPLAY
        }
        if (presentation != null) {
            externalDisplayId = presentation.displayId
            prefs.lastDisplayId = presentation.displayId
            AppLog.info("启动时就检测到外接屏 ${describe(presentation.displayId)}")
            applyTarget(DeviceCtrl.STATE_UNFOLDED, "启动时已有外接屏", force = true)
        } else {
            AppLog.info("启动时无外接屏")
            updateStatus("待机 · 无外接屏")
        }
    }

    // ---- state application ------------------------------------------------------------

    private fun applyTarget(target: Int, reason: String, force: Boolean = false) {
        if (!prefs.monitorEnabled) {
            AppLog.info("$reason，但监控未启用，忽略")
            return
        }
        if (!Shell.hasPermission()) {
            prefs.pendingState = target
            val message = "$reason：Shizuku 不可用，已排队 state $target"
            AppLog.warn(message)
            updateStatus("等待 Shizuku · 待执行 state $target")
            return
        }

        scope.launch {
            applyLock.withLock {
                val current = DeviceCtrl.queryState()
                // A hot-plugged panel can stay in OFF even when the fold state already matches,
                // so a plug event forces one more state push instead of trusting the cache.
                val alreadyApplied = !force && current == target && prefs.lastAppliedState == target

                if (!alreadyApplied) {
                    val result = if (target == DeviceCtrl.STATE_UNFOLDED) {
                        DeviceCtrl.expand()
                    } else {
                        DeviceCtrl.fold()
                    }
                    if (!result.ok) {
                        prefs.pendingState = target
                        updateStatus("state $target 下发失败")
                        return@withLock
                    }
                    prefs.lastAppliedState = target
                    prefs.pendingState = -1
                    // Give the display manager a moment to settle before touching rotation.
                    delay(300)
                } else {
                    AppLog.info("state $target 已是目标状态，跳过下发")
                }

                if (target == DeviceCtrl.STATE_UNFOLDED) {
                    val externalDisplay = externalDisplayId.takeIf { it >= 0 }
                        ?.let { displayManager.getDisplay(it) }
                    val profile = externalDisplay?.let { prefs.loadScreenProfile(ScreenIdentity.key(it)) }
                    if (force) {
                        AppLog.info(
                            "外接屏 state=" + (externalDisplay?.state?.toString() ?: "n/a") +
                                "（插入后强制下发）"
                        )
                        DeviceCtrl.wakeUp()
                    }
                    // Per-panel behaviour wins; the global prefs are only a fallback.
                    val lock = profile?.lockLandscape ?: prefs.lockLandscape
                    val unlockKeyguard = profile?.unlockKeyguard ?: prefs.disableKeyguard
                    val immersive = profile?.immersive ?: prefs.immersiveMode
                    if (unlockKeyguard) {
                        DeviceCtrl.unlockKeyguard()
                    }
                    if (lock) {
                        DeviceCtrl.lockLandscape(profile?.rotation ?: prefs.landscapeRotation)
                    }
                    if (externalDisplay != null && profile != null) {
                        AppLog.info("应用屏幕配置 ${profile.label}")
                        ScreenApplier.apply(externalDisplay, profile)
                    }
                    if (immersive) {
                        DeviceCtrl.setImmersive(true)
                    }
                    // Some hot-plug timings leave the panel in OFF even though the fold state is
                    // already 3. Toggling once more is the only thing that reliably re-inits it.
                    if (externalDisplay != null) {
                        delay(400)
                        if (externalDisplay.state != Display.STATE_ON) {
                            AppLog.warn("外接屏仍为 OFF，切一次折叠状态强制重配")
                            DeviceCtrl.fold()
                            delay(450)
                            DeviceCtrl.expand()
                            delay(450)
                        }
                    }
                    val display = externalDisplayId.takeIf { it >= 0 }?.let { describe(it) } ?: "外接屏"
                    updateStatus("已展开 · $display")
                } else {
                    // Going back to the cover screen: portrait is what the cover UI expects.
                    if (prefs.lockLandscape) {
                        DeviceCtrl.restoreAutoRotate()
                    }
                    if (prefs.immersiveMode) {
                        DeviceCtrl.setImmersive(false)
                    }
                    updateStatus("已合盖 · 外屏")
                }
            }
        }
    }

    private fun replayPendingState() {
        val pending = prefs.pendingState
        if (pending < 0) return
        AppLog.info("补执行排队的 state $pending")
        prefs.pendingState = -1
        applyTarget(pending, "Shizuku 恢复后补执行")
    }

    // ---- display helpers --------------------------------------------------------------

    private fun describe(displayId: Int): String {
        val display = displayManager.getDisplay(displayId) ?: return "displayId=$displayId"
        return DisplayInfoText.compact(display)
    }

    private fun updateStatus(text: String) {
        _status.value = text
        if (_running.value) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    // ---- notification -----------------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }
}
