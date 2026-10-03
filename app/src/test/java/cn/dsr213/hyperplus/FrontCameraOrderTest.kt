package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 前摄 id 候选顺序的纯逻辑单测（[orderFrontIds]）。
 *
 * 背景：本机实测有 **5 个前摄**（`1,5,7,8,9`，`dumpsys media.camera` 原文确认
 * `Facing: Front / Orientation: 270 / 无闪光灯`）。引擎会在它们之间轮换 ——
 * 顺序错了不会崩，但会**白白多烧一轮 1.5s 的 burst 超时**，用户体感就是"卡一下"。
 */
class FrontCameraOrderTest {

    /** 真机实测的前摄集合（回归锚点，别随手改成别的） */
    private val realFronts = listOf("1", "5", "7", "8", "9")

    @Test
    fun preferredComesFirst() {
        val got = orderFrontIds(realFronts, "5")
        assertEquals("5", got.first())
    }

    @Test
    fun preferredKeepsEveryOtherCameraExactlyOnce() {
        val got = orderFrontIds(realFronts, "5")
        // ① 每个前摄恰好出现一次（多一个=多烧一轮；少一个=少一条退路）
        assertEquals(realFronts.size, got.size)
        assertEquals(realFronts.toSet(), got.toSet())
        // ② 不能出现重复（重复会让"轮换一圈"提前绕回，白试同一个 id）
        assertEquals(realFronts.size, got.toSet().size)
        // ③ 非偏好的部分保持 id 升序（顺序稳定，不随 cameraIdList 抖动）
        assertEquals(listOf("1", "7", "8", "9"), got.drop(1))
    }

    @Test
    fun noPreferenceFallsBackToSortedOrder() {
        assertEquals(listOf("1", "5", "7", "8", "9"), orderFrontIds(realFronts, ""))
    }

    @Test
    fun stalePreferredIdIsIgnored() {
        // 换机型 / 系统更新后 id 变了：不能让过期的 id 卡在队首反复失败
        val got = orderFrontIds(realFronts, "42")
        assertEquals(listOf("1", "5", "7", "8", "9"), got)
    }

    @Test
    fun alreadyFirstPreferenceIsStable() {
        // 偏好就是升序第一个时，结果应与"无偏好"完全一致（不引入额外顺序变化）
        assertEquals(
            orderFrontIds(realFronts, ""),
            orderFrontIds(realFronts, "1"),
        )
    }

    @Test
    fun inputOrderDoesNotMatter() {
        // cameraIdList 的顺序是系统给的，可能变化 —— 结果必须与输入顺序无关
        val shuffled = listOf("9", "1", "8", "5", "7")
        assertEquals(
            orderFrontIds(realFronts, "7"),
            orderFrontIds(shuffled, "7"),
        )
    }

    @Test
    fun singleFrontCameraStaysSingle() {
        // 只有一个前摄（多数非小米机型）⇒ 轮换天然禁用，列表就是它自己
        assertEquals(listOf("1"), orderFrontIds(listOf("1"), "1"))
        assertEquals(listOf("1"), orderFrontIds(listOf("1"), ""))
    }
}
