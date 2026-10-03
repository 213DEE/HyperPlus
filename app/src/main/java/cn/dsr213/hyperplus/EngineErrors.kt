package cn.dsr213.hyperplus

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 引擎的**结构化失败计数**（2026-10-03 新增）。
 *
 * ============================ 它要解决的问题 ============================
 * 引擎有一套刻意的纪律：**异常一律吞掉**（它跑在 SystemUI 进程里，
 * 未捕获异常 = 状态栏崩，见 `HyperPlusModule` 的纪律说明）。代价已经显现过一次：
 * 2026-10-03「默认方向不生效」那一轮，**根因查不出来** —— 因为失败路径只留一句
 * 普通日志，跟每秒几十条高频日志共用缓冲，十几分钟就被冲干净了。
 *
 * ⇒ 这里给**关键失败路径**各配一个计数器，随状态摘要上报（`EngineHost.summary` 的
 *   `errs=` 字段），于是"静默失败"变成**界面能读到的一格状态**：
 *   它**跟着心跳一起活着**，不会被日志缓冲冲掉。
 *
 * ⚠️ 三条纪律：
 *  ① **只数"失败"，不数"正常但慢"** —— 否则计数会常态化，用户就学会无视它了。
 *  ② 计数**只增不减**（本进程内），不做时间窗、不做滑窗：要看的是"这次开机以来
 *     有没有发生过、发生了多少次"，那是一个**事实**，不是一个速率。
 *  ③ 它**不改变任何行为** —— 加计数不许顺手把某个 catch 改成抛异常，也不许改重试次数。
 *
 * ⚠️ 键名是**跨进程契约**的一部分（App 侧 [cn.dsr213.hyperplus.ui.errsText] 要认它）：
 *   只允许追加新键，⛔ 不许改已有键的名字。认不出来的键在界面上**原样显示**，不猜。
 */
internal object EngineErrors {

    /** 相机绑不上（含重试仍失败）—— 最常见的真因是前摄被别的客户端占着 */
    const val CAMERA_BIND = "bind"

    /** 写 `USER_ROTATION` 失败 —— 方向控制链上最要命的一环 */
    const val ROTATION_WRITE = "rotw"

    /** 读 `USER_ROTATION` 失败 —— 读不到就没法判"要不要写" */
    const val ROTATION_READ = "rotr"

    /** 读前台应用失败 —— 门控会因此整个不生效（按原行为跑） */
    const val FOREGROUND = "fg"

    /** 上报顺序（固定的，让 `errs=` 这一格稳定可比；⛔ 别改成 map 的迭代顺序） */
    private val ORDER = listOf(CAMERA_BIND, ROTATION_WRITE, ROTATION_READ, FOREGROUND)

    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    /** 记一次失败。**热路径友好**：一次哈希查找 + 一次 CAS，不做任何日志。 */
    fun bump(key: String) {
        counters.getOrPut(key) { AtomicInteger() }.incrementAndGet()
    }

    fun count(key: String): Int = counters[key]?.get() ?: 0

    /**
     * 拼成一行上报值，形如 `bind:3,rotw:1`。
     *
     * ★ **一次都没发生过 ⇒ 空串**（不是 `bind:0,...`）。空串在界面上写"无"，
     *   比一长串 0 更容易一眼看出"这台机器没出过问题"。
     */
    fun snapshot(): String = buildString {
        for (k in ORDER) {
            val n = count(k)
            if (n <= 0) continue
            if (isNotEmpty()) append(',')
            append(k).append(':').append(n)
        }
    }
}
