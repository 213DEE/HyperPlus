package cn.dsr213.hyperplus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 引擎反向要配置时的接单者（2026-10-03 新增，广播通道的**反向**那条腿）。
 *
 * ## 它接什么
 * 引擎（跑在 SystemUI 里）启动时，如果 `Settings.System` 里那份配置镜像还不存在
 * （新装机 / 刚升级上来 / 用户清过数据），就会发一条 [ConfigChannel.ACTION_REQUEST]
 * 过来。这个接收者收到后**把 App 进程叫起来、让它把整份配置推一次**。
 *
 * ★ 为什么用**清单接收者**而不是动态注册：
 *   动态注册的那个只有 App 进程活着才在，而"需要它"的场景恰恰是**App 没在跑**的时候。
 *   清单接收者能被显式广播直接拉起进程（发送端用 `setPackage` 定向，
 *   也就不受 Android 8+ 对**隐式**广播的限制）。
 *
 * ⚠️ 已知边界：应用被系统置为「已停止」（刚装上、从没打开过）时收不到广播。
 *   但那种情况下 App 的 prefs 本来也是空的、没有东西可推 ⇒ 不影响正确性，
 *   用户第一次打开 App 时 [AppPrefs.init] 会自己推一次。
 */
class ConfigRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ConfigChannel.ACTION_REQUEST) return
        val app = context?.applicationContext ?: return

        // ⚠️ 必须 `goAsync()`：`onReceive` 跑在**主线程**，而下面这套要读 prefs 文件
        //   （`AppPrefs.init` 里的迁移逻辑会碰磁盘）。在主线程做 I/O 会被 ANR 计时器盯上，
        //   而这是 SystemUI 那边等着的链路 —— 卡住它等于配置一直拖着不生效。
        val pending = goAsync()
        Thread({
            try {
                runCatching {
                    AppPrefs.init(app)
                    AppPrefs.pushConfigNow()
                }.onFailure { Log.w(TAG, "处理「请推配置」请求失败（已吞掉，不影响 App）", it) }
            } finally {
                runCatching { pending.finish() }
            }
        }, "hyperplus-cfgpush").apply { isDaemon = true }.start()
    }

    private companion object {
        /**
         * ⚠️ 与 [ConfigChannel] 用同一个 TAG：排查"配置通道"时一条 `grep` 就能看到全链路，
         *   而这个类本身太薄，不值得单开一个 TAG 让人多记一个名字。
         */
        const val TAG = "HyperPlusChannel"
    }
}
