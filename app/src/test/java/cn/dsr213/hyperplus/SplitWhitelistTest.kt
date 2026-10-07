package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分屏名单（2026-10-05 新增）的守卫测试。
 *
 * ============================ 这一版守的东西 ============================
 * 分屏名单**照抄旋转名单的范式，但不共用数据**（用户当天拍板「各自独立一份」）。
 * 于是这里要守的正好是这条范式的两个侧面：
 *
 * 1. **范式本身不许走样**：`默认 + 加 − 减`，减集必须独立一份（同 [AppWhitelistTest] 第 1 条）。
 * 2. **它必须与旋转名单是两份东西**：默认清单不一样（**长视频不进分屏名单**）、
 *    存储键不一样。这两条一旦破了，症状都不是崩溃，而是"某个游戏莫名不能被分屏"
 *    或者"两页的开关互相打架"——极难定位。
 * 3. **游戏清单只有一份真值**：[SplitWhitelist.DEFAULT_GAMES] 必须**就是**
 *    [AppWhitelist.DEFAULT_GAMES]（同一个对象），不是抄来的副本。
 */
class SplitWhitelistTest {

    /** 默认清单里的游戏（拿王者荣耀当代表，它确实在本机装着） */
    private val defaultGame = "com.tencent.tmgp.sgame"

    /** 默认清单里的长视频应用。★ 它**不该**进分屏名单 —— 这是两份名单的关键差异 */
    private val defaultVideo = "tv.danmaku.bili"

    /** 不在任何默认清单里的第三方应用 */
    private val third = "com.example.third"

    private fun eff(
        added: Set<String> = emptySet(),
        removed: Set<String> = emptySet(),
    ) = SplitWhitelist.resolve(added, removed)

    // ============================================================ ① 默认清单 = 游戏

    @Test
    fun `游戏默认在分屏名单里`() {
        assertTrue("默认游戏折一下不该被分屏", defaultGame in eff())
        assertFalse("第三方应用默认不在名单里（照常分屏）", third in eff())
    }

    /**
     * ★★ 这是**两份名单最要紧的差异**（[SplitWhitelist] 类注释花了一整段解释）：
     *   分屏对视频是**有意义**的（边看边刷），对游戏几乎只有代价。
     *   ⛔ 别因为"两份清单长得像"就把长视频那批一起抄过来。
     */
    @Test
    fun `长视频不在分屏名单里（与旋转名单刻意不同）`() {
        assertFalse(
            "长视频折一下分屏是有意义的场景，必须留给用户自己决定",
            defaultVideo in eff(),
        )
        // ⚠️ 反证：它在**旋转**名单里。两条断言一起才说明"这是两份不同的名单"。
        assertTrue("（对照）长视频在旋转名单里是默认项", defaultVideo in AppWhitelist.DEFAULT_PACKAGES)
    }

    @Test
    fun `分屏默认清单里全是游戏 一个视频都没有`() {
        val nonGames = SplitWhitelist.DEFAULT_PACKAGES - SplitWhitelist.DEFAULT_GAMES
        assertTrue("默认集合必须等于游戏集合（不许混进视频那批）", nonGames.isEmpty())
        assertTrue("游戏清单不该是空的（不然默认名单名存实亡）", SplitWhitelist.DEFAULT_GAMES.isNotEmpty())
    }

    @Test
    fun `isDefault 只认分屏出厂清单`() {
        assertTrue(SplitWhitelist.isDefault(defaultGame))
        assertFalse(SplitWhitelist.isDefault(third))
        assertFalse("长视频不是分屏默认项", SplitWhitelist.isDefault(defaultVideo))
    }

    // ============================================================ ② 游戏清单只有一份真值

    /**
     * ★ 两处各写一份包名清单的后果是"加了一个游戏只改对一处"，而且**不报错**。
     *   [SplitWhitelist.DEFAULT_GAMES] 刻意写成 `= AppWhitelist.DEFAULT_GAMES`（引用而非复制），
     *   这条把它钉死：将来若有人把它改成 `setOf("...")` 字面量，这里立刻红。
     */
    @Test
    fun `分屏游戏清单与旋转游戏清单是同一个集合`() {
        assertEquals(
            "必须是引用，不是抄一份 —— 否则给旋转名单加游戏时分屏不会跟上",
            AppWhitelist.DEFAULT_GAMES,
            SplitWhitelist.DEFAULT_GAMES,
        )
    }

    // ============================================================ ③ 合成公式（与旋转名单同形）

    @Test
    fun `用户加的非默认应用会生效`() {
        assertTrue(third in eff(added = setOf(third)))
    }

    @Test
    fun `用户关掉的默认游戏必须真的关掉（减集是独立一份）`() {
        val s = eff(removed = setOf(defaultGame))
        assertFalse("只删加集的话，这里会被默认值顶回来", defaultGame in s)
    }

    @Test
    fun `减集只影响被减的那个包（不许连坐）`() {
        val otherGame = "com.tencent.KiHan"
        val s = eff(added = setOf(third), removed = setOf(otherGame))
        assertTrue(third in s)
        assertFalse(otherGame in s)
        assertTrue("同批里没被减的默认项照旧", defaultGame in s)
    }

    @Test
    fun `同一个包同时在加集与减集时 减集赢（顺序不许改）`() {
        assertFalse(defaultGame in eff(added = setOf(defaultGame), removed = setOf(defaultGame)))
        assertFalse(third in eff(added = setOf(third), removed = setOf(third)))
    }

    // ============================================================ ④ 与旋转名单的隔离

    /**
     * ★★ 两份名单**不共用存储**（用户拍板）。这条守的是"键名不许撞"——
     *   撞了就是"关掉的游戏被当成打开的读回来"，而且**不报错**。
     */
    @Test
    fun `分屏名单的键与旋转名单的键不能撞`() {
        assertEquals("split_whitelist_add", PrefsBridge.SPLIT_WHITELIST_ADD)
        assertEquals("split_whitelist_remove", PrefsBridge.SPLIT_WHITELIST_REMOVE)
        assertFalse(
            "增集撞了会把另一份名单的勾读成本名单的勾",
            PrefsBridge.SPLIT_WHITELIST_ADD == PrefsBridge.WHITELIST_ADD,
        )
        assertFalse(
            "同上（减集）",
            PrefsBridge.SPLIT_WHITELIST_REMOVE == PrefsBridge.WHITELIST_REMOVE,
        )
        assertFalse(
            "分屏自己的增集与减集也不能撞",
            PrefsBridge.SPLIT_WHITELIST_ADD == PrefsBridge.SPLIT_WHITELIST_REMOVE,
        )
    }

    /**
     * ★ 同一个包，可以在**旋转名单**里被关掉、同时在**分屏名单**里被保留（反之亦然）——
     *   这正是"各自独立一份"的业务含义。`resolve` 是纯函数，所以这条用两个独立的调用来表达。
     */
    @Test
    fun `一个包可以在两份名单里有不同归属（独立性的业务含义）`() {
        // 用户想让某个游戏自己管朝向（旋转名单豁免），但不想让它被分屏（分屏名单也豁免）
        // ⇒ 两份都开：互不影响，各自算各自的
        assertTrue(defaultGame in AppWhitelist.resolve(setOf(defaultGame), emptySet()))
        assertTrue(defaultGame in SplitWhitelist.resolve(setOf(defaultGame), emptySet()))

        // 反过来：旋转名单里关掉它（照常受旋转控制），分屏名单里不动它（仍不被分屏）
        assertFalse(defaultGame in AppWhitelist.resolve(emptySet(), setOf(defaultGame)))
        assertTrue(
            "分屏名单没被旋转名单的减集碰到",
            defaultGame in SplitWhitelist.resolve(emptySet(), emptySet()),
        )
    }

    // ============================================================ ⑤ 编解码（复用旋转那份实现）

    /**
     * ★ 编码**刻意复用** [AppWhitelist.encode] / [AppWhitelist.decode]（见 [SplitWhitelist] 类注释）——
     *   两份实现迟早会分叉。这条既是往返测试，也是"复用"这件事留下的痕迹测试。
     */
    @Test
    fun `编解码往返不丢包名`() {
        val s = setOf("com.a", "com.tencent.tmgp.sgame", "com.netease.l22")
        assertEquals(s, AppWhitelist.decode(AppWhitelist.encode(s)))
    }

    @Test
    fun `空集编码成空串`() {
        assertEquals("", AppWhitelist.encode(emptySet()))
        assertEquals(emptySet<String>(), AppWhitelist.decode(AppWhitelist.encode(emptySet())))
    }
}
