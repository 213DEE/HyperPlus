# HyperPlus

**让屏幕方向跟着你的脸转，而不是跟着重力转。**

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-3ddc84)
![Language](https://img.shields.io/badge/Kotlin-2.4.20-7f52ff)
![Stage](https://img.shields.io/badge/stage-Alpha-orange)

[中文](#中文) · [English](#english)

---

## 中文

### 它解决什么

睡前侧躺着刷手机的人应该都遇到过这件事：手机是侧着拿的，重力却告诉系统"该横屏了"，屏幕就转到你根本不想看的方向。

系统的自动旋转**只认重力，不认你的脸**。HyperPlus 让屏幕方向由**人脸相对设备的朝向**决定。

### 功能

| 功能 | 状态 |
|---|---|
| 人脸方向跟随旋转 | ✅ 真机实测 |
| 朝向传感器触发（不常开相机） | ✅ 真机实测 |
| 其他 App 占用相机时让位（四道闸门） | ✅ 真机实测 |
| 控制中心快捷开关（两态） | ✅ 真机实测 |
| 省电优先 / 响应优先可切换 | ✅ 真机实测 |
| 方向两步标定 | 🔎 已实现，待实测确认 |
| GitHub Release 版本检测与提醒 | 🔎 已实现，待实测确认 |
| **常驻后台**（LSPosed 模块，宿主 = 系统界面进程） | ✅ 真机实测 |

### 安装（两种模式）

| 模式 | 需要什么 | 效果 |
|---|---|---|
| **单机模式** | 装 APK 就行 | 打开 App 时方向跟脸转；**退到后台 / 回桌面即失效**（Android 14+ 后台禁相机） |
| **常驻模式** | 装 APK ＋ root ＋ LSPosed，模块作用域勾 `com.android.systemui` | 引擎常驻在**系统界面进程**里，**回桌面、锁屏、App 被强杀都继续生效** |

常驻模式的配置同步需要 root 授权：App 侧写自定义系统设置键会被 `SettingsProvider` 拒
（`You cannot keep your settings in the secure settings.`，且**授予常规的 `WRITE_SETTINGS` 或
`WRITE_SECURE_SETTINGS` 都无效** —— 那道闸只放行系统 / shell / root 身份与特权包），
所以 App 借 root 身份执行 `settings put` 把偏好写进系统设置库，宿主进程读同一张表实时跟随。
界面上有「授予 root 权限」按钮，点一次永久生效（重启、升级都保留，只有卸载重装要再来一次）。
不授权也能用，只是退回单机模式。

### 怎么做的（原理）

1. **不常开相机。** 先用硬件级的 `TYPE_DEVICE_ORIENTATION`（on-change 中断）感知"设备朝向变了"，才唤醒一次采集。零轮询。
2. **burst 采集。** 拉起前置相机采约 16 帧（≈1 秒），**采完立刻无条件释放**。实测相机从 bind 到首帧约 224 ms。
3. **让步优先。** 四道闸门保证绝不和其他 App 抢相机：事前查占用 → 绑定失败即放弃 → 持有期间被抢立即让位 → 采完无条件释放。任意一道拦下就跳过本轮，不排队、不重试。
4. **判定。** ML Kit 人脸检测 → `headEulerAngleZ` → 量化到最近的 90° 扇区 → 写 `Settings.System.USER_ROTATION`。

#### 两个绕不过去的坑（都实测过）

- **ML Kit 检不到 ±90° 的人脸**，而"竖屏 ↔ 横屏"恰好就是 0° → ±90° —— 正落在失明区。所以每帧要把图像多转几个角度各试一次；人脸角度是连续的，用"上次命中的角度优先"可以把平均搜索次数压到接近 1 次。
- **CameraX 的 `imageInfo.rotationDegrees` 参考系是"绑定相机那一刻的屏幕方向"**，它不跟设备本体走（实测 817 帧里恒为 270，而同期显示方向在 0/1/3 之间变过）。所以本项目改用 `CameraCharacteristics.SENSOR_ORIENTATION` 这个硬件常量做固定基准，让"人脸相对设备的倾斜"与屏幕当前转成什么样彻底解耦。

### 仓库结构

```
app/src/main/java/cn/dsr213/hyperplus/
├── AdaptiveEngine.kt          引擎：触发 → 让步 → burst → 判定 → 接管
├── OrientationDecider.kt      纯算法核心：roll → 屏幕方向（不依赖 Android，可离线单测）
├── FaceAnalyzer.kt            CameraX Analyzer + ML Kit 多角度搜索
├── VersionChecker.kt          GitHub Release 版本检测
├── AppPrefs.kt                模式 / 采集策略 / 标定 持久化（双后端：本地 SP ＋ 跨进程镜像）
├── RotateTileService.kt       控制中心快捷开关
├── CsvRecorder.kt             每帧原始数据落盘，供事后看曲线
├── PrefsBridge.kt             跨进程通道：Settings.System 镜像表（键 / 读写 / 观察）
├── ModuleLink.kt              App 侧：探活（ping）、远程标定、状态读取
├── RootBridge.kt              App 侧：借 root 身份写系统设置（直写失败自动转 root）
├── trigger/
│   └── DeviceOrientationTrigger.kt   朝向传感器触发层
├── module/                    ── 只在 LSPosed 宿主进程里跑的部分 ──
│   ├── HyperPlusModule.kt     LSPosed 入口（加载即启动常驻引擎）
│   ├── EngineHost.kt          常驻引擎宿主：启动 / 异常兜底 / 状态上报 / 心跳
│   ├── SystemUiLifecycle.kt   常驻 LifecycleOwner（SystemUI 没有 Activity）
│   ├── HostEnv.kt             宿主环境：native so 预加载 ＋ ML Kit 初始化
│   └── ModuleSelfCheck.kt     模块自检（默认关闭，可开关打开）
└── ui/
    ├── EngineScreen.kt        MiuiX 主界面
    └── FaceRotateTheme.kt     MiuiX 主题
```

### 开发环境

**设备（实测）**

| 项 | 值 |
|---|---|
| 机型 | Xiaomi 2608BPX34C（代号 `lhasa`） |
| 系统 | Android 17 / `CP2A.260605.016` |
| 安全补丁 | 2026-08-01 |
| 平台 | `xring_o3_asic` |
| ABI | arm64-v8a |

**构建工具链（工程实测）**

| 项 | 版本 |
|---|---|
| Android Gradle Plugin | 9.4.0 |
| Gradle | 9.6.0 |
| Kotlin | 2.4.20 |
| JDK | 17 |
| compileSdk / minSdk / targetSdk | 37 / 30 / 35 |
| Compose | 1.12.0 |
| MiuiX | 0.9.4 |
| CameraX | 1.4.1 |
| ML Kit face-detection | 16.1.7 |

> 这组版本不是随手挑的：MiuiX 0.9.4 要求 Compose 1.12 + `compileSdk ≥ 37`，Compose 1.12 要求 AGP ≥ 9.1，而 AGP 9.1 最高只到 API 36.1 —— 必须升到 AGP 9.4.0，它又要求 Gradle ≥ 9.6.0。

### 构建

```bash
gradle assembleDebug
```

### 现状

**早期开发阶段（Alpha）。** 已知边界：

- 常驻模式已真机实测（**回桌面 / 锁屏 / 强杀 App 后引擎仍在工作**），但它**依赖 root 与 LSPosed**；不装模块时自动退回单机模式（打开 App 才生效）。
- **已知盲区**：触发源是朝向传感器（设备动了才唤醒）。手机架在桌上不动、只有人头转过去的情况**不会触发** —— 目前**没有**"屏幕亮起 / 解锁"之类的兜底触发源。
- 部分机型的朝向传感器在熄屏时不唤醒，触发层可能收不到事件。
- 方向判定的符号与相位依赖具体机型的摄像头朝向与镜像方式，因此提供**两步标定**（竖屏基准 + 左横屏定方向），而不是把参数硬编码。
- 早期开发阶段，具体实现手段与可行边界仍在验证中，本文不写死任何技术方案。

### 捐赠

<img src="docs/donate_wechat_qr.png" width="220" alt="微信捐赠二维码">

**关于收费**：Alpha 阶段将始终保持免费；不排除将来推出 Beta 或正式版后，部分功能收费的可能。

### 免责声明

- 本项目为**非官方**项目，与小米（Xiaomi）、Google 及其关联公司**均无任何关联**。
- 文中提及的产品名、商标归各自所有者。
- 仅供**学习与个人使用**，使用风险由使用者自行承担。
- ⚠️ 本项目会**修改系统显示方向设置**（`Settings.System.ACCELEROMETER_ROTATION` 与 `USER_ROTATION`）。正常退出时会还原；若进程被强制终止，下次启动会自动检测并修复。仍建议在了解这一点后再使用。
- ⚠️ **常驻模式会把代码注入系统界面进程**（LSPosed 模块，作用域 `com.android.systemui`），并在其中常驻一个相机采集线程。它不申请额外特权，但**写自定义系统设置键需要 root**（见「安装」一节的原因）。
- ⚠️ 引擎接管期间会把 `ACCELEROMETER_ROTATION` 置 0。**任何致命异常都会立刻停用引擎并把该值还原**，不会留下"屏幕转不动"的状态。但如果你要卸载模块或关掉 LSPosed，建议先确认该值为 1（或手动打开一次系统自动旋转）。

### License

[AGPL-3.0](LICENSE)

---

## English

### What it solves

Anyone who has doom-scrolled while lying on their side knows this: the phone is sideways in your hand, but gravity tells the system "this should be landscape", and the screen flips to an orientation you never wanted.

Auto-rotate only respects **gravity**, not **your face**. HyperPlus drives screen orientation from **the orientation of your face relative to the device**.

### Features

| Feature | Status |
|---|---|
| Face-driven screen rotation | ✅ Verified on device |
| Orientation-sensor triggered (camera off by default) | ✅ Verified on device |
| Yields when another app holds the camera (four gates) | ✅ Verified on device |
| Quick Settings tile (two states) | ✅ Verified on device |
| Power-saving / Responsive capture modes | ✅ Verified on device |
| Two-step orientation calibration | 🔎 Implemented, pending verification |
| GitHub Release update check | 🔎 Implemented, pending verification |
| **Background persistence** (LSPosed module, host = SystemUI process) | ✅ Verified on device |

### Installation (two modes)

| Mode | Requirements | What you get |
|---|---|---|
| **Standalone** | Just install the APK | Rotation follows your face **while the app is open**; it stops as soon as the app is backgrounded (Android 14+ forbids background camera access) |
| **Resident** | APK + root + LSPosed, with the module scope set to `com.android.systemui` | The engine lives inside the **SystemUI process**: it keeps working after you return to the home screen, lock the screen, or force-stop the app |

Resident mode needs root for config sync. Writing a *custom* `Settings.System` key from an app is rejected
(`You cannot keep your settings in the secure settings.`), and **neither the regular `WRITE_SETTINGS`
nor `WRITE_SECURE_SETTINGS` helps** — that gate only admits the system, shell and root identities, plus
privileged packages. So the app writes its preferences through `settings put` under the root identity,
and the host process watches the same table. There is a one-tap "grant root" button in the UI; it
persists across reboots and app updates and is only lost on uninstall. Without it the app simply falls
back to standalone mode.

### How it works

1. **The camera is not kept open.** A hardware `TYPE_DEVICE_ORIENTATION` (on-change) sensor wakes the pipeline only when the device orientation actually changes. No polling.
2. **Burst capture.** The front camera captures roughly 16 frames (≈1 s) and is **released unconditionally**. Measured bind-to-first-frame latency: about 224 ms.
3. **Yielding comes first.** Four gates guarantee we never fight another app for the camera: pre-check availability → give up on bind failure → yield immediately if preempted while holding → release unconditionally when done. Any gate that trips aborts the round; no queuing, no retries.
4. **Decision.** ML Kit face detection → `headEulerAngleZ` → quantized to the nearest 90° sector → written to `Settings.System.USER_ROTATION`.

#### Two pitfalls that cannot be avoided (both measured)

- **ML Kit cannot detect faces rotated ±90°**, and "portrait ↔ landscape" is exactly the 0° → ±90° transition — right in the blind spot. Each frame therefore tries several extra rotations; since face angle changes continuously, trying the previously hit angle first brings the average search count close to 1.
- **CameraX's `imageInfo.rotationDegrees` is relative to the display orientation at camera-bind time**, not to the device itself (measured: constant 270 across 817 frames while the display rotation varied over 0/1/3). This project therefore uses `CameraCharacteristics.SENSOR_ORIENTATION`, a hardware constant, as a fixed reference — decoupling "face tilt relative to the device" from whatever the screen currently shows.

### Repository layout

```
app/src/main/java/cn/dsr213/hyperplus/
├── AdaptiveEngine.kt          Engine: trigger → yield → burst → decide → take over
├── OrientationDecider.kt      Pure algorithm: roll → screen orientation (unit-testable offline)
├── FaceAnalyzer.kt            CameraX Analyzer + ML Kit multi-angle search
├── VersionChecker.kt          GitHub Release update check
├── AppPrefs.kt                Persisted mode / strategy / calibration
├── RotateTileService.kt       Quick Settings tile
├── CsvRecorder.kt             Per-frame raw data dump for offline curve analysis
├── trigger/
│   └── DeviceOrientationTrigger.kt   Sensor-driven trigger layer
└── ui/
    ├── EngineScreen.kt        MiuiX main screen
    └── FaceRotateTheme.kt     MiuiX theme
```

### Development environment

**Device (measured)**

| Item | Value |
|---|---|
| Model | Xiaomi 2608BPX34C (codename `lhasa`) |
| OS | Android 17 / `CP2A.260605.016` |
| Security patch | 2026-08-01 |
| Platform | `xring_o3_asic` |
| ABI | arm64-v8a |

**Toolchain (measured from this project)**

| Item | Version |
|---|---|
| Android Gradle Plugin | 9.4.0 |
| Gradle | 9.6.0 |
| Kotlin | 2.4.20 |
| JDK | 17 |
| compileSdk / minSdk / targetSdk | 37 / 30 / 35 |
| Compose | 1.12.0 |
| MiuiX | 0.9.4 |
| CameraX | 1.4.1 |
| ML Kit face-detection | 16.1.7 |

> These versions are not arbitrary: MiuiX 0.9.4 requires Compose 1.12 and `compileSdk ≥ 37`; Compose 1.12 requires AGP ≥ 9.1; AGP 9.1 tops out at API 36.1 — so AGP 9.4.0 is required, which in turn requires Gradle ≥ 9.6.0.

### Build

```bash
gradle assembleDebug
```

### Current status

**Early development (Alpha).** Known limits:

- Resident mode is verified on a real device (**it survives returning to the home screen, locking the screen, and force-stopping the app**), but it **requires root and LSPosed**; without the module the app falls back to standalone mode (active only while the app is open).
- **Known blind spot**: the trigger is the orientation sensor, which only fires when the *device* moves. Phone propped on a desk while only your head turns **will not trigger** — there is currently **no** screen-on/unlock fallback trigger.
- On some devices the orientation sensor does not wake while the screen is off, so the trigger layer may receive nothing.
- The sign and phase of the orientation mapping depend on the specific device's camera orientation and mirroring, so the project offers **two-step calibration** (portrait baseline + left-landscape axis) instead of hard-coded parameters.
- This is an early-stage project; implementation details and feasible boundaries are still being validated, and no technical approach is stated here as final.

### Donate

<img src="docs/donate_wechat_qr.png" width="220" alt="WeChat donation QR code">

**On pricing**: free throughout the Alpha stage. Charging for some features after a future Beta or stable release is **not ruled out**.

### Disclaimer

- This is an **unofficial** project, **not affiliated** with Xiaomi, Google, or any of their subsidiaries.
- Product names and trademarks mentioned belong to their respective owners.
- For **learning and personal use only**; use at your own risk.
- ⚠️ This app **modifies system display-orientation settings** (`Settings.System.ACCELEROMETER_ROTATION` and `USER_ROTATION`). It restores them on a normal exit, and self-heals on the next launch if the process was killed. Please be aware of this before using it.
- ⚠️ **Resident mode injects code into the SystemUI process** (LSPosed module, scope `com.android.systemui`) and keeps a camera capture thread alive there. It requests no extra privileges, but **writing custom system settings keys requires root** (see the Installation section for why).
- ⚠️ While the engine holds the takeover it sets `ACCELEROMETER_ROTATION` to 0. **Any fatal exception immediately stops the engine and restores the value**, so it never leaves the screen stuck. Still, if you plan to uninstall the module or disable LSPosed, check that the value is 1 first (or toggle system auto-rotate once).

### License

[AGPL-3.0](LICENSE)
