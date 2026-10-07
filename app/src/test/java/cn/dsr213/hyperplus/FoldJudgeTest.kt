package cn.dsr213.hyperplus

import cn.dsr213.hyperplus.module.FoldJudge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「轻折一下」折角判定核（[FoldJudge.judge]）的行为锁定。
 *
 * ============================ 为什么必须有这个文件 ============================
 * 这套判定原来内联在 `SplitTrigger.onAngle` 里，结果是**分支顺序错了一次而没人发现**：
 * 用户 2026-10-05 报「稍微折一下内屏、过好几秒展开，已经过了动作时限，但依然会触发分屏」——
 *
 * ```
 * 折一下 → 停好几秒（on-change 传感器静止时不发样本）→ 展开
 *                                                   ↑ 下一个样本就是这一个
 * ```
 * 那个样本天然满足「自谷底回升 ≥ D1」，而原来 `when` 里**超时那条排在最后**
 * ⇒ 它从来没被求值过 ⇒ 窗口形同虚设。
 * ⇒ 由 [overdueValleyReboundMustNotTrigger] / [overdueFullReboundMustNotTrigger] 钉死。
 *
 * ============================ 🔴🔴 2026-10-06：第二个报障（合上再展开） ============================
 * 用户原话：「**我合上之后再展开，会触发分屏**」。
 *
 * 根因是**基线窗口跨过了"合上"这件事**：合上时判定闩住，但那个 179° 的旧样本还在窗口里；
 * 重新展开时，`a0(179) - 158 = 21 > D1(20)` 把"我在打开手机"误判成"我开始折了"，
 * 继续涨到 `≥ a0 - D2` 又满足"松手回弹" ⇒ 分屏。
 *
 * ⇒ 修法（合上时清空窗口）由 [closingThenReopeningMustNotTrigger] 逐样本钉死；
 *   [staleBaselineIsWhatCausedTheReopenBug] 是**反证** —— 它手工撤销那次清空，
 *   证明"不清就一定会误触发"，免得后来人以为那道清空只是顺手加的保险。
 *
 * ⚠️ 本文件**不碰 Android**（[FoldJudge] 是纯的），所以跑在普通 JVM 单测里，
 *   ⛔ 别在这里引入 `SensorEvent` / `Context` 之类的东西。
 * ⚠️ 2026-10-06 起**基线窗口住在 [FoldJudge.State] 里** ⇒ 本文件的用例必须显式给窗口播种
 *   （见 [flat] / [folding]），⛔ 别再想"从外面传一个 a0 进去"。
 */
class FoldJudgeTest {

    private val th = SplitThresholds.DEFAULT   // 20 / 6 / 90 / 1000ms

    /** 与 `SplitTrigger` 里那两个常量**同值**（本文件不 import 那个 Android 类） */
    private val openMin = 150f
    private val cooldown = 1_500L

    /**
     * 不关心冷却的用例统一用这个时刻当 `nowMs`。
     * ⚠️ 为什么不能用 1000：`lastTriggerMs` 的初值是 0，`now - 0 = 1000 < 冷却 1500`
     *   ⇒ 那些用例会被**冷却**挡住而不是被它们自己声称的判据挡住 —— 测试照样"绿"，
     *   但绿得没有意义。凡是给 `angle`/`a0` 设前提的用例，都必须先跨过冷却。
     */
    private val T0 = 10_000L

    /** 全展基线（本机实测 ≈179°） */
    private val a0 = 179f

    /**
     * 「刚量到展开基线」的状态：窗口里就一个样本，时刻**贴着 nowMs**。
     * ⚠️ 时刻必须贴着 —— 窗口按 [FoldJudge.A0_WINDOW_MS]（8s）剪枝，
     *   若把它写成固定时刻，`nowMs` 一大的用例（例如 20_000）会把它剪掉、
     *   于是 `a0` 退化成当前角度，用例就"绿得不是它想测的那件事"了。
     */
    private fun flat(nowMs: Long, a0v: Float = a0): FoldJudge.State =
        FoldJudge.State(window = listOf((nowMs - 50L) to a0v), lastSample = a0v)

    /** 折叠中（已越过 D1）的状态 */
    private fun folding(nowMs: Long, startMs: Long, valley: Float, a0v: Float = a0): FoldJudge.State =
        flat(nowMs, a0v).copy(folding = true, foldStartMs = startMs, minDuringFold = valley)

    private fun judge(
        angle: Float,
        nowMs: Long,
        st: FoldJudge.State,
        lastTriggerMs: Long = 0L,
    ) = FoldJudge.judge(st, angle, nowMs, lastTriggerMs, th, openMin, cooldown)

    // ================================================================ 进入折叠

    @Test
    fun freshStateOnFlatPhoneDoesNothing() {
        val v = judge(angle = a0, nowMs = T0, st = FoldJudge.State())
        assertEquals(FoldJudge.Outcome.NOTHING, v.outcome)
        assertFalse(v.state.folding)
        // ★ 冷启动第一个样本：窗口空 ⇒ a0 退化成当前角度 ⇒ 落差 0，绝不会误进
        assertEquals(a0, v.a0, 1e-4f)
    }

    @Test
    fun shallowDipBelowD1DoesNotEnter() {
        // 落差 19° < D1(20°) ⇒ 还算"手抖"，不进入
        val v = judge(angle = a0 - 19f, nowMs = T0, st = flat(T0))
        assertEquals(FoldJudge.Outcome.NOTHING, v.outcome)
        assertFalse(v.state.folding)
    }

    @Test
    fun enteringRecordsStartTimeAndValley() {
        val v = judge(angle = 155f, nowMs = T0, st = flat(T0))
        assertEquals(FoldJudge.Outcome.ENTER, v.outcome)
        assertTrue(v.state.folding)
        assertEquals(T0, v.state.foldStartMs)
        assertEquals(155f, v.state.minDuringFold, 1e-4f)
    }

    /**
     * 基线本身已经不在展开态（半折摆放久了 `a0` 会掉下来）⇒ 落差再大也没有物理意义。
     * 这道闸原来就有，⛔ 别在重构时丢掉。
     */
    @Test
    fun doesNotEnterWhenBaselineIsNotOpen() {
        val v = judge(angle = 100f, nowMs = T0, st = flat(T0, a0v = 140f))
        assertEquals(FoldJudge.Outcome.NOTHING, v.outcome)
        assertFalse(v.state.folding)
    }

    @Test
    fun cooldownBlocksAQuickSecondFold() {
        // 上一秒刚触发过 ⇒ 这次落差够（179-150=29）也不进入
        val v = judge(angle = 150f, nowMs = T0, st = flat(T0), lastTriggerMs = T0 - 100L)
        assertEquals(FoldJudge.Outcome.NOTHING, v.outcome)
        assertFalse(v.state.folding)
    }

    /** 冷却一过，同样的落差就能进入了 —— 上面那条才有意义。 */
    @Test
    fun afterCooldownTheSameDipEnters() {
        val v = judge(angle = 150f, nowMs = T0, st = flat(T0), lastTriggerMs = T0 - cooldown)
        assertEquals(FoldJudge.Outcome.ENTER, v.outcome)
    }

    // ================================================================ 正常触发

    @Test
    fun quickValleyReboundTriggers() {
        // 进入后 800ms 从谷底回升 30°（≥D1）⇒ 触发
        val v = judge(angle = 170f, nowMs = 1_800L, st = folding(1_800L, 1_000L, 140f))
        assertEquals(FoldJudge.Outcome.TRIGGER, v.outcome)
        assertFalse(v.state.folding)
    }

    @Test
    fun quickFullReturnToBaselineTriggers() {
        // 完全弹回基线附近（angle ≥ a0 - D2 = 173）⇒ 触发
        val v = judge(angle = 176f, nowMs = 1_600L, st = folding(1_600L, 1_000L, 140f))
        assertEquals(FoldJudge.Outcome.TRIGGER, v.outcome)
    }

    @Test
    fun midFoldSampleDeepensTheValley() {
        // 还在往下折：谷底被刷新，继续等回弹
        val v = judge(angle = 120f, nowMs = 1_200L, st = folding(1_200L, 1_000L, 150f))
        assertEquals(FoldJudge.Outcome.NOTHING, v.outcome)
        assertTrue(v.state.folding)
        assertEquals(120f, v.state.minDuringFold, 1e-4f)
    }

    // ================================================================ ★★★ 报障一（2026-10-05）：超时不触发
    //
    // 两条都用「进入时 a0=179、谷底 140」，唯一变量是 nowMs（= 有没有过窗口）与角度。

    /**
     * ★★★ **用户报的那一条**：折一下、停 4 秒、再展开。
     *
     * 展开那一刻的样本是 `angle - 谷底 = 30 ≥ D1(20)` —— 如果按"分支顺序"去判，
     * 它会命中「回弹②」⇒ 触发。**必须**命中「到期」。
     */
    @Test
    fun overdueValleyReboundMustNotTrigger() {
        val v = judge(angle = 170f, nowMs = 5_000L, st = folding(5_000L, 1_000L, 140f))
        assertEquals(FoldJudge.Outcome.CANCEL_TIMEOUT, v.outcome)
        assertFalse(v.state.folding)
        assertTrue("超时必须闩住，否则下一个样本会每帧重进", v.state.suppressed)
    }

    /** 同一件事，但展开得更干净（`angle ≥ a0 - D2`）—— 「回弹①」同样不许越过期限。 */
    @Test
    fun overdueFullReboundMustNotTrigger() {
        val v = judge(angle = 178f, nowMs = 9_000L, st = folding(9_000L, 1_000L, 140f))
        assertEquals(FoldJudge.Outcome.CANCEL_TIMEOUT, v.outcome)
        assertTrue(v.state.suppressed)
    }

    /**
     * 边界的**唯一解释**：判据是 `now - 进入 > win`（严格大于）。
     * ⇒ 恰好等于窗口的那一刻**还不算超时**，该触发就触发。
     * ⚠️ 这条不是为了"好用"，是为了让边界样本的行为只有一种读法 —— ⛔ 别改成 `>=`。
     */
    @Test
    fun exactlyAtWindowIsStillInTime() {
        val t = 1_000L + th.winMs
        val v = judge(angle = 170f, nowMs = t, st = folding(t, 1_000L, 140f))
        assertEquals(FoldJudge.Outcome.TRIGGER, v.outcome)
    }

    /** 1ms 过线就翻面 —— 上面那条边界测试的另一半。 */
    @Test
    fun oneMillisecondOverTheWindowCancels() {
        val t = 1_000L + th.winMs + 1
        val v = judge(angle = 170f, nowMs = t, st = folding(t, 1_000L, 140f))
        assertEquals(FoldJudge.Outcome.CANCEL_TIMEOUT, v.outcome)
    }

    // ================================================================ 合上保护

    /**
     * 真·合手机是**单调下降**，越 D3 就取消 —— 而且它排在超时之前（日志语义，
     * 两者行为相同：都取消 + 闩住）。⇒ 慢悠悠合上（早过窗口）也要报「要合上」，
     * 不能报「超时」—— 那是排查"用户以为在轻折、其实在合手机"时唯一的分辨依据。
     */
    @Test
    fun closingBeatsTimeoutInLogging() {
        val v = judge(angle = 5f, nowMs = 20_000L, st = folding(20_000L, 1_000L, 90f))
        assertEquals(FoldJudge.Outcome.CANCEL_CLOSE, v.outcome)
        assertTrue(v.state.suppressed)
        // ★★ 一次报障（2026-10-06）的修法就在这一行断言里：
        //   合上那一刻必须把展开基线**清空**，否则重新展开会被当成"折一下再松手"。
        //   ⛔ 删掉 FoldJudge 里那句 `window = emptyList()`，这条立刻红。
        assertTrue("合上必须清空展开基线窗口", v.state.window.isEmpty())
        assertTrue("lastSample 也要复位，否则重开后第一个展开样本会被滤噪丢掉", v.state.lastSample.isNaN())
    }

    // ================================================================ 闩锁与重新武装

    @Test
    fun latchedStaysLatchedUntilReallyOpen() {
        val s = FoldJudge.State(suppressed = true)
        // 半折位不算"回到展开态" ⇒ 继续闩着（⛔ 不是相对 a0，是绝对角度）
        val v1 = judge(angle = 120f, nowMs = 2_000L, st = s)
        assertEquals(FoldJudge.Outcome.NOTHING, v1.outcome)
        assertTrue(v1.state.suppressed)

        val v2 = judge(angle = 160f, nowMs = 2_100L, st = s)   // > OPEN_MIN(150)
        assertEquals(FoldJudge.Outcome.REARM, v2.outcome)
        assertFalse(v2.state.suppressed)
    }

    /** 闩住期间**不许**触发 —— 否则"每帧重进一次"的风暴又回来了。 */
    @Test
    fun latchedNeverTriggersEvenOnBigRebound() {
        val v = judge(angle = 178f, nowMs = 2_000L, st = FoldJudge.State(suppressed = true))
        assertEquals(FoldJudge.Outcome.REARM, v.outcome)
        assertFalse(v.state.suppressed)
    }

    /** 刚触发完（`folding=false`）的下一个样本必须走冷却，而不是被当成"新一次折叠"。 */
    @Test
    fun afterTriggerNextSampleIsGovernedByCooldown() {
        val fired = judge(angle = 170f, nowMs = 1_800L, st = folding(1_800L, 1_000L, 140f))
        assertEquals(FoldJudge.Outcome.TRIGGER, fired.outcome)
        val again = judge(angle = 150f, nowMs = 1_900L, st = fired.state, lastTriggerMs = 1_800L)
        assertEquals(FoldJudge.Outcome.NOTHING, again.outcome)
    }

    // ================================================================ ★★★★ 报障二（2026-10-06）：合上再展开
    //
    // 两条用例共用同一段「展开 → 合上 → 再展开」轨迹，唯一差别是：
    //   · [closingThenReopeningMustNotTrigger] —— 走**真判定**（合上会清窗口）⇒ 不许触发；
    //   · [staleBaselineIsWhatCausedTheReopenBug] —— 手工撤销那次清空 ⇒ **必然**触发。

    /**
     * 重新展开的那段行程：从 150° 起、每步 2° 涨到 180°。
     *
     * ⚠️ 步长必须是 2°：`ENTER` 要求 `a0 - angle > D1` ⇒ `angle < 159°`，
     *   而闩锁是在**第一个 > 150°** 的样本上解开的（那个样本被 REARM 吃掉）。
     *   步长太大（比如 12°）会一步跨过 (150,159) 这段，**复现不出**这个 bug ——
     *   于是测试会"绿"得毫无意义。这是实测反推出来的：真机上手指缓慢展开就是小步长。
     *
     * @return 三元组：末状态、末时刻、这一路上出现过的所有结论
     */
    private fun reopenRamp(from: FoldJudge.State, t0: Long): Triple<FoldJudge.State, Long, List<FoldJudge.Outcome>> {
        var s = from
        var t = t0
        val seen = mutableListOf<FoldJudge.Outcome>()
        var a = 150f
        while (a <= 180f) {
            t += 60L
            val v = judge(angle = a, nowMs = t, st = s)
            s = v.state
            seen += v.outcome
            a += 2f
        }
        return Triple(s, t, seen)
    }

    /** 展开态（喂几个 179° 的样本，让基线稳稳立在 179） */
    private fun openAndSettle(t0: Long): Pair<FoldJudge.State, Long> {
        var s = FoldJudge.State()
        var t = t0
        repeat(3) { t += 80L; s = judge(angle = 179f, nowMs = t, st = s).state }
        return s to t
    }

    /**
     * ★★★★ **用户 2026-10-06 报的那一条**：合上手机、再展开，**不该**分屏。
     *
     * 它同时钉两件事：
     *   ① 合上（[FoldJudge.Outcome.CANCEL_CLOSE]）时窗口被清空；
     *   ② 之后整段重新展开的行程里，**一次 ENTER / TRIGGER 都不许出现**。
     */
    @Test
    fun closingThenReopeningMustNotTrigger() {
        val (open, t1) = openAndSettle(T0)

        // —— 合上：一路降到 5°（每步 60ms） ——
        var s = open
        var t = t1
        val closing = mutableListOf<FoldJudge.Outcome>()
        for (a in listOf(150f, 100f, 50f, 5f)) {
            t += 60L
            val v = judge(angle = a, nowMs = t, st = s)
            s = v.state
            closing += v.outcome
        }
        // ⚠️ 断言的是「**这一路上出现过** CANCEL_CLOSE」，不是「最后一拍的结论」——
        //   判成合上（越过 D3）的那一拍**当场就闩住**了，后面几拍只回 NOTHING。
        //   （第一版就是写成断言最后一拍，于是 `expected:<CANCEL_CLOSE> but was:<NOTHING>`。）
        assertTrue("合上途中必须判出 CANCEL_CLOSE，实际：$closing",
            closing.contains(FoldJudge.Outcome.CANCEL_CLOSE))
        // ★ 顺带钉住「D3 排在超时之前」：合上是**角度**越线判出来的，
        //   不是被 1s 窗口兜住的 —— 否则日志里那句「要合上」就永远不会出现。
        assertFalse("合上不该被当成超时（CANCEL_TIMEOUT 是另一条路）",
            closing.contains(FoldJudge.Outcome.CANCEL_TIMEOUT))
        assertTrue("合上必须闩住", s.suppressed)
        assertTrue("合上必须把展开基线清空", s.window.isEmpty())

        // —— 重新展开：全程不许出现 ENTER / TRIGGER ——
        val (end, _, seen) = reopenRamp(s, t)
        assertFalse("重新展开不该被当成「开始折叠」（ENTER）", seen.contains(FoldJudge.Outcome.ENTER))
        assertFalse("★ 重新展开绝不许触发分屏（TRIGGER）", seen.contains(FoldJudge.Outcome.TRIGGER))
        // 解闩那一下还是要发生的，否则说明"展开"根本没被识别
        assertTrue("重新展开到 >150° 必须解闩", seen.contains(FoldJudge.Outcome.REARM))
        assertFalse(end.suppressed)
    }

    /**
     * 🔴 **反证**：把「合上时清窗口」这一步手工撤销 ⇒ 用户的报障立刻复现。
     *
     * ★ 这条测试**故意断言一个坏行为**。它存在的唯一意义：证明
     *   [closingThenReopeningMustNotTrigger] 里那道清空**确实是病因所在**，
     *   而不是"顺手加的一道保险"。⛔ 若哪天它不再触发，说明判定语义被改动了，
     *   那时要连带复核 [FoldJudge] 类注释里那段成因分析还成不成立。
     */
    @Test
    fun staleBaselineIsWhatCausedTheReopenBug() {
        // 状态 = 刚判过「要合上」（闩住），但窗口里**还留着**合上之前的 179°
        val t0 = T0 + 200L
        val stale = FoldJudge.State(
            suppressed = true,
            window = listOf((t0 - 100L) to 179f),
            lastSample = 179f,
        )
        val (_, _, seen) = reopenRamp(stale, t0)
        assertTrue("窗口里若留着合上之前的基线，重开就会被误判成「开始折叠」", seen.contains(FoldJudge.Outcome.ENTER))
        assertTrue("★ 而且会一路走到触发分屏 —— 这正是用户报的现象", seen.contains(FoldJudge.Outcome.TRIGGER))
    }

    // ================================================================ 基线窗口自身的行为
    //
    // 2026-10-06 窗口搬进 [FoldJudge.State] ⇒ 这几条把它自己的规矩钉住。

    /** 半折摆放不该把基线拉低（否则"轻折"永远检测不到）。 */
    @Test
    fun baselineOnlyTakesOpenSamples() {
        var s = flat(T0)
        var t = T0
        // 停在 100° 挺久（< openMin ⇒ 不进窗口）
        repeat(5) { t += 100L; s = judge(angle = 100f, nowMs = t, st = s).state }
        assertEquals("半折样本不许进窗口", a0, s.window.maxOfOrNull { it.second } ?: 0f, 1e-4f)
    }

    /**
     * 超过窗口宽度的老样本必须被剪掉，否则基线会永远停在"很久以前那个 179°"。
     *
     * ⚠️ 进来的角度**必须与旧样本差 ≥ EPS**（这里用 178°）—— [FoldJudge.advance] 有去重，
     *   喂一模一样的 179° 时新样本**不会入窗**，于是剪完窗口是**空的**、而不是"只剩新的那个"。
     *   （第一版就写成了 179° + 断言 size==1，于是 `expected:<1> but was:<0>`。）
     *   去重导致窗口自然空掉这件事本身由 [identicalSampleIsNotReaddedWindowMayEmpty] 单独钉。
     */
    @Test
    fun staleSamplesArePruned() {
        val s = flat(T0)
        // 8 秒之后再看：那个样本已过期（`now - t > 8000` 严格大于）
        val t = T0 + FoldJudge.A0_WINDOW_MS + 10L
        val v = judge(angle = 178f, nowMs = t, st = s)   // 与旧样本差 1° ≥ EPS ⇒ 会入窗
        assertEquals("过期样本必须被剪掉，只剩当前这一个", 1, v.state.window.size)
        assertEquals(t, v.state.window.first().first)
        assertEquals(178f, v.state.window.first().second, 1e-4f)
        // 新样本自己不会过期（`now - t == 0`）
        assertEquals("剪完的基线就是这个新样本", 178f, v.a0, 1e-4f)
    }

    /**
     * 与上一条互补：角度**没变**时去重会让它不进窗 ⇒ 老样本又已过期 ⇒ **窗口空掉**。
     *
     * ★ 这不是 bug，是去重的正常后果，而且**安全**：窗口空时 `a0` 退化成当前角度
     *   ⇒ 落差恒为 0 ⇒ 绝不会误进「折叠判定」。
     * ⚠️ 之所以要单独钉住：真机上「手机摊平放很久、角度不变」正是这条路径的未来形态，
     *   谁要是后来把 `?: angle` 这个兜底删了，这条会红。
     */
    @Test
    fun identicalSampleIsNotReaddedWindowMayEmpty() {
        val s = flat(T0)
        val t = T0 + FoldJudge.A0_WINDOW_MS + 10L
        val v = judge(angle = a0, nowMs = t, st = s)     // 与旧样本**完全相同** ⇒ 去重不入窗
        assertTrue("旧样本过期 + 新样本被去重 ⇒ 窗口空", v.state.window.isEmpty())
        assertEquals("窗口空 ⇒ a0 退化成当前角度（兜底，不是 0）", a0, v.a0, 1e-4f)
        assertEquals("落差为 0 ⇒ 什么都不做", FoldJudge.Outcome.NOTHING, v.outcome)
    }

    // ================================================================ 常量自检

    /**
     * 阈值现在是**编译期常量**（校准已下线）⇒ 这里钉住 2026-10-06 用户口径那两个数。
     * ⚠️ 它们**不是**可调参数了：改这里就必须连带改 [SplitThresholds.DEFAULT]，
     *   而引擎按 [SplitThresholds.DEFAULT] 判 ⇒ 两边一旦不一致，本文件会红。
     */
    @Test
    fun frozenThresholdsMatchUserSpec() {
        // 「轻折一下、在 1 秒内展开才触发；超过 1 秒不触发」
        assertEquals("窗口必须是用户口径的「1 秒」", 1_000L, th.winMs)
        // 「折角超过 90 度都不触发」（相对量：比展开基线低 90°；本机全展 179° ⇒ 落到 89° 以下）
        assertEquals(90f, th.d3, 1e-4f)
        assertTrue("D2 必须明显小于 D1，否则「回到基线」这条判据会天天命中", th.d2 < th.d1)
        assertTrue("D1 必须小于 D3，否则「进入」还没成立就先被判成合上", th.d1 < th.d3)
    }

    /**
     * 窗口必须**容得下**真机上"折一下再展开"的完整来回（实测 1122ms 那次偏慢，
     * 但用户口径就是 1 秒 ⇒ 它必须刚好卡在这个量级附近，不能大到形同虚设）。
     */
    @Test
    fun windowStaysInSaneRange() {
        assertTrue("窗口不该小到连一次正常折放都装不下", th.winMs >= 600L)
        assertTrue("窗口不该大到把「停了很久」也放过去", th.winMs <= 3_000L)
    }

    // ================================================================ ★★★ 报障二（2026-10-06 第二轮）
    //     「轻折超过 1 秒还是会分屏」——
    //     1 秒必须从「**开始折**」算，而不是从「已经折掉 D1(20°)」算。

    /**
     * ★★★ **用户报的那一条**，逐样本复刻真机日志 05:24:51 的时序：
     * 慢慢折下去（每步 5°/150ms，折下段 1.05s），到 144° 再回升 20°。
     *
     * 真机上「折深 20° → 回升 20°」只有 620ms（⇒ 旧实现触发）；而**从开始折算**全程 > 1 秒。
     * ⛔ 这条用例**在旧实现下必红**（`foldStartMs` 只在 ENTER 时设 ⇒ 最后一步 `angle-minFold=20`
     *   命中回弹② ⇒ TRIGGER）。它就是本轮修法的守门人。
     */
    @Test
    fun slowFoldThenReboundMustNotTrigger() {
        val seen = mutableListOf<FoldJudge.Outcome>()
        var s = flat(T0)
        var t = T0
        // 慢慢折：179 → 144（每步 -5°、150ms）
        for (a in listOf(174f, 169f, 164f, 159f, 154f, 149f, 144f)) {
            t += 150L
            val v = judge(angle = a, nowMs = t, st = s)
            s = v.state
            seen += v.outcome
        }
        // 再回升 20°（144 → 164），同样是慢的
        for (a in listOf(154f, 164f)) {
            t += 150L
            val v = judge(angle = a, nowMs = t, st = s)
            s = v.state
            seen += v.outcome
        }
        assertFalse("慢慢折超过 1 秒 ⇒ 绝不许分屏", seen.contains(FoldJudge.Outcome.TRIGGER))
        assertTrue("必须由「超时」收场", seen.contains(FoldJudge.Outcome.CANCEL_TIMEOUT))
    }

    /**
     * 起点必须是「**开始折**」那一拍，而不是「折掉 D1」那一拍。
     * ★ 这是本轮修法的**最小判据**：只要它成立，上面那条的时间账就成立。
     */
    @Test
    fun foldTimerStartsWhenTheDropBegins() {
        val s0 = flat(T0)

        // ① 只掉 10°（不够 D1）⇒ 本次动作**开局**，但还没进入
        val t1 = T0 + 100L
        val v1 = judge(angle = 169f, nowMs = t1, st = s0)
        assertEquals(FoldJudge.Outcome.NOTHING, v1.outcome)
        assertEquals("开局时刻必须被记下（掉 10° > START_DROP 5°）", t1, v1.state.dropStartMs)

        // ② 继续折到 154°（掉 25° ≥ D1）⇒ 进入，起点**必须还是 t1**
        val t2 = T0 + 300L
        val v2 = judge(angle = 154f, nowMs = t2, st = v1.state)
        assertEquals(FoldJudge.Outcome.ENTER, v2.outcome)
        assertEquals("★ 计时起点必须是「开始折」那一拍，不是 ENTER 这一拍", t1, v2.state.foldStartMs)
    }

    /**
     * 反面校验：**别把起点提前到把正常动作也误杀**。
     * 快速轻折（全程 400ms：折下 200ms + 回升 200ms）必须仍然分屏。
     */
    @Test
    fun fastFoldStillTriggers() {
        val seen = mutableListOf<FoldJudge.Outcome>()
        var s = flat(T0)
        var t = T0
        for (a in listOf(165f, 150f, 160f, 170f)) {
            t += 100L
            val v = judge(angle = a, nowMs = t, st = s)
            s = v.state
            seen += v.outcome
        }
        assertTrue("快速轻折必须仍然触发（防假阴性：该分屏不分屏）",
            seen.contains(FoldJudge.Outcome.TRIGGER))
    }

    /**
     * ★★ **同源隐患的回归**（见 [FoldJudge] 类注释第三段）：
     * 「折到半开停住 ⇒ 超时闩住 ⇒ 接着把手机合上 ⇒ 再展开」这条路上，
     * 那条 179° 的旧基线也**必须**作废 —— 否则重新展开会被当成「轻折一下」。
     *
     * ⛔ 这条在「只在 CANCEL_CLOSE 里清窗口」的旧实现下必红：
     *   超时那一支**故意不清**窗口，而闩住后 `suppressed` 分支只等 `angle > openMin`、
     *   不会再去判一次"合上" ⇒ 179° 活到重新展开 ⇒ 152→158 时 `179-158=21 > D1` ⇒ 误 ENTER ⇒ 分屏。
     */
    @Test
    fun timeoutThenCloseThenReopenMustNotTrigger() {
        val seen = mutableListOf<FoldJudge.Outcome>()
        var s = flat(T0)
        var t = T0

        // ① 折到半开 90°（掉 89°，**还差一点**才够 D3=90）—— 刻意避开「要合上」
        for (a in listOf(160f, 120f, 90f)) {
            t += 200L
            val v = judge(angle = a, nowMs = t, st = s)
            s = v.state
            seen += v.outcome
        }
        // 停住（on-change 传感器静止不发样本）⇒ 超过窗口 ⇒ 超时闩住
        t += 1_200L
        run {
            val v = judge(angle = 90f, nowMs = t, st = s)
            s = v.state
            seen += v.outcome
        }
        assertTrue("半开停住 > 1 秒必须超时", seen.contains(FoldJudge.Outcome.CANCEL_TIMEOUT))
        assertTrue("超时必须闩住", s.suppressed)

        // ② 合上手机（掉到 5°）—— 此时已经闩住，suppressed 分支不会去判"合上"
        t += 200L
        run {
            val v = judge(angle = 5f, nowMs = t, st = s)
            s = v.state
            seen += v.outcome
        }
        assertTrue("★ 合上必须作废旧基线（哪怕状态机已经闩住）", s.window.isEmpty())

        // ③ 重新展开（这段正是真机上"合上再展开"的那条路）
        for (a in listOf(152f, 158f, 175f, 179f)) {
            t += 60L
            val v = judge(angle = a, nowMs = t, st = s)
            s = v.state
            seen += v.outcome
        }
        assertFalse("★ 超时→合上→重新展开，绝不许分屏", seen.contains(FoldJudge.Outcome.TRIGGER))
    }
}
