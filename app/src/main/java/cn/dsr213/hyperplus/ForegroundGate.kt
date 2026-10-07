package cn.dsr213.hyperplus

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * 前台门 —— **「当前前台应用是不是在白名单里」**，是则不控制它（停手）。
 *
 * ============================ 判据换过一次（2026-09-28，用户拍板） ============================
 *
 * 改造前判据是**自动推**的：读前台 Activity 声明的 `screenOrientation`，命中"自管朝向"
 * 的取值就停手。它连着出了两次真错，最终用户决定**把判据本身换掉**：
 *
 * > 「把（原来的）判断删了、改成应用白名单，白名单应用不受 app 控制」
 *
 * | 老判据踩的坑 | 现象 |
 * |---|---|
 * | `BEHIND`(3) 被当成"锁死方向"（它其实是"沿用栈下层"） | 抖音里**功能整个消失**，屏幕还被交还给系统自转 |
 * | 探针读的是 manifest 里的**静态声明值**，看不见 ATMS 的运行时解析 | 判据与真实行为长期不一致，且**无从自查** |
 *
 * ★ 新判据的根本好处：**它不需要任何推断**。名单是用户点的，命中与否是一次集合查找 ——
 *   不存在"猜错某个 ROM 的语义"、"静态值 vs 运行时值"这类只能靠踩坑才发现的问题。
 *   代价是"默认什么都不豁免"，由 [AppWhitelist.DEFAULT_PACKAGES] 把默认值补上。
 *
 * ============================ 为什么必须停手（不是优化，是修 bug） ============================
 * 用户报过「打游戏经常断触」。代码级排查结论：**没有任何一处代码直接碰触控**
 * （零 hook、零输入注入；并且**从来没有过** `InputManager` / `injectInputEvent`）。
 * 但引擎在游戏期间**持续**做一件事 —— 每轮触发开前摄 + 跑 8 帧 ML Kit 推理
 * （2026-09-25 真机实测最密 4 轮/4 秒 ≈ **32 次神经网络推理/秒**），跟游戏抢 CPU/GPU。
 * 厂商在高负载下普遍降触控采样，观感就是"断触"。停手 = 停相机 = 停推理。
 *
 * ⚠️ **2026-10-01 更正**：上面那句原来写的是"零输入／窗口／悬浮层 API"，**已不成立** ——
 *   07:48 半自动模式引入了本工程唯一的窗口（`RotateHintOverlay`）。所以本类停手挡的
 *   现在是**三样**：① 相机 + 推理；② 半自动那个会吃触摸的窗口；③ 写方向带来的显示重配。
 *   完整清单与证据见 `docs/触控影响审查_2026-10-01.md`。
 *   ⛔ 别再把"本工程不碰窗口"当成事实引用 —— 它在半自动路径上是错的。
 *
 * ============================ 文件分工 ============================
 * - [ForegroundGate]：**纯逻辑**（不引任何 Android 类）⇒ JVM 单测零依赖直跑。
 * - [ForegroundProbe]：真机读 top activity 的包名（要 Context）。
 */
internal object ForegroundGate {

    /**
     * `ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED`。
     *
     * ⚠️ **它不再参与任何决策** —— 只作为 [ForegroundProbe] 读不到值时的占位。
     *   留着 `label()` 是为了让诊断日志还能打出"这个应用自己声明了什么朝向"，
     *   那对**排查**很有用（"为什么这个应用不转"第一件要看的就是它），
     *   但它**不参与判断** —— 这一点是那次换判据的关键，别再用它写 `if`。
     */
    const val UNSPECIFIED = -1

    /** 判定结果 */
    enum class Decision {
        /** 前台应用不在白名单里 → 正常干活（开相机 / 弹按钮 / 写方向） */
        MANAGEABLE,

        /** 前台应用在白名单里 → 停手（不开相机、不写方向，并按开关交还系统自动旋转） */
        YIELD,

        /**
         * 读不到前台应用（权限被收 / API 不可用）→ **不下结论**。
         *
         * ★ 这条兜底是刻意的：宁可退回"改造前的行为"，也不允许因为读不到就
         *   **永久误停**。判错方向的代价是**不对称**的 ——
         *   多干活的代价是一点电量，误停的代价是"功能整个消失"。
         */
        UNKNOWN,
    }

    /**
     * **为什么停手** —— 行为完全相同（都不采帧 / 不弹按钮 / 不写方向），
     * 但**日志与排查必须能分辨是谁让它停的**。
     *
     * | 原因 | 性质 | 用户能不能改 |
     * |---|---|---|
     * | [WHITELIST] | **用户偏好**（"这个应用不受我控制"） | 能 —— 界面上的开关就是它 |
     *
     * ⛔ 曾经删掉过三个取值，别再补回来：
     *   - 09-29 上午的 `OUTER_DESKTOP` / `OUTER_DECLARED` —— 当天下午随**外屏旋转增强整体
     *     删除**一起消失（外屏根本不介入，自然没有"外屏上的豁免"）；
     *   - 10-05 的 `UNCONTROLLABLE` —— 它是**实测事实**（写过方向、屏幕真的没动），
     *     按用户点名改成「转不动就弹一次提示」、**不再停手**，于是它不再是停手原因。
     *     完整复盘见 `AdaptiveEngine.scheduleSemiReadback` 的 KDoc。
     *
     * ⚠️ 保留这个"只有一个取值"的枚举，是为了让**停手原因**有一个统一的表达 ——
     *   日志、[cn.dsr213.hyperplus.ModuleLink.State.foregroundStop]、界面都指着它。
     *   ⛔ 别因为"现在只有一个值"就把它整个删掉：那会把"原因"这个概念从契约里抹掉，
     *     将来真要加第二种原因时，又得从头把这些调用点改一遍。
     */
    enum class StopReason { WHITELIST }

    /**
     * 判一次。
     *
     * ★ 判据只有一个输入：**要不要停手**（三条豁免的合成在调用方做，见
     *   `AdaptiveEngine.refreshForegroundGate`）—— 本函数刻意保持"单输入纯逻辑"，
     *   这样它的正确性可以在 JVM 上穷举，不需要任何 Android 环境。
     *
     * ⚠️ **刻意不收 `orientation`**：老签名是 `decide(orientation, engineWrites)`，
     *   两个参数现在都不需要了 —— 前者是那次换掉的判据本体，后者是为它服务的分档
     *   （"自适应严格 / 半自动宽松"）。判据**对两种模式一视同仁**：
     *   不停手就干活，停手就一模一样地停。
     *   少一个参数就少一种"两档行为不一致"的可能。
     *
     * @param pkg 前台包名；null / 空 = 读不到 ⇒ [Decision.UNKNOWN]
     * @param yield 该包是否命中两条豁免之一（调用方先算，本函数保持纯逻辑）
     */
    fun decide(pkg: String?, yield: Boolean): Decision = when {
        pkg.isNullOrEmpty() -> Decision.UNKNOWN
        yield -> Decision.YIELD
        else -> Decision.MANAGEABLE
    }

    /**
     * 把声明值翻成可读名（**日志 / 界面用，不参与判断**）。
     * 取值对照 `android.content.pm.ActivityInfo` 的 `SCREEN_ORIENTATION_*`。
     */
    fun label(o: Int): String = when (o) {
        UNSPECIFIED -> "UNSPECIFIED"
        0 -> "LANDSCAPE"
        1 -> "PORTRAIT"
        2 -> "USER"
        3 -> "BEHIND"
        4 -> "SENSOR"
        5 -> "NOSENSOR"
        6 -> "SENSOR_LANDSCAPE"
        7 -> "SENSOR_PORTRAIT"
        8 -> "REVERSE_LANDSCAPE"
        9 -> "REVERSE_PORTRAIT"
        10 -> "FULL_SENSOR"
        11 -> "USER_LANDSCAPE"
        12 -> "USER_PORTRAIT"
        13 -> "FULL_USER"
        14 -> "LOCKED"
        else -> "?#$o"
    }
}

/**
 * 真机侧的读数器：拿到「前台 activity 的包名（以及它声明的朝向，仅作诊断）」。
 *
 * ★ 判据换了之后，**包名成了唯一真正被用到的返回值**；[Info.orientation] 只进日志。
 *   保留它是因为"这个应用自己声明了什么朝向"正是排查"为什么它不转"的第一手线索。
 *
 * ★ 它为什么能在宿主进程里工作：引擎跑在 **SystemUI** 里，而 SystemUI 持有
 *   `android.permission.REAL_GET_TASKS`（本机实测 `granted=true`）与
 *   `MANAGE_ACTIVITY_TASKS` ⇒ 能读别人的任务栈。**普通 App 读不到**，
 *   所以这段只在宿主模式（`hosted`）下有实际意义 —— 读不到就会走
 *   [ForegroundGate.Decision.UNKNOWN]。
 *
 * ⛔ **刻意不 hook `WMS` / `ATMS`**：模块目前零 hook，为了读一个包名去 hook
 *   框架会把稳定性风险抬上去（用户已报过模块相关的异常）。public API 够用。
 */
internal class ForegroundProbe(private val context: Context) {

    /** 读到的前台信息 */
    data class Info(val pkg: String, val component: String, val orientation: Int)

    private var cacheComponent: String? = null
    private var cacheOrientation = ForegroundGate.UNSPECIFIED

    /**
     * 读一次。返回 null = **读不到**（调用方必须按 UNKNOWN 处理，不得据此停手）。
     *
     * ⚠️ 内部是两次 binder IPC（`getRunningTasks` + `getActivityInfo`），几十微秒量级，
     *   可以直接在调用线程上跑；但**别放进每帧的高频路径**（只在触发与低频巡检时调）。
     *   `getActivityInfo` 的结果按组件名缓存 —— 声明值是静态的，同一个组件不必重复问。
     */
    fun read(): Info? {
        val cn = topActivity() ?: return null
        val key = runCatching { cn.flattenToShortString() }.getOrNull() ?: return null
        if (key == cacheComponent) {
            return Info(cn.packageName, key, cacheOrientation)
        }
        val orient = runCatching { context.packageManager.getActivityInfo(cn, 0).screenOrientation }
            .getOrNull() ?: return null
        cacheComponent = key
        cacheOrientation = orient
        return Info(cn.packageName, key, orient)
    }

    /** 缓存过多少条（诊断用；本类只缓存最近一条） */
    fun cacheSize(): Int = if (cacheComponent == null) 0 else 1

    private fun topActivity(): ComponentName? {
        // —— 路径①：public API（已弃用但仍可用）——
        //    SystemUI 有 REAL_GET_TASKS，实测 granted=true；普通 App 只会拿到自己的任务。
        runCatching {
            @Suppress("DEPRECATION")
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            am.getRunningTasks(1)?.firstOrNull()?.topActivity?.let { return it }
        }.onFailure { Log.w(TAG, "getRunningTasks 不可用，转 hidden API", it) }

        // —— 路径②：hidden API 兜底 ——
        //    宿主是平台应用，有 hidden API 豁免；参数顺序 (maxNum, filterOnlyVisibleRecents)。
        runCatching {
            val svc = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null) ?: return null
            val tasks = svc.javaClass
                .getMethod(
                    "getTasks",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
                .invoke(svc, 1, false) as? List<*> ?: return null
            val info = tasks.firstOrNull() ?: return null
            // RunningTaskInfo.topActivity 是 public 字段
            return info.javaClass.getField("topActivity").get(info) as? ComponentName
        }.onFailure { Log.w(TAG, "ActivityTaskManager.getTasks 也不可用（前台门将按 UNKNOWN 处理）", it) }

        return null
    }

    private companion object {
        const val TAG = "HyperPlusFgGate"
    }
}
