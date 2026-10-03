package cn.dsr213.hyperplus

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 控制中心快捷开关。
 *
 * ★ 展示仍是**两态**，但循环是**三态**（2026-09-28 加半自动模式）：
 *   官方 QS Tile 的 `state` 只有 ACTIVE / INACTIVE / UNAVAILABLE 三种**展示**状态，
 *   没有原生三态控件。所以做法是：
 *     - 展示：只要本引擎**接管了方向盘**（= 自适应 或 半自动）就 ACTIVE，否则 INACTIVE；
 *     - 循环：单击在「跟随系统 → 自适应 → 半自动 → 跟随系统」之间转一圈
 *       （见 `AppPrefs.toggleMode`）；
 *     - 具体现在是哪一档，由 `tile.subtitle` 如实写出来（`mode.shortLabelRes`）。
 *
 *   ★ 为什么不把三档硬塞进 state：`state` 的两种可用值表达不了三种含义，
 *     硬塞的结果是"用户点开控制中心也不知道自己在哪一档"。subtitle 才是诚实的载体。
 *
 * ★ 2026-09-28 起模式**按内外屏解耦**，于是这个开关也要说清"改的是哪一块屏"：
 *   - 循环改的是**当前这块屏**的模式（`AppPrefs.toggleMode` 用当前形态取值）；
 *   - subtitle 带上形态名 —— 否则用户在控制中心点一下，看到档位变了，
 *     却不知道变的是内屏还是外屏那一档（两块屏各有一份，很容易搞混）。
 *
 * ⚠️ 一个必须知道的耦合：
 *   「跟随系统」这一档与系统自带那个旋转快捷开关**操作的是同一份设置**，
 *   所以控制中心会有两个旋转开关，改一个另一个的图标也会跟着变。
 *   这是绕不开的——除非将来做成真模块直接替换系统那个 Tile。
 *
 * 引擎侧通过**配置文件**跟随模式变化（App 写自己的 prefs，SystemUI 里的引擎用
 * LSPosed 的 XSharedPreferences 通道监听文件变更），所以这里改了模式，
 * 正在运行的引擎会立刻跟随启停，不需要 App 主动通知 —— App 侧压根没有引擎实例。
 */
class RotateTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // `init` 内部会顺带量一次当前形态（内屏 / 外屏）—— 模式是按形态取的，
        // 不知道自己在哪块屏就会改错一份配置。见 AppPrefs.init / syncScreenForm。
        AppPrefs.init(this)
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        AppPrefs.init(this)
        AppPrefs.toggleMode()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val mode = AppPrefs.mode.value          // = 当前形态的生效档
        val form = AppPrefs.screenForm.value
        // ACTIVE = 我们在管方向盘（自适应或半自动），INACTIVE = 完全交给系统
        tile.state = if (mode.engages) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        // subtitle 需要 API 29+，本工程 minSdk 30 满足
        // ⚠️ 用 shortLabelRes：subtitle 是一行小字，"自适应旋转（人脸）"会被系统截断，
        //    而截断处正好是括号里最关键的信息。
        // ⚠️ 必须走 **Res（界面口径）**，⛔ 不能用 `form.label` / `mode.shortLabel`
        //    —— 那两个是**日志口径**的中文常量，界面语言选英文时会在这里露出中文。
        tile.subtitle = "${getString(form.labelRes)} · ${getString(mode.shortLabelRes)}"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_rotate_adaptive)
        tile.updateTile()
    }
}
