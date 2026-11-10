# FlipPilot

**外接屏幕插拔自动配置**

给拆掉内屏、改成掌机的 Galaxy Z Flip5 用的一个后台 App：插上 HDMI 屏幕自动展开并配置显示，拔掉自动合盖。

---

## 功能

**自动切换**
- 监听外接屏接入/断开（`DisplayManager` + `FLAG_PRESENTATION`），500 ms 防抖
- 接入 → `cmd device_state state 3`（展开）；断开 → `state 0`（合盖）
- 插屏时**强制**重新下发一次状态，避免热插拔后外接屏停在 OFF 导致物理屏黑屏

**展开后的显示配置**
- 解除锁屏（`locksettings set-disabled` + `wm dismiss-keyguard`，锁屏会强制竖屏）
- 锁定横屏（`settings put system user_rotation`，`wm` 是假成功）
- 沉浸全屏（`policy_control immersive.full=*`）

**按屏幕保存配置**
- 每块屏幕一份配置，用 EDID 指纹识别（`displayId` 和 `uniqueId` 每次插拔都会变，不能当 ID）
- 可配置：分辨率/刷新率、DPI、屏幕方向、音频输出、以及上面三个开关
- 插屏时自动应用该屏幕的配置

**其他**
- 前台服务常驻（`specialUse` 类型），通知栏显示当前状态
- 开机自启（可开关）
- 首页实时状态：服务、折叠状态、外接屏原生/实际分辨率帧率、当前音频输出
- 应用内日志（含每条 shell 命令的结果）

## 工作原理

```
DisplayListener.onDisplayAdded / onDisplayRemoved
        │
        ├─ 500 ms 防抖（插拔瞬间会连发多次事件）
        │
        ├─ 读取该屏幕的 ScreenProfile（EDID 指纹匹配）
        │
        └─ 通过 Shizuku（shell 身份）执行：
             cmd device_state state 3 / state 0
             locksettings set-disabled true; wm dismiss-keyguard
             settings put system accelerometer_rotation 0; user_rotation 3
             wm density <dpi> -d <displayId>
             cmd display set-user-preferred-display-mode ...
             settings put global policy_control immersive.full=*
```

Shizuku 13.x 把 `newProcess` 收成了私有方法，这里通过反射调用（binder ABI 自 11 起没变），所以 12.x / 13.x 客户端都能用。

**屏幕指纹** = `Display.getName()` + 物理分辨率 + 排序后的 `supportedModes` 集合。同型号的两块屏会共用一份配置——要区分它们需要 EDID serial，公开 API 拿不到。

## 权限

| 权限 | 用途 | 如何授予 |
|---|---|---|
| Shizuku | 以 shell 身份执行 `cmd device_state` / `settings` / `wm` | 启动 Shizuku 后，在设置页点「授权」 |
| `WRITE_SECURE_SETTINGS` | 仅用于 App 进程内直接写 Settings（当前实现不需要，保留自助授权） | 设置页点「授权」，走 Shizuku 执行 `pm grant` |
| `POST_NOTIFICATIONS` | 前台服务通知 | 设置页点「申请」 |
| 电池优化白名单 | 避免服务被冻结 | 设置页点「设置」，走 `dumpsys deviceidle whitelist +<包名>` |

> `WRITE_SECURE_SETTINGS` 带 `development` 保护级别，shell 用户可 `pm grant`，所以不需要连电脑。

## 构建

环境：JDK 17、Android SDK（platform 35、build-tools 35.0.0）。

```powershell
$env:JAVA_HOME = "C:\Users\xRain\scoop\apps\openjdk17\current"
$env:ANDROID_HOME = "C:\Android\Sdk"
cd E:\zflip5
.\gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

技术栈：Kotlin 2.0.21、AGP 8.7.3、Gradle 8.11.1、compileSdk 35 / minSdk 31 / targetSdk 35。

## 首次使用

1. 在设备上启动 Shizuku（重启后需要重新启动）
2. 安装 APK，打开 FlipPilot
3. **设置**页：逐项授权（Shizuku → 通知 → 电池优化）
4. **配置**页：插上外接屏，按屏幕设置分辨率/DPI/方向/音频/展开行为，点「保存」
5. **设置**页：开启「开启显示器监听」，需要开机自启就同时打开「开机自动启动」
6. 回**首页**确认服务运行、日志里出现 `外接屏 state=2（插入后强制下发）`

## 已知限制（都是实测结论）

- **外接屏分辨率/刷新率改不动**：`cmd display set-user-preferred-display-mode` 命令能执行，但这台 ROM 会忽略（`userPreferredModeId` 回 `-1`）；`wm size -d` 对外接屏也无效。**DPI 是真正生效的**（`wm density <dpi> -d <id>`）。
- **全局 60Hz**：内屏面板已拆除，驱动套兜底 timing 只有 `1080x1920@60`；内外屏在同一个 `displayGroupId`，DisplayModeDirector 会把整组压到 60Hz。面板原生支持 120Hz（`defaultMode` 能看到），但应用侧 vsync 仍是 60。`peak_refresh_rate` 也被 ROM 忽略。
- **音频无法静默切换**：第三方 App 没有 `MODIFY_AUDIO_ROUTING`（系统权限），只能调 `MediaRouter2.showSystemOutputSwitcher()`（Android 14+）弹系统媒体输出选择器让用户选。配置里只记录偏好。
- **同型号屏幕共用配置**：指纹基于 EDID 名称 + 分辨率 + 模式表，无法区分两块同型号面板。
- **改包名会重置授权**：`applicationId` 目前是 `com.flip5.dockpilot`，改包名相当于新应用，Shizuku 授权、`WRITE_SECURE_SETTINGS`、电池白名单都要重新给。

## 代码结构

```
app/src/main/java/com/flip5/dockpilot/
├── MainActivity.kt          三页 UI（首页 / 配置 / 设置）
├── DisplayMonitorService.kt 前台服务：显示监听、防抖、状态下发
├── DeviceCtrl.kt            device_state / 旋转 / 锁屏 / 沉浸命令
├── Shell.kt                 Shizuku shell 执行封装
├── ScreenIdentity.kt        屏幕指纹（跨插拔识别）
├── ScreenProfile.kt         每屏配置模型
├── ScreenApplier.kt         应用每屏配置（模式 / DPI）
├── ScreenAudio.kt           音频输出检测与系统选择器
├── DisplayInfoText.kt       原生/实际分辨率帧率格式化
├── Prefs.kt                 配置与状态持久化
├── AppLog.kt                内存日志环形缓冲
├── LogAdapter.kt            日志列表
└── BootReceiver.kt          开机自启
```
