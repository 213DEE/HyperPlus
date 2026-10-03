package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮按钮内容几何的**纯算术**测试（2026-09-28 新增）。
 *
 * 起因是一次真错：倒计时环的圆心被算成"可见圆边长的一半"，而圆底/箭头算的是
 * "窗口边长的一半"。两者只在 `contentScale == 1` 时相等 ——
 * 也就是**内屏正常、外屏偏心**，而内屏恰好是开发时唯一会去看的那块屏。
 *
 * ⇒ 这里钉住的不变量只有一条，但它正是那个 bug 的反面：
 *   **倒计时环的圆心必须与窗口中心重合，且与 `contentScale` 无关。**
 *
 * ⚠️ 不覆盖的部分（诚实记录）：`RectF` 是否被正确赋值、`invalidate` 有没有触发、
 *    实际像素看上去是否同心 —— 那些只能在真机上看。本测试只保证"算出来的圆心对"。
 */
class HintRingGeometryTest {

    /**
     * 两档 `contentScale`：1（窗口 == 可见圆）与一个 ≠1 的档。
     *
     * ⚠️ 这里的 38/48 是**历史参数**：2026-09-28 外屏可见圆 38dp、窗口被 48dp 触摸下限
     *   顶大 ⇒ 比例 ≈ 0.7917。2026-09-29 改过尺寸策略之后（可见圆最小 54dp > 48dp），
     *   两块屏的 `contentScale` **都恒为 1**，这个非 1 的档在真机上暂时不会出现。
     *
     * 保留它的理由：本测试要钉住的不变量是"**圆心与 `contentScale` 无关**"——
     * 那就必须喂一个 ≠1 的比例，否则 `viewSide * scale` 与 `viewSide` 的差别根本暴露不出来，
     * 测试会退化成"只验证 1 == 1"。参数随时可能再变回"窗口 > 可见圆"（见 `HintSizePolicy`）。
     */
    private val innerScale = 1f
    private val outerScale = 38f / 48f

    /** `RotateHintOverlay.RING_R` */
    private val ringR = 0.345f

    /**
     * ★ 核心不变量：环心 == 窗口中心，**任意 `contentScale` 下都成立**。
     *
     * 这正是旧代码违背的那一条：它算出的圆心是 `viewSide * scale / 2`，
     * 在外屏上会比窗口中心小 `0.105 × viewSide`（132px 窗口上约 14px）—— 肉眼就是"环偏了"。
     */
    @Test
    fun ringCenterAlwaysEqualsViewCenter() {
        for (side in listOf(132f, 148f, 2364f)) {
            val c = HintRingGeometry.viewCenter(side)
            assertEquals(side / 2f, c, 0f)
            // 旧写法（错的）在这两档下的差值 —— 外屏必须非零，否则本测试等于没测
            val oldInner = side * innerScale * 0.5f
            val oldOuter = side * outerScale * 0.5f
            assertEquals(c, oldInner, 0.001f)          // 内屏：新旧一致 ⇒ 所以当初看不出来
            assertTrue(c - oldOuter > 1f)              // 外屏：旧写法确实偏了（且偏得不小）
        }
    }

    /**
     * 半径只跟**可见圆**有关：窗口变大而比例不变时，半径同比放大。
     *
     * ⚠️ 这条是"防手滑"而非"防偏心"：半径的算法从一开始就是对的，
     *   加它是因为**改圆心时最容易顺手把半径也改了**（两者在同一行里）。
     */
    @Test
    fun ringRadiusScalesWithVisibleCircleNotWithView() {
        val view = 148f
        val r = HintRingGeometry.ringRadius(view, outerScale, ringR)
        // = 可见圆边长(38/48×148 ≈ 117) × 0.345
        assertEquals(view * outerScale * ringR, r, 0.001f)
        // 若误按窗口边长算，半径会大约 26% —— 必须能区分开
        assertTrue(HintRingGeometry.ringRadius(view, innerScale, ringR) > r)
    }

    /** 环必须**整个落在**圆底里面 —— 几何比例的硬约束，顺手在这里一起钉住 */
    @Test
    fun ringFitsInsideBaseCircle() {
        val view = 148f
        val outer = HintRingGeometry.ringRadius(view, outerScale, ringR) + view * outerScale * 0.046f / 2f
        val base = view * outerScale * 0.45f
        assertTrue("环外缘 $outer 必须小于圆底 $base", outer < base)
    }
}
