package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/**
 * 形态判据的**根基**：两块屏的实测 dp 必须分别落在阈值两侧。
 *
 * ============================ 为什么值得单独一个测试文件 ============================
 * 这一条判据是「内外屏模式解耦」「内外屏白名单解耦」「外屏桌面豁免」等一整串功能的
 * **共同前提**。而它**出过一次全军覆没的事故**：阈值曾写作 360dp（按错误密度 4.088 推的），
 * 而本机两块屏的真实 dp 是 608 / 425 —— **两个都在 360 之上** ⇒ 判定恒为
 * [ScreenForm.INNER] ⇒ "内外屏解耦"实际从来没生效过（外屏那份配置永远是死的）。
 * 那次是**人肉读日志**才发现的，代价很大。
 *
 * 所以这里钉的不是"某个函数返回什么"，而是**几条谁都不能碰坏的事实**：
 *   1. 量法：1672px ÷ 2.75 = **608dp**（内屏）、1168px ÷ 2.75 = **425dp**（外屏）；
 *   2. 两块屏**必须判出不同形态**（这条就是上面那次事故的反面）；
 *   3. 边界语义（`>=` 阈值算内屏）定死；
 *   4. 旋转**不改变**判定 —— 取最小边正是为了这个（横过来取宽会让外屏跑到阈值上面去）。
 *
 * ⚠️ 这里的数字（1672 / 2364 / 1168 / 1712 / 2.75）是**本机 `wm size` + `wm density`
 *   实测值**，不是推的。换机型时这些断言要跟着换 —— 那正是提醒你"形态判据得重核"的地方。
 */
class ScreenFormTest {

    /** 本机实测（440dpi ⇒ 2.75x） */
    private val density = 2.75f
    private val innerPx = 1672 to 2364
    private val outerPx = 1168 to 1712

    private fun dpOf(px: Pair<Int, Int>): Int =
        DisplaySize.Measured(min(px.first, px.second), density, "test").smallestWidthDp

    // ---------------------------------------------------------------- ① 量法

    @Test
    fun innerScreenMeasures608dp() {
        assertEquals(608, dpOf(innerPx))
    }

    @Test
    fun outerScreenMeasures425dp() {
        assertEquals(425, dpOf(outerPx))
    }

    // ---------------------------------------------------------------- ② 核心不变量

    @Test
    fun theTwoScreensLandOnOppositeSidesOfTheThreshold() {
        // ★ 这条是整个判据存在的意义 —— 两块屏必须能分出彼此。
        //   2026-09-28 那次事故（阈值 360dp）就是死在这一条上，而当时没有任何测试拦它。
        val inner = ScreenForm.ofSmallestWidth(dpOf(innerPx))
        val outer = ScreenForm.ofSmallestWidth(dpOf(outerPx))
        assertEquals(ScreenForm.INNER, inner)
        assertEquals(ScreenForm.OUTER, outer)
        assertNotEquals(inner, outer)
    }

    // ---------------------------------------------------------------- ③ 边界语义

    @Test
    fun thresholdItselfCountsAsInner() {
        assertEquals(ScreenForm.INNER, ScreenForm.ofSmallestWidth(ScreenForm.INNER_MIN_WIDTH_DP))
    }

    @Test
    fun oneDpBelowThresholdCountsAsOuter() {
        assertEquals(
            ScreenForm.OUTER,
            ScreenForm.ofSmallestWidth(ScreenForm.INNER_MIN_WIDTH_DP - 1),
        )
    }

    // ---------------------------------------------------------------- ④ 旋转不变量

    @Test
    fun rotationDoesNotChangeTheJudgement() {
        // 内屏横过来是 2364×1672、外屏横过来是 1712×1168。
        // 取 min(宽, 高) ⇒ 两种朝向得到**同一个** dp 值。
        assertEquals(dpOf(innerPx), dpOf(innerPx.second to innerPx.first))
        assertEquals(dpOf(outerPx), dpOf(outerPx.second to outerPx.first))
        // 反面：把量法换成"取宽"（这里直接把长边当最小边喂进去模拟），外屏横过来是 623dp
        // ⇒ 跑到阈值 512 之上 ⇒ 会被误判成内屏。这就是"必须取最小边"的理由。
        val outerLandscapeAsIfWidth = DisplaySize.Measured(1712, density, "取宽（错误量法）")
            .smallestWidthDp
        assertEquals(623, outerLandscapeAsIfWidth)
        assertTrue(
            "横屏取宽会把外屏推到阈值之上 ⇒ 量法必须取最小边",
            outerLandscapeAsIfWidth > ScreenForm.INNER_MIN_WIDTH_DP,
        )
    }

    // ---------------------------------------------------------------- ⑥ 按屏认形态（2026-09-29）

    @Test
    fun eachPhysicalScreenMapsToItsOwnForm() {
        // 引擎"按形态挑出当前活动屏"就靠这条 —— 它替代了以前一律读 `DEFAULT_DISPLAY` 的写法。
        // 理由：**那个 id 绑的是哪块屏，本机至今说不准**（两种互相矛盾的记录见
        // [ActiveDisplay] 的类注释）⇒ 不赌 id，按尺寸认。
        // 换屏那一刻的方向 bug（用户 2026-09-29 两次报的 180° / 90°）就出在这条链路上。
        assertEquals(ScreenForm.INNER, ScreenForm.ofPhysicalMinSide(1672, 440))
        assertEquals(ScreenForm.OUTER, ScreenForm.ofPhysicalMinSide(1168, 440))
    }

    @Test
    fun physicalMinSideKeepsEachScreenOnItsOwnSide() {
        // `Display.getMode()` 给的是**物理**分辨率，不随旋转交换 ⇒ 输入天然是旋转不变量。
        // ⚠️ 反面：误把**长边**当最小边喂进来 —— 外屏 1712px 会算成 623dp ⇒ 判成内屏、认错屏。
        //    所以"取 min"这一步必须发生在**调用点**（引擎那边就是这样），不能省。
        assertEquals(ScreenForm.INNER, ScreenForm.ofPhysicalMinSide(1712, 440))
        assertEquals(ScreenForm.OUTER, ScreenForm.ofPhysicalMinSide(1168, 440))
    }

    @Test
    fun illegalPhysicalSizeYieldsNullInsteadOfAGuess() {
        // "认不出"必须是 null —— 让调用方去兜底（引擎那边退 USER_ROTATION、再退 DEFAULT_DISPLAY）。
        // 这里若顺手返回一个 INNER，就成了"静默读错屏"，正是本次要修掉的那类错。
        assertNull(ScreenForm.ofPhysicalMinSide(0, 440))
        assertNull(ScreenForm.ofPhysicalMinSide(-1, 440))
        assertNull(ScreenForm.ofPhysicalMinSide(1672, 0))
    }

    // ---------------------------------------------------------------- ⑤ 余量

    @Test
    fun bothScreensKeepMarginFromThreshold() {
        // 阈值两侧各留余量，是为了容忍"用户改显示大小/DPI"带来的整体缩放。
        // 余量如果被削到 0（阈值贴着某一侧的实测值），下一次改动就会踩线串档。
        val threshold = ScreenForm.INNER_MIN_WIDTH_DP
        val innerMargin = (dpOf(innerPx) - threshold).toFloat() / dpOf(innerPx)
        val outerMargin = (threshold - dpOf(outerPx)).toFloat() / dpOf(outerPx)
        assertTrue("内屏侧余量太小：$innerMargin", innerMargin > 0.15f)
        assertTrue("外屏侧余量太小：$outerMargin", outerMargin > 0.15f)
    }
}
