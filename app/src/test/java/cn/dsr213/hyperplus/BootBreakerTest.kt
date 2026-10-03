package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动熔断的**判据**与**跨进程契约**（2026-10-03）。
 *
 * ★ 它为什么必须有（这是本项目唯一一个"出事会让整机不能用"的机制）：
 *   熔断的两端分别在两个进程里 —— 引擎写 `Settings.System` 的计数、App 读状态串显示。
 *   引擎跑在 SystemUI 进程里、**没法在这台开发机上直接单测**，所以：
 *     - 判据抽成纯函数 [bootBreakerTripped]（可测）；
 *     - 状态串的解析走 [ModuleLink.parse]（可测，且它就是那个跨进程契约）。
 *   ⇒ 这两头一旦写错，症状是"要么锁死用户、要么根本没拦住"，都不能靠真机随手一试发现。
 *
 * ⚠️ 阈值 [BOOT_BREAKER_THRESHOLD] 的**具体数值**刻意不在这里断言：
 *   断言 3 会把"改阈值"变成一件要先改测试的事，而阈值是**产品取舍**、理应能单独调。
 *   这里只锁**语义**（达到即停、未达到即放行）。
 */
class BootBreakerTest {

    @Test
    fun `未达到阈值一律放行`() {
        for (n in 0 until BOOT_BREAKER_THRESHOLD) {
            assertFalse("计数 $n 不该触发熔断", bootBreakerTripped(n))
        }
    }

    @Test
    fun `达到阈值即触发`() {
        assertTrue(bootBreakerTripped(BOOT_BREAKER_THRESHOLD))
    }

    @Test
    fun `超过阈值仍然触发_不能因为计数继续涨就放行`() {
        // ★ 这一条防的是"把判据写成 == 阈值"：计数一旦越过阈值（用户连点了几次重新启用），
        //   写成相等就再也不触发了 —— 无限循环会**在最需要它的时候**失效。
        assertTrue(bootBreakerTripped(BOOT_BREAKER_THRESHOLD + 1))
        assertTrue(bootBreakerTripped(BOOT_BREAKER_THRESHOLD + 100))
    }

    @Test
    fun `负数是脏数据_按放行处理`() {
        // 计数只可能是 0 或正数；真读到负数说明键被外部改坏了。
        // 此时**放行**是对的：宁可多试一次，也不能因为一个坏值把功能永久锁死。
        assertFalse(bootBreakerTripped(-1))
    }

    // ---------------------------------------------------------------- 状态串契约

    @Test
    fun `熔断态的状态串能被解析出来`() {
        val s = ModuleLink.parse("v1|phase=halted|attempts=3", 0)
        assertEquals("halted", s?.phase)
        assertEquals(3, s?.breakerAttempts)
    }

    @Test
    fun `老格式没有 attempts 字段时兜底 0 且不崩`() {
        val s = ModuleLink.parse("v1|phase=ready|mode=ADAPTIVE|takeover=1", 0)
        assertEquals("ready", s?.phase)
        assertEquals(0, s?.breakerAttempts)
        assertEquals("ADAPTIVE", s?.mode)
        assertTrue(s?.takeover == true)
    }

    @Test
    fun `认不出的 phase 原样保留`() {
        // ★ 契约纪律：引擎若先上新取值、App 还没跟上，界面必须**原样显示**而不是猜一个。
        val s = ModuleLink.parse("v1|phase=brand_new_thing", 0)
        assertEquals("brand_new_thing", s?.phase)
    }

    @Test
    fun `格式不对时返回 null 而不是硬猜`() {
        assertNull(ModuleLink.parse(null))
        assertNull(ModuleLink.parse(""))
        assertNull(ModuleLink.parse("halted|attempts=3"))
    }
}
