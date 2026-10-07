package cn.dsr213.hyperplus

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * ★★★ **全工程唯一的 Toast 出口** —— 所有"给用户弹一句话"都必须走这里。
 *
 * ⛔ **别在别处直接写 `Toast.makeText(...).show()`** —— 那样会绕过下面三道保障，
 *   而其中任何一道都踩过坑（见各自的说明）。
 *
 * ============================ 为什么要有这个封装（不是"多此一举"）============================
 * 直接调系统 API 只有一行，但要让它**在任何进程、任何线程都不出事**，需要三样东西：
 *
 * **① 进程差异：App 进程弹不出、SystemUI 进程弹得出（2026-10-05 实测）**
 *   - `App` 进程（`cn.dsr213.hyperplus`，普通 uid）：Android 10+ 起**后台应用不能弹 Toast**，
 *     系统直接静默丢弃 —— logcat 里只有一句
 *     `NotificationService: Suppressing toast from package cn.dsr213.hyperplus by user request.`，
 *     **不崩、不报错、什么都没发生**。
 *     ⇒ App 进程的 Toast **只在界面活着时**才有效（用户正看着本应用）——
 *       这与"用户在分屏的两个 App 里折手机时也要看得见提示"是**两个场景**。
 *   - `引擎`（跑在 `com.android.systemui`，uid 10193 系统进程）：**不受那条限制**。
 *     实测 `Toast.makeText(host, …).show()` 正常渲染，系统侧有完整证据链：
 *     `WindowManager: setClientSurface … VRI-Toast#…` →
 *     `VRI[Toast]: vri.reportDrawFinished … Rect(...)` →
 *     `wms.showSurfaceRobustly mWin:Window{… Toast}` →
 *     `SurfaceFlinger: layername:VRI-Toast#… regionblurRadius:250`（HyperOS 的毛玻璃 Toast）。
 *     **⛔ 没有任何 `Suppressing` 记录。**
 *   ⇒ **结论：本封装对两个进程都可用，但只有宿主是系统进程时才指望得上"App 不在前台也能弹出"。**
 *
 * **② 线程：`show()` 必须在有 Looper 的线程**
 *   调用点经常在回调里（`ContentObserver.onChange`、广播 `onReceive`、折叠状态回调），
 *   那些地方**可能是非主线程**；直接 `show()` 会抛
 *   `RuntimeException: Can't create handler inside thread that has not called Looper.prepare()`。
 *   ⇒ 这里统一 `post` 到主线程，调用方**不用关心自己在哪个线程**。
 *
 * **③ 去重：连点/连折不该刷屏**
 *   用户连着折好几下、或某个状态反复触发，会在几百毫秒内连来多条 ⇒ 屏幕上一条叠一条，
 *   而"提示变噪音"的代价是**下一次真有问题时他也不看了**。
 *   ⇒ 同一句话 [DEDUP_MS] 内只弹第一条（按**文案**去重，不同文案互不影响）。
 *
 * ============================ 失败纪律 ============================
 * ⚠️ **一律吞掉并如实记日志**：Toast 弹不出来是"要排查的事实"，但它
 *   **绝不该影响任何主链**（分屏动作、界面渲染）⇒ ⛔ 不许把异常抛给调用方。
 *   ★ 也别写成"静默吞" —— 日志里必须留下痕迹，否则线上出问题时无从下手。
 *
 * ⚠️ **Toast 的时长与位置由系统决定**（`LENGTH_SHORT` ≈ 2s / `LENGTH_LONG` ≈ 3.5s，
 *   位置在**屏幕下方**、且**不受本应用控制**）—— 这正是用户要的「完全对齐系统 Toast 的位置」，
 *   ⛔ 别为了"想调位置/调样式"又回去自绘一个浮层（本工程曾有一个 `SplitLimitOverlay`，
 *      2026-10-05 按用户点名删掉 —— 那条路已经走过一次了）。
 *
 * 用法：
 * ```
 * AppToast.show(ctx, "已经到上限了")       // 短提示（≈2s，默认）
 * AppToast.showLong(ctx, "……说明……")      // 长提示（≈3.5s，只在需要读两遍时用）
 * ```
 */
object AppToast {

    private const val TAG = "HyperPlusToast"

    /**
     * 同一句话的静默期 —— 见于类注释 ③。
     *
     * ★ 取 2s 与 `Toast.LENGTH_SHORT` 的实际显示时长**对齐**：短提示自己会消失，
     *   在它还没消失之前再弹同一句就是纯粹的叠加（用户看到的是"闪了一下"而不是两条）。
     * ⚠️ 只按**文案**判重，不按"调用点"判 —— 两个不同功能碰巧说同一句话也该合并。
     */
    private const val DEDUP_MS = 2_000L

    private val main = Handler(Looper.getMainLooper())

    /** 上次弹某句话的时刻（文案 → `uptimeMillis`）。⚠️ 只会在主线程被读写。 */
    private val lastShown = HashMap<String, Long>()

    /**
     * 弹一句短提示（≈2 秒）。
     *
     * @param ctx 任意 Context（Activity / Application / **SystemUI 的系统 context** 都行）。
     *        ⚠️ 传 Activity 时 Toast 会**随 Activity 一起被销毁时取消**吗？—— 不会，
     *        Toast 由系统 `NotificationManagerService` 持有，与 ctx 生命周期无关，
     *        这里只用它取 `packageName` / 资源。但**别传一个已经死掉的 Activity** 即可。
     * @param msg 要显示的话（**已本地化**的成品字符串，本函数不做任何 i18n）。
     */
    fun show(ctx: Context, msg: String) = showImpl(ctx, msg, Toast.LENGTH_SHORT)

    /**
     * 弹一句长提示（≈3.5 秒）。
     *
     * ⚠️ **别滥用**：长提示挡屏幕更久。只有当这句话用户**需要读两遍**（比如带条件的说明）才用，
     *   否则一律用 [show]。
     */
    fun showLong(ctx: Context, msg: String) = showImpl(ctx, msg, Toast.LENGTH_LONG)

    /**
     * 真正干活的地方 —— 三道保障都在这里。
     *
     * ★ 抽出来是为了让两个公开入口**共用同一份去重表和同一个 handler**
     *   （各写一份的话，短/长提示会各去各的重，等于没有去重）。
     */
    private fun showImpl(ctx: Context, msg: String, duration: Int) {
        if (msg.isEmpty()) return
        runCatching {
            // ② 线程：已经在主线程就直接弹（少一次消息往返），否则 post。
            if (Looper.myLooper() == Looper.getMainLooper()) {
                showNow(ctx, msg, duration)
            } else {
                main.post { runCatching { showNow(ctx, msg, duration) } }
            }
        }.onFailure { Log.w(TAG, "弹 Toast 失败（已吞掉）：$msg", it) }
    }

    /** ⚠️ **只允许从主线程调用**（去重表不是线程安全的，[showImpl] 已经保证了这点）。 */
    private fun showNow(ctx: Context, msg: String, duration: Int) {
        // ③ 去重：同一句话在静默期内只放行第一条。
        val now = android.os.SystemClock.uptimeMillis()
        val last = lastShown[msg]
        if (last != null && now - last < DEDUP_MS) return
        lastShown[msg] = now
        // 表很小（就几条固定文案），但长期运行也要防"文案是动态拼出来的"那种调用点撑大内存。
        if (lastShown.size > 64) lastShown.entries.removeAll { now - it.value > DEDUP_MS * 10 }

        Toast.makeText(ctx, msg, duration).show()
    }
}
