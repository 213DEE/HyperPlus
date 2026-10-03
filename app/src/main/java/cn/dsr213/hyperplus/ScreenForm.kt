package cn.dsr213.hyperplus

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.annotation.StringRes
import kotlin.math.min

/**
 * 「现在这块屏是内屏还是外屏」—— **内/外屏模式解耦的判据**。
 *
 * ============================ 用户诉求（2026-09-28）============================
 * 原话：「内屏和外屏的模式解耦（内屏和外屏可以设置不同模式）」。
 * 场景很实在：合上手机只用外屏时，人脸识别意义不大（外屏小、常是扫码/看通知），
 * 半自动更省电；展开用内屏时又想让它自动转。以前只有**一份**全局模式，
 * 折起来和展开只能是同一档。
 *
 * ============================ 判据怎么取的（这是本文件最需要解释的部分）============================
 * 取**当前显示的最小宽度（dp）**分档。设备实测（`wm density` = 440dpi ⇒ 2.75x，
 * `dumpsys display` 两块屏 `densityDpi` 也都是 440）：
 *
 * | | 像素 | 换算（px ÷ 2.75） | 判定 |
 * |---|---|---|---|
 * | 内屏 | 1672 × 2364 | **608dp** | ≥ 512dp ⇒ [INNER] |
 * | 外屏 | 1168 × 1712 | **425dp** | < 512dp ⇒ [OUTER] |
 *
 * ★ 为什么用**最小宽度**而不是宽或高：它是**旋转不变量**。
 *   内屏横过来是 2364×1672，直接取宽会得到 860dp；外屏横过来是 1712×1168，
 *   取宽得到 623dp —— **两个都跑到阈值上面去了**，横屏时外屏会被误判成内屏
 *   （竖屏能分、横屏不能分，这种判据比没有还危险）。
 *   取 `min(宽, 高)` 则无论怎么转：内屏恒 608dp、外屏恒 425dp。
 *   ⇒ 转屏**不会**让你的模式凭空换一档（这条是硬要求，不是优化）。
 *
 * ★ 为什么阈值取 **512dp**：实测两个值 425 / 608 之间，512 大致是它们的**几何中点**
 *   （608/512 ≈ 1.19，512/425 ≈ 1.20），两侧各留约 20% 余量。
 *   ⚠️ 这个余量不是"随便留的"：dp 值 = 像素 ÷ 密度，而**用户改「显示大小 / DPI」
 *   会让密度整体变一次**（两层屏同比例变）。要让分档仍然正确，密度必须落在
 *   ≈370~530dpi 之间（默认 440 正在中间）。超出这个范围（比如密度被改成 360），
 *   **外屏的 dp 值会长到 512 以上而被误判成内屏**。真要跨密度稳，判据得改成
 *   比**像素**（1672 vs 1168 不随密度变）或者读设备的物理折叠状态 —— 目前没做，
 *   因为这是一台固定机型，默认密度下两层屏各留了 ±20% 余量。
 *   （🔎 推断：MIUI 的显示大小档位幅度约在 ±15% 内，所以默认设置下不会踩到边界；
 *     未实测各档位的确切 dpi，若发现"改显示大小后模式串档"，先来改这里。）
 *
 * ⚠️ **诚实标注**：这套判据是**按尺寸推形态**（🔎 推断），不是读设备的物理折叠状态。
 *   之所以不读 `DeviceStateManager`：它的 `getCurrentState()` 在部分机型上返回
 *   恒定值 / 需要额外权限，一旦读不到就得写兜底，而**尺寸是任何设备上都拿得到的
 *   同名事实**，且正好是"用户看到的屏幕"本身。用户也确认过
 *   「display 0 会跟随主屏状态切换」⇒ 主屏换了，这里量到的尺寸就换了。
 *   若将来换到尺寸相近的折叠机（内外屏最小宽度差不到 20%），只需改 [INNER_MIN_WIDTH_DP]。
 *
 * ★ 数据来源优先级：
 *   ① `WindowManager.maximumWindowMetrics.bounds` —— **首选**。它是 WMS 给的
 *      "这块屏最大能有多大"，即**显示区的完整尺寸**，与调用方是不是 Activity 无关，
 *      也**不受状态栏/导航栏扣减**。这一条是本文件在 2026-09-28 改过一次的地方，原因见下；
 *   ② 退路 `currentWindowMetrics.bounds` —— 在 Activity 里它是**应用窗口**（已扣掉系统栏）。
 *      ⚠️ 拿它判形态有真实风险：内屏横屏时窗口高约 1500px，最小边只剩 ≈545dp，
 *      离 512dp 的阈值只有 ≈33dp —— 系统栏再高一点就会被**误判成外屏**，
 *      症状是"横过来之后模式悄悄换了一档"。所以它只做退路。
 *   ③ 再退 `Resources.displayMetrics` —— 本工程实测曾给出过 2.6 倍的错值，最后兜底。
 */
enum class ScreenForm(
    /** 落进 prefs 的键后缀（`rotate_mode_inner` / `rotate_mode_outer` 的 `inner`/`outer`） */
    val storageKey: String,
    /**
     * **日志口径**的名字（引擎侧写 logcat 用，本文件里那三处 `form.label` 都是日志）。
     *
     * ⚠️ 2026-10-03 多语言改造时**刻意保留**它：日志要固定说法、且引擎读不到
     *   App 里选的语言（见 `AppPrefs.RotateMode.label` 那段注释，理由相同）。
     *   ⛔ 别拿它显示在界面上 —— 界面用 [labelRes]。
     */
    val label: String,
    /** **界面口径**的名字（多语言）。⚠️ 与 [label] 同义，改文案要一起改 */
    @StringRes val labelRes: Int,
) {
    INNER("inner", "内屏", R.string.form_inner),
    OUTER("outer", "外屏", R.string.form_outer);

    /** 另一块屏 —— 用于"切换形态时该看哪一份配置"这类对称逻辑 */
    val other: ScreenForm get() = if (this == INNER) OUTER else INNER

    companion object {

        private const val TAG = "HyperPlusScreenForm"

        /**
         * 内 / 外屏的最小宽度分界。**这是全工程唯一的形态判据**，改动只需改这一处。
         *
         * ★ 实测两块屏分别落在 **608dp（内）/ 425dp（外）**，512 大致是它们的几何中点，
         *   两侧各留约 20% 余量（换算与容差推导见类注释）。
         *
         * ⚠️ **2026-09-28 更正**：原值是 360dp，那是按"内屏 409dp / 外屏 286dp"（错误密度）
         *   推的。实测两块屏**都**在 360 之上 ⇒ 判定恒为 [INNER]，
         *   也就是"内外屏模式解耦"实际从来没生效过（外屏那份配置永远是死的）。
         */
        const val INNER_MIN_WIDTH_DP = 512

        /** 从 prefs 里的字符串还原（未知 / 空 ⇒ null，由调用方决定兜底） */
        fun fromStorage(v: String?): ScreenForm? =
            values().firstOrNull { it.storageKey == v }

        /** 当前形态。判定不出来时**返回 [INNER]** —— 宁可当内屏（配置界面就在内屏上），也不要抛 */
        fun of(context: Context): ScreenForm = probe(context).form

        /**
         * 量一次并给出形态判定，连同**测量值本身**一起返回。
         *
         * ★ 为什么要把 `smallestWidthDp` 和 `source` 带出来：判定错了（比如换了台机器）
         *   第一件要知道的事就是"它到底量到了多少、从哪儿量的"。只回一个枚举的话，
         *   诊断页只能显示"判成了内屏"，等于没信息。
         *
         * ⚠️ **本函数不打日志**：界面重组时会被高频调用，打日志会刷屏。
         *   需要留下痕迹的地方（形态**真的变了**）由 `AppPrefs.updateScreenForm` 负责。
         */
        fun probe(context: Context): Probe {
            val m = DisplaySize.of(context)
            return Probe(ofSmallestWidth(m.smallestWidthDp), m.smallestWidthDp, m.source)
        }

        /**
         * 纯判定：一个「最小边宽度（dp）」落在阈值哪一侧。
         *
         * ★ 为什么从 [probe] 里抽出来（2026-09-29）：这是**全工程唯一一处形态判据的真身**，
         *   而它出过一次全军覆没的事故 —— 阈值曾写作 360dp（按错误密度推的），
         *   导致两块屏**都**落在阈值之上 ⇒ 判定恒为 [INNER] ⇒ "内外屏模式解耦"
         *   其实从来没生效过（外屏那份配置永远是死的）。
         *   抽成不依赖 Context 的纯函数之后，"内屏 608 / 外屏 425 分别落在阈值两侧"
         *   这条事实就能被单测直接钉住，改阈值改错会立刻变红。
         *
         * 边界语义：**等于阈值算 [INNER]**（`>=`）。选哪边都行，但必须定死并测住。
         */
        internal fun ofSmallestWidth(dp: Int): ScreenForm =
            if (dp >= INNER_MIN_WIDTH_DP) INNER else OUTER

        /**
         * 用**物理最小边 + 密度**判一块屏的形态 —— 给"手上是一个 `Display` 对象、
         * 要认出它属于**哪一块屏**"的调用点用（`AdaptiveEngine.displayFormOf`）。
         *
         * ★ 为什么值得单独抽出来（2026-09-29）：引擎要按形态挑"当前活动屏"再读它的方向，
         *   而以前一律读 `Display.DEFAULT_DISPLAY` —— **那个 id 绑哪块屏本机至今说不准**
         *   （两种互相矛盾的记录见 [ActiveDisplay] 的类注释）。
         *   用户报的「展开有概率翻转 180°」/「变成顺时针 90°」都出在换屏那一刻，
         *   而换屏链路**主要在折叠态**跑、恰恰是"读哪块屏"最容易错的场景。
         *   这条 px→dp 的换算就是"按尺寸认屏"的判据，**必须有测试钉住** ——
         *   否则下次换机型、或用户改「显示大小 / DPI」，都会静默退回"认错屏"。
         *
         * @param minSidePx 该屏的**物理**最小边像素（`Display.getMode()` 的
         *   `physicalWidth/Height` 取 min）。物理尺寸**不随旋转交换** ⇒ 这个输入天然旋转不变。
         * @param densityDpi 该屏的密度（`Display.getRealMetrics().densityDpi`）
         * @return null = 输入非法（0 / 负数）⇒ 调用方必须自己兜底，**不要**在这里猜一个出来
         */
        internal fun ofPhysicalMinSide(minSidePx: Int, densityDpi: Int): ScreenForm? {
            if (minSidePx <= 0 || densityDpi <= 0) return null
            return ofSmallestWidth((minSidePx * 160f / densityDpi + 0.5f).toInt())
        }
    }

    /**
     * 一次形态测量的完整结果。
     *
     * @param smallestWidthDp 当前显示的**最小边**宽度（dp）—— 判定就靠它
     * @param source 这个像素尺寸是从哪个 API 读来的（见 [probe] 的来源优先级）
     */
    data class Probe(
        val form: ScreenForm,
        val smallestWidthDp: Int,
        val source: String,
    ) {
        /** 一行诊断文本（诊断页 / 日志共用） */
        fun describe(): String =
            "${form.label}（最小宽度 ${smallestWidthDp}dp，取自 $source，" +
                "阈值 ${INNER_MIN_WIDTH_DP}dp）"
    }
}

/**
 * 「当前显示有多大」——**全工程唯一一处量法**。
 *
 * ★ 为什么要抽出来（而不是各写各的）：这个数字现在有**两个**消费者，而且它们
 *   必须给出一致的答案 ——
 *     1. [ScreenForm] 的形态判据（内屏 / 外屏 ⇒ 用哪一份模式）；
 *     2. `RotateHintOverlay` 的按钮尺寸自适应（按屏宽比例缩放）。
 *   两处若各读一套 API，就会出现"判成内屏、却按外屏的比例画按钮"这种自相矛盾的状态，
 *   而且因为两处都"看起来没错"，排查时极难定位。
 *
 * ★ 取 `min(宽, 高)` 而不是宽或高：**旋转不变量**。内屏横过来是 2364×1672，
 *   直接取宽会得到 860dp、外屏横过来得到 623dp —— 数值随方向乱跳，而且**两个都跳到
 *   [ScreenForm.INNER_MIN_WIDTH_DP] 之上**（横屏时外屏会被误判成内屏）。
 *   取最小边则无论怎么转：内屏恒 **608dp**、外屏恒 **425dp**（密度 440dpi = 2.75x）。
 */
internal object DisplaySize {

    private const val TAG = "HyperPlusDisplaySize"

    /**
     * @param smallestSidePx 最小边像素数
     * @param density 屏幕密度（px ↔ dp 换算）
     * @param source 这个读数取自哪个 API（诊断用；判据出错时第一件要看的事）
     */
    data class Measured(val smallestSidePx: Int, val density: Float, val source: String) {
        val smallestWidthDp: Int get() = (smallestSidePx / density + 0.5f).toInt()
    }

    fun of(context: Context): Measured {
        val density = runCatching { context.resources.displayMetrics.density }
            .getOrDefault(1f)
            .takeIf { it > 0f } ?: 1f

        val wm = runCatching {
            context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }.getOrNull()

        // ① 显示区完整尺寸（首选，理由见 ScreenForm 的类注释）
        runCatching { wm?.maximumWindowMetrics?.bounds }.getOrNull()
            ?.takeIf { it.width() > 0 && it.height() > 0 }
            ?.let { return Measured(min(it.width(), it.height()), density, "WMS maximumWindowMetrics") }

        // ② 应用窗口（Activity 里已扣掉系统栏 —— 只当退路）
        runCatching { wm?.currentWindowMetrics?.bounds }.getOrNull()
            ?.takeIf { it.width() > 0 && it.height() > 0 }
            ?.let { return Measured(min(it.width(), it.height()), density, "WMS currentWindowMetrics") }

        // ③ 资源里的 metrics（本工程实测有过错值，最后兜底）
        val dm = context.resources.displayMetrics
        Log.w(TAG, "拿不到窗口尺寸，退回 resources.displayMetrics（${dm.widthPixels}×${dm.heightPixels}）")
        return Measured(min(dm.widthPixels, dm.heightPixels), density, "Resources.displayMetrics")
    }
}

/**
 * 「**用户正在看的那块屏**是哪个 `Display`」——**全工程唯一一处按形态认屏的地方**。
 *
 * ======================= 它存在的理由：`display 0` 会**跟着当前屏跑** =======================
 * 起因是用户报的两次方向 bug：先「展开到内屏**有概率翻转 180°**」、修完又报
 * 「现在变成**顺时针 90°**」。两次都发生在**换屏那一刻**，而换屏链路里读方向的地方
 * 用的一直是 `Display.DEFAULT_DISPLAY`（常量 0）。
 *
 * ★★ **"display 0 是哪块屏"已于 2026-09-29 13:09 定论：它跟着"当前在用的那块屏"走。**
 *
 * | 时刻 | `display 0` 是谁 | 来源 |
 * |---|---|---|
 * | 展开态 | **内屏** 1672×2364 | 多次 `dumpsys window displays` |
 * | **折叠态** | **外屏** 1168×1712 | 用户 2026-09-29 明确原话「display 是相对值，display#0 永远绑在当前使用的屏幕上」；本项目更早期的折叠态实测；本轮 `watchScreenForm` 日志同框佐证 |
 *
 * ✅ **两条实测本来就都成立、不矛盾** —— 我一度拿**展开态**的观测外推折叠态、
 *   写成"`display 0` 恒为内屏"，那是**过度推断**（用户当场纠正）。⛔ 别再犯。
 *   系统侧也能直读：`dumpsys display` 的 `Logical Displays` 里带 `mHasContent=true` 的那块就是在用的。
 *
 * ⇒ 由此推出**"按尺寸认屏"的真正理由**（不是"id 说不准"，而是**时序**）：
 *   **切换一发生，`display 0` 立刻就是新屏了** —— 而我们需要的是**旧屏最后那个值**（搬运锚点）、
 *   以及**悬浮按钮该落在哪块屏上**。同一时刻盘上只有"当前屏"这个别名可用，
 *   所以必须**按物理尺寸**把那一块认出来。**无论 id 怎么分配，按尺寸认都挑得对。**
 *
 * ⛔ 所以：**任何"按屏读方向"的地方都走这里**，别再直接 `getDisplay(DEFAULT_DISPLAY)`，
 *   也别在别处再写一份"按尺寸判形态"的副本（两条判据各写一份，就会出现
 *   "按内屏挑、按外屏算"这种自相矛盾）。
 */
internal object ActiveDisplay {

    /**
     * 这块 `Display` 属于内屏还是外屏 —— 按**物理最小边 dp** 判，与 [ScreenForm] 同一个阈值。
     *
     * ★ 为什么用 `Display.getMode()` 的 `physicalWidth/Height`、而不是 `getRealMetrics()` 的宽高：
     *   mode 是**物理分辨率**（1672×2364 / 1168×1712），**不随旋转交换**；
     *   realMetrics 的宽高横屏时会对调 —— 虽然取 `min` 后两者等价，但 mode 少一层歧义。
     *   ⚠️ 所以"取 min"这一步是**调用方**的责任，这里只负责 px→dp→档位。
     * ⚠️ 密度只能从 `getRealMetrics().densityDpi` 拿（本机两块屏都是 440）。
     *
     * @return null = 认不出（拿不到 mode / dpi 非法）⇒ 调用方必须自己兜底，**不要**在这里猜
     */
    fun formOf(display: Display): ScreenForm? = runCatching {
        val mode = display.mode ?: return@runCatching null
        val dpi = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            display.getRealMetrics(it)
        }.densityDpi
        ScreenForm.ofPhysicalMinSide(minOf(mode.physicalWidth, mode.physicalHeight), dpi)
    }.getOrNull()

    /** 指定形态对应的那块 `Display`。认不出返回 null */
    fun forForm(context: Context, form: ScreenForm): Display? = runCatching {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        dm.displays.firstOrNull { formOf(it) == form }
    }.getOrNull()

    /**
     * 指定形态那块屏的**显示旋转**（面板空间的值，就是 `USER_ROTATION` 那个坐标系）。
     *
     * @return null = 认不出那块屏 ⇒ 调用方必须自己兜底（引擎那边退 `USER_ROTATION`、
     *   再退 `DEFAULT_DISPLAY`）—— **不要**把 null 当成 0
     */
    fun rotationOf(context: Context, form: ScreenForm): Int? = forForm(context, form)?.rotation

    /**
     * 诊断：把**每一块** display 的「形态 + id + rotation」打成一行。
     *
     * ★ 本次的错是"**认错了屏**"，而这种错**只看最终方向是看不出来的** ——
     *   上一轮排查就因此在"到底是哪块屏在转"上绕了很久。
     *   一行把两块屏并排摆出来，就没有歧义了。
     */
    fun describe(context: Context): String = runCatching {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return "无 DisplayManager"
        dm.displays.joinToString(" ") { d ->
            "${formOf(d)?.label ?: "认不出"}#${d.displayId}=${d.rotation}"
        }
    }.getOrDefault("读取失败")
}

/**
 * 形态判定的调试记录。目前只在 `AppPrefs.syncScreenForm` 里用，
 * 所以放在顶层而不是塞进 `ScreenForm`。
 */
internal object ScreenFormLog {
    private const val TAG = "HyperPlusScreenForm"

    /**
     * @param changed 这次测量有没有**改变**结论。没变也记一行 —— 频率很低
     *   （初始化 / 折叠 / 转屏），而"它到底量到了多少、凭哪条 API 量的"正是
     *   判定出问题时唯一要查的东西。
     */
    fun note(probe: ScreenForm.Probe, mode: RotateMode, changed: Boolean) {
        val mark = if (changed) "切换" else "确认"
        Log.i(TAG, "当前屏$mark → ${probe.describe()}；生效模式「${mode.label}」")
    }

    fun failed(t: Throwable) {
        Log.w(TAG, "判定当前屏失败（沿用上一次的形态）", t)
    }
}
