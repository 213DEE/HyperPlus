package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「实测不可控」名单的纯逻辑测试（A 方案，2026-09-29 用户拍板）。
 *
 * ============================ 这一层为什么必须单测 ============================
 * 这个名单是**引擎自动写入持久化**的唯一一处判据（其余三条豁免都是现场算的）。
 * 它错了的两种后果**都很严重**，而且**都不对称**：
 *   - **误标记**（本来能转的应用被记上）⇒ 功能**永久消失**，用户只能靠"清除记录"自救；
 *   - **漏标记**（该记的没记上）⇒ 那个"按了不转的假按钮"又回来，等于这轮白做。
 * 而它又是**靠观测驱动的**：真机上要等"点了按钮 + 屏幕没动"才会走到写入路径 ——
 * 想验证一次得真去点，成本高。⇒ 编解码与判据边界必须在这里穷举钉死。
 *
 * ⚠️ 不覆盖的部分（诚实记录）：读回计时、写 `Settings.System`、清除请求那条链路 ——
 *   那些要 Context / Handler，只能在真机上看（见 `docs/实测不可控_A方案_2026-09-29.md` §验证）。
 */
class UncontrollableTest {

    private val aweme = "com.ss.android.ugc.aweme"
    private val coolapk = "com.coolapk.market"

    // ================================================================ 键名

    /**
     * 键名必须带 `hyperplus_` 前缀。
     *
     * ★ 为什么值得一条用例：项目约定"落到 `Settings.System` 的键统一加前缀"
     *   （见 [PrefsBridge]），而这个键**只有引擎写**、App 只读 ——
     *   前缀错了不会有任何编译错误，只会在真机上手改键值时发现"怎么都读不出来"。
     */
    @Test
    fun keyUsesProjectPrefix() {
        assertEquals("hyperplus_uncontrollable", Uncontrollable.KEY)
    }

    // ================================================================ 编码 / 解码

    @Test
    fun tokenCarriesForm() {
        assertEquals("$aweme@outer", Uncontrollable.token(aweme, ScreenForm.OUTER))
        assertEquals("$aweme@inner", Uncontrollable.token(aweme, ScreenForm.INNER))
    }

    /**
     * ★ 形态必须能分开 —— 这是"内外屏解耦"在这个名单上的落实。
     *
     * 同一个应用**完全可能**外屏转不动、内屏转得动（内屏 `ignoreOrientationRequest=true`，
     * 应用声明被忽略 ⇒ 我们写得动）。把形态并掉的后果是**误伤内屏** ——
     * 那正是这轮解耦要避免的错误。
     */
    @Test
    fun markedOnOneFormDoesNotAffectTheOther() {
        val raw = Uncontrollable.withMarked(null, aweme, ScreenForm.OUTER)
        assertTrue(Uncontrollable.isMarked(raw, aweme, ScreenForm.OUTER))
        assertFalse("外屏被标记不该影响内屏", Uncontrollable.isMarked(raw, aweme, ScreenForm.INNER))
    }

    /** ★ 幂等：同一个「包 + 屏」重复标记，串不能越写越长（引擎会反复观测到同一件事） */
    @Test
    fun withMarkedIsIdempotent() {
        val once = Uncontrollable.withMarked(null, aweme, ScreenForm.OUTER)
        val twice = Uncontrollable.withMarked(once, aweme, ScreenForm.OUTER)
        assertEquals(once, twice)
        assertEquals(1, Uncontrollable.sizeOf(twice))
        // 同一块屏标记 100 次仍然只有 1 条
        var acc = ""
        repeat(100) { acc = Uncontrollable.withMarked(acc, aweme, ScreenForm.OUTER) }
        assertEquals(1, Uncontrollable.sizeOf(acc))
        assertEquals(once, acc)
    }

    /** 两块屏各记一条 ⇒ 两条；顺带钉住"链式追加不丢前面的" */
    @Test
    fun bothFormsAccumulate() {
        val both = Uncontrollable.withMarked(
            Uncontrollable.withMarked(null, aweme, ScreenForm.OUTER),
            aweme,
            ScreenForm.INNER,
        )
        assertEquals(2, Uncontrollable.sizeOf(both))
        assertTrue(Uncontrollable.isMarked(both, aweme, ScreenForm.OUTER))
        assertTrue(Uncontrollable.isMarked(both, aweme, ScreenForm.INNER))
    }

    /** 多个包各自独立 */
    @Test
    fun packagesAreIndependent() {
        val raw = Uncontrollable.withMarked(
            Uncontrollable.withMarked(null, aweme, ScreenForm.OUTER),
            coolapk,
            ScreenForm.OUTER,
        )
        assertEquals(2, Uncontrollable.sizeOf(raw))
        assertTrue(Uncontrollable.isMarked(raw, aweme, ScreenForm.OUTER))
        assertTrue(Uncontrollable.isMarked(raw, coolapk, ScreenForm.OUTER))
        assertFalse(Uncontrollable.isMarked(raw, "com.other.app", ScreenForm.OUTER))
    }

    /**
     * ★ 输出必须**排序且稳定**：内容没变 ⇒ 字符串没变。
     *
     * 这条不是洁癖 —— 引擎侧的变更检测是**整串比对**（见 [AppWhitelist.encode] 的同款理由），
     * 顺序不稳定会让"没变"看起来像"变了"，进而触发多余的重判 / 日志噪声。
     */
    @Test
    fun outputIsSortedAndStable() {
        val a = Uncontrollable.withMarked(null, "com.zzz.app", ScreenForm.OUTER)
        val b = Uncontrollable.withMarked(a, "com.aaa.app", ScreenForm.OUTER)
        val c = Uncontrollable.withMarked(null, "com.aaa.app", ScreenForm.OUTER)
        val d = Uncontrollable.withMarked(c, "com.zzz.app", ScreenForm.OUTER)
        assertEquals("先加后加的顺序不该影响结果", b, d)
        assertTrue("结果应按包名升序", b.indexOf("com.aaa.app") < b.indexOf("com.zzz.app"))
    }

    // ================================================================ 解析的宽容度

    /**
     * 解析要能容忍各种脏值 —— 这个键是**可被外部改坏**的
     * （`Settings.System` 对特权身份开放，手改 / 旧版本写入都可能留下杂物）。
     *
     * ★ 判据是"坏值只会变成匹配不到的条目，不会让整张名单失效" ——
     *   即不能因为一个坏条目就抛异常 / 返回空集。
     */
    @Test
    fun decodeToleratesJunk() {
        assertTrue(Uncontrollable.decode(null).isEmpty())
        assertTrue(Uncontrollable.decode("").isEmpty())
        assertTrue(Uncontrollable.decode("   ").isEmpty())
        assertTrue(Uncontrollable.decode(",,").isEmpty())
        // 换行 / 空格 / 制表符混用，以及空行
        val raw = "\n $aweme@outer ,, \t $coolapk@inner \n\n"
        val set = Uncontrollable.decode(raw)
        assertEquals(2, set.size)
        assertTrue(set.contains("$aweme@outer"))
        assertTrue(set.contains("$coolapk@inner"))
    }

    /** 大小写与形态无关：`@OUTER` 这种写法匹配不到（我们只写 `outer`），但**不该崩** */
    @Test
    fun unknownFormTokenIsJustNotMatched() {
        val raw = "$aweme@OUTER,$coolapk"
        assertFalse(Uncontrollable.isMarked(raw, aweme, ScreenForm.OUTER))
        assertFalse(Uncontrollable.isMarked(raw, coolapk, ScreenForm.OUTER))
        // 但它仍然是"两条记录"（解析层不负责判合法性，只负责切分）
        assertEquals(2, Uncontrollable.sizeOf(raw))
    }

    // ================================================================ 判据纪律

    /**
     * ★★ **读不到包名 ⇒ 绝不判为不可控**。
     *
     * 这是全工程反复出现的一条纪律（[ForegroundGate] 那一组豁免同款）：
     * 判错方向的代价**不对称** —— 多干一点活的代价是电量，误停的代价是"功能整个消失"。
     * 而这里更严重：误标会**落盘**，从此那个应用一直不弹按钮，且用户看不出原因。
     */
    @Test
    fun missingPackageIsNeverMarked() {
        val raw = Uncontrollable.withMarked(null, aweme, ScreenForm.OUTER)
        assertFalse(Uncontrollable.isMarked(raw, null, ScreenForm.OUTER))
        assertFalse(Uncontrollable.isMarked(raw, "", ScreenForm.OUTER))
        // 名单本身为空时同理
        assertFalse(Uncontrollable.isMarked(null, aweme, ScreenForm.OUTER))
        assertFalse(Uncontrollable.isMarked("", aweme, ScreenForm.OUTER))
    }

    // ================================================================ 界面文案

    /**
     * ⚠️ 这里原本有一个 `noteNamesTheForm()`，断言"红字必须点名是哪块屏 + 必须说'实测'"。
     *
     * 2026-10-03 多语言把它**搬走了**，因为它断言的是**文案**，而文案已经不在 Kotlin 里 ——
     * 它现在是 `res/values` 系列三套 `strings.xml` 里的 `uc_note`（带一个 `%1$s` 让界面注入形态名）。
     * JVM 单测读不到资源（没有 Robolectric），硬塞一个"读源文件"的测试又会依赖
     * Gradle 的工作目录假设，太脆。
     *
     * ⇒ 这两条契约现在的守卫是 **`_probe/check_strings_parity.py`** 里的
     *   「资源契约」段（它本来就要解析三套 XML，位置天然合适）：
     *     · `uc_note` 在**三套**里都必须含 `%1$s` ⇒ 形态名一定会被注入（点名是哪块屏）；
     *     · 简体那一套的值必须含「实测」⇒ 判据仍是"我们真的试过、屏幕真的没动"。
     *   ⛔ 别为了"看着完整"把这两条再抄回单测里 —— 那会变成两处真值。
     */

    // ================================================================ 界面计数（只数内屏那一侧）

    /**
     * ★ `countFor` 是**删外屏增强时补的**（2026-09-29）。
     *
     * 名单里可能同时有 `@inner` 与 `@outer` 两种条目，而外屏那部分**现在没有下游**
     * （引擎只在内屏判这一条）。界面若用 [Uncontrollable.sizeOf] 数总数，就会出现
     * 「显示 3 条、列表里只找得到 2 处红字」这种对不上账的现象 ——
     * 用户会以为"有个应用被标了但我找不到"。
     */
    @Test
    fun countForCountsOnlyTheGivenForm() {
        val raw = "com.a@inner,com.b@outer,com.c@inner,com.d"
        assertEquals("内屏 2 条", 2, Uncontrollable.countFor(raw, ScreenForm.INNER))
        assertEquals("外屏 1 条", 1, Uncontrollable.countFor(raw, ScreenForm.OUTER))
        assertEquals("总数是 4 —— 所以界面**不能**用它（3 条 vs 2 处红字就是这么来的）", 4, Uncontrollable.sizeOf(raw))
    }

    @Test
    fun countForIsSafeOnNullOrBlank() {
        assertEquals(0, Uncontrollable.countFor(null, ScreenForm.INNER))
        assertEquals(0, Uncontrollable.countFor("", ScreenForm.INNER))
        assertEquals(0, Uncontrollable.countFor(" , ,\n ", ScreenForm.INNER))
    }

    /** 没有形态后缀（手改键值留下的裸包名）**不该**被算进任何一侧 —— 它本来也匹配不到 */
    @Test
    fun barePackageNameCountsForNeitherForm() {
        val raw = "com.a,com.b@inner"
        assertEquals(1, Uncontrollable.countFor(raw, ScreenForm.INNER))
        assertEquals(0, Uncontrollable.countFor(raw, ScreenForm.OUTER))
    }

    // ================================================================ 与形态枚举的耦合

    /**
     * `token` 用的是 [ScreenForm.storageKey]（`inner` / `outer`），**不是枚举名**（`INNER`）。
     *
     * ★ 这条锁的是一个"落盘格式"约定：`storageKey` 是既有的、稳定的键后缀
     *   （`rotate_mode_inner` / `rotate_mode_outer` 用的就是它）。
     *   如果哪天有人把 `token` 改成 `form.name`，老记录会集体失效 ——
     *   而这是个**静默**故障（名单还在，只是再也匹配不上）。
     */
    @Test
    fun tokenUsesStorageKeyNotEnumName() {
        assertTrue(Uncontrollable.token(aweme, ScreenForm.OUTER).endsWith("@outer"))
        assertTrue(Uncontrollable.token(aweme, ScreenForm.INNER).endsWith("@inner"))
        assertFalse(Uncontrollable.token(aweme, ScreenForm.OUTER).endsWith("@OUTER"))
    }

    // ================================================================ 清除请求的判据

    /**
     * ★★ 这组用例是**修 bug 修出来的**（2026-09-29 真机验证抓到）。
     *
     * 第一版拿"`seen` 是不是空串"来判断"引擎有没有认过路"，于是
     * 「读到的值确实是空」（从来没人点过清除）与「还没读过」**撞成同一个值** ——
     * 冷启动后的**第一次真实清除请求**会被当成"首次记账"静默丢掉。
     * 真机复现：把 `uncontrollable_clear` 从"不存在"改成"一个新时间戳"，
     * 引擎收到后纹丝不动，名单一条都没少。
     *
     * ⇒ 结论写在这里防止回退：**凡是"首次"语义，就必须有独立的第一状态**，
     *   不能借值域里的一个特殊值充当。
     */
    @Test
    fun clearRequestNeedsPrimingButNotTwice() {
        // 冷启动第一次读数（值也是空）⇒ 只记账，不执行
        assertFalse(shouldRunClear(primed = false, seen = "", req = ""))
        // ★ 关键那条：冷启动之后**紧接着**来的一个真实请求 ⇒ 必须执行
        assertTrue(shouldRunClear(primed = true, seen = "", req = "1759100400000"))
        // 冷启动第一次读数**带值**（上次点过的残留）⇒ 也只记账（否则每次重启都清空一次记录）
        assertFalse(shouldRunClear(primed = false, seen = "", req = "1759100400000"))
        assertFalse(shouldRunClear(primed = false, seen = "old", req = "new"))
    }

    @Test
    fun clearRequestSkipsEmptyAndRepeat() {
        // 从来没人点过（值为空）⇒ 无事可做
        assertFalse(shouldRunClear(primed = true, seen = "", req = ""))
        assertFalse(shouldRunClear(primed = true, seen = "x", req = ""))
        // 还是上次那个值（StateFlow 会重放、2 秒轮询也会重复读到）⇒ 不重复执行
        assertFalse(shouldRunClear(primed = true, seen = "123", req = "123"))
        // 换成新值 ⇒ 执行（用户连点两次也要各算一次）
        assertTrue(shouldRunClear(primed = true, seen = "123", req = "124"))
    }
}
