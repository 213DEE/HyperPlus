package cn.dsr213.hyperplus

import android.content.ContentResolver
import android.os.SystemClock

/**
 * 「实时角度」读数 —— App 侧读宿主写下的那一行（[PrefsBridge.LIVE_ANGLE]）。
 *
 * ============================ 它是什么（2026-10-01）============================
 * 用户原话：「**在方向校准里面加一个实时的角度显示，我告诉你正确方向**」。
 * 宿主（SystemUI 里的引擎）在预览期间每 [PREVIEW_TICK_MS] 把当前角度
 * 写进 `Settings.System`，这里负责把它解析成界面能画的东西。
 *
 * ★ **角度的 0° 基准 = 当前屏幕的默认方向**（即手机竖屏正对自己、人脸正立）：
 *   屏幕默认方向 ⇒ 0°；手机逆时针转 90° ⇒ 90°；倒过来 ⇒ 180°；顺时针转 90° ⇒ 270°。
 *   这正是用户要的「以屏幕默认方向为 0 度基准」—— 引擎侧的
 *   `OrientationDecider.angleOf()` 输出的就是这个量（已含标定的符号位与相位偏移）。
 *
 * ⚠️ 它是**只读**的：App 只读 `Settings`（零门槛），永远不写 —— 写那个键需要特权，
 *   只有宿主（SystemUI，特权包）写得动。理由见 [PrefsBridge] 的类注释。
 *
 * ⚠️ 解析不出来就返回 null（**不猜**）：格式不认识时宁可让界面显示"读不到"，
 *   也不要拿一个 0° 去冒充读数 —— 那会让人以为"手机正好是正立的"。
 */
internal object LiveAngle {

    /**
     * 一次读数。
     *
     * @param normDeg 归一化角（0..360，**0 = 屏幕默认方向**）；`NaN` = 此刻没识别到人脸
     * @param rawRoll 原始 roll（度，未做符号/偏移处理）—— 留给"标定前后对照"用
     * @param sector  宿主当前判定的方向（0..3；-1 = 还没有结论）
     * @param display 系统当前实际显示方向（0..3）
     * @param form    宿主报的形态（`INNER` / `OUTER`）
     * @param ageMs   这条读数距今多少毫秒（同一条 `elapsedRealtime` 时钟算出来的）
     */
    data class Sample(
        val normDeg: Float,
        val rawRoll: Float,
        val sector: Int,
        val display: Int,
        val form: String,
        val ageMs: Long,
    ) {
        /** 读数是不是"活着"的。宿主每 250ms 写一次，超过 1.5 秒没更新就不该再当真 */
        val fresh: Boolean get() = ageMs <= STALE_MS

        /**
         * 有没有识别到人脸。
         *
         * ★ 判据是 [rawRoll]（`eulerZ`，宿主**没脸时会清空**的那一格），不是 [normDeg]。
         *   两者在 [read] 里已经绑成一致（没脸 ⇒ 两个都是 NaN），这里只是把
         *   「谁才是权威」写在明面上，防止将来有人只看归一化角又把它改回去。
         */
        val hasFace: Boolean get() = rawRoll.isFinite()
    }

    /**
     * 超过这么久没更新 ⇒ 判定读数已停。
     *
     * ★ 取 1500ms = 上报周期（250ms）的 6 倍：容得下 SystemUI 的调度抖动与
     *   相机偶尔的丢帧，又短到用户"转过手机它还不动"时能马上在界面上看出来。
     */
    const val STALE_MS = 1_500L

    /**
     * 读一眼。拿不到 / 格式不认识 / 时间戳坏了都返回 null。
     */
    fun read(cr: ContentResolver): Sample? {
        val raw = PrefsBridge.readString(cr, PrefsBridge.LIVE_ANGLE) ?: return null
        val p = raw.split('|')
        // 契约是 6 段（见 PrefsBridge.LIVE_ANGLE）。段数不对说明是别的版本写的 —— 不猜。
        if (p.size < 6) return null
        val ts = p[5].toLongOrNull() ?: return null
        // ★★ 2026-10-02：「有没有脸」一律以 `rawRoll`（第 2 段）为准。
        //   为什么不能看 `normDeg`（第 1 段）：**没脸时它不是空的，而是留着上一次的值** ——
        //   实测（2026-10-01 23:39:30 起连续 130+ 秒）`norm=0.6` 而人脸字段早已为空。
        //   拿它当判据的后果是界面一直显示一个**假角度**，用户照着它做的判断全是错的。
        //   引擎侧已同时修（见 `OrientationDecider.update` 的 no-face 分支），这里是第二道：
        //   ⚠️ **读的时候不假设对面已经修好** —— 两边都硬一遍，任一边单独失效都不至于骗人。
        val rawRoll = p[1].toFloatOrNull() ?: Float.NaN
        val hasFace = rawRoll.isFinite()
        return Sample(
            // ⚠️ 空串是**合法**的（= 没脸）⇒ 不能用 toFloatOrNull() 的 null 当失败，
            //   那样"没脸"与"格式坏了"就分不开了。
            // 没脸 ⇒ 角度字段一并作废（**不返回陈旧值**，也不返回 0 —— 0 是合法角度）
            normDeg = if (hasFace) (p[0].toFloatOrNull() ?: Float.NaN) else Float.NaN,
            rawRoll = rawRoll,
            sector = p[2].toIntOrNull() ?: -1,
            display = p[3].toIntOrNull() ?: -1,
            form = p[4],
            // 时钟可能因为"宿主刚重启而本进程还留着旧值"出现负差，夹到 0（不显示负数）
            ageMs = (SystemClock.elapsedRealtime() - ts).coerceAtLeast(0L),
        )
    }
}
