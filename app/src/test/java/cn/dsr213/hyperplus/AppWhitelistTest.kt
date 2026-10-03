package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白名单**单层化**（2026-09-29 第二轮）的守卫测试。
 *
 * ============================ 这一版守的东西变了 ============================
 * 09-29 上午这里守的是"两块屏 + 三个作用域"的合成公式（`effectiveFor` / `Scope`）与
 * "按应用声明强制外屏豁免"（`isOuterForced`）的边界。当天下午用户拍板
 * 「**直接删除外屏的旋转增强，只保留内屏的旋转增强和应用豁免**」，
 * 于是那两样东西**整个没了** —— 相关断言全部编译不过（`effectiveFor` 等符号不存在），
 * 所以这篇重新写，改守下面三条**现在仍然成立**的不变量：
 *
 * 1. **合成公式只剩一条**（[AppWhitelist.resolve]）：`默认 + 用户加 − 用户减`。
 *    这里最容易悄悄坏的是"减集必须是独立一份"—— 只删加集的话，默认清单里的包
 *    会在下一次现算时被默认值顶回来，用户会觉得"我明明关过，怎么又开了"。
 * 2. **落盘的键名不许改** —— 改了就是"老用户的勾全丢"，而这是**静默**的：
 *    编译过、测试过、装上去一切正常，只是配置没了。
 *    ⚠️ 名单收成一份时**刻意沿用了原来"全局层"的那个键**，就是为了零迁移，这条守着它。
 * 3. **本应用自己不再恒豁免**（用户 09-29 第 3 问：「我们的模块 app 本身为什么不受旋转
 *    按钮的控制？」）。它现在只是名单里的普通一行、**默认不勾** ——
 *    所以它必须不在 [AppWhitelist.DEFAULT_PACKAGES] 里，也不许有"减不掉"的特权。
 */
class AppWhitelistTest {

    /** 一个默认清单里的游戏（用来验证"默认清单生效"） */
    private val defaultGame = "com.tencent.tmgp.sgame"

    /** 一个默认清单里的长视频应用 */
    private val defaultVideo = "tv.danmaku.bili"

    /** 不在任何默认清单里的第三方应用（用来验证"用户手动开"） */
    private val third = "com.example.third"

    /** 生效集合。只有一份，没有"哪块屏"这个维度了 —— 那正是这轮改动本身 */
    private fun eff(
        added: Set<String> = emptySet(),
        removed: Set<String> = emptySet(),
    ) = AppWhitelist.resolve(added, removed)

    // ============================================================ ① 默认清单

    @Test
    fun `游戏与长视频默认在名单里（用户要求：按之前的方法默认豁免）`() {
        val s = eff()
        assertTrue("默认游戏应默认豁免", defaultGame in s)
        assertTrue("默认长视频应默认豁免", defaultVideo in s)
        assertFalse("不在任何清单里的第三方应用默认不豁免", third in s)
    }

    @Test
    fun `默认清单 = 游戏集 + 长视频集`() {
        assertEquals(AppWhitelist.DEFAULT_GAMES + AppWhitelist.DEFAULT_VIDEO, AppWhitelist.DEFAULT_PACKAGES)
        assertTrue("两个子集都不该是空的（不然默认清单名存实亡）", AppWhitelist.DEFAULT_GAMES.isNotEmpty())
        assertTrue(AppWhitelist.DEFAULT_VIDEO.isNotEmpty())
    }

    @Test
    fun `isDefault 只认出厂清单`() {
        assertTrue(AppWhitelist.isDefault(defaultGame))
        assertFalse(AppWhitelist.isDefault(third))
        assertFalse("本应用自己不在出厂清单里（见 ③）", AppWhitelist.isDefault(AppWhitelist.OWN_PACKAGE))
    }

    // ============================================================ ② 合成公式（唯一一条）

    @Test
    fun `用户加的非默认应用会生效`() {
        assertTrue(third in eff(added = setOf(third)))
    }

    @Test
    fun `用户关掉的默认项必须真的关掉（减集是独立一份）`() {
        val s = eff(removed = setOf(defaultGame))
        assertFalse("只删加集的话，这里会被默认值顶回来", defaultGame in s)
        assertTrue("减掉王者不该把哔哩哔哩也减掉", defaultVideo in s)
    }

    @Test
    fun `减集只影响被减的那个包（不许连坐）`() {
        val s = eff(added = setOf(third), removed = setOf(defaultVideo))
        assertTrue(third in s)
        assertFalse(defaultVideo in s)
        assertTrue("同批里没被减的默认项照旧", defaultGame in s)
    }

    /**
     * ⚠️ 这条钉的是 [AppWhitelist.resolve] 的**运算顺序**：`默认 + 加 − 减`，
     *   所以"同一个包既在加集又在减集"时**减集赢**。
     *
     * ★ 正常情况下这两个集合**不会**同时含一个包（[AppPrefs.setAppWhitelisted] 写之前
     *   会把包从另一侧摘掉）。但 `resolve` 是纯函数，它必须有一个确定的语义 ——
     *   真出了"两边都有"（手改 prefs、两处并发写），行为要可预期。
     *   判据取"减集赢"：**"不想被控制"比"想被控制"更该被尊重**。
     */
    @Test
    fun `同一个包同时在加集与减集时 减集赢（顺序不许改）`() {
        assertFalse(
            "`默认 + 加 − 减` 里减在后面 ⇒ 减集压得住加集",
            defaultGame in eff(added = setOf(defaultGame), removed = setOf(defaultGame)),
        )
        assertFalse(third in eff(added = setOf(third), removed = setOf(third)))
    }

    @Test
    fun `想让包回到名单必须把它移出减集（resolve 不认识改主意）`() {
        // ★ 这条说明了一个**设计约束**而不是 bug：`resolve` 不认识"用户后来改主意了"，
        //   它只看两个集合。要让一个包回到名单里，界面必须把它从减集里**摘掉**
        //   （这正是 `setAppWhitelisted` 做的事：写加集时先把包从减集 remove）。
        //   ⇒ 只往加集里塞、不摘减集，用户会看到"我打开了它又自己关回去"。
        assertFalse(defaultGame in eff(added = setOf(defaultGame), removed = setOf(defaultGame)))
        assertTrue("摘掉减集之后才回来", defaultGame in eff(added = setOf(defaultGame), removed = emptySet()))
    }

    // ============================================================ ③ 本应用自己：默认不豁免

    @Test
    fun `本应用自己默认不被豁免（用户第 3 问的正解）`() {
        assertFalse(
            "09-29 之前它靠 ALWAYS_EXEMPT 恒豁免 ⇒ 设置页永远不受自己管、用户以为是坏了",
            AppWhitelist.OWN_PACKAGE in eff(),
        )
    }

    @Test
    fun `本应用自己没有任何减不掉的特权`() {
        val own = AppWhitelist.OWN_PACKAGE
        assertFalse("减集能减掉它", own in eff(removed = setOf(own)))
        assertTrue("加集能加回来（界面上那一格是可以勾的）", own in eff(added = setOf(own)))
    }

    @Test
    fun `本应用自己不是默认清单成员 所以界面上的默认标记不会打到它头上`() {
        assertFalse(AppWhitelist.isDefault(AppWhitelist.OWN_PACKAGE))
    }

    // ============================================================ ④ 落盘：键名与枚举名

    /**
     * ★★ 名单从三层收成一份时**沿用了原来"全局层"的键** —— 这是"零迁移"的全部依据。
     *   改掉这个键名 = 老用户以前勾过的包一个都不剩，而且是静默丢。
     */
    @Test
    fun `白名单键名沿用旧的全局层键（零迁移）`() {
        assertEquals("app_whitelist_add", PrefsBridge.WHITELIST_ADD)
        assertEquals("app_whitelist_remove", PrefsBridge.WHITELIST_REMOVE)
    }

    @Test
    fun `模式键名沿用内屏那份（零迁移）`() {
        // ★ 唯一在用的模式键。旧用户的那一档落在 rotate_mode_inner 上，原样生效。
        assertEquals("rotate_mode_inner", PrefsBridge.MODE_INNER)
        // ⚠️ 退役键：只在迁移指纹 / 配置通道指纹里被 contains 一下，不再读写。
        assertEquals("rotate_mode_outer", PrefsBridge.MODE_OUTER)
        assertEquals("rotate_mode", PrefsBridge.MODE_LEGACY)
    }

    @Test
    fun `增集与减集的键不能撞`() {
        assertFalse(
            "撞了就是「关掉的包」被当成「打开的包」读回来",
            PrefsBridge.WHITELIST_ADD == PrefsBridge.WHITELIST_REMOVE,
        )
    }

    // ============================================================ ⑤ 编解码

    @Test
    fun `编解码往返不丢包名`() {
        val s = setOf("com.a", "cn.dsr213.hyperplus", "tv.danmaku.bili")
        assertEquals(s, AppWhitelist.decode(AppWhitelist.encode(s)))
    }

    @Test
    fun `编码与集合顺序无关（引擎侧是整串比对）`() {
        assertEquals(
            AppWhitelist.encode(setOf("com.b", "com.a")),
            AppWhitelist.encode(setOf("com.a", "com.b")),
        )
    }

    @Test
    fun `解码容忍空值与手改文件留下的杂质`() {
        assertEquals(emptySet<String>(), AppWhitelist.decode(null))
        assertEquals(emptySet<String>(), AppWhitelist.decode(""))
        assertEquals(setOf("com.a", "com.b"), AppWhitelist.decode("com.a\n\n  com.b \n,"))
    }

    @Test
    fun `空集编码成空串（不许编出一个"看起来有内容"的值）`() {
        // ★ 这条与配置通道有关：`hasContent` 那条兜底判据会 `p.contains(键)`，
        //   而落盘的空串与"键不存在"在 contains 上是**不同**的 —— 但语义上都该是"没有"。
        assertEquals("", AppWhitelist.encode(emptySet()))
        assertEquals(emptySet<String>(), AppWhitelist.decode(AppWhitelist.encode(emptySet())))
    }
}
