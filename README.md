# HyperPlus

**给小米 18 Fold 补上它缺的那几项实用功能。**

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-3ddc84)
![Language](https://img.shields.io/badge/Kotlin-2.4.20-7f52ff)
![Stage](https://img.shields.io/badge/stage-Alpha-orange)

[中文](#中文) · [English](#english)

---

## 中文

LSPosed 模块：给小米 18 Fold 加系统没有的功能。引擎常驻系统界面进程，App 只做配置。

### 运行环境

| 需要 | 具体 |
|---|---|
| 机型 | 小米折叠屏 |
| 系统 | HyperOS 4 |
| root | 任意（KernelSU / Magisk…） |
| LSPosed | 支持 libxposed API 101 及以上（1.8 及更早不行） |
| CPU | arm64-v8a |
| 摄像头 | 有前置 |
| 空间 | APK 约 41 MB ＋ 原生库约 8.6 MB |
| 其它 | 不需要 GMS、不需要联网下模型 |

作用域只勾 `com.android.systemui`，HyperPlus 自己不用勾。不用给任何权限。

> ⚠️ 会往系统界面进程注入代码并常驻，也会改系统的显示方向设置（正常退出还原，被强杀后下次启动自修）。要解锁 Bootloader，丢保修，部分银行 / 支付 App 可能受影响。
>
> 下面标「需要 root」的几项才用得上 root，不点它们就不用。

退后台、锁屏、划掉、清后台都不影响，重启自动恢复。

> 📌 只在小米 18 Fold（HyperOS 4）上测试过。小米 MIX Fold 系列理论上可用，未实测。换机型请在「方向」卡里核对展开方向。

### 功能

#### 旋转

三种转屏方式，随时切换：

| | 怎么转 |
|---|---|
| 人脸 | 看脸朝哪边，屏幕转到哪边。适合躺着、侧着用 |
| 半自动 | 转动手机后右下角弹按钮，点一下才转。不碰摄像头，最省电 |
| 跟随系统 | 完全交给系统自动旋转，模块不插手 |

| 还能 | 需要 root |
|---|---|
| 每个应用单独挑一种转屏方式（没挑的跟随全局） | |
| 暗光或看不到脸时，自动改成「点一下才转」 | |
| 指定不干预的应用（游戏默认在里面） | |
| 转屏按钮停留多久（1–60 秒） | |
| 控制中心快捷开关，单击循环三种方式 | |
| 每次展开内屏的默认朝向 | ⚠️ 需要 |

#### 分屏

| 功能 | 需要 root |
|---|---|
| 轻折一下机身，当前应用挤成半屏，再多出一格分屏 | |
| 分屏展开时当前应用在左半边还是右半边 | |
| 指定不被分屏的应用（游戏默认在里面） | |
| 分屏温度上限（默认 47°C）与过热保护开关 | |
| 最多同时摆 8 个应用（系统默认 6 个） | ⚠️ 需要 |

「轻折加分屏」默认关闭，去「实验功能」打开。上限改到 8 个后重启一次系统界面。

### 出问题了

**系统界面反复重启，甚至进不了桌面** —— 在 LSPosed 里关掉本模块，重启系统界面（或整机）。管理器进不去就等 LSPosed 自己禁用；系统起不来就进安全模式（开机按住音量减）关掉。

> 反复的原因：引擎在系统界面进程里，「出错就停用」只对当次进程有效，进程一重启就复位。本模块有熔断：同一故障连着重放 3 次（每次没活过 60 秒）就不再自动启动，在 App 里点「重新启用」恢复。

**导日志**：设置 → 监控与排查 → 诊断 → 导出诊断日志。没 root 也能导。

**提 issue**：https://github.com/213DEE/HyperPlus/issues ，附上那个 txt 和机型、系统版本、LSPosed 里勾没勾 `com.android.systemui`、复现步骤。

导不出来也可以手动取（要 root）：

```bash
adb logcat -b crash -d
adb shell su -c "logcat -d -s HyperPlusSplit FaceRotate HyperPlusModule"
adb shell ls -l /data/tombstones/
```

### License

[AGPL-3.0](LICENSE)

---

## English

LSPosed module: adds features the Xiaomi 18 Fold does not ship with. The engine lives in the SystemUI process; the app is configuration only.

### Requirements

| Requirement | Detail |
|---|---|
| Device | Xiaomi foldable |
| System | HyperOS 4 |
| root | any (KernelSU / Magisk…) |
| LSPosed | libxposed API 101 or newer (1.8 and older will not work) |
| CPU | arm64-v8a |
| Camera | front camera |
| Storage | ~41 MB APK + ~8.6 MB native libraries |
| Other | no Google Play Services, no model download |

Scope: tick `com.android.systemui` only. HyperPlus itself does not need to be ticked. No permissions to grant.

> ⚠️ Injects code into the SystemUI process and stays resident; also changes the system display-orientation settings (restored on a normal exit, self-healed on the next launch if killed). Requires an unlocked bootloader — voids the warranty and may break some banking / payment apps.
>
> Rows marked "Root: yes" below are the only ones that need root; skip them and root is not needed.

Backgrounding, locking, swiping it away or clearing it from Recents all make no difference; a reboot brings it back.

> 📌 Tested only on the Xiaomi 18 Fold (HyperOS 4). Xiaomi MIX Fold series untested but should work. On a different model, confirm the unfold orientation on the "Orientation" card.

### Features

#### Rotation

Three modes, switchable at any time:

| | How |
|---|---|
| Face | Turns the screen to wherever your face points. Good for lying down or on your side |
| Semi-auto | Turn the phone, then tap the button in the bottom-right. No camera, lowest battery use |
| Follow system | Left to the system's auto-rotate |

| Also | Root |
|---|---|
| Pick a rotation mode per app (unset apps follow the global one) | |
| Falls back to tap-to-rotate when it is dark or no face is visible | |
| Exempt apps (games are on the list by default) | |
| How long the rotate button stays on screen (1–60 s) | |
| Quick Settings tile — one tap cycles the three modes | |
| Default orientation each time the inner screen unfolds | ⚠️ yes |

#### Split screen

| Feature | Root |
|---|---|
| A light fold squeezes the current app into half the screen and adds one more pane | |
| Keep the current app on the left or right half | |
| Exempt apps (games are on the list by default) | |
| Temperature cap (47 °C by default) and the overheat guard | |
| Up to 8 apps at once (system default: 6) | ⚠️ yes |

"Fold to add a split" is off by default — turn it on under "Experimental". Reboot System UI once after raising the limit to 8.

### When something breaks

**SystemUI keeps restarting, sometimes the launcher never comes up** — disable this module in the LSPosed manager, then restart SystemUI (or the device). If the manager is unreachable, wait for LSPosed to disable the module by itself; if the system never comes back, boot into safe mode (hold Volume Down during boot) and disable it there.

> Why it repeats: the engine lives in the SystemUI process, and its "stop on error" switch only holds for the current process — a restart resets it. A boot breaker caps this: three replays in a row (each failing to survive 60 s) stop the engine from auto-starting. Tap "Re-enable" in the app to recover.

**Logs**: Settings → Monitoring & troubleshooting → Diagnostics → "Export diagnostic log". Works without root.

**Issues**: https://github.com/213DEE/HyperPlus/issues — attach that txt plus model, system version, whether `com.android.systemui` is ticked in LSPosed, and the steps.

Or collect it manually (root):

```bash
adb logcat -b crash -d
adb shell su -c "logcat -d -s HyperPlusSplit FaceRotate HyperPlusModule"
adb shell ls -l /data/tombstones/
```

### License

[AGPL-3.0](LICENSE)
