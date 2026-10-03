package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「目标方向 ↔ 要写进 `USER_ROTATION` 的值」这层换算的纯逻辑测试（[PanelOrientation]）。
 *
 * ============================ 本文件 2026-09-29 深夜被**整体反转** ============================
 * 上一版这里钉的是「内屏安装朝向 = 2 ⇒ 同一个目标在两块屏上要写**相差 180°** 的两个值」。
 * 那个前提**是错的**（成因见 [PanelOrientation] 类注释：把 `dumpsys display` 的
 * `installOrientation` 误当成 `USER_ROTATION` 的原点），而它正是用户报的
 * 「内屏展开翻转 180°」「半自动方向是反的」的总根源。
 *
 * ⇒ 现在钉的是它的**反面**：两块屏同值同义、偏置恒 0、换屏搬运就是**原值照搬**。
 *   第 1、3 两条是这个文件里最要紧的 —— 谁再把偏置加回来，它们立刻会红。
 *
 * ⚠️ 本文件**不测**任何需要 `Context` 的东西（[PanelOrientation.installOffset] /
 *   `handoffDelta` 要在真机上量形态，JVM 单测拿不到）。这里测的是**纯算式**，
 *   并通过 [PanelOrientation.offsetFor]（形态 → 偏移）把真机取值接进来。
 */
class PanelOrientationTest {

    // ⚠️ 这两个值的**唯一合法来源**是 `DisplayRotation.configure(width, height)` 的判据：
    //   「这块面板自己的宽高」—— 两块屏都是 `宽 < 高` ⇒ 两块的 `mPortraitRotation` 都是
    //   `ROTATION_0`、`mLandscapeRotation` 都是 `ROTATION_90` ⇒ **语义对称 ⇒ 偏置 0**。
    //   ⛔ 判据**不是** `installOrientation`（那正是踩过的坑，它由显示管线自己消费，
    //      框架的 `rotationForOrientation()` 全文不读它）。

    /** 真机面板：外屏 1168×1712（宽 < 高，天然竖屏）⇒ 偏置 0 */
    private val outerI = 0

    /** 真机面板：内屏 1672×2364（宽 < 高，天然竖屏）⇒ 偏置 0 */
    private val innerI = 0

    // ================================================================ ★ 最重要：两块屏同值同义

    /**
     * ★★ 全测里最要紧的一条。上一版这里断言的是"两屏必须相差 180°"，**方向是反的**。
     *
     * 判据在源码里：`DisplayRotation.configure()` 按**每块面板自己的宽高**决定旋转原点，
     * 两块屏都是"宽 < 高" ⇒ 两块的 `mPortraitRotation` / `mLandscapeRotation` 完全一样
     * ⇒ 同一个目标方向在两块屏上要写的值**必然相同**。
     *
     * 若有人给某块屏加回偏置，这条立刻红 —— 而它红的时候，真机上就是"屏幕转到相反方向去"。
     */
    @Test
    fun sameTargetMapsToTheSamePanelValueOnBothScreens() {
        (0..3).forEach { t ->
            val o = PanelOrientation.toPanel(t, outerI)
            val i = PanelOrientation.toPanel(t, innerI)
            assertEquals("目标 $t：两块屏写出的值必须相同（同值同义）", o, i)
        }
    }

    /**
     * ★★ 形态 → 偏置：两块屏**都是 0**。
     *
     * ⚠️ 但这条测试要守的**形式**没变，仍然重要：偏置必须**按形态查表**取，
     *   不能按 display id 取 —— 因为本机 `display 0` 跟着"当前在用的那块屏"走
     *   （用户原话，2026-09-29 定论），按 id 取迟早会取错屏。
     */
    @Test
    fun offsetIsZeroOnBothScreensButStillLookedUpByForm() {
        assertEquals("外屏偏置 0", 0, PanelOrientation.offsetFor(ScreenForm.OUTER))
        assertEquals(
            "内屏偏置也是 0 —— 曾经这里是 2，那是「内屏一切方向都反」的根",
            0, PanelOrientation.offsetFor(ScreenForm.INNER),
        )
    }

    /** 两块屏同值 ⇒ 搬运增量恒 0，且**与往哪边搬无关**（不存在符号歧义） */
    @Test
    fun handoffDeltaIsZeroBothDirections() {
        val toInner = PanelOrientation.normalize(
            PanelOrientation.offsetFor(ScreenForm.OUTER) - PanelOrientation.offsetFor(ScreenForm.INNER),
        )
        val toOuter = PanelOrientation.normalize(
            PanelOrientation.offsetFor(ScreenForm.INNER) - PanelOrientation.offsetFor(ScreenForm.OUTER),
        )
        assertEquals("折叠 → 展开", 0, toInner)
        assertEquals("展开 → 折叠", 0, toOuter)
    }

    // ================================================================ 换算本身

    @Test
    fun toPanelAndToDeviceAreInverses() {
        (0..3).forEach { off ->
            (0..3).forEach { t ->
                assertEquals(
                    "offset=$off t=$t：换算出去再读回来必须回到原值",
                    t,
                    PanelOrientation.toDevice(PanelOrientation.toPanel(t, off), off),
                )
            }
        }
    }

    /** 偏置 0 ⇒ 两向都是**恒等**。这条把"现在没有换算"写成断言，避免将来又长出静默的偏移 */
    @Test
    fun withZeroOffsetBothConversionsAreIdentity() {
        (0..3).forEach { t ->
            assertEquals(t, PanelOrientation.toPanel(t, 0))
            assertEquals(t, PanelOrientation.toDevice(t, 0))
        }
    }

    @Test
    fun normalizeHandlesNegativesAndOversizedInput() {
        assertEquals(0, PanelOrientation.normalize(0))
        assertEquals(2, PanelOrientation.normalize(2))
        assertEquals(3, PanelOrientation.normalize(-1))
        assertEquals(2, PanelOrientation.normalize(-2))
        assertEquals(1, PanelOrientation.normalize(5))
        assertEquals(3, PanelOrientation.normalize(99))
    }

    // ================================================================ 换屏搬运

    /**
     * ★★ 搬运要守住的那条不变量：**用户看到的方向不变**。
     *
     * 这正是用户 2026-09-28 原话里的要求 ——「不能让系统自己算一次方向 …… **应该根据外屏当前
     * 状态来定内屏状态**」。本机偏置 0 ⇒ 照搬就是**原值抄过去**。
     */
    @Test
    fun handoffPreservesVisibleDirectionAcrossScreens() {
        val delta = delta(outerI, innerI)
        (0..3).forEach { t ->
            val vOuter = PanelOrientation.toPanel(t, outerI)
            val vInner = PanelOrientation.normalize(vOuter + delta)
            assertEquals("目标 $t：搬完之后内屏看到的方向必须还是 $t", t, vInner)
            assertEquals(
                "换算回设备空间也必须还是 $t",
                t, PanelOrientation.toDevice(vInner, innerI),
            )
        }
    }

    /** 最常见的那一档：外屏竖屏(0) → 展开后内屏写的还是 0 */
    @Test
    fun handoffOuterPortraitStaysPortraitOnInner() {
        val vOld = 0
        val d = delta(outerI, innerI)
        assertEquals("两块屏同值 ⇒ 增量 0", 0, d)
        assertEquals("照搬 ⇒ 内屏仍写 0（用户视角仍是竖屏正立）", 0, PanelOrientation.normalize(vOld + d))
    }

    /** 横屏那档同样照搬（设备空间 1 = 设备逆时针转 90°） */
    @Test
    fun handoffOuterLandscapeStaysLandscapeOnInner() {
        val vOld = PanelOrientation.toPanel(1, outerI)
        val vNew = PanelOrientation.normalize(vOld + delta(outerI, innerI))
        assertEquals(1, PanelOrientation.toDevice(vNew, innerI))
    }

    /**
     * ★ 搬运算式是**相对**的（`旧值 + delta`），所以"多做一次"只会把方向推走一格 —— 偏置为 0
     *   时多搬一次**就等于没搬**（幂等）。
     *
     * 这条是上一版"多搬一次翻 180°"那条的**反面**，刻意留着：它说明偏置归零之后，
     * "被框架抢先写一次 ⇒ 我们多搬一次"这个风险**从根上消失了**。
     */
    @Test
    fun handoffAppliedTwiceIsIdempotent() {
        val d = delta(outerI, innerI)
        (0..3).forEach { t ->
            val once = PanelOrientation.normalize(PanelOrientation.toPanel(t, outerI) + d)
            val twice = PanelOrientation.normalize(once + d)
            assertEquals("目标 $t：搬一次 = $t", t, PanelOrientation.toDevice(once, innerI))
            assertEquals("再搬一次仍是 $t（幂等）", t, PanelOrientation.toDevice(twice, innerI))
        }
    }

    /** 来回折一次方向不能自己跑掉（对称性） */
    @Test
    fun handoffIsReversible() {
        val toInner = delta(outerI, innerI)
        val toOuter = delta(innerI, outerI)
        (0..3).forEach { t ->
            val v0 = PanelOrientation.toPanel(t, outerI)
            val v2 = PanelOrientation.normalize(
                PanelOrientation.normalize(v0 + toInner) + toOuter,
            )
            assertEquals("目标 $t：来回折一次必须回到原值", v0, v2)
        }
    }

    // ================================================================ 换屏搬运的锚点筛选

    /**
     * ★★ 「展开到内屏有概率翻转 180°」/「变成顺时针 90°」那个 bug 的守门测试。
     *
     * 根因：搬运的锚点是巡检**每 2 秒**采一次，而"折叠一下马上再展开"时折叠态停留常常**不到 2 秒**
     * ⇒ 那段时间一次都没采过，锚点还停在**上一块屏**的值上，却被当作"旧屏的锚点"用。
     * 偏置归零并不能治好它（照搬一个"另一块屏的值"同样是搬错了方向）。
     *
     * ⚠️ 这种错**从数值上看不出来** —— 另一块屏的值同样是合法的 0..3。
     *   所以判据必须是"**它来自哪块屏**"，不能只查值域。这条测试钉的就是这一点。
     */
    @Test
    fun handoffAnchorMustComeFromTheOldScreen() {
        // 展开（新屏 = 内屏 ⇒ 旧屏 = 外屏）：只有"采自外屏"的值能当锚点
        assertEquals(
            "采自外屏 ⇒ 可用",
            1, PanelOrientation.handoffAnchor(1, ScreenForm.OUTER, ScreenForm.INNER),
        )
        assertNull(
            "采自内屏的值绝不能当「旧屏=外屏」的锚点 —— 这就是那两个 bug 的成因",
            PanelOrientation.handoffAnchor(1, ScreenForm.INNER, ScreenForm.INNER),
        )
        // 折叠（新屏 = 外屏 ⇒ 旧屏 = 内屏）同理
        assertEquals(
            "采自内屏 ⇒ 可用",
            2, PanelOrientation.handoffAnchor(2, ScreenForm.INNER, ScreenForm.OUTER),
        )
        assertNull(
            "采自外屏的值不能当「旧屏=内屏」的锚点",
            PanelOrientation.handoffAnchor(2, ScreenForm.OUTER, ScreenForm.OUTER),
        )
    }

    @Test
    fun handoffAnchorRejectsUnsampledValues() {
        // 没采过（形态未知）⇒ 不能当锚点
        assertNull(
            "从未采过样 ⇒ 必须退回现读，而不是拿一个默认值去搬",
            PanelOrientation.handoffAnchor(0, null, ScreenForm.INNER),
        )
        // 哨兵 −1（"还没采到"，见 AdaptiveEngine.lastStablePanelRotation）⇒ 不能当锚点
        assertNull(
            "哨兵 −1 不能当锚点（形态对也不行）",
            PanelOrientation.handoffAnchor(-1, ScreenForm.OUTER, ScreenForm.INNER),
        )
        // 值域外的其它数（脏数据）⇒ 不能当锚点
        assertNull(PanelOrientation.handoffAnchor(4, ScreenForm.OUTER, ScreenForm.INNER))
        assertNull(PanelOrientation.handoffAnchor(-2, ScreenForm.OUTER, ScreenForm.INNER))
        // 边界：0 与 3 都是合法的锚点值（0 常被误当成"没采到"）
        assertEquals(
            "0 是合法锚点 —— 不能把它和哨兵 −1 混为一谈",
            0, PanelOrientation.handoffAnchor(0, ScreenForm.OUTER, ScreenForm.INNER),
        )
        assertEquals(3, PanelOrientation.handoffAnchor(3, ScreenForm.INNER, ScreenForm.OUTER))
    }

    /** 搬运算式的集中表达：`delta = (I_old - I_new) mod 4`，本机两块屏都是 0 ⇒ 恒 0 */
    private fun delta(from: Int, to: Int): Int = PanelOrientation.normalize(from - to)
}
