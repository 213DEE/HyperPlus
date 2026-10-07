package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折角触发阈值 [SplitThresholds] 的**冻结值**锁定（2026-10-06 重写）。
 *
 * ============================ 这个文件为什么从 180 行变成 40 行 ============================
 * 它原来测的是 `fromDemo` / `decode` / `summary` / `trim` 四个**校准专用**的函数
 * （从"用户演示的那一折"反推一组候选阈值、落盘解析、界面摘要格式）。
 * 用户 2026-10-06 原话：「**把角度校准功能删掉，不给这么多自定义功能，越多越难做**」
 * ⇒ 那四个函数连同整条校准链一起删除，本文件改为只钉**常量本身**。
 *
 * ★ 留下的价值没变：阈值是引擎判定的**全部输入量**，它错一位的后果是
 *   "折一下没反应"或"屏幕当着用户的面裂开"。所以哪怕它现在是常量，也要有东西盯着。
 * ⛔ 别再把"从演示反推阈值"那套加回来（已否一次）；要调阈值 = 改 [SplitThresholds.DEFAULT]。
 */
class SplitThresholdsTest {

    private val d = SplitThresholds.DEFAULT

    /** 用户 2026-10-06 的两条口径 —— 这条是**验收眼**，改了它就意味着改了产品行为。 */
    @Test
    fun frozenValuesMatchUserSpec() {
        // 「轻折一下、在 1 秒内展开才触发；轻折一下、但是超过 1 秒不触发」
        assertEquals("窗口 = 用户口径的「1 秒」", 1_000L, d.winMs)
        // 「或者折角超过 90 度都不触发」
        assertEquals("合上判定角 = 用户口径的「90 度」", 90f, d.d3, 1e-4f)
    }

    /** 2026-10-04 本机定标出来的那两个数没有被顺手改掉。 */
    @Test
    fun calibratedValuesArePreserved() {
        assertEquals(20f, d.d1, 1e-4f)
        assertEquals(6f, d.d2, 1e-4f)
    }

    /**
     * 四个数之间的**关系**不变式（比具体数值更能说明"它还是一组能用的阈值"）。
     *
     * ★ 为什么要钉关系而不是只钉数值：将来有人只改一个数（比如把 D3 调到 15），
     *   数值断言他会一起改，但这几条不变式会立刻红 —— 那才是真正会伤到用户的那种改动。
     */
    @Test
    fun invariantsHold() {
        assertTrue("D2 < D1：否则「回到基线附近」会天天命中，等于没有进入判据", d.d2 < d.d1)
        assertTrue("D1 < D3：否则「进入」还没成立就先被判成「要合上」", d.d1 < d.d3)
        assertTrue("D3 必须明显低于半开位（实测 89~92°）才拦得住真·合上", d.d3 in 85f..120f)
        assertTrue("窗口必须是正数", d.winMs > 0L)
    }

    /** 日志那行 `判定阈值：20.0|6.0|90.0|1000` 靠它 —— 格式变了会误导排查。 */
    @Test
    fun encodeIsStable() {
        assertEquals("20.0|6.0|90.0|1000", d.encode())
    }
}
