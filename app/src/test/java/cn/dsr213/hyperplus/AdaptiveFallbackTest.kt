package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1「自适应读不到环境 ⇒ 临时降级半自动」的状态机（[AdaptiveFallback]）。
 *
 * ============================ 为什么它值得单独一个测试文件 ============================
 * 这一功能**唯一的出错方式就是判错时机**，而它的两种错法都直接落在用户身上：
 *   - **判早了**（一次偶发就降级）⇒ 表现是"时灵时不灵"，用户以为功能坏了；
 *   - **判晚了 / 不恢复**（暗光下转三次手机没按钮；或走进亮处后一直不切回）
 *     ⇒ 表现是"这个功能根本没做出来"。
 * 而这两件事**在真机上极难复现**（要一间暗房 + 一台能跑 SystemUI 引擎的机器）。
 * 状态转移本来只是"几个数进、几个数出"，抽成纯函数（[AdaptiveFallback.step]）
 * 之后就能在这里把三种错法全部钉死。
 *
 * ⚠️ 另外钉死一条**安全不变式**：`isDowngradedAt` 必须在 `probeDueMs` **到点的那一刻**
 *   返回 false（= 放行一次自适应）。若有人把它写成 `<=` 的反面，降级就会变成**死锁**：
 *   引擎永远走半自动、永远不开相机、永远不知道环境已经恢复。
 *   而这条错法在真机上的表现恰好是"自适应莫名其妙不工作了"，很难反推到这一行。
 */
class AdaptiveFallbackTest {

    /** 一个随便挑的、远离 0 的起点，避免把"0 恰好等于默认值"误当成逻辑正确 */
    private val t0 = 1_000_000L

    // ================================================================ 基本盘

    @Test
    fun freshStateIsNotDowngraded() {
        val s = AdaptiveFallback.State()
        assertFalse("初始状态不该是降级", s.downgraded)
        assertEquals(0, s.missStreak)
        assertFalse(AdaptiveFallback.isDowngradedAt(s, t0))
    }

    // ================================================================ 进入

    @Test
    fun staysNormalBelowMissRounds() {
        var s = AdaptiveFallback.State()
        var kind = AdaptiveFallback.Kind.NONE
        for (i in 1 until R1_MISS_ROUNDS) {
            val (n, k) = AdaptiveFallback.step(s, t0 + i * 1_000L, hadFace = false)
            s = n
            kind = k
        }
        assertEquals("还没攒够轮数，不该降级", AdaptiveFallback.Kind.NONE, kind)
        assertFalse(s.downgraded)
        assertEquals("但轮数要一轮一轮攒着", R1_MISS_ROUNDS - 1, s.missStreak)
    }

    @Test
    fun entersAfterMissRoundsConsecutiveMisses() {
        var s = AdaptiveFallback.State()
        var kind = AdaptiveFallback.Kind.NONE
        var lastAt = t0
        repeat(R1_MISS_ROUNDS) { i ->
            lastAt = t0 + i * 1_000L
            val (n, k) = AdaptiveFallback.step(s, lastAt, hadFace = false)
            s = n
            kind = k
        }
        assertEquals("攒够 $R1_MISS_ROUNDS 轮就该降级", AdaptiveFallback.Kind.ENTER, kind)
        assertTrue(s.downgraded)
        assertEquals(R1_MISS_ROUNDS, s.missStreak)
        assertEquals("刚进降级时探测窗口要推到 $R1_PROBE_INTERVAL_MS 之后", lastAt + R1_PROBE_INTERVAL_MS, s.probeDueMs)
        assertTrue("此刻应当处于降级（窗口还没到）", AdaptiveFallback.isDowngradedAt(s, lastAt))
    }

    // ================================================================ 探测窗口

    @Test
    fun probeWindowAllowsExactlyOneRoundWhenDue() {
        val due = t0 + R1_PROBE_INTERVAL_MS
        val s = AdaptiveFallback.State(downgraded = true, probeDueMs = due)
        assertTrue("窗口内 ⇒ 仍按降级走", AdaptiveFallback.isDowngradedAt(s, t0))
        assertTrue("差 1ms 到点 ⇒ 仍是降级", AdaptiveFallback.isDowngradedAt(s, due - 1))
        assertFalse(
            "★ 到点那一刻必须放行一次 —— 这是「环境恢复就自动切回」唯一的手段，" +
                "写成 `now <= probeDueMs` 会让降级变成死锁",
            AdaptiveFallback.isDowngradedAt(s, due),
        )
    }

    @Test
    fun probeWindowIsRepushedOnEveryRoundWhileDowngraded() {
        // 窗口已到 ⇒ 这一轮就是"被放行"的那一次；它**失败**了
        val s = AdaptiveFallback.State(downgraded = true, probeDueMs = t0)
        val now = t0 + 5_000L
        val (n, k) = AdaptiveFallback.step(s, now, hadFace = false)
        assertEquals("已经在降级里，不该再报一次 ENTER", AdaptiveFallback.Kind.NONE, k)
        assertTrue("仍在降级", n.downgraded)
        assertEquals(
            "★ 无论成败都要重新计时 —— 不推的话窗口一直开着，降级从此再也不生效",
            now + R1_PROBE_INTERVAL_MS,
            n.probeDueMs,
        )
        assertTrue(AdaptiveFallback.isDowngradedAt(n, now + 1))
    }

    // ================================================================ 退出

    @Test
    fun exitOnFirstUsableFace() {
        val due = t0 + 60_000L
        val s = AdaptiveFallback.State(missStreak = R1_MISS_ROUNDS, downgraded = true, probeDueMs = due)
        val (n, k) = AdaptiveFallback.step(s, due, hadFace = true)
        assertEquals("★ 一轮成功就切回（不做「连续 M 轮」的滞回）", AdaptiveFallback.Kind.EXIT, k)
        assertFalse("退出后不再是降级", n.downgraded)
        assertEquals(0, n.missStreak)
        assertEquals(due + R1_PROBE_INTERVAL_MS, n.probeDueMs)
        assertEquals("退出时要起算冷却", due + R1_RECOVER_COOLDOWN_MS, n.recoverUntilMs)
    }

    @Test
    fun faceWithoutPriorDowngradeIsJustNone() {
        val s = AdaptiveFallback.State(missStreak = 1)
        val (n, k) = AdaptiveFallback.step(s, t0, hadFace = true)
        assertEquals("本来就没降级 ⇒ 无事发生", AdaptiveFallback.Kind.NONE, k)
        assertFalse(n.downgraded)
        assertEquals(0, n.missStreak)
    }

    /** 连着两轮都读到脸，状态应当稳定在"非降级"上（不该来回报 EXIT） */
    @Test
    fun repeatedSuccessIsIdempotent() {
        var s = AdaptiveFallback.State(downgraded = true, probeDueMs = t0)
        var exits = 0
        repeat(3) { i ->
            val (n, k) = AdaptiveFallback.step(s, t0 + i * 1_000L, hadFace = true)
            s = n
            if (k == AdaptiveFallback.Kind.EXIT) exits++
        }
        assertEquals("只有第一轮该报 EXIT", 1, exits)
        assertFalse(s.downgraded)
    }

    // ================================================================ 冷却（用户拍板"带滞回 + 冷却"）

    @Test
    fun cooldownBlocksReentryButKeepsStreak() {
        // 刚退出降级：冷却到 t0 + R1_RECOVER_COOLDOWN_MS
        var s = AdaptiveFallback.State(recoverUntilMs = t0 + R1_RECOVER_COOLDOWN_MS)
        var kind = AdaptiveFallback.Kind.NONE
        repeat(R1_MISS_ROUNDS + 2) { i ->
            val (n, k) = AdaptiveFallback.step(s, t0 + 1_000L + i * 100L, hadFace = false)
            s = n
            kind = k
        }
        assertEquals("冷却期内即使攒够也不该进降级", AdaptiveFallback.Kind.NONE, kind)
        assertFalse(s.downgraded)
        assertEquals(
            "★ 但轮数要照常攒着：冷却一过、下一次再失败就直接进，不必重新数两轮",
            R1_MISS_ROUNDS + 2,
            s.missStreak,
        )
    }

    @Test
    fun reentersOnceCooldownExpires() {
        val s = AdaptiveFallback.State(missStreak = R1_MISS_ROUNDS, recoverUntilMs = t0)
        val (n, k) = AdaptiveFallback.step(s, t0, hadFace = false)
        assertEquals("冷却到点 ⇒ 这次直接进（轮数已经攒够）", AdaptiveFallback.Kind.ENTER, k)
        assertTrue(n.downgraded)
    }

    // ================================================================ 常量之间的关系

    @Test
    fun constantsAreWithinSaneRange() {
        assertTrue(
            "至少 2 轮才降级 —— 1 轮等于「一次偶发就降」，表现是「时灵时不灵」",
            R1_MISS_ROUNDS >= 2,
        )
        assertTrue(
            "探测间隔不该短于 5 秒，否则暗光下几乎每次转动都白开一次相机，" +
                "降级的省电意义就没了",
            R1_PROBE_INTERVAL_MS >= 5_000L,
        )
        assertTrue(
            "冷却不该长于探测间隔 —— 否则「恢复了又立刻被挡回去」，用户看到的是按钮忽有忽无",
            R1_RECOVER_COOLDOWN_MS <= R1_PROBE_INTERVAL_MS,
        )
    }
}
