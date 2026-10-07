package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高温保护的**取值边界**（[AppPrefs.clampThermalLimitC] 与那三个常量）。
 *
 * ============================ 为什么值得单独一个测试文件 ============================
 * 这三个数不是随便定的，每一个都有它**挡着什么**：
 *   - [AppPrefs.THERMAL_LIMIT_C_DEFAULT] = 47 必须**逐字等于系统的出厂阈值**
 *     （`MultiTaskingTemperatureObserver.HIGH_TEMPERATURE = 47`）——
 *     它同时是界面上「恢复默认」那个按钮写进去的值。差 1 度，用户点"恢复默认"
 *     就**回不到出厂行为**了，而且没有任何日志会提示这件事。
 *   - [AppPrefs.THERMAL_LIMIT_C_MAX] = 60 是**用户在需求里点名的硬顶**
 *     （原话「禁止超过60度」）⇒ 它是产品需求，不是我们调出来的手感值。
 *     谁放松它，就是在替用户改需求。
 *   - [AppPrefs.THERMAL_LIMIT_C_MIN] = 40 必须**明显高于**任何日常板温
 *     （本机实测 36°C —— 见 `docs/分屏增强_实现方案_2026-10-04.md` §14.2.1），
 *     否则"调到下限"会让高温判定**永不触发**，等于一条**不经过二次确认弹窗**的
 *     关保护路径 —— 而关掉保护该走它自己的开关
 *     （`split_thermal_guard_off`，带二次确认弹窗）。
 *     ⚠️ 这个数**正是被本文件那条测试改掉的**（原为 30）：当时它红了，见下面
 *     [minIsAboveIdleBoardTemperature]。
 *
 * ⚠️ 还有一条**顺序不变式**：`MIN < DEFAULT < MAX`。它看着像凑数，
 *   但 [clampThermalLimitC] 的三段夹取依赖它 —— 三者一旦相等或倒序，
 *   夹取会静默退化成一个常数（用户改什么都没用），而**不会有任何报错**。
 */
class ThermalLimitTest {

    @Test
    fun defaultEqualsFactoryThreshold() {
        // 🔴 47 是**照抄**系统的 `HIGH_TEMPERATURE`，不是我们挑的。
        //    改动它之前先去看那个常量 —— 两边必须一起动。
        assertEquals("默认值必须等于系统的出厂阈值 47", 47, AppPrefs.THERMAL_LIMIT_C_DEFAULT)
    }

    @Test
    fun maxIsTheUserMandatedHardCap() {
        // 🔴 60 是**用户原话**（「禁止超过60度」）⇒ 放松它 = 替用户改需求。
        assertEquals("上限必须是用户点名的 60", 60, AppPrefs.THERMAL_LIMIT_C_MAX)
    }

    @Test
    fun boundsAreStrictlyOrdered() {
        assertTrue(
            "MIN < DEFAULT < MAX 必须成立（否则夹取会退化成常数，且不报错）",
            AppPrefs.THERMAL_LIMIT_C_MIN < AppPrefs.THERMAL_LIMIT_C_DEFAULT &&
                AppPrefs.THERMAL_LIMIT_C_DEFAULT < AppPrefs.THERMAL_LIMIT_C_MAX,
        )
    }

    /**
     * ★ 下限必须**明显高于**日常板温。
     *
     * 本机实测板温 35.969°C（2026-10-05，`/sys/class/thermal/thermal_message/board_sensor_temp`）。
     * 若下限低于日常温度，用户把输入框拉到最小就等于"每一刻都在高温态" ——
     * 那是一条**没有确认弹窗**的关闭保护之路，绕过了我们特意加的那道闸。
     */
    @Test
    fun minIsAboveIdleBoardTemperature() {
        val observedIdleTemp = 36
        assertTrue(
            "下限必须高于实测日常板温（$observedIdleTemp°C），否则它等于一条绕过确认的关保护路径",
            AppPrefs.THERMAL_LIMIT_C_MIN > observedIdleTemp,
        )
    }

    // ================================================================ 夹取

    @Test
    fun clampKeepsInRangeValues() {
        assertEquals(47, AppPrefs.clampThermalLimitC(47))
        assertEquals(40, AppPrefs.clampThermalLimitC(40))
        assertEquals(60, AppPrefs.clampThermalLimitC(60))
    }

    @Test
    fun clampLiftsBelowMin() {
        // 用户输 0 / 5 / 39 —— 一律抬到下限（而不是"关掉保护"）。
        assertEquals(AppPrefs.THERMAL_LIMIT_C_MIN, AppPrefs.clampThermalLimitC(0))
        assertEquals(AppPrefs.THERMAL_LIMIT_C_MIN, AppPrefs.clampThermalLimitC(5))
        assertEquals(AppPrefs.THERMAL_LIMIT_C_MIN, AppPrefs.clampThermalLimitC(39))
    }

    @Test
    fun clampCapsAboveMax() {
        // 🔴 这一条就是用户那句「禁止超过60度」的**可执行形态**。
        //    界面上的输入过滤器只挡"三位数"，真正的兜底在这里。
        assertEquals(AppPrefs.THERMAL_LIMIT_C_MAX, AppPrefs.clampThermalLimitC(61))
        assertEquals(AppPrefs.THERMAL_LIMIT_C_MAX, AppPrefs.clampThermalLimitC(100))
        assertEquals(AppPrefs.THERMAL_LIMIT_C_MAX, AppPrefs.clampThermalLimitC(Int.MAX_VALUE))
    }

    @Test
    fun clampIsIdempotent() {
        // 夹取两次 == 夹取一次（"归一化"该有的性质；setter 与两条读盘路径都靠它）
        for (v in intArrayOf(Int.MIN_VALUE, -1, 0, 29, 39, 40, 47, 60, 61, 999, Int.MAX_VALUE)) {
            val once = AppPrefs.clampThermalLimitC(v)
            assertEquals("clamp 应幂等（输入 $v）", once, AppPrefs.clampThermalLimitC(once))
        }
    }

    /**
     * ★★ **「关掉保护」与「把上限调到最低」必须是两种不同的状态。**
     *
     * 这是设计上的分界（见 [PrefsBridge.SPLIT_THERMAL_GUARD_OFF] 的类注释）：
     *   前者是一个带二次确认弹窗的布尔，后者是一个普通数字。
     * 如果 `clampThermalLimitC` 的下限低到能表达"关掉"，用户就会有一条
     * **不经过弹窗**的关保护路径 —— 那正是我们加弹窗要防的事。
     * ⇒ 下限必须严格大于 0（在这里表达为"夹到下限之后仍然是一个会拦人的温度"）。
     */
    @Test
    fun clampingToMinDoesNotMeanGuardOff() {
        val clamped = AppPrefs.clampThermalLimitC(0)
        assertTrue(
            "夹到下限之后仍应是一个真实温度（> 0），而不是 0 这种「恒不触发」的哨兵值",
            clamped > 0,
        )
    }
}
