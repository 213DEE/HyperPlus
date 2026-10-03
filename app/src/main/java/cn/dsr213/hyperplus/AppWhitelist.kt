package cn.dsr213.hyperplus

/**
 * 应用白名单 —— **「哪些应用不受本模块控制」的唯一判据**（2026-09-28 用户拍板）。
 *
 * ============================ 为什么把原来的判据换掉 ============================
 * 改造前，「要不要对某个前台应用停手」是**自动推**出来的：读该 Activity 声明的
 * `screenOrientation`，命中"自管朝向"的取值就停手。这套推法连着踩了两次真错：
 *
 * | 踩过的坑 | 现象 |
 * |---|---|
 * | `BEHIND`(3) 被当成"锁死方向" | 抖音里**功能整个消失**（屏幕还被交还给系统自转） |
 * | 探针读的是**静态声明值**，看不见 ATMS 的运行时解析 | 判据与真实行为长期不一致，且无从自查 |
 *
 * 用户 2026-09-28 的结论是**换判据本身**：「把（原来的）判断删了，改成应用白名单，
 * 白名单应用不受 app 控制」。这条判据的好处是它**不需要任何推断** ——
 * 名单是用户点的，命中与否是一次集合查找，不存在"猜错某个 ROM 的语义"这种事。
 *
 * 代价：默认什么都不豁免，用户得自己勾。用 [DEFAULT_PACKAGES] 把这个代价压到最小
 * （常见游戏 / 长视频默认进白名单）。
 *
 * ============================ 两件事，别混 ============================
 * - [DEFAULT_PACKAGES]：出厂默认进白名单的第三方应用（装了才可能命中，见 [resolve]）。
 * - 用户自己加 / 减的：落在 `AppPrefs` 里（[PrefsBridge.WHITELIST_ADD] / `WHITELIST_REMOVE`）。
 *
 * ============================ 「检测到有就默认开启」怎么落地 ============================
 * 不是"首次运行时把已装的扫一遍写进配置"，而是**每次现算**：
 *
 * ```
 * 生效集合 = (DEFAULT_PACKAGES ∪ 用户加的) − 用户关掉的
 * ```
 *
 * ★ 为什么**不**检查"这个包现在装没装"：没装的应用不可能成为前台应用，
 *   把它留在集合里**没有任何副作用**，却换来一个重要的好处 ——
 *   **用户以后新装一个默认清单里的游戏，不用再打开本应用勾一次，立刻就是白名单**。
 *   这也正是"检测到有就默认开启"的字面意思。
 *
 * ★ 也正因为"用户关掉的"是一份**独立的减集**，用户手动关过的应用不会被默认值反复顶回来。
 *
 * ============ ⛔ 2026-09-29 删掉的三样东西（记过案，别再捡回来） ============
 *
 * ### ① 外屏的旋转增强（整个功能）
 * 用户 09-29 拍板：「**直接删除外屏的旋转增强，只保留内屏的旋转增强和应用豁免**」。
 * ⇒ 外屏**一点不介入**：不写方向、不开相机、不弹按钮。本对象的那份名单因此只服务内屏。
 *   实现上的唯一闸在 `AppPrefs.refreshEffectiveMode()`：跑在外屏时"生效档"恒为 SYSTEM。
 *
 * ### ② 内外屏解耦（三层名单：内屏 / 外屏 / 全局）
 * 09-29 上午用户要求把名单按内外屏解耦，理由是两块屏**对"应用声明"的态度不同**
 * （外屏 `ignoreOrientationRequest=false` ⇒ 声明生效 ⇒ 我们写 `user_rotation` 无效）。
 * 同一天外屏增强被删（见 ①），"外屏豁免"就**没有可豁免的对象**了，
 * 三层立刻退化成一层 ⇒ **名单收成一份**（用的就是当时那个"全局层"的键，
 * 用户以前勾过的包一个都不用重勾）。
 *
 * ### ③ 按应用声明判「外屏强制豁免」（`isOuterForced` / `OUTER_FORCED_ORIENTATIONS`）
 * 它按"应用在清单里声明了什么朝向"推断"外屏上转不动它"，然后**强制豁免且不允许关闭**。
 * 用户随后的反馈：「有些软件其实是可以旋转的，但被识别成不可旋转，强制豁免了，然后还关不掉」。
 * 病根有两处，都是结构性的：
 * 1. **判据读错了对象**：界面读的是**启动页**（`CATEGORY_LAUNCHER` 解析出来那个 Activity）
 *    的声明，而引擎读的是**当前栈顶 Activity** 的声明 —— "启动页声明 PORTRAIT、
 *    主界面不声明"是极常见的写法，于是一个能转的应用被界面标成"不可转、不许关"。
 * 2. **"不许关"这个设计本身就是坏的**：判错了用户没有任何自救手段。
 *
 * ⇒ 现在只留**一条**豁免判据：**实测**（[Uncontrollable]——「我们真的写过方向、
 *   屏幕两次读回都没动」）。它不解释任何 ROM 语义，判错了还有"清除重测"的出口。
 */
internal object AppWhitelist {

    /** 本应用自己的包名。**全工程只在这里定义一次**（别处一律引它） */
    const val OWN_PACKAGE = "cn.dsr213.hyperplus"

    /**
     * 默认白名单：**游戏**。
     *
     * ★ 为什么要预置：用户原话「把市面上常见的游戏、视频应用的包名设置为默认白名单」。
     *   游戏是"停手"最主要的受益者（每轮触发 = 开前摄 + 8 帧 ML Kit 推理 ≈ 32 次/秒，
     *   与游戏抢 CPU/GPU 的表现就是"断触"）。
     *
     * ⚠️ 这份清单是**尽力而为**（按 2026-09 国内常见游戏整理，包名以官方应用商店为准），
     *   而且**写错无害**：不存在的包名永远不会成为前台应用，只是白占一行。
     *   所以判据是"宁多勿少"——漏掉一个游戏的代价是"它在打游戏时还在抢推理"，
     *   多写一个错包名的代价是零。用户也能在界面上随时开关。
     */
    val DEFAULT_GAMES: Set<String> = setOf(
        // —— 腾讯系 ——
        "com.tencent.tmgp.sgame",           // 王者荣耀
        "com.tencent.tmgp.pubgmhd",         // 和平精英
        "com.tencent.tmgp.dfm",             // 三角洲行动
        "com.tencent.tmgp.cf",              // 穿越火线：枪战王者
        "com.tencent.tmgp.cod",             // 使命召唤手游
        "com.tencent.tmgp.speedmobile",     // QQ 飞车手游
        "com.tencent.lolm",                 // 英雄联盟手游
        "com.tencent.jkchess",              // 金铲铲之战
        "com.tencent.tmgp.supercell.clashofclans",   // 部落冲突（国服）
        "com.tencent.tmgp.supercell.clashroyale",    // 皇室战争（国服）
        // —— 网易系 ——
        "com.netease.dwrg",                 // 第五人格
        "com.netease.hyxd",                 // 荒野行动
        "com.netease.mrzh",                 // 明日之后
        "com.netease.party",                // 蛋仔派对
        "com.netease.sky",                  // 光·遇
        "com.netease.onmyoji",              // 阴阳师
        "com.netease.race",                 // 王牌竞速
        // —— 米哈游 / 二次元 ——
        "com.miHoYo.Yuanshen",              // 原神
        "com.miHoYo.hkrpg",                 // 崩坏：星穹铁道
        "com.miHoYo.Nap",                   // 绝区零
        "com.miHoYo.bh3",                   // 崩坏 3
        "com.miHoYo.ys.mi",                 // 云·原神
        "com.hypergryph.arknights",         // 明日方舟
        "com.bilibili.azurlane",            // 碧蓝航线
        "com.papegames.nn4",                // 无限暖暖
        // —— 其他大厂 / 国际服 ——
        "com.ztgame.bob",                   // 球球大作战
        "com.mojang.minecraftpe",           // 我的世界
        "com.roblox.client",                // Roblox
        "com.supercell.clashofclans",       // 部落冲突（国际服）
        "com.supercell.clashroyale",        // 皇室战争（国际服）
        "com.supercell.brawlstars",         // 荒野乱斗（国际服）
        "com.blizzard.diablo.immortal",     // 暗黑破坏神：不朽
    )

    /**
     * 默认白名单：**长视频 / 播放器**。
     *
     * ⛔⛔ **刻意不含短视频应用** —— 这是用户 2026-09-28 明确拍板的分界，别顺手加回来：
     *
     * | 不收 | 为什么 |
     * |---|---|
     * | `com.ss.android.ugc.aweme` 抖音 | 用户**上一轮**才明确要求「在抖音里要弹按钮让我确认」 |
     * | `com.smile.gifmaker` 快手 | 同上（同属无限下滑的短视频，旋转按钮才是想要的交互） |
     * | `com.xingin.xhs` 小红书 | 同上（图文/短视频流） |
     *
     * 把短视频收进白名单，等价于"在抖音里永不弹按钮、方向交回系统自转"——
     * 那正是用户上一轮报的那个现象（原话「为什么在抖音里面会自动旋转？而不是让我手动确认？」）。
     * ⇒ 两条要求会打架，用户选了**保留按钮**这一侧。
     */
    val DEFAULT_VIDEO: Set<String> = setOf(
        "com.qiyi.video",                   // 爱奇艺
        "com.tencent.qqlive",               // 腾讯视频
        "com.youku.phone",                  // 优酷
        "tv.danmaku.bili",                  // 哔哩哔哩
        "tv.danmaku.bilibilihd",            // 哔哩哔哩 HD
        "com.hunantv.imgo.activity",        // 芒果 TV
        "com.ss.android.article.video",     // 西瓜视频
        "com.sohu.sohuvideo",               // 搜狐视频
        "com.miui.video",                   // 小米视频（预装）
        "com.mxtech.videoplayer.ad",        // MX Player
        "org.videolan.vlc",                 // VLC
    )

    /** 出厂默认集合（游戏 + 长视频）。界面用它渲染"默认"标记 */
    val DEFAULT_PACKAGES: Set<String> = DEFAULT_GAMES + DEFAULT_VIDEO

    /**
     * 算出**生效**白名单 —— 名单只有这一份（见类注释"记过案"那段）。
     *
     * @param added 用户手动开启的包
     * @param removed 用户手动关闭的包。**必须是一份独立存下来的减集**，理由见
     *   [PrefsBridge.WHITELIST_REMOVE]：只删加集的话，[DEFAULT_PACKAGES] 里那些包
     *   会在下一次现算时被默认值顶回来。
     */
    fun resolve(added: Set<String>, removed: Set<String>): Set<String> =
        DEFAULT_PACKAGES + added - removed

    /**
     * 这个包是不是出厂默认就在白名单里（装了就该默认开）。
     *
     * ★ 界面用它决定开关的**初始状态**：`isDefault && !removed` ⇒ 开。
     */
    fun isDefault(pkg: String): Boolean = pkg in DEFAULT_PACKAGES

    // ---------------------------------------------------------------- 落盘编解码

    /**
     * 集合 → 单键字符串（每行一个包名，排序后）。
     *
     * ★ 为什么不用 `putStringSet`：名单要穿过一条**序列化**的通道（[ConfigChannel] 的
     *   长度前缀格式），而 `Set` 在 `SharedPreferences` 的 XML 里是嵌套标签、
     *   在那套格式里也只能退化成 `toString()`；同时引擎侧的"内容变没变"判据是
     *   **整快照比对**（见 `ModulePrefs.advanceBaseline`）—— 用单个字符串，
     *   变更就天然是一个可打印的差异，诊断日志里能直接看出"改了哪几个包"。
     *   ⚠️ 2026-10-03 之前这里的理由挂在 nsp 那条腿（读的是文件内容）上，那条腿已删除；
     *     换成广播快照之后结论**没变**（仍然要单键字符串），但依据换了，别再引旧依据。
     */
    fun encode(s: Set<String>): String = s.sorted().joinToString("\n")

    /** 单键字符串 → 集合（容忍空 / 多余空白 / 手改文件留下的空行） */
    fun decode(raw: String?): Set<String> =
        raw?.split('\n', ' ', '\t', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
}
