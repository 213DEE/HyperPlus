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

它解决的是这一件事，也只解决这一件事。除此之外不碰系统任何其它行为。

### 它能做什么

| 能力 | 说明 | 状态 |
|---|---|---|
| **人脸方向跟随旋转** | 前置摄像头看一眼你脸朝哪，屏幕就朝哪 | ✅ 真机实测（需先在「实验功能」里打开开关） |
| **半自动旋转（点击确认）** | 转手机时右下角弹出按钮，**点一下才转**；不开摄像头，最省电 | ✅ 真机实测 |
| **跟随系统** | 本应用完全不介入，用系统自带的自动旋转 | ✅ 真机实测 |
| **常驻生效** | 引擎住在系统界面进程里，回桌面 / 锁屏 / App 被强杀后仍然生效 | ✅ 真机实测 |
| **不常开摄像头** | 靠朝向传感器唤醒，一次只采约 1 秒，采完立刻释放 | ✅ 真机实测 |
| **绝不和别的 App 抢摄像头** | 四道闸门保证：正在扫码 / 视频通话时本应用自动让位 | ✅ 真机实测 |
| **应用名单** | 名单里的应用（游戏、长视频）不弹按钮、不改方向 | ✅ 真机实测 |
| **控制中心快捷开关** | 三档循环切换，不用打开 App | ✅ 真机实测 |
| **实时角度预览** | 界面上实时显示识别到的角度，用来判断"到底跟没跟上" | ✅ 真机实测 |
| **内屏 / 外屏分别设置** | 折叠屏两块屏的模式互不影响 | ✅ 真机实测 |
| **默认方向** | 指定展开后内屏保持的方向 | 🔎 已实现，**需要 root** |
| **方向校准** | 两步校准，适配不同机型的摄像头朝向 | 🔎 已实现，待更多机型验证 |
| **界面多语言** | 简体中文 / 繁体中文 / English，跟随系统或手动指定 | 🔎 已实现 |
| **更新提醒** | 启动时检查 GitHub Release 是否有新版本 | 🔎 已实现 |

> 「自适应旋转」放在「实验功能」页里是有意的：它需要开摄像头，且方向判定依赖你的机型和
> 握持方式。想用它，先去「实验功能」把开关打开，再回「旋转增强」页把模式选成它。

### 安装

**门槛（写在最前面，免得白装）：需要 root ＋ LSPosed。**

| 需要什么 | 你会得到什么 |
|---|---|
| 装 APK ＋ root ＋ LSPosed；模块作用域**同时**勾选 `com.android.systemui` 与 **HyperPlus 自己** | 引擎常驻在**系统界面进程**里，**回桌面、锁屏、App 被强杀都继续生效** |

**只有一个引擎，它住在系统界面进程里。** App 本身不跑引擎、不开相机，只是一块「遥控器 + 仪表盘」：
你在这里改的每一项配置都写进一份配置文件，常驻引擎读同一份文件并实时跟随。

**为什么作用域要勾两项**（这一条最容易漏）：

- `com.android.systemui` —— 引擎本体注入这里，于是「常驻」成立；
- **HyperPlus 自己** —— 配置从「App 的进程」流到「引擎的进程」，走的是 LSPosed 官方的
  `XSharedPreferences` 通道：只有 App 进程也被注入，它才会把配置写到引擎读得到的位置。
  **漏掉这一项 = 界面上的开关全部无效**（配置写进了应用私有目录，引擎读不到）。

> App 会自检并把这个结论直接写在「配置通道」卡片里 —— 通道没打通时它明确告诉你原因，
> 而不是让你对着一堆点了没反应的开关猜。

**不需要额外的权限。** 相机与「修改系统设置」都由系统界面进程自带的权限承担；
半自动模式的悬浮按钮同理（系统界面是 system uid，不需要「显示在其他应用上层」）。
装好后无需授予任何权限即可使用。

> ⚠️ **唯一的例外是「默认方向」**：折叠屏的展开方向由系统的一个私有槽位决定，读它、写它
> 都需要 root。不用这个功能就完全不需要 root，界面里带 root 字样的按钮也不去点它即可。

### 三种模式，内屏 / 外屏各存一份

| 档位 | 判据 | 开摄像头？ | 谁拍板 |
|---|---|---|---|
| **跟随系统** | 系统自己的重力传感器 | ❌ | 系统 |
| **自适应旋转（人脸）** | 前摄 + 人脸检测投票 | ✅ | 引擎自动 |
| **半自动旋转（点击确认）** | 设备姿态（重力 / 朝向传感器） | ❌ | **你点一下** |

「跟随系统」= 本应用完全不介入，用系统自带的「自动旋转」开关 —— **系统那个开关开着就转、关着就不转**。
没有单独的「关闭旋转」档：它本来就是系统那份设置里的另一个位置，拆成本应用的独立一档
只会让"系统设置"和"本应用"两个地方各说各话（理由写在 `AppPrefs.kt` 的 `RotateMode` 注释里）。

**内屏和外屏的模式是两份、互不影响**（折叠屏用得到：合上只用外屏时常是扫码看通知，设成半自动更省电；
展开用内屏时仍要自动转）。区别只看**当前显示的最小宽度**：

| | 像素 | 换算（密度 440dpi = 2.75x） | 判定 |
|---|---|---|---|
| 内屏 | 1672 × 2364 | **608dp** | ≥ 512dp ⇒ 内屏 |
| 外屏 | 1168 × 1712 | **425dp** | < 512dp ⇒ 外屏 |

取**最小边**而不是宽或高，是为了让这个数**旋转不变** —— 内屏横过来也不会从 608dp 跳到 860dp，
否则"转一下屏就换了一档"这种怪事就会发生。裁决与量法都在 `ScreenForm.kt`，
界面与常驻引擎两边读的是同一个数（`DisplaySize`）。

悬浮按钮也跟着屏幕自适应：可见圆按屏宽比例缩放（都是屏宽的 8.9% ⇒ 内屏 54dp、外屏 38dp），
但**窗口仍取 48dp 做触摸目标**，多出来的一圈透明居中 —— 视觉上变小，不会变得点不中。

### 怎么做的（原理）

1. **不常开相机。** 先用硬件级的 `TYPE_DEVICE_ORIENTATION`（on-change 中断）感知"设备朝向变了"，才唤醒一次采集。零轮询。
2. **burst 采集。** 拉起前置相机采约 16 帧（≈1 秒），**采完立刻无条件释放**。实测相机从 bind 到首帧约 224 ms。
3. **让步优先。** 四道闸门保证绝不和其他 App 抢相机：事前查占用 → 绑定失败即放弃 → 持有期间被抢立即让位 → 采完无条件释放。任意一道拦下就跳过本轮，不排队、不重试。
4. **判定。** 人脸检测 → 头部滚转角 → 量化到最近的 90° 扇区 → 写系统显示方向。

#### 两个绕不过去的坑（都实测过）

- **人脸检测器检不到 ±90° 的人脸**，而"竖屏 ↔ 横屏"恰好就是 0° → ±90° —— 正落在失明区。所以每帧要把图像多转几个角度各试一次；人脸角度是连续的，用"上次命中的角度优先"可以把平均搜索次数压到接近 1 次。
- **CameraX 的 `imageInfo.rotationDegrees` 参考系是"绑定相机那一刻的屏幕方向"**，它不跟设备本体走（实测 817 帧里恒为 270，而同期显示方向在 0/1/3 之间变过）。所以本项目改用 `CameraCharacteristics.SENSOR_ORIENTATION` 这个硬件常量做固定基准，让"人脸相对设备的倾斜"与屏幕当前转成什么样彻底解耦。

#### 配置是怎么下发到常驻引擎的（不需要 root）

两条方向分开走，一句话概括：**「用户能编辑的」走 App 自己的配置文件，「引擎自己算的账」走系统设置库。**

| 数据 | 方向 | 走哪 | 要 root 吗 |
|---|---|---|---|
| 模式 / 策略 / 门控开关 | App → 引擎 | App 的 prefs（LSPosed 的 `XSharedPreferences` 通道） | ❌ |
| 标定请求（你点按钮） | App → 引擎 | App 的 prefs | ❌ |
| 标定值（sign / 偏移） | 引擎自己算并持久化 | `Settings.System` | ❌（引擎是特权包） |
| 接管标志 / 交还目标 / 前摄 id | 引擎自己的账 | `Settings.System` | ❌ |
| 状态 / 心跳 / 标定结果 | 引擎 → App | `Settings.System` | ❌ |

原因很简单：**App 写不了非公开的系统设置键**（判据见「安装」一节），而**引擎写不了 App 的私有文件**
（跨 uid + SELinux 双重拦截）—— 让各自写自己写得动的东西，两边就都不需要 root。
引擎侧的变更检测同时跑「文件监听 + 2 秒兜底轮询」：LSPosed 的文件监听依赖被监控目录的读权限，
而它给的共享目录只有执行位（源码级推断，见 `ModulePrefs` 的类注释），所以不赌它。

### 仓库结构

```
app/src/main/java/cn/dsr213/hyperplus/
├── MainActivity.kt            薄壳界面：只挂载 Compose，**不含引擎**
├── AppPrefs.kt                配置真身（App 侧 prefs）＋ 引擎侧标定值的读写
├── AppWhitelist.kt            应用名单：默认名单 ± 用户增删
├── AppLocale.kt               界面语言（只管 App 进程）
├── ScreenForm.kt              内屏 / 外屏判据 ＋ 全工程唯一的屏幕尺寸量法
├── PanelOrientation.kt        两块屏的方向槽位（默认方向，**需要 root**）
├── RootShell.kt               借 root 执行系统命令的唯一出口
├── PrefsBridge.kt             键名约定 ＋ 引擎侧写 Settings.System 的工具
├── ModuleLink.kt              App 侧：读引擎状态摘要、请求远程标定
├── ── 引擎（只活在 LSPosed 宿主进程里） ──
├── AdaptiveEngine.kt          引擎主体：触发 → 让步 → burst → 判定 → 接管
├── EngineHost / EngineLogic / EngineTuning / EngineErrors
│                              宿主启动与异常兜底 / 纯逻辑 / 可调常量 / 错误归因
├── CameraXBootstrap.kt        宿主进程里的 CameraX 初始化
├── OrientationDecider.kt      纯算法核心：roll → 屏幕方向（不依赖 Android，可离线单测）
├── OrientationFusion.kt       重力当尺子：校验人脸链路的符号位
├── TiltSearch.kt              多角度搜索（对付 ±90° 盲区）
├── VoteTally.kt               多帧投票
├── FaceAnalyzer.kt            CameraX Analyzer ＋ 人脸检测多角度搜索
├── ForegroundGate.kt          前台朝向门（前台应用自管朝向时停手）
├── Uncontrollable.kt          「实测不可控」的记账（哪些应用本模块真的转不动）
├── LiveAngle.kt               实时角度（4Hz，供界面预览，不进配置通道）
├── HintSizePolicy.kt          悬浮按钮的尺寸策略
├── RotateHintOverlay.kt       半自动模式的「转一下？」悬浮按钮
├── CsvRecorder.kt             每帧原始数据落盘，供事后看曲线
├── VersionChecker.kt          GitHub Release 版本检测
├── RotateTileService.kt       控制中心快捷开关
├── trigger/
│   └── DeviceOrientationTrigger.kt   朝向传感器触发层
├── module/                    ── 只在 LSPosed 宿主进程（系统界面）里跑的部分 ──
│   ├── HyperPlusModule.kt     LSPosed 入口（加载即启动常驻引擎）
│   ├── EngineHost.kt          常驻引擎宿主：启动 / 异常兜底 / 状态上报 / 心跳
│   ├── ModulePrefs.kt         配置通道：读 App 的 prefs（＋ 兜底轮询）
│   ├── SystemUiLifecycle.kt   常驻 LifecycleOwner（SystemUI 没有 Activity）
│   ├── HostEnv.kt             宿主环境：native so 预加载 ＋ 人脸模型初始化
│   └── ModuleSelfCheck.kt     模块自检（默认关闭，可开关打开）
└── ui/
    ├── AppNav.kt              全部页面与路由（唯一真值）
    ├── HyperPlusApp.kt        应用外壳
    ├── UiCommon.kt            共用组件
    ├── FunctionPage.kt        主页一：功能（旋转增强 / 实验）
    ├── SettingsPage.kt        主页二：设置（监控与排查 / 应用 / 关于 / 语言）
    ├── RotationPage.kt        旋转增强（模式 / 开关 / 默认方向 / 实时角度 / 校准）
    ├── AppWhitelistPage.kt    应用名单
    ├── DiagnosticsPage.kt     诊断
    ├── StatusPage.kt          运行状态
    ├── ExperimentalPage.kt    实验功能
    ├── LanguagePage.kt        界面语言
    ├── AboutPage.kt           关于
    ├── DonateCard.kt          捐赠卡片
    ├── LiquidGlassBar.kt      液态玻璃悬浮底栏
    ├── LiveAngleSection.kt    实时角度预览
    ├── SlotCalibrationSection.kt  默认方向（根）
    ├── UpdateDialog.kt        更新提示
    └── FaceRotateTheme.kt     主题
```

`res/` 下另有三套字符串资源：`values`（英文，默认）/ `values-zh-rCN` / `values-zh-rTW`。
`design/icon/` 存放桌面图标的矢量母版与生成器。

### 测试环境

**本项目的所有结论都来自下面这一台设备。** 换机型请把「方向校准」走一遍。

**设备（实测）**

| 项 | 值 |
|---|---|
| 机型 | Xiaomi 2608BPX34C（代号 `lhasa`，折叠屏） |
| 系统 | Android 17 / `CP2A.260605.016` |
| 安全补丁 | 2026-08-01 |
| 平台 | `xring_o3_asic` |
| ABI | arm64-v8a |
| 内屏 | 1672 × 2364（608dp） |
| 外屏 | 1168 × 1712（425dp） |

**运行环境（实测）**

| 项 | 值 |
|---|---|
| root | KernelSU（**late-load 临时 root**，重启即失效） |
| 注入框架 | LSPosed（Zygisk） |
| 模块作用域 | `com.android.systemui` ＋ `cn.dsr213.hyperplus` |

**构建工具链（工程实测）**

| 项 | 版本 |
|---|---|
| Android Gradle Plugin | 9.4.0 |
| Gradle | 9.6.0 |
| **JDK** | **21**（MiuiX 的字节码是 JVM 21，JDK 17 编不过 —— 见 `gradle.properties`） |
| Kotlin | 2.4.20 |
| compileSdk / minSdk / targetSdk | 37 / 30 / 35 |
| Compose | 1.12.0 |
| MiuiX | 0.9.4 |
| CameraX | 1.4.1 |
| ML Kit face-detection | 16.1.7 |

> 这组版本不是随手挑的：MiuiX 0.9.4 要求 Compose 1.12 + `compileSdk ≥ 37`，Compose 1.12 要求 AGP ≥ 9.1，而 AGP 9.1 最高只到 API 36.1 —— 必须升到 AGP 9.4.0，它又要求 Gradle ≥ 9.6.0；而 MiuiX 的全部 class 文件都是 JVM 21 字节码，所以 JDK 必须是 21。

### 构建

```bash
# JDK 21 必须。Gradle 会从 gradle.properties 里的 org.gradle.java.home 读路径 ——
# ⚠️ 那一行是作者本机路径，克隆后请改成你自己的 JDK 21 安装路径，或删掉它并设置 JAVA_HOME。
./gradlew assembleDebug          # 调试包
./gradlew assembleRelease        # 未签名的 release 包（自行用 apksigner 签名）
./gradlew testDebugUnitTest      # 单元测试

# ⚠️ 改过公开 API 之后，上面两条都要跑 —— assembleDebug 不编译单测源。
```

### 现状

**早期开发阶段（Alpha）。** 已知边界：

- 引擎常驻在系统界面进程里，已真机实测（**回桌面 / 锁屏 / 强杀 App 后引擎仍在工作**），
  但它**依赖 root 与 LSPosed**，而且**模块没启用 / 作用域漏勾 HyperPlus 自己 = 功能不可用**
  （这是"引擎只有一个、且住在系统进程里"的必然含义，没有退路可走）。
- **已知盲区**：触发源是朝向传感器（设备动了才唤醒）。手机架在桌上不动、只有人头转过去的情况**不会触发** —— 目前**没有**"屏幕亮起 / 解锁"之类的兜底触发源。
- 部分机型的朝向传感器在熄屏时不唤醒，触发层可能收不到事件。
- 方向判定的符号与相位依赖具体机型的摄像头朝向与镜像方式，因此提供**两步标定**（竖屏基准 + 左横屏定方向），而不是把参数硬编码。
- 只在**一台**设备上验证过（见「测试环境」）。折叠屏的内屏 / 外屏差异、各家 ROM 的
  方向策略差异，都还没有足够的样本。
- 早期开发阶段，具体实现手段与可行边界仍在验证中，本文不写死任何技术方案。

### 捐赠

<img src="docs/donate_wechat_qr.png" width="220" alt="微信捐赠二维码">

**关于收费**：Alpha 阶段将始终保持免费；不排除将来推出 Beta 或正式版后，部分功能收费的可能。

### 免责声明

- 本项目为**非官方**项目，与小米（Xiaomi）、Google 及其关联公司**均无任何关联**。
- 文中提及的产品名、商标归各自所有者。
- 仅供**学习与个人使用**，使用风险由使用者自行承担。
- ⚠️ 本项目会**修改系统显示方向设置**（`Settings.System.ACCELEROMETER_ROTATION` 与 `USER_ROTATION`）。正常退出时会还原；若进程被强制终止，下次启动会自动检测并修复。仍建议在了解这一点后再使用。
- ⚠️ **本项目会把代码注入系统界面进程**（LSPosed 模块，作用域 `com.android.systemui`），
  并在其中常驻一个相机采集线程。它**不申请任何额外权限**（相机与写方向都用系统界面自带的），
  配置同步也**不需要 root**。
- ⚠️ 引擎接管期间会把 `ACCELEROMETER_ROTATION` 置 0。**任何致命异常都会立刻停用引擎并把该值还原**，不会留下"屏幕转不动"的状态。但如果你要卸载模块或关掉 LSPosed，建议先确认该值为 1（或手动打开一次系统自动旋转）。
- ⚠️ **需要 root 与 LSPosed 意味着需要解锁 Bootloader**，这会让设备失去官方保修、并可能影响部分
  银行 / 支付类应用。请在完全了解风险后再决定是否使用。

### License

[AGPL-3.0](LICENSE)

---

## English

### What it solves

Anyone who has doom-scrolled while lying on their side knows this: the phone is sideways in your hand, but gravity tells the system "this should be landscape", and the screen flips to an orientation you never wanted.

Auto-rotate only respects **gravity**, not **your face**. HyperPlus drives screen orientation from **the orientation of your face relative to the device**.

That is the one problem it solves, and it touches nothing else in the system.

### What it can do

| Capability | What it means | Status |
|---|---|---|
| **Face-driven rotation** | The front camera takes one look at where your face points; the screen follows | ✅ Verified on device (enable it under "Experimental" first) |
| **Semi-auto (tap to confirm)** | Turn the phone and a button appears; **the screen rotates only when you tap it**. No camera, lowest power | ✅ Verified on device |
| **Follow system** | The app stays out of the way and uses the system auto-rotate toggle | ✅ Verified on device |
| **Resident engine** | The engine lives in the SystemUI process — still working after returning home, locking the screen, or force-stopping the app | ✅ Verified on device |
| **Camera is never kept open** | A hardware orientation sensor wakes a single ~1 s burst, then the camera is released | ✅ Verified on device |
| **Never competes for the camera** | Four gates: while you scan a code or take a video call, this module steps aside | ✅ Verified on device |
| **Per-app list** | Apps on the list (games, long videos) get no button and no rotation | ✅ Verified on device |
| **Quick Settings tile** | Cycle the three modes without opening the app | ✅ Verified on device |
| **Live angle readout** | Shows the detected angle in real time, so you can tell whether it is tracking | ✅ Verified on device |
| **Separate inner / outer settings** | The two panels of a foldable keep independent modes | ✅ Verified on device |
| **Default orientation** | Pin the orientation the inner screen unfolds into | 🔎 Implemented, **requires root** |
| **Two-step calibration** | Adapts to a given device's camera orientation | 🔎 Implemented, needs more devices |
| **UI localisation** | Simplified Chinese / Traditional Chinese / English, system-following or manual | 🔎 Implemented |
| **Update check** | Checks GitHub Releases for a newer version on launch | 🔎 Implemented |

> "Adaptive rotation" lives under an *Experimental* section on purpose: it opens the camera, and the
> orientation mapping depends on your device and how you hold it. To use it, flip the switch under
> Experimental first, then pick it as the mode on the rotation page.

### Installation

**The prerequisite, stated up front so you don't install for nothing: root + LSPosed.**

| Requirements | What you get |
|---|---|
| APK + root + LSPosed, with the module scope set to **`com.android.systemui`** *and* **HyperPlus itself** | The engine lives inside the **SystemUI process**: it keeps working after you return to the home screen, lock the screen, or force-stop the app |

**There is exactly one engine, and it lives in the SystemUI process.** The app itself runs no engine and
opens no camera — it is a remote control plus a dashboard. Everything you change here is written to a
configuration file, and the resident engine reads that same file and follows along.

**Why two entries in the scope list** (this is the easy one to miss):

- `com.android.systemui` — the engine itself is injected there, which is what makes it resident;
- **HyperPlus itself** — config travels from the app process to the engine process over LSPosed's
  official `XSharedPreferences` channel, and that only works if the app process is injected too:
  only then will it write the config where the engine can read it.
  **Miss this one and every switch in the UI silently does nothing** (the config lands in the app's
  private directory, which the engine cannot read).

> The app self-checks this and states the conclusion right in its "config channel" card — when the
> channel is broken it tells you why, instead of leaving you to guess in front of dead switches.

**No extra grants are needed.** The camera and "modify system settings" come from permissions the
SystemUI process already holds, and the semi-auto floating button works the same way (SystemUI runs
as the system uid, so it does not need "display over other apps"). Nothing needs to be granted after
install.

> ⚠️ **The one exception is "Default orientation"**: a foldable's unfold orientation is governed by a
> private system slot, and both reading and writing it require root. Skip that feature and root is
> never needed — just don't tap the buttons marked as requiring root.

### Three modes, stored separately for each panel

| Mode | Signal | Camera? | Who decides |
|---|---|---|---|
| **Follow system** | The system's own gravity sensor | ❌ | The system |
| **Adaptive (face)** | Front camera + face-detection voting | ✅ | The engine, automatically |
| **Semi-auto (tap to confirm)** | Device attitude (gravity / orientation sensor) | ❌ | **You, with a tap** |

"Follow system" means this app stays completely out of the way and the system's own auto-rotate
toggle decides. There is deliberately no separate "rotation off" mode: that is just the other
position of the same system setting, and duplicating it here would leave the system settings page and
this app telling you two different things (the reasoning is in the `RotateMode` comment in `AppPrefs.kt`).

**The inner and outer screens keep two independent sets of settings** — useful on a foldable: when
closed you mostly glance at notifications or scan codes, so semi-auto saves power, while the inner
screen still wants to follow your face. The only thing that distinguishes them is the **smallest
current width**:

| | Pixels | Converted (440 dpi = 2.75×) | Verdict |
|---|---|---|---|
| Inner | 1672 × 2364 | **608dp** | ≥ 512dp ⇒ inner |
| Outer | 1168 × 1712 | **425dp** | < 512dp ⇒ outer |

Using the **shortest side** rather than width or height keeps this number **rotation-invariant** — the
inner screen does not jump from 608dp to 860dp when you turn it, which would otherwise make the mode
change out from under you. Both the UI and the resident engine read the same value (`DisplaySize`).

The floating button scales with the display too: the visible circle is 8.9% of the screen width
(54dp inner, 38dp outer), while the touch target stays a full 48dp with the extra ring transparent
and centred — smaller to the eye, not smaller to your finger.

### How it works

1. **The camera is not kept open.** A hardware `TYPE_DEVICE_ORIENTATION` (on-change) sensor wakes the pipeline only when the device orientation actually changes. No polling.
2. **Burst capture.** The front camera captures roughly 16 frames (≈1 s) and is **released unconditionally**. Measured bind-to-first-frame latency: about 224 ms.
3. **Yielding comes first.** Four gates guarantee we never fight another app for the camera: pre-check availability → give up on bind failure → yield immediately if preempted while holding → release unconditionally when done. Any gate that trips aborts the round; no queuing, no retries.
4. **Decision.** Face detection → head roll angle → quantized to the nearest 90° sector → written to the system display orientation.

#### Two pitfalls that cannot be avoided (both measured)

- **The face detector cannot detect faces rotated ±90°**, and "portrait ↔ landscape" is exactly the 0° → ±90° transition — right in the blind spot. Each frame therefore tries several extra rotations; since face angle changes continuously, trying the previously hit angle first brings the average search count close to 1.
- **CameraX's `imageInfo.rotationDegrees` is relative to the display orientation at camera-bind time**, not to the device itself (measured: constant 270 across 817 frames while the display rotation varied over 0/1/3). This project therefore uses `CameraCharacteristics.SENSOR_ORIENTATION`, a hardware constant, as a fixed reference — decoupling "face tilt relative to the device" from whatever the screen currently shows.

#### How the config reaches the resident engine (no root involved)

The two directions travel separately. In one sentence: **what the user can edit goes through the app's
own config file; what the engine computes for itself goes through the system settings database.**

| Data | Direction | Route | Root? |
|---|---|---|---|
| Mode / strategy / gate switches | app → engine | the app's prefs (LSPosed `XSharedPreferences` channel) | ❌ |
| Calibration requests (your button taps) | app → engine | the app's prefs | ❌ |
| Calibration values (sign / offset) | computed and persisted by the engine | `Settings.System` | ❌ (the engine is a privileged package) |
| Takeover flag / restore target / camera id | the engine's own bookkeeping | `Settings.System` | ❌ |
| Status / heartbeat / calibration result | engine → app | `Settings.System` | ❌ |

The reasoning is simple: **the app cannot write private system-settings keys** (see the Installation
section), and **the engine cannot write the app's private files** (blocked by both the uid boundary and
SELinux). Let each end write what it is actually able to write, and neither needs root. Change detection
on the engine side runs *both* file watching and a 2-second polling fallback: LSPosed's file watcher
needs read permission on the watched directory, while the shared directory it hands out is
execute-only (a source-level inference — see the class comment in `ModulePrefs`), so we don't bet on it.

### Repository layout

```
app/src/main/java/cn/dsr213/hyperplus/
├── MainActivity.kt            Thin shell: mounts Compose, **no engine**
├── AppPrefs.kt                The config itself (app-side prefs) + engine-side calibration values
├── AppWhitelist.kt            Per-app list: default set ± user additions/removals
├── AppLocale.kt               UI language (app process only)
├── ScreenForm.kt              Inner/outer verdict + the project's only screen-measurement routine
├── PanelOrientation.kt        Per-panel orientation slot (default orientation, **needs root**)
├── RootShell.kt               The single place that runs a command as root
├── PrefsBridge.kt             Key naming + engine-side helpers to write Settings.System
├── ModuleLink.kt              App side: read the engine's status summary, request remote calibration
├── ── engine (only alive inside the LSPosed host process) ──
├── AdaptiveEngine.kt          Engine: trigger → yield → burst → decide → take over
├── EngineHost / EngineLogic / EngineTuning / EngineErrors
│                              Host startup and panic handling / pure logic / tunables / error attribution
├── CameraXBootstrap.kt        CameraX init inside the host process
├── OrientationDecider.kt      Pure algorithm: roll → screen orientation (unit-testable offline)
├── OrientationFusion.kt       Gravity as a ruler: validates the sign of the face pipeline
├── TiltSearch.kt              Multi-angle search (works around the ±90° blind spot)
├── VoteTally.kt               Multi-frame voting
├── FaceAnalyzer.kt            CameraX Analyzer + multi-angle face detection
├── ForegroundGate.kt          Foreground-orientation gate (stand down when the foreground app drives orientation)
├── Uncontrollable.kt          Bookkeeping for apps this module genuinely cannot rotate
├── LiveAngle.kt               Live angle (4 Hz, UI preview only — never enters the config channel)
├── HintSizePolicy.kt          Sizing policy for the floating button
├── RotateHintOverlay.kt       The "rotate?" floating button used by semi-auto mode
├── CsvRecorder.kt             Per-frame raw data dump for offline curve analysis
├── VersionChecker.kt          GitHub Release update check
├── RotateTileService.kt       Quick Settings tile
├── trigger/
│   └── DeviceOrientationTrigger.kt   Sensor-driven trigger layer
├── module/                    ── runs only inside the LSPosed host process (SystemUI) ──
│   ├── HyperPlusModule.kt     LSPosed entry point (starts the resident engine on load)
│   ├── EngineHost.kt          Resident engine host: startup / panic handling / status reporting / heartbeat
│   ├── ModulePrefs.kt         Config channel: reads the app's prefs (+ polling fallback)
│   ├── SystemUiLifecycle.kt   Resident LifecycleOwner (SystemUI has no Activity)
│   ├── HostEnv.kt             Host environment: native so preloading + face model init
│   └── ModuleSelfCheck.kt     Module self-check (off by default, can be enabled)
└── ui/
    ├── AppNav.kt              All pages and routes (single source of truth)
    ├── HyperPlusApp.kt        App shell
    ├── UiCommon.kt            Shared components
    ├── FunctionPage.kt        Home page 1: Function (rotation / experimental)
    ├── SettingsPage.kt        Home page 2: Settings (monitoring / apps / about / language)
    ├── RotationPage.kt        Rotation (mode / switches / default orientation / live angle / calibration)
    ├── AppWhitelistPage.kt    Per-app list
    ├── DiagnosticsPage.kt     Diagnostics
    ├── StatusPage.kt          Runtime status
    ├── ExperimentalPage.kt    Experimental features
    ├── LanguagePage.kt        UI language
    ├── AboutPage.kt           About
    ├── DonateCard.kt          Donation card
    ├── LiquidGlassBar.kt      Liquid-glass floating bottom bar
    ├── LiveAngleSection.kt    Live angle preview
    ├── SlotCalibrationSection.kt  Default orientation (root)
    ├── UpdateDialog.kt        Update prompt
    └── FaceRotateTheme.kt     Theme
```

`res/` also ships three string sets: `values` (English, default) / `values-zh-rCN` / `values-zh-rTW`.
`design/icon/` holds the vector master for the launcher icon and its generator.

### Test environment

**Every claim in this README comes from the single device below.** On a different model, run the
two-step calibration.

**Device (measured)**

| Item | Value |
|---|---|
| Model | Xiaomi 2608BPX34C (codename `lhasa`, foldable) |
| OS | Android 17 / `CP2A.260605.016` |
| Security patch | 2026-08-01 |
| Platform | `xring_o3_asic` |
| ABI | arm64-v8a |
| Inner screen | 1672 × 2364 (608dp) |
| Outer screen | 1168 × 1712 (425dp) |

**Runtime (measured)**

| Item | Value |
|---|---|
| root | KernelSU (**late-load temporary root**; lost on reboot) |
| Injection framework | LSPosed (Zygisk) |
| Module scope | `com.android.systemui` + `cn.dsr213.hyperplus` |

**Toolchain (measured from this project)**

| Item | Version |
|---|---|
| Android Gradle Plugin | 9.4.0 |
| Gradle | 9.6.0 |
| **JDK** | **21** (MiuiX ships JVM 21 bytecode; JDK 17 cannot compile it — see `gradle.properties`) |
| Kotlin | 2.4.20 |
| compileSdk / minSdk / targetSdk | 37 / 30 / 35 |
| Compose | 1.12.0 |
| MiuiX | 0.9.4 |
| CameraX | 1.4.1 |
| ML Kit face-detection | 16.1.7 |

> These versions are not arbitrary: MiuiX 0.9.4 requires Compose 1.12 and `compileSdk ≥ 37`; Compose
> 1.12 requires AGP ≥ 9.1; AGP 9.1 tops out at API 36.1 — so AGP 9.4.0 is required, which in turn
> requires Gradle ≥ 9.6.0; and since all of MiuiX's classes are JVM 21 bytecode, the JDK must be 21.

### Build

```bash
# JDK 21 is mandatory. Gradle reads its path from org.gradle.java.home in gradle.properties —
# ⚠️ that line points at the author's machine; change it to your own JDK 21 path after cloning,
# or delete it and set JAVA_HOME instead.
./gradlew assembleDebug          # debug build
./gradlew assembleRelease        # unsigned release build (sign it yourself with apksigner)
./gradlew testDebugUnitTest      # unit tests

# ⚠️ Run both after changing any public API — assembleDebug does not compile test sources.
```

### Current status

**Early development (Alpha).** Known limits:

- The engine lives inside the SystemUI process and is verified on a real device (**it survives returning
  to the home screen, locking the screen, and force-stopping the app**), but it **requires root and
  LSPosed** — and **if the module is not enabled, or HyperPlus itself is missing from the scope, the
  feature simply does not work** (that is the necessary consequence of having exactly one engine, living
  in the system process; there is no fallback path).
- **Known blind spot**: the trigger is the orientation sensor, which only fires when the *device* moves. Phone propped on a desk while only your head turns **will not trigger** — there is currently **no** screen-on/unlock fallback trigger.
- On some devices the orientation sensor does not wake while the screen is off, so the trigger layer may receive nothing.
- The sign and phase of the orientation mapping depend on the specific device's camera orientation and mirroring, so the project offers **two-step calibration** (portrait baseline + left-landscape axis) instead of hard-coded parameters.
- Verified on **one** device only (see Test environment). Foldable inner/outer differences and the
  orientation policies of other ROMs have far too few samples yet.
- This is an early-stage project; implementation details and feasible boundaries are still being validated, and no technical approach is stated here as final.

### Donate

<img src="docs/donate_wechat_qr.png" width="220" alt="WeChat donation QR code">

**On pricing**: free throughout the Alpha stage. Charging for some features after a future Beta or stable release is **not ruled out**.

### Disclaimer

- This is an **unofficial** project, **not affiliated** with Xiaomi, Google, or any of their subsidiaries.
- Product names and trademarks mentioned belong to their respective owners.
- For **learning and personal use only**; use at your own risk.
- ⚠️ This app **modifies system display-orientation settings** (`Settings.System.ACCELEROMETER_ROTATION` and `USER_ROTATION`). It restores them on a normal exit, and self-heals on the next launch if the process was killed. Please be aware of this before using it.
- ⚠️ **This project injects code into the SystemUI process** (LSPosed module, scope
  `com.android.systemui`) and keeps a camera capture thread alive there. It **requests no extra
  permissions** (the camera and the settings write both use what SystemUI already holds), and config
  sync **does not need root** either.
- ⚠️ While the engine holds the takeover it sets `ACCELEROMETER_ROTATION` to 0. **Any fatal exception immediately stops the engine and restores the value**, so it never leaves the screen stuck. Still, if you plan to uninstall the module or disable LSPosed, check that the value is 1 first (or toggle system auto-rotate once).
- ⚠️ **Requiring root and LSPosed means unlocking the bootloader**, which voids the official warranty and
  can break some banking / payment apps. Decide with full knowledge of the risk.

### License

[AGPL-3.0](LICENSE)
