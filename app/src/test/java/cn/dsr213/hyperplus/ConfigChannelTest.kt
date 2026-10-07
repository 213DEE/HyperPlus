package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置快照编解码的契约测试（2026-10-03，广播通道）。
 *
 * ★ 为什么这些用例值得写：`ConfigChannel.encode/decode` 是**跨进程契约** ——
 *   App 编、引擎解，两边各自升级时不会有编译期错误，只会**静默读错**。
 *   而它错了的样子是最难查的一种：配置"改了没反应"，界面上看不出任何异常。
 *
 * ⚠️ 刻意的取舍：
 *   1. **不测 Android 相关部分**（`sendPush` / `snapshotOf`）—— 它们在 JVM 上是空壳存根，
 *      测了也是假绿。这里只测**纯函数**，也就是真正会被两条进程同时用到的那部分。
 *   2. **不断言编码串的逐字形态**（除了下面那一条格式示例）—— 那种断言会在"格式微调"
 *      时集体报警，而真正要守住的是**往返一致**。唯一钉死形态的是
 *      [encodeFormatIsLengthPrefixed]：格式本身是契约，它变了老引擎就解不出来。
 */
class ConfigChannelTest {

    // ---------------------------------------------------------------- 往返一致

    @Test
    fun roundTripKeepsTypesAndValues() {
        val src: Map<String, Any?> = linkedMapOf(
            "rotate_mode_inner" to "SYSTEM",
            "semi_hint_ms" to 3000,
            "gate_enabled" to true,
            "handoff_auto_rotate" to false,
            "app_whitelist_add" to "",
        )
        val back = ConfigChannel.decode(ConfigChannel.encode(src))
        assertEquals(src, back)
    }

    @Test
    fun intStaysIntAndFloatStaysFloat() {
        val back = ConfigChannel.decode(ConfigChannel.encode(linkedMapOf("n" to 90, "f" to 90.0f)))
        assertEquals(90, back?.get("n"))
        assertEquals(90.0f, back?.get("f"))
        assertTrue("整数键不该被读成浮点", back?.get("n") is Int)
        assertTrue("浮点键不该被读成整数", back?.get("f") is Float)
    }

    @Test
    fun longIsPreserved() {
        val v = 1_791_002_314_254L
        assertEquals(v, ConfigChannel.decode(ConfigChannel.encode(mapOf("t" to v)))?.get("t"))
    }

    /**
     * ★ 这条是**格式本身**的契约：长度前缀。
     *   格式一改，跨进程两侧就不再兼容 —— 所以要被钉死，不能只在心里记着。
     *
     * ⚠️ `semi_hint_ms` 是 **12** 个字符（不是 11）——
     *   这里原先写错过，正是这条用例把它抓出来的。改长度时先数一遍再改。
     */
    @Test
    fun encodeFormatIsLengthPrefixed() {
        val s = ConfigChannel.encode(linkedMapOf("semi_hint_ms" to 3000))
        assertEquals("i12:semi_hint_ms4:3000", s)
    }

    // ---------------------------------------------------------------- 长度前缀要能救的场

    /**
     * ★ 值里带分隔符、换行、中文、数字 —— 长度前缀的**全部意义**就在这几条。
     *   白名单是 `\n` 分隔的一个字符串，请求键是 `"<时间戳>|<方向>"`
     *   ⇒ 如果当初图省事用了 `|`/`\n` 分隔的格式，这里全都会碎。
     */
    @Test
    fun valuesWithSeparatorsAndNewlinesSurvive() {
        val dirty = "com.a\ncom.b\ncom.c"
        val req = "1791002314254|LEFT"
        val cjk = "跟随系统：中文，以及 colon: 与 12:34"
        val src = linkedMapOf<String, Any?>(
            "app_whitelist_add" to dirty,
            "hint_test" to req,
            "note" to cjk,
        )
        assertEquals(src, ConfigChannel.decode(ConfigChannel.encode(src)))
    }

    /** 值本身长得像一条记录时也不能被误解析（长度前缀的"免疫"测试） */
    @Test
    fun valueLookingLikeARecordIsNotMisparsed() {
        val evil = "i12:semi_hint_ms4:9999"
        val back = ConfigChannel.decode(ConfigChannel.encode(mapOf("a" to evil, "b" to "x")))
        assertEquals(evil, back?.get("a"))
        assertEquals("x", back?.get("b"))
    }

    // ---------------------------------------------------------------- 空表与 null

    @Test
    fun emptyMapRoundTripsToEmptyMap() {
        assertEquals(emptyMap<String, Any?>(), ConfigChannel.decode(ConfigChannel.encode(emptyMap())))
    }

    /**
     * `null` 值要被**跳过**而不是编成空串：两者语义不同
     * （跳过 = 这个键不存在；空串 = 键存在且值为空，白名单的"全不豁免"就靠它）。
     */
    @Test
    fun nullValuesAreSkippedNotEncodedAsEmpty() {
        val back = ConfigChannel.decode(ConfigChannel.encode(mapOf("a" to null, "b" to "")))
        assertEquals(setOf("b"), back?.keys)
        assertEquals("", back?.get("b"))
    }

    // ---------------------------------------------------------------- 坏包必须被识别出来

    @Test
    fun malformedBlobsReturnNullInsteadOfPartialConfig() {
        val bad = listOf(
            "i12:semi_hint_ms4:30", // 长度声明 4 但只剩 2 个字符
            "x3:abc1:v", // 认不出的类型标记
            "i3:abc:1", // 长度后面不是 ':'（键长处）
            "i12:semi_hint_msz1:v", // 值长不是数字
            "i12:semi_hint_ms1:v", // Int 的值不是数字
            "b1:a2:ok", // Boolean 的值既不是 1 也不是 0
            "i", // 刚开了个头就结束
            "i12", // 有长度、没有冒号
        )
        for (b in bad) {
            assertNull("应判为坏包：$b", ConfigChannel.decode(b))
        }
    }

    /** 空串是**合法的空快照**（0 个键），不是坏包 —— 两件事必须分得开 */
    @Test
    fun emptyBlobIsValidEmptySnapshot() {
        assertEquals(emptyMap<String, Any?>(), ConfigChannel.decode(""))
    }

    /**
     * 超长/越界长度不能把解析带进越界下标（会抛异常杀死 SystemUI 进程）。
     *
     * ★ 后两条是**整数溢出**的回归用例：判据若写成 `i + len > blob.length`，
     *   `len = Int.MAX_VALUE` 时右边会溢出成负数 ⇒ 判据放行 ⇒ `substring` 当场抛
     *   `StringIndexOutOfBoundsException`。实现侧因此改成 `len > blob.length - i`
     *   （见 [ConfigChannel.decode] 里 `fits` 的注释）。
     */
    @Test
    fun outOfRangeLengthReturnsNullNotCrash() {
        assertNull(ConfigChannel.decode("s9999999999:a1:b"))
        assertNull(ConfigChannel.decode("s2147483647:a1:b")) // 键长溢出
        assertNull(ConfigChannel.decode("s99999999999999999999:a1:b")) // 超过 Int 范围
        assertNull(ConfigChannel.decode("s1:a2147483647:b")) // ★ 值长溢出
        assertNull(ConfigChannel.decode("i12:semi_hint_ms2147483647:30")) // ★ 值长溢出（真实键名）
    }

    // ---------------------------------------------------------------- 退化

    @Test
    fun unknownTypeDegradesToString() {
        val back = ConfigChannel.decode(ConfigChannel.encode(mapOf("s" to setOf("a", "b"))))
        assertEquals(setOf("a", "b").toString(), back?.get("s"))
    }

    // ---------------------------------------------------------------- 贴近真实的整包

    /**
     * 用**真实的键名**跑一遍：这条用例的价值在于"新加的配置键忘了加进编码器"这类改动
     * 会被它拦住 —— 因为键名本身参与了长度前缀。
     */
    @Test
    fun realisticSnapshotRoundTrips() {
        val src: Map<String, Any?> = linkedMapOf(
            PrefsBridge.MODE_INNER to "ADAPTIVE",
            PrefsBridge.STRATEGY to "POWER_SAVING",
            PrefsBridge.HANDOFF_ROTATE to true,
            PrefsBridge.GATE to true,
            PrefsBridge.EXPERIMENTAL_ADAPTIVE to false,
            PrefsBridge.HINT_MS to 3000,
            PrefsBridge.CALIB_REQ to "1791002314254|1",
            PrefsBridge.BREAKER_RESET to "1791002314254",
            PrefsBridge.WHITELIST_ADD to "com.tencent.mm\ncom.tencent.mobileqq",
            PrefsBridge.WHITELIST_REMOVE to "",
        )
        assertEquals(src, ConfigChannel.decode(ConfigChannel.encode(src)))
    }
}
