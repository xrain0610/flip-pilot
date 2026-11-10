package com.flip5.dockpilot

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val logAdapter = LogAdapter()

    // 页面
    private lateinit var pageHome: View
    private lateinit var pageConfig: View
    private lateinit var pageSettings: View
    private lateinit var navHome: TextView
    private lateinit var navConfig: TextView
    private lateinit var navSettings: TextView
    private var currentPage = 0

    // 首页
    private lateinit var tvRunning: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvDeviceState: TextView
    private lateinit var tvDisplayInfo: TextView
    private lateinit var tvAudioOutput: TextView

    // 设置页
    private lateinit var tvShizukuState: TextView
    private lateinit var tvSecureState: TextView
    private lateinit var tvNotificationState: TextView
    private lateinit var tvBatteryState: TextView
    private lateinit var swMonitor: SwitchCompat
    private lateinit var swBoot: SwitchCompat
    private lateinit var rvLogs: RecyclerView

    // 配置页
    private lateinit var tvScreenLabel: TextView
    private lateinit var tvScreenId: TextView
    private lateinit var tvAudioState: TextView
    private lateinit var spExternalMode: Spinner
    private lateinit var spDensity: Spinner
    private lateinit var spRotation: Spinner
    private lateinit var spAudioOutput: Spinner
    private lateinit var swLockLandscape: SwitchCompat
    private lateinit var swUnlockKeyguard: SwitchCompat
    private lateinit var swImmersive: SwitchCompat
    private var externalModes: List<Display.Mode> = emptyList()
    private var currentDisplay: Display? = null
    private val densityValues = listOf(0, 240, 320, 420, 480, 560, 640)
    private val densityLabels = listOf("不修改", "240", "320", "420", "480", "560", "640")

    private val shizukuRequestCode = 1001

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.info("通知权限申请结果: $granted")
            refreshPermissionState()
        }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == shizukuRequestCode) {
                AppLog.info("Shizuku 授权结果: $grantResult")
                refreshPermissionState()
            }
        }

    private val shizukuBinderReceived = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshPermissionState() }
    }

    private val shizukuBinderDead = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshPermissionState() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        bindViews()
        setupNavigation()
        setupHomeActions()
        setupConfigPage()
        setupSettingsPage()
        collectState()
        selectPage(0)
        refreshPermissionState()

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived)
        Shizuku.addBinderDeadListener(shizukuBinderDead)

        if (prefs.monitorEnabled && !DisplayMonitorService.running.value) {
            AppLog.info("监控已启用，重新拉起服务")
            DisplayMonitorService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        syncSwitches()
        refreshSnapshot()
        if (currentPage == 1) refreshScreenConfig()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderReceived)
            Shizuku.removeBinderDeadListener(shizukuBinderDead)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    // ---- binding ------------------------------------------------------------------

    private fun bindViews() {
        pageHome = findViewById(R.id.pageHome)
        pageConfig = findViewById(R.id.pageConfig)
        pageSettings = findViewById(R.id.pageSettings)
        navHome = findViewById(R.id.navHome)
        navConfig = findViewById(R.id.navConfig)
        navSettings = findViewById(R.id.navSettings)

        tvRunning = findViewById(R.id.tvRunning)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvDeviceState = findViewById(R.id.tvDeviceState)
        tvDisplayInfo = findViewById(R.id.tvDisplayInfo)
        tvAudioOutput = findViewById(R.id.tvAudioOutput)

        tvShizukuState = findViewById(R.id.tvShizukuState)
        tvSecureState = findViewById(R.id.tvSecureState)
        tvNotificationState = findViewById(R.id.tvNotificationState)
        tvBatteryState = findViewById(R.id.tvBatteryState)
        swMonitor = findViewById(R.id.swMonitor)
        swBoot = findViewById(R.id.swBoot)
        rvLogs = findViewById(R.id.rvLogs)

        tvScreenLabel = findViewById(R.id.tvScreenLabel)
        tvScreenId = findViewById(R.id.tvScreenId)
        tvAudioState = findViewById(R.id.tvAudioState)
        spExternalMode = findViewById(R.id.spExternalMode)
        spDensity = findViewById(R.id.spDensity)
        spRotation = findViewById(R.id.spRotation)
        spAudioOutput = findViewById(R.id.spAudioOutput)
        swLockLandscape = findViewById(R.id.swLockLandscape)
        swUnlockKeyguard = findViewById(R.id.swUnlockKeyguard)
        swImmersive = findViewById(R.id.swImmersive)

        rvLogs.layoutManager = LinearLayoutManager(this)
        rvLogs.adapter = logAdapter
        rvLogs.isNestedScrollingEnabled = false
    }

    private fun setupNavigation() {
        navHome.setOnClickListener { selectPage(0) }
        navConfig.setOnClickListener { selectPage(1) }
        navSettings.setOnClickListener { selectPage(2) }
    }

    private fun selectPage(index: Int) {
        currentPage = index
        pageHome.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageConfig.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageSettings.visibility = if (index == 2) View.VISIBLE else View.GONE

        val active = ContextCompat.getColor(this, R.color.ok)
        val inactive = ContextCompat.getColor(this, R.color.nav_inactive)
        navHome.setTextColor(if (index == 0) active else inactive)
        navConfig.setTextColor(if (index == 1) active else inactive)
        navSettings.setTextColor(if (index == 2) active else inactive)
        navHome.setTextSize(if (index == 0) 15f else 14f)
        navConfig.setTextSize(if (index == 1) 15f else 14f)
        navSettings.setTextSize(if (index == 2) 15f else 14f)

        when (index) {
            0 -> refreshSnapshot()
            1 -> refreshScreenConfig()
            2 -> {
                refreshPermissionState()
                syncSwitches()
            }
        }
    }

    // ---- home ---------------------------------------------------------------------

    private fun setupHomeActions() {
        findViewById<Button>(R.id.btnExpand).setOnClickListener { manualState(DeviceCtrl.STATE_UNFOLDED) }
        findViewById<Button>(R.id.btnFold).setOnClickListener { manualState(DeviceCtrl.STATE_FOLDED) }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshSnapshot() }
    }

    // ---- config page --------------------------------------------------------------

    private fun setupConfigPage() {
        spRotation.adapter = spinnerAdapter(listOf("270°（避让在右）", "90°（避让在左）"))
        spDensity.adapter = spinnerAdapter(densityLabels)
        spAudioOutput.adapter = spinnerAdapter(listOf("外接屏", "手机扬声器"))

        findViewById<Button>(R.id.btnSaveProfile).setOnClickListener { saveCurrentProfile(apply = false) }
        findViewById<Button>(R.id.btnApplyProfile).setOnClickListener { saveCurrentProfile(apply = true) }
        findViewById<Button>(R.id.btnResetProfile).setOnClickListener { resetCurrentProfile() }
        findViewById<Button>(R.id.btnAudioSwitcher).setOnClickListener {
            if (!ScreenAudio.showOutputSwitcher(this)) {
                toast("当前系统不支持媒体输出选择器（需要 Android 14+）")
            }
        }
    }

    private fun refreshScreenConfig() {
        val displayManager = getSystemService(DisplayManager::class.java)
        val external = displayManager.displays.firstOrNull {
            it.flags and Display.FLAG_PRESENTATION != 0 && it.displayId != Display.DEFAULT_DISPLAY
        }
        tvAudioState.text = "外接屏音频：" + if (ScreenAudio.hasHdmiOutput(this)) "可用" else "不可用"

        if (external == null) {
            // Everything on this page is per external panel; nothing to configure without one.
            currentDisplay = null
            externalModes = emptyList()
            tvScreenLabel.text = "未检测到外接屏"
            tvScreenId.text = "插上 HDMI 后再回来配置"
            spExternalMode.adapter = spinnerAdapter(listOf("未检测到外接屏"))
            spExternalMode.isEnabled = false
            spDensity.isEnabled = false
            spRotation.isEnabled = false
            spAudioOutput.isEnabled = false
            swLockLandscape.isEnabled = false
            swUnlockKeyguard.isEnabled = false
            swImmersive.isEnabled = false
            setProfileButtonsEnabled(false)
            return
        }

        currentDisplay = external
        val profile = prefs.loadScreenProfile(ScreenIdentity.key(external))
        tvScreenLabel.text = ScreenIdentity.label(external)
        tvScreenId.text = "指纹 ${ScreenIdentity.shortKey(external)} · displayId ${external.displayId}"
        spDensity.isEnabled = true
        spRotation.isEnabled = true
        spAudioOutput.isEnabled = true
        swLockLandscape.isEnabled = true
        swUnlockKeyguard.isEnabled = true
        swImmersive.isEnabled = true
        swLockLandscape.isChecked = profile?.lockLandscape ?: prefs.lockLandscape
        swUnlockKeyguard.isChecked = profile?.unlockKeyguard ?: prefs.disableKeyguard
        swImmersive.isChecked = profile?.immersive ?: prefs.immersiveMode
        setProfileButtonsEnabled(true)

        externalModes = external.supportedModes.sortedWith(
            compareByDescending<Display.Mode> { it.physicalWidth }.thenByDescending { it.refreshRate }
        )
        val labels = mutableListOf("不修改（保持系统选择）")
        labels += externalModes.map {
            "${it.physicalWidth}x${it.physicalHeight} @ ${it.refreshRate.toInt()}Hz（mode ${it.modeId}）"
        }
        spExternalMode.adapter = spinnerAdapter(labels)
        spExternalMode.isEnabled = true
        val savedIndex = externalModes.indexOfFirst {
            profile != null &&
                it.physicalWidth == profile.width &&
                it.physicalHeight == profile.height &&
                kotlin.math.abs(it.refreshRate - profile.refreshRate) < 0.5f
        }
        spExternalMode.setSelection(if (savedIndex >= 0) savedIndex + 1 else 0)

        val densityIndex = densityValues.indexOf(profile?.density ?: 0)
        spDensity.setSelection(if (densityIndex >= 0) densityIndex else 0)
        spRotation.setSelection(if ((profile?.rotation ?: prefs.landscapeRotation) == 3) 0 else 1)
        spAudioOutput.setSelection(
            if ((profile?.audioOutput ?: ScreenProfile.AUDIO_SCREEN) == ScreenProfile.AUDIO_PHONE) 1 else 0
        )
    }

    private fun setProfileButtonsEnabled(enabled: Boolean) {
        findViewById<Button>(R.id.btnSaveProfile).isEnabled = enabled
        findViewById<Button>(R.id.btnApplyProfile).isEnabled = enabled
        findViewById<Button>(R.id.btnResetProfile).isEnabled = enabled
    }

    private fun saveCurrentProfile(apply: Boolean) {
        val display = currentDisplay ?: return
        val key = ScreenIdentity.key(display)
        val profile = prefs.loadScreenProfile(key) ?: ScreenProfile(key)
        profile.label = ScreenIdentity.label(display)

        val modeIndex = spExternalMode.selectedItemPosition
        if (spExternalMode.isEnabled && modeIndex > 0) {
            val mode = externalModes.getOrNull(modeIndex - 1)
            if (mode != null) {
                profile.width = mode.physicalWidth
                profile.height = mode.physicalHeight
                profile.refreshRate = mode.refreshRate
            }
        } else {
            profile.width = 0
            profile.height = 0
            profile.refreshRate = 0f
        }
        profile.density = densityValues.getOrNull(spDensity.selectedItemPosition) ?: 0
        profile.rotation = if (spRotation.selectedItemPosition == 0) 3 else 1
        profile.audioOutput =
            if (spAudioOutput.selectedItemPosition == 1) ScreenProfile.AUDIO_PHONE else ScreenProfile.AUDIO_SCREEN
        profile.lockLandscape = swLockLandscape.isChecked
        profile.unlockKeyguard = swUnlockKeyguard.isChecked
        profile.immersive = swImmersive.isChecked

        prefs.saveScreenProfile(profile)
        // Keep the global defaults in step: they are used when no profile exists (e.g. unplugging).
        prefs.landscapeRotation = profile.rotation
        prefs.lockLandscape = profile.lockLandscape
        prefs.disableKeyguard = profile.unlockKeyguard
        prefs.immersiveMode = profile.immersive
        AppLog.info(
            "已保存屏幕配置 ${profile.label}: ${profile.width}x${profile.height}" +
                "@${profile.refreshRate.toInt()}Hz dpi=${profile.density} " +
                "rotation=${profile.rotation} audio=${profile.audioOutput} " +
                "lock=${profile.lockLandscape} unlockKeyguard=${profile.unlockKeyguard} " +
                "immersive=${profile.immersive}"
        )
        if (!apply) {
            toast("已保存该屏幕配置")
            return
        }
        if (!Shell.hasPermission()) {
            toast("需要 Shizuku 权限才能应用")
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val result = ScreenApplier.apply(display, profile)
                if (profile.unlockKeyguard) DeviceCtrl.unlockKeyguard()
                if (profile.lockLandscape) DeviceCtrl.lockLandscape(profile.rotation)
                if (profile.immersive) DeviceCtrl.setImmersive(true)
                result
            }
            if (profile.audioOutput == ScreenProfile.AUDIO_PHONE) {
                if (!ScreenAudio.showOutputSwitcher(this@MainActivity)) {
                    toast("系统不支持音频输出选择器")
                }
            }
            toast(if (ok) "已应用到当前屏幕" else "部分设置未生效，见日志")
            refreshScreenConfig()
        }
    }

    private fun resetCurrentProfile() {
        val display = currentDisplay ?: return
        prefs.deleteScreenProfile(ScreenIdentity.key(display))
        AppLog.info("已删除屏幕配置 ${ScreenIdentity.label(display)}")
        if (!Shell.hasPermission()) {
            toast("需要 Shizuku 权限才能重置")
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { ScreenApplier.reset(display) }
            toast("已重置该屏幕的模式与密度")
            refreshScreenConfig()
        }
    }

    // ---- settings page ------------------------------------------------------------

    private fun setupSettingsPage() {
        findViewById<Button>(R.id.btnShizuku).setOnClickListener { requestShizukuPermission() }
        findViewById<Button>(R.id.btnSecure).setOnClickListener { grantSecureSettings() }
        findViewById<Button>(R.id.btnNotification).setOnClickListener { requestNotificationPermissionIfNeeded(true) }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { fixBatteryOptimization() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { AppLog.clear() }

        swMonitor.setOnCheckedChangeListener { _, checked ->
            prefs.monitorEnabled = checked
            if (checked) {
                requestNotificationPermissionIfNeeded()
                AppLog.info("开启显示器监听")
                DisplayMonitorService.start(this)
            } else {
                AppLog.info("关闭显示器监听")
                DisplayMonitorService.stop(this)
            }
            refreshRunningUi()
        }

        swBoot.isChecked = prefs.bootEnabled
        swBoot.setOnCheckedChangeListener { _, checked ->
            prefs.bootEnabled = checked
            AppLog.info("开机自动启动: $checked")
        }
    }

    private fun syncSwitches() {
        if (swMonitor.isChecked != prefs.monitorEnabled) {
            swMonitor.setOnCheckedChangeListener(null)
            swMonitor.isChecked = prefs.monitorEnabled
            attachMonitorListener()
        }
        if (swBoot.isChecked != prefs.bootEnabled) {
            swBoot.setOnCheckedChangeListener(null)
            swBoot.isChecked = prefs.bootEnabled
            attachBootListener()
        }
    }

    private fun attachMonitorListener() {
        swMonitor.setOnCheckedChangeListener { _, checked ->
            prefs.monitorEnabled = checked
            if (checked) {
                requestNotificationPermissionIfNeeded()
                AppLog.info("开启显示器监听")
                DisplayMonitorService.start(this)
            } else {
                AppLog.info("关闭显示器监听")
                DisplayMonitorService.stop(this)
            }
            refreshRunningUi()
        }
    }

    private fun attachBootListener() {
        swBoot.setOnCheckedChangeListener { _, checked ->
            prefs.bootEnabled = checked
            AppLog.info("开机自动启动: $checked")
        }
    }

    // ---- state --------------------------------------------------------------------

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppLog.entries.collect { entries ->
                        logAdapter.submit(entries)
                        if (entries.isNotEmpty()) rvLogs.scrollToPosition(0)
                    }
                }
                launch {
                    DisplayMonitorService.running.collect { refreshRunningUi() }
                }
                launch {
                    DisplayMonitorService.status.collect { text ->
                        tvStatusDetail.text = text
                    }
                }
            }
        }
    }

    private fun refreshRunningUi() {
        val running = DisplayMonitorService.running.value
        tvRunning.text = if (running) "服务：运行中" else "服务：已停止"
        tvRunning.setTextColor(ContextCompat.getColor(this, if (running) R.color.ok else R.color.bad))
        syncSwitches()
    }

    private fun refreshSnapshot() {
        val displayManager = getSystemService(DisplayManager::class.java)
        val external = displayManager.displays.firstOrNull {
            it.flags and Display.FLAG_PRESENTATION != 0 && it.displayId != Display.DEFAULT_DISPLAY
        }
        tvDisplayInfo.text = if (external == null) {
            "外接屏：无"
        } else {
            DisplayInfoText.detailed(external)
        }

        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                if (Shell.hasPermission()) DeviceCtrl.queryState() else null
            }
            tvDeviceState.text = when (state) {
                DeviceCtrl.STATE_UNFOLDED -> "折叠状态：展开 (3)"
                DeviceCtrl.STATE_FOLDED -> "折叠状态：合盖 (0)"
                null -> "折叠状态：未知（需要 Shizuku 权限）"
                else -> "折叠状态：$state"
            }
        }

        lifecycleScope.launch {
            val audio = withContext(Dispatchers.IO) { ScreenAudio.currentOutput(this@MainActivity) }
            tvAudioOutput.text = "音频输出：" + (audio ?: "未知")
        }
    }

    // ---- permissions --------------------------------------------------------------

    private fun refreshPermissionState() {
        val binderAlive = Shell.isBinderAlive()
        val shizukuGranted = Shell.hasPermission()
        tvShizukuState.text = when {
            !binderAlive -> "Shizuku：服务未运行"
            shizukuGranted -> "Shizuku：已授权"
            else -> "Shizuku：未授权"
        }
        colorize(tvShizukuState, shizukuGranted)

        val secureGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED
        tvSecureState.text = "WRITE_SECURE_SETTINGS：" + if (secureGranted) "已授予" else "未授予"
        colorize(tvSecureState, secureGranted)

        val notifications = NotificationManagerCompat.from(this).areNotificationsEnabled()
        tvNotificationState.text = "通知：" + if (notifications) "已允许" else "未允许"
        colorize(tvNotificationState, notifications)

        val ignoringBattery = isIgnoringBatteryOptimizations()
        tvBatteryState.text = "电池优化：" + if (ignoringBattery) "已加入白名单" else "未加入"
        colorize(tvBatteryState, ignoringBattery)
    }

    private fun colorize(view: TextView, good: Boolean) {
        view.setTextColor(ContextCompat.getColor(this, if (good) R.color.ok else R.color.bad))
    }

    private fun requestShizukuPermission() {
        when {
            !Shell.isBinderAlive() -> {
                AppLog.warn("Shizuku 服务未运行，无法申请权限")
                toast("Shizuku 服务未运行，请先在设备上启动 Shizuku")
            }

            Shell.isPreV11() -> {
                AppLog.warn("Shizuku 版本过低（pre-v11），需要手动启动服务")
                toast("Shizuku 版本过低，请升级到 11 以上")
            }

            Shell.hasPermission() -> toast("Shizuku 已授权")

            else -> try {
                Shizuku.requestPermission(shizukuRequestCode)
            } catch (t: Throwable) {
                AppLog.error("申请 Shizuku 权限异常: ${t.message}")
                toast("申请失败：${t.message}")
            }
        }
    }

    /**
     * WRITE_SECURE_SETTINGS carries the `development` protection flag, so the shell uid may grant
     * it. Shizuku runs as shell, which lets the app authorise itself without a PC.
     *
     * The monitor itself does not need this permission: every settings write goes through
     * `settings put` executed by Shizuku as shell.
     */
    private fun grantSecureSettings() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            toast("WRITE_SECURE_SETTINGS 已授予")
            return
        }
        if (!Shell.hasPermission()) {
            copySecureSettingsCommand()
            toast("Shizuku 不可用，已复制 adb 命令")
            return
        }
        AppLog.info("尝试通过 Shizuku 授予 WRITE_SECURE_SETTINGS")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                Shell.exec("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
            }
            if (result.ok) {
                AppLog.info("已通过 Shizuku 授予 WRITE_SECURE_SETTINGS")
            } else {
                AppLog.warn("Shizuku 授权失败: ${result.text}，改用 adb 命令")
                copySecureSettingsCommand()
            }
            refreshPermissionState()
        }
    }

    private fun copySecureSettingsCommand() {
        val command = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("adb", command))
        AppLog.info("已复制授权命令: $command")
        toast("已复制：$command")
    }

    private fun requestNotificationPermissionIfNeeded(showToast: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            if (showToast) toast("当前系统不需要通知权限")
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (showToast) {
            toast("通知权限已允许")
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * Android's battery optimization screen only lists apps that are *not* optimized, and the
     * "All apps" filter is buried in a dropdown — the app is often impossible to find there.
     * Shell can add the package to the Doze whitelist directly, so prefer that route.
     */
    private fun fixBatteryOptimization() {
        if (isIgnoringBatteryOptimizations()) {
            toast("已在电池优化白名单中")
            return
        }
        if (!Shell.hasPermission()) {
            openBatterySettings()
            return
        }
        AppLog.info("通过 Shizuku 加入电池优化白名单")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                Shell.exec("dumpsys deviceidle whitelist +$packageName")
            }
            if (result.ok) {
                AppLog.info("已加入电池优化白名单")
                toast("已加入电池优化白名单")
            } else {
                AppLog.warn("加入白名单失败: ${result.text}，打开系统设置")
                openBatterySettings()
            }
            refreshPermissionState()
        }
    }

    private fun openBatterySettings() {
        // Ask for this exact package first; the generic list is the fallback.
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        } catch (t: Throwable) {
            AppLog.warn("打开单应用电池优化弹窗失败: ${t.message}")
        }
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (t: Throwable) {
            AppLog.warn("打开电池优化设置失败: ${t.message}")
            toast("无法打开电池优化设置")
        }
    }

    // ---- actions ------------------------------------------------------------------

    private fun manualState(target: Int) {
        if (!Shell.hasPermission()) {
            toast("需要先授予 Shizuku 权限")
            return
        }
        AppLog.info("手动下发 state $target")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (target == DeviceCtrl.STATE_UNFOLDED) DeviceCtrl.expand() else DeviceCtrl.fold()
            }
            if (result.ok) {
                prefs.lastAppliedState = target
                withContext(Dispatchers.IO) {
                    if (target == DeviceCtrl.STATE_UNFOLDED) {
                        DeviceCtrl.wakeUp()
                        if (prefs.disableKeyguard) DeviceCtrl.unlockKeyguard()
                        if (prefs.lockLandscape) DeviceCtrl.lockLandscape(prefs.landscapeRotation)
                    } else {
                        if (prefs.lockLandscape) DeviceCtrl.restoreAutoRotate()
                    }
                }
            }
            refreshSnapshot()
        }
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.spinner_item, items).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
