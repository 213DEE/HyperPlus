package cn.dsr213.hyperplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台门 —— **应用白名单判据**的纯逻辑测试（2026-09-28 判据换代后整篇重写）。
 *
 * ============================ 为什么是重写而不是改几条 ============================
 * 老判据（读前台 Activity 声明的 `screenOrientation` 去"推"它会不会自管朝向）留下过
 * 一整组测试：`everyKnownExplicitOrientationYields` / `semiAllowsBehindBecause...` /
 * `isSelfManagedMatchesSemiStopSet`… 它们守的是"哪个声明值算自管朝向"。
 *
 * 判据换成白名单之后，`decide` **已经不吃 orientation 了** —— 那些断言要么编译不过、
 * 要么"跑得过但已经和代码没关系了"（后者更危险：会让人以为分档那套还在）。
 * 所以整篇推倒重写，改为守下面四条：
 *
 * 1. **[ForegroundGate.decide] 的三态语义** —— 尤其是"读不到前台"必须走 UNKNOWN 而
 *    不是 YIELD。判错方向的代价不对称：多干活的代价是一点电量，误停的代价是
 *    "功能整个消失"，而且用户不知道为什么。
 * 2. **[AppWhitelist.resolve] 的两份数据合成** —— 默认清单 + 用户增删。
 *    ⚠️ 09-29 之前这里还有第三份"恒豁免的自己"，同一天被删掉了（见 [AppWhitelist] 类注释
 *    "记过案"）；现在**本应用自己默认不豁免**，所以它该走"不在名单里 ⇒ 继续管"那条。
 * 3. **用户拍板过的两条边界**：短视频**不**进默认清单（否则抖音里再也不弹按钮）、
 *    常见游戏**进**默认清单（否则打游戏继续抢推理、继续断触）。
 * 4. **落盘编解码的稳定性** —— 引擎侧的变更检测是"整个字符串比对"，编码不稳定会报假变更。
 */
class ForegroundGateTest {

    // ================================================================ 三态判定

    @Test
    fun nullPackageIsUnknown() {
        assertEquals(
            ForegroundGate.Decision.UNKNOWN,
            ForegroundGate.decide(null, yield = false),
        )
        // 读不到时，白名单标记是什么都不该改变结论 —— 我们连是谁都不知道
        assertEquals(
            ForegroundGate.Decision.UNKNOWN,
            ForegroundGate.decide(null, yield = true),
        )
    }

    @Test
    fun emptyPackageIsUnknownToo() {
        // 空串与 null 同等对待：`ForegroundProbe` 拿不到组件名时给的就是空
        assertEquals(ForegroundGate.Decision.UNKNOWN, ForegroundGate.decide("", yield = true))
    }

    @Test
    fun whitelistedPackageYields() {
        assertEquals(
            ForegroundGate.Decision.YIELD,
            ForegroundGate.decide("com.tencent.tmgp.sgame", yield = true),
        )
    }

    @Test
    fun nonWhitelistedPackageIsManageable() {
        assertEquals(
            ForegroundGate.Decision.MANAGEABLE,
            ForegroundGate.decide("com.tencent.mm", yield = false),
        )
    }

    /**
     * ★★ 本组最要紧的一条不变量：**UNKNOWN 只可能来自"读不到前台"**，
     *   绝不可能因为白名单状态而产生。
     */
    @Test
    fun unknownOnlyComesFromUnreadableForeground() {
        val pkgs = listOf(null, "", "com.tencent.mm", "com.tencent.tmgp.sgame")
        pkgs.forEach { p ->
            listOf(true, false).forEach { wl ->
                val d = ForegroundGate.decide(p, wl)
                if (p.isNullOrEmpty()) {
                    assertEquals("pkg=$p wl=$wl 应当 UNKNOWN", ForegroundGate.Decision.UNKNOWN, d)
                } else {
                    assertTrue(
                        "读得到前台时不得返回 UNKNOWN（pkg=$p wl=$wl）",
                        d != ForegroundGate.Decision.UNKNOWN,
                    )
                }
            }
        }
    }

    /**
     * 决策**只**由"在不在白名单里"决定，与包名长什么样无关。
     *
     * ★ 这条是把"没有按包名的特判"这件事钉成断言 —— 老判据的毛病正是一堆按声明值的特判，
     *   每加一条都是在赌某个应用的语义。新判据里这种赌注应该为零。
     */
    @Test
    fun decisionDependsOnlyOnTheWhitelistFlag() {
        listOf("com.a", "com.b.c", "com.tencent.tmgp.sgame", "cn.dsr213.hyperplus", "com.miui.home")
            .forEach { p ->
                assertEquals("$p 在白名单里 → 必须停手", ForegroundGate.Decision.YIELD, ForegroundGate.decide(p, true))
                assertEquals("$p 不在白名单里 → 必须继续管", ForegroundGate.Decision.MANAGEABLE, ForegroundGate.decide(p, false))
            }
    }

    // ================================================================ 白名单生效集合

    @Test
    fun defaultsAreInEffectWithNoUserInput() {
        val wl = AppWhitelist.resolve(added = emptySet(), removed = emptySet())
        listOf(
            "com.tencent.tmgp.sgame",   // 王者荣耀
            "com.netease.dwrg",         // 第五人格
            "com.miHoYo.Yuanshen",      // 原神
            "tv.danmaku.bili",          // 哔哩哔哩
            "com.qiyi.video",           // 爱奇艺
        ).forEach { assertTrue("$it 应当默认就在生效白名单里", it in wl) }
    }

    /**
     * ★★ 用户拍板的边界之一：**短视频不进默认白名单**。
     *
     * 把短视频收进来等价于"在抖音里永不弹按钮、方向交回系统自转"—— 那正是用户
     * **上一轮**报的那个现象（原话「为什么在抖音里面会自动旋转？而不是让我手动确认？」）。
     * 两条要求会打架，用户 2026-09-28 选了**保留按钮**这一侧。
     *
     * ⛔ 谁要是顺手把抖音加进 `DEFAULT_VIDEO`，这条会红 —— 那是**故意**的。
     */
    @Test
    fun shortVideoAppsAreDeliberatelyNotDefaultWhitelisted() {
        val mustNot = listOf(
            "com.ss.android.ugc.aweme",   // 抖音
            "com.smile.gifmaker",         // 快手
            "com.xingin.xhs",             // 小红书
        )
        mustNot.forEach {
            assertFalse("$it 不得进默认清单（用户要求保留旋转按钮）", it in AppWhitelist.DEFAULT_PACKAGES)
        }
        // 而且生效集合里也不能有它（防止从别的来源被捎带进去）
        val wl = AppWhitelist.resolve(emptySet(), emptySet())
        mustNot.forEach { assertFalse("$it 不得出现在生效白名单里", it in wl) }
    }

    /** 用户关掉的默认项必须**真的**关掉 —— 减集必须是独立的，不能被默认值顶回来 */
    @Test
    fun userRemovalsBeatDefaults() {
        val wl = AppWhitelist.resolve(added = emptySet(), removed = setOf("com.tencent.tmgp.sgame"))
        assertFalse("用户手动关掉的默认项必须失效", "com.tencent.tmgp.sgame" in wl)
        // 同一减集不该误伤别人
        assertTrue("减掉王者不该把原神也减掉", "com.miHoYo.Yuanshen" in wl)
    }

    @Test
    fun userAdditionsSurviveDefaults() {
        val third = "com.example.someindiegame"
        assertFalse("前提：这个包不在默认清单里", third in AppWhitelist.DEFAULT_PACKAGES)
        val wl = AppWhitelist.resolve(added = setOf(third), removed = emptySet())
        assertTrue(third in wl)
    }

    /**
     * ★★ 用户 2026-09-29 拍板：**本应用自己默认不豁免**。
     *
     * 起因是他的第 3 问：「我们的模块 app 本身为什么不受旋转按钮的控制？」——
     * 那之前的实现里有一份 `ALWAYS_EXEMPT = setOf(OWN_PACKAGE)` 把它**恒豁免**了
     * （理由听着很正当："模块把自己的设置页锁住方向，任何解释都说不通"），
     * 但代价是这个应用在名单里**看不见、也关不掉** —— 一个用户无法观察也无法撤销的隐式行为。
     *
     * ⇒ 现在它是名单里的普通一行、**默认不勾**：想在设置页里跟随系统转，用户自己打开那一格。
     *   ⛔ 别再给 OWN_PACKAGE 加任何"减不掉"的特权。
     */
    @Test
    fun ownPackageIsNotExemptByDefault() {
        val wl = AppWhitelist.resolve(added = emptySet(), removed = emptySet())
        assertFalse("默认不该豁免自己", AppWhitelist.OWN_PACKAGE in wl)
        assertFalse("减集也减得动它（它没有任何特权）", AppWhitelist.OWN_PACKAGE in
            AppWhitelist.resolve(emptySet(), setOf(AppWhitelist.OWN_PACKAGE)))
        assertTrue("加集加得回来 —— 界面上那一格是能勾的", AppWhitelist.OWN_PACKAGE in
            AppWhitelist.resolve(setOf(AppWhitelist.OWN_PACKAGE), emptySet()))
        // ⇒ 于是"设置页自己不跟随系统旋转"是**用户自己的选择**，而不是一个隐式行为
    }

    @Test
    fun isDefaultMatchesTheBundledList() {
        assertTrue(AppWhitelist.isDefault("com.tencent.tmgp.sgame"))
        assertFalse(AppWhitelist.isDefault("com.example.not.in.list"))
        assertFalse(
            "自己不在出厂清单里 ⇒ 界面上的「默认」标记不该打到它头上",
            AppWhitelist.isDefault(AppWhitelist.OWN_PACKAGE),
        )
    }

    /** 默认清单里的包名至少得像包名（防手打错：多一个空格、用了中文标点、漏了段） */
    @Test
    fun everyDefaultPackageLooksLikeAPackageName() {
        // ⚠️ 允许大写：`com.miHoYo.Yuanshen` 就是官方那个写法
        val re = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        AppWhitelist.DEFAULT_PACKAGES.forEach { p ->
            assertTrue("默认清单里的包名格式可疑：$p", re.matches(p))
        }
    }

    // ================================================================ 落盘编解码

    @Test
    fun encodeDecodeRoundTrips() {
        val s = setOf("com.b.bb", "com.a.aa", "com.c.cc")
        assertEquals(s, AppWhitelist.decode(AppWhitelist.encode(s)))
    }

    /**
     * ★ 同一个集合无论按什么顺序装进去，落盘字符串必须一致。
     *
     * 引擎侧的配置变更判据是**整快照比对**（见 `ModulePrefs.advanceBaseline`）：
     * 编码不稳定 ⇒ 内容没变却报"变了" ⇒ 每次轮询都触发一次白名单重算与重判。
     */
    @Test
    fun encodeIsOrderIndependent() {
        assertEquals(
            AppWhitelist.encode(setOf("com.b.bb", "com.a.aa")),
            AppWhitelist.encode(setOf("com.a.aa", "com.b.bb")),
        )
    }

    @Test
    fun decodeToleratesMessyInput() {
        // 手改 prefs 文件 / 老版本留下的逗号分隔 / 空行与行尾空白，都该吃下去，
        // 且**不许造出空包名**（空的进了集合就会变成"有个包名是空串"这种鬼东西）
        val raw = "\n com.a.aa ,\n\ncom.b.bb\t\n  , com.c.cc  \n"
        assertEquals(setOf("com.a.aa", "com.b.bb", "com.c.cc"), AppWhitelist.decode(raw))
    }

    @Test
    fun decodeOfNullAndBlankIsEmptyNotCrash() {
        assertEquals(emptySet<String>(), AppWhitelist.decode(null))
        assertEquals(emptySet<String>(), AppWhitelist.decode(""))
        assertEquals(emptySet<String>(), AppWhitelist.decode("  \n \t ,,"))
    }

    // ================================================================ 可读名（仅诊断）

    @Test
    fun labelsAreStillAvailableForDiagnostics() {
        // ⚠️ 这些名字**不再参与判断**（判据是白名单），只用于日志里说明"它自己声明了什么朝向"。
        //   留着它们是因为"为什么这个应用不转"的第一手线索仍然是它声明了什么。
        assertEquals("UNSPECIFIED", ForegroundGate.label(-1))
        assertEquals("PORTRAIT", ForegroundGate.label(1))
        assertEquals("USER", ForegroundGate.label(2))
        assertEquals("BEHIND", ForegroundGate.label(3))
        assertEquals("SENSOR", ForegroundGate.label(4))
        assertEquals("SENSOR_LANDSCAPE", ForegroundGate.label(6))
        assertEquals("FULL_USER", ForegroundGate.label(13))
    }

    @Test
    fun labelsAreDistinctAndNeverFallThroughForKnownValues() {
        val labels = (-1..14).map { ForegroundGate.label(it) }
        assertEquals("可读名不得重复（否则日志分不清是哪个值）", labels.size, labels.toSet().size)
        labels.forEach { l -> assertFalse("未被识别的取值：$l", l.startsWith("?#")) }
    }

    @Test
    fun unknownValueIsMarkedInLabel() {
        assertEquals("?#99", ForegroundGate.label(99))
    }

    // ================================================================ 真机样张回归

    /**
     * 2026-09-28 本机（Android 17 / MIUI V816）的样张，锁住**判据换代后 + 09-29 收敛后**的结论。
     *
     * ★ 与老版本的区别：这里断言的依据不再是"它声明了什么朝向"，而是"它在不在名单里"。
     *   两条最要紧的：
     *   - 抖音那条是用户上一轮明确要的交互，它红了 = 有人把短视频收进了默认清单。
     *   - `cn.dsr213.hyperplus`（本应用自己）那条在 09-29 翻过面 —— 从"恒豁免 ⇒ 停手"
     *     变成了"默认不在名单 ⇒ 继续管"。它红了 = 有人把 `ALWAYS_EXEMPT` 捡回来了。
     */
    @Test
    fun realDeviceSamplesUnderWhitelistPolicy() {
        val wl = AppWhitelist.resolve(added = emptySet(), removed = emptySet())
        val samples = mapOf(
            "com.tencent.tmgp.sgame" to true,    // 王者荣耀：默认清单里的游戏 ⇒ 停手
            "com.ss.android.ugc.aweme" to false, // ★ 抖音：刻意不在清单里 ⇒ 继续管（弹按钮）
            "com.miui.home" to false,            // 桌面：不在清单 ⇒ 继续管（内屏桌面确实能被我们转）
            "com.tencent.mm" to false,           // 微信：不在清单 ⇒ 继续管
            "cn.dsr213.hyperplus" to false,      // 本应用自己：默认不豁免 ⇒ 继续管（能弹按钮）
        )
        samples.forEach { (pkg, shouldYield) ->
            assertEquals(
                "$pkg 的决策",
                if (shouldYield) ForegroundGate.Decision.YIELD else ForegroundGate.Decision.MANAGEABLE,
                ForegroundGate.decide(pkg, yield = pkg in wl),
            )
        }
    }
}
