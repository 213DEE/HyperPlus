package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExtraPicker] 的规则测试 —— 直接对应「竖屏切横屏有概率变成相反的横屏」这个实测 bug。
 *
 * 用例里的角度都是可算的：人脸在基准图里转 100° 时，最优 extra 是 270（摆到约 10°），
 * 而次优的 180 会把脸摆到约 80° —— 后者正是"勉强认出但会把角度还原错 180°"的那一个。
 */
class ExtraPickerTest {

    /** 稳态：热点角一次命中且脸是正的 ⇒ 立刻采纳，不再试别的（保证功耗不涨） */
    @Test
    fun uprightHitAcceptedImmediately() {
        val p = ExtraPicker<Unit>()
        assertTrue("摆到 5° 就该停", p.record(270, 5f, Unit))
        assertEquals(270, p.result()?.extra)
    }

    /** 45° 正好在阈值上 ⇒ 仍然采纳（阈值是"不超过"） */
    @Test
    fun exactlyTrustDegIsAccepted() {
        val p = ExtraPicker<Unit>()
        assertTrue(p.record(90, 45f, Unit))
        assertTrue(p.record(90, -45f, Unit))
    }

    /** 46° 超出阈值 ⇒ 不许停，继续试下一个角度 */
    @Test
    fun slightlyOverTrustDegKeepsSearching() {
        val p = ExtraPicker<Unit>()
        assertFalse("46° 属于勉强认出，必须继续找更正的", p.record(180, 46f, Unit))
        assertFalse("80° 更加不能停", p.record(180, 80f, Unit))
        assertTrue("试到 10° 才停", p.record(270, 10f, Unit))
        assertEquals("应当采纳最正的那个 270", 270, p.result()?.extra)
    }

    /**
     * 这次 bug 的现场：热点角 180 勉强认出（保脸歪 80°），
     * 正确角 270 能把脸摆到 10°。
     *
     * ★ 修复前（第一个检出就停）会采纳 180 ⇒ 角度整体错 180° ⇒ 屏幕切到相反的横屏。
     *   修复后必须采纳 270。
     */
    @Test
    fun suboptimalHitIsNotAdopted() {
        val p = ExtraPicker<Unit>()
        // 热点角先试到、且"检出成功"，但它把脸摆到 80° —— 不能采纳
        assertFalse(p.record(180, 80f, Unit))
        // 继续试：270 把脸摆到 10°
        assertTrue(p.record(270, 10f, Unit))
        assertEquals("必须采纳把脸摆得最正的 270，而不是先检出的 180", 270, p.result()?.extra)
    }

    /** 所有候选都只能勉强认出 ⇒ 退而取倾斜最小的（总比没有好），并如实带出它的倾斜值 */
    @Test
    fun fallsBackToLeastTiltedWhenNoneIsTrustworthy() {
        val p = ExtraPicker<Unit>()
        assertFalse(p.record(0, 70f, Unit))
        assertFalse(p.record(90, 60f, Unit))
        assertFalse(p.record(180, 52f, Unit))
        assertFalse(p.record(270, 65f, Unit))

        assertEquals("四个都不够正时取最小倾斜的 180", 180, p.result()?.extra)
        assertEquals(52f, p.result()?.apparentTiltDeg)
    }

    /** 一个都没命中 ⇒ 没有结果（上层按"这帧没人脸"处理，不许拿空值当方向） */
    @Test
    fun noHitYieldsNull() {
        val p = ExtraPicker<Unit>()
        assertNull(p.result())
    }

    /** NaN 视为最差：不许因为"测不出来"而把某个候选当成最优 */
    @Test
    fun nanTiltIsWorst() {
        val p = ExtraPicker<Unit>()
        assertFalse(p.record(0, Float.NaN, Unit))
        assertFalse(p.record(90, 60f, Unit))
        assertEquals("60° 虽然也不够正，但比 NaN 强", 90, p.result()?.extra)

        // 反过来：先记 NaN，再记一个真值 → 真值胜出
        val q = ExtraPicker<Unit>()
        assertFalse(q.record(0, Float.NaN, Unit))
        assertFalse(q.record(180, 80f, Unit))
        assertEquals(180, q.result()?.extra)
    }

    /** 更小倾斜才替换；相等时保留先试到的（顺序即热点优先，尽量少换角） */
    @Test
    fun onlySmallerTiltReplaces() {
        val p = ExtraPicker<Unit>()
        p.record(10, 30f, Unit)
        p.record(20, 40f, Unit)
        assertEquals("40 > 30，不该替换", 10, p.result()?.extra)

        p.record(30, 30f, Unit)
        assertEquals("相等也不替换", 10, p.result()?.extra)

        p.record(40, 29f, Unit)
        assertEquals("更小才替换", 40, p.result()?.extra)
    }

    /** payload 必须跟着命中的那个角度走（图像尺寸随角度变，不能张冠李戴） */
    @Test
    fun payloadFollowsTheAdoptedHit() {
        val p = ExtraPicker<String>()
        p.record(180, 80f, "payload-of-180")
        p.record(270, 10f, "payload-of-270")
        assertEquals("payload-of-270", p.result()?.payload)

        // 回退路径同样要带对
        val q = ExtraPicker<String>()
        q.record(90, 60f, "payload-of-90")
        assertEquals("payload-of-90", q.result()?.payload)
    }
}
