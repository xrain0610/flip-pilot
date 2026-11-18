package com.flip5.dockpilot

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private companion object {
        const val STATE_PAGE = "current_page"
    }

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
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvProfileConfig: TextView
    private lateinit var tvAudioOutput: TextView
    private lateinit var btnRotation: Button

    // 设置页
    private lateinit var tvShizukuState: TextView
    private lateinit var tvSecureState: TextView
    private lateinit var tvNotificationState: TextView
    private lateinit var tvBatteryState: TextView
    private lateinit var tvAccessibilityState: TextView
    private lateinit var swMonitor: SwitchCompat
    private lateinit var swBoot: SwitchCompat
    private lateinit var swDimInternal: SwitchCompat
    private lateinit var swForceLandscape: SwitchCompat
    private lateinit var rvLogs: RecyclerView

    // 配置页
    private lateinit var tvScreenLabel: TextView
    private lateinit var tvScreenId: TextView
    private lateinit var tvAudioState: TextView
    private lateinit var etWidth: EditText
    private lateinit var etHeight: EditText
    private lateinit var spDensity: Spinner
    private lateinit var spRotation: Spinner
    private lateinit var spAudioOutput: Spinner
    private lateinit var swLockLandscape: SwitchCompat
    private lateinit var swUnlockKeyguard: SwitchCompat
    private lateinit var swImmersive: SwitchCompat
    private var currentDisplay: Display? = null
    private val densityValues = listOf(0, 240, 320, 420, 480, 560, 640)
    private val densityLabels = listOf("不修改", "240", "320", "420", "480", "560", "640")
    private val rotationValues = listOf(0, 1, 2, 3)
    private val rotationLabels = listOf("0°（竖屏）", "90°", "180°（倒置）", "270°（横屏）")

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
        applySystemBarInsets()
        setupNavigation()
        setupHomeActions()
        setupConfigPage()
        setupSettingsPage()
        collectState()
        // wm size/density changes recreate the activity; keep the user on the same page.
        selectPage(savedInstanceState?.getInt(STATE_PAGE, 0) ?: 0)
        refreshPermissionState()

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived)
        Shizuku.addBinderDeadListener(shizukuBinderDead)

        if (prefs.monitorEnabled && !DisplayMonitorService.running.value) {
            AppLog.info("监控已启用，重新拉起服务")
            DisplayMonitorService.start(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, currentPage)
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
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvProfileConfig = findViewById(R.id.tvProfileConfig)
        tvAudioOutput = findViewById(R.id.tvAudioOutput)
        btnRotation = findViewById(R.id.btnRotation)

        tvShizukuState = findViewById(R.id.tvShizukuState)
        tvSecureState = findViewById(R.id.tvSecureState)
        tvNotificationState = findViewById(R.id.tvNotificationState)
        tvBatteryState = findViewById(R.id.tvBatteryState)
        tvAccessibilityState = findViewById(R.id.tvAccessibilityState)
        swMonitor = findViewById(R.id.swMonitor)
        swBoot = findViewById(R.id.swBoot)
        swDimInternal = findViewById(R.id.swDimInternal)
        swForceLandscape = findViewById(R.id.swForceLandscape)
        rvLogs = findViewById(R.id.rvLogs)

        tvScreenLabel = findViewById(R.id.tvScreenLabel)
        tvScreenId = findViewById(R.id.tvScreenId)
        tvAudioState = findViewById(R.id.tvAudioState)
        etWidth = findViewById(R.id.etWidth)
        etHeight = findViewById(R.id.etHeight)
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

    /**
     * Keeps content clear of the navigation bar. No extra top padding: under the immersive policy
     * the status bar is hidden, and adding a fixed top offset only produced a black band above the
     * content on the external display.
     */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
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
        findViewById<Button>(R.id.btnRotation).setOnClickListener { toggleRotation() }
    }

    /** Flips between portrait and the configured landscape orientation. */
    private fun toggleRotation() {
        if (!Shell.hasPermission()) {
            toast("需要 Shizuku 权限")
            return
        }
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { DeviceCtrl.currentRotation() }
            val isLandscape = current == 1 || current == 3
            val target = if (isLandscape) 0 else prefs.landscapeRotation.coerceAtLeast(1)
            withContext(Dispatchers.IO) { DeviceCtrl.setRotation(target) }
            AppLog.info(if (target == 0) "切换为竖屏" else "切换为横屏 rotation=$target")
            toast(if (target == 0) "已切换竖屏" else "已切换横屏")
            refreshSnapshot()
        }
    }

    // ---- config page --------------------------------------------------------------

    private fun setupConfigPage() {
        spRotation.adapter = spinnerAdapter(rotationLabels)
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
            tvScreenLabel.text = "未检测到外接屏"
            tvScreenId.text = "插上 HDMI 后再回来配置"
            etWidth.setText("")
            etHeight.setText("")
            etWidth.isEnabled = false
            etHeight.isEnabled = false
            spDensity.isEnabled = false
            spRotation.isEnabled = false
            spAudioOutput.isEnabled = false
            swLockLandscape.isEnabled = false
            swUnlockKeyguard.isEnabled = false
            swImmersive.isEnabled = false
            swForceLandscape.isEnabled = false
            setProfileButtonsEnabled(false)
            return
        }

        currentDisplay = external
        val profile = prefs.loadScreenProfile(ScreenIdentity.key(external))
        tvScreenLabel.text = ScreenIdentity.label(external)
        tvScreenId.text = "指纹 ${ScreenIdentity.shortKey(external)} · displayId ${external.displayId}"
        etWidth.isEnabled = true
        etHeight.isEnabled = true
        spDensity.isEnabled = true
        spRotation.isEnabled = true
        spAudioOutput.isEnabled = true
        swLockLandscape.isEnabled = true
        swUnlockKeyguard.isEnabled = true
        swImmersive.isEnabled = true
        swForceLandscape.isEnabled = true
        swLockLandscape.isChecked = profile?.lockLandscape ?: prefs.lockLandscape
        swUnlockKeyguard.isChecked = profile?.unlockKeyguard ?: prefs.disableKeyguard
        swImmersive.isChecked = profile?.immersive ?: prefs.immersiveMode
        swForceLandscape.isChecked = profile?.forceLandscape ?: false
        setProfileButtonsEnabled(true)

        // SecondScreen-style: mirror mode changes display 0 globally (wm size), so the resolution
        // is entered manually. Empty means "leave the system resolution alone".
        if (profile != null && profile.width > 0 && profile.height > 0) {
            etWidth.setText(profile.width.toString())
            etHeight.setText(profile.height.toString())
        } else {
            etWidth.setText("")
            etHeight.setText("")
        }

        val densityIndex = densityValues.indexOf(profile?.density ?: 0)
        spDensity.setSelection(if (densityIndex >= 0) densityIndex else 0)
        val rotationIndex = rotationValues.indexOf(profile?.rotation ?: prefs.landscapeRotation)
        spRotation.setSelection(if (rotationIndex >= 0) rotationIndex else rotationValues.indexOf(3))
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

        val widthText = etWidth.text.toString().trim()
        val heightText = etHeight.text.toString().trim()
        if (widthText.isNotEmpty() || heightText.isNotEmpty()) {
            val width = widthText.toIntOrNull()
            val height = heightText.toIntOrNull()
            if (width == null || height == null) {
                toast("宽和高都要填写数字，或两个都留空")
                return
            }
            if (width < 320 || height < 240 || width > 7680 || height > 7680) {
                toast("宽高范围 320~7680，请检查输入")
                return
            }
            profile.width = width
            profile.height = height
        } else {
            profile.width = 0
            profile.height = 0
        }
        profile.refreshRate = 0f
        profile.density = densityValues.getOrNull(spDensity.selectedItemPosition) ?: 0
        profile.rotation = rotationValues.getOrNull(spRotation.selectedItemPosition)
            ?: prefs.landscapeRotation
        profile.audioOutput =
            if (spAudioOutput.selectedItemPosition == 1) ScreenProfile.AUDIO_PHONE else ScreenProfile.AUDIO_SCREEN
        profile.lockLandscape = swLockLandscape.isChecked
        profile.unlockKeyguard = swUnlockKeyguard.isChecked
        profile.immersive = swImmersive.isChecked
        profile.forceLandscape = swForceLandscape.isChecked

        prefs.saveScreenProfile(profile)
        // Keep the global defaults in step: they are used when no profile exists (e.g. unplugging).
        // landscapeRotation is only the fallback used by the home "rotate" toggle, so keep it
        // pointing at a real landscape value.
        if (profile.rotation == 1 || profile.rotation == 3) {
            prefs.landscapeRotation = profile.rotation
        }
        prefs.lockLandscape = profile.lockLandscape
        prefs.disableKeyguard = profile.unlockKeyguard
        prefs.immersiveMode = profile.immersive
        AppLog.info(
            "已保存屏幕配置 ${profile.label}: ${profile.width}x${profile.height}" +
                "@${profile.refreshRate.toInt()}Hz dpi=${profile.density} " +
                "rotation=${profile.rotation} audio=${profile.audioOutput} " +
                "lock=${profile.lockLandscape} unlockKeyguard=${profile.unlockKeyguard} " +
                "immersive=${profile.immersive} forceLandscape=${profile.forceLandscape}"
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
                val result = ScreenApplier.apply(this@MainActivity, display, profile)
                if (profile.unlockKeyguard) DeviceCtrl.unlockKeyguard()
                // The accessibility overlay owns the orientation when enabled; it also ignores the
                // accelerometer so rotating the phone cannot pop the system rotate hint.
                if (profile.forceLandscape) {
                    if (OverlayRotationService.ensureEnabled()) {
                        OverlayRotationService.setForcedLandscape(true)
                        DeviceCtrl.setSensorRotation(false)
                    } else {
                        AppLog.warn("无障碍服务未连接，强制横屏未生效")
                    }
                } else {
                    OverlayRotationService.setForcedLandscape(false)
                    if (profile.lockLandscape) {
                        DeviceCtrl.lockLandscape(profile.rotation)
                    } else {
                        DeviceCtrl.setSensorRotation(true)
                    }
                }
                // Always publish the switch value so turning it off clears the policy right away.
                DeviceCtrl.setImmersive(profile.immersive)
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
            withContext(Dispatchers.IO) { ScreenApplier.reset(this@MainActivity, display) }
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
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Throwable) {
                toast("无法打开无障碍设置")
            }
        }
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

        swDimInternal.isChecked = prefs.dimInternal
        swDimInternal.setOnCheckedChangeListener { _, checked ->
            prefs.dimInternal = checked
            AppLog.info("接外接屏时关闭内屏: $checked")
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
                // Refresh the status card periodically so Shizuku/accessibility changes show up
                // without the user pressing the refresh button.
                launch {
                    while (true) {
                        delay(5_000)
                        if (currentPage == 0) refreshSnapshot()
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

        val binderAlive = Shell.isBinderAlive()
        val shizukuReady = Shell.hasPermission()
        tvShizukuStatus.text = when {
            !binderAlive -> "Shizuku：未运行（需要启动 Shizuku）"
            !shizukuReady -> "Shizuku：未授权（需要授权）"
            else -> "Shizuku：已连接"
        }
        tvShizukuStatus.setTextColor(
            ContextCompat.getColor(this, if (shizukuReady) R.color.ok else R.color.bad)
        )

        // Mirror the per-screen configuration from the config page so it can be checked at a glance.
        val profile = external?.let { prefs.loadScreenProfile(ScreenIdentity.key(it)) }
        tvProfileConfig.text = if (external == null || profile == null) {
            "配置：暂无（未检测到外接屏）"
        } else {
            val resolution = if (profile.width > 0) "${profile.width}x${profile.height}" else "不修改"
            val density = if (profile.density > 0) profile.density.toString() else "不修改"
            buildString {
                append("分辨率：$resolution · DPI：$density · 方向：${profile.rotation * 90}°\n")
                append("锁定方向：${onOff(profile.lockLandscape)} · 解除锁屏：${onOff(profile.unlockKeyguard)}\n")
                append("沉浸全屏：${onOff(profile.immersive)} · 强制横屏：${onOff(profile.forceLandscape)}")
            }
        }

        lifecycleScope.launch {
            val (rotation, accessibilityEnabled, accessibilityConnected) = withContext(Dispatchers.IO) {
                val permission = Shell.hasPermission()
                val r = if (permission) DeviceCtrl.currentRotation() else null
                // Reading through shell is reliable from the very first frame; the
                // AccessibilityManager list can still be empty while the service binds.
                val enabled = if (permission) {
                    DeviceCtrl.isOverlayAccessibilityEnabled()
                } else {
                    isOverlayServiceEnabled()
                }
                Triple(r, enabled, OverlayRotationService.isConnected())
            }
            btnRotation.text = when (rotation) {
                1, 3 -> "转竖屏"
                0, 2 -> "转横屏"
                else -> "切换方向"
            }
            val accessibilityOk = accessibilityEnabled == true && accessibilityConnected
            tvAccessibilityStatus.text = when {
                accessibilityEnabled == null -> "无障碍：未知"
                accessibilityOk -> "无障碍：已连接"
                accessibilityEnabled -> "无障碍：已启用（未连接）"
                else -> "无障碍：未开启（需要在设置中开启）"
            }
            tvAccessibilityStatus.setTextColor(
                ContextCompat.getColor(this@MainActivity, if (accessibilityOk) R.color.ok else R.color.bad)
            )
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

        lifecycleScope.launch {
            val accessibilityEnabled = withContext(Dispatchers.IO) {
                if (Shell.hasPermission()) {
                    DeviceCtrl.isOverlayAccessibilityEnabled() ?: isOverlayServiceEnabled()
                } else {
                    isOverlayServiceEnabled()
                }
            }
            tvAccessibilityState.text = "无障碍服务：" + if (accessibilityEnabled) "已开启" else "未开启"
            colorize(tvAccessibilityState, accessibilityEnabled)
        }
    }

    /**
     * Whether the overlay accessibility service is enabled. Uses AccessibilityManager because
     * reading Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES requires a system permission.
     */
    private fun isOverlayServiceEnabled(): Boolean {
        if (OverlayRotationService.isConnected()) return true
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id.startsWith("$packageName/") }
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
                    val profile = currentDisplay?.let { prefs.loadScreenProfile(ScreenIdentity.key(it)) }
                    val immersive = profile?.immersive ?: prefs.immersiveMode
                    if (target == DeviceCtrl.STATE_UNFOLDED) {
                        DeviceCtrl.wakeUp()
                        if (prefs.disableKeyguard) DeviceCtrl.unlockKeyguard()
                        // The overlay owns the orientation while it is active.
                        if (prefs.lockLandscape && !prefs.forceLandscape) {
                            DeviceCtrl.lockLandscape(prefs.landscapeRotation)
                        }
                        // Always publish the value so switching it off clears an existing policy.
                        DeviceCtrl.setImmersive(immersive)
                        if (prefs.dimInternal) {
                            val current = DeviceCtrl.internalBrightness()
                            if (current != null && current > 0f) {
                                prefs.savedInternalBrightness = current
                            }
                            DeviceCtrl.setInternalBrightness(0f)
                        }
                    } else {
                        if (prefs.lockLandscape) DeviceCtrl.restoreAutoRotate()
                        DeviceCtrl.setImmersive(false)
                        if (prefs.dimInternal) {
                            DeviceCtrl.setInternalBrightness(prefs.savedInternalBrightness)
                        }
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

    private fun onOff(value: Boolean): String = if (value) "开" else "关"
}
