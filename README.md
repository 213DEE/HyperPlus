# HyperPlus

**给小米 18 Fold 补上它缺的那几项实用功能。**

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-3ddc84)
![Language](https://img.shields.io/badge/Kotlin-2.4.20-7f52ff)
![Stage](https://img.shields.io/badge/stage-Alpha-orange)

[中文](#中文) · [English](#english)

---

## 中文

HyperPlus 是一个 LSPosed 模块，为小米 18 Fold 增加系统本身没有的实用功能。引擎常驻系统界面进程，App 只做配置界面。

### 运行环境

| 需要 | 具体 |
|---|---|
| root | 任意（KernelSU / Magisk …） |
| LSPosed | **需支持 libxposed API 101 及以上**（现行的 LSPosed 2.x 都满足，1.8 及更早的版本不行） |
| Android | **11 及以上** |
| CPU | **arm64-v8a** —— APK 只带这一套原生库，**纯 32 位机型直接装不上** |
| 摄像头 | **必须有前置摄像头** |
| 空间 | APK 约 41 MB，装机后还会解出约 8.6 MB 原生库 |
| 系统 | **分屏功能依赖小米 HyperOS**，旋转功能不限机型。不需要 GMS，不需要联网下载模型（人脸模型打在 APK 里） |

**模块作用域只需要勾一项：`com.android.systemui`。**

- `com.android.systemui` —— 引擎本体注入这里，「常驻」就是这么成立的；
- **HyperPlus 自己不用勾** —— 配置从 App 进程流到引擎进程走的是模块自己的通道（App 主动推送 ＋ 引擎代写镜像），**不需要 App 进程也被注入**。

**不需要额外权限。** 相机与「修改系统设置」都由系统界面进程自带的权限承担；半自动模式的悬浮按钮同理（系统界面是 system uid，不需要「显示在其他应用上层」）。装好后无需授予任何权限即可使用。

> ⚠️ 唯一的例外是下面标了 **需要 root** 的三项。不用它们就完全不需要 root，带 root 字样的按钮不去点它即可。

> ⚠️ **代价，也先说清楚**：本模块**会把代码注入系统界面进程**并在其中常驻，还会**修改系统的显示方向设置**（正常退出时还原；进程被强杀后下次启动会自动修复）。而「需要 root 与 LSPosed」意味着要**解锁 Bootloader**，会让设备失去官方保修，也可能影响部分银行 / 支付类应用。

**装完就不用管。** 引擎住在系统界面进程里，不需要给本应用加自启动或省电白名单 —— 退到后台、锁屏、划掉、清后台都不影响，重启手机后也自动恢复。

> 📌 目前只在**一台**设备上验证过（小米折叠屏 / HyperOS）。换机型请在「方向」卡里核对展开方向。

### 功能

#### 旋转

**三种转屏方式，随时切换：**

| | 怎么转 |
|---|---|
| **人脸** | 看你的脸朝哪边，屏幕就转到哪边。适合躺着、侧着用 |
| **半自动** | 转动手机后，右下角弹出一个按钮，点一下才转。不碰摄像头，最省电 |
| **跟随系统** | 完全交给系统的「自动旋转」，模块不插手 |

**在此基础上还能：**

| 功能 | 需要 root |
|---|---|
| 每个应用单独挑一种转屏方式（没挑的跟随全局） | |
| 光线太暗或看不到脸时，自动改成"点一下才转" | |
| 指定哪些应用不要干预（游戏默认在里面，玩的时候不抢资源） | |
| 转屏按钮停留多久（1–60 秒可调） | |
| 控制中心快捷开关，单击循环切换三种方式 | |
| 每次展开内屏时的默认朝向 | ⚠️ 需要 |

#### 分屏

| 功能 | 需要 root |
|---|---|
| 轻折一下机身，当前应用挤成半屏，再多出一格分屏 | |
| 分屏展开时，当前应用留在左半边还是右半边 | |
| 指定哪些应用不要被分屏（游戏默认在里面） | |
| 分屏的温度上限（默认 47°C）与过热保护开关 | |
| 最多同时摆 8 个应用（系统默认 6 个） | ⚠️ 需要 |

> 「轻折加分屏」默认关闭，到「实验功能」把它打开。
> 「最多同时摆 8 个应用」改完要重启一次系统界面。

### 遇到问题：怎么导出日志、怎么提 issue

#### 先看这个 —— 装完系统界面反复重启，甚至进不了桌面

**唯一要做的紧急动作是先把模块停掉：**

1. 在 LSPosed 管理器里**关掉本模块**，然后重启一次系统界面（或整机）；
2. 管理器已经进不去：LSPosed 自带崩溃保护，通常在连续崩溃后**会自己把模块禁用**，等它一两轮；
3. 系统完全起不来：用 Magisk / KernelSU 的**安全模式**（开机时按住音量减）进入，在那里关掉模块。

> **为什么会「反复」重启**：引擎住在系统界面进程里，而「出错就停用」这个开关只在**当前这一次**系统界面进程里有效 —— 进程一重启它就复位，于是同一个故障会被一遍遍重放。本模块有**启动熔断**兜底：同一个故障连着重放 3 次（每次都没能活过 60 秒）就不再自动启动。要恢复，在 App 里点一下「重新启用」。

#### 导出诊断日志（这一步最重要）

**设置 → 监控与排查 → 诊断 → 「导出诊断日志」**，然后把这个 txt 文件分享 / 发出来。

报告里包含：设备与系统信息、模块状态、当前配置、**App 与模块的运行日志**、以及**系统崩溃与重启记录**。**没授权 root 也能导出**（模块那部分日志读不到，报告里会写明原因）。

#### 提交 issue

到 **https://github.com/213DEE/HyperPlus/issues** 新建一条，把上面那份 txt **附上**（粘贴或附件都行），再写一句你遇到的现象。

**方便的话请顺手说明这三点**：机型与系统版本 · LSPosed 里有没有勾 `com.android.systemui` · 问题出现的步骤。

**万一日志导不出来**，有 root 的话也可以手动取：

```bash
adb logcat -b crash -d                                                    # Java 崩溃栈
adb shell su -c "logcat -d -s HyperPlusSplit FaceRotate HyperPlusModule"  # 模块日志
adb shell ls -l /data/tombstones/                                         # 有文件 = native 崩溃
```

### License

[AGPL-3.0](LICENSE)

---

## English

HyperPlus is an LSPosed module that adds features the Xiaomi 18 Fold does not ship with. The engine is resident in the SystemUI process; the app is a configuration UI only.

### Requirements

| Requirement | Detail |
|---|---|
| root | any (KernelSU / Magisk …) |
| LSPosed | **must support libxposed API 101 or newer** (every current LSPosed 2.x does; 1.8 and older does not) |
| Android | **11 or newer** |
| CPU | **arm64-v8a** — the APK ships only that ABI, so **32-bit-only devices cannot install it at all** |
| Camera | a **front camera is required** |
| Storage | ~41 MB for the APK, plus ~8.6 MB of native libraries unpacked at install time |
| System | **split screen requires Xiaomi HyperOS**; rotation is not model-specific. No Google Play Services, no model download (the face model ships inside the APK) |

**The module scope needs exactly one entry: `com.android.systemui`.**

- `com.android.systemui` — the engine itself is injected there, which is what makes it resident;
- **HyperPlus itself does not need to be ticked** — config travels from the app process to the engine over the module's own channel (the app pushes, the engine mirrors), and that **does not require the app process to be injected**.

**No extra grants are needed.** The camera and "modify system settings" come from permissions the SystemUI process already holds, and the semi-auto floating button works the same way (SystemUI runs as the system uid, so it does not need "display over other apps"). Nothing needs to be granted after install.

> ⚠️ The only exceptions are the three rows marked **Root? Yes** below. Skip those and root is never needed — just don't tap the buttons marked as requiring root.

> ⚠️ **The cost, also stated up front**: this module **injects code into the SystemUI process** and keeps it resident there, and it **modifies the system display-orientation settings** (restored on a normal exit, self-healed on the next launch if the process was killed). Requiring root and LSPosed means **unlocking the bootloader**, which voids the official warranty and can break some banking / payment apps.

**Set it and forget it.** The engine lives inside the SystemUI process, so this app needs no autostart entry and no battery whitelist — backgrounding, locking, swiping it away or clearing it from Recents all change nothing, and a reboot brings it back on its own.

> 📌 Every claim here comes from **one** device (a Xiaomi foldable running HyperOS). On a different model, confirm the unfold orientation on the "Orientation" card.

### Features

#### Rotation

**Three ways to rotate, switchable at any time:**

| | How it rotates |
|---|---|
| **Face** | Sees which way your face points and turns the screen that way. Good for lying down or lying on your side |
| **Semi-auto** | Turn the phone and a button pops up in the bottom-right; tap it to rotate. No camera, lowest battery use |
| **Follow system** | Left entirely to the system's "Auto-rotate"; the module stays out of the way |

**On top of that:**

| Feature | Root |
|---|---|
| Pick a rotation mode per app (unset apps follow the global one) | |
| Falls back to tap-to-rotate when it's too dark or no face is visible | |
| Exempt apps you don't want it to touch (games are on the list by default) | |
| How long the rotate button stays on screen (1–60 s) | |
| Quick Settings tile — one tap cycles the three modes | |
| Default orientation each time the inner screen unfolds | ⚠️ Yes |

#### Split screen

| Feature | Root |
|---|---|
| Light fold squeezes the current app into half the screen and adds one more pane | |
| Keep the current app on the left or right half | |
| Exempt apps you don't want split (games are on the list by default) | |
| Split temperature cap (47 °C by default) and the overheat-guard switch | |
| Up to 8 apps at once (system default: 6) | ⚠️ Yes |

> "Fold to add a split" is off by default — turn it on under "Experimental".
> Reboot System UI once after raising the limit to 8.

### When something goes wrong: exporting logs and filing an issue

#### Read this first — SystemUI keeps restarting, sometimes to the point where the launcher never comes up

**The only urgent step is to stop the module:**

1. disable this module in the LSPosed manager, then restart SystemUI (or the device);
2. if the manager is unreachable: LSPosed has crash protection and normally **disables the module by itself** after repeated crashes — give it a round or two;
3. if the system never comes back, boot into Magisk / KernelSU **safe mode** (hold Volume Down during boot) and disable the module there.

> **Why it restarts "repeatedly"**: the engine lives inside the SystemUI process, and its "stop on any error" switch only holds for **the current SystemUI process** — a restart resets it, so the same fault is replayed over and over. A **boot breaker** caps this: three replays in a row (each failing to survive 60 seconds) stop the engine from auto-starting. Tap "Re-enable" in the app to recover.

#### Export the diagnostic log (the most important step)

**Settings → Monitoring & troubleshooting → Diagnostics → "Export diagnostic log"**, then share the txt file it produces.

The report contains device and system info, module state, the current config, **the runtime logs of both the app and the module**, and **system crash and restart records**. It **works without root** (the module's own logs cannot be read then, and the report says so).

#### File an issue

Open a new one at **https://github.com/213DEE/HyperPlus/issues**, **attach** the txt from above (paste or attach), and describe what you saw.

**If you can, also mention these three things**: model and system version · whether `com.android.systemui` is ticked in LSPosed · the steps that trigger the problem.

**If you cannot export the log at all**, you can also collect it manually (root required):

```bash
adb logcat -b crash -d                                                    # Java crash stack
adb shell su -c "logcat -d -s HyperPlusSplit FaceRotate HyperPlusModule"  # module log
adb shell ls -l /data/tombstones/                                         # a file here = native crash
```

### License

[AGPL-3.0](LICENSE)
