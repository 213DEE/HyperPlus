package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旋转按钮**尺寸策略**的纯算术测试（2026-09-29 新增）。
 *
 * 为什么值得单独钉：这套算术**只有把两块屏同时代入才看得出对错**。
 * 2026-09-28 出过一次错 —— 密度写错 ⇒ 比例偏大 ⇒ 内屏撞上限、外屏也撞同一个上限，
 * 两块屏被夹成同一个数，"按屏宽自适应"静默失效；而当时无论看日志还是看内屏都完全正常，
 * 只有把外屏也代进去才会发现"外屏占屏宽 12.7%、内屏 8.9%"。
 *
 * ⇒ 这里的原则：**输入用真机实测宽度，期望值写死**。这样改常量时测试会直接报出
 *   "两块屏各自变成了多大"，逼人确认那是想要的结果，而不是让注释和代码各自漂移。
 *
 * 本机两档（`wm density` = 440dpi ⇒ 2.75x）：
 * | 屏 | 像素最小边 | 最小宽度 |
 * |---|---|---|
 * | 内屏 | 1672px | 608dp |
 * | 外屏 | 1168px | 425dp |
 *
 * ⚠️ 不覆盖的部分（诚实记录）：px 换算（`density`）、窗口能不能挂上去、真机上看着多大
 *   —— 那些只能在设备上看。本测试只保证"dp 算对了"。
 */
class HintSizePolicyTest {

    /** 本机内屏最小宽度（1672 ÷ 2.75 = 608dp） */
    private val innerWidthDp = 608

    /** 本机外屏最小宽度（1168 ÷ 2.75 ≈ 425dp） */
    private val outerWidthDp = 425

    /**
     * ★ 核心要求（用户 2026-09-29 原话）：「把外屏按钮加大到**现在内屏的尺寸**」。
     *   "现在内屏的尺寸" = 上一版的 54dp —— 这个 54 **写死**在这里，它就是要实现的东西本身。
     */
    @Test
    fun outerScreenButtonEqualsPreviousInnerSize() {
        assertEquals(54f, HintSizePolicy.visualDp(outerWidthDp), 0f)
    }

    /** ★ 另一半要求：「把**内屏**按钮也加大一点」—— 上一版是 54dp，现在必须是 62dp */
    @Test
    fun innerScreenButtonGotBigger() {
        val inner = HintSizePolicy.visualDp(innerWidthDp)
        assertTrue("内屏应大于上一版的 54dp，实际 $inner", inner > 54f)
        assertEquals(62f, inner, 0f)
    }

    /**
     * 两块屏的差必须**收窄**：上一版是 54 / 38（差 16dp），现在应为 62 / 54（差 8dp）。
     *
     * ⚠️ 这条是"防退化"的：如果哪天把上限调低到接近下限，两屏会被夹成同一个值，
     *   差值算出来是 0 —— 本测试会失败，提醒"自适应已经名存实亡"。
     */
    @Test
    fun twoScreensAreCloserThanBefore() {
        val inner = HintSizePolicy.visualDp(innerWidthDp)
        val outer = HintSizePolicy.visualDp(outerWidthDp)
        assertEquals(8f, inner - outer, 0f)
        assertTrue("外屏不应大于内屏", outer <= inner)
    }

    /** 下限必须严格小于上限 —— 否则 `coerceIn` 会把两屏都夹到同一个值（自适应失效） */
    @Test
    fun lowerBoundStaysStrictlyBelowUpperBound() {
        assertTrue(
            "下限 ${HintSizePolicy.VISUAL_DP_MIN} 必须小于上限 ${HintSizePolicy.VISUAL_DP_MAX}",
            HintSizePolicy.VISUAL_DP_MIN < HintSizePolicy.VISUAL_DP_MAX,
        )
    }

    /**
     * 触摸目标下限：**任何**屏宽下窗口都不能小于 48dp（Material / 无障碍推荐值）。
     *
     * ★ 这条是安全网：当前参数下（可见圆最小 54dp）它用不上，但将来若有人把下限调到
     *   48 以下，本测试会立刻报出来 —— 那时候就该重新想"视觉小、触摸大"怎么共存了。
     */
    @Test
    fun touchTargetNeverBelowMinimum() {
        for (w in listOf(320, 360, 392, 425, 480, 608, 800, 1280)) {
            val visual = HintSizePolicy.visualDp(w)
            val view = HintSizePolicy.viewDp(visual)
            assertTrue("屏宽 ${w}dp 时可见圆 $visual 小于 48dp 触摸下限", visual >= 48f)
            assertTrue("屏宽 ${w}dp 时窗口 $view 小于 48dp 触摸下限", view >= HintSizePolicy.TOUCH_DP_MIN)
        }
    }

    /** 比例与上限必须自洽：`参考屏宽 × 比例 == 上限`（否则内屏撞不到上限，算式与注释不符） */
    @Test
    fun ratioMatchesMaxOnReferenceWidth() {
        val byRatio = HintSizePolicy.REF_SCREEN_WIDTH_DP * HintSizePolicy.VISUAL_W_RATIO
        assertEquals(HintSizePolicy.VISUAL_DP_MAX.toFloat(), byRatio, 0.001f)
        // 且参考屏宽下的最终结果确实取到上限
        assertEquals(HintSizePolicy.VISUAL_DP_MAX.toFloat(), HintSizePolicy.visualDp(608), 0f)
    }

    /** 单调性：屏越宽按钮越大（同一比例下不该出现反向），且**外屏是被下限夹住的固定值** */
    @Test
    fun sizeIsNonDecreasingWithScreenWidth() {
        var prev = 0f
        for (w in 300..900 step 50) {
            val v = HintSizePolicy.visualDp(w)
            assertTrue("屏宽 ${w}dp 的结果 $v 比上一档 $prev 更小", v >= prev)
            prev = v
        }
        // 夹住的事实：外屏附近两档算出来一样 ⇒ "外屏尺寸不随屏宽变化"是有意为之
        assertEquals(
            HintSizePolicy.visualDp(outerWidthDp),
            HintSizePolicy.visualDp(380),
            0f,
        )
    }
}
