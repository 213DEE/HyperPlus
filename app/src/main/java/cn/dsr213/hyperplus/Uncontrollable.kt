package cn.dsr213.hyperplus

/**
 * 「实测不可控」名单 —— **A 方案**（2026-09-29 用户拍板）的落盘层。
 *
 * ============================ 为什么需要它（不是优化，是修 bug） ============================
 * 用户报「外屏抖音**弹了按钮但按了不转**」。排查结论（完整四层取证见
 * `docs/外屏抖音弹按钮不能转_2026-09-29.md`）：**"猜应用声明了什么"这条路补不全**——
 *
 * | 来源 | 同刻读到什么 |
 * |---|---|
 * | 引擎探针（`ForegroundProbe`） | `fgori=BEHIND`（抖音 `MainActivity` 的**清单静态声明**） |
 * | 系统 WMS | `mCurrentAppOrientation=NOSENSOR`，方向来源 `WindowedMagnification:0:31@…`（**根本不是 activity**） |
 * | 屏幕实况 | 引擎写了 `user_rotation=1`，外屏 `rotation` 仍是 0 ⇒ **真没转** |
 *
 * ⇒ 两个数字对不上，而且**探针读的对象与系统用的对象不是同一个**（当时栈里只有
 *   `SplashActivity` 与 `UltraDetailActivity`，两者都不是 `BEHIND`）。
 *   所以「换一张更全的声明表」也是白费 —— 病根不在表全不全。
 *
 * ============================ A 方案：不猜，只观测 ============================
 * 用户拍板选 A（其余三方案与对比见文档 §4），做法是：
 *
 * > **点按钮 → 写方向 → 延时读回屏幕的真实 `rotation` → 没变就记住「这个包 + 这块屏不可控」**，
 * > 此后对该组合**静默停手**（不弹按钮），界面上标红字说明。
 *
 * ★ 它为什么比"读声明"可靠：**不需要任何关于 ROM 语义的推断**。
 *   `BEHIND` 继承下层、`NOSENSOR`、运行时临时改方向、厂商私有的窗口级方向来源 ——
 *   全都被「实测转不动」这一个**事实**统一覆盖，不需要逐个建模。
 * ★ 代价（如实记下）：**第一次仍会弹一次假按钮** —— 必须先观测到才能有结论。
 *   所以写入要"两次读回都没变"才落笔（见 `AdaptiveEngine.scheduleUncontrollableCheck`），
 *   否则会把"系统忙、还没来得及转"误记成不可控，那比不记更糟（功能永久消失且没理由）。
 *
 * ⛔⛔ **归因前置条件（2026-09-29 真机事故后补，别再漏）**：
 *   判"这个应用在这块屏上转不动"之前，必须确认**方向盘真的握在我们手里**
 *   （`ACCELEROMETER_ROTATION == 0`，即系统的自动旋转是关的）。
 *   它是开的 ⇒ `USER_ROTATION` 会被传感器在几毫秒内覆盖 ⇒ 屏幕当然不动 ⇒
 *   这跟"应用能不能转"毫无关系，却会被记成它的错。
 *   实测对照（同一台机器、同一个美团）：`ACCELEROMETER_ROTATION=1` 时写方向屏幕不动、
 *   被误记；固定成 `0` 之后它转得好好的。
 *   ⛔ 所以"屏幕没动"**不能单独作为判据**，它必须带上"我们确实控制着方向盘"这个前提。
 *
 * ============================ 落盘在哪、为什么 ============================
 * 走 `Settings.System`（键 [KEY]）—— 因为它是**「引擎自己算出来的账」**，
 * 而这类数据的既定通道就是 `Settings.System`：引擎是特权包、直写免 root，App 读零门槛
 * （见 [PrefsBridge] 类注释里那张"谁写谁读走哪"的表）。
 *
 * ⛔ **不能走 App 的 prefs**：引擎写不了 App 的私有文件（跨 uid，DAC + SELinux 双重拦截）。
 * ⛔ **也不能让 App 来清**：App 没有 `WRITE_SETTINGS`（清单里没有这个权限），
 *   所以"清除"要做成 App 写自己 prefs 里的请求键 → 引擎读到后代为清除（见
 *   [PrefsBridge.UNCONTROLLABLE_CLEAR]）。
 *
 * ============================ 编码格式 ============================
 * ```
 * com.ss.android.ugc.aweme@outer,com.coolapk.market@outer
 * ```
 * - 一个条目 = `包名@形态`，形态取 [ScreenForm.storageKey]（`inner` / `outer`）。
 *   包名不含 `@` 与 `,` ⇒ 这两个分隔符不会歧义。
 * - ★ **必须带形态**：同一个应用完全可能"外屏转不动、内屏能转"
 *   （内屏 `ignoreOrientationRequest=true` ⇒ 应用声明被忽略 ⇒ 我们写得动）。
 *   把形态并掉会**误伤内屏** —— 这正是最该避免的那类错。
 *   ⚠️ 外屏的旋转增强 2026-09-29 已删 ⇒ 带 `@outer` 的条目现在**没有下游**
 *   （引擎只在内屏判这条），是删功能之前留下的记录，留着无害。
 *   ⇒ 界面计数一律只数 INNER 那一侧（[countFor]），别把两边加在一起。
 * - 排序后拼接：让"内容变了"与"字符串变了"一一对应（引擎侧变更检测是整串比对）。
 */
internal object Uncontrollable {

    /**
     * `Settings.System` 里的键名（`hyperplus_uncontrollable`）。
     *
     * ★ 前缀由 [PrefsBridge.full] 统一补，别在这里手写 `hyperplus_`（那是两处真相）。
     */
    val KEY: String = PrefsBridge.full("uncontrollable")

    /**
     * 条目之间的分隔符。
     *
     * ⚠️ 刻意用 `Char` 而不是 `String`：解析那侧要把分隔符**和**换行 / 空格一起传进
     *   `split`，而 Kotlin 的 `split` 有 `vararg Char` 与 `vararg String` 两个重载 ——
     *   混着传会直接编译不过（这个坑本文件第一次编译就踩到了）。
     */
    private const val ITEM_SEP = ','

    /** 包名与形态之间的分隔符 */
    private const val FORM_SEP = "@"

    /** 一个条目：`包名@形态` */
    fun token(pkg: String, form: ScreenForm): String = pkg + FORM_SEP + form.storageKey

    /**
     * 整串 → 条目集合。
     *
     * ★ 容忍空值 / 换行 / 多余空白 / 手改键值留下的杂物 —— 这个键是**可被外部改坏**的
     *   （`Settings.System` 对特权身份是开放的），解析层松一点，坏值只会变成"多一条
     *   匹配不到的条目"，不会让整张名单失效。
     */
    fun decode(raw: String?): Set<String> =
        raw?.split(ITEM_SEP, '\n', ' ', '\t')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    /**
     * 这个包在**这块屏**上是否已被实测标记为不可控。
     *
     * ⚠️ 读不到包名（`null` / 空）⇒ **false**（不豁免）—— 与 [ForegroundGate] 同一条纪律：
     *   **读不到就不下结论**，宁可退回"多干一点活"，也不许因为读不到就永久误停。
     */
    fun isMarked(raw: String?, pkg: String?, form: ScreenForm): Boolean =
        !pkg.isNullOrEmpty() && token(pkg, form) in decode(raw)

    /**
     * 追加一条（已存在则结果不变）。
     *
     * ★ 幂等：引擎可能对同一个「包 + 屏」重复观测到"转不动"，不能让它把键越写越长。
     *   排序后输出，也让"名单没变"表现为"字符串没变"。
     */
    fun withMarked(raw: String?, pkg: String, form: ScreenForm): String =
        (decode(raw) + token(pkg, form)).sorted().joinToString(ITEM_SEP.toString())

    /** 条目条数（界面统计用） */
    fun sizeOf(raw: String?): Int = decode(raw).size

    /**
     * 某块屏上的条目数。
     *
     * ★ 为什么需要它（2026-09-29）：外屏的旋转增强删掉之后，名单里**外屏**那部分条目
     *   已经没有任何下游（引擎只在内屏判这条），界面若把两边的条数加在一起，
     *   会出现"显示 3 条却只找得到 2 处红字"这种对不上账的现象。
     *   ⇒ 界面一律按 [ScreenForm.INNER] 计数（外屏那些老条目留着无害，只是不再被数）。
     */
    fun countFor(raw: String?, form: ScreenForm): Int =
        decode(raw).count { it.endsWith(FORM_SEP + form.storageKey) }

    /**
     * ⚠️ 2026-10-03 多语言：原来这里有一个 `fun note(form: ScreenForm): String`（返回中文），
     *   现在**已删除**。它唯一的调用点是白名单列表里的那行红字，而那是**纯界面文案** ——
     *   留在这里等于同一个说法有两份真值（一份 String、一份资源），迟早只改一处。
     *   ⇒ 文案现在住 `res/values` 系列三套 `strings.xml` 的 `uc_note`，由界面侧
     *     `stringResource` 取。
     *   ⛔ 别为了"方便"把它加回来。
     */
}
