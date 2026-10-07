package cn.dsr213.hyperplus.module

import cn.dsr213.hyperplus.SplitThresholds

/**
 * ★★★ 「轻折一下」折角状态机的**纯判定核**（2026-10-05 新增；2026-10-06 收进基线窗口）。
 *
 * ============================ 为什么把它抽出来 ============================
 * 这块判定原本内联在 [SplitTrigger.onAngle] 里，直接读写字段 + 打日志 + 真触发。
 * 结果就是：**分支顺序错了一次而没人发现**（见下）。抽成纯函数之后，
 * 「什么输入 ⇒ 什么结论」可以**离线钉死**，不必真机折手机去验证
 * （真机验证的代价是：一次错误判定的后果是**屏幕当着用户的面裂开**）。
 *
 * ============================ 🔴 它修的第一个 bug（2026-10-05 用户报障） ============================
 * 用户原话：「**稍微折一下内屏、过好几秒展开，已经过了 1.5s 的动作时限，但依然会触发分屏**」。
 *
 * **根因 = 分支顺序**。原来的 `when` 是：
 * ```
 * a0 - angle >= D3              ⇒ 合上（闩住）
 * angle      >= a0 - D2         ⇒ 触发
 * angle - 谷底 >= D1            ⇒ 触发        ← 这里
 * now - 进入  >  win            ⇒ 超时（闩住） ← 永远是最后一名，够不着
 * ```
 * 而 `TYPE_HINGE_ANGLE` 是 **on-change 传感器**：手机静止时**没有任何回调**
 * （`SplitTrigger.logTrajectory` 的注释里早就实测记过这件事）。
 * ⇒ 「折一下 → 停好几秒 → 展开」这条路上，样本只在**展开那一下**才来；
 *   而那个样本天然满足「自谷底回升 ≥ D1」，于是**超时那条分支从来没被求值过**。
 *
 * ⚠️ 这不是「窗口调大一点就好」的问题：窗口是对的，**是判定在自己语义上被架空了** ——
 * [SplitThresholds.winMs] 的定义就是「**从进入开始算，超时就不触发**」，一个绝对期限不该是一条兜底分支。
 *
 * ⛔⛔ **所以 `expired` 必须排在两条回弹之前。** 别为了「少一次比较」把它挪回去 ——
 *   [FoldJudgeTest.overdueValleyReboundMustNotTrigger] 就是钉这条的，挪回去它立刻红。
 *
 * ============================ 🔴🔴 它修的第二个 bug（2026-10-06 用户报障） ============================
 * 用户原话：「**我合上之后再展开，会触发分屏**」。
 *
 * **根因 = 基线窗口跨过了「合上」这件事**。展开基线 A0 原来是 `SplitTrigger` 里的一个
 * 8 秒滑动窗口（取窗口内**最大**角度）。合上手机时判定会闩住（[Outcome.CANCEL_CLOSE]），
 * 但那个 179° 的旧样本**还留在窗口里**。于是重新展开时：
 * ```
 * ① 展开到 152° ⇒ 越过 openMin ⇒ 解闩（REARM）
 * ② 再涨到 158° ⇒ a0(179) - 158 = 21 > D1(20) ⇒ ★ 误判成「开始折叠」！
 * ③ 继续涨到 178° ⇒ 178 ≥ a0 - D2(173) ⇒ ★ 误判成「轻折回弹」⇒ 分屏
 * ```
 * ⇒ 也就是说：**「把手机打开」这个动作本身被看成了「折一下再松手」**，只因为参照物是合上之前的那个 179°。
 *
 * ★ 修法 = **合上那一刻把基线窗口清空**（见 [advance] 之外的那一处 `window = emptyList()`）。
 *   物理上说得通：'a0' 的语义是"**最近见过的最大展开度**"，而手机一旦合上，
 *   合上之前那个 179° 就不再代表"当前这部手机的展开基线"了 ⇒ 清掉，由重新展开的过程自己重建。
 *   ⚠️ **超时**（[Outcome.CANCEL_TIMEOUT]）**不清**：那一次用户并没有合上手机
 *   （合上会先命中 D3），旧的 179° 仍然是有效基线。
 *   ⇒ 这条修法由 [FoldJudgeTest.closingThenReopeningMustNotTrigger] 逐样本钉死。
 *
 * ============================ 🔴🔴🔴 它修的第三个 bug（2026-10-06 第二轮报障） ============================
 * 用户原话：「**轻折超过 1 秒还是会分屏**」。
 *
 * **根因 = 计时起点太晚**。[SplitThresholds.winMs]（1 秒）的用户口径是
 * 「**轻折一下、1 秒内展开**」—— 那是**一个完整动作**的时长，**含「折下去」那一段**。
 * 但 [State.foldStartMs] 原来只在 [Outcome.ENTER]（折深已越过 `D1`=20°）那一刻才设
 * ⇒ **用户慢慢折下去的那段耗时压根不计入窗口**。
 *
 * ★ 真机日志实证（2026-10-06 05:24:51，用户当场的操作）：
 * ```
 * 05:24:51.440 ▼ 进入折叠判定：angle=158.0 A0=179.0 差=21.0° (D1=20.0)
 * 05:24:52.060 ✅ 判为「轻折回弹」：历时 620ms，最低 144.0° ⇒ 触发
 * ```
 * ⇒ 用户体感一秒多（他确实在慢慢折），系统只算了 620ms。
 * ⚠️ 同一次会话里「超时」那条链是**正常**的（`历时 1018ms / 1179ms / 1220ms / 2960ms`
 *   全被正确拦下）⇒ **问题不在阈值大小，在起算点。**
 *
 * ★ 修法 = 新增 [State.dropStartMs] ＋ [START_DROP]：角度自 A0 下降超过 5° 就记「开局」，
 *   [Outcome.ENTER] 时优先拿它当 [State.foldStartMs]。
 *
 * ⚠️ 顺手堵掉一条**同源隐患**（见 `judge` 里的 ⓪.6）：起算点提前 ⇒ 更容易命中
 *   [Outcome.CANCEL_TIMEOUT] ⇒ 而超时**故意不清**基线窗口 ⇒ 若用户接着把手机合上，
 *   那个 179° 就活了下来（闩住期间不再判一次"合上"）⇒ 重新展开又被误判成分屏。
 *   ⇒ 现在「确认掉进合上区间」这件事**在任何状态下**都会作废基线，与状态机怎么走无关。
 *   （这条由 [FoldJudgeTest.timeoutThenCloseThenReopenMustNotTrigger] 钉死。）
 *
 * ============================ 为什么「合上」仍排在超时之前 ============================
 * 两者都是「取消 + 闩住」，**行为完全相同**，区别只在日志与闩锁的语义。
 * 让 `D3` 先判，是为了保住那句 `✗ 判为「要合上」` —— 它是排查「用户以为自己在轻折、
 * 其实在合手机」这类报障时唯一能一眼看出区别的证据。
 * ⇒ 顺序：`D3` → **`expired`** → 回弹① → 回弹②。⛔ 只有 `expired` 的位置是硬要求。
 *
 * ============================ 基线窗口为什么住在**这里**（2026-10-06） ============================
 * 它原来住在 [SplitTrigger]（一个 `ArrayDeque` + 一个 `baseline()` 函数）。搬进来的唯一理由：
 * **上面第二个 bug 必须能被离线钉死**。只要窗口在外面，"合上再展开"这条链就没法在
 * JVM 单测里复现 —— 它需要"判定"与"窗口"两个部件协同，而它们在两个文件里。
 * ★ 顺带解决了一个隐患：原来 `SplitTrigger` 的四个 `var` 与 [State] 的字段是
 *   **人工一对一**的（注释里写着"改的时候两处一起看，别只改一边"）—— 现在只剩**一个** [State]。
 *
 * ============================ 纯的边界 ============================
 * 本 object **不读不写任何全局状态**（阈值、`OPEN_MIN`、冷却、窗口宽度都由调用方传进来）、
 * **不打日志**、**不触发分屏** —— 那些全是 [SplitTrigger] 的事。
 * ⇒ 可以直接在 JVM 单测里跑，⛔ 别在这里 `import` 任何 `android.*`。
 */
internal object FoldJudge {

    /** [State.minDuringFold] 的初值（= 完全展开，比任何真实角度都大） */
    const val MIN_INIT = 180f

    /**
     * 基线自学习的滑动窗口宽度：取最近这么久内的**最大**角度当展开基线 A0。
     * ★ 原来是 `SplitTrigger.A0_WINDOW_MS`，2026-10-06 随窗口一起搬进来。
     * ★ 为什么是"窗口内最大值"而不是"最近一次稳定值"：那样用户折到 90° 停一会儿
     *   基线就变成 90，之后"轻折"永远检测不到。取最大值天然等于"最近见过的最大展开度"。
     */
    const val A0_WINDOW_MS = 8_000L

    /** 样本入窗口的最小角度变化（滤噪声）。★ 原来是 `SplitTrigger.EPS`。 */
    const val EPS = 0.35f

    /**
     * ★★★ 「本次往下折」的**开局判据**：角度自 A0 下降超过这个值，就算**动作已经开始了**。
     * 2026-10-06 新增（用户报障「轻折超过 1 秒还是会分屏」）。
     *
     * ==================== 为什么需要它 ====================
     * [SplitThresholds.winMs] 的用户口径是「**轻折一下、在 1 秒内展开**」——
     * 说的是**一个完整动作**的时长，**含「折下去」那一段**。
     * 但 [State.foldStartMs] 原本只在 [Outcome.ENTER] 那一刻才设，而 ENTER 的条件是
     * 「已经折掉 `D1`(20°)」⇒ **用户慢慢折下去的那段耗时压根不计入窗口**。
     *
     * ★ 真机实证（2026-10-06 05:24:51，本机日志）：
     * ```
     * 05:24:51.440 ▼ 进入折叠判定：angle=158.0 A0=179.0 差=21.0° (D1=20.0)
     * 05:24:52.060 ✅ 判为「轻折回弹」：历时 620ms，最低 144.0° ⇒ 触发
     * ```
     * 用户做的是「慢慢折到 144° 再展开」，体感一秒多 ⇒ 他要的是**不触发**，
     * 而系统只算了「折深 20° 之后」那 620ms ⇒ 触发。**差异全在起算点，不在阈值大小。**
     *
     * ==================== 取值为什么是 5 ====================
     * - 远大于 [EPS]（0.35，滤噪声）⇒ 静止抖动不会误开局；
     * - 远小于 `D1`（20）⇒ 才能把「折下去」的大头圈进来。
     * ⛔ **别调到 1° 量级**：静止时的微小漂移会把起点提前到"上一次展开"，
     *   于是正常轻折也被当成超时（**假阴性：该分屏不分屏**，比假阳性更难排查）。
     */
    const val START_DROP = 5f

    /**
     * 状态机的全部可变位（不可变快照）。
     * ★★ 2026-10-06：它现在是 [SplitTrigger] 里**唯一**的一份状态 ——
     *   原来那四个 `var`（`folding`/`suppressed`/`foldStartMs`/`minDuringFold`）
     *   与基线窗口的 `recent`/`lastSample` 都已并进来，⛔ 别再在调用方复制一份。
     */
    data class State(
        /** 已越过 D1，正在等回弹 / 等它继续合上 */
        val folding: Boolean = false,
        /** 「已判为要合上 / 已超时」的闩锁；只有角度回到展开态才解 */
        val suppressed: Boolean = false,
        /**
         * 本次折叠的**计时起点** —— ★ 2026-10-06 起它的语义是「**用户开始往下折**」那一刻，
         * 而不是「[folding] 变 true 那一刻」（见 [START_DROP] 的 KDoc：用户口径是
         * 「轻折一下、1 秒内展开」，含折下去那段）。
         * 取值优先级：[dropStartMs]（若本次动作已开局）→ 否则退化成 ENTER 那一刻。
         */
        val foldStartMs: Long = 0L,
        /**
         * 本次「往下折」动作的**开局时刻**（角度自 A0 首次下降超过 [START_DROP] 的那一刻）；
         * `-1` = 当前没有正在进行的下折动作。
         * ★ 它是 [foldStartMs] 的**候选值**：有了它，「1 秒」才从「开始折」算起。
         */
        val dropStartMs: Long = -1L,
        /** 本次折叠过程中见过的最小角度（谷底） */
        val minDuringFold: Float = MIN_INIT,
        /**
         * 展开基线 A0 的**滑动窗口**：`(时刻, 角度)`，按时间递增。
         * ★ 只收 > `openMin` 的样本 —— 半折摆放不该把基线拉低。
         * ⚠️ 它是判定的一部分（[Outcome.ENTER] 与 [Outcome.CANCEL_CLOSE] 都要用 A0），
         *   ⛔ 别为了"省一次拷贝"把它挪回调用方。
         */
        val window: List<Pair<Long, Float>> = emptyList(),
        /** 上一个进过窗口的角度（滤噪去重用的） */
        val lastSample: Float = Float.NaN,
    )

    /** 判定结论。⛔ 它**只描述发生了什么**，不含「该做什么」（副作用留在 [SplitTrigger]）。 */
    enum class Outcome {
        /** 什么都不做：冷却中 / 已闩住但还没回展开位 / 落差不够 */
        NOTHING,

        /** 角度回到展开态 ⇒ 解开闩锁、重新武装 */
        REARM,

        /** 落差越过 D1 ⇒ 开始一次折叠计时 */
        ENTER,

        /** 轻折回弹 ⇒ ★ 该触发分屏了 */
        TRIGGER,

        /** 落差越过 D3 ⇒ 判为「要合上手机」，取消 + 闩住 + **清空基线窗口** */
        CANCEL_CLOSE,

        /** 超过 [SplitThresholds.winMs] 还没回弹 ⇒ 取消 + 闩住（第一个报障修的就是它） */
        CANCEL_TIMEOUT,
    }

    /**
     * 判定结果：**新状态** + **结论** + **本次用的展开基线**。
     *
     * ★ [a0] 由本函数算出来（窗口在里面），调用方要打日志就取它 ——
     *   ⛔ 别在调用方再算一遍（那正是"两个真值"的开端）。
     */
    data class Verdict(val state: State, val outcome: Outcome, val a0: Float)

    /**
     * 一个折角样本进来，算出新的状态与结论。
     *
     * @param s 进入本次判定之前的状态（含基线窗口）
     * @param angle 当前折角（**越大越展开**；本机实测 全展≈179 / 半开≈89~92 / 合上≈2~3）
     * @param nowMs 采样时刻（`SystemClock.elapsedRealtime()`）
     * @param lastTriggerMs 上一次**真的触发**的时刻（只用于冷却判断，本函数不改它）
     * @param th 用户配置的四个阈值
     * @param openMin 「算展开态」的角度下限（`SplitTrigger.OPEN_MIN`）
     * @param cooldownMs 两次触发之间的最小间隔（`SplitTrigger.COOLDOWN_MS`）
     */
    fun judge(
        s: State,
        angle: Float,
        nowMs: Long,
        lastTriggerMs: Long,
        th: SplitThresholds,
        openMin: Float,
        cooldownMs: Long,
        a0WindowMs: Long = A0_WINDOW_MS,
        eps: Float = EPS,
    ): Verdict {
        // —— ⓪ 先把基线窗口推进一格 ——
        // ⚠️ 它必须在所有分支**之前**做：闩住期间也要继续收样本，否则重新展开之后
        //   a0 无处可建（那正是 2026-10-06 那个 bug 的另一半成因）。
        val w = advance(s, angle, nowMs, openMin, a0WindowMs, eps)
        val a0 = w.window.maxOfOrNull { it.second } ?: angle

        // —— ⓪.5 跟踪「本次往下折」的开局（2026-10-06，见 [START_DROP]）——
        //    ★ 必须在所有分支之前：ENTER 要拿它当计时起点。
        val d = trackDrop(w, angle, a0, nowMs)

        // —— ⓪.6 ★★ 只要确认掉进「合上」区间，旧的展开基线**一律作废**（任何状态都要做）——
        //
        // ⚠️ 为什么不能只在 [Outcome.CANCEL_CLOSE] 那一支里清：闩住期间还有**另一条路** ——
        //    「折到半开（≈90°）停超过 1 秒 ⇒ 超时闩住（⚠️ 超时**故意不清**窗口，当时的判断是
        //      '用户并没有合上手机'）⇒ 接着把手机合上 ⇒ 但 [suppressed] 分支只等
        //      `angle > openMin`，**不会再去判一次合上** ⇒ 那个 179° 就活了下来」
        //    ⇒ 重新展开时它又会被当成「轻折一下」。
        //    这正是 2026-10-06 那个「合上再展开会误触发分屏」的**另一条路径**，
        //    入口从「直接合上」换成了「先超时、后合上」。
        // ★ 判据用 `a0 - angle >= d3`（与 CANCEL_CLOSE 同一条相对量）；清完之后
        //   `a0` 会在**下一拍**退化成当前角度 —— 那正是我们要的（基线归零）。
        val cur0 = if (a0 - angle >= th.d3 && d.window.isNotEmpty()) {
            d.copy(window = emptyList(), lastSample = Float.NaN)
        } else {
            d
        }

        // —— ① 闩住中：只等「手机真的回到展开位」，别的什么都不做 ——
        //
        // ★★★ **B4（2026-10-05）**：解闩判据从「相对 `a0`」改成**绝对角度**。
        //
        // ==================== 原来的写法为什么会漏 ====================
        // 原来是 `angle >= a0 - d2`。看着合理（「回到基线附近」），但它有个**自我拆台**的隐患：
        // `a0` 是滑动窗口里的**最大值** —— 用户若停在半折位（比如 100°）
        // **超过 8 秒**，窗口里就只剩 100° 了 ⇒ `a0` 自己降到 100 ⇒ `angle(100) >= 100 - 6` 成立
        // ⇒ **他明明还把手机折着，闩锁却解开了**。
        // 之后他若继续折到 60° ⇒ `a0(100) - 60 = 40 > D1` ⇒ 又进「折叠判定」⇒ 再回升 ⇒ **触发**。
        // 而他刚才那一下的本意是「要合上手机」（超时闩住就是为这个）⇒ 这是**假阳性**。
        //
        // ⇒ 判据回到**物理事实**：「重新武装」的条件是**手机真的回到展开位**。
        //   本机实测：全展 ≈ 179°、半开 89~92°、合上 2~3° ⇒ 用 [openMin]（`SplitTrigger.OPEN_MIN`，
        //   150°）当判据 —— 它本来就是「角度 > 这个值才算展开态样本」的那条线，
        //   同一个物理含义就复用同一个常量。
        // ⚠️ 不用 `a0` 不是「嫌麻烦」：`a0` 在闩住期间**没有任何输入能把它抬回去**
        //   （最大值窗口只会随着老样本过期而下跌），所以它在这里是**单向下行**的 ——
        //   拿一个只会跌的参照去判「回到高处」，早晚会误判。
        if (s.suppressed) {
            return if (angle > openMin) Verdict(cur0.copy(suppressed = false), Outcome.REARM, a0)
            else Verdict(cur0, Outcome.NOTHING, a0)
        }

        // —— ② 还没进入：先过冷却，再看落差够不够 ——
        if (!s.folding) {
            if (nowMs - lastTriggerMs < cooldownMs) return Verdict(cur0, Outcome.NOTHING, a0)
            // ⚠️ `a0 > openMin` 这道前置不能省：a0 是「窗口内最大角」，半折摆放久了它会掉下来，
            //   掉到展开态以下时 `a0 - angle` 已经没有物理意义了。
            return if (a0 > openMin && a0 - angle > th.d1) {
                Verdict(
                    cur0.copy(
                        folding = true,
                        // ★★★ 计时起点优先取「**开始折**」那一刻（[dropStartMs]，见 [START_DROP]）：
                        //   只有在本次动作还没开局的极端情况下（例如冷启动直接收到一个深折样本）
                        //   才退化成"就是现在"。
                        foldStartMs = if (cur0.dropStartMs >= 0L) cur0.dropStartMs else nowMs,
                        minDuringFold = angle,
                    ),
                    Outcome.ENTER,
                    a0,
                )
            } else {
                Verdict(cur0, Outcome.NOTHING, a0)
            }
        }

        // —— ③ 折叠中 ——
        val minFold = minOf(s.minDuringFold, angle)
        val cur = cur0.copy(minDuringFold = minFold)

        return when {
            // (a) 合上：单调下降越过 D3。**排在超时之前**只是为了日志语义（两者都「取消+闩住」）。
            //     ★★★ 这里**顺手清空基线窗口** —— 2026-10-06 那个「合上再展开会触发分屏」的修法。
            //     ⛔ 别删掉 `window = emptyList()`：删了那条 bug 立刻回来
            //       （[FoldJudgeTest.closingThenReopeningMustNotTrigger] 会红）。
            //     ⚠️ `lastSample` 一起复位 —— 否则"重新展开后第一个 >150° 的样本"可能因为
            //       与合上之前的旧值太接近而被滤噪丢掉，窗口要晚一格才建得起来。
            a0 - angle >= th.d3 ->
                Verdict(
                    cur.copy(
                        folding = false,
                        suppressed = true,
                        window = emptyList(),
                        lastSample = Float.NaN,
                        // ★ 基线都作废了，本次动作的开局时刻也就没有参照物了 ⇒ 一起复位。
                        //   （不清的后果：手机合着摆一会儿再展开，"起点"还停在合上之前。）
                        dropStartMs = -1L,
                    ),
                    Outcome.CANCEL_CLOSE,
                    a0,
                )

            // (b) ★★★ 到期：⛔⛔ **位置是硬要求，必须在两条回弹之前**（见类注释第一个 bug）。
            //     `>` 而不是 `>=`：与原来逐字一致，别顺手改（边界样本的行为要有唯一的解释）。
            //     ⚠️ 这里**不清窗口**：超时意味着用户没合上手机（合上会先命中 D3），
            //       旧的展开基线仍然有效。
            nowMs - s.foldStartMs > th.winMs ->
                Verdict(cur.copy(folding = false, suppressed = true), Outcome.CANCEL_TIMEOUT, a0)

            // (c) 回弹①：完全弹回原基线附近 —— 最干净的一路
            angle >= a0 - th.d2 ->
                Verdict(cur.copy(folding = false), Outcome.TRIGGER, a0)

            // (d) 回弹②：只判「从谷底明显回升 ≥ D1」 —— 松手后铰链必然回弹，不必等它回到展开位；
            //     合上过程是**单调下降**，绝不会走到这里。
            angle - minFold >= th.d1 ->
                Verdict(cur.copy(folding = false), Outcome.TRIGGER, a0)

            else -> Verdict(cur, Outcome.NOTHING, a0)
        }
    }

    /**
     * 把「**本次往下折**」的开局时刻**推进一格**（2026-10-06，见 [START_DROP]）。
     *
     * 规则只有两条：
     * - 角度自 A0 下降超过 [START_DROP] ⇒ 这是"一次下折动作"的**开局**，记下时刻；
     * - 角度回到 A0 附近（不再算下折）⇒ 复位成 `-1`。
     *
     * ⚠️ **只记第一次**（动作进行中不刷新）。刷新的后果是起点随着折叠一路后退 ⇒
     *   等于把窗口无限延长，「1 秒」这条约束直接失效。
     * ⚠️ 它**不看** [State.folding]：折叠中途的回升（还没回到 A0 附近）不该清掉起点 ——
     *   否则「折下去 → 抖一下 → 再折下去」会被当成两次动作。
     * ⚠️ [State.suppressed] 期间同样跟踪：闩住时用户正在重新展开，这条恰好负责把
     *   残留的旧起点清掉（`a0 - angle` 会回到 [START_DROP] 以内）。
     */
    private fun trackDrop(s: State, angle: Float, a0: Float, nowMs: Long): State = when {
        a0 - angle > START_DROP ->
            if (s.dropStartMs < 0L) s.copy(dropStartMs = nowMs) else s

        else ->
            if (s.dropStartMs >= 0L) s.copy(dropStartMs = -1L) else s
    }

    /**
     * 把基线窗口**推进一格**：可能收进当前样本、并剪掉过期样本。
     *
     * ⚠️ 收样本的两个条件都不能少：
     *   ① `angle > openMin` —— 否则半折摆放会把基线拉低（见 [State.window]）；
     *   ② 与上一个进窗样本的差 ≥ [eps] —— 否则静止时的微小抖动会把窗口塞满重复值。
     * ⚠️ 剪枝用 **严格大于** `a0WindowMs`：恰好落在边界上的样本**留着**（与旧实现逐字一致）。
     */
    private fun advance(
        s: State,
        angle: Float,
        nowMs: Long,
        openMin: Float,
        a0WindowMs: Long,
        eps: Float,
    ): State {
        var win = s.window
        var last = s.lastSample
        if (angle > openMin && (last.isNaN() || kotlin.math.abs(angle - last) >= eps)) {
            win = win + (nowMs to angle)
            last = angle
        }
        // 时间是单调递增的 ⇒ `dropWhile` 等价于"从队首一个个弹掉过期的"
        val kept = win.dropWhile { nowMs - it.first > a0WindowMs }
        return s.copy(window = kept, lastSample = last)
    }
}
