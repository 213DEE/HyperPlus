# HyperPlus

**让屏幕方向跟着你的脸转，而不是跟着重力转。**

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-3ddc84)
![Language](https://img.shields.io/badge/Kotlin-2.4.20-7f52ff)
![Stage](https://img.shields.io/badge/stage-Alpha-orange)

[中文](#中文) · [English](#english)

---

## 中文

睡前侧躺着刷手机，手机是侧着拿的，重力却告诉系统"该横屏了"——屏幕就转到你根本不想看的方向。

系统的自动旋转**只认重力，不认你的脸**。HyperPlus 让屏幕方向由**人脸相对设备的朝向**决定。

> 📌 这篇 README 只讲三件事：**① 有哪些功能 ② 需要什么运行环境 ③ 出问题怎么导出日志、怎么提 issue。**

### 一、这个模块有哪些功能

#### 旋转（只在展开后的内屏生效）

| 功能 | 说明 | 要 root 吗 |
|---|---|---|
| **人脸方向跟随旋转** | 前摄看一眼你脸朝哪，屏幕就朝哪。**首次使用要先到「实验功能」把开关打开** | ❌ |
| **半自动旋转（点击确认）** | 转手机时右下角弹出按钮，**点一下才转**；不开摄像头，最省电 | ❌ |
| **跟随系统** | 本模块完全不介入，用系统自带的自动旋转 | ❌ |
| **暗光降级** | 光线太暗或认不出脸时自动改成「点一下才转」，光线恢复后自动切回 | ❌ |
| **每应用单独挑旋转方式** | 逐个应用指定：跟随全局 / 跟随系统 / 人脸 / 半自动；没指定的跟随全局那一档 | ❌ |
| **旋转应用名单** | 名单里的应用（游戏、长视频）不弹按钮、不改方向 | ❌ |
| **控制中心快捷开关** | 三档循环切换，不用打开 App | ❌ |
| **方向** | 展开后内屏保持的方向，四选一（按摄像头位置选） | ⚠️ **需要** |

#### 分屏（只在展开后的内屏生效）

| 功能 | 说明 | 要 root 吗 |
|---|---|---|
| **轻折加分屏** | 轻折一下机身，把当前应用挤成半屏、再多出一格分屏；当前应用留在哪一侧可选（朝左 / 朝右）。**默认关闭 —— 到「分屏增强」页把开关打开** | ❌ |
| **分屏应用名单** | 名单里的应用（默认＝市面上常见游戏）折一下也不分屏 | ❌ |
| **高温保护可配置** | 增加分屏格子的温度上限可调（默认 47°C，是出厂设定值），过热保护也可以整个关掉 | ❌ |
| **提高分屏上限** | 打开后折叠时最多同时摆 8 个应用（系统默认 6 个）；改完要重启一次系统界面 | ⚠️ **需要**（写开关） |

> ⚠️ **分屏这一组依赖小米 HyperOS 的私有实现**，非小米机型上不会生效。旋转那一组不挑机型。

> 📌 「轻折加分屏」的界面开关是 **2026-10-07 才接上的** —— 在此之前它只能在 adb 里设置档位，界面上没有入口，所以对用户一直是关的。如果你打开开关后轻折仍然没反应，请照第三节把诊断日志导出来提个 issue。

#### 通用

| 功能 | 说明 | 要 root 吗 |
|---|---|---|
| **常驻生效** | 引擎住在系统界面进程里 —— 回桌面 / 锁屏 / App 被强杀后仍然生效 | ❌ |
| **不常开摄像头** | 靠朝向传感器唤醒，一次只采约 1 秒，采完立刻释放 | ❌ |
| **绝不和别的 App 抢摄像头** | 四道闸门保证：正在扫码 / 视频通话时本模块自动让位 | ❌ |
| **外屏交给系统** | 增强只作用在展开后的内屏；合上后本模块不介入，屏幕仍由系统决定 | ❌ |
| **权限管理页** | 模块 / root / 修改系统设置三项自检，缺哪项点哪一行去授权，可随时重新检测 | ❌ |
| **导出诊断日志** | 一键导出设备信息、模块状态与运行日志（没 root 也能导出） | ❌ |
| **重启系统界面** | 设置页一键重启状态栏与导航栏，改了设置没生效时用 | ⚠️ **需要** |
| **界面多语言** | 简体中文 / 繁体中文 / English，跟随系统或手动指定 | ❌ |
| **更新提醒** | 启动时检查 GitHub Release 是否有新版本 | ❌ |

**只有一个引擎，它住在系统界面进程里。** App 本身不跑引擎、不开相机，只是一块「遥控器 ＋ 仪表盘」：你在这里改的每一项配置都会直接推给引擎，即时生效，并跨重启保留。

### 二、需要什么运行环境

**门槛写在最前面，免得白装：需要 root ＋ LSPosed。**

| 需要 | 具体 |
|---|---|
| root | 任意（KernelSU / Magisk …） |
| LSPosed | **需支持 libxposed API 101 及以上**（现行的 LSPosed 2.x 都满足，1.8 及更早的版本不行） |
| Android | **11 及以上** |
| CPU | **arm64-v8a** —— APK 只带这一套原生库，**纯 32 位机型直接装不上** |
| 摄像头 | **必须有前置摄像头** |
| 空间 | APK 约 41 MB，装机后还会解出约 8.6 MB 原生库 |
| 系统 | **分屏那一组依赖小米 HyperOS**；旋转那一组不限机型。除此之外不需要 GMS、不需要联网下载模型（人脸模型打在 APK 里） |

**模块作用域只需要勾一项：`com.android.systemui`。**

- `com.android.systemui` —— 引擎本体注入这里，「常驻」就是这么成立的；
- **HyperPlus 自己不用勾** —— 配置从 App 进程流到引擎进程走的是模块自己的通道（App 主动推送 ＋ 引擎代写镜像），**不需要 App 进程也被注入**。

**不需要额外权限。** 相机与「修改系统设置」都由系统界面进程自带的权限承担；半自动模式的悬浮按钮同理（系统界面是 system uid，不需要「显示在其他应用上层」）。装好后无需授予任何权限即可使用。

> ⚠️ 唯一的例外是上面表里标了 **要 root** 的三项（方向 / 提高分屏上限 / 重启系统界面）。不用它们就完全不需要 root，带 root 字样的按钮不去点它即可。

> ⚠️ **代价，也先说清楚**：本模块**会把代码注入系统界面进程**并在其中常驻，还会**修改系统的显示方向设置**（正常退出时还原；进程被强杀后下次启动会自动修复）。而「需要 root 与 LSPosed」意味着要**解锁 Bootloader**，会让设备失去官方保修，也可能影响部分银行 / 支付类应用。

> 📌 目前只在**一台**设备上验证过（小米折叠屏 / HyperOS）。换机型请在「方向」卡里核对展开方向。

### 三、遇到问题：怎么导出日志、怎么提 issue

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

Anyone who has doom-scrolled while lying on their side knows this: the phone is sideways in your hand, but gravity tells the system "this should be landscape", and the screen flips to an orientation you never wanted.

Auto-rotate only respects **gravity**, not **your face**. HyperPlus drives screen orientation from **the orientation of your face relative to the device**.

> 📌 This README covers exactly three things: **① what it can do ② what it needs to run ③ how to export logs and file an issue.**

### 1. What it can do

#### Rotation (inner screen only, i.e. when unfolded)

| Capability | What it means | Root? |
|---|---|---|
| **Face-driven rotation** | The front camera takes one look at where your face points, and the screen follows. **Enable it under "Experimental" first** | ❌ |
| **Semi-auto (tap to confirm)** | Turn the phone and a button appears in the corner; **the screen rotates only when you tap it**. No camera, lowest power | ❌ |
| **Follow system** | This module stays out of the way and uses the system auto-rotate toggle | ❌ |
| **Low-light fallback** | When it is too dark or no face is visible it switches to tap-to-rotate, then switches back once there is enough light | ❌ |
| **Per-app rotation mode** | Pick a mode per app: follow global / follow system / face / semi-auto; apps you leave unset follow the global mode | ❌ |
| **Rotation app list** | Apps on the list (games, long videos) get no button and no rotation | ❌ |
| **Quick Settings tile** | Cycle the three modes without opening the app | ❌ |
| **Orientation** | Pick the orientation the inner screen unfolds into (one of four) | ⚠️ **Yes** |

#### Split screen (inner screen only, i.e. when unfolded)

| Capability | What it means | Root? |
|---|---|---|
| **Fold to add a split** | A light fold squeezes the current app into half the screen and adds one more pane; you choose which side the current app stays on (left / right). **Off by default — turn the switch on under "Split screen enhancements"** | ❌ |
| **Split app list** | Apps on the list (common games by default) are never split when you fold | ❌ |
| **Configurable overheat guard** | The temperature limit for adding panes is adjustable (default 47 °C, the factory value) and the guard can be turned off entirely | ❌ |
| **Raise the split limit** | Once on, up to 8 apps can be split while folded (the system default is 6); a SystemUI restart is needed | ⚠️ **Yes** (to write the switch) |

> ⚠️ **The split-screen group relies on Xiaomi HyperOS private implementations** and does nothing on non-Xiaomi devices. The rotation group is not model-specific.

> 📌 The **"Fold to add a split" switch only got its UI entry on 2026-10-07**. Before that the mode could only be set from adb — there was no way to reach it from the app, so for users it was permanently off. If you turn the switch on and folding still does nothing, please follow section 3 and attach the diagnostic log to a new issue.

#### General

| Capability | What it means | Root? |
|---|---|---|
| **Resident engine** | The engine lives inside the SystemUI process — still working after returning home, locking the screen, or force-stopping the app | ❌ |
| **Camera is never kept open** | A hardware orientation sensor wakes a single ~1 s burst, then the camera is released | ❌ |
| **Never competes for the camera** | Four gates: while you scan a code or take a video call, this module steps aside | ❌ |
| **Outer screen left to the system** | Enhancements apply only on the unfolded inner screen; once folded the module stays out of the way | ❌ |
| **Permissions page** | Self-check for module / root / write-system-settings; tap a missing row to grant it, re-check any time | ❌ |
| **Export diagnostic log** | One tap exports device info, module state and runtime logs (works without root) | ❌ |
| **Restart System UI** | One tap restarts the status bar and navigation bar — use it when a change does not take effect | ⚠️ **Yes** |
| **UI localisation** | Simplified Chinese / Traditional Chinese / English, system-following or manual | ❌ |
| **Update check** | Checks GitHub Releases for a newer version on launch | ❌ |

**There is exactly one engine, and it lives in the SystemUI process.** The app itself runs no engine and opens no camera — it is a remote control plus a dashboard. Everything you change here is pushed straight to the engine, effective immediately and preserved across reboots.

### 2. What it needs to run

**The prerequisite, stated up front so you don't install for nothing: root + LSPosed.**

| Requirement | Detail |
|---|---|
| root | any (KernelSU / Magisk …) |
| LSPosed | **must support libxposed API 101 or newer** (every current LSPosed 2.x does; 1.8 and older does not) |
| Android | **11 or newer** |
| CPU | **arm64-v8a** — the APK ships only that ABI, so **32-bit-only devices cannot install it at all** |
| Camera | a **front camera is required** |
| Storage | ~41 MB for the APK, plus ~8.6 MB of native libraries unpacked at install time |
| System | **the split-screen group requires Xiaomi HyperOS**; the rotation group is not model-specific. Nothing else is needed: no Google Play Services, no model download (the face model ships inside the APK) |

**The module scope needs exactly one entry: `com.android.systemui`.**

- `com.android.systemui` — the engine itself is injected there, which is what makes it resident;
- **HyperPlus itself does not need to be ticked** — config travels from the app process to the engine over the module's own channel (the app pushes, the engine mirrors), and that **does not require the app process to be injected**.

**No extra grants are needed.** The camera and "modify system settings" come from permissions the SystemUI process already holds, and the semi-auto floating button works the same way (SystemUI runs as the system uid, so it does not need "display over other apps"). Nothing needs to be granted after install.

> ⚠️ The only exceptions are the three rows marked **Root? Yes** above (Orientation / Raise the split limit / Restart System UI). Skip those and root is never needed — just don't tap the buttons marked as requiring root.

> ⚠️ **The cost, also stated up front**: this module **injects code into the SystemUI process** and keeps it resident there, and it **modifies the system display-orientation settings** (restored on a normal exit, self-healed on the next launch if the process was killed). Requiring root and LSPosed means **unlocking the bootloader**, which voids the official warranty and can break some banking / payment apps.

> 📌 Every claim here comes from **one** device (a Xiaomi foldable running HyperOS). On a different model, confirm the unfold orientation on the "Orientation" card.

### 3. When something goes wrong: exporting logs and filing an issue

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
