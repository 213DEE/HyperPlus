# HyperPlus

**为小米 18 Fold 补齐系统缺失的实用功能。**

![License](https://img.shields.io/badge/license-AGPL--3.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-3ddc84)
![Language](https://img.shields.io/badge/Kotlin-2.4.20-7f52ff)
![Stage](https://img.shields.io/badge/stage-Alpha-orange)

[中文](#中文) · [English](#english)

---

## 中文

LSPosed 模块，为小米 18 Fold 提供系统未内置的功能。引擎常驻系统界面进程，App 仅作配置界面。

### 运行环境

| 需要 | 具体 |
|---|---|
| 机型 | 小米 18 Fold（其它小米折叠屏理论可用） |
| 系统 | HyperOS 4 |
| root | 任意（KernelSU / Magisk…） |
| LSPosed | 支持 libxposed API 101 及以上（1.8 及更早不行） |
| CPU | arm64-v8a |
| 摄像头 | 前置摄像头（人脸模式用它判方向） |
| 空间 | APK 约 41 MB ＋ 原生库约 8.6 MB |
| 其它 | 不需要 GMS、不需要联网下模型 |

作用域只勾 `com.android.systemui`，HyperPlus 自己不用勾。不用给任何权限。

> ⚠️ 会往系统界面进程注入代码并常驻，也会改系统的显示方向设置（正常退出还原，被强杀后下次启动自修）。要解锁 Bootloader，丢保修，部分银行 / 支付 App 可能受影响。
>
> 下面标「需要 root」的几项才用得上 root，不点它们就不用。

退至后台、锁屏、划掉、清理后台均不影响运行；重启后自动恢复。

> 📌 只在小米 18 Fold（HyperOS 4）上测试过。其它小米折叠屏理论可用，未实测。换机型请在「方向」卡里核对展开方向。

### 功能

#### 旋转

三种转屏方式，可随时切换：

| | 怎么转 |
|---|---|
| 人脸 | 按面部朝向自动转屏，适合躺卧或侧身使用 |
| 半自动 | 转动手机后在右下角弹出按钮，点击后转屏。不使用摄像头，功耗最低 |
| 跟随系统 | 完全交由系统自动旋转，模块不做干预 |

| 其他能力 | 需要 root |
|---|---|
| 为每个应用单独指定转屏方式（未指定的跟随全局） | |
| 光线不足或检测不到人脸时，自动降级为「点一下才转」 | |
| 指定不干预的应用（游戏已在默认列表中） | |
| 转屏按钮的停留时长（1–60 秒） | |
| 控制中心快捷开关，点击循环切换三种方式 | |
| 每次展开内屏的默认朝向 | ⚠️ 需要 |

#### 分屏

| 功能 | 需要 root |
|---|---|
| 轻折机身，当前应用缩为半屏，并新增一格分屏 | |
| 分屏展开时当前应用位于左半屏或右半屏 | |
| 指定不分屏的应用（游戏已在默认列表中） | |
| 分屏温度上限（默认 47°C）与过热保护开关 | |
| 最多同时放置 8 个应用（系统默认为 6 个） | ⚠️ 需要 |

「轻折加分屏」默认关闭，可在「实验功能」中开启。上限调至 8 个后需重启一次系统界面。

### 故障排查

**系统界面反复重启，甚至无法进入桌面** —— 在 LSPosed 中禁用本模块，然后重启系统界面（或整机）。若管理器无法打开，等待 LSPosed 自动禁用；若系统无法启动，进入安全模式（开机时按住音量减）后禁用。

> 原因：引擎运行在系统界面进程中，「出错即停用」仅对当次进程有效，进程重启后即复位。本模块设有熔断：同一故障连续重放 3 次（每次存活不足 60 秒）后不再自动启动，可在 App 中点击「重新启用」恢复。

**导出日志**：设置 → 监控与排查 → 诊断 → 导出诊断日志。无需 root。

**提交 issue**：https://github.com/213DEE/HyperPlus/issues ，请附上该日志文件，以及机型、系统版本、LSPosed 中是否勾选 `com.android.systemui`、复现步骤。

若无法导出，可手动采集（需要 root）：

```bash
adb logcat -b crash -d
adb shell su -c "logcat -d -s HyperPlusSplit FaceRotate HyperPlusModule"
adb shell ls -l /data/tombstones/
```

### License

[AGPL-3.0](LICENSE)

---

## English

LSPosed module that adds features the Xiaomi 18 Fold does not ship with. The engine runs inside the SystemUI process; the app is a configuration UI only.

### Requirements

| Requirement | Detail |
|---|---|
| Device | Xiaomi 18 Fold (other Xiaomi foldables should work) |
| System | HyperOS 4 |
| root | any (KernelSU / Magisk…) |
| LSPosed | libxposed API 101 or newer (1.8 and older will not work) |
| CPU | arm64-v8a |
| Camera | Front camera (the Face mode uses it to detect direction) |
| Storage | ~41 MB APK + ~8.6 MB native libraries |
| Other | no Google Play Services, no model download |

Scope: tick `com.android.systemui` only. HyperPlus itself does not need to be ticked. No permissions to grant.

> ⚠️ Injects code into the SystemUI process and stays resident; also changes the system display-orientation settings (restored on a normal exit, self-healed on the next launch if killed). Requires an unlocked bootloader — voids the warranty and may break some banking / payment apps.
>
> Rows marked "Root: yes" below are the only ones that need root; skip them and root is not needed.

Backgrounding, locking, swiping it away or clearing it from Recents make no difference; a reboot restores it.

> 📌 Tested only on the Xiaomi 18 Fold (HyperOS 4). Other Xiaomi foldables should work but are untested. On a different model, confirm the unfold orientation on the "Orientation" card.

### Features

#### Rotation

Three modes, switchable at any time:

| | How |
|---|---|
| Face | Rotates the screen to follow your face direction. Suited to lying down or lying on your side |
| Semi-auto | Rotate the phone, then tap the button in the bottom-right. No camera use, lowest power draw |
| Follow system | Handled entirely by the system auto-rotate |

| Other | Root |
|---|---|
| Set a rotation mode per app (apps without one follow the global mode) | |
| Falls back to tap-to-rotate when it is dark or no face is visible | |
| Exempt apps (games are listed by default) | |
| How long the rotate button stays on screen (1–60 s) | |
| Quick Settings tile — one tap cycles the three modes | |
| Default orientation each time the inner screen unfolds | ⚠️ yes |

#### Split screen

| Feature | Root |
|---|---|
| A light fold shrinks the current app to half the screen and adds one more pane | |
| Keep the current app on the left or right half | |
| Exempt apps (games are listed by default) | |
| Temperature cap (47 °C by default) and the overheat guard | |
| Up to 8 apps at once (system default: 6) | ⚠️ yes |

"Fold to add a split" is off by default — turn it on under "Experimental". Reboot System UI once after raising the limit to 8.

### Troubleshooting

**SystemUI keeps restarting, sometimes the launcher never comes up** — disable this module in the LSPosed manager, then restart SystemUI (or the device). If the manager is unreachable, wait for LSPosed to disable the module automatically; if the system never comes back, boot into safe mode (hold Volume Down during boot) and disable it there.

> Why it repeats: the engine lives in the SystemUI process, and its "stop on error" switch only holds for the current process — a restart resets it. A boot breaker caps this: three replays in a row (each failing to survive 60 s) stop the engine from auto-starting. Tap "Re-enable" in the app to recover.

**Logs**: Settings → Monitoring & troubleshooting → Diagnostics → "Export diagnostic log". No root needed.

**Issues**: https://github.com/213DEE/HyperPlus/issues — attach that txt plus model, system version, whether `com.android.systemui` is ticked in LSPosed, and the steps.

Or collect it manually (root):

```bash
adb logcat -b crash -d
adb shell su -c "logcat -d -s HyperPlusSplit FaceRotate HyperPlusModule"
adb shell ls -l /data/tombstones/
```

### License

[AGPL-3.0](LICENSE)
