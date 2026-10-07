package cn.dsr213.hyperplus

/**
 * 「**提高分屏上限**」这道实验开关的**判定核** —— 纯函数，零 Android / 零 LSPosed 依赖。
 *
 * ============================ 为什么单独一个文件 ============================
 * 真正挂钩子的那部分在 `module/SplitStageLimit`，而它**引用了 libxposed**
 * （`compileOnly("io.github.libxposed:api:102.0.0")` ⇒ **不在单测的类路径上**，
 * 见 `app/build.gradle.kts`）。判定核若和那些 `XposedInterface` 待在同一个类里，
 * 这个类在 JVM 单测里就**加载不起来** ⇒ 于是「**开关关着时不许碰系统**」这条最重要的
 * 不变式，就只能靠真机碰运气。
 *
 * ⇒ 把「要不要抬、抬到几」抽到这里（与 `AdaptiveFallback` / `FoldJudge` / `SplitThresholds`
 *   同一个套路：**判定核纯函数化**），判定就能在单测里钉死；
 * `SplitStageLimit` 那边只负责「把钩子挂上去」与「把值读进来」。
 *
 * ⚠️ 本文件里**不许**出现 `android.*` / `io.github.libxposed.*` 的任何 import
 *   （含间接引用）—— 否则上面这条好处立刻失效，而且是**静默失效**
 *   （测试类加载不起来时报的是 NoClassDefFoundError，跟逻辑对错毫无关系）。
 */
internal object SplitStageGate {

    /**
     * 本机系统结构能接受的**硬上限 = 8**。
     *
     * ✅ 依据（反汇编逐字）：官方 `MultipleSplitStageOrderOperator.<init>` 里
     *   `stageIds = List.of(0, 1, 2, 3, 4, 5, 6, 7)` —— 只预置了 8 个 id，
     *   再往上 `stageIds.get(8)` 直接越界。
     *   ⇒ 这不是我们挑的数，是**结构决定的**，所以它是个常量而不是配置项。
     */
    const val MAX_SUPPORTED = 8

    /**
     * 决定这次要不要抬、抬到几 —— **属性优先，镜像兜底**。
     *
     * @param propRaw `persist.sys.hyperplus.multisplit`（[PrefsBridge.PROP_MULTISPLIT]）的
     *   **原始字符串**；键不存在时传 `null` 或 `""`。
     * @param mirror `Settings.System` 里那份 `hyperplus_config_mirror`
     *   （[PrefsBridge.MIRROR]）解出来的键值表；读不到时传 `null`。
     *   ⚠️ 调用方在 `propRaw` 已明确取值时**可以不读它**（传 `null`）—— 见下面的短路规则。
     * @return [MAX_SUPPORTED]（开关**明确**开着）／ `null`（**不干预**）。
     *
     * ============================ 两条通道的优先级（2026-10-06） ============================
     * | `propRaw` | 含义 | 结果 |
     * |---|---|---|
     * | `"1"` | 属性明确说「开」 | [MAX_SUPPORTED]，**短路，不看镜像** |
     * | `"0"` | 属性明确说「关」 | `null`，**短路，不看镜像** |
     * | 其它 / `null` / `""` | 属性没写过（老用户、或写入失败） | **回落**镜像那条老路 |
     *
     * ★ 为什么属性说了算、且说了就短路：它是**进程起来第一毫秒就能读**的那条通道
     *   （实测比镜像早约 4.6 秒，见 [PrefsBridge.PROP_MULTISPLIT] 的日志表），
     *   而我们的截止线是宿主类初始化那一刻。
     *   两条都读一遍再「取或」没有任何好处 —— 只会让「到底谁说了算」变模糊。
     *   ⚠️ 但**回落**必须留着：属性从没写过的用户，正是靠它才不至于静默失效。
     *
     * ★★ 所有「不干预」的情形必须合并成同一个 `null`：
     *   ① 开关关着　② 两条通道都读不到　③ 读到了但没有这个键　④ 值的类型/内容不认识。
     *   ⇒ 它们对本模块意味着**同一件事：什么都别做，让系统用自己的上限**（本机 = 6）。
     *   ⚠️ 尤其**不许**把 ②③④ 变成「猜一个默认值出来抬上去」—— 那等于在用户没同意的
     *     情况下改了系统行为，真机上的表现是「**我没开它却生效了**」，极难反查。
     *
     * ⚠️ 三个判据都写**精确相等**（`== "1"` / `== "0"` / `== true`），
     *   不用 `!= false` 或任何真值转换：属性是个**跨版本、跨应用共享**的字符串空间，
     *   镜像的值又是跨进程解码来的（坏包 / 老版本可能写进别的类型）
     *   ⇒ **只有明确读到认得的取值才动手**。
     *   唯一放宽的是**两端空白**（`getprop` 的输出可能带换行/空格）—— 那是格式，不是取值。
     */
    fun targetStages(propRaw: String?, mirror: Map<String, Any?>?): Int? =
        when (propRaw?.trim()) {
            // 属性明确说了 ⇒ 以它为准，短路（调用方通常连镜像都没读）
            "1" -> MAX_SUPPORTED
            "0" -> null
            // 属性没写过 ⇒ 回落镜像那条老路
            else -> if (mirror?.get(PrefsBridge.EXPERIMENTAL_MULTISPLIT) == true) MAX_SUPPORTED else null
        }
}
