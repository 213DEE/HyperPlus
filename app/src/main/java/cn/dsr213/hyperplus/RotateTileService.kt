package cn.dsr213.hyperplus

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 控制中心快捷开关（两态）。
 *
 * ★ 为什么是两态而不是三态：
 *   官方 QS Tile 的 `state` 只有 ACTIVE / INACTIVE / UNAVAILABLE 三种**展示**状态，
 *   没有原生三态循环。用户已确认只做两态：
 *     - 自动旋转（系统）→ Tile INACTIVE
 *     - 自适应旋转（人脸）→ Tile ACTIVE
 *   于是单击就是干净的互切，不需要绕路做"点两下循环"或"弹面板"。
 *
 * ⚠️ 一个必须知道的耦合：
 *   "自动旋转（系统）"这一态与系统自带那个旋转快捷开关**操作的是同一份设置**，
 *   所以控制中心会有两个旋转开关，改一个另一个的图标也会跟着变。
 *   这是绕不开的——除非将来做成真模块直接替换系统那个 Tile。
 *
 * 引擎侧通过 AppPrefs 的 StateFlow 订阅模式变化，所以这里改了模式，
 * 正在运行的引擎会立刻跟随启停，不需要跨进程通信。
 */
class RotateTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
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
        val mode = AppPrefs.mode.value
        tile.state = if (mode == RotateMode.ADAPTIVE) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        // subtitle 需要 API 29+，本工程 minSdk 30 满足
        tile.subtitle = mode.label
        tile.icon = Icon.createWithResource(this, R.drawable.ic_rotate_adaptive)
        tile.updateTile()
    }
}
