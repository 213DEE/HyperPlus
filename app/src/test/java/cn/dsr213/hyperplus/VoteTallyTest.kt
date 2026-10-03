package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VoteTally] 的边界测试。
 *
 * 这些用例对应的都是"真机上很难复现、但一旦错了用户立刻能感觉到"的场景：
 * 平票乱动一格、样本太少就下结论、转弯途中死等多数而迟钝。
 */
class VoteTallyTest {

    /** 干净多数：12 票里 9 票投给 3 —— 应当定论，方向取 3 */
    @Test
    fun clearMajorityDecides() {
        val t = VoteTally()
        repeat(9) { t.add(3) }
        repeat(2) { t.add(0) }
        t.add(1)

        assertTrue("9/12 应定论", t.confident)
        assertEquals(3, t.winner)
        assertEquals(12, t.total)
        assertEquals("3:9 0:2 1:1 的票面文本", "0:2 1:1 3:9", t.text())
        assertEquals(0.75f, t.ratio(), 0.0001f)
    }

    /** 平票：2:2 —— 绝不能定论（这一轮看不准，动就是赌） */
    @Test
    fun tieDoesNotDecide() {
        val t = VoteTally()
        repeat(2) { t.add(1) }
        repeat(2) { t.add(3) }

        assertFalse("平票不许定论", t.confident)
        // winner 仍是"票数最多的那个"，平票时取先达到最大值的格子（1）
        assertEquals(1, t.winner)
        assertEquals(4, t.total)
    }

    /**
     * 样本太少：连投 2 张同一个方向 —— 仍然不许定论（低于 minVotes=3）。
     *
     * ★ 门槛的取值史见 VoteTally 类注释（4 → 6 → 3）。这里守的是**边界本身**：
     *   少一票不许判、够一票立刻判。至于"3 是不是太低"，那是上限问题，不归这条管。
     */
    @Test
    fun tooFewVotesDoesNotDecide() {
        val t = VoteTally()
        repeat(2) { t.add(2) }

        assertFalse("只有 2 票，样本不足，不许定论", t.confident)
        assertEquals(2, t.winner)

        // 第 3 票补上 → 立刻定论（minVotes 边界）
        t.add(2)
        assertTrue("第 3 票起应定论", t.confident)
        assertEquals(2, t.winner)
    }

    /** 转弯途中：5:4 —— 票面已分裂且获胜方没攒够 6 票，继续攒，别乱动 */
    @Test
    fun turningSplitIsNotConfidentAtFiveToFour() {
        val t = VoteTally()
        repeat(5) { t.add(3) }
        repeat(4) { t.add(0) }

        assertEquals(9, t.total)
        assertEquals(3, t.winner)
        // 5/9 ≈ 55.6% < 60%、也未达 2 倍领先，且获胜方只有 5 票（< 6）⇒ 不定论
        assertFalse("票面分裂，应继续攒票", t.confident)
    }

    /** 转弯途中：6:2 —— 6/8 = 75% ≥ 60% ⇒ 定论（不必等到 16 帧采完） */
    @Test
    fun turningWithClearLeadDecidesEarly() {
        val t = VoteTally()
        repeat(6) { t.add(3) }
        repeat(2) { t.add(0) }

        assertTrue("75% 多数应定论", t.confident)
        assertEquals(3, t.winner)
        assertEquals(8, t.total)
    }

    /**
     * 只有「明显领先」这条判据成立的情形：6:3:3。
     *
     * sum=12、6/12=50% < 60% ⇒ 绝对多数**不**成立；
     * 但 6 ≥ 2×3 ⇒ 明显领先成立 ⇒ 应定论。
     * 这个用例专门守住判据 ② —— 少了它，"转弯途中"会被死等的 60% 卡住而迟钝。
     */
    @Test
    fun dominantLeadDecidesWithoutMajority() {
        val t = VoteTally()
        repeat(6) { t.add(0) }
        repeat(3) { t.add(1) }
        repeat(3) { t.add(3) }

        assertEquals(12, t.total)
        assertEquals(0, t.winner)
        assertEquals("6/12 = 50%，绝对多数这条不成立", 0.5f, t.ratio(), 0.0001f)
        assertTrue("但 6 ≥ 2×3，靠明显领先应定论", t.confident)
    }

    /** 非法方向必须被忽略，不能污染票数 */
    @Test
    fun illegalSectorIgnored() {
        val t = VoteTally()
        t.add(-1)
        t.add(4)
        t.add(99)

        assertEquals(0, t.total)
        assertEquals(-1, t.winner)
        assertFalse(t.confident)
        assertEquals("", t.text())
        assertEquals(0f, t.ratio(), 0.0001f)
    }

    /** reset 必须清干净 —— 否则上一轮的票会让旧方向一直赢（引擎里每轮都会调） */
    @Test
    fun resetClearsEverything() {
        val t = VoteTally()
        repeat(9) { t.add(3) }
        assertTrue(t.confident)

        t.reset()
        assertEquals(0, t.total)
        assertEquals(-1, t.winner)
        assertEquals(0, t.leaderVotes)
        assertFalse(t.confident)
        assertEquals("", t.text())

        // 重置后重新攒票，行为与全新实例一致
        repeat(6) { t.add(1) }
        assertTrue(t.confident)
        assertEquals(1, t.winner)
    }
}
