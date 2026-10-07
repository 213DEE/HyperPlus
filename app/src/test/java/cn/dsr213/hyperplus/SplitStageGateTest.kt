package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「提高分屏上限」实验开关的判定核（[SplitStageGate]）。
 *
 * ============================ 为什么它值得单独一个测试文件 ============================
 * 这道开关的**唯一出错方式就是「该不动的时候动了」** —— 而它一旦错，后果不是「功能没出来」，
 * 而是**用户没同意就改了系统行为**（分屏上限被悄悄抬到 8），
 * 表现是「我没开它却生效了」，跟配置通道、缓存、时序全都能扯上关系，**极难反推**。
 *
 * ⇒ 这里把每一种「不干预」逐条钉死：关着 / 两条通道都为 null / 没有这个键 / 值不认识。
 *   最后一条尤其重要：镜像里的值是跨进程解码来的、属性值又是个**跨版本、跨应用共享**的
 *   字符串空间，**类型和内容都不一定是我们写进去的那个**，而任何「真值转换」式的写法
 *   （`!= false`、`as? Boolean ?: 默认`）都会在坏数据上悄悄变成「开着」。
 *
 * ============================ 2026-10-06：多了「属性优先」这一层 ============================
 * 开关的值现在**先走 `persist.*` 属性**（进程起来第一毫秒就能读，理由见
 * [PrefsBridge.PROP_MULTISPLIT] 的实测日志表），镜像退成兜底。
 * ⇒ 新增三条**优先级**判据：
 *   · 属性 `"1"` 要**压过**镜像里的 `false`（属性说了算）
 *   · 属性 `"0"` 要**压过**镜像里的 `true`（★ 最要紧的一条：它保证「关」真的关得掉）
 *   · 属性没写过时**如实回落**镜像（别把老用户判成「关」）
 *
 * ⚠️ 与之相对的那一面（抬起来之后系统真能建出 7/8 格）**测不了** ——
 *   它要一台真机、要折一次手机。那边的证据在 `docs/` 与真机日志里，别指望这个文件。
 */
class SplitStageGateTest {

    /** 用真键名而不是抄一个字面量：键名写错时这里会跟着错，而不是「测试绿、线上不生效」 */
    private val key = PrefsBridge.EXPERIMENTAL_MULTISPLIT

    /** 属性名同理，也从常量取 */
    private val prop = PrefsBridge.PROP_MULTISPLIT

    // ================================================================ 不干预 · 属性那一层

    @Test
    fun propZeroMeansDoNothing() {
        assertNull("属性明确写 0 ⇒ 必须什么都不做", SplitStageGate.targetStages("0", null))
    }

    @Test
    fun propMissingMeansFallBackNotOff() {
        // ⚠️ 这条测的是「回落」而不是「关」：属性从没写过的老用户走的是镜像那条路，
        //    所以这里必须**看不出结果**，由镜像值决定 —— 见 propMissingFallsBackToMirror。
        assertNull("属性为 null + 没有镜像 ⇒ 必须什么都不做", SplitStageGate.targetStages(null, null))
        assertNull("属性为空串 + 没有镜像 ⇒ 必须什么都不做", SplitStageGate.targetStages("", null))
        assertNull("属性只有空白 + 没有镜像 ⇒ 必须什么都不做", SplitStageGate.targetStages("   ", null))
    }

    @Test
    fun unrecognizedPropValueDoesNotCountAsOn() {
        // ⛔ 绝不许被任何「真值转换」放行 —— 属性是个跨版本、跨应用共享的字符串空间。
        //    「-1」尤其要当不认识：那是 ROM 自己「未设置」的语义，不是我们的「开」。
        for (v in listOf("true", "TRUE", "on", "yes", "0x1", "01", "-1", "11", "10")) {
            assertNull("属性 '" + v + "' 不算开", SplitStageGate.targetStages(v, null))
        }
    }

    // ================================================================ 不干预 · 镜像那一层

    @Test
    fun offByMirrorMeansDoNothing() {
        assertNull(
            "属性没写过、镜像里开关关着 ⇒ 必须什么都不做",
            SplitStageGate.targetStages(null, mapOf(key to false)),
        )
    }

    @Test
    fun nullMirrorMeansDoNothing() {
        assertNull("两条通道都读不到 ⇒ 必须什么都不做", SplitStageGate.targetStages(null, null))
    }

    @Test
    fun emptyMirrorMeansDoNothing() {
        assertNull("镜像是空的 ⇒ 必须什么都不做", SplitStageGate.targetStages(null, emptyMap()))
    }

    @Test
    fun missingKeyMeansDoNothing() {
        assertNull(
            "镜像里有别的键、但没有我们这个 ⇒ 必须什么都不做",
            SplitStageGate.targetStages(null, mapOf("some_other_key" to true)),
        )
    }

    @Test
    fun nonBooleanValueDoesNotCountAsOn() {
        // 解码坏包 / 老版本写过别的类型 —— ⛔ 绝不许被当成「开着」
        assertNull("字符串 true 不算开", SplitStageGate.targetStages(null, mapOf(key to "true")))
        assertNull("数字 1 不算开", SplitStageGate.targetStages(null, mapOf(key to 1)))
        assertNull("null 值不算开", SplitStageGate.targetStages(null, mapOf(key to null)))
    }

    // ================================================================ 干预（两条通道各一种）

    @Test
    fun explicitTrueRaisesToMax() {
        val t: Int? = SplitStageGate.targetStages(null, mapOf(key to true))
        assertEquals("镜像里明确读到 true ⇒ 抬到硬上限", 8, t)
    }

    @Test
    fun propOneRaisesToMax() {
        val t: Int? = SplitStageGate.targetStages("1", null)
        assertEquals("属性明确写 1 ⇒ 抬到硬上限（不需要镜像）", 8, t)
    }

    // ================================================================ 优先级（★ 本轮新增的核心）

    @Test
    fun propOneBeatsMirrorOff() {
        val t: Int? = SplitStageGate.targetStages("1", mapOf(key to false))
        assertEquals("属性说开、镜像说关 ⇒ 以属性为准", 8, t)
    }

    @Test
    fun propZeroBeatsMirrorOn() {
        // ★★ 这条最要紧：它保证「关」真的关得掉。属性写 0 而镜像还留着旧的 true
        //    （推送丢了、或引擎还没落盘）时，绝不能被那个陈旧的 true 顶回来 ——
        //    否则用户会看到「我明明关了，它还在」，而且**没有任何报错**。
        assertNull("属性说关、镜像说开 ⇒ 以属性为准", SplitStageGate.targetStages("0", mapOf(key to true)))
    }

    @Test
    fun propMissingFallsBackToMirror() {
        val t: Int? = SplitStageGate.targetStages(null, mapOf(key to true))
        assertEquals("属性没写过 ⇒ 如实回落镜像（别把老用户判成关）", 8, t)
    }

    @Test
    fun propToleratesSurroundingWhitespace() {
        // getprop 的输出可能带空白（被当行读出来时尤其明显）⇒ 两端空白应被忽略。
        // ⚠️ 只放宽**两端**：中间有东西（"1 0"）仍然是「不认识」。
        assertEquals(8, SplitStageGate.targetStages(" 1 ", null))
        assertNull(SplitStageGate.targetStages(" 0 ", mapOf(key to true)))
        assertNull(SplitStageGate.targetStages("1 0", null))
    }

    // ================================================================ 两道不变的边界

    @Test
    fun hardCapIsEightAndIsNotConfigurable() {
        // ⚠️ 这条不是在测「8 这个数字好看」：官方 stageIds 只预置 0..7 共 8 个 id，
        //    抬高会越界。谁要把它改成 9，必须先解释 8 个 id 从哪来。
        assertEquals(8, SplitStageGate.MAX_SUPPORTED)
    }

    @Test
    fun propNameIsOursNotTheRoms() {
        // ⛔ ROM 自己那个 persist.sys.multiple.split.max_stages 是把「上限数字」直接塞进
        //    ROM 的档位逻辑（实测设成 8 会连崩三次，见 docs/ 里那份字节码结论）。
        //    我们只借「属性」这个早读通道，绝不碰它的键 —— 这条测试就是那道分界线的看门人。
        assertEquals("persist.sys.hyperplus.multisplit", prop)
    }

    @Test
    fun otherTruthyKeysDoNotChangeTheAnswer() {
        // 同一份镜像里别的实验开关也开着 ⇒ 与本开关无关
        val t: Int? = SplitStageGate.targetStages(
            null,
            mapOf("experimental_adaptive" to true, key to true),
        )
        assertEquals(8, t)
    }
}
